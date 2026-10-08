(ns dk.simongray.html
  "HTML embedded in other content, such as show notes, comments or the
  fields of a CMS, read into Hiccup and rendered from there.

  A parse gives a sequence of nodes. An element is a vector of a keyword
  tag, a map of keyword attributes and its children, and text is a string.
  For HTML that a client can render, use the hiccup function. It parses
  and sanitizes HTML, and makes paragraphs of plain text. The text and
  markdown functions render HTML or Hiccup as text, and emit writes Hiccup
  back as HTML.

  The tokens are those of the HTML Standard, by dk.simongray.html.tokenizer,
  and the tree follows the rules of the standard that such content needs.
  It's no parser of whole documents.

  TODO: the rules of the tree builder that a browser follows and this
  leaves out, by an option or a namespace of their own: formatting that a
  browser carries on past a misnested end tag, the repairs of tables, and
  SVG and MathML.

  TODO: an optional report of what parse discarded and what sanitize
  removed, e.g. in metadata, so that a validator can tell the author of
  the HTML what a client won't show."
  (:require [clojure.string :as str]
            [dk.simongray.html.tokenizer :as tokenizer]))

;; HTML Standard, "ASCII whitespace": tab, LF, FF, CR and space. HTML
;; collapses and trims no other whitespace, so a no-break space stays. The
;; \s of regular expressions and str/trim differ on it between the JVM and
;; JavaScript, so neither is used for text.

(defn ^:no-doc whitespace?
  "Whether the character `c` is ASCII whitespace."
  [c]
  (case c
    (\tab \newline \formfeed \return \space) true
    false))

(defn ^:no-doc trim-whitespace
  "The text `s` without the ASCII whitespace at its ends."
  [s]
  (let [n     (count s)
        end   (loop [i n]
                (if (and (pos? i) (whitespace? (tokenizer/char-at s n (dec i))))
                  (recur (dec i))
                  i))
        start (loop [i 0]
                (if (and (< i end) (whitespace? (tokenizer/char-at s n i)))
                  (recur (inc i))
                  i))]
    (if (and (zero? start) (= n end))
      s
      (subs s start end))))

(defn ^:no-doc blank?
  "Whether the text `s` holds nothing but ASCII whitespace."
  [s]
  (let [n (count s)]
    (loop [i 0]
      (cond
        (= n i)                                   true
        (whitespace? (tokenizer/char-at s n i))   (recur (inc i))
        :else                                     false))))

(def ^:no-doc markup
  "What makes a text HTML rather than plain text: a comment or a
  whole tag. Plain text may hold an email address in angle brackets, or a
  less-than sign before a 3, and neither is a tag."
  #"<!--|<[a-zA-Z][a-zA-Z0-9]*(?:[\t\n\f\r ][^<>]*)?/?>|</[a-zA-Z][a-zA-Z0-9]*[\t\n\f\r ]*>")

;; HTML Standard, "Elements": the void elements, and param of older pages
(def ^:no-doc void-elements
  "Elements that never have content or an end tag."
  #{:area :base :br :col :embed :hr :img :input :link :meta :param :source
    :track :wbr})

;; HTML Standard, "Optional tags", as of 2026-09-29
(def ^:no-doc block-elements
  "Elements whose start tag closes an open paragraph, as HTML defines."
  #{:address :article :aside :blockquote :details :dialog :div :dl :fieldset
    :figcaption :figure :footer :form :h1 :h2 :h3 :h4 :h5 :h6 :header :hgroup
    :hr :main :menu :nav :ol :p :pre :search :section :table :ul})

