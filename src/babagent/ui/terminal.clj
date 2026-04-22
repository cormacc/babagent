(ns babagent.ui.terminal
  (:require
   [babagent.config :as config]
   [babagent.server :as server]
   [babagent.tools :as tools]
   [charm.components.spinner :as spinner]
   [charm.components.text-input :as text-input]
   [charm.message :as msg]
   [charm.program :as program]
   [charm.style.border :as border]
   [charm.style.color :as color]
   [charm.style.core :as style]
   [clojure.string :as str]))

;;; ---------------------------------------------------------------------------
;;; Spinner
;;; ---------------------------------------------------------------------------

(defn- make-spinner []
  (spinner/spinner :dots :id :session-wait-spinner))

;;; ---------------------------------------------------------------------------
;;; Theme / colour helpers
;;; ---------------------------------------------------------------------------

(def ^:private extension->language
  {"bb"   "clojure"
   "clj"  "clojure"
   "cljc" "clojure"
   "cljs" "clojure"
   "css"  "css"
   "edn"  "clojure"
   "go"   "go"
   "html" "html"
   "java" "java"
   "js"   "javascript"
   "json" "json"
   "kt"   "kotlin"
   "md"   "markdown"
   "nix"  "nix"
   "py"   "python"
   "rb"   "ruby"
   "rs"   "rust"
   "sh"   "bash"
   "sql"  "sql"
   "ts"   "typescript"
   "tsx"  "tsx"
   "xml"  "xml"
   "yaml" "yaml"
   "yml"  "yaml"})

(def ^:private ansi-color-keywords
  #{:black :red :green :yellow :blue :magenta :cyan :white
    :bright-black :bright-red :bright-green :bright-yellow
    :bright-blue :bright-magenta :bright-cyan :bright-white})

(defn- parse-ansi-color [value]
  (let [k (cond
            (keyword? value) value
            (string? value)  (-> value str/lower-case (str/replace "_" "-") keyword)
            :else            nil)]
    (when (contains? ansi-color-keywords k)
      (color/ansi k))))

(defn- valid-byte? [n]
  (and (integer? n) (<= 0 n 255)))

(defn- resolve-theme-color [spec]
  (cond
    (nil? spec)     nil
    (keyword? spec) (parse-ansi-color spec)
    (string? spec)
    (if (str/starts-with? spec "#")
      (try (color/hex spec) (catch Exception _ nil))
      (parse-ansi-color spec))
    (map? spec)
    (cond
      (:none spec)            (color/no-color)
      (contains? spec :ansi)  (parse-ansi-color (:ansi spec))
      (contains? spec :ansi256)
      (let [code (:ansi256 spec)]
        (when (and (integer? code) (<= 0 code 255))
          (color/ansi256 code)))
      (contains? spec :hex)
      (try (color/hex (:hex spec)) (catch Exception _ nil))
      (contains? spec :rgb)
      (let [[r g b] (:rgb spec)]
        (when (and (valid-byte? r) (valid-byte? g) (valid-byte? b))
          (color/rgb r g b)))
      :else nil)
    :else nil))

(defn- resolve-style-opts [opts]
  (-> (or opts {})
      (update :fg        resolve-theme-color)
      (update :bg        resolve-theme-color)
      (update :border-fg resolve-theme-color)
      (update :border-bg resolve-theme-color)))

(defn- make-style [opts]
  (apply style/style (mapcat identity (resolve-style-opts opts))))

(defn- merge-theme-section [defaults overrides]
  (if (map? overrides) (merge defaults overrides) defaults))

(defn- theme-config [state]
  (let [user-theme (or (:theme state) {})]
    (reduce (fn [acc k]
              (assoc acc k (merge-theme-section (get config/default-theme k)
                                                (get user-theme k))))
            {}
            (keys config/default-theme))))

;;; ---------------------------------------------------------------------------
;;; Markdown / language inference
;;; ---------------------------------------------------------------------------

