(ns babagent.config
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]))

(def default-model "claude-sonnet-4-6")

(def default-theme
  {;; ---- structural chrome ----
   :heading          {:fg {:ansi :bright-cyan} :bold true}
   :status-bar       {:fg {:ansi :bright-white}
                      :bg {:ansi256 24}
                      :border-fg {:ansi256 31}
                      :bold true
                      :padding [0 1]}
   ;; ---- message role labels ----
   :user-message     {:fg {:ansi :bright-white}
                      :bg {:ansi256 238}
                      :padding [0 2]}
   :assistant-label  {:fg {:ansi :bright-blue} :bold true}
   :system-label     {:fg {:ansi :yellow} :bold true}
   ;; ---- tool rendering ----
   :tool-header      {:fg {:ansi :bright-yellow} :bold true}
   :tool-success     {:fg {:ansi :bright-green} :bold true :bg {:ansi256 22}}
   :tool-error       {:fg {:ansi :bright-red} :bold true :bg {:ansi256 52}}})

(def default-config
  {:anthropic-api-key nil
   :model default-model
   :theme default-theme})

(defn- xdg-config-home []
  (let [config-home (System/getenv "XDG_CONFIG_HOME")]
    (if (seq config-home)
      (fs/path config-home)
      (fs/path (fs/home) ".config"))))

(defn- xdg-data-home []
  (let [data-home (System/getenv "XDG_DATA_HOME")]
    (if (seq data-home)
      (fs/path data-home)
      (fs/path (fs/home) ".local" "share"))))

(defn config-path []
  (or (some-> (System/getenv "BABAGENT_CONFIG") fs/path)
      (fs/path (xdg-config-home) "babagent" "config.edn")))

(defn session-log-path [session-cwd start-timestamp]
  (let [project-folder (-> (or session-cwd "")
                           (str/replace "/" "-")
                           (str/replace "\\" "-"))]
    (fs/path (xdg-data-home)
             "babagent"
             "sessions"
             (if (str/blank? project-folder)
               "unknown-cwd"
               project-folder)
             (str start-timestamp ".edn"))))

(defn- ensure-config-dir! []
  (fs/create-dirs (fs/parent (config-path))))

(defn- file-config []
  (let [path (config-path)]
    (if (fs/exists? path)
      (try
        (edn/read-string (slurp (str path)))
        (catch Exception _
          {}))
      {})))

(defn- env-config []
  (let [api-key (System/getenv "ANTHROPIC_API_KEY")]
    (cond-> {}
      (seq api-key) (assoc :anthropic-api-key api-key))))

(defn load-config []
  (merge default-config
         (file-config)
         (env-config)))

(defn save-config! [config]
  (ensure-config-dir!)
  (spit (str (config-path)) (pr-str config))
  config)
