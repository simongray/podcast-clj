(ns dk.simongray.podcast.directory.links
  "Links to a show and to an episode in the apps of listeners, and a link
  to a time in a media file.

  An app has its own form of link, and no standard defines it. Most forms
  hold a key that the app didn't make: the URL of the feed, the
  podcast:guid, the id of Apple Podcasts or the id of the Podcast Index.

  A link with the scheme of an app, such as podcast:// or pktc://, opens
  the app and subscribes. It works only where the app is. A link to the
  web page of an app works in each browser.

  A link to a time follows the one standard here: the media fragment of
  the W3C, which starts a media file at a time."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.feed.coerce :as coerce]
            [dk.simongray.podcast.feed.guid :as guid]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.util.bytes :as bytes]
            [dk.simongray.podcast.util.url :as url]))

(def apps
  "The forms of the links of each app, by an id of the app. The options
  can give another table as :apps.

  No standard defines these forms. They're those of the list Podcast
  Platform Links, https://github.com/nathangathright/podcast-platform-links,
  as the list was on 29 September 2026: the forms whose keys an app
  didn't make.

  An entry has the :name of the app, and its :type: :app, or :page for a
  web page that leads a listener to the apps. It has the forms of its
  links under :show, :episode and :subscribe, the first form first. A
  link of the kind :subscribe has the scheme of the app.

  A form is a vector of its parts. A text is a part as it is. A keyword is
  a key of ids, such as :apple-id. A pair is a key in an encoding, e.g.
  [:base64url :feed-url], and the encodings are these:

  - :encoded, the percent-encoding of a query
  - :base64url, the base64 of URLs
  - :hex, hexadecimal
  - :bare, the URL without its scheme"
  {:onetuner
   {:name    "1tuner" :type :app
    :show    [["https://1tuner.com/podcast/" :slug "/" [:base64url :feed-url]]]
    :episode [["https://1tuner.com/podcast/" :slug "/" [:base64url :feed-url]
               "/" [:encoded :episode-guid]]]}

   :antennapod
   {:name      "AntennaPod" :type :app
    :show      [["https://antennapod.org/deeplink/subscribe?url=" :feed-url]]
    :subscribe [["antennapod-subscribe://" [:bare :feed-url]]]}

   :anytime
   {:name "Anytime Player" :type :app
    :show [["https://anytimeplayer.app/subscribe?url=" :feed-url]]}

   :apple
   {:name      "Apple Podcasts" :type :app
    :show      [["https://podcasts.apple.com/podcast/id" :apple-id]]
    :subscribe [["podcast://" [:bare :feed-url]]]}

   :breez
   {:name    "Breez" :type :app
    :show    [["https://breez.link/p?feedURL=" [:encoded :feed-url]]]
    :episode [["https://breez.link/p?feedURL=" [:encoded :feed-url]
               "&episodeID=" [:encoded :episode-guid]]]}

   :castamatic
   {:name      "Castamatic" :type :app
    :show      [["https://castamatic.com/guid/" :podcast-guid]]
    :subscribe [["castamatic://" [:bare :feed-url]]]}

   :castbox
   {:name "Castbox" :type :app
    :show [["https://castbox.fm/vic/" :apple-id]]}

   :castro
   {:name      "Castro" :type :app
    :show      [["https://castro.fm/itunes/" :apple-id]]
    :subscribe [["castros://subscribe/" [:bare :feed-url]]]}

   :curiocaster
   {:name "CurioCaster" :type :app
    :show [["https://curiocaster.com/podcast/pi" :podcastindex-id]]}

   :downcast
   {:name      "Downcast" :type :app
    :subscribe [["downcast://" [:bare :feed-url]]]}

   :fountain
   {:name    "Fountain" :type :app
    :show    [["https://fountain.fm/show/" :podcastindex-id]]
    :episode [["https://fountain.fm/episode/" :podcastindex-episode-id]]}

   :goodpods
   {:name "Goodpods" :type :app
    :show [["https://www.goodpods.com/podcasts-aid/" :apple-id]]}

   :gpodder
   {:name      "gPodder" :type :app
    :show      [["https://gpodder.net/subscribe?url=" :feed-url]]
    :subscribe [["gpodder://" [:bare :feed-url]]]}

   :icatcher
   {:name      "iCatcher!" :type :app
    :subscribe [["icatcher://" [:bare :feed-url]]]}

   :metacast
   {:name "Metacast" :type :app
    :show [["https://metacast.app/itunes/" :apple-id]
           ["https://open.metacast.app/podcasts/" :podcast-guid]]}

   :moonfm
   {:name "Moon FM" :type :app
    :show [["https://moon.fm/itunes/" :apple-id]]}

   :overcast
   {:name      "Overcast" :type :app
    :show      [["https://overcast.fm/itunes" :apple-id]]
    :subscribe [["overcast://x-callback-url/add?url=" :feed-url]]}

   :playerfm
   {:name      "Player FM" :type :app
    :show      [["https://player.fm/subscribe?id=" [:encoded :feed-url]]]
    :subscribe [["playerfm://" [:bare :feed-url]]]}

   :pocketcasts
   {:name      "Pocket Casts" :type :app
    :show      [["https://pca.st/itunes/" :apple-id]]
    :subscribe [["pktc://subscribe/" [:bare :feed-url]]]}

   :podbean
   {:name "Podbean" :type :app
    :show [["https://www.podbean.com/itunes/" :apple-id]]}

   :podcastaddict
   {:name      "Podcast Addict" :type :app
    :show      [["https://podcastaddict.com/feed/" [:encoded :feed-url]]]
    :episode   [["https://podcastaddict.com/episode/"
                 [:encoded :enclosure-url]]]
    :subscribe [["podcastaddict://" [:bare :feed-url]]]}

   :podcastguru
   {:name      "Podcast Guru" :type :app
    :show      [["https://app.podcastguru.io/podcast/" :apple-id]
                ["https://app.podcastguru.io/podcast/X" [:hex :feed-url]]]
    :subscribe [["podcastguru://" [:bare :feed-url]]]}

   :podcastparrot
   {:name "Podcast Parrot" :type :app
    :show [["https://podcastparrot.app/show?url=" [:encoded :feed-url]]]}

   :podcastrepublic
   {:name "Podcast Republic" :type :app
    :show [["https://www.podcastrepublic.net/podcast/" :apple-id]]}

   :podcat
   {:name      "Podcat" :type :app
    :subscribe [["podcat://" [:bare :feed-url]]]}

   :podfriend
   {:name    "Podfriend" :type :app
    :episode [["https://podfriend.com/podcast/" :apple-id
               "/" [:encoded :episode-guid]]]}

   :podkicker
   {:name      "Podkicker" :type :app
    :subscribe [["podkicker://subscribe/" [:bare :feed-url]]]}

   :podlp
   {:name "PodLP" :type :app
    :show [["https://link.podlp.app/" :apple-id]
           ["https://link.podlp.app/" :podcast-guid]]}

   :podstation
   {:name "podStation" :type :app
    :show [["https://podstation.github.io/subscribe-ext/?feedURL=" :feed-url]]}

   :podurama
   {:name "Podurama" :type :app
    :show [["https://podurama.com/podcast/" :slug "-i" :apple-id]]}

   :podverse
   {:name "Podverse" :type :app
    :show [["https://api.podverse.fm/api/v1/podcast/podcastindex/"
            :podcastindex-id]]}

   :rssradio
   {:name      "RSSRadio" :type :app
    :subscribe [["rssradio://" [:bare :feed-url]]]}

   :sonnet
   {:name "Sonnet" :type :app
    :show [["https://sonnet.fm/p/" :apple-id]]}

   :steno
   {:name    "Steno.fm" :type :app
    :show    [["https://steno.fm/show/" :podcast-guid]]
    :episode [["https://steno.fm/show/" :podcast-guid
               "/episode/" [:base64url :episode-guid]]]}

   :thepodcastapp
   {:name "The Podcast App" :type :app
    :show [["https://thepodcastapp.dev/p/itunes" :apple-id]
           ["https://thepodcastapp.dev/p/" :podcastindex-id]]}

   :truefans
   {:name "TrueFans" :type :app
    :show [["https://truefans.fm/" :podcast-guid]]}

   :youtubemusic
   {:name "YouTube Music" :type :app
    :show [["https://music.youtube.com/library/podcasts?addrssfeed="
            [:base64url :feed-url]]]}

   :greatpods
   {:name "Great Pods" :type :page
    :show [["https://www.greatpods.co/podcasts?podcastIndexId="
            :podcastindex-id]
           ["https://www.greatpods.co/podcasts?itunesId=" :apple-id]]}

   :odesli
   {:name "Odesli" :type :page
    :show [["https://pods.link/i/" :apple-id]]}

   :plink
   {:name "Plink" :type :page
    :show [["https://plinkhq.com/i/" :apple-id "?to=page"]]}

   :podengine
   {:name "Pod Engine" :type :page
    :show [["https://www.podengine.ai/podcasts/id/" :apple-id]]}

   :podlink
   {:name "pod.link" :type :page
    :show [["https://pod.link/" :apple-id]
           ["https://pod.link/" [:base64url :feed-url]]]}

   :podcastdetails
   {:name "Podcast Details" :type :page
    :show [["https://podcastdetails.com/?url=" :feed-url]]}

   :podcastindex
   {:name "Podcast Index" :type :page
    :show [["https://podcastindex.org/podcast/" :podcastindex-id]
           ["https://podcastindex.org/podcast/" :podcast-guid]]}

   :podcastxray
   {:name "Podcast X-Ray" :type :page
    :show [["https://podcastxray.com/podcast/" :apple-id]]}

   :podchaser
   {:name "Podchaser" :type :page
    :show [["https://podchaser.com/f/pod/" :apple-id]]}

   :podfollow
   {:name "podfollow" :type :page
    :show [["https://podfollow.com/" :apple-id]]}

   :podnews
   {:name "Podnews" :type :page
    :show [["https://podnews.net/podcast/" :apple-id]
           ["https://podnews.net/podcast/pi" :podcastindex-id]
           ["https://podnews.net/podcast/" :podcast-guid]]}})

