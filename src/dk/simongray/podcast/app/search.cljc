(ns dk.simongray.podcast.app.search
  "Finding episodes, by filtering and ordering the items of feeds with
  criteria given as data, or by full-text search.

  The criteria read the model through feed.resolve, so each means what the
  accessor means:

      (episodes feed {:where {:season 2 :type \"full\" :duration [600 nil]}
                      :order :newest})

  The full-text index is a map built with index and add, and searched with
  query. Text is lower-cased with its diacritics folded, so \"cafe\"
  finds \"Café\", and the results are ranked by BM25 per field."
  (:refer-clojure :exclude [remove])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.format.transcript :as transcript]))

(def ^:no-doc ligatures
  "The letters that decomposition leaves whole, and how fold spells them
  out."
  {\æ "ae" \ø "o" \œ "oe" \ß "ss" \ð "d" \þ "th" \ł "l" \đ "d" \ħ "h" \ı "i" \ŋ "n" \ŧ "t"})

;; Unicode, Blocks.txt: the five blocks of combining marks
(def ^:no-doc marks
  "A pattern of the combining marks that decomposition splits off a letter
  with diacritics."
  #"[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]")

(defn- decompose
  [s]
  #?(:clj  (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD)
     :cljs (.normalize s "NFD")))

(defn- marked?
  "Whether `s` has a combining mark in the ranges of marks.

  A loop is far faster than the pattern on text without marks, and each
  comparison takes two arguments so that Clojure compiles it inline."
  [^String s]
  (let [n (count s)]
    (loop [i 0]
      (if (= i n)
        false
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (or (and (<= 0x0300 c) (<= c 0x036F)) (and (<= 0x1AB0 c) (<= c 0x1AFF))
                  (and (<= 0x1DC0 c) (<= c 0x1DFF)) (and (<= 0x20D0 c) (<= c 0x20FF))
                  (and (<= 0xFE20 c) (<= c 0xFE2F)))
            true
            (recur (inc i))))))))

(defn fold
  "The string `s` lower-cased, with its diacritics stripped and its
  ligatures spelled out, e.g. \"Café Ø\" becomes \"cafe o\"."
  [s]
  (let [s (str/lower-case (str s))
        d (decompose s)]
    (str/escape (if (and (identical? d s) (not (marked? s))) s (str/replace d marks "")) ligatures)))

(def ^:no-doc word
  "The pattern of a word: a run of letters, marks and digits."
  #"(?u)[\p{L}\p{M}\p{N}]+")

(defn tokens
  "The terms of `s`, its words folded, with scripts that have no spaces
  between words indexed as runs."
  [s]
  (let [folded (fold s)]
    #?(:clj  (let [m (re-matcher word folded)]
               (loop [acc (transient [])]
                 (if (.find m)
                   (recur (conj! acc (.group m)))
                   (persistent! acc))))
       :cljs (vec (re-seq word folded)))))

(defn ^:no-doc spans
  "The words of `s` as maps of :start, :end and :term, for highlighting
  matches."
  [s]
  (loop [words (re-seq word s) from 0 acc []]
    (if-let [[w & more] (seq words)]
      (let [start (long (str/index-of s w from))
            end   (+ start (count w))]
        (recur more end (conj acc {:start start :end end :term (fold w)})))
      acc)))