;; HTML Standard, "Optional tags", and 13.2.6.4.7 for a, which closes an
;; open a up to the markers of the list of active formatting elements
(def ^:no-doc implied-end
  "For each tag, the open elements that its start tag closes, and the
  ancestors where the search for them stops. A new li closes an open li,
  but only inside the same list."
  {:li     [#{:li} #{:ul :ol :menu}]
   :dt     [#{:dt :dd} #{:dl}]
   :dd     [#{:dt :dd} #{:dl}]
   :tr     [#{:tr :td :th} #{:table}]
   :td     [#{:td :th} #{:tr :table}]
   :th     [#{:td :th} #{:tr :table}]
   :option [#{:option} #{:select}]
   :a      [#{:a} #{:td :th :caption :applet :marquee :object :template}]})

(def ^:no-doc headings
  "The headings, whose start tag closes a heading that's the current node,
  as 13.2.6.4.7 of the HTML Standard has it."
  #{:h1 :h2 :h3 :h4 :h5 :h6})

(def ^:no-doc foreign-roots
  "The roots of SVG and MathML, inside which an element can close itself
  with a slash, as 13.2.6.5 of the HTML Standard has it."
  #{:svg :math})

(def ^:no-doc paragraph-scope
  "The elements that bound the search for an open p: block-level ones, list
  items and table cells."
  (into block-elements #{:li :td :th :dt :dd :body}))

(def ^:no-doc newline-dropping
  "The elements whose start tag drops a line feed right after it, as
  13.2.6.4.7 of the HTML Standard has it."
  #{:pre :listing :textarea})

(def max-depth
  "How deep the parse function nests elements, as deep as WebKit and
  Chromium do. An element opened deeper is kept empty, and its content goes
  to its parent, so no walk of the tree recurses without bound."
  512)

;; The parse builds the tree in a builder, a transient map of :stack, the
;; open elements, the root first, each a transient vector [tag attrs &
;; children], and :open, how many are open by tag, so that an end tag
;; nothing matches costs nothing to ignore. Each function of the builder
;; takes it and gives it back, through the values that assoc!, conj! and
;; pop! return. Tags are the keywords of Hiccup.

(defn ^:no-doc merge-text
  "The element `node` with each run of adjacent strings among its children
  joined into one. Only a comment or a stray end tag between two texts
  leaves such a run, so most elements come back as they are."
  [node]
  (if (loop [i 3]
        (cond
          (<= (count node) i)                                   false
          (and (string? (nth node i)) (string? (nth node (dec i)))) true
          :else                                                 (recur (inc i))))
    (into []
          (comp (partition-by string?)
                (mapcat #(if (string? (first %)) [(apply str %)] %)))
          node)
    node))

(defn- top
  "The stack index of the top element of the builder `b`."
  [b]
  (dec (count (:stack b))))

(defn- tag-at
  "The tag of the open element at the stack index `i` of the builder `b`."
  [b i]
  (nth (nth (:stack b) i) 0))

(defn- open?
  "Whether an element of one of `tags` is open in the builder `b`."
  [b tags]
  (let [open (:open b)]
    (some #(pos? (get open % 0)) tags)))

(defn- update-count!
  "The builder `b` with the count of the open elements of `tag` changed by
  `f`."
  [b tag f]
  (let [open (:open b)]
    (assoc! b :open (assoc! open tag (f (get open tag 0))))))

(defn- append!
  "The builder `b` with `child` appended to its top element."
  [b child]
  (let [stack (:stack b)
        i     (top b)]
    (assoc! b :stack (assoc! stack i (conj! (nth stack i) child)))))

(defn- push!
  "The builder `b` with an element of `tag` and `attrs` opened on top."
  [b tag attrs]
  (-> b
      (assoc! :stack (conj! (:stack b) (transient [tag attrs])))
      (update-count! tag inc)))

(defn- close-top!
  "The builder `b` with its top element closed into its parent. The text of
  the element is joined here, once, rather than on every token."
  [b]
  (let [stack (:stack b)
        node  (persistent! (nth stack (top b)))]
    (-> b
        (assoc! :stack (pop! stack))
        (update-count! (nth node 0) dec)
        (append! (merge-text node)))))

(defn ^:no-doc open-index
  "The stack index of the nearest open element of the builder `b` whose
  tag is in `tags`, or nil. The search goes down from the top and gives up
  at one of `boundaries`."
  [b tags boundaries]
  (when (open? b tags)
    (loop [i (top b)]
      (when (pos? i)
        (let [tag (tag-at b i)]
          (cond
            (tags tag)       i
            (boundaries tag) nil
            :else            (recur (dec i))))))))

(defn- close-through!
  "The builder `b` with the open elements closed from the top down to the
  nearest one whose tag is in `tags`, that one included. Nothing closes
  when one of `boundaries` comes first or none of `tags` is open."
  [b tags boundaries]
  (if-let [i (open-index b tags boundaries)]
    (loop [b b k (- (count (:stack b)) i)]
      (if (zero? k)
        b
        (recur (close-top! b) (dec k))))
    b))

(defn- open-element!
  "The builder `b` with the element `tag` and its `attrs` opened, or
  appended when it's void, `self-closing?` in SVG or MathML, or past
  max-depth. The elements that its start tag implies an end for are
  closed first."
  [b tag attrs self-closing?]
  (let [b (if (and (block-elements tag) (open-index b #{:p} paragraph-scope))
            (close-through! b #{:p} #{})
            b)
        b (if-let [[tags boundaries] (implied-end tag)]
            (close-through! b tags boundaries)
            b)
        b (if (and (headings tag) (headings (tag-at b (top b))))
            (close-top! b)
            b)]
    (if (or (void-elements tag)
            (and self-closing? (or (foreign-roots tag) (open? b foreign-roots)))
            (> (count (:stack b)) max-depth))
      (append! b [tag attrs])
      (push! b tag attrs))))

(def ^:no-doc nul-text
  "U+0000 NULL as a text, which the tree builder drops from text."
  (str tokenizer/nul))

(defn- step!
  "The builder `b` after the `token` of dk.simongray.html.tokenizer, as a
  few of the rules of 13.2.6.4.7 of the HTML Standard build the tree."
  [b token]
  (let [drop-newline? (:drop-newline? b)
        b             (cond-> b
                        drop-newline? (dissoc! :drop-newline?))]
    (cond
      (string? token)
      (let [text (cond-> token
                   (str/includes? token nul-text) (str/replace nul-text ""))
            text (cond-> text
                   (and drop-newline? (str/starts-with? text "\n")) (subs 1))]
        (cond-> b
          (seq text) (append! text)))

      (= :start-tag (:type token))
      (let [{:keys [name attrs self-closing?]} token
            tag                                (keyword name)]
        (cond-> (open-element! b tag (update-keys attrs keyword) self-closing?)
          (newline-dropping tag) (assoc! :drop-newline? true)))

      ;; an end tag br is a line break
      (= :end-tag (:type token))
      (let [tag (keyword (:name token))]
        (if (= :br tag)
          (open-element! b :br {} false)
          (close-through! b #{tag} #{})))

      :else
      b)))

(defn parse
  "The HTML fragment `s` as a sequence of Hiccup nodes.

  The tokens are those of the HTML Standard, by dk.simongray.html.tokenizer,
  and the tree follows the rules of the standard that embedded HTML needs:

  - unclosed paragraphs, list items, table cells, headings and links
    close where HTML says they do
  - void elements, and the elements of SVG and MathML that close
    themselves with a slash, have no content
  - stray end tags are ignored, and </br> is a line break
  - comments, DOCTYPEs and processing instructions vanish

  Misnested inline elements close through the nearest match, where a
  browser would carry them on. Tags and attribute keys are lower-case
  keywords, an attribute without a value has the empty string, and text
  is a string with its character references decoded.

  Hiccup renderers take a sequence as a fragment, so the result can go
  straight into a parent element."
  [s]
  (let [builder (transient {:stack (transient [(transient [:root {}])])
                            :open  (transient {})})]
    (loop [b (reduce step! builder (tokenizer/tokens s))]
      (if (= 1 (count (:stack b)))
        (apply list (drop 2 (merge-text (persistent! (nth (:stack b) 0)))))
        (recur (close-top! b))))))

(defn ^:no-doc scheme
  "The scheme of the URL `s` in lower case, or nil for a relative URL.

  The URL is read as a browser reads it, so a tab inside javascript:
  doesn't hide the scheme. The tabs and line breaks anywhere in it are
  dropped, and so are the control characters and spaces in front."
  [s]
  (let [s (-> (str s)
              (str/replace #"[\t\n\r]" "")
              (str/replace #"^[\x00-\x20]+" ""))]
    (some-> (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*(?=:)" s) str/lower-case)))

(defn ^:no-doc text-of
  "The value `v` of an attribute when it's text that isn't blank, which a
  bare attribute's empty string, or true in Hiccup, isn't."
  [v]
  (when (and (string? v) (not (blank? v)))
    v))

(def allowed-tags
  "The elements that sanitize keeps."
  #{:p :br :a :strong :b :em :i :u :s :strike :del :ins :mark :small :sup :sub
    :ul :ol :li :dl :dt :dd :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :q :cite :abbr
    :dfn :code :kbd :pre :hr :img :span :div :figure :figcaption
    :table :thead :tbody :tfoot :tr :td :th :caption})

(def dropped-tags
  "The elements that sanitize removes with their content."
  #{:script :style :iframe :object :embed :form :input :button :select :option
    :textarea :noscript :svg :math :template :head :title :meta :link :base
    :frame :frameset :applet :audio :video :canvas})

(def allowed-attributes
  "The attributes that sanitize keeps, by element."
  {:a          #{:href :title :rel :hreflang}
   :img        #{:src :alt :title :width :height}
   :td         #{:colspan :rowspan}
   :th         #{:colspan :rowspan :scope}
   :ol         #{:start :reversed}
   :blockquote #{:cite}
   :q          #{:cite}
   :abbr       #{:title}
   :dfn        #{:title}})

(def ^:no-doc url-attributes
  "The attributes whose value is a URL, checked by scheme."
  #{:href :src :cite})

(def ^:no-doc structural-tags
  "Elements whose text children are layout whitespace, never content."
  #{:ul :ol :dl :table :thead :tbody :tfoot :tr})

(defn- safe-url?
  "Whether the URL `s` is relative, or has a scheme that a client can
  follow, read as a browser reads it."
  [s]
  (contains? #{nil "http" "https" "mailto"} (scheme s)))

(defn ^:no-doc parts
  "The tag, the attributes and the children of the Hiccup element `node`,
  whose attribute map can be left out, e.g. [:p \"x\"]."
  [node]
  (if (map? (get node 1))
    [(node 0) (node 1) (subvec node 2)]
    [(node 0) {} (subvec node 1)]))

(defn- safe-attributes
  "The attributes `attrs` of an element `tag` that the table `allowed`
  has, without a URL that a client can't follow."
  [tag attrs allowed]
  (into {} (for [[k v] attrs
                 :when (contains? (get allowed tag) k)
                 :when (or (not (url-attributes k)) (safe-url? v))]
             [k v])))

(defn sanitize
  "The Hiccup `nodes` that a client may render, by the rules below and
  `opts`. In the result:

  - the elements and attributes of allowed-tags and allowed-attributes
    are kept
  - a URL is kept when it's http, https, mailto or relative
  - the elements of dropped-tags are removed with their content
  - any other element is replaced by its children
  - lists and tables lose the whitespace of their layout

  The options :allowed-tags, :dropped-tags and :allowed-attributes take
  the place of the vars of the same names."
  ([nodes]
   (sanitize nodes {}))
  ([nodes opts]
   {:pre [(map? opts)]}
   (let [allowed    (:allowed-tags opts allowed-tags)
         dropped    (:dropped-tags opts dropped-tags)
         attributes (:allowed-attributes opts allowed-attributes)]
     (letfn [(node [x]
               (cond
                 (string? x)       [x]
                 (not (vector? x)) []
                 :else
                 (let [[tag attrs children] (parts x)]
                   (if (dropped tag)
                     []
                     (let [kids (into []
                                      (comp (mapcat node)
                                            (if (structural-tags tag)
                                              (remove #(and (string? %) (blank? %)))
                                              identity))
                                      children)]
                       (if (allowed tag)
                         [(into [tag (safe-attributes tag attrs attributes)] kids)]
                         kids))))))]
       (apply list (into [] (mapcat node) nodes))))))

(defn- decoded
  "The text `s` with its character references decoded and every other
  character kept, as HTML reads the text of a title: the RCDATA state of
  13.2.5.2 of the HTML Standard."
  [s]
  (apply str (tokenizer/tokens s {:state :rcdata})))

(defn- paragraphs
  "The plain text `s` as paragraphs, with blank lines between them and line
  breaks within."
  [s]
  (let [text (trim-whitespace (decoded s))]
    (apply list (for [paragraph (str/split text #"\n[\t\n\f\r ]*\n")
                      :let  [lines (->> (str/split-lines paragraph)
                                        (map trim-whitespace)
                                        (remove blank?))]
                      :when (seq lines)]
                  (into [:p {}] (interpose [:br {}] lines))))))

(defn hiccup
  "The Hiccup that a client can render for the HTML or plain text `s`, as a
  sequence of nodes to put inside a parent element. HTML is parsed and
  sanitized, and plain text becomes paragraphs with line breaks."
  [s]
  (let [s (str s)]
    (if (re-find markup s)
      (sanitize (parse s))
      (paragraphs s))))

(def ^:no-doc paragraph-tags
  "Elements set off by a blank line in text and Markdown."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :pre :ul :ol :dl :table :hr :figure})

(def ^:no-doc line-tags
  "Elements that end a line in text and Markdown."
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot
    :section :article})

(defn- nodes-of
  "The nodes of `x`, which is a string, a node or nodes. A string is parsed
  when it holds markup, or read as paragraphs when it's plain text."
  [x]
  (cond
    (string? x)             (if (re-find markup x) (parse x) (paragraphs x))
    (and (vector? x)
         (keyword? (first x))) [x]
    :else                   x))

(defn- collapse
  "The text `s` with each run of ASCII whitespace as one space. Most text
  has only single spaces, which indexOf tells without a regular
  expression, and then it stays as it is."
  [s]
  (if (some #(str/includes? s %) ["\n" "  " "\t" "\r" "\f"])
    (str/replace s #"[\t\n\f\r ]+" " ")
    s))

(def ^:no-doc pre-mark
  "The mark that render puts at the start of each line of preformatted
  text, so that tidy leaves its indentation alone and then removes it.
  It's a character of the private use area, which no text holds."
  "\uE000")

(defn- marked-lines
  "The preformatted text `s` with pre-mark at the start of each line, and
  without the line breaks at its end."
  [s]
  (let [end (loop [i (count s)]
              (if (and (pos? i) (contains? #{\newline \return} (nth s (dec i))))
                (recur (dec i))
                i))]
    (str pre-mark (str/replace (subs s 0 end) "\n" (str "\n" pre-mark)))))

(defn- tidy
  "The rendered `s`, trimmed, with each run of blank lines cut to one. Its
  lines are trimmed too, except those of preformatted text."
  [s]
  (let [trim-line #(if (str/starts-with? % pre-mark) % (trim-whitespace %))
        lines     (str/join "\n" (into [] (map trim-line) (str/split-lines s)))
        text      (trim-whitespace (cond-> lines
                                     (str/includes? lines "\n\n\n")
                                     (str/replace #"\n{3,}" "\n\n")))]
    (cond-> text
      (str/includes? text pre-mark) (str/replace pre-mark ""))))

(defn- render
  "The `nodes` rendered as text by the functions `inline` and `element`,
  starting from `context`.

  The `inline` function renders a string, and takes the string and the
  context. The `element` function renders an element, and takes its tag
  and attributes, its rendered children and the context. The context is a
  map:

  - :list is :ul or :ol inside a list
  - :index is a volatile that counts the list items
  - :pre? is true inside a pre

  The elements of dropped-tags render as nothing, as a browser shows no
  script. Every line of a pre starts with pre-mark."
  [nodes inline element context]
  (letfn [(node [x ctx]
            (cond
              (string? x)       (inline x ctx)
              (not (vector? x)) ""
              :else
              (let [[tag attrs children] (parts x)]
                (if (dropped-tags tag)
                  ""
                  (let [ctx   (cond-> ctx
                                (#{:ul :ol} tag) (assoc :list tag :index (volatile! 0))
                                (= :pre tag)      (assoc :pre? true))
                        inner (str/join (into [] (map #(node % ctx)) children))]
                    (element [tag attrs]
                             (if (= :pre tag) (marked-lines inner) inner)
                             ctx))))))]
    (tidy (str/join (into [] (map #(node % context)) nodes)))))

(defn- list-marker
  [{:keys [list index]}]
  (case list
    :ol (str (vswap! index inc) ". ")
    :ul "- "
    ""))

(defn- text-element
  "The text of the element of `tag` and `attrs`, whose children render as
  `inner`, in the context `ctx` of render, with the URL of a link when
  `links?`."
  [links? [tag attrs] inner ctx]
  (let [href   (:href attrs)
        shown? (and links? href (not= (trim-whitespace inner) (str href)))]
    (cond
      (= :br tag)          "\n"
      (= :hr tag)          "\n\n"
      (= :img tag)         (str (:alt attrs))
      (= :a tag)           (if shown? (str inner " (" href ")") inner)
      (= :li tag)          (str (list-marker ctx) (trim-whitespace inner) "\n")
      (paragraph-tags tag) (str "\n\n" inner "\n\n")
      (line-tags tag)      (str inner "\n")
      :else                inner)))

(defn text
  "The HTML text or Hiccup `x` as plain text, with the URLs of links when
  `opts` has :links?. In the text:

  - paragraphs are separated by a blank line
  - list items are on their own lines, with a marker
  - a link is its text, followed by its URL in parentheses when :links?
  - an image is its alt text"
  ([x]
   (text x {}))
  ([x {:keys [links?] :as opts}]
   (render (nodes-of x)
           (fn [s {:keys [pre?]}] (if pre? s (collapse s)))
           (partial text-element links?)
           {})))

(defn- markdown-element
  "The Markdown of the element of `tag` and `attrs`, whose children render
  as `inner`, in the context `ctx` of render."
  [[tag attrs] inner ctx]
  (let [heading (when-let [[_ n] (re-matches #"h([1-6])" (name tag))]
                  (parse-long n))
        quoted  #(->> (str/split-lines %)
                      (map (fn [line] (str "> " line)))
                      (str/join "\n"))]
    (cond
      heading
      (str "\n\n" (apply str (repeat heading "#")) " " (trim-whitespace inner) "\n\n")

      (= :br tag)
      "\\\n"

      (= :hr tag)
      "\n\n---\n\n"

      (= :img tag)
      (str "![" (:alt attrs) "](" (:src attrs) ")")

      (= :a tag)
      (if (:href attrs)
        (str "[" inner "](" (:href attrs) ")")
        inner)

      (#{:strong :b} tag)
      (str "**" inner "**")

      (#{:em :i} tag)
      (str "*" inner "*")

      (#{:s :strike :del} tag)
      (str "~~" inner "~~")

      (= :code tag)
      (if (:pre? ctx) inner (str "`" inner "`"))

      (= :pre tag)
      (str "\n\n```\n" (trim-whitespace inner) "\n```\n\n")

      (= :blockquote tag)
      (str "\n\n" (quoted (trim-whitespace inner)) "\n\n")

      (= :li tag)
      (str (list-marker ctx) (trim-whitespace inner) "\n")

      (#{:td :th} tag)
      (str (trim-whitespace inner) " | ")

      (= :tr tag)
      (str "| " inner "\n")

      (paragraph-tags tag)
      (str "\n\n" inner "\n\n")

      (line-tags tag)
      (str inner "\n")

      :else
      inner)))

(defn markdown
  "The HTML text or Hiccup `x` as Markdown. Text isn't escaped, so a
  literal asterisk stays an asterisk. In the Markdown:

  - headings, emphasis, links, images, lists, quotes, code and rules take
    their Markdown forms
  - a line break becomes a backslash at the end of the line
  - everything else is its text"
  [x]
  (render (nodes-of x)
          (fn [s {:keys [pre?]}] (if pre? s (collapse s)))
          markdown-element
          {}))

(def ^:no-doc text-escapes
  "The characters that text in HTML escapes, by character."
  {\& "&amp;" \< "&lt;" \> "&gt;"})

(def ^:no-doc value-escapes
  "The characters that an attribute value in double quotes escapes, by
  character."
  (assoc text-escapes \" "&quot;"))

(defn- attributes-html
  [attrs]
  (str/join (for [[k v] attrs]
              (if (true? v)
                (str " " (name k))
                (str " " (name k) "=\"" (str/escape (str v) value-escapes) "\"")))))

(defn emit
  "The Hiccup `nodes` as an HTML string, with the text escaped and the void
  elements without end tags."
  [nodes]
  (letfn [(node [x]
            (cond
              (string? x)       (str/escape x text-escapes)
              (not (vector? x)) ""
              :else
              (let [[tag attrs children] (parts x)
                    name  (name tag)
                    start (str "<" name (attributes-html attrs) ">")]
                (if (void-elements tag)
                  start
                  (str start (str/join (map node children)) "</" name ">")))))]
    (str/join (map node (nodes-of nodes)))))

#?(:clj
   (comment
     (parse "<p>Hello <b>world</b><br>Line two<ul><li>one<li>two</ul>")
     (hiccup "Plain text\n\nwith two paragraphs\nand a break")
     (text (str "<p>Hello <a href=\"https://x\">link</a></p>"
                "<ul><li>one</li><li>two</li></ul>")
           {:links? true})
     (markdown "<h2>Notes</h2><p>Hello <b>world</b></p>")
     #_.))
