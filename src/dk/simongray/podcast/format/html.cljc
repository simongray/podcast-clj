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
  "One token of HTML at a time: a comment, a CDATA section, a doctype or
  other declaration, a processing instruction, an end tag, a start tag, a
  run of text, or a stray angle bracket."
  #"(?s)<!--.*?(?:-->|$)|<!\[CDATA\[.*?(?:\]\]>|$)|<![^>]*>?|<\?[^>]*>?|</[a-zA-Z][^>]*>?|<[a-zA-Z][^>]*>?|[^<]+|<")

(def ^:no-doc attribute-pattern
  "An attribute: a name, and perhaps a value in double quotes, single quotes
  or none."
  #"([^\s\"'=<>/]+)(?:\s*=\s*(?:\"([^\"]*)\"|'([^']*)'|([^\s\"'=<>`]+)))?")

(def ^:no-doc void-elements
  "Elements that never have content or an end tag."
  #{"area" "base" "br" "col" "embed" "hr" "img" "input" "link" "meta" "param"
    "source" "track" "wbr"})

(def ^:no-doc raw-text-elements
  "Elements whose content is text however much it looks like markup."
  #{"script" "style"})

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
  "`[name attrs self-closing?]` of the start tag `token`."
  [token]
  (let [[_ name rest] (re-matches #"(?s)^<([a-zA-Z][\w:.-]*)(.*?)/?>?$" token)]
    [(str/lower-case name)
     (parse-attributes (str/replace (or rest "") #"/\s*$" ""))
     (boolean (re-find #"/\s*>?$" token))]))

(defn- end-tag
  [token]
  (str/lower-case (second (re-find #"</([a-zA-Z][\w:.-]*)" token))))

(defn- append
  "Append `child` to the element on top of `stack`, merging text with a
  preceding text node."
  [stack child]
  (let [top (peek stack)
        top (if (and (string? child) (string? (peek top)) (> (count top) 2))
              (conj (pop top) (str (peek top) child))
              (conj top child))]
    (conj (pop stack) top)))

(defn- close-top
  "Pop the element on top of `stack` into its parent."
  [stack]
  (let [top (peek stack)]
    (append (pop stack) top)))

(defn- close-through
  "Close open elements from the top down to and including the nearest one
  whose tag is in `names`, stopping without closing anything when one of
  `boundaries` is met first or none of `names` is open."
  [stack names boundaries]
  (let [open (map tag-name (rest stack))
        hit  (->> (reverse open) (take-while #(not (boundaries %))) (some names))]
    (if-not hit
      stack
      (loop [stack stack]
        (let [top (peek stack)
              stack (close-top stack)]
          (if (names (tag-name top)) stack (recur stack)))))))

(defn- open-paragraph?
  "Whether the nearest open block-level element of `stack` is a p."
  [stack]
  (= "p" (some #(when (or (block-elements %) (#{"li" "td" "th" "dt" "dd" "body"} %)) %)
               (reverse (map tag-name (rest stack))))))

(defn- open-element
  "Push or append the element `name` with `attrs`, closing what its start
  tag implies."
  [stack name attrs self-closing?]
  (let [stack (if (and (block-elements name) (open-paragraph? stack))
                (close-through stack #{"p"} #{})
                stack)
        stack (if-let [[names boundaries] (implied-end name)]
                (close-through stack names boundaries)
                stack)
        node  [(keyword name) attrs]]
    (if (or (void-elements name) self-closing?)
      (append stack node)
      (conj stack node))))

(defn- token-kind
  [token]
  (cond
    (str/starts-with? token "<!--")       :comment
    (str/starts-with? token "<![CDATA[")  :cdata
    (str/starts-with? token "<!")         :declaration
    (str/starts-with? token "<?")         :instruction
    (re-find #"^</[a-zA-Z]" token)        :end
    (re-find #"^<[a-zA-Z]" token)         :start
    :else                                 :text))

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
         stack  [[:root {}]]]
    (if-let [token (first tokens)]
      (let [tokens (rest tokens)
            raw    (raw-text-elements (tag-name (peek stack)))]
        (cond
          (and raw (not (and (= :end (token-kind token)) (= raw (end-tag token)))))
          (recur tokens (append stack token))

          :else
          (case (token-kind token)
            :text        (recur tokens (append stack (xml/decode-entities token)))
            :cdata       (recur tokens (append stack (str/replace token #"^<!\[CDATA\[|\]\]>$" "")))
            :start       (let [[name attrs self-closing?] (start-tag token)]
                           (recur tokens (open-element stack name attrs self-closing?)))
            :end         (recur tokens (close-through stack #{(end-tag token)} #{}))
            (recur tokens stack))))
      (loop [stack stack]
        (if (= 1 (count stack))
          (apply list (drop 2 (peek stack)))
          (recur (close-top stack)))))))

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
  "Whether the URL `s` is relative or uses a scheme a client may follow."
  [s]
  (let [s (str/trim (str s))]
    (or (not (re-find #"^[a-zA-Z][a-zA-Z0-9+.-]*:" s))
        (boolean (re-find #"(?i)^(?:https?|mailto):" s)))))

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
              (let [[tag attrs & children] x
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
  sanitized when it holds markup, paragraphs with line breaks when it is
  plain text."
  [s]
  (let [s (str s)]
    (if (re-find #"<[a-zA-Z!/]" s)
      (sanitize (parse s))
      (paragraphs s))))

(def ^:no-doc paragraph-tags
  "Elements set off by a blank line in text and Markdown."
  #{:p :h1 :h2 :h3 :h4 :h5 :h6 :blockquote :pre :ul :ol :dl :table :hr :figure})

(def ^:no-doc line-tags
  "Elements that end a line in text and Markdown."
  #{:div :li :tr :dt :dd :figcaption :address :caption :thead :tbody :tfoot :section :article})

(defn- nodes-of
  "`x` as a sequence of nodes: a string is parsed, a node wrapped."
  [x]
  (cond
    (string? x)             (parse x)
    (and (vector? x)
         (keyword? (first x))) [x]
    :else                   x))

(defn- collapse
  [s]
  (str/replace s #"\s+" " "))

(defn- tidy
  "Trim the rendered `s` and reduce its blank lines to one."
  [s]
  (-> s
      (str/replace #"[ \t]+\n" "\n")
      (str/replace #"\n[ \t]+" "\n")
      (str/replace #"\n{3,}" "\n\n")
      str/trim))

(defn- render
  "Render `nodes` with `inline`, a function of a text string and the
  context, and `element`, a function of a node, its rendered children and
  the context, which is a map with `:list` (:ul or :ol), `:index` (an atom
  counting list items) and `:pre?`."
  [nodes inline element context]
  (letfn [(node [x ctx]
            (cond
              (string? x)       (inline x ctx)
              (not (vector? x)) ""
              :else
              (let [[tag _ & children] x
                    ctx (cond-> ctx
                          (#{:ul :ol} tag) (assoc :list tag :index (atom 0))
                          (= :pre tag)      (assoc :pre? true))]
                (element x (str/join (map #(node % ctx) children)) ctx))))]
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
              (let [[tag attrs & children] x
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
