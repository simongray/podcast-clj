(ns dk.simongray.podcast.test-support
  "What the tests share: fixtures inlined from the classpath, a test of
  steps that give promises in ClojureScript, the instants and the two
  devices the sync tests put through a store, the bytes of files built by
  hand, and on the JVM a temporary directory, a server of a file on
  localhost and the feeds of the corpus."
  #?(:cljs (:require-macros [dk.simongray.podcast.test-support]))
  (:require #?(:clj [clojure.java.io :as io])
            #?(:clj [clojure.string :as str])
            #?(:cljs [clojure.test :refer [is async]])
            #?(:clj [dk.simongray.podcast :as podcast])
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.crdt :as crdt]
            #?(:clj [dk.simongray.podcast.util.json :as json])
            [dk.simongray.podcast.util.nostr :as nostr])
  #?(:clj (:import [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
                   [java.io File InputStream OutputStream]
                   [java.net InetAddress InetSocketAddress ServerSocket Socket]
                   [java.nio.file Files]
                   [java.nio.file.attribute FileAttribute]
                   [java.security MessageDigest]
                   [java.util Arrays Base64])))

#?(:clj
   (defmacro fixture
     "The classpath resource at `path`, a literal, inlined as a string when
     the test compiles, on both platforms."
     [path]
     (slurp (io/resource path))))

(defn run
  "Run the steps `fs` one after another as a test on either platform. Each
  step is a function of no arguments that gives a value or a promise.

  In ClojureScript, a deftest runs only the async test that it returns. So
  a deftest calls `run` once, as its last form, with all its steps."
  [& fs]
  #?(:clj  (doseq [f fs] (f))
     :cljs (async done
             (-> (reduce (fn [p f] (.then p (fn [_] (f)))) (js/Promise.resolve nil) fs)
                 (.catch (fn [e] (is false (str "rejected: " e))))
                 (.finally done)))))

