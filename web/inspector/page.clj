(ns inspector.page
  "The pages of the feed inspector as Hiccup for Replicant: data in, markup
  out, no I/O."
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.drop-in-search.queries :as search.queries]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.analytics.op3 :as op3]
            [dk.simongray.podcast.comments :as comments]
            [dk.simongray.podcast.comments.atproto :as atproto]
            [dk.simongray.podcast.comments.nostr :as nostr-comments]
            [dk.simongray.podcast.criteria :as criteria]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.directory :as directory]
            [dk.simongray.podcast.feed.guid :as feed.guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.schedule :as schedule]
            [dk.simongray.podcast.feed.validate :as validate]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.nostr :as nostr]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.wary-fetch.url :as url]))

(def css
  "The inspector's stylesheet, inlined into every page."
  "body{font:15px/1.45 system-ui,sans-serif;margin:0;color:#222;
        background:#fafafa}
   main{max-width:1100px;margin:0 auto;padding:1rem 1.5rem 3rem}
   header{display:flex;gap:1rem;align-items:center}
   header img{width:96px;height:96px;border-radius:8px;object-fit:cover}
   h1{margin:.2rem 0;font-size:1.5rem}
   h2{margin-top:2rem;border-bottom:1px solid #ddd;padding-bottom:.2rem}
   table{border-collapse:collapse;width:100%;font-size:14px}
   th,td{text-align:left;vertical-align:top;padding:.3rem .5rem;
         border-bottom:1px solid #eee}
   th{background:#f0f0f0} code,pre{font:13px/1.4 ui-monospace,Menlo,monospace}
   pre{background:#f4f4f4;padding:.6rem;overflow:auto;max-height:24rem;
       white-space:pre-wrap}
   .error{color:#b00020} .warning{color:#a15c00} .info{color:#555}
   .muted{color:#777}
   .badge{display:inline-block;padding:0 .4rem;border-radius:4px;
          background:#e8e8e8;margin-right:.3rem;font-size:12px}
   nav a{margin-right:1rem}
   form input[type=url],form input[type=search],form input[type=password],
   form input[type=text]{width:60%;padding:.4rem}
   form button{padding:.4rem .8rem}
   form input[type=number]{width:8rem;padding:.4rem}
   form input[name=message]{width:40%;padding:.4rem}
   form.selection input[type=search]{width:30%}
   form.selection input,form.selection select{padding:.3rem}
   .results li{margin:.6rem 0} .snippet{color:#444;max-width:80ch}
   mark{background:#fff3a0}
   ul.compact{margin:0;padding-left:1.2rem}
   .notes{max-width:70ch} .notes img{max-width:100%;height:auto}
   ol.comments{list-style:none;padding-left:1rem;border-left:2px solid #eee}
   .comment{margin:.8rem 0}
   .avatar{width:24px;height:24px;border-radius:50%;vertical-align:middle;
           margin-right:.4rem}
   img.attachment{max-width:240px;max-height:240px;margin:.3rem .3rem 0 0}
   #player-bar:empty{display:none}
   #player-bar{position:sticky;top:0;z-index:1;background:#fff;
               border-bottom:1px solid #ddd;padding:.5rem 1.5rem}
   #player-bar .bar{max-width:1100px;margin:0 auto;display:flex;gap:1rem;
                    align-items:center;flex-wrap:wrap}
   #player-bar audio{height:32px}
   #player-bar video{max-width:320px;max-height:180px}")

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
    ;; The browser player mounts here and stays while the content below is
    ;; swapped on navigation, so that playback survives moving between pages.
    [:div#player-bar]
    (into [:main#content
           [:nav
            [:a {:href "/"} "Inspector"]
            [:a {:href "/queue"} "Queue"]
            [:a {:href "/podping"} "Podping"]
            [:span.muted "podcast-clj reference implementation"]]]
          body)
    [:script {:src "/js/shared.js"}]
    [:script {:src "/js/player.js"}]]])

(defn- pretty
  [x]
  (str/trimr (with-out-str (pprint/pprint x))))

(defn- feed-href
  [route url & [params]]
  (let [query (for [[k v] params
                    :when v]
                (str "&" (name k) "=" (url/percent-encode v)))]
    (str route "?url=" (url/percent-encode url) (apply str query))))

(defn- link
  "The URL `url`, which a feed chose, when a link may lead there: over http
  or https. Nil otherwise, which leaves the attribute out, so that a
  javascript: URL is never a link."
  [url]
  (when (url/web-url? url)
    url))

(defn- field
  "A required input of the type `kind`, named `field-name`, with the
  `placeholder`."
  [kind field-name placeholder]
  [:input {:type        kind
           :name        field-name
           :placeholder placeholder
           :required    true}])

(defn home
  "The landing page: a URL form and the `corpus`, pairs of a name and a
  URL."
  [corpus]
  (layout "Home"
          [:h1 "Podcast feed inspector"]
          [:p (str "Parse any RSS feed with the library, see what it "
                   "understood, what it had to forgive, and how the feed "
                   "measures up to the standards.")]
          [:form {:method "get" :action "/find"}
           (field "text" "q" "a feed, a website, or a link from a podcast app")
           " "
           [:button {:type "submit"} "Inspect"]]
          [:form {:method "get" :action "/directory"}
           (field "search" "q" "or find a podcast in the directories")
           " "
           [:button {:type "submit"} "Find"]
           " "
           [:a.muted {:href "/directory"} "charts"]]
          [:form {:method "get" :action "/thread"}
           (field "text" "uri" "or read the comments below a post")
           " "
           [:button {:type "submit"} "Read"]]
          [:p
           [:a {:href "/wallet"} "Connect a wallet"]
           [:span.muted " with Nostr Wallet Connect"]]
          (when (seq corpus)
            (list [:h2 "Corpus"]
                  [:p.muted (str "Real feeds from the hosting platforms the "
                                 "library is tested against.")]
                  [:form {:method "get" :action "/search"}
                   (field "search" "q" "Search every episode in the corpus")
                   " "
                   [:button {:type "submit"} "Search"]]
                  [:ul.compact
                   (for [[name url] corpus]
                     [:li
                      [:a {:href (str "/corpus/" name)} name]
                      " "
                      [:span.muted url]])]))))

(defn- level-class
  [level]
  (name (or level :info)))

(defn diagnostics-table
  "A table of report or validation `diagnostics`, one row per kind, with
  the count and one example."
  [diagnostics]
  (if (empty? diagnostics)
    [:p.muted "None."]
    [:table
     [:thead
      [:tr [:th "Level"] [:th "Diagnostic"] [:th "Count"] [:th "Example"]]]
     [:tbody
      (for [{:keys [id level message example] n :count}
            (diagnostics/summarize diagnostics)]
        [:tr
         [:td {:class (level-class level)} (name (or level :info))]
         [:td [:code (str id)]]
         [:td n]
         [:td message
          (when-let [path (:path example)]
            [:span.muted (str " · " (validate/location path))])
          (when-let [extras (seq (dissoc example :id :level :message :path))]
            (let [pairs (for [[k v] extras]
                          (str (name k) "=" (pr-str v)))]
              [:span.muted (str " · " (str/join ", " pairs))]))]])]]))

(defn- verdict-line
  "A line that says whether the feed passes by the `verdict` of its
  diagnostics, with `against` naming the requirements, and the counts."
  [{:keys [passed? counts]} against]
  (let [n (fn [level word]
            (let [k (get counts level)]
              (str k " " word (when (and (not= 1 k) (not= :info level)) "s"))))]
    [:p
     [:strong {:class (if passed? "info" "error")} (if passed? "Passes" "Fails")]
     (str against ": " (n :error "error") ", " (n :warning "warning") ", "
          (n :info "info"))]))

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

(defn- shorten
  "The text `s` cut to `n` characters with an ellipsis."
  [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- resolved-table
  "A table of the resolved `properties` and their `sources`: what an app
  reads of the feed, and the raw fields each answer came from."
  [properties sources]
  (let [key-str  #(if (namespace %) (str %) (name %))
        path-str #(str/join " " (map key-str %))]
    [:table
     [:thead [:tr [:th "Property"] [:th "Value"] [:th "From"]]]
     [:tbody
      (for [[k v] (sort-by key properties)]
        [:tr
         [:td [:code (name k)]]
         [:td [:code (shorten (pr-str v) 160)]]
         [:td.muted (str/join ", " (map path-str (get sources k)))]])]]))

(defn- numbering-str
  "The label in the style of S2E3 for the `numbering` of an item, or an
  empty string without numbers."
  [{:keys [season episode episode-display type] :as numbering}]
  (str (when season (str "S" season))
       (when (or episode episode-display)
         (str "E" (or episode-display episode)))
       (when-not (= "full" type) (str " " type))))

(defn- size-str
  "The size `n` in bytes, in the unit that suits it."
  [n]
  (cond
    (>= n 1000000) (str (quot n 1000000) " MB")
    (>= n 1000)    (str (quot n 1000) " KB")
    :else          (str n " B")))

(defn- item-row
  "The row of the items table for `item` of `feed` at `feed-url`, numbered
  after its index `i`, showing what an app would show."
  [feed-url feed i item]
  (let [{:keys [url type length]} (resolve/enclosure item)
        alternatives             (count (resolve/enclosures item))
        people                   (resolve/people feed item)
        own-people?              (seq (:podcast/people item))
        guid                     (feed.guid/item-guid item)
        title                    (or (resolve/title item) "(untitled)")
        href                     (when guid
                                   (feed-href "/item" feed-url {:guid guid}))
        chapters-url             (resolve/chapters-url item)
        embedded                 (resolve/embedded-chapters item)]
    [:tr
     [:td (inc i)]
     [:td [:strong (if href [:a {:href href} title] title)]
      (when-let [n (not-empty (numbering-str (resolve/numbering item)))]
        [:span.badge n])
      [:br] [:span.muted (inst-str (resolve/published item))]
      (when-let [d (resolve/duration item)]
        [:span.muted (str " · " (time/format-clock d))])
      (when (resolve/explicit? feed item)
        [:span.muted " · explicit"])]
     [:td (when url
            [:span [:a {:href (link url)} (or type "media")]
             (when (number? length)
               [:span.muted (str " · " (size-str length))])
             (when (> alternatives 1)
               [:span.muted (str " · " alternatives " sources")])])]
     [:td (concat
           (when-let [t (resolve/transcript item)]
             [[:span.badge (str "transcript " (:type t))]])
           (when chapters-url
             [[:span.badge "chapters"]])
           (when embedded
             [[:span.badge "embedded chapters"]])
           (when (and (not chapters-url)
                      (not embedded)
                      (resolve/inferred-chapters item))
             [[:span.badge "chapters in the notes"]])
           (for [p people]
             [:span.badge {:class (when-not own-people? "muted")}
              (str (:role p) ": " (:name p))])
           (when (resolve/value feed item)
             [[:span.badge "value"]])
           (when (seq (:podcast/soundbites item))
             [[:span.badge "soundbites"]])
           (when (seq (:podcast/social-interacts item))
             [[:span.badge "social"]])
           (when-let [art (resolve/artwork item)]
             [[:span.badge [:a {:href (link art)} "artwork"]]])
           (for [[tag] (:xml/unknown item)]
             [:span.badge.muted (str tag)]))]]))

(def orders
  "The orders the feed page offers: those of the criteria that need no
  profile, which the server does not have."
  [:listening :newest :oldest :shortest :longest :title])

(defn- selection-form
  "The form that filters and orders the items of the feed at `url`, showing
  the current `selection` and keeping the `requirements`. Its choices are
  the library's own criteria and orders, so a client's UI has the same to
  offer."
  [url {:keys [where order] :as selection} requirements]
  (let [types       [["" "any type"] ["full" "full"] ["trailer" "trailer"]
                     ["bonus" "bonus"]]
        search-href (str "/search?url=" (url/percent-encode url)
                         (when-let [q (:text where)]
                           (str "&q=" (url/percent-encode q))))]
    [:form.selection {:method "get" :action "/feed"}
     [:input {:type "hidden" :name "url" :value url}]
     (when requirements
       [:input {:type  "hidden"
                :name  "requirements"
                :value (name requirements)}])
     [:input {:type        "search"
              :name        "q"
              :placeholder "words in the title or notes"
              :value       (:text where)}]
     " season "
     [:input {:type  "number"
              :name  "season"
              :min   0
              :value (:season where)
              :style {:width "4em"}}]
     " "
     [:select {:name "type"}
      (for [[v label] types]
        [:option {:value v :selected (= v (or (:type where) ""))} label])]
     " "
     [:select {:name "order"}
      (for [o orders]
        [:option {:value    (name o)
                  :selected (= o (or order :listening))}
         (name o)])]
     " "
     [:button {:type "submit"} "Apply"]
     " "
     [:a {:href search-href} "full-text search"]]))

(defn feed-page
  "The inspector page for `feed`, fetched from `url` with the HTTP
  `status`, which lives at `feed-url`. It has the parse `report`, the
  validation against `requirements`, and the items as `selection` filters
  and orders them."
  [{:keys [url feed-url feed report requirements status selection]}]
  (let [items      (criteria/episodes feed (or selection {}))
        total      (count (:rss/items feed))
        validation (validate/validate feed {:requirements requirements})
        feed-url   (or feed-url url)
        live       (seq (:podcast/live-items feed))
        serial?    (= "serial" (some-> (:itunes/type feed) str/lower-case))
        next-check (schedule/next-check feed (time/now))
        facts      [(resolve/author feed)
                    (:rss/language feed)
                    (str "medium " (resolve/medium feed))
                    (str total " items")
                    (when live (str (count live) " live"))
                    (when (resolve/explicit? feed) "explicit")
                    (when (resolve/complete? feed) "complete")
                    (when (resolve/locked? feed) "locked")
                    (when serial? "serial")
                    (when next-check
                      (str "next check " (time/format-rfc2822 next-check)))]
        required   (some-> requirements name)
        op3?       (some #(op3/prefixed? (get-in % [:rss/enclosure :url]))
                         (:rss/items feed))
        guid       (feed.guid/feed-guid feed feed-url)
        op3-href   (str op3/origin "/show/" guid)
        selected   {:q      (get-in selection [:where :text])
                    :season (some-> (get-in selection [:where :season]) str)
                    :type   (get-in selection [:where :type])
                    :order  (some-> (:order selection) name)}
        under      (fn [p]
                     (->> selected
                          (into {:requirements (some-> p name)})
                          (feed-href "/feed" url)))
        against    (when requirements
                     (str " against "
                          (:name (validate/requirements requirements))))
        shown      (count items)]
    (layout (or (resolve/title feed) url)
            [:header
             (when-let [img (link (resolve/artwork feed))]
               [:img {:src img :alt ""}])
             [:div
              [:h1 (or (resolve/title feed) "(untitled)")]
              [:div.muted (str/join " · " (keep not-empty facts))]
              (when-let [paths (seq (resolve/categories feed))]
                [:div.muted (str/join " · " (map #(str/join " › " %) paths))])
              [:div.muted "guid " [:code (or guid "?")]
               (when-not (:podcast/guid feed)
                 [:span.muted " (computed from the URL)"])]
              [:div.muted (if-let [href (link feed-url)]
                            [:a {:href href} feed-url]
                            feed-url)
               (when status (str " · HTTP " status))]]]
            (verdict-line (diagnostics/verdict (concat report validation))
                          against)
            [:p
             [:a {:href (feed-href "/feed.edn" url)} "model as EDN"] " · "
             [:a {:href (feed-href "/feed.xml" url)} "round-tripped XML"] " · "
             [:a {:href (feed-href "/check" url {:requirements required})}
              "check the files over the network"]
             " · "
             (when op3?
               (list [:a {:href op3-href} "downloads at OP3"] " · "))
             "requirements: "
             (for [p [nil :psp-1 :apple]]
               (list (if (= p requirements)
                       [:strong (if p (name p) "none")]
                       [:a {:href (under p)} (if p (name p) "none")])
                     " "))]
            [:h2 "Parse report"]
            [:p.muted (str "What the parser had to repair, normalize or give "
                           "up on. An empty report means the feed is written "
                           "the way the standards say.")]
            (diagnostics-table report)
            [:h2 (str "Validation" against)]
            (diagnostics-table validation)
            [:h2 "Resolved"]
            [:p.muted (str "One answer per property where the vocabularies "
                           "overlap, and the fields it was read from; a "
                           "default names none.")]
            (resolved-table (resolve/properties feed) (resolve/sources feed))
            [:h2 "Channel"]
            (kv-table (dissoc feed :rss/items :podcast/live-items :xml/unknown))
            (when-let [unknown (:xml/unknown feed)]
              (list [:h2 "Unknown channel elements"]
                    [:pre (pretty unknown)]))
            (when live
              (list [:h2 "Live items"] (kv-table (zipmap (range) live))))
            [:h2 (str "Items (" shown
                      (when (not= shown total) (str " of " total))
                      ")")]
            (selection-form url selection requirements)
            [:table
             [:thead
              [:tr [:th "#"] [:th "Episode"] [:th "Enclosure"]
               [:th "Podcasting 2.0 and more"]]]
             [:tbody
              (map-indexed (partial item-row url feed) (take 200 items))]]
            (when (> shown 200)
              [:p.muted (str "Showing the first 200 of " shown " items.")]))))

(defn- told
  "What the diagnostic `d` tells about a failure: its reason, or its
  message when it has no reason."
  [d]
  (or (:reason d) (:message d)))

(defn- file-row
  "The row of `file` in the table of the files that a check fetched: what
  the answers to its requests told, what the file holds, and the ids of
  the `findings` about it."
  [findings {:keys [kind url facts] :as file}]
  (let [{:keys [head image media read integrity stream] got :get} facts
        answer  (or got head)
        failed  (told (or (:error facts) (:error answer)))
        ;; the length of a 206 is that of the part
        size    (or (get-in got [:range :total])
                    (when (= 200 (:status got)) (:length got))
                    (when (= 200 (:status head)) (:length head)))
        unread  (told (or (:error media) (:error image) (:error read)
                          (:error stream)))
        clock   #(some->> (time/format-clock %) (str " · "))]
    [:tr
     [:td (name kind)]
     [:td [:a {:href (link url)} (shorten url 70)]]
     [:td (cond
            (:unchecked facts)
            [:span.muted "not checked"]

            failed
            [:span.error failed]

            :else
            (list (str "HTTP " (:status answer))
                  (when-let [hops (seq (:redirects answer))]
                    (let [unit (if (next hops) " redirects" " redirect")]
                      [:span.muted (str " · " (count hops) unit)]))
                  (when (= :media kind)
                    [:span.muted (if (= 206 (:status got))
                                   " · ranges"
                                   " · no ranges")])
                  (when-let [cors (:cors answer)]
                    [:span.muted (str " · CORS " cors)])))]
     [:td (:type answer) (when size [:span.muted (str " · " (size-str size))])]
     [:td (cond
            unread
            [:span.error unread]

            (:width image)
            (str (:width image) "×" (or (:height image) "?") " "
                 (some-> (:color image) name)
                 (when (:alpha? image) ", alpha"))

            (:type image)
            (:type image)

            (:format media)
            (str (name (:format media))
                 (clock (:duration media))
                 (when (:height media)
                   (str " · " (:width media) "×" (:height media))))

            stream
            (str "HLS"
                 (when-let [variants (seq (:variants stream))]
                   (str " · " (count variants) " variants"))
                 (clock (:duration stream)))

            read
            (str (:count read)
                 (if (= :transcript kind) " segments" " chapters")))
      (when integrity
        (let [algorithm (:algorithm integrity)]
          (case (:valid? integrity)
            true  [:span.muted (str " · " algorithm " matches")]
            false [:span.error (str " · " algorithm " does not match")]
            [:span.muted (str " · integrity not checked: "
                              (told (:error integrity)))])))]
     [:td (for [{:keys [id level]} findings]
            (list [:code {:class (level-class level)} (subs (str id) 1)]
                  " "))]]))

(defn check-page
  "The check over the network of `feed`, fetched from `url`, against
  `requirements`. It shows the diagnostics of the check `result` and the
  files it fetched of the newest `items`, with their integrity when
  `integrity?`."
  [{:keys [url feed requirements items integrity? result]}]
  (let [files    (:files result)
        hashed   (count (filter #(get-in % [:declared :integrity]) files))
        valid?   #(boolean? (get-in % [:facts :integrity :valid?]))
        judged   (count (filter valid? files))
        by-url   (group-by :url (:report result))
        required (some-> requirements name)
        under    (fn [p]
                   (feed-href "/check" url
                              {:requirements (some-> p name)
                               :items        items
                               :integrity    (when integrity? "1")}))
        whole    (feed-href "/check" url
                            {:requirements required
                             :items        items
                             :integrity    "1"})]
    (layout (str "Check · " (or (resolve/title feed) url))
            [:h1 (or (resolve/title feed) "(untitled)")]
            [:p [:a {:href (feed-href "/feed" url {:requirements required})}
                 "back to the feed"]
             " · "
             "requirements: "
             (for [p [nil :psp-1 :apple]]
               (list (if (= p requirements)
                       [:strong (if p (name p) "none")]
                       [:a {:href (under p)} (if p (name p) "none")])
                     " "))]
            [:p.muted (str "The files that the feed and its " items " newest "
                           "items link to, fetched as a listener's app fetches "
                           "them: HEAD and the first two bytes of the feed and "
                           "of each media file, HEAD and the header of each "
                           "image, HEAD and the whole of each transcript and "
                           "chapters file, what the media reader needs of "
                           "each media file, and the playlists of each "
                           "stream.")]
            (when (pos? hashed)
              [:p (if integrity?
                    [:span.muted (str judged " of the " hashed " files with a "
                                      "podcast:integrity were fetched whole "
                                      "and checked against it.")]
                    (list (str hashed " files have a podcast:integrity. ")
                          [:a {:href whole} "Check them against it"]
                          [:span.muted
                           " (this fetches each of them whole)."]))])
            [:h2 "Diagnostics"]
            (diagnostics-table (:report result))
            [:h2 (str "Files (" (count files) ")")]
            [:table
             [:thead
              [:tr [:th "Kind"] [:th "File"] [:th "Answer"] [:th "Type"]
               [:th "Holds"] [:th "Findings"]]]
             [:tbody (map #(file-row (by-url (:url %)) %) files)]])))

(defn- part-status
  "A line about a part of the view that came from a file: the `label`, the
  `url` of the file, and the `error` when the file did not come."
  [{:keys [url error]} label]
  [:p.muted label " " (when url [:a {:href (link url)} url])
   (when error
     (let [status (:status error)]
       [:span.error (str " · " (:message error)
                         (when status (str " HTTP " status)))]))])

(def chapter-sources
  "What the page says of each source of chapters."
  {:file     "JSON chapters file:"
   :embedded "Embedded Podlove chapters."
   :media    "Read from the media file:"
   :inferred (str "Read from the timestamps in the show notes; the feed "
                  "offers no chapters.")})

(defn- chapters-section
  "The `chapters` of the view of an episode, a map of :source, :url,
  :document and :report. The source is the first that has chapters of the
  file, the embedded ones, the media file and the timestamps of the show
  notes."
  [{:keys [chapters]}]
  (let [{:keys [source document report]} chapters]
    (list
     [:h2 "Chapters"]
     (when source (part-status chapters (chapter-sources source)))
     (if-not document
       [:p.muted "None."]
       (let [found    (:chapters document)
             located? (some :location found)]
         (list
          [:p.muted (str (count found) " chapters, format version "
                         (:version document)
                         (when located? ", with locations"))]
          (when (seq report)
            (diagnostics-table report))
          [:table
           [:thead [:tr [:th "Start"] [:th "Title"] [:th "Links"]]]
           [:tbody
            (for [{:keys [start-time title url img toc location]}
                  (chapters/sorted document)]
              [:tr
               [:td (when (number? start-time) (time/format-clock start-time))]
               [:td (or title [:span.muted "(silent chapter)"])
                (when (false? toc) [:span.badge.muted "hidden"])
                (when location
                  [:span.badge (str (:name location) " " (:geo location))])]
               [:td (when url [:a {:href (link url)} "link"])
                " "
                (when img [:a {:href (link img)} "image"])]])]]))))))

(defn- transcript-section
  "The `transcript` of the view of an episode: the chosen reference with
  its :document and its :report, or its :error."
  [{:keys [transcript]}]
  (list
   [:h2 "Transcript"]
   (if-not transcript
     [:p.muted "None."]
     (let [{:keys [type language document report]} transcript
           segments (:segments document)
           speakers (distinct (keep :speaker segments))]
       (list
        (part-status transcript
                     (str "Chosen " type
                          (when language (str ", " language))
                          ":"))
        (when document
          (list
           [:p.muted (str (count segments) " segments · format "
                          (name (:format document))
                          (when (seq speakers)
                            (str " · speakers: "
                                 (str/join ", " (take 8 speakers)))))]
           (when (seq report)
             (diagnostics-table report))
           [:table
            [:tbody
             (for [{:keys [start-time end-time speaker body]}
                   (take 40 segments)]
               [:tr
                [:td.muted (when (number? start-time)
                             (time/format-clock start-time))
                 (when (number? end-time)
                   (str "–" (time/format-clock end-time)))]
                [:td (when speaker [:strong (str speaker ": ")]) body]])]]
           (when (> (count segments) 40)
             [:p.muted (str "Showing the first 40 of " (count segments)
                            " segments.")]))))))))

(defn- media-file-section
  "The `media` of the view of an episode, what its media file says of
  itself: its duration and its artwork, with the report."
  [{:keys [media]}]
  (when media
    (let [{:keys [duration artwork report error]} media]
      (list
       [:h2 "Media file"]
       (part-status media "Read by range requests:")
       (when-not error
         (let [size  (when artwork (size-str (bytes/length (:data artwork))))
               facts [(when duration
                        (str "duration " (time/format-clock duration)))
                      (when artwork
                        (str "artwork " (:type artwork) ", " size))
                      (when-not (or duration artwork) "nothing read")]]
           [:p.muted (str/join " · " (remove nil? facts))]))
       (when (seq report)
         (diagnostics-table report))))))

(defn- payments-table
  [recipients]
  [:table
   [:thead [:tr [:th "Recipient"] [:th "Type"] [:th "Split"] [:th "Of 1000"]]]
   [:tbody
    (for [{:keys [recipient amount]} (value/payments recipients 1000)]
      [:tr
       [:td (:name recipient)]
       [:td (:type recipient) (when (:fee recipient) " (fee)")]
       [:td (:split recipient)]
       [:td amount]])]])

(defn- time-split-section
  "The time `split` of `block`: who is paid during it, with the remote item
  folded in when `remote-values` has its block. The `remote-report` tells
  why a block is missing, and `index?` whether the index can be asked."
  [block {:keys [remote-values remote-report index?]}
   {:keys [start-time duration remote-item remote-percentage] :as split}]
  (let [paid    (value/recipients-at block start-time remote-values)
        failure (some #(when (= remote-item (:remote-item %)) %) remote-report)
        remote  (when remote-item
                  (str " · remote item " (:feed-guid remote-item)
                       (when (:item-guid remote-item)
                         (str " / " (:item-guid remote-item)))
                       " at " (or remote-percentage 100) "%"))]
    (list
     [:h3 (str "From " (time/format-clock start-time)
               " for " (time/format-clock duration)
               remote)]
     (cond
       (or (nil? remote-item) (get remote-values remote-item))
       (payments-table (:recipients paid))

       (contains? remote-values remote-item)
       [:p.muted (str "No value block was found for this remote item; the "
                      "feed's own recipients apply.")]

       (or index? (:feed-url remote-item))
       [:p.muted (str "The value block of this remote item couldn't be "
                      "fetched, so only the fees are paid: "
                      (or (:reason failure) (str "status " (:status failure))))]

       :else
       [:p.muted (str "Set PODCASTINDEX_API_KEY and PODCASTINDEX_API_SECRET "
                      "to resolve the remote item through the Podcast "
                      "Index.")]))))

(defn- value-section
  [{:keys [value]} remotes]
  (when-let [block value]
    (let [sats   (value/sats block)
          splits (:time-splits block)
          timed  (->> splits
                      (filter (comp number? :start-time))
                      (sort-by :start-time))]
      (list
       [:h2 "Value"]
       [:p.muted (str (:type block) " / " (:method block)
                      (when sats (str " · suggested " sats " sats per minute"))
                      (when (seq splits)
                        (str " · " (count splits) " time splits")))]
       (payments-table (:recipients block))
       (for [split timed]
         (time-split-section block remotes split))))))

(defn item-page
  "The page for one `item` of `feed` at `url`, from its `view` with what
  its files say merged in, and the `remotes` of its time splits."
  [{:keys [url feed item view remotes]}]
  (let [{:keys [title guid numbering published duration explicit? people
                artwork notes enclosures value]} view
        into-player (fn [mode]
                      (str "/queue?add=" (url/percent-encode url)
                           "&guid=" (url/percent-encode guid)
                           "&mode=" mode))]
    (layout (or title "(untitled)")
            [:p [:a {:href (feed-href "/feed" url)}
                 "← " (or (resolve/title feed) url)]]
            [:header
             (when-let [src (link artwork)]
               [:img {:src src :alt ""}])
             [:div
              [:h1 (or title "(untitled)")]
              [:div.muted
               (when-let [n (not-empty (numbering-str numbering))]
                 (str n " · "))
               (inst-str published)
               (when duration (str " · " (time/format-clock duration)))
               (when explicit? " · explicit")]
              [:div.muted "guid " [:code guid]]
              (when (seq people)
                (let [roles (map #(str (:role %) ": " (:name %)) people)]
                  [:div.muted (str/join " · " roles)]))
              [:div
               (if (:url (resolve/enclosure item))
                 (list [:a.badge {:href (into-player "now")} "Play now"]
                       [:a.badge {:href (into-player "last")} "Add to queue"]
                       [:a.badge {:href (into-player "next")} "Play next"])
                 [:span.badge.muted "Nothing to play"])
               [:a.badge {:href (feed-href "/comments" url {:guid guid})}
                "Comments"]
               (when value
                 [:a.badge {:href (feed-href "/wallet" url {:guid guid})}
                  "Boost"])]]]
            [:h2 "Description"]
            [:div.notes notes]
            [:h2 "Media"]
            [:table
             [:thead
              [:tr [:th "Source"] [:th "Type"] [:th "URL"] [:th "Details"]]]
             [:tbody
              (for [{:keys [source type url length bitrate title default]}
                    enclosures
                    :let [details [(when (number? length) (size-str length))
                                   (when bitrate (str bitrate " b/s"))
                                   title]]]
                [:tr
                 [:td (name source) (when default " (default)")]
                 [:td type]
                 [:td [:a {:href (link url)} url]]
                 [:td (str/join " · " (remove nil? details))]])]]
            (media-file-section view)
            (chapters-section view)
            (transcript-section view)
            (value-section view remotes)
            [:h2 "Resolved"]
            [:p.muted (str "What an app shows for this episode, inherited from "
                           "the show where the item says nothing, and the "
                           "fields each answer was read from.")]
            (resolved-table (resolve/properties feed item)
                            (resolve/sources feed item))
            [:h2 "Item as data"]
            (kv-table (dissoc item :rss/description :content/encoded)))))

(def result-limit
  "How many results of a full-text query the search page shows."
  50)

(defn- near-words
  "The words of a query that matched others with `typos`, a map of each to
  those words, as a line of text with the first few of each."
  [typos]
  (interpose "; "
             (for [[word near] typos]
               (list "Near matches for " [:em word] ": "
                     (str/join ", " (take 3 near))
                     (when (< 3 (count near)) ", …")))))

(defn search-page
  "The results of the full-text query `q` over the items of the feed at
  `url`, or over the corpus without one. Each of the `results` of the
  drop-in-search query of the `index` has the :item it came from, for a
  snippet of its notes. The `typos` are the words of `q` that matched
  others with typos."
  [{:keys [q url index results typos]}]
  (layout (str "Search: " q)
          (when url
            [:p [:a {:href (feed-href "/feed" url)} "← back to the feed"]])
          [:h1 (if url "Search this feed" "Search the corpus")]
          [:form {:method "get" :action "/search"}
           (when url [:input {:type "hidden" :name "url" :value url}])
           [:input {:type        "search"
                    :name        "q"
                    :value       q
                    :placeholder "words, a prefix*, \"a phrase\", -not"
                    :required    true}]
           " "
           [:button {:type "submit"} "Search"]]
          [:p.muted (str "Every word must occur; the last matches as a prefix "
                         "unless a space follows it; words in quotes must "
                         "occur in a row. OR or | for either, NOT, ! or a "
                         "leading - to leave out, parentheses to group, and "
                         "title:word or notes:word for one field. A word "
                         "that no episode holds matches words with typos, "
                         "and word~ asks for that for any word. A word in a "
                         "title weighs more than one in the notes; the "
                         "snippet is from the notes.")]
          (when (seq typos)
            [:p (near-words typos)])
          (when (= result-limit (count results))
            [:p.muted (str "The " result-limit " best matches.")])
          (if (empty? results)
            [:p.muted (if (str/blank? q)
                        "Type something to search for."
                        "Nothing matched.")]
            [:ol.results
             (for [{:keys [score stored item] :as result} results
                   :let [{:keys [title show published duration url guid]}
                         stored]]
               [:li
                [:strong [:a {:href (feed-href "/item" url {:guid guid})}
                          (or title "(untitled)")]]
                " " [:span.muted show]
                [:br]
                [:span.muted (inst-str published)
                 (when duration (str " · " (time/format-clock duration)))
                 (str " · score " (/ (Math/round (* 100.0 score)) 100.0))
                 (when-let [near (:typos result)]
                   (str " · near match: "
                        (str/join ", " (distinct (mapcat val near)))))]
                (when item
                  (let [notes (html/text (resolve/description item))]
                    [:div.snippet
                     (for [{:keys [text match?]}
                           (search.queries/snippet notes
                                                   q
                                                   (search/query-opts index))]
                       (if match? [:mark text] text))]))])])))

(defn find-page
  "The `feeds` found for the text `q`, best first, and the `report` of what
  was tried in vain, or the `error` when none were found."
  [{:keys [q feeds report error]}]
  (layout (str "Find: " q)
          [:h1 "The feeds of " [:code q]]
          (if (seq feeds)
            [:ol.results
             (for [{:keys [url title found-by podcast?]} feeds]
               [:li
                [:strong [:a {:href (feed-href "/feed" url)} (or title url)]]
                (when-not podcast? (list " " [:span.badge "not a podcast"]))
                [:br] [:span.muted url " · found by " (name found-by)]])]
            (list [:p.warning (:message error)
                   (when-let [reason (:reason error)]
                     [:span.muted " " reason])]
                  [:p "Try another address, or "
                   [:a {:href (str "/directory?q=" (url/percent-encode q))}
                    "search the directories"]
                   "."]))
          (when (seq report)
            (list [:h2 "What failed"]
                  (diagnostics-table report)))))

(defn directory-page
  "The shows the directories answered for the query `q`, or their charts
  for `country` without one, of the `category` when there is one. The
  `results` have one entry for each show, and the `report` names the
  sources that failed."
  [{:keys [q country category results report]}]
  (let [charts?    (str/blank? q)
        chart      (fn [params] (str "/directory?" (url/query-string params)))
        categories ["Technology" "News" "Society & Culture" "True Crime"
                    "Comedy" "Science"]]
    (layout (if charts? "Charts" (str "Find: " q))
            [:h1 (if charts?
                   (str "The charts"
                        (when country (str ", " country))
                        (when category (str ", " category)))
                   "Find a podcast")]
            [:form {:method "get" :action "/directory"}
             [:input {:type        "search"
                      :name        "q"
                      :value       q
                      :placeholder "words in a show's title"
                      :required    true}]
             " "
             [:button {:type "submit"} "Find"]]
            [:p.muted (str "Apple Podcasts, fyyd and gpodder.net, and the "
                           "Podcast Index when its credentials are set, asked "
                           "at once and combined into one entry per show. "
                           "Charts: ")
             [:a {:href "/directory?country="} "all sources"] " · "
             [:a {:href "/directory?country=us"} "US"] " · "
             [:a {:href "/directory?country=dk"} "Denmark"] " · "
             [:a {:href "/directory?country=de"} "Germany"]]
            (when charts?
              [:p.muted "The chart of one category: "
               (interpose " · "
                          (for [c categories]
                            [:a {:href (chart {:country country :category c})}
                             c]))])
            (when (seq report)
              (let [sources (str/join ", " (map (comp name :source) report))
                    reasons (str/join "; " (map :reason report))]
                [:p.warning (str "No answer from " sources ": " reasons)]))
            (if (empty? results)
              [:p.muted "Nothing found."]
              [:ol.results
               (for [show results
                     :let [{:directory/keys [sources episode-count
                                             subscribers rank]} show
                           feed-url (resolve/self-url show)
                           title    (or (resolve/title show) "(untitled)")]]
                 [:li
                  [:strong (if feed-url
                             [:a {:href (feed-href "/feed" feed-url)} title]
                             title)]
                  " " [:span.muted (resolve/author show)]
                  [:br]
                  (for [source (keys sources)]
                    [:span.badge (:name (directory/sources source))])
                  (when rank [:span.muted (str " · rank " rank)])
                  (when episode-count
                    [:span.muted (str " · " episode-count " episodes")])
                  (when subscribers
                    [:span.muted (str " · " subscribers " subscribers")])
                  (when-not feed-url
                    [:span.muted " · no feed URL from these sources"])
                  (when-let [d (resolve/description show)]
                    [:div.snippet (shorten (html/text d) 200)])])]))))

(defn podping-page
  "The podpings read from the chain just now: `pings` from the blocks up to
  `block`, the `refused` ones of other accounts, the `head` of the chain,
  and what is known of the `writers`. The `error` of the poll tells when
  no node answered a call."
  [{:keys [pings refused block head writers error]}]
  (layout "Podping"
          [:h1 "Podping"]
          [:p.muted (str "The feeds that just announced a change on the Hive "
                         "blockchain, read by "
                         "dk.simongray.podcast.updates.podping from the "
                         "twenty blocks before the head, about a minute of "
                         "the chain, in one request. A poller keeps the block "
                         "it reached and asks for the blocks since; a ping "
                         "counts only when the podping account follows its "
                         "writer.")]
          [:p.muted (str "Head block " head ", read up to " block "; "
                         (count pings) " pings from the " (count writers)
                         " writers podping follows"
                         (when (seq refused)
                           (let [accounts (distinct (map :account refused))]
                             (str "; " (count refused) " refused, from "
                                  (str/join ", " accounts)))))]
          (when error
            [:p [:span.error (str (:message error) " " (:reason error))]])
          (if (empty? pings)
            [:p.muted "No pings in these blocks."]
            [:table
             [:thead
              [:tr [:th "Block"] [:th "Time"] [:th "Writer"] [:th "Medium"]
               [:th "Reason"] [:th "Feeds"]]]
             [:tbody
              (for [{:keys [block timestamp account medium reason iris]} pings]
                [:tr
                 [:td block]
                 [:td (inst-str timestamp)]
                 [:td [:code account]]
                 [:td medium]
                 [:td reason]
                 [:td (for [iri iris]
                        [:div [:a {:href (feed-href "/feed" iri)}
                               (shorten iri 90)]])]])]])))

(defn queue-page
  "The shell of the play queue, which the browser player mounts into. The
  listener's profile is stored in the browser."
  []
  (layout "Queue"
          [:h1 "Play queue"]
          [:div#app
           [:p.muted (str "Loading the player… If nothing appears, compile it "
                          "first: ")
            [:code "clojure -M:cljs compile player"]]]))

(defn- web-link
  "The web page of the comment `c`. For a post of the AT Protocol, it is the
  page in Bluesky's web app. For a note of Nostr, it is the page of the
  gateway njump.me. These were chosen for the inspector among the apps
  that show such posts."
  [{:keys [url id] :as c}]
  (or (link url)
      (let [{:keys [authority collection rkey]} (atproto/at-uri id)]
        (when (and (= "app.bsky.feed.post" collection) rkey)
          (str "https://bsky.app/profile/" authority "/post/" rkey)))
      (some->> (nostr/encode {:type :nevent :id id})
               (str "https://njump.me/"))))

(defn- part-link
  "The web page of `part` of the content of a comment: a mention, a
  hashtag or a reference. For Nostr, it is the page of the gateway
  njump.me, and for the AT Protocol, the page in Bluesky's web app. A part
  of ActivityPub keeps the link that its post has."
  [{:keys [protocol type uri tag id] :as part}]
  (case protocol
    "nostr"   (if uri
                (str "https://njump.me/" (subs uri (count "nostr:")))
                (str "https://njump.me/t/" (url/percent-encode tag)))
    "atproto" (case type
                :mention (str "https://bsky.app/profile/"
                              (url/percent-encode id))
                :hashtag (str "https://bsky.app/hashtag/"
                              (url/percent-encode tag))
                nil)
    nil))

(defn- comment-item
  "The comment `c` of the tree of `thread`, and the replies below it."
  [thread {:keys [author published title content summary sensitive? labels
                  attachments deleted? error hidden? replies]
           ::nostr-comments/keys [receipts]
           :as   c}]
  (let [body (list (when title [:strong title])
                   [:div.notes (comments/hiccup thread c {:link-fn part-link})]
                   (for [{:keys [kind url preview alt]} attachments]
                     (if (= :image kind)
                       [:a {:href (link url)}
                        [:img.attachment {:src (link (or preview url))
                                          :alt (str alt)}]]
                       [:a.badge {:href (link url)} (name kind)])))]
    [:li.comment
     [:div.muted
      (when-let [avatar (link (:avatar author))]
        [:img.avatar {:src avatar :alt ""}])
      [:strong (or (:name author) (:handle author) (:id author))]
      " " (:handle author)
      " · " [:a {:href (web-link c)}
             (if published (inst-str published) "open")]
      (when (seq receipts) " · with a payment")
      (when hidden? " · hidden by the author of the root post")]
     (cond
       deleted?
       [:p.muted "This post was deleted."]

       (and error (not content))
       [:p.muted "The inspector did not get this post."]

       (or summary sensitive?)
       [:details
        [:summary (or summary (str "Sensitive: " (str/join ", " labels)))]
        body]

       :else
       body)
     (when (seq replies)
       [:ol.comments (map #(comment-item thread %) replies)])]))

(defn- thread-view
  "The read `thread` as a tree with its report, with the replies that the
  author of the root post hid when `hidden?`. The link to `toggle` leads to
  the same page with or without them.

  A thread about an episode itself has no root post, so the view shows the
  comments on the episode."
  [thread hidden? toggle]
  (let [about?   (str/starts-with? (str (:uri thread)) "podcast:")
        tree     (comments/tree thread {:hidden? hidden?})
        ;; the root of a thread about an episode is the episode, no post
        counted? #(and (:read-at %)
                       (not (and about? (= (:root thread) (:id %)))))
        posts    (count (filter counted? (vals (:comments thread))))
        hiding?  (or hidden? (some :hidden? (vals (:comments thread))))]
    (list [:p.muted "The thread of " [:code (:uri thread)]
           " over " (:protocol thread)
           (when (:via thread) ", read through the Mastodon API")
           ", " posts (if (= 1 posts) " post. " " posts. ")
           (when hiding?
             [:a {:href toggle} (if hidden?
                                  "Leave out the hidden replies"
                                  "Show the hidden replies")])]
          (cond
            (nil? tree)
            [:p.muted "The inspector did not get the root post."]

            (not about?)
            [:ol.comments (comment-item thread tree)]

            (seq (:replies tree))
            [:ol.comments (map #(comment-item thread %) (:replies tree))]

            :else
            [:p.muted "The relays have no note about this episode."])
          [:h2 "Report"]
          (diagnostics-table (:report thread)))))

(defn comments-page
  "The page of the comments on `item` of the feed at `url`, from the
  `interacts` of the item and the read `thread`. With `hidden?`, it holds
  the replies that the author of the root post hid.

  It shows the thread as a tree, with its report, or tells why there is no
  thread."
  [{:keys [url item interacts thread hidden?]}]
  (let [title (or (resolve/title item) "(untitled)")
        guid  (feed.guid/item-guid item)]
    (layout (str "Comments: " title)
            [:p [:a {:href (feed-href "/item" url {:guid guid})} "← " title]]
            [:h1 "Comments"]
            (cond
              (= "disabled" (:protocol (first interacts)))
              [:p.muted (str "The podcaster wants no public comments shown "
                             "with this episode.")]

              (and (nil? thread) (empty? interacts))
              [:p.muted (str "The feed names no thread of comments for this "
                             "episode.")]

              (nil? thread)
              (let [protocols (distinct (map :protocol interacts))]
                [:p.muted (str "podcast-clj reads no comments over "
                               (str/join ", " protocols) ".")])

              :else
              (let [params {:guid guid :hidden (when-not hidden? "1")}
                    toggle (feed-href "/comments" url params)]
                (list (when (str/starts-with? (str (:uri thread)) "podcast:")
                        [:p.muted (str "The feed names no thread of comments "
                                       "that podcast-clj reads for this "
                                       "episode. ")
                         "These are the notes about the episode on Nostr."])
                      (thread-view thread hidden? toggle)))))))

(defn thread-page
  "The page of the comments below the post that `address` names, from the
  read `thread`, with the hidden replies when `hidden?`. It has a form for
  the address of another post.

  Without a thread, the page tells that the inspector does not know the
  address, when there is one."
  [{:keys [address thread hidden?]}]
  (layout "Thread"
          [:h1 "The comments below a post"]
          [:form {:method "get" :action "/thread"}
           [:input {:type        "text"
                    :name        "uri"
                    :value       (str address)
                    :placeholder "The address of a post"
                    :required    true}]
           " "
           [:button {:type "submit"} "Read"]]
          [:p.muted (str "The address is the web page of a post of Mastodon "
                         "or of another server of ActivityPub, ")
           "the web page of a post at bsky.app or its at:// URI, "
           "or the name of a note of Nostr, such as nostr:nevent1… or note1…. "
           (str "An id of NIP-73, such as podcast:item:guid:…, gives the notes "
                "about an episode.")]
          (cond
            thread
            (let [toggle (str "/thread?uri=" (url/percent-encode address)
                              (when-not hidden? "&hidden=1"))]
              (thread-view thread hidden? toggle))

            (not (str/blank? (str address)))
            [:p.error (str "The inspector does not know this address as the "
                           "address of a post.")])))

(defn- sats-str
  "The amount of `msat` millisatoshis in sats, as text."
  [msat]
  (let [sats (quot msat 1000)]
    (str sats (if (= 1 sats) " sat" " sats"))))

(defn- answer-cell
  "What the wallet answered to a command, for a cell of a table: the
  problem of `answer`, or what `told-fn` gives for it."
  [answer told-fn]
  (cond
    (nil? answer)
    [:span.muted "Not asked"]

    (:error answer)
    (let [{:keys [level message code reason relays expires-at]}
          (:error answer)]
      [:span {:class (name (or level :warning))}
       (str/join " " (remove nil? [code reason]))
       (when (or code reason) [:br])
       [:span.muted message]
       (when (inst? expires-at)
         [:span.muted (str " It expires at " (inst-str expires-at) ".")])
       (when (seq relays)
         [:ul.compact
          (for [{:keys [url reason]} relays]
            [:li [:code url] " " reason])])])

    :else
    (told-fn answer)))

(defn- wallet-facts
  "What the wallet told of itself and of the connection, from the
  `answers` of the wallet page."
  [{:keys [connection app info wallet balance] :as answers}]
  (list
   [:h2 "The connection"]
   [:table
    [:tbody
     (for [[k v] [["The public key of the wallet service" (:wallet connection)]
                  ["The public key of the inspector" app]
                  ["The relays" (str/join ", " (:relays connection))]
                  ["The Lightning address of the listener" (:lud16 connection)]]
           :when v]
       [:tr [:th k] [:td [:code v]]])]]
   [:h2 "The wallet"]
   [:table
    [:tbody
     [:tr [:th "The commands that it takes"]
      [:td (answer-cell info #(str/join ", " (:methods %)))]]
     [:tr [:th "The encryption that it reads"]
      [:td (answer-cell info #(str/join ", " (:encryption %)))]]
     [:tr [:th "Its name and its network"]
      [:td (answer-cell wallet
                        #(->> [(:alias %) (:network %)]
                              (remove nil?)
                              (str/join " · ")))]]
     [:tr [:th "Its balance, after the payments of this page"]
      [:td (answer-cell balance #(sats-str (:msat % 0)))]]]]))

(defn- boost-results
  "The payments of a `boost`, one row for each recipient."
  [{:keys [sats payments] :as boost}]
  (let [paid (fn [{:keys [preimage fees-msat proven?]}]
               (list "Paid"
                     (when fees-msat
                       (str ", with a fee of " (sats-str fees-msat)))
                     (when (true? proven?)
                       ", and the preimage is the proof of the invoice")
                     (when (false? proven?)
                       [:span.warning (str ". The wallet tells so, and its "
                                           "preimage is no proof of the "
                                           "invoice. Do not pay again.")])
                     (when preimage
                       (list [:br]
                             [:span.muted "preimage "
                              [:code (shorten preimage 24)]]))))]
    (list
     [:h2 (str "A boost of " sats " sats")]
     [:table
      [:thead
       [:tr [:th "Recipient"] [:th "Type"] [:th "Sats"]
        [:th "The answer of the wallet"]]]
      [:tbody
       (for [{:keys [recipient sats answer]} payments]
         [:tr
          [:td (or (:name recipient) (:address recipient))]
          [:td (:type recipient) (when (:fee recipient) " (fee)")]
          [:td sats]
          [:td (answer-cell answer paid)]])]])))

(defn wallet-page
  "The page of the wallet of the listener, with a form for the connection
  URI of Nostr Wallet Connect, from `answers` with these keys:

  - :episode, a map of :url, :feed and :item, which the form pays a boost
    for
  - :connection, without its secret, and :app, the public key of the
    inspector
  - :info, :wallet, :balance and :boost, what the wallet answered
  - :problem, why the inspector did not ask, or did not pay
  - :token, the token of the form, which a post that pays needs"
  [{:keys [episode problem connection boost token] :as answers}]
  (let [{:keys [url feed item]} episode
        block (when episode (resolve/value feed item))
        guid  (when episode (feed.guid/item-guid item))]
    (layout "Wallet"
            [:h1 "The wallet of the listener"]
            [:p (str "Give the connection URI of Nostr Wallet Connect that "
                     "your wallet made for this app. ")
             "The inspector asks the wallet what it takes and what it holds."]
            [:p.muted (str "The URI holds a secret key, and who has it can "
                           "spend from the wallet. ")
             (str "The inspector uses the URI for this one request, and keeps "
                  "it nowhere. ")
             "Give the connection a small budget in the wallet."]
            (when episode
              (list [:h2 "A boost for "
                     [:a {:href (feed-href "/item" url {:guid guid})}
                      (or (resolve/title item) "(untitled)")]]
                    (if block
                      (payments-table (:recipients block))
                      [:p.muted (str "The episode has no value block, so it "
                                     "has no recipients.")])))
            [:form {:method "post" :action "/wallet"}
             [:p [:input {:type         "password"
                          :name         "uri"
                          :placeholder  "nostr+walletconnect://…"
                          :required     true
                          :autocomplete "off"}]]
             [:input {:type "hidden" :name "token" :value token}]
             (when block
               (list [:input {:type "hidden" :name "url" :value url}]
                     [:input {:type "hidden" :name "guid" :value guid}]
                     [:p [:input {:type        "number"
                                  :name        "sats"
                                  :min         1
                                  :placeholder "sats"}]
                      " "
                      [:input {:type        "text"
                               :name        "message"
                               :placeholder "a message for the show"
                               :maxlength   500}]]
                     [:p.muted (str "Without an amount, the inspector asks "
                                    "the wallet and pays nothing. ")
                      (str "With an amount, the wallet pays each recipient "
                           "its share. This is real money.")]))
             [:button {:type "submit"} (if block
                                         "Ask the wallet, and pay the amount"
                                         "Ask the wallet")]]
            (when problem
              [:p.error problem])
            (when connection
              (wallet-facts answers))
            (when boost
              (boost-results boost)))))

(defn error-page
  "The page shown when what `heading` names failed, about `subject` if
  any. The `message` says why, and the Hiccup `more` what to try instead."
  ([heading subject message]
   (error-page heading subject message nil))
  ([heading subject message more]
   (layout "Error"
           [:h1 heading]
           (when subject [:p [:code subject]])
           [:pre message]
           more
           [:p [:a {:href "/"} "Back"]])))
