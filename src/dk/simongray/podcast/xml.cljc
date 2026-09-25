(ns dk.simongray.podcast.xml
  "XML as Hiccup, with namespaces resolved by URI rather than by prefix.

  Parsing has two steps. A thin platform adapter (`parse-raw`) turns XML into
  *raw* Hiccup with names exactly as written and xmlns declarations left in
  place as ordinary attributes:

      [\"itunes:author\" {\"xmlns:itunes\" \"http://www.itunes.com/...\"} \"Jane\"]

  The pure `resolve-namespaces` step then walks that tree, keeps track of the
  declarations in scope and rewrites every name into a keyword whose namespace
  is the canonical alias of the URI: `:itunes/author`, `:podcast/transcript`.
  Unprefixed elements are RSS 2.0 core and never get a namespace, whatever
  default namespace a feed declares. Problems found on the way (undeclared
  prefixes, unknown URIs, a default namespace) end up in the `:xml/report`
  metadata of the result, and the URIs of unknown namespaces under
  `:xml/namespaces` so that `emit` can declare them again.

  `emit` is the inverse: canonical Hiccup back to an XML string, with the
  declarations for every alias in use written onto the root element."
  (:require [clojure.set :as set]
            [clojure.string :as str])
  #?(:clj (:import [java.io InputStream Reader StringReader]
                   [javax.xml.stream XMLInputFactory XMLStreamConstants
                                     XMLStreamException XMLStreamReader])))

;;;; Namespaces

(def namespaces
  "The canonical namespace URI of every alias the library knows about."
  {:itunes     "http://www.itunes.com/dtds/podcast-1.0.dtd"
   :podcast    "https://podcastindex.org/namespace/1.0"
   :atom       "http://www.w3.org/2005/Atom"
   :content    "http://purl.org/rss/1.0/modules/content/"
   :dc         "http://purl.org/dc/elements/1.1/"
   :media      "http://search.yahoo.com/mrss/"
   :psc        "http://podlove.org/simple-chapters"
   :googleplay "http://www.google.com/schemas/play-podcasts/1.0"
   :spotify    "http://www.spotify.com/ns/rss"
   :sy         "http://purl.org/rss/1.0/modules/syndication/"
   :rawvoice   "http://www.rawvoice.com/rawvoiceRssModule/"
   :xhtml      "http://www.w3.org/1999/xhtml"
   :opml       "http://opml.org/spec2"})

(def uri->alias
  "Look up the alias of a namespace URI. The podcast namespace specification
  requires its old GitHub URI to be treated as identical to the canonical one."
  (assoc (set/map-invert namespaces)
    "https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/1.0.md"
    :podcast))

;;;; Hiccup helpers

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
  (apply str (filter string? (children element))))

(defn elements
  "The element children of `element`, optionally only those tagged `tag`."
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

(defn compact-text
  "Merge adjacent strings in `nodes` and drop the ones that are blank; what
  every raw-parsing adapter does to the children it reads."
  [nodes]
  (->> (partition-by string? nodes)
       (mapcat (fn [group]
                 (if (string? (first group))
                   (let [s (apply str group)]
                     (when-not (str/blank? s) [s]))
                   group)))))

;;;; Raw parsing

(defn strip-bom
  "Remove a byte order mark that survived decoding from the front of `s`."
  [s]
  (if (str/starts-with? s "﻿")
    (subs s 1)
    s))

#?(:clj
   (defn input-factory
     "A StAX factory with namespace processing off, so that undeclared prefixes
     survive and xmlns declarations arrive as plain attributes, and with DTDs
     and external entities disabled."
     []
     (let [factory (doto (XMLInputFactory/newInstance)
                     (.setProperty XMLInputFactory/IS_NAMESPACE_AWARE false)
                     (.setProperty XMLInputFactory/IS_COALESCING true)
                     (.setProperty XMLInputFactory/SUPPORT_DTD false)
                     (.setProperty XMLInputFactory/IS_SUPPORTING_EXTERNAL_ENTITIES false)
                     (.setProperty XMLInputFactory/IS_REPLACING_ENTITY_REFERENCES true))]
       ;; Recent JDKs cap a single text node at 100,000 characters, which show
       ;; notes exceed in the wild. With DTDs off the limits guard nothing.
       (doseq [limit ["jdk.xml.maxGeneralEntitySizeLimit" "jdk.xml.totalEntitySizeLimit"]]
         (try
           (.setProperty factory limit "0")
           (catch Exception _)))
       factory)))

