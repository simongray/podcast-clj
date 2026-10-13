(ns dk.simongray.podcast.format.media.id3
  "ID3v2 tags, the metadata at the start of an MP3 or AAC file.

  The versions are 2.2, 2.3 and 2.4, as id3.org specifies them, with the
  chapters of the ID3v2 Chapter Frame Addendum.

  A frame is a map with its :id. Its other keys are those of its kind:

      a text frame     :text
      TXXX, WXXX       :description, and :text or :url
      COMM, USLT       :language, :description and :text
      a URL frame      :url
      APIC, PIC        :type, :picture-type, :description and :data
      CHAP             :element-id, :start-ms, :end-ms and :frames
      CTOC             :element-id, :top-level?, :ordered?, :children, :frames
      each other frame :size

  The reading is strict. Each accommodation for a tag that breaks the
  specification is a quirk with an id. The report records a quirk that
  applies, and the :except option leaves out the quirks that it names:

      :id3/frame-size    a frame size in the form of the other version
      :id3/text-encoding a text encoding that ID3 doesn't define
      :id3/utf8-latin1   text declared ISO-8859-1 whose bytes are UTF-8
      :id3/utf16-bom     UTF-16 text without its byte order mark
      :id3/image-type    the type of a picture loose, such as JPG, or missing
      :id3/chapter-end   a chapter end that isn't after its start
      :id3/second-tag    a second tag right after the first"
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.format.image :as image]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.bytes.view :as view]))

