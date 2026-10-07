(ns dk.simongray.podcast.util.url
  "URLs, and the hosts that they name, the same way on both platforms.

  This covers percent-encoding and query strings, a URL made absolute
  against the document that it's in, as a browser does it, and the origin
  of a URL. It also tells whether the host of a URL is on the public
  internet, which is what a request that a stranger chose must check.
  The client that sends requests is dk.simongray.podcast.util.http."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.util.secret :as secret])
  #?(:clj (:import [java.net IDN InetAddress URI]
                   [java.nio.charset StandardCharsets])))

(defn percent-encode
  "The string `s` percent-encoded for a query string, the same way on both
  platforms. Spaces become %20, not +, and letters, digits and the marks
  - _ . ! ~ * ' ( ) stay as they are."
  [s]
  #?(:clj  (-> (java.net.URLEncoder/encode (str s) "UTF-8")
               (str/replace "+" "%20")
               (str/replace "%21" "!")
               (str/replace "%27" "'")
               (str/replace "%28" "(")
               (str/replace "%29" ")")
               (str/replace "%7E" "~"))
     :cljs (js/encodeURIComponent (str s))))

(defn percent-decode
  "The string `s` with its percent-encoding undone, the inverse of
  percent-encode.
  A plus sign stays a plus sign, and a string with an escape that doesn't
  decode comes back as it is, rather than throwing."
  [s]
  #?(:clj  (try
             (java.net.URLDecoder/decode (str/replace (str s) "+" "%2B")
                                         "UTF-8")
             (catch IllegalArgumentException _
               (str s)))
     :cljs (try
             (js/decodeURIComponent (str s))
             (catch :default _
               (str s)))))

(defn query-string
  "The query string for `params`, with keys and values URL-encoded and nil
  values left out. A true value becomes the bare flag that some APIs take."
  [params]
  (str/join "&" (for [[k v] params
                      :when (some? v)]
                  (if (true? v)
                    (percent-encode (name k))
                    (str (percent-encode (name k)) "=" (percent-encode v))))))