#?(:clj
   (defn- qname-str
     "Join `prefix` and `local` back into the name as written in the document."
     [prefix local]
     (if (str/blank? prefix)
       local
       (str prefix ":" local))))

#?(:clj
   (defn- raw-attrs
     [^XMLStreamReader r]
     (-> {}
         (into (for [i (range (.getAttributeCount r))]
                 [(qname-str (.getAttributePrefix r i) (.getAttributeLocalName r i))
                  (.getAttributeValue r i)]))
         (into (for [i (range (.getNamespaceCount r))]
                 [(qname-str "xmlns" (.getNamespacePrefix r i))
                  (.getNamespaceURI r i)])))))

#?(:clj
   (declare read-element))

#?(:clj
   (defn- read-children
     "Read the children of the element `r` is inside, up to its end tag."
     [^XMLStreamReader r]
     (loop [acc (transient [])]
       (let [event (.next r)]
         (cond
           (= event XMLStreamConstants/START_ELEMENT)
           (recur (conj! acc (read-element r)))

           (or (= event XMLStreamConstants/CHARACTERS)
               (= event XMLStreamConstants/CDATA)
               (= event XMLStreamConstants/SPACE))
           (recur (conj! acc (.getText r)))

           (= event XMLStreamConstants/END_ELEMENT)
           (persistent! acc)

           :else
           (recur acc))))))

#?(:clj
   (defn- read-element
     "Read the element `r` is positioned on, including its subtree."
     [^XMLStreamReader r]
     (let [tag   (qname-str (.getPrefix r) (.getLocalName r))
           attrs (raw-attrs r)]
       (into [tag attrs] (compact-text (read-children r))))))

#?(:clj
   (defn- stream-reader
     ^XMLStreamReader [^XMLInputFactory factory xml]
     (cond
       (string? xml)
       (.createXMLStreamReader factory (StringReader. ^String (strip-bom xml)))

       (instance? InputStream xml)
       (.createXMLStreamReader factory ^InputStream xml)

       (instance? Reader xml)
       (.createXMLStreamReader factory ^Reader xml)

       :else
       (throw (ex-info "Cannot parse XML from this value" {:type (type xml)})))))

#?(:cljs
   (defn- items
     "The members of the DOM collection `coll` (a NodeList or NamedNodeMap),
     through the `item` method every implementation has."
     [coll]
     (map #(.item coll %) (range (.-length coll)))))

#?(:cljs
   (defn- node->raw
     "The raw Hiccup for the DOM `node`: elements and their text, nothing else."
     [node]
     (case (.-nodeType node)
       1 (into [(.-nodeName node)
                (into {} (for [a (items (.-attributes node))]
                           [(.-nodeName a) (.-nodeValue a)]))]
               (compact-text (keep node->raw (items (.-childNodes node)))))
       (3 4) (.-nodeValue node)
       nil)))

#?(:cljs
   (defn- malformed
     [message]
     (ex-info (str "Malformed XML: " message) {:type ::malformed})))

#?(:cljs
   (defn- dom-parse
     "Parse `xml` with the DOMParser of the platform (the browser's, or one a
     host such as Node has installed as `js/DOMParser`) into its root element.
     Browsers report errors as a parsererror element; other implementations
     may throw or hand back a document without a root."
     [xml]
     (let [doc    (try
                    (.parseFromString (js/DOMParser.) xml "text/xml")
                    (catch :default e
                      (throw (malformed (or (ex-message e) (str e))))))
           errors (.getElementsByTagName doc "parsererror")
           root   (.-documentElement doc)]
       (cond
         (pos? (.-length errors)) (throw (malformed (.-textContent (.item errors 0))))
         (nil? root)              (throw (malformed "no root element"))
         :else                    root))))

