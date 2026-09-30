(ns dk.simongray.podcast.app.episode-test
  "The view of an episode: what the feed says, and what its files say,
  read through a stand-in for the sender."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.episode :as episode]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.test-support :refer [fixture run]]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.util.bytes :as bytes]))

(def show
  (podcast/parse (fixture "feeds/namespace-example.xml")))

(def item
  "Episode 3, which has a chapters file, two transcripts and five
  enclosures."
  (first (resolve/episodes show)))

(def chapters-json
  "{\"version\":\"1.2.0\",\"chapters\":[{\"startTime\":0,\"title\":\"Intro\"},{\"startTime\":60,\"title\":\"News\"}]}")

(def vtt
  "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<v Ann>Hola\n\n00:00:04.000 --> 00:00:06.000\nAdiós\n")

(defn- answering
  "A sender that answers each URL by `answers`, a map of URL to `{:status
  :body}`, and throws for a URL that is not in it, noting each URL in
  `log`."
  [answers log]
  (fn [{:keys [url]}]
    (swap! log conj url)
    (or (some-> (get answers url) (assoc :headers {}))
        (throw (ex-info (str "No route to " url) {:url url})))))

(deftest what-the-feed-says
  (let [v (episode/view show item)]
    (testing "the item, its properties and the identity"
      (is (= "Episode 3 - The Future" (:title v)))
      (is (= "Episode 3 - The Future" (:rss/title v)) "the raw fields stay")
      (is (= "https://example.com/ep0003" (:guid v)))
      (is (= [(:podcast/guid show) "https://example.com/ep0003"] (:id v)))
      (is (= {:type "full" :season 1 :episode 3} (select-keys (:numbering v) [:type :season :episode]))))
    (testing "the show notes as Hiccup and as text"
      (is (= '([:p {} "A look into the future of podcasting and how we get to Podcasting 2.0!"]) (:notes v)))
      (is (= "A look into the future of podcasting and how we get to Podcasting 2.0!" (:text v))))
    (testing "the enclosure and the transcript, as the options select them"
      (is (= "https://example.com/file-03.mp3" (:url (:enclosure v))))
      (is (= "https://example.com/file-low-03.opus"
             (:url (:enclosure (episode/view show item {:types ["audio/opus"] :max-bitrate 64000})))))
      (is (= "text/vtt" (:type (:transcript v))) "a timed type first")
      (is (= "text/plain" (:type (:transcript (episode/view show item {:transcript-types ["text/plain"]}))))))
    (testing "the chapters that the feed holds, and the file that it names"
      (is (nil? (:chapters v)) "no embedded chapters, no timestamps in the notes")
      (is (= "https://example.com/ep3_chapters.json" (:chapters-url v)))
      (let [timed (assoc item :rss/description "00:00 Intro\n12:30 News")]
        (is (= [:inferred 2] ((juxt :source (comp count :chapters :document)) (:chapters (episode/view show timed)))))
        (is (= [:chapters/inferred] (map :id (:report (:chapters (episode/view show timed))))))))
    (testing "the links to the apps"
      (is (some #(= :podcastindex (:app %)) (:links v))))
    (testing "what a list leaves out is not computed"
      (let [timed (assoc item :rss/description "00:00 Intro\n12:30 News")
            row   (episode/view show timed {:except #{:notes :text :chapters :links}})]
        (is (= "Episode 3 - The Future" (:title row)))
        (is (not-any? #(contains? row %) [:notes :text :chapters :links]))
        (is (= [:inferred] [(:source (:chapters (episode/view show timed {:except #{:notes :text}})))])
            "the chapters read the text, whether the view keeps it or not")))))

(deftest what-the-files-say-when-some-are-left-out
  (let [log (atom [])]
    (run
     (fn []
       (async/then (episode/read-files! (episode/view show item) {:send (answering {} log) :except #{:chapters :transcript :media}})
                   (fn [files]
                     (is (= [] @log) "no file is asked for")
                     (is (= {} files) "nothing to merge into the view")))))))

(deftest what-the-files-say
  (let [log     (atom [])
        answers {"https://example.com/ep3_chapters.json" {:status 200 :body chapters-json}
                 "https://example.com/episode1/transcript.vtt" {:status 200 :body vtt}
                 "https://example.com/file-03.mp3" {:status 200 :body (bytes/of (repeat 64 0))}}
        page    (fn [view opts]
                  (async/then (episode/read-files! view opts) #(merge view %)))]
    (run
     (fn []
       (async/then (page (episode/view show item {:languages ["es"]}) {:send (answering answers log)})
                   (fn [v]
                     (testing "the chapters file, the transcript and the media file"
                       (is (= [:file "https://example.com/ep3_chapters.json" ["Intro" "News"]]
                              ((juxt :source :url #(map :title (:chapters (:document %)))) (:chapters v))))
                       (is (= ["Hola" "Adiós"] (map :body (:segments (:document (:transcript v))))))
                       (is (= "Ann" (:speaker (first (:segments (:document (:transcript v)))))))
                       (is (= "https://example.com/file-03.mp3" (:url (:media v))))
                       (is (= [:file/unknown-format] (map :id (:report (:media v)))) "64 zero bytes are no media file")
                       (is (= 43200000 (:length (:enclosure v))) "the enclosure is as the feed has it")
                       (is (= [[:media :file/unknown-format]] (map (juxt :part :id) (episode/report v))))
                       (is (= 3 (count (distinct @log))) "one request for each file")))))
     (fn []
       (async/then (page (episode/view show item {:transcript-types ["text/plain"]}) {:send (answering answers log) :except #{:media}})
                   (fn [v]
                     (testing "without the media file"
                       (is (nil? (:media v)))
                       (is (= "text/plain" (:type (:transcript v))))
                       (is (= :file/unreachable (:id (:error (:transcript v)))) "the plain transcript has no route")
                       (is (= [[:transcript :file/unreachable]] (map (juxt :part :id) (episode/report v)))))))))))

(deftest the-order-of-the-chapter-sources
  (let [log      (atom [])
        embedded (assoc item :psc/chapters [{:start "00:00:00" :title "Embedded"}])
        timed    (assoc item :rss/description "00:00 Intro\n12:30 News")
        gone     {"https://example.com/ep3_chapters.json" {:status 404 :body ""}}
         source  (fn [item answers]
                   (async/then (episode/read-files! (episode/view show item) {:send (answering answers log) :except #{:media}})
                               (fn [files] [(:source (:chapters files)) (map :id (:report (:chapters files))) (:id (:error (:chapters files)))])))]
    (run
     (fn []
       (async/then (source embedded {"https://example.com/ep3_chapters.json" {:status 200 :body chapters-json}})
                   #(is (= [:file () nil] %) "the file first")))
     (fn []
       (async/then (source embedded gone)
                   #(is (= [:embedded [:file/unreachable] nil] %) "then the embedded ones, told of the file")))
     (fn []
       (async/then (source timed gone)
                   #(is (= [:inferred [:chapters/inferred :file/unreachable] nil] %) "then the timestamps")))
     (fn []
       (async/then (source item gone)
                   #(is (= [:file [] :file/unreachable] %) "nothing else: the file, with its error"))))))
