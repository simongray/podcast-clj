(ns dk.simongray.podcast.companions-test
  "Chapters, transcripts, OPML, value blocks and the Podcast Index client."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.chapters :as chapters]
            [dk.simongray.podcast.index :as index]
            [dk.simongray.podcast.opml :as opml]
            [dk.simongray.podcast.transcript :as transcript]
            [dk.simongray.podcast.value :as value]))

(defn- fixture
  [path]
  (slurp (io/resource path)))

;;;; Chapters

(deftest json-chapters
  (let [doc (chapters/parse-json (fixture "chapters/example-complex.json"))]
    (is (= "1.2.0" (:version doc)))
    (is (= 11 (count (:chapters doc))))
    (is (= {:start-time 0 :title "Intro"} (first (:chapters doc))))
    (is (= {:name "Eiffel Tower, Paris" :geo "geo:42.3417649,-70.9661596"}
           (:location (nth (:chapters doc) 7))))
    (is (= 10 (count (chapters/visible doc))))
    (is (= "Progress Report" (:title (chapters/at doc 300))))
    (is (nil? (chapters/at (chapters/parse-json "{\"version\":\"1.2.0\",\"chapters\":[{\"startTime\":10}]}") 5)))
    (is (= 168 (:end-time (first (chapters/with-end-times doc)))))
    (testing "round trip"
      (is (= doc (chapters/parse-json (chapters/emit-json doc)))))))

(deftest podlove-chapters
  (let [psc {:version "1.2" :chapters [{:start "00:00:00.000" :title "Intro"}
                                       {:start "00:12:30.500" :title "Main" :href "https://x" :image "https://i"}]}
        doc (chapters/from-psc psc)]
    (is (= [{:start-time 0 :title "Intro"} {:start-time 750.5 :title "Main" :url "https://x" :img "https://i"}]
           (:chapters doc)))
    (is (= [:psc/chapters {:version "1.2"}
            [:psc/chapter {:start "00:00:00.000" :title "Intro"}]
            [:psc/chapter {:start "00:12:30.500" :title "Main" :href "https://x" :image "https://i"}]]
           (chapters/to-psc doc)))))

;;;; Transcripts