(defn- infer-language-from-path [path]
  (let [path      (or path "")
        extension (some->> path
                           (re-find #"\.([A-Za-z0-9]+)$")
                           second
                           str/lower-case)]
    (or (get extension->language extension) "text")))

(defn- infer-language-from-text [text]
  (when-let [[_ path] (re-find #"(?i)(?:path:\s*|`)([^`\s]+?\.[a-z0-9]+)" (or text ""))]
    (infer-language-from-path path)))

(defn- auto-label-markdown-fences [text]
  (let [lines (str/split-lines (or text ""))]
    (loop [remaining lines
           acc        []
           context    []]
      (if-let [line (first remaining)]
        (if (= line "```")
          (let [[block-lines tail]  (split-with #(not= % "```") (rest remaining))
                context-snippet     (str/join "\n" (concat (take-last 6 context) (take 6 block-lines)))
                language            (or (infer-language-from-text context-snippet) "text")
                closing             (when (seq tail) ["```"])
                acc'                (into acc (concat [(str "```" language)] block-lines closing))
                remaining'          (if (seq tail) (rest tail) [])
                context'            (into [] (take-last 12 (concat context [line] block-lines closing)))]
            (recur remaining' acc' context'))
          (recur (rest remaining) (conj acc line) (into [] (take-last 12 (conj context line)))))
        (str/join "\n" acc)))))

;;; ---------------------------------------------------------------------------
;;; Message rendering
;;; ---------------------------------------------------------------------------

(defn- render-message [theme _width {:keys [role text] :as msg}]
  (case role
    :user
    (style/render (make-style (:user-message theme)) (or text "_(empty prompt)_"))

    :assistant
    (let [label (style/render (make-style (:assistant-label theme)) "Assistant")]
      (str label "\n\n"
           (if (seq text) (auto-label-markdown-fences text) "_(empty response)_")))

    :system
    (let [label (style/render (make-style (:system-label theme)) "System")]
      (str label "\n\n"
           (if (seq text) (auto-label-markdown-fences text) "_(empty system message)_")))

    :tool-result
    (let [tool-name   (:tool-name msg)
          input       (:input msg)
          result      (:result msg)
          ok?         (:ok result)
          call-text   (tools/render-tool-call tool-name input)
          result-text (tools/render-tool-result tool-name input result)
          header      (style/render (make-style (:tool-header theme))
                                    (str "[" tool-name "]"
                                         (when (seq call-text) (str " " call-text))))
          status      (if ok? "✓" "✗")
          block-style (if ok? (:tool-success theme) (:tool-error theme))]
      (style/render (make-style block-style)
                    (str header "\n\n" status " " result-text)))

    (str "### Agent\n\n"
         (if (seq text) (auto-label-markdown-fences text) "_(empty message)_"))))

(defn- history-view [session theme width]
  (->> (:messages session)
       (take-last 30)
       (map (partial render-message theme width))
       (str/join "\n\n---\n\n")))

;;; ---------------------------------------------------------------------------
;;; Reactive session subscription — bridges add-watch to Charm's cmd loop
;;; ---------------------------------------------------------------------------

(defonce !tui-session-queue (java.util.concurrent.LinkedBlockingQueue.))

(defn- session-watch-cmd []
  (program/cmd
   (fn []
     {:type    :client/session-synced
      :session (.take !tui-session-queue)})))

(defn- watch-tui-session! [session-id]
  (.clear !tui-session-queue)
  (remove-watch server/!server-state ::tui-watcher)
  (add-watch server/!server-state ::tui-watcher
             (fn [_ _ old new]
               (let [new-s (get-in new [:sessions session-id])
                     old-s (get-in old [:sessions session-id])]
                 (when (and new-s (not= new-s old-s))
                   (.offer !tui-session-queue new-s))))))

(defn- unwatch-tui-session! []
  (remove-watch server/!server-state ::tui-watcher)
  (.clear !tui-session-queue))

;;; ---------------------------------------------------------------------------
;;; Input handling
;;; ---------------------------------------------------------------------------

(defn- apply-client-effects [state client-effects]
  (if (some #(= :client/quit (first %)) client-effects)
    [state program/quit-cmd]
    [state nil]))

(defn- handle-submit [state]
  (let [line       (-> state :input text-input/value str/trim)
        next-state (assoc state :input (text-input/reset (:input state)))
        {:keys [client-effects]} (server/submit! (:session-id state)
                                                 {:type :session/submit-input
                                                  :text line})]
    (apply-client-effects next-state client-effects)))

;;; ---------------------------------------------------------------------------
;;; Update
;;; ---------------------------------------------------------------------------

(defn update-fn [state message]
  (let [[spinner-state spinner-cmd] (spinner/spinner-update (:spinner state) message)
        state (assoc state :spinner spinner-state)]
    (cond
      (msg/key-match? message "ctrl+c")
      [state program/quit-cmd]

      (msg/window-size? message)
      (let [new-state (assoc state :window-size {:width  (:width message)
                                                 :height (:height message)})]
        (if (:session-watch-started? state)
          [new-state nil]
          [(assoc new-state :session-watch-started? true) (session-watch-cmd)]))

      (= :client/session-synced (:type message))
      (let [new-session  (:session message)
            was-waiting? (get-in state [:session :waiting?])
            is-waiting?  (:waiting? new-session)
            base-state   (assoc state
                                :session new-session
                                :theme   (:theme (config/load-config)))]
        (cond
          (and (not was-waiting?) is-waiting?)
          (let [[sp-state sp-cmd] (spinner/spinner-init (:spinner base-state))]
            [(-> base-state
                 (assoc :spinner sp-state)
                 (assoc :thinking-started-at (System/currentTimeMillis)))
             (program/batch sp-cmd (session-watch-cmd))])

          (and was-waiting? (not is-waiting?))
          [(-> base-state
               (assoc :spinner (make-spinner))
               (dissoc :thinking-started-at))
           (session-watch-cmd)]

          :else [base-state (session-watch-cmd)]))

      (spinner/tick-msg? message)
      [state spinner-cmd]

      (msg/key-match? message "enter")
      (handle-submit state)

      :else
      (let [[input cmd] (text-input/text-input-update (:input state) message)]
        [(assoc state :input input) cmd]))))

;;; ---------------------------------------------------------------------------
;;; View helpers
;;; ---------------------------------------------------------------------------

(defn- markdown-heading-line? [line]
  (boolean (re-matches #"^\s{0,3}#{1,6}\s+\S.*$" (or line ""))))

(defn- fence-line? [line]
  (boolean (re-matches #"^\s*```.*$" (or line ""))))

(defn- highlight-markdown-headings [text theme]
  (let [lines         (str/split (or text "") #"\n" -1)
        heading-style (make-style (:heading theme))]
    (loop [remaining lines
           in-fence?  false
           acc        []]
      (if-let [line (first remaining)]
        (let [fence?       (fence-line? line)
              highlighted  (if (and (not in-fence?) (markdown-heading-line? line))
                             (style/render heading-style line)
                             line)
              next-fence?  (if fence? (not in-fence?) in-fence?)]
          (recur (rest remaining) next-fence? (conj acc highlighted)))
        (str/join "\n" acc)))))

(defn- line-count [text]
  (let [text (or text "")]
    (if (empty? text) 0 (count (str/split text #"\n" -1)))))

(defn- pad-lines [n]
  (apply str (repeat (max 0 n) "\n")))

(defn- status-bar-view [session window-width theme]
  (let [status-text    (str "Provider: Anthropic"
                            "  │  Model: " (:model session)
                            "  │  Enter=send"
                            "  │  Ctrl+C=quit"
                            "  │  /help=commands")
        content-width  (when (and window-width (pos? window-width))
                         (max 24 (- window-width 2)))
        status-style   (make-style
                        (cond-> (merge {:border border/rounded :align :left}
                                       (:status-bar theme))
                          content-width (assoc :width content-width)))]
    (style/render status-style status-text)))

;;; ---------------------------------------------------------------------------
;;; View
;;; ---------------------------------------------------------------------------

(defn view-fn [state]
  (let [session       (:session state)
        global        (server/global-state)
        theme         (theme-config state)
        window-width  (get-in state [:window-size :width])
        content-width (when (and window-width (pos? window-width))
                        (max 24 (- window-width 2)))
        top-section   (str "# babagent\n\n"
                           "- Session: `" (:id session) "`\n"
                           "- Anthropic auth: **" (if (:api-key global) "configured" "missing") "**\n"
                           (when-let [status (:status session)]
                             (str "- Status: "
                                  (if (:waiting? session)
                                    (let [elapsed (some-> (:thinking-started-at state)
                                                          (as-> t (quot (- (System/currentTimeMillis) t) 1000)))]
                                      (str (spinner/spinner-view (:spinner state))
                                           " Thinking (" (or elapsed 0) "s)..."))
                                    status)
                                  "\n"))
                           "\n"
                           (if (seq (:messages session))
                             (history-view session theme content-width)
                             (let [label (style/render (make-style (:system-label theme)) "System")]
                               (str label "\n\nWelcome. Configure Anthropic auth with `/auth <key>`."))))
        bottom-section (str (text-input/text-input-view (:input state))
                            "\n"
                            (status-bar-view session window-width theme))
        window-height  (get-in state [:window-size :height])
        spacer-lines   (if (and window-height (pos? window-height))
                         (max 1 (- window-height
                                   (line-count top-section)
                                   (line-count bottom-section)))
                         2)]
    (highlight-markdown-headings
     (str top-section (pad-lines spacer-lines) bottom-section)
     theme)))

;;; ---------------------------------------------------------------------------
;;; Init and run
;;; ---------------------------------------------------------------------------

(defn init-state
  ([] (init-state nil))
  ([preferred-session-id]
   (server/init!)
   (let [cfg        (config/load-config)
         session-id (or preferred-session-id (:id (server/create-session!)))
         session    (server/get-session session-id)]
     (watch-tui-session! session-id)
     {:session-id             session-id
      :session                session
      :theme                  (:theme cfg)
      :spinner                (make-spinner)
      :session-watch-started? false
      :input                  (text-input/text-input :prompt      "> "
                                                     :placeholder "Type a prompt or /help")
      :window-size            nil})))

(defn run!
  "Start the terminal UI. Blocks until the user quits.
   Optional preferred-session-id attaches to an existing session."
  ([] (run! nil))
  ([preferred-session-id]
   (try
     (program/run {:init       #(init-state preferred-session-id)
                   :update     #'update-fn
                   :view       #'view-fn
                   :alt-screen true})
     (finally
       (unwatch-tui-session!)))))
