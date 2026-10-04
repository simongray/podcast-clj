(ns dk.simongray.podcast.util.nostr
  "The Nostr protocol, which the comments of comments.nostr and the
  wallets of value.nwc speak. Nothing here is about podcasts.

  An event is the JSON object of NIP-01 as plain data with string keys,
  as a relay gives it. The verified? function tells whether an event has
  the id and the signature of its content. Show no event that isn't
  verified, since a relay can send anything.

  The decode and encode functions read and write the names of NIP-19,
  e.g. npub1…, note1… and nevent1…, and the nostr: URIs of NIP-21. Secret
  keys aren't read, so nsec1… isn't a name here. The parts function
  splits the content of an event into text, mentions, references, links
  and hashtags.

  A relay speaks over a WebSocket. The send! function opens one, asks for
  the events of some filters, reads them until the relay has no more, and
  closes it. The received function is the pure step of that reading, for
  when you hold your own connections."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.crypto.secp256k1 :as secp256k1]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.http :as http]
            [dk.simongray.podcast.util.json :as json])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient WebSocket WebSocket$Listener]
                   [java.time Duration]
                   [java.util.concurrent TimeUnit])))

(def default-limits
  "The limits of send! that its request doesn't set. One answer has at
  most :max-events events, and the relay sends at most :max-bytes
  characters."
  {:timeout-seconds 30
   :max-events      500
   :max-bytes       (* 5 1024 1024)})

(def max-name-length
  "The most characters of a name that decode reads, the limit of NIP-19."
  5000)

(def ^:no-doc subscription
  "The id of the one subscription of an exchange of send!."
  "podcast-clj")