(deftest transcript-formats
  (let [vtt (transcript/parse (fixture "transcripts/example.vtt"))
        srt (transcript/parse (fixture "transcripts/example.srt") "application/x-subrip")
        js  (transcript/parse (fixture "transcripts/example.json"))
        html (transcript/parse (fixture "transcripts/example.html"))]
    (is (= :vtt (:format vtt)))
    (is (= {:start 0 :end 2.76 :speaker "Sarah" :body "In today's episode, you'll learn whether or not you"}
           (first (:segments vtt))))
    (is (= :srt (:format srt)))
    (is (= 222 (count (:segments srt))))
    (is (= {:start 0.179 :end 2.399 :speaker "Travis" :body "When you first get\nstarted in podcasting, it's"}
           (first (:segments srt))))
    (is (= :json (:format js)))
    (is (= {:start 0.5 :end 0.75 :speaker "Darth Vader" :body "I"} (first (:segments js))))
    (is (= :html (:format html)))
    (is (= 10 (count (:segments html))))
    (is (= "Travis" (:speaker (first (:segments html)))))
    (is (= 0 (:start (first (:segments html)))))
    (testing "sniffing"
      (is (= :vtt (transcript/sniff (fixture "transcripts/example.vtt"))))
      (is (= :srt (transcript/sniff (fixture "transcripts/example.srt"))))
      (is (= :json (transcript/sniff (fixture "transcripts/example.json"))))
      (is (= :html (transcript/sniff (fixture "transcripts/example.html"))))
      (is (= :text (transcript/sniff "Just words."))))
    (testing "round trips"
      (is (= (:segments srt) (:segments (transcript/parse (transcript/emit-srt srt)))))
      (is (= (:segments vtt) (:segments (transcript/parse (transcript/emit-vtt vtt)))))
      (is (= (:segments js) (:segments (transcript/parse (transcript/emit-json js))))))
    (testing "text and position"
      (is (re-find #"^Sarah: In today's episode" (transcript/text vtt)))
      (is (= 11.61 (:start (transcript/at vtt 12)))))))

;;;; OPML

(deftest opml-round-trip
  (let [list (opml/parse "<opml version=\"2.0\"><head><title>Mine</title></head><body>
                <outline text=\"Tech\"><outline type=\"rss\" text=\"PC20\" xmlUrl=\"https://feeds.podcastindex.org/pc20.xml\"/></outline>
                <outline type=\"rss\" text=\"ATP\" xmlUrl=\"https://atp.fm/rss\" htmlUrl=\"https://atp.fm\"/>
              </body></opml>")]
    (is (= "Mine" (:title list)))
    (is (= [{:type "rss" :text "PC20" :xml-url "https://feeds.podcastindex.org/pc20.xml" :path ["Tech"]}
            {:type "rss" :text "ATP" :xml-url "https://atp.fm/rss" :html-url "https://atp.fm"}]
           (:subscriptions list)))
    (is (= list (opml/parse (opml/emit list))))
    (is (re-find #"xmlUrl=\"https://atp.fm/rss\"" (opml/emit list)))))

;;;; Value

(def block
  {:type "lightning" :method "keysend" :suggested 1.5E-7
   :recipients [{:name "Host" :type "node" :address "02d5" :split 50}
                {:name "Co-Host" :type "node" :address "032f" :split 40}
                {:name "Producer" :type "node" :address "03ae" :split 10}
                {:name "Index" :type "node" :address "03ae" :split 1 :fee true}]
   :time-splits [{:start-time 60 :duration 237 :remote-percentage 95
                  :remote-item {:feed-guid "a94f" :item-guid "x"}}
                 {:start-time 400 :duration 10
                  :recipients [{:name "Guest" :type "node" :address "0abc" :split 100}]}]})

(deftest value-arithmetic
  (is (= [["Index" 1] ["Host" 51] ["Co-Host" 39] ["Producer" 9]]
         (map (juxt (comp :name :recipient) :amount) (value/payments (:recipients block) 100))))
  (is (= 100 (transduce (map :amount) + (value/payments (:recipients block) 100))))
  (is (= 15 (value/sats block)))
  (is (= 100 (value/share-total (:recipients block))))
  (testing "which block applies"
    (is (= block (value/effective {:podcast/values [block]} {})))
    (is (= {:type "x"} (value/effective {:podcast/values [block]} {:podcast/values [{:type "x"}]})))
    (is (= block (value/effective {:podcast/values [block]} {:podcast/values [{:type "x"}]} "lightning"))))
  (testing "recipients over time"
    (is (= 4 (count (:recipients (value/recipients-at block 10)))))
    (let [{:keys [recipients remote]} (value/recipients-at block 100)]
      (is (= ["Index"] (map :name recipients)))
      (is (= {:remote-item {:feed-guid "a94f" :item-guid "x"} :percentage 95 :start-time 0 :offset 40} remote)))
    (is (= ["Index" "Guest"] (map :name (:recipients (value/recipients-at block 405)))))
    (is (= 4 (count (:recipients (value/recipients-at block 410)))))))

;;;; Podcast Index

(deftest index-requests
  (let [r (index/request {:key "K" :secret "S"} :search/by-term {:q "podcasting 2.0" :max 5 :fulltext true} 1700000000)]
    (is (= :get (:method r)))
    (is (= "https://api.podcastindex.org/api/1.0/search/byterm?q=podcasting+2.0&max=5&fulltext" (:url r)))
    (is (= {"User-Agent" "dk.simongray/podcast" "X-Auth-Key" "K" "X-Auth-Date" "1700000000"
            "Authorization" "2112f595eb505b9d54dc5eab2336b197daf80af4"}
           (:headers r))))
  (is (thrown? clojure.lang.ExceptionInfo (index/request {:key "K" :secret "S"} :nope {})))
  (is (= {"status" "true"} (index/response "{\"status\":\"true\"}"))))
