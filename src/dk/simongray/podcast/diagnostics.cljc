(ns dk.simongray.podcast.diagnostics
  "Every diagnostic that the library can put in a report, by id, with its
  level and what it means.

  The ids are API, since apps match on them, and doc/diagnostics.md lists
  them all. The namespace of an id names what raises it:

  - fetch, the fetch of a feed
  - discovery, finding the feeds of a show from an address
  - directory, a search or a chart of the directories
  - repair, the text repairs of a malformed document
  - xml, the resolution of XML namespaces
  - quirk, the normalizations of the tree
  - feed, the model
  - transcript and chapters, the companion formats
  - id3, mp3, mp4 and file, media files and images
  - validate, the validator, whose diagnostics are about the feed rather
    than about reading it
  - check, the checks over the network of what the feed links to
  - sync, a sync step, whose diagnostics are those of like-minded
  - gpodder, a sync through the gpodder API
  - library, a library saved to a store and read back
  - podping, a poll of the Hive chain
  - comments, reading a thread of comments
  - value, the records of payments
  - analytics, the list of user agents and the lines of server logs

  The level says how much a diagnostic matters:

  - :error, a rule of a spec or of the chosen requirements is broken, so
    apps or a directory can misread the feed, or refuse it
  - :warning, the feed works, but apps are likely to show or treat
    something worse than the podcaster means
  - :info, something worth knowing that needs no change, e.g. a repair
    that the parse made

  The requirements of a directory can raise a level, e.g. a repeated
  enclosure URL is an error for Apple. An error under Apple's
  requirements breaks a rule of its guide, though Apple Podcasts Connect
  doesn't say which rules it refuses a feed for."
  (:require [clojure.string :as str]
            [dk.simongray.like-minded.diagnostics :as sync-diagnostics]
            [dk.simongray.wary-fetch.url :as url]))

