(ns ^:no-doc dk.simongray.podcast.nostr.impl
  "The internals of nostr: the serialized form that an event id hashes, the
  reader of the TLV fields of NIP-19 names, the pattern of the parts of a
  note, and the WebSocket plumbing of an exchange with a relay."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.nostr :as-alias nostr]
            #?(:clj [dk.simongray.podcast.util.client :as client])
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.webcrypto.bytes :as bytes])
  #?(:clj (:import [java.net URI]
                   [java.net.http HttpClient WebSocket WebSocket$Listener]
                   [java.time Duration]
                   [java.util.concurrent TimeUnit])))

(def max-name-length
  "The most characters of a name, the limit of NIP-19."
  5000)

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

(defn serialized
  "The text whose SHA-256 is the id of `event`, as the section Events and
  signatures of NIP-01 defines it."
  [{:strs [pubkey created_at kind tags content] :as event}]
  (let [tag #(str "[" (str/join "," (map quoted %)) "]")]
    (str "[0," (quoted pubkey)
         "," (json/whole created_at)
         "," (json/whole kind)
         ",[" (str/join "," (map tag tags)) "]"
         "," (quoted content)
         "]")))

(def name-types
  "The prefix of each name of NIP-19 that podcast-clj reads, with the type
  of the name."
  {"npub"     :npub
   "note"     :note
   "nprofile" :nprofile
   "nevent"   :nevent
   "naddr"    :naddr})

;; NIP-19, part "Shareable identifiers with extra metadata": the types 0
;; (the thing), 1 (a relay), 2 (the author) and 3 (the kind)
(defn fields
  "The fields in the bytes `b` of a name with extra data, in order, each
  as a pair of its type and its value. Nil when a field ends after the
  bytes.

  A field is a byte for the type, a byte for the length, and the value."
  [b]
  (let [n (bytes/length b)]
    (loop [i 0 out []]
      (cond
        (= i n)
        out

        (> (+ i 2) n)
        nil

        :else
        (let [end (+ i 2 (long (bytes/at b (inc i))))]
          (when (<= end n)
            (let [entry [(bytes/at b i) (bytes/slice b (+ i 2) end)]]
              (recur end (conj out entry)))))))))

(defn with-fields
  "The name of the type `type` whose data are the fields `fs`, as a map.
  Nil when the field that names the thing has a wrong size."
  [type fs]
  (let [of-type (fn [t] (map second (filter #(= t (first %)) fs)))
        key-of  (fn [b] (when (and b (= 32 (bytes/length b))) (bytes/hex b)))
        special (first (of-type 0))
        author  (key-of (first (of-type 2)))
        kind    (when-let [b (first (of-type 3))]
                  (when (= 4 (bytes/length b))
                    (reduce (fn [acc i] (+ (* 256 acc) (bytes/at b i)))
                            0
                            (range 4))))
        extras  (cond-> {:relays (mapv bytes/decode (of-type 1))}
                  author (assoc :author author)
                  kind   (assoc :kind kind))]
    (case type
      :nprofile (when-let [k (key-of special)]
                  {:type type :public-key k :relays (:relays extras)})
      :nevent   (when-let [id (key-of special)]
                  (assoc extras :type type :id id))
      :naddr    (when (and special author kind)
                  (assoc extras
                         :type       type
                         :identifier (bytes/decode special))))))

(def part-pattern
  "What names something in the content of an event: a nostr: URI with a
  name, a web URL, and a word after a number sign."
  (re-pattern
   (str "(?u)"
        "nostr:(?:npub|nprofile|note|nevent|naddr)1[02-9ac-hj-np-z]+"
        "|https?://[^\\s<>]+"
        "|#[\\p{L}\\p{N}_]+")))

(defn failure
  "The exception of an exchange with the relay at `url` that failed for
  the reason `message`, with the `cause` and the `answer` so far.

  When the event went out before the failure, the data of the exception
  has :sent? true."
  [url message cause answer]
  (ex-info (str message ": " url)
           (cond-> {:type ::nostr/failed :url url}
             (:sent? answer) (assoc :sent? true))
           cause))

(defn due?
  "Whether the event of an exchange goes out now, by its `answer`. It does
  when the relay has the subscription, the event hasn't gone out, and the
  exchange goes on."
  [{:keys [complete? sent? done?] :as answer}]
  (boolean (and complete? (not sent?) (not done?))))

#?(:clj
   (defn- listener
     "The listener of a WebSocket that reads the messages of a relay into
     the atom `answer` with the function `step` within `limits`, with the
     promises `ready` and `done`. The `step` takes the answer, a message
     and the limits, as nostr/received does.

     It delivers :ready to `ready` when the event of the exchange is due,
     and the answer to `done` when the exchange ends."
     [answer step ready done {:keys [max-bytes] :as limits}]
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
               (let [read (swap! answer step text limits)]
                 ;; the thread of the exchange sends the event, since a
                 ;; send from here fails while the send of the first
                 ;; message is pending, and the JDK clears that only after
                 ;; the message went out
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
     "End the subscription of `socket` with the CLOSE message `close` and
     close it, waiting at most two seconds for the relay to take each of
     the two messages."
     [^WebSocket socket close]
     (try
       (when-not (.isOutputClosed socket)
         (-> (.sendText socket close true)
             (.get 2 TimeUnit/SECONDS))
         (-> (.sendClose socket WebSocket/NORMAL_CLOSURE "")
             (.get 2 TimeUnit/SECONDS)))
       (catch Exception _
         nil)
       (finally
         (.abort socket)))))

#?(:clj
   (defonce ^{:doc "The client of the WebSockets to the relays, built on
  first use."}
     websocket-client
     (delay (HttpClient/newHttpClient))))

#?(:clj
   (defn exchange
     "The answer of the relay at `url` to the opening `messages`, read with
     the function `step` within `limits`, over a WebSocket of the JDK, with
     the :user-agent of the limits as its User-Agent. The `messages` end
     the subscription with their :close."
     [url messages {:keys [timeout-seconds user-agent]
                    :or   {user-agent client/user-agent}
                    :as   limits}
      step]
     (let [answer   (atom {})
           ready    (promise)
           done     (promise)
           socket   (try
                      (-> (.newWebSocketBuilder ^HttpClient @websocket-client)
                          (.connectTimeout (Duration/ofSeconds timeout-seconds))
                          (.header "User-Agent" user-agent)
                          (.buildAsync (URI. url)
                                       (listener answer step ready done
                                                 limits))
                          (.get timeout-seconds TimeUnit/SECONDS))
                      (catch Exception e
                        (throw (failure url "The relay gave no connection"
                                        e {}))))
           deadline (+ (System/currentTimeMillis) (* 1000 timeout-seconds))
           left     #(max 0 (- deadline (System/currentTimeMillis)))
           write!   (fn [text]
                      (-> (.sendText ^WebSocket socket text true)
                          (.get (left) TimeUnit/MILLISECONDS)))
           failed   "The connection to the relay failed"]
       (try
         (when (:sent? messages)
           (swap! answer assoc :sent? true))
         (write! (:first messages))
         (when (and (:later messages)
                    (= :ready (deref ready (left) ::nostr/timeout)))
           ;; noted before it goes out: an event that failed on its way
           ;; counts as one that went out
           (swap! answer assoc :sent? true)
           (write! (:later messages)))
         (let [result (deref done (left) ::nostr/timeout)]
           (cond
             (= ::nostr/timeout result)
             (assoc @answer :timeout? true)

             (:error result)
             (throw (failure url failed (:error result) @answer))

             :else
             result))
         (catch java.util.concurrent.ExecutionException e
           (throw (failure url failed (ex-cause e) @answer)))
         (catch java.util.concurrent.TimeoutException e
           (throw (failure url "The relay took no message in time" e @answer)))
         (finally
           (hang-up socket (:close messages)))))))

#?(:cljs
   (defn exchange
     "A promise of the answer of the relay at `url` to the opening
     `messages`, read with the function `step` within `limits`, over the
     WebSocket of the platform. The `messages` end the subscription with
     their :close."
     [url messages {:keys [timeout-seconds] :as limits} step]
     (js/Promise.
      (fn [resolve reject]
        (let [answer  (atom (if (:sent? messages) {:sent? true} {}))
              socket  (js/WebSocket. url)
              timer   (atom nil)
              end     (fn [f x]
                        (js/clearTimeout @timer)
                        (try
                          (when (= 1 (.-readyState socket))
                            (.send socket (:close messages)))
                          (.close socket)
                          (catch :default _ nil))
                        (f x))
              time-up #(end resolve (assoc @answer :timeout? true))]
          (reset! timer (js/setTimeout time-up (* 1000 timeout-seconds)))
          (set! (.-onopen socket) #(.send socket (:first messages)))
          (set! (.-onmessage socket)
                (fn [e]
                  (let [read (swap! answer step (str (.-data e)) limits)]
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
                    (end reject
                         (failure url "The connection to the relay failed"
                                  (.-error e) @answer))))))))))
