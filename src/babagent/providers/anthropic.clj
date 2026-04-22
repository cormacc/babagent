(ns babagent.providers.anthropic
  (:require
   [babagent.tools :as tools]
   [babashka.http-client :as http]
   [cheshire.core :as json]
   [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; HTTP client
;;; ---------------------------------------------------------------------------

(def api-url "https://api.anthropic.com/v1")
(def anthropic-version "2023-06-01")
(def default-max-tokens 1024)

(defn- headers [api-key]
  {"x-api-key" api-key
   "anthropic-version" anthropic-version
   "content-type" "application/json"})

(defn- parse-body [body]
  (cond
    (string? body) (json/parse-string body true)
    (map? body) body
    :else {}))

(defn request [{:keys [api-key method path body]}]
  (let [response (http/request {:uri (str api-url path)
                                :method method
                                :headers (headers api-key)
                                :body (when body (json/generate-string body))
                                :throw false})]
    {:status (:status response)
     :body (parse-body (:body response))}))

(defn successful? [response]
  (<= 200 (:status response) 299))

(defn error-message [response]
  (or (get-in response [:body :error :message])
      (get-in response [:body :message])
      (str "Anthropic request failed with status " (:status response))))

(defn list-models [api-key]
  (request {:api-key api-key
            :method :get
            :path "/models"}))

(defn create-message [api-key {:keys [model prompt messages tools max-tokens system]}]
  (request {:api-key api-key
            :method :post
            :path "/messages"
            :body (cond-> {:model model
                           :max_tokens (or max-tokens default-max-tokens)
                           :messages (or messages
                                         [{:role "user" :content prompt}])}
                    system (assoc :system system)
                    (seq tools) (assoc :tools tools))}))

(defn content-blocks [response]
  (or (get-in response [:body :content])
      []))

(defn response-text [response]
  (->> (content-blocks response)
       (filter #(= "text" (:type %)))
       (map :text)
       (str/join "")))

(defn tool-uses [response]
  (->> (content-blocks response)
       (filter #(= "tool_use" (:type %)))))

;;; ---------------------------------------------------------------------------
;;; Tool definitions
;;; ---------------------------------------------------------------------------

(def ^:private builtin-tools
  [{:name "read"
    :description "Read text from a file path"
    :input_schema {:type "object"
                   :properties {:path {:type "string"
                                       :description "File path to read"}
                                :start_line {:type "integer"
                                             :description "1-based line number to start reading from"}
                                :end_line {:type "integer"
                                           :description "1-based inclusive line number to end reading at"}
                                :max_chars {:type "integer"
                                            :description "Maximum characters to return"}}
                   :required ["path"]}}
   {:name "write"
    :description "Write text to a file path (replace by default, append when append=true)"
    :input_schema {:type "object"
                   :properties {:path {:type "string"
                                       :description "File path to write"}
                                :content {:type "string"
                                          :description "Text content to write"}
                                :append {:type "boolean"
                                         :description "Append to file instead of replacing"}}
                   :required ["path" "content"]}}
   {:name "edit"
    :description "Replace one exact text occurrence in a file"
    :input_schema {:type "object"
                   :properties {:path {:type "string"
                                       :description "File path to edit"}
                                :old_text {:type "string"
                                           :description "Exact text to replace (must occur exactly once)"}
                                :new_text {:type "string"
                                           :description "Replacement text"}}
                   :required ["path" "old_text" "new_text"]}}
   {:name "bash"
    :description "Run a shell command"
    :input_schema {:type "object"
                   :properties {:command {:type "string"
                                          :description "Shell command to execute"}
                                :timeout_seconds {:type "integer"
                                                  :description "Command timeout in seconds (default 120)"}}
                   :required ["command"]}}
   {:name "read_terminal"
    :description "Read recent terminal output captured from previous bash tool calls"
    :input_schema {:type "object"
                   :properties {:lines {:type "integer"
                                        :description "How many recent lines to return (default 200)"}}}}
   {:name "clojure_find_nrepl_port"
    :description "Helper to find nREPL port. Checks for port files in current directory or tries default ports. Validates by evaluating (+ 1 1). Used as a helper to find the port for clojure_eval to connect to."
    :input_schema {:type "object"
                   :properties {}}}
   {:name "clojure_eval"
    :description "Evaluate Clojure code via nREPL. Requires an existing nREPL connection (see clojure_find_nrepl_port to find one)."
    :input_schema {:type "object"
                   :properties {:code {:type "string"
                                       :description "Clojure code to evaluate"}
                                :port {:type "number"
                                       :description "nREPL port"}
                                :host {:type "string"
                                       :description "nREPL host"}
                                :ns {:type "string"
                                     :description "Target namespace"}
                                :timeout {:type "number"
                                          :description "Timeout in milliseconds (default: 30000)"}}
                   :required ["code" "port"]}}])

(defn tool-definitions []
  (->> (concat builtin-tools
               (vals @tools/extension-tool-registry))
       (mapv #(dissoc % :handler :external :render-call :render-result))))

;;; ---------------------------------------------------------------------------
;;; System prompts
;;; ---------------------------------------------------------------------------

(def ^:private assistant-system-prompt
  (str
   "You are babagent, a coding assistant with access to local tools. "
   "Answer directly when the user does not need tool-based verification or file changes. "
   "Use tools only when they materially improve correctness or are needed to inspect or modify the workspace. "
   "Do not loop on repeated tool calls. After gathering enough information, stop using tools and answer. "
   "Format all user-visible replies as Markdown. Clearly separate user requests, assistant responses, and status updates with headings or lists. "
   "When showing file contents, diffs, commands, or edited snippets, use fenced code blocks and include file paths where relevant. "
   "When a file path is known, choose a fence language from its extension (for example .clj -> clojure, .ts -> typescript, .sh -> bash)."))

(def ^:private final-answer-system-prompt
  (str
   assistant-system-prompt
   " Tool use is now disabled. Based on the conversation and tool results already available, "
   "provide the best final answer you can. If information is still missing, say exactly what is missing."))

(def ^:private final-answer-user-prompt
  "Using the context and tool results gathered so far, answer the user's request now. Do not call tools. Format the final answer as Markdown, include fenced code blocks for file or command content, and label fences from known file extensions when possible.")

;;; ---------------------------------------------------------------------------
;;; Agent loop
;;; ---------------------------------------------------------------------------

(defn- summarize-tool-failures [tool-failures]
  (when (seq tool-failures)
    (str
     " Tool failures: "
     (->> tool-failures
          (map (fn [{:keys [name error]}]
                 (str name ": " error)))
          (str/join "; ")))))

(defn- final-no-tools-response [api-key model messages tool-failures]
  (let [final-messages (conj (vec messages)
                             {:role "user"
                              :content final-answer-user-prompt})
        response (create-message api-key {:model model
                                          :messages final-messages
                                          :system final-answer-system-prompt})]
    (if (successful? response)
      {:type :provider/assistant-response
       :ok true
       :text (or (not-empty (response-text response))
                 "(No text content in response)")}
      {:type :provider/assistant-response
       :ok false
       :error (str "Failed to produce final answer after bounded tool use. "
                   (error-message response)
                   (or (summarize-tool-failures tool-failures) ""))})))

(defn execute-assistant-request [{:keys [api-key model prompt]} notify!]
  (try
    (loop [messages [{:role "user"
                      :content prompt}]
           rounds 0
           tool-failures []]
      (if (>= rounds tools/max-tool-rounds)
        (final-no-tools-response api-key model messages tool-failures)
        (let [response (create-message api-key {:model model
                                                :messages messages
                                                :tools (tool-definitions)
                                                :system assistant-system-prompt})]
          (if-not (successful? response)
            {:type :provider/assistant-response
             :ok false
             :error (str "Assistant request failed"
                         (when (seq tool-failures)
                           (str " after tool activity."
                                (summarize-tool-failures tool-failures)))
                         ": "
                         (error-message response))}
            (let [tool-use-blocks (tool-uses response)]
              (if (seq tool-use-blocks)
                (let [raw-tool-results (mapv (fn [tool-use]
                                               (let [result (tools/execute-tool! (:name tool-use)
                                                                                  (:input tool-use))]
                                                 (notify! {:type      :tool/executed
                                                           :tool-name (:name tool-use)
                                                           :input     (:input tool-use)
                                                           :result    result})
                                                 {:tool-use tool-use :result result}))
                                             tool-use-blocks)
                      tool-results (mapv (fn [{:keys [tool-use result]}]
                                           {:type "tool_result"
                                            :tool_use_id (:id tool-use)
                                            :is_error (not (:ok result))
                                            :content (if (:ok result)
                                                       (:content result)
                                                       (str "Error: " (:error result)))})
                                         raw-tool-results)
                      new-tool-failures (into tool-failures
                                              (keep (fn [{:keys [tool-use result]}]
                                                      (when-not (:ok result)
                                                        {:name (:name tool-use)
                                                         :error (:error result)})))
                                              raw-tool-results)
                      next-messages (-> messages
                                        (conj {:role "assistant"
                                               :content (content-blocks response)})
                                        (conj {:role "user"
                                               :content tool-results}))]
                  (recur next-messages (inc rounds) new-tool-failures))
                {:type :provider/assistant-response
                 :ok true
                 :text (or (not-empty (response-text response))
                           "(No text content in response)")}))))))
    (catch Exception e
      {:type :provider/assistant-response
       :ok false
       :error (.getMessage e)})))

(defn execute-list-models [{:keys [api-key response-event]}]
  (try
    (let [response (list-models api-key)]
      (if (successful? response)
        {:type response-event
         :ok true
         :models (->> (get-in response [:body :data])
                      (map :id)
                      sort
                      vec)}
        {:type response-event
         :ok false
         :error (error-message response)}))
    (catch Exception e
      {:type response-event
       :ok false
       :error (.getMessage e)})))
