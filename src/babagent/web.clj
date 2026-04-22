(ns babagent.web
  (:require
   [babagent.server :as server]
   [babagent.tools :as tools]
   [babashka.fs :as fs]
   [babashka.process :as process]
   [cheshire.core :as json]
   [clojure.string :as str]
   [org.httpkit.server :as http]))

;;; ---------------------------------------------------------------------------
;;; Web UI build check
;;; ---------------------------------------------------------------------------

(def ^:private web-ui-output "resources/public/js/web-ui.js")

(def ^:private web-ui-sources
  ["shadow-cljs.edn"
   "src/babagent/web_ui"])

(defn- source-mtimes []
  (for [p     web-ui-sources
        :let  [fpath (fs/path p)]
        f     (if (fs/directory? fpath)
                (fs/glob fpath "**.cljs")
                [fpath])
        :when (fs/exists? f)]
    (fs/last-modified-time f)))

(defn- web-ui-needs-rebuild? []
  (let [out (fs/path web-ui-output)]
    (if-not (fs/exists? out)
      true
      (let [out-mtime (fs/last-modified-time out)]
        (some #(pos? (compare % out-mtime)) (source-mtimes))))))

(defn ensure-web-ui-built! []
  (when (web-ui-needs-rebuild?)
    (println "[web] Web UI sources changed — rebuilding...")
    (let [result (process/shell "npm run build:web-ui")]
      (if (zero? (:exit result))
        (println "[web] Web UI build complete")
        (println "[web] WARNING: Web UI build failed (exit" (:exit result) ")")))))


(declare stop!)

;;; ---------------------------------------------------------------------------
;;; Client tracking and shutdown watchdog
;;; ---------------------------------------------------------------------------

(defonce !clients          (atom {}))   ; WS: channel → session-id
(defonce !tui-client-count (atom 0))    ; in-process TUI registrations
(defonce !web-session-id   (atom nil))

(defn- total-clients []
  (+ (count @!clients) @!tui-client-count))

(defn- schedule-shutdown! []
  (future
    (Thread/sleep 30000)
    (when (zero? (total-clients))
      (println "[web] No clients connected for 30s, shutting down")
      (stop!)
      (System/exit 0))))

(defn- check-shutdown! []
  (when (zero? (total-clients))
    (schedule-shutdown!)))

(defn register-tui-client! []
  (swap! !tui-client-count inc))

(defn deregister-tui-client! []
  (swap! !tui-client-count dec)
  (check-shutdown!))

;;; ---------------------------------------------------------------------------
;;; Session → JSON
;;; ---------------------------------------------------------------------------

(defn- message->json [msg]
  (case (:role msg)
    :tool-result
    {:role        "tool-result"
     :tool-name   (name (:tool-name msg))
     :call-text   (tools/render-tool-call (:tool-name msg) (:input msg))
     :result-text (tools/render-tool-result (:tool-name msg) (:input msg) (:result msg))
     :ok          (boolean (get-in msg [:result :ok]))}
    {:role (name (:role msg))
     :text (:text msg)}))

(defn- session->json [session]
  (-> session
      (update :messages (fn [msgs] (mapv message->json (or msgs []))))
      (update :waiting? boolean)
      (dissoc :available-models)))

(defn- session-summary [session]
  {:id            (:id session)
   :model         (:model session)
   :updated-at    (:updated-at session)
   :message-count (count (:messages session))})

(defn- list-sessions-sorted []
  (->> (server/list-sessions)
       (sort-by :updated-at #(compare %2 %1))))

;;; ---------------------------------------------------------------------------
;;; Send helpers
;;; ---------------------------------------------------------------------------

(defn- send-to! [ch payload]
  (try
    (http/send! ch (json/generate-string payload))
    (catch Exception _
      (swap! !clients dissoc ch))))

(defn- send-session! [ch session]
  (send-to! ch {:type "session" :session (session->json session)}))

(defn- send-sessions-list! [ch]
  (send-to! ch {:type    "sessions"
                :sessions (mapv session-summary (list-sessions-sorted))}))

;;; ---------------------------------------------------------------------------
;;; Broadcast to clients watching a particular session
;;; ---------------------------------------------------------------------------

(defn- broadcast-session! [session]
  (let [sid (:id session)]
    (doseq [[ch watched-sid] @!clients]
      (when (= watched-sid sid)
        (send-session! ch session)))))

(defn- start-session-watch! []
  (add-watch server/!server-state ::web-broadcast
             (fn [_ _ old new]
               (doseq [[sid new-s] (:sessions new)]
                 (let [old-s (get-in old [:sessions sid])]
                   (when (not= old-s new-s)
                     (broadcast-session! new-s)))))))

;;; ---------------------------------------------------------------------------
;;; WebSocket message handling
;;; ---------------------------------------------------------------------------

(defn- handle-ws-message! [ch raw]
  (try
    (let [msg        (json/parse-string raw true)
          session-id (get @!clients ch)]
      (case (:type msg)
        "submit"
        (when session-id
          (server/submit! session-id {:type :session/submit-input
                                      :text (str (:text msg))}))

        "select-session"
        (let [new-sid (keyword (:session-id msg))]
          (when-let [s (server/get-session new-sid)]
            (swap! !clients assoc ch new-sid)
            (send-sessions-list! ch)
            (send-session! ch s)))

        nil))
    (catch Exception e
      (println "[web] WS message error:" (.getMessage e)))))

(defn- handle-ws [req]
  (http/as-channel req
    {:on-open
     (fn [ch]
       (let [sessions   (list-sessions-sorted)
             default-s  (or (first sessions) (server/create-session!))
             default-id (:id default-s)]
         (swap! !clients assoc ch default-id)
         (send-sessions-list! ch)
         (send-session! ch (server/get-session default-id))))

     :on-receive
     (fn [ch data]
       (handle-ws-message! ch data))

     :on-close
     (fn [ch _status]
       (swap! !clients dissoc ch)
       (check-shutdown!))}))

;;; ---------------------------------------------------------------------------
;;; Static file serving
;;; ---------------------------------------------------------------------------

(def ^:private mime-types
  {"html" "text/html; charset=utf-8"
   "css"  "text/css"
   "js"   "application/javascript"
   "json" "application/json"
   "png"  "image/png"
   "ico"  "image/x-icon"
   "svg"  "image/svg+xml"})

(defn- mime-for [path]
  (let [ext (some->> path (re-find #"\.([^.]+)$") second str/lower-case)]
    (get mime-types ext "application/octet-stream")))

(defn- serve-file [rel-path]
  (let [f (fs/path "resources/public" rel-path)]
    (if (and (fs/exists? f) (not (fs/directory? f)))
      {:status  200
       :headers {"content-type" (mime-for rel-path)}
       :body    (clojure.java.io/input-stream (str f))}
      {:status 404 :body "Not found\n"})))

(defn- serve-static [req]
  (let [uri (:uri req)]
    (serve-file (if (= uri "/") "web/index.html" (subs uri 1)))))

;;; ---------------------------------------------------------------------------
;;; Ring handler
;;; ---------------------------------------------------------------------------

(defn- websocket-upgrade? [req]
  (= "websocket" (str/lower-case (get-in req [:headers "upgrade"] ""))))

(defn- app []
  (fn [req]
    (cond
      (and (= (:uri req) "/ws") (websocket-upgrade? req))
      (handle-ws req)

      (and (= (:uri req) "/ping") (= :get (:request-method req)))
      {:status  200
       :headers {"content-type" "application/json"}
       :body    (json/generate-string {:ok       true
                                       :sessions (count (server/list-sessions))})}

      (= :get (:request-method req))
      (serve-static req)

      :else
      {:status 404 :body "Not found\n"})))

;;; ---------------------------------------------------------------------------
;;; Lifecycle
;;; ---------------------------------------------------------------------------

(defonce !http-server (atom nil))

(defn start! [port]
  (ensure-web-ui-built!)
  (server/init!)
  (let [session    (server/create-session!)
        session-id (:id session)]
    (reset! !web-session-id session-id)
    (start-session-watch!)
    (let [srv (http/run-server (app) {:port port})]
      (reset! !http-server srv)
      (println (str "[web] babagent web UI running on http://localhost:" port)))))

(defn stop! []
  (when-let [srv @!http-server]
    (srv)
    (reset! !http-server nil))
  (remove-watch server/!server-state ::web-broadcast))

(defn -main [& _args]
  (start! 6767)
  @(promise))
