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
  that it still needs. An image embedded in text, e.g. the artwork of a
  chapter, is a data URL, which data-url and from-data-url write and read.
  Only colors, palette and dominant-color decode an image, through the
  platform, for the colours that an app can colour its UI by, and
  thumbnails, for the small copies that a server can send in place of
  artwork of 3000 pixels. The image namespace of colour-coded decodes and
  scales the images for them."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.colour-coded.image :as colour-image]
            [dk.simongray.podcast.format.image.impl :as impl]
            [dk.simongray.podcast.util.bytes.ranges :as ranges]
            [dk.simongray.podcast.util.bytes.view :as view]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn sniff
  "The MIME type of the image whose bytes `b` begin as a JPEG, PNG, GIF or
  WebP image begins, or nil for other bytes."
  [b]
  (some (fn [[type signature]]
          (when (and (>= (bytes/length b) (count signature))
                     (every? (fn [[i x]] (or (nil? x) (= x (bytes/at b i))))
                             (map-indexed vector signature)))
            type))
        impl/signatures))

(defn- read-header
  "The header of the image in `view` when the view holds the bytes that the
  reading needs."
  [view]
  (view/need view 0 12)
  (let [start   (view/slice view 0 (min 12 (or (:total view) 12)))
        type    (sniff start)
        header  (case type
                  "image/png"  (impl/png view)
                  "image/jpeg" (impl/jpeg view)
                  "image/gif"  (impl/gif view)
                  "image/webp" (impl/webp view)
                  nil)
        failure {:id    (if type :file/malformed :file/unknown-format)
                 :level :warning}]
    (or header
        (when-not type
          (some->> (impl/other-type view) (hash-map :type)))
        (view/reported (if type {:type type} {}) [failure]))))

(defn report
  "The diagnostics of the read `image`. Each diagnostic has its message."
  [image]
  (view/report image))

(defn read
  "The header of the image whose parts `view` holds, as a map of :type,
  :width, :height, :color and :alpha?.

  - :type is a MIME type
  - :width and :height are in pixels
  - :color is the color model: :rgb, :indexed, :grayscale or :cmyk
  - :alpha? is whether the image has transparent pixels, or nil when the
    header doesn't tell, as for a GIF

  A JPEG also gives:

  - :process, the coding process as ITU-T T.81 names it, e.g. :baseline or
    :progressive
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

  - :fetch, a function as http/fetcher makes, or for a local file one
    that ranges/file-fetcher or ranges/blob-fetcher makes, when `url` only
    names the file in an error
  - :headers, :timeout-seconds and :send, as http/fetcher takes them
  - :allow-pred and :user-agent, as http/send! takes them
  - the limits of ranges/read!, with ranges/default-limits for those that
    they don't set

  Nothing that the network or the server does throws. When a request
  fails, the result has a :file/unreachable diagnostic under :error."
  ([url]
   (read! url {}))
  ([url opts]
   (ranges/read-url! url read opts)))

;; RFC 2397, section 3
(defn data-url
  "The bytes `b` of the MIME type `type` as a data URL, which a browser
  shows as an image source."
  [type b]
  (str "data:" type ";base64," (bytes/base64 b)))

;; RFC 2397, section 3
(defn from-data-url
  "The MIME type and the bytes of the data URL `s` as a map of :type and
  :data, or nil for a text that isn't a data URL. A data URL without a
  type is text/plain;charset=US-ASCII."
  [s]
  (let [comma            (when (string? s)
                           (str/index-of s ","))
        [_ type base64?] (when comma
                           (re-matches #"(?i)^data:(.*?)(;base64)?$"
                                       (subs s 0 comma)))
        body             (when type
                           (subs s (inc comma)))
        data             (when body
                           (if base64?
                             (impl/from-forgiving-base64 body)
                             (impl/percent-decoded body)))]
    (when data
      {:type (cond
               (str/blank? type)           "text/plain;charset=US-ASCII"
               (str/starts-with? type ";") (str "text/plain" type)
               :else                       type)
       :data data})))

