(ns dk.simongray.podcast.nostr
  "Nostr, as far as a podcast app reads it. The namespace has events and
  their signatures, the names that a person reads, the filters and the
  messages of a relay, and one exchange with a relay.

  An event is the JSON object of NIP-01 as plain data with string keys, as
  a relay gives it. `verified?` tells whether an event has the id and the
  signature of its content. An app shows no event that is not verified,
  because a relay can send anything.

  `decode` and `encode` read and write the names of NIP-19, such as
  npub1…, note1… and nevent1…, and the nostr: URIs of NIP-21. podcast-clj
  reads no secret key, so nsec1… is no name here. `parts` gives the
  content of an event as text, mentions, references, links and hashtags.

  A relay speaks over a WebSocket. `call!` opens one, asks for the events
  of some filters, reads them until the relay has no more, and closes it.
  `received` is the pure step of that reading, for an app that holds its
  own connections. `relays` names the relays that podcast-clj asks when
  nothing names another relay."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.bech32 :as bech32]
            [dk.simongray.podcast.bytes :as bytes]
            [dk.simongray.podcast.http :as http]
            [dk.simongray.podcast.json :as json]
            [dk.simongray.podcast.secp256k1 :as secp256k1])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient WebSocket WebSocket$Listener]
                   [java.time Duration]
                   [java.util.concurrent TimeUnit])))

(def relays
  "The relays that podcast-clj asks when the options of a function name
  no `:relays`. doc/services.md tells when each relay answered."
  ["wss://relay.fountain.fm" "wss://relay.damus.io" "wss://relay.primal.net"])

(def default-limits
  "The limits of `call!` that its request does not set. `:max-events` is
  the most events of one answer, and `:max-bytes` is the most characters
  that the relay sends."
  {:timeout-seconds 30
   :max-events      500
   :max-bytes       (* 5 1024 1024)})

(def max-name-length
  "The most characters of a name that `decode` reads, which is the limit
  that NIP-19 gives."
  5000)

(def ^:no-doc subscription
  "The id of the one subscription of an exchange of `call!`."
  "podcast-clj")