(def t0
  "The instant the sync tests start from; `t1` to `t4` follow an hour apart."
  #inst "2026-01-01T00:00:00Z")

(def t1
  "An hour after `t0`."
  #inst "2026-01-01T01:00:00Z")

(def t2
  "Two hours after `t0`."
  #inst "2026-01-01T02:00:00Z")

(def t3
  "Three hours after `t0`."
  #inst "2026-01-01T03:00:00Z")

(def t4
  "Four hours after `t0`."
  #inst "2026-01-01T04:00:00Z")

(def a
  "The id of an episode: the first of feed-a."
  ["feed-a" "a1"])

(defn phone-state
  "The state of a phone that named itself and subscribed to feed-a at `t0`,
  and was 100 seconds into `a` at `t1`."
  []
  (-> (profile/make "phone")
      (crdt/identify {:name "Phone"} t0)
      (profile/subscribe "feed-a" {:url "https://a/rss" :title "A"} t0)
      (profile/progress a 100 3600 t1)))

(defn laptop-state
  "The state of a laptop that subscribed to feed-b at `t1` and was 900
  seconds into `a` at `t2`, further than the phone."
  []
  (-> (profile/make "laptop")
      (profile/subscribe "feed-b" {:url "https://b/rss" :title "B"} t1)
      (profile/progress a 900 3600 t2)))

(defn same?
  "Whether two states agree on all the listener sees: everything but whose
  they are, the registry, whose entries reach the other devices a round
  later, and the clock that follows it."
  [x y]
  (= (dissoc x :device :devices :clock) (dissoc y :device :devices :clock)))

(defn be
  "The `n` bytes of the number `x`, big-endian."
  [n x]
  (for [i (range (dec n) -1 -1)]
    (mod (quot x (long (Math/pow 256 i))) 256)))

(defn le
  "The `n` bytes of the number `x`, little-endian."
  [n x]
  (reverse (be n x)))

(defn code
  "The code of the character `c`, on both platforms."
  [c]
  #?(:clj (int c) :cljs (.charCodeAt c 0)))

(defn ascii
  "The bytes of `s`, which holds only ASCII."
  [s]
  (map code s))

(defn secret-key
  "A secret key of a test, which is the number `n` from 1 to 255."
  [n]
  (bytes/hex (bytes/of (concat (repeat 31 0) [n]))))

(defn signed
  "The Nostr event of `kind` with `content` and `tags`, which the owner of
  the secret key `secret` wrote at `at`, a time in seconds. It has the id
  and the signature of its content."
  [secret {:keys [kind content tags at] :or {kind 1 content "" tags []}}]
  (nostr/signed {"created_at" at "kind" kind "tags" (vec tags) "content" content}
                secret
                (bytes/hex (bytes/of (repeat 32 7)))))

(defn invoice
  "A BOLT 11 invoice for 50 sats whose payment hash is the SHA-256 of
  `preimage`, hexadecimal text of 32 bytes. Its data are the time, the
  payment hash, and a signature of zeros, so no wallet pays it."
  [preimage]
  (let [hash  (bytes/sha256 (bytes/from-hex preimage))
        bits  (concat (for [i (range 32), j [7 6 5 4 3 2 1 0]]
                        (bit-and 1 (bit-shift-right (bytes/at hash i) j)))
                      [0 0 0 0])
        words (map #(reduce (fn [acc bit] (+ (* 2 acc) bit)) 0 %) (partition 5 bits))]
    (bech32/of-words "lnbc500n" (concat [1 12 18 31 28 25 2] [1 1 20] words (repeat 104 0)))))

(defn view
  "A view of all of the bytes `xs`."
  [xs]
  (let [b (bytes/of xs)]
    (bytes/add (bytes/view (bytes/length b)) 0 b (bytes/length b))))

(defn fetch-from
  "A fetch over the bytes `b`, as `dk.simongray.podcast.util.bytes/read!` takes
  it, noting each range in `log`."
  [b log]
  (fn [start end]
    (swap! log conj [start end])
    (let [end (min end (bytes/length b))]
      {:bytes (bytes/copy b start end) :start start :total (bytes/length b)})))

#?(:clj
   (defn with-temp-dir
     "Call `f` with a fresh temporary directory, a `java.io.File`, and delete
     it with everything in it afterwards."
     [f]
     (let [dir (.toFile (Files/createTempDirectory "podcast-test" (make-array FileAttribute 0)))]
       (try
         (f dir)
         (finally
           (doseq [file (reverse (file-seq dir))]
             (io/delete-file file true)))))))

#?(:clj
   (defn- answer
     "What the server of `with-server` answers the request for the file
     `file` with `method` and the Range header value `range`: `[status
     bytes headers]`."
     [{:keys [body headers status ranges? head?] :or {status 200 ranges? true head? true} :as file} method range]
     (let [^bytes b (if (string? body) (.getBytes ^String body "UTF-8") (or body (byte-array 0)))
           total    (alength b)
           [_ s e]  (when (and range ranges? (= 200 status) (= "GET" method))
                      (re-matches #"bytes=(\d+)-(\d*)" range))]
       (cond
         (nil? file)
         [404 (byte-array 0) {}]

         (and (= "HEAD" method) (not head?))
         [405 (byte-array 0) {}]

         (and s (>= (parse-long s) total))
         [416 (byte-array 0) (assoc headers "Content-Range" (str "bytes */" total))]

         s
         (let [start (parse-long s)
               end   (min (dec total) (if (seq e) (parse-long e) (dec total)))]
           [206 (Arrays/copyOfRange b (int start) (int (inc end)))
            (assoc headers "Content-Range" (str "bytes " start "-" end "/" total))])

         :else
         [status b headers]))))

#?(:clj
   (defn with-server
     "Call `f` with the URL of a server on localhost that serves `files`, a
     map of paths to `{:body :headers :status :ranges? :head?}`: the bytes or
     the string of the body, the headers of the answer, its status (default
     200), whether a Range request gets the bytes it names, or 416 for a
     range past the end, as RFC 9110 has it (default true), and whether HEAD
     is answered (default true). A path the map lacks is answered 404.
     `log`, when given, is called with each request as `{:method :path
     :range :origin :authorization}`."
     ([files f]
      (with-server files nil f))
     ([files log f]
      (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
        (.createContext server "/"
                        (reify HttpHandler
                          (handle [_ exchange]
                            (let [^HttpExchange exchange exchange
                                  method   (.getRequestMethod exchange)
                                  path     (.getPath (.getRequestURI exchange))
                                  request  (.getRequestHeaders exchange)
                                  range    (.getFirst request "Range")
                                  _        (when log
                                             (log {:method method :path path :range range
                                                   :origin (.getFirst request "Origin")
                                                   :authorization (.getFirst request "Authorization")}))
                                  [status ^bytes part headers] (answer (get files path) method range)
                                  n        (alength part)]
                              (doseq [[k v] headers]
                                (.add (.getResponseHeaders exchange) (str k) (str v)))
                              ;; the JDK server leaves out the length of an answer to HEAD
                              ;; unless it is set by hand, and takes 0 to mean chunked
                              (if (or (= "HEAD" method) (zero? n))
                                (do (.add (.getResponseHeaders exchange) "Content-Length" (str n))
                                    ;; with the request body open, Java 11 closes the connection
                                    ;; after an answer with no body, and the client reuses it
                                    (.close (.getRequestBody exchange))
                                    (.sendResponseHeaders exchange status -1))
                                (do (.sendResponseHeaders exchange status n)
                                    (with-open [out (.getResponseBody exchange)] (.write out part))))
                              (.close exchange)))))
        (.start server)
        (try
          (f (str "http://127.0.0.1:" (.getPort (.getAddress server))))
          (finally
            (.stop server 0)))))))

#?(:clj
   (defn with-file-server
     "Call `f` with the URL of the bytes `b` as a file on a server on
     localhost, as `with-server` serves it, which answers a Range request
     with those bytes unless `:ranges?` in `opts` is false; `:log`, an atom,
     gets the Range header of each request, nil for none."
     [b {:keys [ranges? log] :or {ranges? true}} f]
     (with-server {"/episode" {:body b :ranges? ranges?}}
                  (when log #(swap! log conj (:range %)))
                  #(f (str % "/episode")))))

#?(:clj
   (defn- frame
     "The frame of a WebSocket for the text `s`, as a server sends it."
     ^bytes [^String s]
     (let [payload (.getBytes s "UTF-8")
           n       (alength payload)
           header  (cond
                     (< n 126)   [0x81 n]
                     (< n 65536) [0x81 126 (quot n 256) (mod n 256)]
                     :else       (into [0x81 127] (map #(mod (quot n (long (Math/pow 256 %))) 256) (range 7 -1 -1))))]
       (byte-array (concat (map unchecked-byte header) payload)))))

#?(:clj
   (defn- read-frame
     "The next frame of the client on `in`, as `[opcode text]`. Nil at the
     end of the stream."
     [^InputStream in]
     (let [b0 (.read in)
           b1 (.read in)]
       (when-not (or (neg? b0) (neg? b1))
         (let [short (bit-and b1 0x7f)
               n     (case short
                       126 (+ (* 256 (.read in)) (.read in))
                       127 (reduce (fn [acc _] (+ (* 256 acc) (.read in))) 0 (range 8))
                       short)
               mask  (vec (repeatedly 4 #(.read in)))
               data  (.readNBytes in (int n))]
           [(bit-and b0 0x0f)
            (String. (byte-array (map-indexed (fn [i b] (unchecked-byte (bit-xor (bit-and b 0xff) (mask (mod i 4))))) data))
                     "UTF-8")])))))

#?(:clj
   (defn- handshake
     "Reads the request of a client on `in`, and answers on `out` as a
     server of WebSockets answers, by RFC 6455."
     [^InputStream in ^OutputStream out]
     (let [request (loop [text ""]
                     (if (str/ends-with? text "\r\n\r\n")
                       text
                       (recur (str text (char (.read in))))))
           key     (second (re-find #"(?i)sec-websocket-key: *(\S+)" request))
           accept  (.encodeToString (Base64/getEncoder)
                                    (.digest (MessageDigest/getInstance "SHA-1")
                                             (.getBytes (str key "258EAFA5-E914-47DA-95CA-C5AB0DC85B11") "UTF-8")))]
       (.write out (.getBytes (str "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                                   "Sec-WebSocket-Accept: " accept "\r\n\r\n")
                              "UTF-8"))
       (.flush out))))

#?(:clj
   (defn with-relay
     "Call `f` with the URL of a relay of Nostr on localhost. The relay
     answers each REQ with the messages that `(answer filters)` gives, and
     each EVENT with those that `(answer event)` gives. `log`, an atom,
     gets each message of the client."
     [answer log f]
     (let [server (ServerSocket. 0 5 (InetAddress/getByName "127.0.0.1"))
           serve  (fn [^Socket socket]
                    (let [in  (.getInputStream socket)
                          out (.getOutputStream socket)]
                      (handshake in out)
                      (loop []
                        (when-let [[opcode text] (read-frame in)]
                          (when (= 1 opcode)
                            (swap! log conj (json/read-str text))
                            (let [[kind x & filters] (json/read-str text)]
                              (doseq [message (case kind
                                                "REQ"   (answer (vec filters))
                                                "EVENT" (answer x)
                                                [])]
                                (.write out (frame message)))
                              (.flush out))
                            (recur))))))
           served (future
                    (while (not (.isClosed server))
                      ;; a client that has enough leaves while the relay still sends
                      (try
                        (with-open [socket (.accept server)]
                          (serve socket))
                        (catch Exception _
                          nil))))]
       (try
         (f (str "ws://127.0.0.1:" (.getLocalPort server)))
         (finally
           (.close server)
           (future-cancel served))))))

#?(:clj
   (def corpus-feeds
     "The feeds of dev-resources/corpus, which is not in git: `{:name :feed}`,
     the file name and the parsed feed, for every .xml file directly in the
     directory, in name order. Empty when the corpus is absent."
     (delay (->> (.listFiles (io/file "dev-resources/corpus"))
                 (filter #(and (.isFile ^File %) (.endsWith (.getName ^File %) ".xml")))
                 (sort-by #(.getName ^File %))
                 (mapv (fn [^File file] {:name (.getName file) :feed (podcast/parse (slurp file))}))))))
