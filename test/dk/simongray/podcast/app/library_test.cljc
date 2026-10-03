(ns dk.simongray.podcast.app.library-test
  "The library of a listener: subscribing, following a list, the step that
  fetches what is due through stand-ins, the views, the episodes across
  shows, the events of the player, the sync between two devices and the
  downloads."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?(:clj [clojure.edn :as edn] :cljs [cljs.reader :as edn])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.feeds :as feeds]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.crdt :as crdt]
            [dk.simongray.podcast.util.time :as time]))

(def now #inst "2026-09-30T12:00:00Z")

(defn- at
  [seconds]
  (time/->inst (+ (inst-ms now) (* 1000 seconds))))

(def url "https://example.com/feed.xml")

(def show
  (podcast/parse (fixture "feeds/namespace-example.xml")))

(def guid (:podcast/guid show))

(def fresh
  {:status 200 :url url :feed show :etag "\"v1\""})

(def other-url "https://example.org/other.xml")

(def other
  "Another show, with one episode."
  (-> show
      (assoc :podcast/guid "0ther-guid" :rss/title "Other")
      (update :rss/items (fn [items] [(assoc (first items) :rss/guid {:content "o1"} :rss/title "Other one")]))))

(def newer
  "The show with a new episode."
  (update show :rss/items conj (assoc (first (:rss/items show)) :rss/guid {:content "new"} :rss/title "New one" :rss/pub-date #inst "2026-09-30T00:00:00Z")))

(def library
  (-> (library/make "phone" {:name "Phone" :config {:languages ["es"] :now (constantly now)}})
      (library/subscribe url fresh {:auto-queue? true})))

(def picks
  "An OPML list with the other show."
  (opml/parse (str "<opml version=\"2.0\"><head><title>Picks</title></head><body>"
                   "<outline type=\"rss\" text=\"Other\" xmlUrl=\"" other-url "\"/>"
                   "</body></opml>")))

(deftest a-slim-library
  (let [make    (fn [config]
                  (-> (library/make "phone" {:name "Phone" :config (assoc config :now (constantly now))})
                      (library/follow "https://example.org/picks.opml" picks {:title "Picks"})
                      (library/subscribe url fresh {:auto-queue? true})))
        fetch   (fn [u _]
                  (case u
                    "https://example.com/feed.xml"  {:status 200 :url u :feed newer :etag "\"v2\""}
                    "https://example.org/other.xml" {:status 200 :url u :feed other}))
        send    (fn [_] {:status 304 :headers {} :body ""})
        entries (fn [lib] (update-vals (:feeds lib) #(dissoc % :feed)))
        both    (fn [libs opts] (async/all #(library/refresh! % opts) libs))]
    (is (= (feeds/slim show) (get-in (make {:keep feeds/slim}) [:feeds guid :feed])) "a subscription keeps the slim feed")
    (run
     (fn []
       (async/then (both [(make {}) (make {:keep feeds/slim})] {:at (at 86400) :fetch fetch :send send})
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
    (is (nil? (:index lib)) "no index unless the config says so")
    (is (= {} (:feeds lib)))
    (is (= library/default-config (:config lib)))
    (is (some? (:index (library/make "phone" {:config {:search? true}}))))
    (is (= now (library/next-sync lib {:at now})) "due at once before the first sync")))

(deftest the-clock
  (let [id      [guid "https://example.com/ep0003"]
        clock   #(:clock (:profile %))
        clocked (assoc-in library [:config :now] #(at 60))]
    (testing "each change is stamped with the time of the clock in the config"
      (is (= (at 60) (clock (library/unsubscribe clocked guid))))
      (is (= (at 60) (clock (library/update-profile clocked profile/enqueue [guid "new"]))))
      (is (= (at 60) (clock (library/update-profile clocked (fn [p t] (profile/star p id t))))) "a series of changes")
      (is (= (at 60) (library/next-sync clocked)))
      (is (= 3 (count (library/episodes clocked {:where {:age [0 nil]}}))) "the criteria that need the time"))
    (testing "or with the :at of the options, an instant"
      (is (= (at 120) (clock (library/unsubscribe clocked guid {:at (at 120)}))))
      (is (= (library/step clocked [:play id]) (library/step clocked [:play id] {:at (at 60)}))))
    (testing "and without a clock in the config, that of the platform"
      (is (<= (inst-ms now) (inst-ms (clock (library/unsubscribe (update library :config dissoc :now) guid)))))
      (is (= "tablet" (:device (library/make "tablet")))))
    (run
     (fn []
       (async/then (library/sync! clocked [(store/backend (store/memory (atom {})) {:app "test"})])
                   (fn [lib] (is (= (at 60) (get-in lib [:sync :at])))))))))

(deftest subscribing
  (testing "the feed, the subscription and its settings"
    (is (= [url "\"v1\""] ((juxt :url :etag) (get-in library [:feeds guid]))))
    (is (= show (get-in library [:feeds guid :feed])))
    (let [subs (library/subscriptions library)]
      (is (= [guid] (keys subs)))
      (is (= "Podcasting 2.0 Namespace Example" (:title (subs guid))))
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
      (is (nil? (library/episode library [guid "nope"])))))
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
                       (is (= :fetch/unreachable (:id (:error (:publisher linked))))))))
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
    (is (= [guid "https://example.com/ep0003"] (:id (first (library/search lib "future")))))
    (is (= 0 (count (:docs (:index (library/unsubscribe lib guid))))))
    (is (nil? (library/search library "future")) "no index, no search")))

(deftest following-a-list
  (let [lib (library/follow library "https://example.org/picks.opml" picks {:title "Picks" :folder "Picks" :etag "\"p1\""})]
    (is (= {"https://example.org/picks.opml" {:title "Picks" :folder "Picks"}} (profile/lists (:profile lib))))
    (is (= "\"p1\"" (get-in lib [:fetched-lists "https://example.org/picks.opml" :etag])))
    (is (= (at 86400) (get-in lib [:fetched-lists "https://example.org/picks.opml" :next-check])))
    (is (= 2 (count (library/subscriptions lib))) "the show of the list joined")
    (is (nil? (:feed (get (library/subscriptions lib) (profile/normal-url other-url)))) "not fetched yet")
    (let [gone (library/unfollow lib "https://example.org/picks.opml")]
      (is (= {} (profile/lists (:profile gone))))
      (is (= {} (:fetched-lists gone)))
      (is (= 2 (count (library/subscriptions gone))) "the subscriptions stay"))))

(deftest the-step-that-fetches-what-is-due
  (let [lib     (library/follow library "https://example.org/picks.opml" picks {:title "Picks" :folder "Picks"})
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
                   (fn [{{:keys [added moved report]} :refresh :as lib'}]
                     (testing "the list asked with its validators, and unchanged"
                       (is (some #(= ["https://example.org/picks.opml" nil] %) @asked))
                       (is (= (at (* 2 86400)) (get-in lib' [:fetched-lists "https://example.org/picks.opml" :next-check]))))
                     (testing "the feed of the new subscription fetched and kept, under the guid that it declares"
                       (is (= "Other" (:rss/title (:feed (get (library/subscriptions lib') "0ther-guid")))))
                       (is (= #{guid "0ther-guid"} (set (keys (library/subscriptions lib')))) "the guid of the URL is gone")
                       (is (= "Picks" (:folder (:settings (get (library/subscriptions lib') "0ther-guid")))) "the folder of the list moved with it")
                       (is (= 2 (count (:feeds lib')))))
                     (testing "the due feed fetched, its new episode found and queued, and its move applied"
                       (is (= [[guid "new"]] added))
                       (is (= [[guid "new"]] (profile/queue (:profile lib'))) "auto-queue")
                       (is (= {guid "https://example.com/moved.xml"} moved))
                       (is (= "https://example.com/moved.xml" (:url (get (library/subscriptions lib') guid))))
                       (is (= "https://example.com/moved.xml" (get-in lib' [:feeds guid :url])))
                       (is (= "\"v2\"" (get-in lib' [:feeds guid :etag]))))
                     (is (= [] report)))))
     (fn []
       (reset! asked [])
       (async/then (library/refresh! lib {:at    later
                                          :fetch (fn [url _] (throw (ex-info "down" {:url url})))
                                          :send  (fn [_] (throw (ex-info "down" {})))})
                   (fn [{{:keys [added report]} :refresh :as lib'}]
                     (testing "nothing comes: the library stays, and the report says why"
                       (is (= [] added))
                       (is (= #{:fetch/unreachable} (set (map :id report))))
                       (is (= 3 (count report)) "the list, the new subscription and the due feed")
                       (is (= show (get-in lib' [:feeds guid :feed])))
                       (is (= 1 (:failures (get-in lib' [:feeds guid])))))))))))

(deftest a-new-subscription-through-the-send
  (let [xml  (fixture "feeds/namespace-example.xml")
        lib  (library/update-profile (library/make "phone") profile/subscribe "g" {:url url})
        send (fn [request] {:status 200 :url (:url request) :body xml})]
    (run #(async/then (library/refresh! lib {:at now :send send})
                      (fn [{{:keys [report]} :refresh :as lib'}]
                        (is (= [] report))
                        (is (= [guid] (keys (:feeds lib'))) "fetch! by default, through the :send, without a :fetch"))))))

(deftest a-feed-that-declares-the-guid-of-another
  (let [evil-url "https://evil.example/feed.xml"
        sent     (atom [])
        fetch    (fn [u opts]
                   (swap! sent conj [u (:credentials opts)])
                   {:status 200 :url u :feed show})
        listed   (library/update-profile library profile/subscribe (feed/feed-guid {} evil-url) {:url evil-url})]
    (testing "subscribing to it throws, and names the subscription it would take over"
      (let [e (try (library/subscribe library evil-url show)
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

(deftest the-episodes-across-the-shows
  (let [lib (library/subscribe library other-url {:status 200 :url other-url :feed other})]
    (is (= 4 (count (library/episodes lib))))
    (is (= #{"Other" "Podcasting 2.0 Namespace Example"} (set (map (comp :rss/title first) (library/episodes lib)))))
    (is (= ["0ther-guid" "o1"] (let [[feed item] (first (filter #(= "Other" (:rss/title (first %))) (library/episodes lib)))]
                                 (library/episode-id lib feed item)))
        "the identity of a pair, for the profile and the views")
    (is (= 2 (count (library/episodes lib {:limit 2}))))
    (let [id     [guid "https://example.com/ep0003"]
          played (update lib :profile profile/mark-played id now)]
      (is (= 3 (count (library/episodes played {:where {:status :unplayed}})))))))

(deftest the-events-of-the-player
  (let [id      [guid "https://example.com/ep0003"]
        lib     (update library :profile profile/enqueue id now)
        stepped (library/step lib [:progress id 100 3600])]
    (is (= [] (:effects stepped)))
    (is (= 100 (profile/resume-position (:profile stepped) guid id)))
    (is (= [[:seek id 130]] (:effects (library/step stepped [:skip id :forward]))))
    (testing "the sleep timer of the device lives in the library"
      (let [timed (assoc stepped :sleep {:episodes 2})
            spent (library/step (assoc timed :sleep {:episodes 1}) [:ended id])]
        (is (= {:episodes 1} (:sleep (library/step timed [:ended id]))) "one episode less to go")
        (is (= [[:stop id]] (:effects spent)))
        (is (not (contains? spent :sleep)) "the last episode ended: the timer is gone")))))

(deftest keeping-two-devices-in-step
  (let [documents (atom {})
        backend   (store/backend (store/memory documents) {:app "test"})
        laptop    (-> (library/make "laptop" {:name "Laptop" :config {:now (constantly now)}})
                      (library/subscribe other-url {:status 200 :url other-url :feed other}))]
    (run
     (fn []
       (async/then (library/sync! library [backend] {:at (at 10)})
                   (fn [{{:keys [failed report]} :sync :as lib}]
                     (is (nil? failed))
                     (is (= [] report))
                     (is (= 1 (count @documents)) "the phone wrote its document")
                     (is (map? (get-in lib [:sync :cursors])))
                     (is (= (at 10) (get-in lib [:sync :at])))
                     (is (= (at (+ 10 900)) (library/next-sync lib {:at (at 10)})) "idle: nothing changed since")
                     (async/then (library/sync! laptop [backend] {:at (at 20)})
                                 (fn [lib]
                                   (testing "the laptop learns the subscription of the phone"
                                     (is (= 2 (count (library/subscriptions lib))))
                                     (is (nil? (:feed (get (library/subscriptions lib) guid))) "a feed to fetch at the next refresh!"))))))))))

(deftest a-show-left-on-another-device
  (let [backend (store/backend (store/memory (atom {})) {:app "test"})
        laptop  (-> (library/make "laptop" {:name "Laptop" :config {:now (constantly now)}})
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

(deftest merging-what-a-fetch-gave
  (let [id      [guid "https://example.com/ep0003"]
        fetch   (fn [url _] {:status 200 :url url :feed newer :etag "\"v2\""})
        indexed (-> (library/make "phone" {:config {:search? true :now (constantly now)}})
                    (library/subscribe url fresh {:auto-queue? true}))
        backend (store/backend (store/memory (atom {})) {:app "test"})]
    (run
     (fn []
       (async/then (library/refresh! indexed {:at (at 86400) :fetch fetch})
                   (fn [result]
                     (testing "what the listener did during a refresh stays, with what the refresh found"
                       (let [meanwhile (-> indexed
                                           (library/step [:progress id 100 3600] {:at (at 86410)})
                                           (update :profile profile/star id (at 86420)))
                             merged    (library/merge meanwhile result)]
                         (is (= 100 (profile/resume-position (:profile merged) guid id)))
                         (is (= [id] (profile/starred (:profile merged))))
                         (is (= [[guid "new"]] (profile/queue (:profile merged))) "the new episode, queued")
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
                         (is (= #{guid "0ther-guid"} (set (keys (:feeds (library/merge joined result)))))))))))
     (fn []
       (async/then (library/sync! library [backend] {:at (at 10)})
                   (fn [result]
                     (testing "what the listener did during a sync stays, with the step of the sync"
                       (let [merged (library/merge (update library :profile profile/star id (at 12)) result)]
                         (is (= [id] (profile/starred (:profile merged))))
                         (is (= (:sync result) (:sync merged)))
                         (is (= (at 20) (library/next-sync merged {:at (at 12)})) "soon, since it changed")))))))))

(deftest a-library-read-back-without-its-config
  (let [saved (dissoc library :config)
        lib   (assoc saved :config {:now (constantly now)})]
    (is (= saved (edn/read-string (pr-str saved))) "EDN without the clock of the config")
    (is (= (at 86400) (get-in (library/follow lib "https://example.org/picks.opml" picks)
                              [:fetched-lists "https://example.org/picks.opml" :next-check]))
        "the defaults stand in for what the config leaves out")))

(deftest a-document-to-save-by-hand
  (let [{:keys [name body]} (library/document library {:app "test"})
        written             (sync/read-document body)]
    (is (= (str (:device library) ".edn") name) "the name that a backend gives it")
    (is (= (:profile library) (:state written)))
    (is (= ["test" now] ((juxt :app :written-at) written)) "written now, by the clock of the library")))

(deftest the-downloads
  (let [id  [guid "https://example.com/ep0003"]
        lib (-> library
                (update :profile profile/enqueue id now)
                (update :profile profile/prefer {:downloads {:queue 1}} now))]
    (is (= [[id :queue]] (map (juxt :id :reason) (:download (library/plan lib {})))))
    (is (= [] (:download (library/plan lib {id {:bytes 1 :at now}}))) "a file that the device has")))
