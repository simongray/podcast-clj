(ns dk.simongray.podcast.format.html
  "The HTML that feeds embed in descriptions and show notes, read into
  Hiccup and rendered from there: `parse` reads a fragment into a sequence
  of nodes, `[tag attrs & children]` with keyword tags and attribute keys
  and strings for text; `sanitize` keeps what a client may render; `hiccup`
  does both and turns a plain-text description into paragraphs; `text` and
  `markdown` render either shape; `emit` writes Hiccup back as HTML."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.xml :as xml]))

(def ^:no-doc token-pattern
  "One token of HTML at a time: a script or style element whole, its text
  running to its end tag, or to the end when it has none, as a browser
  reads it, however much of it looks like markup; a comment, a CDATA
  section, a doctype or other declaration, a processing instruction, an
  end tag, a start tag, whose quoted values may hold a `>`, a run of text,
  or a stray angle bracket. The start tag is a run of plain characters and
  then quoted values each followed by another, which a regular expression
  matches in one pass, where a choice for every character costs a third
  of the time of the whole parse."
  #"(?s)<[sS][cC][rR][iI][pP][tT](?=[\s/>])[^>]*>.*?(?:</[sS][cC][rR][iI][pP][tT]\s*>|$)|<[sS][tT][yY][lL][eE](?=[\s/>])[^>]*>.*?(?:</[sS][tT][yY][lL][eE]\s*>|$)|<!--.*?(?:-->|$)|<!\[CDATA\[.*?(?:\]\]>|$)|<![^>]*>?|<\?[^>]*>?|</[a-zA-Z][^>]*>?|<[a-zA-Z][^>\"']*(?:(?:\"[^\"]*\"|'[^']*'|[\"'])[^>\"']*)*>?|[^<]+|<")

(def ^:no-doc markup
  "What makes a description HTML rather than plain text: a comment, or a
  tag whole, `<p>`, `<a href=\"…\">` or `</b>`. Plain text may hold
  `<show@example.com>` or `<3`, and neither is a tag."
  #"<!--|<[a-zA-Z][a-zA-Z0-9]*(?:\s[^<>]*)?/?>|</[a-zA-Z][a-zA-Z0-9]*\s*>")

(def ^:no-doc attribute-pattern
  "An attribute: a name, and perhaps a value in double quotes, single quotes
  or none."
  #"([^\s\"'=<>/]+)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'=<>`]+)))?")

(def ^:no-doc void-elements
  "Elements that never have content or an end tag."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "param"
    "source" "track" "wbr"})

(def ^:no-doc block-elements
  "Elements whose start tag closes an open paragraph, as HTML defines."
  #{"address" "article" "aside" "blockquote" "details" "div" "dl" "fieldset"
    "figcaption" "figure" "footer" "form" "h1" "h2" "h3" "h4" "h5" "h6"
    "header" "hgroup" "hr" "main" "menu" "nav" "ol" "p" "pre" "section"
    "table" "ul"})

(def ^:no-doc implied-end
  "Elements whose start tag closes an open element of the listed kinds, and
  the ancestors that bound the search: a new li closes an open li, but not
  one outside the list it belongs to."
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
  "How deep `parse` nests elements, as deep as WebKit and Chromium nest them:
  an element opened deeper still is kept, empty, and its content goes to
  the element it stands in, so that no walk of the tree recurses without
  bound."
  512)

(defn- tag-name
  [node]
  (name (first node)))

(defn- parse-attributes
  "The attributes in the start-tag remainder `s`, keys lower-cased, values
  with their entities decoded, a bare attribute `true`."
  [s]
  (into {} (for [[_ k dq sq bare] (re-seq attribute-pattern s)
                 :let [v (or dq sq bare)]]
             [(keyword (str/lower-case k))
              (if (nil? v) true (xml/decode-entities v))])))

(defn- start-tag
  "`[name attrs self-closing?]` of the start tag `token`. A slash before
  the `>` closes the element when white space, a quote or the name comes
  before it, not at the end of a value without quotes, as in
  `href=https://x/`."
  [token]
  (let [[_ name rest] (re-matches #"(?s)^<([a-zA-Z][\w:.-]*)(.*?)>?$" token)
        rest          (or rest "")
        self-closing? (boolean (re-find #"(?:^|[\s\"'])/$" rest))]
    [(str/lower-case name)
     (parse-attributes (if self-closing? (subs rest 0 (dec (count rest))) rest))
     self-closing?]))

(defn- raw-element
  "The script or style element of the token `token`, its text as it is."
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
  "Append `child` to the element on top of the parse `state`."
  [state child]
  (update state :stack #(conj (pop %) (conj (peek %) child))))

(defn- push
  "Open the element `node` on top of the parse `state`."
  [state node]
  (-> state
      (update :stack conj node)
      (update-in [:open (tag-name node)] (fnil inc 0))))

(defn- close-top
  "Close the element on top of the parse `state` into its parent, its text
  joined once rather than on every token."
  [{:keys [stack] :as state}]
  (let [top (peek stack)]
    (-> state
        (assoc :stack (pop stack))
        (update-in [:open (tag-name top)] dec)
        (append (merge-text top)))))

(defn ^:no-doc open-index
  "The index in the stack of the parse `state` of the nearest open element
  whose tag is in `names`, searching from the top and giving up at one of
  `boundaries`; nil when there is none."
  [{:keys [stack open]} names boundaries]
  (when (some #(pos? (get open % 0)) names)
    (loop [i (dec (count stack))]
      (when (pos? i)
        (let [tag (tag-name (nth stack i))]
          (cond
            (names tag)      i
            (boundaries tag) nil
            :else            (recur (dec i))))))))

(defn- close-through
  "Close open elements from the top down to and including the nearest one
  whose tag is in `names`, closing nothing when one of `boundaries` is met
  first or none of `names` is open."
  [state names boundaries]
  (if-let [i (open-index state names boundaries)]
    (nth (iterate close-top state) (- (count (:stack state)) i))
    state))

(defn- open-paragraph?
  "Whether the nearest open block-level element of the parse `state` is a p."
  [state]
  (some? (open-index state #{"p"} paragraph-scope)))

(defn- open-element
  "Push or append the element `name` with `attrs`, closing what its start
  tag implies; past `max-depth`, append it."
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
  "Read the HTML fragment `s` into Hiccup the way a browser would, as a
  sequence of nodes: elements `[tag attrs & children]` with lower-case
  keyword tags and keyword attribute keys, and strings with their entities
  decoded. Comments and declarations vanish, unknown tags are kept, unclosed
  elements close where HTML says they do, and stray end tags are ignored.
  Hiccup renderers take a sequence as a fragment, so the result goes
  straight into a parent element: `[:div (parse s)]`."
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

(def allowed-tags
  "The elements `sanitize` keeps."
  #{:p :br :a :strong :b :em :i :u :s :strike :del :ins :mark :small :sup :sub
    :ul :ol :li :dl :dt :dd :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :q :cite :abbr
    :dfn :code :kbd :pre :hr :img :span :div :figure :figcaption
    :table :thead :tbody :tfoot :tr :td :th :caption})

(def dropped-tags
  "The elements `sanitize` removes with their content."
  #{:script :style :iframe :object :embed :form :input :button :select :option
    :textarea :noscript :svg :math :template :head :title :meta :link :base
    :frame :frameset :applet :audio :video :canvas})

(def allowed-attributes
  "The attributes `sanitize` keeps, by element."
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
  "Whether the URL `s` is relative or uses a scheme a client may follow,
  judged as a browser reads it: without the tabs and line breaks it drops
  anywhere, and the control characters and spaces it drops in front, so
  that `java&#9;script:` is `javascript:` here too."
  [s]
  (let [s (-> (str s)
              (str/replace #"[\t\n\r]" "")
              (str/replace #"^[\x00-\x20]+" ""))]
    (or (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s))
        (boolean (re-find #"(?i)^(?:https?|mailto):" s)))))

(defn ^:no-doc parts
  "The tag, the attributes and the children of the Hiccup element `node`,
  whose attribute map may be left out, as in `[:p \"x\"]`."
  [[tag & more]]
  (if (map? (first more))
    [tag (first more) (rest more)]
    [tag {} more]))

(defn- safe-attributes
  [tag attrs]
  (into {} (for [[k v] attrs
                 :when (contains? (allowed-attributes tag) k)
                 :when (or (not (url-attributes k)) (safe-url? v))]
             [k v])))

(defn sanitize
  "The Hiccup `nodes` a client may render: the elements and attributes of
  `allowed-tags` and `allowed-attributes` kept, URLs limited to http, https,
  mailto and relative ones, `dropped-tags` removed with their content, any
  other element replaced by its children, and layout whitespace removed
  from lists and tables."
  [nodes]
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
                  (dropped-tags tag) []
                  (allowed-tags tag) [(into [tag (safe-attributes tag attrs)] kids)]
                  :else              kids))))]
    (apply list (mapcat node nodes))))

(defn- paragraphs
  "The plain text `s` as paragraphs, blank lines between them and line
  breaks within."
  [s]
  (apply list (for [paragraph (str/split (str/trim (xml/decode-entities s)) #"\n\s*\n")
                    :let [lines (remove str/blank? (map str/trim (str/split-lines paragraph)))]
                    :when (seq lines)]
                (into [:p {}] (interpose [:br {}] lines)))))

(defn hiccup
  "The Hiccup a client can render for the description `s`, as a sequence of
  nodes to put inside a parent element, `[:div (hiccup s)]`: parsed and
  sanitized when it holds `markup`, paragraphs with line breaks when it is
  plain text."
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
  "`x` as a sequence of nodes: a string parsed when it holds `markup` and
  read as paragraphs when it is plain text, a node wrapped."
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
  "`s` without the spaces and tabs at its ends, found without a regular
  expression: `[ \\t]+$` is tried again from every blank of a long run."
  [s]
  (let [blank? #(contains? #{\space \tab} (nth s %))
        end    (loop [i (count s)] (if (and (pos? i) (blank? (dec i))) (recur (dec i)) i))
        start  (loop [i 0] (if (and (< i end) (blank? i)) (recur (inc i)) i))]
    (subs s start end)))

(def ^:no-doc pre-mark
  "What `render` puts at the start of every line of preformatted text, a
  character of the private use area, which no text holds, so that `tidy`
  leaves the indentation of those lines alone, and then removes it."
  "\uE000")

(defn- marked-lines
  "The preformatted text `s` with `pre-mark` at the start of each of its
  lines, the line breaks at its end dropped."
  [s]
  (let [end (loop [i (count s)]
              (if (and (pos? i) (contains? #{\newline \return} (nth s (dec i))))
                (recur (dec i))
                i))]
    (str pre-mark (str/replace (subs s 0 end) "\n" (str "\n" pre-mark)))))

(defn- tidy
  "Trim the rendered `s` and its lines, but for the lines of preformatted
  text, and reduce its blank lines to one."
  [s]
  (-> (str/join "\n" (map #(if (str/starts-with? % pre-mark) % (trim-blanks %)) (str/split-lines s)))
      (str/replace #"\n{3,}" "\n\n")
      str/trim
      (str/replace pre-mark "")))

(defn- render
  "Render `nodes` with `inline`, a function of a text string and the
  context, and `element`, a function of the tag and the attributes of a
  node, its rendered children and the context, which is a map with
  `:list` (:ul or :ol), `:index` (an atom counting list items) and
  `:pre?`. The `dropped-tags` render as nothing, as a browser shows no
  script, and every line of a pre starts with `pre-mark`."
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
  "`x`, HTML text or Hiccup, as plain text: paragraphs separated by a blank
  line, list items on their own lines with a marker, links as their text
  or, with `{:links? true}` in `opts`, followed by their URL in parentheses,
  images as their alt text."
  ([x]
   (text x {}))
  ([x {:keys [links?]}]
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
  "`x`, HTML text or Hiccup, as Markdown: headings, emphasis, links, images,
  lists, quotes, code and rules in their Markdown forms, a line break as a
  backslash break, everything else as its text. Text is not escaped, so a
  literal asterisk stays an asterisk."
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
  "The Hiccup `nodes` as an HTML string: text escaped, void elements
  without end tags."
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
