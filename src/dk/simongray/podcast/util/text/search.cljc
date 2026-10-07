(ns dk.simongray.podcast.util.text.search
  "A full-text index of documents as a plain map, and queries of it ranked
  by BM25F, on both platforms.

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

(declare added)

(defn index
  "An index of `docs` with the `opts` below, or an empty one to add to.

  The `opts` are the defaults of query on this index, e.g. the :boosts of
  its fields and the :aliases that a query can name them by, and of
  parse, snippet and matches? when their options name the :index. They
  print with the index, so they're data.

  The index is a plain map that prints as EDN. Read back, its terms are a
  hash set, which restore sorts again."
  ([]
   {:docs {} :postings {} :terms (sorted-set) :fields {}})
  ([docs]
   (index docs {}))
  ([docs opts]
   {:pre [(map? opts)]}
   (cond-> (added (index) docs)
     (seq opts) (assoc :defaults opts))))

(defn restore
  "The `index` read back from EDN, with its terms sorted again, so that a
  prefix reads a range of them rather than every one."
  [index]
  (update index :terms #(into (sorted-set) %)))

(defn- uncounted
  "The `fields` of an index without the fields of the `lengths` of a
  document, a map of a field to the number of its positions."
  [fields lengths]
  (reduce-kv (fn [fields field n]
               (-> fields
                   (update-in [field :docs] dec)
                   (update-in [field :length] - n)))
             fields
             lengths))

(defn- unposted
  "The transient `touched` postings of terms with the document `id` taken
  out of those of its `terms`. The first time a term is touched, its
  postings come from `known`."
  [touched known id terms]
  (reduce (fn [touched term]
            (let [postings (or (get touched term)
                               (transient (get known term {})))]
              (assoc! touched term (dissoc! postings id))))
          touched
          terms))

;; Changes of many documents go through transients, so that the postings
;; of a term are copied once rather than once for each document
(defn- removed
  "The `index` without the documents of `ids`."
  [index ids]
  (let [known   (:postings index)
        entries (keep #(some->> (get-in index [:docs %]) (vector %))
                      (distinct ids))]
    (if (empty? entries)
      index
      (let [touched  (persistent!
                      (reduce (fn [touched [id {:keys [terms]}]]
                                (unposted touched known id terms))
                              (transient {})
                              entries))
            postings (persistent!
                      (reduce-kv (fn [acc term postings]
                                   (let [postings (persistent! postings)]
                                     (if (empty? postings)
                                       (dissoc! acc term)
                                       (assoc! acc term postings))))
                                 (transient known)
                                 touched))
            gone     (filter #(not (contains? postings %)) (keys touched))
            docs     (persistent! (reduce dissoc!
                                          (transient (:docs index))
                                          (map first entries)))
            lengths  (map (comp :lengths second) entries)]
        (-> index
            (assoc :docs docs :postings postings)
            (update :terms #(reduce disj % gone))
            (update :fields #(reduce uncounted % lengths)))))))

(defn remove
  "The `index` without the document `id`, and the `ids` after it."
  ([index]
   index)
  ([index id & ids]
   (removed index (cons id ids))))

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
                (let [base (long (* span (get numbers field)))
                      n    (min span (count terms))]
                  (loop [i 0 acc acc]
                    (if (< i n)
                      (let [term (nth terms i)
                            at   (+ base i)
                            acc  (if (vector? term)
                                   (reduce #(placed %1 %2 at) acc term)
                                   (placed acc term at))]
                        (recur (inc i) acc))
                      acc))))
              (transient {}) analyzed)))

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

(defn- counted
  "The `fields` of an index with the `analyzed` fields of a document
  counted in."
  [fields analyzed]
  (reduce-kv (fn [fields field terms]
               (-> fields
                   (update-in [field :docs] inc)
                   (update-in [field :length] + (count terms))))
             fields
             analyzed))

(defn- interned
  "The one string of `term` that an index keeps, so that its documents
  share it, from the transient map `seen` of the new terms since the
  `known` postings, or the postings themselves, or nil for a new term."
  [known seen term]
  (or (get seen term)
      (some-> (find known term) key)))

(defn- posted
  "The transient `touched` postings of terms with the document `id` at the
  positions `at` of `term`. The first time a term is touched, its postings
  come from `known`."
  [touched known term id at]
  (let [postings (or (get touched term)
                     (transient (get known term {})))]
    (assoc! touched term (assoc! postings id at))))

;; A field's length counts its positions, so a term that shares one adds
;; nothing, as discountOverlaps does in Lucene's BM25Similarity
(defn- with-document
  "The parts of an index that `built` holds, with the document `doc` in
  them: the transient :docs, :touched postings and :seen terms, and the
  :fields. The `known` postings are those of the index."
  [known {:keys [docs touched seen fields]} {:keys [id stored] :as doc}]
  (let [analyzed (analyzed-fields (:fields doc))
        fields   (numbered fields analyzed)
        by-term  (positions (update-vals fields :no) analyzed)
        [touched seen terms]
        (reduce-kv (fn [[touched seen terms] term at]
                     (let [same (interned known seen term)
                           seen (if same seen (assoc! seen term term))
                           term (or same term)]
                       [(posted touched known term id at)
                        seen
                        (conj! terms term)]))
                   [touched seen (transient [])]
                   by-term)]
    {:docs    (assoc! docs id {:stored  stored
                               :lengths (update-vals analyzed count)
                               :terms   (persistent! terms)})
     :touched touched
     :seen    seen
     :fields  (counted fields analyzed)}))

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

(defn- added
  "The `index` with the documents `docs`, as add gives it."
  [index docs]
  (when-let [doc (some #(when (nil? (:id %)) %) docs)]
    (throw (ex-info "A document needs an :id" {:document doc})))
  (let [docs     (latest docs)
        index    (removed index (map :id docs))
        known    (:postings index)
        built    (reduce #(with-document known %1 %2)
                         {:docs    (transient (:docs index))
                          :touched (transient {})
                          :seen    (transient {})
                          :fields  (:fields index)}
                         docs)
        postings (persistent!
                  (reduce-kv (fn [acc term postings]
                               (assoc! acc term (persistent! postings)))
                             (transient known)
                             (persistent! (:touched built))))]
    (-> index
        (assoc :docs     (persistent! (:docs built))
               :postings postings
               :fields   (:fields built))
        (update :terms into (keys (persistent! (:seen built)))))))

(defn add
  "The `index` with the document `doc`, and the `docs` after it, each a map
  of :id, :fields and :stored. A document replaces any earlier one with
  the same id."
  ([index]
   index)
  ([index doc & docs]
   (added index (cons doc docs))))

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
(defn- occurrence
  "What an occurrence of a term in the field numbered `no` counts in a
  document whose fields have the `lengths`, with the `weights` of the
  fields."
  ^double [weights lengths ^long no]
  (let [field  (nth weights no)
        weight (double (:weight field))]
    (if (zero? weight)
      0.0
      (let [length (long (get lengths (:name field)))
            norm   (+ (double (:a field))
                      (* (double (:c field)) length))]
        (/ weight norm)))))

(defn- frequency
  "BM25F's frequency of a term in the document `id` of the `docs` of an
  index, which has it at the positions `at`, one or a vector of them,
  with the `weights` of the fields.

  The positions of a field come together, so what an occurrence counts
  is worked out once for each run of them."
  ^double [docs weights id at]
  (let [lengths (:lengths (get docs id))]
    (if (vector? at)
      (let [n (count at)]
        (loop [i 0 sum 0.0 field -1 counts 0.0]
          (if (= i n)
            sum
            (let [no     (quot (long (nth at i)) span)
                  counts (if (= no field)
                           counts
                           (occurrence weights lengths no))]
              (recur (inc i) (+ sum counts) no counts)))))
      (occurrence weights lengths (quot (long at) span)))))

(defn- saturated
  "The scores of the documents in `frequencies`, a map of id to BM25F's
  frequency, for a term of the inverse document frequency `idf`, with the
  saturation `k1`."
  [frequencies idf k1]
  (let [idf (double idf)
        k1  (double k1)]
    ;; a transient of the frequencies changes their values in place
    (persistent!
     (reduce-kv (fn [acc id f]
                  (let [f (double f)]
                    (assoc! acc id (* idf (/ f (+ k1 f))))))
                (transient frequencies)
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
  (let [postings (:postings index)
        docs     (:docs index)]
    (persistent!
     (reduce (fn [acc term]
               (reduce-kv (fn [acc id at]
                            (let [f (frequency docs weights id at)]
                              (if (pos? f)
                                (assoc! acc id (+ f (double (get acc id 0.0))))
                                acc)))
                          acc
                          (get postings term)))
             (transient {})
             terms))))

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
  "The edits that the fuzzy term `term` allows: those of :fuzzy when it's a
  number, at most 2, or by its length for :auto, as the AUTO of
  Elasticsearch has it, none below 3 characters and 2 above 5."
  [{:keys [term fuzzy]}]
  (cond
    (number? fuzzy) (min 2 (long fuzzy))
    (= :auto fuzzy) (let [n (count term)]
                      (cond
                        (< n 3)  0
                        (<= n 5) 1
                        :else    2))
    :else           0))

(def ^:no-doc fuzzy-weight
  "How much a match with typos weighs before its edits count, as the
  fuzzyWeight of MiniSearch has it."
  0.45)

(defn- completions
  "The terms of `index` that start with `prefix`, at most `most` of them,
  those that the most documents hold."
  [index prefix most]
  (let [terms (vec (expansions index prefix))]
    (if (<= (count terms) (long most))
      terms
      (let [postings (:postings index)
            held     #(- (count (get postings %)))]
        (->> (map (juxt held identity) terms)
             (sort-by first)
             (take most)
             (map second))))))

(defn- fuzzy-expansions
  "The terms of `index` within the edits of the fuzzy `leaf`, at most
  `most` of them, the closest first, as pairs of a term and its edits.
  Only terms that start with the same character count, which keeps the
  search short."
  [index {:keys [term] :as leaf} most]
  (let [edits (long (edits leaf))
        n     (count term)]
    (->> (for [found (expansions index (subs term 0 1))
               :when (<= (Math/abs (- (count found) n)) edits)
               :let  [d (distance term found edits)]
               :when d]
           [found d])
         (sort-by (fn [[found d]] [d found]))
         (take most))))

;; MiniSearch weighs a match with typos by 0.45 length / (length + edits),
;; and each document counts its best match, so that an exact match outranks
;; one with typos. The expansions share one inverse document frequency, as
;; Lucene blends those of a FuzzyQuery
(defn- merged-scores
  "The maps `ms` of id to score as one, where `f` combines the scores of
  an id that more than one has, e.g. + or max."
  [f ms]
  (if (empty? ms)
    {}
    (let [[most & others] (sort-by count > ms)]
      (persistent!
       (reduce (fn [acc m]
                 (reduce-kv (fn [acc id score]
                              (assoc! acc id (if-let [known (get acc id)]
                                               (f known score)
                                               score)))
                            acc
                            m))
               (transient most)
               others)))))

(defn- fuzzy-scores
  "The scores of the documents that hold a term within the edits of the
  fuzzy `leaf`, with the constants of `ctx`."
  [{:keys [index weights k1 total max-expansions]} {:keys [term] :as leaf}]
  (let [n        (count term)
        weighed  (for [[found d] (fuzzy-expansions index leaf max-expansions)]
                   [(if (zero? (long d))
                      1.0
                      (* fuzzy-weight (/ (double n) (+ n (long d)))))
                    (term-frequencies index weights [found])])
        matched  (count (into #{} (mapcat (comp keys second)) weighed))
        idf      (idf total matched)]
    (merged-scores max (for [[weight frequencies] weighed]
                         (saturated frequencies (* (double weight) idf) k1)))))

;; A prefix counts as one term of all the terms it starts, with the number
;; of documents that hold any of them. Lucene blends the expansions of a
;; prefix to the largest document frequency, so that the rarest one doesn't
;; outrank the rest
(defn- term-scores
  "The scores of the documents that hold the `term`, or with :prefix? a
  term that starts with it, or with :fuzzy one within its edits, with the
  constants of `ctx`."
  [{:keys [index weights k1 total max-completions] :as ctx}
   {:keys [term prefix?] :as leaf}]
  (let [fuzzy? (pos? (long (edits leaf)))
        plain  (when (or prefix? (not fuzzy?))
                 (let [terms       (if prefix?
                                     (completions index term max-completions)
                                     [term])
                       frequencies (term-frequencies index weights terms)]
                   (saturated frequencies (idf total (count frequencies)) k1)))]
    (cond
      (and plain fuzzy?) (merged-scores max [plain (fuzzy-scores ctx leaf)])
      fuzzy?             (fuzzy-scores ctx leaf)
      :else              plain)))

(defn- with-fuzzy
  "The query `node` with :fuzzy as `fuzzy` on each term that isn't of a
  script without spaces, unless it has its own, or `node` as it is when
  `fuzzy` is nil or false."
  [node fuzzy]
  (let [fuzzy (if (true? fuzzy) :auto fuzzy)
        each  #(mapv (fn [n] (with-fuzzy n fuzzy)) %)]
    (cond
      (not fuzzy)              node
      (contains? node :and)    (update node :and each)
      (contains? node :or)     (update node :or each)
      (contains? node :not)    (update node :not with-fuzzy fuzzy)
      (and (:term node)
           (not (:fuzzy node))
           (not (text/unspaced? (:term node))))
      (assoc node :fuzzy fuzzy)
      :else                    node)))

(defn- prefix-postings
  "The postings of the terms of `index` that start with `prefix`, at most
  `most` of them as completions gives them, as a map of id to a vector of
  positions."
  [index prefix most]
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
             (completions index prefix most)))))

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
  [{:keys [index weights k1 total max-completions]} {:keys [phrase prefix?]}]
  (let [postings    (:postings index)
        lists       (conj (mapv #(get postings %) (pop phrase))
                          (if prefix?
                            (prefix-postings index
                                             (peek phrase)
                                             max-completions)
                            (get postings (peek phrase))))
        [fewest & others] (sort-by count lists)
        candidates  (filter (fn [id] (every? #(contains? % id) others))
                            (keys fewest))
        docs        (:docs index)
        frequencies (persistent!
                     (reduce (fn [acc id]
                               (let [starts (phrase-starts lists id)
                                     f      (if (seq starts)
                                              (frequency docs weights id starts)
                                              0.0)]
                                 (if (pos? f)
                                   (assoc! acc id f)
                                   acc)))
                             (transient {})
                             candidates))
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
                   :else             (merged-scores + positive))]
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

(defn- as-query
  "The query `q` as data, read with the `opts`: a string parsed, a vector
  of terms taken as they are, or the data itself."
  [q opts]
  (cond
    (string? q) (parse q opts)
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

(defn- top
  "The best `n` of the `found` scores of documents, or all of them for a
  nil `n`, as the results that `result` makes of a pair of an id and a
  score, with the :filter and :rank of `opts`."
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

(defn query
  "The documents of `index` that match the query `q`, best first, as maps
  of :id, :score and :stored, with the `opts` below.

  The query is a string that parse reads, the same query as data, or a
  vector of terms taken as they are. The `opts` are those of parse, and
  these, over the defaults of the index:

  - :limit, the most results
  - :offset, the results to skip first, e.g. for the next page
  - :fuzzy, to find words with typos: true for the edits by the length
    of a word, none below 3 characters and 2 above 5, or a number of
    edits, at most 2. A match with typos weighs less, and a prefix
    matches the words that it starts as well.
  - :boosts, the weight of a match in each field, 1 by default, where 0
    leaves a field out unless the query names it
  - :aliases, other names that a query can give the fields, as a map of a
    name to a field
  - :k1 and :b, the saturation and length normalization of BM25F
  - :filter, a predicate of a result to keep it
  - :rank, a function of a result to order by, ascending, instead of the
    score
  - :max-completions and :max-expansions, the limits of default-limits

  Equal scores come in the order of their ids, so the order is stable."
  ([index q]
   (query index q {}))
  ([index q opts]
   {:pre [(map? opts)]}
   (let [{:keys [limit offset boosts] :or {offset 0} :as opts}
         (merge default-limits (:defaults index) opts)
         node   (-> (as-query q (assoc opts :index index))
                    (with-fuzzy (:fuzzy opts)))
         ctx    {:index           index
                 :weights         (weights index boosts (:b opts b))
                 :k1              (double (:k1 opts k1))
                 :total           (count (:docs index))
                 :max-completions (:max-completions opts)
                 :max-expansions  (:max-expansions opts)}
         found  (if node
                  (scores ctx node)
                  {})
         result (fn [[id score]]
                  {:id     id
                   :score  score
                   :stored (get-in index [:docs id :stored])})
         ranked (top found
                     result
                     (some-> limit (+ (long offset)))
                     opts)]
     (subvec ranked (min (long offset) (count ranked))))))

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
  - :fuzzy, as query takes it"
  ([text q]
   (snippet text q {}))
  ([text q {:keys [width] :or {width 160} :as opts}]
   {:pre [(map? opts)]}
   (let [opts       (merge (:defaults (:index opts)) opts)
         text       (str text)
         spans      (text/spans text)
         query      (with-fuzzy (as-query q opts) (:fuzzy opts))
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
  and :fuzzy as query takes it, as one field of a document would, without
  an index."
  ([text q]
   (matches? text q {}))
  ([text q opts]
   {:pre [(map? opts)]}
   (let [text   (str text)
         terms  (delay (into #{}
                             (mapcat #(if (vector? %) % [%]))
                             (text/tokens text)))
         spans  (delay (text/spans text))
         found? (fn [{:keys [term phrase prefix? fuzzy] :as leaf}]
                  (cond
                    phrase           (boolean (seq (hits @spans [leaf])))
                    (or prefix?
                        fuzzy)       (boolean (some #(leaf-matches? leaf %)
                                                    @terms))
                    :else            (contains? @terms term)))]
     (satisfied? found? (with-fuzzy (as-query q opts) (:fuzzy opts))))))

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
