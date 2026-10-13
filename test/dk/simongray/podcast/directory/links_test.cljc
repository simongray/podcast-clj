(ns dk.simongray.podcast.directory.links-test
  "The links to a show and an episode in the apps: the keys of a show, the
  forms of the table, the ids read back from a link, and the link to a
  time in a media file."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.directory.links :as links]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.wary-fetch.url :as url]))

(def feed-url "https://feeds.podcastindex.org/pc20.xml")

(def show
  "A show as `directory/combine` gives it, with the ids of two directories."
  {:rss/title         "Podcasting 2.0"
   :podcast/guid      "917393e3-1b1e-5cef-ace4-edaa54e1f810"
   :atom/links        [{:rel "self" :href feed-url}]
   :directory/sources {:apple "1524356573" :podcastindex 920666}})

(def item
  {:rss/guid {:content "PC20 272/a"} :rss/enclosure {:url "https://example.org/272.mp3?x=1"}})

(deftest the-keys-of-a-show
  (is (= {:feed-url        feed-url
          :podcast-guid    "917393e3-1b1e-5cef-ace4-edaa54e1f810"
          :apple-id        "1524356573"
          :podcastindex-id 920666}
         (links/ids show)))
  (is (= {:episode-guid "PC20 272/a" :enclosure-url "https://example.org/272.mp3?x=1"}
         (select-keys (links/ids show item) [:episode-guid :enclosure-url :podcastindex-episode-id])))
  (testing "a show of one directory, and a feed and an episode of the Podcast Index"
    (is (= "1584274529" (:apple-id (links/ids {:directory/source :apple :directory/id "1584274529"}))))
    (is (= {:apple-id 1524356573 :podcastindex-id 920666}
           (select-keys (links/ids {:index/id 920666 :index/itunes-id 1524356573}) [:apple-id :podcastindex-id])))
    (is (= 16795090 (:podcastindex-episode-id (links/ids show {:index/id 16795090 :index/feed-id 920666})))))
  (testing "a feed that declares no guid has the guid of its URL, as the Podcast Index gives it"
    (is (= {:feed-url "https://example.org/feed.xml" :podcast-guid (guid/from-url "https://example.org/feed.xml")}
           (links/ids {:rss/title "No links"} nil {:url "https://example.org/feed.xml"}))))
  (testing "but a show from a directory only has the guid that it knows the feed declares"
    (is (= {:feed-url feed-url :apple-id "1584274529"}
           (links/ids {:atom/links [{:rel "self" :href feed-url}] :directory/source :apple :directory/id "1584274529"}))))
  (is (= {} (links/ids {:rss/title "Nothing"})) "a show that nothing names")
  (is (= {} (links/ids {} {:rss/guid {:content ""}}))))

(deftest the-link-of-an-app
  (let [ids (links/ids show item)]
    (testing "a key as it is, and in each encoding"
      (is (= "https://pca.st/itunes/1524356573" (links/link :pocketcasts :show ids)))
      (is (= "https://fountain.fm/show/920666" (links/link :fountain :show ids)))
      (is (= "https://antennapod.org/deeplink/subscribe?url=https://feeds.podcastindex.org/pc20.xml"
             (links/link :antennapod :show ids)))
      (is (= "https://player.fm/subscribe?id=https%3A%2F%2Ffeeds.podcastindex.org%2Fpc20.xml"
             (links/link :playerfm :show ids)))
      (is (= "https://pod.link/aHR0cHM6Ly9mZWVkcy5wb2RjYXN0aW5kZXgub3JnL3BjMjAueG1s"
             (links/link :podlink :show {:feed-url feed-url})))
      (is (= "https://app.podcastguru.io/podcast/X68747470733a2f2f612e6578616d706c652f662e786d6c"
             (links/link :podcastguru :show {:feed-url "https://a.example/f.xml"}))))
    (testing "a link that opens the app has the URL of the feed without its scheme"
      (is (= "podcast://feeds.podcastindex.org/pc20.xml" (links/link :apple :subscribe ids)))
      (is (= "pktc://subscribe/feeds.podcastindex.org/pc20.xml" (links/link :pocketcasts :subscribe ids)))
      (is (= "overcast://x-callback-url/add?url=https://feeds.podcastindex.org/pc20.xml"
             (links/link :overcast :subscribe ids))
          "but for Overcast, which takes the scheme too"))
    (testing "the link of an episode"
      (is (= "https://podcastaddict.com/episode/https%3A%2F%2Fexample.org%2F272.mp3%3Fx%3D1"
             (links/link :podcastaddict :episode ids)))
      (is (= "https://steno.fm/show/917393e3-1b1e-5cef-ace4-edaa54e1f810/episode/UEMyMCAyNzIvYQ"
             (links/link :steno :episode ids)))
      (is (= "https://podfriend.com/podcast/1524356573/PC20%20272%2Fa" (links/link :podfriend :episode ids))))
    (testing "the first form whose keys the show has"
      (is (= "https://pod.link/1524356573" (links/link :podlink :show ids)))
      (is (= "https://podnews.net/podcast/pi920666" (links/link :podnews :show (dissoc ids :apple-id))))
      (is (= "https://podnews.net/podcast/917393e3-1b1e-5cef-ace4-edaa54e1f810"
             (links/link :podnews :show (dissoc ids :apple-id :podcastindex-id)))))
    (testing "the part of a link that the app does not read"
      (is (= "https://podurama.com/podcast/podcast-i5" (links/link :podurama :show {:apple-id 5})))
      (is (= "https://podurama.com/podcast/pc20-i5" (links/link :podurama :show {:apple-id 5 :slug "pc20"}))))
    (testing "no link"
      (is (nil? (links/link :pocketcasts :show {:feed-url feed-url})) "the show lacks the key")
      (is (nil? (links/link :pocketcasts :episode ids)) "the app has no such link in the table")
      (is (nil? (links/link :nothing :show ids)) "the table has no such app"))))

(deftest the-links-of-a-show
  (let [ids (links/ids show item)]
    (is (= [{:app       :antennapod :name "AntennaPod" :type :app
             :show      "https://antennapod.org/deeplink/subscribe?url=https://feeds.podcastindex.org/pc20.xml"
             :subscribe "antennapod-subscribe://feeds.podcastindex.org/pc20.xml"}
            {:app :fountain :name "Fountain" :type :app :show "https://fountain.fm/show/920666"}
            {:app :podlink :name "pod.link" :type :page :show "https://pod.link/1524356573"}]
           (links/links ids {:only #{:antennapod :fountain :podlink}}))
        "in the order of the names, each with the kinds that the keys are enough for")
    (is (= (count links/apps) (count (links/links ids))) "a show with each key has a link in each app")
    (is (= ["1tuner" "AntennaPod" "Anytime Player" "Apple Podcasts"]
           (take 4 (map :name (links/links {:feed-url feed-url}))))
        "a feed alone has the links that take its URL")
    (is (not-any? #(= :pocketcasts (:app %)) (links/links {:apple-id nil :feed-url nil})))
    (is (= [] (links/links {})))
    (testing "the table of the app"
      (is (= [{:app :mine :name "Mine" :type :app :show "https://mine.example/s/917393e3-1b1e-5cef-ace4-edaa54e1f810?t=754"}]
             (links/links (assoc ids :time 754)
                          {:apps {:mine {:name "Mine" :type :app
                                         :show [["https://mine.example/s/" :podcast-guid "?t=" :time]]}}}))
          "a form takes each key that the ids have, such as a time"))))

(deftest the-table-of-the-apps
  (is (every? (fn [[_ {:keys [name type]}]] (and (string? name) (contains? #{:app :page} type))) links/apps))
  (is (every? (fn [[_ entry]] (some entry links/kinds)) links/apps) "each app has a link")
  (testing "each form has parts that `link` knows"
    (doseq [[app entry] links/apps
            kind        links/kinds
            form        (get entry kind)
            p           form]
      (is (or (string? p)
              (contains? #{:feed-url :podcast-guid :apple-id :podcastindex-id :slug :episode-guid
                           :enclosure-url :podcastindex-episode-id :apple-episode-id}
                         (if (keyword? p) p (second p))))
          (pr-str [app kind p]))
      (is (or (not (vector? p)) (contains? #{:encoded :base64url :hex :bare} (first p)))
          (pr-str [app kind p]))))
  (testing "a link to the web is an https link, and a link to an app has the scheme of the app"
    (doseq [{:keys [app show episode subscribe]} (links/links (links/ids show item))]
      (is (every? #(str/starts-with? % "https://") (remove nil? [show episode])) (str app))
      (is (or (nil? subscribe) (re-find #"^[a-z][a-z0-9+.-]*://" subscribe)) (str app))
      (is (not (str/starts-with? (str subscribe) "http")) (str app)))))

#?(:clj
   (deftest the-table-in-the-docs
     (let [page (slurp "doc/services.md")
           part (fn [p]
                  (cond
                    (string? p)  p
                    (keyword? p) (str "{" (name p) "}")
                    :else        (str "{" (name (first p)) " " (name (second p)) "}")))
           cell (fn [forms] (str/join "<br>" (map #(str "`" (apply str (map part %)) "`") forms)))]
       (doseq [[app {:keys [name type show episode subscribe]}] links/apps]
         (is (str/includes? page (str "| " name " | " (clojure.core/name type) " | " (cell show) " | " (cell episode)
                                      " | " (cell subscribe) " |"))
             (str "doc/services.md has the forms of " app " as the table has them")))
       (is (= (count links/apps) (count (re-seq #"(?m)^\| [^|]+ \| (?:app|page) \|" page)))
           "and no app that the table does not have"))))

(deftest reading-a-link-back
  (let [ids  {:feed-url                feed-url
              :podcast-guid            "917393e3-1b1e-5cef-ace4-edaa54e1f810"
              :apple-id                "1524356573"
              :podcastindex-id         920666
              :slug                    "podcasting-2-0"
              :episode-guid            "PC20-272"
              :enclosure-url           "https://example.org/272.mp3"
              :podcastindex-episode-id 123}
        bare #(str/replace (str %) #"^https://" "")]
    (testing "each link that the table writes, back to the ids it holds"
      (doseq [[app entry] links/apps
              kind        links/kinds
              :let        [link (links/link app kind ids)]
              :when       (and link (some #{:feed-url :apple-id :podcast-guid :podcastindex-id}
                                          (keep #(if (keyword? %) % (second %)) (mapcat identity (get entry kind)))))
              :let        [read (links/link-ids link)]]
        (is (seq read) link)
        (doseq [[k v] read]
          (is (= (if (= :feed-url k) (bare v) v) (if (= :feed-url k) (bare (ids k)) (ids k))) (str link " " k)))))
    (testing "the links that Apple Podcasts and Overcast share"
      (is (= {:apple-id "1524356573" :apple-episode-id "1000"}
             (links/link-ids "https://podcasts.apple.com/dk/podcast/podcasting-2-0/id1524356573?i=1000"))
          "with the episode")
      (is (= {:apple-id "1524356573"} (links/link-ids "http://www.podcasts.apple.com/podcast/id1524356573")))
      (is (= {:apple-id "1524356573"} (links/link-ids "https://overcast.fm/itunes1524356573/podcasting-2-0"))))
    (testing "a feed URL that is encoded, though its form has it plain"
      (is (= {:feed-url feed-url}
             (links/link-ids (str "https://antennapod.org/deeplink/subscribe?url=" (url/percent-encode feed-url))))))
    (testing "a link that names no show"
      (is (nil? (links/link-ids "https://fountain.fm/episode/123")) "only an episode")
      (is (nil? (links/link-ids "https://pod.link/not-a-url")) "a base64 that is no URL")
      (is (nil? (links/link-ids feed-url)) "a feed")
      (is (nil? (links/link-ids "https://podcasts.apple.com/dk/charts"))))))

(deftest a-time-in-a-media-file
  (let [url "https://example.org/272.mp3"]
    (is (= "https://example.org/272.mp3#t=754" (links/at-time url 754)))
    (is (= "https://example.org/272.mp3#t=754,796.5" (links/at-time url 754 796.5)))
    (is (= "https://example.org/272.mp3#t=,20" (links/at-time url nil 20)) "to a time, from the start of the file")
    (is (= "https://example.org/272.mp3?x=1#t=5" (links/at-time (str url "?x=1#t=1") 5))
        "in the place of the fragment that the URL has")
    (is (= "https://example.org/272.mp3#t=0,0.0000001" (links/at-time url 0 1.0E-7)) "seconds without an exponent")
    (doseq [bad [nil -1 ##NaN ##Inf "754"]]
      (is (= url (links/at-time (str url "#old") bad)) (str "no time for " (pr-str bad))))))
