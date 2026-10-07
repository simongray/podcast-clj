(ns dk.simongray.podcast.util.text.search
  "A full-text index of documents as a plain map, and queries of it ranked
  by BM25F, on both platforms.

  A document is a map of its :id, its :fields, each a string or a vector
  of terms, and what a result gives back, under :stored. A query is a
  string in the language that parse reads, or the same query as data:

      (def idx (index [{:id 1 :fields {:title \"Clojure\" :notes \"Data\"}}]))
      (query idx \"title:cloj* -rust\")

  Text is analyzed by util.text. The comments name the source of each
  part, and where it departs from it:

  - Robertson and Zaragoza, The Probabilistic Relevance Framework: BM25
    and Beyond, Foundations and Trends in Information Retrieval 3(4),
    2009, https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf
  - Lucene's BM25Similarity and BooleanQuery,
    https://lucene.apache.org/core/9_11_1/core/
  - Elasticsearch's simple_query_string,
    https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-simple-query-string-query
  - SQLite FTS5's query syntax and its snippet function,
    https://www.sqlite.org/fts5.html"
  (:refer-clojure :exclude [remove])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.text :as text]))

(def ^{:no-doc true :const true} span
  "The block of positions that each field of a document gets.

  The positions of a term in a document are in one vector, each offset by
  its field's number times this. Two positions one apart are then always
  in the same field. A longer field is indexed up to this length."
  1000000)

(declare add)

(defn index
  "An index of `docs` with the `opts` below, or an empty one to add to.

  The `opts` are the defaults of query on this index, e.g. the :boosts of
  its fields and the :aliases that a query can name them by. They print
  with the index, so they're data.

  The index is a plain map that prints as EDN. Read back, its terms are a
  hash set, which restore sorts again."
  ([]
   {:docs {} :postings {} :terms (sorted-set) :fields {}})
  ([docs]
   (index docs {}))
  ([docs opts]
   {:pre [(map? opts)]}
   (cond-> (reduce add (index) docs)
     (seq opts) (assoc :defaults opts))))

(defn restore
  "The `index` read back from EDN, with its terms sorted again, so that a
  prefix reads a range of them rather than every one."
  [index]
  (update index :terms #(into (sorted-set) %)))

(defn remove
  "The `index` without the document `id`."
  [index id]
  (if-let [{:keys [lengths terms]} (get-in index [:docs id])]
    (let [known    (:postings index)
          gone     (filter #(= 1 (count (get known %))) terms)
          postings (persistent!
                    (reduce (fn [postings term]
                              (let [docs (dissoc (get known term) id)]
                                (if (empty? docs)
                                  (dissoc! postings term)
                                  (assoc! postings term docs))))
                            (transient known) terms))]
      (-> index
          (update :docs dissoc id)
          (assoc :postings postings)
          (update :terms #(reduce disj % gone))
          (update :fields
                  (fn [fields]
                    (reduce-kv (fn [fields field n]
                                 (-> fields
                                     (update-in [field :docs] dec)
                                     (update-in [field :length] - n)))
                               fields lengths)))))
    index))

(defn- analyze
  "The terms of the field value `v` by position: a string tokenized, a
  vector taken as terms already, or each string of any other collection
  tokenized."
  [v]
  (cond
    (string? v) (text/tokens v)
    (vector? v) v
    :else       (into [] (mapcat text/tokens) v)))

(defn- numbered
  "The `fields` of an index with a number for each new field of
  `analyzed`, in order of appearance."
  [fields analyzed]
  (reduce (fn [fields field]
            (if (contains? fields field)
              fields
              (assoc fields field {:no (count fields) :docs 0 :length 0})))
          fields (keys analyzed)))

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
  position or a vector of them. Each is offset into the block of its field
  by the field `numbers`, and the terms of a vector share a position."
  [numbers analyzed]
  (persistent!
   (reduce-kv (fn [acc field terms]
                (let [base (long (* span (get numbers field)))]
                  (loop [i 0 terms (seq terms) acc acc]
                    (if (and terms (< i span))
                      (let [term (first terms)
                            at   (+ base i)
                            acc  (if (vector? term)
                                   (reduce #(placed %1 %2 at) acc term)
                                   (placed acc term at))]
                        (recur (inc i) (next terms) acc))
                      acc))))
              (transient {}) analyzed)))

;; A field's length counts its positions, so a term that shares one adds
;; nothing, as discountOverlaps does in Lucene's BM25Similarity
(defn add
  "The `index` with the document `doc`, a map of :id, :fields and :stored,
  replacing any earlier one with the same id."
  [index {:keys [id fields stored] :as doc}]
  (when (nil? id)
    (throw (ex-info "A document needs an :id" {:document doc})))
  (let [index    (remove index id)
        analyzed (into {} (for [[field v] fields
                                :let [terms (analyze v)]
                                :when (seq terms)]
                            [field terms]))
        fields   (numbered (:fields index) analyzed)
        by-term  (positions (update-vals fields :no) analyzed)
        known    (:postings index)
        ;; the postings' own string for a term they already hold, so that an
        ;; index keeps one copy of each term
        terms    (mapv #(if-let [e (find known %)] (key e) %) (keys by-term))
        postings (persistent!
                  (reduce-kv (fn [postings term at]
                               (let [docs (assoc (get postings term) id at)]
                                 (assoc! postings term docs)))
                             (transient known) by-term))]
    (-> index
        (assoc-in [:docs id] {:stored  stored
                              :lengths (update-vals analyzed count)
                              :terms   terms})
        (assoc :postings postings)
        (update :terms into (filter #(not (contains? known %))) terms)
        (assoc :fields
               (reduce-kv (fn [fields field terms]
                            (-> fields
                                (update-in [field :docs] inc)
                                (update-in [field :length] + (count terms))))
                          fields analyzed)))))

;; The operators that Lucene's simple query parser, Elasticsearch's
;; simple_query_string and SQLite FTS5 share, with ! for NOT as in many
;; programming languages
(def syntax
  "The spellings of the operators of the query language that parse reads.

  A spelling of letters, e.g. AND, is an operator only as a word of its
  own, and a sign, e.g. &, inside a word too. A sign of :not counts only
  at the start of a word, one of :field only after the name of a field,
  and one of :prefix only at the end of a word."
  {:and    #{"AND" "&"}
   :or     #{"OR" "|"}
   :not    #{"NOT" "!" "-"}
   :field  #{":" "="}
   :prefix #{"*"}})

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
  "The lexemes of the query `q` that `pattern` finds, as maps of :text,
  :start and :end."
  [pattern q]
  (loop [found (re-seq pattern q) from 0 acc []]
    (if-let [[s & more] (seq found)]
      (let [start (long (str/index-of q s from))
            end   (+ start (count s))]
        (recur more end (conj acc {:text s :start start :end end})))
      acc)))

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

(defn- word-lexemes
  "The lexemes of the word `raw` of a query in `syntax` that can name
  `fields`: a :not for a sign at its start, a :field for a field's name
  and a sign after it, and the :word, with :prefix? when it ends in a
  sign of one."
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
        word         (subs word 0 (- (count word) (count prefix-sign)))]
    (cond-> []
      not-sign    (conj {:kind :not :start start :end after-not})
      name        (conj {:kind  :field
                         :field (get fields (text/fold name))
                         :start after-not
                         :end   after-field})
      (seq word)  (conj {:kind    :word
                         :text    word
                         :prefix? (some? prefix-sign)
                         :start   after-field
                         :end     end}))))

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
  :typing? when nothing follows it."
  [q syntax fields]
  (let [found (into []
                    (mapcat #(lexemes-of % syntax fields))
                    (raw-lexemes (lexeme-pattern syntax) q))
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
  only start."
  [{:keys [text prefix? typing?] :as lexeme} {:keys [complete? min-prefix]}]
  (let [tokens  (text/tokens text)
        terms   (mapv #(if (vector? %) (first %) %) tokens)
        prefix? (or prefix?
                    (and typing?
                         complete?
                         (<= (long min-prefix) (count (peek terms)))))]
    (case (count terms)
      0 nil
      1 (let [term  (first terms)
              lone? (and (string? (first tokens))
                         (text/unspaced? term))]
          (cond-> {:term term}
            (or prefix? lone?) (assoc :prefix? true)))
      (cond-> {:phrase terms}
        prefix? (assoc :prefix? true)))))

(defn- in-field
  "The query `node` with `field` on each of its terms and phrases that
  names no field of its own."
  [node field]
  (let [each #(mapv (fn [n] (in-field n field)) %)]
    (cond
      (contains? node :and) (update node :and each)
      (contains? node :or)  (update node :or each)
      (contains? node :not) (update node :not in-field field)
      (:field node)         node
      :else                 (assoc node :field field))))

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
      :open   (let [[node j] (parse-or ctx (inc i))
                    closing  (get-in ctx [:lexemes j])]
                [node (if (= :close (:kind closing)) (inc j) j)])
      :phrase [(word-query lexeme ctx) (inc i)]
      :word   [(word-query lexeme ctx) (inc i)]
      [nil i])))

(defn- parse-unary
  [ctx i]
  (if (= :not (get-in ctx [:lexemes i :kind]))
    (let [[node j] (parse-unary ctx (inc i))]
      [(when node {:not node}) j])
    (parse-atom ctx i)))

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

(defn parse
  "The query `q`, a string, as data, read with the `opts` below, or nil
  when it asks for nothing.

  Words must all occur, and the last is a prefix to complete unless `q`
  ends in a space or a quote. The operators are those of syntax: AND, OR
  and NOT, or & | ! and - before a word, with parentheses, \"a phrase\",
  a prefix* and field:word or field=word for a word in one field:

      (parse \"title:clojure -rust\")
      ;; => {:and [{:term \"clojure\" :field :title}
      ;;           {:not {:term \"rust\" :prefix? true}}]}

  A query as data is a map of :and or :or with a vector of queries, of
  :not with a query, of :term with a term, or of :phrase with a vector
  of terms in a row. A term or phrase has :prefix? when its last term is
  a prefix, and :field when it's in that field only.

  The `opts` are:

  - :syntax, the spellings of operators over those of syntax, e.g. OG
    for AND in Danish with {:and #{\"OG\" \"&\"}}
  - :fields, the fields that a query can name, as a map of a name to the
    field
  - :operator, :and or :or between words without one, :and by default
  - :prefix?, false to take the last word whole
  - :min-prefix, the fewest characters of a last word to complete, 1 by
    default

  Nothing is an error, and a sign or a parenthesis that makes no sense
  counts for nothing."
  ([q]
   (parse q {}))
  ([q {:keys [operator prefix? min-prefix]
       :or   {operator :and prefix? true min-prefix 1}
       :as   opts}]
   {:pre [(map? opts)]}
   (let [spellings (merge syntax (:syntax opts))
         fields    (update-keys (or (:fields opts) {}) text/fold)
         q         (spaced (str q))
         ctx       {:lexemes    (lexemes q spellings fields)
                    :operator   operator
                    :complete?  prefix?
                    :min-prefix min-prefix}
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

(defn- weights
  "The constants of BM25F for each field of `index`, by field number: its
  name, its weight from `boosts`, 1 by default, and a and c of its length
  normalization a + c length, which fold in `b` and the field's average
  length."
  [index boosts b]
  (let [b (double b)]
    (->> (for [[name {:keys [no docs length]}] (:fields index)
               :let [average (if (pos? (long docs))
                               (/ (double length) (long docs))
                               1.0)]]
           [no {:name   name
                :weight (double (get boosts name 1))
                :a      (- 1.0 b)
                :c      (/ b average)}])
         (into (sorted-map))
         vals
         vec)))

(defn- restricted
  "The `weights` of the fields with all but `field` left out, and `field`
  at its weight, or 1 when that is 0, since a query names it."
  [weights field]
  (mapv (fn [w]
          (cond
            (not= field (:name w))  (assoc w :weight 0.0)
            (zero? (:weight w))     (assoc w :weight 1.0)
            :else                   w))
        weights))

;; BM25F, Robertson and Zaragoza 2009, section 3.6, equation 3.19: each
;; occurrence counts the weight of its field, divided by the field's
;; length normalization
(defn- frequency
  "BM25F's frequency of a term in the document `id` of `index`, which has
  it at the positions `at`, one or a vector of them, with the `weights`
  of the fields."
  ^double [index weights id at]
  (let [lengths (:lengths (get (:docs index) id))
        weigh   (fn [sum position]
                  (let [field  (nth weights (quot (long position) span))
                        weight (double (:weight field))]
                    (if (zero? weight)
                      sum
                      (let [length (long (get lengths (:name field)))
                            norm   (+ (double (:a field))
                                      (* (double (:c field)) length))]
                        (+ (double sum) (/ weight norm))))))]
    (double (if (vector? at)
              (reduce weigh 0.0 at)
              (weigh 0.0 at)))))

(defn- saturated
  "The scores of the documents in `frequencies`, a map of id to BM25F's
  frequency, for a term of the inverse document frequency `idf`, with the
  saturation `k1`."
  [frequencies idf k1]
  (let [idf (double idf)
        k1  (double k1)]
    (persistent!
     (reduce-kv (fn [acc id f]
                  (let [f (double f)]
                    (assoc! acc id (* idf (/ f (+ k1 f))))))
                (transient {})
                frequencies))))

(defn- expansions
  "The terms of `index` that start with `prefix`: a range of them when
  they're sorted, and a scan when the index was read back from EDN."
  [index prefix]
  (let [terms (:terms index)]
    (if (sorted? terms)
      (subseq terms >= prefix < (str prefix "\uFFFF"))
      (filter #(str/starts-with? % prefix) terms))))

(defn- term-frequencies
  "A map of id to BM25F's frequency for each document of `index` with one
  of `terms` in a field of the `weights`, the frequencies of all the
  terms added up."
  [index weights terms]
  (let [postings (:postings index)]
    (persistent!
     (reduce (fn [acc term]
               (reduce-kv (fn [acc id at]
                            (let [f (frequency index weights id at)]
                              (if (pos? f)
                                (assoc! acc id (+ f (double (get acc id 0.0))))
                                acc)))
                          acc
                          (get postings term)))
             (transient {})
             terms))))

;; A prefix counts as one term of all the terms it starts, with the number
;; of documents that hold any of them. Lucene blends the expansions of a
;; prefix to the largest document frequency, so that the rarest one doesn't
;; outrank the rest
(defn- term-scores
  "The scores of the documents that hold the `term`, or with :prefix? a
  term that starts with it, with the constants of `ctx`."
  [{:keys [index weights k1 total]} {:keys [term prefix?]}]
  (let [terms       (if prefix?
                      (expansions index term)
                      [term])
        frequencies (term-frequencies index weights terms)]
    (saturated frequencies (idf total (count frequencies)) k1)))

(defn- prefix-postings
  "The postings of the terms of `index` that start with `prefix`, as a map
  of id to a vector of positions."
  [index prefix]
  (let [postings (:postings index)]
    (persistent!
     (reduce (fn [acc term]
               (reduce-kv (fn [acc id at]
                            (assoc! acc id (if (vector? at)
                                             (into (get acc id []) at)
                                             (conj (get acc id []) at))))
                          acc
                          (get postings term)))
             (transient {})
             (expansions index prefix)))))

(defn- positions-in
  [postings id]
  (let [at (get postings id)]
    (if (vector? at) at [at])))

(defn- phrase-starts
  "The positions in the document `id` where the terms of `lists`, the
  postings of each, start in a row."
  [lists id]
  (let [later (mapv #(set (positions-in % id)) (rest lists))]
    (filterv (fn [start]
               (every? (fn [i]
                         (contains? (nth later i) (+ (long start) i 1)))
                       (range (count later))))
             (positions-in (first lists) id))))

;; Lucene's PhraseQuery: the inverse document frequency of a phrase is the
;; sum of those of its terms
(defn- phrase-scores
  "The scores of the documents that hold the terms of `phrase` in a row,
  the last as a prefix with :prefix?, with the constants of `ctx`."
  [{:keys [index weights k1 total]} {:keys [phrase prefix?]}]
  (let [postings    (:postings index)
        lists       (conj (mapv #(get postings %) (pop phrase))
                          (if prefix?
                            (prefix-postings index (peek phrase))
                            (get postings (peek phrase))))
        [fewest & others] (sort-by count lists)
        candidates  (filter (fn [id] (every? #(contains? % id) others))
                            (keys fewest))
        frequencies (reduce (fn [acc id]
                              (let [starts (phrase-starts lists id)
                                    f      (if (seq starts)
                                             (frequency index weights id starts)
                                             0.0)]
                                (if (pos? f)
                                  (assoc acc id f)
                                  acc)))
                            {}
                            candidates)
        idf         (reduce + (map #(idf total (count %)) lists))]
    (saturated frequencies idf k1)))

(declare scores)

(defn- intersected
  "The ids in all the maps `ms` of id to score, with their scores added."
  [ms]
  (let [[fewest & others] (sort-by count ms)]
    (persistent!
     (reduce-kv (fn [acc id score]
                  (if (every? #(contains? % id) others)
                    (assoc! acc id (reduce #(+ (double %1) (double (get %2 id)))
                                           score
                                           others))
                    acc))
                (transient {})
                fewest))))

;; Lucene's BooleanQuery: the scores of the clauses that match add up, and
;; a clause of :not, MUST_NOT, only leaves documents out
(defn- combined
  "The scores of the documents that match all `queries` when `op` is :and,
  or any of them when it's :or, and none of those under a :not, with the
  constants of `ctx`."
  [ctx op queries]
  (let [positive (mapv #(scores ctx %) (clojure.core/remove :not queries))
        negative (keep :not queries)
        joined   (cond
                   (empty? positive) {}
                   (= :and op)       (intersected positive)
                   :else             (apply merge-with + positive))]
    (if (and (seq negative) (seq joined))
      (let [excluded (mapv #(scores ctx %) negative)
            out?     (fn [id] (some #(contains? % id) excluded))]
        (persistent!
         (reduce (fn [acc id]
                   (if (out? id)
                     (dissoc! acc id)
                     acc))
                 (transient joined)
                 (keys joined))))
      joined)))

(defn- in-its-field
  "The `ctx` of a term or phrase `node`, with all fields but its own left
  out when it names one."
  [ctx node]
  (if-let [field (:field node)]
    (update ctx :weights restricted field)
    ctx))

(defn- scores
  "The scores of the documents that match the query `node`, as a map of
  id to score, with the constants of `ctx`. A query of :not alone matches
  nothing."
  [ctx node]
  (cond
    (contains? node :and)    (combined ctx :and (:and node))
    (contains? node :or)     (combined ctx :or (:or node))
    (contains? node :phrase) (phrase-scores (in-its-field ctx node) node)
    (contains? node :term)   (term-scores (in-its-field ctx node) node)
    :else                    {}))

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

(defn- as-query
  "The query `q` of `index` as data: a string parsed with the `opts` and
  the names of the fields, a vector of terms taken as they are, or the
  data itself."
  [index q opts]
  (cond
    (string? q) (parse q (assoc opts
                                :fields
                                (field-names index (:aliases opts))))
    (vector? q) (combine (:operator opts :and) (mapv #(hash-map :term %) q))
    :else       q))

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
  "A comparator of the pairs of id and score `a` and `b`: the higher score
  first, then the lower id.

  Ids that aren't both numbers compare by hash and then as strings, so
  that the order is total and the same on both platforms, which hash
  numbers differently. BM25 ties are common, so this must stay cheap."
  [a b]
  (let [sa (double (val a))
        sb (double (val b))]
    (cond
      (> sa sb) -1
      (< sa sb) 1
      :else     (compare-ids (key a) (key b)))))

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

(defn query
  "The documents of `index` that match the query `q`, best first, as maps
  of :id, :score and :stored, with the `opts` below.

  The query is a string that parse reads, the same query as data, or a
  vector of terms taken as they are. The `opts` are those of parse, and
  these, over the defaults of the index:

  - :limit, the most results
  - :boosts, the weight of a match in each field, 1 by default, where 0
    leaves a field out unless the query names it
  - :aliases, other names that a query can give the fields, as a map of a
    name to a field
  - :k1 and :b, the saturation and length normalization of BM25F
  - :filter, a predicate of a result to keep it
  - :rank, a function of a result to order by, ascending, instead of the
    score

  Equal scores come in the order of their ids, so the order is stable."
  ([index q]
   (query index q {}))
  ([index q opts]
   {:pre [(map? opts)]}
   (let [{:keys [limit boosts rank] keep? :filter :as opts}
         (merge (:defaults index) opts)
         node   (as-query index q opts)
         ctx    {:index   index
                 :weights (weights index boosts (:b opts b))
                 :k1      (double (:k1 opts k1))
                 :total   (count (:docs index))}
         found  (if node
                  (scores ctx node)
                  {})
         result (fn [[id score]]
                  {:id     id
                   :score  score
                   :stored (get-in index [:docs id :stored])})]
     (cond
       rank
       (->> found
            (map result)
            (filter (or keep? any?))
            (map (fn [r] [(rank r) r]))
            (sort-by first)
            (map second)
            (#(if limit (take limit %) %))
            vec)

       ;; a filter that costs, e.g. criteria of a library, reads the best
       ;; first, and more of them only when too few are kept
       (and keep? limit)
       (loop [n (* 2 (max 1 (long limit))) read 0 kept []]
         (let [top  (best better n found)
               kept (into kept
                          (comp (filter #(keep? (result %)))
                                (take (- (long limit) (count kept))))
                          (subvec top read))]
           (if (or (= (count kept) limit)
                   (>= n (count found)))
             (mapv result kept)
             (recur (* 4 n) (count top) kept))))

       :else
       (->> (if keep?
              (filter #(keep? (result %)) found)
              found)
            (best better limit)
            (mapv result))))))

(defn- leaves
  "The terms and phrases of the query `node` that a match holds, those
  under a :not left out."
  [node]
  (cond
    (contains? node :and) (mapcat leaves (:and node))
    (contains? node :or)  (mapcat leaves (:or node))
    (contains? node :not) []
    (nil? node)           []
    :else                 [node]))

(defn- term-matches?
  [term prefix? found]
  (if prefix?
    (str/starts-with? found term)
    (= term found)))

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
  (let [by-position (group-by :position spans)]
    (->> (for [[i {:keys [term phrase prefix?]}] (map-indexed vector leaves)
               span spans
               :let [end (cond
                           term
                           (when (term-matches? term prefix? (:term span))
                             (:end span))

                           (term-matches? (first phrase)
                                          (and prefix? (= 1 (count phrase)))
                                          (:term span))
                           (phrase-end by-position span phrase prefix?))]
               :when end]
           {:start (:start span) :end end :leaf i})
         (sort-by :start)
         vec)))

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
  (let [starts (mapv :start spans)
        ends   (mapv :end spans)
        place  (fn [at end-at-least]
                 (let [i    (first-at-least starts at)
                       from (if (< i (count starts)) (nth starts i) at)
                       to   (min length (max (+ from width) end-at-least))
                       j    (dec (first-at-least ends (inc to)))
                       to   (if (and (< to length) (<= 0 j))
                              (max (nth ends j) from)
                              to)]
                   [from to]))
        shown  (fn [[from to]]
                 (->> hits
                      (filter #(and (<= from (:start %)) (<= (:end %) to)))
                      (map :leaf)
                      distinct
                      count))
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
  ellipsis where the window cuts the text. Of the windows at the start
  and at each match, the one with the most of the query's words and
  phrases wins, the first of them on a tie. The `opts` are those of
  parse, and these:

  - :width, the number of characters shown, 160 by default
  - :index, an index whose defaults and fields the query is read with"
  ([text q]
   (snippet text q {}))
  ([text q {:keys [width] :or {width 160} :as opts}]
   {:pre [(map? opts)]}
   (let [index      (:index opts)
         opts       (merge (:defaults index) opts)
         text       (str text)
         spans      (text/spans text)
         found      (hits spans (leaves (as-query index q opts)))
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
  as one field of a document would, without an index."
  ([text q]
   (matches? text q {}))
  ([text q opts]
   {:pre [(map? opts)]}
   (let [text   (str text)
         terms  (delay (into #{}
                             (mapcat #(if (vector? %) % [%]))
                             (text/tokens text)))
         spans  (delay (text/spans text))
         found? (fn [{:keys [term phrase prefix?] :as leaf}]
                  (cond
                    phrase  (boolean (seq (hits @spans [leaf])))
                    prefix? (boolean (some #(str/starts-with? % term) @terms))
                    :else   (contains? @terms term)))]
     (satisfied? found? (as-query nil q opts)))))

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
