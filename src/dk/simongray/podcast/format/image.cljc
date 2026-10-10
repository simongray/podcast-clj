(ns dk.simongray.podcast.format.image
  "The header of an image file, which is what a check of artwork needs.

  The header gives the type of the image, its size in pixels, its color
  model and whether it can be transparent. The reader takes them from the
  first bytes and doesn't decode the image. It reads these formats:

  - PNG, by the W3C PNG specification
  - JPEG, by ITU-T T.81
  - GIF87a and GIF89a
  - WebP, by RFC 9649, with the VP8 frame header of RFC 6386, section 9.1

  A reading takes a view of the parts of the file at hand, as in
  dk.simongray.podcast.util.bytes.view, and gives the header, or the ranges
  that it still needs."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.wary-fetch.async :as async]))

(defn ^:no-doc u16le
  "The little-endian 16-bit number at `p` in `view`."
  [view p]
  (+ (view/u8 view p) (* 256 (view/u8 view (inc p)))))

(defn- u24le
  "The little-endian 24-bit number at `p` in `view`."
  [view p]
  (+ (u16le view p) (* 65536 (view/u8 view (+ p 2)))))

(defn ^:no-doc u32le
  "The little-endian 32-bit number at `p` in `view`."
  [view p]
  (+ (u16le view p) (* 65536 (u16le view (+ p 2)))))

(defn ^:no-doc unless-broken
  "What `f` gives when called, or nil when it fails. A failure because the
  view lacks bytes or the file ends too soon is thrown again, so that a
  reader can fetch the bytes or report the end."
  [f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (when (or (view/missing e)
                (view/truncated? e))
        (throw e))
      nil)))

(defn- chunk-type?
  "Whether the four bytes at `p` in `view` can be the type of a PNG chunk,
  which is ASCII letters."
  [view p]
  (let [letter? (fn [c]
                  (or (and (<= 65 c) (<= c 90))
                      (and (<= 97 c) (<= c 122))))]
    (every? #(letter? (view/u8 view %))
            (range p (+ p 4)))))

