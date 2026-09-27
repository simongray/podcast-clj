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
  the checks over the network of what the feed links to, and `sync` for a
  step of keeping devices in step.")

(def catalogue
  "The diagnostics, in the order they tend to arise."
  [;; fetching
   {:id :fetch/page-failed :level :warning
    :doc "A page of a feed that comes in pages could not be fetched or read, so the items of the pages from there on are missing."}

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
    :doc "An ID3v2 frame gave its size in the form of the other version, a plain number in ID3v2.4 or a syncsafe one in ID3v2.3, and was read in that form."}
   {:id :id3/utf8-latin1 :level :info
    :doc "ID3 text declared ISO-8859-1 was UTF-8, and was read as UTF-8."}
   {:id :id3/utf16-bom :level :info
    :doc "ID3 text declared UTF-16 had no byte order mark, and its byte order was taken from where its zero bytes fall."}
   {:id :id3/image-type :level :info
    :doc "The type of an ID3 picture was loose, such as JPG or image/jpg, or missing, and was taken from the bytes of the image."}
   {:id :id3/chapter-end :level :info
    :doc "An ID3 chapter ended at or before its start, and its end was left out."}
   {:id :id3/second-tag :level :info
    :doc "A second ID3v2 tag followed the first, as a writer that adds a tag in front of another leaves it, and was read too."}
   {:id :id3/skipped-frame :level :info
    :doc "An ID3v2 frame was compressed or encrypted, and was skipped."}
   {:id :id3/malformed :level :warning
    :doc "An ID3v2 frame could not be read; the frames before it were kept."}
   {:id :id3/padding-junk :level :info
    :doc "Bytes other than zeros lay in the padding after the ID3v2 frames, as a writer that does not clear the padding leaves them, or as a frame of a wrong size makes it seem."}
   {:id :mp3/junk-before-audio :level :info
    :doc "Bytes that are no MPEG audio frame, such as padding that the size of the tag leaves out, lay between the tags and the first frame, and were skipped."}
   {:id :mp3/estimated-duration :level :info
    :doc "The MP3 file has no Xing, Info or VBRI header, so its duration was estimated from the bitrate of its first frames and its size, which is exact for a constant bitrate."}
   {:id :mp3/no-audio :level :warning
    :doc "No MPEG audio frame was found after the tags, so the file has no duration."}
   {:id :mp4/zero-duration :level :info
    :doc "The movie header of the MP4 file gave no duration, so the duration of its longest track was taken."}
   {:id :mp4/fragments-unread :level :info
    :doc "The MP4 file is written in fragments, and neither an mehd box nor an index at the end of the file gave the duration of all of them, so the file has no duration."}
   {:id :mp4/no-movie :level :warning
    :doc "The MP4 file has no movie box, so it gives neither a duration nor chapters."}
   {:id :file/unknown-format :level :warning
    :doc "The file is in no format that its reader knows, so nothing was read from it: MP3 or MP4 for a media file, and for an image PNG, JPEG, GIF or WebP, whose headers it reads, or AVIF, HEIC, HEIF, SVG, BMP, TIFF or ICO, whose type it knows."}
   {:id :file/truncated :level :warning
    :doc "The file ends before its structures say that it does."}
   {:id :file/malformed :level :warning
    :doc "The file could not be read as its format says."}
   {:id :file/incomplete :level :warning
    :doc "Bytes that the reading of the file needed did not arrive, so the file was not read."}

   ;; checks over the network of the files a feed links to
   {:id :check/unreachable :level :error
    :doc "The file could not be fetched: the connection, the TLS handshake or the time allowed failed."}
   {:id :check/status :level :error
    :doc "The server answered the request for the file with a status that is not a success, so a listener does not get the file."}
   {:id :check/downgrade :level :error
    :doc "A redirect leads from https to http, which clients refuse to follow."}
   {:id :check/redirects :level :warning
    :doc "The file lies more than five redirects away, more than the check and some clients follow."}
   {:id :check/moved :level :warning
    :doc "The file moved for good, by a redirect with status 301 or 308, so the feed can name its new URL."}
   {:id :check/head :level :error
    :doc "The server does not answer a HEAD request for the file, which RFC 9110 requires of every general-purpose server, and which directories use to check files."}
   {:id :check/type :level :warning
    :doc "The server gives the file another media type than the feed declares."}
   {:id :check/length :level :info
    :doc "The size of the file differs from the length that the feed declares. A host that inserts ads sends a file of another size to each request, so the length cannot be exact."}
   {:id :check/ranges :level :warning
    :doc "The server does not answer a request for a byte range with that range. RFC 9110 makes ranges optional, but says that servers ought to support them when possible, and players need them to seek and to resume. Apple Podcasts requires them."}
   {:id :check/format :level :warning
    :doc "The media file is in another format than the type that the feed declares."}
   {:id :check/duration :level :info
    :doc "The duration of the media file differs from the duration that the feed declares by more than 10 seconds and 2 percent. A host that inserts ads can explain it."}
   {:id :check/unread :level :warning
    :doc "The file came, but reading what it holds failed: a request for more of its bytes failed, or its reader did, so its contents were not checked."}
   {:id :check/integrity :level :error
    :doc "The media file does not match the hash of its podcast:integrity."}
   {:id :check/unverified :level :info
    :doc "The media file was not checked against its podcast:integrity: a PGP signature needs the key of the publisher, the integrity names no hash that Subresource Integrity knows, or the whole file did not come."}
   {:id :check/image-type :level :info
    :doc "The server gives the image another media type than its bytes have, such as image/jpg for a JPEG image. Browsers read the type from the bytes of an image."}
   {:id :check/image-size :level :warning
    :doc "The size of the image differs from the width, height or aspect ratio that its podcast:image declares."}
   {:id :check/artwork-size :level :error
    :doc "The image of an itunes:image is not a square of 1400 to 3000 pixels, as the profile requires."}
   {:id :check/artwork-type :level :error
    :doc "The image of an itunes:image is neither JPEG nor PNG, as the profile requires, or is a JPEG that apps and browsers do not decode: lossless, hierarchical, arithmetic-coded or JPEG-LS, or with more than 8 bits a sample."}
   {:id :check/artwork-color :level :error
    :doc "The image of an itunes:image is not in RGB colors, as the profile requires."}
   {:id :check/artwork-alpha :level :error
    :doc "The image of an itunes:image has an alpha channel or transparency, which the profile does not allow."}
   {:id :check/artwork-extension :level :warning
    :doc "The file extension of the URL of an itunes:image does not match the type of its image, as the profile requires."}
   {:id :check/last-modified :level :warning
    :doc "The server answers a HEAD request for the artwork without Last-Modified, which the profile asks for."}
   {:id :check/cors :level :warning
    :doc "The server does not let pages of other sites read the file, by the CORS header Access-Control-Allow-Origin of its answer or of a redirect on the way, so web players cannot show it. The podcast namespace requires CORS for transcripts."}
   {:id :check/empty :level :error
    :doc "The transcript or chapters file holds no segments or chapters in the format that the feed declares."}
   {:id :check/feed-cors :level :info
    :doc "The server does not let pages of other sites read the feed, so a web app cannot read it without a server of its own."}
   {:id :check/validators :level :info
    :doc "The server sends the feed without ETag or Last-Modified, so a client cannot ask whether the feed changed, and fetches it whole each time."}

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
    :doc "The shared document changed under every attempt to write it; the step ended without writing."}])

(def by-id
  "The catalogue by id."
  (into {} (map (juxt :id identity)) catalogue))

(defn describe
  "The `diagnostic` with a `:message`: its own when it has one, else what
  the catalogue says its id means. The parser records only the id, the
  level and the specifics of a diagnostic, so every `report` function runs
  its diagnostics through here. An id the catalogue does not know is left
  as it is."
  [diagnostic]
  (if-let [doc (and (not (:message diagnostic)) (:doc (by-id (:id diagnostic))))]
    (assoc diagnostic :message doc)
    diagnostic))