(defn parse-raw
  "Parse `xml` into raw Hiccup: names as written, declarations kept as
  attributes, comments and processing instructions dropped, adjacent text and
  CDATA merged and whitespace-only text removed.

  `xml` is a string; on the JVM it may also be an InputStream, which lets the
  parser honour the document's own encoding declaration, or a Reader. A
  document the parser rejects throws an ex-info of type `::malformed`."
  [xml]
  #?(:clj
     (let [r (stream-reader (input-factory) xml)]
       (try
         (loop []
           (if (= (.next r) XMLStreamConstants/START_ELEMENT)
             (read-element r)
             (recur)))
         (catch XMLStreamException e
           (throw (ex-info (str "Malformed XML: " (.getMessage e))
                           {:type ::malformed}
                           e)))
         (finally
           (.close r))))
     :cljs
     (node->raw (dom-parse (strip-bom xml)))))

;;;; Namespace resolution

(defn split-qname
  "Split the raw XML name `s` into `[prefix local]`, `prefix` nil when absent."
  [s]
  (if-let [i (str/index-of s ":")]
    [(subs s 0 i) (subs s (inc i))]
    [nil s]))

(defn declarations
  "The namespace declarations among raw `attrs`, as a map from prefix to URI.
  The default namespace, if declared, is under the key nil."
  [attrs]
  (into {} (keep (fn [[k v]]
                   (cond
                     (= k "xmlns") [nil v]
                     (str/starts-with? k "xmlns:") [(subs k 6) v])))
        attrs))

(defn- resolve-name
  "Resolve the raw name `s` in `scope`; returns `[keyword problem]` where
  `problem` is nil unless the prefix was undeclared or its URI unknown. An
  unknown URI keeps the prefix as its alias and is reported with the URI, so
  that `emit` can declare it again."
  [uri->alias scope s]
  (let [[prefix local] (split-qname s)]
    (cond
      (nil? prefix)
      [(keyword local) nil]

      (= prefix "xml")
      [(keyword "xml" local) nil]

      :else
      (if-let [uri (get scope prefix)]
        (if-let [alias (uri->alias uri)]
          [(keyword (name alias) local) nil]
          [(keyword prefix local)
           {:id :xml/unknown-namespace :level :info :prefix prefix :uri uri}])
        [(keyword prefix local)
         {:id :xml/undeclared-prefix :level :warning :prefix prefix}]))))

