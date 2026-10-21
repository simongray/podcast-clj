(ns dk.simongray.podcast.feed.repair
  "Named repairs for the text of a feed that isn't well-formed XML,
  applied only when the strict parse fails, and always reported.

  Every repair has an id, each use of one goes into a report, and the
  option :except leaves out those that it names."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.util.entities :as entities]
            [dk.simongray.qname-hiccup :as xml]))

(defn ^:no-doc map-outside-cdata
  "The string `s` with `f` applied to every stretch outside a CDATA
  section."
  [f s]
  (loop [from 0
         acc  []]
    (if-let [start (str/index-of s "<![CDATA[" from)]
      (let [end (or (some-> (str/index-of s "]]>" start) (+ 3)) (count s))]
        (recur end (conj acc (f (subs s from start)) (subs s start end))))
      (apply str (conj acc (f (subs s from)))))))

(defn strip-leading-junk
  "The string `s` without anything before the XML declaration or the rss
  start tag, whichever comes first, or else before its first tag.

  That's byte order marks, blank lines and stray output of the server,
  such as a PHP warning."
  [s]
  (if-let [start (some->> (re-find #"<\?xml|<rss[\s>/]" s) (str/index-of s))]
    (subs s start)
    (str/replace s #"^[^<]+" "")))

(defn strip-trailing-junk
  "The string `s` without anything but whitespace after the rss end tag,
  which is stray output of the server, such as a PHP warning."
  [s]
  (let [end (some->> (str/last-index-of s "</rss") (str/index-of s ">") inc)]
    (if (and end
             (not= "" (xml/trim (subs s end))))
      (subs s 0 end)
      s)))

(defn ^:no-doc legal-code-point?
  "Whether XML 1.0, section 2.2, allows the character with the code point
  `n`."
  [n]
  (or (= n 9)
      (= n 10)
      (= n 13)
      (<= 0x20 n 0xD7FF)
      (<= 0xE000 n 0xFFFD)
      (<= 0x10000 n 0x10FFFF)))

(defn- legal-reference?
  "Whether the character reference `ref`, such as #38 or #x26, names a
  character that XML 1.0 allows."
  [ref]
  (boolean (some-> (entities/code-point ref) legal-code-point?)))

(defn- illegal-reference?
  "Whether `s` can hold a character reference to a character that XML 1.0
  forbids, judged at each &# alone. One in CDATA, which isn't a reference,
  counts too, and only costs a closer look."
  [s]
  (let [n (count s)]
    (loop [from 0]
      (if-let [at (str/index-of s "&#" from)]
        (let [window  (subs s at (min n (+ at 32)))
              [_ ref] (re-find #"^&(#\d+|#[xX][0-9a-fA-F]+);" window)]
          (if (and ref (not (legal-reference? ref)))
            true
            (recur (+ at 2))))
        false))))

(defn strip-control-characters
  "The string `s` without the control characters that XML 1.0 forbids, and
  without the character references to them outside CDATA, which XML
  forbids too."
  [s]
  ;; a loop over the characters and a look at each &# come first, so that
  ;; only a document with something to remove pays for the patterns
  (let [s (if (xml/legal? s)
            s
            (str/replace s xml/illegal-characters ""))]
    (if (illegal-reference? s)
      (map-outside-cdata
       #(str/replace % #"&(#\d+|#[xX][0-9a-fA-F]+);"
                     (fn [[whole ref]]
                       (if (legal-reference? ref) whole "")))
       s)
      s)))

(defn replace-html-entities
  "The string `s` with its HTML entities outside CDATA replaced by numeric
  references, which XML understands. Unknown named entities become literal
  text."
  [s]
  (map-outside-cdata
   #(str/replace % #"&([A-Za-z][A-Za-z0-9]*);"
                 (fn [[whole name]]
                   (let [chars (entities/html-entity name)]
                     (cond
                       (entities/xml-entities name)
                       whole

                       chars
                       (entities/character-references chars)

                       :else
                       (str "&amp;" name ";")))))
   s))

(defn escape-bare-ampersands
  "The string `s` with every ampersand outside CDATA that doesn't start a
  reference escaped."
  [s]
  (map-outside-cdata
   #(str/replace %
                 #"&(?!(?:#\d+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);)"
                 "&amp;")
   s))

(defn- prefix-before?
  "Whether the colon at `i` of `s` ends a name that follows the start of a
  tag or whitespace, as a namespace prefix does. The colon of https: in a
  quoted value follows a quote."
  [^String s ^long i]
  (loop [j (dec i)]
    (if (neg? j)
      false
      (let [c #?(:clj (int (.charAt s j)) :cljs (.charCodeAt s j))]
        (if (or (and (<= 97 c) (<= c 122))
                (and (<= 65 c) (<= c 90))
                (and (<= 48 c) (<= c 57))
                (== c 95)
                (== c 46)
                (== c 45))
          (recur (dec j))
          (and (< j (dec i))
               (or (== c 60)
                   (== c 32)
                   (== c 9)
                   (== c 10)
                   (== c 13))))))))

;; One pass over the characters finds them, since the text is most of a
;; feed and few of its tags name a prefix
(defn- prefixed-tags
  "The start tags of `s` that name a namespace prefix."
  [^String s]
  ;; primitive locals throughout, -1 for no tag, and comparisons of two
  ;; arguments, which Clojure compiles inline, since this runs for every
  ;; character of the feed
  (let [n (count s)]
    (loop [i 0 start -1 prefixed? false acc (transient [])]
      (if (< i n)
        (let [c #?(:clj (int (.charAt s i)) :cljs (.charCodeAt s i))]
          (cond
            (== c 60)
            (let [j       (inc i)
                  letter? (and (< j n)
                               (let [d #?(:clj  (int (.charAt s j))
                                          :cljs (.charCodeAt s j))]
                                 (or (and (<= 65 d) (<= d 90))
                                     (and (<= 97 d) (<= d 122)))))
                  acc     (if prefixed? (conj! acc (subs s start i)) acc)]
              (recur j (if letter? i -1) false acc))

            (== c 62)
            (let [acc (if prefixed? (conj! acc (subs s start i)) acc)]
              (recur (inc i) -1 false acc))

            (and (== c 58)
                 (>= start 0)
                 (not prefixed?))
            (recur (inc i) start (prefix-before? s i) acc)

            :else
            (recur (inc i) start prefixed? acc)))
        (persistent! (if prefixed? (conj! acc (subs s start n)) acc))))))

(defn undeclared-prefixes
  "The namespace prefixes that the tags and attributes in `s` use without
  an xmlns: declaration, looked for in the start tags alone."
  [s]
  (let [tags     (prefixed-tags s)
        ;; a prefix of the tag name, or of an attribute name
        prefixed #"^<([A-Za-z][\w.-]*):|[ \t\r\n]([A-Za-z][\w.-]*):[\w.-]+="
        xmlns    #"[ \t\r\n]xmlns:([\w.-]+)="
        used     (into #{}
                       (comp (mapcat #(re-seq prefixed %))
                             (mapcat rest)
                             (remove nil?))
                       tags)
        declared (into #{"xml" "xmlns"}
                       (comp (mapcat #(re-seq xmlns %))
                             (map second))
                       tags)]
    (remove declared used)))

(defn declare-known-prefixes
  "The string `s` with every undeclared prefix that names one of the known
  namespaces declared on its root element."
  [s]
  (let [uri     #(spec/namespaces (keyword %))
        missing (filter uri (undeclared-prefixes s))
        open    (re-find #"<[A-Za-z][\w.-]*" s)
        at      (some->> open (str/index-of s))]
    (if (or (empty? missing) (nil? at))
      s
      (let [end   (+ at (count open))
            xmlns (for [p missing]
                    (str " xmlns:" p "=\"" (uri p) "\""))]
        (str (subs s 0 end)
             (apply str xmlns)
             (subs s end))))))

(def repairs
  "The text repairs, in the order that they're applied."
  [{:id :repair/leading-junk        :f strip-leading-junk}
   {:id :repair/trailing-junk       :f strip-trailing-junk}
   {:id :repair/control-characters  :f strip-control-characters}
   {:id :repair/undeclared-prefixes :f declare-known-prefixes}
   {:id :repair/html-entities       :f replace-html-entities}
   {:id :repair/bare-ampersands     :f escape-bare-ampersands}])

(defn repair
  "Apply every repair to the XML text `s`, except those whose ids the
  :except of `opts` names.

  It returns a map of the :xml and a :report, with a diagnostic for each
  repair that changed something."
  ([s]
   (repair s {}))
  ([s {:keys [except] :or {except #{}} :as opts}]
   {:pre [(map? opts)]}
   (reduce (fn [{:keys [xml] :as acc} {:keys [id f]}]
             (let [xml' (f xml)]
               (cond-> (assoc acc :xml xml')
                 (not= xml xml')
                 (update :report conj {:id id :level :warning}))))
           {:xml s :report []}
           (remove (comp except :id) repairs))))

(def ^:no-doc lenient-parser
  "The parser to fall back on when a document is still malformed after the
  repairs and the options give no :lenient-parser, or nil.

  On the JVM, it's that of jsoup when org.jsoup/jsoup is on the
  classpath, so adding jsoup to the dependencies is all it takes.
  ClojureScript has none."
  (delay
   #?(:clj  (try
              (requiring-resolve 'dk.simongray.qname-hiccup.jsoup/parse-raw)
              (catch Exception _ nil))
      :cljs nil)))

(defn- held
  "The `xml` in a form that can be read twice, first strictly and then for
  the repairs. On the JVM, an InputStream becomes bytes, and a Reader a
  string."
  [xml]
  #?(:clj  (cond
             (instance? java.io.InputStream xml)
             (.readAllBytes ^java.io.InputStream xml)

             (instance? java.io.Reader xml)
             (slurp xml)

             :else
             xml)
     :cljs xml))

(defn- strict
  "Parse the held `xml` strictly into a map with the :raw tree, or the
  :error when it's malformed. Bytes go to the parser as a stream, so that
  it honours the document's own encoding."
  [xml]
  (let [source #?(:clj  (if (bytes? xml)
                          (java.io.ByteArrayInputStream. ^bytes xml)
                          xml)
                  :cljs xml)]
    (try
      {:raw (xml/parse-raw source)}
      (catch #?(:clj Exception :cljs :default) e
        (if (= ::xml/malformed (:type (ex-data e)))
          {:error e}
          (throw e))))))

(defn- repaired
  "Repair the malformed held `xml` with `opts`, and parse it again into a
  map of the :raw tree and its :report.

  The parse is strict when the repairs were enough, and lenient when
  there's a lenient parser and :except in `opts` doesn't name
  :repair/lenient-parser. Otherwise, it throws the error of the strict
  parser."
  [xml {:keys [except] :as opts}]
  (let [text            #?(:clj  (if (bytes? xml) (xml/decode xml) xml)
                           :cljs xml)
        repaired        (repair text opts)
        report          (:report repaired)
        parsed          (strict (:xml repaired))
        parse-leniently (when-not (contains? except :repair/lenient-parser)
                          (or (:lenient-parser opts) @lenient-parser))]
    (cond
      (:raw parsed)
      {:raw (:raw parsed) :report report}

      parse-leniently
      {:raw    (parse-leniently (:xml repaired))
       :report (conj report {:id :repair/lenient-parser :level :warning})}

      :else
      (throw (:error parsed)))))

(defn parse-raw
  "Parse the XML document `xml` into its raw tree, and repair it first
  when it's malformed, except by the repairs that the :except of `opts`
  names. It returns a map of the :raw tree and the :report of the repairs.

  The `xml` is a string or, on the JVM, an InputStream or a Reader. The
  strict parse comes first, so a well-formed document has an empty
  report. When the repairs aren't enough, a lenient parser reads the
  document if there is one, and else the error of the strict parse is
  thrown.

  The lenient parser is the :lenient-parser of `opts`, or else jsoup on
  the JVM when it's on the classpath. ClojureScript has none of its own,
  so give it one there, e.g. over htmlparser2. It's a function of the
  XML text that returns its raw Hiccup, as qname-hiccup's parse-raw does:
  elements as vectors of the name as written, a map of the attributes,
  and the children, with the namespace declarations as attributes, e.g.

      [\"rss\" {\"xmlns:itunes\" \"http://www.itunes.com/dtds/podcast-1.0.dtd\"}
       [\"channel\" {} [\"itunes:author\" {} \"Ann\"]]]"
  ([xml]
   (parse-raw xml {}))
  ([xml opts]
   {:pre [(map? opts)]}
   (let [xml           (held xml)
         {:keys [raw]} (strict xml)]
     (if raw
       {:raw raw :report []}
       (repaired xml opts)))))

(comment
  (repair (str "﻿<rss><channel><title>A & B &nbsp;"
               "<itunes:author>x</itunes:author></title></channel></rss>"))
  (parse-raw "<rss><channel><title>A & B</title></channel></rss>")
  #_.)
