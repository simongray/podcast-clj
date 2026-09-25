# Elements the library understands

Generated from the tag registry in `dk.simongray.podcast.spec` by `dev/reference.clj`;
do not edit by hand. Every element listed here is read into the feed model, written
back by `emit`, and checked by `validate`. Elements not listed are kept as Hiccup under
`:xml/unknown` and survive a round trip unchanged.

Keys are namespaced by vocabulary and kebab-cased; an element with attributes or children
becomes a map with its attributes under the keys shown, its text under `:content` and its
children under their own keys; a repeatable element becomes a vector under a plural key.

## Value types

| Type | Reading and writing |
|---|---|
| text | the element text, trimmed |
| html | text that may contain HTML, kept as written |
| url, email, mime, language, token | strings; the validator checks their shape |
| enum | a string; the validator checks it against the listed values, ignoring case |
| integer | a long; written back as digits |
| decimal | a number, a long when whole; written back without an exponent |
| boolean | `true`/`false` in the XML, a boolean in the model |
| yes-no | `yes`/`no` in the XML, a boolean in the model |
| rfc2822 | an RFC 2822 date, read leniently, an `#inst` in the model; written as GMT |
| iso8601 | an ISO 8601 date or date-time, an `#inst` in the model; written in UTC |
| duration | seconds as a number, read from plain seconds or clock notation; written as plain seconds |
| rrule | an RFC 5545 recurrence rule, a map of its parts in the model; written with the parts in the RFC's order |
| geo | a geo URI, a map of `:lat`, `:lon` and perhaps `:altitude` and `:uncertainty` in the model |
| osm | an OpenStreetMap reference such as R113314#4, a map of `:type`, `:id` and perhaps `:revision` |

## RSS 2.0

The core elements, as specified at https://www.rssboard.org/rss-specification.

### `<title>`

The name of the channel or item.

