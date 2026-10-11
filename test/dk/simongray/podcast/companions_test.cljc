(ns dk.simongray.podcast.companions-test
  "Chapters, transcripts, OPML, value blocks and query-string encoding."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.feed.rss :as rss]
            [dk.simongray.podcast.format.chapters :as chapters]
            [dk.simongray.podcast.format.opml :as opml]
            [dk.simongray.podcast.format.transcript :as transcript]
            [dk.simongray.podcast.test-support :refer [fixture]]
            [dk.simongray.podcast.value :as value]
            [dk.simongray.wary-fetch.url :as url]))

(def complex-chapters
  (fixture "chapters/example-complex.json"))

(def vtt-text
  (fixture "transcripts/example.vtt"))

(def srt-text
  (fixture "transcripts/example.srt"))

(def json-text
  (fixture "transcripts/example.json"))

(def html-text
  (fixture "transcripts/example.html"))

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
      (is (= [:chapters/bad-timestamps] (map :id (chapters/report doc))))))
  (testing "a document that is not an object reads as no chapters and is reported"
    (let [doc (chapters/parse-json "[{\"startTime\":0,\"title\":\"Intro\"}]")]
      (is (= [] (:chapters doc)))
      (is (= [:chapters/not-an-object] (map :id (chapters/report doc))))))
  (testing "nor does one that is not JSON, and chapters that are not objects are left out"
    (is (= [:chapters/not-an-object] (map :id (chapters/report (chapters/parse-json "<html><body>404</body></html>")))))
    (let [doc (chapters/parse-json "\uFEFF{\"chapters\": [1, \"x\", {\"startTime\": 5, \"location\": \"Copenhagen\"}]}")]
      (is (= [{:start-time 5 :location "Copenhagen"}] (:chapters doc)))
      (is (= [:chapters/malformed] (map :id (chapters/report doc))))
      (is (string? (chapters/emit-json doc)) "a location that is not an object is written as it is"))
    (is (= [] (:chapters (chapters/parse-json "{\"chapters\": {\"startTime\": 1}}")))))
  (testing "a title, link or place name that is not text is left out, and a number is text"
    (let [doc (chapters/parse-json (str "{\"chapters\": [{\"startTime\": 0, \"title\": {\"a\": 1}, \"url\": [1]},"
                                        " {\"startTime\": 5, \"title\": 1984, \"location\": {\"name\": {}, \"geo\": \"geo:1,2\"}}]}"))]
      (is (= [{:start-time 0} {:start-time 5 :title "1984" :location {:geo "geo:1,2"}}] (:chapters doc)))
      (is (= [{:id :chapters/not-text :count 3}] (map #(select-keys % [:id :count]) (chapters/report doc))))))
  (testing "a document without a version is not reported as an old one"
    (is (= [] (chapters/report (chapters/parse-json "{\"chapters\":[{\"startTime\":0}]}"))))))

(deftest podlove-chapters
  (let [psc {:version "1.2" :chapters [{:start "00:00:00.000" :title "Intro"}
                                       {:start "00:12:30.500" :title "Main" :href "https://x" :image "https://i"}]}
        doc (chapters/from-psc psc)]
    (is (= [{:start-time 0 :title "Intro"} {:start-time 750.5 :title "Main" :url "https://x" :img "https://i"}]
           (:chapters doc)))
    (is (= psc (chapters/to-psc doc)) "the model map of the feed item, which from-psc reads")
    (is (= doc (chapters/from-psc (chapters/to-psc doc))) "and back")
    (is (= {:version "1.2" :chapters [{:start "00:00:05.000" :title "A"}]}
           (chapters/to-psc {:chapters [{:title "No start"} {:start-time 5 :title "A"}]}))
        "a chapter without a start is left out, as Podlove requires one")
    (let [item {:rss/title "E" :psc/chapters (chapters/to-psc doc)}
          back (-> (rss/parse (rss/emit {:rss/title "T" :rss/items [item]})) :rss/items first)]
      (is (= doc (chapters/from-psc (:psc/chapters back))) "through a feed too"))))

(deftest jumps-between-chapters
  (let [doc {:chapters [{:start-time 600 :title "Talk"}
                        {:start-time 0 :title "Intro"}
                        {:start-time 300 :title "Ad" :toc false}
                        {:start-time 90 :title "News"}
                        {:title "No start"}]}]
    (testing "to the next chapter of the table of contents"
      (is (= [90 90 600 600 600 nil nil] (map #(chapters/jump doc % :next) [0 50 90 300 599 600 700]))))
    (testing "to the start of the chapter, or in its first seconds to the chapter before it"
      (is (= [0 0 0 0 90 90 90 90 600] (map #(chapters/jump doc % :previous) [0 2 90 92 93 300 600 602 604])))
      (is (= chapters/restart 3))
      (is (= 90 (chapters/jump doc 604 :previous {:restart 10})) "with another number of seconds")
      (is (= 600 (chapters/jump doc 600 :previous {:restart 0}))))
    (testing "before the first chapter, and with no chapters"
      (is (= 0 (chapters/jump {:chapters [{:start-time 60}]} 30 :previous)))
      (is (= 60 (chapters/jump {:chapters [{:start-time 60}]} 30 :next)))
      (is (= [nil 0] (map #(chapters/jump {} 30 %) [:next :previous]))))))

(deftest chapters-from-show-notes
  (testing "timestamp lines at the start, however they are marked"
    (let [doc (chapters/from-text "<p>Great episode.</p><ul><li>00:00 Intro</li><li>(05:12) - The news</li><li>[1:02:03] Wrap-up</li></ul>")]
      (is (= [{:start-time 0 :title "Intro"} {:start-time 312 :title "The news"} {:start-time 3723 :title "Wrap-up"}]
             (:chapters doc)))
      (is (= [{:id :chapters/inferred :level :info :count 3}]
             (map #(dissoc % :message) (chapters/report doc))))
      (is (= "The news" (:title (chapters/at doc 400))))))
  (testing "plain text with the time last, three lines rising"
    (is (= ["Intro" "The news" "Wrap-up"]
           (map :title (:chapters (chapters/from-text "Intro - 0:00\nThe news (5:12)\nWrap-up 1:02:03\n"))))))
  (testing "the lines of the text, for a caller that parsed the notes already"
    (is (= (chapters/from-text "<ul><li>00:00 Intro</li><li>05:12 The news</li></ul>")
           (chapters/from-lines ["Intro" "" "- 00:00 Intro" "- 05:12 The news"])))
    (is (= [{:id :chapters/inferred :level :info :count 2}]
           (map #(dissoc % :message) (chapters/report (chapters/from-lines ["00:00 Intro" "05:12 The news"])))))
    (is (nil? (chapters/from-lines ["Recorded at ten." "No times here."]))))
  (testing "one timestamp is not a list, nor is a mention of the clock"
    (is (nil? (chapters/from-text "<p>Recorded at 10:30. Runtime 45:00.</p>")))
    (is (nil? (chapters/from-text "00:00 Intro")))
    (is (nil? (chapters/from-text "Doors 19:00\nShow 20:00")))
    (is (nil? (chapters/from-text nil))))
  (testing "a colon written as a character reference counts"
    (is (= [0 312] (map :start-time (:chapters (chapters/from-text "<p>00&#58;00 Intro</p><p>05&#x3A;12 The news</p>"))))))
  (testing "runs of blanks are one space, and a long one is read in linear time"
    (is (= ["Intro" "The middle bit"]
           (map :title (:chapters (chapters/from-text "<pre>00:00  Intro\n12:34   The   middle   bit</pre>")))))
    (is (nil? (chapters/from-text (str "<pre>12:34 a" (apply str (repeat 100000 " ")) "b</pre>"))))))

(deftest transcripts-that-bend-html
  (testing "paragraphs without their optional end tags"
    (is (= [{:body "One" :start-time 0 :speaker "A"} {:body "Two" :start-time 0 :speaker "A"}]
           (:segments (transcript/parse-html "<cite>A:</cite><time>0:00</time><p>One<p>Two"))))
    (is (= 20000 (count (:segments (transcript/parse-html (apply str (repeat 20000 "<p>word "))))))
        "read in linear time"))
  (testing "a cue full of stray angle brackets is read in linear time"
    (let [text (str (apply str (repeat 50000 "a <v b ")) "c")]
      (is (= [text] (map :body (:segments (transcript/parse-vtt (str "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\n" text)))))))))

(deftest transcript-formats
  (let [vtt  (transcript/parse vtt-text)
        srt  (transcript/parse srt-text "application/x-subrip")
        js   (transcript/parse json-text)
        html (transcript/parse html-text)]
    (is (= :vtt (:format vtt)))
    (is (= {:start-time 0 :end-time 2.76 :speaker "Sarah" :body "In today's episode, you'll learn whether or not you"}
           (first (:segments vtt))))
    (is (= :srt (:format srt)))
    (is (= 222 (count (:segments srt))))
    (is (= {:start-time 0.179 :end-time 2.399 :speaker "Travis" :body "When you first get\nstarted in podcasting, it's"}
           (first (:segments srt))))
    (is (= :json (:format js)))
    (is (= {:start-time 0.5 :end-time 0.75 :speaker "Darth Vader" :body "I"} (first (:segments js))))
    (is (= :html (:format html)))
    (is (= 10 (count (:segments html))))
    (is (= "Travis" (:speaker (first (:segments html)))))
    (is (= 0 (:start-time (first (:segments html)))))
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
      (is (= 11.61 (:start-time (transcript/at vtt 12))))
      (is (= "b" (:body (transcript/at {:segments [{:body "a"} {:start-time 1 :body "b"}]} 3)))
          "a segment without a start does not end the search"))))

(defn- report-ids
  [x]
  (mapv :id (transcript/report x)))

(deftest transcripts-in-the-wild
  (testing "JSON with times written as strings"
    (let [t (transcript/parse "{\"version\":\"1.0.0\",\"segments\":[{\"speaker\":\"Justin\",\"startTime\":\"0.0\",\"endTime\":\"8.559999\",\"body\":\"Hi.\"},{\"speaker\":\"\",\"startTime\":\"9\",\"body\":\"Bye.\"}]}")]
      (is (= [{:start-time 0 :end-time 8.559999 :speaker "Justin" :body "Hi."} {:start-time 9 :body "Bye."}]
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
  (testing "voice spans of WebVTT"
    (let [cue    (fn [i text] (str "00:00:0" i ".000 --> 00:00:0" (inc i) ".000\n" text))
          parsed (fn [& texts]
                   (transcript/parse (str "WEBVTT\n\n" (str/join "\n\n" (map-indexed cue texts)))))
          turns  (parsed "<v Justin>Hi." "What is going on?" "<v Michael>Geese." "<v Justin>Right." "Anyway.")
          each   (parsed "<v Ann>Hi." "<v Ann>Welcome." "La la la, the closing song." "<v Bo>Bye.")]
      (is (= ["Justin" "Justin" "Michael" "Justin" "Justin"] (map :speaker (:segments turns)))
          "carry over in a file that marks only where the speaker changes")
      (is (= [[:transcript/voices-carried 2]] (map (juxt :id :count) (transcript/report turns))))
      (is (= ["Ann" "Ann" nil "Bo"] (map :speaker (:segments each)))
          "but name only their own cue in a file that marks every cue, as WebVTT has it")
      (is (= [] (transcript/report each)))
      (is (= ["Ann" "Ann" nil "Bo"]
             (map :speaker (:segments (transcript/parse (transcript/emit-vtt each)))))
          "so a cue without a speaker comes back without one")))
  (testing "odd JSON"
    (is (= [{:body "hi" :start-time 0 :speaker "1"}]
           (:segments (transcript/parse "{\"segments\": [{\"speaker\": 1, \"startTime\": 0, \"body\": \"hi\"}]}" "application/json")))
        "a speaker written as a number")
    (let [t (transcript/parse "not json" "application/json")]
      (is (= [] (:segments t)))
      (is (= [:transcript/not-an-object] (report-ids t))))
    (let [t (transcript/parse "{\"segments\": [1, {\"startTime\": 0, \"body\": \"a\"}]}" "application/json")]
      (is (= [{:body "a" :start-time 0}] (:segments t)))
      (is (= [:transcript/malformed] (report-ids t))))
    (let [t (transcript/parse "{\"segments\": [{\"speaker\": {\"name\": \"A\"}, \"startTime\": 0, \"body\": \"a\"}]}" "application/json")]
      (is (= [{:body "a" :start-time 0}] (:segments t)) "a speaker that is not text is left out")
      (is (= [:transcript/not-text] (report-ids t)))))
  (testing "cue timings come before a tag, and SRT without fractions of a second"
    (is (= :srt (:format (transcript/parse "1\n00:00:01,000 --> 00:00:03,000\n<p>Hello</p>\n" "application/x-subrip"))))
    (is (= ["I said <p> once." "Then more."] (map :body (:segments (transcript/parse "I said <p> once.\n\nThen more." "text/plain")))))
    (is (= [{:start-time 1 :end-time 3 :body "Hello"}] (:segments (transcript/parse "1\n00:00:01 --> 00:00:03\nHello\n")))))
  (testing "a blank line in a body is no end of the cue it is written as"
    (doseq [emit [transcript/emit-srt transcript/emit-vtt]]
      (is (= ["one\ntwo"] (map :body (:segments (transcript/parse (emit {:segments [{:start-time 1 :end-time 2 :body "one\n\ntwo"}]}))))))))
  (testing "an ampersand and the signs of a tag in a cue of WebVTT go as character references"
    (let [segment {:start-time 1 :end-time 2 :speaker "R&D <team>" :body "a < b & c > d"}
          written (transcript/emit-vtt {:segments [segment]})]
      (is (str/includes? written "<v R&amp;D &lt;team&gt;>a &lt; b &amp; c &gt; d"))
      (is (= [segment] (:segments (transcript/parse written))))))
  (testing "text that only looks like JSON is read as text, and a byte order mark is no text"
    (doseq [type [nil "text/plain"]]
      (let [t (transcript/parse "{Intro music}\n\nHost: Welcome." type)]
        (is (= :text (:format t)) (pr-str type))
        (is (= ["{Intro music}" "Welcome."] (map :body (:segments t))) (pr-str type))))
    (is (= :vtt (:format (transcript/parse "\uFEFFWEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi")))))
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
      (is (= [{:body "All right, Kevin, how many?" :start-time 0 :speaker "Jordan"}
              {:body "We're good.\n\nSecond paragraph." :start-time 0 :speaker "Kevin"}]
             (:segments t)))))
  (testing "an SRT wrapped in paragraphs is read as the SRT it is"
    (let [t (transcript/parse "<cite>Speaker:</cite><time>00:00:03</time><p>What does the future</p><p> hold?</p><p> </p><p> 2</p><p> 00:00:06,578 --&gt; 00:00:11,982</p><p> And in particular,</p><p> what about creators?</p><p> </p><p> 3</p><p> 00:00:11,982 --&gt; 00:00:15,124</p><p> Something else.</p>" "text/html")]
      (is (= :html (:format t)))
      (is (= [:transcript/wrapped-srt] (report-ids t)))
      (is (= [{:start-time 6.578 :end-time 11.982 :body "And in particular,\nwhat about creators?"}
              {:start-time 11.982 :end-time 15.124 :body "Something else."}]
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
                               :segments [{:start-time 0.08 :end-time 0.16 :speaker "Jordan" :body "All"}
                                          {:start-time 0.16 :end-time 0.4 :speaker "Jordan" :body "right,"}
                                          {:start-time 0.5 :end-time 0.9 :speaker "Kevin" :body "Yes."}
                                          {:start-time 1 :end-time 1.5 :body "(music)"}
                                          {:start-time 2 :end-time 2.5 :body "(music)"}]})]
      (is (= [{:start-time 0.08 :end-time 0.4 :speaker "Jordan" :body "All right,"}
              {:start-time 0.5 :end-time 0.9 :speaker "Kevin" :body "Yes."}
              {:start-time 1 :end-time 1.5 :body "(music)"}
              {:start-time 2 :end-time 2.5 :body "(music)"}]
             (:segments t)))
      (is (= [{:start-time 0 :end-time 1 :speaker "A" :body "a b"}]
             (:segments (transcript/turns {:segments [{:start-time 0 :end-time 1 :speaker "A" :body "a"}
                                                      {:start-time 1 :speaker "A" :body "b"}]})))
          "a turn ends where the last segment with an end does"))))

(deftest matches-in-a-transcript
  (let [vtt (transcript/parse (fixture "transcripts/example.vtt"))]
    (is (= [[2.76 "Sarah"] [21.45 "Gillian"]]
           (mapv (juxt :start-time :speaker) (transcript/matches vtt "trailer")))
        "at the start of the cue where the first match is")
    (is (= [{:text "Hi Buzzsprout, Gillian here from breaking through careers podcast. My question is, do we need a podcast "}
            {:text "trailer" :match? true}
            {:text "?"}]
           (:parts (second (transcript/matches vtt "trailer")))))
    (is (= [] (transcript/matches vtt "trailer -podcast")))
    (is (= 1 (count (transcript/matches vtt "\"podcast trailer\" gillian")))))
  (testing "the words of a word-level transcript make up a passage"
    (let [json (transcript/parse (fixture "transcripts/example.json"))]
      (is (= [[1.5 "I am your father."]]
             (mapv (juxt :start-time :body) (transcript/matches json "\"your father\""))))))
  (is (= [] (transcript/matches {:segments []} "x"))))

(deftest emitting-untimed-segments
  (let [untimed (transcript/parse "Justin: Hi.\n\nBye." "text/plain")
        mixed   (update untimed :segments conj {:start-time 1 :end-time 2 :body "Timed."})]
    (testing "segments without a start time are left out of the timed formats"
      (is (= [] (:segments (transcript/parse (transcript/emit-srt untimed)))))
      (is (= [] (:segments (transcript/parse (transcript/emit-vtt untimed)))))
      (is (= [{:start-time 1 :end-time 2 :body "Timed."}]
             (:segments (transcript/parse (transcript/emit-srt mixed)))))
      (is (= [{:start-time 1 :end-time 2 :body "Timed."}]
             (:segments (transcript/parse (transcript/emit-json mixed))))))))

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
    (is (re-find #"xmlUrl=\"https://atp.fm/rss\"" (opml/emit list))))
  (testing "xmlUrl in any case, and a blank one, which is no subscription"
    (is (= [{:text "A" :xml-url "https://a/rss"} {:text "C" :xml-url "https://c/rss" :html-url "https://c"}]
           (:subscriptions (opml/parse "<opml version=\"2.0\"><body><outline text=\"A\" xmlurl=\"https://a/rss\"/>
                                        <outline text=\"B\" xmlUrl=\"\"/>
                                        <outline text=\"C\" XMLURL=\"https://c/rss\" HTMLURL=\"https://c\"/></body></opml>")))))
  (testing "categories, the time an outline was created, and the dates and owner of the list"
    (let [list (opml/parse "<opml version=\"2.0\"><head><title>Mine</title>
                              <dateCreated>Mon, 05 Jan 2026 10:00:00 GMT</dateCreated>
                              <dateModified>someday</dateModified><ownerEmail>a@b.c</ownerEmail></head><body>
                              <outline type=\"rss\" text=\"A\" xmlUrl=\"https://a/rss\" category=\"Daily, /News/Denmark,\"
                                       created=\"Mon, 05 Jan 2026 10:00:00 GMT\"/>
                              <outline type=\"rss\" text=\"B\" xmlUrl=\"https://b/rss\" category=\" \" created=\"yesterday\"/>
                            </body></opml>")]
      (is (= {:title "Mine" :date-created #inst "2026-01-05T10:00:00Z" :date-modified "someday" :owner-email "a@b.c"}
             (dissoc list :subscriptions))
          "a date that does not read stays as written")
      (is (= [{:type "rss" :text "A" :xml-url "https://a/rss" :category ["Daily" "/News/Denmark"]
               :created #inst "2026-01-05T10:00:00Z"}
              {:type "rss" :text "B" :xml-url "https://b/rss" :created "yesterday"}]
             (:subscriptions list)))
      (is (= list (opml/parse (opml/emit list))))
      (is (re-find #"category=\"Daily,/News/Denmark\"" (opml/emit list)))
      (is (re-find #"category=\"Science_ Tech\"" (opml/emit {:subscriptions [{:text "C" :xml-url "https://c/rss"
                                                                                :category ["Science, Tech"]}]}))
          "a comma inside a category, which OPML cannot escape")))
  (testing "inclusions of other lists, in their folders, by the type include or a link to .opml"
    (let [list (opml/parse "<opml version=\"2.0\"><head><title>Lists</title></head><body>
                              <outline text=\"News\">
                                <outline type=\"rss\" text=\"A\" xmlUrl=\"https://a/rss\"/>
                                <outline type=\"include\" text=\"Danish news\" url=\"https://lists.example/dk.opml\"/>
                              </outline>
                              <outline type=\"rss\" text=\"B\" xmlUrl=\"https://b/rss\"/>
                              <outline type=\"link\" text=\"Blogroll\" url=\"https://x.example/roll.OPML\"/>
                              <outline type=\"link\" text=\"A page\" url=\"https://x.example/about\"/>
                            </body></opml>")]
      (is (= [{:url "https://lists.example/dk.opml" :text "Danish news" :path ["News"]}
              {:url "https://x.example/roll.OPML" :text "Blogroll"}]
             (:includes list))
          "a link to a page is neither")
      (is (= ["A" "B"] (map :text (:subscriptions list))))
      (is (= list (opml/parse (opml/emit list))))
      (is (= 1 (count (re-seq #"text=\"News\"" (opml/emit list)))) "a folder once, its inclusion after its subscriptions")))
  (testing "a list from somewhere else, read as data"
    (is (= {:list {:subscriptions []}} (opml/try-parse "<opml version=\"2.0\"><body/></opml>")))
    (is (= [:fetch/malformed "https://x.example/l.opml"]
           ((juxt :id :url) (:error (opml/try-parse "<html><body/></html>" {:url "https://x.example/l.opml"}))))
        "a document whose root isn't opml")
    (is (= :fetch/malformed (:id (:error (opml/try-parse "<opml")))))))

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
  (testing "a share recipient without a split is paid nothing"
    (is (= [["A" 3] ["B" 7] ["C" 0]]
           (map (juxt (comp :name :recipient) :amount)
                (value/payments [{:name "A" :split 1} {:name "B" :split 2} {:name "C"}] 10)))))
  (testing "a split that did not read counts as none, and no amount is negative"
    (is (= [["A" 0] ["B" 10]]
           (map (juxt (comp :name :recipient) :amount)
                (value/payments [{:name "A" :split "12.5"} {:name "B" :split 50}] 10))))
    (is (= [600 400 0] (map :amount (value/payments [{:split 60 :fee true} {:split 60 :fee true} {:split 50}] 1000)))
        "the fees stop where the amount runs out")
    (is (= [1000 0 0] (map :amount (value/payments [{:split 200 :fee true} {:split -10} {:split 10}] 1000))))
    (is (= 100 (get-in (value/recipients-at {:time-splits [{:start-time 0 :duration 10 :remote-percentage "half"
                                                            :remote-item {:feed-guid "a"}}]}
                                           5)
                       [:remote :percentage]))))
  (is (= 15 (value/sats block)))
  (is (= 100 (value/share-total (:recipients block))))
  (testing "which block applies"
    (is (= block (resolve/value {:podcast/values [block]} {})))
    (is (= {:type "x"} (resolve/value {:podcast/values [block]} {:podcast/values [{:type "x"}]})))
    (is (= block (resolve/value {:podcast/values [block]} {:podcast/values [{:type "x"}]} "lightning"))))
  (testing "recipients over time"
    (is (= 4 (count (:recipients (value/recipients-at block 10)))))
    (let [{:keys [recipients remote]} (value/recipients-at block 100)]
      (is (= ["Index"] (map :name recipients)))
      (is (= {:remote-item {:feed-guid "a94f" :item-guid "x"} :percentage 95 :start-time 0 :offset 40} remote)))
    (is (= ["Index" "Guest"] (map :name (:recipients (value/recipients-at block 405)))))
    (is (= 4 (count (:recipients (value/recipients-at block 410))))))
  (testing "folding in a resolved remote block"
    (let [at     (value/recipients-at block 100)
          remote {:type "lightning" :method "keysend"
                  :recipients [{:name "A" :type "node" :address "0a" :split 60}
                               {:name "B" :type "node" :address "0b" :split 40}
                               {:name "C" :type "node" :address "0c" :split 2 :fee true}]}
          folded (value/with-remote block at remote)
          paid   (into {} (map (juxt (comp :name :recipient) :amount)) (value/payments (:recipients folded) 1000))]
      (is (= [["Index" 1 true] ["C" 2 true] ["Host" 25000 nil] ["Co-Host" 20000 nil] ["Producer" 5000 nil]
              ["A" 570000 nil] ["B" 380000 nil]]
             (map (juxt :name :split :fee) (:recipients folded))))
      (is (= {"Index" 10 "C" 20 "Host" 24 "Co-Host" 19 "Producer" 4 "A" 555 "B" 368} paid))
      (is (= 1000 (reduce + (vals paid))))
      (is (= (:remote at) (:remote folded)) "the remote item stays named, for the payment")
      (testing "by the remote values that recipients-at takes"
        (let [item (:remote-item (:remote at))]
          (is (= folded (value/recipients-at block 100 {item remote})))
          (is (= (:recipients block) (:recipients (value/recipients-at block 100 {item nil}))) "a remote item without a block")
          (is (= at (value/recipients-at block 100 {})) "a remote item that wasn't fetched")))
      (testing "everything to the remote side at 100 percent"
        (let [everything (value/with-remote block (assoc-in at [:remote :percentage] 100) remote)]
          (is (= ["Index" "C" "A" "B"] (map :name (:recipients everything))))))
      (testing "without a resolved block, the feed's own recipients apply"
        (is (= (:recipients block) (:recipients (value/with-remote block at nil)))))
      (testing "outside a remote split nothing changes"
        (let [plain (value/recipients-at block 10)]
          (is (= plain (value/with-remote block plain remote))))))))

(deftest boostagrams
  (is (= {"podcast" "Podcasting 2.0" "guid" "917393e3" "url" "https://f/rss" "episode" "Episode 84"
          "episode_guid" "PC2084" "ts" 754 "time" "00:12:34" "action" "boost"
          "value_msat" 1000000 "value_msat_total" 5000000 "name" "Host" "app_name" "Inspector"
          "message" "Great show" "uuid" "abc"}
         (value/boostagram {:podcast "Podcasting 2.0" :feed-guid "917393e3" :feed-url "https://f/rss"
                            :episode "Episode 84" :item-guid "PC2084" :position 754.4 :action :boost
                            :sats 1000 :total-sats 5000 :name "Host" :app-name "Inspector"
                            :message "Great show" :uuid "abc" :sender-name nil})))
  (is (= {"action" "stream" "value_msat" 10000 "value_msat_total" 10000}
         (value/boostagram {:action :stream :sats 10})))
  (is (= {"action" "boost"} (value/boostagram {})))
  (is (= "100:00:00" (get (value/boostagram {:position 360000.5}) "time")) "a hundred hours"))

(deftest query-string-encoding
  (testing "the same on both platforms: %20 for a space, the marks kept"
    (is (= "a*b!c'd(e)f~g%20h" (url/percent-encode "a*b!c'd(e)f~g h")))
    (is (= "q=a%20b&flag" (url/query-string {:q "a b" :flag true :none nil}))))
  (testing "an escape that does not decode is left as it is, on both platforms"
    (is (= "a b" (url/percent-decode "a%20b")))
    (is (= "100%.edn" (url/percent-decode "100%.edn")))))

