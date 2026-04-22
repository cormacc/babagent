(ns babagent.ui.web.nexus
  (:require
   [clojure.string :as str]
   [nexus.core :as nexus]))

;;; ---------------------------------------------------------------------------
;;; WebSocket connection
;;; ---------------------------------------------------------------------------

(defonce !ws (atom nil))

(defn send-ws! [data]
  (when-let [ws @!ws]
    (when (= (.-readyState ws) js/WebSocket.OPEN)
      (.send ws (js/JSON.stringify (clj->js data))))))

(defn connect-ws! [store]
  (let [ws (js/WebSocket. (str "ws://" (.-host js/location) "/ws"))]
    (set! (.-onopen ws)
          (fn [_]
            (swap! store assoc :connected? true)))
    (set! (.-onclose ws)
          (fn [_]
            (swap! store assoc :connected? false)
            (js/setTimeout #(connect-ws! store) 2000)))
    (set! (.-onerror ws)
          (fn [e] (.error js/console "WebSocket error" e)))
    (set! (.-onmessage ws)
          (fn [e]
            (try
              (let [data (js->clj (js/JSON.parse (.-data e)) :keywordize-keys true)
                    t    (:type data)]
                (cond
                  (= t "sessions") (swap! store assoc :sessions (:sessions data))
                  (= t "session")  (swap! store assoc :session (:session data))))
              (catch js/Error err
                (.error js/console "WS parse error" (.-message err) err)))))
    (reset! !ws ws)))

;;; ---------------------------------------------------------------------------
;;; Nexus system map
;;; ---------------------------------------------------------------------------

(def system
  {:nexus/system->state deref

   :nexus/placeholders
   {:event.target/value
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-target .-value))

    :event/key
    (fn [{:replicant/keys [dom-event]}]
      (some-> dom-event .-key))}

   :nexus/effects
   {:effects/save
    ^:nexus/batch
    (fn [_ctx store path-vs]
      (swap! store
             (fn [state]
               (reduce (fn [acc [path v]]
                         (assoc-in acc path v))
                       state
                       path-vs))))

    :effects/ws-send
    (fn [_ctx store data]
      (send-ws! data))

    :effects/prevent-default
    (fn [{:replicant/keys [dom-event]} _store]
      (some-> dom-event .preventDefault))

    :effects/scroll-to-bottom
    (fn [ctx _store]
      (when-let [node (get-in ctx [:dispatch-data :replicant/node])]
        (set! (.-scrollTop node) (.-scrollHeight node))))}

   :nexus/actions
   {:actions/submit
    (fn [state]
      (let [text (str/trim (get state :input ""))]
        (when-not (str/blank? text)
          [[:effects/ws-send {:type "submit" :text text}]
           [:effects/save [:input] ""]])))

    :actions/select-session
    (fn [_state session-id]
      [[:effects/ws-send {:type "select-session" :session-id session-id}]])

    :actions/submit-on-enter
    (fn [state key]
      (when (= key "Enter")
        (let [text (str/trim (get state :input ""))]
          (when-not (str/blank? text)
            [[:effects/ws-send {:type "submit" :text text}]
             [:effects/save [:input] ""]]))))}})

;;; ---------------------------------------------------------------------------
;;; Dispatch entry point
;;; ---------------------------------------------------------------------------

(defn dispatch [store dispatch-data actions]
  (nexus/dispatch system store dispatch-data actions))
