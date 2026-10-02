(ns dk.simongray.podcast.format.html
  "The HTML that feeds embed in descriptions and show notes, read into
  Hiccup and rendered from there.

  A parse gives a sequence of nodes. An element is a vector of a keyword
  tag, a map of keyword attributes and its children, and text is a string.
  For a description that a client can render, use the hiccup function. It
  parses and sanitizes HTML, and makes paragraphs of plain text. The text
  and markdown functions render HTML or Hiccup as text, and emit writes
  Hiccup back as HTML."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.xml :as xml]))

(def ^:no-doc token-pattern
  "One token of HTML at a time, which is one of:

  - a whole script or style element
  - a comment, a CDATA section, a declaration or a processing instruction
  - an end tag
  - a start tag, whose quoted values may hold a closing angle bracket
  - a run of text, or a stray angle bracket

  As in a browser, a script or style element runs to its end tag or the
  end of the HTML, even where its text looks like markup.

  A start tag is a run of plain characters, then quoted values that are
  each followed by another such run. A regular expression matches it in
  one pass, where a choice at every character would cost a third of the
  time of the whole parse.

  On the JVM, the run of quoted values is possessive. A run that can give
  back what it took costs a stack frame for each value there, and a tag
  with thousands of values overflows the stack."
  (re-pattern
   (str "(?s)<[sS][cC][rR][iI][pP][tT](?=[\\s/>])[^>]*>.*?(?:</[sS][cC][rR][iI][pP][tT]\\s*>|$)"
        "|<[sS][tT][yY][lL][eE](?=[\\s/>])[^>]*>.*?(?:</[sS][tT][yY][lL][eE]\\s*>|$)"
        "|<!--.*?(?:-->|$)|<!\\[CDATA\\[.*?(?:\\]\\]>|$)|<![^>]*>?|<\\?[^>]*>?|</[a-zA-Z][^>]*>?"
        "|<[a-zA-Z][^>\"']*(?:(?:\"[^\"]*\"|'[^']*'|[\"'])[^>\"']*)*" #?(:clj "+" :cljs "")
        ">?|[^<]+|<")))

(def ^:no-doc markup
  "What makes a description HTML rather than plain text: a comment or a
  whole tag. Plain text may hold an email address in angle brackets, or a
  less-than sign before a 3, and neither is a tag."
  #"<!--|<[a-zA-Z][a-zA-Z0-9]*(?:\s[^<>]*)?/?>|</[a-zA-Z][a-zA-Z0-9]*\s*>")

(def ^:no-doc attribute-pattern
  "An attribute: a name, and perhaps a value in double quotes, single quotes
  or none."
  #"([^\s\"'=<>/]+)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'=<>`]+)))?")

;; HTML Standard, "Elements": the void elements, and param of older pages
(def ^:no-doc void-elements
  "Elements that never have content or an end tag."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "param"
    "source" "track" "wbr"})

;; HTML Standard, "Optional tags", as of 2026-09-29
(def ^:no-doc block-elements
  "Elements whose start tag closes an open paragraph, as HTML defines."
  #{"address" "article" "aside" "blockquote" "details" "dialog" "div" "dl"
    "fieldset" "figcaption" "figure" "footer" "form" "h1" "h2" "h3" "h4" "h5"
    "h6" "header" "hgroup" "hr" "main" "menu" "nav" "ol" "p" "pre" "search"
    "section" "table" "ul"})

;; HTML Standard, "Optional tags"
(def ^:no-doc implied-end
  "For each tag, the open elements that its start tag closes, and the
  ancestors where the search for them stops. A new li closes an open li,
  but only inside the same list."
  {"li"     [#{"li"} #{"ul" "ol" "menu"}]
   "dt"     [#{"dt" "dd"} #{"dl"}]
   "dd"     [#{"dt" "dd"} #{"dl"}]
   "tr"     [#{"tr" "td" "th"} #{"table"}]
   "td"     [#{"td" "th"} #{"tr" "table"}]
   "th"     [#{"td" "th"} #{"tr" "table"}]
   "option" [#{"option"} #{"select"}]})

(def ^:no-doc paragraph-scope
  "The elements that bound the search for an open p: block-level ones, list
  items and table cells."
  (into block-elements #{"li" "td" "th" "dt" "dd" "body"}))

(def max-depth
  "How deep the parse function nests elements, as deep as WebKit and
  Chromium do. An element opened deeper is kept empty, and its content goes
  to its parent, so no walk of the tree recurses without bound."
  512)

(defn- tag-name
  [node]
  (name (first node)))

(defn- parse-attributes
  "The attributes in `s`, the rest of a start tag, with lower-case keys and
  decoded entities. A bare attribute has the value true."
  [s]
  (into {} (for [[_ k dq sq bare] (re-seq attribute-pattern s)
                 :let [v (or dq sq bare)]]
             [(keyword (str/lower-case k))
              (if (nil? v) true (xml/decode-entities v))])))

(defn- start-tag
  "The name, the attributes and the self-closing flag of the start tag
  `token`, as a vector.

  A slash at the end closes the element when a space, a quote or the name
  comes before it. The slash that ends a value without quotes doesn't, as
  in href=https://x/."
  [token]
  (let [[_ name rest] (re-matches #"(?s)^<([a-zA-Z][\w:.-]*)(.*?)>?$" token)
        rest          (or rest "")
        self-closing? (boolean (re-find #"(?:^|[\s\"'])/$" rest))]
    [(str/lower-case name)
     (parse-attributes (if self-closing? (subs rest 0 (dec (count rest))) rest))
     self-closing?]))

(defn- raw-element
  "The script or style element of `token`, with its text as it is."
  [token]
  (let [[_ name rest text] (re-matches #"(?is)^<(script|style)([^>]*)>(.*?)(?:</\1\s*>)?$" token)]
    (cond-> [(keyword (str/lower-case name)) (parse-attributes (str/replace rest #"/$" ""))]
      (seq text) (conj text))))

(defn- end-tag
  [token]
  (str/lower-case (second (re-find #"</([a-zA-Z][\w:.-]*)" token))))

;; The parse state is `{:stack [...] :open {...}}`: the open elements, the
;; root first, and how many are open by tag, so that an end tag nothing
;; matches costs nothing to ignore.

(defn ^:no-doc merge-text
  "The element `node` with each run of adjacent strings among its children
  joined into one."
  [node]
  (into [] (comp (partition-by string?) (mapcat #(if (string? (first %)) [(apply str %)] %))) node))

(defn- append
  "The parse `state` with `child` appended to its top element."
  [state child]
  (update state :stack #(conj (pop %) (conj (peek %) child))))

(defn- push
  "The parse `state` with the element `node` opened on top."
  [state node]
  (-> state
      (update :stack conj node)
      (update-in [:open (tag-name node)] (fnil inc 0))))

(defn- close-top
  "The parse `state` with its top element closed into its parent. The text
  of the element is joined here, once, rather than on every token."
  [{:keys [stack] :as state}]
  (let [top (peek stack)]
    (-> state
        (assoc :stack (pop stack))
        (update-in [:open (tag-name top)] dec)
        (append (merge-text top)))))

(defn ^:no-doc open-index
  "The stack index of the nearest open element of the parse `state` whose
  tag is in `names`, or nil. The search goes down from the top and gives
  up at one of `boundaries`."
  [{:keys [stack open] :as state} names boundaries]
  (when (some #(pos? (get open % 0)) names)
    (loop [i (dec (count stack))]
      (when (pos? i)
        (let [tag (tag-name (nth stack i))]
          (cond
            (names tag)      i
            (boundaries tag) nil
            :else            (recur (dec i))))))))

(defn- close-through
  "The parse `state` with the open elements closed from the top down to the
  nearest one whose tag is in `names`, that one included. Nothing closes
  when one of `boundaries` comes first or none of `names` is open."
  [state names boundaries]
  (if-let [i (open-index state names boundaries)]
    (nth (iterate close-top state) (- (count (:stack state)) i))
    state))

(defn- open-paragraph?
  "Whether the nearest open block-level element of the parse `state` is a p."
  [state]
  (some? (open-index state #{"p"} paragraph-scope)))

(defn- open-element
  "The parse `state` with the element `name` and its `attrs` opened, or
  appended when it's void, `self-closing?` or past max-depth. The elements
  that its start tag implies an end for are closed first."
  [state name attrs self-closing?]
  (let [state (if (and (block-elements name) (open-paragraph? state))
                (close-through state #{"p"} #{})
                state)
        state (if-let [[names boundaries] (implied-end name)]
                (close-through state names boundaries)
                state)
        node  [(keyword name) attrs]]
    (if (or (void-elements name) self-closing? (> (count (:stack state)) max-depth))
      (append state node)
      (push state node))))

(defn- token-kind
  [token]
  (cond
    (str/starts-with? token "<!--")                      :comment
    (str/starts-with? token "<![CDATA[")                 :cdata
    (str/starts-with? token "<!")                        :declaration
    (str/starts-with? token "<?")                        :instruction
    (re-find #"^</[a-zA-Z]" token)                       :end
    (re-find #"(?i)^<(?:script|style)(?=[\s/>])" token)  :raw
    (re-find #"^<[a-zA-Z]" token)                        :start
    :else                                                :text))

(defn parse
  "The HTML fragment `s` as a sequence of Hiccup nodes, read the way a
  browser reads it.

  Tags and attribute keys are lower-case keywords, and text is a string
  with its entities decoded. In the parse:

  - comments and declarations vanish
  - unknown tags are kept
  - unclosed elements close where HTML says they do
  - stray end tags are ignored

  Hiccup renderers take a sequence as a fragment, so the result can go
  straight into a parent element."
  [s]
  (loop [tokens (re-seq token-pattern (str s))
         state  {:stack [[:root {}]] :open {}}]
    (if-let [token (first tokens)]
      (let [tokens (rest tokens)]
        (case (token-kind token)
          :text  (recur tokens (append state (xml/decode-entities token)))
          :cdata (recur tokens (append state (str/replace token #"^<!\[CDATA\[|\]\]>$" "")))
          :raw   (recur tokens (append state (raw-element token)))
          :start (let [[name attrs self-closing?] (start-tag token)]
                   (recur tokens (open-element state name attrs self-closing?)))
          :end   (recur tokens (close-through state #{(end-tag token)} #{}))
          (recur tokens state)))
      (loop [state state]
        (if (= 1 (count (:stack state)))
          (apply list (drop 2 (merge-text (peek (:stack state)))))
          (recur (close-top state)))))))

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
  "The value `v` of an attribute when it's text that isn't blank. A bare
  attribute has the value true, which isn't text."
  [v]
  (when (and (string? v) (not (str/blank? v)))
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
  [[tag & more :as node]]
  (if (map? (first more))
    [tag (first more) (rest more)]
    [tag {} more]))

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
                 (let [[tag attrs children] (parts x)
                       kids (mapcat node children)
                       kids (if (structural-tags tag)
                              (remove #(and (string? %) (str/blank? %)) kids)
                              kids)]
                   (cond
                     (dropped tag) []
                     (allowed tag) [(into [tag (safe-attributes tag attrs attributes)] kids)]
                     :else         kids))))]
       (apply list (mapcat node nodes))))))

(defn- paragraphs
  "The plain text `s` as paragraphs, with blank lines between them and line
  breaks within."
  [s]
  (apply list (for [paragraph (str/split (str/trim (xml/decode-entities s)) #"\n\s*\n")
                    :let [lines (remove str/blank? (map str/trim (str/split-lines paragraph)))]
                    :when (seq lines)]
                (into [:p {}] (interpose [:br {}] lines)))))

(defn hiccup
  "The Hiccup that a client can render for the description `s`, as a
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
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot :section :article})

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
  [s]
  (str/replace s #"\s+" " "))

(defn- trim-blanks
  "The string `s` without the spaces and tabs at its ends, found without a
  regular expression, which would try again from every blank of a long
  run."
  [s]
  (let [blank? #(contains? #{\space \tab} (nth s %))
        end    (loop [i (count s)] (if (and (pos? i) (blank? (dec i))) (recur (dec i)) i))
        start  (loop [i 0] (if (and (< i end) (blank? i)) (recur (inc i)) i))]
    (subs s start end)))

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
  (-> (str/join "\n" (map #(if (str/starts-with? % pre-mark) % (trim-blanks %)) (str/split-lines s)))
      (str/replace #"\n{3,}" "\n\n")
      str/trim
      (str/replace pre-mark "")))

(defn- render
  "The `nodes` rendered as text by the functions `inline` and `element`,
  starting from `context`.

  The `inline` function renders a string, and takes the string and the
  context. The `element` function renders an element, and takes its tag
  and attributes, its rendered children and the context. The context is a
  map:

  - :list is :ul or :ol inside a list
  - :index is an atom that counts the list items
  - :pre? is true inside a pre

  The elements of dropped-tags render as nothing, as a browser shows no
  script. Every line of a pre starts with pre-mark."
  [nodes inline element context]
  (letfn [(node [x ctx]
            (cond
              (string? x)       (inline x ctx)
              (not (vector? x)) ""
              :else
              (let [[tag attrs children] (parts x)
                    ctx (cond-> ctx
                          (#{:ul :ol} tag) (assoc :list tag :index (atom 0))
                          (= :pre tag)      (assoc :pre? true))
                    inner (str/join (map #(node % ctx) children))]
                (cond
                  (dropped-tags tag) ""
                  (= :pre tag)       (element [tag attrs] (marked-lines inner) ctx)
                  :else              (element [tag attrs] inner ctx)))))]
    (tidy (str/join (map #(node % context) nodes)))))

(defn- list-marker
  [{:keys [list index]}]
  (case list
    :ol (str (swap! index inc) ". ")
    :ul "- "
    ""))

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
           (fn [[tag attrs] inner ctx]
             (cond
               (= :br tag)          "\n"
               (= :hr tag)          "\n\n"
               (= :img tag)         (str (:alt attrs))
               (= :a tag)           (if (and links? (:href attrs) (not= (str/trim inner) (str (:href attrs))))
                                      (str inner " (" (:href attrs) ")")
                                      inner)
               (= :li tag)          (str (list-marker ctx) (str/trim inner) "\n")
               (paragraph-tags tag) (str "\n\n" inner "\n\n")
               (line-tags tag)      (str inner "\n")
               :else                inner))
           {})))

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
          (fn [[tag attrs] inner ctx]
            (let [heading (when-let [[_ n] (re-matches #"h([1-6])" (name tag))] (parse-long n))]
              (cond
                heading              (str "\n\n" (apply str (repeat heading "#")) " " (str/trim inner) "\n\n")
                (= :br tag)          "\\\n"
                (= :hr tag)          "\n\n---\n\n"
                (= :img tag)         (str "![" (:alt attrs) "](" (:src attrs) ")")
                (= :a tag)           (if (:href attrs) (str "[" inner "](" (:href attrs) ")") inner)
                (#{:strong :b} tag)  (str "**" inner "**")
                (#{:em :i} tag)      (str "*" inner "*")
                (#{:s :strike :del} tag) (str "~~" inner "~~")
                (= :code tag)        (if (:pre? ctx) inner (str "`" inner "`"))
                (= :pre tag)         (str "\n\n```\n" (str/trim inner) "\n```\n\n")
                (= :blockquote tag)  (str "\n\n" (str/join "\n" (map #(str "> " %) (str/split-lines (str/trim inner)))) "\n\n")
                (= :li tag)          (str (list-marker ctx) (str/trim inner) "\n")
                (#{:td :th} tag)     (str (str/trim inner) " | ")
                (= :tr tag)          (str "| " inner "\n")
                (paragraph-tags tag) (str "\n\n" inner "\n\n")
                (line-tags tag)      (str inner "\n")
                :else                inner)))
          {}))

(defn- escape
  [s]
  (-> s (str/replace "&" "&amp;") (str/replace "<" "&lt;") (str/replace ">" "&gt;")))

(defn- attributes-html
  [attrs]
  (str/join (for [[k v] attrs]
              (if (true? v)
                (str " " (name k))
                (str " " (name k) "=\"" (str/replace (escape (str v)) "\"" "&quot;") "\"")))))

(defn emit
  "The Hiccup `nodes` as an HTML string, with the text escaped and the void
  elements without end tags."
  [nodes]
  (letfn [(node [x]
            (cond
              (string? x)       (escape x)
              (not (vector? x)) ""
              :else
              (let [[tag attrs children] (parts x)
                    name (name tag)]
                (if (void-elements name)
                  (str "<" name (attributes-html attrs) ">")
                  (str "<" name (attributes-html attrs) ">" (str/join (map node children)) "</" name ">")))))]
    (str/join (map node (nodes-of nodes)))))

#?(:clj
   (comment
     (parse "<p>Hello <b>world</b><br>Line two<ul><li>one<li>two</ul>")
     (hiccup "Plain text\n\nwith two paragraphs\nand a break")
     (text "<p>Hello <a href=\"https://x\">link</a></p><ul><li>one</li><li>two</li></ul>" {:links? true})
     (markdown "<h2>Notes</h2><p>Hello <b>world</b></p>")
     #_.))
