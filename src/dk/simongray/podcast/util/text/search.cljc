(ns dk.simongray.podcast.util.text.search
  "A full-text index of documents, and queries of it ranked by BM25F, on
  both platforms.

  A document is a map of its :id, its :fields, and what a result gives
  back, under :stored. A field holds text, or anything whose text counts,
  such as a number or a set of keywords. To search the records of an app,
  make a document of each:

      (defn document
        \"The search document of the blog post `post`.\"
        [post]
        {:id     (:slug post)
         :fields (select-keys post [:title :body :tags])
         :stored (select-keys post [:title])})

      (def idx (index (map document posts) {:boosts {:title 3}}))
      (query idx \"title:clojure -rust\" {:limit 10})
      ;; => [{:id \"clojure-data\" :score 0.51 :stored {:title \"…\"}}]
      (snippet (:body post) \"clojure\" {:index idx})

  The index is a value: add and remove give a changed one, e.g. (add idx
  (document post)) when a post changes, and it prints as EDN, which
  restore reads back. A query is a string in the language that parse
  reads, or the same query as data.

  Text is analyzed by util.text. The comments name the source of each
  part, and where it departs from it:

  - Robertson and Zaragoza, The Probabilistic Relevance Framework: BM25
    and Beyond, Foundations and Trends in Information Retrieval 3(4),
    2009, https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf
  - Lucene's BM25Similarity and BooleanQuery, and the segments of its
    index, https://lucene.apache.org/core/9_11_1/core/
  - Elasticsearch's simple_query_string,
    https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-simple-query-string-query
  - SQLite FTS5's query syntax and its snippet function,
    https://www.sqlite.org/fts5.html"
  (:refer-clojure :exclude [remove])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.text :as text])
  #?(:clj (:import [java.io Writer]
                   [java.util Arrays])))

(def ^{:no-doc true :const true} span
  "The block of positions that each field of a document gets.

  Each position of a term in a document is offset by its field's number
  times this. Two positions one apart are then always in the same field.
  A longer field is indexed up to this length."
  1000000)

(defn- make-ints
  "A new array of `n` ints, all 0."
  [n]
  #?(:clj  (int-array n)
     :cljs (js/Int32Array. n)))

(defn- make-doubles
  "A new array of `n` doubles, all 0."
  [n]
  #?(:clj  (double-array n)
     :cljs (js/Float64Array. n)))

(defn- as-ints
  "The ints `xs`, a vector or an array, as an array."
  [xs]
  (if (vector? xs)
    #?(:clj  (int-array xs)
       :cljs (js/Int32Array. (into-array xs)))
    xs))

(defn- as-doubles
  "The numbers of the vector `xs` as an array of doubles."
  [xs]
  #?(:clj  (double-array xs)
     :cljs (js/Float64Array. (into-array xs))))

(defn- as-objects
  "The values `xs`, a vector or an array, as an array."
  [xs]
  (if (vector? xs)
    (to-array xs)
    xs))

(defn- as-vector
  "The values of the array `arr` as a vector."
  [arr]
  #?(:clj  (vec arr)
     :cljs (vec (js/Array.from arr))))

(defn- slice
  "The ints of the array `arr` from `from` to `to`, as an array of their
  own."
  [arr from to]
  #?(:clj  (Arrays/copyOfRange ^ints arr (int from) (int to))
     :cljs (.slice arr from to)))

(defn- trimmed-doubles
  "The first `n` doubles of the array `arr`, as an array of their own."
  [arr n]
  #?(:clj  (Arrays/copyOf ^doubles arr (int n))
     :cljs (.slice arr 0 n)))

(defn- copy-ints!
  "Copy `n` ints of the array `src` from `from` into the array `dst` at
  `to`."
  [src from dst to n]
  #?(:clj  (System/arraycopy src (int from) dst (int to) (int n))
     :cljs (.set dst (.subarray src from (+ from n)) to)))

(defn- lower-bound
  "The first index from `from` to `to` of the sorted ints `arr` that holds
  at least `x`, or `to`."
  ^long [^ints arr ^long from ^long to ^long x]
  (loop [lo from hi to]
    (if (< lo hi)
      (let [mid (quot (+ lo hi) 2)]
        (if (< (aget arr mid) x)
          (recur (inc mid) hi)
          (recur lo mid)))
      lo)))

;; Exponential search, Bentley and Yao 1976, so that a short list of
;; documents walks through a long one in few steps
(defn- seek
  "The first index from `from` to `to` of the sorted ints `arr` that holds
  at least `x`, or `to`, found in steps that double from `from`."
  ^long [^ints arr ^long from ^long to ^long x]
  (loop [lo from step 1]
    (let [hi (+ lo step)]
      (if (and (< hi to)
               (< (aget arr hi) x))
        (recur hi (* 2 step))
        (lower-bound arr lo (min hi to) x)))))

(defn- term-bound
  "The first index of the sorted array `terms` whose term is at least
  `term`, or the count of `terms`."
  ^long [terms term]
  (let [^objects terms terms]
    (loop [lo 0 hi (alength terms)]
      (if (< lo hi)
        (let [mid (quot (+ lo hi) 2)]
          (if (neg? (compare (aget terms mid) term))
            (recur (inc mid) hi)
            (recur lo mid)))
        lo))))

(defn- term-number
  "The index of `term` in the sorted array `terms`, or -1."
  ^long [terms term]
  (let [^objects terms terms
        i              (term-bound terms term)]
    (if (and (< i (alength terms))
             (= term (aget terms i)))
      i
      -1)))

;; A segment holds the postings of some of the documents of an index in
;; sorted arrays of ints, as a segment of Lucene's index does, which take
;; far less memory than maps:
;;
;; - ids and stored, those of each document by its number
;; - terms, in order, and doc-start, where the postings of each start in
;;   docs, with the end of the last
;; - docs, the document of each posting, in order for each term, and
;;   pos-start, where its positions start in positions, with the end of
;;   the last
;; - positions, those of each posting in order, offset as span says
;; - lengths, the positions of each field of each document, width of them
;;   to a document, and field-stats, the documents with each field and
;;   their positions in all, by field number
;; - deleted, the numbers of the documents that are removed
(declare segment-data)

(deftype Segment [ids stored terms doc-start docs pos-start positions lengths
                  ^long width field-stats deleted]
  #?@(:clj
      [Object
       (equals [this other]
         (and (instance? Segment other)
              (= (segment-data this) (segment-data other))))
       (hashCode [_]
         (hash [ids deleted]))]

      :cljs
      [IEquiv
       (-equiv [this other]
         (and (instance? Segment other)
              (= (segment-data this) (segment-data other))))

       IHash
       (-hash [_]
         (hash [ids deleted]))

       IPrintWithWriter
       (-pr-writer [this writer opts]
         (-pr-writer (segment-data this) writer opts))]))

#?(:clj
   (defmethod print-method Segment
     [segment ^Writer writer]
     (print-method (segment-data segment) writer)))

(defn- parts
  "The fields of `segment` as a map."
  [^Segment segment]
  {:ids         (.-ids segment)
   :stored      (.-stored segment)
   :terms       (.-terms segment)
   :doc-start   (.-doc-start segment)
   :docs        (.-docs segment)
   :pos-start   (.-pos-start segment)
   :positions   (.-positions segment)
   :lengths     (.-lengths segment)
   :field-stats (.-field-stats segment)
   :deleted     (.-deleted segment)})

(defn- segment-data
  "The `segment` as plain data, a map of its fields with its arrays as
  vectors, as it prints."
  [segment]
  (reduce #(update %1 %2 as-vector)
          (parts segment)
          [:terms :doc-start :docs :pos-start :positions :lengths]))

(defn- segment-of
  "The segment of `m`, a map of its fields, whose arrays can be vectors, as
  segment-data gives them."
  [{:keys [ids stored terms doc-start docs pos-start positions lengths
           field-stats deleted]
    :as   m}]
  (->Segment (vec ids)
             (vec stored)
             (as-objects terms)
             (as-ints doc-start)
             (as-ints docs)
             (as-ints pos-start)
             (as-ints positions)
             (as-ints lengths)
             (count field-stats)
             (vec field-stats)
             (set deleted)))

(defn- size
  "The number of documents in `segment`, those removed too."
  [^Segment segment]
  (count (.-ids segment)))

(defn- live
  "The number of documents in `segment` that aren't removed."
  [^Segment segment]
  (- (size segment) (count (.-deleted segment))))

(defn- with-removed
  "The `segment` with the documents numbered `nos` removed."
  [segment nos]
  (segment-of (update (parts segment) :deleted into nos)))

