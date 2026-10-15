(ns dk.simongray.podcast.nostr
  "The Nostr protocol: its events, its names and its relays. Nothing here
  is about podcasts.

  An event is the JSON object of NIP-01 as plain data with string keys,
  as a relay gives it. Show no event whose id and signature aren't
  verified, since a relay can send anything."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.nostr.impl :as impl]
            [dk.simongray.podcast.nostr.secp256k1 :as secp256k1]
            [dk.simongray.podcast.util.bech32 :as bech32]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.webcrypto.hash :as hash]))

(def default-limits
  "The limits of an exchange with a relay that its request doesn't set.
  One answer has at most :max-events events, and the relay sends at most
  :max-bytes characters."
  {:timeout-seconds 30
   :max-events      500
   :max-bytes       (* 5 1024 1024)})

(def subscription
  "The id of the one subscription of an exchange with a relay."
  "podcast-clj")

(defn hex-id?
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

(defn event-id
  "The id that NIP-01 gives `event`: the SHA-256 of its fields, in
  hexadecimal."
  [event]
  (bytes/hex (hash/digest :sha-256 (bytes/encode (impl/serialized event)))))

(defn verified?
  "Whether `event` is an event whose id is the id of its content, and
  whose signature is that of the owner of its public key.

  NIP-01 defines the id, and BIP-340 defines the signature."
  [event]
  (boolean (and (event? event)
                (= (get event "id") (event-id event))
                (secp256k1/verify (get event "pubkey")
                                  (get event "id")
                                  (get event "sig")))))

(defn signed
  "The `event` signed with `secret-key` and `aux`: with the public key of
  the owner, the id of NIP-01 and the signature of BIP-340. Nil when
  `secret-key` isn't a secret key.

      (signed {\"kind\" 1 \"created_at\" 1790000000
               \"tags\" [] \"content\" \"Hi\"}
              secret-key
              aux)

  The `event` has its kind and its time, in seconds. Its tags and its
  content are empty when it has none. The `aux` is 32 random bytes in
  hexadecimal that nobody can guess, new for each signature.

  How long it takes depends on the secret key."
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
  (let [{:strs [id pubkey kind created_at]} event
        tagged? (fn [tag values]
                  (boolean (some (set values) (tag-values event tag))))]
    (every? (fn [[k v]]
              (cond
                (= "ids" k)                 (contains? (set v) id)
                (= "authors" k)             (contains? (set v) pubkey)
                (= "kinds" k)               (contains? (set v) kind)
                (= "since" k)               (<= v created_at)
                (= "until" k)               (<= created_at v)
                (re-matches #"#[a-zA-Z]" k) (tagged? (subs k 1) v)
                :else                       true))
            filter)))

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
    (let [text  (str/replace (str/trim s) #"(?i)^nostr:" "")
          named (bech32/decode text {:max-length impl/max-name-length})]
      (when-let [{:keys [prefix] b :bytes} named]
        (when-let [type (impl/name-types prefix)]
          (case type
            :npub (when (= 32 (bytes/length b))
                    {:type type :public-key (bytes/hex b)})
            :note (when (= 32 (bytes/length b))
                    {:type type :id (bytes/hex b)})
            (some->> (impl/fields b) (impl/with-fields type))))))))

(defn- field
  "The bytes of a field of the type `t` with the value `b`, which has 255
  bytes or fewer."
  [t b]
  (let [octets (map #(bytes/at b %) (range (bytes/length b)))]
    (bytes/of (concat [t (bytes/length b)] octets))))

(defn encode
  "The name of NIP-19 for `x`, a map of the form that decode gives, or nil
  when `x` names nothing of its type.

  A relay with more than 255 bytes in its URL is left out."
  [{:keys [type id public-key identifier author kind relays] :as x}]
  (let [short?     #(<= (bytes/length %) 255)
        kind-bytes (fn [kind]
                     (bytes/of (map #(bit-and (quot kind %) 255)
                                    [16777216 65536 256 1])))
        extras     (concat (for [b     (map bytes/encode relays)
                                 :when (short? b)]
                             (field 1 b))
                           (when (hex-id? author)
                             [(field 2 (bytes/from-hex author))])
                           (when (and (integer? kind) (<= 0 kind 0xffffffff))
                             [(field 3 (kind-bytes kind))]))
        named      (fn [prefix special]
                     (let [parts (cons (field 0 special) extras)]
                       (bech32/encode prefix (apply bytes/concat parts))))]
    (case type
      :npub     (when (hex-id? public-key)
                  (bech32/encode "npub" (bytes/from-hex public-key)))
      :note     (when (hex-id? id)
                  (bech32/encode "note" (bytes/from-hex id)))
      :nprofile (when (hex-id? public-key)
                  (named "nprofile" (bytes/from-hex public-key)))
      :nevent   (when (hex-id? id)
                  (named "nevent" (bytes/from-hex id)))
      :naddr    (when (and (string? identifier)
                           (hex-id? author)
                           (integer? kind)
                           (short? (bytes/encode identifier)))
                  (named "naddr" (bytes/encode identifier)))
      nil)))

(defn uri
  "The nostr: URI of NIP-21 for `x`, a map of the form that decode gives,
  or nil when `x` names nothing of its type."
  [x]
  (some->> (encode x) (str "nostr:")))

(defn- part
  "The part that the match `s` gives, in an event whose hashtags in lower
  case are `hashtags`, or nil for a match that is text.

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
  reference is the decoded name. A hashtag is a word after a number sign
  that the event names in a t tag, as NIP-24 defines. A mention of a
  profile can have :relays."
  [event]
  (let [content   (get event "content")
        content   (if (string? content) content "")
        hashtags  (->> (tag-values event "t")
                       (filter string?)
                       (map str/lower-case)
                       set)
        ;; the text from `from` to `to` goes into `out`, unless it is empty
        with-text (fn [out from to]
                    (cond-> out
                      (< from to)
                      (conj {:type :text :text (subs content from to)})))]
    ;; `at` is where the next match starts or later, and `from` is where
    ;; the text starts that no part has yet
    (loop [found (re-seq impl/part-pattern content) at 0 from 0 out []]
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
    (let [url   (url/without-trailing-slashes (str/trim s))
          lower (fn [[_ scheme host]]
                  (str (str/lower-case scheme) "://" (str/lower-case host)))]
      (when (re-matches #"(?i)wss?://[^\s/?#]+(?:[/?#]\S*)?" url)
        (str/replace url #"(?i)^(wss?)://([^/?#]+)" lower)))))

(defn public?
  "Whether the host of the relay at `url` is on the public internet."
  [url]
  (boolean (some-> (relay-url url) (str/replace #"^ws" "http") url/public?)))

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
  JSON."
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
          "EVENT"  (when (and (string? a) (map? b))
                     {:type :event :subscription a :event b})
          "EOSE"   (when (string? a)
                     {:type :eose :subscription a})
          "CLOSED" (when (string? a)
                     {:type :closed :subscription a :message (str b)})
          "NOTICE" {:type :notice :message (str a)}
          "OK"     (when (string? a)
                     {:type :ok :id a :accepted? (true? b) :message (str c)})
          "AUTH"   (when (string? a)
                     {:type :auth :challenge a})
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

  The events are only those of the subscription of the exchange, as the
  relay gives them, and they aren't verified."
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
      (let [answer (update answer :events (fnil conj []) (:event message))
            full?  (and max-events
                        (>= (count (:events answer)) max-events))]
        (cond-> answer
          (= :event until) (assoc :done? true)
          full?            (assoc :done? true :truncated :max-events)))

      (and ours? (= :eose (:type message)))
      (cond-> (assoc answer :complete? true)
        (not (contains? #{:event :ok} until)) (assoc :done? true))

      (and ours? (= :closed (:type message)))
      (assoc answer :done? true :closed (:message message))

      (and published
           (= :ok (:type message))
           (= published (:id message)))
      (cond
        (not (:accepted? message))
        (assoc answer :done? true :refused (:message message))

        (= :ok until)
        (assoc answer :done? true :accepted? true)

        :else
        (assoc answer :accepted? true))

      (= :notice (:type message))
      (update answer :notices (fnil conj []) (:message message))

      (= :auth (:type message))
      (assoc answer :auth? true)

      :else
      answer)))

(defn response
  "The response of an exchange for the `answer` of the relay at `url`."
  [url answer]
  (merge {:url url :events []} (dissoc answer :bytes :done?)))

(defn- opening
  "The messages that an exchange for `filters` and the `event` sends, as
  the :first and the :later message, one of which can be absent.

  With filters, the event goes out later, when the relay has the
  subscription. Without them, the event is the first message, and the
  map has :sent? true."
  [filters event]
  (cond
    ;; so that the answer to the event finds the subscription, and an event
    ;; doesn't go to a relay that refuses the subscription
    (and (seq filters) event)
    {:first (request-message subscription filters)
     :later (event-message event)}

    event
    {:first (event-message event) :sent? true}

    :else
    {:first (request-message subscription filters)}))

(defn send!
  "Ask the relay of the `request` for the events of its filters, and give
  what it answers. On the JVM it gives the response, and in ClojureScript
  a promise of it.

      {:url     \"wss://relay.example\"
       :filters [{\"kinds\" [1111] \"#I\" [\"podcast:item:guid:…\"]
                  \"limit\" 100}]}

  The exchange is one subscription. It opens a WebSocket, sends the REQ
  of NIP-01, reads until the relay sends EOSE, and closes. The response
  has the :url and the :events, with the other keys of received. A relay
  that doesn't answer in time gives the events that came, with :timeout?
  true.

  The `request` has these keys:

  - :url, the URL of the relay
  - :filters, the filters of NIP-01
  - :event, a signed event to give the relay
  - :timeout-seconds, :max-events and :max-bytes, which default to those
    of default-limits
  - :allow-pred, a predicate of the URL of the relay, as public? is
  - :user-agent, the User-Agent on the JVM, client/user-agent by default

  It throws ::refused for a URL that :allow-pred refuses or that isn't the URL
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

  The events aren't verified, and they needn't pass the filters."
  [{:keys [url filters event allow-pred]
    :or   {allow-pred (constantly true)}
    :as   request}]
  (let [relay    (relay-url url)
        messages (assoc (opening filters event)
                        :close (close-message subscription))
        limits   (cond-> (merge default-limits
                                (select-keys request (keys default-limits))
                                (select-keys request [:user-agent]))
                   event (assoc :published (get event "id")
                                :until     (if (seq filters) :event :ok)))]
    (if-not (and relay (allow-pred relay))
      (let [refused (ex-info (str "The request to " url " is not allowed")
                             {:type ::refused :url url})]
        #?(:clj (throw refused) :cljs (js/Promise.reject refused)))
      #?(:clj  (response relay (impl/exchange relay messages limits received))
         :cljs (.then (impl/exchange relay messages limits received)
                      #(response relay %))))))

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
    {"kinds" [1111]
     "#I"    ["podcast:item:guid:d98d189b-dc7b-45b1-8720-d4b98690f31f"]
     "limit" 100})

  (request-message subscription [about-episode])

  ;; the pure step of an exchange
  (-> {}
      (received "[\"NOTICE\",\"hello\"]" default-limits)
      (received "[\"EOSE\",\"podcast-clj\"]" default-limits))
  #_.)

#?(:clj
   (comment
     (send! {:url             "wss://relay.damus.io"
             :filters         [about-episode]
             :timeout-seconds 10})
     (map verified? (:events *1))
     #_.))
