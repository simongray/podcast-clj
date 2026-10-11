(ns dk.simongray.podcast.feed.publish
  "What a host does to a feed before serving it: the pages of a long feed,
  the private feed of a subscriber, and the URLs of its files."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.wary-fetch.secret :as secret]
            [dk.simongray.wary-fetch.url :as url]))

(def page-size
  "How many items a document of pages holds, unless its options give
  another :page-size."
  100)

(def ^:no-doc document-rels
  "The link relations of RFC 5005 that tie the documents of one feed
  together, and self, the link of a document to itself. Section 3 has
  those of a paged feed, and section 4 those of an archived feed."
  #{"self" "first" "last" "previous" "next"
    "prev-archive" "next-archive" "current"})

(defn ^:no-doc document-link?
  "Whether the atom:link `link` ties the documents of a feed together, or
  is a link of a document to itself."
  [link]
  (contains? document-rels (some-> (:rel link) str/lower-case)))

(defn- document
  "One document of `feed` for pages, at `url`, with the `items` alone and
  the `links` of the document, as a map of :url and :feed.

  The `links` have the URL of each link relation of the document, and a
  relation without a URL is left out. They take the place of such links
  that the feed has."
  [feed url items links]
  (let [type  (or (some :type (resolve/atom-links feed "self"))
                  "application/rss+xml")
        links (for [[rel href] (cons ["self" url] links)
                    :when href]
                {:rel rel :href href :type type})
        other (remove document-link? (:atom/links feed))]
    {:url  url
     :feed (-> (dissoc feed :fh/complete :fh/archive)
               (assoc :rss/items  (vec items)
                      :atom/links (into (vec links) other)))}))

