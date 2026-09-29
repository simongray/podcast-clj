(ns dk.simongray.podcast.format.image
  "The header of an image file, which is what a check of artwork needs.

  The header gives the type of the image, its size in pixels, its color
  model and whether it can be transparent. The reader takes them from the
  first bytes and does not decode the image. It reads PNG as the W3C PNG
  specification defines it, JPEG as ITU-T T.81 and JFIF (T.871) do,
  GIF89a, and WebP as RFC 9649 does.

  `read` takes a view of the parts of the file at hand (see
  dk.simongray.podcast.util.bytes). It gives the header, or the ranges that it
  still needs, as `dk.simongray.podcast.format.media/read` does for media
  files. `read!` fetches them, and `report` gives the diagnostics of a
  reading."
  (:refer-clojure :exclude [read])
  (:require [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]))

(defn ^:no-doc u16le
  "The little-endian 16-bit number at `p` in `view`."
  [view p]
  (+ (bytes/u8 view p) (* 256 (bytes/u8 view (inc p)))))

(defn- u24le
  "The little-endian 24-bit number at `p` in `view`."
  [view p]
  (+ (u16le view p) (* 65536 (bytes/u8 view (+ p 2)))))

(defn ^:no-doc u32le
  "The little-endian 32-bit number at `p` in `view`."
  [view p]
  (+ (u16le view p) (* 65536 (u16le view (+ p 2)))))

(defn ^:no-doc unless-broken
  "What `(f)` gives, or nil when it fails for any reason but bytes that the
  view lacks or a file that ends too soon, which it throws on, so that a
  reader can fetch the bytes or report the end."
  [f]
  (try
    (f)
    (catch #?(:clj Exception :cljs :default) e
      (when (or (bytes/missing e) (bytes/truncated? e)) (throw e))
      nil)))

(defn- chunk-type?
  "Whether the four bytes at `p` in `view` can be the type of a PNG chunk:
  ASCII letters."
  [view p]
  (every? #(let [c (bytes/u8 view %)] (or (and (<= 65 c) (<= c 90)) (and (<= 97 c) (<= c 122))))
          (range p (+ p 4))))