- Key: `:rss/title`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<link>`

The URL of the website or web page.

- Key: `:rss/link`
- Parent: channel, item (and liveItem)
- Count: one
- Text: url

### `<description>`

Phrase or sentence describing the channel; the item synopsis or notes.

- Key: `:rss/description`
- Parent: channel, item (and liveItem)
- Count: one
- Text: html

### `<language>`

The language the channel is written in.

- Key: `:rss/language`
- Parent: channel
- Count: one
- Text: language

### `<copyright>`

Copyright notice for content in the channel.

- Key: `:rss/copyright`
- Parent: channel
- Count: one
- Text: text

### `<managingEditor>`

Email address for the person responsible for editorial content.

- Key: `:rss/managing-editor`
- Parent: channel
- Count: one
- Text: email

### `<webMaster>`

Email address for the person responsible for technical issues.

- Key: `:rss/web-master`
- Parent: channel
- Count: one
- Text: email

### `<pubDate>`

The publication date.

- Key: `:rss/pub-date`
- Parent: channel, item (and liveItem)
- Count: one
- Text: rfc2822

### `<lastBuildDate>`

The last time the content of the channel changed.

- Key: `:rss/last-build-date`
- Parent: channel
- Count: one
- Text: rfc2822

### `<category>`

One or more categories that the channel or item belongs to.

- Key: `:rss/categories`
- Parent: channel, item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `domain` | `:domain` | url |  |

### `<generator>`

A string indicating the program used to generate the channel.

- Key: `:rss/generator`
- Parent: channel
- Count: one
- Text: text

### `<docs>`

A URL that points to the documentation for the format used.

- Key: `:rss/docs`
- Parent: channel
- Count: one
- Text: url

### `<cloud>`

Allows processes to register with a cloud to be notified of updates.

- Key: `:rss/cloud`
- Parent: channel
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `domain` | `:domain` | text |  |
| `path` | `:path` | text |  |
| `port` | `:port` | integer |  |
| `protocol` | `:protocol` | text |  |
| `registerProcedure` | `:register-procedure` | text |  |

### `<ttl>`

Number of minutes the channel can be cached before refreshing.

- Key: `:rss/ttl`
- Parent: channel
- Count: one
- Text: integer

### `<image>`

A GIF, JPEG or PNG image that can be displayed with the channel.

- Key: `:rss/image`
- Parent: channel
- Count: one
- Text: none

### `<url>`

The URL of the image.

- Key: `:url`
- Parent: <image>
- Count: one
- Text: url

### `<title>`

Describes the image; the ALT attribute when rendered in HTML.

- Key: `:title`
- Parent: <image>
- Count: one
- Text: text

### `<link>`

The URL of the site; the image links to it when rendered.

- Key: `:link`
- Parent: <image>
- Count: one
- Text: url

### `<width>`

Width of the image in pixels; at most 144.

- Key: `:width`
- Parent: <image>
- Count: one
- Text: integer

### `<height>`

Height of the image in pixels; at most 400.

- Key: `:height`
- Parent: <image>
- Count: one
- Text: integer

### `<description>`

Text for the TITLE attribute of the link formed around the image.

- Key: `:description`
- Parent: <image>
- Count: one
- Text: text

### `<rating>`

The PICS rating for the channel.

- Key: `:rss/rating`
- Parent: channel
- Count: one
- Text: text

### `<skipHours>`

Hours (GMT) during which aggregators may skip reading the channel.

- Key: `:rss/skip-hours`
- Parent: channel
- Count: one
- Text: none

### `<hour>`

An hour of the day, 0 to 23.

- Key: `:hours`
- Parent: <skipHours>
- Count: many
- Text: integer

### `<skipDays>`

Days during which aggregators may skip reading the channel.

- Key: `:rss/skip-days`
- Parent: channel
- Count: one
- Text: none

### `<day>`

A day of the week, Monday to Sunday.

- Key: `:days`
- Parent: <skipDays>
- Count: many
- Text: text

### `<author>`

Email address of the author of the item.

- Key: `:rss/author`
- Parent: item (and liveItem)
- Count: one
- Text: email

### `<author>`

Not RSS 2.0, but a fifth of feeds name their author here as well.

- Key: `:rss/author`
- Parent: channel
- Count: one
- Text: text

### `<comments>`

URL of a page for comments relating to the item.

- Key: `:rss/comments`
- Parent: item (and liveItem)
- Count: one
- Text: url

### `<enclosure>`

The media object attached to the item.

- Key: `:rss/enclosure`
- Parent: item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `length` | `:length` | integer |  |
| `type` | `:type` | mime |  |
| `url` | `:url` | url | required |

### `<guid>`

A string that uniquely identifies the item.

- Key: `:rss/guid`
- Parent: item (and liveItem)
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `isPermaLink` | `:is-perma-link` | boolean |  |

### `<source>`

The RSS channel that the item came from.

- Key: `:rss/source`
- Parent: item (and liveItem)
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `url` | `:url` | url | required |

### `<item>`

An episode.

- Key: `:rss/items`
- Parent: channel
- Count: many
- Text: none

## iTunes

Apple's namespace, https://podcasters.apple.com/support/823-podcast-requirements.

- `itunes`: http://www.itunes.com/dtds/podcast-1.0.dtd

### `<itunes:title>`

The title specific to Apple Podcasts.

- Key: `:itunes/title`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<itunes:author>`

The group responsible for creating the show.

- Key: `:itunes/author`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<itunes:subtitle>`

A short description; dropped from Apple's specification.

- Key: `:itunes/subtitle`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<itunes:summary>`

A description; dropped from Apple's specification but widely read.

- Key: `:itunes/summary`
- Parent: channel, item (and liveItem)
- Count: one
- Text: html

### `<itunes:image>`

The artwork for the show or episode.

- Key: `:itunes/image`
- Parent: channel, item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `href` | `:href` | url | required |

### `<itunes:category>`

The show category; may nest one subcategory.

- Key: `:itunes/categories` under channel, `:categories` under <itunes:category>
- Parent: channel, <itunes:category>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `text` | `:text` | text | required |

### `<itunes:explicit>`

Parental advisory information: true or false.

- Key: `:itunes/explicit`
- Parent: channel, item (and liveItem)
- Count: one
- Text: boolean

### `<itunes:type>`

Whether episodes are meant to be consumed in order.

- Key: `:itunes/type`
- Parent: channel
- Count: one
- Text: enum: `episodic`, `serial`

### `<itunes:owner>`

The podcast owner contact information, not displayed.

- Key: `:itunes/owner`
- Parent: channel
- Count: one
- Text: none

### `<itunes:name>`

The owner's name.

- Key: `:name`
- Parent: <itunes:owner>
- Count: one
- Text: text

