(ns babagent.core
  (:require
   [babagent.ui.terminal :as terminal]
   [babagent.web :as web]
   [babashka.http-client :as http-client]))

(defn- backend-running? []
  (try
    (= 200 (:status (http-client/get "http://localhost:6767/ping" {:timeout 1000})))
    (catch Exception _ false)))

(defn- try-start-web! [port]
  (try
    (web/start! port)
    true
    (catch java.net.BindException _
      (println (str "[web] Port " port " already in use"))
      false)))

(defn -main [& args]
  (let [no-server?   (some #(= "--no-server" %) args)
        web-started? (when-not no-server?
                       (if (backend-running?)
                         (do (println "[web] Backend already running on :6767") false)
                         (try-start-web! 6767)))]
    (when web-started?
      (web/register-tui-client!))
    (try
      (terminal/run! (when web-started? @web/!web-session-id))
      (finally
        (when web-started?
          (web/deregister-tui-client!))))))
