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

  The index is a value: add and remove give a changed one, e.g. (add idx
  (document post)) when a post changes, and it prints as EDN, which
  restore reads back. To keep a large one in a store, use
  dk.simongray.podcast.util.text.search.ciff. A query is a string in the
  language of util.text.query, or the same query as data. To show where a
  text matches it, give util.text.query/snippet the options of query-opts.

  Text is analyzed by util.text. The comments name the source of each
  part, and where it departs from it:

  - Robertson and Zaragoza, The Probabilistic Relevance Framework: BM25
    and Beyond, Foundations and Trends in Information Retrieval 3(4),
    2009, https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf
  - Lucene's BM25Similarity and BooleanQuery, and the segments of its
    index, https://lucene.apache.org/core/9_11_1/core/"
  (:refer-clojure :exclude [remove])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.text :as text]
            [dk.simongray.podcast.util.text.query :as text.query])
  #?(:clj (:import [java.io Writer]
                   [java.util Arrays])))

(def ^{:no-doc true :const true} span
  "The block of positions that each field of a document gets.

  Each position of a term in a document is offset by its field's number
  times this. Two positions one apart are then always in the same field.
  A longer field is indexed up to this length."
  1000000)

(defn ^:no-doc make-ints
  "A new array of `n` ints, all 0."
  [n]
  #?(:clj  (int-array n)
     :cljs (js/Int32Array. n)))

(defn- make-doubles
  "A new array of `n` doubles, all 0."
  [n]
  #?(:clj  (double-array n)
     :cljs (js/Float64Array. n)))

(defn ^:no-doc as-ints
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

(defn ^:no-doc slice
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

(defn ^:no-doc copy-ints!
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
;; - name, that of its document in a store, see plan, which an equal
;;   segment can have another of
(declare contents segment-data)

(deftype Segment [ids stored terms doc-start docs pos-start positions lengths
                  ^long width field-stats deleted name]
  #?@(:clj
      [Object
       (equals [this other]
         (and (instance? Segment other)
              (= (contents this) (contents other))))
       (hashCode [_]
         (hash [ids deleted]))]

      :cljs
      [IEquiv
       (-equiv [this other]
         (and (instance? Segment other)
              (= (contents this) (contents other))))

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
  "The fields of `segment` as a map, with its :name as it is, a delay
  until something needs it."
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
   :deleted     (.-deleted segment)
   :name        (.-name segment)})

(defn- contents
  "What `segment` holds as plain data, a map of its fields but its name,
  with its arrays as vectors."
  [segment]
  (reduce #(update %1 %2 as-vector)
          (dissoc (parts segment) :name)
          [:terms :doc-start :docs :pos-start :positions :lengths]))

(defn ^:no-doc segment-name
  "The name of the document of `segment` in a store."
  [^Segment segment]
  (force (.-name segment)))

(defn- segment-data
  "The `segment` as plain data, as it prints: its contents and its name."
  [segment]
  (assoc (contents segment) :name (segment-name segment)))

;; FNV-1a of Fowler, Noll and Vo, over the ints of arrays rather than
;; bytes, in two lanes that read them in opposite orders
(defn- fnv
  "The 32-bit FNV-1a hash `h` with the int `x` mixed in."
  ^long [^long h ^long x]
  #?(:clj  (bit-and (* (bit-xor h (bit-and x 0xFFFFFFFF)) 16777619)
                    0xFFFFFFFF)
     :cljs (unsigned-bit-shift-right (js/Math.imul (bit-xor h x) 16777619)
                                     0)))

(defn- fnv-ints
  "The hash `h` with the count and the ints of the array `arr` mixed in,
  in order, or the other way round when `reversed?`."
  ^long [^long h ^ints arr reversed?]
  (let [n (alength arr)]
    (loop [i 0 h (fnv h n)]
      (if (< i n)
        (recur (inc i) (fnv h (aget arr (if reversed? (- n i 1) i))))
        h))))

(defn- hex
  "The number `h`, below 2 to the power of 32, as eight hex digits."
  [h]
  #?(:clj  (format "%08x" h)
     :cljs (.padStart (.toString h 16) 8 "0")))

