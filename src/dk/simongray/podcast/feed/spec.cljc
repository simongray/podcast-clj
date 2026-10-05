(ns dk.simongray.podcast.feed.spec
  "The vocabulary of the library as data: every element that it
  understands.

  Each entry of elements describes one XML element: what the model calls it,
  where it can appear, how often, and the types of its attributes and
  text. The parser, the emitter and the validator interpret this data, so
  adding a tag or an attribute is a change to the data here and nowhere
  else. The keys of an entry are:

  - :tag, the XML name, namespaced by alias, e.g. :podcast/transcript or
    :title
  - :key, the key in the model, or a map from parent to key when the
    element has another name under another parent
  - :parent, where it can appear: :channel, :item, which covers
    podcast:liveItem too, or the tag of its parent element
  - :count, :one or :many, or a map from parent to either
  - :attrs, the specified attributes by XML name, each with an optional
    :type from dk.simongray.podcast.feed.coerce/types, :required, and
    :values for an :enum
  - :content, the type of the node text, :none when the element has none,
    or :presence for an empty element that's true when it's there
  - :values, the allowed values of the node text of an enumeration
  - :deprecated, true, or the tag that replaces it
  - :children-as, the context to look up the children of the element in,
    when it borrows those of another element, as liveItem does with item
  - :doc, one line from the specification

  The function registry indexes elements, or elements and an app's own
  entries, into the value that every interpreter takes as the :registry
  of its options. The registry default is elements indexed.")

(def mediums
  "The values of podcast:medium, without the list variants."
  #{"podcast" "music" "video" "film" "audiobook" "newsletter" "blog"
    "publisher" "course"})

