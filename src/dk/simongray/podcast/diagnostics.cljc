(ns dk.simongray.podcast.diagnostics
  "Every diagnostic the library can put in a report, by id: its level and
  what it means. Consumers match on these ids, so they are API; a test keeps
  the catalogue and the source in step, and dev/reference.clj writes it out
  as doc/diagnostics.md.

  Ids are namespaced by the layer that raises them: `repair` for the text
  repairs of a malformed document, `xml` for namespace resolution, `quirk`
  for the normalizations of the tree, `feed` for the model, `transcript` and
  `chapters` for the companion formats, `id3`, `mp3`, `mp4` and `file` for
  the media files and images, `validate` for the validator, whose
  diagnostics are about the feed rather than about reading it, `check` for
  the checks over the network of what the feed links to, `sync` for a step
  of keeping devices in step, `comments` for the reading of a thread of
  comments, `value` for the records of payments, and `analytics` for the
  list of user agents.")

(def catalogue
  "The diagnostics, in the order they tend to arise."
  [;; fetching
   {:id :fetch/page-failed :level :warning
    :doc "podcast-clj did not get or read a page of a feed that comes in pages. The feed lacks the items of that page and of the pages after it."}

   ;; repairs of the XML text, applied only when the strict parse failed
   {:id :repair/leading-junk :level :warning
    :doc "Text before the XML declaration or the rss element, such as whitespace, a byte order mark or a PHP warning, was removed."}
   {:id :repair/trailing-junk :level :warning
    :doc "Text after the end of the rss element, such as a PHP warning, was removed."}
   {:id :repair/control-characters :level :warning
    :doc "Control characters XML forbids, and character references to them, were removed."}
   {:id :repair/undeclared-prefixes :level :warning
    :doc "Known namespace prefixes used without a declaration were declared."}
   {:id :repair/html-entities :level :warning
    :doc "HTML entities XML lacks, such as &nbsp;, were replaced by character references."}
   {:id :repair/bare-ampersands :level :warning
    :doc "Bare ampersands were escaped."}
   {:id :repair/lenient-parser :level :warning
    :doc "The document was still not well-formed after the repairs and was parsed leniently with jsoup."}

   ;; namespace resolution
   {:id :xml/default-namespace :level :info
    :doc "The document declares a default namespace; unprefixed elements are read as RSS 2.0 regardless."}
   {:id :xml/unknown-namespace :level :info
    :doc "A namespace URI the library does not know; its prefix serves as the alias."}
   {:id :xml/undeclared-prefix :level :warning
    :doc "A prefix used without a declaration, which only the JVM parser tolerates; it serves as the alias."}

   ;; quirks: normalizations of the tree
   {:id :quirk/namespace-variant :level :info
    :doc "A namespace was bound to a variant of its URI, http for https, other casing, a trailing slash or an old URI, and recognized anyway."}
   {:id :quirk/itunes-image-text :level :info
    :doc "An itunes:image gave its URL as text or in a url attribute instead of href."}
   {:id :quirk/explicit-value :level :info
    :doc "itunes:explicit said yes, no, explicit or clean, read as true or false."}
   {:id :quirk/yes-no-value :level :info
    :doc "A yes/no element said true or false, read as yes or no."}
   {:id :quirk/transcript-type :level :info
    :doc "A transcript type was written loosely (srt, vtt, json, text) and corrected to its MIME type."}
   {:id :quirk/attribute-name :level :info
    :doc "An href attribute stood where the specification asks for url and was renamed."}
   {:id :quirk/enclosure-length :level :info
    :doc "An enclosure length that was not a number was dropped."}
   {:id :quirk/empty-guid :level :info
    :doc "An empty guid was dropped, so the item's identity is its enclosure URL."}
   {:id :quirk/deprecated-images :level :info
    :doc "A deprecated podcast:images srcset was turned into podcast:image elements."}
   {:id :quirk/item-image :level :info
    :doc "An item's attribute-only image element, which RSS 2.0 lacks, became the itunes:image it stood for."}
   {:id :quirk/podcast-category :level :info
    :doc "A channel had the non-standard podcast:category elements Wavlake writes and no itunes:category; they were read as itunes:category, whose shape they mirror."}
   {:id :quirk/duration-format :level :info
    :doc "An itunes:duration was written as a clock rather than in seconds; read as seconds."}
   {:id :quirk/date-format :level :info
    :doc "A date was not in strict RFC 2822 form but could still be read."}

   ;; the model
   {:id :feed/duplicate-element :level :warning
    :doc "An element the specification allows once occurred again; the first occurrence is kept."}
   {:id :feed/empty-element :level :info
    :doc "A scalar element had no text and was omitted."}
   {:id :feed/empty-attribute :level :info
    :doc "An optional attribute was left blank and is absent from the model; a required one left blank is kept for the validator."}
   {:id :feed/invalid-value :level :warning
    :doc "A value did not read as its type and stays a string."}
   {:id :feed/markup-in-text :level :info
    :doc "Markup sat where the registry expects text; kept as markup for HTML content, flattened to text otherwise."}
   {:id :feed/unexpected-text :level :info
    :doc "Text inside an element the specification leaves empty was dropped."}
   {:id :feed/dropped-attributes :level :info
    :doc "Attributes the model has no place for were dropped: those of an element read as a single value, of rss other than its version, and of channel."}

   ;; transcripts
   {:id :transcript/speaker-prefixes :level :info
    :doc "Speakers were taken from Name: prefixes in the cues and carried over the cues that follow."}
   {:id :transcript/string-times :level :warning
    :doc "Segment times were given as strings rather than numbers and were read as numbers."}
   {:id :transcript/wrapped-srt :level :warning
    :doc "An HTML transcript was an SRT file with every line wrapped in an element, and was read as SRT."}
   {:id :transcript/unknown-type :level :warning
    :doc "The declared type is not one the specification names; the format was sniffed instead."}
   {:id :transcript/type-mismatch :level :warning
    :doc "The declared type does not match the file, which was read as the format it is."}
   {:id :transcript/not-an-object :level :warning
    :doc "A JSON transcript is not JSON, or not a JSON object, so it holds no segments."}
   {:id :transcript/malformed :level :warning
    :doc "Segments of a JSON transcript that are not JSON objects in a list were left out."}
   {:id :transcript/not-text :level :warning
    :doc "Speakers in a JSON transcript that are not text, such as a JSON object, were left out."}

   ;; chapters
   {:id :chapters/string-times :level :warning
    :doc "Chapter times were given as strings rather than numbers and were read as numbers."}
   {:id :chapters/not-text :level :warning
    :doc "Chapter titles, links or place names that are not text, such as a JSON object, were left out."}
   {:id :chapters/missing-start-time :level :warning
    :doc "Chapters without a start time cannot be placed."}
   {:id :chapters/unsorted :level :info
    :doc "The chapters were not in order of start time, which the format assumes."}
   {:id :chapters/unknown-keys :level :info
    :doc "Keys the format does not define were kept as they are."}
   {:id :chapters/old-version :level :info
    :doc "The document was written against an older version of the format."}
   {:id :chapters/bad-timestamps :level :warning
    :doc "Podlove chapters whose start is not a timestamp were dropped."}
   {:id :chapters/inferred :level :info
    :doc "The chapters were read from timestamps in the show notes rather than from a chapters file."}
   {:id :chapters/not-an-object :level :warning
    :doc "The document is not JSON, or not a JSON object, so it holds no chapters."}
   {:id :chapters/malformed :level :warning
    :doc "Chapters that are not JSON objects in a list were left out."}

   ;; media files and images: the ID3v2 tags, the MPEG audio frames, the MP4
   ;; boxes and the headers of images
   {:id :id3/frame-size :level :info
    :doc "An ID3v2 frame gave its size in the form of the other version: a plain number in ID3v2.4, or a syncsafe number in ID3v2.3. podcast-clj read the size in that form."}
   {:id :id3/text-encoding :level :info
    :doc "An ID3v2 frame names a text encoding that ID3 does not define. podcast-clj read its text as ISO-8859-1."}
   {:id :id3/utf8-latin1 :level :info
    :doc "An ID3v2 frame declared its text as ISO-8859-1, but the bytes are UTF-8. podcast-clj read the text as UTF-8."}
   {:id :id3/utf16-bom :level :info
    :doc "An ID3v2 frame declared its text as UTF-16, but the text has no byte order mark. podcast-clj took the byte order from the places of the zero bytes."}
   {:id :id3/image-type :level :info
    :doc "An ID3 picture gave its type loosely, such as JPG or image/jpg, or gave no type. podcast-clj took the type from the bytes of the image."}
   {:id :id3/chapter-end :level :info
    :doc "An ID3 chapter ends at its start or before it. podcast-clj left out the end."}
   {:id :id3/second-tag :level :info
    :doc "A second ID3v2 tag follows the first tag. Some writers add a tag in front of a tag that they leave in place. podcast-clj read the two tags."}
   {:id :id3/skipped-frame :level :info
    :doc "An ID3v2 frame is compressed or encrypted. podcast-clj did not read it."}
   {:id :id3/malformed :level :warning
    :doc "A frame of an ID3v2 tag does not read, so podcast-clj left it out. When the size of the frame does not read, podcast-clj also left out the frames after it."}
   {:id :id3/padding-junk :level :info
    :doc "The padding after the ID3v2 frames holds bytes that are not zeros. A writer did not clear the padding, or a frame gave a wrong size."}
   {:id :mp3/junk-before-audio :level :info
    :doc "Bytes that are no MPEG audio frame lie between the tags and the first frame. An example is padding that the size of a tag leaves out. podcast-clj skipped the bytes."}
   {:id :mp3/estimated-duration :level :info
    :doc "The MP3 file has no Xing, Info or VBRI header. podcast-clj estimated the duration from the bitrate of the first frames and the size of the file. The estimate is exact for a constant bitrate."}
   {:id :mp3/no-audio :level :warning
    :doc "podcast-clj found no MPEG audio frame after the tags, so the file has no duration."}
   {:id :mp4/zero-duration :level :info
    :doc "The movie header of the MP4 file gives no duration. podcast-clj took the duration of the longest track."}
   {:id :mp4/fragments-unread :level :info
    :doc "The MP4 file is in fragments. It has no mehd box, and no index at its end that gives the duration of all the fragments. So the file has no duration."}
   {:id :mp4/no-movie :level :warning
    :doc "The MP4 file has no movie box, so it gives no duration and no chapters."}
   {:id :file/unknown-format :level :warning
    :doc "The file is in no format that its reader knows, so the reader read nothing from it. The reader of media files knows MP3 and MP4. The reader of images reads the headers of PNG, JPEG, GIF and WebP. It knows the types of AVIF, HEIC, HEIF, SVG, BMP, TIFF and ICO."}
   {:id :file/truncated :level :warning
    :doc "The file ends before its structures say that it does."}
   {:id :file/malformed :level :warning
    :doc "The file does not read as its format says."}
   {:id :file/incomplete :level :warning
    :doc "The reading of the file did not get the bytes that it needed, so it gave nothing. The bytes did not arrive, or a limit stopped the reading."}

   ;; checks over the network of the files a feed links to
   {:id :check/unreachable :level :error
    :doc "The check did not get the file. The connection failed, the TLS handshake failed, or the request took too long."}
   {:id :check/status :level :error
    :doc "The server answered the request for the file with a status that is not a success, so a listener does not get the file."}
   {:id :check/downgrade :level :error
    :doc "A redirect leads from https to http, which clients refuse to follow."}
   {:id :check/redirects :level :warning
    :doc "The file lies more than five redirects away, more than the check and some clients follow."}
   {:id :check/moved :level :warning
    :doc "The file moved for good, by a redirect with status 301 or 308, so the feed can name its new URL."}
   {:id :check/head :level :error
    :doc "The server does not answer a HEAD request for the file. RFC 9110 requires HEAD of each general-purpose server, and directories use it to check files."}
   {:id :check/type :level :warning
    :doc "The server gives the file another media type than the feed declares."}
   {:id :check/length :level :info
    :doc "The size of the file differs from the length that the feed declares. A host that inserts ads sends a file of another size to each request, so the length cannot be exact."}
   {:id :check/ranges :level :warning
    :doc "The server does not answer a request for a byte range with that range. RFC 9110 makes ranges optional, but it recommends them. Players need ranges to seek and to resume, and Apple Podcasts requires them."}
   {:id :check/format :level :warning
    :doc "The media file is in another format than the type that the feed declares."}
   {:id :check/duration :level :info
    :doc "The duration of the media file differs from the duration that the feed declares by more than 10 seconds and 2 percent. A host that inserts ads can explain it."}
   {:id :check/unread :level :warning
    :doc "The file came, but the check did not read what it holds. A request for more of its bytes failed, or its reader failed."}
   {:id :check/integrity :level :error
    :doc "The media file does not match the hash of its podcast:integrity."}
   {:id :check/unverified :level :info
    :doc "The check did not compare the media file with its podcast:integrity. A PGP signature needs the key of the publisher. The integrity names no hash that Subresource Integrity knows. Or the whole file did not come."}
   {:id :check/image-type :level :info
    :doc "The server gives the image another media type than its bytes have, such as image/jpg for a JPEG image. Browsers read the type from the bytes of an image."}
   {:id :check/image-size :level :warning
    :doc "The size of the image differs from the width, height or aspect ratio that its podcast:image declares."}
   {:id :check/artwork-size :level :error
    :doc "The image of an itunes:image is not a square of 1400 to 3000 pixels, as the profile requires."}
   {:id :check/artwork-type :level :error
    :doc "The image of an itunes:image is not JPEG or PNG, as the profile requires. Or it is a JPEG that apps and browsers do not decode. Such a JPEG is lossless, hierarchical, arithmetic-coded or JPEG-LS, or it has more than 8 bits a sample."}
   {:id :check/artwork-color :level :error
    :doc "The image of an itunes:image is not in RGB colors, as the profile requires."}
   {:id :check/artwork-alpha :level :error
    :doc "The image of an itunes:image has an alpha channel or transparency, which the profile does not allow."}
   {:id :check/artwork-extension :level :warning
    :doc "The file extension of the URL of an itunes:image does not match the type of its image, as the profile requires."}
   {:id :check/last-modified :level :warning
    :doc "The server answers a HEAD request for the artwork without Last-Modified, which the profile asks for."}
   {:id :check/cors :level :warning
    :doc "The server does not let pages of other sites read the file, so web players cannot show it. The CORS header Access-Control-Allow-Origin of the answer, or of a redirect on the way, decides this. The podcast namespace requires CORS for transcripts."}
   {:id :check/empty :level :error
    :doc "The transcript or chapters file holds no segments or chapters in the format that the feed declares."}
   {:id :check/feed-cors :level :info
    :doc "The server does not let pages of other sites read the feed, so a web app cannot read it without a server of its own."}
   {:id :check/validators :level :info
    :doc "The server sends the feed without ETag or Last-Modified, so a client cannot ask whether the feed changed, and fetches it whole each time."}
   {:id :check/unchecked :level :warning
    :doc "The check reached a limit of files or of time, and did not look at the files after that. The diagnostic counts them."}

   ;; validation
   {:id :validate/rss-version :level :warning
    :doc "The rss element's version is not 2.0."}
   {:id :validate/missing-element :level :error
    :doc "An element RSS 2.0 requires is missing."}
   {:id :validate/missing-enclosure :level :warning
    :doc "An item has no enclosure, so there is nothing to play."}
   {:id :validate/missing-attribute :level :error
    :doc "A required attribute is missing."}
   {:id :validate/wrong-shape :level :error
    :doc "A value is not the shape its element takes: a bare value where a map of attributes belongs, or a map or sequence where a single value does."}
   {:id :validate/missing-child :level :error
    :doc "A container lacks a child the specification requires."}
   {:id :validate/invalid-structure :level :error
    :doc "A container's children contradict the specification, such as a time split with both recipients and a remote item."}
   {:id :validate/invalid-value :level :error
    :doc "A value is not of its type, such as a string where a date belongs or :no where a boolean does, or it is a number below zero, as no quantity in a feed is."}
   {:id :validate/invalid-enum :level :warning
    :doc "A value is not one of those the specification lists."}
   {:id :validate/invalid-url :level :warning
    :doc "A URL is not absolute."}
   {:id :validate/http-url :level :info
    :doc "A hypertext URL uses http where the podcast namespace requires https."}
   {:id :validate/invalid-guid :level :warning
    :doc "A podcast:guid is not a UUID."}
   {:id :validate/unknown-role :level :info
    :doc "A person's role is not in the Podcast Taxonomy."}
   {:id :validate/unknown-element :level :info
    :doc "An element the registry does not know, kept under :xml/unknown."}
   {:id :validate/unknown-key :level :warning
    :doc "A key the registry does not define: a typo such as :rss/titel, or on the channel, where a parse keeps no attributes, any key that is not an element."}
   {:id :validate/deprecated :level :info
    :doc "An element its specification has deprecated."}
   {:id :validate/profile-required :level :error
    :doc "An element the chosen profile requires is missing."}
   {:id :validate/profile-recommended :level :warning
    :doc "An element the chosen profile recommends is missing."}

   ;; syncing: what a step of keeping devices in step found
   {:id :sync/unreadable-document :level :warning
    :doc "A document in the store would not read as a sync document; it was left out."}
   {:id :sync/newer-format :level :warning
    :doc "A document was written by a newer version of the library than this one reads; it was left out."}
   {:id :sync/clock-skew :level :warning
    :doc "A device wrote its document at an instant more than a minute ahead of this device's clock."}
   {:id :sync/shared-device :level :warning
    :doc "This device's own document changed under it, so two installations write with the same device id."}
   {:id :sync/retired-device :level :warning
    :doc "This device has been retired from another device; nothing was written for it."}
   {:id :sync/conflict :level :warning
    :doc "The shared document changed under every attempt to write it; the step ended without writing."}
   {:id :sync/unknown-episodes :level :info
    :doc "Episode actions from a gpodder server name episodes of feeds that the app does not know yet. They wait in the cursor, and the next pull tries them again."}
   {:id :sync/future-actions :level :warning
    :doc "Episode actions from a gpodder server name a time more than a minute after the pull, as a device with a fast clock writes them. They changed nothing."}
   {:id :sync/malformed-actions :level :warning
    :doc "Episode actions from a gpodder server name no feed, no media file or no time that reads. They were left out."}
   {:id :sync/unnamed-episodes :level :info
    :doc "Episodes changed, but the app knows no URL of their media, which the gpodder API names an episode by, so the server was not told."}
   {:id :sync/rejected-url :level :warning
    :doc "A gpodder server refused a URL that was sent to it, so it keeps no subscription or episode action for it."}

   ;; comments: reading the thread that a podcast:socialInteract names
   {:id :comments/unsupported :level :info
    :doc "podcast-clj does not read comments over the protocol of the thread."}
   {:id :comments/no-appview :level :warning
    :doc "podcast-clj did not read an AT Protocol thread, because the options named no AppView."}
   {:id :comments/malformed :level :warning
    :doc "The URI of the root post, or a document that a server sent, is not in a form that podcast-clj reads. Such a document is a post, a collection of replies, an author or a thread."}
   {:id :comments/unreachable :level :warning
    :doc "podcast-clj did not get a post, a collection of replies or an author. The request failed, or the server answered with an error. When the diagnostic has `:retry?`, the next fetch of the thread asks again."}
   {:id :comments/refused :level :warning
    :doc "A server refused an unsigned request, as a server in secure mode does."}
   {:id :comments/untrusted :level :warning
    :doc "A server sent a post or an author from another server. podcast-clj did not use it, because a server speaks only for its own posts and authors."}
   {:id :comments/through-api :level :info
    :doc "ActivityPub did not give the root post. podcast-clj read the thread through the Mastodon API of the root post's server instead. The API can give fewer replies."}
   {:id :comments/withheld :level :info
    :doc "podcast-clj left out some replies. They are not public, their authors ask apps to show them only to signed-in users, or a moderation service hid them. An AppView also keeps back a reply when its author and the author of the post above it have a block between them."}
   {:id :comments/truncated :level :warning
    :doc "A limit of size cut some replies, and the diagnostic counts them. Its `:limit` names the limit. `:page` is the 1,000 items of one page of replies, and `:id` is the 2,048 characters of an id. `:thread` is the 10,000 comments of one thread, and `:depth` is the 64 levels of replies of an AT Protocol thread."}
   {:id :comments/incomplete :level :info
    :doc "The fetch reached a limit before it read the whole thread, or a server did not answer for a time. To read more of the thread, fetch it again."}

   ;; value: reading the record of a payment
   {:id :value/not-an-object :level :warning
    :doc "The record is not a JSON object, so it describes no payment."}
   {:id :value/text-for-number :level :info
    :doc "Numbers came as text, as some apps write them, and were read as numbers."}
   {:id :value/rounded :level :info
    :doc "Amounts in millisatoshis came with a fraction, and were rounded."}
   {:id :value/field-alias :level :info
    :doc "Fields came under other names, as some apps write them, and were read under their own names."}
   {:id :value/unreadable :level :warning
    :doc "Some fields do not hold a value of their type. They were left out."}
   {:id :value/guid-as-item-id :level :info
    :doc "The itemID holds the guid of the episode, as Breez writes it, and was read as that guid."}
   {:id :value/action-alias :level :info
    :doc "The action came as streaming, as Podverse writes it, and was read as stream."}
   {:id :value/unknown-action :level :warning
    :doc "The action is not boost, stream or auto, so the payment has no action."}
   {:id :value/unknown-keys :level :info
    :doc "The record has fields that its format does not define. They keep their names."}
   {:id :value/no-podcast :level :warning
    :doc "The record names no podcast: no guid, feed id, feed URL or title of a podcast."}
   {:id :value/claimed-amount :level :warning
    :doc "The sender claims another amount than the node received. The payment keeps the amount of the node."}
   {:id :value/claimed-recipient :level :warning
    :doc "The sender claims that the payment went to another recipient than the one that received it."}
   {:id :value/no-metadata :level :warning
    :doc "The comment names no URL of metadata, or the URL answered without the header x-rss-payment."}
   {:id :value/custom-record :level :error
    :doc "The records of the payment leave out the custom key and value of the recipient. The key is no number, it is a type that the Lightning protocol keeps for itself, or the value is absent. The payment reaches the node and not the wallet of the recipient. Do not send it."}

   ;; value: paying a Lightning address
   {:id :value/not-an-address :level :warning
    :doc "The text is not a Lightning address, user@domain."}
   {:id :value/unreachable :level :warning
    :doc "The request failed: the server did not answer."}
   {:id :value/lnurl-error :level :warning
    :doc "The service of a Lightning address answered with an error, and told why."}
   {:id :value/malformed :level :warning
    :doc "The service of a Lightning address answered with something that LNURL-pay does not define."}
   {:id :value/amount-out-of-range :level :warning
    :doc "The Lightning address does not take this amount."}
   {:id :value/wrong-amount :level :error
    :doc "The invoice asks for another amount than the one requested. Do not pay it."}
   {:id :value/unknown-success-action :level :error
    :doc "The answer holds a success action that LUD-09 does not define, or a URL on another host. Do not pay the invoice."}
   {:id :value/no-keysend :level :info
    :doc "The domain of the Lightning address names no node for keysend. Pay it with LNURL-pay."}
   {:id :value/insecure :level :error
    :doc "The answer came over plain http, where anyone on the way can change it. Do not pay with it."}

   ;; analytics: reading the list of user agents, and the downloads of OP3
   {:id :analytics/unreadable-pattern :level :warning
    :doc "A pattern of the list of user agents does not compile on this platform, so its entry was left out."}
   {:id :analytics/unreachable :level :warning
    :doc "OP3 gave no page of downloads: the request failed, or OP3 answered with an error. The result has the downloads of the pages before that page, and its continuation token names that page."}])

(def by-id
  "The catalogue by id."
  (into {} (map (juxt :id identity)) catalogue))

(defn describe
  "The `diagnostic` with a `:message` and a `:level`: its own when it has
  them, else what the catalogue says of its id.

  The parser records only the id, the level and the specifics of a
  diagnostic, so every `report` function runs its diagnostics through
  here. An id the catalogue does not know is left as it is."
  [diagnostic]
  (let [{:keys [doc level]} (by-id (:id diagnostic))]
    (cond-> diagnostic
      (and doc (not (:message diagnostic)))  (assoc :message doc)
      (and level (not (:level diagnostic))) (assoc :level level))))
