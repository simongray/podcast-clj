# Diagnostics

Generated from `dk.simongray.podcast.diagnostics` by `dev/reference.clj`. Do not edit
this file by hand.

A diagnostic is a map with an `:id`, a `:level` and a `:message`. The message is the
meaning below, unless the code gives a more specific message. Other keys give details,
such as a `:path` into the data or the `:tag` of an element. `report` reads the
diagnostics of a parsed feed, transcript or chapters document. `validate` returns the
diagnostics of a feed. `summarize` groups diagnostics by kind.

The namespace of an id tells which part of podcast-clj found the problem:

- `fetch`: the fetch of a feed
- `repair`: the repairs of malformed XML
- `xml`: namespace resolution
- `quirk`: the normalizations of the tree
- `feed`: the model
- `transcript` and `chapters`: the companion formats
- `id3`, `mp3`, `mp4` and `file`: the media files and the images
- `validate`: the validator
- `check`: the checks over the network
- `sync`: the sync between devices
- `podping`: a poll of the Hive chain
- `comments`: the threads of comments
- `value`: the records of payments and the Lightning addresses
- `analytics`: the list of user agents, and the downloads of OP3

A function that sends requests gives a failure as a diagnostic too, under the
`:error` of its result.

| Id | Level | Meaning |
|---|---|---|
| `:fetch/unreachable` | warning | podcast-clj did not get the feed. The connection failed, the request took too long, the answer was too large, or the guard of the request refused the URL. |
| `:fetch/malformed` | warning | The server answered with a body that is no RSS feed. The body is no XML, or its root element is not rss. |
| `:fetch/page-failed` | warning | podcast-clj did not get or read a page of a feed that comes in pages. The feed lacks the items of that page and of the pages after it. |
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
| `:id3/frame-size` | info | An ID3v2 frame gave its size in the form of the other version: a plain number in ID3v2.4, or a syncsafe number in ID3v2.3. podcast-clj read the size in that form. |
| `:id3/text-encoding` | info | An ID3v2 frame names a text encoding that ID3 does not define. podcast-clj read its text as ISO-8859-1. |
| `:id3/utf8-latin1` | info | An ID3v2 frame declared its text as ISO-8859-1, but the bytes are UTF-8. podcast-clj read the text as UTF-8. |
| `:id3/utf16-bom` | info | An ID3v2 frame declared its text as UTF-16, but the text has no byte order mark. podcast-clj took the byte order from the places of the zero bytes. |
| `:id3/image-type` | info | An ID3 picture gave its type loosely, such as JPG or image/jpg, or gave no type. podcast-clj took the type from the bytes of the image. |
| `:id3/chapter-end` | info | An ID3 chapter ends at its start or before it. podcast-clj left out the end. |
| `:id3/second-tag` | info | A second ID3v2 tag follows the first tag. Some writers add a tag in front of a tag that they leave in place. podcast-clj read the two tags. |
| `:id3/skipped-frame` | info | An ID3v2 frame is compressed or encrypted. podcast-clj did not read it. |
| `:id3/malformed` | warning | A frame of an ID3v2 tag does not read, so podcast-clj left it out. When the size of the frame does not read, podcast-clj also left out the frames after it. |
| `:id3/padding-junk` | info | The padding after the ID3v2 frames holds bytes that are not zeros. A writer did not clear the padding, or a frame gave a wrong size. |
| `:mp3/junk-before-audio` | info | Bytes that are no MPEG audio frame lie between the tags and the first frame. An example is padding that the size of a tag leaves out. podcast-clj skipped the bytes. |
| `:mp3/estimated-duration` | info | The MP3 file has no Xing, Info or VBRI header. podcast-clj estimated the duration from the bitrate of the first frames and the size of the file. The estimate is exact for a constant bitrate. |
| `:mp3/no-audio` | warning | podcast-clj found no MPEG audio frame after the tags, so the file has no duration. |
| `:mp4/zero-duration` | info | The movie header of the MP4 file gives no duration. podcast-clj took the duration of the longest track. |
| `:mp4/fragments-unread` | info | The MP4 file is in fragments. It has no mehd box, and no index at its end that gives the duration of all the fragments. So the file has no duration. |
| `:mp4/no-movie` | warning | The MP4 file has no movie box, so it gives no duration and no chapters. |
| `:file/unknown-format` | warning | The file is in no format that its reader knows, so the reader read nothing from it. The reader of media files knows MP3 and MP4. The reader of images reads the headers of PNG, JPEG, GIF and WebP. It knows the types of AVIF, HEIC, HEIF, SVG, BMP, TIFF and ICO. |
| `:file/truncated` | warning | The file ends before its structures say that it does. |
| `:file/malformed` | warning | The file does not read as its format says. |
| `:file/incomplete` | warning | The reading of the file did not get the bytes that it needed, so it gave nothing. The bytes did not arrive, or a limit stopped the reading. |
| `:file/unreachable` | warning | podcast-clj did not get the file. The request failed, or the server answered with a status that is not a success. |
| `:file/unverifiable` | info | podcast-clj has no check for this podcast:integrity. A PGP signature needs the key of the publisher. A value of the type sri must name a hash of sha256, sha384 or sha512. |
| `:check/unreachable` | error | The check did not get the file. The connection failed, the TLS handshake failed, or the request took too long. |
| `:check/status` | error | The server answered the request for the file with a status that is not a success, so a listener does not get the file. |
| `:check/downgrade` | error | A redirect leads from https to http, which clients refuse to follow. |
| `:check/redirects` | warning | The file lies more than five redirects away, more than the check and some clients follow. |
| `:check/moved` | warning | The file moved for good, by a redirect with status 301 or 308, so the feed can name its new URL. |
| `:check/head` | error | The server does not answer a HEAD request for the file. RFC 9110 requires HEAD of each general-purpose server, and directories use it to check files. |
| `:check/type` | warning | The server gives the file another media type than the feed declares. |
| `:check/length` | info | The size of the file differs from the length that the feed declares. A host that inserts ads sends a file of another size to each request, so the length cannot be exact. |
| `:check/ranges` | warning | The server does not answer a request for a byte range with that range. RFC 9110 makes ranges optional, but it recommends them. Players need ranges to seek and to resume, and Apple Podcasts requires them. |
| `:check/format` | warning | The media file is in another format than the type that the feed declares. |
| `:check/duration` | info | The duration of the media file differs from the duration that the feed declares by more than 10 seconds and 2 percent. A host that inserts ads can explain it. |
| `:check/unread` | warning | The file came, but the check did not read what it holds. A request for more of its bytes failed, or its reader failed. |
| `:check/integrity` | error | The media file does not match the hash of its podcast:integrity. |
| `:check/unverified` | info | The check did not compare the media file with its podcast:integrity. A PGP signature needs the key of the publisher. The integrity names no hash that Subresource Integrity knows. Or the whole file did not come. |
| `:check/image-type` | info | The server gives the image another media type than its bytes have, such as image/jpg for a JPEG image. Browsers read the type from the bytes of an image. |
| `:check/image-size` | warning | The size of the image differs from the width, height or aspect ratio that its podcast:image declares. |
| `:check/artwork-size` | error | The image of an itunes:image is not a square of 1400 to 3000 pixels, as the profile requires. |
| `:check/artwork-type` | error | The image of an itunes:image is not JPEG or PNG, as the profile requires. Or it is a JPEG that apps and browsers do not decode. Such a JPEG is lossless, hierarchical, arithmetic-coded or JPEG-LS, or it has more than 8 bits a sample. |
| `:check/artwork-color` | error | The image of an itunes:image is not in RGB colors, as the profile requires. |
| `:check/artwork-alpha` | error | The image of an itunes:image has an alpha channel or transparency, which the profile does not allow. |
| `:check/artwork-extension` | warning | The file extension of the URL of an itunes:image does not match the type of its image, as the profile requires. |
| `:check/last-modified` | warning | The server answers a HEAD request for the artwork without Last-Modified, which the profile asks for. |
| `:check/cors` | warning | The server does not let pages of other sites read the file, so web players cannot show it. The CORS header Access-Control-Allow-Origin of the answer, or of a redirect on the way, decides this. The podcast namespace requires CORS for transcripts. |
| `:check/empty` | error | The transcript or chapters file holds no segments or chapters in the format that the feed declares. |
| `:check/feed-cors` | info | The server does not let pages of other sites read the feed, so a web app cannot read it without a server of its own. |
| `:check/validators` | info | The server sends the feed without ETag or Last-Modified, so a client cannot ask whether the feed changed, and fetches it whole each time. |
| `:check/unchecked` | warning | The check reached a limit of files or of time, and did not look at the files after that. The diagnostic counts them. |
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
| `:sync/future-actions` | warning | Episode actions from a gpodder server name a time more than a minute after the pull, as a device with a fast clock writes them. They changed nothing. |
| `:sync/malformed-actions` | warning | Episode actions from a gpodder server name no feed, no media file or no time that reads. They were left out. |
| `:sync/unnamed-episodes` | info | Episodes changed, but the app knows no URL of their media, which the gpodder API names an episode by, so the server was not told. |
| `:sync/rejected-url` | warning | A gpodder server refused a URL that was sent to it, so it keeps no subscription or episode action for it. |
| `:podping/unavailable` | warning | No node of the Hive chain answered a call of the poll. The result has the pings of the blocks that the poll read before that call. |
| `:comments/unsupported` | info | podcast-clj does not read comments over the protocol of the thread. |
| `:comments/no-appview` | warning | podcast-clj did not read an AT Protocol thread, because the options named no AppView. |
| `:comments/no-relay` | warning | podcast-clj did not read a Nostr thread, because the options named no relay and the root names none. |
| `:comments/malformed` | warning | The URI of the root post, or a document that a server sent, is not in a form that podcast-clj reads. Such a document is a post, a collection of replies, an author or a thread. |
| `:comments/unreachable` | warning | podcast-clj did not get a post, a collection of replies or an author. The request failed, or the server answered with an error. When the diagnostic has `:retry?`, the next fetch of the thread asks again. |
| `:comments/refused` | warning | A server refused an unsigned request, as a server in secure mode does. |
| `:comments/untrusted` | warning | A server sent a post or an author from another server. podcast-clj did not use it, because a server speaks only for its own posts and authors. |
| `:comments/through-api` | info | ActivityPub did not give the root post. podcast-clj read the thread through the Mastodon API of the root post's server instead. The API can give fewer replies. |
| `:comments/withheld` | info | podcast-clj left out some replies. They are not public, their authors ask apps to show them only to signed-in users, or a moderation service hid them. An AppView also keeps back a reply when its author and the author of the post above it have a block between them. |
| `:comments/unverified` | warning | A relay of Nostr gave events that podcast-clj left out, and the diagnostic counts them. Such an event has a wrong id or a wrong signature, or it is not an event that the request asked for. |
| `:comments/truncated` | warning | A limit of size cut some replies, and the diagnostic counts them. Its `:limit` names the limit. `:page` is the 1,000 items of one page of replies, and `:id` is the 2,048 characters of an id. `:thread` is the 10,000 comments of one thread, and `:depth` is the 64 levels of replies of an AT Protocol thread. |
| `:comments/incomplete` | info | The fetch reached a limit before it read the whole thread, or a server did not answer for a time. To read more of the thread, fetch it again. |
| `:value/not-an-object` | warning | The record is not a JSON object, so it describes no payment. |
| `:value/text-for-number` | info | Numbers came as text, as some apps write them, and were read as numbers. |
| `:value/rounded` | info | Amounts in millisatoshis came with a fraction, and were rounded. |
| `:value/field-alias` | info | Fields came under other names, as some apps write them, and were read under their own names. |
| `:value/unreadable` | warning | Some fields do not hold a value of their type. They were left out. |
| `:value/guid-as-item-id` | info | The itemID holds the guid of the episode, as Breez writes it, and was read as that guid. |
| `:value/action-alias` | info | The action came as streaming, as Podverse writes it, and was read as stream. |
| `:value/unknown-action` | warning | The action is not boost, stream or auto, so the payment has no action. |
| `:value/unknown-keys` | info | The record has fields that its format does not define. They keep their names. |
| `:value/no-podcast` | warning | The record names no podcast: no guid, feed id, feed URL or title of a podcast. |
| `:value/claimed-amount` | warning | The sender claims another amount than the node received. The payment keeps the amount of the node. |
| `:value/claimed-recipient` | warning | The sender claims that the payment went to another recipient than the one that received it. |
| `:value/no-metadata` | warning | The comment names no URL of metadata, or the URL answered without the header x-rss-payment. |
| `:value/custom-record` | error | The records of the payment leave out the custom key and value of the recipient. The key is no number, it is a type that the Lightning protocol keeps for itself, or the value is absent. The payment reaches the node and not the wallet of the recipient. Do not send it. |
| `:value/not-an-address` | warning | The text is not a Lightning address, user@domain. |
| `:value/unreachable` | warning | The request failed: the server did not answer. |
| `:value/lnurl-error` | warning | The service of a Lightning address answered with an error, and told why. |
| `:value/malformed` | warning | The service of a Lightning address answered with something that LNURL-pay does not define. |
| `:value/amount-out-of-range` | warning | The Lightning address does not take this amount. |
| `:value/wrong-amount` | error | The invoice asks for another amount than the one requested. Do not pay it. |
| `:value/unknown-success-action` | error | The answer holds a success action that LUD-09 does not define, or a URL on another host. Do not pay the invoice. |
| `:value/no-keysend` | info | The domain of the Lightning address names no node for keysend. Pay it with LNURL-pay. |
| `:value/insecure` | error | The answer came over plain http, where anyone on the way can change it. Do not pay with it. |
| `:analytics/unreadable-pattern` | warning | A pattern of the list of user agents does not compile on this platform, so its entry was left out. |
| `:analytics/unreachable` | warning | OP3 gave no page of downloads: the request failed, or OP3 answered with an error. The result has the downloads of the pages before that page, and its continuation token names that page. |