(def list-mediums
  "The list variants of mediums, and the mixed list."
  (into #{"mixed"} (map #(str % "L")) mediums))

;; socialprotocols.txt of the podcast namespace, as of 2026-09-30
(def social-protocols
  "The protocols that podcast:socialInteract can name."
  #{"disabled" "activitypub" "twitter" "lightning" "atproto" "hive" "matrix"
    "nostr"})

(def chat-protocols
  "The protocols that podcast:chat can name."
  #{"irc" "xmpp" "nostr" "matrix"})

(def value-types
  "The payment networks that a podcast:value block can name."
  #{"lightning" "bitcoin"})

(def value-methods
  "The payment methods that a podcast:value block can name."
  #{"keysend" "amp" "lnaddress" "default"})

(def recipient-types
  "The kinds of address that a podcast:valueRecipient can have."
  #{"node" "lnaddress" "wallet"})

;; serviceslugs.txt of the podcast namespace, as of 2026-09-30
(def service-slugs
  "The platform slugs of podcast:block."
  #{"acast" "amazon" "anchor" "apple" "audible" "audioboom" "backtracks"
    "bitcoin" "blubrry" "buzzsprout" "captivate" "castos" "castopod" "facebook"
    "fireside" "fyyd" "google" "gpodder" "hypercatcher" "kasts" "libsyn"
    "mastodon" "megafono" "megaphone" "omnystudio" "overcast" "paypal"
    "pinecast" "podbean" "podcastaddict" "podcastguru" "podcastindex"
    "podcasts" "podchaser" "podcloud" "podfriend" "podiant" "podigee" "podnews"
    "podomatic" "podserve" "podverse" "redcircle" "relay" "resonaterecordings"
    "rss" "shoutengine" "simplecast" "slack" "soundcloud" "spotify" "spreaker"
    "tiktok" "transistor" "twitter" "whooshkaa" "youtube" "zencast"})

(def transcript-types
  "The MIME types that the transcript specification names, and the
  variants in use."
  #{"text/plain" "text/html" "text/vtt" "application/json"
    "application/x-subrip" "application/srt" "text/srt"})

;; taxonomy.json of the podcast namespace, as of 2026-09-30
(def taxonomy
  "The groups of the Podcast Taxonomy Project and the roles in each, in
  lower case, which podcast:person draws from."
  {"creative direction"    ["director" "assistant director" "executive producer"
                            "senior producer" "producer" "associate producer"
                            "development producer" "creative director"]
   "cast"                  ["host" "co-host" "guest host" "guest" "voice actor"
                            "narrator" "announcer" "reporter"]
   "writing"               ["author" "editorial director" "co-writer" "writer"
                            "songwriter" "guest writer" "story editor"
                            "managing editor" "script editor"
                            "script coordinator" "researcher" "editor"
                            "fact checker" "translator" "transcriber" "logger"]
   "audio production"      ["studio coordinator" "technical director"
                            "technical manager" "audio engineer"
                            "remote recording engineer"
                            "post production engineer"]
   "audio post-production" ["audio editor" "sound designer" "foley artist"
                            "composer" "theme music" "music production"
                            "music contributor"]
   "administration"        ["production coordinator" "booking coordinator"
                            "production assistant" "content manager"
                            "marketing manager" "sales representative"
                            "sales manager"]
   "visuals"               ["graphic designer" "cover art designer"]
   "community"             ["social media manager"]
   "misc."                 ["consultant" "intern"]
   "video production"      ["camera operator" "lighting designer" "camera grip"
                            "assistant camera"]
   "video post-production" ["editor" "assistant editor"]})

(def roles
  "Every role in taxonomy."
  (into #{} (mapcat val) taxonomy))

(def ^:no-doc url
  "Attribute shorthand: a URL."
  {:type :url})

(def ^:no-doc req-url
  "Attribute shorthand: a required URL."
  {:type :url :required true})

(def ^:no-doc req
  "Attribute shorthand: required text."
  {:required true})

(def ^:no-doc integer
  "Attribute shorthand: an integer."
  {:type :integer})

(def ^:no-doc decimal
  "Attribute shorthand: a decimal number."
  {:type :decimal})

(def ^:no-doc bool
  "Attribute shorthand: true or false."
  {:type :boolean})

(def ^:no-doc media-parents
  "Where an optional Media RSS element can be: beside a media object, in
  the channel or an item, or inside one."
  #{:channel :item :media/content :media/group})

(defn ^:no-doc media-key
  "The :key of an optional Media RSS element whose plain key is `k`: `k`
  inside a media object, and `k` in the media namespace beside one."
  [k]
  {:default       (keyword "media" (name k))
   :media/content k
   :media/group   k})

(def elements
  "Every element that the library understands, in the order of emit."
  [;; RSS 2.0, channel
   {:tag :title :key :rss/title
    :parent #{:channel :item} :count :one
    :doc "The name of the channel or item."}
   {:tag :link :key :rss/link
    :parent #{:channel :item} :count :one :content :url
    :doc "The URL of the website or web page."}
   {:tag :description :key :rss/description
    :parent #{:channel :item} :count :one :content :html
    :doc "Phrase or sentence describing the channel; the item synopsis or notes."}
   {:tag :language :key :rss/language
    :parent #{:channel} :count :one :content :language
    :doc "The language the channel is written in."}
   {:tag :copyright :key :rss/copyright
    :parent #{:channel} :count :one
    :doc "Copyright notice for content in the channel."}
   {:tag :managingEditor :key :rss/managing-editor
    :parent #{:channel} :count :one :content :email
    :doc "Email address for the person responsible for editorial content."}
   {:tag :webMaster :key :rss/web-master
    :parent #{:channel} :count :one :content :email
    :doc "Email address for the person responsible for technical issues."}
   {:tag :pubDate :key :rss/pub-date
    :parent #{:channel :item} :count :one :content :rfc2822
    :doc "The publication date."}
   {:tag :lastBuildDate :key :rss/last-build-date
    :parent #{:channel} :count :one :content :rfc2822
    :doc "The last time the content of the channel changed."}
   {:tag :category :key :rss/categories
    :parent #{:channel :item} :count :many
    :attrs {:domain url}
    :doc "One or more categories that the channel or item belongs to."}
   {:tag :generator :key :rss/generator
    :parent #{:channel} :count :one
    :doc "A string indicating the program used to generate the channel."}
   {:tag :docs :key :rss/docs
    :parent #{:channel} :count :one :content :url
    :doc "A URL that points to the documentation for the format used."}
   {:tag :cloud :key :rss/cloud
    :parent #{:channel} :count :one :content :none
    :attrs {:domain            {}
            :port              integer
            :path              {}
            :registerProcedure {}
            :protocol          {}}
    :doc "Allows processes to register with a cloud to be notified of updates."}
   {:tag :ttl :key :rss/ttl
    :parent #{:channel} :count :one :content :integer
    :doc "Number of minutes the channel can be cached before refreshing."}
   {:tag :image :key :rss/image
    :parent #{:channel} :count :one :content :none
    :doc "A GIF, JPEG or PNG image that can be displayed with the channel."}
   {:tag :url :key :url
    :parent #{:image} :count :one :content :url
    :doc "The URL of the image."}
   {:tag :title :key :title
    :parent #{:image} :count :one
    :doc "Describes the image; the ALT attribute when rendered in HTML."}
   {:tag :link :key :link
    :parent #{:image} :count :one :content :url
    :doc "The URL of the site; the image links to it when rendered."}
   {:tag :width :key :width
    :parent #{:image} :count :one :content :integer
    :doc "Width of the image in pixels; at most 144."}
   {:tag :height :key :height
    :parent #{:image} :count :one :content :integer
    :doc "Height of the image in pixels; at most 400."}
   {:tag :description :key :description
    :parent #{:image} :count :one
    :doc "Text for the TITLE attribute of the link formed around the image."}
   {:tag :rating :key :rss/rating
    :parent #{:channel} :count :one
    :doc "The PICS rating for the channel."}
   {:tag :skipHours :key :rss/skip-hours
    :parent #{:channel} :count :one :content :none
    :doc "Hours (GMT) during which aggregators may skip reading the channel."}
   {:tag :hour :key :hours
    :parent #{:skipHours} :count :many :content :integer
    :doc "An hour of the day, 0 to 23."}
   {:tag :skipDays :key :rss/skip-days
    :parent #{:channel} :count :one :content :none
    :doc "Days during which aggregators may skip reading the channel."}
   {:tag :day :key :days
    :parent #{:skipDays} :count :many
    :doc "A day of the week, Monday to Sunday."}

   ;; RSS 2.0, item
   {:tag :author :key :rss/author
    :parent #{:item} :count :one :content :email
    :doc "Email address of the author of the item."}
   {:tag :author :key :rss/author
    :parent #{:channel} :count :one
    :doc "Not in RSS 2.0; read because so many feeds name their author here."}
   {:tag :comments :key :rss/comments
    :parent #{:item} :count :one :content :url
    :doc "URL of a page for comments relating to the item."}
   {:tag :enclosure :key :rss/enclosure
    :parent #{:item} :count :one :content :none
    :attrs {:url req-url :length integer :type {:type :mime}}
    :doc "The media object attached to the item."}
   {:tag :guid :key :rss/guid
    :parent #{:item} :count :one
    :attrs {:isPermaLink bool}
    :doc "A string that uniquely identifies the item."}
   {:tag :source :key :rss/source
    :parent #{:item} :count :one
    :attrs {:url req-url}
    :doc "The RSS channel that the item came from."}

   ;; Atom, Dublin Core, content, syndication
   {:tag :atom/link :key :atom/links
    :parent #{:channel :item} :count :many :content :none
    :attrs {:href     req-url
            :rel      {}
            :type     {:type :mime}
            :title    {}
            :hreflang {:type :language}}
    :doc "A link; rel=self gives the canonical feed URL, rel=next pages the archive."}
   {:tag :content/encoded :key :content/encoded
    :parent #{:channel :item} :count :one :content :html
    :doc "The full HTML content of the item; some hosts give the channel one too."}
   {:tag :dc/creator :key :dc/creator
    :parent #{:channel :item} :count :one
    :doc "The entity primarily responsible for making the content."}
   {:tag :sy/updatePeriod :key :sy/update-period
    :parent #{:channel} :count :one
    :content :enum :values #{"hourly" "daily" "weekly" "monthly" "yearly"}
    :doc "The period over which the channel is updated."}
   {:tag :sy/updateFrequency :key :sy/update-frequency
    :parent #{:channel} :count :one :content :integer
    :doc "How many times the channel is updated per period."}

   ;; feed paging and archiving: RFC 5005, sections 2 and 4, and appendix B
   ;; for their place in the channel of RSS 2.0
   {:tag :fh/complete :key :fh/complete
    :parent #{:channel} :count :one :content :presence
    :doc "The document holds every item of the feed."}
   {:tag :fh/archive :key :fh/archive
    :parent #{:channel} :count :one :content :presence
    :doc "The document is an archive of the feed, and its items do not change."}

   ;; iTunes, channel
   {:tag :itunes/title :key :itunes/title
    :parent #{:channel :item} :count :one
    :doc "The title specific to Apple Podcasts."}
   {:tag :itunes/author :key :itunes/author
    :parent #{:channel :item} :count :one
    :doc "The group responsible for creating the show."}
   {:tag :itunes/subtitle :key :itunes/subtitle
    :parent #{:channel :item} :count :one
    :doc "A short description; dropped from Apple's specification."}
   {:tag :itunes/summary :key :itunes/summary
    :parent #{:channel :item} :count :one :content :html
    :doc "A description; dropped from Apple's specification but widely read."}
   {:tag :itunes/image :key :itunes/image
    :parent #{:channel :item} :count :one :content :none
    :attrs {:href req-url}
    :doc "The artwork for the show or episode."}
   {:tag :itunes/category
    :key {:default :itunes/categories :itunes/category :categories}
    :parent #{:channel :itunes/category} :count :many :content :none
    :attrs {:text req}
    :doc "The show category; may nest one subcategory."}
   {:tag :itunes/explicit :key :itunes/explicit
    :parent #{:channel :item} :count :one :content :boolean
    :doc "Parental advisory information: true or false."}
   {:tag :itunes/type :key :itunes/type
    :parent #{:channel} :count :one
    :content :enum :values #{"episodic" "serial"}
    :doc "Whether episodes are meant to be consumed in order."}
   {:tag :itunes/owner :key :itunes/owner
    :parent #{:channel} :count :one :content :none
    :doc "The podcast owner contact information, not displayed."}
   {:tag :itunes/name :key :name
    :parent #{:itunes/owner} :count :one
    :doc "The owner's name."}
   {:tag :itunes/email :key :email
    :parent #{:itunes/owner} :count :one :content :email
    :doc "The owner's email address."}
   {:tag :itunes/new-feed-url :key :itunes/new-feed-url
    :parent #{:channel} :count :one :content :url
    :doc "The new podcast RSS feed URL when the feed has moved."}
   {:tag :itunes/block :key :itunes/block
    :parent #{:channel :item} :count :one :content :yes-no
    :doc "Yes hides the show or episode from Apple Podcasts."}
   {:tag :itunes/complete :key :itunes/complete
    :parent #{:channel} :count :one :content :yes-no
    :doc "Yes means no more episodes will ever be published."}
   {:tag :itunes/keywords :key :itunes/keywords
    :parent #{:channel :item} :count :one :deprecated true
    :doc "Comma-separated keywords; no longer read by Apple."}
   {:tag :itunes/applepodcastsverify :key :itunes/applepodcastsverify
    :parent #{:channel} :count :one
    :doc "The token Apple Podcasts Connect issues to prove ownership of the feed."}

   ;; iTunes, item
   {:tag :itunes/duration :key :itunes/duration
    :parent #{:item} :count :one :content :duration
    :doc "The duration of the episode, in seconds."}
   {:tag :itunes/episode :key :itunes/episode
    :parent #{:item} :count :one :content :integer
    :doc "The episode number; required for serial shows."}
   {:tag :itunes/season :key :itunes/season
    :parent #{:item} :count :one :content :integer
    :doc "The season number."}
   {:tag :itunes/episodeType :key :itunes/episode-type
    :parent #{:item} :count :one
    :content :enum :values #{"full" "trailer" "bonus"}
    :doc "Whether the item is a full episode, a trailer or bonus content."}
   {:tag :itunes/isClosedCaptioned :key :itunes/closed-captioned?
    :parent #{:item} :count :one :content :yes-no :deprecated true
    :doc "Yes when the video episode has closed captions; historical."}
   {:tag :itunes/order :key :itunes/order
    :parent #{:item} :count :one :content :integer :deprecated true
    :doc "Overrides the default ordering of episodes; historical."}

   ;; Google Play and Spotify
   {:tag :googleplay/author :key :googleplay/author
    :parent #{:channel :item} :count :one
    :doc "The author of the show or episode, as Google Play showed it."}
   {:tag :googleplay/description :key :googleplay/description
    :parent #{:channel :item} :count :one :content :html
    :doc "The description of the show or episode, plain text or HTML."}
   {:tag :googleplay/explicit :key :googleplay/explicit
    :parent #{:channel :item} :count :one
    :content :enum :values #{"yes" "no" "clean"}
    :doc "Whether the show or episode has explicit content: yes, no or clean."}
   {:tag :googleplay/image :key :googleplay/image
    :parent #{:channel :item} :count :one :content :none
    :attrs {:href req-url}
    :doc "The artwork of the show or episode."}
   {:tag :googleplay/block :key :googleplay/block
    :parent #{:channel :item} :count :one :content :yes-no
    :doc "Yes to keep the show or episode out of Google Play."}
   {:tag :googleplay/email :key :googleplay/email
    :parent #{:channel} :count :one :content :email
    :doc "The email address Google Play verified the show's ownership with."}
   {:tag :googleplay/category :key :googleplay/categories
    :parent #{:channel} :count :many :content :none
    :attrs {:text req}
    :doc "The category of the show, from Google Play's list."}
   {:tag :googleplay/new-feed-url :key :googleplay/new-feed-url
    :parent #{:channel} :count :one :content :url
    :doc "The new URL of a feed that has moved."}
   {:tag :spotify/countryOfOrigin :key :spotify/country-of-origin
    :parent #{:channel} :count :one
    :doc "Space-separated ISO 3166 country codes for Spotify's targeting."}
   {:tag :spotify/limit :key :spotify/limit
    :parent #{:channel} :count :one :content :none
    :attrs {:recentCount integer}
    :doc "How many recent episodes Spotify should show."}

   ;; RawVoice, the Blubrry and PowerPress module
   {:tag :rawvoice/rating :key :rawvoice/rating
    :parent #{:channel} :count :one
    :attrs {:tv {} :movie {}}
    :doc "A TV or movie parental rating such as TV-PG."}
   {:tag :rawvoice/location :key :rawvoice/location
    :parent #{:channel} :count :one
    :doc "Where the show is produced, as free text."}
   {:tag :rawvoice/frequency :key :rawvoice/frequency
    :parent #{:channel} :count :one
    :doc "How often episodes come out, as free text."}
   {:tag :rawvoice/poster :key :rawvoice/poster
    :parent #{:item} :count :one :content :none
    :attrs {:url req-url}
    :doc "The poster image of a video episode."}
   {:tag :rawvoice/isHd :key :rawvoice/hd?
    :parent #{:item} :count :one :content :yes-no
    :doc "Yes when the video episode is high definition."}
   {:tag :rawvoice/embed :key :rawvoice/embed
    :parent #{:item} :count :one :content :html
    :doc "HTML that embeds the episode's player."}
   {:tag :rawvoice/webm :key :rawvoice/webm
    :parent #{:item} :count :one :content :none
    :attrs {:src req-url :type {:type :mime} :length integer}
    :doc "A WebM rendition of the video episode."}
   {:tag :rawvoice/mp4 :key :rawvoice/mp4
    :parent #{:item} :count :one :content :none
    :attrs {:src req-url :type {:type :mime} :length integer}
    :doc "An MP4 rendition of the video episode."}
   {:tag :rawvoice/metamark :key :rawvoice/metamarks
    :parent #{:item} :count :many
    :attrs {:type     {:required true :type :enum
                       :values #{"video" "audio" "image" "comment" "tag" "ad"
                                 "lowerthird"}}
            :link     url
            :position {:required true :type :decimal}
            :duration decimal}
    :doc "A point in the episode with something attached: an ad, a tag, a comment."}

   ;; Media RSS. Its optional elements may sit beside a media object or
   ;; inside it, under media:content or media:group, where they get plain
   ;; keys; hosts do both.
   {:tag :media/group :key :media/group
    :parent #{:item} :count :one :content :none
    :doc "Renditions of one media object, as media:content children."}
   {:tag :media/content :key {:default :media/contents :media/group :contents}
    :parent #{:item :media/group} :count :many :content :none
    :attrs {:url          url
            :fileSize     integer
            :type         {:type :mime}
            :medium       {}
            :isDefault    bool
            :expression   {}
            :bitrate      decimal
            :framerate    decimal
            :samplingrate decimal
            :channels     integer
            :duration     integer
            :height       integer
            :width        integer
            :lang         {:type :language}}
    :doc "A media object; some hosts use it beside or instead of enclosure."}
   {:tag :media/title :key (media-key :title)
    :parent media-parents :count :one
    :attrs {:type {:type :enum :values #{"plain" "html"}}}
    :doc "The title of the media object."}
   {:tag :media/description :key (media-key :description)
    :parent media-parents :count :one
    :attrs {:type {:type :enum :values #{"plain" "html"}}}
    :doc "A short description of the media object."}
   {:tag :media/thumbnail :key (media-key :thumbnails)
    :parent media-parents :count :many :content :none
    :attrs {:url req-url :width integer :height integer :time {}}
    :doc "A representative image."}
   {:tag :media/player :key (media-key :player)
    :parent #{:item :media/content :media/group} :count :one :content :none
    :attrs {:url req-url :height integer :width integer}
    :doc "A web page that plays the media."}
   {:tag :media/credit :key (media-key :credits)
    :parent media-parents :count :many
    :attrs {:role {} :scheme {}}
    :doc "Someone who contributed to the media, by role."}
   {:tag :media/rating :key (media-key :ratings)
    :parent media-parents :count :many
    :attrs {:scheme url}
    :doc "A rating such as adult or nonadult."}
   {:tag :media/category :key (media-key :categories)
    :parent media-parents :count :many
    :attrs {:scheme url :label {}}
    :doc "A category from a Media RSS scheme."}
   {:tag :media/copyright :key (media-key :copyright)
    :parent media-parents :count :one
    :attrs {:url url}
    :doc "Copyright information for the media."}
   {:tag :media/keywords :key (media-key :keywords)
    :parent media-parents :count :one
    :doc "Comma-separated keywords for the media."}
   {:tag :media/restriction :key (media-key :restrictions)
    :parent media-parents :count :many
    :attrs {:relationship req :type {}}
    :doc "Restrictions on the media, e.g. by country."}

   ;; Podlove Simple Chapters
   {:tag :psc/chapters :key :psc/chapters
    :parent #{:item} :count :one :content :none
    :attrs {:version {}}
    :doc "Chapters embedded in the feed, the pre-2.0 convention."}
   {:tag :psc/chapter :key :chapters
    :parent #{:psc/chapters} :count :many :content :none
    :attrs {:start req :title {} :href url :image url}
    :doc "One chapter: a start timestamp and a title."}

   ;; podcast namespace, channel
   {:tag :podcast/guid :key :podcast/guid
    :parent #{:channel} :count :one
    :doc "The global identifier of the podcast, a UUIDv5 of its original feed URL."}
   {:tag :podcast/locked :key :podcast/locked
    :parent #{:channel} :count :one :content :yes-no
    :attrs {:owner {:type :email}}
    :doc "Yes tells other platforms not to import this feed."}
   {:tag :podcast/funding :key :podcast/funding
    :parent #{:channel :item} :count :many
    :attrs {:url req-url}
    :doc "A donation or funding link with its display text."}
   {:tag :podcast/medium :key :podcast/medium
    :parent #{:channel} :count :one
    :content :enum :values (into mediums list-mediums)
    :doc "What the content in the feed is: a podcast, music, an audiobook..."}
   {:tag :podcast/block :key :podcast/blocks
    :parent #{:channel} :count :many :content :yes-no
    :attrs {:id {:type :enum :values service-slugs}}
    :doc "Which platforms may publicly display the feed."}
   {:tag :podcast/txt :key :podcast/txt
    :parent #{:channel :item} :count :many
    :attrs {:purpose {}}
    :doc "Free-form text modelled on the DNS TXT record."}
   {:tag :podcast/updateFrequency :key :podcast/update-frequency
    :parent #{:channel} :count :one
    :attrs {:complete bool :dtstart {:type :iso8601} :rrule {:type :rrule}}
    :doc "The intended release schedule as text and an RFC 5545 rule."}
   {:tag :podcast/license :key :podcast/license
    :parent #{:channel :item} :count :one
    :attrs {:url url}
    :doc "The license of the audio or video content."}
   {:tag :podcast/trailer :key :podcast/trailers
    :parent #{:channel} :count :many
    :attrs {:url     req-url
            :pubdate {:type :rfc2822 :required true}
            :length  integer
            :type    {:type :mime}
            :season  integer}
    :doc "A trailer for the whole podcast or for a season."}
   {:tag :podcast/person :key :podcast/people
    :parent #{:channel :item} :count :many
    :attrs {:role {} :group {} :img url :href url}
    :doc "A person of interest: host, guest or any Podcast Taxonomy role."}
   {:tag :podcast/location :key :podcast/locations
    :parent #{:channel :item} :count :many
    :attrs {:rel     {:type :enum :values #{"subject" "creator"}}
            :geo     {:type :geo}
            :osm     {:type :osm}
            :country {}}
    :doc "Where the content is about or where it was made."}
   {:tag :podcast/image :key :podcast/images
    :parent #{:channel :item} :count :many :content :none
    :attrs {:href         req-url
            :alt          {}
            :aspect-ratio {}
            :width        integer
            :height       integer
            :type         {:type :mime}
            :purpose      {}}
    :doc "An image of a given size, ratio and purpose."}
   {:tag :podcast/images :key :podcast/srcset-images
    :parent #{:channel :item} :count :one :content :none
    :deprecated :podcast/image
    :attrs {:srcset req}
    :doc "Deprecated: image sizes as an HTML srcset."}
   {:tag :podcast/value :key :podcast/values
    :parent #{:channel :item} :count :many :content :none
    :attrs {:type      {:required true :type :enum :values value-types}
            :method    {:required true :type :enum :values value-methods}
            :suggested decimal}
    :doc "The payment layer, transport and suggested amount for streaming payments."}
   {:tag :podcast/valueRecipient :key :recipients
    :parent #{:podcast/value :podcast/valueTimeSplit} :count :many
    :content :none
    :attrs {:type        {:required true :type :enum :values recipient-types}
            :address     req
            :split       {:type :integer :required true}
            :name        {}
            :customKey   {}
            :customValue {}
            :fee         bool}
    :doc "A destination for a share of each payment."}
   {:tag :podcast/valueTimeSplit :key :time-splits
    :parent #{:podcast/value} :count :many :content :none
    :attrs {:startTime        {:type :decimal :required true}
            :duration         {:type :decimal :required true}
            :remoteStartTime  decimal
            :remotePercentage decimal}
    :doc "Alternative recipients for a period of the media."}
   {:tag :podcast/remoteItem
    :key {:channel                :podcast/remote-items
          :podcast/podroll        :remote-items
          :podcast/publisher      :remote-item
          :podcast/valueTimeSplit :remote-item}
    :parent #{:channel :podcast/podroll :podcast/publisher
              :podcast/valueTimeSplit}
    :count {:default :many :podcast/publisher :one :podcast/valueTimeSplit :one}
    :content :none
    :attrs {:feedGuid req
            :feedUrl  url
            :itemGuid {}
            :title    {}
            :medium   {:type :enum :values (into mediums list-mediums)}}
    :doc "A pointer to another feed or to an item in it."}
   {:tag :podcast/podroll :key :podcast/podroll
    :parent #{:channel} :count :one :content :none
    :doc "Other podcasts the creator recommends."}
   {:tag :podcast/publisher :key :podcast/publisher
    :parent #{:channel} :count :one :content :none
    :doc "The publisher feed this podcast belongs to."}
   {:tag :podcast/podping :key :podcast/podping
    :parent #{:channel} :count :one :content :none
    :attrs {:usesPodping bool}
    :doc "Signals that the feed sends Podping notifications."}
   {:tag :podcast/chat :key :podcast/chat
    :parent #{:channel :item} :count :one :content :none
    :attrs {:server    req
            :protocol  {:required true :type :enum :values chat-protocols}
            :accountId {}
            :space     {}}
    :doc "Where the official chat for the podcast or episode is."}
   {:tag :podcast/socialInteract :key :podcast/social-interacts
    :parent #{:channel :item} :count :many :content :none
    :attrs {:protocol   {:required true :type :enum :values social-protocols}
            :uri        url
            :accountId  {}
            :accountUrl url
            :priority   integer}
    :doc "The root post of the comment thread for the episode or podcast."}

   ;; podcast namespace, item
   {:tag :podcast/transcript :key :podcast/transcripts
    :parent #{:item} :count :many :content :none
    :attrs {:url      req-url
            :type     {:required true :type :enum :values transcript-types}
            :language {:type :language}
            :rel      {:type :enum :values #{"captions"}}}
    :doc "A transcript or closed-captions file."}
   {:tag :podcast/chapters :key :podcast/chapters
    :parent #{:item} :count :one :content :none
    :attrs {:url req-url :type {:required true :type :mime}}
    :doc "An external JSON chapters file."}
   {:tag :podcast/soundbite :key :podcast/soundbites
    :parent #{:item} :count :many
    :attrs {:startTime {:type :decimal :required true}
            :duration  {:type :decimal :required true}}
    :doc "A clip of the episode worth previewing or sharing."}
   {:tag :podcast/season :key :podcast/season
    :parent #{:item} :count :one :content :integer
    :attrs {:name {}}
    :doc "The season number, with an optional name."}
   {:tag :podcast/episode :key :podcast/episode
    :parent #{:item} :count :one :content :decimal
    :attrs {:display {}}
    :doc "The episode number, decimal, with an optional display string."}
   {:tag :podcast/alternateEnclosure :key :podcast/alternate-enclosures
    :parent #{:item} :count :many :content :none
    :attrs {:type    {:required true :type :mime}
            :length  integer
            :bitrate decimal
            :height  integer
            :lang    {:type :language}
            :title   {}
            :rel     {}
            :codecs  {}
            :default bool}
    :doc "Another version of, or companion to, the enclosure."}
   {:tag :podcast/source :key :sources
    :parent #{:podcast/alternateEnclosure} :count :many :content :none
    :attrs {:uri req :contentType {:type :mime}}
    :doc "A URI where the alternate media can be fetched."}
   {:tag :podcast/integrity :key :integrity
    :parent #{:podcast/alternateEnclosure} :count :one :content :none
    :attrs {:type  {:required true :type :enum :values #{"sri" "pgp-signature"}}
            :value req}
    :doc "An SRI string or PGP signature for the alternate media."}
   {:tag :podcast/contentLink :key :podcast/content-links
    :parent #{:item} :count :many
    :attrs {:href req-url}
    :doc "Where else the content can be found, e.g. a live stream on the web."}

   ;; podcast namespace, live items; then the items themselves
   {:tag :podcast/liveItem :key :podcast/live-items
    :parent #{:channel} :count :many :content :none :children-as :item
    :attrs {:status {:required true :type :enum
                     :values #{"pending" "live" "ended"}}
            :start  {:type :iso8601 :required true}
            :end    {:type :iso8601}}
    :doc "A live stream, described like an item plus its status and times."}
   {:tag :item :key :rss/items
    :parent #{:channel} :count :many :content :none
    :doc "An episode."}])

(defn registry
  "The tag `entries` indexed for the interpreters, which take the result as
  the :registry of their options.

  The index is a map of :entries, :by-tag, :by-parent and :containers, and
  a registry that's already indexed comes back as it is. Every entry needs
  a :tag, a :key, a set of :parent and a :count, or this throws. An app
  extends the vocabulary of the library by indexing elements with its own
  entries:

      (registry (conj elements {:tag :acme/rating :key :acme/rating
                                :parent #{:channel} :count :one}))"
  [entries]
  (if (map? entries)
    entries
    (let [entries    (vec entries)
          add-parent (fn [m entry]
                       (reduce #(update %1 %2 (fnil conj []) entry)
                               m
                               (:parent entry)))]
      (doseq [entry entries]
        (when-not (and (keyword? (:tag entry))
                       (some? (:key entry))
                       (set? (:parent entry))
                       (seq (:parent entry))
                       (some? (:count entry)))
          (throw (ex-info (str "A registry entry needs :tag, :key, a set of"
                               " :parent and :count")
                          {:entry entry}))))
      {:entries    entries
       :by-tag     (group-by :tag entries)
       :by-parent  (reduce add-parent {} entries)
       :containers (into #{} (mapcat :parent) entries)})))

(def default
  "The registry of elements, which every interpreter reads unless its
  options give another."
  (registry elements))

(defn entry-for
  "The entry of `registry` for `tag` under `parent`, or nil when none
  applies."
  [registry tag parent]
  (some #(when (contains? (:parent %) parent) %)
        (get (:by-tag registry) tag)))

(defn entries-for
  "The entries of `registry` that can appear right under `parent`, in the
  order of emit."
  [registry parent]
  (get (:by-parent registry) parent []))

(defn- by-parent
  [x parent]
  (if (map? x)
    (get x parent (:default x))
    x))

(defn key-for
  "The model key of `entry` under `parent`."
  [entry parent]
  (by-parent (:key entry) parent))

(defn count-for
  "The cardinality of `entry` under `parent`: :one or :many."
  [entry parent]
  (by-parent (:count entry) parent))

(defn children-context
  "The parent context that the children of `entry` are looked up in: its
  tag, unless the entry borrows the children of another element, as
  liveItem does."
  [entry]
  (or (:children-as entry)
      (:tag entry)))

(defn container?
  "Whether `entry` has child entries of its own in `registry`, so that
  elements inside it are expected."
  [registry entry]
  (contains? (:containers registry) (children-context entry)))

(defn map-shaped?
  "Whether the value of `entry` in `registry` is a map, since it has
  attributes or child entries, rather than its coerced text alone."
  [registry entry]
  (boolean (or (seq (:attrs entry))
               (container? registry entry))))

(defn content-type
  "The value type of the node text of `entry`, or :text when none is
  given."
  [entry]
  (get entry :content :text))

(comment
  (entry-for default :podcast/remoteItem :podcast/publisher)
  (key-for (entry-for default :podcast/remoteItem :podcast/podroll)
           :podcast/podroll)
  (map :tag (entries-for default :item))
  (registry (conj elements {:tag :acme/rating :key :acme/rating
                            :parent #{:channel} :count :one}))
  #_.)