(defn ^:no-doc hex-id?
  "Whether `x` is the text of 32 bytes in lower-case hexadecimal, as an
  id and a public key of NIP-01 are."
  [x]
  (boolean (and (string? x) (re-matches #"[0-9a-f]{64}" x))))

(defn event?
  "Whether `x` has the form of an event of NIP-01, with each of its
  fields of the right type."
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
  [{:strs [pubkey created_at kind tags content] :as event}]
  (str "[0," (quoted pubkey)
       "," (json/whole created_at)
       "," (json/whole kind)
       ",[" (str/join "," (map #(str "[" (str/join "," (map quoted %)) "]") tags)) "]"
       "," (quoted content)
       "]"))

(defn event-id
  "The id that NIP-01 gives `event`: the SHA-256 of its fields, in
  hexadecimal."
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

(defn signed
  "The `event` signed with `secret-key` and `aux`: with the public key of
  the owner, the id of NIP-01 and the signature of BIP-340. Nil when
  `secret-key` isn't a secret key.

      (signed {\"kind\" 1 \"created_at\" 1790000000 \"tags\" [] \"content\" \"Hi\"}
              secret-key
              (bytes/hex (bytes/generate! 32)))

  The `event` has its kind and its time, in seconds. Its tags and its
  content are empty when it has none. The `aux` is 32 bytes in
  hexadecimal that nobody can guess, new for each signature, as in the
  example.

  How long it takes depends on the secret key, as for secp256k1/sign."
  [event secret-key aux]
  (when-let [public (secp256k1/public-key secret-key)]
    (let [event (merge {"tags" [] "content" ""} event {"pubkey" public})
          id    (event-id event)]
      (assoc event "id" id "sig" (secp256k1/sign secret-key id aux)))))

(defn tag-values
  "The values of the tags of `event` with the name `k`: the second part of
  each such tag, in the order of the tags."
  [event k]
  (for [[tag value] (get event "tags")
        :when (and (= k tag) (some? value))]
    value))

(defn matches?
  "Whether `event` passes the `filter` of NIP-01, a map with keys such as
  \"ids\", \"kinds\" and \"#e\".

  A key that NIP-01 doesn't define lets every event pass, and so does
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

;; NIP-19, part "Shareable identifiers with extra metadata": the types 0
;; (the thing), 1 (a relay), 2 (the author) and 3 (the kind)
(defn- fields
  "The fields in the bytes `b` of a name with extra data, in order, each
  as a pair of its type and its value. Nil when a field ends after the
  bytes.

  A field is a byte for the type, a byte for the length, and the value."
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
  "The name of the type `type` whose data are the fields `fs`, as decode
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
  "What the name `s` names, as a map with its :type, or nil for another
  text and for the name of a secret key. The name is one of NIP-19, e.g.
  npub1…, or a nostr: URI of NIP-21 with such a name.

      {:type :npub :public-key \"3bf0…\"}
      {:type :note :id \"5c83…\"}
      {:type :nprofile :public-key \"3bf0…\" :relays [\"wss://r.x.com\"]}
      {:type :nevent :id \"5c83…\" :relays […] :author \"3bf0…\" :kind 1}
      {:type :naddr :identifier \"…\" :relays […] :author \"3bf0…\" :kind 30023}

  The ids and the keys are in hexadecimal, as the events have them. A
  field that NIP-19 doesn't define is left out, as NIP-19 asks."
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
  "The bytes of a field of the type `t` with the value `b`, which has 255
  bytes or fewer."
  [t b]
  (bytes/of (concat [t (bytes/length b)] (map #(bytes/at b %) (range (bytes/length b))))))

(defn encode
  "The name of NIP-19 for `x`, a map of the form that decode gives, or nil
  when `x` names nothing of its type.

  A relay with more than 255 bytes in its URL is left out."
  [{:keys [type id public-key identifier author kind relays] :as x}]
  (let [short? #(<= (bytes/length %) 255)
        extras (concat (for [b (map bytes/encode relays) :when (short? b)] (field 1 b))
                       (when (hex-id? author) [(field 2 (bytes/from-hex author))])
                       (when (and (integer? kind) (<= 0 kind 0xffffffff))
                         [(field 3 (bytes/of (map #(bit-and (quot kind %) 255) [16777216 65536 256 1])))]))
        named  (fn [prefix special] (bech32/encode prefix (bytes/join (cons (field 0 special) extras))))]
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
  "The nostr: URI of NIP-21 for `x`, a map of the form that decode gives,
  or nil when encode gives no name."
  [x]
  (some->> (encode x) (str "nostr:")))

(def ^:no-doc part-pattern
  "What parts finds in the content of an event: a nostr: URI with a name,
  a web URL, and a word after a number sign."
  #"(?u)nostr:(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]+|https?://[^\s<>]+|#[\p{L}\p{N}_]+")

(defn- part
  "The part that the match `s` of part-pattern gives, in an event whose
  hashtags in lower case are `hashtags`, or nil for a match that is text.

  The URL of a link is without the punctuation that ends a sentence after
  it."
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
  is a map with its :type:

      {:type :text :text \"Is this \"}
      {:type :mention :public-key \"5f7a…\" :uri \"nostr:npub1…\"}
      {:type   :reference
       :target {:type :nevent :id \"…\" :kind 1}
       :uri    \"nostr:nevent1…\"}
      {:type :link :url \"https://example.org/\"}
      {:type :hashtag :tag \"podcast\"}

  A mention is a nostr: URI with the name of a profile, and a reference is
  one with the name of an event, as NIP-27 defines. The :target of a
  reference is what decode gives for the name. A hashtag is a word after
  a number sign that the event names in a t tag, as NIP-24 defines. A
  mention of a profile can have :relays."
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
        (let [i (long (str/index-of content match at))
              p (part match hashtags)]
          (if p
            (recur more
                   (+ i (count match))
                   (+ i (count (:url p match)))
                   (conj (with-text out from i) p))
            (recur more (+ i (count match)) from out)))
        (with-text out from (count content))))))

(defn relay-url
  "The URL `s` of a relay in a normal form, or nil when it isn't a ws or
  wss URL with a host. The form is trimmed, with the scheme and the host
  in lower case, and without a slash at the end."
  [s]
  (when (string? s)
    (let [url (str/replace (str/trim s) #"/+$" "")]
      (when (re-matches #"(?i)wss?://[^\s/?#]+(?:[/?#]\S*)?" url)
        (str/replace url #"(?i)^(wss?)://([^/?#]+)" (fn [[_ scheme host]] (str (str/lower-case scheme) "://" (str/lower-case host))))))))

(defn public?
  "Whether the host of the relay at `url` is on the public internet, as
  http/public? tells for a web URL."
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

(defn event-message
  "The message that gives a relay the `event`: the EVENT of NIP-01, as
  JSON. The signed function gives an event its id and its signature."
  [event]
  (json/write-str ["EVENT" event]))

(defn read-message
  "The message `text` of a relay, as a map with its :type, or nil when it
  isn't a message of a relay.

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
  "The `answer` of a relay with the message `text` read into it, within
  the `limits`. An exchange starts with an empty map.

      {:events [{…}] :notices [\"…\"] :complete? true :done? true}

  The answer is :done? when the exchange ends. It ends in these cases:

  - The relay has no more events, and the answer is :complete?.
  - The relay closes the subscription, and :closed is its reason.
  - A limit is reached, and :truncated names it.

  The answer has :auth? when the relay asks who the reader is, as NIP-42
  defines.

  An exchange that gives the relay an event has the id of the event as
  :published in `limits`, and :until says when it ends:

  - With :until :ok, it ends when the relay takes the event, and the
    answer is :accepted?.
  - With :until :event, it ends at the first event that comes, and not
    when the relay has no more events.

  In both cases, it ends when the relay refuses the event, and :refused
  is the reason of the relay. If you hold your own connection, give the
  relay the event when the answer is :complete?, since the relay has the
  subscription then.

  The events are only those of the subscription of send!, as the relay
  gives them, and they aren't verified."
  [answer text {:keys [max-events max-bytes until published] :as limits}]
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
          (= :event until) (assoc :done? true)
          (and max-events (>= (count (:events answer)) max-events)) (assoc :done? true :truncated :max-events)))

      (and ours? (= :eose (:type message)))
      (cond-> (assoc answer :complete? true)
        (not (contains? #{:event :ok} until)) (assoc :done? true))

      (and ours? (= :closed (:type message)))
      (assoc answer :done? true :closed (:message message))

      (and published (= :ok (:type message)) (= published (:id message)))
      (cond
        (not (:accepted? message)) (assoc answer :done? true :refused (:message message))
        (= :ok until)              (assoc answer :done? true :accepted? true)
        :else                      (assoc answer :accepted? true))

      (= :notice (:type message))
      (update answer :notices (fnil conj []) (:message message))

      (= :auth (:type message))
      (assoc answer :auth? true)

      :else
      answer)))

(defn ^:no-doc response
  "The response of send! for the `answer` of the relay at `url`."
  [url answer]
  (merge {:url url :events []} (dissoc answer :bytes :done?)))

(defn- failure
  "The exception of an exchange with the relay at `url` that failed for
  the reason `message`, with the `cause` and the `answer` so far.

  When the event went out before the failure, the data of the exception
  has :sent? true."
  [url message cause answer]
  (ex-info (str message ": " url)
           (cond-> {:type ::failed :url url}
             (:sent? answer) (assoc :sent? true))
           cause))

(defn- due?
  "Whether the event of an exchange goes out now, by its `answer`. It does
  when the relay has the subscription, the event hasn't gone out, and the
  exchange goes on."
  [{:keys [complete? sent? done?] :as answer}]
  (boolean (and complete? (not sent?) (not done?))))

#?(:clj
   (defn- listener
     "The listener of a WebSocket that reads the messages of a relay into
     the atom `answer` within `limits`, with the promises `ready` and
     `done`.

     It delivers :ready to `ready` when the event of the exchange is due,
     and the answer to `done` when the exchange ends.

     The thread of exchange sends the event. A send from the listener
     fails while the send of the first message is pending, and the JDK
     clears that only after the message went out."
     [answer ready done {:keys [max-bytes] :as limits}]
     (let [partial (StringBuilder.)
           end     (fn [result]
                     (deliver ready :done)
                     (deliver done result))]
       (reify WebSocket$Listener
         (onOpen [_ socket]
           (.request ^WebSocket socket 1))
         (onText [_ socket data last?]
           (.append partial ^CharSequence data)
           (when (and max-bytes (> (.length partial) (long max-bytes)))
             (end (swap! answer assoc :done? true :truncated :max-bytes)))
           (when last?
             (let [text (str partial)]
               (.setLength partial 0)
               (let [read (swap! answer received text limits)]
                 (when (due? read)
                   (deliver ready :ready))
                 (when (:done? read)
                   (end @answer)))))
           (.request ^WebSocket socket 1)
           nil)
         (onClose [_ _ _ _]
           (end @answer)
           nil)
         (onError [_ _ e]
           (end (if (seq (:events @answer)) @answer {:error e})))))))

#?(:clj
   (defn- hang-up
     "End the subscription of `socket` and close it, waiting at most two
     seconds for the relay to take each of the two messages."
     [^WebSocket socket]
     (try
       (when-not (.isOutputClosed socket)
         (.get (.sendText socket (close-message subscription) true) 2 TimeUnit/SECONDS)
         (.get (.sendClose socket WebSocket/NORMAL_CLOSURE "") 2 TimeUnit/SECONDS))
       (catch Exception _
         nil)
       (finally
         (.abort socket)))))

(defn- opening
  "The messages that an exchange for `filters` and the `event` sends, as
  the :first and the :later message, one of which can be absent.

  With filters, the event goes out later, when the relay has the
  subscription. So the answer to the event finds the subscription, and an
  event doesn't go to a relay that refuses the subscription. Without
  them, the event is the first message, and the map has :sent? true."
  [filters event]
  (cond
    (and (seq filters) event) {:first (request-message subscription filters) :later (event-message event)}
    event                     {:first (event-message event) :sent? true}
    :else                     {:first (request-message subscription filters)}))

#?(:clj
   (defn- exchange
     "The answer of the relay at `url` to the `messages` of opening, within
     `limits`, over a WebSocket of the JDK."
     [url messages {:keys [timeout-seconds] :as limits}]
     (let [answer   (atom {})
           ready    (promise)
           done     (promise)
           socket   (try
                      (-> (.newWebSocketBuilder ^HttpClient @http/client)
                          (.connectTimeout (Duration/ofSeconds timeout-seconds))
                          (.header "User-Agent" http/user-agent)
                          (.buildAsync (URI. url) (listener answer ready done limits))
                          (.get timeout-seconds TimeUnit/SECONDS))
                      (catch Exception e
                        (throw (failure url "The relay gave no connection" e {}))))
           deadline (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
           left     #(max 0 (- deadline (System/currentTimeMillis)))
           write!   (fn [text]
                      (.get (.sendText ^WebSocket socket text true) (left) TimeUnit/MILLISECONDS))]
       (try
         (when (:sent? messages)
           (swap! answer assoc :sent? true))
         (write! (:first messages))
         (when (and (:later messages) (= :ready (deref ready (left) ::timeout)))
           ;; noted before it goes out: an event that failed on its way
           ;; counts as one that went out
           (swap! answer assoc :sent? true)
           (write! (:later messages)))
         (let [result (deref done (left) ::timeout)]
           (cond
             (= ::timeout result) (assoc @answer :timeout? true)
             (:error result)      (throw (failure url "The connection to the relay failed" (:error result) @answer))
             :else                result))
         (catch java.util.concurrent.ExecutionException e
           (throw (failure url "The connection to the relay failed" (ex-cause e) @answer)))
         (catch java.util.concurrent.TimeoutException e
           (throw (failure url "The relay took no message in time" e @answer)))
         (finally
           (hang-up socket))))))

#?(:cljs
   (defn- exchange
     "A promise of the answer of the relay at `url` to the `messages` of
     opening, within `limits`, over the WebSocket of the platform."
     [url messages {:keys [timeout-seconds] :as limits}]
     (js/Promise.
      (fn [resolve reject]
        (let [answer (atom (if (:sent? messages) {:sent? true} {}))
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
          (set! (.-onopen socket) #(.send socket (:first messages)))
          (set! (.-onmessage socket)
                (fn [e]
                  (let [read (swap! answer received (str (.-data e)) limits)]
                    (when (and (:later messages) (due? read))
                      ;; noted before it goes out: an event that failed on
                      ;; its way counts as one that went out
                      (swap! answer assoc :sent? true)
                      (.send socket (:later messages)))
                    (when (:done? read)
                      (end resolve @answer)))))
          (set! (.-onclose socket) #(end resolve @answer))
          (set! (.-onerror socket)
                (fn [e]
                  (if (seq (:events @answer))
                    (end resolve @answer)
                    (end reject (failure url "The connection to the relay failed" (.-error e) @answer))))))))))

(defn send!
  "Ask the relay of the `request` for the events of its filters, and give
  what it answers. On the JVM it gives the response, and in ClojureScript
  a promise of it.

      {:url     \"wss://relay.example\"
       :filters [{\"kinds\" [1111] \"#I\" [\"podcast:item:guid:…\"] \"limit\" 100}]}

  The exchange is one subscription. It opens a WebSocket, sends the REQ
  of NIP-01, reads until the relay sends EOSE, and closes. The response
  has the :url and the :events, with the other keys of received. A relay
  that doesn't answer in time gives the events that came, with :timeout?
  true.

  The `request` has these keys:

  - :url, the URL of the relay
  - :filters, the filters of NIP-01
  - :event, an event that signed wrote, to give the relay
  - :timeout-seconds, :max-events and :max-bytes, which default to those
    of default-limits
  - :allow?, a predicate of the URL of the relay, as public? is

  It throws ::refused for a URL that :allow? refuses or that isn't the URL
  of a relay, and ::failed when the relay gives no connection.

  With an :event, the request gives the relay that event as the EVENT of
  NIP-01. The filters are then for the answer to the event, and the
  exchange ends at the first event of the filters that comes. The event
  goes out when the relay has the subscription, which the relay tells
  with EOSE, so the event doesn't go to a relay that refuses the
  subscription. Without filters, the event goes out at once, and the
  exchange ends when the relay takes it.

  The response has :sent? true when the event went out. It has :accepted?
  true when the relay took the event, and :refused with the reason of the
  relay when it didn't. An exception of ::failed has :sent? true in its
  data when the event went out before the failure. Without :sent?, the
  event never went out.

  The events aren't verified. Use verified? and matches? to choose which
  to keep."
  [{:keys [url filters event allow?] :or {allow? (constantly true)} :as request}]
  (let [relay    (relay-url url)
        messages (opening filters event)
        limits   (cond-> (merge default-limits (select-keys request (keys default-limits)))
                   event (assoc :published (get event "id") :until (if (seq filters) :event :ok)))]
    (if-not (and relay (allow? relay))
      (let [refused (ex-info (str "The request to " url " is not allowed") {:type ::refused :url url})]
        #?(:clj (throw refused) :cljs (js/Promise.reject refused)))
      #?(:clj  (response relay (exchange relay messages limits))
         :cljs (.then (exchange relay messages limits) #(response relay %))))))

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
     (send! {:url "wss://relay.damus.io" :filters [about-episode] :timeout-seconds 10})
     (map verified? (:events *1))
     #_.))
