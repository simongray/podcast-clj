(ns dk.simongray.podcast.format.id3
  "ID3v2 tags, the metadata at the start of an MP3 file: versions 2.2, 2.3
  and 2.4 as id3.org specifies them, and the chapters of the ID3v2 Chapter
  Frame Addendum. `read` gives the frames of the tag at an offset of a byte
  view (see dk.simongray.podcast.bytes), `read-all` those of the tags that
  follow each other there, and `chapters`, `artwork` and `length` what an
  app wants of the frames.

  A frame is a map with its `:id`: text frames with `:text`, TXXX, WXXX and
  COMM with a `:description` too, COMM with its `:language`, URL frames
  with `:url`, APIC and PIC with `:type`, `:picture-type`, `:description`
  and `:data`, CHAP with `:element-id`, `:start-ms`, `:end-ms` and the
  `:frames` inside it, CTOC with `:element-id`, `:top-level?`, `:ordered?`,
  `:children` and `:frames`, and any other frame with its `:size`.

  The reading is strict. Each accommodation for a tag that breaks the
  specification is a quirk with an id, which the report records when it
  applies and which `:except` in the options leaves out:

      :id3/frame-size   a frame size in the form of the other version
      :id3/utf8-latin1  text declared ISO-8859-1 whose bytes are UTF-8
      :id3/utf16-bom    UTF-16 text without its byte order mark
      :id3/image-type   the type of a picture loose, such as JPG, or missing
      :id3/chapter-end  a chapter end that is not after its start
      :id3/second-tag   a second tag right after the first"
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.bytes :as bytes]))

(defn- diagnostic
  [id level & {:as extra}]
  (merge {:id id :level level} extra))

(defn- note!
  "Record the diagnostic `d` in the report of the reading context `ctx`."
  [ctx d]
  (vswap! (:report ctx) conj d))

(defn- quirk?
  "Whether the reading context `ctx` makes the quirk `id`."
  [ctx id]
  (not (contains? (:except ctx) id)))

(defn- context
  "A reading context for `opts`, with an empty report."
  [{:keys [except]}]
  {:except (set except) :report (volatile! [])})

(defn- view-of
  "A view that holds all of the bytes `b`."
  [b]
  (bytes/add (bytes/view (bytes/length b)) 0 b (bytes/length b)))

