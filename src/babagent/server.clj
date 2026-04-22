(ns babagent.server
  (:require
   [babagent.providers.anthropic :as anthropic]
   [babagent.app :as app]
   [babagent.config :as config]
   [babagent.extensions :as extensions]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [nexus.core :as nexus])
  (:import
   (java.time Instant ZoneOffset)
   (java.time.format DateTimeFormatter)))

(defonce !server-state (atom {:next-session-id 0
                              :global {:api-key nil}
                              :sessions {}
                              :session-logs {}}))

(defonce !initialized? (atom false))

(defn init!
  []
  (when (compare-and-set! !initialized? false true)
    (doseq [definition (app/builtin-command-definitions)]
      (extensions/register-command! definition))
    (extensions/load-extensions!))
  nil)

(defn- allocate-session-id! []
  (let [id (str "session-" (:next-session-id @!server-state))]
    (swap! !server-state update :next-session-id inc)
    id))

(defn- bump-revision [session]
  (update session :revision (fnil inc 0)))

(def ^:private session-timestamp-formatter
  (-> (DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmss'Z'")
      (.withZone ZoneOffset/UTC)))

(defn- now-timestamp []
  (.format session-timestamp-formatter (Instant/now)))

(defn- session-cwd []
  (or (System/getProperty "user.dir") ""))

(defn- register-session-log! [session-id]
  (let [cwd (session-cwd)
        started-at (now-timestamp)
        path (config/session-log-path cwd started-at)]
    (swap! !server-state assoc-in [:session-logs session-id]
           {:path path
            :cwd cwd
            :started-at started-at})
    path))

(defn- write-session-log! [session-id session]
  (when-let [{:keys [path cwd started-at]} (get-in @!server-state [:session-logs session-id])]
    (try
      (fs/create-dirs (fs/parent path))
      (spit (str path)
            (pr-str {:session-id session-id
                     :started-at started-at
                     :updated-at (now-timestamp)
                     :cwd cwd
                     :project-folder (-> (or cwd "")
                                         (str/replace "/" "-")
                                         (str/replace "\\" "-"))
                     :session session}))
      (catch Exception _
        nil))))

(defn create-session!
  []
  (init!)
  (let [cfg (config/load-config)
        _ (swap! !server-state assoc :global {:api-key (:anthropic-api-key cfg)})
        session-id (allocate-session-id!)
        session (-> (app/new-session {:model (:model cfg)})
                    (assoc :id session-id)
                    bump-revision)]
    (register-session-log! session-id)
    (swap! !server-state assoc-in [:sessions session-id] session)
    (write-session-log! session-id session)
    session))

(defn get-session [session-id]
  (get-in @!server-state [:sessions session-id]))

(defn list-sessions []
  (vals (:sessions @!server-state)))

(defn- store-session! [session-id session]
  (swap! !server-state assoc-in [:sessions session-id] session)
  (write-session-log! session-id session)
  session)

(defn global-state []
  (:global @!server-state))

(defn- reload-runtime! []
  (extensions/load-extensions!)
  (let [cfg (config/load-config)]
    (swap! !server-state assoc :global {:api-key (:anthropic-api-key cfg)})
    cfg))

(defn apply-event!
  [session-id event]
  (let [session (or (get-session session-id)
                    (throw (ex-info "Unknown session" {:session-id session-id})))
        shared (global-state)
        {:keys [session effects client-effects shared-effects]} (app/handle-event session shared event)
        final-session (-> session bump-revision)]
    (store-session! session-id final-session)
    {:session final-session
     :effects effects
     :client-effects client-effects
     :shared-effects shared-effects}))

;;; Nexus effect dispatch
;;; ---------------------------------------------------------------------------

(declare server-dispatch!)

(def ^:private base-server-system
  {:nexus/system->state deref

   :nexus/effects
   {:shared/set-api-key
    (fn [_ctx store {:keys [api-key]}]
      (swap! store assoc-in [:global :api-key] api-key))

    :config/save-global-auth
    (fn [_ctx _store {:keys [api-key]}]
      (config/save-config! {:anthropic-api-key api-key
                            :model             (:model (config/load-config))}))

    :anthropic/create-message
    (fn [{:keys [dispatch-data]} _store {:keys [api-key model prompt]}]
      (let [{:keys [session-id]} dispatch-data
            notify! #(server-dispatch! session-id %)
            result  (anthropic/execute-assistant-request {:api-key api-key :model model :prompt prompt}
                                               notify!)]
        (server-dispatch! session-id result)))

    :anthropic/list-models
    (fn [{:keys [dispatch-data]} _store effect-args]
      (server-dispatch! (:session-id dispatch-data)
                        (anthropic/execute-list-models effect-args)))

    :extensions/load
    (fn [{:keys [dispatch-data]} _store]
      (server-dispatch! (:session-id dispatch-data)
                        (try (extensions/load-extensions!)
                             {:type :extensions/reloaded}
                             (catch Exception e
                               {:type :extensions/reload-failed
                                :error (.getMessage e)}))))

    :runtime/reload
    (fn [{:keys [dispatch-data]} _store]
      (server-dispatch! (:session-id dispatch-data)
                        (try (let [cfg (reload-runtime!)]
                               {:type :runtime/reloaded
                                :model         (:model cfg)
                                :theme-loaded? (map? (:theme cfg))})
                             (catch Exception e
                               {:type :runtime/reload-failed
                                :error (.getMessage e)}))))}})

(defonce !server-system (atom base-server-system))

(defn register-effect-handler!
  "Register a server-side nexus effect handler fn[ctx store & args] → nil.
   Intended for use by extensions to add new effect types."
  [effect-type handler-fn]
  (swap! !server-system assoc-in [:nexus/effects effect-type] handler-fn))

(defn- dispatch-effects! [session-id effects]
  (when (seq effects)
    (nexus/dispatch @!server-system !server-state {:session-id session-id}
                    effects)))

(defn- server-dispatch! [session-id event]
  (let [{:keys [effects shared-effects]} (apply-event! session-id event)]
    (dispatch-effects! session-id (vec (concat shared-effects effects)))))

(defn submit!
  "Apply event and run effects asynchronously. State changes are observable
   via add-watch on !server-state. Returns {:client-effects [...]} immediately."
  [session-id event]
  (let [{:keys [effects shared-effects client-effects]} (apply-event! session-id event)
        all-effects (vec (concat shared-effects effects))]
    (when (seq all-effects)
      (future (dispatch-effects! session-id all-effects)))
    {:client-effects client-effects}))
