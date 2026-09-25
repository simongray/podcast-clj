(ns dk.simongray.podcast.companions-test
  "Chapters, transcripts, OPML, value blocks and the Podcast Index client."
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.chapters :as chapters]
            [dk.simongray.podcast.index :as index]
            [dk.simongray.podcast.opml :as opml]
            [dk.simongray.podcast.transcript :as transcript]
            [dk.simongray.podcast.value :as value]))

(def complex-chapters
  #?(:clj  (slurp (io/resource "chapters/example-complex.json"))
     :cljs (rc/inline "chapters/example-complex.json")))

(def vtt-text
  #?(:clj  (slurp (io/resource "transcripts/example.vtt"))
     :cljs (rc/inline "transcripts/example.vtt")))

(def srt-text
  #?(:clj  (slurp (io/resource "transcripts/example.srt"))
     :cljs (rc/inline "transcripts/example.srt")))

(def json-text
  #?(:clj  (slurp (io/resource "transcripts/example.json"))
     :cljs (rc/inline "transcripts/example.json")))

(def html-text
  #?(:clj  (slurp (io/resource "transcripts/example.html"))
     :cljs (rc/inline "transcripts/example.html")))

;;;; Chapters

(deftest json-chapters
  (let [doc (chapters/parse-json complex-chapters)]
    (is (= "1.2.0" (:version doc)))
    (is (= 11 (count (:chapters doc))))
    (is (= {:start-time 0 :title "Intro"} (first (:chapters doc))))
    (is (= {:name "Eiffel Tower, Paris" :geo "geo:42.3417649,-70.9661596"}
           (:location (nth (:chapters doc) 7))))
    (is (= 10 (count (chapters/visible doc))))
    (is (= "Progress Report" (:title (chapters/at doc 300))))
    (is (nil? (chapters/at (chapters/parse-json "{\"version\":\"1.2.0\",\"chapters\":[{\"startTime\":10}]}") 5)))
    (is (= 168 (:end-time (first (chapters/with-end-times doc)))))
    (testing "the specification's own example carries a key the format does not define"
      (is (= [{:id :chapters/unknown-keys :level :info :keys ["value"]}]
             (map #(dissoc % :message) (chapters/report doc)))))
    (testing "round trip"
      (is (= doc (chapters/parse-json (chapters/emit-json doc)))))))

(deftest chapters-in-the-wild
  (let [doc (chapters/parse-json "{\"version\":\"1.1.0\",\"chapters\":[
                                    {\"startTime\":\"120\",\"title\":\"Later\",\"number\":2},
                                    {\"startTime\":0,\"title\":\"Intro\",\"number\":1},
                                    {\"title\":\"Lost\"}]}")]
    (testing "string times become numbers, everything is kept, and the report says what was odd"
      (is (= [{:start-time 120 :title "Later" "number" 2}
              {:start-time 0 :title "Intro" "number" 1}
              {:title "Lost"}]
             (:chapters doc)))
      (is (= [{:id :chapters/string-times :level :warning :count 1}
              {:id :chapters/missing-start-time :level :warning :count 1}
              {:id :chapters/unsorted :level :info}
              {:id :chapters/unknown-keys :level :info :keys ["number"]}
              {:id :chapters/old-version :level :info :version "1.1.0"}]
             (map #(dissoc % :message) (chapters/report doc)))))
    (testing "the helpers ignore what cannot be placed"
      (is (= ["Intro" "Later"] (map :title (chapters/sorted doc))))
      (is (= "Later" (:title (chapters/at doc 130))))))
  (testing "Podlove chapters with a bad start are dropped and reported"
    (let [doc (chapters/from-psc {:chapters [{:start "00:00:00.000" :title "A"} {:start "soon" :title "B"}]})]
      (is (= ["A"] (map :title (:chapters doc))))
      (is (= [:chapters/bad-timestamps] (map :id (chapters/report doc)))))))

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
  (let [vtt  (transcript/parse vtt-text)
        srt  (transcript/parse srt-text "application/x-subrip")
        js   (transcript/parse json-text)
        html (transcript/parse html-text)]
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
      (is (= :vtt (transcript/sniff vtt-text)))
      (is (= :srt (transcript/sniff srt-text)))
      (is (= :json (transcript/sniff json-text)))
      (is (= :html (transcript/sniff html-text)))
      (is (= :text (transcript/sniff "Just words."))))
    (testing "round trips"
      (is (= (:segments srt) (:segments (transcript/parse (transcript/emit-srt srt)))))
      (is (= (:segments vtt) (:segments (transcript/parse (transcript/emit-vtt vtt)))))
      (is (= (:segments js) (:segments (transcript/parse (transcript/emit-json js))))))
    (testing "text and position"
      (is (re-find #"^Sarah: In today's episode" (transcript/text vtt)))
      (is (= 11.61 (:start (transcript/at vtt 12)))))))

(defn- report-ids
  [x]
  (mapv :id (transcript/report x)))

(deftest transcripts-in-the-wild
  (testing "JSON with times written as strings"
    (let [t (transcript/parse "{\"version\":\"1.0.0\",\"segments\":[{\"speaker\":\"Justin\",\"startTime\":\"0.0\",\"endTime\":\"8.559999\",\"body\":\"Hi.\"},{\"speaker\":\"\",\"startTime\":\"9\",\"body\":\"Bye.\"}]}")]
      (is (= [{:start 0 :end 8.559999 :speaker "Justin" :body "Hi."} {:start 9 :body "Bye."}]
             (:segments t)))
      (is (= [{:id :transcript/string-times :level :warning :count 2}]
             (map #(dissoc % :message) (transcript/report t))))))
  (testing "caption files mark speakers with a prefix only when they change"
    (let [vtt (transcript/parse "WEBVTT\n\nNOTE\nMade by a tool\n\n00:00:00.550 --> 00:00:01.540\nJACK: I just came back,\n\n00:00:01.540 --> 00:00:03.300\nand it was wonderful.\n\n00:00:03.300 --> 00:00:04.630\nANNA: Was it?\n")
          srt (transcript/parse "1\n00:00:20,534 --> 00:00:27,738\n[Mark]: Design is everywhere,\nbut do we get it wrong?\n\n2\n00:00:28,499 --> 00:00:34,803\nand what is the impact?\n" :srt)]
      (is (= ["JACK" "JACK" "ANNA"] (map :speaker (:segments vtt))))
      (is (= "I just came back," (:body (first (:segments vtt)))))
      (is (= [:transcript/speaker-prefixes] (report-ids vtt)))
      (is (= 2 (:count (first (transcript/report vtt)))))
      (is (= ["Mark" "Mark"] (map :speaker (:segments srt))))
      (is (= "Design is everywhere,\nbut do we get it wrong?" (:body (first (:segments srt)))))))
  (testing "a clean file has an empty report"
    (is (= [] (transcript/report (transcript/parse vtt-text "text/vtt")))))
  (testing "a declared type the file contradicts is reported, and the file read as what it is"
    (let [t (transcript/parse vtt-text "text/plain")]
      (is (= :vtt (:format t)))
      (is (= 7 (count (:segments t))))
      (is (= [{:id :transcript/type-mismatch :level :warning :declared :text :used :vtt}]
             (map #(dissoc % :message) (transcript/report t)))))
    (let [t (transcript/parse srt-text "text/x-nothing")]
      (is (= :srt (:format t)))
      (is (= [{:id :transcript/unknown-type :level :warning :declared "text/x-nothing"}]
             (->> (transcript/report t)
                  (filter (comp #{:transcript/unknown-type} :id))
                  (map #(dissoc % :message)))))))
  (testing "HTML with layout whitespace, citations without colons, headings and a document around it"
    (let [t (transcript/parse "<!DOCTYPE html><html><head><title>Transcript</title></head><body>
                                 <h2 id=\"x\"><a href=\"#x\">Chapter one</a></h2>
                                 <cite>Jordan</cite>\n<time>0:00</time>\n<p>\n   All   right,   Kevin,\n   how   many?\n</p>
                                 <cite>Kevin:</cite><p>We&#39;re good.\n\nSecond paragraph.</p><p> </p></body></html>")]
      (is (= [{:body "All right, Kevin, how many?" :start 0 :speaker "Jordan"}
              {:body "We're good.\n\nSecond paragraph." :start 0 :speaker "Kevin"}]
             (:segments t)))))
  (testing "an SRT wrapped in paragraphs is read as the SRT it is"
    (let [t (transcript/parse "<cite>Speaker:</cite><time>00:00:03</time><p>What does the future</p><p> hold?</p><p> </p><p> 2</p><p> 00:00:06,578 --&gt; 00:00:11,982</p><p> And in particular,</p><p> what about creators?</p><p> </p><p> 3</p><p> 00:00:11,982 --&gt; 00:00:15,124</p><p> Something else.</p>" "text/html")]
      (is (= :html (:format t)))
      (is (= [:transcript/wrapped-srt] (report-ids t)))
      (is (= [{:start 6.578 :end 11.982 :body "And in particular,\nwhat about creators?"}
              {:start 11.982 :end 15.124 :body "Something else."}]
             (:segments t)))))
  (testing "plain text as speaker-prefixed paragraphs"
    (let [t (transcript/parse "Justin: I can't do a podcast with you.\n\nWhat is going on here?\n\nMichael: Oh gosh. It's my door.\n" "text/plain")]
      (is (= :text (:format t)))
      (is (= [{:speaker "Justin" :body "I can't do a podcast with you."}
              {:speaker "Justin" :body "What is going on here?"}
              {:speaker "Michael" :body "Oh gosh. It's my door."}]
             (:segments t)))))
  (testing "word-level segments merge into turns"
    (let [t (transcript/turns {:format :json
                               :segments [{:start 0.08 :end 0.16 :speaker "Jordan" :body "All"}
                                          {:start 0.16 :end 0.4 :speaker "Jordan" :body "right,"}
                                          {:start 0.5 :end 0.9 :speaker "Kevin" :body "Yes."}
                                          {:start 1 :end 1.5 :body "(music)"}
                                          {:start 2 :end 2.5 :body "(music)"}]})]
      (is (= [{:start 0.08 :end 0.4 :speaker "Jordan" :body "All right,"}
              {:start 0.5 :end 0.9 :speaker "Kevin" :body "Yes."}
              {:start 1 :end 1.5 :body "(music)"}
              {:start 2 :end 2.5 :body "(music)"}]
             (:segments t))))))

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
    (is (= "https://api.podcastindex.org/api/1.0/search/byterm?q=podcasting%202.0&max=5&fulltext" (:url r)))
    (is (= {"User-Agent" "dk.simongray/podcast" "X-Auth-Key" "K" "X-Auth-Date" "1700000000"
            "Authorization" "2112f595eb505b9d54dc5eab2336b197daf80af4"}
           (:headers r))))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs ExceptionInfo)
               (index/request {:key "K" :secret "S"} :nope {})))
  (is (= {"status" "true"} (index/response "{\"status\":\"true\"}"))))