(def kinds
  "The kinds of link that an entry of apps can have:

  - :show, to the page of a show
  - :episode, to the page of an episode
  - :subscribe, to the app itself, which then subscribes to the show"
  [:show :episode :subscribe])

(def default-slug
  "The text in the place of a link that an app doesn't read, which the
  forms name :slug. The :slug of the ids takes its place."
  "podcast")

(defn ids
  "The keys that name `show` and its episode `item` in the links of the
  apps. The :url of `opts` is the URL of a feed that doesn't name its own.

      {:feed-url \"…\" :podcast-guid \"…\" :apple-id \"…\"
       :podcastindex-id 920666 :episode-guid \"…\" :enclosure-url \"…\"
       :podcastindex-episode-id 123}

  A key is absent when the show doesn't have it. The `show` is a parsed
  feed, or a show of a directory, which has the ids of the directories.
  The `item` is an item of the feed, or an episode from the Podcast
  Index. A feed names its URL in its atom:link rel=self.

  The podcast:guid is that of the feed, or else the guid that the podcast
  namespace derives from the URL. The Podcast Index derives the same guid
  for a feed that declares none. A show from a directory has only the
  guid that the directory knows the feed declares, since the feed may
  declare another than its URL gives."
  ([show]
   (ids show nil {}))
  ([show item]
   (ids show item {}))
  ([show item {:keys [url] :as opts}]
   {:pre [(map? opts)]}
   (let [url        (or url (resolve/self-url show))
         source     (fn [k]
                      (or (get-in show [:directory/sources k])
                          (when (= k (:directory/source show))
                            (:directory/id show))))
         guid       (if (:directory/source show)
                      (:podcast/guid show)
                      (guid/feed-guid show url))
         apple-id   (or (source :apple) (:index/itunes-id show))
         index-id   (or (source :podcastindex) (:index/id show))
         item-guid  (not-empty (get-in item [:rss/guid :content]))
         episode-id (when (:index/feed-id item) (:index/id item))]
     (into {} (remove (comp nil? val))
           {:feed-url                url
            :podcast-guid            guid
            :apple-id                apple-id
            :podcastindex-id         index-id
            :episode-guid            item-guid
            :enclosure-url           (get-in item [:rss/enclosure :url])
            :podcastindex-episode-id episode-id}))))

