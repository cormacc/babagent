(ns babagent.ui.web.views)

;;; ---------------------------------------------------------------------------
;;; Message views
;;; ---------------------------------------------------------------------------

(defn- tool-message-view [msg]
  [:div {:class (if (:ok msg) "message msg-tool-success" "message msg-tool-error")}
   [:div.tool-header
    [:span.tool-name (str "[" (:tool-name msg) "]")]
    (when (seq (:call-text msg))
      [:span.tool-args (str " " (:call-text msg))])]
   [:div.tool-body
    [:span.tool-status (if (:ok msg) "✓" "✗")]
    [:pre.tool-output (str " " (:result-text msg))]]])

(defn message-view [msg]
  (case (:role msg)
    "user"
    [:div.message.msg-user {}
     [:div.msg-label "You"]
     [:p.msg-text (:text msg)]]

    "assistant"
    [:div.message.msg-assistant {}
     [:div.msg-label "Assistant"]
     [:pre.msg-text (or (not-empty (:text msg)) "(empty response)")]]

    "system"
    [:div.message.msg-system {}
     [:div.msg-label "System"]
     [:p.msg-text (:text msg)]]

    "tool-result"
    (tool-message-view msg)

    ;; default
    [:div.message {}
     [:p.msg-text (or (:text msg) "")]]))

;;; ---------------------------------------------------------------------------
;;; Session picker
;;; ---------------------------------------------------------------------------

(defn- session-option [active-id {:keys [id model message-count]}]
  [:option {:value    (name id)
            :selected (= id active-id)}
   (str (name id) " — " model " (" message-count " msgs)")])

(defn session-picker-view [state]
  (let [sessions  (get state :sessions [])
        active-id (get-in state [:session :id])]
    (when (seq sessions)
      [:div.session-picker
       [:label {:for "session-select"} "Session:"]
       [:select#session-select
        {:on {:change [[:actions/select-session [:event.target/value]]]}}
        (map-indexed
         (fn [i s]
           (assoc-in (session-option active-id s) [1 :replicant/key] i))
         sessions)]])))

;;; ---------------------------------------------------------------------------
;;; Header
;;; ---------------------------------------------------------------------------

(defn header-view [state]
  (let [session  (:session state)
        waiting? (boolean (:waiting? session))]
    [:header
     [:h1 "babagent"]
     [:div.header-right
      (session-picker-view state)
      [:div.session-info
       (when-let [model (:model session)]
         [:span.model model])
       (when waiting?
         [:span.thinking "Thinking…"])
       [:span {:class (if (:connected? state) "conn-status status-ok" "conn-status status-err")}
        (if (:connected? state) "● connected" "○ disconnected")]]]]))

;;; ---------------------------------------------------------------------------
;;; Input bar
;;; ---------------------------------------------------------------------------

(defn input-view [state]
  (let [waiting? (boolean (get-in state [:session :waiting?]))]
    [:div.input-area
     [:input#main-input
      {:type        "text"
       :value       (get state :input "")
       :placeholder "Type a message or /command…"
       :disabled    waiting?
       :on          {:input   [[:effects/save [:input] [:event.target/value]]]
                     :keydown [[:actions/submit-on-enter [:event/key]]]}}]
     [:button.send-btn
      {:disabled waiting?
       :on       {:click [[:actions/submit]]}}
      "Send"]]))

;;; ---------------------------------------------------------------------------
;;; Root view
;;; ---------------------------------------------------------------------------

(defn app-view [state]
  (let [messages (get-in state [:session :messages] [])]
    [:div#app
     (header-view state)
     [:main.messages
      {:replicant/on-update [[:effects/scroll-to-bottom]]}
      (if (seq messages)
        (map-indexed
         (fn [i msg]
           (assoc-in (message-view msg) [1 :replicant/key] i))
         messages)
        [:div.welcome {}
         [:p {} "Welcome to babagent. Configure Anthropic auth with "
          [:code {} "/auth <key>"] "."]])]
     (input-view state)]))