(defn- transparent?
  "Whether the PNG image in `view` of the color type `kind` has a tRNS
  chunk that makes a pixel transparent, or nil when the chunks before the
  data break off.

  - a greyscale or truecolour image has a transparent color in any tRNS
  - an indexed image has one when an entry of its tRNS is below 255, since
    the entries left out are 255

  The chunks break off at a type that isn't letters, or at a length beyond
  2^31 - 1, as the PNG specification bounds it."
  [view kind]
  (unless-broken
   (fn []
     (loop [p 33]
       (view/need view p (+ p 8))
       (let [n    (long (view/u32 view p))
             type (when (chunk-type? view (+ p 4))
                    (view/code view (+ p 4) 4))]
         (cond
           (or (nil? type) (> n 0x7FFFFFFF))
           nil

           (= "tRNS" type)
           (if (= 3 kind)
             (let [end (+ p 8 (min n 256))]
               (view/need view (+ p 8) end)
               (boolean (some #(< (view/u8 view %) 255)
                              (range (+ p 8) end))))
             true)

           (#{"IDAT" "IEND"} type)
           false

           :else
           (recur (+ p 12 n))))))))

;; PNG (Third Edition), section 11.2.1
(def ^:no-doc png-colors
  "The color model of each color type of a PNG image."
  {0 :grayscale 2 :rgb 3 :indexed 4 :grayscale 6 :rgb})

(defn- png
  "The header of the PNG image in `view`, from the IHDR chunk, which comes
  first. Its :alpha? is nil when the chunks after it don't read, and the
  report says so when the file ends before them."
  [view]
  (view/need view 8 33)
  (when (= "IHDR" (view/code view 12 4))
    (let [kind  (view/u8 view 25)
          alpha (if (contains? #{4 6} kind)
                  {:value true}
                  (view/part :transparency #(transparent? view kind)))]
      (view/reported {:type   "image/png"
                      :width  (view/u32 view 16)
                      :height (view/u32 view 20)
                      :color  (get png-colors kind)
                      :alpha? (:value alpha)}
                     (:report alpha)))))

(def ^:no-doc frame-markers
  "The coding process of each JPEG marker that starts a frame, which gives
  the size and the components of the image.

  The names are those of ITU-T T.81, and T.87 for JPEG-LS. DHP gives the
  size of a hierarchical image before its frames."
  {0xC0 :baseline
   0xC1 :extended
   0xC2 :progressive
   0xC3 :lossless
   0xC5 :differential
   0xC6 :differential
   0xC7 :differential
   0xC9 :extended-arithmetic
   0xCA :progressive-arithmetic
   0xCB :lossless-arithmetic
   0xCD :differential-arithmetic
   0xCE :differential-arithmetic
   0xCF :differential-arithmetic
   0xDE :hierarchical
   0xF7 :jpeg-ls})

(defn- orientation
  "The EXIF orientation, 1 to 8, of the APP1 segment at `p` in `view`,
  which has `length` bytes after its marker, or nil. It's the Orientation
  tag of the first IFD, in the byte order of the TIFF header."
  [view p length]
  (let [end (+ p 2 length)
        t   (+ p 10)]
    (unless-broken
     #(do (view/need view p end)
          (when (and (<= (+ t 8) end)
                     (= "Exif" (view/code view (+ p 4) 4)))
            (let [le?      (= "II" (view/code view t 2))
                  u16*     (fn [q] (if le? (u16le view q) (view/u16 view q)))
                  u32*     (fn [q] (if le? (u32le view q) (view/u32 view q)))
                  ifd      (+ t (u32* (+ t 4)))
                  n        (when (<= (+ ifd 2) end)
                             (min (u16* ifd) (quot (- end ifd 2) 12)))
                  oriented (fn [i]
                             (let [e (+ ifd 2 (* 12 i))]
                               (when (= 0x0112 (u16* e))
                                 (let [o (u16* (+ e 8))]
                                   (when (and (<= 1 o) (<= o 8)) o)))))]
              (some oriented (range (or n 0)))))))))

(def ^:no-doc jpeg-colors
  "The color model of a JPEG image by the number of its components. Three
  components are YCbCr or RGB, and four are CMYK or YCCK, which is CMYK
  too."
  {1 :grayscale 3 :rgb 4 :cmyk})

(defn- jpeg-frame
  "The header of the JPEG image whose frame header is at `p` in `view`,
  after the frame `marker`. It has the EXIF orientation `turned` of the
  segments before it.

  A height of 0 means that it comes later, in a DNL segment after the
  image data, so it's left out."
  [view p marker turned]
  (view/need view p (+ p 10))
  (let [height      (view/u16 view (+ p 5))
        process     (frame-markers marker)
        progressive #{:progressive :progressive-arithmetic}]
    (cond-> {:type         "image/jpeg"
             :width        (view/u16 view (+ p 7))
             :color        (get jpeg-colors (view/u8 view (+ p 9)))
             :alpha?       false
             :process      process
             :bits         (view/u8 view (+ p 4))
             :progressive? (contains? progressive process)}
      (pos? height) (assoc :height height)
      turned        (assoc :orientation turned))))

(defn- jpeg
  "The header of the JPEG image in `view`, or nil when the image data or
  the end comes first.

  It's the frame header of the first frame marker, found by walking the
  segments before it. It has the coding process, the sample precision in
  bits and the EXIF orientation."
  [view]
  (loop [p 2 turned nil]
    (view/need view p (+ p 4))
    (let [marker (view/u8 view (inc p))]
      (cond
        (not= 0xFF (view/u8 view p))
        nil

        (= 0xFF marker)
        (recur (inc p) turned)

        (contains? frame-markers marker)
        (jpeg-frame view p marker turned)

        (contains? #{0xD9 0xDA} marker)
        nil

        ;; markers that stand alone, without a length
        (or (= 0x01 marker)
            (and (<= 0xD0 marker) (<= marker 0xD7)))
        (recur (+ p 2) turned)

        :else
        (let [length (long (view/u16 view (+ p 2)))
              app1?  (= 0xE1 marker)]
          (recur (+ p 2 length)
                 (or turned
                     (when app1?
                       (orientation view p length)))))))))

(defn- gif
  "The header of the GIF image in `view`, from the logical screen
  descriptor of a GIF87a or GIF89a image, or nil for any other version."
  [view]
  (view/need view 0 10)
  (when (contains? #{"87a" "89a"} (view/code view 3 3))
    {:type   "image/gif"
     :width  (u16le view 6)
     :height (u16le view 8)
     :color  :indexed}))

(defn- webp
  "The header of the WebP image in `view`, from its first chunk:

  - VP8X, the extended format, with the canvas size and an alpha flag
  - VP8L, a lossless image, after its signature
  - VP8, a lossy image, after its start code"
  [view]
  (view/need view 12 30)
  (case (view/code view 12 4)
    "VP8X" {:type   "image/webp"
            :width  (inc (u24le view 24))
            :height (inc (u24le view 27))
            :color  :rgb
            :alpha? (bit-test (view/u8 view 20) 4)}
    "VP8L" (when (= 0x2F (view/u8 view 20))
             (let [bits (u32le view 21)]
               {:type   "image/webp"
                :width  (inc (bit-and bits 0x3FFF))
                :height (inc (bit-and (bit-shift-right bits 14) 0x3FFF))
                :color  :rgb
                :alpha? (bit-test bits 28)}))
    "VP8 " (when (and (= 0x9D (view/u8 view 23))
                      (= 0x01 (view/u8 view 24))
                      (= 0x2A (view/u8 view 25)))
             {:type   "image/webp"
              :width  (bit-and (u16le view 26) 0x3FFF)
              :height (bit-and (u16le view 28) 0x3FFF)
              :color  :rgb
              :alpha? false})
    nil))

(defn- other-type
  "The MIME type of the image in `view` by the bytes it begins with, for a
  format whose header the reader doesn't read, or nil. These formats are
  BMP, TIFF, ICO, AVIF, HEIC, HEIF and SVG."
  [view]
  (let [n  (min 256 (or (:total view) 256))
        b  #(view/u8 view %)
        is (fn [& xs]
             (every? (fn [[i x]] (= x (b i))) (map-indexed vector xs)))]
    (view/need view 0 n)
    (cond
      (and (>= n 2) (is 0x42 0x4D))
      "image/bmp"

      (and (>= n 4)
           (or (is 0x49 0x49 0x2A 0)
               (is 0x4D 0x4D 0 0x2A)))
      "image/tiff"

      (and (>= n 4) (is 0 0 1 0))
      "image/vnd.microsoft.icon"

      (and (>= n 12) (= "ftyp" (view/code view 4 4)))
      (let [brand (view/code view 8 4)]
        (cond
          (contains? #{"avif" "avis"} brand)
          "image/avif"

          (contains? #{"heic" "heix" "hevc" "hevx" "heim" "heis"} brand)
          "image/heic"

          (contains? #{"mif1" "msf1"} brand)
          "image/heif"))

      (re-find #"<svg[\s>]" (view/code view 0 n))
      "image/svg+xml")))

(defn- read-header
  "The header of the image in `view` when the view holds the bytes that the
  reading needs."
  [view]
  (view/need view 0 12)
  (let [start   (view/slice view 0 (min 12 (or (:total view) 12)))
        type    (bytes/image-type start)
        header  (case type
                  "image/png"  (png view)
                  "image/jpeg" (jpeg view)
                  "image/gif"  (gif view)
                  "image/webp" (webp view)
                  nil)
        failure {:id    (if type :file/malformed :file/unknown-format)
                 :level :warning}]
    (or header
        (when-not type
          (some->> (other-type view) (hash-map :type)))
        (view/reported (if type {:type type} {}) [failure]))))

(defn report
  "The diagnostics of the read `image`. Each diagnostic has its message."
  [image]
  (mapv diagnostics/describe (view/report image)))

(defn read
  "The header of the image whose parts `view` holds, as a map of :type,
  :width, :height, :color and :alpha?.

  - :type is a MIME type
  - :width and :height are in pixels
  - :color is the color model: :rgb, :indexed, :grayscale or :cmyk
  - :alpha? is whether the image has transparent pixels, or nil when the
    header doesn't tell, as for a GIF

  A JPEG also gives:

  - :process, the coding process, which is a value of frame-markers
  - :progressive?
  - :bits, the sample precision
  - :orientation, the EXIF orientation, 1 to 8

  The height of a JPEG is absent when the image gives it only after its
  data. AVIF, HEIC, HEIF, SVG, BMP, TIFF and ICO give their :type alone,
  since the reader doesn't read their headers.

  When the view lacks bytes, the result has the ranges that the reading
  needs under :need, as pairs of start and end. For an image that doesn't
  read, the report tells why, and the result has the :type when the first
  bytes tell it."
  [view]
  (view/reading #(read-header view)))

(defn read!
  "Read the header of the image at `url`, fetched with `opts`. On the JVM
  it gives the result, and in ClojureScript a promise of it.

  The reading most often needs the first 64 KB alone. The `opts` are
  these:

  - :fetch, a function as http/fetcher makes
  - :headers, :timeout-seconds and :send, as http/fetcher takes them
  - :allow-pred and :user-agent, as http/send! takes them
  - the limits of view/read!, with view/default-limits for those that
    they don't set

  Nothing that the network or the server does throws. When a request
  fails, the result has a :file/unreachable diagnostic under :error."
  ([url]
   (read! url {}))
  ([url opts]
   (async/then (view/read-url! url read opts)
               #(cond-> % (:error %) (update :error diagnostics/describe)))))

(comment
  ;; the start of a PNG image of 1400 by 1400 pixels in RGB: the
  ;; signature, the IHDR chunk, and the header of the IDAT chunk
  (def sample-bytes
    (bytes/of (concat [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]
                      [0 0 0 13 0x49 0x48 0x44 0x52]
                      [0 0 5 120 0 0 5 120 8 2 0 0 0]
                      [0 0 0 0]
                      [0 0 0 0 0x49 0x44 0x41 0x54])))

  (def sample
    (view/add (view/view) 0 sample-bytes nil))

  (read sample)
  (report (read sample))

  ;; the first 20 bytes alone: the reading tells the range that it needs
  (read (view/add (view/view) 0 (bytes/copy sample-bytes 0 20) nil))

  ;; a file that is no image
  (report (read (view/add (view/view) 0
                          (bytes/encode "<html>not an image</html>") 25)))

  (read! "https://example.org/artwork.png")
  #_.)
