(ns dk.simongray.podcast.app.library-test
  "The library of a listener: subscribing, following a list, the step that
  fetches what is due through stand-ins, the views, the episodes across
  shows, the events of the player, the sync between two devices and the
  downloads."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.drop-in-search :as search]
            [dk.simongray.like-minded.document :as document]
            [dk.simongray.like-minded.replica :as replica]
            [dk.simongray.like-minded.store :as store]
            [dk.simongray.like-minded.transit :as transit]
            #?(:cljs [dk.simongray.like-minded.store.indexeddb :as indexeddb])
            [dk.simongray.mind-meld :as crdt]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.app.library.impl :as library.impl]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.discovery :as discovery]
            [dk.simongray.podcast.feed.guid :as feed.guid]
            [dk.simongray.podcast.feed.local :as local]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.profile.marks :as profile.marks]
            [dk.simongray.podcast.profile.opml :as profile.opml]
            [dk.simongray.podcast.profile.queue :as profile.queue]
            [dk.simongray.podcast.test-support :refer [fixture ranges run]]
            [dk.simongray.podcast.util.time :as time]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.websub :as websub]))

(def now #inst "2026-09-30T12:00:00Z")

(defn- at
  [seconds]
  (time/->inst (+ (inst-ms now) (* 1000 seconds))))

(defn- restored-report
  "The report of the last restore of `lib`, without :library/slow-format,
  which ClojureScript gives a restore of documents in EDN."
  [lib]
  (remove #(= :library/slow-format (:id %)) (:report (:restore lib))))

(def url "https://example.com/feed.xml")

(def show
  (rss/parse (fixture "feeds/namespace-example.xml")))

;; the example declares a guid that isn't a UUID, so its key is its URL's
(def guid (feed.guid/feed-guid show url))

(def fresh
  {:status 200 :url url :feed show :etag "\"v1\""})

(def other-url "https://example.org/other.xml")

(def other
  "Another show, with one episode."
  (-> show
      (assoc :podcast/guid "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" :rss/title "Other")
      (update :rss/items (fn [items] [(assoc (first items) :rss/guid {:content "o1"} :rss/title "Other one")]))))

(def newer
  "The show with a new episode."
  (update show :rss/items conj (assoc (first (:rss/items show)) :rss/guid {:content "new"} :rss/title "New one" :rss/pub-date #inst "2026-09-30T00:00:00Z")))

(def library
  (-> (library/make "phone" {:name "Phone" :config {:languages ["es"] :now-fn (constantly now)}})
      (library/subscribe url fresh {:auto-queue? true})))

(def picks
  "An OPML list with the other show."
  (opml/parse (str "<opml version=\"2.0\"><head><title>Picks</title></head><body>"
                   "<outline type=\"rss\" text=\"Other\" xmlUrl=\"" other-url "\"/>"
                   "</body></opml>")))

(deftest a-slim-library
  (let [make    (fn [config]
                  (-> (library/make "phone" {:name "Phone" :config (assoc config :now-fn (constantly now))})
                      (library/follow-list "https://example.org/picks.opml" picks {:title "Picks"})
                      (library/subscribe url fresh {:auto-queue? true})))
        fetch   (fn [u _]
                  (case u
                    "https://example.com/feed.xml"  {:status 200 :url u :feed newer :etag "\"v2\""}
                    "https://example.org/other.xml" {:status 200 :url u :feed other}))
        send    (fn [_] {:status 304 :headers {} :body ""})
        entries (fn [lib] (update-vals (:feeds lib) #(dissoc % :feed)))
        both    (fn [libs opts] (async/all #(library/refresh! % opts) libs))]
    (is (= (feeds/slim show) (get-in (make {:keep-fn feeds/slim}) [:feeds guid :feed])) "a subscription keeps the slim feed")
    (run
     (fn []
       (async/then (both [(make {}) (make {:keep-fn feeds/slim})] {:at (at 86400) :fetch fetch :send send})
                   (fn [[full thin]]
                     (is (= [[guid "new"]] (:added (:refresh thin))) "the new episode, found from the slim feed")
                     (is (= (:refresh full) (:refresh thin)))
                     (is (= (:profile full) (:profile thin)) "the episode queued alike")
                     (is (= (entries full) (entries thin)) "the same ETags and next checks")
                     (is (= (update-vals (:feeds full) (comp feeds/slim :feed)) (update-vals (:feeds thin) :feed))
                         "each feed slim, the new subscription's too")
                     (async/then (both [full thin] {:at (at (* 30 86400)) :fetch (fn [u _] {:status 304 :url u}) :send send})
                                 (fn [[full thin]]
                                   (is (= 2 (count (:feeds thin))))
                                   (is (= (entries full) (entries thin)) "the same next checks after a 304")))))))))

(deftest a-fresh-library
  (let [lib (library/make "phone" {:name "Phone" :platform "ios" :at now})]
    (is (= "phone" (:device lib)))
    (is (= {:name "Phone" :platform "ios"} (dissoc (get (crdt/devices (:profile lib)) "phone") :updated-at :device)))
    (is (= now (:clock (:profile lib))))
    (is (some? (:index lib)) "an index unless the config says :search? false")
    (is (= {} (:feeds lib)))
    (is (= library/default-config (:config lib)))
    (is (nil? (:index (library/make "phone" {:config {:search? false}}))))
    (is (= now (library/next-sync lib {:at now})) "due at once before the first sync")))

(deftest the-clock
  (let [id      [guid "https://example.com/ep0003"]
        clock   #(:clock (:profile %))
        clocked (assoc-in library [:config :now-fn] #(at 60))]
    (testing "each change is stamped with the time of the clock in the config"
      (is (= (at 60) (clock (library/unsubscribe clocked guid))))
      (is (= (at 60) (clock (library/update-profile clocked profile.queue/enqueue [guid "new"]))))
      (is (= (at 60) (clock (library/update-profile clocked (fn [p t] (profile.marks/star p id t))))) "a series of changes")
      (is (= (at 60) (library/next-sync clocked)))
      (is (= 3 (count (library/episodes clocked {:where {:age [0 nil]}}))) "the criteria that need the time"))
    (testing "or with the :at of the options, an instant"
      (is (= (at 120) (clock (library/unsubscribe clocked guid {:at (at 120)}))))
      (is (= (library/step clocked [:play id]) (library/step clocked [:play id] {:at (at 60)}))))
    (testing "and without a clock in the config, that of the platform"
      (is (<= (inst-ms now) (inst-ms (clock (library/unsubscribe (update library :config dissoc :now-fn) guid)))))
      (is (= "tablet" (:device (library/make "tablet")))))
    (run
     (fn []
       (async/then (library/sync! clocked [(library/backend (store/memory (atom {})) {:app "test"})])
                   (fn [lib] (is (= (at 60) (get-in lib [:sync :at])))))))))

(deftest subscribing
  (testing "the feed, the subscription and its settings"
    (is (= [url "\"v1\""] ((juxt :url :etag) (get-in library [:feeds guid]))))
    (is (= show (get-in library [:feeds guid :feed])))
    (let [subs (library/subscriptions library)]
      (is (= [guid] (keys subs)))
      (is (= "Podcasting 2.0 Namespace Example" (:title (subs guid))))
      (is (= "https://example.com/images/show/pci_avatar-massive.png" (:image (subs guid)))
          "the artwork, for a device that hasn't fetched the feed yet")
      (is (true? (:auto-queue? (:settings (subs guid)))))
      (is (= show (:feed (subs guid))))))
  (testing "a parsed feed in the place of the fetch"
    (let [lib (library/subscribe (library/make "phone") url show)]
      (is (= show (get-in lib [:feeds guid :feed])))
      (is (nil? (get-in lib [:feeds guid :etag])) "no validators")
      (is (= [guid] (keys (library/subscriptions lib))))))
  (testing "a fetch that gave no feed throws its diagnostic"
    (let [thrown (fn [fetched]
                   (try (library/subscribe library url fetched)
                        nil
                        (catch #?(:clj Exception :cljs :default) e (ex-data e))))]
      (is (= [:fetch/status 404] ((juxt :id :status) (thrown {:status 404 :url url}))))
      (is (= :fetch/unreachable (:id (thrown {:url url :error {:id :fetch/unreachable :url url}}))))))
  (testing "the views of the library"
    (is (= "Podcasting 2.0 Namespace Example" (:title (library/show library guid))))
    (is (= 3 (count (:episodes (library/show library guid)))))
    (is (nil? (library/show library "nope")))
    (let [id [guid "https://example.com/ep0003"]]
      (is (= "Episode 3 - The Future" (:title (library/episode library id))))
      (is (= "text/vtt" (:type (:transcript (library/episode library id)))) "the languages of the config choose the Spanish one")
      (is (= "text/plain" (:type (:transcript (library/episode library id {:languages ["en"] :transcript-types ["text/plain"]})))))
      (is (nil? (library/episode library [guid "nope"]))))
    (let [id       [guid "https://example.com/ep0003"]
          watching (library/update-profile library profile/prefer {:video? true})]
      (is (= "https://example.com/file-03.mp3" (:url (:enclosure (library/episode library id))))
          "audio unless the listener watches")
      (is (= "https://example.com/file-720.mp4" (:url (:enclosure (library/episode watching id)))))
      (is (= "https://example.com/file-03.mp3" (:url (:enclosure (library/episode watching id {:video? false}))))
          "unless the call says otherwise")))
  (testing "what the network adds to the views, asked with the config of the library"
    (let [down (fn [_] (throw (ex-info "down" {})))]
      (run
       (fn []
         (async/then (library/read-files! library [guid "https://example.com/ep0003"] {:send down :except #{:media}})
                     (fn [files]
                       (is (= [:chapters :transcript] (sort (keys files))) "the chapters file and the Spanish transcript, each asked")
                       (is (= :file/unreachable (:id (:error (:transcript files))))))))
       (fn []
         (async/then (library/fetch-linked-shows! library guid {:send down})
                     (fn [linked]
                       (is (= [:podroll :publisher] (sort (keys linked))) "no credentials of the index: the feeds themselves")
                       (is (every? #(= :fetch/unreachable (:id (:error %))) (:podroll linked)))
                       (is (= :fetch/unreachable (:id (:error (:publisher linked)))))
                       (is (= (:message (diagnostics/describe {:id :fetch/unreachable}))
                              (:message (:error (:publisher linked))))
                           "with the message of a feed, not of a file"))))
       (fn []
         (let [block {:type        "lightning" :method "keysend"
                      :recipients  [{:name "Host" :type "node" :address "01" :split 100}]
                      :time-splits [{:start-time 0 :duration 60 :remote-item {:feed-guid "fa" :item-guid "a"}}]}
               split (update show :rss/items (partial mapv #(assoc % :podcast/values [block])))
               lib   (library/subscribe (library/make "phone" {:config {:index {:key "K" :secret "S"}}}) url split)]
           (is (nil? (library/fetch-remote-values! lib [guid "nope"])))
           (async/then (library/fetch-remote-values! lib [guid "https://example.com/ep0003"] {:send down})
                       (fn [{:keys [remote-values remote-report]}]
                         (is (= {} remote-values))
                         (is (= [:value/remote-unresolved "down"] ((juxt :id :reason) (first remote-report))))
                         (is (re-find #"^https://api\.podcastindex\.org/" (:url (first remote-report))) "the index of the config, asked"))))))))
  (testing "unsubscribing"
    (let [lib (library/unsubscribe library guid)]
      (is (= {} (library/subscriptions lib)))
      (is (= {} (:feeds lib)))
      (is (false? (profile/subscribed? (:profile lib) guid))))))

(deftest the-search-index
  (let [lib (-> (library/make "phone" {:config {:search? true}})
                (library/subscribe url fresh))]
    (is (= 4 (count (:docs (:index lib)))) "the show and its three episodes")
    (is (= (search/query (:index lib) "future")
           (search/query (library.impl/index lib) "future"))
        "an index built again from the feeds")
    (is (= [guid "https://example.com/ep0003"] (:id (first (library/search lib "future")))))
    (is (= 0 (count (:docs (:index (library/unsubscribe lib guid))))))
    (is (nil? (library/search (library/make "phone" {:config {:search? false}})
                              "future"))
        "no index, no search")
    (testing "each result with its feed and item"
      (let [[episode] (library/search lib "future")]
        (is (= "Episode 3 - The Future" (:rss/title (:item episode))))
        (is (= guid (feed.guid/feed-key (:feed episode) url)))))
    (testing "a snippet of the text of a result around the words that match, the notes before the title"
      (let [[episode] (library/search lib "future")]
        (is (= {:field :description
                :parts [{:text "A look into the "} {:text "future" :match? true}
                        {:text " of podcasting and how we get to Podcasting 2.0!"}]}
               (library/snippet lib episode "future")))
        (is (= {:field :title :parts [{:text "Episode 3 - The "} {:text "Future" :match? true}]}
               (library/snippet lib episode "title:future"))
            "the field of a query")
        (is (= "…" (:text (peek (:parts (library/snippet lib episode "future" {:width 30}))))))
        (is (nil? (library/snippet lib episode "present")))))
    (testing "the words that matched with typos"
      (let [found (library/search lib "futrue ")]
        (is (= ["future"] (get-in (meta found) [:typos "futrue"])))
        (is (= {"futrue" ["future"]} (:typos (first found))))))
    (testing "the criteria of an episode, which leave the show out"
      (let [id     [guid "https://example.com/ep0003"]
            played (library/update-profile lib profile/mark-played id)]
        (is (some #(= guid (:id %)) (library/search lib "podcasting")) "the show matches")
        (is (every? #(vector? (:id %)) (library/search lib "podcasting" {:where {}})))
        (is (= [id] (mapv :id (library/search lib "future" {:where {:status :unplayed}}))))
        (is (= [] (library/search played "future" {:where {:status :unplayed}})))
        (is (= [id] (mapv :id (library/search played "future" {:where {:status :played} :limit 5}))))
        (is (= [] (library/search lib "future" {:where {:text "-futu"}})) "a :not alone holds for nothing")
        (is (= [id] (mapv :id (library/search lib "future" {:where {:not {:text "present"}}}))))))
    (testing "the episodes whose title and notes match a text, read from the index"
      (is (= (library/episodes (assoc lib :index nil) {:where {:text "futu"}})
             (library/episodes lib {:where {:text "futu"}})))
      (is (= ["Episode 3 - The Future"]
             (mapv (comp :rss/title second) (library/episodes lib {:where {:text "futu"}})))))))

#?(:cljs
   (deftest a-library-in-indexeddb
     (if-not (exists? js/indexedDB)
       (is true "no IndexedDB here")
       (let [name    (str "podcast-test-" (random-uuid))
             s       (indexeddb/store name)
             ids     #(mapv :id (library/search % "future"))
             forget! #(js/Promise. (fn [resolve _]
                                     (set! (.-onsuccess (.deleteDatabase js/indexedDB name)) resolve)))]
         (run #(library/save! library s)
              #(async/then (library/restore! (library/make "phone") s)
                           (fn [restored]
                             (is (= [] (restored-report restored)))
                             (is (= (ids library) (ids restored)))))
              forget!)))))

(defn- show-of
  "A feed of `title` in `language` with the podcast:guid `guid`, and an
  episode of each title, notes and perhaps number of `episodes`, with its
  index as its guid."
  [guid title language episodes]
  (rss/parse
   (str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\""
        " xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">"
        "<channel><title>" title "</title><language>" language "</language>"
        "<podcast:guid>" guid "</podcast:guid>"
        (apply str (map-indexed (fn [i [episode notes number]]
                                  (str "<item><title>" episode "</title>"
                                       "<guid>" i "</guid>"
                                       (when number
                                         (str "<itunes:episode>" number
                                              "</itunes:episode>"))
                                       "<description>" notes "</description>"
                                       "<enclosure url=\"https://example.com/" i
                                       ".mp3\" type=\"audio/mpeg\" length=\"1\"/>"
                                       "</item>"))
                                episodes))
        "</channel></rss>")))

(def coffee "11111111-1111-5111-8111-111111111111")
(def brew "22222222-2222-5222-8222-222222222222")

(def shelf
  "A library of two shows about coffee, one in Danish and one about bikes."
  (reduce (fn [lib [guid title language episodes]]
            (library/subscribe lib
                               (str "https://example.com/" guid ".xml")
                               (show-of guid title language episodes)))
          (library/make "phone" {:config {:search? true}})
          [[coffee "Coffee Talk" "en-US"
            [["Espresso grinders" "Burr grinders for espresso, and how fine to grind the beans."]
             ["Roasting at home" "Roasting coffee beans at home in a popcorn machine."]
             ["Tea" "A week of green tea instead."]]]
           [brew "Brew Lab" "en"
            [["Machines" "Pressure, burr grinders and the shot."]
             ["Roasting basics" "Light and dark roasting of coffee beans."]
             ["Grinders compared" "Conical and flat burr grinders."]]]
           ["33333333-3333-5333-8333-333333333333" "Kaffe" "da"
            [["Espresso" "Espresso på dansk."]
             ["Cykler" "Cykling i regnvejr."]]]
           ["44444444-4444-5444-8444-444444444444" "Bikes" "en"
            [["Commuting" "Commuting by bicycle in the rain."]
             ["Gears" "The gears of a bicycle."]]]]))

(deftest recommendations
  (let [grinders [coffee "0"]
        titles   (fn [found]
                   (mapv (comp :rss/title :item) found))
        ;; the episodes of the shelf have a line of notes each, so most of
        ;; these tests take a word that an episode uses once, and a result
        ;; that holds one of its words
        loose    {:min-term-freq 1 :min-match 1}
        rec      (fn
                   ([lib ids]
                    (library/recommend lib ids loose))
                   ([lib ids opts]
                    (library/recommend lib ids (merge loose opts))))
        liked    (fn
                   ([lib]
                    (titles (rec lib [grinders])))
                   ([lib opts]
                    (titles (rec lib [grinders] opts))))]
    (testing "the unplayed episodes most like one, of other shows in its language"
      (is (= ["Grinders compared" "Machines"] (liked shelf)))
      (is (= ["espresso" "grinders"]
             (take 2 (map first (:terms (meta (rec shelf [grinders])))))))
      (is (= [["grinders" "burr"] ["grinders" "burr"]]
             (map #(take 2 (:terms %)) (rec shelf [grinders])))
          "each with the words of the query that it holds, the best first")
      (is (nil? (library/recommend (library/make "phone" {:config {:search? false}})
                                   [grinders]))
          "no index, no recommendations"))
    (testing "the words and the results that the evidence takes, by default"
      (is (= #{"espresso" "grinders"}
             (set (map first (:terms (meta (library/recommend shelf [grinders] {:min-match 1}))))))
          "words that it uses twice, or once in its title")
      (is (= [] (titles (library/recommend shelf [grinders])))
          "and results that hold two of them, which none here does"))
    (testing "the options"
      (is (= ["Grinders compared" "Machines" "Roasting at home"]
             (liked shelf {:same-show? true}))
          "but not Tea, which only the name of its show links")
      (is (= ["Grinders compared" "Machines" "Espresso"]
             (liked shelf {:same-language? false})))
      (is (= ["Espresso"] (liked shelf {:where {:language "da"}}))
          "a language of the criteria in place of the episode's")
      (is (= ["Grinders compared"] (liked shelf {:max-per-show 1})))
      (is (= ["Grinders compared"] (liked shelf {:limit 1}))))
    (testing "what the listener has played or hidden"
      (is (= ["Machines" "Roasting basics"]
             (liked (library/update-profile shelf profile/mark-played [brew "2"]))))
      (is (= ["Machines" "Roasting basics"]
             (liked (library/update-profile shelf profile/hide [brew "2"]))))
      (is (= ["Grinders compared" "Machines"]
             (liked (library/update-profile shelf profile/hide [brew "2"])
                    {:where {:hidden? #{true false}}}))))
    (testing "the episodes that the listener heard last, without ids"
      (let [heard (library/update-profile shelf profile/progress grinders 60)]
        (is (= [] (titles (rec shelf nil))))
        (is (= (liked shelf) (titles (rec heard nil))))
        (is (= [] (titles (rec (library/update-profile heard profile/forget grinders) nil)))
            "but not one that the profile forgot")
        (is (= [] (titles (rec heard nil {:from {:not {:show coffee}}})))
            "nor one that the criteria of :from leave out")))
    (testing "the episodes to start from that the criteria of :from take"
      (is (= [] (liked shelf {:from {:not {:show coffee}}}))))
    (testing "the episodes of a show that the library doesn't hold"
      (let [grind "55555555-5555-5555-8555-555555555555"
            feed  (show-of grind "Grind Weekly" "en"
                           [["Burrs" "Burr grinders for espresso beans."]])]
        (is (= [] (titles (rec shelf [[grind "0"]]))))
        (is (= ["Espresso grinders" "Grinders compared" "Machines" "Roasting at home"]
               (titles (rec shelf [[grind "0"]]
                            {:feeds {grind {:feed feed
                                            :url  "https://example.com/grind.xml"}}}))))))
    (testing "episodes unliked, and the weights of episodes"
      (let [weight (fn [ids opts]
                     (->> (rec shelf ids opts)
                          meta
                          :terms
                          (into {})))
            both   [grinders [coffee "1"]]]
        (is (< (get (weight [grinders] {:unlike [[brew "1"]]}) "beans")
               (get (weight [grinders] {}) "beans")))
        (is (< (get (weight both {}) "espresso")
               (get (weight both {:weight-fn {grinders 4 [coffee "1"] 1}})
                    "espresso")))))
    (testing "the words that link episodes by chance"
      (let [hosted (reduce (fn [lib [guid title episodes]]
                             (library/subscribe lib
                                                (str "https://example.com/" guid ".xml")
                                                (assoc (show-of guid title "en" episodes)
                                                       :podcast/people [{:content "Adam Curry"}])))
                           (library/make "phone" {:config {:search? true}})
                           [[coffee "Async Talk" [["Runtimes" "Adam on async runtimes, Mon 22 Sep, with @js."]]]
                            [brew "Adam's Kitchen" [["Recipes" "Adam cooks on Mon 22 Sep, with @js."]
                                                    ["Async" "Async runtimes and threads."]]]
                            ;; so that "on" and "with" are words that most shows use
                            ["44444444-4444-5444-8444-444444444444" "Bikes"
                             [["Gears" "Riding on gears with care."]]]])
            terms  (set (map first (:terms (meta (library/recommend hosted [[coffee "0"]])))))]
        (is (= ["Async"] (titles (library/recommend hosted [[coffee "0"]]))))
        (is (empty? (filter terms ["adam" "mon" "sep" "js" "22"]))
            "a host, a date and a handle, but not a topic")))
    (testing "the number of an episode, which links it to nothing"
      (let [numbered (reduce (fn [lib [guid title episodes]]
                               (library/subscribe lib
                                                  (str "https://example.com/" guid ".xml")
                                                  (show-of guid title "en" episodes)))
                             (library/make "phone" {:config {:search? true}})
                             [[coffee "Async Talk" [["#561: Runtimes" "Async runtimes." 561]]]
                              [brew "Accidental" [["561: Market your bug" "Bugs and markets."]
                                                  ["Async" "Async runtimes and threads."]]]
                              ["44444444-4444-5444-8444-444444444444" "Bikes"
                               [["Gears" "The gears of a bicycle."]]]])]
        (is (= ["Async"] (titles (library/recommend numbered [[coffee "0"]]))))))))

(deftest the-cap-of-a-common-word
  (let [episodes (fn [n] (for [i (range n)] [(str "Episode " i) "Notes."]))
        lib      (-> (library/make "phone" {:config {:search? true}})
                     (library/subscribe "https://example.com/en.xml" (show-of coffee "Talk" "en" (episodes 250)))
                     (library/subscribe "https://example.com/da.xml" (show-of brew "Snak" "da" (episodes 100))))
        cap      #(library.impl/common-cap lib [(library.impl/seed lib %)])]
    (is (= 20 (cap [coffee "0"])) "8% of the episodes in the language of the episode")
    (is (= 10 (cap [brew "0"])) "but never fewer than 10")))

(deftest following-a-list
  (let [lib (library/follow-list library "https://example.org/picks.opml" picks {:title "Picks" :folder "Picks" :etag "\"p1\""})]
    (is (= {"https://example.org/picks.opml" {:title "Picks" :folder "Picks"}} (profile.opml/lists (:profile lib))))
    (is (= "\"p1\"" (get-in lib [:fetched-lists "https://example.org/picks.opml" :etag])))
    (is (= (at 86400) (get-in lib [:fetched-lists "https://example.org/picks.opml" :next-check])))
    (is (= 2 (count (library/subscriptions lib))) "the show of the list joined")
    (let [joined (get (library/subscriptions lib) (feed.guid/feed-guid nil other-url))]
      (is (= other-url (:url joined)) "under the guid of its URL")
      (is (nil? (:feed joined)) "not fetched yet"))
    (let [gone (library/unfollow-list lib "https://example.org/picks.opml")]
      (is (= {} (profile.opml/lists (:profile gone))))
      (is (= {} (:fetched-lists gone)))
      (is (= 2 (count (library/subscriptions gone))) "the subscriptions stay"))))

(deftest the-step-that-fetches-what-is-due
  (let [lib     (library/follow-list library "https://example.org/picks.opml" picks {:title "Picks" :folder "Picks"})
        later   (at 86400)
        asked   (atom [])
        fetch   (fn [url _]
                  (swap! asked conj url)
                  (case url
                    "https://example.com/feed.xml" {:status 200 :url url :feed newer :etag "\"v2\"" :permanent-url "https://example.com/moved.xml"}
                    "https://example.org/other.xml" {:status 200 :url url :feed other}
                    (throw (ex-info "no route" {:url url}))))
        send    (fn [{:keys [url headers]}]
                  (swap! asked conj [url (get headers "If-None-Match")])
                  {:status 304 :headers {} :body ""})]
    (run
     (fn []
       (async/then (library/refresh! lib {:at later :fetch fetch :send send})
                   (fn [{{:keys [added moved report counts seconds]} :refresh :as lib'}]
                     (testing "a summary of the feeds: the new subscription's and the due one"
                       (is (= {:checked 2 :unchanged 0 :changed 2 :failed 0 :left 0} counts))
                       (is (= 0.0 seconds) "by the clock of the config, which stands still here"))
                     (testing "the list asked with its validators, and unchanged"
                       (is (some #(= ["https://example.org/picks.opml" nil] %) @asked))
                       (is (= (at (* 2 86400)) (get-in lib' [:fetched-lists "https://example.org/picks.opml" :next-check]))))
                     (testing "the feed of the new subscription fetched and kept, under the guid that it declares"
                       (is (= "Other" (:rss/title (:feed (get (library/subscriptions lib') "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f")))))
                       (is (= #{guid "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"} (set (keys (library/subscriptions lib')))) "the guid of the URL is gone")
                       (is (= "Picks" (:folder (:settings (get (library/subscriptions lib') "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f")))) "the folder of the list moved with it")
                       (is (= 2 (count (:feeds lib')))))
                     (testing "the due feed fetched, its new episode found and queued, and its move applied"
                       (is (= [[guid "new"]] added))
                       (is (= [[guid "new"]] (profile.queue/queue (:profile lib'))) "auto-queue")
                       (is (= {guid "https://example.com/moved.xml"} moved))
                       (is (= "https://example.com/moved.xml" (:url (get (library/subscriptions lib') guid))))
                       (is (= "https://example.com/moved.xml" (get-in lib' [:feeds guid :url])))
                       (is (= "\"v2\"" (get-in lib' [:feeds guid :etag]))))
                     (testing "a feed that declares no guid moved, so it gives itself another, and keeps its key"
                       (let [moved-guid (feed.guid/from-url "https://example.com/moved.xml")]
                         (is (= [[:fetch/guid-changed guid guid moved-guid]] (map (juxt :id :feed-guid :old :new) report)))
                         (is (= moved-guid (get-in lib' [:profile :subscriptions guid :guid]) (get-in lib' [:feeds guid :guid]))
                             "noted for the other devices, and for a takeover check")
                         (is (= [guid "new"] (library/episode-id lib' (get-in lib' [:feeds guid :feed]) {:rss/guid {:content "new"}}))
                             "and the ids of its episodes keep it too"))))))
     (fn []
       (reset! asked [])
       (async/then (library/refresh! lib {:at    later
                                          :fetch (fn [url _] (throw (ex-info "down" {:url url})))
                                          :send  (fn [_] (throw (ex-info "down" {})))})
                   (fn [{{:keys [added report counts]} :refresh :as lib'}]
                     (testing "nothing comes: the library stays, and the report says why"
                       (is (= [] added))
                       (is (= {:checked 2 :unchanged 0 :changed 0 :failed 2 :left 0} counts))
                       (is (= #{:fetch/unreachable} (set (map :id report))))
                       (is (= 3 (count report)) "the list, the new subscription and the due feed")
                       (is (= show (get-in lib' [:feeds guid :feed])))
                       (is (= 1 (:failures (get-in lib' [:feeds guid])))))))))))

(deftest a-screen-of-shows-and-the-choices-of-a-show
  (let [other-guid "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"
        lib        (library/subscribe library other-url {:status 200 :url other-url :feed other})
        counts     #(into {} (map (juxt :feed-guid :counts)) (library/shows %))]
    (testing "the shows by title, with the counts of their episodes"
      (is (= [["Other" 1] ["Podcasting 2.0 Namespace Example" 3]]
             (map (juxt :title (comp :episodes :counts)) (library/shows lib))))
      (is (= #inst "2020-10-09T04:30:38Z" (:newest (second (library/shows lib)))))
      (let [lib (-> lib
                    (library/update-profile profile/mark-played [guid "https://example.com/ep0001"])
                    (library/update-profile profile/progress [guid "https://example.com/ep0002"] 60 3600)
                    (library/update-profile profile.queue/enqueue [guid "https://example.com/ep0003"]))]
        (is (= {:episodes 3 :unplayed 1 :in-progress 1 :played 1 :inbox 0 :queued 1} (get (counts lib) guid)))))
    (testing "in the listener's order, under their own title"
      (let [lib (-> lib
                    (library/update-profile profile/move-show guid 0)
                    (library/update-profile profile/configure other-guid {:custom-title "Mine"}))]
        (is (= [guid other-guid] (map :feed-guid (library/shows lib))))
        (is (= "Mine" (:title (second (library/shows lib))) (:title (library/show lib other-guid))))))
    (testing "the episodes of a show in the order that the listener chose for it"
      (let [titles #(map :rss/title (:episodes (library/show % guid)))
            oldest (library/update-profile lib profile/configure guid {:order :oldest})]
        (is (= ["Episode 3 - The Future" "Episode 2 - The Present" "Episode 1 - The Past"] (titles lib)))
        (is (= ["Episode 1 - The Past" "Episode 2 - The Present" "Episode 3 - The Future"] (titles oldest)))
        (is (= (titles oldest) (map (comp :rss/title second) (library/episodes oldest {:feed-guids [guid]}))))))
    (testing "only the new episodes that meet the show's criteria join the queue"
      (let [later   (at 86400)
            fetch   (fn [url _] {:status 200 :url url :feed (if (= url other-url) other newer)})
            refresh #(library/refresh! % {:at later :fetch fetch :send (fn [_] {:status 304 :headers {} :body ""})})
            picky   (library/update-profile library profile/configure guid {:join-where {:text "nothing like it"}})
            broad   (library/update-profile library profile/configure guid {:join-where {:text "new"}})]
        (run (fn []
               (async/then (async/all [(refresh picky) (refresh broad)])
                           (fn [[picky broad]]
                             (is (= [[guid "new"]] (get-in picky [:refresh :added])) "though it's new")
                             (is (= [] (profile.queue/queue (:profile picky))))
                             (is (= [[guid "new"]] (profile.queue/queue (:profile broad))))))))))))

(deftest hidden-episodes-out-of-the-lists
  (let [id  [guid "https://example.com/ep0003"]
        lib (library/update-profile library profile/hide id)
        ids #(set (map (fn [[feed item]] (library/episode-id lib feed item)) %))]
    (is (= 3 (count (library/episodes library))))
    (is (= 2 (count (library/episodes lib))))
    (is (not (contains? (ids (library/episodes lib)) id)))
    (is (= #{id} (ids (library/episodes lib {:where {:hidden? true}}))))
    (is (= 3 (count (library/episodes lib {:where {:hidden? #{true false}}}))))
    (is (= 3 (count (:episodes (library/show lib guid)))) "but the page of the show has them all")))

(deftest the-episodes-of-overcasts-export
  (let [list {:subscriptions [{:xml-url url :text "S" :subscribed "1"
                               :episodes [{:enclosure-url "https://example.com/file-03.mp3?tracking=1" :played "1"}
                                          {:enclosure-url "http://example.com/file-02.mp3" :progress "754"}
                                          {:enclosure-url "https://elsewhere.example/x.mp3" :played "1"}]}]}
        lib  (library/episodes-from-opml library list)]
    (is (= {[guid "https://example.com/ep0003"] {:played? true}
            [guid "https://example.com/ep0002"] {:position 754}}
           (update-vals (profile/episode-entries (:profile lib)) #(select-keys % [:played? :position])))
        "by the media of the feeds that the library has, whatever its query and its scheme")
    (is (= [["https://example.com/file-02.mp3" 754] ["https://example.com/file-03.mp3" nil]]
           (map (juxt :enclosure-url :progress) (:episodes (first (:subscriptions (library/to-opml lib)))))))))

(deftest the-options-of-one-refresh
  (let [picks-url "https://example.org/picks.opml"
        lib       (library/follow-list library picks-url picks {:title "Picks"})
        later     (at 86400)
        sent      (atom [])
        fetch     (fn [u _]
                    (case u
                      "https://example.com/feed.xml"  {:status 304 :url u}
                      "https://example.org/other.xml" {:status 200 :url u :feed other}))
        unchanged (fn [request] (swap! sent conj request) {:status 304 :headers {} :body ""})
        busy      (fn [request] (swap! sent conj request) {:status 503 :headers {"retry-after" "600"} :body ""})
        cadence   {:silent 60 :complete 60}
        next-of   #(get-in (feeds/add {} other-url {:status 200 :url other-url :feed other} later %) ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" :next-check])]
    (is (not= (next-of cadence) (next-of {})) "a cadence that tells the two apart")
    (run
     (fn []
       (async/then (library/refresh! lib {:at later :fetch fetch :send unchanged :list-seconds 60 :cadence cadence})
                   (fn [lib']
                     (is (= (at (+ 86400 60)) (get-in lib' [:fetched-lists picks-url :next-check])) "the :list-seconds of the call")
                     (is (= (next-of cadence) (get-in lib' [:feeds "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" :next-check]))
                         "the :cadence of the call for the feed of a new subscription"))))
     (fn []
       (reset! sent [])
       (async/then (library/refresh! lib {:at             later
                                          :fetch          fetch
                                          :send           busy
                                          :credentials-of #(when (= picks-url %) {:username "simon" :password "x"})})
                   (fn [{{:keys [report]} :refresh :as lib'}]
                     (is (contains? (:headers (first @sent)) "Authorization") "the credentials of the list")
                     (is (= [[:fetch/status 503]] (map (juxt :id :status) report)))
                     (is (< (inst-ms (time/now)) (inst-ms (get-in lib' [:fetched-lists picks-url :next-check])))
                         "not before the Retry-After")))))))

(deftest an-offline-refresh
  (let [lib   (library/follow-list library "https://example.org/picks.opml" picks {:title "Picks"})
        later (at (* 2 86400))
        sent  (atom 0)
        down  (fn [u] (swap! sent inc) (throw (ex-info (str "down: " u) {:type ::http/unresolved :url u})))
        opts  {:at later :fetch (fn [u _] (down u)) :send (fn [{:keys [url]}] (down url))}]
    (run
     #(async/then (library/refresh! lib opts)
                  (fn [lib']
                    (is (= 3 @sent) "the list, the show and the new one from the list, at two hosts")
                    (is (= [:refresh/offline] (map :id (:report (:refresh lib')))))
                    (is (= (:feeds lib) (:feeds lib')) "no feed counts it as a failure")
                    (is (= (:fetched-lists lib) (:fetched-lists lib')) "nor the list, which stays due")
                    (is (= {:checked 0 :unchanged 0 :changed 0 :failed 0 :left 2} (:counts (:refresh lib')))
                        "the show and the new one from the list stay due")
                    (is (= (at (+ (* 2 86400) 300)) (library/next-refresh lib' {:at later}))
                        "the next refresh waits a while rather than at once")
                    (is (= (at (* 3 86400)) (library/next-refresh lib' {:at (at (* 3 86400))})))))
     (fn []
       (reset! sent 0)
       (async/then (library/refresh! lib (assoc opts :online? false))
                   (fn [lib']
                     (is (= [0 [:refresh/offline]] [@sent (map :id (:report (:refresh lib')))])
                         "and nothing is fetched when the device knows it's offline")))))))

(deftest when-to-refresh-again
  (let [next-check (get-in library [:feeds guid :next-check])]
    (is (= next-check (library/next-refresh library)) "the next check of its one feed")
    (is (= (at 999999) (library/next-refresh library {:at (at 999999)})) "never before the time of the call")
    (is (nil? (library/next-refresh (library/make "tablet"))) "nothing to fetch")
    (is (nil? (library/next-refresh (assoc-in library [:feeds guid :gone?] true))) "a feed that's gone is never due")
    (is (= now (library/next-refresh (library/follow-list library "https://example.org/picks.opml" picks {:title "Picks"})))
        "the show of a list, never fetched, at once")))

(deftest a-subscription-that-never-answered
  (let [dead  "https://example.net/gone.xml"
        lib   (library/subscribe (library/make "phone") dead)
        later (at 86400)
        fetch (fn [u _] {:status 404 :url u})]
    (is (= later (library/next-refresh lib {:at later})) "due at once")
    (run #(async/then (library/refresh! lib {:at later :fetch fetch})
                      (fn [lib']
                        (is (= (at (+ 86400 900))
                               (library/next-refresh lib' {:at later}))
                            "and after a failure, as a feed that failed once"))))))

(deftest the-new-episodes-to-announce
  (let [fetch   (fn [url _] {:status 200 :url url :feed newer})
        opts    {:at (at 86400) :fetch fetch}
        noisy   (library/update-profile library profile/prefer {:notify? true})
        new-one {:id [guid "new"] :title "New one" :published #inst "2026-09-30T00:00:00Z"}]
    (run #(async/then (library/refresh! noisy opts)
                      (fn [lib]
                        (let [[found :as all] (library/notifications lib)]
                          (is (= 1 (count all)))
                          (is (= [guid "Podcasting 2.0 Namespace Example" [new-one]]
                                 ((juxt :feed-guid :title :episodes) found))))
                        (is (= [] (library/notifications (library/update-profile lib profile/configure guid {:notify? false})))
                            "the show's own setting over the listener's")
                        (is (= [] (library/notifications (library/update-profile lib profile/mark-played [guid "new"])))
                            "nor an episode played already")))
         #(async/then (library/refresh! library opts)
                      (fn [lib]
                        (is (= [[guid "new"]] (:added (:refresh lib))))
                        (is (= [] (library/notifications lib)) "silent unless the listener says"))))))

(deftest pushes-from-a-websub-hub
  (let [hubs     {:hubs ["https://hub.example.org/"] :topic url}
        lib      (atom (-> (library/make "server" {:config {:now-fn (constantly now)}})
                           (library/subscribe url (assoc fresh :websub hubs) {:auto-queue? true})
                           (library/subscribe other-url {:status 200 :url other-url :feed other})))
        websubs  (atom {})
        origin   "https://app.example.net"
        callback (fn [_ _] (str origin "/websub/" (websub/token)))
        handler  (websub/handler
                  (assoc (websub/handler-options #(deref websubs))
                         :verified! #(swap! websubs websub/verified % now)
                         :deliver!  (fn [{:keys [request] :as delivery}]
                                      (let [[feed-guid] (websub/subscription-of @websubs request)]
                                        (swap! lib library/pushed feed-guid delivery)))))
        asked    (library/hub-requests @lib @websubs callback)
        _        (swap! websubs websub/asked guid (get asked guid) now)
        {:keys [hub topic secret] :as pending} (get-in @websubs [guid :pending])
        uri      (str/replace-first (:callback pending) origin "")
        push!    (fn [xml]
                   (let [body (bytes/encode xml)]
                     (:status (handler {:request-method :post
                                        :uri            uri
                                        :headers        {"content-type"    "application/rss+xml"
                                                         "x-hub-signature" (websub/signature "sha256" secret body)}
                                        :body           body}))))
        new-item (str "<item><title>Pushed</title><guid>pushed</guid>"
                      "<pubDate>Wed, 30 Sep 2026 11:00:00 GMT</pubDate></item>")]
    (testing "a subscription for the show whose feed names a hub, at its first hub, with a secret"
      (is (= [guid] (keys asked)))
      (is (= ["https://hub.example.org/" url] [hub topic]))
      (is (some? secret))
      (is (false? ((:allow-pred (get asked guid)) "http://127.0.0.1/admin"))
          "a feed chooses its hub, so no request goes to a private network"))
    (testing "confirmed when the hub checks it, and then nothing more until its renewal"
      (is (= "c1" (:body (handler {:request-method :get
                                   :uri            uri
                                   :query-string   (str "hub.mode=subscribe&hub.topic=" url
                                                        "&hub.challenge=c1&hub.lease_seconds=864000")}))))
      (is (= :active (get-in @websubs [guid :state])))
      (is (= {} (library/hub-requests @lib @websubs callback)))
      (is (= [guid] (keys (library/hub-requests @lib @websubs callback {:at (get-in @websubs [guid :renew-at])})))))
    (testing "a pushed feed applied as a refresh is, with its new episode queued"
      (is (= 200 (push! (str/replace-first (fixture "feeds/namespace-example.xml") "<item>" (str new-item "<item>")))))
      (is (= [[guid "pushed"]] (:added (:refresh @lib))))
      (is (= [[guid "pushed"]] (profile.queue/queue (:profile @lib)))))
    (testing "a body that's no feed makes the feed due at once"
      (is (= 200 (push! "<html></html>")))
      (is (= [:library/push-not-applied] (map :id (:report (:refresh @lib)))))
      (is (= now (get-in @lib [:feeds guid :next-check]))))
    (testing "an unsubscription once the listener leaves the show"
      (let [left (library/hub-requests (library/unsubscribe @lib guid) @websubs callback)]
        (is (= :unsubscribe (get-in (websub/asked @websubs guid (get left guid) now) [guid :pending :mode])))))
    (testing "a subscription at the new hub when the feed names another"
      (let [moved (assoc-in @lib [:feeds guid :websub :hubs] ["https://other.example.org/"])]
        (is (= "https://other.example.org/" (:url (get (library/hub-requests moved @websubs callback) guid))))))))

(deftest the-queue-for-a-page
  (let [ep3 [guid "https://example.com/ep0003"]
        ep2 [guid "https://example.com/ep0002"]
        lib (-> library
                (library/update-profile #(profile.queue/dequeue-all %1 (profile.queue/queue %1) %2))
                (library/update-profile profile.queue/enqueue ep3)
                (library/update-profile profile.queue/enqueue ep2)
                (library/step [:progress ep3 754 3600] {:at (at 10)}))
        {:keys [episodes remaining]} (library/queue lib)]
    (is (= [ep3 ep2] (map :id episodes)) "the current episode first")
    (is (= {:title     "Episode 3 - The Future"
            :show      "Podcasting 2.0 Namespace Example"
            :position  754
            :duration  3600
            :remaining 2846}
           (select-keys (first episodes) [:title :show :position :duration :remaining])))
    (is (= 0 (:position (second episodes))) "one that hasn't started")
    (is (= 2846 remaining) "the time left of those whose duration is known")
    (is (= {:episodes [] :remaining 0}
           (library/queue (library/update-profile lib #(profile.queue/dequeue-all %1 [ep3 ep2] %2)))))))

(deftest the-inbox-for-a-page
  (let [[ep1 ep2 ep3] (map #(vector guid (str "https://example.com/ep000" %)) [1 2 3])
        arrived       (fn [lib id]
                        (library/update-profile lib #(crdt/write %1 [:inbox id] (constantly {:removed? false}) %2)))
        lib           (reduce arrived
                              (library/update-profile library #(profile.queue/dequeue-all %1 (profile.queue/queue %1) %2))
                              [ep2 ep3 ep1])
        {:keys [episodes]} (library/inbox lib)]
    (is (= [ep3 ep2 ep1] (map :id episodes)) "the newest first, whatever the order they arrived in")
    (is (= {:title     "Episode 3 - The Future"
            :show      "Podcasting 2.0 Namespace Example"
            :published #inst "2020-10-09T04:30:38Z"}
           (select-keys (first episodes) [:title :show :published])))
    (let [back (-> lib
                   (library/update-profile profile.queue/dismiss ep2)
                   (library/update-profile profile.queue/undismiss ep2))]
      (is (= [ep3 ep2 ep1] (map :id (:episodes (library/inbox back))))
          "and one put back takes its place again"))
    (is (= {:episodes []}
           (library/inbox (library/update-profile lib #(profile.queue/dismiss-all %1 [ep1 ep2 ep3] %2)))))))

(deftest what-a-player-needs
  (let [id    [guid "https://example.com/ep0003"]
        lib   (-> library
                  (library/step [:progress id 754 3600] {:at (at 10)})
                  (library/update-profile profile/configure guid {:playback-rate 1.5}))
        view  (library/playback lib id)
        files {:chapters {:source   :file
                          :document {:chapters [{:start-time 0 :title "Intro"}
                                                {:start-time 60 :title "Ads" :toc false}
                                                {:start-time 94 :title "Talk" :img "https://x/i.png"}]}}
               :duration 3599.5}]
    (is (= {:id            id
            :src           "https://example.com/file-03.mp3"
            :local?        false
            :position      754
            :duration      3600
            :playback-rate 1.5
            :played?       false
            :title         "Episode 3 - The Future"
            :show          "Podcasting 2.0 Namespace Example"}
           (select-keys view [:id :src :local? :position :duration :playback-rate
                              :played? :title :show])))
    (is (= ["https://example.com/ep3_chapters.json" "text/vtt"] [(:chapters-url view) (:type (:transcript view))]))
    (is (= {:title        "Episode 3 - The Future"
            :artist       "John Doe"
            :album        "Podcasting 2.0 Namespace Example"
            :artwork      [{:src "https://example.com/images/ep3/pci_avatar-massive.png"}]
            :chapter-info []}
           (:metadata view))
        "the metadata of the Media Session")
    (testing "a downloaded file"
      (is (= ["file:///ep3.mp3" true] ((juxt :src :local?) (library/playback lib id {:local-file-fn {id "file:///ep3.mp3"}}))))
      (let [downloaded (-> lib
                           (library/start-download {:id id :url "https://example.com/file-03.mp3" :bytes 9000})
                           (library/finish-download id {:path "/podcasts/ep3.mp3" :bytes 8800}))]
        (is (= ["/podcasts/ep3.mp3" true] ((juxt :src :local?) (library/playback downloaded id)))
            "from the record of the files")
        (is (= "file:///ep3.mp3" (:src (library/playback downloaded id {:local-file-fn {id "file:///ep3.mp3"}})))
            "after the app's own")))
    (testing "what the files that were read add"
      (let [with-read (library/playback library id {:read files})]
        (is (= [{:title "Intro" :start-time 0} {:title "Talk" :start-time 94 :artwork [{:src "https://x/i.png"}]}]
               (:chapter-info (:metadata with-read)))
            "the chapters of the table of contents")
        (is (= 3599.5 (:duration with-read)))
        (is (= 3600 (:duration (library/playback lib id {:read files}))) "after the duration the player reported")))
    (is (nil? (library/playback lib [guid "none"])))))

(deftest the-ids-of-a-show-in-the-directories
  (let [apple   (str "{\"resultCount\":1,\"results\":[{\"kind\":\"podcast\",\"collectionId\":123456,"
                     "\"collectionName\":\"Podcasting 2.0 Namespace Example\","
                     "\"feedUrl\":\"https://example.com/feed.xml\"}]}")
        asked   (atom 0)
        send    (fn [_] (swap! asked inc) {:status 200 :body apple})
        apps    (fn [lib] (set (map :app (:links (library/show lib guid)))))]
    (run #(async/then (library/identify! library {:send send})
                      (fn [lib]
                        (is (= {:apple "123456"} (get-in lib [:feeds guid :ids])) "kept in the entry of the feed")
                        (is (= [] (:report (:identify lib))))
                        (is (some #{:castbox} (apps lib)) "so the views link to the show in the apps that know it by its id")
                        (is (not (some #{:castbox} (apps library))))
                        (is (= {:apple "123456"}
                               (get-in (library/merge (assoc-in library [:feeds guid :fetched-at] (at 99)) lib)
                                       [:feeds guid :ids]))
                            "and a merge keeps them, whichever fetch was later")
                        (async/then (library/identify! lib {:send send})
                                    (fn [_] (is (= 1 @asked) "a show with its ids isn't asked again")))))
         #(async/then (library/identify! library {:send (constantly {:status 503 :body ""})})
                      (fn [lib]
                        (is (nil? (get-in lib [:feeds guid :ids])) "a show whose directory failed is asked again next time")
                        (is (= [:directory/source-failed] (map :id (:report (:identify lib))))))))))

(deftest a-new-subscription-through-the-send
  (let [xml  (fixture "feeds/namespace-example.xml")
        lib  (library/update-profile (library/make "phone") profile/subscribe "g" {:url url})
        send (fn [request] {:status 200 :url (:url request) :body xml})]
    (run #(async/then (library/refresh! lib {:at now :send send})
                      (fn [{{:keys [report]} :refresh :as lib'}]
                        (is (= [] report))
                        (is (= [guid] (keys (:feeds lib'))) "fetch! by default, through the :send, without a :fetch"))))))

(deftest subscribing-to-the-start-of-a-feed
  (let [items  (apply str (repeat 2000 "<item><title>An episode</title><guid>g</guid></item>"))
        xml    #(str "<rss version=\"2.0\" xmlns:podcast=\"https://podcastindex.org/namespace/1.0\">"
                     "<channel><title>Head</title>" % items "</channel></rss>")
        id     "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"
        head!  #(discovery/fetch-head! other-url {:send (ranges (xml %) (atom []))})]
    (run #(async/then
           (async/all [(head! (str "<podcast:guid>" id "</podcast:guid>")) (head! "")])
           (fn [[declared plain]]
             (is (every? (comp discovery/head? :feed) [declared plain]))
             (let [lib (library/subscribe (library/make "phone") other-url declared)]
               (is (= {} (:feeds lib)) "the feed itself comes at the next refresh")
               (is (= ["Head" id] ((juxt :title :guid) (get (library/subscriptions lib) id)))
                   "with the title and the guid of the start"))
             (let [lib (library/subscribe (library/make "phone") other-url plain)
                   of  (feed.guid/feed-guid nil other-url)]
               (is (= [of] (keys (library/subscriptions lib))))
               (is (nil? (:guid (get (library/subscriptions lib) of)))
                   "a guid that the start doesn't declare comes from the first fetch, as without one")))))))

(deftest subscribing-without-a-fetch
  (let [lib   (library/subscribe (library/make "phone") other-url)
        of    (feed.guid/feed-guid nil other-url)
        there "https://example.org/moved.xml"]
    (is (= [of] (keys (library/subscriptions lib))) "the guid of the URL")
    (is (= {} (:feeds lib)) "nothing fetched yet")
    (run
     (fn []
       (async/then (library/refresh! lib {:at now :fetch (fn [u _] {:status 503 :url u})})
                   (fn [{{:keys [report]} :refresh :as lib'}]
                     (is (= [[:fetch/status 503 of]] (map (juxt :id :status :feed-guid) report)))
                     (is (= {} (:feeds lib')) "a feed still not fetched is left out, to fetch next time"))))
     (fn []
       (async/then (library/refresh! lib {:at    now
                                          :fetch (fn [u _] {:status 200 :url u :feed other :permanent-url there})})
                   (fn [{{:keys [added moved report]} :refresh :as lib'}]
                     (let [sub (get (library/subscriptions lib') "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f")]
                       (is (= [] report))
                       (is (= [] added) "the episodes of a new subscription aren't new ones")
                       (is (= ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"] (keys (library/subscriptions lib'))) "under the guid that the feed declares")
                       (is (= ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"] (keys (:feeds lib'))))
                       (is (= "Other" (:title sub)) "with the title of the feed")
                       (is (= {"0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" there} moved))
                       (is (= there (:url sub)) "and its move")))))
     (fn []
       (async/then (library/refresh! lib {:at         now
                                          :feed-guids []
                                          :fetch      (fn [u _] {:status 200 :url u :feed other})})
                   (fn [lib']
                     (is (= ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"] (keys (:feeds lib')))
                         "a new subscription is fetched besides the feeds that are asked for")))))))

(def others
  "The other show, with a second episode."
  (update other :rss/items conj (assoc (first (:rss/items other))
                                       :rss/guid {:content "o2"}
                                       :rss/title "Other two")))

(deftest an-episode-of-another-show
  (let [fetched {:status 200 :url other-url :feed others}
        lib     (library/add-episode library other-url fetched "o1")
        id      ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" "o1"]
        ids     (fn [lib]
                  (set (for [[feed item] (library/episodes lib)]
                         (library/episode-id lib feed item))))]
    (testing "the profile notes a remote item, and the library lists that episode alone"
      (is (= {id other-url} (update-vals (profile/remote-items (:profile lib)) :feed-url)))
      (is (= [guid] (keys (library/subscriptions lib))) "no subscription")
      (is (= 4 (count (ids lib))) "the three of the show and the one")
      (is (contains? (ids lib) id))
      (is (= [id] (mapv :id (library/search lib "other"))) "nor the show or the other episode in the index")
      (is (= (search/query (:index lib) "other")
             (search/query (library.impl/index lib) "other"))
          "an index built again from the feeds finds the same")
      (is (= ["Other one"] (map :rss/title (get-in lib [:feeds "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" :feed :rss/items])))
          "the feed kept with that episode alone")
      (is (nil? (library/episode lib ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" "o2"]))))
    (testing "it throws without a feed or the episode"
      (is (thrown? #?(:clj Exception :cljs js/Error) (library/add-episode library other-url {:status 404 :url other-url} "o1")))
      (is (thrown? #?(:clj Exception :cljs js/Error) (library/add-episode library other-url fetched "nope"))))
    (testing "removed, its feed goes"
      (let [gone (library/remove-episode lib id)]
        (is (= {} (profile/remote-items (:profile gone))))
        (is (not (contains? (:feeds gone) "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f")))
        (is (= [] (library/search gone "other")))))
    (testing "an unsubscription keeps the feed of a remote item"
      (let [both (-> (library/subscribe library other-url fetched)
                     (library/add-episode other-url fetched "o1"))
            left (library/unsubscribe both "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f")]
        (is (= 5 (count (ids both))))
        (is (= (ids lib) (ids left)))
        (is (= [id] (mapv :id (library/search left "other"))))))
    (testing "a merge keeps the feed, and a remote item that came with it fetches the feed whole once"
      (let [o2     ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" "o2"]
            two    (library/update-profile lib profile/add-remote-item o2 {:feed-url other-url})
            merged (library/merge lib {:profile (:profile two)})
            asked  (atom [])
            fetch  (fn [u opts]
                     (swap! asked conj [u (:etag opts)])
                     {:status 200 :url u :feed others})]
        (is (contains? (:feeds merged) "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"))
        (is (= #{id} (set (map :id (library/search merged "other")))))
        (is (= now (library/next-refresh merged {:at now})) "due at once")
        (run (fn []
               (async/then (library/refresh! merged {:at now :fetch fetch :send (fn [_] {:status 304 :headers {} :body ""})})
                           (fn [lib']
                             (is (= [[other-url nil]] @asked) "without the validators of the part it held")
                             (is (= #{id o2} (set (map :id (library/search lib' "other")))))
                             (is (= ["Other one" "Other two"]
                                    (sort (map :rss/title (get-in lib' [:feeds "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" :feed :rss/items])))))
                             (reset! asked [])
                             (async/then (library/refresh! lib' {:at (at (* 90 86400)) :fetch fetch
                                                                 :send (fn [_] {:status 304 :headers {} :body ""})})
                                         (fn [_]
                                           (is (not-any? #{other-url} (map first @asked))
                                               "and then never checked again, as the show has no subscription")))))))))))

(deftest the-played-episodes-of-other-shows
  (let [other-guid "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"
        [o1 o2]    [[other-guid "o1"] [other-guid "o2"]]
        fetched    {:status 200 :url other-url :feed others}
        lib        (-> library
                       (library/add-episode other-url fetched "o1")
                       (library/add-episode other-url fetched "o2")
                       (library/update-profile profile.queue/enqueue o1)
                       (library/update-profile profile.queue/add-to-playlist "Road trip" o1)
                       (library/update-profile profile/mark-played o1)
                       (library/update-profile profile/mark-played o2)
                       (library/update-profile profile.marks/star o2))
        tidy       (library/remove-played-episodes lib)]
    (is (= [o2] (keys (profile/remote-items (:profile tidy)))) "but a starred one")
    (is (not (profile.queue/queued? (:profile tidy) o1)))
    (is (= {} (profile.queue/playlists (:profile tidy))))
    (is (= ["Other two"] (map :rss/title (get-in tidy [:feeds other-guid :feed :rss/items]))))
    (is (not (contains? (:feeds (library/remove-played-episodes (library/update-profile lib profile.marks/unstar o2)))
                        other-guid))
        "and the feed goes with the last of them")
    (is (= library (library/remove-played-episodes library)) "nothing changes without one")))

(deftest live-items-across-the-shows
  (let [live   (fn [guid status start]
                 {:podcast/guid guid :rss/guid {:content guid} :rss/title guid :status status :start start
                  :rss/enclosure {:url (str "https://example.com/" guid ".mp3") :type "audio/mpeg"}})
        lives  [(live "over" "ended" (at -86400)) (live "soon" "pending" (at 3600))
                (live "now" "live" (at -600)) (live "later" "pending" (at 7200))]
        lib    (library/subscribe library url (assoc fresh :feed (assoc show :podcast/live-items lives)))]
    (is (= ["now" "soon" "later" "over"] (map (comp :rss/title second) (library/live-items lib {:at now}))))
    (is (= "https://example.com/now.mp3" (:src (library/playback lib [guid "now"]))) "and one plays by its id")
    (is (= [] (library/live-items (library/unsubscribe lib guid))) "the subscriptions' alone")))

(deftest an-episode-of-another-show-on-another-device
  (let [id     ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" "o1"]
        lib    (library/add-episode library other-url {:status 200 :url other-url :feed others} "o1")
        synced (library/merge (library/make "tablet") {:profile (:profile lib)})
        newer  (update others :rss/items conj (assoc (first (:rss/items others)) :rss/guid {:content "o3"}))
        fetch  (fn [feed] (fn [u _] (if (= u other-url) {:status 200 :url u :feed feed} {:status 304 :url u})))
        listed #(set (map :id (library/search % "other")))]
    (is (not (contains? (:feeds synced) "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f")) "the sync brings the remote item, and no feed")
    (run (fn []
           (async/then (library/refresh! synced {:at now :fetch (fetch others)})
                       (fn [{{:keys [added report]} :refresh :as lib'}]
                         (is (= [] report))
                         (is (= [] added) "the episodes of a show it isn't subscribed to aren't new")
                         (is (= #{id} (listed lib')) "fetched by refresh!, and the one episode indexed")
                         (async/then (library/refresh! lib' {:at (at 86400) :fetch (fetch newer)})
                                     (fn [{{:keys [added]} :refresh :as lib''}]
                                       (is (= [] added))
                                       (is (= #{id} (listed lib'')) "nor are those that come later")))))))))

(deftest a-feed-that-declares-the-guid-of-another
  (let [evil-url "https://evil.example/feed.xml"
        evil     (assoc show :podcast/guid guid)
        sent     (atom [])
        fetch    (fn [u opts]
                   (swap! sent conj [u (:credentials opts)])
                   {:status 200 :url u :feed (if (= u evil-url) evil show)})
        listed   (library/update-profile library profile/subscribe (feed.guid/feed-guid {} evil-url) {:url evil-url})]
    (testing "subscribing to it throws, and names the subscription it would take over"
      (let [e (try (library/subscribe library evil-url evil)
                   nil
                   (catch #?(:clj Exception :cljs :default) e e))]
        (is (= [:fetch/guid-taken evil-url guid url] ((juxt :id :url :feed-guid :other-url) (ex-data e))))))
    (testing "the same feed at another form of its URL takes nothing over"
      (let [lib (library/subscribe library "http://example.com/feed.xml/" show)]
        (is (= ["http://example.com/feed.xml/"] (map :url (vals (library/subscriptions lib)))))))
    (run #(async/then (library/refresh! listed {:at (at 60) :fetch fetch :credentials-of {url {:username "ann"}}})
                      (fn [{{:keys [report]} :refresh :as lib'}]
                        (is (= [[:fetch/guid-taken evil-url guid url]] (map (juxt :id :url :feed-guid :other-url) report))
                            "a show from a list or another device that declares it is left out, and the report says why")
                        (is (= url (:url (get (library/subscriptions lib') guid)) (get-in lib' [:feeds guid :url]))
                            "the subscription keeps its URL")
                        (is (= [[evil-url nil]] @sent) "and the credentials of the private feed go by its URL only"))))))

(deftest two-devices-agree-on-a-key
  (let [plain    (dissoc other :podcast/guid)
        fetching (fn [feed] (fn [u _] {:status 200 :url u :feed feed}))
        key      (feed.guid/from-url other-url)
        declared (:podcast/guid other)
        phone    (library/subscribe (library/make "phone" {:config {:now-fn (constantly now)}}) other-url)]
    (run #(async/then (library/refresh! phone {:at (at 60) :fetch (fetching plain)})
                      (fn [phone']
                        (is (= {key key} (update-vals (library/subscriptions phone') :guid))
                            "settled on the guid of its URL, as the feed declares none")
                        (let [laptop (update (library/make "laptop" {:config {:now-fn (constantly now)}})
                                             :profile profile/merge (:profile phone'))]
                          (async/then (library/refresh! laptop {:at (at 120) :fetch (fetching other)})
                                      (fn [{{:keys [report]} :refresh :as laptop'}]
                                        (is (= [key] (keys (library/subscriptions laptop')))
                                            "the feed declares a guid by the time the laptop fetches it, and the key stays")
                                        (is (= [[:fetch/guid-changed key key declared]] (map (juxt :id :feed-guid :old :new) report)))
                                        (is (= declared (get-in laptop' [:profile :subscriptions key :guid])) "an alias")
                                        (is (= [key] (keys (profile/subscriptions (profile/merge (:profile phone') (:profile laptop')))))
                                            "so the devices agree")
                                        (let [e (try (library/subscribe laptop' "https://elsewhere.example/copy.xml" other)
                                                     nil
                                                     (catch #?(:clj Exception :cljs :default) e e))]
                                          (is (= [:fetch/guid-taken key] ((juxt :id :feed-guid) (ex-data e)))
                                              "and another URL that declares the alias takes nothing over"))))))))))

(deftest the-episodes-across-the-shows
  (let [lib (library/subscribe library other-url {:status 200 :url other-url :feed other})]
    (is (= 4 (count (library/episodes lib))))
    (is (= #{"Other" "Podcasting 2.0 Namespace Example"} (set (map (comp :rss/title first) (library/episodes lib)))))
    (is (= ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f" "o1"]
           (let [[feed item] (first (filter #(= "Other" (:rss/title (first %))) (library/episodes lib)))]
             (library/episode-id lib feed item)))
        "the identity of a pair, for the profile and the views")
    (is (= 2 (count (library/episodes lib {:limit 2}))))
    (let [id     [guid "https://example.com/ep0003"]
          played (update lib :profile profile/mark-played id now)]
      (is (= 3 (count (library/episodes played {:where {:status :unplayed}})))))
    (testing "the episodes of some shows, e.g. for a show's page"
      (is (= #{"Other"} (set (map (comp :rss/title first)
                                  (library/episodes lib {:feed-guids ["0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"]})))))
      (is (= [] (library/episodes lib {:feed-guids ["none"]}))))
    (testing "the state of a download, by the library's record of the files"
      (let [id         [guid "https://example.com/ep0003"]
            started    (library/start-download lib {:id id :url "https://example.com/file-03.mp3"})
            ids-where  #(mapv (fn [[feed item]] (library/episode-id started feed item))
                              (library/episodes started {:where %}))]
        (is (= [id] (ids-where {:download :downloading})))
        (is (= 3 (count (ids-where {:download :none})))))))
  (testing "a show without a podcast:guid, which the profile knows by its URL"
    (let [bare-url "https://example.net/bare.xml"
          bare     (dissoc other :podcast/guid)
          lib      (-> (library/make "phone" {:config {:search? true}})
                       (library/subscribe bare-url {:status 200 :url bare-url :feed bare}))]
      (is (= ["Other one"] (mapv (comp :rss/title second) (library/episodes lib {:where {:status :unplayed}}))))
      (is (= ["Other one"] (mapv (comp :rss/title :item) (library/search lib "other" {:where {:status :unplayed}})))))))

(deftest the-events-of-the-player
  (let [id      [guid "https://example.com/ep0003"]
        lib     (update library :profile profile.queue/enqueue id now)
        stepped (library/step lib [:progress id 100 3600])]
    (is (= [] (:effects stepped)))
    (is (= 100 (profile/resume-position (:profile stepped) guid id)))
    (is (= [[:seek id 130]] (:effects (library/step stepped [:skip id :forward]))))
    (testing "the sleep timer of the device lives in the library"
      (let [timed (assoc stepped :sleep {:episodes 2})
            spent (library/step (assoc timed :sleep {:episodes 1}) [:ended id])]
        (is (= {:episodes 1} (:sleep (library/step timed [:ended id]))) "one episode less to go")
        (is (= [[:stop id]] (:effects spent)))
        (is (not (contains? spent :sleep)) "the last episode ended: the timer is gone")))
    (testing "continuous play goes on with the first unplayed episode of the show, in its listening order"
      (let [on     (library/update-profile stepped profile/prefer {:continuous? true})
            order  (remove #{id} (map (fn [[feed item]] (library/episode-id lib feed item))
                                      (library/episodes lib {:feed-guids [guid] :order :listening})))
            played (library/update-profile on profile/mark-played (first order))]
        (is (= 2 (count order)))
        (is (= [[:play (first order)]] (:effects (library/step on [:ended id]))))
        (is (= [[:play (second order)]] (:effects (library/step played [:ended id]))) "but for one played already")
        (is (= [] (:effects (library/step stepped [:ended id]))) "only when the listener wants it")))
    (testing "a rewind after a pause"
      (is (= 100 (:position (library/playback stepped id))))
      (is (= 90 (:position (library/playback stepped id {:at (at 7200)})))))
    (testing "every episode of a show played at once"
      (let [caught-up (library/mark-show-played lib guid)]
        (is (= 3 (count (filter #(profile/played? (:profile caught-up) %)
                                (map (fn [[feed item]] (library/episode-id lib feed item)) (library/episodes lib))))))
        (is (= [] (library/episodes caught-up {:where {:status :unplayed}})))))))

(deftest keeping-two-devices-in-step
  (let [documents (atom {})
        backend   (library/backend (store/memory documents) {:app "test"})
        laptop    (-> (library/make "laptop" {:name "Laptop" :config {:now-fn (constantly now)}})
                      (library/subscribe other-url {:status 200 :url other-url :feed other}))]
    (run
     (fn []
       (async/then (library/sync! library [backend] {:at (at 10)})
                   (fn [{{:keys [report]} :sync :as lib}]
                     (is (= [] report) "no backend failed, and nothing else to report")
                     (is (= 1 (count @documents)) "the phone wrote its document")
                     (is (map? (get-in lib [:sync :cursors])))
                     (is (= (at 10) (get-in lib [:sync :at])))
                     (is (= (at (+ 10 900)) (library/next-sync lib {:at (at 10)})) "idle: nothing changed since")
                     (async/then (library/sync! laptop [backend] {:at (at 20)})
                                 (fn [lib]
                                   (testing "the laptop learns the subscription of the phone"
                                     (is (= 2 (count (library/subscriptions lib))))
                                     (is (nil? (:feed (get (library/subscriptions lib) guid))) "a feed to fetch at the next refresh!"))))))))))

(deftest leaving-the-sync-and-coming-back
  (let [documents (atom {})
        backend   (library/backend (store/memory documents) {:app "test"})
        laptop    (library/make "laptop" {:name "Laptop" :config {:now-fn (constantly now)}})
        devices   #(set (keys (crdt/devices (:profile %))))]
    (run
     (fn []
       (-> (library/sync! library [backend] {:at (at 10)})
           (async/then
            (fn [phone]
              (-> (library/sync! laptop [backend] {:at (at 20)})
                  (async/then #(library/leave! % [backend] {:at (at 30)}))
                  (async/then
                   (fn [left]
                     (is (crdt/retired? (:profile left) "laptop"))
                     (is (= [] (get-in left [:sync :report])))
                     (-> (library/sync! phone [backend] {:at (at 40)})
                         (async/then
                          (fn [phone]
                            (is (= #{"phone"} (devices phone)) "the phone learns that the laptop left")
                            (is (= 1 (count @documents)) "and deletes its document")
                            (library/sync! left [backend] {:at (at 50)})))
                         (async/then
                          (fn [again]
                            (is (= [:sync/retired-device] (map :id (get-in again [:sync :report])))
                                "a device that left can't sync again")
                            (library/sync! (library/with-device left "laptop-2" {:name "Laptop"}) [backend]
                                           {:at (at 60)})))
                         (async/then
                          (fn [back]
                            (is (= "laptop-2" (:device back) (get-in back [:profile :device])))
                            (is (= [] (get-in back [:sync :report])))
                            (is (= #{"phone" "laptop-2"} (devices back)) "but a new device of it can")
                            (is (contains? (library/subscriptions back) guid) "with what the old one held"))))))))))))))

(deftest archiving-by-the-config
  (let [id      [guid "old"]
        played  (update library :profile profile/mark-played id (at 10))
        horizon (assoc-in played [:config :archive-after] 60000)
        backend #(library/backend (store/memory (atom {})) {:app "test"})]
    (run #(async/then (library/sync! played [(backend)] {:at (at 100)})
                      (fn [lib]
                        (is (= [id] (keys (get-in lib [:profile :played]))) "a mark stays for a year")
                        (let [merged (library/merge horizon lib)]
                          (is (empty? (get-in merged [:profile :played])) "but a merge archives by the config")
                          (is (profile/played? (:profile merged) id)))))
         #(async/then (library/sync! horizon [(backend)] {:at (at 100)})
                      (fn [lib]
                        (is (empty? (get-in lib [:profile :played])) "and so does a sync")
                        (is (profile/played? (:profile lib) id))))
         #(async/then (library/sync! horizon [(backend)] {:at (at 100) :compact false})
                      (fn [lib]
                        (is (= [id] (keys (get-in lib [:profile :played]))) "unless it doesn't compact"))))))

(deftest syncs-that-fail-in-a-row
  (let [broken {:name "broken" :pull (fn [_ _ _] (throw (ex-info "down" {}))) :push (fn [_ _ _] {:cursor {}})}
        good   (library/backend (store/memory (atom {})) {:app "test"})]
    (run #(async/then (library/sync! library [broken] {:at (at 10)})
                      (fn [lib]
                        (async/then (library/sync! lib [broken] {:at (at 20)})
                                    (fn [lib]
                                      (is (= [2 (at 20)] ((juxt :failures :at) (:sync lib))) "counted for the next sync")
                                      (is (= (at (+ 20 60)) (library/next-sync lib {:at (at 21)})) "which waits longer")
                                      (async/then (library/sync! lib [good] {:at (at 30)})
                                                  (fn [lib]
                                                    (is (= 0 (:failures (:sync lib))) "until one works"))))))))))

(deftest a-show-left-on-another-device
  (let [backend (library/backend (store/memory (atom {})) {:app "test"})
        laptop  (-> (library/make "laptop" {:name "Laptop" :config {:now-fn (constantly now)}})
                    (library/subscribe url fresh {:auto-queue? true}))
        phone   (library/unsubscribe library guid {:at (at 10)})]
    (run #(async/then (library/sync! phone [backend] {:at (at 20)})
                      (fn [_]
                        (async/then (library/sync! laptop [backend] {:at (at 30)})
                                    (fn [result]
                                      (let [merged (library/merge laptop result)]
                                        (is (= {} (library/subscriptions merged)))
                                        (is (= {} (:feeds merged)) "so refresh! no longer fetches it")
                                        (is (= [] (library/episodes merged {})))))))))))

(deftest merging-before-the-first-sync
  (let [left (library/unsubscribe library guid {:at (at 10)})]
    (is (= {} (library/subscriptions (library/merge (library/make "tablet") {:profile (:profile left)})))
        "a tombstone, and no sync to compact at")))

(deftest merging-what-a-fetch-gave
  (let [id      [guid "https://example.com/ep0003"]
        fetch   (fn [url _] {:status 200 :url url :feed newer :etag "\"v2\""})
        indexed (-> (library/make "phone" {:config {:search? true :now-fn (constantly now)}})
                    (library/subscribe url fresh {:auto-queue? true}))
        backend (library/backend (store/memory (atom {})) {:app "test"})]
    (run
     (fn []
       (async/then (library/refresh! indexed {:at (at 86400) :fetch fetch})
                   (fn [result]
                     (testing "what the listener did during a refresh stays, with what the refresh found"
                       (let [meanwhile (-> indexed
                                           (library/step [:progress id 100 3600] {:at (at 86410)})
                                           (update :profile profile.marks/star id (at 86420)))
                             merged    (library/merge meanwhile result)]
                         (is (= 100 (profile/resume-position (:profile merged) guid id)))
                         (is (= [id] (profile.marks/starred (:profile merged))))
                         (is (= [[guid "new"]] (profile.queue/queue (:profile merged))) "the new episode, queued")
                         (is (= [[guid "new"]] (:added (:refresh merged))))
                         (is (= "\"v2\"" (get-in merged [:feeds guid :etag])))
                         (is (= [guid "new"] (:id (first (library/search merged "new one")))) "and indexed")
                         (is (= (:effects meanwhile) (:effects merged)) "the effects of the last step")))
                     (testing "a show that the listener left during a refresh doesn't come back"
                       (let [gone (library/merge (library/unsubscribe indexed guid {:at (at 86410)}) result)]
                         (is (= {} (library/subscriptions gone)))
                         (is (= {} (:feeds gone)))))
                     (testing "a show that the listener subscribed to during a refresh stays"
                       (let [joined (library/subscribe indexed other-url {:status 200 :url other-url :feed other} {:at (at 86410)})]
                         (is (= #{guid "0e7e9f35-8c4e-5b1a-9d2f-3c4b5a6d7e8f"} (set (keys (:feeds (library/merge joined result)))))))))))
     (fn []
       (async/then (library/sync! library [backend] {:at (at 10)})
                   (fn [result]
                     (testing "what the listener did during a sync stays, with the step of the sync"
                       (let [merged (library/merge (update library :profile profile.marks/star id (at 12)) result)]
                         (is (= [id] (profile.marks/starred (:profile merged))))
                         (is (= (:sync result) (:sync merged)))
                         (is (= (at 20) (library/next-sync merged {:at (at 12)})) "soon, since it changed")))))))))

(deftest a-library-read-back-without-its-config
  (let [saved (dissoc library :config)
        lib   (assoc saved :config {:now-fn (constantly now)})]
    (is (= saved (-> (edn/read-string (pr-str saved))
                     (update :index search/restore)))
        "EDN without the clock of the config, with its index restored")
    (is (= (at 86400) (get-in (library/follow-list lib "https://example.org/picks.opml" picks)
                              [:fetched-lists "https://example.org/picks.opml" :next-check]))
        "the defaults stand in for what the config leaves out")))

(deftest a-library-saved-and-restored
  (let [documents   (atom {})
        s           (store/memory documents)
        undeletable (dissoc (store/memory) :delete)
        fail?       (atom true)
        flaky       (update (store/memory) :delete
                            (fn [delete]
                              (fn [name]
                                (if @fail?
                                  (throw (ex-info "The disk said no" {}))
                                  (delete name)))))
        made        #(library/make "phone" {:config {:now-fn (constantly now)}})
        ids         #(mapv :id (library/search % "future"))
        breaking    (fn [docs]
                      (into {} (for [[k v] docs]
                                 [k (cond-> v
                                      (re-find #"\.ciff$" k) (assoc :body "x"))])))]
    (run #(async/then (library/save! library s)
                      (fn [{:keys [put delete]}]
                        (is (= "library.edn" (peek put)) "the library last")
                        (is (some #{"index.edn"} put))
                        (is (= [] delete))))
         #(async/then (library/restore! (made) s)
                      (fn [lib]
                        (is (= [] (restored-report lib)))
                        (is (= (:profile library) (:profile lib)))
                        (is (= (:feeds library) (:feeds lib)))
                        (is (= (ids library) (ids lib)) "the index read back")))
         #(async/then (library/save! library s)
                      (fn [{:keys [put]}]
                        (is (= ["index.edn" "library.edn"] put)
                            "no segment written again")))
         #(do (swap! documents breaking)
              (async/then (library/restore! (made) s)
                          (fn [lib]
                            (is (= [:library/index-rebuilt]
                                   (map :id (restored-report lib))))
                            (is (= (ids library) (ids lib))
                                "an index built again from the feeds"))))
         #(do (reset! documents {})
              (async/then (library/save! library s)
                          (fn [_] (library/save! (made) s {:keys [:feeds]}))))
         #(async/then (library/restore! (made) s)
                      (fn [lib]
                        (is (= {} (:feeds lib)))
                        (is (= [] (ids lib))
                            "an index of feeds that the library doesn't have")))
         #(do (swap! documents assoc "library.edn" {:body "{:feeds" :version "9"})
              (async/then (library/restore! (made) s)
                          (fn [lib]
                            (is (= [:library/unreadable-document]
                                   (map :id (restored-report lib))))
                            (is (= {} (:feeds lib))))))
         #(async/then (library/save! (library/make "phone" {:config {:search? false}})
                                     (store/memory))
                      (fn [{:keys [put]}]
                        (is (= ["library.edn"] put) "no index, no documents of one")))
         #(async/then (library/save! library (dissoc (store/memory) :put))
                      (fn [{:keys [error]}]
                        (is (= :library/save-failed (:id error)))
                        (is (= "The store is read only" (:reason error)))))
         #(async/then (async/then (library/save! library undeletable)
                                  (fn [_] (library/save! (made) undeletable)))
                      (fn [{:keys [delete error]}]
                        (is (nil? error))
                        (is (= [] delete))))
         #(async/then ((:list undeletable))
                      (fn [docs]
                        (is (some (fn [{:keys [name]}] (re-find #"\.ciff$" name)) docs)
                            "a store that can't delete keeps the old segments")))
         #(async/then (async/then (library/save! library flaky)
                                  (fn [_] (library/save! (made) flaky)))
                      (fn [{:keys [delete error]}]
                        (is (nil? error) "a delete that failed doesn't fail the save")
                        (is (= [] delete))))
         #(do (reset! fail? false)
              (async/then (library/save! (made) flaky)
                          (fn [{:keys [delete]}]
                            (is (some (fn [name] (re-find #"\.ciff$" name)) delete)
                                "the next save deletes it")))))))

(deftest a-library-saved-in-parts
  (let [documents (atom {})
        s         (store/memory documents)
        made      #(library/make "phone" {:config {:now-fn (constantly now)}})
        doc-name  (library.impl/document-name guid (get-in library [:feeds guid :changed-at]))
        saved     #(binding [*print-namespace-maps* false] (pr-str %))
        too-new   {:version (inc library.impl/version) :profile {:from :the-future}}
        later     #inst "2026-10-01T12:00:00Z"
        changed   (-> library
                      (assoc-in [:feeds guid :changed-at] later)
                      (update-in [:feeds guid :feed] assoc :rss/title "Renamed"))]
    (is (inst? (get-in library [:feeds guid :changed-at])) "a fetched feed says when it changed")
    (is (re-matches #"feed-[0-9a-f-]{36}-\d+\.edn" doc-name) "a plain file name for a guid that isn't a UUID")
    (run #(async/then (library/save! library s)
                      (fn [{:keys [put]}]
                        (is (= doc-name (first put)) "each feed in a document of its own")
                        (let [edn (edn/read-string (:body (get @documents "library.edn")))]
                          (is (= library.impl/version (:version edn)))
                          (is (= doc-name (get-in edn [:feeds guid :document])))
                          (is (not (contains? (get-in edn [:feeds guid]) :feed)) "and not in library.edn"))))
         #(async/then (library/save! changed s)
                      (fn [{:keys [put delete]}]
                        (is (= (library.impl/document-name guid later) (first put)) "a feed that changed, again")
                        (is (= [doc-name] delete) "and its old document gone")))
         #(async/then (library/restore! (made) s)
                      (fn [lib]
                        (is (= [] (restored-report lib)))
                        (is (= "Renamed" (get-in lib [:feeds guid :feed :rss/title])))))
         #(do (swap! documents dissoc (library.impl/document-name guid later))
              (async/then (library/restore! (made) s)
                          (fn [lib]
                            (is (= [[:library/unreadable-document (library.impl/document-name guid later)]]
                                   (map (juxt :id :name) (restored-report lib))))
                            (is (= {:url url} (get-in lib [:feeds guid]))
                                "a feed whose document is gone, to fetch again"))))
         #(do (reset! documents {"library.edn" {:body (saved (select-keys library library/saved-keys)) :version "1"}})
              (async/then (library/restore! (made) s)
                          (fn [lib]
                            (is (= [] (restored-report lib)))
                            (is (= (:feeds library) (:feeds lib)) "a library of version 1, with the feeds inline")
                            (library/save! lib s))))
         #(is (contains? @documents doc-name) "and written out at the next save")
         #(do (swap! documents assoc "library.edn" {:body (saved too-new) :version "2"})
              (async/then (library/restore! (made) s)
                          (fn [lib]
                            (is (= [[:library/newer-format (:version too-new)]]
                                   (map (juxt :id :version) (restored-report lib))))
                            (is (= (:profile (made)) (:profile lib)) "nothing of a newer version")
                            (async/then (library/save! lib s)
                                        (fn [{:keys [error]}]
                                          (is (= :library/newer-format (:id error))))))))
         #(is (= (saved too-new) (:body (get @documents "library.edn")))
              "which a save doesn't write over")
         #(do (swap! documents assoc "library.edn"
                     {:body (saved {:version 2 :profile (assoc (:profile library) :subscriptions "nope")}) :version "3"})
              (async/then (library/restore! (made) s)
                          (fn [lib]
                            (is (= [:library/malformed-profile] (map :id (restored-report lib))))
                            (is (= (:profile (made)) (:profile lib)) "the profile it had")))))))

(deftest a-library-saved-in-transit
  (let [documents (atom {})
        s         (store/memory documents)
        config    {:now-fn (constantly now) :codec transit/codec}
        made      #(library/make "phone" {:config config})
        in-transit (assoc-in library [:config :codec] transit/codec)
        feed-name (library.impl/document-name guid (get-in library [:feeds guid :changed-at]) transit/codec)]
    (run #(library/save! library s)
         #(async/then (library/restore! (library/make "phone" {:config {:now-fn (constantly now)}}) s)
                      (fn [lib]
                        (is (= #?(:clj [] :cljs [:library/slow-format])
                               (map :id (:report (:restore lib))))
                            "EDN reads slowly in ClojureScript")))
         #(async/then (library/restore! (made) s)
                      (fn [lib]
                        (is (= [] (restored-report lib)) "a library saved in EDN reads with the codec of Transit")
                        (is (= (:feeds library) (:feeds lib)))
                        (async/then (library/save! lib s)
                                    (fn [{:keys [put delete]}]
                                      (is (= "library.transit.json" (peek put)))
                                      (is (some #{feed-name} put) "each feed written anew in Transit")
                                      (is (= #{"library.edn" (library.impl/document-name guid (get-in library [:feeds guid :changed-at]))}
                                             (set (remove (fn [n] (re-find #"\.ciff$" n)) delete)))
                                          "and the documents in EDN gone")))))
         #(async/then (library/restore! (made) s)
                      (fn [lib]
                        (is (= [] (restored-report lib)))
                        (is (= (:profile library) (:profile lib)))
                        (is (= (:feeds library) (:feeds lib)) "read back from Transit")
                        (is (= (:feed (get-in in-transit [:feeds guid]))
                               (:feed (transit/read-str (:body (get @documents feed-name)))))))))))

(deftest replicas-that-share-a-store
  (let [s         (store/memory)
        config    {:now-fn (constantly now)}
        tab       (fn [n] (library/make "phone" {:replica n :config config}))
        id        [guid "https://example.com/ep0003"]
        other-key (feed.guid/feed-guid other other-url)
        listened  #(get-in % [:profile :listened guid "2026-09"])
        heard     (atom [])
        told      (replica/channel "replicas-test")
        hearing   (replica/channel "replicas-test")
        tabs      (atom {})]
    (replica/listen! hearing #(swap! heard conj %))
    (is (library/shared? (tab "1")))
    (is (= "phone/1" (get-in (tab "1") [:profile :replica])))
    (is (not (library/shared? library)))
    (run #(library/save! library s)
         #(async/then (async/all [(library/restore! (tab "1") s) (library/restore! (tab "2") s)])
                      (fn [[one two]]
                        (is (= "phone/1" (get-in one [:profile :replica])) "a restore keeps the replica of the context")
                        (is (some? (get-in one [:stored :version])))
                        (reset! tabs {:one (-> (assoc-in one [:config :channel] told)
                                               (library/step [:progress id 100 3600] {:at (at 10)})
                                               (library/step [:progress id 160 3600] {:at (at 70)}))
                                      :two (-> two
                                               (library/step [:progress id 200 3600] {:at (at 30)})
                                               (library/step [:progress id 230 3600] {:at (at 60)})
                                               (library/subscribe other-url {:status 200 :url other-url :feed other} {:at (at 80)})
                                               (library/update-profile profile.marks/star id))})
                        (library/read-saved! one s)))
         #(async/then (library/save! (:one @tabs) s)
                      (fn [{:keys [library]}]
                        (is (some? (get-in library [:stored :version])))
                        (swap! tabs assoc :one library)
                        (library/save! (:two @tabs) s)))
         #(async/then (library/read-saved! (:one @tabs) s)
                      (fn [stored]
                        (let [one    (:one @tabs)
                              caught (library/rebase one (:stored one) stored)]
                          (testing "a save catches up with what another saved, and the other with that"
                            (is (= "phone/1" (get-in caught [:profile :replica])))
                            (is (= [id] (profile.marks/starred (:profile caught))))
                            (is (= #{guid other-key} (set (keys (library/subscriptions caught)))))
                            (is (= {"phone/1" 60 "phone/2" 30}
                                   (update-vals (listened caught) :seconds))
                                "a count of each replica, so that neither tab's listening is lost")
                            (is (library/loaded? caught other-key) "a feed new to it, read")
                            (is (identical? (get-in one [:feeds guid :feed]) (get-in caught [:feeds guid :feed]))
                                "a feed it holds, kept as it was"))
                          (library/read-saved! caught s))))
         #(async/then (library/read-saved! (library/make "phone" {:replica "1" :config config}) s)
                      (fn [stored]
                        (is (some? stored) "a library that never read the store reads all of it")))
         ;; a message reaches the other channel as a task of its own
         #?(:clj  (constantly nil)
            :cljs #(js/Promise. (fn [resolve] (js/setTimeout resolve 50))))
         (fn []
           (is (= 1 (count @heard)) "the replica with a channel told the other on it")
           (is (= "memory" (:saved (first @heard))))
           (replica/close! told)
           (replica/close! hearing)))))

(deftest feeds-on-demand
  (let [documents (atom {})
        s         (store/memory documents)
        made      #(library/make "phone" {:config {:now-fn (constantly now)}})
        two       (library/subscribe library other-url {:status 200 :url other-url :feed other})
        other-key (feed.guid/feed-guid other other-url)
        fetched   (atom [])
        fetch     (fn [u _] (swap! fetched conj u) {:status 200 :url u :feed newer})]
    (run #(library/save! two s)
         #(async/then (library/restore! (made) s {:load :none})
                      (fn [lib]
                        (is (= #{guid other-key} (set (library/unloaded lib))))
                        (is (not (library/loaded? lib guid)))
                        (is (= {:url other-url :unloaded? true :title "Other"}
                               (select-keys (library/show lib other-key) [:url :unloaded? :title]))
                            "a show with what the profile knows of it")
                        (is (every? :unloaded? (library/shows lib)))
                        (is (= [] (library/episodes lib)))
                        (is (nil? (library/episode lib [other-key "o1"])))
                        (is (seq (library/search lib "Other one")) "the search index still has it")
                        (async/then (library/save! lib s)
                                    (fn [{:keys [put delete]}]
                                      (is (not-any? (fn [n] (re-find #"^feed-" n)) put))
                                      (is (= [] delete) "the documents of the feeds stay")))))
         #(async/then (library/restore! (made) s {:load [guid]})
                      (fn [lib]
                        (is (= [true false] (map (partial library/loaded? lib) [guid other-key])))
                        (async/then (library/load! lib s [other-key])
                                    (fn [loaded]
                                      (is (= [] (:report (:load loaded))))
                                      (is (= (get-in two [:feeds other-key]) (get-in loaded [:feeds other-key]))
                                          "as it was")))))
         #(async/then (library/unload! two s [guid])
                      (fn [lib]
                        (is (= [] (:report (:unload lib))))
                        (is (= [guid] (library/unloaded lib)))
                        (is (contains? @documents (get-in lib [:feeds guid :document])) "written first")
                        (is (= [guid] (library/unloaded (library/merge two lib))) "a merge keeps it unloaded")
                        (is (seq (library/search (library/merge two lib) "Other one")))))
         (fn []
           (let [failing (assoc s :put (fn [_ _ _] (throw (ex-info "Disk full" {}))))]
             (async/then (library/unload! two failing [guid])
                         (fn [lib]
                           (is (= [:library/save-failed] (map :id (:report (:unload lib)))))
                           (is (library/loaded? lib guid) "a feed that couldn't be written stays")))))
         (fn []
           (async/then (library/restore! (made) s {:load :none})
                       (fn [lib]
                         (let [opts {:at (at 86400) :feed-guids [guid] :fetch fetch}]
                           (async/then (library/refresh! lib opts)
                                       (fn [lib]
                                         (is (= [:library/not-loaded] (map :id (:report (:refresh lib)))))
                                         (is (= [] @fetched) "nothing fetched without the store")
                                         (async/then (library/refresh! lib (assoc opts :store s))
                                                     (fn [lib]
                                                       (is (= [[guid "new"]] (:added (:refresh lib)))
                                                           "loaded first, so the new episode is new")
                                                       (is (library/loaded? lib guid))
                                                       (is (not (library/loaded? lib other-key)))))))))))
         (fn []
           (let [id     [guid "https://example.com/ep0003"]
                 played (-> library
                            (update :profile profile/prefer {:downloads {:default {:played 0}}} now)
                            (update :files assoc id {:url "https://example.com/file-03.mp3" :bytes 8800
                                                     :at now :finished-at now :path "/ep3.mp3"})
                            (library/update-profile profile/mark-played id))]
             (is (= [id] (map :id (:delete (library/plan played)))))
             (async/then (library/unload! played s [guid])
                         (fn [lib]
                           (is (= [] (:delete (library/plan lib)))
                               "no file of a feed that the plan can't see"))))))))

(deftest the-episodes-that-a-fetch-no-longer-brings-back
  (let [[ep3 ep2 ep1] (:rss/items show)
        id        (fn [item] [guid (feed.guid/item-guid item)])
        guids     (fn [lib] (mapv feed.guid/item-guid (get-in lib [:feeds guid :feed :rss/items])))
        refreshed (fn [lib items t opts]
                    (library/refresh! lib (merge {:at         t
                                                  :feed-guids [guid]
                                                  :fetch      (fn [u _] {:status 200 :url u :feed (assoc show :rss/items items)})}
                                                 opts)))
        missed    {:missed-at (at 86400)}
        starred   (library/update-profile library profile.marks/star (id ep1))
        s         (store/memory (atom {}))
        pages     (atom nil)]
    (run
     #(async/then (refreshed library [ep3] (at 86400) {})
                  (fn [lib]
                    (testing "an episode that the feed no longer brings back can still be shown and played"
                      (is (= missed (:retained (library/episode lib (id ep1)))))
                      (is (= [nil missed missed] (map :retained (:episodes (library/show lib guid))))
                          "and the show lists it with its fact")
                      (is (= [nil missed missed] (map (comp :retained second) (library/episodes lib {:order :newest})))))
                    (async/then (refreshed lib (:rss/items show) (at (* 2 86400)) {})
                                (fn [lib']
                                  (is (= [] (:added (:refresh lib'))) "nor is it new when it comes back")
                                  (is (nil? (get-in lib' [:feeds guid :retained])))))))
     #(async/then (refreshed starred [ep3] (at 86400) {:max-items 1})
                  (fn [lib]
                    (is (= (mapv feed.guid/item-guid [ep3 ep1]) (guids lib))
                        "a cap drops the episodes that the feed no longer brings back, but none that the listener marked")
                    (is (= {(feed.guid/item-guid ep1) missed} (get-in lib [:feeds guid :retained])))))
     #(async/then (refreshed library [ep3] (at 86400) {:max-items 2})
                  (fn [lib]
                    (is (= (mapv feed.guid/item-guid [ep3 ep2]) (guids lib)) "the oldest first")))
     #(async/then (refreshed library [ep3] (at 86400) {})
                  (fn [lib]
                    (async/then (library/save! lib s)
                                (fn [_]
                                  (async/then (library/restore! (library/make "phone") s)
                                              (fn [lib']
                                                (is (= (get-in lib [:feeds guid]) (get-in lib' [:feeds guid]))
                                                    "saved with the feed, and read back")))))))
     #(async/then (refreshed library [] (at 86400) {})
                  (fn [lib]
                    (is (= [:fetch/empty-feed] (map :id (:report (:refresh lib)))))
                    (is (= show (get-in lib [:feeds guid :feed])) "an empty answer leaves the episodes")))
     #(async/then (library/refresh! (library/update-profile (library/make "phone" {:config {:max-pages 3}})
                                                            profile/subscribe "g" {:url url})
                                    {:at now :fetch (fn [u opts] (reset! pages (:max-pages opts)) {:status 200 :url u :feed show})})
                  (fn [_]
                    (is (= 3 @pages) "the pages of a paged feed at the first fetch of a subscription"))))))

(deftest a-host-that-rewrites-guids
  (let [[ep3 _ ep1] (:rss/items show)
        moved       #(update-in % [:rss/guid :content] str "?moved")
        rewritten   (update show :rss/items (partial mapv moved))
        id          (fn [item] [guid (feed.guid/item-guid item)])
        lib         (library/update-profile library profile.queue/enqueue (id ep1))]
    (run #(async/then (library/refresh! lib {:at         (at 86400)
                                             :feed-guids [guid]
                                             :fetch      (fn [u _] {:status 200 :url u :feed rewritten})})
                      (fn [{{:keys [added backfilled renamed]} :refresh :as lib'}]
                        (is (= [[] []] [added backfilled]) "appends nothing to the queue")
                        (is (= (set (map (juxt id (comp id moved)) (:rss/items show))) (set renamed)))
                        (is (= [(id (moved ep1))] (profile.queue/queue (:profile lib'))) "the episode keeps its place")
                        (is (= (:rss/title ep3) (:rss/title (library/episode lib' (id (moved ep3))))))
                        (is (nil? (get-in lib' [:feeds guid :retained])) "and the old items aren't kept"))))))

(deftest a-document-to-save-by-hand
  (let [{:keys [name body]} (library/document library {:app "test"})
        written             (document/decode body profile/sync-options)]
    (is (= (str (:device library) ".edn") name) "the name that a backend gives it")
    (is (= (:profile library) (:state written)))
    (is (= ["test" now] ((juxt :app :written-at) written)) "written now, by the clock of the library")))

(deftest the-downloads
  (let [id  [guid "https://example.com/ep0003"]
        lib (-> library
                (update :profile profile.queue/enqueue id now)
                (update :profile profile/prefer {:downloads {:queue 1}} now))]
    (is (= [[id :queue]] (map (juxt :id :reason) (:download (library/plan lib {})))))
    (is (= [] (:download (library/plan lib {id {:bytes 1 :at now}}))) "a file that the device has")))

(deftest the-record-of-the-files
  (let [id     [guid "https://example.com/ep0003"]
        lib    (-> library
                   (update :profile profile.queue/enqueue id now)
                   (update :profile profile/prefer {:downloads {:queue 1 :default {:played 0}}} now))
        entry  (first (:download (library/plan lib)))
        filed  (-> lib
                   (library/start-download entry {:at (at 10)})
                   (library/finish-download id {:path "/podcasts/ep3.mp3" :bytes 8800} {:at (at 20)}))
        played (library/update-profile filed (fn [p at]
                                               (-> (profile/mark-played p id at)
                                                   (profile.queue/dequeue id at))))
        s      (store/memory (atom {}))]
    (is (= {id {:url "https://example.com/file-03.mp3" :type "audio/mpeg" :bytes 8800
                :at (at 10) :finished-at (at 20) :path "/podcasts/ep3.mp3"}}
           (:files filed)))
    (testing "the plan reads it, unless it's given files of its own"
      (is (= [] (:download (library/plan filed))))
      (is (= [{:id id :bytes 8800 :reason :played}] (:delete (library/plan played))))
      (is (= [] (:delete (library/plan played {}))))
      (is (= [] (:delete (library/plan (library/keep-file played id true)))) "nor a file that the listener keeps")
      (is (= [] (:delete (library/plan (library/remove-file played id {:at (at 30)})))) "nor one that's gone"))
    (testing "a merge keeps the record of this device"
      (is (= (:files filed) (:files (library/merge filed lib)))))
    (run #(async/then (library/save! filed s)
                      (fn [_]
                        (async/then (library/restore! (library/make "phone" {:config {:now-fn (constantly now)}}) s)
                                    (fn [lib]
                                      (is (= (:files filed) (:files lib)) "saved and read back"))))))))

(defn- dune
  "The show of the local files `names` of an audiobook, as feed.local
  builds it."
  [& names]
  (local/feed (for [n names]
                {:url   (str "file:///Users/ann/Books/Dune/" n)
                 :name  n
                 :media {:format :mp3 :duration 600 :tags {:album "Dune" :artist "Frank Herbert"}}})))

(deftest a-show-of-local-files
  (let [show    (dune "01.mp3" "02.mp3")
        g       (:podcast/guid show)
        id      [g "01.mp3"]
        fetched (atom [])
        fetch   (fn [u _] (swap! fetched conj u) fresh)
        phone   (-> (library/make "phone" {:config {:now-fn (constantly now) :fetch fetch}})
                    (library/subscribe-local show)
                    (update :profile profile.queue/enqueue id now)
                    (update :profile profile/prefer {:downloads {:queue 1}} now))]
    (is (= {:url nil :title "Dune"} (select-keys (get (profile/subscriptions (:profile phone)) g) [:url :title]))
        "a subscription without a URL")
    (is (nil? (library/next-refresh phone)) "with nothing to fetch")
    (is (= [] (:download (library/plan phone))) "or to download, though it's queued")
    (is (= [[] ["Dune"]] ((juxt :subscriptions #(map :text (:comments %))) (profile.opml/to-opml (:profile phone))))
        "and a comment in its OPML rather than a feed for another app to import")
    (is (= ["file:///Users/ann/Books/Dune/01.mp3" true] ((juxt :src :local?) (library/playback phone id)))
        "a file on the device to play")
    (testing "the files as they are now"
      (let [more (library/subscribe-local (library/update-profile phone profile/progress id 300) (dune "01.mp3" "02.mp3" "03.mp3"))]
        (is (= ["01" "02" "03"] (map :rss/title (get-in more [:feeds g :feed :rss/items]))))
        (is (= 300 (:position (library/playback more id))) "and the progress of an episode stays")))
    (testing "another device with the same files"
      (let [laptop (-> (library/make "laptop" {:config {:now-fn (constantly now)}})
                       (library/subscribe-local (dune "02.mp3" "01.mp3"))
                       (update :profile profile/merge (:profile (library/update-profile phone profile/progress id 300))))]
        (is (= 300 (:position (library/playback laptop id))) "shares the progress, by the guids that the files give")))
    (run #(async/then (library/refresh! phone {:feed-guids [g]})
                      (fn [_]
                        (is (= [] @fetched) "a refresh never fetches it, even when asked to"))))))