;; CSS Color 4, section 7.1: a hue turned halfway round, at the same
;; saturation and lightness, is each channel taken from the sum of the
;; largest and the smallest
(defn complementary-color
  "The complement of the CSS hex colour `hex`, #rrggbb: the colour of the
  opposite hue, e.g. #00a9a9 of #a90000, or nil when `hex` isn't one. A
  grey has no hue, so it's its own complement."
  [hex]
  (when-let [rgb (impl/rgb-of hex)]
    (let [sum (+ (apply max rgb) (apply min rgb))]
      (impl/hex-of (map #(- sum %) rgb)))))

(defn contrast
  "The contrast ratio of the CSS hex colours `a` and `b`, #rrggbb, from 1
  to 21 by WCAG 2.2, or nil when either isn't one. Text wants 4.5 and a
  control 3."
  [a b]
  (let [rgbs (map impl/rgb-of [a b])]
    (when (every? some? rgbs)
      (let [[l1 l2] (sort > (map impl/luminance rgbs))]
        (/ (+ l1 0.05) (+ l2 0.05))))))

;; CSS Color 5, section 8, Editor's Draft of 8 October 2026: contrast-color()
;; leaves the measure to the browser, and resolves a tie to white
(defn contrast-color
  "Black or white, whichever reads better as text on the CSS hex colour
  `hex`, #rrggbb, by the contrast ratio of WCAG 2.2, or nil when `hex`
  isn't one. A tie goes to white, as with contrast-color() of CSS."
  [hex]
  (when (impl/rgb-of hex)
    (if (> (contrast hex "#000000") (contrast hex "#ffffff"))
      "#000000"
      "#ffffff")))

;; Material 3's dynamic colour derives a whole scheme from one source
;; colour, which a grey can't be, since it has no hue; artwork whose most
;; common colour is white or black still has one further down its palette
;; https://m3.material.io/styles/color/dynamic/user-generated-source
(defn seed-color
  "The first colour of the `palette` that has some chroma, i.e. 0.04 or
  more in OKLCH, to derive the colours of a UI from, e.g. by its hue, or
  nil where every colour of it is about grey."
  [palette]
  (first (filter #(some-> (impl/rgb-of %) impl/chroma (>= 0.04)) palette)))

;; WCAG 2.2, success criterion 1.4.11: a control has a contrast of 3:1
(defn scheme
  "The colours of a UI by the `palette` of an image, the most common colour
  first, as palette gives it: the map that colors gives, or nil for an
  empty palette. It's colors without the decoding."
  [palette]
  (when-let [background (first palette)]
    (let [complement (complementary-color background)
          apart?     #(>= (contrast background %) 3)
          accent     (or (first (filter apart? (rest palette)))
                         (when (apart? complement) complement)
                         (contrast-color background))]
      {:background  background
       :text        (contrast-color background)
       :accent      accent
       :accent-text (contrast-color accent)
       :complement  complement
       :seed        (seed-color palette)
       :palette     palette})))

(defn palette
  "The colours that the most of the image in the bytes `b` has, at most
  `n`, by default 5, the most common first, as CSS hex colours. On the JVM
  it gives them, and in ClojureScript a promise of them. They're nil
  where the platform can't decode the image, as dominant-color says."
  ([b]
   (palette b 5))
  ([b n]
   (impl/with-pixels b #(not-empty (impl/palette-of % n)))))

(defn dominant-color
  "The colour that most of the image in the bytes `b` has, as a CSS hex
  colour, e.g. #1e3a5f, for a player to tint its screen by the artwork.
  On the JVM it gives the colour, and in ClojureScript a promise of it.

  The platform decodes the image at a small size: ImageIO on the JVM, and
  the browser's own decoder in ClojureScript. The colour is nil where the
  platform can't decode the image, e.g. WebP on the JVM, or anything in
  Node, which has no decoder, and for an image that's all transparent."
  [b]
  (impl/with-pixels b #(first (impl/palette-of % 1))))

(defn colors
  "The colours for an app to colour its UI by the image in the bytes `b`,
  e.g. the artwork of a show, as a map. On the JVM it gives the map, and
  in ClojureScript a promise of it.

      {:background  \"#a90000\"     ; the dominant colour, to tint by
       :text        \"#ffffff\"     ; black or white, whichever reads better
       :accent      \"#e8c070\"     ; for controls, e.g. a progress bar
       :accent-text \"#000000\"     ; for text on the accent
       :complement  \"#00a9a9\"     ; the opposite hue of the background
       :seed        \"#a90000\"     ; the first colour with a hue, or nil
       :palette     [\"#a90000\" …]} ; the most common colours, most first

  The accent is the first other colour of the palette that stands apart
  from the background by a contrast of 3:1, as WCAG 2.2 asks of controls,
  or else the complement when it does, or else the text colour. The map is
  nil where the platform can't decode the image, as dominant-color says."
  [b]
  (impl/with-pixels b #(scheme (impl/palette-of % 5))))

(defn thumbnails
  "The image in the bytes `b` scaled down to each of the `widths`, with the
  :max-pixels of `opts`, as colour-coded's image/thumbnails gives them: a
  map of each width to a map of the thumbnail's :type, :width, :height and
  :data, its bytes. On the JVM it gives the map, and in ClojureScript a
  promise of it.

  A thumbnail is a JPEG, or a PNG when the image has transparency. It's
  never wider than the image: the widths at or above the image's own share
  one thumbnail, the image at its own width.

  The platform decodes the image: ImageIO on the JVM, and the browser's
  own decoder in ClojureScript. Node has no decoder, so there it's nil. It's
  nil for an image that it can't scale too, so that a server can send the
  image itself instead:

  - a format other than JPEG, PNG and GIF, e.g. WebP or SVG
  - a CMYK JPEG, or one that EXIF turns, since a browser turns it too
  - an animated GIF on the JVM. A browser scales its first frame.
  - more than the :max-pixels of `opts`, by default colour-coded's 25
    million, which the header tells before the image is decoded"
  ([b widths]
   (thumbnails b widths {}))
  ([b widths opts]
   (let [{:keys [type color orientation width height]} (read (view/whole b))
         most (:max-pixels opts colour-image/max-pixels)]
     (if (and (#{"image/jpeg" "image/png" "image/gif"} type)
              (not= :cmyk color)
              (contains? #{nil 1} orientation)
              (or (nil? width) (nil? height) (<= (* width height) most)))
       (colour-image/thumbnails b widths {:max-pixels most})
       #?(:clj  nil
          :cljs (js/Promise.resolve nil))))))

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
  (read (view/add (view/view) 0 (bytes/slice sample-bytes 0 20) nil))

  ;; a file that is no image
  (report (read (view/add (view/view) 0
                          (bytes/encode "<html>not an image</html>") 25)))

  (read! "https://example.org/artwork.png")
  #_.)
