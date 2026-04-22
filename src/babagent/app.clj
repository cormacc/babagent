(ns babagent.app
  (:require
   [babagent.extensions :as extensions]
   [clojure.string :as str]))

(def session-message-limit 80)

(defn add-message
  ([session msg]       (update session :messages conj msg))
  ([session role text] (update session :messages conj {:role role :text text})))

(defn trim-messages [messages limit]
  (if (> (count messages) limit)
    (vec (take-last limit messages))
    messages))

(defn mask-key [api-key]
  (if (or (nil? api-key)
          (< (count api-key) 10))
    "(hidden)"
    (str (subs api-key 0 6) "..." (subs api-key (- (count api-key) 4)))))

(defn new-session [{:keys [model]}]
  {:id nil
   :revision 0
   :messages []
   :model model
   :available-models []
   :status "Ready"
   :waiting? false})

(defn- result
  [session & {:keys [effects client-effects shared-effects]}]
  {:session session
   :effects (vec effects)
   :client-effects (vec client-effects)
   :shared-effects (vec shared-effects)})

(defn- help-text []
  (str "### Commands\n\n"
       (->> (extensions/commands)
            (sort-by :name)
            (map (fn [{:keys [name description]}]
                   (str "- `" name "`"
                        (when-not (str/blank? description)
                          (str " — " description)))))
            (str/join "\n"))))

(defn- format-model-list [models current-model]
  (if (seq models)
    (str "### Available models\n\n"
         (->> models
              (map-indexed (fn [idx model]
                             (str (inc idx) ". `" model "`"
                                  (when (= model current-model)
                                    " **(current)**"))))
              (str/join "\n"))
         "\n\nUse `/model <number>` to select, or `/model <name>` to set manually.")
    "No models returned by Anthropic."))

