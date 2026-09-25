# Diagnostics the library reports

Generated from `dk.simongray.podcast.diagnostics` by `dev/reference.clj`; do not edit
by hand. Every diagnostic is a map with `:id` and `:level`, usually a `:message`, and
a `:path` into the data where one applies. `report` reads them off a parsed feed,
transcript or chapters document, `validate` returns them for a feed, and `summarize`
groups them by kind.

| Id | Level | Meaning |
|---|---|---|
| `:repair/leading-junk` | warning | Bytes before the XML declaration, such as whitespace or a byte order mark, were removed. |
| `:repair/control-characters` | warning | Control characters XML forbids were removed. |
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
| `:transcript/speaker-prefixes` | info | Speakers were taken from Name: prefixes in the cues and carried over the cues that follow. |
| `:transcript/string-times` | warning | Segment times were given as strings rather than numbers and were read as numbers. |
| `:transcript/wrapped-srt` | warning | An HTML transcript was an SRT file with every line wrapped in an element, and was read as SRT. |
| `:transcript/unknown-type` | warning | The declared type is not one the specification names; the format was sniffed instead. |
| `:transcript/type-mismatch` | warning | The declared type does not match the file, which was read as the format it is. |
| `:chapters/string-times` | warning | Chapter times were given as strings rather than numbers and were read as numbers. |
| `:chapters/missing-start-time` | warning | Chapters without a start time cannot be placed. |
| `:chapters/unsorted` | info | The chapters were not in order of start time, which the format assumes. |
| `:chapters/unknown-keys` | info | Keys the format does not define were kept as they are. |
| `:chapters/old-version` | info | The document was written against an older version of the format. |
| `:chapters/bad-timestamps` | warning | Podlove chapters whose start is not a timestamp were dropped. |
| `:validate/rss-version` | warning | The rss element's version is not 2.0. |
| `:validate/missing-element` | error | An element RSS 2.0 requires is missing. |
| `:validate/missing-enclosure` | warning | An item has no enclosure, so there is nothing to play. |
| `:validate/missing-attribute` | error | A required attribute is missing. |
| `:validate/missing-child` | error | A container lacks a child the specification requires. |
| `:validate/invalid-structure` | error | A container's children contradict the specification, such as a time split with both recipients and a remote item. |
| `:validate/invalid-value` | error | A value did not read as its type. |
| `:validate/invalid-enum` | warning | A value is not one of those the specification lists. |
| `:validate/invalid-url` | warning | A URL is not absolute. |
| `:validate/http-url` | info | A hypertext URL uses http where the podcast namespace requires https. |
| `:validate/invalid-guid` | warning | A podcast:guid is not a UUID. |
| `:validate/unknown-role` | info | A person's role is not in the Podcast Taxonomy. |
| `:validate/unknown-element` | info | An element the registry does not know, kept under :xml/unknown. |
| `:validate/deprecated` | info | An element its specification has deprecated. |
| `:validate/profile-required` | error | An element the chosen profile requires is missing. |
| `:validate/profile-recommended` | warning | An element the chosen profile recommends is missing. |