(defn- transparent?
  "Whether the PNG image in `view` of the color type `kind` has a tRNS
  chunk among the chunks before its data that makes a pixel transparent:
  any for a greyscale or truecolour image, which it gives a transparent
  color, and for an indexed image one of its entries below 255, since the
  entries it leaves out are 255. Nil when the chunks break off before the
  data, with a type that is not letters or a length beyond 2^31 - 1, as
  the PNG specification bounds it."
  [view kind]
  (unless-broken
   (fn []
     (loop [p 33]
       (bytes/need view p (+ p 8))
       (let [n    (long (bytes/u32 view p))
             type (when (chunk-type? view (+ p 4)) (bytes/code view (+ p 4) 4))]
         (cond
           (or (nil? type) (> n 0x7FFFFFFF)) nil
           (= "tRNS" type)                   (if (= 3 kind)
                                               (let [end (+ p 8 (min n 256))]
                                                 (bytes/need view (+ p 8) end)
                                                 (boolean (some #(< (bytes/u8 view %) 255)
                                                                (range (+ p 8) end))))
                                               true)
           (#{"IDAT" "IEND"} type)           false
           :else                             (recur (+ p 12 n))))))))

(def ^:no-doc png-colors
  "The color model of each color type of a PNG image."
  {0 :grayscale 2 :rgb 3 :indexed 4 :grayscale 6 :rgb})

(defn- png
  "The header of the PNG image in `view`, from the IHDR chunk, which comes
  first; `:alpha?` nil when the chunks after it do not read. When the file
  ends before them, the report says so."
  [view]
  (bytes/need view 8 33)
  (when (= "IHDR" (bytes/code view 12 4))
    (let [kind  (bytes/u8 view 25)
          alpha (if (contains? #{4 6} kind)
                  {:value true}
                  (bytes/part :transparency #(transparent? view kind)))]
      (bytes/reported {:type   "image/png"
                       :width  (bytes/u32 view 16)
                       :height (bytes/u32 view 20)
                       :color  (get png-colors kind)
                       :alpha? (:value alpha)}
                      (:report alpha)))))

(def ^:no-doc frame-markers
  "The coding process of each JPEG marker that starts a frame, which gives
  the size and the components of the image, as ITU-T T.81 names them, and
  T.87 for JPEG-LS; DHP gives the size of a hierarchical image before its
  frames."
  {0xC0 :baseline 0xC1 :extended 0xC2 :progressive 0xC3 :lossless
   0xC5 :differential 0xC6 :differential 0xC7 :differential
   0xC9 :extended-arithmetic 0xCA :progressive-arithmetic 0xCB :lossless-arithmetic
   0xCD :differential-arithmetic 0xCE :differential-arithmetic 0xCF :differential-arithmetic
   0xDE :hierarchical 0xF7 :jpeg-ls})

(defn- orientation
  "The EXIF orientation, 1 to 8, that the APP1 segment at `p` in `view`
  holds, of `length` bytes after its marker: the Orientation tag of its
  first IFD, in the byte order of its TIFF header; nil when it holds none."
  [view p length]
  (let [end (+ p 2 length)
        t   (+ p 10)]
    (unless-broken
     #(do (bytes/need view p end)
          (when (and (<= (+ t 8) end) (= "Exif" (bytes/code view (+ p 4) 4)))
            (let [le?  (= "II" (bytes/code view t 2))
                  u16* (fn [q] (if le? (u16le view q) (bytes/u16 view q)))
                  ifd  (+ t (if le? (u32le view (+ t 4)) (bytes/u32 view (+ t 4))))
                  n    (when (<= (+ ifd 2) end) (min (u16* ifd) (quot (- end ifd 2) 12)))]
              (some (fn [i]
                      (let [e (+ ifd 2 (* 12 i))]
                        (when (= 0x0112 (u16* e))
                          (let [o (u16* (+ e 8))] (when (and (<= 1 o) (<= o 8)) o)))))
                    (range (or n 0)))))))))

(def ^:no-doc jpeg-colors
  "The color model of a JPEG image by the number of its components. Three
  components are YCbCr or RGB, and four are CMYK or YCCK, which is CMYK
  too."
  {1 :grayscale 3 :rgb 4 :cmyk})

(defn- jpeg-frame
  "The header of the JPEG image whose frame header is at `p` in `view`,
  after the frame marker `marker`, with the EXIF orientation `turned` of
  the segments before it. A height of 0 is given later, in a DNL segment
  after the image data, so it is left out."
  [view p marker turned]
  (bytes/need view p (+ p 10))
  (let [height  (bytes/u16 view (+ p 5))
        process (frame-markers marker)]
    (cond-> {:type         "image/jpeg"
             :width        (bytes/u16 view (+ p 7))
             :color        (get jpeg-colors (bytes/u8 view (+ p 9)))
             :alpha?       false
             :process      process
             :bits         (bytes/u8 view (+ p 4))
             :progressive? (contains? #{:progressive :progressive-arithmetic} process)}
      (pos? height) (assoc :height height)
      turned        (assoc :orientation turned))))

(defn- jpeg
  "The header of the JPEG image in `view`: the frame header of the first
  frame marker, found by walking the segments before it, with the coding
  process, the sample precision in bits and the EXIF orientation; nil when
  the image data or the end comes first."
  [view]
  (loop [p 2 turned nil]
    (bytes/need view p (+ p 4))
    (let [marker (bytes/u8 view (inc p))]
      (cond
        (not= 0xFF (bytes/u8 view p))
        nil

        (= 0xFF marker)
        (recur (inc p) turned)

        (contains? frame-markers marker)
        (jpeg-frame view p marker turned)

        (contains? #{0xD9 0xDA} marker)
        nil

        ;; markers that stand alone, without a length
        (or (= 0x01 marker) (and (<= 0xD0 marker) (<= marker 0xD7)))
        (recur (+ p 2) turned)

        :else
        (let [length (long (bytes/u16 view (+ p 2)))]
          (recur (+ p 2 length)
                 (or turned (when (= 0xE1 marker) (orientation view p length)))))))))

(defn- gif
  "The header of the GIF image in `view`: the logical screen descriptor of
  a GIF87a or GIF89a image; nil for any other version."
  [view]
  (bytes/need view 0 10)
  (when (contains? #{"87a" "89a"} (bytes/code view 3 3))
    {:type "image/gif" :width (u16le view 6) :height (u16le view 8) :color :indexed}))

(defn- webp
  "The header of the WebP image in `view`, from its first chunk: VP8X for the
  extended format, with the canvas size and an alpha flag, VP8L for a
  lossless image, after its signature, and VP8 for a lossy one, after its
  start code."
  [view]
  (bytes/need view 12 30)
  (case (bytes/code view 12 4)
    "VP8X" {:type   "image/webp"
            :width  (inc (u24le view 24))
            :height (inc (u24le view 27))
            :color  :rgb
            :alpha? (bit-test (bytes/u8 view 20) 4)}
    "VP8L" (when (= 0x2F (bytes/u8 view 20))
             (let [bits (u32le view 21)]
               {:type   "image/webp"
                :width  (inc (bit-and bits 0x3FFF))
                :height (inc (bit-and (bit-shift-right bits 14) 0x3FFF))
                :color  :rgb
                :alpha? (bit-test bits 28)}))
    "VP8 " (when (and (= 0x9D (bytes/u8 view 23)) (= 0x01 (bytes/u8 view 24)) (= 0x2A (bytes/u8 view 25)))
             {:type   "image/webp"
              :width  (bit-and (u16le view 26) 0x3FFF)
              :height (bit-and (u16le view 28) 0x3FFF)
              :color  :rgb
              :alpha? false})
    nil))

(defn- other-type
  "The MIME type of an image in a format whose header the reader does not
  read, by the bytes it begins with: BMP, TIFF, ICO, AVIF, HEIC or HEIF,
  and SVG; nil for none."
  [view]
  (let [n  (min 256 (or (:total view) 256))
        b  #(bytes/u8 view %)
        is (fn [& xs] (every? (fn [[i x]] (= x (b i))) (map-indexed vector xs)))]
    (bytes/need view 0 n)
    (cond
      (and (>= n 2) (is 0x42 0x4D))                                "image/bmp"
      (and (>= n 4) (or (is 0x49 0x49 0x2A 0) (is 0x4D 0x4D 0 0x2A))) "image/tiff"
      (and (>= n 4) (is 0 0 1 0))                                  "image/vnd.microsoft.icon"
      (and (>= n 12) (= "ftyp" (bytes/code view 4 4)))
      (let [brand (bytes/code view 8 4)]
        (cond
          (contains? #{"avif" "avis"} brand)                      "image/avif"
          (contains? #{"heic" "heix" "hevc" "hevx" "heim" "heis"} brand) "image/heic"
          (contains? #{"mif1" "msf1"} brand)                      "image/heif"))
      (re-find #"<svg[\s>]" (bytes/code view 0 n))                   "image/svg+xml")))

(defn- read-header
  "What `read` gives for the image in `view` when the view holds the bytes
  that the reading needs."
  [view]
  (bytes/need view 0 12)
  (let [type   (bytes/image-type (bytes/slice view 0 (min 12 (or (:total view) 12))))
        header (case type
                 "image/png"  (png view)
                 "image/jpeg" (jpeg view)
                 "image/gif"  (gif view)
                 "image/webp" (webp view)
                 nil)]
    (or header
        (when-not type (some->> (other-type view) (hash-map :type)))
        (bytes/reported (if type {:type type} {})
                        [{:id (if type :file/malformed :file/unknown-format) :level :warning}]))))

(defn report
  "The diagnostics of `image`, which `read` or `read!` gave. Each
  diagnostic has its message."
  [image]
  (bytes/report image))

(defn read
  "The header of the image whose parts `view` holds, as `{:type :width
  :height :color :alpha?}`.

  `:type` is a MIME type, and the size is in pixels. `:color` is the
  color model: :rgb, :indexed, :grayscale or :cmyk. `:alpha?` tells
  whether the image has transparent pixels. It is nil when the header
  does not tell, as for a GIF.

  A JPEG also gives its coding `:process`, its sample precision in
  `:bits`, `:progressive?`, and the EXIF `:orientation`, 1 to 8. The
  processes go from :baseline, :extended and :progressive to :lossless,
  :hierarchical, :jpeg-ls and the arithmetic processes. The height of a
  JPEG is absent when the image gives it only after its data.

  An image in a format whose header the reader does not read gives its
  `:type` alone. Such formats are AVIF, HEIC, HEIF, SVG, BMP, TIFF and
  ICO.

  When the view lacks bytes, the result is `{:need [[start end] ...]}`,
  the ranges that the reading needs. For an image that does not read,
  `report` tells why, and the result has the `:type` when the first bytes
  tell it."
  [view]
  (bytes/reading #(read-header view)))

(defn read!
  "The header of the image at `url`, as `read` gives it. On the JVM it
  gives the result, and in ClojureScript a promise of it.

  `dk.simongray.podcast.util.bytes/read!` fetches the ranges that the reading
  needs, most often the first 64 KB alone. `opts` can give `:fetch`, a
  function as `http/fetcher` makes. They can also give the `:headers`, the
  `:timeout-seconds` and the `:send` of `http/fetcher`, and the `:allow?`
  of `http/call!`.

  `opts` give the limits of `bytes/read!`, with `bytes/default-limits` for
  the limits that they do not set.

  `read!` throws nothing for what the network or the server does. When a
  request fails, the result is `{:error diagnostic}`, and the diagnostic
  is `:file/unreachable`."
  ([url]
   (read! url {}))
  ([url opts]
   (http/read-by-ranges! url read opts)))

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
    (bytes/add (bytes/view) 0 sample-bytes nil))

  (read sample)
  (report (read sample))

  ;; the first 20 bytes alone: the reading tells the range that it needs
  (read (bytes/add (bytes/view) 0 (bytes/copy sample-bytes 0 20) nil))

  ;; a file that is no image
  (report (read (bytes/add (bytes/view) 0 (bytes/encode "<html>not an image</html>") 25)))

  (read! "https://example.org/artwork.png")
  #_.)
