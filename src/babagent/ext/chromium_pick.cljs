(ns babagent.ext.chromium-pick
  "Browser-side helper registered as window.pick(message).

   Compiled by shadow-cljs to resources/public/js/chromium-pick.js and
   injected into the active Chromium tab by babagent.ext.chromium's
   browser-pick-tool.")

(defn- truncate [s n]
  (when (some? s)
    (if (> (count s) n) (subs s 0 n) s)))

(defn- element-info [el]
  (let [id (.-id el)
           cls (.-className el)]
       (js-obj "tag"   (.toLowerCase (.-tagName el))
               "id"    (when-not (= "" id) id)
               "class" (when-not (= "" cls) cls)
               "text"  (truncate (some-> (.-textContent el) .trim) 200)
               "html"  (truncate (.-outerHTML el) 500))))

(defn- highlight-style [r]
  (str "position:absolute;"
       "border:2px solid #3b82f6;"
       "background:rgba(59,130,246,0.1);"
       "top:" (.-top r) "px;"
       "left:" (.-left r) "px;"
       "width:" (.-width r) "px;"
       "height:" (.-height r) "px"))

(def ^:private overlay-style
  (str "position:fixed;top:0;left:0;width:100%;height:100%;"
       "z-index:2147483647;pointer-events:none"))

(def ^:private banner-style
  (str "position:fixed;bottom:20px;left:50%;"
       "transform:translateX(-50%);background:#1f2937;color:white;"
       "padding:12px 24px;border-radius:8px;font:14px sans-serif;"
       "box-shadow:0 4px 12px rgba(0,0,0,0.3);"
       "pointer-events:auto;z-index:2147483647"))

(def ^:private highlight-base-style
  (str "position:absolute;border:2px solid #3b82f6;"
       "background:rgba(59,130,246,0.1);transition:all 0.1s"))

(defn pick
  "Interactive element picker. Resolves with a single element info map
   (click) or an array of element info maps (Cmd/Ctrl-click to add,
   Enter to finish). Resolves with nil on Escape."
  [message]
  (when-not message
    (throw (js/Error. "pick() requires a message parameter")))
  (js/Promise.
   (fn [resolve _reject]
     (let [selections (array)
           selected-elements (js/Set.)
           overlay (.createElement js/document "div")
           highlight (.createElement js/document "div")
           banner (.createElement js/document "div")
           handlers (atom nil)
           update-banner (fn []
                           (set! (.-textContent banner)
                                 (str message
                                      " (" (.-length selections) " selected, "
                                      "Cmd/Ctrl+click to add, "
                                      "Enter to finish, "
                                      "ESC to cancel)")))
           cleanup (fn []
                     (let [{:keys [on-move on-click on-key]} @handlers]
                       (when on-move
                         (.removeEventListener js/document "mousemove" on-move true))
                       (when on-click
                         (.removeEventListener js/document "click" on-click true))
                       (when on-key
                         (.removeEventListener js/document "keydown" on-key true)))
                     (.remove overlay)
                     (.remove banner)
                     (.forEach selected-elements
                               (fn [el]
                                 (set! (.. el -style -outline) ""))))
           on-move (fn [e]
                     (let [el (.elementFromPoint js/document
                                                 (.-clientX e)
                                                 (.-clientY e))]
                       (when (and el
                                  (not (.contains overlay el))
                                  (not (.contains banner el)))
                         (set! (.. highlight -style -cssText)
                               (highlight-style (.getBoundingClientRect el))))))
           on-click (fn [e]
                      (when-not (.contains banner (.-target e))
                        (.preventDefault e)
                        (.stopPropagation e)
                        (let [el (.elementFromPoint js/document
                                                    (.-clientX e)
                                                    (.-clientY e))]
                          (when (and el
                                     (not (.contains overlay el))
                                     (not (.contains banner el)))
                            (cond
                              (or (.-metaKey e) (.-ctrlKey e))
                              (when-not (.has selected-elements el)
                                (.add selected-elements el)
                                (set! (.. el -style -outline) "3px solid #10b981")
                                (.push selections (element-info el))
                                (update-banner))

                              :else
                              (do
                                (cleanup)
                                (resolve (if (pos? (.-length selections))
                                           selections
                                           (element-info el)))))))))
           on-key (fn [e]
                    (cond
                      (= "Escape" (.-key e))
                      (do (.preventDefault e)
                          (cleanup)
                          (resolve nil))

                      (and (= "Enter" (.-key e))
                           (pos? (.-length selections)))
                      (do (.preventDefault e)
                          (cleanup)
                          (resolve selections))))]
       (reset! handlers {:on-move on-move
                         :on-click on-click
                         :on-key on-key})
       (set! (.. overlay -style -cssText) overlay-style)
       (set! (.. highlight -style -cssText) highlight-base-style)
       (.appendChild overlay highlight)
       (set! (.. banner -style -cssText) banner-style)
       (update-banner)
       (.append (.-body js/document) banner overlay)
       (.addEventListener js/document "mousemove" on-move true)
       (.addEventListener js/document "click" on-click true)
       (.addEventListener js/document "keydown" on-key true)))))

(defn ^:export install! []
  (when (undefined? (.-pick js/window))
    (set! (.-pick js/window) pick)))