(defn- syncsafe
  "The 28-bit number of the four syncsafe bytes at `p` in `view`; nil when a
  byte has its top bit set, as a syncsafe byte never has."
  [view p]
  (let [bs (map #(bytes/u8 view (+ p %)) (range 4))]
    (when (every? #(< % 0x80) bs)
      (reduce #(+ (* 128 %1) %2) 0 bs))))

(defn header
  "The ID3v2 tag header at `p` in `view`, `{:version :flags :start :end}`,
  `:end` being where the tag and its footer end; nil when no tag begins
  there."
  [view p]
  (bytes/need view p (+ p 10))
  (let [version (bytes/u8 view (+ p 3))
        flags   (bytes/u8 view (+ p 5))
        size    (syncsafe view (+ p 6))]
    (when (and (= "ID3" (bytes/code view p 3)) (#{2 3 4} version) (< (bytes/u8 view (+ p 4)) 0xFF) size)
      {:version version
       :flags   flags
       :start   p
       :end     (+ p 10 size (if (and (= 4 version) (bit-test flags 4)) 10 0))})))

(defn- resync
  "The bytes `b` with unsynchronisation undone: the zero byte after each
  0xFF removed, into bytes of the same size, of which as many as remain are
  kept."
  [b]
  (let [n   (bytes/length b)
        out #?(:clj (byte-array n) :cljs (js/Uint8Array. n))]
    (loop [i 0 j 0]
      (if (>= i n)
        (bytes/copy out 0 j)
        (let [x (bytes/at b i)]
          #?(:clj (aset-byte out j (unchecked-byte x)) :cljs (aset out j x))
          (recur (if (and (= 0xFF x) (< (inc i) n) (zero? (bytes/at b (inc i)))) (+ i 2) (inc i))
                 (inc j)))))))

(def ^:no-doc encodings
  "The text encoding of each encoding byte, and the width of its terminator."
  {0 [:latin1 1] 1 [:utf16 2] 2 [:utf16be 2] 3 [:utf8 1]})

(defn- terminator
  "Where the text in `view` from `start` ends, before `end`: at the first
  terminator of `width` zero bytes, aligned for UTF-16; `end` when there is
  none."
  [view start end width]
  (loop [p start]
    (cond
      (> (+ p width) end)                                     end
      (every? #(zero? (bytes/u8 view (+ p %))) (range width)) p
      :else                                                   (recur (+ p width)))))

(defn- utf16-order
  "The byte order of the UTF-16 text in `view` from `start` to `end`, and
  where the text starts, as `[encoding start]`: by its byte order mark,
  which is skipped; without one, big-endian, as Unicode has it, or, by the
  quirk, the order in which the zero bytes of plain letters fall."
  [ctx view start end]
  (let [a (when (< (inc start) end) (bytes/u8 view start))
        b (when (< (inc start) end) (bytes/u8 view (inc start)))]
    (cond
      (and (= 0xFF a) (= 0xFE b))   [:utf16le (+ start 2)]
      (and (= 0xFE a) (= 0xFF b))   [:utf16be (+ start 2)]
      (or (>= start end)
          (not (quirk? ctx :id3/utf16-bom))) [:utf16be start]
      :else
      (let [zeros #(count (filter (fn [p] (zero? (bytes/u8 view p))) (range (+ start %) end 2)))]
        (note! ctx (diagnostic :id3/utf16-bom :info))
        [(if (> (zeros 1) (zeros 0)) :utf16le :utf16be) start]))))

(defn- decoded
  "The text in `view` from `start` to `end` in the ID3 `encoding`."
  [ctx view start end encoding]
  (case encoding
    :latin1 (if (and (quirk? ctx :id3/utf8-latin1) (bytes/utf8? (bytes/slice view start end) 0 (- end start)))
              (do (note! ctx (diagnostic :id3/utf8-latin1 :info))
                  (bytes/text view start end :utf8))
              (bytes/text view start end :latin1))
    :utf16  (let [[order start] (utf16-order ctx view start end)]
              (bytes/text view start end order))
    (bytes/text view start end encoding)))

(defn- text-at
  "The text in `view` from `start`, in `encoding` with terminators of
  `width` bytes, up to its terminator or `end`, and where what follows it
  starts: `[text next]`."
  [ctx view start end encoding width]
  (let [stop (terminator view start end width)]
    [(decoded ctx view start stop encoding) (min end (+ stop width))]))

(defn- texts
  "The texts in `view` from `start` to `end`, each ended by its terminator
  but perhaps the last, the empty ones left out."
  [ctx view start end encoding width]
  (loop [p start acc []]
    (if (>= p end)
      acc
      (let [[s next] (text-at ctx view p end encoding width)]
        (recur (max next (inc p)) (cond-> acc (not (str/blank? s)) (conj s)))))))

(defn- image-type
  "The MIME type of a picture, `declared` in its frame: as declared when it
  is a MIME type, and, by the quirk, from the image `data` when it is
  loose, such as JPG or image/jpg, or missing."
  [ctx declared data]
  (let [t (str/lower-case (str/trim (str declared)))]
    (if-let [sniffed (and (not (and (re-matches #"image/[a-z0-9.+-]+" t) (not= "image/jpg" t)))
                          (quirk? ctx :id3/image-type)
                          (bytes/image-type data))]
      (do (note! ctx (diagnostic :id3/image-type :info :declared declared))
          sniffed)
      (if (str/blank? t) declared t))))

(declare frames)

(defn- frame-body
  "The frame `id` of `version` whose data is all of `view`, read by its
  kind; the reading throws when the data is too short for its kind."
  [ctx view version id]
  (let [end              (:total view)
        [encoding width] (when (pos? end) (get encodings (bytes/u8 view 0) [:latin1 1]))]
    (cond
      (#{"TXXX" "TXX"} id)
      (let [[description next] (text-at ctx view 1 end encoding width)]
        {:id id :description description :text (first (text-at ctx view next end encoding width))})

      (#{"WXXX" "WXX"} id)
      (let [[description next] (text-at ctx view 1 end encoding width)]
        {:id id :description description :url (str/trim (first (text-at ctx view next end :latin1 1)))})

      (#{"COMM" "COM" "USLT" "ULT"} id)
      (let [[description next] (text-at ctx view 4 end encoding width)]
        {:id id :language (bytes/code view 1 3) :description description
         :text (first (text-at ctx view next end encoding width))})

      (#{"APIC" "PIC"} id)
      (let [[declared next] (if (= "PIC" id)
                              (let [image-format (bytes/code view 1 3)
                                    mime         {"JPG" "image/jpeg" "PNG" "image/png"}]
                                [(get mime (str/upper-case image-format) image-format) 4])
                              (text-at ctx view 1 end :latin1 1))
            picture-type    (bytes/u8 view next)
            [description p] (text-at ctx view (inc next) end encoding width)]
        (if (= "-->" declared)
          {:id id :picture-type picture-type :description description :url (bytes/text view p end :latin1)}
          (let [data (bytes/slice view p end)]
            {:id id :type (image-type ctx declared data) :picture-type picture-type
             :description description :data data})))

      ;; the frames inside a CHAP or CTOC frame are its title and the like;
      ;; one inside those is not read as a chapter again, so that nesting
      ;; cannot run deeper than one level
      (and (= "CHAP" id) (not (:embedded? ctx)))
      (let [stop (terminator view 0 end 1)
            p    (inc stop)]
        {:id         id
         :element-id (bytes/text view 0 stop :latin1)
         :start-ms   (bytes/u32 view p)
         :end-ms     (bytes/u32 view (+ p 4))
         :frames     (frames (assoc ctx :embedded? true) view version (+ p 16) end)})

      (and (= "CTOC" id) (not (:embedded? ctx)))
      (let [stop  (terminator view 0 end 1)
            flags (bytes/u8 view (inc stop))
            n     (bytes/u8 view (+ stop 2))
            [children p] (loop [p (+ stop 3) acc []]
                           (if (= n (count acc))
                             [acc p]
                             (let [s (terminator view p end 1)]
                               (when (>= p end)
                                 (throw (ex-info "A table of contents with fewer children than it counts" {})))
                               (recur (inc s) (conj acc (bytes/text view p s :latin1))))))]
        {:id         id
         :element-id (bytes/text view 0 stop :latin1)
         :top-level? (bit-test flags 1)
         :ordered?   (bit-test flags 0)
         :children   children
         :frames     (frames (assoc ctx :embedded? true) view version p end)})

      (re-matches #"T[A-Z0-9]{2,3}" id)
      (let [values (texts ctx view 1 end encoding width)]
        (cond-> {:id id :text (first values)}
          (next values) (assoc :values values)))

      (re-matches #"W[A-Z0-9]{2,3}" id)
      {:id id :url (str/trim (first (text-at ctx view 0 end :latin1 1)))}

      :else
      {:id id :size end})))

(defn- frame-id?
  "Whether `s` is the id of a frame of `version`: three capitals or digits
  for 2.2, four for 2.3 and 2.4."
  [version s]
  (boolean (re-matches (if (= 2 version) #"[A-Z0-9]{3}" #"[A-Z0-9]{4}") s)))

(defn- plausible-end?
  "Whether a frame that ends at `p` in `view` is followed by what may follow
  a frame before `end`: the end, the header of another frame, or padding,
  which is zeros to the end of the tag, of which the first kilobyte is
  looked at."
  [view version p end]
  (or (= p end)
      (and (<= (+ p 10) end) (frame-id? version (bytes/code view p 4)))
      (and (< p end) (every? #(zero? (bytes/u8 view %)) (range p (min end (+ p 1024)))))))

(defn- frame-size
  "The size of the frame of `version` 2.3 or 2.4 whose header is at `p`:
  plain for 2.3 and syncsafe for 2.4. When that size leads to no place a
  frame can end and the other form does, the quirk takes the other form,
  which some writers use; nil when no size fits within `end`."
  [ctx view version p end]
  (let [plain       (bytes/u32 view (+ p 4))
        safe        (syncsafe view (+ p 4))
        [own other] (if (= 4 version) [safe plain] [plain safe])
        fits?       #(and % (plausible-end? view version (+ p 10 %) end))]
    (cond
      (fits? own)                                      own
      (and (quirk? ctx :id3/frame-size) (fits? other)) (do (note! ctx (diagnostic :id3/frame-size :info))
                                                           other)
      (and own (<= (+ p 10 own) end))                  own)))

(defn- frames
  "The frames of `version` in `view` from `p` to `end`, until padding or the
  end. A frame that does not read ends them, and the report says so."
  [ctx view version p end]
  (let [header (if (= 2 version) 6 10)]
    (loop [p p acc []]
      (if (or (>= p end) (zero? (bytes/u8 view p)) (> (+ p header) end))
        (do (when (and (< p end) (not (plausible-end? view version p end)))
              (note! ctx (diagnostic :id3/padding-junk :info :offset p)))
            acc)
        (let [id   (bytes/code view p (if (= 2 version) 3 4))
              size (when (frame-id? version id)
                     (if (= 2 version) (bytes/u24 view (+ p 3)) (frame-size ctx view version p end)))]
          (if (or (nil? size) (> (+ p header size) end))
            (do (note! ctx (diagnostic :id3/malformed :warning :offset p)) acc)
            (let [flags (if (= 2 version) 0 (bytes/u8 view (+ p 9)))
                  [compressed? encrypted? grouped? unsync? length?]
                  (case version
                    4 [(bit-test flags 3) (bit-test flags 2) (bit-test flags 6)
                       (or (bit-test flags 1) (:unsync-all? ctx)) (bit-test flags 0)]
                    3 [(bit-test flags 7) (bit-test flags 6) (bit-test flags 5) false false]
                    [false false false false false])
                  start (+ p header (if grouped? 1 0) (if encrypted? 1 0) (if length? 4 0)
                           (if (and compressed? (= 3 version)) 4 0))
                  stop  (+ p header size)]
              (if (or compressed? encrypted? (> start stop))
                (do (note! ctx (diagnostic :id3/skipped-frame :info :frame id))
                    (recur stop acc))
                (let [data  (cond-> (bytes/slice view start stop) unsync? resync)
                      frame (try
                              (frame-body ctx (view-of data) version id)
                              (catch #?(:clj Exception :cljs :default) _
                                (note! ctx (diagnostic :id3/malformed :warning :offset p :frame id))
                                nil))]
                  (recur stop (cond-> acc frame (conj frame))))))))))))

(defn- tag
  "The tag of the header `h` in `view`, read in the context `ctx`. A tag
  whose extended header does not read has no frames, and the report says
  so."
  [ctx view {:keys [version flags start end] :as h}]
  (bytes/need view start end)
  (let [body-end (- end (if (and (= 4 version) (bit-test flags 4)) 10 0))
        body     (cond-> (bytes/slice view (+ start 10) body-end)
                   (and (#{2 3} version) (bit-test flags 7)) resync)
        body     (view-of body)
        extended (fn []
                   (cond
                     (not (bit-test flags 6)) 0
                     (= 4 version)            (or (syncsafe body 0) 0)
                     (= 3 version)            (+ 4 (bytes/u32 body 0))
                     :else                    0))
        ctx      (assoc ctx :unsync-all? (and (= 4 version) (bit-test flags 7)))]
    (assoc (select-keys h [:version :start :end])
           :frames (try
                     (frames ctx body version (extended) (:total body))
                     (catch #?(:clj Exception :cljs :default) _
                       (note! ctx (diagnostic :id3/malformed :warning :offset start))
                       [])))))

(defn read
  "The ID3v2 tag at `p` in `view`, `{:version :start :end :frames}`, with
  the diagnostics of the reading under `:report`; nil when no tag begins
  there. `opts` may give `:except`, the ids of the quirks to leave out. The
  view must hold the whole tag, or the reading throws for the bytes it
  lacks (see `dk.simongray.podcast.bytes/missing`)."
  ([view p]
   (read view p {}))
  ([view p opts]
   {:pre [(map? opts)]}
   (when-let [h (header view p)]
     (let [ctx (context opts)]
       (assoc (tag ctx view h) :report @(:report ctx))))))

(defn read-all
  "The ID3v2 tag at `p` in `view`, as `read` reads it, and, by the quirk
  `:id3/second-tag`, the tags that follow it at once, which some writers
  add in front of a tag they leave in place: `[tag ...]`, empty when no
  tag begins at `p`."
  ([view p]
   (read-all view p {}))
  ([view p opts]
   (let [first-tag (read view p opts)
         more?     (not (contains? (set (:except opts)) :id3/second-tag))]
     (loop [acc (if first-tag [first-tag] []) p (:end first-tag)]
       (if-let [more (and p more?
                          (try (read view p opts)
                               (catch #?(:clj Exception :cljs :default) e
                                 (when (bytes/missing e) (throw e))
                                 nil)))]
         (recur (conj acc (update more :report conj (diagnostic :id3/second-tag :info :offset p))) (:end more))
         acc)))))

(defn- frame
  "The first of `frames` with one of the `ids`."
  [frames & ids]
  (some #(when (some #{(:id %)} ids) %) frames))

(defn artwork
  "The picture of the frames `frames`, the front cover before any other, as
  `{:type :data :description}`; nil when there is none."
  [frames]
  (let [pictures (filter #(and (#{"APIC" "PIC"} (:id %)) (:data %)) frames)]
    (some-> (or (first (filter #(= 3 (:picture-type %)) pictures)) (first pictures))
            (select-keys [:type :data :description]))))

(defn length
  "The length of the audio in seconds that the TLEN frame of `frames` gives
  in milliseconds; nil when it gives none that reads."
  [frames]
  (when-let [s (:text (frame frames "TLEN" "TLE"))]
    (when (re-matches #"\s*\d{1,12}\s*" s)
      (/ (parse-long (str/trim s)) 1000.0))))

(defn- listed
  "The element ids that the table of contents `toc` lists, through the
  tables it lists, found among `tocs` by id, in order. Each table is
  followed once, however many tables list it, so neither a long chain of
  tables nor tables that list the same ones can make the walk deep or
  long."
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

(defn chapters
  "The chapters of the frames `frames`, from their CHAP and CTOC frames, as
  `dk.simongray.podcast.format.chapters` has chapters: maps with
  `:start-time` and `:end-time` in seconds, and the `:title`, `:url` and
  `:img` of the frames inside each, the image as a data URL. The top-level
  table of contents gives the order; a chapter it does not list comes by
  its start after those it does, with `:toc false`. `opts` may give
  `:except`, and `chapters-report` gives the quirks applied."
  ([frames]
   (chapters frames {}))
  ([frames opts]
   (let [ctx    (context opts)
         tocs   (into {} (map (juxt :element-id identity)) (filter #(= "CTOC" (:id %)) frames))
         top    (or (some #(when (:top-level? %) %) (vals tocs)) (first (vals tocs)))
         rank   (into {} (comp (distinct) (map-indexed (fn [i id] [id i]))) (when top (listed top tocs)))
         ->ch   (fn [{:keys [element-id start-ms end-ms frames]}]
                  (let [title   (:text (frame frames "TIT2"))
                        url     (some #(when (and (re-matches #"W[A-Z0-9]{3}" (:id %)) (not (str/blank? (:url %))))
                                         (:url %))
                                      frames)
                        picture (artwork frames)
                        drop?   (and (<= end-ms start-ms) (quirk? ctx :id3/chapter-end))]
                    (when drop?
                      (note! ctx (diagnostic :id3/chapter-end :info :element-id element-id)))
                    (cond-> {:start-time (/ start-ms 1000.0)}
                      (not drop?)                                 (assoc :end-time (/ end-ms 1000.0))
                      title                                       (assoc :title title)
                      url                                         (assoc :url url)
                      picture                                     (assoc :img (bytes/data-url (:type picture) (:data picture)))
                      (and top (not (contains? rank element-id))) (assoc :toc false))))]
     (with-meta (->> (filter #(= "CHAP" (:id %)) frames)
                     (sort-by (fn [{:keys [element-id start-ms]}] [(get rank element-id ##Inf) start-ms]))
                     (mapv ->ch))
       {::report @(:report ctx)}))))

(defn chapters-report
  "The quirks that `chapters` applied to give `result`."
  [result]
  (::report (meta result)))