(defn ^:no-doc syncsafe
  "The 28-bit number of the four syncsafe bytes at `p` in `view`, or nil
  when a byte has its top bit set, as a syncsafe byte never has."
  [view p]
  (let [bs (map #(view/u8 view (+ p %)) (range 4))]
    (when (every? #(< % 0x80) bs)
      (reduce #(+ (* 128 %1) %2) 0 bs))))

(defn header
  "The header of the ID3v2 tag at `p` in `view`, or nil when no tag begins
  there. It's a map of :version, :flags, :start and :end. The :end comes
  after the footer, when the tag has one."
  [view p]
  (view/need view p (+ p 10))
  (let [version  (view/u8 view (+ p 3))
        revision (view/u8 view (+ p 4))
        flags    (view/u8 view (+ p 5))
        size     (syncsafe view (+ p 6))
        footer?  (and (= 4 version) (bit-test flags 4))]
    (when (and (= "ID3" (view/code view p 3))
               (#{2 3 4} version)
               (< revision 0xFF)
               size)
      {:version version
       :flags   flags
       :start   p
       :end     (+ p 10 size (if footer? 10 0))})))

(defn ^:no-doc resync
  "The bytes `b` with unsynchronisation undone, i.e. without the zero byte
  after each 0xFF."
  [b]
  (let [n   (bytes/length b)
        out #?(:clj (byte-array n) :cljs (js/Uint8Array. n))]
    (loop [i 0 j 0]
      (if (>= i n)
        (bytes/copy out 0 j)
        (let [x         (bytes/at b i)
              inserted? (and (= 0xFF x)
                             (< (inc i) n)
                             (zero? (bytes/at b (inc i))))]
          #?(:clj  (aset-byte out j (unchecked-byte x))
             :cljs (aset out j x))
          (recur (if inserted? (+ i 2) (inc i))
                 (inc j)))))))

(def ^:no-doc encodings
  "The text encoding of each encoding byte, and the width of its terminator."
  {0 [:latin1 1]
   1 [:utf16 2]
   2 [:utf16be 2]
   3 [:utf8 1]})

(defn ^:no-doc text-encoding
  "The text encoding that the first byte of the frame in `view` names, and
  the width of its terminator, as a pair, read in the context `ctx`. It's
  nil for a frame without bytes.

  By the quirk, a byte that names no encoding of ID3 stands for
  ISO-8859-1. Without the quirk, the reading throws."
  [ctx view]
  (when (pos? (:total view))
    (let [b (view/u8 view 0)]
      (cond
        (contains? encodings b)
        (encodings b)

        (view/quirk? ctx :id3/text-encoding)
        (do (view/note! ctx (view/diagnostic :id3/text-encoding :info
                                             :encoding b))
            [:latin1 1])

        :else
        (throw (ex-info "A text encoding that ID3 does not define"
                        {:encoding b}))))))

(defn ^:no-doc terminator
  "Where the text in `view` from `start` ends, before `end`: at the first
  terminator of `width` zero bytes, aligned for UTF-16. It's `end` when
  the text has no terminator."
  [view start end width]
  (loop [p start]
    (cond
      (> (+ p width) end)
      end

      (every? #(zero? (view/u8 view (+ p %))) (range width))
      p

      :else
      (recur (+ p width)))))

(defn- utf16-order
  "The byte order of the UTF-16 text in `view` from `start` to `end`, read
  in the context `ctx`, and where the text starts, as a pair.

  A byte order mark gives the order, and the text starts after it.
  Without one, the order is big-endian, as Unicode has it. By the quirk,
  it's the order in which the zero bytes of plain letters fall instead."
  [ctx view start end]
  (let [room? (< (inc start) end)
        a     (when room? (view/u8 view start))
        b     (when room? (view/u8 view (inc start)))
        zeros (fn [k]
                (count (filter #(zero? (view/u8 view %))
                               (range (+ start k) end 2))))]
    (cond
      (and (= 0xFF a) (= 0xFE b))
      [:utf16le (+ start 2)]

      (and (= 0xFE a) (= 0xFF b))
      [:utf16be (+ start 2)]

      (or (>= start end)
          (not (view/quirk? ctx :id3/utf16-bom)))
      [:utf16be start]

      :else
      (do (view/note! ctx (view/diagnostic :id3/utf16-bom :info))
          [(if (> (zeros 1) (zeros 0)) :utf16le :utf16be) start]))))

(defn- decoded
  "The text in `view` from `start` to `end` in the ID3 `encoding`, read in
  the context `ctx`."
  [ctx view start end encoding]
  (case encoding
    :latin1 (if (and (view/quirk? ctx :id3/utf8-latin1)
                     (bytes/utf8? (view/slice view start end) 0 (- end start)))
              (do (view/note! ctx (view/diagnostic :id3/utf8-latin1 :info))
                  (view/text view start end :utf8))
              (view/text view start end :latin1))
    :utf16  (let [[order start] (utf16-order ctx view start end)]
              (view/text view start end order))
    (view/text view start end encoding)))

(defn ^:no-doc text-at
  "The text in `view` from `start` up to its terminator or `end`, read in
  the context `ctx`, and where what follows it starts, as a pair. The text
  is in `encoding`, with terminators of `width` bytes."
  [ctx view start end encoding width]
  (let [stop (terminator view start end width)]
    [(decoded ctx view start stop encoding) (min end (+ stop width))]))

(defn- texts
  "The texts in `view` from `start` to `end` in `encoding`, with
  terminators of `width` bytes, read in the context `ctx`. The last
  terminator can be missing, and the empty texts are left out."
  [ctx view start end encoding width]
  (loop [p start acc []]
    (if (>= p end)
      acc
      (let [[s next] (text-at ctx view p end encoding width)]
        (recur (max next (inc p))
               (cond-> acc
                 (not (str/blank? s)) (conj s)))))))

(defn- image-type
  "The MIME type of a picture, as `declared` in its frame, or by the quirk
  from the image `data`, read in the context `ctx`. The quirk applies
  when the declared type is loose, such as JPG or image/jpg, or missing."
  [ctx declared data]
  (let [t (str/lower-case (str/trim (str declared)))]
    (if-let [sniffed (and (image/loose? declared)
                          (view/quirk? ctx :id3/image-type)
                          (image/sniff data))]
      (do (view/note! ctx (view/diagnostic :id3/image-type :info
                                           :declared declared))
          sniffed)
      (if (str/blank? t) declared t))))

(declare frames)

(defn- user-text
  "The TXXX frame `id` in `view`, read in the context `ctx`: a description,
  then a text."
  [ctx view _ id]
  (let [end                (:total view)
        [encoding width]   (text-encoding ctx view)
        [description next] (text-at ctx view 1 end encoding width)
        [text _]           (text-at ctx view next end encoding width)]
    {:id id :description description :text text}))

(defn- user-url
  "The WXXX frame `id` in `view`, read in the context `ctx`: a description,
  then a URL."
  [ctx view _ id]
  (let [end                (:total view)
        [encoding width]   (text-encoding ctx view)
        [description next] (text-at ctx view 1 end encoding width)
        [url _]            (text-at ctx view next end :latin1 1)]
    {:id id :description description :url (str/trim url)}))

(defn- remark
  "The COMM or USLT frame `id` in `view`, read in the context `ctx`: a
  language, a description and a text."
  [ctx view _ id]
  (let [end                (:total view)
        [encoding width]   (text-encoding ctx view)
        [description next] (text-at ctx view 4 end encoding width)]
    {:id          id
     :language    (view/code view 1 3)
     :description description
     :text        (first (text-at ctx view next end encoding width))}))

(def ^:no-doc picture-formats
  "The MIME type of each image format that a PIC frame of ID3v2.2 names."
  {"JPG" "image/jpeg"
   "PNG" "image/png"})

(defn- picture
  "The APIC or PIC frame `id` in `view`, read in the context `ctx`. It has
  the type of the image and of the picture, a description, and the image
  or a link to it."
  [ctx view _ id]
  (let [end              (:total view)
        [encoding width] (text-encoding ctx view)
        [declared next]  (if (= "PIC" id)
                           (let [image-format (view/code view 1 3)
                                 mime-type    (get picture-formats
                                                   (str/upper-case image-format)
                                                   image-format)]
                             [mime-type 4])
                           (text-at ctx view 1 end :latin1 1))
        picture-type     (view/u8 view next)
        [description p]  (text-at ctx view (inc next) end encoding width)]
    (if (= "-->" declared)
      {:id           id
       :picture-type picture-type
       :description  description
       :url          (view/text view p end :latin1)}
      (let [data (view/slice view p end)]
        {:id           id
         :type         (image-type ctx declared data)
         :picture-type picture-type
         :description  description
         :data         data}))))

(defn- chapter
  "The CHAP frame `id` of `version` in `view`, read in the context `ctx`:
  an element id, a start and an end in milliseconds, and the frames inside
  it."
  [ctx view version id]
  (let [end   (:total view)
        stop  (terminator view 0 end 1)
        p     (inc stop)
        inner (assoc ctx :embedded? true)]
    {:id         id
     :element-id (view/text view 0 stop :latin1)
     :start-ms   (view/u32 view p)
     :end-ms     (view/u32 view (+ p 4))
     :frames     (frames inner view version (+ p 16) end)}))

(defn- child-ids
  "The element ids of the `n` children of a table of contents in `view`
  from `p` to `end`, and where they end, as a pair. The reading throws
  when the table holds fewer than `n`."
  [view p end n]
  (loop [p p acc []]
    (if (= n (count acc))
      [acc p]
      (let [s (terminator view p end 1)]
        (when (>= p end)
          (throw (ex-info (str "A table of contents with fewer children "
                               "than it counts")
                          {})))
        (recur (inc s) (conj acc (view/text view p s :latin1)))))))

(defn- contents
  "The CTOC frame `id` of `version` in `view`, read in the context `ctx`.
  It has an element id, two flags, the element ids of its children and
  the frames inside it."
  [ctx view version id]
  (let [end          (:total view)
        stop         (terminator view 0 end 1)
        flags        (view/u8 view (inc stop))
        n            (view/u8 view (+ stop 2))
        [children p] (child-ids view (+ stop 3) end n)
        inner        (assoc ctx :embedded? true)]
    {:id         id
     :element-id (view/text view 0 stop :latin1)
     :top-level? (bit-test flags 1)
     :ordered?   (bit-test flags 0)
     :children   children
     :frames     (frames inner view version p end)}))

(defn- text-frame
  "The text frame `id` in `view`, read in the context `ctx`: one text, or
  more for ID3v2.4."
  [ctx view _ id]
  (let [[encoding width] (text-encoding ctx view)
        values           (texts ctx view 1 (:total view) encoding width)]
    (cond-> {:id id :text (first values)}
      (next values) (assoc :values values))))

(defn- url-frame
  "The URL frame `id` in `view`, read in the context `ctx`."
  [ctx view _ id]
  (let [[url _] (text-at ctx view 0 (:total view) :latin1 1)]
    {:id id :url (str/trim url)}))

(defn- unread
  "The frame `id` in `view` of a kind that the reader doesn't read, with
  its size."
  [_ view _ id]
  {:id id :size (:total view)})

(def ^:no-doc readers
  "The reader of each frame that has a form of its own, by the id of the
  frame."
  {"TXXX" user-text "TXX" user-text
   "WXXX" user-url "WXX" user-url
   "COMM" remark "COM" remark "USLT" remark "ULT" remark
   "APIC" picture "PIC" picture
   "CHAP" chapter
   "CTOC" contents})

(defn- reader
  "The function that reads the frame `id` in the reading context `ctx`.

  The frames inside a CHAP or CTOC frame are its title and the like. A
  CHAP or CTOC frame among them isn't read as a chapter again, so the
  nesting can't run deeper than one level."
  [ctx id]
  (cond
    (and (:embedded? ctx) (#{"CHAP" "CTOC"} id)) unread
    (contains? readers id)                       (readers id)
    (re-matches #"T[A-Z0-9]{2,3}" id)            text-frame
    (re-matches #"W[A-Z0-9]{2,3}" id)            url-frame
    :else                                        unread))

(defn ^:no-doc frame-id?
  "Whether `s` is the id of a frame of `version`: three capitals or digits
  for 2.2, four for 2.3 and 2.4."
  [version s]
  (boolean (re-matches (if (= 2 version) #"[A-Z0-9]{3}" #"[A-Z0-9]{4}") s)))

(defn ^:no-doc plausible-end?
  "Whether a frame of `version` that ends at `p` in `view` is followed by
  what may follow a frame before `end`:

  - the end
  - the header of another frame
  - padding, which is zeros to the end of the tag, of which the first
    kilobyte is looked at"
  [view version p end]
  (or (= p end)
      (and (<= (+ p 10) end)
           (frame-id? version (view/code view p 4)))
      (and (< p end)
           (every? #(zero? (view/u8 view %))
                   (range p (min end (+ p 1024)))))))

(defn- frame-size
  "The size of the frame of `version` 2.3 or 2.4 whose header is at `p` in
  `view`, read in the context `ctx`, or nil when no size fits within `end`.

  The size is plain for 2.3 and syncsafe for 2.4. Some writers use the
  other form. By the quirk, the other form is taken when only it leads to
  a place where a frame can end."
  [ctx view version p end]
  (let [plain       (view/u32 view (+ p 4))
        safe        (syncsafe view (+ p 4))
        [own other] (if (= 4 version) [safe plain] [plain safe])
        fits?       #(and % (plausible-end? view version (+ p 10 %) end))]
    (cond
      (fits? own)
      own

      (and (view/quirk? ctx :id3/frame-size) (fits? other))
      (do (view/note! ctx (view/diagnostic :id3/frame-size :info))
          other)

      (and own (<= (+ p 10 own) end))
      own)))

(defn- frame-flags
  "What the flags of the frame of `version` whose header is at `p` in
  `view` say, read in the context `ctx`. It's a map of :compressed?,
  :encrypted?, :grouped?, :unsync? and :length?."
  [ctx view version p]
  (let [flags (if (= 2 version) 0 (view/u8 view (+ p 9)))]
    (case version
      4 {:compressed? (bit-test flags 3)
         :encrypted?  (bit-test flags 2)
         :grouped?    (bit-test flags 6)
         :unsync?     (or (bit-test flags 1) (:unsync-all? ctx))
         :length?     (bit-test flags 0)}
      3 {:compressed? (bit-test flags 7)
         :encrypted?  (bit-test flags 6)
         :grouped?    (bit-test flags 5)}
      {})))

(defn- content-start
  "Where the content of a frame of `version` starts, after its header that
  ends at `p` and the fields that its `flags` add."
  [version p {:keys [compressed? encrypted? grouped? length?] :as flags}]
  (+ p
     (if grouped? 1 0)
     (if encrypted? 1 0)
     (if length? 4 0)
     (if (and compressed? (= 3 version)) 4 0)))

(defn- frame-body
  "The frame `id` of `version` whose data is the bytes `data`, read by its
  kind in the context `ctx`. It's nil when the data is too short for its
  kind, and the report says so, with the offset `p` of the frame."
  [ctx data version id p]
  (try
    ((reader ctx id) ctx (view/whole data) version id)
    (catch #?(:clj Exception :cljs :default) _
      (view/note! ctx (view/diagnostic :id3/malformed :warning
                                       :offset p :frame id))
      nil)))

(defn- frame-header
  "The header of the frame of `version` at `p` in `view`, read in the
  context `ctx`, or nil when no frame that ends within `end` is there.
  It's a map of :id, :flags, :start of the content and :stop of the frame."
  [ctx view version p end]
  (let [v2?    (= 2 version)
        header (if v2? 6 10)
        id     (view/code view p (if v2? 3 4))
        size   (when (frame-id? version id)
                 (if v2?
                   (view/u24 view (+ p 3))
                   (frame-size ctx view version p end)))]
    (when (and size (<= (+ p header size) end))
      (let [flags (frame-flags ctx view version p)]
        {:id    id
         :flags flags
         :start (content-start version (+ p header) flags)
         :stop  (+ p header size)}))))

(defn ^:no-doc frames
  "The frames of `version` in `view` from `p` to `end`, read in the context
  `ctx`, until padding or the end. A frame that doesn't read ends them,
  and the report says so."
  [ctx view version p end]
  (let [header (if (= 2 version) 6 10)]
    (loop [p p acc []]
      (if (or (>= p end)
              (zero? (view/u8 view p))
              (> (+ p header) end))
        (do (when (and (< p end)
                       (not (plausible-end? view version p end)))
              (view/note! ctx (view/diagnostic :id3/padding-junk :info
                                               :offset p)))
            acc)
        (if-let [{:keys [id flags start stop]}
                 (frame-header ctx view version p end)]
          (let [{:keys [compressed? encrypted? unsync?]} flags]
            (if (or compressed? encrypted? (> start stop))
              (do (view/note! ctx (view/diagnostic :id3/skipped-frame :info
                                                   :frame id))
                  (recur stop acc))
              (let [data  (cond-> (view/slice view start stop)
                            unsync? resync)
                    frame (frame-body ctx data version id p)]
                (recur stop (cond-> acc frame (conj frame))))))
          (do (view/note! ctx (view/diagnostic :id3/malformed :warning
                                               :offset p))
              acc))))))

(defn- tag
  "The tag of the header `h` in `view`, read in the context `ctx`. A tag
  whose extended header doesn't read has no frames, and the report says
  so."
  [ctx view {:keys [version flags start end] :as h}]
  (view/need view start end)
  (let [footer?   (and (= 4 version) (bit-test flags 4))
        unsync?   (bit-test flags 7)
        body-end  (- end (if footer? 10 0))
        body      (cond-> (view/slice view (+ start 10) body-end)
                    (and (#{2 3} version) unsync?) resync)
        body      (view/whole body)
        extended  (fn []
                    (cond
                      (not (bit-test flags 6)) 0
                      (= 4 version)            (or (syncsafe body 0) 0)
                      (= 3 version)            (+ 4 (view/u32 body 0))
                      :else                    0))
        ctx       (assoc ctx :unsync-all? (and (= 4 version) unsync?))
        malformed (view/diagnostic :id3/malformed :warning :offset start)]
    (assoc (select-keys h [:version :start :end])
           :frames (try
                     (frames ctx body version (extended) (:total body))
                     (catch #?(:clj Exception :cljs :default) _
                       (view/note! ctx malformed)
                       [])))))

(defn report
  "The diagnostics of `x`, a read tag or chapters. Each diagnostic has its
  message."
  [x]
  (view/report x))

(defn read
  "The ID3v2 tag at `p` in `view`, read with `opts`, or nil when no tag
  begins there. It's a map of :version, :start, :end and :frames.

  The :except of `opts` is the ids of the quirks to leave out. The view
  must hold the whole tag. When it lacks bytes, the reading throws an
  exception that holds their ranges."
  ([view p]
   (read view p {}))
  ([view p opts]
   {:pre [(map? opts)]}
   (when-let [h (header view p)]
     (let [ctx    (view/context opts)
           result (tag ctx view h)]
       (view/reported result (view/noted ctx))))))

(defn read-all
  "The ID3v2 tags in `view` from `p` on, each read with `opts`, or an
  empty vector when no tag begins at `p`.

  Some writers add a tag in front of a tag that they leave in place. The
  quirk :id3/second-tag reads each tag that comes right after the tag
  before it."
  ([view p]
   (read-all view p {}))
  ([view p opts]
   (let [first-tag (read view p opts)
         more?     (not (contains? (set (:except opts)) :id3/second-tag))]
     (loop [acc (if first-tag [first-tag] []) p (:end first-tag)]
       (if-let [more (and p
                          more?
                          (try (read view p opts)
                               (catch #?(:clj Exception :cljs :default) e
                                 (when (view/missing e) (throw e))
                                 nil)))]
         (let [quirk (view/diagnostic :id3/second-tag :info :offset p)
               noted (vary-meta more update :podcast/report conj quirk)]
           (recur (conj acc noted) (:end more)))
         acc)))))

(defn ^:no-doc skip
  "Where the ID3v2 tags in a row from `p` in `view` end, or `p` when no
  tag begins there. It needs each whole tag with the ten bytes after it,
  as a reader of the tags would, so that they come at once."
  [view p]
  (let [total (or (:total view) ##Inf)]
    (loop [p p]
      (if-let [h (and (<= (+ p 10) total)
                      (= "ID3" (view/code view p 3))
                      (header view p))]
        (do (view/need view p (+ (:end h) 10))
            (recur (:end h)))
        p))))

(defn ^:no-doc frame
  "The first of `frames` with one of the `ids`."
  [frames & ids]
  (some #(when (some #{(:id %)} ids) %) frames))

(defn artwork
  "The picture in `frames` as a map of :type, :data and :description, or
  nil when they hold none. The front cover comes before each other
  picture."
  [frames]
  (let [pictures (filter #(and (#{"APIC" "PIC"} (:id %)) (:data %)) frames)
        front    (first (filter #(= 3 (:picture-type %)) pictures))]
    (some-> (or front (first pictures))
            (select-keys [:type :data :description]))))

;; ID3v2.4 native frames, section 4.2, with the TYER of ID3v2.3, the
;; three-letter ids of ID3v2.2, and the TDES of Apple Podcasts
(defn named
  "The tags of `frames` as one map of :title, :artist, :album, :date,
  :genre and :description, each absent when the frames don't have it. The
  description is that of a podcast's episode, or else the comment."
  [frames]
  (let [text    #(some-> (apply frame frames %&) :text str/trim not-empty)
        ;; iTunes writes comments such as iTunNORM with a description
        comment (some #(when (and (#{"COMM" "COM"} (:id %))
                                  (str/blank? (:description %)))
                         (some-> (:text %) str/trim not-empty))
                      frames)
        named   {:title       (text "TIT2" "TT2")
                 :artist      (text "TPE1" "TP1")
                 :album       (text "TALB" "TAL")
                 :date        (text "TDRC" "TYER" "TYE")
                 :genre       (text "TCON" "TCO")
                 :description (or (text "TDES") comment)}]
    (into {} (filter val) named)))

(defn duration
  "The duration of the audio in seconds, from the TLEN frame in `frames`,
  or nil when no TLEN frame gives a number. The frame gives the length in
  milliseconds."
  [frames]
  (when-let [s (:text (frame frames "TLEN" "TLE"))]
    (when (re-matches #"\s*\d{1,12}\s*" s)
      (/ (parse-long (str/trim s)) 1000.0))))

(defn- listed
  "The element ids that the table of contents `toc` lists, in order,
  through the tables it lists, which are found among `tocs` by id.

  Each table is followed once, however many tables list it, so no chain
  of tables or repeated listing can make the walk deep or long."
  [toc tocs]
  (loop [todo  (seq (:children toc))
         stack ()
         seen  #{(:element-id toc)}
         acc   []]
    (cond
      todo
      (let [[child & more] todo]
        (if-let [sub (and (not (seen child)) (get tocs child))]
          (recur (seq (:children sub)) (conj stack more) (conj seen child) acc)
          (recur more stack seen (conj acc child))))

      (seq stack)
      (recur (first stack) (rest stack) seen acc)

      :else
      acc)))

(defn- toc-places
  "The place of each element id in the top-level table of contents of
  `frames`, as a map of element id to index, or nil when the frames hold
  no table."
  [frames]
  (let [tocs (into {}
                   (comp (filter #(= "CTOC" (:id %)))
                         (map (juxt :element-id identity)))
                   frames)
        top  (or (some #(when (:top-level? %) %) (vals tocs))
                 (first (vals tocs)))]
    (when top
      (into {}
            (comp (distinct) (map-indexed (fn [i id] [id i])))
            (listed top tocs)))))

(defn- chapter-of
  "The chapter of the CHAP frame `chap`, read in the context `ctx`. It has
  :toc false when the `places` in the table of contents lack it."
  [ctx {:keys [element-id start-ms end-ms frames] :as chap} places]
  (let [title     (:text (frame frames "TIT2"))
        link?     #(and (re-matches #"W[A-Z0-9]{3}" (:id %))
                        (not (str/blank? (:url %))))
        url       (some #(when (link? %) (:url %)) frames)
        picture   (artwork frames)
        drop?     (and (<= end-ms start-ms)
                       (view/quirk? ctx :id3/chapter-end))
        unlisted? (and places (not (contains? places element-id)))]
    (when drop?
      (view/note! ctx (view/diagnostic :id3/chapter-end :info
                                       :element-id element-id)))
    (cond-> {:start-time (/ start-ms 1000.0)}
      (not drop?) (assoc :end-time (/ end-ms 1000.0))
      title       (assoc :title title)
      url         (assoc :url url)
      picture     (assoc :artwork (select-keys picture [:type :data]))
      unlisted?   (assoc :toc false))))

(defn chapters
  "The chapters in `frames`, from their CHAP and CTOC frames, read with
  `opts`, in the shape of dk.simongray.podcast.format.chapters.

  Each chapter is a map with :start-time and :end-time in seconds. It has
  the :title, the :url and the picture of the frames inside its frame, the
  picture as :artwork, a map of :type and :data. The top-level table of
  contents gives the order. A chapter that the table doesn't list comes
  after the others, by its start, with :toc false.

  The :except of `opts` is the ids of the quirks to leave out, and the
  report gives the quirks that applied."
  ([frames]
   (chapters frames {}))
  ([frames opts]
   (let [ctx    (view/context opts)
         places (toc-places frames)
         order  (fn [{:keys [element-id start-ms]}]
                  [(get places element-id ##Inf) start-ms])
         result (->> (filter #(= "CHAP" (:id %)) frames)
                     (sort-by order)
                     (mapv #(chapter-of ctx % places)))]
     (view/reported result (view/noted ctx)))))

(defn ^:no-doc read-front
  "Read the ID3v2 tags at the front of the file in `view` with `opts`, and
  what their frames say about the file.

  It's a map of the tags as :id3, where they :end, the :tags that named
  gives, and the :duration of a TLEN frame, the :chapters and the
  :artwork, each absent when the frames don't have it. Its report is that
  of the tags and of the chapters."
  [view opts]
  (let [tags    (read-all view 0 opts)
        frames  (mapcat :frames tags)
        seconds (duration frames)
        chs     (chapters frames opts)
        art     (artwork frames)]
    (view/reported
     (cond-> {:id3  tags
              :tags (named frames)
              :end  (or (:end (peek tags)) 0)}
       seconds   (assoc :duration seconds)
       (seq chs) (assoc :chapters chs)
       art       (assoc :artwork art))
     (concat (mapcat report tags) (report chs)))))

(comment
  ;; a tag of ID3v2.4 with a title, TIT2, and a length in milliseconds, TLEN
  (def sample
    (let [header [0x49 0x44 0x33 4 0 0 0 0 0 28]
          tit2   [0x54 0x49 0x54 0x32 0 0 0 3 0 0 3 0x48 0x69]
          tlen   [0x54 0x4C 0x45 0x4E 0 0 0 5 0 0 0 0x31 0x35 0x30 0x30]]
      (view/whole (bytes/of (concat header tit2 tlen)))))

  (header sample 0)
  (read sample 0)
  (report (read sample 0))
  (duration (:frames (read sample 0)))

  ;; a text encoding that ID3 does not define, with the quirk and without it
  (def loose
    (let [header [0x49 0x44 0x33 4 0 0 0 0 0 13]
          tit2   [0x54 0x49 0x54 0x32 0 0 0 3 0 0 9 0x48 0x69]]
      (view/whole (bytes/of (concat header tit2)))))

  (map :id (report (read loose 0)))
  (map :id (report (read loose 0 {:except #{:id3/text-encoding}})))
  #_.)