(defn ^:no-doc hex-id?
  "Whether `x` is the text of 32 bytes in lower-case hexadecimal, as an
  id and a public key of NIP-01 are."
  [x]
  (boolean (and (string? x) (re-matches #"[0-9a-f]{64}" x))))

(defn event?
  "Whether `x` has the form of an event of NIP-01. Such an event has an
  id, a public key, a time, a kind, tags, a content and a signature, each
  of its type."
  [x]
  (boolean
   (and (map? x)
        (hex-id? (get x "id"))
        (hex-id? (get x "pubkey"))
        (when-let [at (json/whole (get x "created_at"))] (<= 0 at))
        (when-let [kind (json/whole (get x "kind"))] (<= 0 kind 65535))
        (string? (get x "content"))
        (string? (get x "sig"))
        (re-matches #"[0-9a-f]{128}" (get x "sig"))
        (vector? (get x "tags"))
        (every? #(and (vector? %) (seq %) (every? string? %)) (get x "tags")))))

(defn- quoted
  "The text `s` as a JSON string with the escapes that NIP-01 names, and
  no other escape."
  [s]
  (str "\""
       (str/escape s {\newline   "\\n"
                      \"         "\\\""
                      \\         "\\\\"
                      \return    "\\r"
                      \tab       "\\t"
                      \backspace "\\b"
                      \formfeed  "\\f"})
       "\""))

(defn ^:no-doc serialized
  "The text whose SHA-256 is the id of `event`, as the section Events and
  signatures of NIP-01 defines it."
  [{:strs [pubkey created_at kind tags content]}]
  (str "[0," (quoted pubkey)
       "," (json/whole created_at)
       "," (json/whole kind)
       ",[" (str/join "," (map #(str "[" (str/join "," (map quoted %)) "]") tags)) "]"
       "," (quoted content)
       "]"))

(defn event-id
  "The id that NIP-01 gives `event`: the SHA-256 of its public key, its
  time, its kind, its tags and its content, in hexadecimal."
  [event]
  (bytes/hex (bytes/sha256 (bytes/encode (serialized event)))))

(defn verified?
  "Whether `event` is an event whose id is the id of its content, and
  whose signature is that of the owner of its public key.

  NIP-01 defines the id, and BIP-340 defines the signature."
  [event]
  (boolean (and (event? event)
                (= (get event "id") (event-id event))
                (secp256k1/verify (get event "pubkey") (get event "id") (get event "sig")))))

(defn tag-values
  "The values of the tags of `event` with the name `k`: the second part of
  each such tag, in the order of the tags."
  [event k]
  (for [[tag value] (get event "tags")
        :when (and (= k tag) (some? value))]
    value))

(defn matches?
  "Whether `event` passes the filter `filter` of NIP-01, a map with keys
  such as \"ids\", \"authors\", \"kinds\", \"#e\", \"since\" and \"until\".

  An attribute that NIP-01 does not define takes each event, and so does
  \"limit\", which counts events."
  [filter event]
  (let [{:strs [id pubkey kind created_at]} event]
    (every? (fn [[k v]]
              (cond
                (= "ids" k)                (contains? (set v) id)
                (= "authors" k)            (contains? (set v) pubkey)
                (= "kinds" k)              (contains? (set v) kind)
                (= "since" k)              (<= v created_at)
                (= "until" k)              (<= created_at v)
                (re-matches #"#[a-zA-Z]" k) (boolean (some (set v) (tag-values event (subs k 1))))
                :else                      true))
            filter)))

(def ^:no-doc name-types
  "The prefix of each name of NIP-19 that podcast-clj reads, with the type
  of the name."
  {"npub" :npub "note" :note "nprofile" :nprofile "nevent" :nevent "naddr" :naddr})

(defn- fields
  "The fields of the bytes `b` of a name with extra data, as `[type
  bytes]` in their order: a byte for the type, a byte for the length, and
  the value. Nil when a field ends after the bytes."
  [b]
  (loop [i 0 out []]
    (cond
      (= i (bytes/length b))       out
      (> (+ i 2) (bytes/length b)) nil
      :else
      (let [end (+ i 2 (long (bytes/at b (inc i))))]
        (when (<= end (bytes/length b))
          (recur end (conj out [(bytes/at b i) (bytes/copy b (+ i 2) end)])))))))

(defn- text-of
  "The text of the bytes `b`, as UTF-8."
  [b]
  (bytes/decode b 0 (bytes/length b) :utf8))

(defn- with-fields
  "The name of the type `type` whose data are the fields `fs`, as `decode`
  gives it. Nil when the field that names the thing has a wrong size."
  [type fs]
  (let [of-type (fn [t] (map second (filter #(= t (first %)) fs)))
        key-of  (fn [b] (when (and b (= 32 (bytes/length b))) (bytes/hex b)))
        special (first (of-type 0))
        author  (key-of (first (of-type 2)))
        kind    (when-let [b (first (of-type 3))]
                  (when (= 4 (bytes/length b))
                    (reduce (fn [acc i] (+ (* 256 acc) (bytes/at b i))) 0 (range 4))))
        extras  (cond-> {:relays (mapv text-of (of-type 1))}
                  author (assoc :author author)
                  kind   (assoc :kind kind))]
    (case type
      :nprofile (when-let [k (key-of special)] {:type type :public-key k :relays (:relays extras)})
      :nevent   (when-let [id (key-of special)] (assoc extras :type type :id id))
      :naddr    (when (and special author kind) (assoc extras :type type :identifier (text-of special))))))

(defn decode
  "What the name `s` of NIP-19 names, as a map with its `:type`. `s` is a
  name such as npub1…, or a nostr: URI of NIP-21 with such a name. Nil
  for another text, and for the name of a secret key.

      {:type :npub :public-key \"3bf0…\"}
      {:type :note :id \"5c83…\"}
      {:type :nprofile :public-key \"3bf0…\" :relays [\"wss://r.x.com\"]}
      {:type :nevent :id \"5c83…\" :relays […] :author \"3bf0…\" :kind 1}
      {:type :naddr :identifier \"…\" :relays […] :author \"3bf0…\" :kind 30023}

  The ids and the keys are in hexadecimal, as the events have them. A
  field that NIP-19 does not define is left out, as NIP-19 asks."
  [s]
  (when (string? s)
    (let [text (str/replace (str/trim s) #"(?i)^nostr:" "")]
      (when-let [{:keys [prefix] b :bytes} (bech32/decode text {:max-length max-name-length})]
        (when-let [type (name-types prefix)]
          (case type
            :npub (when (= 32 (bytes/length b)) {:type type :public-key (bytes/hex b)})
            :note (when (= 32 (bytes/length b)) {:type type :id (bytes/hex b)})
            (some->> (fields b) (with-fields type))))))))

(defn- field
  "The bytes of a field of the type `t` with the value `b`, bytes of 255
  or fewer."
  [t b]
  (bytes/of (concat [t (bytes/length b)] (map #(bytes/at b %) (range (bytes/length b))))))

(defn- joined
  "The bytes of each of `bs`, one after another."
  [bs]
  (bytes/of (mapcat (fn [b] (map #(bytes/at b %) (range (bytes/length b)))) bs)))

(defn encode
  "The name of NIP-19 for `x`, a map of the form that `decode` gives. Nil
  for a map that names no thing of its type.

  A relay with more than 255 bytes in its URL is left out."
  [{:keys [type id public-key identifier author kind relays]}]
  (let [short? #(<= (bytes/length %) 255)
        extras (concat (for [b (map bytes/encode relays) :when (short? b)] (field 1 b))
                       (when (hex-id? author) [(field 2 (bytes/from-hex author))])
                       (when (and (integer? kind) (<= 0 kind 0xffffffff))
                         [(field 3 (bytes/of (map #(bit-and (quot kind %) 255) [16777216 65536 256 1])))]))
        named  (fn [prefix special] (bech32/encode prefix (joined (cons (field 0 special) extras))))]
    (case type
      :npub     (when (hex-id? public-key) (bech32/encode "npub" (bytes/from-hex public-key)))
      :note     (when (hex-id? id) (bech32/encode "note" (bytes/from-hex id)))
      :nprofile (when (hex-id? public-key) (named "nprofile" (bytes/from-hex public-key)))
      :nevent   (when (hex-id? id) (named "nevent" (bytes/from-hex id)))
      :naddr    (when (and (string? identifier) (hex-id? author) (integer? kind)
                           (short? (bytes/encode identifier)))
                  (named "naddr" (bytes/encode identifier)))
      nil)))

(defn uri
  "The nostr: URI of NIP-21 for `x`, a map of the form that `decode`
  gives. Nil when `encode` gives no name."
  [x]
  (some->> (encode x) (str "nostr:")))

(def ^:no-doc part-pattern
  "What `parts` finds in the content of an event: a nostr: URI with a
  name, a web URL, and a word after a number sign."
  #"(?u)nostr:(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]+|https?://[^\s<>]+|#[\p{L}\p{N}_]+")

(defn- part
  "The part that the match `s` of `part-pattern` gives, in an event with
  the hashtags `hashtags`, in lower case. Nil for a match that is text.
  The URL of a link is without the signs that end a sentence after it."
  [s hashtags]
  (let [named (when (str/starts-with? s "nostr:") (decode s))]
    (cond
      (contains? #{:npub :nprofile} (:type named))
      (assoc (select-keys named [:public-key :relays]) :type :mention :uri s)

      named
      {:type :reference :target named :uri s}

      (re-find #"^https?://" s)
      {:type :link :url (str/replace s #"[.,;:!?)\]'\"]+$" "")}

      (contains? hashtags (str/lower-case (subs s 1)))
      {:type :hashtag :tag (subs s 1)})))

(defn parts
  "The parts of the content of `event`, in their order. The content of a
  note is plain text, and some of its words name other things. Each part
  is a map with its `:type`:

      {:type :text :text \"Is this \"}
      {:type :mention :public-key \"5f7a…\" :uri \"nostr:npub1…\"}
      {:type :reference :target {:type :nevent :id \"…\" :kind 1} :uri \"nostr:nevent1…\"}
      {:type :link :url \"https://example.org/\"}
      {:type :hashtag :tag \"podcast\"}

  A mention is a nostr: URI with the name of a profile, and a reference is
  one with the name of an event, as NIP-27 defines. The `:target` of a
  reference is what `decode` gives for the name. A hashtag is a word after
  a number sign that the event names in a t tag, as NIP-24 defines. A
  mention of a profile can have `:relays`."
  [event]
  (let [content  (if (string? (get event "content")) (get event "content") "")
        hashtags (set (map str/lower-case (filter string? (tag-values event "t"))))
        ;; the text from `from` to `to` goes into `out`, unless it is empty
        with-text (fn [out from to]
                    (cond-> out
                      (< from to) (conj {:type :text :text (subs content from to)})))]
    ;; `at` is where the next match starts or later, and `from` is where
    ;; the text starts that no part has yet
    (loop [found (re-seq part-pattern content) at 0 from 0 out []]
      (if-let [[match & more] (seq found)]
        (let [i (str/index-of content match at)
              p (part match hashtags)]
          (if p
            (recur more
                   (+ i (count match))
                   (+ i (count (:url p match)))
                   (conj (with-text out from i) p))
            (recur more (+ i (count match)) from out)))
        (with-text out from (count content))))))

(defn relay-url
  "The URL `s` of a relay as podcast-clj knows a relay: a ws or wss URL
  with a host, without white space around it and without a slash at its
  end. Nil for another text."
  [s]
  (when (string? s)
    (let [url (str/replace (str/trim s) #"/+$" "")]
      (when (re-matches #"(?i)wss?://[^\s/?#]+(?:[/?#]\S*)?" url)
        (str/replace url #"(?i)^(wss?)://([^/?#]+)" (fn [[_ scheme host]] (str (str/lower-case scheme) "://" (str/lower-case host))))))))

(defn public?
  "Whether the host of the relay at `url` is on the public internet, as
  `http/public?` tells for a web URL."
  [url]
  (boolean (some-> (relay-url url) (str/replace #"^ws" "http") http/public?)))

(defn request-message
  "The message that asks a relay for the events of `filters`, under the
  subscription `id`: the REQ of NIP-01, as JSON."
  [id filters]
  (json/write-str (into ["REQ" id] filters)))

(defn close-message
  "The message that ends the subscription `id`: the CLOSE of NIP-01."
  [id]
  (json/write-str ["CLOSE" id]))

(defn read-message
  "The message `text` of a relay, as a map with its `:type`. Nil for a
  text that is no message of a relay.

      {:type :event :subscription \"…\" :event {…}}
      {:type :eose :subscription \"…\"}             ; the relay has no more
      {:type :closed :subscription \"…\" :message \"…\"}
      {:type :notice :message \"…\"}
      {:type :ok :id \"…\" :accepted? true :message \"…\"}
      {:type :auth :challenge \"…\"}                 ; NIP-42

  NIP-01 defines the first five, in its section From relay to client."
  [text]
  (let [message (when (string? text) (json/parse text))]
    (when (vector? message)
      (let [[kind a b c] message]
        (case kind
          "EVENT"  (when (and (string? a) (map? b)) {:type :event :subscription a :event b})
          "EOSE"   (when (string? a) {:type :eose :subscription a})
          "CLOSED" (when (string? a) {:type :closed :subscription a :message (str b)})
          "NOTICE" {:type :notice :message (str a)}
          "OK"     (when (string? a) {:type :ok :id a :accepted? (true? b) :message (str c)})
          "AUTH"   (when (string? a) {:type :auth :challenge a})
          nil)))))

(defn received
  "The answer `answer` of a relay with the message `text` read into it,
  within the limits `limits`. An exchange starts with an empty map.

      {:events [{…}] :notices [\"…\"] :complete? true :done? true}

  The answer is `:done?` when the exchange ends. It ends when the relay
  has no more events, which is `:complete?`. It ends when the relay
  closes the subscription, and `:closed` is then the reason of the relay.
  It also ends at a limit, and `:truncated` then names the limit. `:auth?`
  tells that the relay asks who the reader is, as NIP-42 defines.

  The events are those of the subscription `subscription`, as the relay
  gives them. They are not verified."
  [answer text {:keys [max-events max-bytes]}]
  (let [counted (update answer :bytes (fnil + 0) (count text))
        message (read-message text)
        ours?   (= subscription (:subscription message))
        answer  (if (:done? answer) answer counted)]
    (cond
      (:done? answer)
      answer

      (and max-bytes (> (:bytes answer) max-bytes))
      (assoc answer :done? true :truncated :max-bytes)

      (and ours? (= :event (:type message)))
      (let [answer (update answer :events (fnil conj []) (:event message))]
        (cond-> answer
          (and max-events (>= (count (:events answer)) max-events)) (assoc :done? true :truncated :max-events)))

      (and ours? (= :eose (:type message)))
      (assoc answer :done? true :complete? true)

      (and ours? (= :closed (:type message)))
      (assoc answer :done? true :closed (:message message))

      (= :notice (:type message))
      (update answer :notices (fnil conj []) (:message message))

      (= :auth (:type message))
      (assoc answer :auth? true)

      :else
      answer)))

(defn ^:no-doc response
  "The response of `call!` for the answer `answer` of the relay at `url`."
  [url answer]
  (merge {:url url :events []} (dissoc answer :bytes :done?)))

(defn- failure
  "The exception of an exchange with the relay at `url` that failed for
  the reason `message`, with the cause `cause`."
  [url message cause]
  (ex-info (str message ": " url) {:type ::failed :url url} cause))

#?(:clj
   (defn- listener
     "The listener of a WebSocket that reads the messages of a relay into
     the atom `answer` within `limits`, and gives the answer to the promise
     `done` when the exchange ends."
     [answer done {:keys [max-bytes] :as limits}]
     (let [partial (StringBuilder.)]
       (reify WebSocket$Listener
         (onOpen [_ socket]
           (.request ^WebSocket socket 1))
         (onText [_ socket data last?]
           (.append partial ^CharSequence data)
           (when (and max-bytes (> (.length partial) (long max-bytes)))
             (deliver done (swap! answer assoc :done? true :truncated :max-bytes)))
           (when last?
             (let [text (str partial)]
               (.setLength partial 0)
               (when (:done? (swap! answer received text limits))
                 (deliver done @answer))))
           (.request ^WebSocket socket 1)
           nil)
         (onClose [_ _ _ _]
           (deliver done @answer)
           nil)
         (onError [_ _ e]
           (deliver done (if (seq (:events @answer)) @answer {:error e})))))))

#?(:clj
   (defn- hang-up
     "Ends the subscription of `socket` and closes it. A relay that does
     not take the two messages in two seconds is left."
     [^WebSocket socket]
     (try
       (when-not (.isOutputClosed socket)
         (.get (.sendText socket (close-message subscription) true) 2 TimeUnit/SECONDS)
         (.get (.sendClose socket WebSocket/NORMAL_CLOSURE "") 2 TimeUnit/SECONDS))
       (catch Exception _
         nil)
       (finally
         (.abort socket)))))

#?(:clj
   (defn- exchange
     "The answer of the relay at `url` to the `filters`, within `limits`,
     over a WebSocket of the JDK."
     [url filters {:keys [timeout-seconds] :as limits}]
     (let [answer (atom {})
           done   (promise)
           socket (try
                    (-> (.newWebSocketBuilder ^HttpClient @http/client)
                        (.connectTimeout (Duration/ofSeconds timeout-seconds))
                        (.header "User-Agent" http/user-agent)
                        (.buildAsync (URI. url) (listener answer done limits))
                        (.get timeout-seconds TimeUnit/SECONDS))
                    (catch Exception e
                      (throw (failure url "The relay gave no connection" e))))]
       (try
         (.get (.sendText ^WebSocket socket (request-message subscription filters) true)
               timeout-seconds TimeUnit/SECONDS)
         (let [result (deref done (* 1000 timeout-seconds) ::timeout)]
           (cond
             (= ::timeout result) (assoc @answer :timeout? true)
             (:error result)      (throw (failure url "The connection to the relay failed" (:error result)))
             :else                result))
         (finally
           (hang-up socket))))))

#?(:cljs
   (defn- exchange
     "A promise of the answer of the relay at `url` to the `filters`,
     within `limits`, over the WebSocket of the platform."
     [url filters {:keys [timeout-seconds] :as limits}]
     (js/Promise.
      (fn [resolve reject]
        (let [answer (atom {})
              socket (js/WebSocket. url)
              timer  (atom nil)
              end    (fn [f x]
                       (js/clearTimeout @timer)
                       (try
                         (when (= 1 (.-readyState socket))
                           (.send socket (close-message subscription)))
                         (.close socket)
                         (catch :default _ nil))
                       (f x))]
          (reset! timer (js/setTimeout #(end resolve (assoc @answer :timeout? true)) (* 1000 timeout-seconds)))
          (set! (.-onopen socket) #(.send socket (request-message subscription filters)))
          (set! (.-onmessage socket)
                (fn [e]
                  (when (:done? (swap! answer received (str (.-data e)) limits))
                    (end resolve @answer))))
          (set! (.-onclose socket) #(end resolve @answer))
          (set! (.-onerror socket)
                (fn [e]
                  (if (seq (:events @answer))
                    (end resolve @answer)
                    (end reject (failure url "The connection to the relay failed" (.-error e)))))))))))

(defn call!
  "Ask the relay of the request `request` for the events of its filters,
  and give what the relay answers. On the JVM it gives the response, and
  in ClojureScript a promise of it.

      {:url     \"wss://relay.example\"
       :filters [{\"kinds\" [1111] \"#I\" [\"podcast:item:guid:…\"] \"limit\" 100}]}

  The response has the `:url` and the `:events`, with the other keys of
  `received`. A relay that does not answer in time gives the events that
  came, and `:timeout? true`. The exchange is one subscription: `call!`
  opens a WebSocket, sends the REQ of NIP-01, reads until the relay sends
  EOSE, and closes.

  The request can give `:timeout-seconds`, `:max-events` and `:max-bytes`
  in the place of `default-limits`. `:allow?` is a predicate of the URL of
  a relay, as `public?` is. `call!` throws `::refused` for a URL that it
  refuses or that is no URL of a relay, and `::failed` when the relay
  gives no connection.

  The events are not verified. `verified?` and `matches?` tell which of
  them to keep."
  [{:keys [url filters allow?] :or {allow? (constantly true)} :as request}]
  (let [relay  (relay-url url)
        limits (merge default-limits (select-keys request (keys default-limits)))]
    (if-not (and relay (allow? relay))
      (let [refused (ex-info (str "The request to " url " is not allowed") {:type ::refused :url url})]
        #?(:clj (throw refused) :cljs (js/Promise.reject refused)))
      #?(:clj  (response relay (exchange relay filters limits))
         :cljs (.then (exchange relay filters limits) #(response relay %))))))

(comment
  (def note
    {:type :nevent
     :id   "5c83da77af1dec6d7289834998ad7aafbd9e2191396d75ec3cc27f5a77226f36"
     :relays ["wss://relay.example"]})

  (encode note)
  (uri note)
  (decode (uri note))
  (decode "npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6")

  ;; the filter of NIP-22 for the comments on an episode, by its guid
  (def about-episode
    {"kinds" [1111] "#I" ["podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"] "limit" 100})

  (request-message subscription [about-episode])

  ;; the pure step of an exchange
  (-> {}
      (received "[\"NOTICE\",\"hello\"]" default-limits)
      (received "[\"EOSE\",\"podcast-clj\"]" default-limits))
  #_.)

#?(:clj
   (comment
     (call! {:url (first relays) :filters [about-episode] :timeout-seconds 10})
     (map verified? (:events *1))
     #_.))
