(ns ^:no-doc dk.simongray.podcast.comments.atproto.impl
  "The parts of the reading of AT Protocol threads behind comments.atproto
  that need none of its URLs: the requests to an AppView, the labels, and
  the content and the media of a post."
  (:require [clojure.string :as str]
            [dk.simongray.html-pieces :as html]
            [dk.simongray.podcast.comments :as-alias comments]
            [dk.simongray.podcast.comments.common :as common]
            [dk.simongray.podcast.util.json :as json]
            [dk.simongray.wary-fetch.url :as url]
            [dk.simongray.webcrypto.bytes :as bytes]))

;; AT Protocol, DID, Handle and Record Key, as of 2026-10-13: the
;; characters of each, and the dots of a handle
(def actor-pattern
  "The DID or the handle of an actor."
  #"did:[a-z]+:[A-Za-z0-9._:%-]{1,2048}|[A-Za-z0-9-]{1,63}(?:\.[A-Za-z0-9-]{1,63}){1,126}")

(defn rkey?
  "Whether `s` is a record key."
  [s]
  (boolean (and (string? s)
                (re-matches #"[A-Za-z0-9._:~-]{1,512}" s)
                (not (contains? #{"." ".."} s)))))

(defn thread-request
  "The request to the AppView at `appview` for the thread below the post
  `uri`: ten levels of replies, and none of the posts above it."
  [appview uri]
  (let [root  (url/without-trailing-slashes appview)
        query (url/query-string {"uri" uri "depth" 10 "parentHeight" 0})]
    {:method        :get
     :url           (str root "/xrpc/app.bsky.feed.getPostThread?" query)
     :headers       {"Accept" "application/json"}
     ::comments/for [:thread uri]}))

;; AT Protocol, Labels. The values are those of the lexicon
;; com.atproto.label.defs, as of 2026-09-30.
(defn labels
  "The labels that apply to the AT Protocol post `view`: those of the post
  and those of the account of its author, without the labels that are
  negated."
  [view]
  (let [own     (common/values view "labels")
        account (common/values (get view "author") "labels")]
    (remove #(get % "neg")
            (filter map? (concat own account)))))

(defn withheld?
  "Whether the AT Protocol post `view` is kept from a reader without an
  account. Its author or the post asks so with the label
  !no-unauthenticated, or a moderation service hid it with !hide."
  [view]
  (let [did (get-in view ["author" "did"])]
    (boolean (some (fn [{:strs [val src]}]
                     (or (= "!no-unauthenticated" val)
                         (and (= "!hide" val) (not= src did))))
                   (labels view)))))

(defn warnings
  "The labels of the AT Protocol post `view` that put it behind a warning.

  They're the labels of adult content and graphic media, and !warn when a
  moderation service set it, since clients ignore it from the author. A
  label on the account of the author counts as a label on each of its
  posts."
  [view]
  (let [did       (get-in view ["author" "did"])
        sensitive #{"porn" "sexual" "nudity" "graphic-media"}]
    (set (for [{:strs [val src]} (labels view)
               :when (or (contains? sensitive val)
                         (and (= "!warn" val) (not= src did)))]
           val))))

(defn- feature
  "What `facet` makes of the text that it marks, by the first of its
  features that podcast-clj knows: a link, a mention of a profile by its
  DID, or a hashtag. The lexicon app.bsky.richtext.facet defines them."
  [facet]
  (some (fn [f]
          (case (get f "$type")
            "app.bsky.richtext.facet#link"
            (when-let [url (common/web-url (get f "uri"))]
              {:type :link :url url})

            "app.bsky.richtext.facet#mention"
            (when (string? (get f "did"))
              {:type :mention :id (get f "did")})

            "app.bsky.richtext.facet#tag"
            (when (string? (get f "tag"))
              {:type :hashtag :tag (get f "tag")})

            nil))
        (filter map? (common/values facet "features"))))

(defn- marks
  "What the `facets` of a post mark in its text of `n` bytes, in order, as
  triples of a start, an end and a part, counted in bytes.

  A facet that overlaps the one before it is left out, as the official
  clients do."
  [facets n]
  (->> (for [facet (filter map? facets)
             :let  [{:strs [byteStart byteEnd]} (get facet "index")
                    start (json/whole byteStart)
                    end   (json/whole byteEnd)
                    part  (feature facet)]
             :when (and start
                        end
                        part
                        (<= 0 start)
                        (< start end)
                        (<= end n))]
         [start end part])
       (sort-by first)
       (reduce (fn [kept [start :as mark]]
                 (let [reach (or (second (peek kept)) 0)]
                   (if (< start reach) kept (conj kept mark))))
               [])))

(defn parts
  "The parts of the content of `record`, the record of an AT Protocol post
  with its \"text\" and its \"facets\", in their order. Each part is a map
  with its :type and the :text that the post shows for it:

      {:type :text :text \"/cc \"}
      {:type :link :text \"atproto.com/blog\" :url \"https://atproto.com/blog\"}
      {:type :mention :text \"@npmx.dev\" :id \"did:plc:u5zp…\"}
      {:type :hashtag :text \"#podcast\" :tag \"podcast\"}

  A facet counts UTF-8 bytes, so the text is cut as bytes."
  [record]
  (let [text (when (map? record) (get record "text"))]
    (if-not (and (string? text) (not (str/blank? text)))
      []
      (let [b      (bytes/encode text)
            n      (bytes/length b)
            cut    #(bytes/decode (bytes/slice b %1 %2))
            marked (marks (common/values record "facets") n)
            pieces (fn [[start end part] from]
                     [{:type :text :text (cut from start)}
                      (assoc part :text (cut start end))])
            tail   {:type :text :text (cut (or (second (last marked)) 0) n)}]
        (->> (concat (mapcat pieces marked (cons 0 (map second marked)))
                     [tail])
             (remove #(= "" (:text %)))
             vec)))))

(defn hiccup
  "The content of `record`, the record of an AT Protocol post, as Hiccup by
  `opts`: one paragraph, with a break for each line break of the text.
  The result is a sequence of nodes to put inside a parent element.

  A link of the post is a link. The :link-fn of `opts` is a function of a
  part. It gives the URL that a mention or a hashtag links to, or nil for
  no link, e.g. the profile-url of the :id of a mention."
  ([record]
   (hiccup record {}))
  ([record opts]
   {:pre [(map? opts)]}
   (let [node (fn [{:keys [type text url] :as part}]
                (case type
                  :text (interpose [:br {}] (str/split text #"\n" -1))
                  :link [(common/user-link url text)]
                  [(common/linked part text opts)]))]
     (if-let [ps (seq (parts record))]
       (list (into [:p {}] (comp (mapcat node) (remove #{""})) ps))
       ()))))

(defn post-html
  "The content of the AT Protocol `record` as sanitized HTML, or nil for a
  post without text. A mention and a hashtag have no link."
  [record]
  (when-let [paragraphs (seq (hiccup record))]
    (html/sanitize paragraphs)))

(defn- medium
  "The attachment of the `kind` :image or :video that the item `x` of an
  embed view gives by its properties `url` and `preview`, or nil when it
  has no web URL."
  [kind x url preview]
  (when-let [url (common/web-url (get x url))]
    (let [ratio (get x "aspectRatio")
          w     (common/dimension (get ratio "width"))
          h     (common/dimension (get ratio "height"))]
      (common/present {:kind         kind
                       :url          url
                       :preview      (common/web-url (get x preview))
                       :alt          (common/first-text (get x "alt"))
                       :aspect-ratio (when (and w h) [w h])}))))

(defn embed-attachments
  "The media of the `embed` view of an AT Protocol post, each with the
  ratio of its width to its height. They're the images, the video, or the
  media beside the post that it quotes."
  [embed]
  (case (get embed "$type")
    "app.bsky.embed.images#view"
    (vec (keep #(medium :image % "fullsize" "thumb")
               (common/values embed "images")))

    "app.bsky.embed.gallery#view"
    (vec (keep #(medium :image % "fullsize" "thumbnail")
               (common/values embed "items")))

    "app.bsky.embed.video#view"
    (if-let [video (medium :video embed "playlist" "thumbnail")]
      [(assoc video :media-type "application/vnd.apple.mpegurl")]
      [])

    "app.bsky.embed.recordWithMedia#view"
    (embed-attachments (get embed "media"))

    []))

(defn resumed
  "The AT Protocol `thread` as a new reading of it starts: unread again
  where a failure that can pass with time stopped the last reading.

  This is the root, and the replies below a post."
  [{:keys [comments] :as thread}]
  (let [unread #(cond-> %
                  (:retry? (:error (::comments/collection %)))
                  (dissoc ::comments/collection))]
    (cond-> (common/retried thread)
      comments (update :comments update-vals unread))))

(defn reply-requests
  "The requests for the replies that the AT Protocol `thread` has no view
  of yet, below the levels that the AppView at `appview` gave, nearest
  the root first."
  [thread appview]
  (let [unread (for [{:keys [id] ::comments/keys [reply-count collection]}
                     (vals (:comments thread))
                     :when (and reply-count (not (:read-at collection)))]
                 (thread-request appview id))]
    (common/nearest-first (common/depths thread) unread)))
