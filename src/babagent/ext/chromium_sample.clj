(ns babagent.ext.chromium-sample
  (:require
   [babagent.extensions :as ext]
   [babagent.tools :as tools]
   [cheshire.core :as json]
   [clojure.string :as str]))

(defn- pget
  ([m k] (pget m k nil))
  ([m k default]
   (let [v (or (get m k) (get m (name k)))]
     (if (nil? v) default v))))

(defn- parse-json [s]
  (try
    (when (string? s)
      (json/parse-string s true))
    (catch Exception _
      nil)))

(defn- browser-page-info-tool [input]
  (let [base-result (tools/execute-tool! "browser_eval"
                                         {:code "({title: document.title, url: window.location.href})"})]
    (if-not (:ok base-result)
      base-result
      (let [payload (parse-json (:content base-result))
            title (or (:title payload) "(unknown)")
            url (or (:url payload) "(unknown)")
            selector (some-> (pget input :selector)
                             str/trim
                             not-empty)
            selector-result (when selector
                              (tools/execute-tool! "browser_inspect"
                                                   {:selector selector
                                                    :action "text"
                                                    :waitFor false}))
            lines (cond-> [(str "Title: " title)
                           (str "URL: " url)]
                    selector
                    (conj (if (:ok selector-result)
                            (str selector ": " (:content selector-result))
                            (str selector ": <error> " (:error selector-result))))) ]
        {:ok true
         :content (str/join "\n" lines)}))))

(defn register! []
  (ext/register-tool!
   {:name "browser_page_info"
    :description "Sample extension tool: returns current page title and URL, and optional selector text."
    :input_schema {:type "object"
                   :properties {:selector {:type "string"
                                           :description "Optional CSS selector to include text for."}}}
    :handler browser-page-info-tool})
  nil)
