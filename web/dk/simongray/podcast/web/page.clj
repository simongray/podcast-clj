(ns dk.simongray.podcast.web.page
  "The pages of the feed inspector as Hiccup: data in, markup out, no I/O.
  Rendered to strings by Replicant in the service namespace."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.time :as time]
            [dk.simongray.podcast.validate :as validate]
            [dk.simongray.podcast.resolve :as resolve]))

(def css
  "body{font:15px/1.45 system-ui,sans-serif;margin:0;color:#222;background:#fafafa}
   main{max-width:1100px;margin:0 auto;padding:1rem 1.5rem 3rem}
   header{display:flex;gap:1rem;align-items:center}
   header img{width:96px;height:96px;border-radius:8px;object-fit:cover}
   h1{margin:.2rem 0;font-size:1.5rem} h2{margin-top:2rem;border-bottom:1px solid #ddd;padding-bottom:.2rem}
   table{border-collapse:collapse;width:100%;font-size:14px}
   th,td{text-align:left;vertical-align:top;padding:.3rem .5rem;border-bottom:1px solid #eee}
   th{background:#f0f0f0} code,pre{font:13px/1.4 ui-monospace,Menlo,monospace}
   pre{background:#f4f4f4;padding:.6rem;overflow:auto;max-height:24rem;white-space:pre-wrap}
   .error{color:#b00020} .warning{color:#a15c00} .info{color:#555}
   .muted{color:#777} .badge{display:inline-block;padding:0 .4rem;border-radius:4px;background:#e8e8e8;margin-right:.3rem;font-size:12px}
   nav a{margin-right:1rem} form input[type=url]{width:60%;padding:.4rem} form button{padding:.4rem .8rem}
   ul.compact{margin:0;padding-left:1.2rem}")

(defn layout
  "The page skeleton around `body`, titled `title`."
  [title & body]
  [:html {:lang "en"}
   [:head
    [:meta {:charset "utf-8"}]
    [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
    [:title (str title " · podcast inspector")]
    [:style css]]
   [:body
    (into [:main
           [:nav [:a {:href "/"} "Inspector"]
            [:span.muted "dk.simongray/podcast reference implementation"]]]
          body)]])

(defn- pretty
  [x]
  (str/trimr (with-out-str (pprint/pprint x))))

(defn- feed-href
  [route url & [params]]
  (str route "?url=" (java.net.URLEncoder/encode (str url) "UTF-8")
       (apply str (for [[k v] params :when v] (str "&" (name k) "=" v)))))

;;;; Home

(defn home
  "The landing page: a URL form and the corpus, `corpus` being a seq of
  `[name url]`."
  [corpus]
  (layout "Home"
          [:h1 "Podcast feed inspector"]
          [:p "Parse any RSS feed with the library, see what it understood, what it had to forgive, and how the feed measures up to the standards."]
          [:form {:method "get" :action "/feed"}
           [:input {:type "url" :name "url" :placeholder "https://example.com/feed.xml" :required true}]
           " "
           [:button {:type "submit"} "Inspect"]]
          (when (seq corpus)
            (list [:h2 "Corpus"]
                  [:p.muted "Real feeds from the hosting platforms the library is tested against."]
                  [:ul.compact
                   (for [[name url] corpus]
                     [:li [:a {:href (str "/corpus/" name)} name] " " [:span.muted url]])]))))

;;;; Findings

(defn- level-class
  [level]
  (name (or level :info)))

(defn findings-table
  "A table of report or validation `findings`, grouped by id with counts and
  one example each."
  [findings]
  (if (empty? findings)
    [:p.muted "None."]
    [:table
     [:thead [:tr [:th "Level"] [:th "Finding"] [:th "Count"] [:th "Example"]]]
     [:tbody
      (for [[id group] (sort-by (comp str key) (group-by :id findings))
            :let [{:keys [level message] :as example} (first group)]]
        [:tr
         [:td {:class (level-class level)} (name (or level :info))]
         [:td [:code (str id)]]
         [:td (count group)]
         [:td (or message
                  (str/join ", " (for [[k v] (dissoc example :id :level :message)]
                                   (str (name k) "=" (pr-str v)))))]])]]))

;;;; The feed

(defn- kv-table
  "A table of the entries of `m`, values pretty-printed."
  [m]
  [:table
   [:tbody
    (for [[k v] (sort-by (comp str key) m)]
      [:tr [:th [:code (str k)]] [:td [:pre (pretty v)]]])]])

(defn- inst-str
  [x]
  (if (inst? x) (time/format-rfc2822 x) (str x)))

(defn- numbering-str
  "S2E3-style label for the `numbering` of an item, empty without numbers."
  [{:keys [season episode episode-display type]}]
  (str (when season (str "S" season))
       (when (or episode episode-display) (str "E" (or episode-display episode)))
       (when-not (= "full" type) (str " " type))))

(defn- item-row
  "One row of the items table, showing what an app would show: everything
  here comes through dk.simongray.podcast.resolve."
  [feed i item]
  (let [{:keys [url type length]} (resolve/enclosure item)
        alternatives             (count (resolve/enclosures item))
        people                   (resolve/people feed item)
        own-people?              (seq (:podcast/people item))]
    [:tr
     [:td (inc i)]
     [:td [:strong (or (resolve/title item) "(untitled)")]
      (when-let [n (not-empty (numbering-str (resolve/numbering item)))]
        [:span.badge n])
      [:br] [:span.muted (inst-str (resolve/published item))]
      (when-let [d (resolve/duration item)]
        [:span.muted (str " · " (time/format-clock d))])
      (when (resolve/explicit? feed item)
        [:span.muted " · explicit"])]
     [:td (when url
            [:span [:a {:href url} (or type "media")]
             (when (number? length) [:span.muted (str " · " (quot length 1000000) " MB")])
             (when (> alternatives 1) [:span.muted (str " · " alternatives " sources")])])]
     [:td (concat
           (when-let [t (resolve/transcript item)] [[:span.badge (str "transcript " (:type t))]])
           (when (resolve/chapters-url item) [[:span.badge "chapters"]])
           (when (resolve/embedded-chapters item) [[:span.badge "embedded chapters"]])
           (for [p people] [:span.badge {:class (when-not own-people? "muted")} (str (:role p) ": " (:name p))])
           (when (resolve/value feed item) [[:span.badge "value"]])
           (when (seq (:podcast/soundbites item)) [[:span.badge "soundbites"]])
           (when (seq (:podcast/social-interacts item)) [[:span.badge "social"]])
           (when-let [art (resolve/artwork item)] [[:span.badge [:a {:href art} "artwork"]]])
           (for [[tag] (:xml/unknown item)] [:span.badge.muted (str tag)]))]]))

(defn feed-page
  "The inspector page for `feed`, fetched from `url`, with its parse
  `report`, validated against `profile`."
  [{:keys [url feed report profile status]}]
  (let [items (:rss/items feed)
        validation (validate/validate feed profile)]
    (layout (or (resolve/title feed) url)
            [:header
             (when-let [img (resolve/artwork feed)]
               [:img {:src img :alt ""}])
             [:div
              [:h1 (or (resolve/title feed) "(untitled)")]
              [:div.muted (resolve/author feed) " · " (:rss/language feed)
               " · medium " (resolve/medium feed)
               " · " (count items) " items"
               (when-let [live (seq (:podcast/live-items feed))] (str " · " (count live) " live"))
               (when (resolve/explicit? feed) " · explicit")
               (when (resolve/complete? feed) " · complete")
               (when (resolve/locked? feed) " · locked")]
              (when-let [paths (seq (resolve/categories feed))]
                [:div.muted (str/join " · " (map #(str/join " › " %) paths))])
              [:div.muted "guid " [:code (or (feed/feed-guid feed url) "?")]
               (when-not (:podcast/guid feed) [:span.muted " (computed from the URL)"])]
              [:div.muted [:a {:href url} url] (when status (str " · HTTP " status))]]]
            [:p
             [:a {:href (feed-href "/feed.edn" url)} "model as EDN"] " · "
             [:a {:href (feed-href "/feed.xml" url)} "round-tripped XML"] " · "
             "profile: "
             (for [p [nil :psp-1 :apple]]
               (list (if (= p profile)
                       [:strong (if p (name p) "none")]
                       [:a {:href (feed-href "/feed" url {:profile (some-> p name)})} (if p (name p) "none")])
                     " "))]
            [:h2 "Parse report"]
            [:p.muted "What the parser had to repair, normalize or give up on. An empty report means the feed is written the way the standards say."]
            (findings-table report)
            [:h2 (str "Validation" (when profile (str " against " (:name (validate/profiles profile)))))]
            (findings-table validation)
            [:h2 "Channel"]
            (kv-table (dissoc feed :rss/items :podcast/live-items :xml/unknown))
            (when-let [unknown (:xml/unknown feed)]
              (list [:h2 "Unknown channel elements"]
                    [:pre (pretty unknown)]))
            (when-let [live (seq (:podcast/live-items feed))]
              (list [:h2 "Live items"] (kv-table (zipmap (range) live))))
            [:h2 (str "Items (" (count items) ")")]
            [:table
             [:thead [:tr [:th "#"] [:th "Episode"] [:th "Enclosure"] [:th "Podcasting 2.0 and more"]]]
             [:tbody (map-indexed (partial item-row feed) (take 200 items))]]
            (when (> (count items) 200)
              [:p.muted (str "Showing the first 200 of " (count items) " items.")]))))

(defn error-page
  "The page shown when `url` could not be fetched or parsed, with the
  `message` of the failure."
  [url message]
  (layout "Error"
          [:h1 "Could not inspect the feed"]
          [:p [:code url]]
          [:pre message]
          [:p [:a {:href "/"} "Back"]]))