### `<itunes:email>`

The owner's email address.

- Key: `:email`
- Parent: <itunes:owner>
- Count: one
- Text: email

### `<itunes:new-feed-url>`

The new podcast RSS feed URL when the feed has moved.

- Key: `:itunes/new-feed-url`
- Parent: channel
- Count: one
- Text: url

### `<itunes:block>`

Yes hides the show or episode from Apple Podcasts.

- Key: `:itunes/block`
- Parent: channel, item (and liveItem)
- Count: one
- Text: yes-no

### `<itunes:complete>`

Yes means no more episodes will ever be published.

- Key: `:itunes/complete`
- Parent: channel
- Count: one
- Text: yes-no

### `<itunes:keywords>`

**Deprecated**.

Comma-separated keywords; no longer read by Apple.

- Key: `:itunes/keywords`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<itunes:applepodcastsverify>`

The token Apple Podcasts Connect issues to prove ownership of the feed.

- Key: `:itunes/applepodcastsverify`
- Parent: channel
- Count: one
- Text: text

### `<itunes:duration>`

The duration of the episode, in seconds.

- Key: `:itunes/duration`
- Parent: item (and liveItem)
- Count: one
- Text: duration

### `<itunes:episode>`

The episode number; required for serial shows.

- Key: `:itunes/episode`
- Parent: item (and liveItem)
- Count: one
- Text: integer

### `<itunes:season>`

The season number.

- Key: `:itunes/season`
- Parent: item (and liveItem)
- Count: one
- Text: integer

### `<itunes:episodeType>`

Whether the item is a full episode, a trailer or bonus content.

- Key: `:itunes/episode-type`
- Parent: item (and liveItem)
- Count: one
- Text: enum: `bonus`, `full`, `trailer`

### `<itunes:isClosedCaptioned>`

**Deprecated**.

Yes when the video episode has closed captions; historical.

- Key: `:itunes/closed-captioned?`
- Parent: item (and liveItem)
- Count: one
- Text: yes-no

### `<itunes:order>`

**Deprecated**.

Overrides the default ordering of episodes; historical.

- Key: `:itunes/order`
- Parent: item (and liveItem)
- Count: one
- Text: integer

## Podcast namespace (Podcasting 2.0)

The `podcast` namespace, https://github.com/Podcastindex-org/podcast-namespace; every formalized tag.

- `podcast`: https://podcastindex.org/namespace/1.0

### `<podcast:guid>`

The global identifier of the podcast, a UUIDv5 of its original feed URL.

- Key: `:podcast/guid`
- Parent: channel
- Count: one
- Text: text

### `<podcast:locked>`

Yes tells other platforms not to import this feed.

- Key: `:podcast/locked`
- Parent: channel
- Count: one
- Text: yes-no

| Attribute | Key | Type | |
|---|---|---|---|
| `owner` | `:owner` | email |  |

### `<podcast:funding>`

A donation or funding link with its display text.

- Key: `:podcast/funding`
- Parent: channel, item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `url` | `:url` | url | required |

### `<podcast:medium>`

What the content in the feed is: a podcast, music, an audiobook...

- Key: `:podcast/medium`
- Parent: channel
- Count: one
- Text: enum: `audiobook`, `audiobookL`, `blog`, `blogL`, `course`, `courseL`, `film`, `filmL`, `mixed`, `music`, `musicL`, `newsletter`, `newsletterL`, `podcast`, `podcastL`, `publisher`, `publisherL`, `video`, `videoL`

### `<podcast:block>`

Which platforms may publicly display the feed.

- Key: `:podcast/blocks`
- Parent: channel
- Count: many
- Text: yes-no

| Attribute | Key | Type | |
|---|---|---|---|
| `id` | `:id` | enum: `acast`, `amazon`, `anchor`, `apple`, `audible`, `audioboom`, `backtracks`, `bitcoin`, `blubrry`, `buzzsprout`, `captivate`, `castopod`, `castos`, `facebook`, `fireside`, `fyyd`, `google`, `gpodder`, `hypercatcher`, `kasts`, `libsyn`, `mastodon`, `megafono`, `megaphone`, `omnystudio`, `overcast`, `paypal`, `pinecast`, `podbean`, `podcastaddict`, `podcastguru`, `podcastindex`, `podcasts`, `podchaser`, `podcloud`, `podfriend`, `podiant`, `podigee`, `podnews`, `podomatic`, `podserve`, `podverse`, `redcircle`, `relay`, `resonaterecordings`, `rss`, `shoutengine`, `simplecast`, `slack`, `soundcloud`, `spotify`, `spreaker`, `tiktok`, `transistor`, `twitter`, `whooshkaa`, `youtube`, `zencast` |  |

### `<podcast:txt>`

Free-form text modelled on the DNS TXT record.

- Key: `:podcast/txt`
- Parent: channel, item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `purpose` | `:purpose` | text |  |

### `<podcast:updateFrequency>`

The intended release schedule as text and an RFC 5545 rule.

- Key: `:podcast/update-frequency`
- Parent: channel
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `complete` | `:complete` | boolean |  |
| `dtstart` | `:dtstart` | iso8601 |  |
| `rrule` | `:rrule` | rrule |  |

### `<podcast:license>`

The license of the audio or video content.

- Key: `:podcast/license`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `url` | `:url` | url |  |

### `<podcast:trailer>`

A trailer for the whole podcast or for a season.

- Key: `:podcast/trailers`
- Parent: channel
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `length` | `:length` | integer |  |
| `pubdate` | `:pubdate` | rfc2822 | required |
| `season` | `:season` | integer |  |
| `type` | `:type` | mime |  |
| `url` | `:url` | url | required |

### `<podcast:person>`

A person of interest: host, guest or any Podcast Taxonomy role.

- Key: `:podcast/people`
- Parent: channel, item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `group` | `:group` | text |  |
| `href` | `:href` | url |  |
| `img` | `:img` | url |  |
| `role` | `:role` | text |  |

### `<podcast:location>`

Where the content is about or where it was made.

- Key: `:podcast/locations`
- Parent: channel, item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `country` | `:country` | text |  |
| `geo` | `:geo` | geo |  |
| `osm` | `:osm` | osm |  |
| `rel` | `:rel` | enum: `creator`, `subject` |  |

### `<podcast:image>`

An image of a given size, ratio and purpose.

- Key: `:podcast/images`
- Parent: channel, item (and liveItem)
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `alt` | `:alt` | text |  |
| `aspect-ratio` | `:aspect-ratio` | text |  |
| `height` | `:height` | integer |  |
| `href` | `:href` | url | required |
| `purpose` | `:purpose` | text |  |
| `type` | `:type` | mime |  |
| `width` | `:width` | integer |  |

### `<podcast:images>`

**Deprecated**, replaced by `<podcast:image>`.

Deprecated: image sizes as an HTML srcset.

- Key: `:podcast/srcset-images`
- Parent: channel, item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `srcset` | `:srcset` | text | required |

### `<podcast:value>`

The payment layer, transport and suggested amount for streaming payments.

- Key: `:podcast/values`
- Parent: channel, item (and liveItem)
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `method` | `:method` | enum: `amp`, `default`, `keysend`, `lnaddress` | required |
| `suggested` | `:suggested` | decimal |  |
| `type` | `:type` | enum: `bitcoin`, `lightning` | required |

### `<podcast:valueRecipient>`

A destination for a share of each payment.

- Key: `:recipients`
- Parent: <podcast:value>, <podcast:valueTimeSplit>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `address` | `:address` | text | required |
| `customKey` | `:custom-key` | text |  |
| `customValue` | `:custom-value` | text |  |
| `fee` | `:fee` | boolean |  |
| `name` | `:name` | text |  |
| `split` | `:split` | integer | required |
| `type` | `:type` | enum: `lnaddress`, `node`, `wallet` | required |

### `<podcast:valueTimeSplit>`

Alternative recipients for a period of the media.

- Key: `:time-splits`
- Parent: <podcast:value>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `duration` | `:duration` | decimal | required |
| `remotePercentage` | `:remote-percentage` | decimal |  |
| `remoteStartTime` | `:remote-start-time` | decimal |  |
| `startTime` | `:start-time` | decimal | required |

### `<podcast:remoteItem>`

A pointer to another feed or to an item in it.

- Key: `:podcast/remote-items` under channel, `:remote-items` under <podcast:podroll>, `:remote-item` under <podcast:publisher>, `:remote-item` under <podcast:valueTimeSplit>
- Parent: channel, <podcast:podroll>, <podcast:publisher>, <podcast:valueTimeSplit>
- Count: many under channel, many under <podcast:podroll>, one under <podcast:publisher>, one under <podcast:valueTimeSplit>
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `feedGuid` | `:feed-guid` | text | required |
| `feedUrl` | `:feed-url` | url |  |
| `itemGuid` | `:item-guid` | text |  |
| `medium` | `:medium` | enum: `audiobook`, `audiobookL`, `blog`, `blogL`, `course`, `courseL`, `film`, `filmL`, `mixed`, `music`, `musicL`, `newsletter`, `newsletterL`, `podcast`, `podcastL`, `publisher`, `publisherL`, `video`, `videoL` |  |
| `title` | `:title` | text |  |

### `<podcast:podroll>`

Other podcasts the creator recommends.

- Key: `:podcast/podroll`
- Parent: channel
- Count: one
- Text: none

### `<podcast:publisher>`

The publisher feed this podcast belongs to.

- Key: `:podcast/publisher`
- Parent: channel
- Count: one
- Text: none

### `<podcast:podping>`

Signals that the feed sends Podping notifications.

- Key: `:podcast/podping`
- Parent: channel
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `usesPodping` | `:uses-podping` | boolean |  |

### `<podcast:chat>`

Where the official chat for the podcast or episode is.

- Key: `:podcast/chat`
- Parent: channel, item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `accountId` | `:account-id` | text |  |
| `protocol` | `:protocol` | enum: `irc`, `matrix`, `nostr`, `xmpp` | required |
| `server` | `:server` | text | required |
| `space` | `:space` | text |  |

### `<podcast:socialInteract>`

The root post of the comment thread for the episode or podcast.

- Key: `:podcast/social-interacts`
- Parent: channel, item (and liveItem)
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `accountId` | `:account-id` | text |  |
| `accountUrl` | `:account-url` | url |  |
| `priority` | `:priority` | integer |  |
| `protocol` | `:protocol` | enum: `activitypub`, `atproto`, `disabled`, `hive`, `lightning`, `matrix`, `nostr`, `twitter` | required |
| `uri` | `:uri` | url |  |

### `<podcast:transcript>`

A transcript or closed-captions file.

- Key: `:podcast/transcripts`
- Parent: item (and liveItem)
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `language` | `:language` | language |  |
| `rel` | `:rel` | enum: `captions` |  |
| `type` | `:type` | mime | required |
| `url` | `:url` | url | required |

### `<podcast:chapters>`

An external JSON chapters file.

- Key: `:podcast/chapters`
- Parent: item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `type` | `:type` | mime | required |
| `url` | `:url` | url | required |

### `<podcast:soundbite>`

A clip of the episode worth previewing or sharing.

- Key: `:podcast/soundbites`
- Parent: item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `duration` | `:duration` | decimal | required |
| `startTime` | `:start-time` | decimal | required |

### `<podcast:season>`

The season number, with an optional name.

- Key: `:podcast/season`
- Parent: item (and liveItem)
- Count: one
- Text: integer

| Attribute | Key | Type | |
|---|---|---|---|
| `name` | `:name` | text |  |

### `<podcast:episode>`

The episode number, decimal, with an optional display string.

- Key: `:podcast/episode`
- Parent: item (and liveItem)
- Count: one
- Text: decimal

| Attribute | Key | Type | |
|---|---|---|---|
| `display` | `:display` | text |  |

### `<podcast:alternateEnclosure>`

Another version of, or companion to, the enclosure.

- Key: `:podcast/alternate-enclosures`
- Parent: item (and liveItem)
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `bitrate` | `:bitrate` | decimal |  |
| `codecs` | `:codecs` | text |  |
| `default` | `:default` | boolean |  |
| `height` | `:height` | integer |  |
| `lang` | `:lang` | language |  |
| `length` | `:length` | integer |  |
| `rel` | `:rel` | text |  |
| `title` | `:title` | text |  |
| `type` | `:type` | mime | required |

### `<podcast:source>`

A URI where the alternate media can be fetched.

- Key: `:sources`
- Parent: <podcast:alternateEnclosure>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `contentType` | `:content-type` | mime |  |
| `uri` | `:uri` | text | required |

### `<podcast:integrity>`

An SRI string or PGP signature for the alternate media.

- Key: `:integrity`
- Parent: <podcast:alternateEnclosure>
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `type` | `:type` | enum: `pgp-signature`, `sri` | required |
| `value` | `:value` | text | required |

### `<podcast:contentLink>`

Where else the content can be found, e.g. a live stream on the web.

- Key: `:podcast/content-links`
- Parent: item (and liveItem)
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `href` | `:href` | url | required |

### `<podcast:liveItem>`

A live stream, described like an item plus its status and times.

- Key: `:podcast/live-items`
- Parent: channel
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `end` | `:end` | iso8601 |  |
| `start` | `:start` | iso8601 | required |
| `status` | `:status` | enum: `ended`, `live`, `pending` | required |

## Podlove Simple Chapters

Chapters embedded in the feed, https://podlove.org/simple-chapters/.

- `psc`: http://podlove.org/simple-chapters

### `<psc:chapters>`

Chapters embedded in the feed, the pre-2.0 convention.

- Key: `:psc/chapters`
- Parent: item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `version` | `:version` | text |  |

### `<psc:chapter>`

One chapter: a start timestamp and a title.

- Key: `:chapters`
- Parent: <psc:chapters>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `href` | `:href` | url |  |
| `image` | `:image` | url |  |
| `start` | `:start` | text | required |
| `title` | `:title` | text |  |

## Media RSS

Yahoo's Media RSS module, as far as podcast hosts use it.

- `media`: http://search.yahoo.com/mrss/

### `<media:group>`

Renditions of one media object, as media:content children.

- Key: `:media/group`
- Parent: item (and liveItem)
- Count: one
- Text: none

### `<media:content>`

A media object; some hosts use it beside or instead of enclosure.

- Key: `:media/contents` under item (and liveItem), `:contents` under <media:group>
- Parent: item (and liveItem), <media:group>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `bitrate` | `:bitrate` | decimal |  |
| `channels` | `:channels` | integer |  |
| `duration` | `:duration` | integer |  |
| `expression` | `:expression` | text |  |
| `fileSize` | `:file-size` | integer |  |
| `framerate` | `:framerate` | decimal |  |
| `height` | `:height` | integer |  |
| `isDefault` | `:is-default` | boolean |  |
| `lang` | `:lang` | language |  |
| `medium` | `:medium` | text |  |
| `samplingrate` | `:samplingrate` | decimal |  |
| `type` | `:type` | mime |  |
| `url` | `:url` | url |  |
| `width` | `:width` | integer |  |

### `<media:title>`

The title of the media object.

- Key: `:media/title` under channel, `:media/title` under item (and liveItem), `:title` under <media:content>, `:title` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `type` | `:type` | enum: `html`, `plain` |  |

### `<media:description>`

A short description of the media object.

- Key: `:media/description` under channel, `:media/description` under item (and liveItem), `:description` under <media:content>, `:description` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `type` | `:type` | enum: `html`, `plain` |  |

### `<media:thumbnail>`

A representative image.

- Key: `:media/thumbnails` under channel, `:media/thumbnails` under item (and liveItem), `:thumbnails` under <media:content>, `:thumbnails` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `height` | `:height` | integer |  |
| `time` | `:time` | text |  |
| `url` | `:url` | url | required |
| `width` | `:width` | integer |  |

### `<media:player>`

A web page that plays the media.

- Key: `:media/player` under item (and liveItem), `:player` under <media:content>, `:player` under <media:group>
- Parent: item (and liveItem), <media:content>, <media:group>
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `height` | `:height` | integer |  |
| `url` | `:url` | url | required |
| `width` | `:width` | integer |  |

### `<media:credit>`

Someone who contributed to the media, by role.

- Key: `:media/credits` under channel, `:media/credits` under item (and liveItem), `:credits` under <media:content>, `:credits` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `role` | `:role` | text |  |
| `scheme` | `:scheme` | text |  |

### `<media:rating>`

A rating such as adult or nonadult.

- Key: `:media/ratings` under channel, `:media/ratings` under item (and liveItem), `:ratings` under <media:content>, `:ratings` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `scheme` | `:scheme` | url |  |

### `<media:category>`

A category from a Media RSS scheme.

- Key: `:media/categories` under channel, `:media/categories` under item (and liveItem), `:categories` under <media:content>, `:categories` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `label` | `:label` | text |  |
| `scheme` | `:scheme` | url |  |

### `<media:copyright>`

Copyright information for the media.

- Key: `:media/copyright` under channel, `:media/copyright` under item (and liveItem), `:copyright` under <media:content>, `:copyright` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: one
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `url` | `:url` | url |  |

### `<media:keywords>`

Comma-separated keywords for the media.

- Key: `:media/keywords` under channel, `:media/keywords` under item (and liveItem), `:keywords` under <media:content>, `:keywords` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: one
- Text: text

### `<media:restriction>`

Restrictions on the media, e.g. by country.

- Key: `:media/restrictions` under channel, `:media/restrictions` under item (and liveItem), `:restrictions` under <media:content>, `:restrictions` under <media:group>
- Parent: channel, item (and liveItem), <media:content>, <media:group>
- Count: many
- Text: text

| Attribute | Key | Type | |
|---|---|---|---|
| `relationship` | `:relationship` | text | required |
| `type` | `:type` | text |  |

## Google Play and Spotify

Directory-specific namespaces still found in feeds.

- `googleplay`: http://www.google.com/schemas/play-podcasts/1.0
- `spotify`: http://www.spotify.com/ns/rss

### `<googleplay:author>`

- Key: `:googleplay/author`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<googleplay:description>`

