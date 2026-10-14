(ns podcast-server.actor
  "The server's ActivityPub actor, whose key signs the proxy's requests for
  ActivityPub documents, so that servers that answer only signed requests
  answer the client too.

  A server checks such a signature by fetching the actor that the key
  belongs to and looking up its account with WebFinger. So both are
  public: the actor's document at its URL, and WebFinger at
  /.well-known/webfinger on the same host. Mastodon looks on port 443, and
  GoToSocial on the port of the actor's URL."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dk.simongray.httpsig :as httpsig]
            [dk.simongray.httpsig.cavage :as cavage]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.podcast.util.ring :as ring]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]))

(def key-name
  "The document of the data folder that holds the actor's key."
  "actor-key.edn")

(def algorithm
  "The algorithm of the actor's key, which every fediverse server checks."
  "rsa-v1_5-sha256")

(defn key-id
  "The id of the key of the `actor`, the URL of its key in its document."
  [actor]
  (str actor "#main-key"))

(defn url-path
  "The path of the URL of the `actor`, where the server serves its
  document."
  [actor]
  (let [{:keys [path]} (url/parts actor)]
    (if (str/blank? path) "/" path)))

;; Mastodon's Account::USERNAME_ONLY_RE, as of October 2026
(defn username
  "The user name of the `actor`, the last part of the path of its URL, or
  nil when that isn't a name that Mastodon takes."
  [actor]
  (let [part (last (str/split (url-path actor) #"/"))]
    (when (and part (re-matches #"[A-Za-z0-9_]+(?:[.-]+[A-Za-z0-9_]+)*" part))
      part)))

(defn account
  "The acct URI of the `actor` that WebFinger answers for, its user name at
  the host of its URL, e.g. acct:actor@podcasts.example."
  [actor]
  (let [{:keys [host port]} (url/parts actor)]
    (str "acct:" (username actor) "@" (str/lower-case host)
         (when port (str ":" port)))))

;; ActivityPub, W3C Recommendation of 2018-01-23, section 4.1, with the
;; publicKey of the Security Vocabulary as Mastodon reads it. Mastodon takes
;; no actor without an inbox, though nothing goes to it, since the actor
;; follows no one.
(defn document
  "The ActivityPub document of the `actor`, with the `public-pem` of its
  key."
  [actor public-pem]
  {"@context"          ["https://www.w3.org/ns/activitystreams"
                        "https://w3id.org/security/v1"]
   "id"                actor
   "type"              "Application"
   "preferredUsername" (username actor)
   "name"              "Podcast server"
   "inbox"             (str actor "/inbox")
   "publicKey"         {"id"           (key-id actor)
                        "owner"        actor
                        "publicKeyPem" public-pem}})

;; RFC 7033, section 4.4
(defn webfinger
  "The WebFinger answer about the `resource`, the account of the `actor` or
  its URL, or nil for any other resource."
  [actor resource]
  (when (contains? #{(str/lower-case (account actor)) actor}
                   (cond-> (str resource)
                     (str/starts-with? (str resource) "acct:") str/lower-case))
    {"subject" (account actor)
     "aliases" [actor]
     "links"   [{"rel"  "self"
                 "type" "application/activity+json"
                 "href" actor}]}))

;; RFC 7033, sections 4.2 and 5
(defn webfinger-handler
  "A Ring handler of WebFinger for the account of the `actor`, which any
  web page can read."
  [actor]
  (fn [{:keys [query-string]}]
    (let [resource (get (url/query-params query-string) "resource")
          answer   (some->> resource (webfinger actor))]
      (cond
        (str/blank? resource)
        (ring/text-response 400 "WebFinger needs a resource.")

        (nil? answer)
        (ring/text-response 404 "The server knows no such resource.")

        :else
        (ring/response 200 "application/jrd+json" (json/write-str answer)
                       {"Access-Control-Allow-Origin" "*"})))))

(defn paths
  "The paths that the server answers for the `actor` without a password:
  those of its document and of WebFinger."
  [actor]
  #{(url-path actor) "/.well-known/webfinger"})

(defn routes
  "The routes of the document and the WebFinger of the `actor`, with the
  `public-pem` of its key, in Pedestal's set syntax."
  [actor public-pem]
  (let [body (json/write-str (document actor public-pem))]
    #{[(url-path actor) :get
       (fn [_] (ring/response 200 "application/activity+json" body))
       :route-name ::document]
      ["/.well-known/webfinger" :get (webfinger-handler actor)
       :route-name ::webfinger]}))

(defn key!
  "The key of the `actor` from `data-store`, or a new one that's saved
  there, as an httpsig-clj key map with its :public-pem. It throws when
  the key can't be read or saved, since a server that kept the old key,
  e.g. GoToSocial, refuses a new one."
  [data-store actor]
  (let [saved (some-> ((:fetch data-store) key-name {}) :body edn/read-string)
        made  (or saved
                  (let [k    (httpsig/generate-key algorithm (key-id actor))
                        text (pr-str {:pem        (secret/reveal (:pem k))
                                      :public-pem (:public-pem k)})]
                    ((:put data-store) key-name text {})
                    k))]
    {:keyid      (key-id actor)
     :alg        algorithm
     :pem        (secret/hide (secret/reveal (:pem made)))
     :public-pem (:public-pem made)}))

;; ActivityPub, section 3.2: a request for an object names its types
(defn activitypub?
  "Whether the `request` is a GET or a HEAD that asks for an ActivityPub
  document, by its Accept header."
  [{:keys [method headers] :as request}]
  (let [accept (->> headers
                    (some (fn [[k v]]
                            (when (= "accept" (str/lower-case (name k))) v)))
                    str
                    str/lower-case)]
    (boolean
     (and (contains? #{nil :get :head} method)
          (or (str/includes? accept "application/activity+json")
              (and (str/includes? accept "application/ld+json")
                   (str/includes? accept
                                  "https://www.w3.org/ns/activitystreams")))))))

(defn signing
  "The `send` of the proxy, signing each request for an ActivityPub
  document with the actor's `key`: with draft-cavage first, and with RFC
  9421 when a server refuses that."
  [send key]
  (let [signed (httpsig/sender send key {:signers [cavage/sign httpsig/sign]})]
    (fn [request]
      (if (activitypub? request)
        (signed request)
        (send request)))))

(comment
  (document "https://podcasts.example/actor" "-----BEGIN PUBLIC KEY-----…")
  (webfinger "https://podcasts.example/actor" "acct:actor@podcasts.example")
  #_.)
