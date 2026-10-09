(ns dk.simongray.podcast.util.xml
  "XML as Hiccup, with namespaces resolved by URI rather than by prefix.

  Raw Hiccup has the names as written. Canonical Hiccup has keywords
  namespaced by the alias that a table gives the URI, e.g. :itunes/author,
  while elements without a prefix stay RSS 2.0. Its metadata has what the
  resolution found under :xml/report, and the namespaces that the table
  lacks under :xml/namespaces."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces.entities :as entities])
  #?(:clj (:import [java.io InputStream Reader StringReader]
                   [javax.xml.stream XMLInputFactory XMLStreamConstants
                                     XMLStreamException XMLStreamReader])))

(defn tag
  "The tag of the Hiccup `element`."
  [element]
  (nth element 0))

(defn attrs
  "The attribute map of the Hiccup `element`."
  [element]
  (nth element 1))

(defn children
  "The children of the Hiccup `element`: strings and elements."
  [element]
  (drop 2 element))

(defn text
  "The concatenated text content of the Hiccup `element`."
  [element]
  ;; one string is the common case, and needs no concatenation
  (if (and (vector? element)
           (== 3 (count element))
           (string? (nth element 2)))
    (nth element 2)
    (apply str (filter string? (children element)))))

(defn elements
  "The element children of `element`, or only those tagged `tag`."
  ([element]
   (filter vector? (children element)))
  ([element tag]
   (filter #(= tag (nth % 0)) (elements element))))

(defn element
  "The first child of `element` tagged `tag`."
  [element tag]
  (first (elements element tag)))

(defn deep-text
  "All the text in `element` and its descendants, in document order."
  [element]
  (apply str (filter string? (tree-seq vector? children element))))

(defn- space-code?
  "Whether the character with the code `c` is white space to the trim of
  either platform. The trim of Java keeps the no-break spaces and the byte
  order mark, which that of JavaScript removes."
  [^long c]
  ;; a primitive argument, and comparisons of two arguments, which Clojure
  ;; compiles inline, since this runs for the ends of every value that a
  ;; parse reads
  (or (<= c 32)
      (== c 0xA0)
      (== c 0x1680)
      (and (<= 0x2000 c) (<= c 0x200A))
      (== c 0x2028)
      (== c 0x2029)
      (== c 0x202F)
      (== c 0x205F)
      (== c 0x3000)
      (== c 0xFEFF)))

(defn ^:no-doc trim
  "The string `s` without the white space at either end, the no-break
  spaces included, so that a value reads the same on the JVM and in
  JavaScript. It's `s` itself when there's none."
  [^String s]
  (let [n     (count s)
        start (loop [i 0]
                (if (and (< i n)
                         (space-code? #?(:clj  (int (.charAt s i))
                                         :cljs (.charCodeAt s i))))
                  (recur (inc i))
                  i))
        end   (loop [i n]
                (if (and (> i start)
                         (space-code? #?(:clj  (int (.charAt s (dec i)))
                                         :cljs (.charCodeAt s (dec i)))))
                  (recur (dec i))
                  i))]
    (if (and (zero? start) (== end n))
      s
      (subs s start end))))

(defn- kept-run
  "The transient `acc` with the run of text `run` added, unless it's
  blank. A blank run is kept when it's `between?` two elements on one
  line."
  [acc run between?]
  (if (and run
           (or (not= "" (trim run))
               (and between?
                    (not (re-find #"[\r\n]" run)))))
    (conj! acc run)
    acc))

(defn ^:no-doc compact-text
  "The `nodes` with adjacent strings merged and the blank ones dropped. A
  space between two elements on one line stays, since inline markup needs
  it, e.g. between a bold and an italic word."
  [nodes]
  (loop [nodes (seq nodes) acc (transient []) run nil after-element? false]
    (if nodes
      (let [node (first nodes)]
        (if (string? node)
          (recur (next nodes) acc (if run (str run node) node) after-element?)
          (let [acc (conj! (kept-run acc run after-element?) node)]
            (recur (next nodes) acc nil true))))
      (persistent! (kept-run acc run false)))))

(defn kebab
  "The camelCase XML name `s` in kebab-case, e.g. startTime as start-time."
  [s]
  ;; most names are in lower case already, and need no pattern
  (if (= s (str/lower-case s))
    s
    (-> s
        (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
        str/lower-case)))

(defn attr-key
  "The model key of the XML attribute named by the keyword `k`:
  kebab-cased when it has no namespace, or as it is otherwise."
  [k]
  (if (namespace k)
    k
    (keyword (kebab (name k)))))

(defn html-entity
  "The characters of the HTML entity `name`, e.g. \"nbsp\", or nil for a
  name that the table of html-pieces lacks."
  [name]
  (get entities/references (str name ";")))

(defn ^:no-doc character-references
  "The text `s` as numeric character references, one for each code point,
  e.g. &#160; for a no-break space."
  [s]
  #?(:clj  (apply str (map #(str "&#" % ";") (.toArray (.codePoints ^String s))))
     :cljs (apply str (map #(str "&#" (.codePointAt % 0) ";") (js/Array.from s)))))

(def xml-entities
  "The five entities that section 4.6 of XML 1.0 predefines."
  #{"amp" "lt" "gt" "quot" "apos"})

(defn- code-point->str
  [n]
  #?(:clj  (String. (Character/toChars n))
     :cljs (js/String.fromCodePoint n)))

(defn ^:no-doc code-point
  "The code point of the numeric reference `ref`, e.g. #65 or #x41, or nil
  when it isn't one that a string can hold: zero, a surrogate, or past
  U+10FFFF."
  [ref]
  (let [n (if (re-find #"^#[xX]" ref)
            #?(:clj  (try
                       (Long/parseLong (subs ref 2) 16)
                       (catch Exception _ nil))
               :cljs (js/parseInt (subs ref 2) 16))
            (parse-long (subs ref 1)))]
    (when (and n
               (or (<= 1 n 0xD7FF)
                   (<= 0xE000 n 0x10FFFF)))
      n)))

(defn decode-entities
  "The text `s` with the character and entity references replaced by the
  characters that they stand for, HTML entities included. Unknown
  references, and numeric ones outside what a string can hold, are kept."
  [s]
  (str/replace s #"&(#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);"
               (fn [[whole ref]]
                 (cond
                   (str/starts-with? ref "#")
                   (if-let [n (code-point ref)]
                     (code-point->str n)
                     whole)

                   :else
                   (or (html-entity ref) whole)))))

(defn- strip-bom
  "The text `s` without the byte order mark at its front, when one
  survived decoding."
  [s]
  (if (str/starts-with? s "\uFEFF")
    (subs s 1)
    s))

(defn- bom-encoding
  "The encoding that the byte order mark at the start of the bytes `b`
  names, or nil when there's none."
  [b]
  (let [n    #?(:clj (alength ^bytes b) :cljs (.-length b))
        at   (fn [i]
               (when (< i n)
                 #?(:clj  (bit-and (aget ^bytes b i) 0xff)
                    :cljs (aget b i))))
        head (map at (range 3))]
    (cond
      (= [0xef 0xbb 0xbf] head)     "UTF-8"
      (= [0xfe 0xff] (take 2 head)) "UTF-16BE"
      (= [0xff 0xfe] (take 2 head)) "UTF-16LE")))

(defn- declared-encoding
  "The encoding that the XML declaration at the start of the bytes `b`
  names, or nil."
  [b]
  (let [n    (min 200 #?(:clj (alength ^bytes b) :cljs (.-length b)))
        head #?(:clj  (String. ^bytes b 0 (int n) "ISO-8859-1")
                :cljs (.decode (js/TextDecoder. "latin1") (.subarray b 0 n)))]
    (second (re-find #"(?i)<\?xml[^>]*encoding=[\"']([\w.:-]+)" head))))

;; XML 1.0, appendix F; RFC 7303, section 3.2
(defn decode
  "The text of the XML document in the bytes `b`, by the encoding that its
  byte order mark names, or else by `charset`, e.g. that of the
  Content-Type of a response, or else by its XML declaration, or else as
  UTF-8.

  An encoding that the platform doesn't know counts as UTF-8, and a byte
  order mark is left out, as a browser leaves it out."
  ([b]
   (decode b nil))
  ([b charset]
   (let [encoding (or (bom-encoding b) charset (declared-encoding b) "UTF-8")]
     (strip-bom
      #?(:clj  (String. ^bytes b
                        (try
                          (java.nio.charset.Charset/forName encoding)
                          (catch Exception _
                            java.nio.charset.StandardCharsets/UTF_8)))
         :cljs (.decode (try
                          (js/TextDecoder. encoding)
                          (catch :default _
                            (js/TextDecoder. "utf-8")))
                        b))))))

(defn ^:no-doc malformed
  "The error for XML that the parser rejects with `message`, and `cause`,
  the exception that carried the message, or nil."
  [message cause]
  (ex-info (str "Malformed XML: " message) {:type ::malformed} cause))

;; Deeper than any feed goes, and shallow enough for every walk of the tree
;; to recurse on a thread with a stack of the default size
(def max-depth
  "How deep elements may nest in a document that the parsers read, with
  the root at 1. A document nested deeper is malformed."
  256)

(defn ^:no-doc check-depth
  "Nil when an element at `depth` nests no deeper than max-depth, or else
  it throws ::malformed."
  [depth]
  (when (> depth max-depth)
    (throw (malformed (str "elements nest deeper than " max-depth " levels")
                      nil))))

#?(:cljs
   (defn- dom-parser
     "A DOMParser of the platform. Without one, it throws with the :type
     ::no-parser, since that's no fault of the XML."
     []
     (if (exists? js/DOMParser)
       (js/DOMParser.)
       (throw (ex-info (str "No DOMParser to read XML with. In Node, install"
                            " one as globalThis.DOMParser, e.g. that of"
                            " @xmldom/xmldom.")
                       {:type ::no-parser})))))

#?(:clj
   (defn- input-factory
     "A StAX factory with namespace processing off, DTDs and external
     entities disabled, and no limits of the JDK on the size of a text or
     the depth of nesting."
     ^XMLInputFactory []
     (let [factory (XMLInputFactory/newInstance)]
       (doto factory
         (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
         (.setProperty XMLInputFactory/IS_COALESCING true)
         (.setProperty XMLInputFactory/SUPPORT_DTD false)
         (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
         (.setProperty XMLInputFactory/IS_REPLACING_ENTITY_REFERENCES true))
       ;; Recent JDKs cap a single text node at 100,000 characters, which
       ;; show notes exceed in the wild, and JDK 24 caps nesting at 100
       ;; levels. With DTDs off the entity limits guard nothing, and
       ;; read-element checks the depth itself, the same on every JDK and in
       ;; JavaScript.
       (doseq [limit ["jdk.xml.maxGeneralEntitySizeLimit"
                      "jdk.xml.totalEntitySizeLimit"
                      "jdk.xml.maxElementDepth"]]
         (try
           (.setProperty factory limit "0")
           (catch Exception _)))
       factory)))

(defn- document
  "The document in `xml`, ready to read.

  On the JVM, `xml` is a string, an InputStream or a Reader. The result is
  a StAX reader at its start, from a factory with namespace processing
  off, so that undeclared prefixes survive and xmlns declarations arrive
  as plain attributes. DTDs and external entities are disabled.

  In JavaScript, `xml` is a string, and the result is the root element of
  the DOM that the DOMParser of the platform builds. That's the one of the
  browser, or one that a host such as Node has installed as js/DOMParser,
  and without one it throws with the :type ::no-parser. Browsers report
  errors as a parsererror element, and other implementations throw or
  give a document without a root."
  [xml]
  #?(:clj
     (let [factory (input-factory)]
       (cond
         (string? xml)
         (.createXMLStreamReader factory
                                 (StringReader. ^String (strip-bom xml)))

         (instance? InputStream xml)
         (.createXMLStreamReader factory ^InputStream xml)

         (instance? Reader xml)
         (.createXMLStreamReader factory ^Reader xml)

         :else
         (throw (ex-info "Cannot parse XML from this value"
                         {:type (type xml)}))))
     :cljs
     (let [parser (dom-parser)
           doc    (try
                    (.parseFromString parser (strip-bom xml) "text/xml")
                    (catch :default e
                      (throw (malformed (or (ex-message e) (str e)) e))))
           errors (.getElementsByTagName doc "parsererror")
           root   (.-documentElement doc)]
       (cond
         (pos? (.-length errors))
         (throw (malformed (.-textContent (.item errors 0)) nil))

         (nil? root)
         (throw (malformed "no root element" nil))

         :else
         root))))

(defn- start-tag
  "The name and the attributes of the element at `x`, as a pair.

  Both are as the document writes them, with the namespace declarations
  among the attributes. On the JVM, `x` is a StAX reader on the start tag
  of the element, and in JavaScript a DOM element."
  [x]
  #?(:clj  (let [^XMLStreamReader r x
                 qname (fn [prefix local]
                         (if (str/blank? prefix) local (str prefix ":" local)))]
             [(qname (.getPrefix r) (.getLocalName r))
              (-> {}
                  (into (for [i (range (.getAttributeCount r))]
                          [(qname (.getAttributePrefix r i)
                                  (.getAttributeLocalName r i))
                           (.getAttributeValue r i)]))
                  (into (for [i (range (.getNamespaceCount r))]
                          [(qname "xmlns" (.getNamespacePrefix r i))
                           (.getNamespaceURI r (int i))])))])
     :cljs (let [attrs (.-attributes x)]
             [(.-nodeName x)
              (into {} (for [i (range (.-length attrs))
                             :let [a (.item attrs i)]]
                         [(.-nodeName a) (.-nodeValue a)]))])))

(declare read-element)

(defn- read-children
  "The raw children of the element at `x`, its elements and its text, which
  nest at `depth`.

  They are read from the StAX reader up to the element's end tag on the
  JVM, and from the child nodes of the DOM element in JavaScript."
  [x depth]
  #?(:clj  (let [^XMLStreamReader r x]
             (loop [acc (transient [])]
               (let [event (.next r)]
                 (cond
                   (= event XMLStreamConstants/START_ELEMENT)
                   (recur (conj! acc (read-element r depth)))

                   (or (= event XMLStreamConstants/CHARACTERS)
                       (= event XMLStreamConstants/CDATA)
                       (= event XMLStreamConstants/SPACE))
                   (recur (conj! acc (.getText r)))

                   (= event XMLStreamConstants/END_ELEMENT)
                   (persistent! acc)

                   :else
                   (recur acc)))))
     :cljs (let [nodes (.-childNodes x)]
             (keep (fn [i]
                     (let [node (.item nodes i)]
                       (case (.-nodeType node)
                         1     (read-element node depth)
                         (3 4) (.-nodeValue node)
                         nil)))
                   (range (.-length nodes))))))

(defn- read-element
  "The raw Hiccup of the element at `x` and its subtree, where `depth` is
  how deep the element nests, 1 for the root.

  On the JVM, `x` is a StAX reader on the start tag of the element, and in
  JavaScript a DOM element."
  [x depth]
  (check-depth depth)
  (let [head (start-tag x)]
    ;; the start tag first: on the JVM, reading the children moves past it
    (into head (compact-text (read-children x (inc depth))))))

(defn parse-raw
  "The raw Hiccup of `xml`, with the names as written and the declarations
  kept as attributes. Comments and processing instructions are dropped,
  adjacent text and CDATA merged, and text of only white space removed.

  The `xml` is a string. On the JVM, it can also be a Reader, or an
  InputStream, which lets the parser honour the encoding declaration of
  the document. These throw an ex-info of the type ::malformed:

  - a document that the parser rejects
  - an XML declaration that it can't read
  - markup or text after the root element
  - elements nested deeper than max-depth"
  [xml]
  #?(:clj
     (try
       (let [^XMLStreamReader r (document xml)]
         (try
           (let [root (loop []
                        (if (= (.next r) XMLStreamConstants/START_ELEMENT)
                          (read-element r 1)
                          (recur)))]
             ;; on to the end, where anything but a comment or white space
             ;; after the root is an error, as it is in a browser
             (while (.hasNext r)
               (.next r))
             root)
           (finally
             (.close r))))
       (catch XMLStreamException e
         (throw (malformed (.getMessage e) e))))
     :cljs
     (read-element (document xml) 1)))

(defn- split-qname
  "The raw XML name `s` as a pair of its prefix, or nil when it has none,
  and its local name."
  [s]
  (if-let [i (str/index-of s ":")]
    [(subs s 0 i) (subs s (inc i))]
    [nil s]))

(defn ^:no-doc declarations
  "The namespace declarations among raw `attrs`, as a map from prefix to URI.
  The default namespace, when declared, is under the key nil."
  [attrs]
  (into {} (keep (fn [[k v]]
                   (cond
                     (= k "xmlns") [nil v]
                     (str/starts-with? k "xmlns:") [(subs k 6) v])))
        attrs))

(defn- resolve-name
  "The raw name `s` resolved in `scope`, as a pair of the keyword and a
  problem. The `scope` maps each prefix to a pair of its URI and alias.

  The problem is nil unless the prefix was undeclared or its URI unknown.
  An unknown URI keeps the prefix as its alias, and the problem has the
  URI, so that it can be declared again when the tree is written."
  [scope s]
  (let [[prefix local] (split-qname s)]
    (cond
      (nil? prefix)
      [(keyword local) nil]

      (= prefix "xml")
      [(keyword "xml" local) nil]

      :else
      (if-let [[uri alias] (get scope prefix)]
        (if alias
          [(keyword (name alias) local) nil]
          [(keyword prefix local)
           {:id :xml/unknown-namespace :level :info :prefix prefix :uri uri}])
        [(keyword prefix local)
         {:id :xml/undeclared-prefix :level :warning :prefix prefix}]))))

(defn- resolve-attrs
  "The attribute names in `attrs` resolved in `scope`, without the xmlns
  declarations, and the `problems` so far with those that the names add,
  as a pair."
  [scope problems attrs]
  (reduce-kv (fn [[m problems] k v]
               (if (or (= k "xmlns") (str/starts-with? k "xmlns:"))
                 [m problems]
                 (let [[k' problem] (resolve-name scope k)]
                   [(assoc m k' v) (cond-> problems problem (conj problem))])))
             [{} problems]
             attrs))

;; Namespaces in XML 1.0, section 6.1
(defn- resolve-element
  "The `element` resolved in `scope` by `uri->alias`, and the `problems`
  so far with those that it adds, as a pair. The `scope` maps each prefix
  to a pair of its URI and alias."
  [uri->alias scope problems [tag attrs & children :as element]]
  (let [decls                (declarations attrs)
        ;; the alias of a declared URI is looked up once, where it's
        ;; declared, not again for every name that uses it
        alias-of             (fn [[prefix uri]]
                               (when prefix
                                 [prefix [uri (uri->alias uri)]]))
        scope                (into scope (keep alias-of) decls)
        problems             (cond-> problems
                               (contains? decls nil)
                               (conj {:id    :xml/default-namespace
                                      :level :info
                                      :uri   (get decls nil)}))
        [tag' problem]       (resolve-name scope tag)
        problems             (cond-> problems problem (conj problem))
        [attrs' problems]    (resolve-attrs scope problems attrs)
        resolve-child        (fn [[acc problems] child]
                               (if (string? child)
                                 [(conj acc child) problems]
                                 (let [[child' problems]
                                       (resolve-element uri->alias scope
                                                        problems child)]
                                   [(conj acc child') problems])))
        [children' problems] (reduce resolve-child [[] problems] children)]
    [(into [tag' attrs'] children') problems]))

(defn resolve-namespaces
  "The raw Hiccup `tree` as canonical Hiccup, with the `uri->alias` map
  from namespace URIs to alias keywords. Tags and attributes are keywords
  namespaced by alias, and the xmlns declarations are removed.

  The metadata of the result has the problems under :xml/report. Under
  :xml/namespaces, it has the URIs of the namespaces that the lookup
  didn't know, and nil for the prefixes that no declaration named, whose
  elements are left out when the tree is written."
  [tree uri->alias]
  (let [[tree' problems] (resolve-element uri->alias {} [] tree)
        problems         (distinct problems)
        by-prefix        (fn [id]
                           (into {} (for [p     problems
                                          :when (= id (:id p))]
                                      [(keyword (:prefix p)) (:uri p)])))
        undeclared       (by-prefix :xml/undeclared-prefix)
        unknown          (by-prefix :xml/unknown-namespace)]
    (with-meta tree' {:xml/report     (vec problems)
                      :xml/namespaces (merge undeclared unknown)})))

(defn parse
  "The canonical Hiccup of `xml`, with the namespaces resolved by
  `uri->alias`, none by default."
  ([xml]
   (parse xml {}))
  ([xml uri->alias]
   (resolve-namespaces (parse-raw xml) uri->alias)))

(defn report
  "The problems recorded while resolving the namespaces of `tree`, as
  diagnostics of an :id, a :level and details."
  [tree]
  (:xml/report (meta tree)))

(defn foreign-namespaces
  "The URIs of the namespaces that `tree` carries in its metadata, by alias,
  to declare when the tree is written.

  For a parse, they're the namespaces that its table didn't know, by the
  prefix that the document used, and a prefix that no declaration named
  has nil."
  [tree]
  (:xml/namespaces (meta tree)))

(def illegal-characters
  "The characters that section 2.2 of XML 1.0 forbids anywhere, as a
  pattern."
  #"[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]")

(defn ^:no-doc legal?
  "Whether the string `s` has no character that XML 1.0 forbids."
  [^String s]
  ;; a plain loop over the characters, since a regular expression crawls
  ;; the megabytes of show notes in a big feed thirty times slower
  (let [n (count s)]
    (loop [i 0]
      (if (= i n)
        true
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (or (< c 9)
                  (= c 11)
                  (= c 12)
                  (and (> c 13) (< c 32))
                  (= c 0xFFFE)
                  (= c 0xFFFF))
            false
            (recur (inc i))))))))

(defn- legal
  "The string `s` without the characters that XML 1.0 forbids anywhere, or
  `s` itself, uncopied, when it has none."
  [s]
  (if (legal? s)
    s
    (str/replace s illegal-characters "")))

(defn ^:no-doc plain?
  "Whether the string `s` goes into a text node as it is, or with `attr?`
  into an attribute value: nothing in it that XML 1.0 forbids, and
  nothing to escape."
  [^String s attr?]
  ;; one loop over the characters, which most values pass, in place of a
  ;; check of what's legal and a pattern each
  (let [n (count s)]
    (loop [i 0]
      (if (== i n)
        true
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (if (< c 63)
                (or (== c 38) (== c 60) (== c 62)
                    (and (< c 32) (or attr? (< c 9) (> c 10)))
                    (and attr? (== c 34)))
                (>= c 0xFFFE))
            false
            (recur (inc i))))))))

;; XML 1.0, section 2.11
(defn- escape-legal-text
  "The legal string `s` escaped for a text node, or `s` itself when nothing
  needs escaping. A carriage return becomes a character reference, the
  only form that a parser doesn't normalize away."
  [s]
  (if (re-find #"[&<>\r]" s)
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\r" "&#13;"))
    s))

;; XML 1.0, section 3.3.3
(defn- escape-attr
  "The string `s` escaped for an attribute value, where a parser also
  normalizes newlines and tabs to spaces unless they're character
  references."
  [s]
  (if (plain? s true)
    s
    (let [s (escape-legal-text (legal s))]
      (if (re-find #"[\"\n\t]" s)
        (-> s
            (str/replace "\"" "&quot;")
            (str/replace "\n" "&#10;")
            (str/replace "\t" "&#9;"))
        s))))

(defn- append-text
  "The transient vector `acc` of string parts with the text `s` appended.
  The text goes as CDATA when it holds markup but no carriage return,
  which CDATA can't carry through a parser, or else escaped."
  [acc s]
  (if (plain? s false)
    (conj! acc s)
    (let [s (legal s)]
      (if (and (str/includes? s "<")
               (not (str/includes? s "]]>"))
               (not (str/includes? s "\r")))
        (-> acc (conj! "<![CDATA[") (conj! s) (conj! "]]>"))
        (conj! acc (escape-legal-text s))))))

(defn qname
  "The XML name of the keyword `k`, e.g. itunes:author for :itunes/author
  and title for :title."
  [k]
  (if-let [ns (namespace k)]
    (str ns ":" (name k))
    (name k)))

(defn- aliases
  "The set of namespace aliases that the tags and attributes in `tree` use,
  in one walk that makes no lazy sequence."
  [tree]
  (letfn [(with-ns [acc k]
            (if-let [ns (namespace k)]
              (conj! acc ns)
              acc))
          (walk [acc el]
            (let [acc (with-ns acc (tag el))
                  acc (reduce-kv (fn [acc k _] (with-ns acc k)) acc (attrs el))
                  n   (count el)]
              (loop [i 2 acc acc]
                (if (< i n)
                  (let [child (nth el i)]
                    (recur (inc i) (if (vector? child) (walk acc child) acc)))
                  acc))))]
    (into #{}
          (comp (remove #{"xml"}) (map keyword))
          (persistent! (walk (transient #{}) tree)))))

(defn ^:no-doc append-attrs
  "The transient vector `acc` of string parts with the attributes `attrs`
  appended, each as a space, its name, an equals sign and its value in
  quotes."
  [acc attrs]
  (reduce-kv (fn [acc k v]
               (-> acc
                   (conj! " ")
                   (conj! (qname k))
                   (conj! "=\"")
                   (conj! (escape-attr (str v)))
                   (conj! "\"")))
             acc
             attrs))

(def ^:no-doc indents
  "The indentation of an element at each depth down to max-depth, two
  spaces a level, made once rather than for every element."
  (vec (take (inc max-depth) (iterate #(str % "  ") ""))))

(declare emit-compact*)

;; One flat vector joined once at the end keeps a 30 MB feed from being
;; copied once for each level of nesting
(defn- emit-element*
  "The transient vector `acc` of string parts with the serialization of
  `element` appended, indented for `depth`. Mixed content is written as
  it is."
  [acc depth element]
  (let [indent   (or (get indents depth) (apply str (repeat (* 2 depth) " ")))
        children (children element)]
    ;; indentation would join the text of mixed content and grow it on
    ;; every round trip
    (if (and (some string? children) (some vector? children))
      (emit-compact* (conj! acc indent) element append-text)
      (let [name (qname (tag element))
            acc  (-> acc
                     (conj! indent)
                     (conj! "<")
                     (conj! name)
                     (append-attrs (attrs element)))]
        (cond
          (empty? children)
          (conj! acc "/>")

          (every? string? children)
          (-> acc
              (conj! ">")
              (append-text (apply str children))
              (conj! "</")
              (conj! name)
              (conj! ">"))

          :else
          (-> (reduce (fn [acc [i child]]
                        (let [acc (if (pos? i) (conj! acc "\n") acc)]
                          (emit-element* acc (inc depth) child)))
                      (conj! acc ">\n")
                      (map-indexed vector children))
              (conj! "\n")
              (conj! indent)
              (conj! "</")
              (conj! name)
              (conj! ">")))))))

(defn- without
  "The Hiccup `element` without the elements and attributes in the
  namespaces `aliases` names, or nil when the element itself is in one."
  [aliases element]
  (let [in? #(contains? aliases (some-> (namespace %) keyword))]
    (when-not (in? (tag element))
      (into [(tag element) (into {} (remove (comp in? key)) (attrs element))]
            (keep #(if (string? %) % (without aliases %)))
            (children element)))))

(defn emit
  "The canonical Hiccup `tree` as an XML string, with the namespaces that
  it uses declared on the root element by `alias->uri`.

  The `alias->uri` map gives the URI of each alias. It defaults to the
  foreign namespaces of `tree`, e.g. those that were recorded when it was
  parsed. An alias that it maps to nil has no URI to declare, so its
  elements and attributes are left out, as for a prefix that no
  declaration named in a parse. An alias that it doesn't map at all is an
  error."
  ([tree]
   (emit tree (foreign-namespaces tree)))
  ([tree alias->uri]
   (let [undeclared (when (map? alias->uri)
                      (set (keep (fn [[alias uri]] (when (nil? uri) alias))
                                 alias->uri)))
         tree       (if (seq undeclared)
                      (or (without undeclared tree) tree)
                      tree)
         uri-of     (fn [alias]
                      (or (get alias->uri alias)
                          (throw (ex-info "Unknown namespace alias"
                                          {:alias alias}))))
         decls      (into (sorted-map)
                          (for [alias (aliases tree)]
                            [(keyword "xmlns" (name alias)) (uri-of alias)]))
         root       (update tree 1 #(into decls %))
         xml-decl   "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"]
     ;; the declaration and the last line break are parts too, so that the
     ;; document is put together once
     (str/join (persistent! (-> (transient [xml-decl])
                                (emit-element* 0 root)
                                (conj! "\n")))))))

(defn- emit-compact*
  "The transient vector `acc` of string parts with the one-line
  serialization of `element` appended, its text appended by `text-fn`, a
  function of the vector and a string."
  [acc element text-fn]
  (let [name (qname (tag element))
        acc  (-> acc (conj! "<") (conj! name) (append-attrs (attrs element)))]
    (if (empty? (children element))
      (conj! acc "/>")
      (-> (reduce (fn [acc child]
                    (if (string? child)
                      (text-fn acc child)
                      (emit-compact* acc child text-fn)))
                  (conj! acc ">")
                  (children element))
          (conj! "</") (conj! name) (conj! ">")))))

(defn- escaped-text
  "The transient vector `acc` of string parts with the text `s` appended,
  escaped, and never as CDATA, which HTML doesn't read."
  [acc s]
  (conj! acc (if (plain? s false) s (escape-legal-text (legal s)))))

(defn inner-xml
  "The content of `element` as markup: its text escaped and its child
  elements serialized compactly.

  This is what an HTML description looks like when a feed embeds it as
  XML rather than wrapping it in CDATA. So an escaped pre tag in the feed
  stays text and doesn't become an element."
  [element]
  (str/join (persistent! (reduce (fn [acc child]
                                   (if (string? child)
                                     (escaped-text acc child)
                                     (emit-compact* acc child escaped-text)))
                                 (transient [])
                                 (children element)))))

(comment
  (parse "<rss version=\"2.0\"
            xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\">
            <channel><title>T</title><itunes:author>A</itunes:author></channel>
          </rss>")
  ;; => [:rss {:version "2.0"}
  ;;     [:channel {} [:title {} "T"] [:itunes/author {} "A"]]]
  (println (emit *1))
  #_.)
