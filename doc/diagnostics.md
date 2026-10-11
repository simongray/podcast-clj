# Diagnostics

Generated from `dk.simongray.podcast.diagnostics` by `dev/reference.clj`, so don't edit it
by hand.

A diagnostic is a map with an `:id`, a `:level` and a `:message`, where the message is the
meaning below unless the code gives a more specific one. Other keys give details, such as
a `:path` into the data or the `:tag` of an element. Use `report` for the diagnostics of a
parsed feed, transcript or chapters document, `validate` for those of a feed, and
`summarize` to group diagnostics by kind.

The level says how much a diagnostic matters:

- `error`: a rule of a spec or of the chosen requirements is broken, so apps or a
  directory can misread the feed, or refuse it
- `warning`: the feed works, but apps are likely to show or treat something worse
  than the podcaster means
- `info`: something worth knowing that needs no change, e.g. a repair that the parse
  made

The requirements of a directory can raise a level, e.g. a repeated enclosure URL is an
error for Apple. An error under Apple's requirements breaks a rule of its guide, though
Apple Podcasts Connect doesn't say which rules it refuses a feed for. To tell whether a
feed passes, give the diagnostics of `validate/report` to `diagnostics/verdict`.

The namespace of an id says which part of podcast-clj found the problem:

- `fetch`: the fetch of a feed or a list
- `discovery`: finding the feeds of a show from an address
- `repair`: the repairs of malformed XML
- `xml`: namespace resolution
- `quirk`: the normalisations of the tree
- `feed`: the model
- `transcript` and `chapters`: the companion formats
- `id3`, `mp3`, `mp4` and `file`: the media files and the images
- `validate`: the validator
- `check`: the checks over the network
- `sync`: the sync between devices, through like-minded
- `gpodder`: a sync through the gpodder API
- `library`: a saved library
- `podping`: a poll of the Hive chain
- `comments`: the threads of comments
- `value`: the records of payments, the remote items of time splits, the Lightning
  addresses and the wallets
- `analytics`: the list of user agents, and the downloads of OP3

A function that sends requests also returns its failures as diagnostics, under the
`:error` of its result.

