(ns dk.simongray.podcast.format.image-test
  "The reader of image headers, on images built here byte by byte: PNG with
  and without transparency, baseline and progressive JPEG in each color
  model, GIF, and the three kinds of WebP; and on the JVM random bytes and
  mangled images, which it must answer with a report, never an exception."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            #?@(:clj [[clojure.test.check.clojure-test :refer [defspec]]
                      [clojure.test.check.generators :as gen]
                      [clojure.test.check.properties :as prop]])
            [clojure.test :refer [deftest is testing #?(:cljs async)]]
            [dk.simongray.podcast.format.image :as image]
            #?(:cljs [dk.simongray.podcast.format.image.impl :as impl])
            [dk.simongray.podcast.test-support :refer [ascii be fetch-from le run view]]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.webcrypto.bytes :as bytes]))

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

(deftest the-type-by-the-first-bytes
  (is (= ["image/jpeg" "image/png" "image/webp" nil]
         (map (comp image/sniff bytes/of)
              [[0xFF 0xD8 0xFF 0xE0 0 16 74 70 73 70] png-signature (webp "VP8 " (repeat 10 0)) [1 2 3 4]]))))

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
    (is (= [[:file/truncated :transparency]] (map (juxt :id :part) (image/report cut))))
    (is (every? :message (image/report cut)))
    (is (not (contains? cut :report)) "the report is in the metadata"))
  (is (= [:file/malformed] (map :id (image/report (image/read (view (concat png-signature (png-chunk "tEXt" (repeat 20 0))))))))
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
  (is (= [:file/malformed] (map :id (image/report (image/read (view (concat [0xFF 0xD8] (segment 0xDA [0]) [0xFF 0xD9]))))))
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
  (is (= [:file/malformed] (map :id (image/report (image/read (view (concat (ascii "GIF8zz") (le 2 3000) (le 2 2000) [0 0 0]))))))
      "a GIF of no version")
  (is (= [:file/malformed] (map :id (image/report (image/read (view (webp "VP8 " (concat [0 0 0] [0 0 0] (le 2 640) (le 2 480) [0 0])))))))
      "a lossy WebP without its start code")
  (is (= {:type "image/avif"} (image/read (view (concat (be 4 24) (ascii "ftypavif") (be 4 0) (ascii "mif1miaf"))))))
  (is (= {:type "image/svg+xml"} (image/read (view (ascii "<?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>")))))
  (is (= [:file/unknown-format] (map :id (image/report (image/read (view (ascii "<html>not an image</html>")))))))
  (is (= [:file/truncated] (map :id (image/report (image/read (view (take 30 (png 1 1 2))))))))
  (is (= [:file/truncated] (map :id (image/report (image/read (view (concat (take 33 (png 1 1 2)) (be 4 0x7FFFFFFF) (ascii "tEXt")))))))
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
         (and (map? r) (or (:type r) (seq (image/report r))))))))

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

(deftest an-image-that-does-not-come
  (let [url "https://example.org/a.png"]
    (run
     #(async/then (image/read! url {:send (fn [_] (throw (ex-info "Connection refused" {})))})
                  (fn [header]
                    (is (= [:error] (keys header)) "a result with the error alone, and nothing thrown")
                    (is (= [:file/unreachable url "Connection refused"]
                           ((juxt :id :url :reason) (:error header))))))
     #(async/then (image/read! url {:send (fn [request] {:status 403 :url (:url request) :headers {}})})
                  (fn [header]
                    (is (= [:file/unreachable url 403] ((juxt :id :url :status) (:error header)))))))))

(deftest data-urls
  (let [read (fn [url]
               (some-> (image/from-data-url url) (update :data bytes/hex)))]
    (is (= {:type "image/png" :data "89504e47"}
           (read (image/data-url "image/png" (bytes/from-hex "89504e47")))))
    (testing "the forms of RFC 2397"
      (is (= {:type "text/plain;charset=US-ASCII" :data "41206e6f7465"} (read "data:,A%20note")))
      (is (= {:type "text/plain;charset=utf-8" :data "c3a6"} (read "data:;charset=utf-8,%C3%A6")))
      (is (= {:type "image/svg+xml" :data "3c7376673ec3a6"} (read "data:image/svg+xml,%3Csvg%3Eæ")))
      (is (= {:type "text/plain" :data "31303025"} (read "data:text/plain,100%")) "a percent sign that encodes nothing"))
    (testing "base64 as browsers read it"
      (is (= "616263" (:data (read "DATA:;BASE64,YW Jj\n"))) "in any case, with spaces")
      (is (= "41" (:data (read "data:;base64,QQ"))) "without its padding")
      (is (= "41" (:data (read "data:;base64,QQ%3D%3D"))) "with its padding percent-encoded")
      (is (= "41" (:data (read "data:;base64,QR"))) "pad bits that aren't zero, which are left out")
      (is (= "4142" (:data (read "data:;base64,QUL"))) "the pad bits of a group of three digits"))
    (doseq [url [nil 5 "" "https://a.example/a.png" "data:image/png" "data:;base64,Q"
                 "data:;base64,QQ=QQ" "data:;base64,Q!==" "data:;base64,QQ="]]
      (is (nil? (image/from-data-url url)) (pr-str url)))
    (testing "a large image reads in a short time"
      (let [b (bytes/of (repeat 300000 7))]
        (is (true? (bytes/same? b (:data (image/from-data-url (image/data-url "image/png" b))))))))))