(defn builtin-command-definitions []
  [{:name "/help"
    :description "Show available commands"
    :handler (fn [{:keys [session]}]
               (result (add-message session :system (help-text))))}

   {:name "/auth"
    :description "Set global Anthropic API key: /auth sk-ant-..."
    :handler (fn [{:keys [session args]}]
               (let [api-key (first args)]
                 (if (str/blank? api-key)
                   (result (add-message session :system "Usage: /auth <anthropic-api-key>"))
                   (result (-> session
                               (assoc :available-models [])
                               (add-message :system
                                            (str "Configured global Anthropic API key "
                                                 (mask-key api-key))))
                           :effects          [[:config/save-global-auth {:api-key api-key}]]
                           :shared-effects   [[:shared/set-api-key     {:api-key api-key}]]))))}

   {:name "/model"
    :description "Show/set session model: /model, /model <name>, /model <number> (after /models)"
    :handler (fn [{:keys [session args]}]
               (let [selection (first args)]
                 (cond
                   (str/blank? selection)
                   (result (add-message session :system
                                        (str "Current model: " (:model session)
                                             "\nRun /models to fetch selectable models.")))

                   (re-matches #"\d+" selection)
                   (let [index (dec (Long/parseLong selection))
                         models (:available-models session)]
                     (cond
                       (empty? models)
                       (result (add-message session :system
                                            "No fetched model list yet. Run /models first."))

                       (or (neg? index) (>= index (count models)))
                       (result (add-message session :system
                                            (str "Invalid model index " selection
                                                 ". Run /models to see valid numbers.")))

                       :else
                       (let [model (nth models index)]
                         (result (-> session
                                     (assoc :model model)
                                     (add-message :system (str "Model set to " model)))))))

                   :else
                   (result (-> session
                               (assoc :model selection)
                               (add-message :system (str "Model set to " selection)))))))}

   {:name "/models"
    :description "Fetch available Anthropic models for this session"
    :handler (fn [{:keys [session shared]}]
               (if (str/blank? (:api-key shared))
                 (result (add-message session :system
                                      "Set key first: /auth <anthropic-api-key>"))
                 (result (-> session
                             (assoc :status "Loading model list...")
                             (assoc :waiting? true))
                         :effects [[:anthropic/list-models {:api-key        (:api-key shared)
                                                             :response-event :provider/models-listed}]])))}

   {:name "/status"
    :description "Show current session auth + model status"
    :handler (fn [{:keys [session shared]}]
               (result (add-message session :system
                                    (str "### Session status\n"
                                         "- Model: `" (:model session) "`\n"
                                         "- Anthropic key: `"
                                         (if (:api-key shared)
                                           (mask-key (:api-key shared))
                                           "not configured")
                                         "`"))))}

   {:name "/test-auth"
    :description "Verify the session API key with Anthropic model listing"
    :handler (fn [{:keys [session shared]}]
               (if (str/blank? (:api-key shared))
                 (result (add-message session :system
                                      "Set key first: /auth <anthropic-api-key>"))
                 (result (-> session
                             (assoc :status "Testing Anthropic authentication...")
                             (assoc :waiting? true))
                         :effects [[:anthropic/list-models {:api-key        (:api-key shared)
                                                             :response-event :provider/auth-tested}]])))}

   {:name "/reload"
    :description "Reload configuration, extensions, and theme"
    :handler (fn [{:keys [session]}]
               (result (-> session
                           (assoc :status "Reloading configuration and extensions...")
                           (assoc :waiting? true))
                       :effects [[:runtime/reload]]))}

   {:name "/reload-ext"
    :description "Reload extensions from ./extensions.edn"
    :handler (fn [{:keys [session]}]
               (result (-> session
                           (assoc :status "Reloading extensions...")
                           (assoc :waiting? true))
                       :effects [[:extensions/load]]))}

   {:name "/quit"
    :description "Close the current client"
    :handler (fn [{:keys [session]}]
               (result session :client-effects [[:client/quit]]))}])

(defn handle-submit [session shared line]
  (let [line (str/trim (or line ""))]
    (cond
      (str/blank? line)
      (result session)

      (and (str/starts-with? line "/") (:waiting? session))
      (result (add-message session :system "Busy with an API request. Wait for completion."))

      (str/starts-with? line "/")
      (extensions/run-command {:session session
                               :shared shared} line)

      (:waiting? session)
      (result (add-message session :system "Busy with an API request. Wait for completion."))

      (str/blank? (:api-key shared))
      (result (add-message session :system "No API key set. Use: /auth <anthropic-api-key>"))

      :else
      (result (-> session
                  (add-message :user line)
                  (assoc :status "Waiting for Anthropic response...")
                  (assoc :waiting? true)
                  (update :messages trim-messages session-message-limit))
              :effects [[:anthropic/create-message {:api-key (:api-key shared)
                                                     :model   (:model session)
                                                     :prompt  line}]]))))

(defn handle-event [session shared event]
  (case (:type event)
    :session/submit-input
    (handle-submit session shared (:text event))

    :provider/auth-tested
    (if (:ok event)
      (result (-> session
                  (assoc :status "Authenticated with Anthropic")
                  (assoc :waiting? false)
                  (add-message :system
                               (if (seq (:models event))
                                 (str "Auth OK. Models: " (str/join ", " (:models event)))
                                 "Auth OK."))))
      (result (-> session
                  (assoc :status "Auth test failed")
                  (assoc :waiting? false)
                  (add-message :system (str "Auth failed: " (:error event))))))

    :provider/models-listed
    (if (:ok event)
      (let [models (:models event)]
        (result (-> session
                    (assoc :status (if (seq models)
                                     (str "Loaded " (count models) " models")
                                     "No models returned"))
                    (assoc :waiting? false)
                    (assoc :available-models models)
                    (add-message :system (format-model-list models (:model session))))))
      (result (-> session
                  (assoc :status "Model list failed")
                  (assoc :waiting? false)
                  (add-message :system (str "Model list failed: " (:error event))))))

    :tool/executed
    (result (add-message session {:role      :tool-result
                                  :tool-name (:tool-name event)
                                  :input     (:input event)
                                  :result    (:result event)}))

    :provider/assistant-response
    (if (:ok event)
      (result (-> session
                  (assoc :status "Ready")
                  (assoc :waiting? false)
                  (add-message :assistant (:text event))
                  (update :messages trim-messages session-message-limit)))
      (result (-> session
                  (assoc :status "Request failed")
                  (assoc :waiting? false)
                  (add-message :system (str "Request failed: " (:error event))))))

    :extensions/reloaded
    (result (-> session
                (assoc :status "Extensions reloaded")
                (assoc :waiting? false)
                (add-message :system "Extensions reloaded")))

    :extensions/reload-failed
    (result (-> session
                (assoc :status "Extension load failed")
                (assoc :waiting? false)
                (add-message :system
                             (str "Extension load failed: " (:error event)))))

    :runtime/reloaded
    (result (-> session
                (assoc :status "Reload complete")
                (assoc :waiting? false)
                (assoc :model (or (:model event) (:model session)))
                (add-message :system
                             (str "### Reload complete\n"
                                  "- Configuration reloaded\n"
                                  "- Extensions reloaded\n"
                                  "- Theme: "
                                  (if (:theme-loaded? event)
                                    "loaded"
                                    "using defaults")))))

    :runtime/reload-failed
    (result (-> session
                (assoc :status "Reload failed")
                (assoc :waiting? false)
                (add-message :system
                             (str "Reload failed: " (:error event)))))

    (result session)))