(defn- analyze
  "The terms of the field value `v` by position: a string tokenized, a
  vector taken as terms already, the text of each value of any other
  collection tokenized, or else the text of `v`, e.g. of a number or a
  keyword."
  [v]
  (cond
    (string? v) (text/tokens v)
    (vector? v) v
    (coll? v)   (into [] (mapcat #(text/tokens (str %))) v)
    :else       (text/tokens (str v))))

(defn- numbered
  "The `numbers` of the fields of an index, a map of field to number, with
  a number for each new field of `analyzed`, in order of appearance."
  [numbers analyzed]
  (reduce (fn [numbers field]
            (cond
              (contains? numbers field)
              numbers

              ;; the positions of a field start at its number times span,
              ;; in an int
              (< (count numbers) (quot 2147483647 span))
              (assoc numbers field (count numbers))

              :else
              (throw (ex-info "An index holds no more fields"
                              {:field field}))))
          numbers
          (keys analyzed)))

(defn- placed
  "The transient map `acc` of terms to positions with `term` at `at`. A
  term has one position, or a vector of them."
  [acc term at]
  (let [seen (get acc term)]
    (assoc! acc term (cond
                       (nil? seen)    at
                       (vector? seen) (conj seen at)
                       :else          [seen at]))))

(defn- positions
  "The positions of each term in the `analyzed` fields, by term, as one
  position or a vector of them in order. Each is offset into the block of
  its field by the field `numbers`, and the terms of a vector share a
  position."
  [numbers analyzed]
  (persistent!
   (reduce (fn [acc field]
             (let [terms (get analyzed field)
                   base  (long (* span (get numbers field)))
                   n     (min span (count terms))]
               (loop [i 0 acc acc]
                 (if (< i n)
                   (let [term (nth terms i)
                         at   (+ base i)
                         acc  (if (vector? term)
                                (reduce #(placed %1 %2 at) acc term)
                                (placed acc term at))]
                     (recur (inc i) acc))
                   acc))))
           (transient {})
           (sort-by numbers (keys analyzed)))))

(defn- analyzed-fields
  "The terms by position of each of the `fields` of a document that has
  any, as analyze gives them."
  [fields]
  (into {}
        (keep (fn [[field v]]
                (let [terms (analyze v)]
                  (when (seq terms)
                    [field terms]))))
        fields))

(defn- posted
  "The transient map `acc` of each term to a transient vector of its
  postings, with the document numbered `no` at the positions `at` of
  `term`."
  [acc no term at]
  (let [postings (or (get acc term) (transient []))]
    (assoc! acc term (-> postings (conj! no) (conj! at)))))

(defn- postings-of
  "The postings of the analyzed documents `entries`, numbered in order,
  whose fields have the `numbers` of an index: a map of each term to a
  vector of the numbers of its documents, each followed by its positions
  there, as positions gives them."
  [numbers entries]
  (let [postings (reduce-kv (fn [acc no {:keys [fields]}]
                              (reduce-kv #(posted %1 no %2 %3)
                                         acc
                                         (positions numbers fields)))
                            (transient {})
                            entries)]
    (update-vals (persistent! postings) persistent!)))

(defn- fill!
  "Fill the arrays `doc-start`, `docs`, `pos-start` and `positions` of a
  segment with the postings `lists` of its terms in order, each a vector
  of the numbers of documents, each followed by its positions there."
  [^ints doc-start ^ints docs ^ints pos-start ^ints positions lists]
  (loop [t 0 i 0 k 0 p 0]
    (cond
      (= t (count lists))
      (aset pos-start k (int p))

      (= i (count (nth lists t)))
      (do (aset doc-start (inc t) (int k))
          (recur (inc t) 0 k p))

      :else
      (let [postings (nth lists t)
            at       (nth postings (inc i))
            ats      (if (vector? at) at [at])]
        (aset docs k (int (nth postings i)))
        (aset pos-start k (int p))
        (dotimes [j (count ats)]
          (aset positions (+ p j) (int (nth ats j))))
        (recur t (+ i 2) (inc k) (+ p (count ats)))))))

(defn- field-stats
  "The number of documents with each field and their positions in all, as
  pairs by field number, from the `lengths` of `n` documents of `width`
  fields each."
  [^ints lengths n width]
  (let [n     (long n)
        width (long width)]
    (mapv (fn [no]
            (loop [d 0 docs 0 length 0]
              (if (< d n)
                (let [l (aget lengths (+ (* d width) (long no)))]
                  (recur (inc d)
                         (if (pos? l) (inc docs) docs)
                         (+ length l)))
                [docs length])))
          (range width))))

;; A field's length counts its positions, so a term that shares one adds
;; nothing, as discountOverlaps does in Lucene's BM25Similarity
(defn- built
  "A segment of the analyzed documents `entries`, maps of :id, :stored and
  :fields as analyzed-fields gives them, whose fields have the `numbers`
  of an index."
  [numbers entries]
  (let [postings      (postings-of numbers entries)
        terms         (to-array (sort (keys postings)))
        lists         (mapv #(get postings %) terms)
        n             (count entries)
        width         (count numbers)
        positions-of  (fn [postings]
                        (transduce (comp (drop 1)
                                         (take-nth 2)
                                         (map #(if (vector? %) (count %) 1)))
                                   +
                                   postings))
        n-postings    (reduce + 0 (map #(quot (count %) 2) lists))
        ^ints lengths (make-ints (* n width))
        doc-start     (make-ints (inc (count lists)))
        docs          (make-ints n-postings)
        pos-start     (make-ints (inc n-postings))
        positions     (make-ints (reduce + 0 (map positions-of lists)))]
    (fill! doc-start docs pos-start positions lists)
    (doseq [[no {:keys [fields]}] (map-indexed vector entries)
            [field terms]         fields]
      (aset lengths (+ (* no width) (get numbers field)) (int (count terms))))
    (segment-of {:ids         (mapv :id entries)
                 :stored      (mapv :stored entries)
                 :terms       terms
                 :doc-start   doc-start
                 :docs        docs
                 :pos-start   pos-start
                 :positions   positions
                 :lengths     lengths
                 :field-stats (field-stats lengths n width)
                 :deleted     #{}})))

(defn- renumbered
  "For each of the `segments`, an array of the number of each of its
  documents in one segment of them all in order, without those removed,
  which get -1."
  [segments]
  (first
   (reduce (fn [[arrays base] ^Segment segment]
             (let [n             (long (size segment))
                   deleted       (.-deleted segment)
                   ^ints numbers (make-ints n)
                   next-base     (loop [d 0 j (long base)]
                                   (cond
                                     (= d n)
                                     j

                                     (contains? deleted d)
                                     (do (aset numbers d -1)
                                         (recur (inc d) j))

                                     :else
                                     (do (aset numbers d (int j))
                                         (recur (inc d) (inc j)))))]
               [(conj arrays numbers) next-base]))
           [[] 0]
           segments)))

(defn- without-removed
  "The values `vs` of the documents of a segment by number, without those
  of the numbers in `deleted`."
  [vs deleted]
  (if (empty? deleted)
    vs
    (into []
          (comp (map-indexed vector)
                (clojure.core/remove #(contains? deleted (first %)))
                (map second))
          vs)))

(defn- copy-postings!
  "Copy the postings of `segment` from `from` to `to` of the documents that
  `numbers` keep into the arrays `docs`, `pos-start` and `positions` of a
  merged segment, from the posting `k` and the position `p` on, and give
  where the copies end, as a pair."
  [^Segment segment ^ints numbers from to
   ^ints docs ^ints pos-start ^ints positions k p]
  (let [^ints old-docs      (.-docs segment)
        ^ints old-pos-start (.-pos-start segment)
        old-positions       (.-positions segment)
        to                  (long to)]
    (loop [i (long from) k (long k) p (long p)]
      (if (< i to)
        (let [d (aget numbers (aget old-docs i))]
          (if (neg? d)
            (recur (inc i) k p)
            (let [start (aget old-pos-start i)
                  n     (- (aget old-pos-start (inc i)) start)]
              (aset docs k d)
              (aset pos-start k (int p))
              (copy-ints! old-positions start positions p n)
              (recur (inc i) (inc k) (+ p n)))))
        [k p]))))

(defn- least-term
  "The least of the terms of the `segments` at their `cursors`, an array of
  the index of the next term of each, or nil when none is left."
  [segments ^ints cursors]
  (reduce (fn [least i]
            (let [^objects terms (.-terms ^Segment (nth segments i))
                  c              (aget cursors i)]
              (if (< c (alength terms))
                (let [term (aget terms c)]
                  (if (or (nil? least)
                          (neg? (compare term least)))
                    term
                    least))
                least)))
          nil
          (range (count segments))))

(defn- combined
  "One segment of the `segments` in order, without their documents that
  are removed."
  [segments]
  (let [segments       (vec segments)
        numbers        (renumbered segments)
        width          (reduce max 0 (map #(.-width ^Segment %) segments))
        values         (fn [f]
                         (into []
                               (mapcat #(without-removed
                                         (f %)
                                         (.-deleted ^Segment %)))
                               segments))
        ids            (values #(.-ids ^Segment %))
        ^ints lengths  (make-ints (* (count ids) width))
        total          (fn [f]
                         (reduce + 0 (map #(alength ^ints (f %)) segments)))
        ^ints docs     (make-ints (total #(.-docs ^Segment %)))
        ^ints starts   (make-ints (inc (alength docs)))
        positions      (make-ints (total #(.-positions ^Segment %)))
        ^ints cursors  (make-ints (count segments))
        copy-term!     (fn [term [k p] i]
                         (let [^Segment segment (nth segments i)
                               ^objects terms   (.-terms segment)
                               ^ints doc-start  (.-doc-start segment)
                               c                (aget cursors i)]
                           (if (and (< c (alength terms))
                                    (= term (aget terms c)))
                             (do (aset cursors i (inc c))
                                 (copy-postings! segment (nth numbers i)
                                                 (aget doc-start c)
                                                 (aget doc-start (inc c))
                                                 docs starts positions k p))
                             [k p])))]
    (doseq [[^Segment segment ^ints nos] (map vector segments numbers)
            :let  [w (.-width segment)]
            d     (range (size segment))
            :let  [j (aget nos d)]
            :when (<= 0 j)]
      (copy-ints! (.-lengths segment) (* d w) lengths (* j width) w))
    (loop [k 0 p 0 terms (transient []) doc-start (transient [0])]
      (if-let [term (least-term segments cursors)]
        (let [[next-k next-p] (reduce #(copy-term! term %1 %2)
                                      [k p]
                                      (range (count segments)))]
          ;; a term whose documents are all removed goes
          (if (= next-k k)
            (recur k p terms doc-start)
            (recur (long next-k)
                   (long next-p)
                   (conj! terms term)
                   (conj! doc-start next-k))))
        (do (aset starts k (int p))
            (segment-of {:ids         ids
                         :stored      (values #(.-stored ^Segment %))
                         :terms       (persistent! terms)
                         :doc-start   (persistent! doc-start)
                         :docs        (slice docs 0 k)
                         :pos-start   (slice starts 0 (inc k))
                         :positions   (slice positions 0 p)
                         :lengths     lengths
                         :field-stats (field-stats lengths (count ids) width)
                         :deleted     #{}}))))))

(defn- located
  "The `docs` of an index, a map of each id to the index of its segment and
  its number there, with those of the `segments` from the one at `i` on."
  [docs segments i]
  (persistent!
   (reduce (fn [docs i]
             (let [^Segment segment (nth segments i)
                   deleted          (.-deleted segment)]
               (reduce-kv (fn [docs no id]
                            (if (contains? deleted no)
                              docs
                              (assoc! docs id [i no])))
                          docs
                          (.-ids segment))))
           (transient docs)
           (range i (count segments)))))

(defn- with-segments
  "The `index` with the `segments`, and its documents in them found again
  from the segment at `i` on."
  [index segments i]
  (assoc index
         :segments segments
         :docs     (located (:docs index) segments i)))

(defn- merged-from
  "The `index` with its segments from the one at `i` on merged into one."
  [index i]
  (let [segments (:segments index)]
    (with-segments index
                   (conj (subvec segments 0 i) (combined (subvec segments i)))
                   i)))

;; Each document is copied about once for each doubling of the documents
;; after it, and an index keeps a few segments, each larger than those
;; after it
(defn- balanced
  "The `index` with its last two segments merged while the last holds at
  least half the documents of the one before it."
  [index]
  (let [segments (:segments index)
        n        (count segments)]
    (if (and (<= 2 n)
             (<= (live (nth segments (- n 2)))
                 (* 2 (live (peek segments)))))
      (recur (merged-from index (- n 2)))
      index)))

(defn- compacted
  "The `index` with all its segments merged into one."
  [index]
  (if (< 1 (count (:segments index)))
    (merged-from index 0)
    index))

(defn- pruned
  "The `index` with each segment that holds more removed documents than
  others written again without them, or left out when it holds no others."
  [index]
  (let [segments (:segments index)
        sparse?  #(< (live %) (count (.-deleted ^Segment %)))
        i        (first (keep-indexed #(when (sparse? %2) %1) segments))]
    (if (nil? i)
      index
      (with-segments index
                     (into (subvec segments 0 i)
                           (keep #(cond
                                    (not (sparse? %)) %
                                    (pos? (live %))   (combined [%])
                                    :else             nil))
                           (subvec segments i))
                     i))))

;; A removed document stays in its segment, marked, until a merge leaves
;; it out, as in Lucene
(defn- removed
  "The `index` without the documents of `ids`."
  [index ids]
  (let [found (keep #(find (:docs index) %) (distinct ids))]
    (if (empty? found)
      index
      (let [by-segment (group-by #(first (val %)) found)
            segments   (reduce-kv (fn [segments i found]
                                    (update segments i with-removed
                                            (map #(second (val %)) found)))
                                  (:segments index)
                                  by-segment)
            docs       (persistent! (reduce dissoc!
                                            (transient (:docs index))
                                            (map key found)))]
        (-> index
            (assoc :segments segments :docs docs)
            pruned
            balanced)))))

(defn- latest
  "The `docs` without those that a later one with the same id replaces,
  in order."
  [docs]
  (let [[_ kept] (reduce (fn [[ids kept] {:keys [id] :as doc}]
                           (if (contains? ids id)
                             [ids kept]
                             [(conj ids id) (conj kept doc)]))
                         [#{} []]
                         (reverse docs))]
    (rseq kept)))

;; A segment is built of at most 4096 documents at once, so that building
;; it takes little memory, and merges join the segments
(defn- added
  "The `index` with the documents `docs`, as add gives it."
  [index docs]
  (when-let [doc (some #(when (nil? (:id %)) %) docs)]
    (throw (ex-info "A document needs an :id" {:document doc})))
  (let [docs (latest docs)]
    (reduce (fn [index chunk]
              (let [entries  (mapv #(update % :fields analyzed-fields) chunk)
                    numbers  (reduce numbered
                                     (:fields index)
                                     (map :fields entries))
                    segments (:segments index)]
                (-> (assoc index :fields numbers)
                    (with-segments (conj segments (built numbers entries))
                                   (count segments))
                    balanced)))
            (removed index (map :id docs))
            (partition-all 4096 docs))))

(defn index
  "An index of `docs` with the `opts` below, or an empty one to add to.

  The `opts` are the defaults of query on this index, e.g. the :boosts of
  its fields and the :aliases that a query can name them by, and of
  parse, snippet and matches? when their options name the :index. They
  print with the index, so they're data.

  The index prints as EDN, its arrays as vectors, and restore reads it
  back."
  ([]
   {:segments [] :docs {} :fields {}})
  ([docs]
   (index docs {}))
  ([docs opts]
   {:pre [(map? opts)]}
   (cond-> (compacted (added (index) docs))
     (seq opts) (assoc :defaults opts))))

(defn restore
  "The `index` read back from EDN with its arrays made again, which a query
  of it otherwise does each time, or nil for a nil `index`."
  [index]
  (some-> index
          (update :segments (partial mapv #(if (instance? Segment %)
                                             %
                                             (segment-of %))))))

(defn remove
  "The `index` without the document `id`, and the `ids` after it."
  ([index]
   index)
  ([index id & ids]
   (removed (restore index) (cons id ids))))

(defn add
  "The `index` with the document `doc`, and the `docs` after it, each a map
  of :id, :fields and :stored. A document replaces any earlier one with
  the same id."
  ([index]
   index)
  ([index doc & docs]
   (added (restore index) (cons doc docs))))

;; The operators that Lucene's simple query parser, Elasticsearch's
;; simple_query_string and SQLite FTS5 share, with ! for NOT as in many
;; programming languages
(def syntax
  "The spellings of the operators of the query language that parse reads.

  A spelling of letters, e.g. AND, is an operator only as a word of its
  own, and a sign, e.g. &, inside a word too. A sign of :not counts only
  at the start of a word, and one of :field only after the name of a
  field. One of :prefix counts only at the end of a word, and so does one
  of :fuzzy, which a number of edits can follow, e.g. clojre~1."
  {:and    #{"AND" "&"}
   :or     #{"OR" "|"}
   :not    #{"NOT" "!" "-"}
   :field  #{":" "="}
   :prefix #{"*"}
   :fuzzy  #{"~"}})

(def default-limits
  "The limits of parse and query that their options don't set, which keep
  the cost of a query that anyone can type within bounds:

  - :max-terms, the words and phrases of a query that count, the first
  - :max-depth, the groups in groups of a query, where a deeper one is
    read without its parentheses
  - :max-completions, the terms that a prefix stands for, those of the
    most documents, as Xapian takes the 100 most frequent
  - :max-expansions, the terms that a word with typos stands for, the
    closest, as Lucene's FuzzyQuery takes 50"
  {:max-terms       32
   :max-depth       32
   :max-completions 100
   :max-expansions  50})

(defn ^:no-doc spaced
  "The query `q` with every kind of whitespace as a space, since the regex
  \\s matches only ASCII whitespace on the JVM but all of it in JavaScript."
  [q]
  (str/replace q #"[\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]" " "))

(defn- word-spelling?
  [s]
  (boolean (re-matches #"(?u)[\p{L}\p{N}]+" s)))

(defn- literal
  "The pattern of `s` that matches it as it is."
  [s]
  (str/replace s #"[\\^$.|?*+()\[\]{}\-/]" #(str "\\" %)))

(defn- lexeme-pattern
  "The pattern of the lexemes of a query in the spellings of `syntax`: a
  phrase in quotes, which an unclosed quote ends at the end, a
  parenthesis, a sign of :and or :or, or a word."
  [syntax]
  (let [signs (->> (concat (:and syntax) (:or syntax))
                   (clojure.core/remove word-spelling?)
                   (sort-by count >)
                   (map literal))
        sign  (str/join "|" signs)
        word  (if (seq signs)
                (str "(?:(?!" sign ")[^\\s\"()])+")
                "[^\\s\"()]+")]
    (re-pattern (str "\"[^\"]*\"?|[()]|"
                     (when (seq signs)
                       (str sign "|"))
                     word))))

(defn- raw-lexemes
  "The lexemes of the query `q` that `pattern` finds, as a lazy sequence of
  maps of :text, :start and :end, so that a reader can stop early."
  [pattern q]
  (letfn [(step [found from]
            (lazy-seq
             (when-let [[s & more] (seq found)]
               (let [start (long (str/index-of q s from))
                     end   (+ start (count s))]
                 (cons {:text s :start start :end end}
                       (step more end))))))]
    (step (re-seq pattern q) 0)))

(defn- leading-sign
  "The one of `signs` that `s` starts with and is longer than, or nil."
  [signs s]
  (some #(when (and (str/starts-with? s %)
                    (< (count %) (count s)))
           %)
        signs))

(defn- field-name
  "The name in `s` of one of `fields`, a map of folded names to fields,
  with the sign of :field after it in `syntax`, as a pair, or nil."
  [s syntax fields]
  (some (fn [sign]
          (when-let [at (str/index-of s sign)]
            (let [name (subs s 0 at)]
              (when (and (pos? at)
                         (contains? fields (text/fold name)))
                [name sign]))))
        (:field syntax)))

(defn- fuzzy-sign
  "The place in the word `s` where a sign of :fuzzy in `syntax` ends it,
  with the edits that follow the sign, as a pair, or nil. The edits are
  :auto without a number."
  [s syntax]
  (some (fn [sign]
          (when-let [at (str/last-index-of s sign)]
            (let [digits (subs s (+ at (count sign)))]
              (when (and (pos? at)
                         (re-matches #"[0-9]*" digits))
                [at (if (seq digits)
                      (parse-long digits)
                      :auto)]))))
        (:fuzzy syntax)))

(defn- word-lexemes
  "The lexemes of the word `raw` of a query in `syntax` that can name
  `fields`: a :not for a sign at its start, a :field for a field's name
  and a sign after it, and the :word, with :prefix? when it ends in a
  sign of one, and :fuzzy with the edits after a sign of :fuzzy."
  [{:keys [text start end] :as raw} syntax fields]
  (let [not-sign     (leading-sign (clojure.core/remove word-spelling?
                                                        (:not syntax))
                                   text)
        after-not    (+ start (count not-sign))
        rest-text    (subs text (count not-sign))
        [name sign]  (field-name rest-text syntax fields)
        after-field  (+ after-not (count name) (count sign))
        word         (subs rest-text (+ (count name) (count sign)))
        prefix-sign  (some #(when (str/ends-with? word %) %) (:prefix syntax))
        word         (subs word 0 (- (count word) (count prefix-sign)))
        [at edits]   (when-not prefix-sign
                       (fuzzy-sign word syntax))
        word         (cond-> word at (subs 0 at))]
    (cond-> []
      not-sign    (conj {:kind :not :start start :end after-not})
      name        (conj {:kind  :field
                         :field (get fields (text/fold name))
                         :start after-not
                         :end   after-field})
      (seq word)  (conj (cond-> {:kind    :word
                                 :text    word
                                 :prefix? (some? prefix-sign)
                                 :start   after-field
                                 :end     end}
                          edits (assoc :fuzzy edits))))))

(defn- lexemes-of
  "The lexemes of the `raw` lexeme of a query in `syntax` that can name
  `fields`."
  [{:keys [text start end] :as raw} syntax fields]
  (let [operator (some #(when (contains? (get syntax %) text) %)
                       [:and :or :not])
        at       {:start start :end end}]
    (cond
      (str/starts-with? text "\"")
      [(assoc at :kind :phrase :text (str/replace text "\"" ""))]

      (= "(" text)
      [(assoc at :kind :open)]

      (= ")" text)
      [(assoc at :kind :close)]

      (and operator
           (or (not= :not operator)
               (word-spelling? text)))
      [(assoc at :kind operator)]

      ;; it's a :not only right before a phrase or a group, see lexemes
      (= :not operator)
      [(assoc at :kind :not-sign)]

      :else
      (word-lexemes raw syntax fields))))

(defn- lexemes
  "The lexemes of the query `q` in `syntax` that can name `fields`, as
  maps of :kind, :start and :end.

  The kinds are :open and :close, :and, :or and :not, a :field with the
  field, and a :phrase or :word with its :text. The last word has
  :typing? when nothing follows it. Reading stops after `max-terms`
  words and phrases."
  [q syntax fields max-terms]
  (let [term? #(contains? #{:word :phrase} (:kind %))
        read  (fn [[found n] raw]
                (if (<= (long max-terms) (long n))
                  (reduced [found n])
                  (let [more (lexemes-of raw syntax fields)]
                    [(into found more) (+ n (count (filter term? more)))])))
        [found] (reduce read [[] 0] (raw-lexemes (lexeme-pattern syntax) q))
        ;; a sign of :not on its own is a :not right before a phrase or a
        ;; group, e.g. -"a b", and else a dash, e.g. in "Part 1 - Intro"
        bound (fn [i {:keys [kind end] :as lexeme}]
                (let [after (get found (inc i))]
                  (cond
                    (not= :not-sign kind)
                    [lexeme]

                    (and (= end (:start after))
                         (contains? #{:phrase :open} (:kind after)))
                    [(assoc lexeme :kind :not)]

                    :else
                    [])))
        found (into [] (comp (map-indexed bound) cat) found)
        last  (peek found)]
    (if (and (= :word (:kind last))
             (= (count q) (:end last)))
      (conj (pop found) (assoc last :typing? true))
      found)))

(defn- word-query
  "The query of the word or phrase `lexeme`, with the options in `ctx`: a
  term, or a phrase of the words of a text with punctuation in it, or of
  the pairs of characters of a run of Chinese, or nil for no words.

  A word that ends in a sign of :prefix, or the last word while it's being
  typed, is a prefix, as is one character of Chinese, which a pair can
  only start. A word with :fuzzy is a term with the edits it allows."
  [{:keys [text prefix? typing? fuzzy] :as lexeme}
   {:keys [complete? min-prefix]}]
  (let [tokens  (text/tokens text)
        terms   (mapv #(if (vector? %) (first %) %) tokens)
        prefix? (or prefix?
                    (and typing?
                         complete?
                         (not fuzzy)
                         (<= (long min-prefix) (count (peek terms)))))]
    (case (count terms)
      0 nil
      1 (let [term  (first terms)
              lone? (and (string? (first tokens))
                         (text/unspaced? term))]
          (cond-> {:term term}
            (or prefix? lone?)        (assoc :prefix? true)
            (and fuzzy
                 (not (text/unspaced? term))) (assoc :fuzzy fuzzy)))
      (cond-> {:phrase terms}
        prefix? (assoc :prefix? true)))))

(defn- kind
  "The kind of the query `node`: :and, :or, :not, :term or :phrase, or nil."
  [node]
  (some #(when (contains? node %) %) [:and :or :not :term :phrase]))

(defn- map-leaves
  "The query `node` with `f` applied to each of its terms and phrases,
  those under a :not too."
  [f node]
  (let [k (kind node)]
    (case k
      (:and :or)      (update node k #(mapv (partial map-leaves f) %))
      :not            (update node :not #(map-leaves f %))
      (:term :phrase) (f node)
      node)))

(defn- in-field
  "The query `node` with `field` on each of its terms and phrases that
  names no field of its own."
  [node field]
  (map-leaves #(cond-> % (not (:field %)) (assoc :field field)) node))

(defn- combine
  "The `queries` joined by `op`, :and or :or, as one query, or nil when
  there are none."
  [op queries]
  (case (count queries)
    0 nil
    1 (first queries)
    {op (into []
              (mapcat #(if (contains? % op) (get % op) [%]))
              queries)}))

(defn- operand-start?
  [lexeme]
  (contains? #{:word :phrase :open :not :field} (:kind lexeme)))

(declare parse-or)

(defn- parse-atom
  "The query of the term, phrase or group at `i` in the lexemes of `ctx`,
  as a pair of the query, or nil, and where the lexemes go on."
  [ctx i]
  (let [lexeme   (get-in ctx [:lexemes i])
        adjacent (get-in ctx [:lexemes (inc i)])]
    (case (:kind lexeme)
      :field  (if (and (= (:end lexeme) (:start adjacent))
                       (contains? #{:word :phrase :open} (:kind adjacent)))
                (let [[node j] (parse-atom ctx (inc i))]
                  [(some-> node (in-field (:field lexeme))) j])
                [nil (inc i)])
      ;; a group deeper than :max-depth is read without its parentheses,
      ;; so that no query can run the stack out
      :open   (if (< (long (:depth ctx)) (long (:max-depth ctx)))
                (let [[node j] (parse-or (update ctx :depth inc) (inc i))
                      closing  (get-in ctx [:lexemes j])]
                  [node (if (= :close (:kind closing)) (inc j) j)])
                [nil (inc i)])
      :phrase [(word-query lexeme ctx) (inc i)]
      :word   [(word-query lexeme ctx) (inc i)]
      [nil i])))

(defn- parse-unary
  "The query at `i` in the lexemes of `ctx` with the :not before it, as a
  pair of the query, or nil, and where the lexemes go on. Two :not undo
  each other."
  [ctx i]
  (let [nots     (count (take-while #(= :not (:kind %))
                                    (subvec (:lexemes ctx) i)))
        [node j] (parse-atom ctx (+ i nots))]
    [(if (and node (odd? nots)) {:not node} node) j]))

;; NOT binds tighter than AND, and AND than OR, as in SQLite FTS5. Words
;; without an operator between them take the default one at its level
(defn- parse-and
  [ctx i]
  (loop [[node j] (parse-unary ctx i) queries []]
    (let [queries (cond-> queries node (conj node))
          lexeme  (get-in ctx [:lexemes j])]
      (cond
        (= :and (:kind lexeme))
        (recur (parse-unary ctx (inc j)) queries)

        (and (= :and (:operator ctx))
             (operand-start? lexeme))
        (recur (parse-unary ctx j) queries)

        :else
        [(combine :and queries) j]))))

(defn- parse-or
  [ctx i]
  (loop [[node j] (parse-and ctx i) queries []]
    (let [queries (cond-> queries node (conj node))
          lexeme  (get-in ctx [:lexemes j])]
      (cond
        (= :or (:kind lexeme))
        (recur (parse-and ctx (inc j)) queries)

        (and (= :or (:operator ctx))
             (operand-start? lexeme))
        (recur (parse-and ctx j) queries)

        :else
        [(combine :or queries) j]))))

(defn- name-of
  [field]
  (if (keyword? field)
    (name field)
    (str field)))

(defn- field-names
  "The fields of `index` by name, and by the names of `aliases`, a map of
  a name to a field."
  [index aliases]
  (into {}
        (concat (for [field (keys (:fields index))]
                  [(name-of field) field])
                aliases)))

(defn parse
  "The query `q`, a string, as data, read with the `opts` below, or nil
  when it asks for nothing.

  Words must all occur, and the last is a prefix to complete unless `q`
  ends in a space or a quote. The operators are those of syntax: AND, OR
  and NOT, or & | ! and - before a word, with parentheses, \"a phrase\",
  a prefix*, a word with typos~ and field:word or field=word for a word
  in one field:

      (parse \"title:clojure -rust\")
      ;; => {:and [{:term \"clojure\" :field :title}
      ;;           {:not {:term \"rust\" :prefix? true}}]}

  A query as data is a map of :and or :or with a vector of queries, of
  :not with a query, of :term with a term, or of :phrase with a vector
  of terms in a row. A term or phrase has :prefix? when its last term is
  a prefix, and :field when it's in that field only.

  The `opts` are:

  - :index, an index whose fields a query can name, and whose defaults
    count under the `opts`
  - :aliases, other names that a query can give the fields, as a map of
    a name to a field
  - :syntax, the spellings of operators over those of syntax, e.g. OG
    for AND in Danish with {:and #{\"OG\" \"&\"}}
  - :operator, :and or :or between words without one, :and by default
  - :prefix?, false to take the last word whole
  - :min-prefix, the fewest characters of a last word to complete, 1 by
    default
  - :max-terms and :max-depth, the limits of default-limits

  Nothing is an error, and a sign or a parenthesis that makes no sense
  counts for nothing."
  ([q]
   (parse q {}))
  ([q opts]
   {:pre [(map? opts)]}
   (let [{:keys [index aliases operator prefix? min-prefix
                 max-terms max-depth]
          :or   {operator :and prefix? true min-prefix 1}
          :as   opts}
         (merge default-limits (:defaults (:index opts)) opts)
         spellings (merge syntax (:syntax opts))
         fields    (update-keys (field-names index aliases) text/fold)
         q         (spaced (str q))
         ctx       {:lexemes    (lexemes q spellings fields max-terms)
                    :operator   operator
                    :complete?  prefix?
                    :min-prefix min-prefix
                    :depth      0
                    :max-depth  max-depth}
         n         (count (:lexemes ctx))]
     (loop [i 0 queries []]
       (if (< i n)
         (let [[node j] (parse-or ctx i)]
           ;; a lexeme that starts nothing, e.g. a stray ), is skipped
           (recur (if (= i j) (inc i) (long j))
                  (cond-> queries node (conj node))))
         (combine operator queries))))))

;; k1, b and idf are those of BM25Similarity of Lucene
(def ^{:no-doc true :const true} k1
  "BM25's saturation: how soon repeating a term stops counting."
  1.2)

(def ^{:no-doc true :const true} b
  "BM25's length normalization: how much a long field is discounted."
  0.75)

(defn- idf
  "The inverse document frequency of a term found in `n` of `total`
  documents."
  ^double [^long total ^long n]
  (Math/log (+ 1 (/ (+ (- total n) 0.5) (+ n 0.5)))))

;; The constants of BM25F of the fields of an index, as arrays by field
;; number: the weight of each, and a and c of its length normalization
;; a + c length
(deftype Weights [weight a c])

;; Until a merge leaves them out, removed documents still count in the
;; statistics of BM25, as in Lucene
(defn- weights
  "The constants of BM25F of the fields of an index with the field
  `numbers` and the `segments`: each field's weight from `boosts`, 1 by
  default, and a and c of its length normalization, which fold in `b` and
  the field's average length."
  [numbers segments boosts b]
  (let [n               (count numbers)
        b               (double b)
        ^doubles weight (make-doubles n)
        ^doubles a      (make-doubles n)
        ^doubles c      (make-doubles n)]
    (doseq [[field no] numbers]
      (let [stats   (keep #(get (.-field-stats ^Segment %) no) segments)
            docs    (reduce + 0 (map first stats))
            length  (reduce + 0 (map second stats))
            average (if (pos? docs)
                      (/ (double length) docs)
                      1.0)]
        (aset weight no (double (get boosts field 1)))
        (aset a no (- 1.0 b))
        (aset c no (/ b average))))
    (->Weights weight a c)))

(defn- restricted
  "The `weights` with every field but the one numbered `no` left out, and
  that one at its weight, or 1 when that is 0, since a query names it.
  Without a `no`, every field is left out."
  [^Weights weights no]
  (let [^doubles weight (.-weight weights)
        ^doubles only   (make-doubles (alength weight))]
    (when no
      (let [w (aget weight (int no))]
        (aset only (int no) (if (zero? w) 1.0 w))))
    (->Weights only (.-a weights) (.-c weights))))

(defn- every-field?
  "Whether the `weights` count every field."
  [^Weights weights]
  (let [^doubles weight (.-weight weights)]
    (every? #(pos? (aget weight (int %))) (range (alength weight)))))

;; BM25F, Robertson and Zaragoza 2009, section 3.6, equation 3.19: each
;; occurrence counts the weight of its field, divided by the field's
;; length normalization
(defn- occurrence
  "What an occurrence of a term in the field numbered `no` of the document
  `d` of `segment` counts, with the `weights` of the fields."
  ^double [^Segment segment ^Weights weights ^long d ^long no]
  (let [^doubles weight (.-weight weights)
        w               (aget weight no)]
    (if (zero? w)
      0.0
      (let [^doubles a     (.-a weights)
            ^doubles c     (.-c weights)
            ^ints lengths  (.-lengths segment)
            length         (aget lengths (+ (* d (.-width segment)) no))]
        (/ w (+ (aget a no) (* (aget c no) length)))))))

(defn- frequency
  "BM25F's frequency of a term in the document `d` of `segment`, whose
  posting of it is the `k`th, with the `weights` of the fields.

  The positions of a field come together, so what an occurrence counts
  is worked out once for each run of them."
  ^double [^Segment segment weights ^long d ^long k]
  (let [^ints pos-start (.-pos-start segment)
        ^ints positions (.-positions segment)
        to              (aget pos-start (inc k))]
    (loop [i (long (aget pos-start k)) sum 0.0 field -1 counts 0.0]
      (if (< i to)
        (let [no     (quot (aget positions i) span)
              counts (if (= no field)
                       counts
                       (occurrence segment weights d no))]
          (recur (inc i) (+ sum counts) no counts))
        sum))))

;; The hits of a query in a segment are a map of :docs, the numbers of
;; the documents that match, in order, and :scores, the score of each, both
;; arrays

(defn- no-hits
  []
  {:docs (make-ints 0) :scores (make-doubles 0)})

(defn- hit-count
  [hits]
  (alength ^ints (:docs hits)))

(defn- collected
  "The hits of the positive values of `acc`, an array of a value for each
  of the `candidates`, or for each document when nil."
  [^doubles acc ^ints candidates]
  (let [n               (alength acc)
        m               (areduce acc i m 0 (if (pos? (aget acc i)) (inc m) m))
        ^ints docs      (make-ints m)
        ^doubles scores (make-doubles m)]
    (loop [j 0 i 0]
      (when (< j n)
        (if (pos? (aget acc j))
          (do (aset docs i (if candidates (aget candidates j) (int j)))
              (aset scores i (aget acc j))
              (recur (inc j) (inc i)))
          (recur (inc j) i))))
    {:docs docs :scores scores}))

;; The postings of a term and the candidates are both in order, so they
;; meet in one walk, each side skipping ahead with seek
(defn- add-frequencies!
  "Add to `acc`, an array of a value for each of the `candidates`, the
  BM25F frequency of each posting of `segment` from `from` to `to` whose
  document is one of them, with the `weights`."
  [^Segment segment weights ^doubles acc ^ints candidates from to]
  (let [^ints docs (.-docs segment)
        n          (alength candidates)
        to         (long to)]
    (loop [j 0 k (long from)]
      (when (and (< j n) (< k to))
        (let [d (aget candidates j)
              e (aget docs k)]
          (cond
            (< d e) (recur (seek candidates j n e) k)
            (> d e) (recur j (seek docs k to d))
            :else   (do (aset acc j (+ (aget acc j)
                                       (frequency segment weights d k)))
                        (recur (inc j) (inc k)))))))))

(defn- add-each-frequency!
  "Add to `acc`, an array of a value for each document of `segment`, the
  BM25F frequency of each posting from `from` to `to`, with the
  `weights`."
  [^Segment segment weights ^doubles acc from to]
  (let [^ints docs (.-docs segment)
        to         (long to)]
    (loop [k (long from)]
      (when (< k to)
        (let [d (aget docs k)]
          (aset acc d (+ (aget acc d) (frequency segment weights d k)))
          (recur (inc k)))))))

(defn- term-ranges
  "The postings in `segment` of those of `terms` that it holds, as pairs of
  where they start and end."
  [^Segment segment terms]
  (let [held         (.-terms segment)
        ^ints starts (.-doc-start segment)]
    (into []
          (keep (fn [term]
                  (let [t (term-number held term)]
                    (when (<= 0 t)
                      [(aget starts t) (aget starts (inc t))]))))
          terms)))

(defn- postings-count
  [ranges]
  (reduce + 0 (map (fn [[from to]] (- (long to) (long from))) ranges)))

(defn- range-frequencies
  "The hits of the documents of `segment` among the `candidates`, or of
  all of them when nil, that have postings in the `ranges` in a field
  that the `weights` count, scored by their BM25F frequencies added up."
  [^Segment segment weights ranges candidates]
  (cond
    (empty? ranges)
    (no-hits)

    candidates
    (let [acc (make-doubles (alength ^ints candidates))]
      (doseq [[from to] ranges]
        (add-frequencies! segment weights acc candidates from to))
      (collected acc candidates))

    ;; a term holds each document once, so its documents are candidates
    (= 1 (count ranges))
    (let [[from to] (first ranges)]
      (recur segment weights ranges (slice (.-docs segment) from to)))

    :else
    (let [acc (make-doubles (size segment))]
      (doseq [[from to] ranges]
        (add-each-frequency! segment weights acc from to))
      (collected acc nil))))

(defn- saturated
  "The `hits` of BM25F frequencies as the scores of a term of the inverse
  document frequency `idf`, with the saturation `k1`."
  [{:keys [docs ^doubles scores] :as hits} idf k1]
  (let [idf          (double idf)
        k1           (double k1)
        n            (alength scores)
        ^doubles out (make-doubles n)]
    (dotimes [i n]
      (let [f (aget scores i)]
        (aset out i (* idf (/ f (+ k1 f))))))
    {:docs docs :scores out}))

(defn- joined
  "The hits `x` and `y` as one, where `op`, :+ or :max, combines the scores
  of a document in both."
  [op x y]
  (let [{^ints xd :docs ^doubles xs :scores} x
        {^ints yd :docs ^doubles ys :scores} y
        nx               (alength xd)
        ny               (alength yd)
        ^ints docs       (make-ints (+ nx ny))
        ^doubles scores  (make-doubles (+ nx ny))
        n                (loop [i 0 j 0 n 0]
                           (cond
                             (and (= i nx) (= j ny))
                             n

                             (or (= j ny)
                                 (and (< i nx)
                                      (< (aget xd i) (aget yd j))))
                             (do (aset docs n (aget xd i))
                                 (aset scores n (aget xs i))
                                 (recur (inc i) j (inc n)))

                             (or (= i nx)
                                 (> (aget xd i) (aget yd j)))
                             (do (aset docs n (aget yd j))
                                 (aset scores n (aget ys j))
                                 (recur i (inc j) (inc n)))

                             :else
                             (let [sx (aget xs i)
                                   sy (aget ys j)]
                               (aset docs n (aget xd i))
                               (aset scores n (if (= :max op)
                                                (max sx sy)
                                                (+ sx sy)))
                               (recur (inc i) (inc j) (inc n)))))]
    {:docs (slice docs 0 n) :scores (trimmed-doubles scores n)}))

(defn- added-up
  "The hits `more`, whose documents are all among those of `hits`, with
  the scores of `hits` added to theirs."
  [hits more]
  (let [{^ints hd :docs ^doubles hs :scores} hits
        {^ints md :docs ^doubles ms :scores} more
        n                (alength md)
        ^doubles scores  (make-doubles n)]
    (loop [i 0 j 0]
      (when (< i n)
        (let [j (seek hd j (alength hd) (aget md i))]
          (aset scores i (+ (aget hs j) (aget ms i)))
          (recur (inc i) (inc j)))))
    {:docs md :scores scores}))

(defn- without
  "The `hits` without the documents of the hits `other`."
  [hits other]
  (let [{^ints hd :docs ^doubles hs :scores} hits
        ^ints od         (:docs other)
        n                (alength hd)
        m                (alength od)
        ^ints docs       (make-ints n)
        ^doubles scores  (make-doubles n)
        kept             (loop [i 0 j 0 kept 0]
                           (if (< i n)
                             (let [d (aget hd i)
                                   j (seek od j m d)]
                               (if (and (< j m) (= d (aget od j)))
                                 (recur (inc i) j kept)
                                 (do (aset docs kept d)
                                     (aset scores kept (aget hs i))
                                     (recur (inc i) j (inc kept)))))
                             kept))]
    {:docs (slice docs 0 kept) :scores (trimmed-doubles scores kept)}))

(defn- codes
  "The codes of the characters of `s`, as an array."
  [^String s]
  (let [n   (count s)
        arr (int-array n)]
    (dotimes [i n]
      (aset arr i #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))))
    arr))

;; The optimal string alignment distance, Damerau-Levenshtein with a swap
;; of neighbours as one edit, as Lucene's FuzzyQuery counts transpositions
(defn- fill-row!
  "Fill `row` with the edits from the first `i` codes of `x` to each start
  of `y`, from the two rows before it, `one` and `two`, and give the
  fewest of them."
  [^ints x ^ints y i ^ints one ^ints two ^ints row]
  (let [i (long i)
        m (alength y)
        c (aget x (dec i))]
    (aset row 0 (int i))
    (loop [j 1 lowest i]
      (if (> j m)
        lowest
        (let [d        (aget y (dec j))
              deleted  (inc (aget one j))
              inserted (inc (aget row (dec j)))
              changed  (+ (aget one (dec j)) (if (= c d) 0 1))
              swapped? (and (> i 1)
                            (> j 1)
                            (= c (aget y (- j 2)))
                            (= (aget x (- i 2)) d))
              v        (cond-> (-> deleted (min inserted) (min changed))
                         swapped? (min (inc (aget two (- j 2)))))]
          (aset row j (int v))
          (recur (inc j) (min lowest v)))))))

(defn ^:no-doc distance
  "The edits that turn `a` into `b`, a swap of two neighbours counted as
  one, or nil when there are more than `most`."
  [a b most]
  (let [^ints x (codes (str a))
        ^ints y (codes (str b))
        n       (alength x)
        m       (alength y)
        most    (long most)
        start   (let [row (int-array (inc m))]
                  (dotimes [j (inc m)]
                    (aset row j (int j)))
                  row)]
    (when (<= (Math/abs (- n m)) most)
      (loop [i 1 ^ints two (int-array (inc m)) ^ints one start]
        (if (> i n)
          (let [d (aget one m)]
            (when (<= d most)
              d))
          (let [row    (int-array (inc m))
                lowest (long (fill-row! x y i one two row))]
            (when (<= lowest most)
              (recur (inc i) one row))))))))

(defn- edits
  "The edits that the term `leaf` allows, those of its :fuzzy, at most 2."
  [{:keys [fuzzy] :as leaf}]
  (if (number? fuzzy)
    (min 2 (long fuzzy))
    0))

;; The AUTO:3,6 of Elasticsearch's fuzziness, and the min_len_1typo and
;; min_len_2typo of Typesense
(defn- auto-edits
  "The edits that `term` allows by its length, with the shortest `lengths`
  of a word with one edit and with two, [3 6] by default."
  [term lengths]
  (let [[one two] (or lengths [3 6])
        n         (count term)]
    (cond
      (< n (long one)) 0
      (< n (long two)) 1
      :else            2)))

(def ^:no-doc fuzzy-weight
  "How much a match with typos weighs before its edits count, as the
  fuzzyWeight of MiniSearch has it."
  0.45)

(defn- prefixed
  "The terms of `segment` that start with `prefix`, in order, as pairs of a
  term and the number of documents that hold it."
  [^Segment segment prefix]
  (let [^objects terms (.-terms segment)
        ^ints starts   (.-doc-start segment)
        n              (alength terms)]
    (loop [i   (term-bound terms prefix)
           acc (transient [])]
      (if (and (< i n)
               (str/starts-with? (aget terms i) prefix))
        (recur (inc i)
               (conj! acc [(aget terms i)
                           (- (aget starts (inc i)) (aget starts i))]))
        (persistent! acc)))))

(defn- term-counts
  "The terms of the `segments` that start with `prefix`, as a map of each
  to the number of documents that hold it."
  [segments prefix]
  (persistent!
   (reduce (fn [acc [term n]]
             (assoc! acc term (+ (long n) (long (get acc term 0)))))
           (transient {})
           (mapcat #(prefixed % prefix) segments))))

(defn- more-held
  "A comparator of the map entries `x` and `y` of a term and the number of
  documents that hold it: the most documents first, then the terms in
  order."
  [x y]
  (let [c (compare (val y) (val x))]
    (if (zero? c)
      (compare (key x) (key y))
      c)))

(declare best)

(defn- completions
  "The terms of the `segments` that start with `prefix`, at most `most` of
  them, those that the most documents hold."
  [segments prefix most]
  (let [counts (term-counts segments prefix)]
    (if (<= (count counts) (long most))
      (vec (sort (keys counts)))
      (mapv key (best more-held most counts)))))

(defn- fuzzy-expansions
  "The terms of the `segments` within the edits of the fuzzy `leaf`, at
  most `most` of them, as pairs of a term and its edits: the closest
  first, and of those the ones that the most documents hold. Only terms
  that start with the same character count, which keeps the search short."
  [segments {:keys [term] :as leaf} most]
  (let [edits (long (edits leaf))
        n     (count term)]
    (->> (term-counts segments (subs term 0 1))
         (filter #(<= (Math/abs (- (count (key %)) n)) edits))
         (keep (fn [[found held]]
                 (when-let [d (distance term found edits)]
                   [found d held])))
         (sort-by (fn [[found d held]] [d (- (long held)) found]))
         (take most)
         (map pop))))

(defn- known?
  "Whether a document of the `segments` holds the term of `leaf`, or with
  :prefix? a term that it starts."
  [segments {:keys [term prefix?]}]
  (boolean
   (some (fn [^Segment segment]
           (let [^objects terms (.-terms segment)
                 i              (term-bound terms term)]
             (and (< i (alength terms))
                  (if prefix?
                    (str/starts-with? (aget terms i) term)
                    (= term (aget terms i))))))
         segments)))

(defn- distinct-docs
  "The documents of `segment` with a posting in the `ranges`."
  [^Segment segment ranges]
  (let [^ints docs (.-docs segment)
        ^ints seen (make-ints (size segment))]
    (doseq [[from to] ranges]
      (loop [k (long from)]
        (when (< k (long to))
          (aset seen (aget docs k) 1)
          (recur (inc k)))))
    (areduce seen i n 0 (+ n (aget seen i)))))

(defn- segment-doc-count
  "The documents of `segment` with one of `terms` in a field that the
  `weights` count, or in any field when `weights` is nil."
  [segment weights terms]
  (let [ranges (term-ranges segment terms)]
    (cond
      (and weights (not (every-field? weights)))
      (hit-count (range-frequencies segment weights ranges nil))

      (< 1 (count ranges))
      (distinct-docs segment ranges)

      :else
      (postings-count ranges))))

(defn- doc-count
  "The documents of the `segments` with one of `terms` in a field that the
  `weights` count, or in any field when `weights` is nil."
  [segments weights terms]
  (reduce + 0 (map #(segment-doc-count % weights terms) segments)))

(defn- leaf-weights
  "The weights of the fields for the term or phrase `leaf`, those of `ctx`
  with all but its :field left out when it names one."
  [{:keys [weights numbers]} {:keys [field]}]
  (if field
    (restricted weights (get numbers field))
    weights))

;; A prefix counts as one term of all the terms it starts, with the number
;; of documents that hold any of them. Lucene blends the expansions of a
;; prefix to the largest document frequency, so that the rarest one doesn't
;; outrank the rest.
;;
;; MiniSearch weighs a match with typos by 0.45 length / (length + edits),
;; and each document counts its best match, so that an exact match outranks
;; one with typos. The expansions share one inverse document frequency, as
;; Lucene blends those of a FuzzyQuery
(defn- prepared-term
  "The term `leaf` with what scoring it in each segment of `ctx` takes:
  its :weights, the :summed terms that it or its completions are, whose
  frequencies add up, and the :typos terms within its edits, each with a
  boost, both with their inverse document frequency."
  [{:keys [segments total max-completions max-expansions] :as ctx}
   {:keys [term prefix?] :as leaf}]
  (let [weights (leaf-weights ctx leaf)
        idf-of  #(idf total (doc-count segments weights %))
        fuzzy?  (pos? (long (edits leaf)))
        terms   (cond
                  prefix? (completions segments term max-completions)
                  fuzzy?  nil
                  :else   [term])
        close   (when fuzzy?
                  (fuzzy-expansions segments leaf max-expansions))
        n       (count term)
        boost   (fn [[_ d]]
                  (if (zero? (long d))
                    1.0
                    (* fuzzy-weight (/ (double n) (+ n (long d))))))]
    (cond-> (assoc leaf :weights weights)
      terms (assoc :summed {:terms terms
                            :idf   (idf-of terms)})
      close (assoc :typos {:terms  (mapv first close)
                           :boosts (mapv boost close)
                           :idf    (idf-of (map first close))}))))

;; Lucene's PhraseQuery: the inverse document frequency of a phrase is the
;; sum of those of its terms
(defn- prepared-phrase
  "The phrase `leaf` with what scoring it in each segment of `ctx` takes:
  its :weights, the :places, the terms that each place of it holds, its
  last term's completions when it's a prefix, and its :idf."
  [{:keys [segments total max-completions] :as ctx}
   {:keys [phrase prefix?] :as leaf}]
  (let [places (conj (mapv vector (pop phrase))
                     (if prefix?
                       (completions segments (peek phrase) max-completions)
                       [(peek phrase)]))]
    (assoc leaf
           :weights (leaf-weights ctx leaf)
           :places  places
           :idf     (reduce + (map #(idf total (doc-count segments nil %))
                                   places)))))

(defn- prepared
  "The query `node` with what scoring its terms and phrases takes, worked
  out once for all the segments of `ctx`."
  [ctx node]
  (map-leaves #(if (:phrase %)
                 (prepared-phrase ctx %)
                 (prepared-term ctx %))
              node))

(defn- with-edits
  "The term `leaf` with the edits that it allows as its :fuzzy: those of
  its own, or of `fuzzy` when that's true or a number, or else when
  `unknown?` picks it and `fuzzy` is nil. Its length decides the edits of
  true and :auto by the `lengths` of auto-edits."
  [{:keys [term] :as leaf} fuzzy unknown? lengths]
  (let [wanted (cond
                 (:fuzzy leaf)    (:fuzzy leaf)
                 (true? fuzzy)    :auto
                 (number? fuzzy)  fuzzy
                 (and (nil? fuzzy)
                      (unknown? leaf)) :auto)]
    (cond-> leaf
      wanted (assoc :fuzzy (if (= :auto wanted)
                             (auto-edits term lengths)
                             wanted)))))

(defn- with-fuzzy
  "The query `node` with the edits that each of its terms allows, as
  with-edits gives them with `fuzzy`, `unknown?` and `lengths`, but none
  for a script without spaces."
  [node fuzzy unknown? lengths]
  (map-leaves #(if (and (:term %) (not (text/unspaced? (:term %))))
                 (with-edits % fuzzy unknown? lengths)
                 %)
              node))

(defn- unknown-in
  "A predicate of a term that no document of `index` holds, or with
  :prefix? one that no term of it starts, which is false for every term
  without an `index`."
  [index]
  (if-let [segments (:segments (restore index))]
    #(not (known? segments %))
    (constantly false)))

(declare hits-in)

(defn- excluded
  "The `found` hits in `segment` without those of the `negative` queries,
  with the constants of `ctx`."
  [ctx segment negative found]
  (reduce (fn [found q]
            (if (zero? (hit-count found))
              (reduced found)
              (without found (hits-in ctx segment q (:docs found)))))
          found
          negative))

(defn- cost
  "The postings in `segment` of the terms of the prepared query `node`, for
  an :and or a phrase those of its rarest part, so that an :and can start
  from its rarest part."
  [segment node]
  (let [positive #(clojure.core/remove :not %)]
    (cond
      (contains? node :and)
      (reduce min ##Inf (map #(cost segment %) (positive (:and node))))

      (contains? node :or)
      (reduce + 0 (map #(cost segment %) (positive (:or node))))

      (contains? node :phrase)
      (reduce min ##Inf (map #(postings-count (term-ranges segment %))
                             (:places node)))

      (contains? node :term)
      (postings-count (term-ranges segment (concat (:terms (:summed node))
                                                   (:terms (:typos node)))))

      :else
      0)))

;; Lucene's BooleanQuery: the scores of the clauses that match add up, and
;; a clause of :not, MUST_NOT, only leaves documents out. An :and starts
;; from its rarest clause, and each next one looks only at the documents
;; that are left
(defn- all-of
  "The hits in `segment` among the `candidates` of the documents that
  match all the prepared `queries` but those under a :not, with the
  constants of `ctx`."
  [ctx segment queries candidates]
  (let [[rarest & others] (sort-by #(cost segment %)
                                   (clojure.core/remove :not queries))]
    (if (nil? rarest)
      (no-hits)
      (->> others
           (reduce (fn [found q]
                     (if (zero? (hit-count found))
                       (reduced found)
                       (added-up found (hits-in ctx segment q (:docs found)))))
                   (hits-in ctx segment rarest candidates))
           (excluded ctx segment (keep :not queries))))))

(defn- any-of
  "The hits in `segment` among the `candidates` of the documents that
  match any of the prepared `queries` but those under a :not, with the
  constants of `ctx`."
  [ctx segment queries candidates]
  (let [positive (clojure.core/remove :not queries)]
    (if (empty? positive)
      (no-hits)
      (->> positive
           (map #(hits-in ctx segment % candidates))
           (reduce #(joined :+ %1 %2))
           (excluded ctx segment (keep :not queries))))))

(defn- term-hits
  "The hits in `segment` among the `candidates` of the prepared term
  `leaf`, with the saturation of `ctx`: those of its :summed terms, the
  best of its :typos terms, or the best of both."
  [{:keys [k1]} segment {:keys [weights summed typos]} candidates]
  (let [scored (fn [terms idf]
                 (-> (range-frequencies segment
                                        weights
                                        (term-ranges segment terms)
                                        candidates)
                     (saturated idf k1)))
        exact  (when summed
                 (scored (:terms summed) (:idf summed)))
        close  (when typos
                 (reduce #(joined :max %1 %2)
                         (no-hits)
                         (map (fn [term boost]
                                (scored [term] (* (double boost) (:idf typos))))
                              (:terms typos)
                              (:boosts typos))))]
    (if (and exact close)
      (joined :max exact close)
      (or exact close (no-hits)))))

;; One walk through each term's postings for all the candidates, as in
;; add-frequencies!, rather than a search for each candidate
(defn- place-slices
  "For each of the `candidates`, a sorted array of numbers of documents of
  `segment`, the ranges of its positions for a place of a phrase, from the
  `ranges` of the postings of the terms that the place can hold, as an
  array of vectors of pairs, or nil for a document without one."
  [^Segment segment ^ints candidates ranges]
  (let [^ints docs      (.-docs segment)
        ^ints pos-start (.-pos-start segment)
        n               (alength candidates)
        ^objects slices (object-array n)]
    (doseq [[from to] ranges]
      (loop [j 0 k (long from)]
        (when (and (< j n) (< k (long to)))
          (let [d (aget candidates j)
                e (aget docs k)]
            (cond
              (< d e) (recur (seek candidates j n e) k)
              (> d e) (recur j (seek docs k to d))
              :else   (let [slice [(aget pos-start k) (aget pos-start (inc k))]]
                        (aset slices j (conj (or (aget slices j) []) slice))
                        (recur (inc j) (inc k))))))))
    slices))

(defn- holds?
  "Whether one of the `slices`, ranges of sorted positions in `segment`,
  holds the position `x`."
  [^Segment segment slices x]
  (let [^ints positions (.-positions segment)
        x               (long x)]
    (boolean
     (some (fn [[from to]]
             (let [to (long to)
                   i  (lower-bound positions (long from) to x)]
               (and (< i to) (= x (aget positions i)))))
           slices))))

(defn- phrase-starts
  "The positions in `segment` where the places of a phrase start in a row,
  from the `slices` of each place in one document."
  [^Segment segment slices]
  (let [^ints positions (.-positions segment)]
    (for [[from to] (first slices)
          i         (range from to)
          :let      [start (aget positions i)]
          :when     (every? #(holds? segment (nth slices %) (+ start %))
                            (range 1 (count slices)))]
      start)))

(defn- phrase-frequency
  "BM25F's frequency of a phrase in the document `d` of `segment`, from the
  `slices` of each place of it there, with the `weights` of the fields."
  [segment weights d slices]
  (->> (phrase-starts segment slices)
       (map #(occurrence segment weights d (quot % span)))
       (reduce + 0.0)))

(defn- phrase-hits
  "The hits in `segment` among the `candidates` of the prepared phrase
  `leaf`, with the saturation of `ctx`. The documents of its rarest place
  are those to look in."
  [{:keys [k1]} segment {:keys [weights places idf]} candidates]
  (let [ranges (mapv #(term-ranges segment %) places)]
    (if (some empty? ranges)
      (no-hits)
      (let [rarest     (apply min-key postings-count ranges)
            ^ints docs (:docs (range-frequencies segment
                                                 weights
                                                 rarest
                                                 candidates))
            by-place   (mapv #(place-slices segment docs %) ranges)
            slices-of  (fn [j]
                         (mapv (fn [^objects slices] (aget slices j))
                               by-place))
            found      (into []
                             (keep #(let [d (aget docs %)
                                          f (phrase-frequency segment
                                                              weights
                                                              d
                                                              (slices-of %))]
                                      (when (pos? f)
                                        [d f])))
                             (range (alength docs)))]
        (saturated {:docs   (as-ints (mapv first found))
                    :scores (as-doubles (mapv second found))}
                   idf
                   k1)))))

(defn- hits-in
  "The hits in `segment` among the `candidates`, a sorted array of the
  numbers of documents, or all of them when nil, of the prepared query
  `node`, with the constants of `ctx`. A query of :not alone matches
  nothing."
  [ctx segment node candidates]
  (cond
    (contains? node :and)    (all-of ctx segment (:and node) candidates)
    (contains? node :or)     (any-of ctx segment (:or node) candidates)
    (contains? node :phrase) (phrase-hits ctx segment node candidates)
    (contains? node :term)   (term-hits ctx segment node candidates)
    :else                    (no-hits)))

(defn- as-query
  "The query `q` as data, read with the `opts`: a string parsed, a vector
  of terms taken as they are, or the data itself."
  [q opts]
  (cond
    (string? q) (parse q opts)
    (vector? q) (combine (:operator opts :and) (mapv #(hash-map :term %) q))
    :else       q))

(defn- fuzzy-query
  "The query `q` as as-query reads it with the `opts`, with the edits that
  each of its terms allows by their :fuzzy and :typo-lengths, where the
  words that no document holds are those of their :index."
  [q opts]
  (some-> (as-query q opts)
          (with-fuzzy (:fuzzy opts)
                      (unknown-in (:index opts))
                      (:typo-lengths opts))))

(defn- compare-ids
  "The order of the ids `x` and `y` of two results of the same score, as
  better gives it."
  [x y]
  (if (and (number? x) (number? y))
    (compare x y)
    (let [hx (long (hash x))
          hy (long (hash y))]
      (cond
        (< hx hy) -1
        (> hx hy) 1
        :else     (compare (str x) (str y))))))

(defn- better
  "A comparator of the found documents `a` and `b`, vectors of an id and a
  score, and what is stored: the higher score first, then the lower id.

  Ids that aren't both numbers compare by hash and then as strings, so
  that the order is total and the same on both platforms, which hash
  numbers differently. BM25 ties are common, so this must stay cheap."
  [a b]
  (let [sa (double (nth a 1))
        sb (double (nth b 1))]
    (cond
      (> sa sb) -1
      (< sa sb) 1
      :else     (compare-ids (nth a 0) (nth b 0)))))

(defn- kept
  "The vector `top` of at most `n` results, sorted by the comparator
  `before`, with `result` in its place, or as it is when `result` is no
  better than the worst of `n`."
  [before n top result]
  (if (and (= (count top) n)
           (<= 0 (before result (peek top))))
    top
    (let [i   (loop [i 0]
                (if (and (< i (count top))
                         (<= (before (top i) result) 0))
                  (recur (inc i))
                  i))
          top (into (conj (subvec top 0 i) result) (subvec top i))]
      (if (> (count top) n)
        (pop top)
        top))))

(defn- best
  "The `results` sorted by the comparator `before`, at most `n` of them if
  `n` is given.

  For a small `n`, the best so far are kept in a small sorted vector, so
  that most candidates cost one comparison with its worst. For an `n`
  beyond the square root of the number of results, all are sorted."
  [before n results]
  (cond
    (and n (<= n 0))
    []

    (or (nil? n) (>= n (Math/sqrt (count results))))
    (let [sorted (sort before results)]
      (vec (if n (take n sorted) sorted)))

    :else
    (reduce #(kept before n %1 %2) [] results)))

(defn- top
  "The best `n` of the `found` documents, or all of them for a nil `n`, as
  the results that `result` makes of each, with the :filter and :rank of
  `opts`."
  [found result n {:keys [rank] keep? :filter :as opts}]
  (cond
    rank
    (->> found
         (map result)
         (filter (or keep? any?))
         (map (fn [r] [(rank r) r]))
         (sort-by first)
         (map second)
         (#(if n (take n %) %))
         vec)

    ;; a filter that costs, e.g. criteria of a library, reads the best
    ;; first, and more of them only when too few are kept
    (and keep? n)
    (loop [m (* 2 (max 1 (long n))) read 0 kept []]
      (let [best-m (best better m found)
            kept   (into kept
                         (comp (filter #(keep? (result %)))
                               (take (- (long n) (count kept))))
                         (subvec best-m read))]
        (if (or (= (count kept) n)
                (>= m (count found)))
          (mapv result kept)
          (recur (* 4 m) (count best-m) kept))))

    :else
    (->> (if keep?
           (filter #(keep? (result %)) found)
           found)
         (best better n)
         (mapv result))))

(defn- results
  "The documents of `segment` that match the prepared query `node`, as
  vectors of an id, a score and what is stored, without those removed,
  with the constants of `ctx`."
  [ctx ^Segment segment node]
  (let [found           (hits-in ctx segment node nil)
        ^ints docs      (:docs found)
        ^doubles scores (:scores found)
        deleted         (.-deleted segment)
        ids             (.-ids segment)
        stored          (.-stored segment)]
    (into []
          (keep (fn [i]
                  (let [d (long (aget docs i))]
                    (when-not (contains? deleted d)
                      [(nth ids d) (aget scores i) (nth stored d)]))))
          (range (alength docs)))))

(defn- matched
  "The documents of the segments of `ctx` that match the prepared query
  `node`, as vectors of an id, a score and what is stored."
  [ctx node]
  (into [] (mapcat #(results ctx % node)) (:segments ctx)))

(declare leaves)

(defn- typos-of
  "The terms of the prepared query `node` that match other terms with
  typos, those under a :not left out, as a map of each to the :exact terms
  it matches, itself or its completions, and the :near ones."
  [node]
  (into {}
        (keep (fn [{:keys [term summed typos]}]
                (let [near (filterv #(not= term %) (:terms typos))]
                  (when (seq near)
                    [term {:exact (:terms summed) :near near}]))))
        (leaves node)))

(defn- holds-term?
  "Whether the document `d` of `segment` holds `term`."
  [^Segment segment term d]
  (let [^ints docs (.-docs segment)
        d          (long d)]
    (boolean
     (some (fn [[from to]]
             (let [to (long to)
                   k  (seek docs (long from) to d)]
               (and (< k to) (= d (aget docs k)))))
           (term-ranges segment [term])))))

(defn- with-typos
  "The `result` of a query of `index` with :typos, a map of each of the
  terms of `typos`, as typos-of gives them, that its document holds only
  with typos, to the near terms that it holds, when there are any."
  [index typos {:keys [id] :as result}]
  (let [[i d]   (get (:docs index) id)
        segment (nth (:segments index) i)
        holds?  #(holds-term? segment % d)
        held    (into {}
                      (keep (fn [[term {:keys [exact near]}]]
                              (when-not (some holds? exact)
                                (let [found (filterv holds? near)]
                                  (when (seq found)
                                    [term found])))))
                      typos)]
    (cond-> result
      (seq held) (assoc :typos held))))

(defn query
  "The documents of `index` that match the query `q`, best first, as maps
  of :id, :score and :stored, with the `opts` below.

  The query is a string that parse reads, the same query as data, or a
  vector of terms taken as they are. The `opts` are those of parse, and
  these, over the defaults of the index:

  - :limit, the most results
  - :offset, the results to skip first, e.g. for the next page
  - :fuzzy, which words match words with typos too: by default those that
    no document holds, true for all, a number for all with at most that
    many edits, and false for none but those marked with ~ in the query
  - :typo-lengths, the shortest words with one edit and with two, [3 6]
    by default, as the AUTO:3,6 of Elasticsearch
  - :boosts, the weight of a match in each field, 1 by default, where 0
    leaves a field out unless the query names it
  - :aliases, other names that a query can give the fields, as a map of a
    name to a field
  - :k1 and :b, the saturation and length normalization of BM25F
  - :filter, a predicate of a result to keep it
  - :rank, a function of a result to order by, ascending, instead of the
    score
  - :max-completions and :max-expansions, the limits of default-limits

  Equal scores come in the order of their ids, so the order is stable.

  A word with typos has as many edits as the number of :fuzzy, at most
  2, or else as many as its length allows by the :typo-lengths. A match
  with typos weighs less than an exact one. When words matched
  others with typos, the results have :typos in their metadata, a map of
  each such word to those it matched, the closest first. A result that
  holds a word only with typos has :typos too, e.g. for a UI to say so:

      (meta (query idx \"intervew\"))
      ;; => {:typos {\"intervew\" [\"interview\" \"interviews\"]}}"
  ([index q]
   (query index q {}))
  ([index q opts]
   {:pre [(map? opts)]}
   (let [index    (restore index)
         {:keys [limit offset boosts] :or {offset 0} :as opts}
         (merge default-limits (:defaults index) opts)
         segments (:segments index)
         numbers  (:fields index)
         ctx      {:segments        segments
                   :numbers         numbers
                   :weights         (weights numbers
                                             segments
                                             boosts
                                             (:b opts b))
                   :k1              (double (:k1 opts k1))
                   :total           (reduce + 0 (map size segments))
                   :max-completions (:max-completions opts)
                   :max-expansions  (:max-expansions opts)}
         node     (some->> (fuzzy-query q (assoc opts :index index))
                           (prepared ctx))
         found    (if node
                    (matched ctx node)
                    [])
         typos    (typos-of node)
         result   (fn [[id score stored]]
                    {:id id :score score :stored stored})
         ranked   (top found
                       result
                       (some-> limit (+ (long offset)))
                       opts)
         page     (subvec ranked (min (long offset) (count ranked)))]
     (if (seq typos)
       (with-meta (mapv #(with-typos index typos %) page)
                  {:typos (update-vals typos :near)})
       page))))

(defn- leaves
  "The terms and phrases of the query `node` that a match holds, those
  under a :not left out."
  [node]
  (cond
    (contains? node :and) (into [] (mapcat leaves) (:and node))
    (contains? node :or)  (into [] (mapcat leaves) (:or node))
    (contains? node :not) []
    (nil? node)           []
    :else                 [node]))

(defn- term-matches?
  [term prefix? found]
  (if prefix?
    (str/starts-with? found term)
    (= term found)))

(defn- leaf-matches?
  "Whether the term `found` is one that the term `leaf` matches: itself,
  one that starts with it for a prefix, or for a fuzzy term one that has
  its first character and is within its edits."
  [{:keys [term prefix?] :as leaf} found]
  (let [most (long (edits leaf))]
    (boolean
     (or (term-matches? term prefix? found)
         (and (pos? most)
              (= (first term) (first found))
              (some? (distance term found most)))))))

(defn- phrase-end
  "Where the `phrase` that starts with the span `first-span` ends, with
  the last term as a prefix if `prefix?`, from the spans `by-position`,
  or nil when it doesn't follow."
  [by-position first-span phrase prefix?]
  (let [last-i (dec (count phrase))]
    (loop [i 1 end (:end first-span)]
      (if (> i last-i)
        end
        (let [term     (nth phrase i)
              p?       (and prefix? (= i last-i))
              position (+ (long (:position first-span)) i)
              found    (some #(when (term-matches? term p? (:term %)) %)
                             (get by-position position))]
          (when found
            (recur (inc i) (:end found))))))))

(defn- hits
  "The places in the text of `spans` where the terms and phrases of
  `leaves` match, as maps of :start, :end and :leaf, the number of the
  one that matches, in order."
  [spans leaves]
  (let [by-position (delay (group-by :position spans))
        end-of      (fn [span {:keys [term phrase prefix?] :as leaf}]
                      (cond
                        term
                        (when (leaf-matches? leaf (:term span))
                          (:end span))

                        (term-matches? (first phrase)
                                       (and prefix? (= 1 (count phrase)))
                                       (:term span))
                        (phrase-end @by-position span phrase prefix?)))
        add-hits    (fn [acc span]
                      (reduce-kv (fn [acc i leaf]
                                   (if-let [end (end-of span leaf)]
                                     (conj! acc {:start (:start span)
                                                 :end   end
                                                 :leaf  i})
                                     acc))
                                 acc
                                 leaves))]
    (persistent! (reduce add-hits (transient []) spans))))

(defn- merged
  "The `hits`, maps of :start and :end in order, with those that overlap
  or touch made one, e.g. the pairs of characters of a run of Chinese."
  [hits]
  (reduce (fn [acc {:keys [start end] :as hit}]
            (let [last-hit (peek acc)]
              (if (and last-hit (<= start (:end last-hit)))
                (conj (pop acc) (update last-hit :end max end))
                (conj acc hit))))
          []
          (map #(select-keys % [:start :end]) hits)))

(defn- first-at-least
  "The index of the first of the sorted numbers `xs` that is at least `x`,
  or the count of `xs` when there is none."
  [xs x]
  (loop [lo 0 hi (count xs)]
    (if (< lo hi)
      (let [mid (quot (+ lo hi) 2)]
        (if (< (long (nth xs mid)) (long x))
          (recur (inc mid) hi)
          (recur lo mid)))
      lo)))

(def ^:no-doc most-windows
  "The most windows that snippet weighs, those at the first matches."
  100)

;; SQLite FTS5's snippet function: the window that holds the most of the
;; query's phrases, of those that start at one
(defn- window
  "The window of about `width` characters of a text of `length` with
  `spans` that shows the most of the terms and phrases of `hits`, the
  first on a tie, as a pair of where it starts and ends."
  [spans hits width length]
  (let [starts     (mapv :start spans)
        ends       (mapv :end spans)
        hit-starts (mapv :start hits)
        place      (fn [at end-at-least]
                     (let [i    (first-at-least starts at)
                           from (if (< i (count starts)) (nth starts i) at)
                           to   (min length (max (+ from width) end-at-least))
                           j    (dec (first-at-least ends (inc to)))
                           to   (if (and (< to length) (<= 0 j))
                                  (max (nth ends j) from)
                                  to)]
                       [from to]))
        ;; the hits are in order, so those in a window are a range of them
        shown      (fn [[from to]]
                     (let [i (first-at-least hit-starts from)
                           j (first-at-least hit-starts (inc (long to)))]
                       (->> (subvec hits i (max i j))
                            (into #{} (comp (filter #(<= (:end %) to))
                                            (map :leaf)))
                            count)))
        tries  (cons (place 0 0)
                     (for [{:keys [start end]} (take most-windows hits)]
                       (place (max 0 (- start (quot width 3))) end)))]
    (reduce (fn [best-window w]
              (let [score (shown w)]
                (if (> score (:score best-window -1))
                  {:window w :score score}
                  best-window)))
            nil
            tries)))

(defn snippet
  "A window of `text` around the words that match the query `q`, as parts
  for a result list to render, with the `opts` below.

  It's a vector of maps of :text, with :match? on the matches, and an
  ellipsis where the window cuts the text. The text is plain, so escape
  it to put it into HTML. Of the windows at the start
  and at each match, the one with the most of the query's words and
  phrases wins, the first of them on a tie. The `opts` are those of
  parse, and these:

  - :width, the number of characters shown, 160 by default
  - :fuzzy and :typo-lengths, as query takes them, where the words that
    no document holds are those of the :index, and none without one"
  ([text q]
   (snippet text q {}))
  ([text q {:keys [width] :or {width 160} :as opts}]
   {:pre [(map? opts)]}
   (let [opts       (merge (:defaults (:index opts)) opts)
         text       (str text)
         spans      (text/spans text)
         query      (fuzzy-query q opts)
         found      (hits spans (leaves query))
         length     (count text)
         [from to]  (:window (window spans found width length))
         inside     (filter #(and (<= from (:start %)) (<= (:end %) to))
                            found)
         piece      (fn [a b] {:text (subs text a b)})
         parts      (loop [pos  from
                           hits (merged inside)
                           acc  (if (pos? from) [{:text "…"}] [])]
                      (if-let [{:keys [start end]} (first hits)]
                        (let [acc (cond-> acc
                                    (< pos start) (conj (piece pos start)))]
                          (recur end
                                 (rest hits)
                                 (conj acc (assoc (piece start end)
                                                  :match? true))))
                        (cond-> acc
                          (< pos to) (conj (piece pos to)))))]
     (cond-> parts
       (< to length) (conj {:text "…"})))))

(defn- satisfied?
  "Whether the query `node` holds when `found?` tells which of its terms
  and phrases a text has, as query matches a document: all of :and, any
  of :or, but none of those under a :not, and nothing for a :not alone."
  [found? node]
  (let [holds? (fn [op queries]
                 (let [positive (clojure.core/remove :not queries)
                       negative (keep :not queries)
                       ok?      #(satisfied? found? %)]
                   (boolean (and (seq positive)
                                 (if (= :and op)
                                   (every? ok? positive)
                                   (some ok? positive))
                                 (not-any? ok? negative)))))]
    (cond
      (contains? node :and) (holds? :and (:and node))
      (contains? node :or)  (holds? :or (:or node))
      (contains? node :not) false
      (nil? node)           false
      :else                 (found? node))))

(defn matches?
  "Whether `text` matches the query `q`, read with the `opts` of parse,
  and :fuzzy and :typo-lengths as snippet takes them, as one field of a
  document would."
  ([text q]
   (matches? text q {}))
  ([text q opts]
   {:pre [(map? opts)]}
   (let [opts   (merge (:defaults (:index opts)) opts)
         text   (str text)
         terms  (delay (into #{}
                             (mapcat #(if (vector? %) % [%]))
                             (text/tokens text)))
         spans  (delay (text/spans text))
         found? (fn [{:keys [term phrase prefix?] :as leaf}]
                  (cond
                    phrase
                    (boolean (seq (hits @spans [leaf])))

                    (or prefix? (pos? (edits leaf)))
                    (boolean (some #(leaf-matches? leaf %) @terms))

                    :else
                    (contains? @terms term)))]
     (satisfied? found? (fuzzy-query q opts)))))

(comment
  (parse "title:clojure -rust | \"value for va")
  (parse "a b OR c (d | e) -f g*")
  (def idx
    (index [{:id 1 :fields {:title "Clojure and the value of simplicity"}}
            {:id 2 :fields {:title "Rust" :notes "A little Clojure"}}]
           {:boosts {:title 3 :notes 1}}))
  (query idx "cloj")
  (query idx "title:clojure | notes:clojure")
  (snippet "Systems programming, and a little Clojure" "clojure")
  (matches? "Systems programming, and a little Clojure" "clojure -rust")
  #_.)