(defn- encoding-of
  "The encoding and the key of the part `p` of a form that names a key."
  [p]
  (if (keyword? p) [:plain p] p))

(defn- part
  "The text of the part `p` of a form, for the keys `ids`, or nil when the
  ids don't have the key of the part."
  [p ids]
  (if (string? p)
    p
    (let [[encoding k] (encoding-of p)
          v            (if (= :slug k) (:slug ids default-slug) (get ids k))]
      (when (some? v)
        (case encoding
          :plain     (str v)
          :encoded   (url/percent-encode v)
          :base64url (bytes/base64url (bytes/encode (str v)))
          :hex       (bytes/hex (bytes/encode (str v)))
          :bare      (str/replace (str v) #"(?i)^[a-z][a-z0-9+.-]*://" ""))))))

(defn- written
  "The link that the form `form` gives for the keys `ids`, or nil when the
  ids lack a key of the form."
  [form ids]
  (let [parts (map #(part % ids) form)]
    (when (every? some? parts)
      (apply str parts))))

(defn link
  "The link of the kind `kind` of the app `app` for the keys `ids`. The
  :apps of `opts` replace the table of apps.

  It's nil when the app has no such link, or when the ids lack the keys
  of each of its forms. The `kind` is one of kinds."
  ([app kind ids]
   (link app kind ids {}))
  ([app kind ids opts]
   {:pre [(map? opts)]}
   (some #(written % ids) (get-in (:apps opts apps) [app kind]))))

(defn- links-of
  "The link of each of kinds that `app` has for the keys `ids`, by kind,
  with the :apps of `opts`."
  [app ids opts]
  (into {}
        (keep (fn [kind]
                (when-let [url (link app kind ids opts)]
                  [kind url])))
        kinds))

(defn links
  "The links of each app that `opts` choose for the keys `ids`, in the
  order of the names of the apps. An app without a link for the ids is
  left out.

  Each is a map with the :app, its :name and its :type, and a link for
  each of kinds that the ids are enough for. The `opts` are these:

  - :apps, a table in place of apps
  - :only, a predicate of the id of an app, such as a set of ids"
  ([ids]
   (links ids {}))
  ([ids {:keys [only] :or {only (constantly true)} :as opts}]
   {:pre [(map? opts)]}
   (->> (for [[app entry] (:apps opts apps)
              :when (only app)
              :let  [found (links-of app ids opts)]
              :when (seq found)]
          (merge {:app app} (select-keys entry [:name :type]) found))
        (sort-by (comp str/lower-case str :name))
        vec)))

(def ^:no-doc id-patterns
  "The regex of the text of each key of the ids in a link. A key that
  isn't here is the text of one part of a path or a query."
  (let [hex #(str "[0-9a-fA-F]{" % "}")]
    {:apple-id                "[0-9]+"
     :podcastindex-id         "[0-9]+"
     :podcastindex-episode-id "[0-9]+"
     :podcast-guid            (str/join "-" (map hex [8 4 4 4 12]))
     :feed-url                ".+"}))

(defn- quoted
  "The text `s` as a regex that matches it as it is."
  [s]
  (let [special #{\\ \^ \$ \. \| \? \* \+ \( \) \[ \] \{ \}}]
    (str/escape s #(when (special %) (str "\\" %)))))

(defn- web-start
  "The regex of `s`, the start of a form of a link on the web. It takes
  http or https, with or without www., and one more part of the path
  before each part of `s` that has a name. So the form of Apple Podcasts
  reads the links that it shares, which have a country and the name of
  the show in their paths."
  [s]
  (let [web-url             #"(?i)https?://(?:www\.)?([^/?#]*)([^?#]*)(.*)"
        [_ host path query] (re-matches web-url s)
        segment             (fn [part]
                              (str (when (< 1 (count part)) "(?:/[^/?#]+)?")
                                   (quoted part)))]
    (str "https?://(?:www\\.)?" (quoted host)
         (apply str (map segment (re-seq #"/[^/]*" path)))
         (quoted query))))

(defn- part-pattern
  "The regex of the text of the part `p` of a form that names a key."
  [p]
  (let [[encoding k] (encoding-of p)]
    (case encoding
      :encoded   "[^&#]+"
      :base64url "[A-Za-z0-9_-]+"
      :hex       "[0-9a-fA-F]+"
      (get id-patterns k "[^/?#&]+"))))

(defn- form-pattern
  "The regex that reads a link of `form`, with a group for each key."
  [[start & parts :as form]]
  (let [web?       (re-find #"(?i)^https?://" start)
        pattern-of #(if (string? %)
                      (quoted %)
                      (str "(" (part-pattern %) ")"))]
    (re-pattern (str "(?i)^"
                     (if web? (web-start start) (quoted start))
                     (apply str (map pattern-of parts))
                     "(?:[/?#].*)?$"))))

(defn- utf8
  "The bytes `b` as UTF-8 text, or nil for no bytes."
  [b]
  (when b
    (bytes/decode b 0 (bytes/length b) :utf8)))

(defn- value-of
  "The value of the key that the part `p` of a form names, read from the
  `text` of the part in a link, or nil when the text doesn't read as one.

  A feed URL that isn't encoded in the form is decoded anyway when it is,
  as some apps encode it. One in a bare form keeps no scheme."
  [p text]
  (let [[encoding k] (encoding-of p)
        encoded-url? (and (= [:plain :feed-url] [encoding k])
                          (not (str/includes? text "://")))
        v            (case encoding
                       :encoded   (url/percent-decode text)
                       :base64url (utf8 (bytes/from-base64url text))
                       :hex       (utf8 (bytes/from-hex text))
                       (if encoded-url? (url/percent-decode text) text))
        with-scheme  (cond->> v (= :bare encoding) (str "https://"))]
    (case k
      :feed-url
      (when (url/web-url? with-scheme) v)

      (:podcastindex-id :podcastindex-episode-id)
      (some-> v parse-long)

      v)))

(defn- read-form
  "The ids that the link `url` holds by `form`, or nil when the form
  doesn't read the link."
  [form url]
  (let [found (re-find (form-pattern form) url)
        keyed (remove string? (rest form))
        ks    (map (comp second encoding-of) keyed)]
    (when (vector? found)
      (let [values (map value-of keyed (rest found))]
        (when (every? some? values)
          (dissoc (zipmap ks values) :slug))))))

(defn link-ids
  "The ids that the link `url` of an app holds, read with the forms of
  the apps table or of the :apps of `opts`. Nil when no form reads it.

  It reads a link more loosely than links are written: with http or https,
  with or without www., and with one more part of the path before each
  part of the form, such as the country and the name of a show in a link
  that Apple Podcasts shares. A feed URL that a link holds without its
  scheme comes without one."
  ([url]
   (link-ids url {}))
  ([url opts]
   {:pre [(map? opts)]}
   (->> (for [entry (vals (:apps opts apps))
              kind  kinds
              form  (get entry kind)]
          form)
        (keep #(read-form % (str url)))
        (filter #(some % [:feed-url :apple-id :podcast-guid :podcastindex-id]))
        first)))

(defn at-time
  "The URL `url` of a media file, to play from `start` seconds, and to
  `end` seconds when there is one. A player that knows media fragments
  starts there, such as the audio element of a browser.

  The link has the fragment of section 4.2.1 of Media Fragments URI 1.0,
  the W3C Recommendation of 25 September 2012, e.g. the fragment t=754 or
  t=754,796.5. It takes the place of a fragment that the URL has. A start
  that's nil is the start of the file."
  ([url start]
   (at-time url start nil))
  ([url start end]
   (let [seconds #(when (and (number? %)
                             (not (neg? %))
                             (not (infinite? %))
                             (== % %))
                    (coerce/plain-decimal %))
         [a b]   [(seconds start) (seconds end)]
         base    (str/replace (str url) #"#.*$" "")]
     (cond
       (and a b) (str base "#t=" a "," b)
       b         (str base "#t=," b)
       a         (str base "#t=" a)
       :else     base))))

(comment
  (def show
    {:rss/title         "Podcasting 2.0"
     :podcast/guid      "917393e3-1b1e-5cef-ace4-edaa54e1f810"
     :atom/links        [{:rel  "self"
                          :href "https://feeds.podcastindex.org/pc20.xml"}]
     :directory/sources {:apple "1524356573" :podcastindex 920666}})

  (ids show)
  (ids show {:rss/guid      {:content "PC20-272"}
             :rss/enclosure {:url "https://example.org/272.mp3"}})

  (link :pocketcasts :show (ids show))
  (link :apple :subscribe (ids show))
  (links (ids show) {:only #{:apple :antennapod :fountain :podlink}})
  (count (links (ids show)))

  ;; a feed alone has its URL, so it has the links that take the URL
  (map :name (links {:feed-url "https://feeds.podcastindex.org/pc20.xml"}))

  ;; and back: the ids in a link that an app shares
  (link-ids "https://podcasts.apple.com/us/podcast/podcasting-2-0/id1584274529")
  (link-ids "pktc://subscribe/feeds.podcastindex.org/pc20.xml")

  ;; a clip of an episode, as a link to its media file
  (at-time "https://example.org/272.mp3" 754 796.5)
  #_.)
