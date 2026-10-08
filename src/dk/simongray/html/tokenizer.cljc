(ns dk.simongray.html.tokenizer
  "HTML text as tokens, by the tokenizer of the HTML Standard, section
  13.2.5, as of 2026-10-08.

  The tokens function gives the tokens of a text. Characters come as
  strings, with adjacent runs joined and character references decoded.
  Every other token is a map with a :type:

      {:type :start-tag :name \"a\" :attrs {\"href\" \"/\"} :self-closing? false}
      {:type :end-tag :name \"a\"}
      {:type :comment :data \" a note \"}
      {:type :doctype}

  As in the standard, a token that the end of the text cuts off is
  dropped. The tokenizer reports no parse errors.

  In the standard, the tree builder switches the tokenizer to raw text
  after a start tag such as script or title. Here the :text-states option
  does it, so that the tokens of HTML content come out right without a
  tree builder.

  The tokenizer is for HTML embedded in other content, so it leaves out
  what only whole documents and SVG or MathML need. Each part ends where
  the standard ends it, so no other token changes:

  - a DOCTYPE runs to the next >, without its name, identifiers or the
    force-quirks flag, 13.2.5.53 to 13.2.5.68
  - <? starts a bogus comment, as before the processing instructions of
    13.2.5.72 to 13.2.5.76
  - a CDATA section is a bogus comment, as it is outside SVG and MathML,
    without the CDATA states of 13.2.5.69 to 13.2.5.71

  TODO: read these too, by an option or a namespace of their own, for a
  parser of whole documents or of SVG and MathML."
  (:require [clojure.string :as str]
            [dk.simongray.html.entities :as entities]))

(def text-states
  "The state that the tokenizer switches to after the start tag of each of
  these elements, as the tree builder does for HTML content with scripting
  off, in section 13.2.6.4.7."
  {"title"     :rcdata
   "textarea"  :rcdata
   "style"     :rawtext
   "xmp"       :rawtext
   "iframe"    :rawtext
   "noembed"   :rawtext
   "noframes"  :rawtext
   "script"    :script-data
   "plaintext" :plaintext})

(def ^:no-doc nul
  "U+0000 NULL as a character."
  (char 0))

(def ^:no-doc replacement
  "U+FFFD REPLACEMENT CHARACTER, which takes the place of a NULL."
  (entities/code-point->string 0xFFFD))

(def ^:no-doc longest-name
  "The length of the longest name of a character reference."
  (apply max (map count (keys entities/references))))

(def ^:no-doc longest-legacy-name
  "The length of the longest name that's read without a semicolon."
  (->> (keys entities/references)
       (remove #(str/ends-with? % ";"))
       (map count)
       (apply max)))

;; 13.2.5.84, the code points that a numeric reference to a C1 control
;; stands for, by windows-1252
(def ^:no-doc c1-replacements
  "The code point that each numeric character reference to a C1 control
  gives instead."
  {0x80 0x20AC 0x82 0x201A 0x83 0x0192 0x84 0x201E 0x85 0x2026 0x86 0x2020
   0x87 0x2021 0x88 0x02C6 0x89 0x2030 0x8A 0x0160 0x8B 0x2039 0x8C 0x0152
   0x8E 0x017D 0x91 0x2018 0x92 0x2019 0x93 0x201C 0x94 0x201D 0x95 0x2022
   0x96 0x2013 0x97 0x2014 0x98 0x02DC 0x99 0x2122 0x9A 0x0161 0x9B 0x203A
   0x9C 0x0153 0x9E 0x017E 0x9F 0x0178})

;; Characters
;;
;; A character is compared with identical?, which is = for the ASCII
;; characters that the tokenizer looks for: the JVM boxes each of them to
;; one cached Character (JLS 5.1.7), and JavaScript compares strings by
;; value.

(defn ^:no-doc char-at
  "The character at `i` of the text `s` of length `n`, or nil at its end."
  [s ^long n ^long i]
  (when (< i n)
    #?(:clj  (.charAt ^String s (int i))
       :cljs (.charAt s i))))

(defn ^:no-doc code
  "The code unit of the character `c`."
  [c]
  #?(:clj  (int c)
     :cljs (.charCodeAt c 0)))

(defn ^:no-doc upper?
  "Whether `c` is an ASCII upper alpha."
  [c]
  (let [x (code c)]
    (and (<= 65 x) (<= x 90))))

(defn ^:no-doc alpha?
  "Whether `c` is an ASCII alpha."
  [c]
  (let [x (code c)]
    (or (and (<= 65 x) (<= x 90))
        (and (<= 97 x) (<= x 122)))))

(defn ^:no-doc digit?
  "Whether `c` is an ASCII digit."
  [c]
  (let [x (code c)]
    (and (<= 48 x) (<= x 57))))

(defn ^:no-doc alphanumeric?
  "Whether `c` is an ASCII alphanumeric."
  [c]
  (or (alpha? c) (digit? c)))

(defn ^:no-doc hex-digit?
  "Whether `c` is an ASCII hex digit."
  [c]
  (let [x (code c)]
    (or (and (<= 48 x) (<= x 57))
        (and (<= 65 x) (<= x 70))
        (and (<= 97 x) (<= x 102)))))

(defn ^:no-doc whitespace?
  "Whether `c` is one of the whitespace characters of the tokenizer: tab,
  line feed, form feed and space."
  [c]
  (case c
    (\tab \newline \formfeed \space) true
    false))

(defn ^:no-doc tag-end?
  "Whether `c` ends the name of a tag: whitespace, / or >."
  [c]
  (or (whitespace? c) (identical? \/ c) (identical? \> c)))

;; the predicates that end a run, made once rather than at each run

(def ^:no-doc not-alpha?
  "Whether a character isn't an ASCII alpha."
  (complement alpha?))

(def ^:no-doc not-alphanumeric?
  "Whether a character isn't an ASCII alphanumeric."
  (complement alphanumeric?))

(def ^:no-doc not-digit?
  "Whether a character isn't an ASCII digit."
  (complement digit?))

(def ^:no-doc not-hex-digit?
  "Whether a character isn't an ASCII hex digit."
  (complement hex-digit?))

(def ^:no-doc not-whitespace?
  "Whether a character isn't whitespace."
  (complement whitespace?))

(def ^:no-doc upper-pattern
  "An ASCII upper alpha, anywhere in a text."
  #?(:clj  #"[A-Z]"
     :cljs (js/RegExp. "[A-Z]" "g")))

(defn ^:no-doc lower-ascii
  "The text `s` with its ASCII upper alphas in lower case, and nothing
  else changed."
  [s]
  #?(:clj  (if-not (re-find upper-pattern s)
             s
             (let [sb (StringBuilder. ^String s)]
               (dotimes [k (.length sb)]
                 (let [c (.charAt sb k)]
                   (when (upper? c)
                     (.setCharAt sb k (char (+ (int c) 32))))))
               (.toString sb)))
     :cljs (.replace s upper-pattern #(.toLowerCase %))))

(defn ^:no-doc without-nul
  "The text `s` with each NULL replaced by U+FFFD."
  [s]
  (if (str/includes? s (str nul))
    (str/replace s (str nul) replacement)
    s))

(defn ^:no-doc tag-name
  "The run `s` of a tag or attribute name, as the name states read it: in
  lower case, with each NULL replaced."
  [s]
  (without-nul (lower-ascii s)))

(defn ^:no-doc run-end
  "The index of the first character from `i` in the text `s` of length `n`
  that `stop?` is true for, or `n`."
  ^long [s ^long n ^long i stop?]
  (loop [j i]
    (if (and (< j n) (not (stop? (char-at s n j))))
      (recur (inc j))
      j)))

(defn ^:no-doc skip-whitespace
  "The index of the first character from `i` in the text `s` of length `n`
  that isn't whitespace, or `n`."
  ^long [s ^long n ^long i]
  (run-end s n i not-whitespace?))

(defn ^:no-doc index-of
  "The index of the first `x`, a string, from `i` in the text `s` of length
  `n`, or `n` when there's none."
  ^long [s ^long n x ^long i]
  (let [k (long #?(:clj  (.indexOf ^String s ^String x (int i))
                   :cljs (.indexOf s x i)))]
    (if (neg? k) n k)))

(defn ^:no-doc starts-at?
  "Whether the text `s` has `word` at `i`."
  [s i word]
  #?(:clj  (.startsWith ^String s ^String word (int i))
     :cljs (.startsWith s word i)))

