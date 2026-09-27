(ns dk.simongray.podcast.diagnostics
  "Every diagnostic the library can put in a report, by id: its level and
  what it means. Consumers match on these ids, so they are API; a test keeps
  the catalogue and the source in step, and dev/reference.clj writes it out
  as doc/diagnostics.md.

  Ids are namespaced by the layer that raises them: `repair` for the text
  repairs of a malformed document, `xml` for namespace resolution, `quirk`
  for the normalizations of the tree, `feed` for the model, `transcript` and
  `chapters` for the companion formats, `validate` for the validator, whose
  diagnostics are about the feed rather than about reading it, and `sync`
  for a step of keeping devices in step.")

(def catalogue
  "The diagnostics, in the order they tend to arise."
  [;; repairs of the XML text, applied only when the strict parse failed
   {:id :repair/leading-junk :level :warning
    :doc "Bytes before the XML declaration, such as whitespace or a byte order mark, were removed."}
   {:id :repair/control-characters :level :warning
    :doc "Control characters XML forbids were removed."}
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

   ;; chapters
   {:id :chapters/string-times :level :warning
    :doc "Chapter times were given as strings rather than numbers and were read as numbers."}
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
    :doc "The document is not a JSON object, so it holds no chapters."}

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
    :doc "A value did not read as its type."}
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