(defn- resolve-attrs
  "Resolve the attribute names in `attrs`, dropping the xmlns declarations;
  returns `[attrs problems]`."
  [uri->alias scope problems attrs]
  (reduce-kv (fn [[m problems] k v]
               (if (or (= k "xmlns") (str/starts-with? k "xmlns:"))
                 [m problems]
                 (let [[k' problem] (resolve-name uri->alias scope k)]
                   [(assoc m k' v) (cond-> problems problem (conj problem))])))
             [{} problems]
             attrs))

(defn- resolve-element
  "Resolve `[tag attrs & children]` in `scope`; returns `[element problems]`."
  [uri->alias scope problems [tag attrs & children]]
  (let [decls           (declarations attrs)
        scope           (into scope (remove (comp nil? key)) decls)
        problems        (cond-> problems
                          (contains? decls nil)
                          (conj {:id :xml/default-namespace :level :info :uri (get decls nil)}))
        [tag' problem]  (resolve-name uri->alias scope tag)
        problems        (cond-> problems problem (conj problem))
        [attrs' problems] (resolve-attrs uri->alias scope problems attrs)
        [children' problems] (reduce (fn [[acc problems] child]
                                       (if (string? child)
                                         [(conj acc child) problems]
                                         (let [[child' problems] (resolve-element uri->alias scope problems child)]
                                           [(conj acc child') problems])))
                                     [[] problems]
                                     children)]
    [(into [tag' attrs'] children') problems]))

(defn resolve-namespaces
  "Rewrite the raw Hiccup `tree` into canonical Hiccup: keyword tags and
  attributes namespaced by alias, xmlns declarations removed. `uri->alias`
  maps a namespace URI to its alias keyword. Problems are recorded under
  `:xml/report` in the metadata of the result, and the URIs of namespaces
  the lookup did not know under `:xml/namespaces`."
  ([tree]
   (resolve-namespaces tree uri->alias))
  ([tree uri->alias]
   (let [[tree' problems] (resolve-element uri->alias {} [] tree)
         problems         (distinct problems)]
     (with-meta tree' {:xml/report     (vec problems)
                       :xml/namespaces (into {} (for [{:keys [id prefix uri]} problems
                                                      :when (= id :xml/unknown-namespace)]
                                                  [(keyword prefix) uri]))}))))

(defn parse
  "Parse `xml` into canonical Hiccup; see `parse-raw` and `resolve-namespaces`."
  ([xml]
   (parse xml uri->alias))
  ([xml uri->alias]
   (resolve-namespaces (parse-raw xml) uri->alias)))

(defn report
  "The problems recorded while resolving the namespaces of `tree`."
  [tree]
  (:xml/report (meta tree)))

(defn foreign-namespaces
  "The URIs of the namespaces in `tree` that the library does not know, by the
  prefix the document used for them."
  [tree]
  (:xml/namespaces (meta tree)))

;;;; Emitting

(def illegal-characters
  "The characters XML 1.0 forbids anywhere, as a pattern."
  #"[\x00-\x08\x0B\x0C\x0E-\x1F￾￿]")

(defn- legal?
  "Whether the string `s` has no character XML 1.0 forbids. A plain loop over
  the characters: a regular expression crawls the megabytes of show notes in
  a big feed thirty times slower."
  [^String s]
  (let [n (count s)]
    (loop [i 0]
      (if (= i n)
        true
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (if (or (< c 9) (= c 11) (= c 12) (and (> c 13) (< c 32)) (= c 0xFFFE) (= c 0xFFFF))
            false
            (recur (inc i))))))))

(defn- legal
  "The string `s` without the characters XML 1.0 forbids anywhere; `s`
  itself, uncopied, when it has none."
  [s]
  (if (legal? s)
    s
    (str/replace s illegal-characters "")))

(defn- escape-legal-text
  "Escape the legal string `s` for a text node; `s` itself when nothing
  needs escaping. A carriage return becomes a character reference, the only
  form a parser does not normalize away."
  [s]
  (if (re-find #"[&<>\r]" s)
    (-> s
        (str/replace "&" "&amp;")
        (str/replace "<" "&lt;")
        (str/replace ">" "&gt;")
        (str/replace "\r" "&#13;"))
    s))

(defn- escape-attr
  "Escape `s` for an attribute value, where a parser also normalizes
  newlines and tabs to spaces unless they are character references."
  [s]
  (let [s (escape-legal-text (legal s))]
    (if (re-find #"[\"\n\t]" s)
      (-> s
          (str/replace "\"" "&quot;")
          (str/replace "\n" "&#10;")
          (str/replace "\t" "&#9;"))
      s)))

(defn- append-text!
  "Append the text `s` to the transient vector `acc` of string parts: as
  CDATA when it contains markup but no carriage return, which CDATA cannot
  carry through a parser, and escaped otherwise. Show notes are the bulk of
  a feed, and a text that needs no change is appended without a copy."
  [acc s]
  (let [s (legal s)]
    (if (and (str/includes? s "<")
             (not (str/includes? s "]]>"))
             (not (str/includes? s "\r")))
      (-> acc (conj! "<![CDATA[") (conj! s) (conj! "]]>"))
      (conj! acc (escape-legal-text s)))))

(defn- keyword-str
  [k]
  (if-let [ns (namespace k)]
    (str ns ":" (name k))
    (name k)))

(defn aliases
  "The set of namespace aliases used by tags and attributes in `tree`."
  [tree]
  (->> (tree-seq vector? elements tree)
       (mapcat (fn [el] (cons (tag el) (keys (attrs el)))))
       (keep namespace)
       (remove #{"xml"})
       (map keyword)
       set))

(defn- attrs-str
  [attrs]
  (apply str (for [[k v] attrs]
               (str " " (keyword-str k) "=\"" (escape-attr (str v)) "\""))))

(defn- emit-element*
  "Append the serialization of `element`, indented for `depth`, to the
  transient vector `acc` of string parts. One flat vector joined once at the
  end keeps a 30 MB feed from being copied once per level of nesting."
  [acc depth element]
  (let [indent   (apply str (repeat (* 2 depth) " "))
        name     (keyword-str (tag element))
        children (children element)
        acc      (-> acc (conj! indent) (conj! "<") (conj! name) (conj! (attrs-str (attrs element))))]
    (cond
      (empty? children)
      (conj! acc "/>")

      (every? string? children)
      (-> acc (conj! ">") (append-text! (apply str children)) (conj! "</") (conj! name) (conj! ">"))

      :else
      (-> (reduce (fn [acc [i child]]
                    (let [acc (if (pos? i) (conj! acc "\n") acc)]
                      (if (string? child)
                        (-> acc (conj! indent) (conj! "  ") (append-text! child))
                        (emit-element* acc (inc depth) child))))
                  (conj! acc ">\n")
                  (map-indexed vector children))
          (conj! "\n") (conj! indent) (conj! "</") (conj! name) (conj! ">")))))

(defn- emit-element
  [depth element]
  (str/join (persistent! (emit-element* (transient []) depth element))))

(defn emit
  "Serialize the canonical Hiccup `tree` to an XML string, declaring the
  namespaces it uses on the root element. `alias->uri` supplies the URI for
  each alias; it defaults to `namespaces` plus whatever foreign namespaces
  were recorded when `tree` was parsed."
  ([tree]
   (emit tree (merge (foreign-namespaces tree) namespaces)))
  ([tree alias->uri]
   (let [decls (into (sorted-map)
                     (for [alias (aliases tree)]
                       [(keyword "xmlns" (name alias))
                        (or (alias->uri alias)
                            (throw (ex-info "Unknown namespace alias" {:alias alias})))]))
         root  (update tree 1 #(into decls %))]
     (str "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
          (emit-element 0 root)
          "\n"))))

(defn- emit-compact*
  "Append the one-line serialization of `element` to the transient vector
  `acc` of string parts."
  [acc element]
  (let [name (keyword-str (tag element))
        acc  (-> acc (conj! "<") (conj! name) (conj! (attrs-str (attrs element))))]
    (if (empty? (children element))
      (conj! acc "/>")
      (-> (reduce (fn [acc child]
                    (if (string? child)
                      (append-text! acc child)
                      (emit-compact* acc child)))
                  (conj! acc ">")
                  (children element))
          (conj! "</") (conj! name) (conj! ">")))))

(defn inner-xml
  "The content of `element` as markup: its text as written and its child
  elements serialized compactly. This is what an HTML description looks like
  when a feed embeds it as XML rather than wrapping it in CDATA."
  [element]
  (str/join (persistent! (reduce (fn [acc child]
                                   (if (string? child)
                                     (conj! acc child)
                                     (emit-compact* acc child)))
                                 (transient [])
                                 (children element)))))

(comment
  (parse "<rss xmlns:itunes=\"http://www.itunes.com/dtds/podcast-1.0.dtd\" version=\"2.0\">
            <channel><title>T</title><itunes:author>A</itunes:author></channel></rss>")
  ;; => [:rss {:version "2.0"} [:channel {} [:title {} "T"] [:itunes/author {} "A"]]]
  (println (emit *1))
  #_.)
