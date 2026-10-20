(ns ^:no-doc dk.simongray.podcast.format.image.impl
  "The header readers of PNG, JPEG, GIF and WebP, the decoding of data
  URLs, and the pixel and colour arithmetic behind format.image."
  (:require [clojure.string :as str]
            [dk.simongray.colour-coded.image :as colour-image]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

;; WHATWG MIME Sniffing Standard, section 6.1
(def signatures
  "The MIME type of an image by the bytes it begins with, nil standing for
  any byte."
  [["image/jpeg" [0xFF 0xD8 0xFF]]
   ["image/png" [0x89 0x50 0x4E 0x47 0x0D 0x0A 0x1A 0x0A]]
   ["image/gif" [0x47 0x49 0x46 0x38]]
   ["image/webp" [0x52 0x49 0x46 0x46 nil nil nil nil 0x57 0x45 0x42 0x50]]])

(defn loose?
  "Whether `declared`, the MIME type that a file gives for an image that it
  holds, is loose, such as JPG or image/jpg, or missing."
  [declared]
  (let [t (str/lower-case (str/trim (str declared)))]
    (not (and (re-matches #"image/[a-z0-9.+-]+" t)
              (not= "image/jpg" t)))))

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
  (view/unless-broken
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
(def png-colors
  "The color model of each color type of a PNG image."
  {0 :grayscale 2 :rgb 3 :indexed 4 :grayscale 6 :rgb})

(defn png
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

(def frame-markers
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
    (view/unless-broken
     #(do (view/need view p end)
          (when (and (<= (+ t 8) end)
                     (= "Exif" (view/code view (+ p 4) 4)))
            (let [le?      (= "II" (view/code view t 2))
                  u16*     (partial (if le? view/u16le view/u16) view)
                  u32*     (partial (if le? view/u32le view/u32) view)
                  ifd      (+ t (u32* (+ t 4)))
                  n        (when (<= (+ ifd 2) end)
                             (min (u16* ifd) (quot (- end ifd 2) 12)))
                  oriented (fn [i]
                             (let [e (+ ifd 2 (* 12 i))]
                               (when (= 0x0112 (u16* e))
                                 (let [o (u16* (+ e 8))]
                                   (when (and (<= 1 o) (<= o 8)) o)))))]
              (some oriented (range (or n 0)))))))))

(def jpeg-colors
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

(defn jpeg
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

(defn gif
  "The header of the GIF image in `view`, from the logical screen
  descriptor of a GIF87a or GIF89a image, or nil for any other version."
  [view]
  (view/need view 0 10)
  (when (contains? #{"87a" "89a"} (view/code view 3 3))
    {:type   "image/gif"
     :width  (view/u16le view 6)
     :height (view/u16le view 8)
     :color  :indexed}))

(defn webp
  "The header of the WebP image in `view`, from its first chunk:

  - VP8X, the extended format, with the canvas size and an alpha flag
  - VP8L, a lossless image, after its signature
  - VP8, a lossy image, after its start code"
  [view]
  (view/need view 12 30)
  (case (view/code view 12 4)
    "VP8X" {:type   "image/webp"
            :width  (inc (view/u24le view 24))
            :height (inc (view/u24le view 27))
            :color  :rgb
            :alpha? (bit-test (view/u8 view 20) 4)}
    "VP8L" (when (= 0x2F (view/u8 view 20))
             (let [bits (view/u32le view 21)]
               {:type   "image/webp"
                :width  (inc (bit-and bits 0x3FFF))
                :height (inc (bit-and (bit-shift-right bits 14) 0x3FFF))
                :color  :rgb
                :alpha? (bit-test bits 28)}))
    "VP8 " (when (and (= 0x9D (view/u8 view 23))
                      (= 0x01 (view/u8 view 24))
                      (= 0x2A (view/u8 view 25)))
             {:type   "image/webp"
              :width  (bit-and (view/u16le view 26) 0x3FFF)
              :height (bit-and (view/u16le view 28) 0x3FFF)
              :color  :rgb
              :alpha? false})
    nil))

(defn other-type
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

;; RFC 3986, section 2.1
(defn percent-decoded
  "The bytes of the text `s` in UTF-8, with each byte that it
  percent-encodes decoded."
  [s]
  (apply bytes/concat
         (for [piece (re-seq #"%[0-9A-Fa-f]{2}|[^%]+|%" s)]
           (if (and (= 3 (count piece))
                    (str/starts-with? piece "%"))
             (bytes/from-hex (subs piece 1))
             (bytes/encode piece)))))

;; WHATWG Infra, section 7, as of 5 October 2026
(defn from-forgiving-base64
  "The bytes that the base64 text `s` names once its percent-encoding is
  undone, or nil. It may have spaces, lack its padding and have pad bits
  that aren't zero, as a data URL may."
  [s]
  (let [digits (str "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
                    "abcdefghijklmnopqrstuvwxyz"
                    "0123456789+/")
        s      (-> s
                   (str/replace #"%([0-9A-Fa-f]{2})"
                                (fn [[_ hex]]
                                  (str (char #?(:clj  (Integer/parseInt hex 16)
                                                 :cljs (js/parseInt hex 16))))))
                   (str/replace #"[\t\n\f\r ]" ""))
        s      (cond-> s
                 (zero? (mod (count s) 4)) (str/replace #"={1,2}$" ""))
        n      (count s)
        ;; the bits of the last digit that make whole bytes
        mask   (get {2 0x30 3 0x3C} (mod n 4))]
    (when (and (not= 1 (mod n 4))
               (re-matches #"[A-Za-z0-9+/]*" s))
      (let [s (if mask
                (let [x (str/index-of digits (subs s (dec n)))]
                  (str (subs s 0 (dec n)) (nth digits (bit-and x mask))))
                s)]
        (bytes/from-base64 (str s (subs "===" 0 (mod (- n) 4))))))))

(defn- hex-pair
  "The byte `n` as two hexadecimal digits."
  [n]
  (let [digits "0123456789abcdef"]
    (str (nth digits (quot n 16)) (nth digits (rem n 16)))))

(defn hex-of
  "The red, green and blue `rgb`, each from 0 to 255, as a CSS hex colour."
  [rgb]
  (apply str "#" (map hex-pair rgb)))

(defn rgb-of
  "The red, green and blue of the CSS hex colour `hex`, #rrggbb, each from
  0 to 255, or nil when it isn't one."
  [hex]
  (when-let [[_ & pairs] (and (string? hex)
                              (re-matches #"#([0-9a-fA-F]{2}){3}" hex)
                              (re-matches #"#(..)(..)(..)" hex))]
    (mapv #(#?(:clj Integer/parseInt :cljs js/parseInt) % 16) pairs)))

(defn palette-of
  "The colours that the most of the `pixels` have, at most `n`, the most
  common first, as CSS hex colours. Each pixel is a vector of red, green,
  blue and alpha from 0 to 255, and only the opaque ones count.

  Pixels count together when they agree in the top three bits of each
  channel, and a colour is their average."
  [pixels n]
  (let [opaque (filter #(>= (nth % 3) 128) pixels)
        bucket (fn [[r g b]] [(quot r 32) (quot g 32) (quot b 32)])
        groups (sort-by (juxt (comp - count) (comp bucket first))
                        (vals (group-by bucket opaque)))
        mean   (fn [group i]
                 (quot (reduce + (map #(nth % i) group)) (count group)))]
    (mapv (fn [group] (hex-of (map #(mean group %) [0 1 2])))
          (take n groups))))

(defn linear
  "The channels of the red, green and blue `rgb`, each from 0 to 255, as
  linear light from 0 to 1, as sRGB defines it."
  [rgb]
  (map #(let [c (/ % 255.0)]
          (if (<= c 0.04045)
            (/ c 12.92)
            (Math/pow (/ (+ c 0.055) 1.055) 2.4)))
       rgb))

;; WCAG 2.2, the definitions of relative luminance and contrast ratio
(defn luminance
  "The relative luminance of the red, green and blue `rgb`."
  [rgb]
  (let [[r g b] (linear rgb)]
    (+ (* 0.2126 r) (* 0.7152 g) (* 0.0722 b))))

;; CSS Color 4, sections 9.2 and 18.6: OKLab from linear sRGB, by way of
;; LMS, and the chroma of OKLCH, the distance from the axis of the greys
;; https://www.w3.org/TR/css-color-4/#ok-lab
(defn chroma
  "The chroma of the red, green and blue `rgb` in OKLCH: 0 for a grey, and
  about 0.3 at most for the most vivid colours of sRGB."
  [rgb]
  (let [[r g b] (linear rgb)
        cbrt    #(Math/cbrt %)
        l       (cbrt (+ (* 0.4122214708 r) (* 0.5363325363 g) (* 0.0514459929 b)))
        m       (cbrt (+ (* 0.2119034982 r) (* 0.6806995451 g) (* 0.1073969566 b)))
        s       (cbrt (+ (* 0.0883024619 r) (* 0.2817188376 g) (* 0.6299787005 b)))
        a       (+ (* 1.9779984951 l) (* -2.4285922050 m) (* 0.4505937099 s))
        b*      (+ (* 0.0259040371 l) (* 0.7827717662 m) (* -0.8086757660 s))]
    (Math/sqrt (+ (* a a) (* b* b*)))))

(defn with-pixels
  "What `f` gives for the pixels of the image in the bytes `b`, decoded at
  a small size by the platform, or nil where it can't decode them, in
  ClojureScript a promise of it, as colour-coded's image/pixels decodes
  them."
  [b f]
  (colour-image/then (colour-image/pixels b) f))
