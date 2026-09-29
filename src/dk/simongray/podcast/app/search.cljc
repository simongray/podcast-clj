(ns dk.simongray.podcast.app.search
  "Finding episodes: the items of a feed filtered and ordered by criteria
  given as data, and full-text search over subscriptions through an index
  that is a value.

  Filtering and ordering read the model through `resolve`, so a criterion
  means what the accessor means:

      (episodes feed {:where {:season 2 :type \"full\" :duration [600 nil]}
                      :order :newest})

  `where` and `order` give the predicate and the sort key behind that, for
  lists an app assembles itself. Both take the player state of
  `dk.simongray.podcast.app.player` for what depends on the listener: whether
  an episode is unplayed, in progress or played, and what was heard last.

  The index is a map built with `index` and `add` and searched with
  `query`. A document is `{:id :fields :stored}`: `:fields` the text to
  search by field name, `:stored` what a result carries back;
  `item-document` and `feed-document` build them from the model. Text is
  analyzed into terms, lower-cased with the diacritics folded, so that
  \"cafe\" finds \"Café\"; a field given as a vector of strings is taken as
  terms already analyzed, for a client that stems or segments its own
  text, and so is a query given as a vector. Results are ranked by BM25
  per field, weighted by `boosts`; the last word of a query matches as a
  prefix, for search as you type; words in quotes must occur in a row.

  Nothing here reads a clock or does I/O; an app fetches transcripts and
  chapters and hands them to `item-document`."
  (:refer-clojure :exclude [remove])
  (:require [clojure.string :as str]
            [dk.simongray.podcast.app.player :as player]
            [dk.simongray.podcast.feed :as feed]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.format.html :as html]
            [dk.simongray.podcast.format.transcript :as transcript]))

(def ^:no-doc ligatures
  "Letters that decomposing leaves whole, spelled out when folding."
  {\æ "ae" \ø "o" \œ "oe" \ß "ss" \ð "d" \þ "th" \ł "l" \đ "d" \ħ "h" \ı "i" \ŋ "n" \ŧ "t"})

(def ^:no-doc marks
  "The combining marks that decomposing a letter with diacritics leaves
  after it."
  #"[̀-ͯ᪰-᫿᷀-᷿⃐-⃿︠-︯]")

(defn- decompose
  "`s` in canonical decomposition, its diacritics as separate marks."
  [s]
  #?(:clj  (java.text.Normalizer/normalize s java.text.Normalizer$Form/NFD)
     :cljs (.normalize s "NFD")))

(defn- marked?
  "Whether `s` holds a combining mark of the ranges `marks` covers, found
  with a loop, which costs text without one far less than the pattern. The
  comparisons take two arguments, which Clojure compiles inline."
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
  "`s` lower-cased, its diacritics stripped and its ligatures spelled out,
  so that texts and queries compare the way a listener types: \"Café Ø\"
  folds to \"cafe o\". Decomposing text with nothing to decompose gives
  the same string back, on both platforms, and such text has marks to
  strip only when it held them already, as \"İ\" lower-cased does."
  [s]
  (let [s (str/lower-case (str s))
        d (decompose s)]
    (str/escape (if (and (identical? d s) (not (marked? s))) s (str/replace d marks "")) ligatures)))

(def ^:no-doc word
  "A run of letters, marks and digits: one word of text."
  #"(?u)[\p{L}\p{M}\p{N}]+")

(defn tokens
  "The terms of `s`: its words, folded. Scripts written without spaces
  between words are indexed as runs."
  [s]
  (let [folded (fold s)]
    #?(:clj  (let [m (re-matcher word folded)]
               (loop [acc (transient [])]
                 (if (.find m)
                   (recur (conj! acc (.group m)))
                   (persistent! acc))))
       :cljs (vec (re-seq word folded)))))

(defn ^:no-doc spans
  "The words of `s` with where they are, `{:start :end :term}`, for
  marking matches in text."
  [s]
  (loop [words (re-seq word s) from 0 acc []]
    (if-let [[w & more] (seq words)]
      (let [start (long (str/index-of s w from))
            end   (+ start (count w))]
        (recur more end (conj acc {:start start :end end :term (fold w)})))
      acc)))

