(ns babagent.ext.chromium
  (:require
   [babashka.fs :as fs]
   [babashka.http-client :as http]
   [babagent.extensions :as ext]
   [cheshire.core :as json]
   [clojure.string :as str]))

(def cdp-url "http://localhost:9222")
(def connect-timeout-ms 5000)
(def cdp-call-timeout-ms 10000)
(def wait-poll-ms 120)

(defonce chromium-state (atom {:active-target-id nil}))
(defonce request-counter (atom 0))

(defn- next-request-id []
  (swap! request-counter inc))

(defn- pget
  ([m k] (pget m k nil))
  ([m k default]
   (let [v (or (get m k) (get m (name k)))]
     (if (nil? v) default v))))

(defn- json-parse [s]
  (cond
    (nil? s) nil
    (string? s) (json/parse-string s true)
    :else s))

(defn- fetch-json [path]
  (let [resp (http/get (str cdp-url path) {:throw false})
        status (:status resp)
        body (json-parse (:body resp))]
    (if (<= 200 status 299)
      body
      (throw (ex-info "Chromium endpoint request failed"
                      {:path path
                       :status status
                       :body body})))))

(defn- page-target? [target]
  (and (= "page" (pget target :type))
       (string? (pget target :webSocketDebuggerUrl))))

(defn- list-page-targets []
  (->> (fetch-json "/json/list")
       (filter page-target?)
       vec))

(defn- set-active-target-id! [target-id]
  (swap! chromium-state assoc :active-target-id target-id)
  target-id)