- Key: `:googleplay/description`
- Parent: channel, item (and liveItem)
- Count: one
- Text: html

### `<googleplay:explicit>`

- Key: `:googleplay/explicit`
- Parent: channel, item (and liveItem)
- Count: one
- Text: enum: `clean`, `no`, `yes`

### `<googleplay:image>`

- Key: `:googleplay/image`
- Parent: channel, item (and liveItem)
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `href` | `:href` | url | required |

### `<googleplay:block>`

- Key: `:googleplay/block`
- Parent: channel, item (and liveItem)
- Count: one
- Text: yes-no

### `<googleplay:email>`

- Key: `:googleplay/email`
- Parent: channel
- Count: one
- Text: email

### `<googleplay:category>`

- Key: `:googleplay/categories`
- Parent: channel
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `text` | `:text` | text | required |

### `<googleplay:new-feed-url>`

- Key: `:googleplay/new-feed-url`
- Parent: channel
- Count: one
- Text: url

### `<spotify:countryOfOrigin>`

Space-separated ISO 3166 country codes for Spotify's targeting.

- Key: `:spotify/country-of-origin`
- Parent: channel
- Count: one
- Text: text

### `<spotify:limit>`

How many recent episodes Spotify should show.

- Key: `:spotify/limit`
- Parent: channel
- Count: one
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `recentCount` | `:recent-count` | integer |  |