(defn ^:no-doc spaced
  "The query `q` with every kind of whitespace as a space, since the regex
  \\s matches only ASCII whitespace on the JVM but all of it in JavaScript."
  [q]
  (str/replace q #"[\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]" " "))

(defn- words
  "The :terms of the query `q`, with the last one as a :prefix to complete
  unless `q` ends in whitespace."
  [q]
  (let [q     (spaced q)
        terms (tokens q)]
    (if (and (seq terms) (not (re-find #"\s$" q)))
      {:terms (pop terms) :prefix (peek terms)}
      {:terms terms})))

(defn- notes
  [x]
  (some-> (resolve/description x) html/text))

(defn- names
  [feed item]
  (str/join " " (map :name (if item (resolve/people feed item) (resolve/people feed)))))

(defn- present
  "The entries of `m` whose value isn't blank or empty."
  [m]
  (into {} (filter (fn [[_ v]] (if (string? v) (not (str/blank? v)) (seq v)))) m))

(defn item-document
  "The search document of `item` in `feed`, with the `opts` below.

  Its fields are :title, :show, :description, :people, :keywords,
  :chapters and :transcript. A result has the title, show, guid,
  publication date, duration and URL. The `opts` are:

  - :url, where the feed was fetched, which a feed without a podcast:guid
    needs, or it throws
  - :chapters, the parsed chapters file, instead of the embedded ones
  - :transcript, the parsed transcript"
  ([feed item]
   (item-document feed item {}))
  ([feed item {:keys [url chapters transcript] :as opts}]
   {:pre [(map? opts)]}
   (let [chapters (or chapters (resolve/embedded-chapters item))
         title    (resolve/title item)
         show     (resolve/title feed)
         id       (player/episode-id feed url item)]
     (when (nil? (first id))
       (throw (ex-info "A feed without a podcast:guid needs :url, or its episodes share ids with those of other shows"
                       {:title show})))
     {:id     id
      :fields (present {:title       title
                        :show        show
                        :description (notes item)
                        :people      (names feed item)
                        :keywords    (:itunes/keywords item)
                        :chapters    (str/join " " (keep :title (:chapters chapters)))
                        :transcript  (some-> transcript transcript/text)})
      :stored (into {} (filter (comp some? val))
                    {:title     title
                     :show      show
                     :guid      (feed/item-guid item)
                     :url       url
                     :published (resolve/published item)
                     :duration  (resolve/duration item)})})))

(defn feed-document
  "The search document of the show `feed` itself, by its guid, with the
  :url in `opts` where the feed was fetched, for a feed without a
  podcast:guid.

  Its fields are :title, :description, :author, :people and :categories."
  ([feed]
   (feed-document feed {}))
  ([feed {:keys [url] :as opts}]
   {:pre [(map? opts)]}
   (let [title  (resolve/title feed)
         author (resolve/author feed)]
     {:id     (feed/feed-guid feed url)
      :fields (present {:title       title
                        :description (notes feed)
                        :author      author
                        :people      (names feed nil)
                        :categories  (str/join " " (map #(str/join " " %) (resolve/categories feed)))})
      :stored (into {} (filter (comp some? val)) {:title title :author author :url url})})))

(def ^{:no-doc true :const true} span
  "The block of positions that each field of a document gets.

  The positions of a term in a document are in one vector, each offset by
  its field's number times this. Two positions one apart are then always
  in the same field. A longer field is indexed up to this length."
  1000000)

(declare add)

(defn index
  "An index of `docs`, or an empty one to add to.

  It's a plain map that prints as EDN. Read back from EDN, its :terms is a
  hash set, so prefix search scans every term until you sort it again:

      (update index :terms #(into (sorted-set) %))"
  ([]
   {:docs {} :postings {} :terms (sorted-set) :fields {}})
  ([docs]
   (reduce add (index) docs)))

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
  "The terms of the field value `v`: a string tokenized, a vector taken as
  terms already, or each string of any other collection tokenized."
  [v]
  (cond
    (string? v) (tokens v)
    (vector? v) v
    :else       (into [] (mapcat tokens) v)))

(defn- numbered
  "The `fields` of an index with a number for each new field of
  `analyzed`, in order of appearance."
  [fields analyzed]
  (reduce (fn [fields field]
            (if (contains? fields field)
              fields
              (assoc fields field {:no (count fields) :docs 0 :length 0})))
          fields (keys analyzed)))

(defn- positions
  "The positions of each term in the `analyzed` fields, by term, as one
  position or a vector of them. Each is offset into the block of its field
  by the field `numbers`."
  [numbers analyzed]
  (persistent!
   (reduce-kv (fn [acc field terms]
                (let [base (long (* span (get numbers field)))]
                  (loop [i 0 terms (seq terms) acc acc]
                    (if (and terms (< i span))
                      (let [term (first terms)
                            at   (+ base i)
                            seen (get acc term)]
                        (recur (inc i) (next terms)
                               (assoc! acc term (cond (nil? seen)    at
                                                      (vector? seen) (conj seen at)
                                                      :else          [seen at]))))
                      acc))))
              (transient {}) analyzed)))

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
                               (assoc! postings term (assoc (get postings term) id at)))
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

(def boosts
  "How much a match in each field weighs in a query, e.g. a word in a
  title counts three times a word in the notes."
  {:title 3 :show 2 :author 2 :people 2 :chapters 2 :keywords 2
   :categories 1 :description 1 :transcript 1})

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

(defn- at-positions
  [at]
  (cond (nil? at) [] (vector? at) at :else [at]))

(defn- field-counts
  "The count of positions in each field of the posting `at`, by field
  number."
  [at]
  (if (vector? at)
    (persistent!
     (reduce (fn [counts p]
               (let [no (quot (long p) span)]
                 (assoc! counts no (inc (long (get counts no 0))))))
             (transient {}) at))
    {(quot (long at) span) 1}))

(defn- weighing
  "The constants of each field of `index` for scoring with `boosts` and
  the BM25 parameters `k1` and `b`, by field number.

  Each has the field's name, its boost, and the constants of BM25's
  saturation, tf (k1 + 1) / (tf + a + c length). The constants fold in
  `k1`, `b` and the field's average length."
  [index boosts k1 b]
  (let [k1 (double k1) b (double b)]
    (into {} (for [[name {:keys [no docs length]}] (:fields index)
                   :let [average (if (pos? (long docs)) (/ (double length) (long docs)) 1.0)]]
               [no {:name  name
                    :boost (double (get boosts name 1))
                    :k     (+ k1 1)
                    :a     (* k1 (- 1 b))
                    :c     (/ (* k1 b) average)}]))))

(defn- weight
  "The boosted BM25 weight of a term in the document `id` of `index`, with
  the `counts` by field number, using the constants of `weighing`."
  ^double [index weighing id counts]
  (let [lengths (get-in index [:docs id :lengths])]
    (double (reduce-kv (fn [sum no tf]
                         (let [{:keys [name boost k a c]} (get weighing no)
                               boost (double boost)]
                           (if (zero? boost)
                             sum
                             (let [tf     (long tf)
                                   length (long (get lengths name))]
                               (+ (double sum)
                                  (* boost (/ (* tf (double k))
                                              (+ tf (double a) (* (double c) length)))))))))
                       0.0 counts))))

(defn- expansions
  "The terms of `index` that `term` matches: itself, or with `prefix?`,
  every term that starts with it. An index read back from EDN has no sorted
  terms, so it takes a scan."
  [index term prefix?]
  (let [terms (:terms index)]
    (cond
      (not prefix?)   (when (contains? (:postings index) term) [term])
      (sorted? terms) (subseq terms >= term < (str term "￿"))
      :else           (filter #(str/starts-with? % term) terms))))

(defn- term-scores
  "The scores of the documents of `index` that hold `term`, as a map of id
  to score, using the constants of `weighing`. With `prefix?`, a document
  scores by its best term that starts with `term`."
  [index weighing term prefix?]
  (let [total (count (:docs index))]
    (persistent!
     (reduce (fn [scores term]
               (let [docs (get-in index [:postings term])
                     idf  (idf total (count docs))]
                 (reduce-kv (fn [scores id at]
                              (let [score (* idf (weight index weighing id (field-counts at)))]
                                (if (pos? score)
                                  (assoc! scores id (max score (double (get scores id 0.0))))
                                  scores)))
                            scores docs)))
             (transient {}) (expansions index term prefix?)))))

(defn- phrase-counts
  "The number of times the `terms` occur in a row in the document `id`, by
  field number, from the `postings` of an index."
  [postings terms id]
  (let [sets (map #(set (at-positions (get-in postings [% id]))) (rest terms))]
    (frequencies (for [start (at-positions (get-in postings [(first terms) id]))
                       :let  [start (long start)]
                       :when (every? identity (map-indexed (fn [i s] (contains? s (+ start (long i) 1))) sets))]
                   (quot start span)))))

(defn- phrase-scores
  "The scores of the documents of `index` where the `terms` occur in a
  row, as a map of id to score, using the constants of `weighing`."
  [index weighing terms]
  (let [postings (:postings index)
        total    (count (:docs index))
        idf      (double (reduce + (map #(idf total (count (get postings %))) terms)))
        ids      (reduce (fn [ids term] (filter #(contains? (get postings term) %) ids))
                         (keys (get postings (first terms)))
                         (rest terms))]
    (into {} (for [id ids
                   :let [counts (phrase-counts postings terms id)
                         score  (* idf (weight index weighing id counts))]
                   :when (pos? score)]
               [id score]))))

(defn- clauses
  "The clauses of the query `q`, a string or a vector of terms, with the
  last word as a prefix if `prefix?` and at least `min-prefix` long.

  A word is a map with its :term, and :prefix? when it's a prefix. Words in
  quotes or joined by punctuation, e.g. \"2.0\", are one map with them as
  its :phrase. A query that ends in whitespace or a quote has no prefix."
  [q prefix? min-prefix]
  (if (string? q)
    (let [clauses (vec (for [[_ quoted word] (re-seq #"\"([^\"]*)\"|(\S+)" q)
                             :let [terms (tokens (or quoted word))]
                             :when (seq terms)]
                         (if (= 1 (count terms)) {:term (first terms)} {:phrase terms})))
          last    (dec (count clauses))]
      (if (and prefix? (<= 0 last)
               (<= (long min-prefix) (count (str (:term (clauses last)))))
               (:term (clauses last))
               (not (re-find #"[\s\"]$" q)))
        (assoc-in clauses [last :prefix?] true)
        clauses))
    (mapv #(hash-map :term %) q)))

(defn- clause-scores
  [index weighing {:keys [term prefix? phrase]}]
  (if phrase
    (phrase-scores index weighing phrase)
    (term-scores index weighing term prefix?)))

(defn- matching
  "The ids in every one of the `scored` clause maps, or in any of them
  when `match` is :any."
  [scored match]
  (case match
    :all (let [[smallest & others] (sort-by count scored)]
           (into [] (filter (fn [id] (every? #(contains? % id) others))) (keys smallest)))
    :any (into [] (distinct) (mapcat keys scored))))

(defn- better
  "A comparator of the results `a` and `b`: the higher score first, then
  the lower id.

  Ids that aren't both numbers compare by hash and then as strings, so
  that the order is total and the same on both platforms, which hash
  numbers differently. BM25 ties are common, so this must stay cheap."
  [a b]
  (let [sa (double (:score a)) sb (double (:score b))]
    (cond (> sa sb) -1
          (< sa sb) 1
          (and (number? (:id a)) (number? (:id b))) (compare (:id a) (:id b))
          :else     (let [ha (long (hash (:id a))) hb (long (hash (:id b)))]
                      (cond (< ha hb) -1
                            (> ha hb) 1
                            :else     (compare (str (:id a)) (str (:id b))))))))

(defn- best
  "The `results` sorted by the comparator `before`, at most `n` of them if
  `n` is given.

  For a small `n`, the best so far are kept in a small sorted vector, so
  that most candidates cost one comparison with its worst. For an `n`
  beyond the square root of the number of results, all are sorted."
  [before n results]
  (cond
    (and n (<= n 0)) []
    (or (nil? n) (>= n (Math/sqrt (count results))))
    (let [sorted (sort before results)]
      (vec (if n (take n sorted) sorted)))
    :else     (reduce (fn [top result]
                        (if (and (= (count top) n) (<= 0 (before result (peek top))))
                          top
                          (let [i   (loop [i 0]
                                      (if (and (< i (count top)) (<= (before (top i) result) 0))
                                        (recur (inc i))
                                        i))
                                top (into (conj (subvec top 0 i) result) (subvec top i))]
                            (if (> (count top) n) (pop top) top))))
                      [] results)))

(defn query
  "The documents of `index` that match the query `q`, best first, as maps
  of :id, :score and :stored, with the `opts` below.

  Every word must occur, the last as a prefix unless `q` ends in
  whitespace, and words in quotes in a row. The `opts` are:

  - :limit, the most results
  - :match, :any for documents that match any word instead of all
  - :prefix?, false to take the last word whole
  - :min-prefix, the fewest characters of a prefix, 1 by default
  - :boosts, the field weights over boosts, where 0 leaves a field out
  - :k1 and :b, the saturation and length normalisation of BM25
  - :where, a predicate of a result to keep it
  - :rank, a function of a result to order by, ascending, instead of the
    score

  Without :rank, equal scores are ordered by id, so the order is stable."
  ([index q]
   (query index q {}))
  ([index q {:keys [limit match prefix? min-prefix where rank]
             :or   {match :all prefix? true min-prefix 1}
             :as   opts}]
   {:pre [(map? opts)]}
   (let [weighing (weighing index (or (:boosts opts) boosts) (:k1 opts k1) (:b opts b))
         q        (if (string? q) (spaced q) q)
         scored   (mapv #(clause-scores index weighing %) (clauses q prefix? min-prefix))
         result   (fn [id]
                    {:id     id
                     :score  (reduce (fn [sum m] (+ (double sum) (double (get m id 0.0)))) 0.0 scored)
                     :stored (get-in index [:docs id :stored])})
         before   (if rank (fn [a b] (compare (rank a) (rank b))) better)]
     (best before limit (into [] (comp (map result) (if where (filter where) identity))
                                 (matching scored match))))))

(defn snippet
  "A window of `text` around the first words that match the query `q`, as
  parts for a result list to render, with the :width in `opts`.

  It's a vector of maps with :text, and :match? on the matches, with an
  ellipsis where the window cuts the text. The :width is the number of
  characters shown, 160 by default."
  ([text q]
   (snippet text q {}))
  ([text q {:keys [width] :or {width 160} :as opts}]
   {:pre [(map? opts)]}
   (let [{:keys [terms prefix]} (words (if (string? q) q (str/join " " q)))
         hit?   (fn [{:keys [term]}]
                  (boolean (or (some #(= term %) terms)
                               (and prefix (str/starts-with? term prefix)))))
         text   (str text)
         spans  (spans text)
         hits   (filter hit? spans)
         length (count text)
         from   (if-let [{:keys [start]} (first hits)] (max 0 (- start (quot width 3))) 0)
         from   (or (some #(when (>= (:start %) from) (:start %)) spans) from)
         to     (min length (max (+ from width) (or (:end (first hits)) 0)))
         to     (if (< to length)
                  (or (last (keep #(when (<= (:end %) to) (:end %)) spans)) to)
                  to)
         inside (filter #(and (>= (:start %) from) (<= (:end %) to)) hits)
         parts  (loop [pos from hits inside acc (if (pos? from) [{:text "…"}] [])]
                  (if-let [{:keys [start end]} (first hits)]
                    (recur end (rest hits)
                           (cond-> acc
                             (< pos start) (conj {:text (subs text pos start)})
                             true          (conj {:text (subs text start end) :match? true})))
                    (cond-> acc (< pos to) (conj {:text (subs text pos to)}))))]
     (cond-> parts
       (< to length) (conj {:text "…"})))))

(defn- in?
  [expected actual]
  (if (set? expected) (contains? expected actual) (= expected actual)))

(defn- within?
  "Whether `x` is in the range from `lo` to `hi`, where a nil end is open.
  A nil `x` is in no range."
  [[lo hi] x]
  (boolean (and (some? x)
                (or (nil? lo) (<= lo x))
                (or (nil? hi) (<= x hi)))))

(defn- url-of
  [{:keys [url]} feed]
  (if (fn? url) (url feed) url))

(defn ^:no-doc episode-id
  "The player id of `item` in `feed`, with the feed URL from `ctx`."
  [ctx feed item]
  (player/episode-id feed (url-of ctx feed) item))

(defn ^:no-doc entry
  "The player entry of `item` in `feed`, from the player state in `ctx`."
  [ctx feed item]
  (player/episode (:player ctx) (episode-id ctx feed item)))

(defn- status
  "The status of the episode with the player entry `e`: :unplayed,
  :in-progress or :played."
  [{:keys [position played?] :as e}]
  (cond played?                                  :played
        (and (number? position) (pos? position)) :in-progress
        :else                                    :unplayed))

(defn- has-chapters?
  "Whether `item` links, embeds or lists chapters."
  [item]
  (boolean (or (resolve/chapters-url item)
               (resolve/embedded-chapters item)
               (resolve/inferred-chapters item))))

(defn- mentions?
  "Whether the set of terms `present` holds every term of the `parsed`
  query, as words gives it, and a term that starts with its prefix."
  [{:keys [terms prefix] :as parsed} present]
  (boolean (and (every? present terms)
                (or (nil? prefix) (some #(str/starts-with? % prefix) present)))))

(defn- mentioned
  "The predicate of the criterion :text for `v`, a query or a set of
  queries. It's true when the title or notes of an item match any of them."
  [v]
  (let [queries (mapv words (if (set? v) v [v]))]
    (fn [_ item]
      (let [present (set (concat (tokens (resolve/title item)) (tokens (notes item))))]
        (boolean (some #(mentions? % present) queries))))))

(defn- age
  "The seconds from the publication of `item` to `at`, at least 0, or nil
  when the item has no date."
  [item at]
  (when-let [published (resolve/published item)]
    (max 0 (/ (- (inst-ms at) (inst-ms published)) 1000.0))))

(defn- since-subscription?
  "Whether `item` of `feed` came out after the listener subscribed to the
  feed, by the player state in `ctx`."
  [ctx feed item]
  (let [[feed-guid] (episode-id ctx feed item)
        added       (get-in ctx [:player :subscriptions feed-guid :added-at])
        published   (resolve/published item)]
    (boolean (and (inst? added) published (> (inst-ms published) (inst-ms added))))))

(declare where)

(def ^:no-doc criterion
  "The test of each criterion of where, a function of the criterion's
  value and the context that returns a predicate of a feed and an item."
  {:season      (fn [v _] (fn [_ item] (in? v (:season (resolve/numbering item)))))
   :type        (fn [v _] (fn [_ item] (in? v (:type (resolve/numbering item)))))
   :duration    (fn [v _] (fn [_ item] (within? v (resolve/duration item))))
   :published   (fn [[lo hi] _]
                  (fn [_ item]
                    (within? [(some-> lo inst-ms) (some-> hi inst-ms)]
                             (some-> (resolve/published item) inst-ms))))
   :age         (fn [v ctx] (fn [_ item] (within? v (age item (:at ctx)))))
   :transcript? (fn [v _] (fn [feed item] (= (boolean v) (some? (resolve/transcript feed item)))))
   :chapters?   (fn [v _] (fn [_ item] (= (boolean v) (has-chapters? item))))
   :explicit?   (fn [v _] (fn [feed item] (= (boolean v) (boolean (resolve/explicit? feed item)))))
   :person      (fn [v _]
                  (let [q (fold v)]
                    (fn [feed item]
                      (boolean (some #(str/includes? (fold (:name %)) q) (resolve/people feed item))))))
   :text        (fn [v _] (mentioned v))
   :not         (fn [v ctx] (complement (where v ctx)))
   :status      (fn [v ctx] (fn [feed item] (in? v (status (entry ctx feed item)))))
   :starred?    (fn [v ctx]
                  (fn [feed item] (= (boolean v) (player/starred? (:player ctx) (episode-id ctx feed item)))))
   :inbox?      (fn [v ctx]
                  (fn [feed item] (= (boolean v) (player/in-inbox? (:player ctx) (episode-id ctx feed item)))))
   :rating      (fn [v ctx]
                  (let [opts (select-keys ctx [:scale])]
                    (fn [feed item]
                      (within? v (player/rating (:player ctx) (episode-id ctx feed item) opts)))))
   :since-subscription?
   (fn [v ctx] (fn [feed item] (= (boolean v) (since-subscription? ctx feed item))))})

(defn where
  "The predicate of a feed and one of its items for `criteria` in the
  context `ctx`. The criteria are a map whose entries must all hold:

  - :season, a season or a set of them, as resolve/numbering gives it
  - :type, \"full\", \"trailer\" or \"bonus\", or a set of them
  - :duration, a range of seconds, e.g. [600 nil], nil for an open end
  - :published, a range of instants
  - :age, a range of seconds since it came out, e.g. [nil 604800]
  - :transcript?, :chapters? and :explicit?, where chapters count when
    linked, embedded or listed in the notes
  - :person, someone credited
  - :text, words that must all be in the title or notes, the last as a
    prefix. A set of such texts matches when any of them does.
  - :not, criteria that the item doesn't meet, e.g. {:text #{\"rerun\"}}
  - :status, :unplayed, :in-progress or :played, or a set of them
  - :starred? and :inbox?
  - :rating, a range of the listener's ratings, which no unrated episode
    is in
  - :since-subscription?, whether it came out after the listener
    subscribed

  An empty map takes every item. The criteria about the listener need the
  player state as :player in `ctx`. For feeds without a podcast:guid, they
  also need the :url, a string or a function of the feed. The :age needs
  the current time as :at, and with :scale, the :rating is in that scale."
  ([criteria]
   (where criteria {}))
  ([criteria ctx]
   (let [unknown (filter #(not (contains? criterion %)) (keys criteria))]
     (when (seq unknown)
       (throw (ex-info (str "Criteria the library does not know: " (str/join ", " unknown))
                       {:criteria (vec unknown)})))
     (when-let [k (and (not (:player ctx))
                       (some #{:status :starred? :inbox? :rating :since-subscription?} (keys criteria)))]
       (throw (ex-info (str k " needs the player state under :player") {:criteria criteria})))
     (when (and (contains? criteria :age) (not (inst? (:at ctx))))
       (throw (ex-info ":age needs the instant of now under :at" {:criteria criteria})))
     (let [tests (mapv (fn [[k v]] ((criterion k) v ctx)) criteria)]
       (fn [feed item]
         (every? #(% feed item) tests))))))

(def orders
  "The orders that order takes, for a UI to offer."
  #{:listening :newest :oldest :shortest :longest :title :recent})

(defn- published-ms
  [item]
  (some-> (resolve/published item) inst-ms))

(defn- last-heard-ms
  "When the listener last touched `item` of `feed`, in milliseconds, from
  the player state in `ctx`."
  [ctx feed item]
  (let [{:keys [played-at updated-at]} (entry ctx feed item)]
    (some-> (or played-at updated-at) inst-ms)))

(defn order
  "The sort key of a feed and one of its items for the order `k` and the
  context `ctx`, for sort-by. When `k` is a function of a feed and an
  item, it's `k` itself.

  - :listening, as resolve/episodes orders a show
  - :newest and :oldest, by publication
  - :shortest and :longest, by duration
  - :title, alphabetically
  - :recent, by when the listener last touched the episode, which needs
    :player in `ctx`

  Items without the property sort last."
  ([k]
   (order k {}))
  ([k ctx]
   (when (and (= :recent k) (not (:player ctx)))
     (throw (ex-info ":recent needs the player state under :player" {:order k})))
   (case k
     :listening resolve/listening-key
     :newest    (fn [_ item] (- (or (published-ms item) ##-Inf)))
     :oldest    (fn [_ item] (or (published-ms item) ##Inf))
     :shortest  (fn [_ item] (or (resolve/duration item) ##Inf))
     :longest   (fn [_ item] (- (or (resolve/duration item) ##-Inf)))
     :title     (fn [_ item] (if-let [title (resolve/title item)] [0 (fold title)] [1 ""]))
     :recent    (fn [feed item] (- (or (last-heard-ms ctx feed item) ##-Inf)))
     (if (fn? k)
       k
       (throw (ex-info (str "An order the library does not know: " k) {:order k}))))))

(defn ^:no-doc sorted-by
  "The items of `coll` sorted by `f` as sort-by sorts them, but with one
  call of `f` per item rather than two per comparison."
  [f coll]
  (map second (sort-by first (map (juxt f identity) coll))))

(defn episodes
  "The items of `feed` that `opts` select, in the order they ask for:

  - :where, criteria as where takes them, or else every item
  - :order, as order takes it, :listening by default
  - :limit, the most items
  - :player, :url, :at and :scale, the context that where and order need

  For a collection of feeds, the result is [feed item] pairs in one order,
  e.g. for an inbox, with :url as a function of the feed. With a :limit
  and one of the orders, the criteria only read as many items as the
  limit needs."
  ([feed]
   (episodes feed {}))
  ([feed {:keys [limit] :as opts}]
   {:pre [(map? opts)]}
   (let [ctx       (select-keys opts [:player :url :scale :at])
         pred      (where (or (:where opts) {}) ctx)
         k         (or (:order opts) :listening)
         key       (order k ctx)
         feeds     (if (map? feed) [feed] feed)
         sort-key  (if (= :listening k)
                     (fn [[i f item]] [i (key f item)])
                     (fn [[_ f item]] (key f item)))
         meets?    (fn [[_ f item]] (pred f item))
         all       (for [[i f] (map-indexed vector feeds), item (:rss/items f)]
                     [i f item])
         ;; a key of the app can need what the criteria select, so the
         ;; order comes first with the keys of the library only
         chosen    (if (and limit (keyword? k))
                     (into [] (comp (filter meets?) (take limit)) (sorted-by sort-key all))
                     (cond->> (sorted-by sort-key (filter meets? all))
                       limit (take limit)))
         pairs     (map rest chosen)]
     (vec (if (map? feed) (map second pairs) (map vec pairs))))))

(defn- where-problem
  "A description of what this version of podcast-clj doesn't know in the
  criteria `where` at `place`, including those under :not."
  [where place]
  (let [unknown (when (map? where) (filter #(not (contains? criterion %)) (keys where)))]
    (cond
      (nil? where)       nil
      (not (map? where)) (str "the criteria under " place " are " (pr-str where) ", not a map")
      (seq unknown)      (str "the criteria " (str/join ", " (map pr-str unknown)) " are not known")
      :else              (where-problem (:not where) :not))))

(defn problem
  "A description of what in the saved filter `criteria` this version of
  podcast-clj doesn't know, or nil.

  A saved filter can come from a device with a later version, and
  episodes throws for criteria that it doesn't know, so check here before
  offering the filter."
  [criteria]
  (let [{:keys [where order limit scale]} (when (map? criteria) criteria)
        in-where (where-problem where :where)]
    (cond
      (not (map? criteria))                   "the criteria are not a map"
      in-where                                in-where
      (not (or (nil? order) (orders order)))  (str "the order " (pr-str order) " is not known")
      (not (or (nil? limit) (number? limit))) (str "the limit is " (pr-str limit) ", not a number")
      (not (or (nil? scale) (player/scale? scale)))
      (str "the scale is " (pr-str scale) ", not a scale of ratings"))))

#?(:clj
   (comment
     (require '[dk.simongray.podcast :as podcast])
     (def f (podcast/parse (slurp "test/resources/feeds/namespace-example.xml")))
     (map :rss/title (episodes f {:order :oldest}))
     (def idx (index (map #(item-document f % {:url "https://example.com/feed.xml"}) (:rss/items f))))
     (query idx "futu")
     (snippet (html/text (resolve/description (first (:rss/items f)))) "future")
     #_.))
