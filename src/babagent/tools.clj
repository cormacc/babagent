(ns babagent.tools
  (:require
   [babashka.fs :as fs]
   [babashka.process :as process]
   [bencode.core :as bencode]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defonce terminal-history (atom []))
(def max-terminal-history-lines 5000)
(def default-bash-timeout-seconds 120)
(def default-external-tool-timeout-seconds 30)
(def max-tool-rounds 8)
(def default-nrepl-host "localhost")
(def default-nrepl-timeout-ms 30000)
(def nrepl-port-validation-timeout-ms 5000)
(def nrepl-port-files
  [".nrepl-port"
   "nrepl-port"
   ".shadow-cljs/nrepl.port"
   ".cider-nrepl.port"])
(def default-nrepl-ports [7888 1666 50505 58885 63333 7889])


(defonce extension-tool-registry (atom {}))

(defn- valid-external-tool? [external]
  (and (map? external)
       (string? (:command external))
       (not (str/blank? (:command external)))))

(defn register-tool! [{:keys [name description input_schema handler external] :as tool-definition}]
  (when-not (and (string? name)
                 (string? description)
                 (map? input_schema)
                 (or (fn? handler)
                     (valid-external-tool? external)))
    (throw (ex-info "Invalid tool definition"
                    {:name name
                     :description description
                     :input_schema input_schema
                     :handler handler
                     :external external})))
  (swap! extension-tool-registry assoc name tool-definition)
  nil)


(defn- maybe-get [m k]
  (or (get m k)
      (get m (name k))))

(defn- first-present [m ks]
  (reduce (fn [_ k]
            (let [v (maybe-get m k)]
              (if (some? v)
                (reduced v)
                nil)))
          nil
          ks))

(defn- parse-long-safe [value default-value]
  (cond
    (nil? value) default-value
    (integer? value) (long value)
    (string? value) (try
                      (Long/parseLong (str/trim value))
                      (catch Exception _
                        default-value))
    :else default-value))

(defn- split-lines* [s]
  (if (str/blank? s)
    []
    (str/split-lines s)))

(defn- append-terminal! [entry]
  (let [lines (split-lines* entry)]
    (when (seq lines)
      (swap! terminal-history
             (fn [history]
               (let [updated (into history lines)
                     overflow (- (count updated) max-terminal-history-lines)]
                 (if (pos? overflow)
                   (vec (drop overflow updated))
                   (vec updated))))))))

(defn- normalize-path [path]
  (-> path fs/path fs/absolutize str))

(defn- line-range [content start-line end-line]
  (let [lines (vec (str/split-lines content))
        line-count (count lines)
        start (parse-long-safe start-line 1)
        end* (parse-long-safe end-line line-count)
        bounded-start (-> (dec start) (max 0) (min line-count))
        bounded-end (-> end* (max start) (min line-count))]
    (if (>= bounded-start bounded-end)
      ""
      (str/join "\n" (subvec lines bounded-start bounded-end)))))

(defn- maybe-truncate [s max-chars]
  (let [limit (parse-long-safe max-chars nil)]
    (if (and (integer? limit) (pos? limit) (> (count s) limit))
      (subs s 0 limit)
      s)))

(defn- count-occurrences [text needle]
  (loop [idx 0
         total 0]
    (let [hit (.indexOf ^String text ^String needle idx)]
      (if (neg? hit)
        total
        (recur (+ hit (max 1 (count needle)))
               (inc total))))))

(def ^:private byte-array-class (class (byte-array 0)))
(defonce ^:private nrepl-id-counter (atom 0))

(defn- next-nrepl-id []
  (str (swap! nrepl-id-counter inc)))

(defn- bytes->str [value]
  (cond
    (nil? value) nil
    (number? value) (str value)
    (= byte-array-class (class value)) (String. ^bytes value "UTF-8")
    (map? value) (into {}
                        (map (fn [[k v]] [(bytes->str k) (bytes->str v)]))
                        value)
    (sequential? value) (mapv bytes->str value)
    :else value))

(defn- write-bencode-msg! [out msg]
  (bencode/write-bencode out msg)
  (.flush out))

(defn- read-bencode-msg [in]
  (some-> (bencode/read-bencode in)
          bytes->str))

(defn- merge-nrepl-response [responses]
  (reduce
   (fn [m [k v]]
     (case k
       ("id" "ns") (assoc m k v)
       "value" (update m k (fnil conj []) v)
       "status" (update m k (fnil into []) v)
       "session" (update m k (fnil conj #{}) v)
       (if (string? v)
         (update m k #(str (or % "") v))
         (assoc m k v))))
   {}
   (mapcat seq responses)))

(defn- messages-for-id [in target-id target-session]
  (loop [messages []]
    (let [msg (read-bencode-msg in)]
      (if (nil? msg)
        (throw (ex-info "Connection closed unexpectedly" {}))
        (let [message-id (get msg "id")
              message-session (get msg "session")
              status (set (or (get msg "status") []))]
          (if (and (= message-id target-id)
                   (or (nil? target-session)
                       (nil? message-session)
                       (= message-session target-session)))
            (let [updated (conj messages msg)]
              (if (contains? status "done")
                updated
                (recur updated)))
            (recur messages)))))))

(defn- send-op! [in out op-map & {:keys [session-id]}]
  (let [id (or (get op-map "id") (next-nrepl-id))
        message (cond-> (assoc op-map "id" id)
                  session-id (assoc "session" session-id))]
    (write-bencode-msg! out message)
    (merge-nrepl-response (messages-for-id in id session-id))))

(defn- with-nrepl-socket [host port timeout-ms f]
  (with-open [socket (doto (java.net.Socket.)
                       (.connect (java.net.InetSocketAddress. host (long port)) timeout-ms)
                       (.setSoTimeout timeout-ms))
              out (java.io.BufferedOutputStream. (.getOutputStream socket))
              in (java.io.PushbackInputStream. (.getInputStream socket))]
    (f in out)))

(defn- eval-via-nrepl [{:keys [host port code ns timeout]}]
  (let [host (or host default-nrepl-host)
        port (parse-long-safe port nil)
        timeout-ms (-> timeout
                       (parse-long-safe default-nrepl-timeout-ms)
                       (max 1))]
    (when-not (and (integer? port) (pos? port))
      (throw (ex-info "Invalid nREPL port" {:port port})))
    (try
      (with-nrepl-socket host port timeout-ms
        (fn [in out]
          (let [clone-response (send-op! in out {"op" "clone"})
                session-id (get clone-response "new-session")]
            (when (str/blank? session-id)
              (throw (ex-info "Failed to create nREPL session" {:response clone-response})))
            (let [eval-message (cond-> {"op" "eval"
                                        "code" code}
                                 (some? ns) (assoc "ns" ns))
                  eval-response (send-op! in out eval-message :session-id session-id)]
              {:vals (vec (or (get eval-response "value") []))
               :out (or (get eval-response "out") "")
               :err (or (get eval-response "err") "")}))))
      (catch java.net.SocketTimeoutException _
        (throw (ex-info (str "nREPL eval timed out after " timeout-ms "ms")
                        {:timeout timeout-ms}))))))

(defn- read-port-file [file-path]
  (try
    (when (fs/exists? file-path)
      (some-> (slurp (str file-path))
              str/trim
              parse-long))
    (catch Exception _
      nil)))

(defn- find-port-in-directory [dir]
  (some (fn [port-file]
          (read-port-file (fs/path dir port-file)))
        nrepl-port-files))

(defn- validate-nrepl-port [host port]
  (try
    (= "2" (first (:vals (eval-via-nrepl {:host host
                                            :port port
                                            :code "(+ 1 1)"
                                            :timeout nrepl-port-validation-timeout-ms}))))
    (catch Exception _
      false)))

(defn- format-clojure-eval-result [{:keys [vals out err]}]
  (let [lines (cond-> []
                (seq vals) (conj (str "=> " (str/join "\n=> " vals)))
                (seq out) (conj (str "stdout: " out))
                (seq err) (conj (str "stderr: " err)))]
    (if (seq lines)
      (str/join "\n" lines)
      "No output (nil or empty)")))

(defn clojure-find-nrepl-port-tool [_input]
  (let [cwd (str (fs/cwd))
        host default-nrepl-host]
    (if-let [file-port (find-port-in-directory cwd)]
      (if (validate-nrepl-port host file-port)
        {:ok true
         :content (str "Found nREPL port " file-port
                       " (from port file) at " host ":" file-port)}
        {:ok false
         :error "No nREPL port found. Start nREPL and try again."})
      (if-let [default-port (some (fn [port]
                                    (when (validate-nrepl-port host port)
                                      port))
                                  default-nrepl-ports)]
        {:ok true
         :content (str "Found nREPL port " default-port " at " host ":" default-port)}
        {:ok false
         :error "No nREPL port found. Start nREPL and try again."}))))

(defn clojure-eval-tool [input]
  (let [code (first-present input [:code])
        port (parse-long-safe (first-present input [:port]) nil)
        host (or (first-present input [:host]) default-nrepl-host)
        ns-name (first-present input [:ns])
        timeout-ms (-> (first-present input [:timeout])
                       (parse-long-safe default-nrepl-timeout-ms)
                       (max 1))]
    (cond
      (str/blank? code)
      {:ok false
       :error "Missing required field: code"}

      (nil? port)
      {:ok false
       :error "Missing required field: port"}

      (not (pos? port))
      {:ok false
       :error "Invalid port: must be a positive integer"}

      :else
      (try
        (let [result (eval-via-nrepl {:host host
                                      :port port
                                      :code code
                                      :ns ns-name
                                      :timeout timeout-ms})]
          {:ok true
           :content (format-clojure-eval-result result)})
        (catch Exception e
          {:ok false
           :error (.getMessage e)})))))

(defn read-tool [input]
  (let [path (first-present input [:path])]
    (cond
      (str/blank? path)
      {:ok false
       :error "Missing required field: path"}

      (not (fs/exists? path))
      {:ok false
       :error (str "Path does not exist: " path)}

      (fs/directory? path)
      {:ok false
       :error (str "Path is a directory, expected file: " path)}

      :else
      (let [content (slurp (str path))
            ranged (line-range content
                               (first-present input [:start_line :start-line])
                               (first-present input [:end_line :end-line]))
            output (maybe-truncate ranged
                                   (first-present input [:max_chars :max-chars]))]
        {:ok true
         :content (str "Path: " (normalize-path path) "\n" output)}))))

(defn write-tool [input]
  (let [path (first-present input [:path])
        content (first-present input [:content])
        append? (boolean (first-present input [:append]))]
    (cond
      (str/blank? path)
      {:ok false
       :error "Missing required field: path"}

      (nil? content)
      {:ok false
       :error "Missing required field: content"}

      :else
      (let [file (fs/path path)]
        (fs/create-dirs (fs/parent file))
        (spit (str file) (str content) :append append?)
        {:ok true
         :content (str (if append? "Appended" "Wrote")
                       " " (count (str content))
                       " chars to " (normalize-path path))}))))

(defn edit-tool [input]
  (let [path (first-present input [:path])
        old-text (first-present input [:old_text :old-text])
        new-text (first-present input [:new_text :new-text])]
    (cond
      (str/blank? path)
      {:ok false
       :error "Missing required field: path"}

      (nil? old-text)
      {:ok false
       :error "Missing required field: old_text"}

      (nil? new-text)
      {:ok false
       :error "Missing required field: new_text"}

      (str/blank? old-text)
      {:ok false
       :error "old_text must be non-empty"}

      (not (fs/exists? path))
      {:ok false
       :error (str "Path does not exist: " path)}

      (fs/directory? path)
      {:ok false
       :error (str "Path is a directory, expected file: " path)}

      :else
      (let [content (slurp (str path))
            matches (count-occurrences content old-text)]
        (cond
          (zero? matches)
          {:ok false
           :error "old_text not found in file"}

          (> matches 1)
          {:ok false
           :error "old_text matched multiple locations; provide more specific text"}

          :else
          (let [idx (.indexOf ^String content ^String old-text)
                updated (str (subs content 0 idx)
                             new-text
                             (subs content (+ idx (count old-text))))]
            (spit (str path) updated)
            {:ok true
             :content (str "Edited " (normalize-path path)
                           " (replaced one occurrence).")}))))))

(defn bash-tool [input]
  (let [command (first-present input [:command])
        timeout-seconds (-> (first-present input [:timeout_seconds :timeout-seconds])
                            (parse-long-safe default-bash-timeout-seconds)
                            (max 1))]
    (if (str/blank? command)
      {:ok false
       :error "Missing required field: command"}
      (let [result-future (future
                            (process/shell {:out :string
                                            :err :string
                                            :continue true}
                                           command))
            result (deref result-future (* timeout-seconds 1000) ::timeout)]
        (if (= ::timeout result)
          (do
            (future-cancel result-future)
            (let [entry (str "$ " command "\n"
                             "[timeout after " timeout-seconds "s]")]
              (append-terminal! entry)
              {:ok false
               :error (str "Command timed out after " timeout-seconds "s")
               :content entry}))
          (let [out (or (:out result) "")
                err (or (:err result) "")
                exit (or (:exit result) 1)
                output-block (str/join "\n"
                                       (remove str/blank?
                                               [out err]))
                entry (str "$ " command
                           (when (seq output-block)
                             (str "\n" output-block))
                           "\n[exit " exit "]")]
            (append-terminal! entry)
            {:ok (zero? exit)
             :content entry
             :exit exit}))))))

(defn read-terminal-tool [input]
  (let [line-count (-> (first-present input [:lines])
                       (parse-long-safe 200)
                       (max 1))
        history @terminal-history
        selection (take-last line-count history)]
    {:ok true
     :content (if (seq selection)
                (str/join "\n" selection)
                "(terminal history is empty)")}))

(defn- parse-external-tool-response [output]
  (try
    (when (seq (str/trim output))
      (json/parse-string output true))
    (catch Exception _
      nil)))

(defn- run-external-tool [tool-name input external]
  (let [command (:command external)
        timeout-seconds (-> (:timeout-seconds external)
                            (parse-long-safe default-external-tool-timeout-seconds)
                            (max 1))
        payload (json/generate-string {:tool tool-name
                                       :input input})
        result-future (future
                        (process/shell {:out :string
                                        :err :string
                                        :in payload
                                        :continue true}
                                       command))
        result (deref result-future (* timeout-seconds 1000) ::timeout)]
    (if (= ::timeout result)
      (do
        (future-cancel result-future)
        {:ok false
         :error (str "External tool timed out after " timeout-seconds "s")})
      (let [exit (or (:exit result) 1)
            out (or (:out result) "")
            err (or (:err result) "")
            parsed (parse-external-tool-response out)
            parsed-ok (boolean (maybe-get parsed :ok))
            parsed-content (maybe-get parsed :content)
            parsed-error (maybe-get parsed :error)]
        (cond
          (not (zero? exit))
          {:ok false
           :error (str "External tool process failed (exit " exit "): "
                       (or (not-empty err)
                           (not-empty out)
                           "no output"))}

          (map? parsed)
          {:ok parsed-ok
           :content (or parsed-content "")
           :error (when-not parsed-ok
                    (or parsed-error
                        "External tool reported failure"))}

          :else
          {:ok false
           :error "External tool returned invalid response (expected JSON map)"})))))

(defn- execute-extension-tool [tool-name input tool-definition]
  (let [handler (:handler tool-definition)
        external (:external tool-definition)]
    (cond
      (fn? handler)
      (try
        (let [result (handler input)]
          (if (and (map? result) (contains? result :ok))
            result
            {:ok false
             :error (str "Tool handler for " tool-name
                         " returned invalid result; expected map with :ok")}))
        (catch Exception e
          {:ok false
           :error (str "Tool " tool-name " failed: " (.getMessage e))}))

      (valid-external-tool? external)
      (run-external-tool tool-name input external)

      :else
      {:ok false
       :error (str "Tool " tool-name " has no valid handler or external command")})))

;; ---------------------------------------------------------------------------
;; Tool rendering
;; ---------------------------------------------------------------------------
;;
;; Each tool can supply optional :render-call and :render-result functions:
;;   :render-call   (fn [input]         -> string)  header shown before/during
;;   :render-result (fn [input result]  -> string)  body shown after execution
;;
;; Registration (register-tool!) stores these in extension-tool-registry.
;; Built-in tools have their renderers in built-in-renderers below.
;; Both fall back to generic defaults when absent.

(def ^:private render-max-chars 600)

(defn- truncate-content [s]
  (let [s (or s "")]
    (if (> (count s) render-max-chars)
      (str (subs s 0 render-max-chars) "\n…(truncated)")
      s)))

(defn- render-call-default [tool-name input]
  (str/join " "
            (cons tool-name
                  (map (fn [[k v]] (str (name k) "=" (pr-str v)))
                       (take 2 input)))))

(defn- render-result-default [_input result]
  (if (:ok result)
    (truncate-content (:content result))
    (str "Error: " (:error result))))

(def ^:private built-in-renderers
  {"bash"
   {:render-call   (fn [input]
                     (str "`" (first-present input [:command]) "`"))
    :render-result (fn [_input result]
                     (str "```\n"
                          (truncate-content (if (:ok result)
                                              (:content result)
                                              (str "Error: " (:error result))))
                          "\n```"))}

   "read"
   {:render-call   (fn [input] (str (first-present input [:path])))
    :render-result (fn [_input result]
                     (if (:ok result)
                       (truncate-content (:content result))
                       (str "Error: " (:error result))))}

   "write"
   {:render-call   (fn [input]
                     (str (first-present input [:path])
                          (when (first-present input [:append]) " (append)")))
    :render-result (fn [_input result]
                     (if (:ok result) (:content result) (str "Error: " (:error result))))}

   "edit"
   {:render-call   (fn [input] (str (first-present input [:path])))
    :render-result (fn [_input result]
                     (if (:ok result) (:content result) (str "Error: " (:error result))))}

   "clojure_eval"
   {:render-call   (fn [input]
                     (str "port=" (first-present input [:port]) "\n"
                          "```clojure\n"
                          (str/trim (or (first-present input [:code]) ""))
                          "\n```"))
    :render-result (fn [_input result]
                     (if (:ok result)
                       (truncate-content (:content result))
                       (str "Error: " (:error result))))}

   "clojure_find_nrepl_port"
   {:render-call   (fn [_input] "")
    :render-result (fn [_input result]
                     (if (:ok result) (:content result) (str "Error: " (:error result))))}

   "read_terminal"
   {:render-call   (fn [input]
                     (str "lines=" (or (first-present input [:lines]) "200")))
    :render-result (fn [_input result]
                     (truncate-content (:content result)))}})

(defn render-tool-call
  "Return a string summarising the tool invocation (the 'call' slot).
   Looks up :render-call on extension tools, then built-in-renderers, then falls
   back to a generic default."
  [tool-name input]
  (let [renderer (or (get-in @extension-tool-registry [tool-name :render-call])
                     (get-in built-in-renderers [tool-name :render-call]))]
    (if renderer
      (renderer input)
      (render-call-default tool-name input))))

(defn render-tool-result
  "Return a string for the tool result (the 'result' slot).
   Looks up :render-result on extension tools, then built-in-renderers, then
   falls back to a generic default."
  [tool-name input result]
  (let [renderer (or (get-in @extension-tool-registry [tool-name :render-result])
                     (get-in built-in-renderers [tool-name :render-result]))]
    (if renderer
      (renderer input result)
      (render-result-default input result))))

(defn execute-tool! [tool-name input]
  (case tool-name
    "read" (read-tool input)
    "write" (write-tool input)
    "edit" (edit-tool input)
    "bash" (bash-tool input)
    "read_terminal" (read-terminal-tool input)
    "clojure_find_nrepl_port" (clojure-find-nrepl-port-tool input)
    "clojure_eval" (clojure-eval-tool input)
    (if-let [tool-definition (get @extension-tool-registry tool-name)]
      (execute-extension-tool tool-name input tool-definition)
      {:ok false
       :error (str "Unknown tool: " tool-name)})))

(defn execute-tool-use [tool-use]
  (let [result (execute-tool! (:name tool-use) (:input tool-use))]
    {:type "tool_result"
     :tool_use_id (:id tool-use)
     :is_error (not (:ok result))
     :content (if (:ok result)
                (:content result)
                (str "Error: " (:error result)))}))