(deftest colours-by-colours
  (is (= ["#00a9a9" "#a08020" "#808080"] (map image/complementary-color ["#a90000" "#2040A0" "#808080"]))
      "the opposite hue, and a grey its own")
  (is (= ["#ffffff" "#000000" "#ffffff"] (map image/contrast-color ["#a90000" "#f0c040" "#000000"])))
  (is (= [nil nil nil] [(image/complementary-color "red") (image/contrast-color "#abc") (image/contrast "red" "#000000")])
      "only #rrggbb")
  (is (= 21.0 (image/contrast "#000000" "#ffffff")))
  (is (= {:background "#a90000" :text "#ffffff" :accent "#f0c040" :accent-text "#000000" :complement "#00a9a9"
          :seed "#a90000" :palette ["#a90000" "#1a1a1a" "#f0c040"]}
         (image/scheme ["#a90000" "#1a1a1a" "#f0c040"]))
      "an accent that stands apart from the background, where the dark grey doesn't")
  (is (= ["#002fff" "#ffffff"] ((juxt :accent :accent-text) (image/scheme ["#ffd000" "#ffc010"])))
      "or else the complement, when it stands apart")
  (is (= "#ffffff" (:accent (image/scheme ["#a90000" "#a01010"])))
      "or else the colour of the text, as the complement of dark red is only 2.7:1")
  (is (= "#000000" (:accent (image/scheme ["#f4f4f4" "#eeeeee"]))) "and a grey its own complement")
  (is (nil? (image/scheme []))))

(deftest a-seed-to-colour-by
  (is (= "#24476b" (image/seed-color ["#ffffff" "#111111" "#24476b"]))
      "the first colour with a hue, past the white and the black that most of the artwork is")
  (is (= "#a90000" (image/seed-color ["#a90000" "#24476b"])))
  (is (nil? (image/seed-color ["#ffffff" "#808080" "#f4f4f0"])) "none among greys, or off-whites")
  (is (nil? (image/seed-color [])))
  (is (nil? (:seed (image/scheme ["#000000" "#ffffff"])))))

#?(:clj
   (deftest the-colour-of-an-image
     (let [png (fn [w h f type]
                 (let [img (java.awt.image.BufferedImage. w h type)
                       out (java.io.ByteArrayOutputStream.)]
                   (doseq [x (range w) y (range h)]
                     (.setRGB img x y (unchecked-int (f x))))
                   (javax.imageio.ImageIO/write img "png" out)
                   (.toByteArray out)))]
       (let [b (png 300 200 #(if (< % 210) 0xFF2040A0 0xFFC03020) java.awt.image.BufferedImage/TYPE_INT_RGB)]
         (is (= "#2040a0" (image/dominant-color b)))
         (is (= ["#2040a0" "#c03020"] (image/palette b)))
         (is (= {:background "#2040a0" :text "#ffffff" :accent "#ffffff" :accent-text "#000000" :complement "#a08020"
                 :seed "#2040a0" :palette ["#2040a0" "#c03020"]}
                (image/colors b))
             "white for the accent, as the red is only 1.6:1 against the blue"))
       (is (nil? (image/dominant-color (png 10 10 (constantly 0x00FF0000) java.awt.image.BufferedImage/TYPE_INT_ARGB)))
           "an image that's all transparent")
       (is (= [nil nil] [(image/dominant-color (bytes/of [1 2 3])) (image/colors (bytes/of [1 2 3]))])
           "bytes that aren't an image"))))

#?(:clj
   (defn- written-image
     "The bytes of an image of `w` by `h` pixels in `format`, e.g. jpeg,
     blue on the left and red on the right, of the BufferedImage `type`,
     whose transparent kinds are transparent at the top."
     [w h format type]
     (let [img (java.awt.image.BufferedImage. w h type)
           out (java.io.ByteArrayOutputStream.)]
       (doseq [x (range w) y (range h)]
         (.setRGB img x y (unchecked-int (cond
                                           (< y 10)        0x00FFFFFF
                                           (< x (quot w 2)) 0xFF2040A0
                                           :else           0xFFC03020))))
       (javax.imageio.ImageIO/write img ^String format out)
       (.toByteArray out))))

