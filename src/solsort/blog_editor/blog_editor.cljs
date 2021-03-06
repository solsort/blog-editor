(ns solsort.blog-editor.blog-editor
  (:require-macros
   [solsort.toolbox.macros :refer [<? except]]
   [cljs.core.async.macros :refer [go go-loop alt!]]
   [reagent.ratom :as ratom :refer  [reaction]])
  (:require
   [cljs.reader]
   [solsort.toolbox.setup]
   [solsort.toolbox.appdb :refer [db db! db-async!]]
   [solsort.toolbox.ui :refer [input select]]
   [solsort.util
    :refer
    [<ajax <seq<! js-seq load-style! put!close! canonize-string
     parse-json-or-nil log page-ready render dom->clj]]
   [reagent.core :as reagent :refer []]
   [clojure.string :as string :refer [replace split blank?]]
   [cljs.core.async :refer [>! <! chan put! take! timeout close! pipe]]))
(defn no-repos? [] (= "Not Found" (get (db [:repos-info]) "message")))

(defn <gh [method token url content]
  (let [c (chan)
        xhr (js/XMLHttpRequest.)]
    (aset xhr "onreadystatechange"
          (fn [a]
            (log 'ready-state (aget xhr "readyState"))
            (when (= 4 (aget xhr "readyState"))
              (put!close! c (JSON.parse (aget xhr "responseText"))))))
    (.open xhr method url)
    (.setRequestHeader xhr "Authorization" (str "token " token))
    (.send xhr content)
    c))
(defn <gh-put [token url content]
  (<gh (if content "PUT" "GET") token url content))

(defn <github-write [token repos path content]
  (go
    (let [url (str "https://api.github.com/repos/" repos "/contents/" path)
          result (log (<! (<gh-put token url nil)))
          empty (= "Not Found" (aget result "message"))
          sha (aget result "sha")]
      (<! (<gh-put
           token
           url
           (log (js/JSON.stringify
                 (clj->js {:message (str "Save `/" path "` via http://blog-editor.solsort.com/")
                           :sha sha
                           :content (js/btoa content)})))))
      content)))
(defn encode-utf8 [s] (js/unescape (js/encodeURIComponent s)))
(defn decode-utf8 [s] (js/decodeURIComponent (js/escape s)))
(defn <gh-write [path content]
  (<github-write (db [:user :auth "token"]) (db [:repos]) path (encode-utf8 content)))

(defn gh-url [path] (str "https://api.github.com/repos/" (db [:repos]) "/contents/" path))
(defn gh-token [] (db [:user :auth "token"]))
(defn <gh-delete [path]
  (go
    (let [token (gh-token)
          result (log (<! (<gh "GET" token (gh-url path) nil)))
          empty (= "Not Found" (aget result "message"))
          sha (aget result "sha")]
      (<! (<gh "DELETE" token (gh-url path)
           (log (js/JSON.stringify
                 (clj->js {:message (str "Delete `/" path "` via http://blog-editor.solsort.com/")
                           :sha sha
                           }))))))))
(defn current-is-draft? []
  (not (clojure.string/starts-with? (db [:current :path] "") "_posts")))
(defn <gh-get [endpoint]
  (<ajax (str "https://api.github.com/" endpoint
              "?access_token=" (db [:user :auth "token"]))
         :credentials false))
(defn hide-editor! [] (aset js/editorparent.style "height" "0px"))
(defn show-editor! [] (aset js/editorparent.style "height" "auto"))
(defn set-editor-content! [s] (js/CKEDITOR.instances.editor.setData s))
(defn get-editor-content [] (js/CKEDITOR.instances.editor.getData))
(defn <load-from-github [file]
  (log 'load-from-github file
       (= "new draft .html" (get file :path)))
  (go
    (if (= "new draft .html" (get file :path))
      (do
        (db! [:current]
             {:body ""
              :path ""
              :sha ""
              :header
              {"date" (.toISOString (js/Date.))
               "layout" "post"
               "title" ""}})
        (set-editor-content! ""))
      (let [o (clojure.walk/keywordize-keys
               (<? (<gh-get (str "repos/" (db [:repos]) "/contents/" (:path file)))))
            content (decode-utf8 (js/atob (:content o)))
            [header body] (clojure.string/split content #"\n---\w*\n" 2)
            header (into {} (map #(clojure.string/split % #":\w*" 2)
                                 (rest (clojure.string/split header "\n"))))]
        (db! [:current]
             {:header header
              :body body
              :sha (:sha o)
              :path (:path file)})
        (set-editor-content! body)))))
(defn file-info [o]
  {:draft (not (re-find #"^_posts" (o "path")))
   :sha (get o "sha")
   :date (.slice (o "name") 0 10)
   :path (o "path")
   :title (.replace (.slice (o "name") 11 -5) (js/RegExp "-" "g") " ")})
(defn <list-repos-files [path]
  (go
    (except
     (<? (<gh-get (str "repos/" (db [:repos]) "/contents/" path)))
     [])))
(defn <update-files []
  (go
    (db!
     [:files]
     (map file-info
          (filter #(re-find #"[.]html$" (get % "path"))
                  (concat
                   [{"sha" ""
                     "path" "new draft .html"
                     "name" (str (.slice (.toISOString (js/Date.)) 0 10) " (new post).html")}]
                   (<? (<list-repos-files "_drafts"))
                   (<? (<list-repos-files "_posts"))))))))
(defn command:delete []
  (go
    (db! [:ui :deleting] true)
    (<! (<gh-delete (db [:current :path])))
    (<? (<update-files))
    (db! [:ui :deleting])))
(defn update-filename []
  (let [title (db [:current :header "title"])]
    (log (db! [:current :path]
             (str
              (if (current-is-draft?)
                "_drafts/"
                "_posts/")
              (canonize-string
               (.trim (str (.slice (.trim (db [:current :header "date"])) 0 10)
                           " "
                           title))
               )
              ".html"
              )))))
  (js/console.log "update-filename")
(log (db))
(defn command:update-filename[]
  (update-filename))
(defn command:save []
  (go
    (db! [:ui :saving] true)
    (<? (<gh-write
         (db [:current :path])
         (str
          "---\n"
          (clojure.string/join (map (fn [[k v]] (str k ": " v "\n")) (db [:current :header])))
          "---\n"
          (get-editor-content))))
    (<? (<update-files))
    (db! [:ui :saving] false)))
(defn command:un-publish []
  (when-not (db [:ui :publishing])
    (go
      (db! [:ui :publishing] true)
      (<! (command:delete))
      ;;   (<! (<gh-delete (db [:current :path])))
      (let [p (db [:current :path])]
        (db! [:current :path]
             (if (current-is-draft?)
               (.replace p "draft" "post")
               (.replace p "post" "draft"))))
      ;;   (<! (<gh-delete (db [:current :path])))
      (<? (command:save))
      (<? (<update-files))
      (db! [:ui :publishing]))))
(defn ui:welcome []
  (hide-editor!)
  [:div.ui.container
   [:h1 "Blog Editor"]

   [:p
    "This is a simple wysiwyg editor to edit posts/drafts of "
    [:a {:href "https://pages.github.com"} "GitHub Pages"]]
   [:div.ui.form
    [:div.field
     [:label "Organisation/repository (leave blank for default)"]
     [:input {:type :text
              :placeholder "organisation/repository"
              :on-change (fn [o]
                           (db! [:repos] (.-value (.-target o)))
                           (js/localStorage.setItem "blog-editor-repos" (db [:repos])))
              :value (db [:repos])}]]]
   [:p]
   [:div
    [:a.primary.ui.button {:href
                           (str
                            "https://mubackend.solsort.com/auth/github?url="
                            js/location.origin
                            js/location.pathname
                            "&scope=public_repo")}
     "Login to GitHub"]]])
(defn ui:file-list []
  (into
   [:select
    {:style {:padding-left 0
             :padding-top 7
             :border-radius 5
             :padding-bottom 7
             :background :white
             :padding-right 0}
      ;:onChange #(db! [:selected-file] (cljs.reader/read-string (.-value (.-target %1))))
     :onChange #(let [file (cljs.reader/read-string (.-value (.-target %1)))]
                  (log 'here file)
                  (<load-from-github file)
                  (db! [:selected-file] file))}]
   (for [file (reverse (sort-by (fn [f] [(:draft f) (:date f)]) (db [:files])))]
     (let [v (prn-str file)]
       [:option {:style {:padding-left 0
                         :padding-right 0}
                 :key v :value v} (str (if (:draft file) "\u00a0\u00a0\u00a0\u00a0" "\u2713") (:date file) " \u00a0 " (:title file))]))))
(defn ui:repos-name []
  [:span
   [:code (db [:repos])] " "
   [:button.secondary.small.ui.button {:on-click #(js/location.reload)}
    "Change repository"]])
(defn ui:command-bar []
  [:span.ui.basic.buttons
   [:button.small.ui.button
    {:class (if (db [:ui :deleting]) "loading" "")
     :on-click command:delete}
    "delete"]
     [:button.small.ui.button
       {:class (if (db [:ui :publishing]) "loading" "")
       :on-click command:un-publish}
      (if (current-is-draft?) "" "un" )
      "publish"]
     
   ])
(defn ui:filename-save []
  [:div.field
   [:label "Filename"]
   [:div.ui.action.input
    [:input {:style {:text-align :center}
             :read-only true
             :value (db [:current :path])}]
    [:button.primary.ui.button
     {:class (if (db [:ui :saving]) "loading" "")
      :on-click command:save}
     "Save"]]])
(defn ui:date-title []
  [:div.fields
   [:div.field [:label "Title"] [input {:db [:current :header "title"]}]]
   [:div.field [:label "Date"] [input {:db [:current :header "date"]}]]
   [:div.field [:label "\u00a0"] [:button.fluid.ui.button
                                  {:on-click command:update-filename}
                                  "Update filename"]]])
(defn ui:file-settings []
  [:div.ui.form
   [ui:date-title]
   [ui:filename-save]])
(defn ui:app []
  (show-editor!)
  [:div
   [ui:repos-name]
   [:p]
   [ui:file-list] " "
   [ui:command-bar]
   [:p]
   [ui:file-settings]])
(defn ui:about-create []
  (hide-editor!)
  [:div
   [ui:repos-name]
   [:h1 "Repository does not exist."]
   [:p "Make sure you wrote the correct repository name, or choose change repository."]
   [:p "A sample repository name that you can try out is: "
    [:a {:on-click (fn [] (js/localStorage.setItem "blog-editor-repos" "rasmuserik/writings") (js/location.reload))} "rasmuserik/writings"]]])
(defn ui:main []
  [:div
   (if (= -1 (.indexOf js/location.hash "muBackendLoginToken"))
     [ui:welcome]
     (if (no-repos?)
       [ui:about-create]
       [ui:app]))])
(let [pos (.indexOf js/location.hash "muBackendLoginToken=")]
  (db! [:repos] (or (js/localStorage.getItem "blog-editor-repos") "rasmuserik/writings"))
  (when (not= -1 pos)
    (go
      (when (not (db [:user :auth]))
        (try
          (db! [:user :auth]
               (<? (<ajax (str
                           "https://mubackend.solsort.com/auth/result/"
                           (.slice location.hash (+ pos 20)))
                          :credentials false)))
          (catch js/Error e
            (aset js/location "hash" "")
            (js/location.reload))))
      (when (not (db [:user :info]))
        (db! [:user :info] (<? (<gh-get "user"))))
      (when (empty? (db [:repos]))
        (db! [:repos] (str (db [:user :info "login"]) ".github.io")))
      (when (not (re-find #"/" (db [:repos] "")))
        (db! [:repos] (str (db [:user :info "login"]) "/" (db [:repos]))))
      (when (empty? (db [:repos-info]))
        (db! [:repos-info] (<? (<gh-get (str "repos/" (db [:repos]))))))
      (when
       (and
        (db [:repos-info "id"])
        (not (db [:files])))
        (<? (<update-files))
        (<? (<load-from-github (first (reverse (sort-by :date (db [:files])))))))))
  (render [ui:main]))