(defn ^:no-doc starts-at-ci?
  "Whether the text `s` of length `n` has `word`, in lower case, at `i`, in
  any case of ASCII."
  [s n i word]
  (let [end (+ i (count word))]
    (and (<= end n) (= word (lower-ascii (subs s i end))))))

;; Text builders: a StringBuilder on the JVM and an array of strings in
;; JavaScript

(defn- builder
  []
  #?(:clj  (StringBuilder.)
     :cljs #js []))

(defn ^:no-doc append!
  "Append the string `x` to the text builder `b`."
  [b x]
  #?(:clj  (.append ^StringBuilder b ^String x)
     :cljs (.push b x))
  b)

(defn ^:no-doc take-text!
  "The text in the builder `b`, or nil when it's empty, and `b` emptied."
  [b]
  #?(:clj  (when (pos? (.length ^StringBuilder b))
             (let [s (.toString ^StringBuilder b)]
               (.setLength ^StringBuilder b 0)
               s))
     :cljs (let [s (.join b "")]
             (set! (.-length b) 0)
             (when-not (= "" s)
               s))))

(defn- with-text
  "The transient vector of tokens `out`, with the text in the builder
  `text` when it has any."
  [out text]
  (if-let [chars (take-text! text)]
    (conj! out chars)
    out))

