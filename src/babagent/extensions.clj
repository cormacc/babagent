(ns babagent.extensions
  (:require
   [babashka.fs :as fs]
   [babagent.tools :as tools]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(defonce command-registry (atom {}))

(defn register-command! [{:keys [name description handler]}]
  (when-not (and (string? name)
                 (fn? handler))
    (throw (ex-info "Invalid command definition"
                    {:name name
                     :description description
                     :handler handler})))
  (swap! command-registry assoc name {:name name
                                      :description description
                                      :handler handler})
  nil)

(defn commands []
  (vals @command-registry))

(defn command [name]
  (get @command-registry name))

(defn register-tool! [tool-definition]
  (tools/register-tool! tool-definition))

(defn register-external-tool!
  [{:keys [name description input_schema command timeout-seconds]}]
  (tools/register-tool! {:name name
                         :description description
                         :input_schema input_schema
                         :external {:command command
                                    :timeout-seconds timeout-seconds}}))

(defn run-command [{:keys [session shared]} line]
  (let [[name & args] (str/split (str/trim line) #"\s+")]
    (if-let [{:keys [handler]} (command name)]
      (handler {:session session
                :shared shared
                :args args
                :line line})
      {:session (update session :messages conj {:role :system
                                                :text (str "Unknown command: " name " (try /help)")})
       :effects []
       :client-effects []
       :shared-effects []})))

(defn- extensions-file []
  (fs/path (fs/cwd) "extensions.edn"))

(defn load-extensions! []
  (let [path (extensions-file)]
    (when (fs/exists? path)
      (let [namespaces (edn/read-string (slurp (str path)))]
        (doseq [ns-sym namespaces]
          (require ns-sym)
          (if-let [register-fn (ns-resolve ns-sym 'register!)]
            (register-fn)
            (throw (ex-info "Extension missing register! function"
                            {:namespace ns-sym}))))))))
