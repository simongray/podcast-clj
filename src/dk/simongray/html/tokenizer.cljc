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
  [s n i]
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
  [s n i stop?]
  (loop [j i]
    (if (and (< j n) (not (stop? (char-at s n j))))
      (recur (inc j))
      j)))

(defn ^:no-doc index-of
  "The index of the first `x`, a string, from `i` in the text `s` of length
  `n`, or `n` when there's none."
  ^long [s ^long n x ^long i]
  (let [k #?(:clj  (.indexOf ^String s ^String x (int i))
             :cljs (.indexOf s x i))]
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

;; 13.2.5.77 to 13.2.5.84, character references

(defn- named-reference
  "The named character reference whose name starts at `i`, 13.2.5.78 and
  13.2.5.79, as [characters index], in an attribute value when
  `in-attribute?`."
  [s n i in-attribute?]
  (let [end    (min (run-end s n i #(not (alphanumeric? %)))
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
        digit  (if hex? hex-digit? digit?)
        first' (char-at s n start)]
    (if (and first' (digit first'))
      (let [end (run-end s n start #(not (digit %)))]
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
  [index state]: up to a less-than sign, which leads to the tag open
  state, or the end.

  The text runs to the next less-than sign or ampersand, which indexOf
  finds. Each index is kept until the reading passes it, so that a text
  with many of one and none of the other is still read in linear time."
  [s n i text]
  (loop [i i lt (index-of s n "<" i) amp (index-of s n "&" i)]
    (let [stop (min lt amp)]
      (when (< i stop)
        (append! text (subs s i stop)))
      (cond
        (= n stop)   [n :eof]
        (= lt stop)  [(inc lt) :tag-open]
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
      (let [end  (run-end s n after #(not (alpha? %)))
            name (lower-ascii (subs s after end))
            next (char-at s n end)]
        (if (and (= name last)
                 next
                 (or (whitespace? next) (identical? \/ next) (identical? \> next)))
          [:end end name]
          [:text end (subs s i end)])))))

(defn- raw-stop?
  [c]
  (or (identical? \< c) (identical? \& c) (identical? nul c)))

(defn ^:no-doc raw-text
  "Read the RCDATA state, 13.2.5.2, when `rcdata?`, or else the RAWTEXT
  state, 13.2.5.3, from `i` onto the builder `text`, with their less-than
  sign and end tag states, 13.2.5.9 to 13.2.5.14, as [index state name]:
  up to an appropriate end tag for `last`, as [index :end-tag name] at the
  character after the name, or the end."
  [s n i text rcdata? last]
  (loop [i i]
    (let [c (char-at s n i)]
      (cond
        (nil? c)
        [n :eof]

        (and (identical? \& c) rcdata?)
        (let [[chars j] (character-reference s n (inc i) false)]
          (append! text chars)
          (recur j))

        (identical? \< c)
        (let [[kind j x] (end-tag-at s n i last)]
          (if (= :end kind)
            [j :end-tag x]
            (do (append! text x)
                (recur j))))

        (identical? nul c)
        (do (append! text replacement)
            (recur (inc i)))

        :else
        (let [j (run-end s n (inc i) raw-stop?)]
          (append! text (subs s i j))
          (recur j))))))

(defn- script-stop?
  [c]
  (or (identical? \< c) (identical? \- c) (identical? nul c)))

(defn ^:no-doc script-text
  "Read the script data state, 13.2.5.4, from `i` onto the builder `text`,
  with its states of escapes, 13.2.5.15 to 13.2.5.31, as [index state
  name], like raw-text."
  [s n i text last]
  (letfn [(less-than [i escaped?]
            ;; the less-than sign states, with their end tag states
            (let [after (char-at s n (inc i))]
              (cond
                (identical? \/ after)
                (let [[kind j x] (end-tag-at s n i last)]
                  (if (= :end kind)
                    [:end j x]
                    [:text j x (if escaped? :escaped :script)]))

                (and (identical? \! after) (not escaped?))
                [:text (+ i 2) "<!" :escape-start]

                (and escaped? after (alpha? after))
                [:text (inc i) "<" :double-escape-start]

                :else
                [:text (inc i) "<" (if escaped? :escaped :script)])))]
    (loop [i i st :script temp ""]
      (let [c (char-at s n i)]
        (if (nil? c)
          [n :eof]
          (case st
            :script
            (cond
              (identical? \< c)   (let [[kind j x st'] (less-than i false)]
                           (if (= :end kind)
                             [j :end-tag x]
                             (do (append! text x) (recur j st' temp))))
              (identical? nul c)  (do (append! text replacement) (recur (inc i) st temp))
              :else      (let [j (run-end s n (inc i) #(or (identical? \< %) (identical? nul %)))]
                           (append! text (subs s i j))
                           (recur j st temp)))

            (:escape-start :escape-start-dash)
            (if (identical? \- c)
              (do (append! text "-")
                  (recur (inc i)
                         (if (= :escape-start st) :escape-start-dash :escaped-dash-dash)
                         temp))
              (recur i :script temp))

            (:escaped :escaped-dash :escaped-dash-dash)
            (cond
              (identical? \- c)   (do (append! text "-")
                             (recur (inc i)
                                    (if (= :escaped st) :escaped-dash :escaped-dash-dash)
                                    temp))
              (identical? \< c)   (let [[kind j x st'] (less-than i true)]
                           (if (= :end kind)
                             [j :end-tag x]
                             (do (append! text x) (recur j st' ""))))
              (and (identical? \> c) (= :escaped-dash-dash st))
              (do (append! text ">") (recur (inc i) :script temp))
              (identical? nul c)  (do (append! text replacement) (recur (inc i) :escaped temp))
              (= :escaped st)
              (let [j (run-end s n (inc i) script-stop?)]
                (append! text (subs s i j))
                (recur j :escaped temp))
              :else      (do (append! text (str c)) (recur (inc i) :escaped temp)))

            (:double-escape-start :double-escape-end)
            (cond
              (or (whitespace? c) (identical? \/ c) (identical? \> c))
              (do (append! text (str c))
                  (recur (inc i)
                         (if (= (= "script" temp) (= :double-escape-start st))
                           :double-escaped
                           :escaped)
                         temp))

              (alpha? c)
              (do (append! text (str c))
                  (recur (inc i) st (str temp (lower-ascii (str c)))))

              :else
              (recur i (if (= :double-escape-start st) :escaped :double-escaped) temp))

            (:double-escaped :double-escaped-dash :double-escaped-dash-dash)
            (cond
              (identical? \- c)   (do (append! text "-")
                             (recur (inc i)
                                    (if (= :double-escaped st)
                                      :double-escaped-dash
                                      :double-escaped-dash-dash)
                                    temp))
              (identical? \< c)   (do (append! text "<")
                             (if (identical? \/ (char-at s n (inc i)))
                               (do (append! text "/")
                                   (recur (+ i 2) :double-escape-end ""))
                               (recur (inc i) :double-escaped temp)))
              (and (identical? \> c) (= :double-escaped-dash-dash st))
              (do (append! text ">") (recur (inc i) :script temp))
              (identical? nul c)  (do (append! text replacement) (recur (inc i) :double-escaped temp))
              (= :double-escaped st)
              (let [j (run-end s n (inc i) script-stop?)]
                (append! text (subs s i j))
                (recur j :double-escaped temp))
              :else      (do (append! text (str c)) (recur (inc i) :double-escaped temp)))))))))

(defn ^:no-doc plain-text
  "Read the PLAINTEXT state, 13.2.5.5, from `i` to the end onto the
  builder `text`."
  [s n i text]
  (append! text (without-nul (subs s i)))
  [n :eof])

;; 13.2.5.8 and 13.2.5.32 to 13.2.5.40, tags

(defn- name-stop?
  [c]
  (or (whitespace? c) (identical? \/ c) (identical? \> c)))

(defn- attribute-name-stop?
  [c]
  (or (whitespace? c) (identical? \/ c) (identical? \> c) (identical? \= c)))

(defn- with-attribute
  "The `attrs` of a tag with the attribute `attr`, a pair of name and
  value, unless it's nil or a duplicate, which the tokenizer drops."
  [attrs [k v :as attr]]
  (if (or (nil? attr) (contains? attrs k))
    attrs
    (assoc attrs k v)))

(defn ^:no-doc tag
  "Read the tag whose name starts at `i`, a start tag or, when `end?`, an
  end tag, from the tag name state, 13.2.5.8, through the states of its
  attributes, 13.2.5.32 to 13.2.5.40, as [index token]. The `name` is the
  part of the name that a state of raw text read already, or nil. The
  token is nil when the end of the text cuts the tag off."
  [s n i end? name]
  (letfn [(token [name attrs self-closing?]
            (if end?
              {:type :end-tag :name name}
              {:type :start-tag :name name :attrs attrs :self-closing? self-closing?}))]
    (loop [i i st :tag-name name (or name "") attrs {} attr nil]
      (let [c (char-at s n i)]
        (if (nil? c)
          [n nil]
          (case st
            :tag-name
            (cond
              (whitespace? c) (recur (inc i) :before-attribute-name name attrs attr)
              (identical? \/ c)        (recur (inc i) :self-closing name attrs attr)
              (identical? \> c)        [(inc i) (token name attrs false)]
              :else           (let [j (run-end s n i name-stop?)]
                                (recur j st (str name (tag-name (subs s i j))) attrs attr)))

            :before-attribute-name
            (cond
              (whitespace? c)        (recur (inc i) st name attrs attr)
              (or (identical? \/ c) (identical? \> c)) (recur i :after-attribute-name name attrs attr)
              (identical? \= c)               (recur (inc i) :attribute-name name
                                            (with-attribute attrs attr) ["=" ""])
              :else                  (recur i :attribute-name name
                                            (with-attribute attrs attr) ["" ""]))

            :attribute-name
            (if (attribute-name-stop? c)
              (recur (if (identical? \= c) (inc i) i)
                     (if (identical? \= c) :before-attribute-value :after-attribute-name)
                     name attrs attr)
              (let [j (run-end s n i attribute-name-stop?)]
                (recur j st name attrs (update attr 0 str (tag-name (subs s i j))))))

            :after-attribute-name
            (cond
              (whitespace? c) (recur (inc i) st name attrs attr)
              (identical? \/ c)        (recur (inc i) :self-closing name attrs attr)
              (identical? \= c)        (recur (inc i) :before-attribute-value name attrs attr)
              (identical? \> c)        [(inc i) (token name (with-attribute attrs attr) false)]
              :else           (recur i :attribute-name name (with-attribute attrs attr) ["" ""]))

            :before-attribute-value
            (cond
              (whitespace? c) (recur (inc i) st name attrs attr)
              (identical? \" c)        (recur (inc i) :double-quoted name attrs attr)
              (identical? \' c)        (recur (inc i) :single-quoted name attrs attr)
              (identical? \> c)        [(inc i) (token name (with-attribute attrs attr) false)]
              :else           (recur i :unquoted name attrs attr))

            (:double-quoted :single-quoted :unquoted)
            (let [delimiter (case st :double-quoted \" :single-quoted \' nil)]
              (cond
                (and delimiter (identical? delimiter c))
                (recur (inc i) :after-quoted-value name attrs attr)

                (and (nil? delimiter) (whitespace? c))
                (recur (inc i) :before-attribute-name name attrs attr)

                (and (nil? delimiter) (identical? \> c))
                [(inc i) (token name (with-attribute attrs attr) false)]

                (identical? \& c)
                (let [[chars j] (character-reference s n (inc i) true)]
                  (recur j st name attrs (update attr 1 str chars)))

                (identical? nul c)
                (recur (inc i) st name attrs (update attr 1 str replacement))

                ;; a quoted value runs to its quote, and within it to an
                ;; ampersand or a NULL, so each search is bounded by the
                ;; value
                delimiter
                (let [end   (index-of s n (str delimiter) i)
                      value (subs s i end)
                      k     (count value)
                      j     (+ i (min (index-of value k "&" 0)
                                      (index-of value k (str nul) 0)))]
                  (recur j st name attrs (update attr 1 str (subs s i j))))

                :else
                (let [j (run-end s n i #(or (whitespace? %)
                                            (identical? \& %)
                                            (identical? \> %)
                                            (identical? nul %)))]
                  (recur j st name attrs (update attr 1 str (subs s i j))))))

            :after-quoted-value
            (cond
              (whitespace? c) (recur (inc i) :before-attribute-name name attrs attr)
              (identical? \/ c)        (recur (inc i) :self-closing name attrs attr)
              (identical? \> c)        [(inc i) (token name (with-attribute attrs attr) false)]
              :else           (recur i :before-attribute-name name attrs attr))

            :self-closing
            (if (identical? \> c)
              [(inc i) (token name (with-attribute attrs attr) true)]
              (recur i :before-attribute-name name attrs attr))))))))

;; 13.2.5.41 to 13.2.5.52, comments

(defn ^:no-doc comment-token
  "A comment token with the `data`."
  [data]
  {:type :comment :data data})

(defn ^:no-doc bogus-comment
  "Read the bogus comment state, 13.2.5.41, from `i`, after the `data` that
  the comment has already, as [index token]."
  [s n i data]
  (loop [i i data data]
    (let [c (char-at s n i)]
      (cond
        (nil? c)   [n (comment-token data)]
        (identical? \> c)   [(inc i) (comment-token data)]
        (identical? nul c)  (recur (inc i) (str data replacement))
        :else      (let [j (run-end s n i #(or (identical? \> %) (identical? nul %)))]
                     (recur j (str data (subs s i j))))))))

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

;; 13.2.5.6, 13.2.5.7 and 13.2.5.42, the states between tokens

(defn ^:no-doc normalized
  "The text `s` with each CR LF pair and each lone CR turned into an LF, as
  section 13.2.3.5 preprocesses the input stream."
  [s]
  (if (str/includes? s "\r")
    (-> s (str/replace "\r\n" "\n") (str/replace "\r" "\n"))
    s))

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
         text (builder)
         out  (volatile! (transient []))
         emit (fn [token]
                (when token
                  (when-let [chars (take-text! text)]
                    (vswap! out conj! chars))
                  (vswap! out conj! token)))
         ;; the end tag that a state of raw text found, or that state's end
         after-text (fn [[i st name]]
                      (if (= :end-tag st)
                        (let [[j token] (tag s n i true name)]
                          (emit token)
                          [j :data])
                        [i st]))]
     ;; the readers give each index boxed in a vector, so i is an object
     (loop [i (identity 0) st state last last-start-tag]
       (case st
         :data          (let [[j st] (data-text s n i text)] (recur j st last))
         :rcdata        (let [[j st] (after-text (raw-text s n i text true last))] (recur j st last))
         :rawtext       (let [[j st] (after-text (raw-text s n i text false last))] (recur j st last))
         :script-data   (let [[j st] (after-text (script-text s n i text last))] (recur j st last))
         :plaintext     (let [[j st] (plain-text s n i text)] (recur j st last))

         ;; 13.2.5.6, where <? is a bogus comment, as it was before the
         ;; processing instructions of 13.2.5.72
         :tag-open
         (let [c (char-at s n i)]
           (cond
             (nil? c)          (do (append! text "<") (recur n :eof last))
             (identical? \! c) (recur (inc i) :markup-declaration last)
             (identical? \/ c) (recur (inc i) :end-tag-open last)
             (identical? \? c) (let [[j token] (bogus-comment s n i "")]
                                 (emit token)
                                 (recur j :data last))
             (alpha? c)        (let [[j token] (tag s n i false nil)]
                                 (if token
                                   (do (emit token)
                                       (recur j (get text-states (:name token) :data) (:name token)))
                                   (recur j :eof last)))
             :else             (do (append! text "<") (recur i :data last))))

         ;; 13.2.5.7
         :end-tag-open
         (let [c (char-at s n i)]
           (cond
             (nil? c)   (do (append! text "</") (recur n :eof last))
             (alpha? c) (let [[j token] (tag s n i true nil)]
                          (emit token)
                          (recur j :data last))
             (identical? \> c)   (recur (inc i) :data last)
             :else      (let [[j token] (bogus-comment s n i "")]
                          (emit token)
                          (recur j :data last))))

         ;; 13.2.5.42, where [CDATA[ always starts a bogus comment, as it
         ;; does outside SVG and MathML
         :markup-declaration
         (let [[j token] (cond
                           (starts-at? s i "--")             (comment-text s n (+ i 2))
                           (starts-at-ci? s n i "doctype")   (doctype-text s n (+ i 7))
                           (starts-at? s i "[CDATA[")        (bogus-comment s n (+ i 7) "[CDATA[")
                           :else                             (bogus-comment s n i ""))]
           (emit token)
           (recur j :data last))

         :eof
         (let [chars (take-text! text)]
           (persistent! (cond-> @out
                          chars (conj! chars)))))))))

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