(defn- content-name
  "The name of the document of a segment with the parts in `m`, from a hash
  of all that it holds, so that one that holds anything else has another."
  [{:keys [ids stored terms doc-start docs pos-start positions lengths]}]
  (let [arrays [doc-start docs pos-start positions lengths
                (as-ints (mapv hash terms))
                (as-ints [(hash ids) (hash stored)])]
        lane   #(reduce (fn [h arr] (fnv-ints h arr %)) 2166136261 arrays)]
    (str "segment-" (hex (lane false)) (hex (lane true)) ".ciff")))

(defn ^:no-doc segment-of
  "The segment of `m`, a map of its fields, whose arrays can be vectors, as
  segment-data gives them. Without a :name, its name is a hash of what it
  holds, worked out once it's needed."
  [{:keys [ids stored terms doc-start docs pos-start positions lengths
           field-stats deleted name]
    :as   m}]
  (let [arrays {:ids       (vec ids)
                :stored    (vec stored)
                :terms     (as-objects terms)
                :doc-start (as-ints doc-start)
                :docs      (as-ints docs)
                :pos-start (as-ints pos-start)
                :positions (as-ints positions)
                :lengths   (as-ints lengths)}]
    (->Segment (:ids arrays)
               (:stored arrays)
               (:terms arrays)
               (:doc-start arrays)
               (:docs arrays)
               (:pos-start arrays)
               (:positions arrays)
               (:lengths arrays)
               (count field-stats)
               (vec field-stats)
               (set deleted)
               (or name (delay (content-name arrays))))))

(defn ^:no-doc size
  "The number of documents in `segment`, those removed too."
  [^Segment segment]
  (count (.-ids segment)))

(defn ^:no-doc live
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

(defn ^:no-doc field-stats
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

(defn- numbers-from
  "An array of the number of each document of `segment` in a merged
  segment, counting from `base` and skipping those removed, which get
  -1."
  [^Segment segment base]
  (let [n             (long (size segment))
        deleted       (.-deleted segment)
        ^ints numbers (make-ints n)]
    (loop [d 0 j (long base)]
      (when (< d n)
        (if (contains? deleted d)
          (do (aset numbers d -1)
              (recur (inc d) j))
          (do (aset numbers d (int j))
              (recur (inc d) (inc j))))))
    numbers))

(defn- renumbered
  "For each of the `segments`, an array of the number of each of its
  documents in one segment of them all in order, as numbers-from gives
  them."
  [segments]
  (mapv numbers-from segments (reductions + 0 (map live segments))))

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

(defn- merged-lengths
  "The lengths of the fields of the `n` documents of the `segments` that
  their `numbers` keep, `width` of them to a document, as an array."
  [segments numbers n width]
  (let [^ints lengths (make-ints (* (long n) (long width)))]
    (doseq [[^Segment segment ^ints nos] (map vector segments numbers)
            :let  [w (.-width segment)]
            d     (range (size segment))
            :let  [j (aget nos d)]
            :when (<= 0 j)]
      (copy-ints! (.-lengths segment) (* d w) lengths (* j (long width)) w))
    lengths))

;; A merge of sorted lists: each segment has a cursor at its next term,
;; and the least of those terms comes next
(defn- merged-postings
  "The terms of the `segments` and their postings of the documents that
  their `numbers` keep, as the parts of one segment."
  [segments numbers]
  (let [total           (fn [f]
                          (reduce + 0 (map #(alength ^ints (f %)) segments)))
        ^ints docs      (make-ints (total #(.-docs ^Segment %)))
        ^ints pos-start (make-ints (inc (alength docs)))
        positions       (make-ints (total #(.-positions ^Segment %)))
        ^ints cursors   (make-ints (count segments))
        copy-term!      (fn [term [k p] i]
                          (let [^Segment segment (nth segments i)
                                ^objects terms   (.-terms segment)
                                ^ints starts     (.-doc-start segment)
                                c                (aget cursors i)]
                            (if (and (< c (alength terms))
                                     (= term (aget terms c)))
                              (do (aset cursors i (inc c))
                                  (copy-postings! segment (nth numbers i)
                                                  (aget starts c)
                                                  (aget starts (inc c))
                                                  docs pos-start positions
                                                  k p))
                              [k p])))]
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
        (do (aset pos-start k (int p))
            {:terms     (persistent! terms)
             :doc-start (persistent! doc-start)
             :docs      (slice docs 0 k)
             :pos-start (slice pos-start 0 (inc k))
             :positions (slice positions 0 p)})))))

(defn- combined
  "One segment of the `segments` in order, without their documents that
  are removed."
  [segments]
  (let [segments (vec segments)
        numbers  (renumbered segments)
        width    (reduce max 0 (map #(.-width ^Segment %) segments))
        kept     (fn [k]
                   (into []
                         (mapcat #(without-removed (k (parts %))
                                                   (.-deleted ^Segment %)))
                         segments))
        ids      (kept :ids)
        lengths  (merged-lengths segments numbers (count ids) width)]
    (segment-of (assoc (merged-postings segments numbers)
                       :ids         ids
                       :stored      (kept :stored)
                       :lengths     lengths
                       :field-stats (field-stats lengths (count ids) width)
                       :deleted     #{}))))

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

(defn ^:no-doc with-segments
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
  (let [found (select-keys (:docs index) ids)]
    (if (empty? found)
      index
      (let [by-segment (reduce (fn [acc [_ [i no]]]
                                 (update acc i (fnil conj []) no))
                               {}
                               found)]
        (-> index
            (update :segments #(reduce-kv (fn [segments i nos]
                                            (update segments i
                                                    with-removed nos))
                                          %
                                          by-segment))
            (update :docs #(persistent! (reduce dissoc!
                                                (transient %)
                                                (keys found))))
            pruned
            balanced)))))

(defn- latest
  "The `docs` without those that a later one with the same id replaces,
  in order."
  [docs]
  (let [last-at (zipmap (map :id docs) (range))]
    (keep-indexed #(when (= %1 (last-at (:id %2))) %2) docs)))

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
  query-opts. They print with the index, so they're data.

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

(defn ids
  "The ids of the documents in `index`."
  [index]
  (keys (:docs index)))

(def default-limits
  "The limits of query that its options don't set, which keep the cost of
  a query that anyone can type within bounds, over those of
  util.text.query/default-limits:

  - :max-completions, the terms that a prefix stands for, those of the
    most documents, as Xapian takes the 100 most frequent
  - :max-expansions, the terms that a word with typos stands for, the
    closest, as Lucene's FuzzyQuery takes 50"
  {:max-completions 100
   :max-expansions  50})

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
      (let [[docs length] (->> segments
                               (keep #(get (.-field-stats ^Segment %) no))
                               (apply map + [0 0]))
            average       (if (pos? docs)
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
  (let [edits (long (text.query/edits leaf))
        n     (count term)]
    (->> (term-counts segments (subs term 0 1))
         (filter #(<= (Math/abs (- (count (key %)) n)) edits))
         (keep (fn [[found held]]
                 (when-let [d (text.query/distance term found edits)]
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
        fuzzy?  (pos? (long (text.query/edits leaf)))
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
  (text.query/map-leaves #(if (:phrase %)
                            (prepared-phrase ctx %)
                            (prepared-term ctx %))
                         node))

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
  (let [costs   #(map (partial cost segment)
                      (first (text.query/clauses %)))
        held-by #(postings-count (term-ranges segment %))]
    (case (text.query/kind node)
      :and    (reduce min ##Inf (costs (:and node)))
      :or     (reduce + 0 (costs (:or node)))
      :phrase (reduce min ##Inf (map held-by (:places node)))
      :term   (held-by (concat (:terms (:summed node)) (:terms (:typos node))))
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
  (let [[positive negative] (text.query/clauses queries)
        [rarest & others]   (sort-by #(cost segment %) positive)
        narrowed            (fn [found q]
                              (if (zero? (hit-count found))
                                (reduced found)
                                (->> (hits-in ctx segment q (:docs found))
                                     (added-up found))))]
    (if rarest
      (->> (reduce narrowed (hits-in ctx segment rarest candidates) others)
           (excluded ctx segment negative))
      (no-hits))))

(defn- any-of
  "The hits in `segment` among the `candidates` of the documents that
  match any of the prepared `queries` but those under a :not, with the
  constants of `ctx`."
  [ctx segment queries candidates]
  (let [[positive negative] (text.query/clauses queries)]
    (if (seq positive)
      (->> positive
           (map #(hits-in ctx segment % candidates))
           (reduce (partial joined :+))
           (excluded ctx segment negative))
      (no-hits))))

(defn- term-hits
  "The hits in `segment` among the `candidates` of the prepared term
  `leaf`, with the saturation of `ctx`: the best of those of its :summed
  terms and of each of its :typos terms at its boost."
  [{:keys [k1]} segment {:keys [weights summed typos]} candidates]
  (let [scored (fn [terms idf]
                 (-> (range-frequencies segment
                                        weights
                                        (term-ranges segment terms)
                                        candidates)
                     (saturated idf k1)))
        exact  (when summed
                 [(scored (:terms summed) (:idf summed))])
        close  (map (fn [term boost]
                      (scored [term] (* (double boost) (:idf typos))))
                    (:terms typos)
                    (:boosts typos))]
    (if-let [parts (seq (concat exact close))]
      (reduce (partial joined :max) parts)
      (no-hits))))

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
  (case (text.query/kind node)
    :and    (all-of ctx segment (:and node) candidates)
    :or     (any-of ctx segment (:or node) candidates)
    :phrase (phrase-hits ctx segment node candidates)
    :term   (term-hits ctx segment node candidates)
    (no-hits)))

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
        (text.query/leaves node)))

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

(defn- name-of
  [field]
  (if (keyword? field)
    (name field)
    (str field)))

(defn query-opts
  "The `opts` over the defaults of `index` for the functions of
  util.text.query, so that they read a query as query does.

  Besides the defaults, they hold the names of the fields among the
  :aliases, and :known?, which tells the terms that the documents hold."
  ([index]
   (query-opts index {}))
  ([index opts]
   (let [{:keys [defaults fields segments]} (restore index)
         opts  (merge defaults opts)
         names (into {} (for [field (keys fields)]
                          [(name-of field) field]))]
     (assoc opts
            :aliases (merge names (:aliases opts))
            :known?  #(known? segments %)))))

(defn query
  "The documents of `index` that match the query `q`, best first, as maps
  of :id, :score and :stored, with the `opts` below.

  The query is a string that util.text.query/parse reads, the same query
  as data, or a vector of terms taken as they are. The `opts` are those
  of parse, and these, over the defaults of the index:

  - :limit, the most results
  - :offset, the results to skip first, e.g. for the next page
  - :fuzzy, which words match words with typos too: by default those that
    no document holds, true for all, a number for all with at most that
    many edits, and false for none but those marked with ~ in the query
  - :typo-lengths, the shortest words with one edit and with two, [3 6]
    by default, as the AUTO:3,6 of Elasticsearch
  - :boosts, the weight of a match in each field, 1 by default, where 0
    leaves a field out unless the query names it
  - :fields, the set of fields to search, all by default, which gives the
    other fields the weight 0
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
         boosts   (if-let [only (:fields opts)]
                    (into {} (for [field (keys numbers)]
                               [field (if (contains? only field)
                                        (get boosts field 1)
                                        0)]))
                    boosts)
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
         node     (some->> (text.query/fuzzy-query q (query-opts index opts))
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

(comment
  (def idx
    (index [{:id 1 :fields {:title "Clojure and the value of simplicity"}}
            {:id 2 :fields {:title "Rust" :notes "A little Clojure"}}]
           {:boosts {:title 3 :notes 1}}))
  (query idx "cloj")
  (query idx "title:clojure | notes:clojure")
  (text.query/parse "notes:clojure" (query-opts idx))
  (text.query/snippet "A little Clojure" "notes:clojure" (query-opts idx))
  #_.)