(defn ^:no-doc spaced
  "The query `q` with every kind of white space as a space, so that the
  JVM, whose `\\s` knows only the ASCII ones, reads it as JavaScript does."
  [q]
  (str/replace q #"[\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]" " "))

(defn- words
  "The terms of the query `q` and, unless it ends in whitespace, the last
  one as the `:prefix` to complete: `{:terms [...] :prefix \"clo\"}`."
  [q]
  (let [q     (spaced q)
        terms (tokens q)]
    (if (and (seq terms) (not (re-find #"\s$" q)))
      {:terms (pop terms) :prefix (peek terms)}
      {:terms terms})))

(defn- notes
  "The show notes of `x` as plain text."
  [x]
  (some-> (resolve/description x) html/text))

(defn- names
  "The names credited on `item` of `feed`, or on the feed, as one string."
  [feed item]
  (str/join " " (map :name (if item (resolve/people feed item) (resolve/people feed)))))

(defn- present
  "The entries of `m` whose value is text or a sequence with something in it."
  [m]
  (into {} (filter (fn [[_ v]] (if (string? v) (not (str/blank? v)) (seq v)))) m))

(defn item-document
  "The search document for `item` of `feed`: its identity as the player
  knows it, the fields `:title`, `:show`, `:description`, `:people`,
  `:keywords` and `:chapters`, and `:transcript` when `opts` carries one;
  stored for results, the title, the show, the guid, when it was
  published, how long it is and the url. A feed without a podcast:guid and
  without `:url` has no identity to give its episodes, and throws. `opts`:

      {:url        \"https://…\"   ; where the feed was fetched, needed for the
                                 ; identity of a feed without a podcast:guid
       :chapters   doc           ; the chapters file fetched and parsed, else the
                                 ; embedded chapters are used; chapters listed in
                                 ; the notes are in :description already
       :transcript doc}          ; the transcript fetched and parsed"
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
  "The search document for the show `feed` itself: its guid as the id, the
  fields `:title`, `:description`, `:author`, `:people` and `:categories`,
  and the title, author and url stored. `:url` in `opts` is where the feed
  was fetched, needed for the identity of a feed without a podcast:guid."
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
  "The block of positions each field of a document gets. A term's
  positions are kept in one vector per document, each field's offset by
  its number times this, so that two positions one apart are always in
  the same field; a field longer than this is indexed up to it."
  1000000)

(declare add)

(defn index
  "An index over `docs`, or an empty one to `add` to. A map, a value like
  everything else: `:docs` by id with what is stored, the length of each
  field and the terms held; `:postings` from term to its positions in
  every document that has it; `:terms` the same terms in order, for
  prefixes; `:fields` with each field's number and the lengths BM25
  normalizes by. Printed as EDN and read back, `:terms` is a hash set, and
  prefixes are found by a scan of every term until `(update index :terms
  #(into (sorted-set) %))` sorts it again."
  ([]
   {:docs {} :postings {} :terms (sorted-set) :fields {}})
  ([docs]
   (reduce add (index) docs)))

(defn remove
  "`index` without the document `id`."
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
  "The terms of a field value: a string analyzed, a vector taken as terms
  analyzed already, and the strings of any other collection analyzed each."
  [v]
  (cond
    (string? v) (tokens v)
    (vector? v) v
    :else       (into [] (mapcat tokens) v)))

(defn- numbered
  "The `fields` of an index with a number for every field of `analyzed`,
  new ones numbered as they appear."
  [fields analyzed]
  (reduce (fn [fields field]
            (if (contains? fields field)
              fields
              (assoc fields field {:no (count fields) :docs 0 :length 0})))
          fields (keys analyzed)))

(defn- positions
  "Where each term occurs in the analyzed `fields`, by term: a position, or
  a vector of them, each offset into the block of its field by `numbers`."
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
  "`index` with the document `doc`, `{:id :fields :stored}`, added, in
  place of an earlier one with the same id."
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
  "How much a match in each field weighs for `query`: a word in a title
  counts three times a word in the notes."
  {:title 3 :show 2 :author 2 :people 2 :chapters 2 :keywords 2
   :categories 1 :description 1 :transcript 1})

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
  "The positions a posting `at` holds, one or several."
  [at]
  (cond (nil? at) [] (vector? at) at :else [at]))

(defn- field-counts
  "How many positions the posting `at` holds in each field, by field
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
  "What scoring against `index` needs of its fields, by field number: the
  name, its boost by `boosts`, and BM25's `k1` and `b` folded with the
  field's average length into the constants of the saturation
  `tf (k1 + 1) / (tf + a + c length)`."
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
  "The boosted BM25 weight of a term occurring `counts` times by field
  number in the document `id`, by `weighing`; a field boosted by zero
  weighs nothing."
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
  "The index terms `term` stands for: itself, or every term it is a prefix
  of when `prefix?`, from the sorted terms or, in an index read back from
  EDN, by a scan of them."
  [index term prefix?]
  (let [terms (:terms index)]
    (cond
      (not prefix?)   (when (contains? (:postings index) term) [term])
      (sorted? terms) (subseq terms >= term < (str term "￿"))
      :else           (filter #(str/starts-with? % term) terms))))

(defn- term-scores
  "The score of every document of `index` holding `term`, or when
  `prefix?` any term it begins, each by the best such term: `{id score}`."
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
  "How often the `terms` occur in a row in the document `id`, by field
  number."
  [postings terms id]
  (let [sets (map #(set (at-positions (get-in postings [% id]))) (rest terms))]
    (frequencies (for [start (at-positions (get-in postings [(first terms) id]))
                       :let  [start (long start)]
                       :when (every? identity (map-indexed (fn [i s] (contains? s (+ start (long i) 1))) sets))]
                   (quot start span)))))

(defn- phrase-scores
  "The score of every document of `index` where the `terms` occur in a
  row: `{id score}`."
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
  "The clauses of the query `q`: `{:term t}` for a word, with `:prefix?
  true` on the last when `prefix?`, it is at least `min-prefix` long and
  the query does not end in whitespace or a quote; `{:phrase [...]}` for
  words in quotes or joined by punctuation, as in \"2.0\". A query given as
  a vector is terms already analyzed."
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
  "The score of every document of `index` matching `clause`: `{id score}`."
  [index weighing {:keys [term prefix? phrase]}]
  (if phrase
    (phrase-scores index weighing phrase)
    (term-scores index weighing term prefix?)))

(defn- matching
  "The ids in every one of the `scored` clause maps, or in any of them
  when `match` is `:any`."
  [scored match]
  (case match
    :all (let [[smallest & others] (sort-by count scored)]
           (into [] (filter (fn [id] (every? #(contains? % id) others))) (keys smallest)))
    :any (into [] (distinct) (mapcat keys scored))))

(defn- better
  "Whether the result `a` ranks before `b`: the higher score, then the id
  when both are numbers, else the hash of the id and finally the id
  printed, so that the order is total and stable, and the same on both
  platforms, whose hashes of a number differ. BM25 ties are common, so
  this must stay cheap."
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
  "`results` in the order of the comparator `before`, at most `n` of them
  when `n` is given. When `n` is small next to the results, the best so
  far are kept as a small sorted vector, so that a candidate below the
  worst of them costs one comparison, which is what nearly every candidate
  does; otherwise, as for `n` beyond the square root of their number, all
  are sorted."
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
  "The documents of `index` matching the query `q`, best first, as
  `{:id :score :stored}`. Every word must occur, the last as a prefix
  unless `q` ends in whitespace, words in quotes in a row. `opts`:

      {:limit      10              ; at most this many results
       :match      :any            ; documents matching any word, not all of them
       :prefix?    false           ; the last word taken whole
       :min-prefix 3               ; the last word a prefix only from this many
                                   ; characters (default 1)
       :boosts     {:title 5 ...}  ; field weights, default `boosts`; a field
                                   ; boosted by 0 does not match at all
       :k1 1.2 :b 0.75             ; BM25's saturation and length normalization
       :where      pred            ; keep the results this predicate accepts,
                                   ; each `{:id :score :stored}`
       :rank       f}              ; order the results by this function of each
                                   ; instead of by score, ascending

  Without `:rank`, equal scores are ordered by the id, so that the order
  is stable; `(fn [r] [(- (:score r)) (- (or (some-> r :stored :published inst-ms) 0))])`
  ranks by score and then puts the newest first. The white space of a
  query is the same on both platforms, no-break spaces included."
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
  "A window of `text` around the first words matching the query `q`, the
  matches marked: a vector of `{:text}` parts with `:match? true` on the
  words found and an ellipsis where the window cuts the text, for a
  result list to render. `:width` in `opts` is the number of characters
  shown (default 160), more when the first match is longer."
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
  "Whether `actual` is `expected`, or one of them when `expected` is a set."
  [expected actual]
  (if (set? expected) (contains? expected actual) (= expected actual)))

(defn- within?
  "Whether `x` lies in `[lo hi]`, either end open when nil; false when
  there is no `x`."
  [[lo hi] x]
  (boolean (and (some? x)
                (or (nil? lo) (<= lo x))
                (or (nil? hi) (<= x hi)))))

(defn- url-of
  "The url of `feed` in the context `ctx`: the `:url` given, or what the
  function given returns for the feed."
  [{:keys [url]} feed]
  (if (fn? url) (url feed) url))

(defn ^:no-doc episode-id
  "The id of `item` of `feed` in the player state, by the context `ctx`."
  [ctx feed item]
  (player/episode-id feed (url-of ctx feed) item))

(defn ^:no-doc entry
  "What the player state in `ctx` knows about `item` of `feed`."
  [ctx feed item]
  (player/episode (:player ctx) (episode-id ctx feed item)))

(defn- status
  "Whether the episode with the player `entry` is `:unplayed`,
  `:in-progress` or `:played`."
  [{:keys [position played?]}]
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
  "Whether the set of terms `present` holds every word of the query
  `words`, the last as a prefix."
  [{:keys [terms prefix]} present]
  (boolean (and (every? present terms)
                (or (nil? prefix) (some #(str/starts-with? % prefix) present)))))

(defn- mentioned
  "The predicate of the criterion :text for `v`, a query or a set of
  queries. It tells whether the title or the notes of an item hold one of
  the queries."
  [v]
  (let [queries (mapv words (if (set? v) v [v]))]
    (fn [_ item]
      (let [present (set (concat (tokens (resolve/title item)) (tokens (notes item))))]
        (boolean (some #(mentions? % present) queries))))))

(defn- age
  "The seconds between the publication of `item` and the instant `at`. 0
  for an item with a later date, and nil for an item without a date."
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
  "The test behind each criterion of `where`: a function of the value given
  and the context, returning a predicate of a feed and an item."
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
  "The predicate of a feed and one of its items for `criteria`. Each entry
  of the map must hold:

      {:season      2                      ; or a set of seasons; see resolve/numbering
       :type        \"full\"                 ; \"full\", \"trailer\" or \"bonus\", or a set
       :duration    [600 3600]              ; seconds, either end nil for open
       :published   [#inst \"2026-01-01\" nil]
       :age         [nil 604800]            ; seconds since it came out, either end
                                           ; nil for open; 0 for a later date
       :transcript? true                    ; has a transcript; false for has none
       :chapters?   true                    ; linked, embedded or listed in the notes
       :explicit?   false                   ; the item's flag, else the feed's
       :person      \"Adam\"                 ; someone credited, matched folded
       :text        \"value for\"            ; every word in the title or notes, the
                                           ; last as a prefix; or a set of such
                                           ; texts, for an item with one of them
       :not         {:text #{\"rerun\"}}     ; criteria that the item does not meet
       :status      :unplayed               ; :unplayed, :in-progress or :played, or a set
       :starred?    true                    ; starred; false for not starred
       :inbox?      true                    ; in the inbox; false for not
       :rating      [4 nil]                 ; the rating of the listener, either end
                                           ; nil for open; an episode with no rating
                                           ; is in no range
       :since-subscription? true}           ; came out after the listener subscribed

  `:status`, `:starred?`, `:inbox?`, `:rating` and `:since-subscription?`
  need the player state under `:player` in `ctx`. For feeds that declare
  no podcast:guid, they also need `:url`, a string or a function of the
  feed. The player names the episodes of such a feed by its URL. An empty
  map takes each item.

  `:age` needs the instant of now under `:at` in `ctx`, because nothing
  here reads a clock. It is for a saved filter, where the dates of
  `:published` get old.

  With `:scale` in `ctx`, the range of `:rating` is in that scale, and
  each rating is read in it, as `player/rating` does. Without it, each
  rating is the number that the state has."
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
  "The orders `order` knows, for a UI to offer."
  #{:listening :newest :oldest :shortest :longest :title :recent})

(defn- published-ms
  [item]
  (some-> (resolve/published item) inst-ms))

(defn- last-heard-ms
  "When the listener last touched `item` of `feed`, by the player state in
  `ctx`."
  [ctx feed item]
  (let [{:keys [played-at updated-at]} (entry ctx feed item)]
    (some-> (or played-at updated-at) inst-ms)))

(defn order
  "The sort key of a feed and one of its items for the order `k`, for
  `sort-by`: `:listening` as `resolve/episodes` orders a show, `:newest`
  and `:oldest` by publication, `:shortest` and `:longest` by duration,
  `:title` alphabetically, folded, and `:recent` by when the listener
  last touched the episode, which needs `:player` in `ctx` like `where`.
  Items missing the property sort last. A function of a feed and an item
  is taken as the key itself."
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
  "The items of `coll` in the order of `(sort-by f coll)`, with one call
  of `f` for each item. `sort-by` calls `f` twice for each comparison."
  [f coll]
  (map second (sort-by first (map (juxt f identity) coll))))

(defn episodes
  "The items of `feed` that `opts` selects, in the order it asks for:

      {:where  {:season 2 :status :unplayed}   ; criteria, see `where`; default all
       :order  :newest                          ; see `order`; default :listening
       :limit  20                               ; at most this many
       :player state                            ; the context `where` and `order` need
       :url    url
       :at     #inst \"2026-09-29\"               ; now, for the criterion :age
       :scale  {:worst 1 :best 100}}            ; the scale of the criterion :rating

  A saved filter that keeps the `:scale` with its criteria reads the same
  in an app with another scale of ratings.

  Given a collection of feeds instead, the selection runs over all of
  them and comes back as `[feed item]` pairs in one order, the inbox of
  an app; `:url` is then a function of the feed. The listening order is
  that of one show, so across shows each keeps its episodes together, in
  the order the feeds are given.

  With a `:limit` and an order of `orders`, the criteria read the items in
  that order, and no more items than the limit needs. So a criterion that
  reads the notes of an item, as `:text` does, reads those of a few
  items."
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
  "What in the criteria `where` of the place `place` this version of
  podcast-clj does not know, described. The criteria under :not are
  criteria too."
  [where place]
  (let [unknown (when (map? where) (filter #(not (contains? criterion %)) (keys where)))]
    (cond
      (nil? where)       nil
      (not (map? where)) (str "the criteria under " place " are " (pr-str where) ", not a map")
      (seq unknown)      (str "the criteria " (str/join ", " (map pr-str unknown)) " are not known")
      :else              (where-problem (:not where) :not))))

(defn problem
  "What in `criteria`, the options of `episodes` as a saved filter has them,
  this version of podcast-clj does not know, described. Nil when it knows
  all of them.

  A saved filter comes from a device of the listener, and that device can
  have a later version of podcast-clj. `episodes` throws for criteria that
  it does not know. So an app asks here before it offers such a filter."
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