## Atom, Dublin Core, content and syndication

Modules from the wider RSS world that podcast feeds carry.

- `atom`: http://www.w3.org/2005/Atom
- `content`: http://purl.org/rss/1.0/modules/content/
- `dc`: http://purl.org/dc/elements/1.1/
- `sy`: http://purl.org/rss/1.0/modules/syndication/

### `<atom:link>`

A link; rel=self gives the canonical feed URL, rel=next pages the archive.

- Key: `:atom/links`
- Parent: channel, item (and liveItem)
- Count: many
- Text: none

| Attribute | Key | Type | |
|---|---|---|---|
| `href` | `:href` | url | required |
| `hreflang` | `:hreflang` | language |  |
| `rel` | `:rel` | text |  |
| `title` | `:title` | text |  |
| `type` | `:type` | mime |  |

### `<content:encoded>`

The full HTML content of the item; some hosts give the channel one too.

- Key: `:content/encoded`
- Parent: channel, item (and liveItem)
- Count: one
- Text: html

### `<dc:creator>`

The entity primarily responsible for making the content.

- Key: `:dc/creator`
- Parent: channel, item (and liveItem)
- Count: one
- Text: text

### `<sy:updatePeriod>`

The period over which the channel is updated.

- Key: `:sy/update-period`
- Parent: channel
- Count: one
- Text: enum: `daily`, `hourly`, `monthly`, `weekly`, `yearly`

### `<sy:updateFrequency>`

How many times the channel is updated per period.

- Key: `:sy/update-frequency`
- Parent: channel
- Count: one
- Text: integer
