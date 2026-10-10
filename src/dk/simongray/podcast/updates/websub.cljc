(ns dk.simongray.podcast.updates.websub
  "WebSub, the W3C Recommendation: a hub sends a feed to its subscribers
  when the feed changes, so a server doesn't have to poll the feed.

  The code follows the Recommendation of 2 June 2026, at
  https://www.w3.org/TR/websub/, and the docstrings name its sections.

  A feed names its hubs, and a subscriber asks a hub for the feed. The hub
  then calls the callback URL of the subscriber: first to make sure that
  the subscriber asked, and later with each new version of the feed. So a
  subscriber is a server with a public URL. An app without a server can
  read Podping instead, which gives the same signal.

  A host is a publisher. It names a hub in its feed with an atom:link
  rel=hub, next to its atom:link rel=self, and tells the hub when the
  feed changed."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]))

(def default-limits
  "The limits of a callback handler that its options don't set.

  A hub sends :max-bytes of a feed at most, 32 MB. The challenge of a hub
  has :max-challenge characters at most. A subscriber gives the
  challenge back as it came, so a long one is a way to make the
  subscriber serve the text of a stranger."
  {:max-bytes     (* 32 1024 1024)
   :max-challenge 512})

(def signature-methods
  "The hash algorithms of a signature that a subscriber takes, by the names
  of section 7.1.1 of WebSub, unless the options give another set as
  :signature-methods.

  Most hubs sign with sha1, which is weak. A subscriber whose callback is
  an http URL leaves it out, as section 8.3 asks."
  #{"sha1" "sha256" "sha384" "sha512"})

(defn ^:no-doc relation?
  "Whether the link `link` has the relation type `rel` among the types of
  its :rel."
  [rel link]
  (let [types (str/split (str/lower-case (str (:rel link))) #"\s+")]
    (contains? (set types) rel)))

(defn- hrefs
  "The hrefs of the `links` that have the relation type `rel`."
  [rel links]
  (keep #(when (relation? rel %) (:href %)) links))

(defn discover
  "The hubs and the topic of the parsed `feed`, from the `response` that
  had it, as a map of :hubs and :topic. It's nil when nothing names a
  hub.

  The `response` has its :url and its :headers. The Link headers of the
  response count first, as section 4 of WebSub asks. Without a hub in
  them, the atom:links of the feed count: rel=hub and rel=self.

  The topic is the URL to subscribe to. It's the rel=self link, or the
  URL of the response when no link names it. A URL can be relative to the
  URL of the response."
  [{:keys [url headers] :as response} feed]
  (let [links       (http/links headers)
        absolute    #(if url (url/absolute-url url %) %)
        header-hubs (seq (hrefs "hub" links))
        hubs        (or header-hubs (resolve/hubs feed))
        self        (if header-hubs
                      (first (hrefs "self" links))
                      (resolve/self-url feed))]
    (when (seq hubs)
      {:hubs  (mapv absolute hubs)
       :topic (if self (absolute self) url)})))

(defn ^:no-doc hub-request
  "The request map of a form for the `hub`, with the `fields`: pairs of a
  name and a value. A field without a value is left out."
  [hub fields]
  {:method  :post
   :url     hub
   :headers {"Content-Type" "application/x-www-form-urlencoded"}
   :body    (url/query-string fields)})

(defn request
  "The request map that asks the `hub` for the feed at `topic`, by `mode`
  and `opts`. The hub sends the feed to `callback`, the URL of the
  subscriber.

  The `mode` is :subscribe or :unsubscribe. The request is that of
  section 5.1 of WebSub. A callback is a URL that nobody can guess. Each
  subscription has its own, since the URL tells the subscriber that the
  hub sends the request. The `opts` are these:

  - :lease-seconds, how long the subscription lasts. The hub sets it, and
    tells it when it checks the subscription. A subscriber asks again
    before the end.
  - :secret, a random text of fewer than 200 bytes. The hub then signs
    what it sends, and the subscriber checks the signature. Section 5.1 says
    that a secret goes over https, so this throws for another hub. The
    secret can be a hidden text of dk.simongray.wary-fetch.secret. The
    request has it in its body, so the body of such a request is a
    hidden text."
  ([mode hub topic callback]
   (request mode hub topic callback {}))
  ([mode hub topic callback {:keys [lease-seconds] :as opts}]
   {:pre [(map? opts)]}
   (when-not (contains? #{:subscribe :unsubscribe} mode)
     (throw (ex-info "The mode of a request is :subscribe or :unsubscribe"
                     {:mode mode})))
   (when-not (every? url/web-url? [hub topic callback])
     (throw (ex-info (str "The hub, the topic and the callback are http or "
                          "https URLs")
                     {:hub hub :topic topic :callback callback})))
   (let [secret (secret/reveal (:secret opts))]
     (when secret
       (when-not (and (string? secret)
                      (< 0 (bytes/length (bytes/encode secret)) 200))
         (throw (ex-info "A secret is a text of fewer than 200 bytes" {})))
       (when-not (re-find #"(?i)^https:" hub)
         (throw (ex-info "A secret goes to an https hub alone" {:hub hub}))))
     (cond-> (hub-request hub [["hub.callback" callback]
                               ["hub.mode" (name mode)]
                               ["hub.topic" topic]
                               ["hub.lease_seconds" lease-seconds]
                               ["hub.secret" secret]])
       secret (update :body secret/hide)))))

(def publish-form
  "The form of the request that tells a hub of a change, as the public
  hubs take it. It has the :fields that each request has, and the name of
  the field that holds the URL of the feed.

  Section 6 of WebSub leaves this request to the hub and the publisher,
  and its note gives this form as an example. So the options can give
  the form of another hub as :publish-form."
  {:fields    {"hub.mode" "publish"}
   :url-field "hub.url"})

(defn publish-request
  "The request map that tells the `hub` that the feed at `topic` changed,
  in the form of the :publish-form of `opts`, publish-form by default. A
  host sends it after each change of its feed."
  ([hub topic]
   (publish-request hub topic {}))
  ([hub topic {form :publish-form :or {form publish-form} :as opts}]
   {:pre [(map? opts)]}
   (when-not (every? url/web-url? [hub topic])
     (throw (ex-info "The hub and the topic are http or https URLs"
                     {:hub hub :topic topic})))
   (hub-request hub (conj (vec (:fields form)) [(:url-field form) topic]))))

(defn call!
  "Send the `request`, to subscribe or to publish, to its hub with `opts`.
  On the JVM it gives the answer as a map of :status and :url, and in
  ClojureScript a promise of it.

  A hub answers 202 to a request that it takes, as section 5.1.2 of
  WebSub has it. It then calls the callback of the subscriber, and only
  that call makes the subscription. A status that isn't a success throws
  with the :type ::failed, and the :status and :message of the hub.

  The `opts` are these:

  - :timeout-seconds, :max-bytes, :allow-pred and :user-agent, as
    http/send! takes them
  - :send, a function that sends a request map in place of client/send!"
  ([request]
   (call! request {}))
  ([request {:keys [send] :or {send client/send!} :as opts}]
   {:pre [(map? opts)]}
   (async/then (send (http/with-options request opts))
               (fn [{:keys [status url body]}]
                 (if (and (integer? status) (<= 200 status 299))
                   {:status status :url url}
                   (let [message (when (string? body)
                                   (subs body 0 (min 200 (count body))))]
                     (throw (ex-info (str "The hub answered " status)
                                     {:type    ::failed
                                      :status  status
                                      :message message}))))))))

(def ^:no-doc algorithms
  "The HMAC algorithm of each signature method."
  {"sha1"   :sha1
   "sha256" :sha256
   "sha384" :sha384
   "sha512" :sha512})

(defn signature
  "The signature of the bytes `body` with the text `secret`, by the hash
  algorithm `method` of signature-methods, or nil for any other method.
  The secret can be a hidden text of dk.simongray.wary-fetch.secret.

  It's the HMAC of the body in hexadecimal, after the method and an
  equals sign, such as sha256=1f3a…. Section 7.1 of WebSub defines it as
  the value of the header X-Hub-Signature."
  [method secret body]
  (when-let [algorithm (algorithms method)]
    (let [k (bytes/encode (secret/reveal secret))]
      (str method "=" (bytes/hex (bytes/hmac algorithm k body))))))

(defn signed?
  "Whether `header`, the value of X-Hub-Signature, is the signature of the
  bytes `body` with the text `secret`, by one of `methods`.

  The method is that of the header, as section 7.1.2 of WebSub asks. The
  `methods` are signature-methods by default."
  ([secret header body]
   (signed? secret header body signature-methods))
  ([secret header body methods]
   (let [signature-form #"(?i)\s*([a-z0-9]{1,16})=([0-9a-f]{1,256})\s*"
         [_ method hex] (re-matches signature-form (str header))
         method         (some-> method str/lower-case)]
     (boolean (and method
                   (contains? methods method)
                   (when-let [expected (signature method secret body)]
                     (let [given (str method "=" (str/lower-case hex))]
                       (bytes/same? (bytes/encode expected)
                                    (bytes/encode given)))))))))

(defn ^:no-doc answer
  "The Ring response of `status` with the text `body`. The media type and
  the nosniff header keep a browser from reading the text as a page, as
  WebSub asks for the challenge that a subscriber gives back."
  [status body]
  ;; sections 5.3.1 and 8.2 of WebSub, since the Recommendation of 2 June
  ;; 2026: the answer with the challenge MUST have a safe media type and
  ;; nosniff, against a script that a stranger puts in the challenge
  {:status  status
   :headers {"Content-Type"           "application/octet-stream"
             "X-Content-Type-Options" "nosniff"}
   :body    body})

(defn- challenge?
  "Whether `s` is a challenge that a subscriber gives back: `limit`
  characters at most, of the characters that WebSub allows."
  [s limit]
  ;; section 8.2 of WebSub, since the Recommendation of 2 June 2026: a
  ;; subscriber SHOULD limit the length of a challenge and refuse binary
  ;; data; the characters are those that section 5.3 gives to a hub
  (boolean (and (string? s)
                (<= 1 (count s) limit)
                (re-matches #"[+\-./0-9=A-Z_a-z]+" s))))

(defn- verification
  "The answer to the GET `request` of a hub, whose query has `params`, by
  the `env` of the handler.

  The hub makes sure that the subscriber wants a subscription or its end,
  as section 5.3 of WebSub has it. Or it tells that it denied a
  subscription, as section 5.2 has it."
  [{:keys [wanted-pred denied! max-challenge] :as env} request params]
  (let [mode      (get params "hub.mode")
        topic     (get params "hub.topic")
        challenge (get params "hub.challenge")
        facts     {:topic topic :request request}]
    (cond
      (= "denied" mode)
      (do (when denied!
            (denied! (assoc facts :reason (get params "hub.reason"))))
          (answer 200 ""))

      (and (contains? #{"subscribe" "unsubscribe"} mode)
           (string? topic)
           (challenge? challenge max-challenge)
           (let [lease (get params "hub.lease_seconds")]
             (wanted-pred (assoc facts
                                 :mode          (keyword mode)
                                 :lease-seconds (some-> lease parse-long)))))
      (answer 200 challenge)

      :else
      (answer 404 ""))))

(defn- distribution
  "The answer to the POST `request` of a hub, which has the feed, by the
  `env` of the handler, as section 7 of WebSub has it. The app gets the
  feed when the request has the signature that its secret asks for.

  Section 7.1.2 says that a subscriber ignores a feed with a wrong
  signature, and that it can answer with a success. So the hub gets the
  same answer for a feed that the app didn't get, and a stranger learns
  nothing about the secret."
  [{:keys [secret deliver! signature-methods max-bytes] :as env}
   {:keys [headers] :as request}]
  (let [body      (ring/body-bytes (:body request) max-bytes)
        secret    (if (fn? secret) (secret request) secret)
        signature (get headers "x-hub-signature")
        links     (http/links headers)]
    (cond
      (nil? body)
      (answer 413 "")

      (and secret
           (not (signed? secret signature body signature-methods)))
      (answer 200 "")

      :else
      (do (deliver! {:body         body
                     :content-type (get headers "content-type")
                     :topic        (first (hrefs "self" links))
                     :hubs         (vec (hrefs "hub" links))
                     :request      request})
          (answer 200 "")))))

(defn handler
  "A Ring handler for the callback URL of a subscriber, by `opts`:

  - :wanted-pred, a function of the :mode, :topic, :lease-seconds and
    :request of a check, which says whether the subscriber asked
  - :deliver!, a function of the :body, :content-type, :topic, :hubs and
    :request of a feed that came
  - :denied!, a function of the :topic, :reason and :request of a
    subscription that the hub denied or ended
  - :secret, a text or a function of the request
  - :signature-methods, signature-methods by default
  - :max-bytes and :max-challenge, with default-limits for those that
    they don't set

  A hub calls the callback in three ways:

  - It makes sure that the subscriber asked for the :mode, :subscribe or
    :unsubscribe, of the :topic. When :wanted-pred says yes, the handler
    gives the challenge of the hub back, and else it answers 404. The
    :lease-seconds is how long the hub keeps the subscription.
  - It sends the feed, and :deliver! gets it as the bytes :body. With a
    :secret, it gets only a feed that has the signature of the secret.
    The handler answers 200 when the feed came, so :deliver! should
    return fast, and the app reads the feed after that.
  - It tells that it denied a subscription, or ended one, and :denied!
    gets the :topic and the :reason.

  The :topic of a feed that came is the rel=self of its Link header. It
  can be another URL than the topic of the subscription, as section 7 of
  WebSub says. So the callback URL tells the subscription, and the
  :request has it."
  [{:keys [wanted-pred deliver!] :as opts}]
  (when-not (and (fn? wanted-pred) (fn? deliver!))
    (throw (ex-info "A handler needs the functions :wanted-pred and :deliver!"
                    {})))
  (let [env (merge default-limits {:signature-methods signature-methods} opts)]
    (fn [{:keys [request-method query-string] :as request}]
      (case request-method
        :get  (verification env request (url/query-params query-string))
        :post (distribution env request)
        (answer 405 "")))))

(comment
  ;; the hubs of a feed, from the headers first
  (discover {:url     "https://example.org/feed.xml"
             :headers {"link" (str "<https://hub.example.org/>; rel=\"hub\", "
                                   "</feed.xml>; rel=\"self\"")}}
            {})

  (discover {:url "https://example.org/feed.xml" :headers {}}
            {:atom/links [{:rel "hub" :href "https://hub.example.org/"}
                          {:rel "self" :href "https://example.org/feed.xml"}]})

  (request :subscribe "https://hub.example.org/" "https://example.org/feed.xml"
           "https://app.example.net/websub/4f1c9e2a"
           {:lease-seconds 864000 :secret "a text that nobody can guess"})

  (publish-request "https://hub.example.org/" "https://example.org/feed.xml")

  ;; a callback that wants one feed, and the three calls of a hub
  (def serve
    (handler {:wanted-pred (fn [{:keys [topic]}]
                             (= "https://example.org/feed.xml" topic))
              :deliver!    (fn [{:keys [body]}]
                             (println (bytes/length body) "bytes came"))
              :secret      "a text that nobody can guess"}))

  (serve {:request-method :get
          :query-string   (str "hub.mode=subscribe"
                               "&hub.topic=https%3A%2F%2Fexample.org%2Ffeed.xml"
                               "&hub.challenge=abc123"
                               "&hub.lease_seconds=864000")})

  (def feed-bytes
    (bytes/encode (str "<rss version=\"2.0\"><channel><title>A show</title>"
                       "</channel></rss>")))

  (def signed
    (signature "sha256" "a text that nobody can guess" feed-bytes))

  (def self-link
    "<https://example.org/feed.xml>; rel=\"self\"")

  (serve {:request-method :post
          :headers        {"x-hub-signature" signed
                           "content-type"    "application/rss+xml"
                           "link"            self-link}
          :body           feed-bytes})

  ;; a feed without the signature gets the same answer, and the app gets
  ;; nothing
  (serve {:request-method :post :headers {} :body feed-bytes})
  #_.)
