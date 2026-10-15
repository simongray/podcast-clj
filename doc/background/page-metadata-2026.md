# Page metadata, October 2026

This plan is for the metadata in the head of a show page or an episode
page: what link previews, podcast apps and search engines read from it,
and how podcast-clj could write it. It was written on 15 October 2026
from entry 22 of play-nice's
[future-direction report](https://github.com/simongray/play-nice/blob/master/doc/future-direction-2026.md),
which left the work to a library that runs on the JVM. It was checked
against podcast-clj at 934bb35, and against the code and docs of each
consumer on the same day.

A link to a show or an episode gets its preview from the HTML that the
server sends, since the crawlers behind link previews, e.g. Mastodon's,
Discord's and Apple's, run no JavaScript. So the tags have to be in the
page as it arrives, whether a host's website or an app's own show and
episode pages serve it. Every fact that the tags need is already in
`feed.resolve`, and nothing writes the tags yet. A pure function from a
feed, and one of its items for an episode page, to the elements of the
head would serve both kinds of page. Most of the gain is in a handful of tags:

- the Open Graph title, description, image and URL, which every preview
  reads;
- the feed's autodiscovery link, which lets a podcast app subscribe from
  the page;
- `fediverse:creator`, which credits the host's account on Mastodon when
  that account lists the site's domain.

Players and JSON-LD gain much less. No preview plays `og:audio`, and of
the consumers below, only Mastodon plays an embedded player from any
site. JSON-LD of the podcast types has no consumer today.


## What the previews read

| Consumer | Text and image | Plays | JSON-LD | oEmbed |
|---|---|---|---|---|
| [Mastodon](https://github.com/mastodon/mastodon/blob/main/app/lib/link_details_extractor.rb) | `og:title`, `og:description`, `og:image` and its alt text, `og:site_name`, `og:locale`, the canonical link, `article:published_time` and `fediverse:creator` | A `twitter:player` page in a frame, or a video oEmbed | Only `NewsArticle` and `WebPage` | Tried first, and when it works the Open Graph tags are skipped. The rich type is refused. |
| [Bluesky](https://bsky.network/docs/about-bluesky-content/posts) | `og:title`, `og:description` and `og:image`, read once by the posting client through a card service whose code isn't public | Only listed hosts, e.g. YouTube and Spotify | – | – |
| [Slack](https://api.slack.com/robots) | Open Graph, Twitter and oEmbed, in an order it doesn't document | Only listed hosts | – | Read |
| [Discord](https://docs.discord.com/developers/link-previews/overview) | `og:title`, `og:description`, `og:site_name`, `og:image` with its size and alt text, and `theme-color` | An MP4, WebM or MOV file as `og:video`, with an `og:type` of `video.*` | `VideoObject` | Only the author and the provider; the HTML is ignored |
| [iMessage](https://developer.apple.com/documentation/technotes/tn3156-create-rich-previews-for-messages) | `og:title`, `og:site_name` and `og:image`, at least 900 pixels wide | A video file as `og:video`, within 10 MB for everything that the page loads | – | – |
| [X](https://web.archive.org/web/20260204163853/https://developer.x.com/en/docs/x-for-websites/cards/overview/player-card) | `og:` tags in place of missing `twitter:` ones | `twitter:player`, whose docs are gone, so whether it still needs approval is unknown | – | – |
| [Facebook](https://developers.facebook.com/docs/sharing/webmasters/) | `og:url`, `og:title`, `og:description` and `og:image` with its size | An MP4 as `og:video`, with its size | – | – |
| [Google](https://developers.google.com/search/docs/appearance/structured-data/search-gallery) | `og:title` for title links, `og:site_name` for site names, and `og:image` in Discover | – | `VideoObject` for video results; there's no podcast feature | – |

So the Twitter tags matter only for the card's layout and its player,
since every consumer reads the `og:` tags. Open Graph has no podcast
type, so a show page is a `website` and an episode page an `article`,
with its date. In schema.org 30.1, `PodcastSeries`, `PodcastEpisode` and
`PodcastSeason` are still pending. The podcast namespace defines no tags
for web pages, and RSS Autodiscovery prefers one feed link per page, with
`rel` and `type` in lower case and a full URL.


## What podcast-clj has already

Almost every fact comes from an accessor of `feed.resolve`, so a host's
own feed and a parsed one give the same tags:

| Fact | Accessor | Note |
|---|---|---|
| Title | `resolve/title` | Not the `:title` of `library/show`, which can be the listener's own name for the show |
| Description | `resolve/description` | HTML, which html-pieces' `excerpt` turns into one line of text |
| Image | `resolve/images`, `resolve/artwork` | A podcast:image can declare its width, height and alt text, while the artwork is a URL without them |
| Poster of a video | `resolve/poster` | |
| Page | `resolve/link` | The item's own page has no fallback to the feed's |
| Feed | `resolve/self-url` | Only when the feed names itself |
| Published | `resolve/published` | An instant |
| Duration | `resolve/duration` | Seconds; util.time has no ISO 8601 duration yet |
| Season and episode | `resolve/numbering` | |
| Media file | `resolve/enclosure` | With its `:type` and `:length` |
| Language | `:rss/language` | e.g. `en-us`, which `og:locale` writes as `en_US` |
| Fediverse account | `resolve/social-interacts` | The `:account-id` of an ActivityPub thread |

The inspector is the obvious first user. It renders its show and episode
pages on the server, at `/feed?url=…` and `/item?url=…&guid=…`, and its
`layout` puts only a title and the styles in the head. The podcast server
gains nothing yet, since its client draws the show pages in the browser.


## The design

The plan is a namespace `feed.page` in the companions layer, next to
`feed.publish`, built on `feed.resolve`, with two functions:

- `head` returns the elements of the head for the page of `feed`, or of
  an `item` of it, as Hiccup, by the `opts`. A site serializes them with
  its own renderer, or with html-pieces' `serialize`.
- `json-ld` returns the schema.org data of the page, which `head` puts in
  a script element.

The options name what the feed can't know, in kebab-case: the `:url` of
the page, the `:feed-url` when the feed doesn't name itself, the
`:site-name`, and the `:player-url` of a page that plays the episode in a
frame. The output keeps the names that each format gives, e.g.
`og:image:width` and `@type`, as `format.transcript` writes `startTime`
and the server's actor writes `@context`. They're foreign data on the way
out, like the XML of a feed.

A view works as the feed or the item too, since it keeps the raw keys
that `feed.resolve` reads.

Two details need care. The JSON-LD goes inside a script element, so a
title that contains `</script>` mustn't end it early: the JSON escapes
every `<` as `\u003c`. And the URLs in the head should be absolute, as
RSS Autodiscovery recommends, so a relative image in a feed is resolved
against the feed's URL.


## The steps

Each step is one batch, so you can stop after any of them.

### 1. The head that every preview reads

- The Open Graph tags in `head`: the title, description, URL, type, site
  name and locale, and the image with its alt text and size where the
  feed declares them. An episode page adds `article:published_time`.
- The canonical link, the meta description, the feed's autodiscovery
  link, `twitter:card` as `summary` for square artwork, and
  `fediverse:creator` from the feed's ActivityPub account.
- A description of at most 200 characters, the limit in X's archived
  docs.
- Tests on the corpus: every feed gives a head with absolute URLs and a
  description of one line.

**Why first.** It's what a shared link shows, and the feed link lets a
podcast app subscribe from the page.

**Size.** A day or two.

### 2. JSON-LD

- The `json-ld` function: a `PodcastSeries` for a show and a
  `PodcastEpisode` for an episode, with its media as an `AudioObject`,
  and a `VideoObject` for a video episode, with the poster as its
  thumbnail.
- An ISO 8601 duration in util.time, e.g. `PT1H2M3S`, and the escaping of
  `<`.

**Why here.** Only the `VideoObject` has readers today, Google and
Discord. The podcast types are pending and nothing reads them, but
they're what schema.org offers for podcasts, and they cost little next
to it.

**Size.** A day.

### 3. Players

- For a video episode whose file is an MP4, `og:video` with its type and
  size, and `og:type` as `video.episode`, so that Discord plays it.
  iMessage loads at most 10 MB for a preview, so a whole episode most
  likely won't play there.
- With a `:player-url`, `twitter:card` as `player` and the
  `twitter:player` tags, which make a video card that plays in Mastodon.
- A page in the inspector that plays one episode in a frame, to try them
  on.

**Why last.** The player tags need a player page that the library
doesn't have, and Mastodon is the only consumer known to play one.

**Size.** A few days, most of it the inspector's player page.

### 4. The inspector and the guide

- The inspector's show and episode pages use `head`.
- A section in [How to build a podcast app](../building-an-app.md), under
  "Build the show and episode pages", for apps and hosts that render their
  pages on the server.
- A row in [Namespaces](../namespaces.md) and a place in
  [the layers](../architecture.md#layers) and their test.

**Size.** Hours, after step 1.


## What to leave out

- **oEmbed.** When Mastodon gets an oEmbed answer, it
  [skips the Open Graph tags](https://github.com/mastodon/mastodon/blob/main/app/services/fetch_link_card_service.rb),
  so the card loses its description and the host's credit. It also
  refuses the rich type that Apple Podcasts and Spotify answer with, and
  Discord ignores the HTML. A site that wants other sites to embed its
  player by URL can still ask for it.
- **`og:audio`.** No preview plays it.
- **A Smart App Banner for Apple Podcasts.** Apple documents the banner
  for a site's own app, and whether Apple Podcasts opens the show from it
  is unverified.
- **Discord's component embeds and `theme-color`.** One consumer each.
  The colour would come from `image/colors`, which needs the image's
  bytes.


## Decisions to make

- **The name and the layer.** `feed.page`, in the companions layer next
  to `feed.publish`? It writes from the feed model, as `feed.publish`
  does, and needs nothing from the `app` namespaces.
- **The scope.** [Podcasting and Clojure](podcasting-and-clojure-2026.md#out-of-scope)
  puts websites among the services of hosts that podcast-clj leaves out.
  The head of a page is data from the feed, like the feed itself, so this
  plan reads that as the website staying the host's, not the tags. Is
  that right?
- **Players.** Step 3 only pays off for video episodes and for a site that
  has a page to embed. Build it, or leave it for when one asks?


## Open questions

Only a live check can answer these:

- Which tags Bluesky's card service reads, since its code isn't public.
- Whether Mastodon's mobile apps play the video card that the web app
  plays.
- Whether X still approves player cards.
- Which of Open Graph, Twitter and oEmbed Slack prefers.