;; 13.2.5.77 to 13.2.5.84, character references

(defn- named-reference
  "The named character reference whose name starts at `i`, 13.2.5.78 and
  13.2.5.79, as [characters index], in an attribute value when
  `in-attribute?`."
  [s n i in-attribute?]
  (let [end    (min (run-end s n i not-alphanumeric?)
                    (+ i longest-name))
        run    (subs s i end)
        semi   (when (identical? \; (char-at s n end))
                 (get entities/references (str run ";")))
        legacy (when-not semi
                 (some #(when-let [chars (get entities/references (subs run 0 %))]
                          [% chars])
                       (range (min (count run) longest-legacy-name) 0 -1)))]
    (cond
      semi
      [semi (inc end)]

      legacy
      (let [[k chars] legacy
            next      (char-at s n (+ i k))]
        (if (and in-attribute?
                 next
                 (or (identical? next \=) (alphanumeric? next)))
          [(str "&" (subs run 0 k)) (+ i k)]
          [chars (+ i k)]))

      ;; the ambiguous ampersand state reads the alphanumerics as they are
      :else
      ["&" i])))

(defn- code-of
  "The number in `digits`, in `base`, but at most 0x110000, past which
  every number reads the same."
  [digits base]
  (reduce (fn [acc c]
            (min 0x110000
                 (+ (* acc base)
                    #?(:clj  (Character/digit (char c) (int base))
                       :cljs (js/parseInt c base)))))
          0
          digits))

(defn- referenced
  "The character of the numeric reference to the code point `x`, as the
  numeric character reference end state, 13.2.5.84, checks it."
  [x]
  (entities/code-point->string
   (cond
     (zero? x)                         0xFFFD
     (< 0x10FFFF x)                    0xFFFD
     (and (<= 0xD800 x) (<= x 0xDFFF)) 0xFFFD
     :else                             (get c1-replacements x x))))

