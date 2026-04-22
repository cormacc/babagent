(ns babagent.ui.web.core
  (:require
   [babagent.ui.web.nexus :as nx]
   [babagent.ui.web.views :as views]
   [replicant.dom :as r]))

(defonce store
  (atom {:session    nil
         :input      ""
         :connected? false}))

(defn init! []
  (let [el (.getElementById js/document "app")]
    ;; Wire Replicant dispatch → Nexus
    (r/set-dispatch!
     (fn [dispatch-data actions]
       (nx/dispatch store dispatch-data actions)))

    ;; Re-render on every store change
    (add-watch store ::render
               (fn [_ _ _ new-state]
                 (r/render el (views/app-view new-state))))

    ;; Open WebSocket connection (auto-reconnects on close)
    (nx/connect-ws! store)

    ;; Initial render
    (r/render el (views/app-view @store))))