#?(:clj
   (deftest thumbnails-of-an-image
     (let [jpeg   (written-image 1000 500 "jpeg" java.awt.image.BufferedImage/TYPE_INT_RGB)
           result (image/thumbnails jpeg [64 128 256 512])]
       (is (= {64  ["image/jpeg" 64 32]
               128 ["image/jpeg" 128 64]
               256 ["image/jpeg" 256 128]
               512 ["image/jpeg" 512 256]}
              (update-vals result (juxt :type :width :height)))
           "each width, with the height in proportion")
       (let [decoded (javax.imageio.ImageIO/read (java.io.ByteArrayInputStream. (:data (get result 64))))
             rgb     (fn [x y] (let [c (.getRGB decoded x y)]
                                 (mapv #(bit-and (bit-shift-right c %) 255) [16 8 0])))
             near?   (fn [a b] (every? #(<= (abs %) 8) (map - a b)))]
         (is (= [64 32] [(.getWidth decoded) (.getHeight decoded)]) "a JPEG that decodes")
         (is (near? [0x20 0x40 0xA0] (rgb 8 24)) "blue on the left")
         (is (near? [0xC0 0x30 0x20] (rgb 56 24)) "and red on the right"))
       (is (= {512 [320 160]} (update-vals (image/thumbnails (written-image 320 160 "png" java.awt.image.BufferedImage/TYPE_INT_RGB) [512])
                                           (juxt :width :height)))
           "never wider than the image itself")
       (is (= "image/png" (:type (get (image/thumbnails (written-image 300 300 "png" java.awt.image.BufferedImage/TYPE_INT_ARGB) [64]) 64)))
           "a PNG for an image with transparency")
       (is (= 64 (:width (get (image/thumbnails (written-image 300 300 "gif" java.awt.image.BufferedImage/TYPE_BYTE_INDEXED) [64]) 64)))
           "a GIF of one frame")
       (is (nil? (image/thumbnails jpeg [64] {:max-pixels 1000})) "an image of more pixels than allowed")
       (is (nil? (image/thumbnails (bytes/of [0x52 0x49 0x46 0x46 0 0 0 0 0x57 0x45 0x42 0x50]) [64]))
           "WebP, which ImageIO doesn't read")
       (is (nil? (image/thumbnails (bytes/of [1 2 3]) [64])) "bytes that aren't an image"))))

#?(:cljs
   (defn- drawn-png
     "A promise of the bytes of a PNG image that the browser draws, 300 by
     200 pixels, of which 70% are blue."
     []
     (let [canvas  (js/OffscreenCanvas. 300 200)
           context (.getContext canvas "2d")]
       (set! (.-fillStyle context) "#c03020")
       (.fillRect context 0 0 300 200)
       (set! (.-fillStyle context) "#2040a0")
       (.fillRect context 0 0 210 200)
       (-> (.convertToBlob canvas)
           (.then #(.arrayBuffer %))
           (.then #(js/Uint8Array. %))))))

#?(:cljs
   (deftest thumbnails-of-an-image
     (async done
       (-> (if (exists? js/OffscreenCanvas)
             (-> (drawn-png)
                 (.then #(image/thumbnails % [64 512]))
                 (.then (fn [result]
                          (is (= {64  ["image/jpeg" 64 43]
                                  512 ["image/jpeg" 300 200]}
                                 (update-vals result (juxt :type :width :height)))
                              "drawn by the browser, a JPEG since no pixel is transparent, and never wider than the image")
                          (image/colors (:data (get result 64)))))
                 (.then #(is (every? (fn [d] (<= (abs d) 4))
                                     (map - (impl/rgb-of "#2040a0") (impl/rgb-of (:background %))))
                             "with the colours of the image, within what JPEG changes")))
             (.then (image/thumbnails (bytes/of [0x89 0x50 0x4E 0x47]) [64])
                    #(is (nil? %) "no thumbnails in Node, which has no decoder")))
           (.catch (fn [e] (is false (str "rejected: " e))))
           (.finally done)))))

#?(:cljs
   (deftest the-colour-of-an-image
     (async done
       (-> (if (exists? js/OffscreenCanvas)
             (-> (drawn-png)
                 (.then image/colors)
                 (.then #(is (= ["#2040a0" "#ffffff" ["#2040a0" "#c03020"]]
                                ((juxt :background :text (comp vec (partial take 2) :palette)) %))
                             "drawn by the browser, which blends the edge of the colours as it shrinks the image")))
             (.then (image/colors (bytes/of [0x89 0x50 0x4E 0x47]))
                    #(is (nil? %) "no colours in Node, which has no decoder")))
           (.catch (fn [e] (is false (str "rejected: " e))))
           (.finally done)))))

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