(defn ^:no-doc query-params
  "The parameters of the query string `s`, decoded, by name. A plus sign
  is a space, as in the body of a form."
  [s]
  (let [decode #(percent-decode (str/replace (str %) "+" " "))]
    (into {} (for [part  (str/split (str s) #"&")
                   :let  [[k v] (map decode (str/split part #"=" 2))]
                   :when (seq k)]
               [k v]))))

(defn with-query
  "The `url` with `params` added to its query, as query-string writes
  them. The parameters go after those that the URL has, and before its
  fragment."
  [url params]
  (let [[_ base fragment] (re-matches #"(?s)([^#]*)(#.*)?" (str url))
        query             (query-string params)
        joint             (cond
                            (not (str/includes? base "?")) "?"
                            (re-find #"[?&]$" base)        ""
                            :else                          "&")]
    (if (str/blank? query)
      (str url)
      (str base joint query fragment))))

(defn split-credentials
  "The `url` without the user name and the password that it holds, and
  those as credentials for authorization, as a pair. The credentials are
  nil when the URL holds none.

  A URL such as https://user:pass@host/feed.xml holds them. Use this when
  you add a feed, to keep the password apart from the address, since an
  address travels into OPML, sync documents and messages.

  The password is a hidden text of util.secret, which the printer doesn't
  show. The secret/reveal function gives its text."
  [url]
  (let [[_ scheme authority tail]
        (re-matches #"(?s)([a-zA-Z][a-zA-Z0-9+.-]*://)([^/?#]*)(.*)"
                    (str url))

        at (some-> authority (str/last-index-of "@"))]
    (if at
      (let [[user password] (str/split (subs authority 0 at) #":" 2)]
        [(str scheme (subs authority (inc at)) tail)
         (when-not (str/blank? user)
           {:username (percent-decode user)
            :password (secret/hide (percent-decode (or password "")))})])
      [url nil])))

(def ^:no-doc legal-url-characters
  "The ASCII characters that a URI may contain as they are, and the
  percent sign of an escape. They're the unreserved and reserved sets of
  RFC 3986, sections 2.3 and 2.2."
  (set (str "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
            ":/?#[]@!$&'()*+,;="
            "%")))

#?(:clj
   (defn- encode-illegal
     "The part `s` of a URL with each character that a URI may not contain
     percent-encoded as UTF-8, and the characters of the set `also` too. A
     percent sign that starts no escape is encoded as well."
     [^String s also]
     (let [sb     (StringBuilder.)
           bytes  (.getBytes s StandardCharsets/UTF_8)
           n      (alength bytes)
           digit? (fn [i]
                    (and (< i n)
                         (let [b (aget bytes i)]
                           (or (<= 48 b 57)
                               (<= 65 b 70)
                               (<= 97 b 102)))))]
       (dotimes [i n]
         (let [b (bit-and (aget bytes i) 0xFF)
               c (char b)]
           (cond
             (and (= \% c)
                  (not (and (digit? (inc i)) (digit? (+ i 2)))))
             (.append sb "%25")

             (and (< b 128)
                  (legal-url-characters c)
                  (not (also c)))
             (.append sb c)

             :else
             (.append sb (format "%%%02X" b)))))
       (str sb))))

#?(:clj
   (defn- ascii-host
     "The scheme and the authority `head` of a URL, with its host in the
     ASCII form of IDNA, so that java.net.URI reads the host."
     [head]
     (let [[_ before host after]
           (re-matches #"(?s)(.*?://(?:[^@]*@)?)([^:\[\]]*)(.*)" head)]
       (if (and host
                (re-find #"[^\x00-\x7F]" host))
         (let [ascii (try
                       (IDN/toASCII host IDN/ALLOW_UNASSIGNED)
                       (catch Exception _ host))]
           (str before ascii after))
         head))))

#?(:clj
   (defn legal-url
     "The `url` with each character that a URI can't contain
     percent-encoded as UTF-8, e.g. a space or a letter outside ASCII.

     A bracket in the path is encoded too, because java.net.URI takes none
     there, and so is a percent sign that starts no escape. An escape stays
     as it is, so an encoded URL comes back unchanged. A host with letters
     outside ASCII gets the ASCII form of IDNA."
     [^String url]
     (let [[_ head path tail]
           (re-matches #"(?s)([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*)?([^?#]*)(.*)"
                       url)]
       (str (encode-illegal (ascii-host (str head)) #{})
            (encode-illegal path #{\[ \]})
            (encode-illegal tail #{})))))

#?(:clj
   (defn- stripped
     "The URL `s` as a browser takes it: without the tabs and line breaks in
     it, and without the spaces and control characters around it."
     [s]
     (let [^String s (str/replace (str s) #"[\t\n\r]" "")
           n         (.length s)
           space?    (fn [i] (<= (int (.charAt s (int i))) 32))
           start     (loop [i 0]
                       (if (and (< i n) (space? i))
                         (recur (inc i))
                         i))
           end       (loop [i n]
                       (if (and (< start i) (space? (dec i)))
                         (recur (dec i))
                         i))]
       (subs s start end))))

#?(:clj
   (defn- resolved
     "The legal URL `url` resolved against the URI `base` by java.net.URI,
     with a base with no path read as one with the path /, and without the
     dot segments that are left at the root, as browsers do."
     [^URI base url]
     (-> (if (and (.getRawAuthority base) (str/blank? (.getRawPath base)))
           (URI. (str (.getScheme base) "://" (.getRawAuthority base) "/"))
           base)
         (.resolve (URI. url))
         .normalize
         str
         (str/replace
          #"^([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*)(?:/\.\.)+(?:/|(?=[?#])|$)"
          "$1/"))))

(defn absolute-url
  "The `url` made absolute against `base`, the URL of the document that
  it's in, as a browser does it. Nil when one of them doesn't read as a
  URL.

  A path, a path from the root, a query alone or a URL without a scheme
  resolves against the base. An absolute URL stays as it is.

  The base is a URL with a scheme that can hold a path, so not a mailto
  URL. An empty `url` names the document itself. A path doesn't go above
  the root, so ../x in a document at the root is /x."
  [base url]
  (when (and base url)
    #?(:clj  (try
               (let [base     (legal-url (stripped base))
                     url      (legal-url (stripped url))
                     b        (URI. base)
                     document (first (str/split base #"#" 2))
                     resource (first (str/split base #"[?#]" 2))]
                 (when (and (.isAbsolute b)
                            (not (.isOpaque b)))
                   ;; java.net.URI resolves by RFC 2396, which differs from
                   ;; browsers on a query or a fragment alone, on no
                   ;; reference, on a base with no path, and on the dot
                   ;; segments that are left at the root
                   (cond
                     (= "" url)                 document
                     (str/starts-with? url "#") (str document url)
                     (str/starts-with? url "?") (str resource url)
                     :else                      (resolved b url))))
               (catch Exception _
                 nil))
       :cljs (try
               (.-href (js/URL. (str url) (str base)))
               (catch :default _
                 nil)))))

(def ^:no-doc default-ports
  "The port of each scheme of the web, for a URL that names none."
  {"http" 80 "https" 443})

#?(:clj
   (defn- host-and-port
     "The host and the port of the `authority` of a URL, as origin reads
     them, or nil when it holds no host that a URL can have. The host is in
     lower case and in the ASCII form of IDNA, and the port is the text of
     the URL."
     [authority]
     (let [;; the user name and the password end at the last @
           at            (or (str/last-index-of authority "@") -1)
           host-port     #"(\[[0-9a-fA-F:.]*\]|[^:\[\]]*)(?::([0-9]*))?"
           [_ host port] (re-matches host-port (subs authority (inc at)))
           host          (when (seq host)
                           (try
                             (str/lower-case (IDN/toASCII (percent-decode host)
                                                          IDN/ALLOW_UNASSIGNED))
                             (catch Exception _
                               nil)))]
       (when (and (seq host)
                  (not (re-find #"[\x00-\x20\x7F\"#%/<>?@\\^`{|}]" host)))
         [host port]))))

(defn ^:no-doc origin
  "The scheme, host and port of the http or https URL `url`, as the URL
  standard and browsers read them. Nil for another URL, and for a URL
  without a host.

  The host is in lower case, and a name with letters outside ASCII is in
  the ASCII form of IDNA. When the URL names no port, the port is the one
  of the scheme."
  [url]
  #?(:clj  (when-let [[_ scheme authority]
                      (re-find #"(?i)^[\x00-\x20]*(https?):[/\\]*([^/\\?#]*)"
                               (str/replace (str url) #"[\t\n\r]" ""))]
             (when-let [[host port] (host-and-port authority)]
               (let [scheme (str/lower-case scheme)]
                 [scheme host (if (seq port)
                                (parse-long port)
                                (default-ports scheme))])))
     :cljs (try
             (let [u      (js/URL. url)
                   scheme (str/replace (.-protocol u) #":$" "")
                   port   (.-port u)]
               (when (and (#{"http" "https"} scheme)
                          (seq (.-hostname u)))
                 [scheme (.-hostname u) (if (seq port)
                                          (parse-long port)
                                          (default-ports scheme))]))
             (catch :default _
               nil))))

(defn ^:no-doc host
  "The host of the http or https URL `url`, as origin reads it."
  [url]
  (second (origin url)))

(defn ^:no-doc web-url?
  "Whether `s` is an http or https URL with a host."
  [s]
  (boolean (and (string? s)
                (re-find #"(?i)^https?://" s)
                (origin s))))

(defn ^:no-doc same-origin?
  "Whether the URLs `a` and `b` have the same scheme, host and port, as
  origin reads them. False when a URL has no host."
  [a b]
  (let [o (origin a)]
    (and (some? o) (= o (origin b)))))

;; IANA IPv4 Special-Purpose Address Registry (RFC 6890), as of 2025-10-09:
;; the blocks that are not globally reachable, and multicast
(defn ^:no-doc public-ipv4?
  "Whether the IPv4 `address`, a vector of four numbers, is on the public
  internet, by the IANA registry of special addresses.

  It isn't when it's in this network, private, shared, loopback,
  link-local, multicast or reserved, or kept for protocols, tests and
  documentation."
  [[a b c _ :as address]]
  (not (or (= 0 a)
           (= 10 a)
           (and (= 100 a) (<= 64 b 127))
           (= 127 a)
           (and (= 169 a) (= 254 b))
           (and (= 172 a) (<= 16 b 31))
           (and (= 192 a) (= 0 b) (contains? #{0 2} c))
           (and (= 192 a) (= 88 b) (= 99 c))
           (and (= 192 a) (= 168 b))
           (and (= 198 a) (contains? #{18 19} b))
           (and (= 198 a) (= 51 b) (= 100 c))
           (and (= 203 a) (= 0 b) (= 113 c))
           (<= 224 a))))

;; IANA IPv6 Special-Purpose Address Registry (RFC 6890), as of 2025-10-09:
;; the blocks that are not globally reachable, and multicast
(defn ^:no-doc public-ipv6?
  "Whether the IPv6 address of the sixteen numbers `b` is on the public
  internet, by the IANA registry of special addresses.

  It isn't when it's unspecified, loopback, unique local, link-local,
  site-local or multicast, or kept for documentation, benchmarks,
  discard, segment routing or Teredo. Nor is an address that holds an
  IPv4 address that isn't public."
  [b]
  (let [word     #(+ (* 256 (nth b %)) (nth b (inc %)))
        ipv4-at  #(public-ipv4? (subvec b % (+ % 4)))
        zeros?   #(every? zero? (subvec b 0 %))
        nat64?   (and (= 0x64 (word 0)) (= 0xff9b (word 2)))]
    (cond
      (zeros? 15)                                               false
      (zeros? 12)                                               (ipv4-at 12)
      (and (zeros? 10) (= 0xffff (word 10)))                    (ipv4-at 12)
      ;; 64:ff9b::/96 holds an IPv4 address, 64:ff9b:1::/48 is for local use
      (and nat64? (= 0 (word 4) (word 6) (word 8) (word 10)))   (ipv4-at 12)
      nat64?                                                    false
      (= 0x2002 (word 0))                                       (ipv4-at 2)
      (and (= 0x2001 (word 0)) (= 0 (word 2)))                  false
      (and (= 0x2001 (word 0)) (= 2 (word 2)) (= 0 (word 4)))   false
      (and (= 0x2001 (word 0)) (= 0xdb8 (word 2)))              false
      (and (= 0x3fff (word 0)) (< (nth b 2) 0x10))              false
      (= 0x5f00 (word 0))                                       false
      (and (= 0x100 (word 0)) (= 0 (word 2) (word 4))
           (<= (word 6) 1))                                     false
      (= 0xfc (bit-and 0xfe (nth b 0)))                         false
      (and (= 0xfe (nth b 0)) (<= 0x80 (nth b 1)))              false
      (= 0xff (nth b 0))                                        false
      :else                                                     true)))

(defn ^:no-doc public-ip?
  "Whether the IP address of the numbers `b`, four of IPv4 or sixteen of
  IPv6, is on the public internet, as public-ipv4? and public-ipv6? tell."
  [b]
  (if (= 4 (count b))
    (public-ipv4? b)
    (public-ipv6? b)))

#?(:clj
   (defn- public-address?
     "Whether the IP address `a` is on the public internet, both by the
     kinds of address of the JDK and by the registries that public-ipv4?
     and public-ipv6? check."
     [^InetAddress a]
     (let [b (mapv #(bit-and % 0xff) (.getAddress a))]
       (and (not (or (.isAnyLocalAddress a)
                     (.isLoopbackAddress a)
                     (.isLinkLocalAddress a)
                     (.isSiteLocalAddress a)
                     (.isMulticastAddress a)))
            (public-ip? b)))))

(defn ^:no-doc ipv4
  "The four numbers of the IPv4 address that the text `s` writes, or nil
  for anything else."
  [s]
  (when (re-matches #"\d{1,3}(?:\.\d{1,3}){3}" s)
    (let [ns (mapv parse-long (str/split s #"\."))]
      (when (every? #(<= 0 % 255) ns)
        ns))))

(defn- words
  "The 16-bit words of the groups of `part`, a part of the text of an IPv6
  address, or nil when a group doesn't read. When `end?`, the part ends
  the address, and its last group can be an IPv4 address."
  [part end?]
  (let [gs (if (= "" part) [] (str/split part #":" -1))]
    (reduce (fn [acc [i g]]
              (cond
                (re-matches #"[0-9a-fA-F]{1,4}" g)
                (conj acc #?(:clj  (Long/parseLong g 16)
                             :cljs (js/parseInt g 16)))

                (and end?
                     (= i (dec (count gs)))
                     (str/includes? g "."))
                (if-let [[a b c d] (ipv4 g)]
                  (conj acc (+ (* 256 a) b) (+ (* 256 c) d))
                  (reduced nil))

                :else
                (reduced nil)))
            []
            (map-indexed vector gs))))

(defn- ipv6
  "The sixteen numbers of the IPv6 address that the text `s` writes,
  without brackets, or nil for anything else."
  [s]
  (let [[head tail] (str/split s #"::" 2)
        front       (words head (nil? tail))
        back        (when tail (words tail true))
        n           (+ (count front) (count back))
        all         (cond
                      (or (nil? front) (and tail (nil? back)))
                      nil

                      tail
                      (when (< n 8)
                        (concat front (repeat (- 8 n) 0) back))

                      :else
                      (when (= n 8) front))]
    (when all
      (vec (mapcat (fn [w] [(quot w 256) (rem w 256)]) all)))))

(defn ^:no-doc ip-address
  "The bytes of the IP address that the text `s` writes, as numbers, or
  nil for anything else, e.g. a name or a hash.

  An IPv4 address has four, and an IPv6 address sixteen. The text of an
  IPv6 address can have the brackets that a URL puts around it."
  [s]
  (when (string? s)
    (or (ipv4 s)
        (let [pattern #"\[([0-9a-fA-F:.]+)\]|([0-9a-fA-F.]*:[0-9a-fA-F:.]*)"]
          (when-let [[_ bracketed bare] (re-matches pattern s)]
            (ipv6 (or bracketed bare)))))))

;; RFC 6761, section 6.3 (localhost); RFC 6762, section 3 (.local);
;; RFC 8375 (.home.arpa)
(defn ^:no-doc public-name?
  "Whether the domain name `host` is a name of the public internet.

  Such a name has a dot in it, not counting a dot at its end. It also
  doesn't stay on a local network, as localhost, .local, .internal and
  .home.arpa do."
  [host]
  (let [h (str/replace (str/lower-case (str host)) #"\.$" "")]
    (and (str/includes? h ".")
         (not (re-find #"(^|\.)(localhost|local|internal|home\.arpa)$" h)))))

(defn public?
  "Whether the host of the http or https URL `url` is on the public
  internet. A host on the network of the app or of its device isn't.

  On the JVM, each address that the name of the host resolves to must be
  public, and a name that doesn't resolve isn't public. The JVM caches a
  resolved name for 30 seconds by default, so the request goes to the
  address that this function checked.

  In a browser, ClojureScript can't resolve a name. There, the host
  mustn't be an address that isn't public. A name must have a dot in it,
  and it mustn't be localhost or a name under .localhost, .local,
  .internal or .home.arpa."
  [url]
  #?(:clj  (try
             ;; the host as the client of the JDK reads it, which sends the
             ;; request
             (let [h (some-> url str legal-url URI. .getHost)]
               (boolean (and h
                             (every? public-address?
                                     (InetAddress/getAllByName h)))))
             (catch Exception _
               false))
     :cljs (let [h (some-> (host url) (str/replace #"\.$" ""))]
             (boolean (and h
                           (if-let [b (ip-address h)]
                             (public-ip? b)
                             (public-name? h)))))))

(comment
  (query-string {:q "clojure podcasts" :max 10 :pretty true})
  (split-credentials "https://ann:secret@example.org/feed.xml")
  (absolute-url "https://example.org/shows/feed.xml" "../art/cover.png")
  (origin "HTTPS://Example.org/feed.xml")
  (public? "http://192.168.1.10/feed.xml")
  #_.)