(defn- resolve-active-target []
  (let [targets (list-page-targets)
        target-id (:active-target-id @chromium-state)
        target (or (some #(when (= target-id (pget % :id)) %) targets)
                   (last targets))]
    (when-not target
      (throw (ex-info "No active tab found"
                      {:cdp-url cdp-url
                       :hint "Start Chromium with --remote-debugging-port=9222"})))
    (set-active-target-id! (pget target :id))
    target))

(defn- create-target! [url]
  (let [target (fetch-json (str "/json/new?" (java.net.URLEncoder/encode (or url "about:blank") "UTF-8")))]
    (set-active-target-id! (pget target :id))
    target))

(defn- completed-future []
  (java.util.concurrent.CompletableFuture/completedFuture nil))

(defn- connect-websocket [ws-url]
  (let [messages (java.util.concurrent.LinkedBlockingQueue.)
        chunk (StringBuilder.)
        listener (reify java.net.http.WebSocket$Listener
                   (onOpen [_ ws]
                     (.request ws Long/MAX_VALUE))
                   (onText [_ ws data last]
                     (.append chunk (str data))
                     (when last
                       (.put messages (.toString chunk))
                       (.setLength chunk 0))
                     (.request ws 1)
                     (completed-future))
                   (onBinary [_ ws _data _last]
                     (.request ws 1)
                     (completed-future))
                   (onPing [_ ws _message]
                     (.request ws 1)
                     (completed-future))
                   (onPong [_ ws _message]
                     (.request ws 1)
                     (completed-future))
                   (onClose [_ _ws status reason]
                     (.put messages {:closed true
                                     :status status
                                     :reason (str reason)})
                     (completed-future))
                   (onError [_ _ws err]
                     (.put messages {:error err})))
        client (.build (doto (java.net.http.HttpClient/newBuilder)
                         (.connectTimeout (java.time.Duration/ofMillis connect-timeout-ms))))
        ws-future (.buildAsync (.newWebSocketBuilder client)
                               (java.net.URI/create ws-url)
                               listener)
        ws (.get ws-future connect-timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
    {:ws ws
     :messages messages}))

(defn- close-websocket! [ws]
  (try
    (.sendClose ws java.net.http.WebSocket/NORMAL_CLOSURE "done")
    (catch Exception _ nil))
  nil)

(defn- with-target-websocket* [target f]
  (let [{:keys [ws messages]} (connect-websocket (pget target :webSocketDebuggerUrl))]
    (try
      (f ws messages)
      (finally
        (close-websocket! ws)))))

(defn- cdp-call [ws messages method params]
  (let [request-id (next-request-id)
        payload (json/generate-string
                 (cond-> {:id request-id
                          :method method}
                   (some? params) (assoc :params params)))]
    (.join (.sendText ws payload true))
    (loop []
      (let [msg (.poll messages cdp-call-timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)]
        (when-not msg
          (throw (ex-info "CDP call timed out"
                          {:method method
                           :timeout-ms cdp-call-timeout-ms})))
        (cond
          (map? msg)
          (if-let [err (:error msg)]
            (throw (ex-info "WebSocket error" {:method method :error err}))
            (throw (ex-info "WebSocket closed"
                            {:method method
                             :close msg})))

          :else
          (let [decoded (json/parse-string msg true)]
            (if (= request-id (:id decoded))
              (if-let [error (:error decoded)]
                (throw (ex-info "CDP call failed"
                                {:method method
                                 :error error}))
                (:result decoded))
              (recur))))))))

(defn- runtime-eval
  ([ws messages expression]
   (runtime-eval ws messages expression {}))
  ([ws messages expression opts]
   (let [result (cdp-call ws messages
                          "Runtime.evaluate"
                          (merge {:expression expression
                                  :awaitPromise true
                                  :returnByValue true}
                                 opts))]
     (when-let [details (:exceptionDetails result)]
       (throw (ex-info "JavaScript evaluation failed"
                       {:exception details
                        :expression expression})))
     (:result result))))

(defn- runtime-value [runtime-result]
  (cond
    (contains? runtime-result :value) (:value runtime-result)
    (contains? runtime-result :unserializableValue) (:unserializableValue runtime-result)
    :else (:description runtime-result)))

(defn- js-string [s]
  (json/generate-string (str s)))

(defn- wait-until [pred timeout-ms message]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (if (pred)
        true
        (if (>= (System/currentTimeMillis) deadline)
          (throw (ex-info message {:timeout-ms timeout-ms}))
          (do
            (Thread/sleep wait-poll-ms)
            (recur)))))))

(defn- wait-for-ready-state! [ws messages]
  (wait-until
   (fn []
     (let [state (runtime-value (runtime-eval ws messages "document.readyState"))]
       (contains? #{"interactive" "complete"} state)))
   15000
   "Timed out waiting for page to load"))

(defn- wait-for-selector! [ws messages selector timeout-ms]
  (let [selector-js (js-string selector)
        expression (str "(function(){"
                        "const el=document.querySelector(" selector-js ");"
                        "if(!el){return false;}"
                        "const style=window.getComputedStyle(el);"
                        "const rect=el.getBoundingClientRect();"
                        "return style.display !== 'none'"
                        " && style.visibility !== 'hidden'"
                        " && style.opacity !== '0'"
                        " && rect.width > 0"
                        " && rect.height > 0;"
                        "})()")]
    (wait-until
     (fn []
       (true? (runtime-value (runtime-eval ws messages expression))))
     timeout-ms
     (str "Element not found or not visible: " selector))))

(defn- navigate! [ws messages url]
  (cdp-call ws messages "Page.enable" {})
  (cdp-call ws messages "Runtime.enable" {})
  (cdp-call ws messages "Page.navigate" {:url url})
  (wait-for-ready-state! ws messages)
  {:title (runtime-value (runtime-eval ws messages "document.title"))
   :url (runtime-value (runtime-eval ws messages "window.location.href"))})

(defn- to-pretty-json [value]
  (json/generate-string value {:pretty true}))

(defn- browser-nav-tool [input]
  (let [url (pget input :url)
        new-tab? (true? (pget input :newTab))]
    (when (str/blank? url)
      (throw (ex-info "Missing required field: url" {})))
    (let [target (if new-tab?
                   (create-target! url)
                   (resolve-active-target))]
      (with-target-websocket* target
        (fn [ws messages]
          (let [{:keys [title url]} (if new-tab?
                                      (do
                                        (cdp-call ws messages "Runtime.enable" {})
                                        (wait-for-ready-state! ws messages)
                                        {:title (runtime-value (runtime-eval ws messages "document.title"))
                                         :url (runtime-value (runtime-eval ws messages "window.location.href"))})
                                      (navigate! ws messages url))]
            {:ok true
             :content (str (if new-tab? "Opened" "Navigated to") ": " title "\nURL: " url)}))))))

(defn- browser-eval-tool [input]
  (let [code (pget input :code)]
    (when (str/blank? code)
      (throw (ex-info "Missing required field: code" {})))
    (let [target (resolve-active-target)
          expression (str "(async () => {"
                          "const code = " (js-string code) ";"
                          "const AsyncFunction = Object.getPrototypeOf(async function(){}).constructor;"
                          "return await new AsyncFunction(`return (${code})`)();"
                          "})()")]
      (with-target-websocket* target
        (fn [ws messages]
          (let [result (runtime-eval ws messages expression)
                value (runtime-value result)
                text (cond
                       (= "undefined" (:type result)) "undefined"
                       (nil? value) "null"
                       (or (map? value) (vector? value)) (to-pretty-json value)
                       :else (str value))]
            {:ok true
             :content text}))))))

(defn- browser-tabs-tool [_input]
  (let [targets (list-page-targets)]
    (when (empty? targets)
      (throw (ex-info "No active tab found" {})))
    (let [window-map (reduce
                      (fn [acc target]
                        (with-target-websocket* target
                          (fn [ws messages]
                            (let [result (cdp-call ws messages
                                                   "Browser.getWindowForTarget"
                                                   {:targetId (pget target :id)})
                                  window-id (or (:windowId result) 0)
                                  tab {:title (or (pget target :title) "")
                                       :url (or (pget target :url) "")}]
                              (update acc window-id (fnil conj []) tab)))))
                      {}
                      targets)
          lines (->> window-map
                     (sort-by key)
                     (map-indexed
                      (fn [idx [window-id tabs]]
                        (str "Window " (inc idx) " (id: " window-id "):\n"
                             (->> tabs
                                  (map-indexed
                                   (fn [tab-idx tab]
                                     (str "  " tab-idx ": " (:title tab) "\n"
                                          "     " (:url tab))))
                                  (str/join "\n")))))
                     (str/join "\n"))]
      {:ok true
       :content lines})))

(defn- write-base64-png! [base64-data]
  (let [filename (str "screenshot-"
                      (str/replace (str (java.time.Instant/now)) #"[:.]" "-")
                      ".png")
        path (fs/path (System/getProperty "java.io.tmpdir") filename)
        bytes (.decode (java.util.Base64/getDecoder) ^String base64-data)]
    (with-open [out (java.io.FileOutputStream. (str path))]
      (.write out bytes))
    (str path)))

(defn- browser-screenshot-tool [input]
  (let [url (pget input :url)
        wait-for (pget input :waitFor)
        wait-timeout-sec (or (pget input :waitTimeout) 10)
        wait-timeout-ms (long (* 1000 (max 1 (long wait-timeout-sec))))
        target (resolve-active-target)]
    (with-target-websocket* target
      (fn [ws messages]
        (cdp-call ws messages "Page.enable" {})
        (cdp-call ws messages "Runtime.enable" {})
        (when (seq url)
          (navigate! ws messages url))
        (when (seq wait-for)
          (wait-for-selector! ws messages wait-for wait-timeout-ms))
        (let [capture (cdp-call ws messages "Page.captureScreenshot" {:format "png"})
              path (write-base64-png! (:data capture))
              title (runtime-value (runtime-eval ws messages "document.title"))
              current-url (runtime-value (runtime-eval ws messages "window.location.href"))]
          {:ok true
           :content (str "Screenshot of \"" title "\" at " current-url "\nSaved to: " path)})))))

(defn- browser-inspect-tool [input]
  (let [selector (pget input :selector)
        action (or (pget input :action) "text")
        attribute (pget input :attribute)
        url (pget input :url)
        wait-for? (not= false (pget input :waitFor true))
        target (resolve-active-target)]
    (when (str/blank? selector)
      (throw (ex-info "Missing required field: selector" {})))
    (with-target-websocket* target
      (fn [ws messages]
        (cdp-call ws messages "Runtime.enable" {})
        (cdp-call ws messages "Page.enable" {})
        (when (seq url)
          (navigate! ws messages url))
        (when (and wait-for? (not= action "exists"))
          (try
            (wait-for-selector! ws messages selector 10000)
            (catch Exception _
              (when-not (= action "count")
                (throw (ex-info (str "Element not found or not visible: \"" selector "\"") {}))))))
        (let [selector-js (js-string selector)
              attr-js (js-string attribute)
              expression
              (case action
                "text" (str "(function(){const el=document.querySelector(" selector-js ");"
                            "if(!el){return '__BB_NOT_FOUND__';}"
                            "return (el.textContent || '').trim();})()")
                "html" (str "(function(){const el=document.querySelector(" selector-js ");"
                            "if(!el){return '__BB_NOT_FOUND__';}"
                            "return el.innerHTML;})()")
                "attr" (do
                          (when (str/blank? attribute)
                            (throw (ex-info "The \"attr\" action requires the \"attribute\" parameter." {})))
                          (str "(function(){const el=document.querySelector(" selector-js ");"
                               "if(!el){return '__BB_NOT_FOUND__';}"
                               "const v = el.getAttribute(" attr-js ");"
                               "return v === null ? '(null)' : v;})()"))
                "count" (str "document.querySelectorAll(" selector-js ").length")
                "visible" (str "(function(){const el=document.querySelector(" selector-js ");"
                               "if(!el){return false;}"
                               "const style=getComputedStyle(el);"
                               "return style.display !== 'none'"
                               " && style.visibility !== 'hidden'"
                               " && style.opacity !== '0';})()")
                "exists" (str "document.querySelector(" selector-js ") !== null")
                (str "(function(){const el=document.querySelector(" selector-js ");"
                     "if(!el){return '__BB_NOT_FOUND__';}"
                     "return (el.textContent || '').trim();})()"))
              value (runtime-value (runtime-eval ws messages expression))]
          (when (and (string? value)
                     (= value "__BB_NOT_FOUND__")
                     (not (#{"count" "exists"} action)))
            (throw (ex-info (str "Element not found: " selector) {})))
          {:ok true
           :content (str value)})))))

(defn- browser-cookies-tool [_input]
  (let [target (resolve-active-target)]
    (with-target-websocket* target
      (fn [ws messages]
        (cdp-call ws messages "Network.enable" {})
        (let [cookies (or (:cookies (cdp-call ws messages "Network.getCookies" {})) [])
              lines (map (fn [cookie]
                           (str (pget cookie :name) ": " (pget cookie :value) "\n"
                                "  domain: " (pget cookie :domain)
                                "  path: " (pget cookie :path)
                                "  httpOnly: " (boolean (pget cookie :httpOnly))
                                "  secure: " (boolean (pget cookie :secure))))
                         cookies)]
          {:ok true
           :content (if (seq lines)
                      (str/join "\n\n" lines)
                      "(no cookies)")})))))

(def pick-helper-js-file
  (fs/path (fs/cwd) "resources" "public" "js" "chromium-pick.js"))

(defonce pick-helper-js
  (delay
    (let [path (str pick-helper-js-file)]
      (when-not (fs/exists? path)
        (throw (ex-info "Missing compiled pick helper JavaScript"
                        {:path path
                         :hint "Run: npm install && npm run build:pick"})))
      (slurp path))))

(defn- browser-pick-tool [input]
  (let [message (pget input :message)
        target (resolve-active-target)]
    (when (str/blank? message)
      (throw (ex-info "Missing required field: message" {})))
    (with-target-websocket* target
      (fn [ws messages]
        (cdp-call ws messages "Runtime.enable" {})
        (let [expression (str "(async () => {"
                              "if (!window.pick) {\n"
                              (force pick-helper-js)
                              "\n}"
                              "return await window.pick(" (js-string message) ");"
                              "})()")
              value (runtime-value (runtime-eval ws messages expression))]
          (if (nil? value)
            {:ok true
             :content "Cancelled by user"}
            {:ok true
             :content (if (or (map? value) (vector? value))
                        (to-pretty-json value)
                        (str value))}))))))

(defn register! []
  (ext/register-tool!
   {:name "browser_nav"
    :description "Navigate the browser to a URL. Connects to Chromium on localhost:9222 (must be started with --remote-debugging-port=9222)."
    :input_schema {:type "object"
                   :properties {:url {:type "string"
                                      :description "URL to navigate to"}
                                :newTab {:type "boolean"
                                         :description "Open in a new tab instead of reusing the active one"}}
                   :required ["url"]}
    :handler browser-nav-tool})

  (ext/register-tool!
   {:name "browser_eval"
    :description "Evaluate JavaScript in the active browser tab. Code runs in an async context. Return values are serialized as text."
    :input_schema {:type "object"
                   :properties {:code {:type "string"
                                       :description "JavaScript code to evaluate. Runs in async context."}}
                   :required ["code"]}
    :handler browser-eval-tool})

  (ext/register-tool!
   {:name "browser_tabs"
    :description "List all open browser tabs, grouped by window."
    :input_schema {:type "object"
                   :properties {}}
    :handler browser-tabs-tool})

  (ext/register-tool!
   {:name "browser_screenshot"
    :description "Take a screenshot of the current browser viewport. Optionally navigates to a URL first and waits for a CSS selector."
    :input_schema {:type "object"
                   :properties {:url {:type "string"
                                      :description "URL to navigate to before taking the screenshot."}
                                :waitFor {:type "string"
                                          :description "CSS selector to wait for before taking the screenshot."}
                                :waitTimeout {:type "number"
                                              :description "Timeout in seconds for waitFor selector. Default: 10."}}}
    :handler browser-screenshot-tool})

  (ext/register-tool!
   {:name "browser_inspect"
    :description "Extract text content, attributes, or element state from the current page using CSS selectors. Navigates to URL first if provided."
    :input_schema {:type "object"
                   :properties {:selector {:type "string"
                                           :description "CSS selector to query"}
                                :action {:type "string"
                                         :description "One of: text, html, attr, count, visible, exists"}
                                :attribute {:type "string"
                                            :description "Attribute name to extract when action is attr."}
                                :url {:type "string"
                                      :description "URL to navigate to first."}
                                :waitFor {:type "boolean"
                                          :description "Wait for selector to be visible before inspecting. Default: true."}}
                   :required ["selector"]}
    :handler browser-inspect-tool})

  (ext/register-tool!
   {:name "browser_cookies"
    :description "Display all cookies for the current browser tab, including domain, path, httpOnly and secure flags."
    :input_schema {:type "object"
                   :properties {}}
    :handler browser-cookies-tool})

  (ext/register-tool!
   {:name "browser_pick"
    :description "Launch an interactive element picker in the browser. The user clicks elements to select them (Cmd/Ctrl+Click for multiple, Enter to finish, Esc to cancel)."
    :input_schema {:type "object"
                   :properties {:message {:type "string"
                                          :description "Prompt message shown to the user in the browser overlay"}}
                   :required ["message"]}
    :handler browser-pick-tool})

  nil)
