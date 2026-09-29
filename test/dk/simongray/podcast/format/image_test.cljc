(ns dk.simongray.podcast.format.image-test
  "The reader of image headers, on images built here byte by byte: PNG with
  and without transparency, baseline and progressive JPEG in each color
  model, GIF, and the three kinds of WebP; and on the JVM random bytes and
  mangled images, which it must answer with a report, never an exception."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.test :refer [deftest is #?(:cljs async)]]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.test-support :refer [ascii be fetch-from le view]]))

(def png-signature
  "The eight bytes a PNG image begins with."
  [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A])

(defn- png-chunk
  "A PNG chunk of `type` with the bytes `data`; its CRC is not read."
  [type data]
  (concat (be 4 (count data)) (ascii type) data [0 0 0 0]))

(defn- png
  "A PNG image of `width` by `height` of the color type `kind`, with the
  `chunks` between its header and its data."
  [width height kind & chunks]
  (concat png-signature
          (png-chunk "IHDR" (concat (be 4 width) (be 4 height) [8 kind 0 0 0]))
          (apply concat chunks)
          (png-chunk "IDAT" [1 2 3])
          (png-chunk "IEND" [])))

(defn- segment
  "A JPEG segment of `marker` with the bytes `data`."
  [marker data]
  (concat [0xFF marker] (be 2 (+ 2 (count data))) data))

(defn- jpeg
  "A JPEG image of `width` by `height` whose frame starts with `marker` and
  has `components`, with the `segments` before the frame."
  [marker width height components & segments]
  (concat [0xFF 0xD8]
          (segment 0xE0 (concat (ascii "JFIF") [0 1 1 0 0 1 0 1 0 0]))
          (apply concat segments)
          (segment marker (concat [8] (be 2 height) (be 2 width) [components]
                                  (mapcat (fn [i] [i 0x11 0]) (range components))))
          (segment 0xDA [1 1 0 0 0x3F 0])
          [0xFF 0xD9]))

(defn- exif
  "The data of an APP1 segment of EXIF whose first IFD gives the orientation
  `o`, in the byte order of `n->bytes`, `be` or `le`."
  [n->bytes o]
  (concat (ascii "Exif") [0 0]
          (ascii (if (= le n->bytes) "II" "MM")) (n->bytes 2 42) (n->bytes 4 8)
          (n->bytes 2 1) (n->bytes 2 0x0112) (n->bytes 2 3) (n->bytes 4 1) (n->bytes 2 o) [0 0]
          (n->bytes 4 0)))

(defn- webp
  "A WebP file whose first chunk is of `type` with the bytes `data`."
  [type data]
  (concat (ascii "RIFF") (le 4 (+ 12 (count data))) (ascii "WEBP") (ascii type) (le 4 (count data)) data))

(deftest png-headers
  (is (= {:type "image/png" :width 3000 :height 3000 :color :rgb :alpha? false}
         (image/read (view (png 3000 3000 2)))))
  (is (:alpha? (image/read (view (png 1400 1400 6)))) "truecolour with alpha")
  (is (:alpha? (image/read (view (png 1400 1400 3 (png-chunk "PLTE" [0 0 0]) (png-chunk "tRNS" [0])))))
      "an indexed image made transparent by tRNS")
  (is (false? (:alpha? (image/read (view (png 1400 1400 3 (png-chunk "PLTE" [0 0 0]) (png-chunk "tRNS" [255 255]))))))
      "a tRNS whose entries are all opaque")
  (is (false? (:alpha? (image/read (view (png 1400 1400 3 (png-chunk "PLTE" [0 0 0]) (png-chunk "tRNS" [])))))))
  (is (nil? (:alpha? (image/read (view (png 1400 1400 2 (png-chunk "t3XT" [0]))))))
      "a chunk type that is not letters")
  (is (= [:grayscale false] ((juxt :color :alpha?) (image/read (view (png 600 600 0))))))
  (let [cut (image/read (view (concat png-signature
                                      (png-chunk "IHDR" (concat (be 4 500) (be 4 400) [8 2 0 0 0]))
                                      (be 4 100000) (ascii "tEXt") [0 0 0 0])))]
    (is (= ["image/png" 500 400 nil] ((juxt :type :width :height :alpha?) cut))
        "a chunk past the end of the file, which leaves the header as it is")
    (is (= [[:file/truncated :transparency]] (map (juxt :id :part) (:report cut))))
    (is (every? :message (:report cut))))
  (is (= [:file/malformed] (map :id (:report (image/read (view (concat png-signature (png-chunk "tEXt" (repeat 20 0))))))))
      "a first chunk that is not IHDR"))

(deftest jpeg-headers
  (is (= {:type "image/jpeg" :width 1400 :height 1400 :color :rgb :alpha? false
          :process :baseline :bits 8 :progressive? false}
         (image/read (view (jpeg 0xC0 1400 1400 3)))))
  (is (:progressive? (image/read (view (jpeg 0xC2 1400 1400 3)))))
  (is (= [:hierarchical 1400] ((juxt :process :width) (image/read (view (jpeg 0xDE 1400 1400 3)))))
      "the size that a DHP segment gives before the frames")
  (is (= :jpeg-ls (:process (image/read (view (jpeg 0xF7 1400 1400 3))))))
  (is (= 6 (:orientation (image/read (view (jpeg 0xC0 1400 1000 3 (segment 0xE1 (exif be 6)))))))
      "turned by EXIF, big-endian")
  (is (= 8 (:orientation (image/read (view (jpeg 0xC0 1400 1000 3 (segment 0xE1 (exif le 8))))))))
  (is (nil? (:orientation (image/read (view (jpeg 0xC0 1400 1000 3 (segment 0xE1 (take 14 (exif be 6))))))))
      "an EXIF segment that ends before its IFD")
  (is (= :cmyk (:color (image/read (view (jpeg 0xC0 1400 1400 4 (segment 0xEE (concat (ascii "Adobe") [0 100 0 0 0 0 2]))))))))
  (is (= :grayscale (:color (image/read (view (jpeg 0xC0 800 600 1))))))
  (is (= [800 nil] ((juxt :width :height) (image/read (view (jpeg 0xC0 800 0 3)))))
      "a height that a DNL segment gives later")
  (is (= [800 600] ((juxt :width :height) (image/read (view (concat [0xFF 0xD8 0xFF 0xFF] (drop 2 (jpeg 0xC0 800 600 3)))))))
      "fill bytes before a marker")
  (is (= [:file/malformed] (map :id (:report (image/read (view (concat [0xFF 0xD8] (segment 0xDA [0]) [0xFF 0xD9]))))))
      "image data before any frame"))

(deftest other-headers
  (is (= {:type "image/gif" :width 3000 :height 2000 :color :indexed}
         (image/read (view (concat (ascii "GIF89a") (le 2 3000) (le 2 2000) [0xF7 0 0])))))
  (is (= {:type "image/webp" :width 3000 :height 3000 :color :rgb :alpha? true}
         (image/read (view (webp "VP8X" (concat [0x10 0 0 0] (le 3 2999) (le 3 2999)))))))
  (is (= {:type "image/webp" :width 1400 :height 1400 :color :rgb :alpha? false}
         (image/read (view (webp "VP8L" (concat [0x2F] (le 4 (+ 1399 (* 1399 16384))) [0 0 0 0]))))))
  (is (= {:type "image/webp" :width 640 :height 480 :color :rgb :alpha? false}
         (image/read (view (webp "VP8 " (concat [0 0 0] [0x9D 0x01 0x2A] (le 2 640) (le 2 480) [0 0]))))))
  (is (= [:file/malformed] (map :id (:report (image/read (view (concat (ascii "GIF8zz") (le 2 3000) (le 2 2000) [0 0 0]))))))
      "a GIF of no version")
  (is (= [:file/malformed] (map :id (:report (image/read (view (webp "VP8 " (concat [0 0 0] [0 0 0] (le 2 640) (le 2 480) [0 0])))))))
      "a lossy WebP without its start code")
  (is (= {:type "image/avif"} (image/read (view (concat (be 4 24) (ascii "ftypavif") (be 4 0) (ascii "mif1miaf"))))))
  (is (= {:type "image/svg+xml"} (image/read (view (ascii "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))))
  (is (= [:file/unknown-format] (map :id (:report (image/read (view (ascii "<html>not an image</html>")))))))
  (is (= [:file/truncated] (map :id (:report (image/read (view (take 30 (png 1 1 2))))))))
  (is (= [:file/truncated] (map :id (:report (image/read (view (concat (take 33 (png 1 1 2)) (be 4 0x7FFFFFFF) (ascii "tEXt")))))))
      "a chunk that says it is 2 GB long in a file of 41 bytes"))

(deftest a-png-of-many-chunks
  (is (:alpha? (image/read (view (apply png 1400 1400 2 (concat (repeat 50000 (png-chunk "tEXt" [1]))
                                                              [(png-chunk "tRNS" [0 0 0 0 0 0])])))))
      "50000 chunks before the transparency, walked in one pass"))

#?(:clj
   (def mangled
     "An image of each format that the reader knows, and bytes changed in it."
     (gen/let [image   (gen/elements [(png 1400 1400 6 (png-chunk "PLTE" [1 2 3]) (png-chunk "tRNS" [0]))
                                      (jpeg 0xC2 1400 1400 3 (segment 0xE1 (range 40)))
                                      (concat (ascii "GIF89a") (le 2 3000) (le 2 2000) [0xF7 0 0])
                                      (webp "VP8X" (concat [0x10 0 0 0] (le 3 2999) (le 3 2999)))])
               changes (gen/vector (gen/tuple gen/nat (gen/choose 0 255)) 1 8)
               cut     gen/nat]
       (let [xs (reduce (fn [v [i x]] (assoc v (mod i (count v)) x)) (vec image) changes)]
         (subvec xs 0 (max 0 (- (count xs) (mod cut 32))))))))

#?(:clj
   (defspec any-bytes-give-a-header-or-a-report 500
     (prop/for-all [xs (gen/one-of [(gen/vector (gen/choose 0 255) 0 300) mangled])]
       (let [r (image/read (view xs))]
         (and (map? r) (or (:type r) (seq (:report r))))))))

(deftest reading-images-in-rounds
  (let [b     (bytes/of (png 1400 1400 2 (png-chunk "iTXt" (repeat 100000 0)) (png-chunk "tRNS" [0 0 0 0 0 0])))
        log   (atom [])
        check (fn [header]
                (is (= {:type "image/png" :width 1400 :height 1400 :color :rgb :alpha? true} header))
                (is (= 2 (count @log)) "the chunk after a large one in a second request"))]
    #?(:clj  (check (image/read! "https://example.org/a.png" {:fetch (fetch-from b log)}))
       :cljs (async done
               (-> (image/read! "https://example.org/a.png" {:fetch (fetch-from b log)})
                   (.then check)
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))

(deftest a-jpeg-of-many-segments
  (let [b     (bytes/of (apply jpeg 0xC0 1400 1400 3 (repeat 9 (segment 0xE2 (repeat 65533 0)))))
        log   (atom [])
        check (fn [header]
                (is (= [1400 1400] ((juxt :width :height) header)))
                (is (<= (count @log) 5) "a colour profile of 9 segments, with more read ahead each round"))]
    #?(:clj  (check (image/read! "https://example.org/a.jpg" {:fetch (fetch-from b log)}))
       :cljs (async done
               (-> (image/read! "https://example.org/a.jpg" {:fetch (fetch-from b log)})
                   (.then check)
                   (.catch (fn [e] (is false (str "rejected: " e))))
                   (.finally done))))))
