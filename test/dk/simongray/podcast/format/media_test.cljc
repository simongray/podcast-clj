(ns dk.simongray.podcast.format.media-test
  "What a media file says about itself, read whole or by ranges: the format
  by the first bytes, after the ID3 tags too, and the duration, the
  chapters as a chapters document, a file in memory,
  on disk, in a Blob or over HTTP, parts that don't read, sizes that lie,
  and a file that doesn't come. On the JVM also random bytes and mangled
  files, which the reader must answer with a report, never an exception."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.format.media :as media]
            [dk.simongray.podcast.test-support :refer [ascii be fetch-from run view
                                                       #?@(:clj [local? with-file-server])]]
            [dk.simongray.podcast.test-support.media
             :refer [about= adts-frame audio-frame box chap chapter-file chapter-track-file ctoc enhanced-file jpeg mvhd
                     opus-file syncsafe tag text xing-frame #?(:clj fragmented-file)]]
            [dk.simongray.podcast.util.bytes.ranges :as ranges]
            [dk.simongray.podcast.util.bytes.view :as view]
            #?(:clj [dk.simongray.wary-fetch :as http])
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webcrypto.bytes :as bytes]))

(deftest reading-media
  (is (= :mp3 (:format (media/read (view (concat (tag 3 (text 3 "TIT2" "T")) (xing-frame 10) (audio-frame)))))))
  (testing "the size of the file and where its audio starts, the header of the IAB's threshold"
    (let [tagged (tag 3 (text 3 "TIT2" "T"))
          file   (concat tagged (xing-frame 10) (audio-frame))]
      (is (= {:bytes (count file) :media-start (count tagged)}
             (select-keys (media/read (view file)) [:bytes :media-start])))
      (is (not (contains? (media/read (view/add (view/view) 0 (bytes/of file) nil)) :bytes))
          "unless the size isn't known")))
  (is (= :mp4 (:format (media/read (view (chapter-file ["A"] [1000] false))))))
  (is (= [:file/unknown-format] (map :id (media/report (media/read (view (ascii "<html><body>not audio</body></html>")))))))
  (testing "a view that lacks bytes gives the ranges it needs"
    (let [b    (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
          head (view/add (view/view (bytes/length b)) 0 (bytes/slice b 0 40) (bytes/length b))]
      (is (= [[45 61]] (:need (media/read head))) "the header of the box after the media data, which ends at 45")))
  (testing "the chapters of an MP3 file as a chapters document"
    (let [r (media/read (view (concat (tag 3 (ctoc 3 "t" ["a"]) (chap 3 "a" 0 5000 (text 3 "TIT2" "A")))
                                      (xing-frame 10) (audio-frame))))]
      (is (= {:version "1.2.0" :chapters [{:start-time 0.0 :end-time 5.0 :title "A"}]} (:chapters r)))))
  (testing "the chapters of an M4A file, with an image as a data URL and a link"
    (let [file (enhanced-file [{:title "A" :ms 5000 :url "https://example.org/a"}] [jpeg])]
      (is (= [{:start-time 0.0 :end-time 5.0 :title "A" :url "https://example.org/a"
               :img (str "data:image/jpeg;base64," (bytes/base64 (bytes/of jpeg)))}]
             (:chapters (:chapters (media/read (view file))))))
      (is (= [{:start-time 0.0 :end-time 5.0 :title "A" :url "https://example.org/a"}]
             (:chapters (:chapters (media/read (view file) {:chapter-images? false}))))
          "and without it")))
  (is (every? :message (media/report (media/read (view (ascii "not audio at all"))))) "every diagnostic with its message"))

(deftest the-format-by-the-first-bytes
  (let [tagged (tag 3 (text 3 "TIT2" "T"))
        adts   (apply concat (repeat 3 (adts-frame)))]
    (is (= :ogg (:format (media/read (view (opus-file))))))
    (is (= :aac (:format (media/read (view adts)))))
    (is (= :aac (:format (media/read (view (concat tagged adts))))) "after the ID3 tags that often come first")
    (is (= :mp3 (:format (media/read (view (concat tagged tagged (xing-frame 10) (audio-frame)))))) "after two tags")
    (is (= :mp3 (:format (media/read (view (concat (ascii "ID3") [9 0 0 0 0 0 0] (audio-frame))))))
        "after a tag whose header doesn't read"))
  (is (= {:codecs "opus" :chapters {:version "1.2.0" :chapters [{:start-time 0 :title "Intro"}]}}
         (select-keys (media/read (view (opus-file :comments ["CHAPTER001=00:00:00.000" "CHAPTER001NAME=Intro"])))
                      [:codecs :chapters :comments]))
      "the chapters of an Ogg file as a chapters document, without the comments")
  (is (= {"audio/ogg" :ogg "audio/opus" :ogg "audio/aac" :aac}
         (select-keys media/formats ["audio/ogg" "audio/opus" "audio/aac"]))))

(deftest the-bitrate-and-the-tags
  (let [r (media/read (view (concat (tag 3 (text 3 "TIT2" "Episode 1")) (apply concat (repeat 20 (audio-frame))))))]
    (is (< 127000 (:bitrate r) 129000) "the media over the duration, when the reader gives none")
    (is (= {:title "Episode 1"} (:tags r)))
    (is (not (contains? r :id3)) "without the ID3 tags themselves")))

#?(:clj
   (deftest reading-media-in-rounds
     (let [b   (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
           log (atom [])
           r   (media/read! "https://example.org/a.m4a" {:fetch (fetch-from b log)})]
       (is (= 150.5 (:duration r)))
       (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))
       (is (<= (count @log) 3) (pr-str @log)))
     (let [log (atom [])]
       (is (= [:file/incomplete]
              (map :id (media/report (media/read! "https://example.org/a.mp3"
                                                  {:fetch (fn [start end] (swap! log conj [start end]) {:bytes (bytes/of []) :start start})}))))
           "a range that never comes"))))

#?(:cljs
   (deftest reading-media-in-rounds
     (async done
       (let [b   (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
             log (atom [])]
         (-> (media/read! "https://example.org/a.m4a" {:fetch (fetch-from b log)})
             (.then (fn [r]
                      (is (= 150.5 (:duration r)))
                      (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally done))))))

(deftest media-in-memory
  (let [b (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))]
    (is (= 150.5 (:duration (media/read (view/whole b)))) "a file that the app has read whole")))

#?(:clj
   (deftest media-from-a-local-file
     (let [b    (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
           file (java.io.File/createTempFile "episode" ".m4a")]
       (try
         (with-open [out (java.io.FileOutputStream. file)]
           (.write out ^bytes b))
         (let [r (media/read! (str file) {:fetch (ranges/file-fetcher file)})]
           (is (= 150.5 (:duration r)))
           (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))
           (is (= (bytes/length b) (:bytes r))))
         (is (= 0 (bytes/length (:bytes ((ranges/file-fetcher file) (+ 10 (bytes/length b)) (+ 20 (bytes/length b))))))
             "no bytes past the end")
         (finally
           (.delete file))))))

#?(:cljs
   (deftest media-from-a-blob
     (async done
       (let [b (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))]
         (-> (media/read! "episode.m4a" {:fetch (ranges/blob-fetcher (js/Blob. #js [b]))})
             (.then (fn [r]
                      (is (= 150.5 (:duration r)))
                      (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))))
             (.catch (fn [e] (is false (str "rejected: " e))))
             (.finally done))))))

#?(:clj
   (deftest media-over-http
     (let [b   (bytes/of (chapter-file ["Intro" "Talk"] [60000 90500] true))
           log (atom [])]
       (with-file-server b {:log log}
         (fn [url]
           (let [r (media/read! url {:allow-pred local?})]
             (is (= 150.5 (:duration r)))
             (is (= ["Intro" "Talk"] (map :title (:chapters (:chapters r)))))
             (is (every? some? @log) "every request for a range"))
           (is (= :file/unreachable (get-in (media/read! url) [:error :id]))
               "but by default only a file on the public internet"))))
     (testing "a server that ignores ranges, of whose file the start is read"
       (let [b     (bytes/of (concat (tag 3 (text 3 "TIT2" "T")) (apply concat (repeat 2500 (audio-frame)))))
             sizes (atom [])]
         (with-file-server b {:ranges? false}
           (fn [url]
             (let [fetch (http/fetcher url {:allow-pred local?})
                   r     (media/read! url {:fetch (fn [start end]
                                                    (let [part (fetch start end)]
                                                      (swap! sizes conj (bytes/length (:bytes part)))
                                                      part))})]
               (is (< (abs (- (/ (* 2500 1152) 44100.0) (:duration r))) 0.01) "estimated from the size of the whole")
               (is (every? #(< % (quot (bytes/length b) 4)) @sizes) (pr-str @sizes)))))))))

(deftest parts-that-do-not-read
  (let [reading (fn [xs] (let [r (media/read (view xs))]
                           [(:format r) (:duration r) (map (juxt :id :part) (media/report r))]))]
    (testing "a chapter track past the end of the file"
      (let [stbl (box "stbl"
                      (box "stts" [0 0 0 0] (be 4 1) (be 4 1) (be 4 1000))
                      (box "stsz" [0 0 0 0] (be 4 10) (be 4 1))
                      (box "stsc" [0 0 0 0] (be 4 1) (be 4 1) (be 4 1) (be 4 1))
                      (box "stco" [0 0 0 0] (be 4 1) (be 4 5000000)))]
        (is (= [:mp4 0.001 [[:file/truncated :chapters]]] (reading (chapter-track-file stbl 1)))
            "the format and the duration as they are, and the report names the part")))
    (testing "a Nero chapter at a time of all ones, more than the JVM counts to"
      (let [chpl (box "chpl" [1 0 0 0] (be 4 0) [1] (repeat 8 0xFF) [5] (ascii "Intro"))
            r    (media/read (view (concat (box "ftyp" (ascii "M4A ") (be 4 0))
                                           (box "moov" (mvhd 600 90000) (box "udta" chpl)))))]
        (is (= [:mp4 150.0 ["Intro"]] [(:format r) (:duration r) (map :title (:chapters (:chapters r)))]))))
    (testing "a tag that says it has an extended header, and has none"
      (is (= [:mp3 (/ (* 1000 1152) 44100.0) [[:id3/malformed nil]]]
             (reading (concat (ascii "ID3") [3 0 0x40 0 0 0 0] (xing-frame 1000) (audio-frame) (audio-frame))))))
    (testing "a Xing header that counts no frames knows no duration"
      (let [[format duration report] (reading (concat (xing-frame 0) (apply concat (repeat 20 (audio-frame)))))]
        (is (= [:mp3 [[:mp3/estimated-duration nil]]] [format report]))
        (is (about= (/ (* 21 1152) 44100.0) duration))))))

(defn- hollow
  "A fetch of a file of `total` bytes that holds the bytes `b` at its start
  and nothing that a reader can use after them, as `ranges/read!` takes it,
  noting each range in `log`."
  [b total log]
  (let [n (bytes/length b)]
    (fn [start end]
      (swap! log conj [start end])
      {:bytes (bytes/slice b (min start n) (min end n)) :start start :total total})))

(deftest sizes-that-lie
  (let [mb      (* 1024 1024)
        n       16
        samples (chapter-track-file
                 (box "stbl"
                      (box "stts" [0 0 0 0] (be 4 1) (be 4 n) (be 4 1000))
                      (box "stsz" [0 0 0 0] (be 4 (* 90 mb)) (be 4 n))
                      (box "stsc" [0 0 0 0] (be 4 1) (be 4 1) (be 4 1) (be 4 1))
                      (box "stco" [0 0 0 0] (be 4 n) (mapcat #(be 4 (* (inc %) 100 mb)) (range n))))
                 n)
        tag     (concat (ascii "ID3") [3 0 0] (syncsafe (* 200 mb)) (repeat 100 0))
        reading (fn [xs opts]
                  (let [log (atom [])]
                    (async/then (media/read! "https://example.org/a" (assoc opts :fetch (hollow (bytes/of xs) (* 3000 mb) log)))
                                (fn [r]
                                  {:report   (map :id (media/report r))
                                   :requests (count @log)
                                   :asked    (reduce + (map (fn [[start end]] (- end start)) @log))}))))]
    (run
     #(async/then (reading samples {})
                  (fn [{:keys [report asked]}]
                    (is (= [:file/incomplete] report))
                    (is (< asked (* 2 mb)) "of text samples that say they hold 90 MB each, the text alone")))
     #(async/then (reading tag {})
                  (fn [{:keys [report requests asked]}]
                    (is (= [:file/incomplete] report))
                    (is (= [1 true] [requests (< asked mb)]) "a tag of 200 MB is more than a reading fetches")))
     #(async/then (reading tag {:max-bytes (* 300 mb)})
                  (fn [{:keys [requests asked]}]
                    (is (= [2 true] [requests (< (* 200 mb) asked)]) "unless the app lets it")))
     #(async/then (reading tag {:max-bytes 10})
                  (fn [{:keys [report requests]}]
                    (is (= [[:file/incomplete] 0] [report requests]) "and with no room, no request"))))))

#?(:clj
   (def mangled
     "A file of each format that the readers know, and bytes changed in it."
     (gen/let [file    (gen/elements [(concat (tag 3 (ctoc 3 "toc" ["a"]) (chap 3 "a" 0 5000 (text 3 "TIT2" "A")))
                                              (xing-frame 10) (audio-frame))
                                      (concat (tag 4 (text 4 "TIT2" "B")) (audio-frame) (audio-frame))
                                      (chapter-file ["Intro" "Talk"] [60000 90500] true)
                                      (fragmented-file 120000 true true)])
               changes (gen/vector (gen/tuple gen/nat (gen/choose 0 255)) 1 8)
               cut     gen/nat]
       (let [xs (reduce (fn [v [i x]] (assoc v (mod i (count v)) x)) (vec file) changes)]
         (subvec xs 0 (max 0 (- (count xs) (mod cut 64))))))))

#?(:clj
   (defspec any-bytes-give-a-reading 300
     (prop/for-all [xs (gen/one-of [(gen/vector (gen/choose 0 255) 0 600) mangled])]
       (let [r (media/read (view xs))]
         (and (map? r) (or (:format r) (seq (media/report r))))))))

(deftest a-file-that-does-not-come
  (let [url     "https://example.org/a.mp3"
        down    (fn [_] (throw (ex-info "Connection refused" {})))
        missing (fn [request] {:status 404 :url (:url request) :headers {}})
        told    (juxt :id :url :status :reason)]
    (run
     #(async/then (media/read! url {:send down})
                  (fn [file]
                    (is (= [:error] (keys file)) "a result with the error alone, and nothing thrown")
                    (is (= [:file/unreachable url nil "Connection refused"] (told (:error file))))
                    (is (string? (:message (:error file))) "the diagnostic with its message")
                    (is (= [] (media/report file)))))
     #(async/then (media/read! url {:send missing})
                  (fn [file]
                    (is (= [:file/unreachable url 404 (str "The file answered 404: " url)] (told (:error file))))))
     #(async/then (media/read! url {:fetch (fn [_ _] (throw (ex-info "The disk is gone" {})))})
                  (fn [file]
                    (is (= [:file/unreachable url nil "The disk is gone"] (told (:error file)))
                        "the fetch of the app"))))))