(defn- unwrap
  "The text `s` on one line, without the line breaks and the indentation
  that wrap it in the source."
  [s]
  (str/replace s #"\s*\n\s*" " "))

(def podcast-catalogue
  "The diagnostics that podcast-clj raises itself, in the order they tend
  to arise."
  (mapv
   #(update % :doc unwrap)
   [;; fetching
    {:id :fetch/unreachable :level :warning
     :doc "The feed or list couldn't be fetched: the name of its host wasn't
           found, the host couldn't be reached, the secure connection failed,
           the request took too long, the answer was too large, or the URL
           wasn't allowed. The :type of wary-fetch says which, when the
           platform tells."}
    {:id :refresh/offline :level :info
     :doc "The device seems to be offline: :online? said so, or every fetch of
           the refresh failed before a server answered, at more than one
           host. No feed counts it as a failure, and each stays due, so the
           next refresh fetches it."}
    {:id :refresh/stopped :level :info
     :doc "The :signal of the refresh stopped it, e.g. as the listener
           cancelled it. The feeds whose fetch it stopped or kept from
           starting count no failure, and stay due."}
    {:id :fetch/malformed :level :warning
     :doc "The document isn't an RSS feed, or for a list, an OPML document:
           it isn't XML, or its root element isn't rss or opml."}
    {:id :fetch/unsupported-format :level :warning
     :doc "The document is an Atom or RSS 1.0 feed rather than RSS 2.0, so it
           can't be read. The :format names which."}
    {:id :fetch/page-failed :level :warning
     :doc "A page of a paged feed couldn't be fetched or read, so the feed lacks
           the items of that page and the pages after it."}
    {:id :fetch/status :level :warning
     :doc "The server answered with a status that isn't a success, such as 404.
           The feed stays as it was, and the next fetch waits longer after each
           failure in a row."}
    {:id :fetch/empty-feed :level :warning
     :doc "The feed came back without items, though it had some before, as
           some hosts answer during an outage. The feed stays as it was, and
           the next fetch waits as after a failure."}
    {:id :fetch/gone :level :warning
     :doc "The feed is gone: its server answered 410, or it failed many
           times in a row, as the :failures say. It isn't checked any more,
           unless a Podping or a refresh of that feed asks for it, and a fetch
           that succeeds brings it back."}
    {:id :fetch/move-unverified :level :warning
     :doc "The feed declares that it moved to the :new-url, but no feed of the
           show answered there, as the :reason says, so the feed stays at its
           old URL and the move is tried again at the next fetch."}
    {:id :fetch/guid-taken :level :warning
     :doc "The feed declares the podcast:guid of another subscription, at
           another URL, and would take that subscription over, so it isn't
           added. To replace the other subscription, unsubscribe from it
           first."}
    {:id :fetch/guid-changed :level :info
     :doc "The feed gives itself another guid than before, from :old to
           :new, e.g. a new podcast:guid, or a new URL of a feed that
           declares none. Its subscription keeps its key, the :feed-guid,
           and notes the new guid."}

    ;; finding the feeds of a show
    {:id :discovery/unreadable :level :warning
     :doc "The text isn't a URL, a domain or a link of an app, so there's
           nothing to fetch. It may be words to search a directory with."}
    {:id :discovery/not-found :level :warning
     :doc "No feed was found: the page names none that could be read, and none
           of the other places that were tried has one."}

    ;; the directories
    {:id :directory/source-failed :level :warning
     :doc "A directory gave no answer to a search, a chart or a lookup, so what
           it knows is missing from the results. The :source names it, and the
           :reason says why."}

    ;; repairs of the XML text, applied only when the strict parse failed
    {:id :repair/leading-junk :level :warning
     :doc "Text before the XML declaration or the rss element, such as
           whitespace, a byte order mark or a PHP warning, was removed."}
    {:id :repair/trailing-junk :level :warning
     :doc "Text after the end of the rss element, such as a PHP warning, was
           removed."}
    {:id :repair/control-characters :level :warning
     :doc "Control characters that XML forbids, and character references to
           them, were removed."}
    {:id :repair/undeclared-prefixes :level :warning
     :doc "Known namespace prefixes that were used without a declaration were
           declared."}
    {:id :repair/html-entities :level :warning
     :doc "HTML entities that XML lacks, such as &nbsp;, were replaced by
           character references."}
    {:id :repair/bare-ampersands :level :warning
     :doc "Bare ampersands were escaped."}
    {:id :repair/lenient-parser :level :warning
     :doc "The document was still malformed after the repairs, so it was parsed
           leniently with jsoup."}

    ;; the parse and namespace resolution
    {:id :xml/skipped-entity :level :warning
     :doc "An entity that only an external DTD could declare was left out of the
           text, since no external DTD is read. The :entity names it."}
    {:id :xml/default-namespace :level :info
     :doc "A default namespace isn't known, so the elements in it have no
           alias, and its declaration stays as an xmlns attribute."}
    {:id :xml/unknown-namespace :level :info
     :doc "The namespace URI isn't known, so its prefix is used as the alias."}
    {:id :xml/undeclared-prefix :level :warning
     :doc "A prefix was used without a declaration, which only the JVM parser
           accepts, so the prefix is used as the alias."}

    ;; quirks: normalizations of the tree
    {:id :quirk/namespace-variant :level :info
     :doc "A namespace was bound to a variant of its URI, such as http for
           https, another case, a trailing slash or an old URI, and was
           recognised anyway."}
    {:id :quirk/rss-default-namespace :level :info
     :doc "The rss element declared a default namespace, such as that of Atom,
           though RSS 2.0 has none, so it was ignored."}
    {:id :quirk/itunes-image-text :level :info
     :doc "An itunes:image gave its URL as text or in a url attribute instead of
           href."}
    {:id :quirk/explicit-value :level :info
     :doc "An itunes:explicit said yes, no, explicit or clean, which was read as
           true or false."}
    {:id :quirk/yes-no-value :level :info
     :doc "A yes/no element said true or false, which was read as yes or no."}
    {:id :quirk/transcript-type :level :info
     :doc "A transcript type was written loosely, as srt, vtt, json or text,
           or with a parameter such as a charset, and was corrected to its
           MIME type."}
    {:id :quirk/attribute-name :level :info
     :doc "An href attribute was used where the spec asks for url, and was
           renamed."}
    {:id :quirk/enclosure-length :level :info
     :doc "An enclosure length that wasn't a number was dropped."}
    {:id :quirk/empty-guid :level :info
     :doc "An empty guid was dropped, so the identity of the item is its
           enclosure URL."}
    {:id :quirk/deprecated-images :level :info
     :doc "A deprecated podcast:images srcset was turned into podcast:image
           elements."}
    {:id :quirk/item-image :level :info
     :doc "An item had an image element with only attributes, which RSS 2.0
           lacks, and it was read as the itunes:image that it stands for."}
    {:id :quirk/podcast-category :level :info
     :doc "A channel had the non-standard podcast:category elements that Wavlake
           writes, and no itunes:category, so they were read as itunes:category,
           whose shape they copy."}
    {:id :quirk/duration-format :level :info
     :doc "An itunes:duration was written as a clock time rather than in
           seconds, and was read as seconds."}
    {:id :quirk/date-format :level :info
     :doc "A date wasn't in strict RFC 2822 form, but could still be read."}

    ;; the model
    {:id :feed/duplicate-element :level :warning
     :doc "An element that the spec allows once occurred again, and only the
           first one is kept. The :tag names the element, and the :count is
           the number of the others."}
    {:id :feed/empty-element :level :info
     :doc "An element with a single value had no text, and was left out."}
    {:id :feed/empty-attribute :level :info
     :doc "An optional attribute was blank, and is left out of the model. A
           required one that's blank is kept for the validator."}
    {:id :feed/invalid-value :level :warning
     :doc "A value didn't read as its type, and stays a string."}
    {:id :feed/string-value :level :info
     :doc "A feed built from data held a string where the model takes another
           type, e.g. a date, and it was read as that type, as parse reads
           the text of XML. The :value is the string, and the :type the
           type."}
    {:id :feed/markup-in-text :level :info
     :doc "Markup appeared where the registry expects text. It's kept as markup
           in HTML content, and flattened to text elsewhere."}
    {:id :feed/unexpected-text :level :info
     :doc "Text inside an element that the spec leaves empty was dropped."}
    {:id :feed/dropped-attributes :level :info
     :doc "Attributes that the model has no place for were dropped: those of an
           element read as a single value, those of rss other than its version,
           and those of channel."}

    ;; transcripts
    {:id :transcript/speaker-prefixes :level :info
     :doc "Speakers were taken from Name: prefixes in the cues, and carried over
           to the cues that follow."}
    {:id :transcript/voices-carried :level :info
     :doc "A WebVTT file gives a voice span only where the speaker changes,
           so the cues without one took the speaker before them. The :count
           is the number of such cues."}
    {:id :transcript/srt-tags :level :info
     :doc "SRT cues held the formatting tags of HTML, e.g. for italics or a
           font, which SRT doesn't define, and the text was read without them.
           The :count is the number of such cues."}
    {:id :transcript/string-times :level :warning
     :doc "Segment times were given as strings rather than numbers, and were
           read as numbers."}
    {:id :transcript/wrapped-srt :level :warning
     :doc "An HTML transcript was an SRT file with every line wrapped in an
           element, and was read as SRT."}
    {:id :transcript/unknown-type :level :warning
     :doc "The declared type isn't one that the spec names, so the format was
           detected from the file instead."}
    {:id :transcript/type-mismatch :level :warning
     :doc "The declared type doesn't match the file, which was read as the
           format that it really is."}
    {:id :transcript/not-an-object :level :warning
     :doc "A JSON transcript isn't JSON, or isn't a JSON object, so it holds no
           segments."}
    {:id :transcript/malformed :level :warning
     :doc "Segments of a JSON transcript that aren't JSON objects in a list were
           left out."}
    {:id :transcript/not-text :level :warning
     :doc "Speakers in a JSON transcript that aren't text, such as a JSON
           object, were left out."}

    ;; chapters
    {:id :chapters/string-times :level :warning
     :doc "Chapter times were given as strings rather than numbers, and were
           read as numbers."}
    {:id :chapters/not-text :level :warning
     :doc "Chapter titles, links or place names that aren't text, such as a JSON
           object, were left out."}
    {:id :chapters/missing-start-time :level :warning
     :doc "Chapters without a start time can't be placed."}
    {:id :chapters/unsorted :level :info
     :doc "The chapters weren't in order of start time, which the format
           assumes."}
    {:id :chapters/unknown-keys :level :info
     :doc "Keys that the format doesn't define were kept as they are."}
    {:id :chapters/old-version :level :info
     :doc "The document was written for an older version of the format."}
    {:id :chapters/bad-timestamps :level :warning
     :doc "Podlove chapters whose start isn't a timestamp were dropped."}
    {:id :chapters/inferred :level :info
     :doc "The chapters were read from timestamps in the show notes rather than
           from a chapters file."}
    {:id :chapters/not-an-object :level :warning
     :doc "The document isn't JSON, or isn't a JSON object, so it holds no
           chapters."}
    {:id :chapters/malformed :level :warning
     :doc "Chapters that aren't JSON objects in a list were left out."}

    ;; media files and images: the ID3v2 tags, the MPEG audio frames, the MP4
    ;; boxes, the Ogg pages, the ADTS frames and the headers of images
    {:id :id3/frame-size :level :info
     :doc "An ID3v2 frame gave its size in the form of the other version, a
           plain number in ID3v2.4 or a syncsafe number in ID3v2.3, and the size
           was read in that form."}
    {:id :id3/text-encoding :level :info
     :doc "An ID3v2 frame names a text encoding that ID3 doesn't define, so its
           text was read as ISO-8859-1."}
    {:id :id3/utf8-latin1 :level :info
     :doc "An ID3v2 frame declared its text as ISO-8859-1, but the bytes are
           UTF-8, so the text was read as UTF-8."}
    {:id :id3/utf16-bom :level :info
     :doc "An ID3v2 frame declared its text as UTF-16 without a byte order mark,
           so the byte order was worked out from where the zero bytes are."}
    {:id :id3/image-type :level :info
     :doc "An ID3 picture gave its type loosely, such as JPG or image/jpg, or
           gave none, so the type was taken from the bytes of the image."}
    {:id :id3/chapter-end :level :info
     :doc "An ID3 chapter ends at or before its start, so its end was left
           out."}
    {:id :id3/second-tag :level :info
     :doc "A second ID3v2 tag follows the first, as when a writer adds a tag in
           front of an existing one, and both tags were read."}
    {:id :id3/skipped-frame :level :info
     :doc "An ID3v2 frame is compressed or encrypted, and wasn't read."}
    {:id :id3/malformed :level :warning
     :doc "A frame of an ID3v2 tag doesn't read, and was left out. When its size
           doesn't read, the frames after it were left out too."}
    {:id :id3/padding-junk :level :info
     :doc "The padding after the ID3v2 frames holds bytes that aren't zeros,
           because a writer didn't clear it or a frame gave a wrong size."}
    {:id :mp3/junk-before-audio :level :info
     :doc "Bytes that aren't MPEG audio frames lie between the tags and the
           first frame, e.g. padding that the size of a tag leaves out, and were
           skipped."}
    {:id :mp3/estimated-duration :level :info
     :doc "The MP3 file has no Xing, Info or VBRI header, so the duration was
           estimated from the bitrate of the first frames and the size of the
           file. The estimate is exact for a constant bitrate."}
    {:id :mp3/no-audio :level :warning
     :doc "No MPEG audio frame was found after the tags, so the file has no
           duration."}
    {:id :mp4/zero-duration :level :info
     :doc "The movie header of the MP4 file gives no duration, so the duration
           of the longest track was used."}
    {:id :mp4/fragments-unread :level :info
     :doc "The MP4 file is in fragments, with no mehd box and no index at its
           end that gives the duration of all of them, so the file has no
           duration."}
    {:id :mp4/no-movie :level :warning
     :doc "The MP4 file has no movie box, so it gives no duration and no
           chapters."}
    {:id :ogg/unknown-codec :level :warning
     :doc "The Ogg file has no Opus or Vorbis stream, e.g. it holds FLAC or
           Speex, so nothing was read from it."}
    {:id :ogg/no-audio :level :warning
     :doc "The Opus or Vorbis stream has no audio after its headers, so the file
           has no duration."}
    {:id :ogg/no-end :level :info
     :doc "No page of the audio stream ends a packet in the last 64 KB of the
           file, e.g. because other streams follow it, so the file has no
           duration."}
    {:id :ogg/chapter-start :level :warning
     :doc "A CHAPTERxxx comment doesn't give the start of its chapter as a time,
           such as 00:05:00.000, so the chapter was left out."}
    {:id :ogg/image-type :level :info
     :doc "A METADATA_BLOCK_PICTURE gave the type of its picture loosely, such
           as JPG or image/jpg, or gave none, so the type was taken from the
           bytes of the image."}
    {:id :aac/estimated-duration :level :info
     :doc "The duration of the AAC file was estimated from the average length of
           its first frames and the size of the file, since the whole file
           wasn't read."}
    {:id :aac/no-audio :level :warning
     :doc "No ADTS frame was found after the tags, so the file has no
           duration."}
    {:id :file/unknown-format :level :warning
     :doc "The file isn't in a format that its reader knows, so nothing was read
           from it. Media files can be MP3, MP4, Ogg with Opus or Vorbis, or AAC
           in ADTS frames, and images PNG, JPEG, GIF or WebP, while AVIF, HEIC,
           HEIF, SVG, BMP, TIFF and ICO images are only recognised by their
           type."}
    {:id :file/truncated :level :warning
     :doc "The file ends before its structures say it does."}
    {:id :file/malformed :level :warning
     :doc "The file doesn't read as its format says."}
    {:id :file/duration :level :info
     :doc "The duration of the media file or stream differs from the one that
           the feed declares by more than 10 seconds and 2 percent. A host
           that inserts ads can explain it, but then a position in this file
           may not be the same moment in another file of the episode, such as
           its video."}
    {:id :file/incomplete :level :warning
     :doc "The bytes that the reader needed didn't arrive, or a limit stopped
           the reading, so nothing was read."}
    {:id :file/unreachable :level :warning
     :doc "The file couldn't be fetched: the request failed, or the server
           answered with a status that isn't a success."}
    {:id :file/unverifiable :level :info
     :doc "This podcast:integrity can't be checked. A PGP signature needs the
           key of the publisher, and an sri value must name a sha256, sha384 or
           sha512 hash."}

    ;; checks over the network of the files a feed links to
    {:id :check/unreachable :level :error
     :doc "The file couldn't be fetched: the name of its host wasn't found,
           the host couldn't be reached, the TLS handshake failed, or the
           request took too long. The :type of wary-fetch says which, when
           the platform tells."}
    {:id :check/status :level :error
     :doc "The server answered with a status that isn't a success, so a listener
           doesn't get the file."}
    {:id :check/downgrade :level :error
     :doc "A redirect leads from https to http, which clients refuse to
           follow."}
    {:id :check/redirects :level :warning
     :doc "The file is more than five redirects away, more than the check and
           some clients follow."}
    {:id :check/moved :level :warning
     :doc "The file moved for good, with a 301 or 308 redirect, so the feed can
           name its new URL."}
    {:id :check/head :level :error
     :doc "The server doesn't answer a HEAD request for the file. RFC 9110
           requires HEAD of every general-purpose server, and directories use it
           to check files."}
    {:id :check/type :level :warning
     :doc "The server gives the file a different media type than the feed
           declares."}
    {:id :check/media-type :level :error
     :doc "The chosen requirements don't take a media file of the :type that
           the server gives it, e.g. YouTube, which makes its videos from audio
           files."}
    {:id :check/no-reader :level :info
     :doc "A reading of the file was asked for, but the media reader knows no
           format of the :type that the feed declares, e.g. audio/x-wav, so
           its duration and codecs weren't checked."}
    {:id :check/no-type :level :warning
     :doc "The feed declares no media type for the file, which RSS 2.0 and
           the podcast namespace require. The :served type is the one that
           the server gives it."}
    {:id :check/length :level :info
     :doc "The size of the file differs from the length that the feed declares.
           A host that inserts ads sends a file of a different size to each
           request, so the length can't be exact."}
    {:id :check/ranges :level :warning
     :doc "The server doesn't answer a request for a byte range with that range.
           RFC 9110 makes ranges optional but recommends them, players need them
           to seek and resume, and Apple Podcasts requires them."}
    {:id :check/format :level :warning
     :doc "The media file is in a different format than the type that the feed
           declares."}
    {:id :check/duration :level :info
     :doc "The duration of the media file differs from the one that the feed
           declares by more than 10 seconds and 2 percent, which a host that
           inserts ads can explain."}
    {:id :check/slow-start :level :warning
     :doc "The movie of the MP4 file comes after its media data, so a player
           must fetch the end of the file before it can play the start.
           Writing the movie first fixes it."}
    {:id :check/codecs :level :warning
     :doc "The codecs that the alternate enclosure declares are those of
           neither its file nor any variant of its stream, so an app that asks
           the platform whether it can play them gets the wrong answer."}
    {:id :check/height :level :warning
     :doc "The height that the alternate enclosure declares is that of
           neither its video nor any variant of its stream."}
    {:id :check/unread :level :warning
     :doc "The file arrived, but its content wasn't read, because a request for
           more of its bytes failed or its reader failed."}
    {:id :check/integrity :level :error
     :doc "The media file doesn't match the hash of its podcast:integrity."}
    {:id :check/unverified :level :info
     :doc "The media file wasn't compared with its podcast:integrity, because a
           PGP signature needs the key of the publisher, the integrity names no
           hash that Subresource Integrity knows, or the whole file didn't
           arrive."}
    {:id :check/image-type :level :info
     :doc "The server gives the image a different media type than its bytes
           have, such as image/jpg for a JPEG. Browsers go by the bytes of an
           image."}
    {:id :check/image-size :level :warning
     :doc "The size of the image differs from the width, height or aspect ratio
           that its podcast:image declares."}
    {:id :check/artwork-size :level :error
     :doc "The image of an itunes:image isn't a square of the size that the
           chosen requirements ask for, e.g. 1400 to 3000 pixels for Apple."}
    {:id :check/artwork-type :level :error
     :doc "The image of an itunes:image isn't of a type that the chosen
           requirements ask for, e.g. JPEG or PNG for Apple, or it's a JPEG that
           apps and browsers can't decode: lossless, hierarchical,
           arithmetic-coded, JPEG-LS, or with more than 8 bits per sample."}
    {:id :check/artwork-color :level :error
     :doc "The image of an itunes:image isn't in RGB colours, as the chosen
           requirements ask."}
    {:id :check/artwork-alpha :level :error
     :doc "The image of an itunes:image has an alpha channel or transparency,
           which the chosen requirements don't allow."}
    {:id :check/artwork-extension :level :warning
     :doc "The file extension in the URL of an itunes:image doesn't match the
           type of its image, as the chosen requirements ask."}
    {:id :check/last-modified :level :warning
     :doc "The server answers a HEAD request for the artwork without
           Last-Modified, which the chosen requirements ask for."}
    {:id :check/cors :level :warning
     :doc "The server doesn't let pages on other sites read the file, so web
           players can't show it. The Access-Control-Allow-Origin header of the
           answer, or of a redirect on the way, decides this, and the podcast
           namespace requires CORS for transcripts."}
    {:id :check/empty :level :error
     :doc "The transcript or chapters file holds no segments or chapters in the
           format that the feed declares."}
    {:id :check/feed-cors :level :info
     :doc "The server doesn't let pages on other sites read the feed, so a web
           app can't read it without a server of its own."}
    {:id :check/validators :level :info
     :doc "The server sends the feed without ETag or Last-Modified, so a client
           can't ask whether it changed, and has to fetch it whole every time."}
    {:id :check/feed-type :level :warning
     :doc "The server sends the feed as a media type that isn't one of XML, the
           :served, e.g. text/html, so a browser can show it as a page and an
           app or a directory can refuse it. Serve it as application/rss+xml
           or application/xml."}
    {:id :check/feed-size :level :warning
     :doc "The feed is larger than the requirements take: its :bytes against
           their :max-bytes."}
    {:id :check/unchecked :level :warning
     :doc "The check reached a limit of files or time, and skipped the files
           after it, which the diagnostic counts."}

    ;; validation
    {:id :validate/rss-version :level :warning
     :doc "The version of the rss element isn't 2.0. The :value is the one it
           has. A feed built from data has none, which emit writes."}
    {:id :validate/missing-element :level :error
     :doc "An element that RSS 2.0 requires is missing."}
    {:id :validate/missing-enclosure :level :warning
     :doc "An item has no enclosure, so there's nothing to play."}
    {:id :validate/duplicate-guid :level :error
     :doc "An item has the guid of an earlier item, so apps, directories and
           sync servers take the two for one episode. The :first-path is
           that of the earlier item."}
    {:id :validate/duplicate-enclosure :level :warning
     :doc "An item has the enclosure URL of an earlier item, e.g. a trailer
           and the episode it's cut from. Apple keeps only the first, so its
           requirements make it an error. The :first-path is that of the
           earlier item."}
    {:id :validate/unknown-media-type :level :warning
     :doc "The type of an enclosure is neither audio, video nor a playlist of
           either, e.g. application/octet-stream, so apps may not play it.
           The :value is the type."}
    {:id :validate/unknown-length :level :info
     :doc "The length of an enclosure is 0, which says that it isn't known,
           e.g. under dynamic ad insertion, so an app can't tell the size of
           the file before it downloads it."}
    {:id :validate/unknown-category :level :warning
     :doc "Apple Podcasts doesn't list the category of a podcast, so the show
           isn't listed under it. The :value is its name, and the
           :replacement is the path of the one that Apple lists in another
           case, if any. The requirements of Apple and PSP-1 make it an
           error."}
    {:id :validate/renamed-category :level :warning
     :doc "The category is one that Apple Podcasts renamed or dropped in
           2019, which it still reads. The :replacement is the path of the
           category and perhaps the subcategory that stands for it now."}
    {:id :validate/escaped-category :level :warning
     :doc "The ampersand of a category's name was escaped twice, e.g.
           Society &amp; Culture, so it names no category. The :replacement
           is the name meant. The requirements of Apple and PSP-1 make it an
           error."}
    {:id :validate/wrong-subcategory :level :warning
     :doc "The subcategory belongs under another category, or stands alone
           where a category belongs. The :replacement is the path where
           Apple Podcasts lists it. The requirements of Apple and PSP-1 make
           it an error."}
    {:id :validate/category-depth :level :warning
     :doc "A subcategory holds a third level, which Apple Podcasts doesn't
           take. The requirements of Apple and PSP-1 make it an error."}
    {:id :validate/missing-attribute :level :error
     :doc "A required attribute is missing."}
    {:id :validate/wrong-shape :level :error
     :doc "A value doesn't have the shape that its element takes: a bare value
           where a map of attributes belongs, or a map or sequence where a
           single value does."}
    {:id :validate/missing-child :level :error
     :doc "A container lacks a child that the spec requires."}
    {:id :validate/invalid-structure :level :error
     :doc "The children of a container contradict the spec, such as a time split
           with both recipients and a remote item."}
    {:id :validate/invalid-value :level :error
     :doc "A value isn't of its type, such as a string where a date belongs or
           :no where a boolean does, or it's a negative number, which no
           quantity in a feed can be. The :value is the value."}
    {:id :validate/invalid-enum :level :warning
     :doc "A value isn't one of those that the spec lists. The :value is the
           value."}
    {:id :validate/invalid-url :level :warning
     :doc "A URL isn't absolute. The :value is the URL."}
    {:id :validate/http-url :level :info
     :doc "A URL uses http where the podcast namespace requires https."}
    {:id :validate/invalid-language :level :warning
     :doc "A language isn't a language tag such as en or en-US, e.g. en_US,
           en-UK or english. The :value is the value, and the :reason says
           why: a reason from langtag-clj with its :subtag, e.g.
           :unregistered-subtag for a subtag that the IANA registry doesn't
           have, or :not-iso-639 for a language that isn't two or three
           letters."}
    {:id :validate/invalid-email :level :info
     :doc "An email address isn't one, alone or followed by a name in
           parentheses, e.g. an author given by name alone. The :value is the
           value."}
    {:id :validate/invalid-media-type :level :warning
     :doc "A media type isn't a type and a subtype such as audio/mpeg. The
           :value is the value."}
    {:id :validate/invalid-guid :level :warning
     :doc "A podcast:guid isn't a UUID, so the library, like the Podcast
           Index, identifies the feed by the guid derived from its URL. The
           :value is the guid."}
    {:id :validate/unknown-role :level :info
     :doc "The role of a person isn't in the Podcast Taxonomy. The :value is
           the role."}
    {:id :validate/unknown-element :level :info
     :doc "The registry doesn't know the element, so it's kept under
           :xml/unknown."}
    {:id :validate/unknown-key :level :warning
     :doc "The registry doesn't define the :key, e.g. the typo :rss/titel, or
           a key on the channel that isn't an element, since a parse keeps no
           attributes there."}
    {:id :validate/deprecated :level :info
     :doc "The spec of the element has deprecated it."}
    {:id :validate/required :level :error
     :doc "A required element of the chosen requirements is missing. The :key
           is its key in the model."}
    {:id :validate/recommended :level :warning
     :doc "A recommended element of the chosen requirements is missing. The
           :key is its key in the model. When every item lacks it, it's one
           diagnostic of all the items, whose :count is the number of
           items."}

    ;; gpodder: what a sync through the gpodder API found
    {:id :gpodder/unknown-episodes :level :info
     :doc "Episode actions from a gpodder server name episodes of feeds that
           aren't fetched yet. They wait in the cursor, and the next pull tries
           them again."}
    {:id :gpodder/future-actions :level :warning
     :doc "Episode actions from a gpodder server name a time more than a minute
           after the pull, as a device with a fast clock writes them, so they
           changed nothing."}
    {:id :gpodder/malformed-actions :level :warning
     :doc "Episode actions from a gpodder server name no feed, no media file or
           no readable time, and were left out."}
    {:id :gpodder/unnamed-episodes :level :info
     :doc "Episodes changed, but the server wasn't told, because the gpodder API
           names an episode by the URL of its media, and that URL isn't known."}
    {:id :gpodder/rejected-url :level :warning
     :doc "A gpodder server refused a URL that was sent to it, so it keeps no
           subscription or episode action for it."}

    ;; library: a library saved to a store and read back
    {:id :library/save-failed :level :warning
     :doc "The library or its search index couldn't be written to the store
           in full. The :reason says why."}
    {:id :library/unreadable-document :level :warning
     :doc "A document of the saved library didn't read: library.edn, so none
           of its parts were restored, or the document of a feed, which is
           fetched again. The :name says which, and the :reason why."}
    {:id :library/newer-format :level :error
     :doc "The saved library is of a newer version than this one reads, so
           none of it was restored, and a save won't write over it. The
           :version says which."}
    {:id :library/malformed-profile :level :warning
     :doc "The saved profile breaks the rules of a profile, so the library
           kept the profile it had, and the next sync brings back what the
           other devices hold. The :reason says what's wrong."}
    {:id :library/index-rebuilt :level :info
     :doc "The search index was built again from the feeds, since some of its
           documents were missing or didn't read. The :reason says why."}

    ;; podping: a poll of the Hive chain
    {:id :podping/unavailable :level :warning
     :doc "No node of the Hive chain answered a call of the poll. The result has
           the pings of the blocks that were read before that call."}
    {:id :podping/not-notified :level :warning
     :doc "The service didn't take the word that a feed changed: the call failed,
           as the :reason says, or the service answered with the :status of a
           failure, e.g. 401 for a token that it doesn't know."}

    ;; comments: reading the thread that a podcast:socialInteract names
    {:id :comments/unsupported :level :info
     :doc "Comments over the protocol of the thread can't be read."}
    {:id :comments/no-appview :level :warning
     :doc "An AT Protocol thread wasn't read, because the options named no
           AppView."}
    {:id :comments/no-relay :level :warning
     :doc "A Nostr thread wasn't read, because neither the options nor the root
           named a relay."}
    {:id :comments/malformed :level :warning
     :doc "The URI of the root post, or a post, a collection of replies, an
           author or a thread that a server sent, isn't in a form that can be
           read."}
    {:id :comments/unreachable :level :warning
     :doc "A post, a collection of replies or an author couldn't be fetched: the
           request failed, or the server answered with an error. With `:retry?`,
           the next fetch of the thread asks again."}
    {:id :comments/refused :level :warning
     :doc "A server refused an unsigned request, as a server in secure mode
           does."}
    {:id :comments/untrusted :level :warning
     :doc "A server sent a post or an author from another server, which wasn't
           used, since a server only speaks for its own posts and authors."}
    {:id :comments/through-api :level :info
     :doc "ActivityPub didn't give the root post, so the thread was read through
           the Mastodon API of the server of the root post, which can give fewer
           replies."}
    {:id :comments/withheld :level :info
     :doc "Some replies were left out, because they aren't public, their authors
           ask apps to show them only to signed-in users, or a moderation
           service hid them. An AppView also holds back a reply when its author
           and the author of the post above it block each other."}
    {:id :comments/unverified :level :warning
     :doc "Events from a Nostr relay were left out, and the diagnostic counts
           them. Such an event has a wrong id or signature, or isn't one that
           the request asked for."}
    {:id :comments/truncated :level :warning
     :doc "A size limit cut some replies, which the diagnostic counts. Its
           `:limit` names the limit: `:page` for the 1,000 items of a page of
           replies, `:id` for the 2,048 characters of an id, `:thread` for the
           10,000 comments of a thread, or `:depth` for the 64 levels of replies
           of an AT Protocol thread."}
    {:id :comments/incomplete :level :info
     :doc "The fetch reached a limit before it read the whole thread, or a
           server didn't answer for a while. Fetch the thread again to read
           more."}

    ;; value: reading the record of a payment
    {:id :value/not-an-object :level :warning
     :doc "The record isn't a JSON object, so it describes no payment."}
    {:id :value/text-for-number :level :info
     :doc "Numbers came as text, as some apps write them, and were read as
           numbers."}
    {:id :value/rounded :level :info
     :doc "Amounts in millisatoshis had a fraction, and were rounded."}
    {:id :value/field-alias :level :info
     :doc "Fields came under other names, as some apps write them, and were read
           under their own names."}
    {:id :value/unreadable :level :warning
     :doc "Some fields don't hold a value of their type, and were left out."}
    {:id :value/guid-as-item-id :level :info
     :doc "The itemID holds the guid of the episode, as Breez writes it, and was
           read as that guid."}
    {:id :value/action-alias :level :info
     :doc "The action came as streaming, as Podverse writes it, and was read as
           stream."}
    {:id :value/unknown-action :level :warning
     :doc "The action isn't boost, stream or auto, so the payment has no
           action."}
    {:id :value/unknown-keys :level :info
     :doc "The record has fields that its format doesn't define, which keep
           their names."}
    {:id :value/no-podcast :level :warning
     :doc "The record names no podcast: no guid, feed id, feed URL or title."}
    {:id :value/claimed-amount :level :warning
     :doc "The sender claims a different amount than the node received, so the
           payment keeps the amount of the node."}
    {:id :value/claimed-recipient :level :warning
     :doc "The sender claims that the payment went to a different recipient than
           the one that received it."}
    {:id :value/no-metadata :level :warning
     :doc "The comment names no URL of metadata, or the URL answered without the
           x-rss-payment header."}
    {:id :value/custom-record :level :error
     :doc "The records of the payment leave out the custom key and value of the
           recipient, because the key isn't a number, it's a type that the
           Lightning protocol reserves, or the value is missing. The payment
           would reach the node but not the wallet of the recipient, so don't
           send it."}

    ;; value: the remote items of time splits
    {:id :value/remote-unresolved :level :warning
     :doc "The value block of the remote item of a time split couldn't be
           fetched, so only the fees of that split are paid until a later fetch
           finds it. The Podcast Index or the remote feed didn't answer, refused
           the request or answered with an error, or there were neither
           credentials for the index nor a feed URL to ask."}

    ;; nostr: signing with the listener's key, and publishing
    {:id :nostr/signer-refused :level :warning
     :doc "The signer didn't sign, e.g. because the listener said no, and
           :reason is what it said."}
    {:id :nostr/signer-approval :level :info
     :doc "The remote signer asks the listener to approve the request on the
           web page at :url first. Open the page, and ask again once the
           listener approved."}
    {:id :nostr/signer-silent :level :warning
     :doc "The remote signer didn't answer in time. The request went out, so
           the signer can still sign it."}
    {:id :nostr/signer-unreachable :level :warning
     :doc "No relay of the connection took the request, so it reached no
           signer, and :relays says why."}
    {:id :nostr/signer-unreadable :level :error
     :doc "The answer of the signer can't be read, or the signer signed
           another event than the one it was asked to, and :reason says
           which."}
    {:id :nostr/request-too-large :level :warning
     :doc "The request to the remote signer has more bytes than an encrypted
           payload of NIP-44 holds, so it wasn't sent."}
    {:id :nostr/not-published :level :warning
     :doc "No relay took the event, and :relays says why."}

    ;; value: the recipients of the value blocks of a feed
    {:id :value/no-recipients :level :error
     :doc "The value block names no recipient, so outside its time splits a
           payment goes to nobody."}
    {:id :value/fees-over :level :error
     :doc "The fees of the recipients add up to more than 100 percent, so they
           take the whole of each payment, and the last of them less than it
           asks."}
    {:id :value/no-shares :level :error
     :doc "No recipient but those of the fees has a split, so after the fees a
           payment goes nowhere."}
    {:id :value/same-recipient :level :warning
     :doc "Two recipients have the same address and custom record, so one
           wallet gets both shares, which is likely a mistake."}
    {:id :value/recipient-address :level :error
     :doc "The address of the recipient doesn't have the form of its :type: the
           public key of a node, hexadecimal text of 33 bytes that starts with
           02 or 03, or a Lightning address of the form user@domain on the
           public internet. No payment can reach it."}
    {:id :value/empty-time-split :level :error
     :doc "The time split names neither a remote item nor a recipient, so during
           it a payment goes to the fees alone."}
    {:id :value/mixed-time-split :level :warning
     :doc "The time split names a remote item and recipients too, which the
           podcast namespace doesn't allow, and an app pays the remote item."}
    {:id :value/time-splits-overlap :level :warning
     :doc "The time split starts before the one before it ends, so an app pays
           the recipients of the earlier one until it ends."}
    {:id :value/no-method :level :warning
     :doc "The app gave no way to pay a recipient of this :type, so it wasn't
           paid."}

    ;; value: paying a Lightning address
    {:id :value/not-an-address :level :warning
     :doc "The text isn't a Lightning address of the form user@domain."}
    {:id :value/unreachable :level :warning
     :doc "The request failed, because the server didn't answer."}
    {:id :value/lnurl-error :level :warning
     :doc "The service of the Lightning address answered with an error, and said
           why."}
    {:id :value/malformed :level :warning
     :doc "The service of the Lightning address answered with something that
           LNURL-pay doesn't define."}
    {:id :value/amount-out-of-range :level :warning
     :doc "The Lightning address doesn't accept this amount."}
    {:id :value/wrong-amount :level :error
     :doc "The invoice asks for a different amount than the one requested, so
           don't pay it."}
    {:id :value/unknown-success-action :level :error
     :doc "The answer has a success action that LUD-09 doesn't define, or a URL
           on another host, so don't pay the invoice."}
    {:id :value/unverifiable :level :info
     :doc "The service of the Lightning address gave no URL to ask whether the
           invoice is paid, which LUD-21 defines."}
    {:id :value/unproven :level :error
     :doc "The service of the Lightning address says that the invoice is paid,
           but gives no preimage that proves it."}
    {:id :value/no-keysend :level :info
     :doc "The domain of the Lightning address names no node for keysend, so pay
           it with LNURL-pay."}
    {:id :value/insecure :level :error
     :doc "The answer came over plain http, where anyone on the way can change
           it, so don't pay with it."}

    ;; value: paying through the wallet of the listener
    {:id :value/no-wallet-info :level :info
     :doc "No relay of the connection has the info event of the wallet, so it
           isn't known which commands the wallet accepts. The wallet says so
           when it gets a command that it doesn't accept."}
    {:id :value/wallet-unsupported :level :warning
     :doc "The wallet doesn't accept the command: its `:methods` don't name the
           `:method` of the command, or its `:encryption` lacks NIP-44 version
           2, which is what podcast-clj writes. No request was sent."}
    {:id :value/command-too-large :level :warning
     :doc "The command is larger than the 65,535 bytes that a request to a
           wallet can hold. The record of a payment includes the titles of the
           show and the episode, and the message of the listener. No request was
           sent."}
    {:id :value/wallet-unreachable :level :warning
     :doc "No relay of the connection took the request: each gave no connection,
           refused the subscription or refused the event, and `:relays` says
           why. The request reached no wallet, so it's safe to try again."}
    {:id :value/wallet-silent :level :error
     :doc "The wallet didn't answer in time. The request went out, so the wallet
           can still carry out the command until `:expires-at`. Don't send the
           payment again before then, and afterwards ask the wallet whether it
           paid."}
    {:id :value/wallet-refused :level :warning
     :doc "The wallet answered with an error and didn't carry out the command.
           The `:code` is the code of NIP-47, such as INSUFFICIENT_BALANCE,
           QUOTA_EXCEEDED or PAYMENT_FAILED, and `:reason` is the text of the
           wallet. A WebLN provider gives a text and no code, also when the user
           said no."}
    {:id :value/wallet-unreadable :level :error
     :doc "The answer can't be read, and `:reason` says why: it isn't the signed
           event of the wallet, it isn't encrypted for this connection, or it
           answers another request. The wallet may have carried out the command,
           so ask it before paying again."}
    {:id :value/no-provider :level :info
     :doc "The page has no WebLN provider, which a browser extension or a wallet
           app gives it."}
    {:id :value/below-one-sat :level :warning
     :doc "WebLN counts a keysend payment in whole sats, and this payment is
           below 1 sat, so nothing was sent."}
    {:id :value/not-a-node :level :warning
     :doc "The recipient isn't a node with a public key, or the payment has no
           amount, so keysend can't pay it and nothing was sent. Pay a Lightning
           address with an invoice."}

    ;; analytics: reading the list of user agents and server logs, and the
    ;; downloads of OP3
    {:id :analytics/unreadable-pattern :level :warning
     :doc "A pattern in the list of user agents doesn't compile on this
           platform, so its entry was left out."}
    {:id :analytics/unreadable-line :level :warning
     :doc "A line of a server log doesn't read as a request in the format of
           the log, so it counts as no download."}
    {:id :analytics/unreachable :level :warning
     :doc "OP3 gave no page of downloads: the request failed, or OP3 answered
           with an error. The result has the downloads of the pages before it,
           and its continuation token names that page."}]))

(def catalogue
  "The diagnostics, in the order they tend to arise, and then those of a
  sync step, from the catalogue of like-minded."
  (into podcast-catalogue sync-diagnostics/catalogue))

(def by-id
  "The catalogue by id."
  (into {} (map (juxt :id identity)) catalogue))

(def ^:no-doc secret-param
  "The pattern of the name of a query parameter whose value can be a
  secret, e.g. the token of a private feed or an API key."
  #"(?i)token|key|secret|pass|auth|sig|session")

(defn ^:no-doc masked-url
  "The `url` without a user name and password, and with … as the value of
  each query parameter whose name sounds secret, e.g. ?token=…"
  [url]
  (str/replace (first (url/split-credentials url))
               #"([?&])([^=&#]*)=([^&#]*)"
               (fn [[whole separator k _]]
                 (if (re-find secret-param k)
                   (str separator k "=…")
                   whole))))

(defn ^:no-doc masked
  "The `diagnostic` with the secrets of its :url masked, there and in its
  :reason, so that a log that holds it holds no token of a private feed."
  [{:keys [url reason] :as diagnostic}]
  (if (string? url)
    (let [m (masked-url url)]
      (cond-> (assoc diagnostic :url m)
        (string? reason) (update :reason str/replace url m)))
    diagnostic))

(defn describe
  "The `diagnostic` with a :message and a :level: its own, or else those
  that the catalogue gives for its id. An id that the catalogue doesn't
  know is left as it is. The secrets of its :url are masked, e.g. the
  ?token= of a private feed."
  [diagnostic]
  (let [{:keys [doc level]} (by-id (:id diagnostic))]
    (cond-> (masked diagnostic)
      (and doc (not (:message diagnostic)))  (assoc :message doc)
      (and level (not (:level diagnostic))) (assoc :level level))))

(defn fetch-failure
  "The diagnostic of the fetch `m` that gave no document, described.

  The `m` is a map of the :url, and the :status of an answer that isn't a
  success, or else the :reason that the request failed. It's :fetch/status
  with a status and :fetch/unreachable without, with the other keys of `m`,
  e.g. a :feed-guid."
  [m]
  (describe (assoc m :id (if (:status m) :fetch/status :fetch/unreachable))))

(defn file-failure
  "The diagnostic of a file that didn't come, described, by the `failure`
  of its request, as http/failure gives it, or by the :status of an answer
  that isn't a success. It's :file/unreachable with the :reason, :type,
  :url and :status of `failure`."
  [failure]
  (describe (into {:id :file/unreachable}
                  (select-keys failure [:reason :type :url :status]))))

(defn failed
  "A map of the `diagnostic`, described, under :error, as a step gives it
  when it fails."
  [diagnostic]
  {:error (describe diagnostic)})

(defn summarize
  "Group `diagnostics` by id and message, most frequent first: one row per
  kind, with its :count and its first occurrence as :example."
  [diagnostics]
  (->> (group-by (juxt :id :message) diagnostics)
       (map (fn [[_ [example :as group]]]
              (-> (select-keys example [:id :level :message])
                  (assoc :count (count group) :example example))))
       (sort-by (fn [{:keys [count id]}] [(- count) (str id)]))))

(def levels
  "The levels of a diagnostic, from the worst."
  [:error :warning :info])

(defn verdict
  "The verdict of `diagnostics` from any reports, e.g. those of a fetch, a
  parse, a validation and a check, as a map:

  - :passed?, whether none is an error
  - :level, the worst level, or nil when there are none
  - :counts, the number at each level
  - :errors, what fails, as summarize gives it"
  [diagnostics]
  (let [counts (frequencies (map :level diagnostics))
        errors (filter (comp #{:error} :level) diagnostics)]
    {:passed? (empty? errors)
     :level   (some #(when (contains? counts %) %) levels)
     :counts  (into {} (map (juxt identity #(get counts % 0))) levels)
     :errors  (summarize errors)}))