| Id | Level | Meaning |
|---|---|---|
| `:fetch/unreachable` | warning | The feed or list couldn't be fetched: the name of its host wasn't found, the host couldn't be reached, the secure connection failed, the request took too long, the answer was too large, or the URL wasn't allowed. The :type of wary-fetch says which, when the platform tells. |
| `:refresh/offline` | info | The device seems to be offline: :online? said so, or every fetch of the refresh failed before a server answered, at more than one host. No feed counts it as a failure, and each stays due, so the next refresh fetches it. |
| `:fetch/malformed` | warning | The document isn't an RSS feed, or for a list, an OPML document: it isn't XML, or its root element isn't rss or opml. |
| `:fetch/unsupported-format` | warning | The document is an Atom or RSS 1.0 feed rather than RSS 2.0, so it can't be read. The :format names which. |
| `:fetch/page-failed` | warning | A page of a paged feed couldn't be fetched or read, so the feed lacks the items of that page and the pages after it. |
| `:fetch/status` | warning | The server answered with a status that isn't a success, such as 404. The feed stays as it was, and the next fetch waits longer after each failure in a row. |
| `:fetch/empty-feed` | warning | The feed came back without items, though it had some before, as some hosts answer during an outage. The feed stays as it was, and the next fetch waits as after a failure. |
| `:fetch/guid-taken` | warning | The feed declares the podcast:guid of another subscription, at another URL, and would take that subscription over, so it isn't added. To replace the other subscription, unsubscribe from it first. |
| `:fetch/guid-changed` | info | The feed gives itself another guid than before, from :old to :new, e.g. a new podcast:guid, or a new URL of a feed that declares none. Its subscription keeps its key, the :feed-guid, and notes the new guid. |
| `:discovery/unreadable` | warning | The text isn't a URL, a domain or a link of an app, so there's nothing to fetch. It may be words to search a directory with. |
| `:discovery/not-found` | warning | No feed was found: the page names none that could be read, and none of the other places that were tried has one. |
| `:directory/source-failed` | warning | A directory gave no answer to a search, a chart or a lookup, so what it knows is missing from the results. The :source names it, and the :reason says why. |
| `:repair/leading-junk` | warning | Text before the XML declaration or the rss element, such as whitespace, a byte order mark or a PHP warning, was removed. |
| `:repair/trailing-junk` | warning | Text after the end of the rss element, such as a PHP warning, was removed. |
| `:repair/control-characters` | warning | Control characters that XML forbids, and character references to them, were removed. |
| `:repair/undeclared-prefixes` | warning | Known namespace prefixes that were used without a declaration were declared. |
| `:repair/html-entities` | warning | HTML entities that XML lacks, such as &nbsp;, were replaced by character references. |
| `:repair/bare-ampersands` | warning | Bare ampersands were escaped. |
| `:repair/lenient-parser` | warning | The document was still malformed after the repairs, so it was parsed leniently with jsoup. |
| `:xml/default-namespace` | info | A default namespace isn't known, so the elements in it have no alias, and its declaration stays as an xmlns attribute. |
| `:xml/unknown-namespace` | info | The namespace URI isn't known, so its prefix is used as the alias. |
| `:xml/undeclared-prefix` | warning | A prefix was used without a declaration, which only the JVM parser accepts, so the prefix is used as the alias. |
| `:quirk/namespace-variant` | info | A namespace was bound to a variant of its URI, such as http for https, another case, a trailing slash or an old URI, and was recognised anyway. |
| `:quirk/rss-default-namespace` | info | The rss element declared a default namespace, such as that of Atom, though RSS 2.0 has none, so it was ignored. |
| `:quirk/itunes-image-text` | info | An itunes:image gave its URL as text or in a url attribute instead of href. |
| `:quirk/explicit-value` | info | An itunes:explicit said yes, no, explicit or clean, which was read as true or false. |
| `:quirk/yes-no-value` | info | A yes/no element said true or false, which was read as yes or no. |
| `:quirk/transcript-type` | info | A transcript type was written loosely, as srt, vtt, json or text, and was corrected to its MIME type. |
| `:quirk/attribute-name` | info | An href attribute was used where the spec asks for url, and was renamed. |
| `:quirk/enclosure-length` | info | An enclosure length that wasn't a number was dropped. |
| `:quirk/empty-guid` | info | An empty guid was dropped, so the identity of the item is its enclosure URL. |
| `:quirk/deprecated-images` | info | A deprecated podcast:images srcset was turned into podcast:image elements. |
| `:quirk/item-image` | info | An item had an image element with only attributes, which RSS 2.0 lacks, and it was read as the itunes:image that it stands for. |
| `:quirk/podcast-category` | info | A channel had the non-standard podcast:category elements that Wavlake writes, and no itunes:category, so they were read as itunes:category, whose shape they copy. |
| `:quirk/duration-format` | info | An itunes:duration was written as a clock time rather than in seconds, and was read as seconds. |
| `:quirk/date-format` | info | A date wasn't in strict RFC 2822 form, but could still be read. |
| `:feed/duplicate-element` | warning | An element that the spec allows once occurred again, and only the first one is kept. The :tag names the element, and the :count is the number of the others. |
| `:feed/empty-element` | info | An element with a single value had no text, and was left out. |
| `:feed/empty-attribute` | info | An optional attribute was blank, and is left out of the model. A required one that's blank is kept for the validator. |
| `:feed/invalid-value` | warning | A value didn't read as its type, and stays a string. |
| `:feed/string-value` | info | A feed built from data held a string where the model takes another type, e.g. a date, and it was read as that type, as parse reads the text of XML. The :value is the string, and the :type the type. |
| `:feed/markup-in-text` | info | Markup appeared where the registry expects text. It's kept as markup in HTML content, and flattened to text elsewhere. |
| `:feed/unexpected-text` | info | Text inside an element that the spec leaves empty was dropped. |
| `:feed/dropped-attributes` | info | Attributes that the model has no place for were dropped: those of an element read as a single value, those of rss other than its version, and those of channel. |
| `:transcript/speaker-prefixes` | info | Speakers were taken from Name: prefixes in the cues, and carried over to the cues that follow. |
| `:transcript/voices-carried` | info | A WebVTT file gives a voice span only where the speaker changes, so the cues without one took the speaker before them. The :count is the number of such cues. |
| `:transcript/string-times` | warning | Segment times were given as strings rather than numbers, and were read as numbers. |
| `:transcript/wrapped-srt` | warning | An HTML transcript was an SRT file with every line wrapped in an element, and was read as SRT. |
| `:transcript/unknown-type` | warning | The declared type isn't one that the spec names, so the format was detected from the file instead. |
| `:transcript/type-mismatch` | warning | The declared type doesn't match the file, which was read as the format that it really is. |
| `:transcript/not-an-object` | warning | A JSON transcript isn't JSON, or isn't a JSON object, so it holds no segments. |
| `:transcript/malformed` | warning | Segments of a JSON transcript that aren't JSON objects in a list were left out. |
| `:transcript/not-text` | warning | Speakers in a JSON transcript that aren't text, such as a JSON object, were left out. |
| `:chapters/string-times` | warning | Chapter times were given as strings rather than numbers, and were read as numbers. |
| `:chapters/not-text` | warning | Chapter titles, links or place names that aren't text, such as a JSON object, were left out. |
| `:chapters/missing-start-time` | warning | Chapters without a start time can't be placed. |
| `:chapters/unsorted` | info | The chapters weren't in order of start time, which the format assumes. |
| `:chapters/unknown-keys` | info | Keys that the format doesn't define were kept as they are. |
| `:chapters/old-version` | info | The document was written for an older version of the format. |
| `:chapters/bad-timestamps` | warning | Podlove chapters whose start isn't a timestamp were dropped. |
| `:chapters/inferred` | info | The chapters were read from timestamps in the show notes rather than from a chapters file. |
| `:chapters/not-an-object` | warning | The document isn't JSON, or isn't a JSON object, so it holds no chapters. |
| `:chapters/malformed` | warning | Chapters that aren't JSON objects in a list were left out. |
| `:id3/frame-size` | info | An ID3v2 frame gave its size in the form of the other version, a plain number in ID3v2.4 or a syncsafe number in ID3v2.3, and the size was read in that form. |
| `:id3/text-encoding` | info | An ID3v2 frame names a text encoding that ID3 doesn't define, so its text was read as ISO-8859-1. |
| `:id3/utf8-latin1` | info | An ID3v2 frame declared its text as ISO-8859-1, but the bytes are UTF-8, so the text was read as UTF-8. |
| `:id3/utf16-bom` | info | An ID3v2 frame declared its text as UTF-16 without a byte order mark, so the byte order was worked out from where the zero bytes are. |
| `:id3/image-type` | info | An ID3 picture gave its type loosely, such as JPG or image/jpg, or gave none, so the type was taken from the bytes of the image. |
| `:id3/chapter-end` | info | An ID3 chapter ends at or before its start, so its end was left out. |
| `:id3/second-tag` | info | A second ID3v2 tag follows the first, as when a writer adds a tag in front of an existing one, and both tags were read. |
| `:id3/skipped-frame` | info | An ID3v2 frame is compressed or encrypted, and wasn't read. |
| `:id3/malformed` | warning | A frame of an ID3v2 tag doesn't read, and was left out. When its size doesn't read, the frames after it were left out too. |
| `:id3/padding-junk` | info | The padding after the ID3v2 frames holds bytes that aren't zeros, because a writer didn't clear it or a frame gave a wrong size. |
| `:mp3/junk-before-audio` | info | Bytes that aren't MPEG audio frames lie between the tags and the first frame, e.g. padding that the size of a tag leaves out, and were skipped. |
| `:mp3/estimated-duration` | info | The MP3 file has no Xing, Info or VBRI header, so the duration was estimated from the bitrate of the first frames and the size of the file. The estimate is exact for a constant bitrate. |
| `:mp3/no-audio` | warning | No MPEG audio frame was found after the tags, so the file has no duration. |
| `:mp4/zero-duration` | info | The movie header of the MP4 file gives no duration, so the duration of the longest track was used. |
| `:mp4/fragments-unread` | info | The MP4 file is in fragments, with no mehd box and no index at its end that gives the duration of all of them, so the file has no duration. |
| `:mp4/no-movie` | warning | The MP4 file has no movie box, so it gives no duration and no chapters. |
| `:file/unknown-format` | warning | The file isn't in a format that its reader knows, so nothing was read from it. Media files can be MP3 or MP4, and images PNG, JPEG, GIF or WebP, while AVIF, HEIC, HEIF, SVG, BMP, TIFF and ICO images are only recognised by their type. |
| `:file/truncated` | warning | The file ends before its structures say it does. |
| `:file/malformed` | warning | The file doesn't read as its format says. |
| `:file/duration` | info | The duration of the media file or stream differs from the one that the feed declares by more than 10 seconds and 2 percent. A host that inserts ads can explain it, but then a position in this file may not be the same moment in another file of the episode, such as its video. |
| `:file/incomplete` | warning | The bytes that the reader needed didn't arrive, or a limit stopped the reading, so nothing was read. |
| `:file/unreachable` | warning | The file couldn't be fetched: the request failed, or the server answered with a status that isn't a success. |
| `:file/unverifiable` | info | This podcast:integrity can't be checked. A PGP signature needs the key of the publisher, and an sri value must name a sha256, sha384 or sha512 hash. |
| `:check/unreachable` | error | The file couldn't be fetched: the name of its host wasn't found, the host couldn't be reached, the TLS handshake failed, or the request took too long. The :type of wary-fetch says which, when the platform tells. |
| `:check/status` | error | The server answered with a status that isn't a success, so a listener doesn't get the file. |
| `:check/downgrade` | error | A redirect leads from https to http, which clients refuse to follow. |
| `:check/redirects` | warning | The file is more than five redirects away, more than the check and some clients follow. |
| `:check/moved` | warning | The file moved for good, with a 301 or 308 redirect, so the feed can name its new URL. |
| `:check/head` | error | The server doesn't answer a HEAD request for the file. RFC 9110 requires HEAD of every general-purpose server, and directories use it to check files. |
| `:check/type` | warning | The server gives the file a different media type than the feed declares. |
| `:check/no-reader` | info | A reading of the file was asked for, but the media reader knows no format of the :type that the feed declares, e.g. audio/x-wav, so its duration and codecs weren't checked. |
| `:check/no-type` | warning | The feed declares no media type for the file, which RSS 2.0 and the podcast namespace require. The :served type is the one that the server gives it. |
| `:check/length` | info | The size of the file differs from the length that the feed declares. A host that inserts ads sends a file of a different size to each request, so the length can't be exact. |
| `:check/ranges` | warning | The server doesn't answer a request for a byte range with that range. RFC 9110 makes ranges optional but recommends them, players need them to seek and resume, and Apple Podcasts requires them. |
| `:check/format` | warning | The media file is in a different format than the type that the feed declares. |
| `:check/duration` | info | The duration of the media file differs from the one that the feed declares by more than 10 seconds and 2 percent, which a host that inserts ads can explain. |
| `:check/slow-start` | warning | The movie of the MP4 file comes after its media data, so a player must fetch the end of the file before it can play the start. Writing the movie first fixes it. |
| `:check/codecs` | warning | The codecs that the alternate enclosure declares are those of neither its file nor any variant of its stream, so an app that asks the platform whether it can play them gets the wrong answer. |
| `:check/height` | warning | The height that the alternate enclosure declares is that of neither its video nor any variant of its stream. |
| `:check/unread` | warning | The file arrived, but its content wasn't read, because a request for more of its bytes failed or its reader failed. |
| `:check/integrity` | error | The media file doesn't match the hash of its podcast:integrity. |
| `:check/unverified` | info | The media file wasn't compared with its podcast:integrity, because a PGP signature needs the key of the publisher, the integrity names no hash that Subresource Integrity knows, or the whole file didn't arrive. |
| `:check/image-type` | info | The server gives the image a different media type than its bytes have, such as image/jpg for a JPEG. Browsers go by the bytes of an image. |
| `:check/image-size` | warning | The size of the image differs from the width, height or aspect ratio that its podcast:image declares. |
| `:check/artwork-size` | error | The image of an itunes:image isn't a square of 1400 to 3000 pixels, as the chosen requirements ask. |
| `:check/artwork-type` | error | The image of an itunes:image isn't JPEG or PNG, as the chosen requirements ask, or it's a JPEG that apps and browsers can't decode: lossless, hierarchical, arithmetic-coded, JPEG-LS, or with more than 8 bits per sample. |
| `:check/artwork-color` | error | The image of an itunes:image isn't in RGB colours, as the chosen requirements ask. |
| `:check/artwork-alpha` | error | The image of an itunes:image has an alpha channel or transparency, which the chosen requirements don't allow. |
| `:check/artwork-extension` | warning | The file extension in the URL of an itunes:image doesn't match the type of its image, as the chosen requirements ask. |
| `:check/last-modified` | warning | The server answers a HEAD request for the artwork without Last-Modified, which the chosen requirements ask for. |
| `:check/cors` | warning | The server doesn't let pages on other sites read the file, so web players can't show it. The Access-Control-Allow-Origin header of the answer, or of a redirect on the way, decides this, and the podcast namespace requires CORS for transcripts. |
| `:check/empty` | error | The transcript or chapters file holds no segments or chapters in the format that the feed declares. |
| `:check/feed-cors` | info | The server doesn't let pages on other sites read the feed, so a web app can't read it without a server of its own. |
| `:check/validators` | info | The server sends the feed without ETag or Last-Modified, so a client can't ask whether it changed, and has to fetch it whole every time. |
| `:check/unchecked` | warning | The check reached a limit of files or time, and skipped the files after it, which the diagnostic counts. |
| `:validate/rss-version` | warning | The version of the rss element isn't 2.0. The :value is the one it has. A feed built from data has none, which emit writes. |
| `:validate/missing-element` | error | An element that RSS 2.0 requires is missing. |
| `:validate/missing-enclosure` | warning | An item has no enclosure, so there's nothing to play. |
| `:validate/duplicate-guid` | error | An item has the guid of an earlier item, so apps, directories and sync servers take the two for one episode. The :first-path is that of the earlier item. |
| `:validate/duplicate-enclosure` | warning | An item has the enclosure URL of an earlier item, e.g. a trailer and the episode it's cut from. Apple keeps only the first, so its requirements make it an error. The :first-path is that of the earlier item. |
| `:validate/unknown-media-type` | warning | The type of an enclosure is neither audio, video nor a playlist of either, e.g. application/octet-stream, so apps may not play it. The :value is the type. |
| `:validate/unknown-length` | info | The length of an enclosure is 0, which says that it isn't known, e.g. under dynamic ad insertion, so an app can't tell the size of the file before it downloads it. |
| `:validate/unknown-category` | warning | Apple Podcasts doesn't list the category of a podcast, so the show isn't listed under it. The :value is its name, and the :replacement is the path of the one that Apple lists in another case, if any. The requirements of Apple and PSP-1 make it an error. |
| `:validate/renamed-category` | warning | The category is one that Apple Podcasts renamed or dropped in 2019, which it still reads. The :replacement is the path of the category and perhaps the subcategory that stands for it now. |
| `:validate/escaped-category` | warning | The ampersand of a category's name was escaped twice, e.g. Society &amp; Culture, so it names no category. The :replacement is the name meant. The requirements of Apple and PSP-1 make it an error. |
| `:validate/wrong-subcategory` | warning | The subcategory belongs under another category, or stands alone where a category belongs. The :replacement is the path where Apple Podcasts lists it. The requirements of Apple and PSP-1 make it an error. |
| `:validate/category-depth` | warning | A subcategory holds a third level, which Apple Podcasts doesn't take. The requirements of Apple and PSP-1 make it an error. |
| `:validate/missing-attribute` | error | A required attribute is missing. |
| `:validate/wrong-shape` | error | A value doesn't have the shape that its element takes: a bare value where a map of attributes belongs, or a map or sequence where a single value does. |
| `:validate/missing-child` | error | A container lacks a child that the spec requires. |
| `:validate/invalid-structure` | error | The children of a container contradict the spec, such as a time split with both recipients and a remote item. |
| `:validate/invalid-value` | error | A value isn't of its type, such as a string where a date belongs or :no where a boolean does, or it's a negative number, which no quantity in a feed can be. The :value is the value. |
| `:validate/invalid-enum` | warning | A value isn't one of those that the spec lists. The :value is the value. |
| `:validate/invalid-url` | warning | A URL isn't absolute. The :value is the URL. |
| `:validate/http-url` | info | A URL uses http where the podcast namespace requires https. |
| `:validate/invalid-guid` | warning | A podcast:guid isn't a UUID, so the library, like the Podcast Index, identifies the feed by the guid derived from its URL. The :value is the guid. |
| `:validate/unknown-role` | info | The role of a person isn't in the Podcast Taxonomy. The :value is the role. |
| `:validate/unknown-element` | info | The registry doesn't know the element, so it's kept under :xml/unknown. |
| `:validate/unknown-key` | warning | The registry doesn't define the :key, e.g. the typo :rss/titel, or a key on the channel that isn't an element, since a parse keeps no attributes there. |
| `:validate/deprecated` | info | The spec of the element has deprecated it. |
| `:validate/required` | error | A required element of the chosen requirements is missing. The :key is its key in the model. |
| `:validate/recommended` | warning | A recommended element of the chosen requirements is missing. The :key is its key in the model. When every item lacks it, it's one diagnostic of all the items, whose :count is the number of items. |
| `:gpodder/unknown-episodes` | info | Episode actions from a gpodder server name episodes of feeds that aren't fetched yet. They wait in the cursor, and the next pull tries them again. |
| `:gpodder/future-actions` | warning | Episode actions from a gpodder server name a time more than a minute after the pull, as a device with a fast clock writes them, so they changed nothing. |
| `:gpodder/malformed-actions` | warning | Episode actions from a gpodder server name no feed, no media file or no readable time, and were left out. |
| `:gpodder/unnamed-episodes` | info | Episodes changed, but the server wasn't told, because the gpodder API names an episode by the URL of its media, and that URL isn't known. |
| `:gpodder/rejected-url` | warning | A gpodder server refused a URL that was sent to it, so it keeps no subscription or episode action for it. |
| `:library/save-failed` | warning | The library or its search index couldn't be written to the store in full. The :reason says why. |
| `:library/unreadable-document` | warning | A document of the saved library didn't read: library.edn, so none of its parts were restored, or the document of a feed, which is fetched again. The :name says which, and the :reason why. |
| `:library/newer-format` | error | The saved library is of a newer version than this one reads, so none of it was restored, and a save won't write over it. The :version says which. |
| `:library/malformed-profile` | warning | The saved profile breaks the rules of a profile, so the library kept the profile it had, and the next sync brings back what the other devices hold. The :reason says what's wrong. |
| `:library/index-rebuilt` | info | The search index was built again from the feeds, since some of its documents were missing or didn't read. The :reason says why. |
| `:podping/unavailable` | warning | No node of the Hive chain answered a call of the poll. The result has the pings of the blocks that were read before that call. |
| `:comments/unsupported` | info | Comments over the protocol of the thread can't be read. |
| `:comments/no-appview` | warning | An AT Protocol thread wasn't read, because the options named no AppView. |
| `:comments/no-relay` | warning | A Nostr thread wasn't read, because neither the options nor the root named a relay. |
| `:comments/malformed` | warning | The URI of the root post, or a post, a collection of replies, an author or a thread that a server sent, isn't in a form that can be read. |
| `:comments/unreachable` | warning | A post, a collection of replies or an author couldn't be fetched: the request failed, or the server answered with an error. With `:retry?`, the next fetch of the thread asks again. |
| `:comments/refused` | warning | A server refused an unsigned request, as a server in secure mode does. |
| `:comments/untrusted` | warning | A server sent a post or an author from another server, which wasn't used, since a server only speaks for its own posts and authors. |
| `:comments/through-api` | info | ActivityPub didn't give the root post, so the thread was read through the Mastodon API of the server of the root post, which can give fewer replies. |
| `:comments/withheld` | info | Some replies were left out, because they aren't public, their authors ask apps to show them only to signed-in users, or a moderation service hid them. An AppView also holds back a reply when its author and the author of the post above it block each other. |
| `:comments/unverified` | warning | Events from a Nostr relay were left out, and the diagnostic counts them. Such an event has a wrong id or signature, or isn't one that the request asked for. |
| `:comments/truncated` | warning | A size limit cut some replies, which the diagnostic counts. Its `:limit` names the limit: `:page` for the 1,000 items of a page of replies, `:id` for the 2,048 characters of an id, `:thread` for the 10,000 comments of a thread, or `:depth` for the 64 levels of replies of an AT Protocol thread. |
| `:comments/incomplete` | info | The fetch reached a limit before it read the whole thread, or a server didn't answer for a while. Fetch the thread again to read more. |
| `:value/not-an-object` | warning | The record isn't a JSON object, so it describes no payment. |
| `:value/text-for-number` | info | Numbers came as text, as some apps write them, and were read as numbers. |
| `:value/rounded` | info | Amounts in millisatoshis had a fraction, and were rounded. |
| `:value/field-alias` | info | Fields came under other names, as some apps write them, and were read under their own names. |
| `:value/unreadable` | warning | Some fields don't hold a value of their type, and were left out. |
| `:value/guid-as-item-id` | info | The itemID holds the guid of the episode, as Breez writes it, and was read as that guid. |
| `:value/action-alias` | info | The action came as streaming, as Podverse writes it, and was read as stream. |
| `:value/unknown-action` | warning | The action isn't boost, stream or auto, so the payment has no action. |
| `:value/unknown-keys` | info | The record has fields that its format doesn't define, which keep their names. |
| `:value/no-podcast` | warning | The record names no podcast: no guid, feed id, feed URL or title. |
| `:value/claimed-amount` | warning | The sender claims a different amount than the node received, so the payment keeps the amount of the node. |
| `:value/claimed-recipient` | warning | The sender claims that the payment went to a different recipient than the one that received it. |
| `:value/no-metadata` | warning | The comment names no URL of metadata, or the URL answered without the x-rss-payment header. |
| `:value/custom-record` | error | The records of the payment leave out the custom key and value of the recipient, because the key isn't a number, it's a type that the Lightning protocol reserves, or the value is missing. The payment would reach the node but not the wallet of the recipient, so don't send it. |
| `:value/remote-unresolved` | warning | The value block of the remote item of a time split couldn't be fetched, so only the fees of that split are paid until a later fetch finds it. The Podcast Index or the remote feed didn't answer, refused the request or answered with an error, or there were neither credentials for the index nor a feed URL to ask. |
| `:value/not-an-address` | warning | The text isn't a Lightning address of the form user@domain. |
| `:value/unreachable` | warning | The request failed, because the server didn't answer. |
| `:value/lnurl-error` | warning | The service of the Lightning address answered with an error, and said why. |
| `:value/malformed` | warning | The service of the Lightning address answered with something that LNURL-pay doesn't define. |
| `:value/amount-out-of-range` | warning | The Lightning address doesn't accept this amount. |
| `:value/wrong-amount` | error | The invoice asks for a different amount than the one requested, so don't pay it. |
| `:value/unknown-success-action` | error | The answer has a success action that LUD-09 doesn't define, or a URL on another host, so don't pay the invoice. |
| `:value/no-keysend` | info | The domain of the Lightning address names no node for keysend, so pay it with LNURL-pay. |
| `:value/insecure` | error | The answer came over plain http, where anyone on the way can change it, so don't pay with it. |
| `:value/no-wallet-info` | info | No relay of the connection has the info event of the wallet, so it isn't known which commands the wallet accepts. The wallet says so when it gets a command that it doesn't accept. |
| `:value/wallet-unsupported` | warning | The wallet doesn't accept the command: its `:methods` don't name the `:method` of the command, or its `:encryption` lacks NIP-44 version 2, which is what podcast-clj writes. No request was sent. |
| `:value/command-too-large` | warning | The command is larger than the 65,535 bytes that a request to a wallet can hold. The record of a payment includes the titles of the show and the episode, and the message of the listener. No request was sent. |
| `:value/wallet-unreachable` | warning | No relay of the connection took the request: each gave no connection, refused the subscription or refused the event, and `:relays` says why. The request reached no wallet, so it's safe to try again. |
| `:value/wallet-silent` | error | The wallet didn't answer in time. The request went out, so the wallet can still carry out the command until `:expires-at`. Don't send the payment again before then, and afterwards ask the wallet whether it paid. |
| `:value/wallet-refused` | warning | The wallet answered with an error and didn't carry out the command. The `:code` is the code of NIP-47, such as INSUFFICIENT_BALANCE, QUOTA_EXCEEDED or PAYMENT_FAILED, and `:reason` is the text of the wallet. A WebLN provider gives a text and no code, also when the user said no. |
| `:value/wallet-unreadable` | error | The answer can't be read, and `:reason` says why: it isn't the signed event of the wallet, it isn't encrypted for this connection, or it answers another request. The wallet may have carried out the command, so ask it before paying again. |
| `:value/no-provider` | info | The page has no WebLN provider, which a browser extension or a wallet app gives it. |
| `:value/below-one-sat` | warning | WebLN counts a keysend payment in whole sats, and this payment is below 1 sat, so nothing was sent. |
| `:value/not-a-node` | warning | The recipient isn't a node with a public key, or the payment has no amount, so keysend can't pay it and nothing was sent. Pay a Lightning address with an invoice. |
| `:analytics/unreadable-pattern` | warning | A pattern in the list of user agents doesn't compile on this platform, so its entry was left out. |
| `:analytics/unreachable` | warning | OP3 gave no page of downloads: the request failed, or OP3 answered with an error. The result has the downloads of the pages before it, and its continuation token names that page. |
| `:sync/backend-failed` | warning | A backend failed in a pull or a push, so the step went on without it, and it keeps its cursor. The :backend names it, and the :reason says why. |
| `:sync/unreadable-document` | warning | A document in the store didn't read as a sync document, and was left out until its device writes one that reads. |
| `:sync/other-kind` | warning | A document holds another kind of state than the one this device syncs, e.g. that of another app pointed at the same folder, and was left out. |
| `:sync/newer-format` | warning | A document was written in a newer version of the state's format than this device reads, and was left out. |
| `:sync/malformed-state` | error | The state of this device holds a value that the other devices can't merge, e.g. from an assoc, so the step wrote nothing. The :error says where. |
| `:sync/clock-skew` | warning | A device wrote its document more than a minute ahead of the clock of this device. When every other device is named, the clock of this device is probably behind. |
| `:sync/shared-device` | warning | The document of this device changed without this device writing it, so two installations, or two tabs of one page, use the same device id. |
| `:sync/retired-device` | warning | This device was retired from another device, so nothing was written for it. Its changes no longer reach the other devices, so give the state a new device id to sync again. |
| `:sync/conflict` | warning | The shared document changed during every attempt to write it, so the step ended without writing. |
