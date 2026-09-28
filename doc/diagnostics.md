# Diagnostics

Generated from `dk.simongray.podcast.diagnostics` by `dev/reference.clj`. Do not edit
this file by hand.

A diagnostic is a map with an `:id`, a `:level` and a `:message`. The message is the
meaning below, unless the code gives a more specific message. Other keys give details,
such as a `:path` into the data or the `:tag` of an element. `report` reads the
diagnostics of a parsed feed, transcript or chapters document. `validate` returns the
diagnostics of a feed. `summarize` groups diagnostics by kind.

The namespace of an id tells which layer found the problem: `repair` for the repairs of
malformed XML, `xml` for namespace resolution, `quirk` for the normalizations of the tree,
`feed` for the model, `transcript` and `chapters` for the companion formats, `validate`
for the validator, and `sync` for the sync between devices.

| Id | Level | Meaning |
|---|---|---|
| `:fetch/page-failed` | warning | A page of a feed that comes in pages could not be fetched or read, so the items of the pages from there on are missing. |
| `:repair/leading-junk` | warning | Text before the XML declaration or the rss element, such as whitespace, a byte order mark or a PHP warning, was removed. |
| `:repair/trailing-junk` | warning | Text after the end of the rss element, such as a PHP warning, was removed. |
| `:repair/control-characters` | warning | Control characters XML forbids, and character references to them, were removed. |
| `:repair/undeclared-prefixes` | warning | Known namespace prefixes used without a declaration were declared. |
| `:repair/html-entities` | warning | HTML entities XML lacks, such as &nbsp;, were replaced by character references. |
| `:repair/bare-ampersands` | warning | Bare ampersands were escaped. |
| `:repair/lenient-parser` | warning | The document was still not well-formed after the repairs and was parsed leniently with jsoup. |
| `:xml/default-namespace` | info | The document declares a default namespace; unprefixed elements are read as RSS 2.0 regardless. |
| `:xml/unknown-namespace` | info | A namespace URI the library does not know; its prefix serves as the alias. |
| `:xml/undeclared-prefix` | warning | A prefix used without a declaration, which only the JVM parser tolerates; it serves as the alias. |
| `:quirk/namespace-variant` | info | A namespace was bound to a variant of its URI, http for https, other casing, a trailing slash or an old URI, and recognized anyway. |
| `:quirk/itunes-image-text` | info | An itunes:image gave its URL as text or in a url attribute instead of href. |
| `:quirk/explicit-value` | info | itunes:explicit said yes, no, explicit or clean, read as true or false. |
| `:quirk/yes-no-value` | info | A yes/no element said true or false, read as yes or no. |
| `:quirk/transcript-type` | info | A transcript type was written loosely (srt, vtt, json, text) and corrected to its MIME type. |
| `:quirk/attribute-name` | info | An href attribute stood where the specification asks for url and was renamed. |
| `:quirk/enclosure-length` | info | An enclosure length that was not a number was dropped. |
| `:quirk/empty-guid` | info | An empty guid was dropped, so the item's identity is its enclosure URL. |
| `:quirk/deprecated-images` | info | A deprecated podcast:images srcset was turned into podcast:image elements. |
| `:quirk/item-image` | info | An item's attribute-only image element, which RSS 2.0 lacks, became the itunes:image it stood for. |
| `:quirk/podcast-category` | info | A channel had the non-standard podcast:category elements Wavlake writes and no itunes:category; they were read as itunes:category, whose shape they mirror. |
| `:quirk/duration-format` | info | An itunes:duration was written as a clock rather than in seconds; read as seconds. |
| `:quirk/date-format` | info | A date was not in strict RFC 2822 form but could still be read. |
| `:feed/duplicate-element` | warning | An element the specification allows once occurred again; the first occurrence is kept. |
| `:feed/empty-element` | info | A scalar element had no text and was omitted. |
| `:feed/empty-attribute` | info | An optional attribute was left blank and is absent from the model; a required one left blank is kept for the validator. |
| `:feed/invalid-value` | warning | A value did not read as its type and stays a string. |
| `:feed/markup-in-text` | info | Markup sat where the registry expects text; kept as markup for HTML content, flattened to text otherwise. |
| `:feed/unexpected-text` | info | Text inside an element the specification leaves empty was dropped. |
| `:feed/dropped-attributes` | info | Attributes the model has no place for were dropped: those of an element read as a single value, of rss other than its version, and of channel. |
| `:transcript/speaker-prefixes` | info | Speakers were taken from Name: prefixes in the cues and carried over the cues that follow. |
| `:transcript/string-times` | warning | Segment times were given as strings rather than numbers and were read as numbers. |
| `:transcript/wrapped-srt` | warning | An HTML transcript was an SRT file with every line wrapped in an element, and was read as SRT. |
| `:transcript/unknown-type` | warning | The declared type is not one the specification names; the format was sniffed instead. |
| `:transcript/type-mismatch` | warning | The declared type does not match the file, which was read as the format it is. |
| `:transcript/not-an-object` | warning | A JSON transcript is not JSON, or not a JSON object, so it holds no segments. |
| `:transcript/malformed` | warning | Segments of a JSON transcript that are not JSON objects in a list were left out. |
| `:transcript/not-text` | warning | Speakers in a JSON transcript that are not text, such as a JSON object, were left out. |
| `:chapters/string-times` | warning | Chapter times were given as strings rather than numbers and were read as numbers. |
| `:chapters/not-text` | warning | Chapter titles, links or place names that are not text, such as a JSON object, were left out. |
| `:chapters/missing-start-time` | warning | Chapters without a start time cannot be placed. |
| `:chapters/unsorted` | info | The chapters were not in order of start time, which the format assumes. |
| `:chapters/unknown-keys` | info | Keys the format does not define were kept as they are. |
| `:chapters/old-version` | info | The document was written against an older version of the format. |
| `:chapters/bad-timestamps` | warning | Podlove chapters whose start is not a timestamp were dropped. |
| `:chapters/inferred` | info | The chapters were read from timestamps in the show notes rather than from a chapters file. |
| `:chapters/not-an-object` | warning | The document is not JSON, or not a JSON object, so it holds no chapters. |
| `:chapters/malformed` | warning | Chapters that are not JSON objects in a list were left out. |
| `:id3/frame-size` | info | An ID3v2 frame gave its size in the form of the other version, a plain number in ID3v2.4 or a syncsafe one in ID3v2.3, and was read in that form. |
| `:id3/utf8-latin1` | info | ID3 text declared ISO-8859-1 was UTF-8, and was read as UTF-8. |
| `:id3/utf16-bom` | info | ID3 text declared UTF-16 had no byte order mark, and its byte order was taken from where its zero bytes fall. |
| `:id3/image-type` | info | The type of an ID3 picture was loose, such as JPG or image/jpg, or missing, and was taken from the bytes of the image. |
| `:id3/chapter-end` | info | An ID3 chapter ended at or before its start, and its end was left out. |
| `:id3/second-tag` | info | A second ID3v2 tag followed the first, as a writer that adds a tag in front of another leaves it, and was read too. |
| `:id3/skipped-frame` | info | An ID3v2 frame was compressed or encrypted, and was skipped. |
| `:id3/malformed` | warning | An ID3v2 frame could not be read; the frames before it were kept. |
| `:id3/padding-junk` | info | Bytes other than zeros lay in the padding after the ID3v2 frames, as a writer that does not clear the padding leaves them, or as a frame of a wrong size makes it seem. |
| `:mp3/junk-before-audio` | info | Bytes that are no MPEG audio frame, such as padding that the size of the tag leaves out, lay between the tags and the first frame, and were skipped. |
| `:mp3/estimated-duration` | info | The MP3 file has no Xing, Info or VBRI header, so its duration was estimated from the bitrate of its first frames and its size, which is exact for a constant bitrate. |
| `:mp3/no-audio` | warning | No MPEG audio frame was found after the tags, so the file has no duration. |
| `:mp4/zero-duration` | info | The movie header of the MP4 file gave no duration, so the duration of its longest track was taken. |
| `:mp4/fragments-unread` | info | The MP4 file is written in fragments, and neither an mehd box nor an index at the end of the file gave the duration of all of them, so the file has no duration. |
| `:mp4/no-movie` | warning | The MP4 file has no movie box, so it gives neither a duration nor chapters. |
| `:file/unknown-format` | warning | The file is in no format that its reader knows, so nothing was read from it: MP3 or MP4 for a media file, and for an image PNG, JPEG, GIF or WebP, whose headers it reads, or AVIF, HEIC, HEIF, SVG, BMP, TIFF or ICO, whose type it knows. |
| `:file/truncated` | warning | The file ends before its structures say that it does. |
| `:file/malformed` | warning | The file could not be read as its format says. |
| `:file/incomplete` | warning | Bytes that the reading of the file needed did not arrive, so the file was not read. |
| `:check/unreachable` | error | The file could not be fetched: the connection, the TLS handshake or the time allowed failed. |
| `:check/status` | error | The server answered the request for the file with a status that is not a success, so a listener does not get the file. |
| `:check/downgrade` | error | A redirect leads from https to http, which clients refuse to follow. |
| `:check/redirects` | warning | The file lies more than five redirects away, more than the check and some clients follow. |
| `:check/moved` | warning | The file moved for good, by a redirect with status 301 or 308, so the feed can name its new URL. |
| `:check/head` | error | The server does not answer a HEAD request for the file, which RFC 9110 requires of every general-purpose server, and which directories use to check files. |
| `:check/type` | warning | The server gives the file another media type than the feed declares. |
| `:check/length` | info | The size of the file differs from the length that the feed declares. A host that inserts ads sends a file of another size to each request, so the length cannot be exact. |
| `:check/ranges` | warning | The server does not answer a request for a byte range with that range. RFC 9110 makes ranges optional, but says that servers ought to support them when possible, and players need them to seek and to resume. Apple Podcasts requires them. |
| `:check/format` | warning | The media file is in another format than the type that the feed declares. |
| `:check/duration` | info | The duration of the media file differs from the duration that the feed declares by more than 10 seconds and 2 percent. A host that inserts ads can explain it. |
| `:check/unread` | warning | The file came, but reading what it holds failed: a request for more of its bytes failed, or its reader did, so its contents were not checked. |
| `:check/integrity` | error | The media file does not match the hash of its podcast:integrity. |
| `:check/unverified` | info | The media file was not checked against its podcast:integrity: a PGP signature needs the key of the publisher, the integrity names no hash that Subresource Integrity knows, or the whole file did not come. |
| `:check/image-type` | info | The server gives the image another media type than its bytes have, such as image/jpg for a JPEG image. Browsers read the type from the bytes of an image. |
| `:check/image-size` | warning | The size of the image differs from the width, height or aspect ratio that its podcast:image declares. |
| `:check/artwork-size` | error | The image of an itunes:image is not a square of 1400 to 3000 pixels, as the profile requires. |
| `:check/artwork-type` | error | The image of an itunes:image is neither JPEG nor PNG, as the profile requires, or is a JPEG that apps and browsers do not decode: lossless, hierarchical, arithmetic-coded or JPEG-LS, or with more than 8 bits a sample. |
| `:check/artwork-color` | error | The image of an itunes:image is not in RGB colors, as the profile requires. |
| `:check/artwork-alpha` | error | The image of an itunes:image has an alpha channel or transparency, which the profile does not allow. |
| `:check/artwork-extension` | warning | The file extension of the URL of an itunes:image does not match the type of its image, as the profile requires. |
| `:check/last-modified` | warning | The server answers a HEAD request for the artwork without Last-Modified, which the profile asks for. |
| `:check/cors` | warning | The server does not let pages of other sites read the file, by the CORS header Access-Control-Allow-Origin of its answer or of a redirect on the way, so web players cannot show it. The podcast namespace requires CORS for transcripts. |
| `:check/empty` | error | The transcript or chapters file holds no segments or chapters in the format that the feed declares. |
| `:check/feed-cors` | info | The server does not let pages of other sites read the feed, so a web app cannot read it without a server of its own. |
| `:check/validators` | info | The server sends the feed without ETag or Last-Modified, so a client cannot ask whether the feed changed, and fetches it whole each time. |
| `:validate/rss-version` | warning | The rss element's version is not 2.0. |
| `:validate/missing-element` | error | An element RSS 2.0 requires is missing. |
| `:validate/missing-enclosure` | warning | An item has no enclosure, so there is nothing to play. |
| `:validate/missing-attribute` | error | A required attribute is missing. |
| `:validate/wrong-shape` | error | A value is not the shape its element takes: a bare value where a map of attributes belongs, or a map or sequence where a single value does. |
| `:validate/missing-child` | error | A container lacks a child the specification requires. |
| `:validate/invalid-structure` | error | A container's children contradict the specification, such as a time split with both recipients and a remote item. |
| `:validate/invalid-value` | error | A value is not of its type, such as a string where a date belongs or :no where a boolean does, or it is a number below zero, as no quantity in a feed is. |
| `:validate/invalid-enum` | warning | A value is not one of those the specification lists. |
| `:validate/invalid-url` | warning | A URL is not absolute. |
| `:validate/http-url` | info | A hypertext URL uses http where the podcast namespace requires https. |
| `:validate/invalid-guid` | warning | A podcast:guid is not a UUID. |
| `:validate/unknown-role` | info | A person's role is not in the Podcast Taxonomy. |
| `:validate/unknown-element` | info | An element the registry does not know, kept under :xml/unknown. |
| `:validate/unknown-key` | warning | A key the registry does not define: a typo such as :rss/titel, or on the channel, where a parse keeps no attributes, any key that is not an element. |
| `:validate/deprecated` | info | An element its specification has deprecated. |
| `:validate/profile-required` | error | An element the chosen profile requires is missing. |
| `:validate/profile-recommended` | warning | An element the chosen profile recommends is missing. |
| `:sync/unreadable-document` | warning | A document in the store would not read as a sync document; it was left out. |
| `:sync/newer-format` | warning | A document was written by a newer version of the library than this one reads; it was left out. |
| `:sync/clock-skew` | warning | A device wrote its document at an instant more than a minute ahead of this device's clock. |
| `:sync/shared-device` | warning | This device's own document changed under it, so two installations write with the same device id. |
| `:sync/retired-device` | warning | This device has been retired from another device; nothing was written for it. |
| `:sync/conflict` | warning | The shared document changed under every attempt to write it; the step ended without writing. |
| `:sync/unknown-episodes` | info | Episode actions from a gpodder server name episodes of feeds that the app does not know yet. They wait in the cursor, and the next pull tries them again. |
| `:sync/unnamed-episodes` | info | Episodes changed, but the app knows no URL of their media, which the gpodder API names an episode by, so the server was not told. |
| `:sync/rejected-url` | warning | A gpodder server refused a URL that was sent to it, so it keeps no subscription or episode action for it. |
| `:comments/unsupported` | info | podcast-clj does not read comments over the protocol of the thread. |
| `:comments/no-appview` | warning | podcast-clj did not read an AT Protocol thread, because the options named no AppView. |
| `:comments/malformed` | warning | The URI of the root post, or a document that a server sent, is not in a form that podcast-clj reads. Such a document is a post, a collection of replies, an author or a thread. |
| `:comments/unreachable` | warning | podcast-clj did not get a post, a collection of replies or an author. The request failed, or the server answered with an error. |
| `:comments/refused` | warning | A server refused an unsigned request, as a server in secure mode does. |
| `:comments/untrusted` | warning | A server sent a post or an author from another server. podcast-clj did not use it, because a server speaks only for its own posts and authors. |
| `:comments/through-api` | info | ActivityPub did not give the root post. podcast-clj read the thread through the Mastodon API of the root post's server instead. The API can give fewer replies. |
| `:comments/withheld` | info | podcast-clj left out some replies. They are not public, their authors ask apps to show them only to signed-in users, or a moderation service hid them. |
| `:comments/incomplete` | info | The fetch reached a limit before it read the whole thread. To read more of the thread, fetch it again. |