(defn- paged
  "The documents of `feed` as a paged feed, as section 3 of RFC 5005
  defines it, with `size` items to a page. The first page is at `url`, and
  page n is at what `page-url-fn` gives for n."
  [feed size url page-url-fn]
  (let [chunks (vec (partition-all size (:rss/items feed)))
        n      (max 1 (count chunks))
        at     #(if (= 1 %) url (page-url-fn %))]
    (vec (for [k (range 1 (inc n))]
           (document feed (at k) (get chunks (dec k))
                     [["first" (at 1)]
                      ["last" (at n)]
                      ["previous" (when (< 1 k) (at (dec k)))]
                      ["next" (when (< k n) (at (inc k)))]])))))

(defn- archived
  "The documents of `feed` as an archived feed, as section 4 of RFC 5005
  defines it, at `url` and the URLs from `page-url-fn`. Each document holds
  `size` items.

  The subscription document at `url` has the newest items, and the
  archives come after it, the newest first. Archive n is at what
  `page-url-fn` gives for n, and its items are counted from the oldest item.

  Section 4 says that the items of an archive don't change, and a
  document links to an archive only when the archive is complete. The
  section doesn't say how to divide the items. Here, an archive becomes a
  document once the feed has an item that's newer than all of its items.
  The items of an archive then don't change when the feed gets an item."
  [feed size url page-url-fn]
  (let [items    (vec (:rss/items feed))
        oldest   (vec (rseq items))
        archives (quot (max 0 (dec (count items))) size)
        archive  (fn [k]
                   (let [chunk (subvec oldest (* (dec k) size) (* k size))
                         older (when (< 1 k)
                                 (page-url-fn (dec k)))
                         newer (when (< k archives)
                                 (page-url-fn (inc k)))]
                     (-> (document feed (page-url-fn k) (rseq chunk)
                                   [["current" url]
                                    ["prev-archive" older]
                                    ["next-archive" newer]])
                         (assoc-in [:feed :fh/archive] true))))
        latest   (when (pos? archives)
                   (page-url-fn archives))]
    (into [(document feed url (take size items)
                     [["prev-archive" latest]])]
          (map archive)
          (range archives 0 -1))))

(defn pages
  "The documents that a host serves for `feed` when it's too long for one
  document, as RFC 5005 defines them, with the `opts` below.

  Each is a map of :url and :feed, in the order that a reader fetches
  them. The `opts` are:

  - :kind, :paged by default, or :archived
  - :page-size, the number of items in a document, page-size by default
  - :url, the URL of the feed, where its first document is, by default
    the atom:link rel=self of the feed
  - :page-url-fn, a function of a number that gives the URL of that page or
    archive, by default :url with the query parameter page or archive

  The pages of a paged feed, as in section 3 of the RFC, change with each
  new item. An archived feed, as in section 4, has a subscription document
  with the newest items, and archives that don't change. A reader fetches
  each archive once. Pages count from 1, the first page, and archives from
  1, the oldest.

  The items are in the order of the feed, the newest first. Each document
  has the channel of the feed, and the links of RFC 5005 in its
  atom:links. An archive has fh:archive."
  ([feed]
   (pages feed {}))
  ([feed {:keys [kind url page-url-fn] :or {kind :paged} :as opts}]
   {:pre [(map? opts)]}
   (let [size        (:page-size opts page-size)
         url         (or url (resolve/self-url feed))
         param       (case kind :paged "page" :archived "archive")
         page-url-fn (or page-url-fn #(url/with-query url {param %}))]
     (when-not (string? url)
       (throw (ex-info (str "The pages of a feed need its URL, as :url or as"
                            " its atom:link rel=self")
                       {:feed feed})))
     (when-not (and (integer? size)
                    (pos? size))
       (throw (ex-info "The size of a page is a whole number above 0"
                       {:page-size size})))
     (case kind
       :paged    (paged feed size url page-url-fn)
       :archived (archived feed size url page-url-fn)))))

(defn update-media-urls
  "The `feed` with the URL of each enclosure, and of each source of an
  alternate enclosure, replaced by what `f` gives for it.

  A value that isn't a string stays as it is. A host uses this to put a
  prefix or a token on the URLs of its media files."
  [feed f]
  (let [rewritten #(if (string? %) (f %) %)
        source    (fn [source]
                    (cond-> source
                      (:uri source) (update :uri rewritten)))
        sources   (fn [alternate]
                    (cond-> alternate
                      (:sources alternate)
                      (update :sources (partial mapv source))))
        item      (fn [item]
                    (cond-> item
                      (get-in item [:rss/enclosure :url])
                      (update-in [:rss/enclosure :url] rewritten)

                      (:podcast/alternate-enclosures item)
                      (update :podcast/alternate-enclosures
                              (partial mapv sources))))]
    (cond-> feed
      (:rss/items feed) (update :rss/items (partial mapv item)))))

(defn- update-images
  "The channel or the item `x` with the URL of each of its images, those
  of resolve/images, replaced by what `f` gives for it."
  [x f]
  (let [href #(cond-> % (:href %) (update :href f))
        url  #(cond-> % (:url %) (update :url f))]
    (cond-> x
      (:podcast/images x)                  (update :podcast/images
                                                   (partial mapv href))
      (get-in x [:itunes/image :href])     (update :itunes/image href)
      (get-in x [:googleplay/image :href]) (update :googleplay/image href)
      (get-in x [:rss/image :url])         (update :rss/image url)
      (:media/thumbnails x)                (update :media/thumbnails
                                                   (partial mapv url)))))

(defn update-file-urls
  "The `feed` with the URL of each file that it links to replaced by what
  `f` gives for it: those of update-media-urls, and the trailers, the
  transcripts, the chapters files and the images of the channel and of
  each item, the files that check/files lists.

  A value that isn't a string stays as it is. A host uses this, e.g., to
  point a show that it imports at the copies of all its files."
  [feed f]
  (let [rewritten #(if (string? %) (f %) %)
        url       #(cond-> % (:url %) (update :url rewritten))
        item      (fn [item]
                    (cond-> (update-images item rewritten)
                      (:podcast/transcripts item)
                      (update :podcast/transcripts (partial mapv url))

                      (:podcast/chapters item)
                      (update :podcast/chapters url)))]
    (cond-> (update-images (update-media-urls feed f) rewritten)
      (:podcast/trailers feed) (update :podcast/trailers (partial mapv url))
      (:rss/items feed)        (update :rss/items (partial mapv item)))))

(def token-param
  "The name of the query parameter that holds the token of a subscriber,
  unless the options give another :token-param."
  "token")

(defn for-subscriber
  "The `feed` as the private feed of the subscriber with the `token`, with
  the `opts` below.

  The host makes and keeps the tokens, and serves this feed at a URL with
  the token. The token can be a hidden text of
  dk.simongray.wary-fetch.secret, and the feed has it in its URLs as
  plain text. It goes into the query of the URL of each file that the
  feed links to, as update-file-urls finds them, so that the transcripts,
  the chapters and the images reach a subscriber from a server that checks
  the token, and into each link of the feed to its own documents. Each
  request then tells who the subscriber is. A URL that isn't an http or
  https URL gets no token.

  The feed is closed to the public, and podcast:block, itunes:block and
  googleplay:block keep it out of the directories. It names no WebSub hub,
  since a subscriber would give the URL of the feed to the hub. No
  standard defines the token of a subscriber, so the query parameter is a
  choice that the options can replace. The `opts` are:

  - :token-param, the name of the query parameter, token-param by default
  - :with-token, a function of a URL and the token that gives the URL of
    the subscriber, for a host that has the token in the path
  - :own-pred, a predicate of a URL, where the URLs that it refuses get no
    token, e.g. the media files on the server of another company
  - :block?, false for a feed that stays as open as it was"
  ([feed token]
   (for-subscriber feed token {}))
  ([feed token {:keys [with-token own-pred block?]
                param :token-param
                :or   {param token-param own-pred (constantly true) block? true}
                :as   opts}]
   {:pre [(map? opts)]}
   (let [token      (secret/reveal token)
         with-token (or with-token #(url/with-query %1 {param %2}))
         personal   (fn [url]
                      (if (and (url/web-url? url)
                               (own-pred url))
                        (with-token url token)
                        url))
         hub?       #(= "hub" (some-> (:rel %) str/lower-case))
         link       (fn [link]
                      (cond-> link
                        (document-link? link) (update :href personal)))
         links      (fn [links]
                      (into []
                            (comp (remove #(and block? (hub? %)))
                                  (map link))
                            links))]
     (cond-> (update-file-urls feed personal)
       (:atom/links feed) (update :atom/links links)
       block?             (assoc :podcast/blocks [{:content true}]
                                 :itunes/block true
                                 :googleplay/block true)))))