(defn- numeric-reference
  "The numeric character reference whose digits or x start at `i`,
  13.2.5.80 to 13.2.5.84, as [characters index]."
  [s n i]
  (let [hex?   (contains? #{\x \X} (char-at s n i))
        start  (if hex? (inc i) i)
        first' (char-at s n start)]
    (if (and first' (if hex? (hex-digit? first') (digit? first')))
      (let [end (run-end s n start (if hex? not-hex-digit? not-digit?))]
        [(referenced (code-of (subs s start end) (if hex? 16 10)))
         (if (identical? \; (char-at s n end)) (inc end) end)])
      ;; no digits: the ampersand, the number sign and the x stay text
      [(subs s (- i 2) start) start])))

(defn ^:no-doc character-reference
  "The character reference whose ampersand comes before `i` in the text `s`
  of length `n`, 13.2.5.77, as [characters index], where the characters
  are what the reference gives, or the ampersand alone when it's no
  reference. The `in-attribute?` flag is true in an attribute value, where
  a legacy name before = or an alphanumeric stays text."
  [s n i in-attribute?]
  (let [c (char-at s n i)]
    (cond
      (nil? c)            ["&" i]
      (alphanumeric? c)   (named-reference s n i in-attribute?)
      (identical? \# c)            (numeric-reference s n (inc i))
      :else               ["&" i])))

;; 13.2.5.1 to 13.2.5.5, the states of text

(defn ^:no-doc data-text
  "Read the data state, 13.2.5.1, from `i` onto the builder `text`, as
  [index token state]: up to a less-than sign, after which the markup
  state reads what it starts, or the end.

  The text runs to the next less-than sign or ampersand, which indexOf
  finds. Each index is kept until the reading passes it, so that a text
  with many of one and none of the other is still read in linear time."
  [s n i text]
  (loop [i i lt (index-of s n "<" i) amp (index-of s n "&" i)]
    (let [stop (min lt amp)]
      (when (< i stop)
        (append! text (subs s i stop)))
      (cond
        (= n stop)   [n nil :eof]
        (= lt stop)  [(inc lt) nil :markup]
        :else        (let [[chars j] (character-reference s n (inc amp) false)]
                       (append! text chars)
                       (recur j
                              (if (< lt j) (index-of s n "<" j) lt)
                              (index-of s n "&" j)))))))

(defn ^:no-doc end-tag-at
  "What the less-than sign at `i` starts in a state of raw text, by the
  less-than sign, end tag open and end tag name states of that state:
  [:end index name] for an appropriate end tag for `last`, where index is
  the character after the name, or else [:text index text] for the text
  to emit and the index to go on from."
  [s n i last]
  (let [after (+ i 2)]
    (cond
      (not= \/ (char-at s n (inc i)))
      [:text (inc i) "<"]

      (not (some-> (char-at s n after) alpha?))
      [:text after "</"]

      :else
      (let [end  (run-end s n after not-alpha?)
            name (lower-ascii (subs s after end))
            next (char-at s n end)]
        (if (and (= name last)
                 next
                 (tag-end? next))
          [:end end name]
          [:text end (subs s i end)])))))

(declare tag)

(defn ^:no-doc end-tag
  "Read the rest of the appropriate end tag `name`, whose name ends before
  `i`, as [index token state]."
  [s n i name]
  (conj (tag s n i true name) :data))

(defn ^:no-doc raw-text
  "Read the RCDATA state, 13.2.5.2, when `rcdata?`, or else the RAWTEXT
  state, 13.2.5.3, from `i` onto the builder `text`, with their less-than
  sign and end tag states, 13.2.5.9 to 13.2.5.14, as [index token state]:
  up to an appropriate end tag for `last`, or the end.

  As in data-text, the less-than signs and, in RCDATA, the ampersands are
  found by indexOf, each kept until the reading passes it."
  [s n i text last rcdata?]
  (loop [i i lt (index-of s n "<" i) amp (if rcdata? (index-of s n "&" i) n)]
    (let [stop (min lt amp)]
      (when (< i stop)
        (append! text (without-nul (subs s i stop))))
      (cond
        (= n stop)
        [n nil :eof]

        (= lt stop)
        (let [[kind j x] (end-tag-at s n lt last)]
          (if (= :end kind)
            (end-tag s n j x)
            (do (append! text x)
                (recur j (index-of s n "<" j) amp))))

        :else
        (let [[chars j] (character-reference s n (inc amp) false)]
          (append! text chars)
          (recur j (if (< lt j) (index-of s n "<" j) lt) (index-of s n "&" j)))))))

(defn ^:no-doc script-text
  "Read the script data state, 13.2.5.4, from `i` onto the builder `text`,
  as [index token state], like raw-text.

  The states of escapes, 13.2.5.15 to 13.2.5.31, keep every character as
  text, so they only decide where a script ends: at the first appropriate
  end tag for `last` outside a double escape. So the script is read by
  searches for what changes the mode rather than a state for each
  character, which the tests of html5lib show to be the same:

  - in a script, <!-- starts an escape, whose dashes can end it at once
  - in an escape, --> ends it, and <script followed by a space, / or >
    starts a double escape
  - in a double escape, --> ends both escapes, and </script followed by a
    space, / or > ends the double one"
  [s n i text last]
  (let [closing     (fn [k]
                      (let [[kind j name] (end-tag-at s n k last)]
                        (when (= :end kind)
                          [j name])))
        script-tag? (fn [k offset]
                      (and (starts-at-ci? s n (+ k offset) "script")
                           (some-> (char-at s n (+ k offset 6)) tag-end?)))
        finish      (fn [end]
                      (append! text (without-nul (subs s i end))))]
    ;; arrow is the next --> once an escape needs it, kept until passed
    (loop [pos i mode :script arrow -1]
      (let [arrow (if (and (not= :script mode) (< arrow pos))
                    (index-of s n "-->" pos)
                    arrow)]
        (case mode
          :script
          (let [k (index-of s n "<" pos)]
            (cond
              (= n k)                 (do (finish n) [n nil :eof])
              (starts-at? s k "<!--") (recur (+ k 2) :escaped -1)
              (closing k)             (let [[j name] (closing k)]
                                        (finish k)
                                        (end-tag s n j name))
              :else                   (recur (inc k) mode arrow)))

          :escaped
          (let [k (index-of s n "<" pos)]
            (cond
              (< arrow k)        (recur (+ arrow 3) :script -1)
              (= n k)            (do (finish n) [n nil :eof])
              (closing k)        (let [[j name] (closing k)]
                                   (finish k)
                                   (end-tag s n j name))
              (script-tag? k 1)  (recur (+ k 8) :double-escaped arrow)
              :else              (recur (inc k) mode arrow)))

          :double-escaped
          (let [k (index-of s n "</" pos)]
            (cond
              (< arrow k)        (recur (+ arrow 3) :script -1)
              (= n k)            (do (finish n) [n nil :eof])
              (script-tag? k 2)  (recur (+ k 9) :escaped arrow)
              :else              (recur (inc k) mode arrow))))))))

(defn ^:no-doc plain-text
  "Read the PLAINTEXT state, 13.2.5.5, from `i` to the end onto the
  builder `text`, as [index token state]."
  [s n i text]
  (append! text (without-nul (subs s i)))
  [n nil :eof])

;; 13.2.5.8 and 13.2.5.32 to 13.2.5.40, tags

(defn- attribute-name-end?
  [c]
  (or (tag-end? c) (identical? \= c)))

(defn- unquoted-value-end?
  [c]
  (or (whitespace? c) (identical? \> c)))

(defn ^:no-doc value-text
  "The raw value `s` of an attribute with its character references
  decoded, as in an attribute, and each NULL replaced. A reference ends
  inside the value, so the value is decoded on its own."
  [s]
  (let [n (count s)]
    (if (= n (index-of s n "&" 0))
      (without-nul s)
      (let [text (builder)]
        (loop [i 0]
          (let [amp (index-of s n "&" i)]
            (append! text (subs s i amp))
            (if (= n amp)
              (without-nul (or (take-text! text) ""))
              (let [[chars j] (character-reference s n (inc amp) true)]
                (append! text chars)
                (recur (long j))))))))))

(defn ^:no-doc attribute
  "Read the attribute whose name starts at `i`, as [index [name value]], or
  nil when the end of the text cuts it off.

  These are the states from the attribute name state, 13.2.5.33, to the
  after attribute value state, 13.2.5.39. The name runs to whitespace, /,
  > or =, though = can be its first character. Whitespace can come on
  either side of the = before a value. A value in quotes runs to its
  quote, and one without them to whitespace or >. Without a value, the
  value is the empty string."
  [s n i]
  (let [name-end (run-end s n (if (identical? \= (char-at s n i)) (inc i) i)
                          attribute-name-end?)
        name     (tag-name (subs s i name-end))
        j        (skip-whitespace s n name-end)]
    (cond
      (= n j)
      nil

      (not (identical? \= (char-at s n j)))
      [j [name ""]]

      :else
      (let [k (skip-whitespace s n (inc j))
            c (char-at s n k)]
        (cond
          (nil? c)
          nil

          (or (identical? \" c) (identical? \' c))
          (let [end (index-of s n (str c) (inc k))]
            (when (< end n)
              [(inc end) [name (value-text (subs s (inc k) end))]]))

          (identical? \> c)
          [k [name ""]]

          :else
          (let [end (run-end s n k unquoted-value-end?)]
            (when (< end n)
              [end [name (value-text (subs s k end))]])))))))

(defn- tag-token
  [end? name attrs self-closing?]
  (if end?
    {:type :end-tag :name name}
    {:type :start-tag :name name :attrs attrs :self-closing? self-closing?}))

(defn ^:no-doc tag
  "Read the tag whose name starts at `i`, a start tag or, when `end?`, an
  end tag, as [index token]. The `name` is the part of the name that a
  state of raw text read already, or nil. The token is nil when the end of
  the text cuts the tag off.

  These are the tag name state, 13.2.5.8, and the states of attributes,
  13.2.5.32 to 13.2.5.40. Between attributes, whitespace is skipped, > ends
  the tag, and so does /> with the self-closing flag, while a / before
  anything else is dropped. An attribute that repeats a name is dropped."
  [s n i end? name]
  (let [j    (run-end s n i tag-end?)
        name (str name (tag-name (subs s i j)))]
    (loop [i j attrs {}]
      (let [i (skip-whitespace s n i)
            c (char-at s n i)]
        (cond
          (nil? c)          [n nil]
          (identical? \> c) [(inc i) (tag-token end? name attrs false)]
          (identical? \/ c) (if (identical? \> (char-at s n (inc i)))
                              [(+ i 2) (tag-token end? name attrs true)]
                              (recur (inc i) attrs))
          :else             (if-let [[j [k v]] (attribute s n i)]
                              (recur (long j)
                                     (cond-> attrs
                                       (not (contains? attrs k)) (assoc k v)))
                              [n nil]))))))

;; 13.2.5.41 to 13.2.5.52, comments

(defn ^:no-doc comment-token
  "A comment token with the `data`."
  [data]
  {:type :comment :data data})

(defn ^:no-doc bogus-comment
  "Read the bogus comment state, 13.2.5.41, from `i`, after the `data` that
  the comment has already, as [index token]: to the next > or the end."
  [s n i data]
  (let [end (index-of s n ">" i)]
    [(min n (inc end)) (comment-token (str data (without-nul (subs s i end))))]))

(defn- unclosed
  "The `data` of a comment that the end of the text cut off, without the
  --!, -- or - that the comment states hold back as a possible end."
  [data]
  (cond
    (str/ends-with? data "--!") (subs data 0 (- (count data) 3))
    (str/ends-with? data "--")  (subs data 0 (- (count data) 2))
    (str/ends-with? data "-")   (subs data 0 (- (count data) 1))
    :else                       data))

(defn ^:no-doc comment-text
  "Read a comment after its <!--, as [index token].

  The comment states, 13.2.5.43 to 13.2.5.52, end a comment at once with >
  or ->, or else at the first --> or --!>, and keep every other character
  as data. So the comment is found by those searches rather than a state
  for each character, which the tests of html5lib show to be the same.
  A comment that the end of the text cuts off ends there."
  [s n i]
  (let [c (char-at s n i)]
    (if (or (identical? \> c)
            (and (identical? \- c) (identical? \> (char-at s n (inc i)))))
      [(if (identical? \> c) (inc i) (+ i 2)) (comment-token "")]
      (let [arrow (index-of s n "-->" i)
            bang  (index-of s n "--!>" i)
            end   (min arrow bang)]
        (if (< end n)
          [(+ end (if (= end arrow) 3 4)) (comment-token (without-nul (subs s i end)))]
          [n (comment-token (without-nul (unclosed (subs s i))))])))))

;; 13.2.5.53 to 13.2.5.68, DOCTYPE

(defn ^:no-doc doctype-text
  "Read a DOCTYPE after its <!DOCTYPE, as [index token]. Each DOCTYPE state
  of 13.2.5.53 to 13.2.5.68 ends it at the next > or the end, so it's read
  to there, without its name, identifiers or force-quirks flag."
  [s n i]
  [(min n (inc (index-of s n ">" i))) {:type :doctype}])

;; 13.2.5.6, 13.2.5.7 and 13.2.5.42, what a less-than sign starts

(defn- end-tag-open
  "Read the end tag open state, 13.2.5.7, from `i` after </, as [index
  token state]."
  [s n i]
  (let [c (char-at s n i)]
    (cond
      (nil? c)          [n "</" :eof]
      (alpha? c)        (conj (tag s n i true nil) :data)
      (identical? \> c) [(inc i) nil :data]
      :else             (conj (bogus-comment s n i "") :data))))

(defn- markup-declaration
  "Read the markup declaration open state, 13.2.5.42, from `i` after <!, as
  [index token state]. [CDATA[ always starts a bogus comment, as it does
  outside SVG and MathML."
  [s n i]
  (conj (cond
          (starts-at? s i "--")           (comment-text s n (+ i 2))
          (starts-at-ci? s n i "doctype") (doctype-text s n (+ i 7))
          (starts-at? s i "[CDATA[")      (bogus-comment s n (+ i 7) "[CDATA[")
          :else                           (bogus-comment s n i ""))
        :data))

(defn ^:no-doc markup
  "Read what a less-than sign starts, from `i` after it, as [index token
  state], by the tag open state, 13.2.5.6: a tag, a comment or a DOCTYPE,
  or else the less-than sign as text. <? starts a bogus comment, as it did
  before the processing instructions of 13.2.5.72."
  [s n i]
  (let [c (char-at s n i)]
    (cond
      (nil? c)          [n "<" :eof]
      (identical? \! c) (markup-declaration s n (inc i))
      (identical? \/ c) (end-tag-open s n (inc i))
      (identical? \? c) (conj (bogus-comment s n i "") :data)
      (alpha? c)        (conj (tag s n i false nil) :data)
      :else             [i "<" :data])))

(defn ^:no-doc normalized
  "The text `s` with each CR LF pair and each lone CR turned into an LF, as
  section 13.2.3.5 preprocesses the input stream."
  [s]
  (if (str/includes? s "\r")
    (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n"))
    s))

;; 13.2.2, parse errors. The standard names each error that the tokenizer
;; meets and recovers from it in one defined way, so the tokens are the
;; same whether or not it's reported. None are reported here: the searches
;; that read comments, scripts and DOCTYPEs pass over the states that raise
;; many of them, and checking for them would slow every parse. They suit a
;; validator of HTML, not a reader of embedded HTML.

(defn tokens
  "The tokens of the HTML text `s`, as a vector, read with the `opts`:

  - :state, the state to start in: :data, the default, :rcdata, :rawtext,
    :script-data or :plaintext
  - :last-start-tag, the name of the last start tag, which an end tag in
    raw text must match, as when the text follows that tag
  - :text-states, the state to switch to after the start tag of each
    element, text-states by default"
  ([s]
   (tokens s {}))
  ([s {:keys [state last-start-tag text-states]
       :or   {state :data text-states text-states}}]
   (let [s    (normalized (str s))
         n    (count s)
         text (builder)]
     ;; Each reader gives [index token state]. Text goes onto the builder,
     ;; so that adjacent runs join, and every other token onto out after
     ;; the text before it. A start tag sets the state by text-states.
     (loop [i 0 st state last last-start-tag out (transient [])]
       (if (= :eof st)
         (persistent! (with-text out text))
         (let [[j token st'] (case st
                               :data        (data-text s n i text)
                               :rcdata      (raw-text s n i text last true)
                               :rawtext     (raw-text s n i text last false)
                               :script-data (script-text s n i text last)
                               :plaintext   (plain-text s n i text)
                               :markup      (markup s n i))
               start         (when (= :start-tag (:type token))
                               (:name token))]
           (when (string? token)
             (append! text token))
           (recur (long j)
                  (if start (get text-states start :data) st')
                  (or start last)
                  (if (map? token)
                    (conj! (with-text out text) token)
                    out))))))))

(comment
  (tokens "<p class=note>Fish &amp chips &notin; <b>bold</p><!-- x -->")
  ;; => [{:type :start-tag, :name "p", :attrs {"class" "note"}, :self-closing? false}
  ;;     "Fish & chips ∉ "
  ;;     {:type :start-tag, :name "b", :attrs {}, :self-closing? false}
  ;;     "bold"
  ;;     {:type :end-tag, :name "p"}
  ;;     {:type :comment, :data " x "}]
  (tokens "<script>if (a<b) x = '</p>'</script>done")
  #_.)
