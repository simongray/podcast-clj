# Services and their defaults

This page lists what podcast-clj knows about other services and apps, such
as the podcast directories, Podping, OP3, gpodder servers and Nostr relays,
and the option that replaces each default. [Principles](principles.md)
explains why they're defaults, and [How to build a podcast
app](building-an-app.md#use-another-service) shows the calls. Namespaces
are relative to `dk.simongray.podcast`.


## The rule

- A fact that a spec defines is a constant in the code, e.g. the
  well-known paths of LNURL-pay, the methods of the Hive API, and the calls
  of the Mastodon API.
- A fact about one service is a default: a public var holds it as data,
  and an option of the same name replaces it for one call.
- Where an option takes the name of an entry in a table, it also takes an
  entry as data, in the same shape.


## The defaults, as of September 2026

| What | Var | Option | Default |
|---|---|---|---|
| The directories | `directory/sources` | a source, as a name or an entry | Apple Podcasts, fyyd, gpodder.net and the Podcast Index |
| The root of the Podcast Index API | `directory.podcastindex/base-url` | `:base-url` | `https://api.podcastindex.org/api/1.0/` |
| The Hive nodes that Podping reads | `podping/nodes` | `:nodes`, or one `:node` | public nodes |
| The service that writes a podping for a host | `podping/gateway` | `:gateway` | `https://podping.cloud/` |
| The request that tells a WebSub hub of a change | `websub/publish-form` | `:publish-form` | the fields `hub.mode=publish` and `hub.url` |
| The genres of Apple Podcasts, for the chart of a category | `directory/apple-genres` | the `:genres` of a source | the genres of Apple |
| The forms of links to the apps | `links/apps` | `:apps` | the [table below](#the-links-of-the-apps) |
| The paths that `find-feeds!` tries on the domain of a page | `feed.discovery/guessed-paths` | `:guessed-paths` | `/feed/` of WordPress, `/rss` of Libsyn and Ghost, `/feed.xml` of Jekyll and `/index.xml` of Hugo |
| The relays of Nostr | `comments.nostr/relays` | `:relays` | the [relays below](#the-relays-of-nostr) |
| The instance of OP3 | `analytics.op3/origin` | `:origin` | `https://op3.dev` |
| The URLs that OP3 accepts behind its prefix | `analytics.op3/accepts?` | `:accepts?`, a predicate of a URL | the rules of op3.dev |
| The paths of the gpodder API | `sync.gpodder/apis` | `:api`, as a name or a map of paths | `:gpodder` |
| The limits of a gpodder server | `sync.gpodder/default-limits` | `:page-size`, `:max-pages`, `:batch-size`, `:max-pending` | 1000, 10, 100 and 500 |
| The paths of statuses, for the Mastodon API | `comments.activitypub/status-paths` | `:status-paths` | the paths of four server programs |
| The requirements of a directory for a feed | `feed.validate/requirements` | `:requirements`, as a name or a map | none |
| The requirements of a directory for artwork and media files | `check/requirements` | `:requirements`, as a name or a map | none |
| The folder that an app writes around a whole OPML list | `profile/wrappers` | `:wrappers`, a set of names or a predicate of a name | `feeds` |

There's no default for these, so you give them:

- the WebSub hub of a feed, which the feed names
- the AppView that reads AT Protocol threads, as `:appview`. As of
  September 2026, Bluesky's `https://public.api.bsky.app` and Blacksky's
  `https://api.blacksky.community` answer without an account.
- the root of a gpodder server and the URL of a WebDAV collection, some of
  which [Sync reference](sync-reference.md) lists
- the OPAWG list of user agents, which `analytics/user-agents` reads from
  files that you keep


## The directories

An entry of `directory/sources` says which API the directory speaks, and
has the URLs of the service:

| Name | `:api` | URLs |
|---|---|---|
| `:apple` | `:apple` | `:search` and `:lookup`, the URLs of the two calls, and `:trending`, the root of the charts |
| `:fyyd` | `:fyyd` | `:root` |
| `:gpodder` | `:gpodder` | `:root` |
| `:podcastindex` | `:podcastindex` | `:root`, and `:pages`, the URL of the pages of the shows |

The functions `request`, `response` and `call!` take a source as a name or
an entry, and the `:sources` of `search!` and `fetch-chart!` can hold both.
The `:id` of an entry names the source in `:directory/source`,
`:directory/sources` and `:failed`, and an entry without an `:id` takes the
name of its API.

### The chart of a category

Each directory names a category its own way. These facts come from the
answers of the services on 29 September 2026:

| Directory | The chart of a category | The category |
|---|---|---|
| Apple Podcasts | The feed `{country}/rss/toppodcasts/limit={n}/genre={id}/json`, below the `:genre-charts` of the entry, in the form of Apple's older feeds. It names no feed, and the chart of one show has an object in place of a list. | The id of a genre. `directory/apple-genres` has the ids by name, from Apple's [genre service](https://itunes.apple.com/WebObjects/MZStoreServices.woa/ws/genres?id=26). |
| Podcast Index | The call `podcasts/trending` with the parameter `cat` | A name or an id from [categories.json](https://github.com/Podcastindex-org/podcast-namespace/blob/main/categories.json) of the podcast namespace, or several, with commas. The index gives the chart of all categories for a name that it doesn't know, without an error. |
| gpodder.net | The call `api/2/tag/{tag}/{n}.json` | A tag, a word that users of gpodder.net gave a show |
| fyyd | None. The call `feature/podcast/hot` ignores a category. | |

The Podcast Index uses the words of Apple's names for its categories, e.g.
Apple's Society & Culture is Society and Culture there. So the names of
Apple's top categories work for both, and `podcastindex/category-filter`
reads a name with an ampersand as its parts. A second-level category of
Apple, such as Tech News, has no name in the Podcast Index, so give a map
with a category for each source.


## The gpodder API

The paths of the calls are in `sync.gpodder/apis`, where `{user}` is
the user name and `{device}` is the id of the device.

| Call | `:gpodder` | `:nextcloud` |
|---|---|---|
| `:login` | `/api/2/auth/{user}/login.json` | none |
| `:register` | `/api/2/devices/{user}/{device}.json` | none |
| `:subscriptions`, `:update-subscriptions` | `/api/2/subscriptions/{user}/{device}.json` | `/index.php/apps/gpoddersync/subscriptions`, `/index.php/apps/gpoddersync/subscription_change/create` |
| `:episodes`, `:update-episodes` | `/api/2/episodes/{user}.json` | `/index.php/apps/gpoddersync/episode_action`, `/index.php/apps/gpoddersync/episode_action/create` |

A backend only logs in when its API has a path for `:login`, and only
registers its device when it has one for `:register`.

| Limit | Default | Meaning |
|---|---|---|
| `:page-size` | 1000 | A page of episode actions with this many actions is full, so a pull asks for one more page. gpodder.net gives 1000 actions at most. |
| `:max-pages` | 10 | The pages of episode actions that one pull reads |
| `:batch-size` | 100 | The episode actions in one request of a push. gpodder.net accepts a longer list in the background, and reports no errors in it. |
| `:max-pending` | 500 | The actions for unknown feeds that the cursor keeps |


## The statuses of the Mastodon API

When the server of the root post refuses the reader, `comments/fetch!`
reads the thread through the Mastodon API, which needs the id of the
status from the URL of the root post. Each entry of
`comments.activitypub/status-paths` is a regular expression for the whole
path of a URL, whose one group is the id.

| Path | Server program |
|---|---|
| `/@USER/ID` | Mastodon |
| `/@USER/statuses/ID` | GoToSocial |
| `/users/USER/statuses/ID` | The ActivityPub id of a status of Mastodon and GoToSocial |
| `/notice/ID` | Pleroma and Akkoma |


## OP3

OP3 answers a URL that it doesn't accept with an error, so an episode with
such a URL wouldn't play, and `analytics.op3/accepts?` checks a URL first.
The URL must be http or https, with a path and without a user name, and
its domain can't be:

- an IPv6 address
- a name without a dot
- a name that RFC 2606 reserves for tests: `.test`, `.example`, `.invalid`
  and `.localhost`

Another instance of OP3 may accept other URLs, so give its `:origin`, and
its rules as `:accepts?`.


## The links of the apps

The forms of the links to a show and to an episode in each app are in
`links/apps`. They come from Nathan Gathright's list [Podcast Platform
Links](https://github.com/nathangathright/podcast-platform-links), as it
was on 29 September 2026. The table only has the forms built from ids that
the app didn't make, and leaves out the forms that need the app's own id,
such as Spotify's id of a show.

A key in braces is a key of `links/ids`, and a word before the key is an
encoding:

- `encoded`: percent-encoded for a query
- `base64url`: the base64 of URLs
- `hex`: hexadecimal
- `bare`: the URL without its scheme

The apps ignore `{slug}`, so `links/default-slug` fills it. An entry of
type page is a web page that leads a listener to the apps.

| Name | Type | Show | Episode | Subscribe |
|---|---|---|---|---|
| 1tuner | app | `https://1tuner.com/podcast/{slug}/{base64url feed-url}` | `https://1tuner.com/podcast/{slug}/{base64url feed-url}/{encoded episode-guid}` |  |
| AntennaPod | app | `https://antennapod.org/deeplink/subscribe?url={feed-url}` |  | `antennapod-subscribe://{bare feed-url}` |
| Anytime Player | app | `https://anytimeplayer.app/subscribe?url={feed-url}` |  |  |
| Apple Podcasts | app | `https://podcasts.apple.com/podcast/id{apple-id}` |  | `podcast://{bare feed-url}` |
| Breez | app | `https://breez.link/p?feedURL={encoded feed-url}` | `https://breez.link/p?feedURL={encoded feed-url}&episodeID={encoded episode-guid}` |  |
| Castamatic | app | `https://castamatic.com/guid/{podcast-guid}` |  | `castamatic://{bare feed-url}` |
| Castbox | app | `https://castbox.fm/vic/{apple-id}` |  |  |
| Castro | app | `https://castro.fm/itunes/{apple-id}` |  | `castros://subscribe/{bare feed-url}` |
| CurioCaster | app | `https://curiocaster.com/podcast/pi{podcastindex-id}` |  |  |
| Downcast | app |  |  | `downcast://{bare feed-url}` |
| Fountain | app | `https://fountain.fm/show/{podcastindex-id}` | `https://fountain.fm/episode/{podcastindex-episode-id}` |  |
| Goodpods | app | `https://www.goodpods.com/podcasts-aid/{apple-id}` |  |  |
| gPodder | app | `https://gpodder.net/subscribe?url={feed-url}` |  | `gpodder://{bare feed-url}` |
| Great Pods | page | `https://www.greatpods.co/podcasts?podcastIndexId={podcastindex-id}`<br>`https://www.greatpods.co/podcasts?itunesId={apple-id}` |  |  |
| iCatcher! | app |  |  | `icatcher://{bare feed-url}` |
| Metacast | app | `https://metacast.app/itunes/{apple-id}`<br>`https://open.metacast.app/podcasts/{podcast-guid}` |  |  |
| Moon FM | app | `https://moon.fm/itunes/{apple-id}` |  |  |
| Odesli | page | `https://pods.link/i/{apple-id}` |  |  |
| Overcast | app | `https://overcast.fm/itunes{apple-id}` |  | `overcast://x-callback-url/add?url={feed-url}` |
| Player FM | app | `https://player.fm/subscribe?id={encoded feed-url}` |  | `playerfm://{bare feed-url}` |
| Plink | page | `https://plinkhq.com/i/{apple-id}?to=page` |  |  |
| Pocket Casts | app | `https://pca.st/itunes/{apple-id}` |  | `pktc://subscribe/{bare feed-url}` |
| Pod Engine | page | `https://www.podengine.ai/podcasts/id/{apple-id}` |  |  |
| pod.link | page | `https://pod.link/{apple-id}`<br>`https://pod.link/{base64url feed-url}` |  |  |
| Podbean | app | `https://www.podbean.com/itunes/{apple-id}` |  |  |
| Podcast Addict | app | `https://podcastaddict.com/feed/{encoded feed-url}` | `https://podcastaddict.com/episode/{encoded enclosure-url}` | `podcastaddict://{bare feed-url}` |
| Podcast Details | page | `https://podcastdetails.com/?url={feed-url}` |  |  |
| Podcast Guru | app | `https://app.podcastguru.io/podcast/{apple-id}`<br>`https://app.podcastguru.io/podcast/X{hex feed-url}` |  | `podcastguru://{bare feed-url}` |
| Podcast Index | page | `https://podcastindex.org/podcast/{podcastindex-id}`<br>`https://podcastindex.org/podcast/{podcast-guid}` |  |  |
| Podcast Parrot | app | `https://podcastparrot.app/show?url={encoded feed-url}` |  |  |
| Podcast Republic | app | `https://www.podcastrepublic.net/podcast/{apple-id}` |  |  |
| Podcast X-Ray | page | `https://podcastxray.com/podcast/{apple-id}` |  |  |
| Podcat | app |  |  | `podcat://{bare feed-url}` |
| Podchaser | page | `https://podchaser.com/f/pod/{apple-id}` |  |  |
| podfollow | page | `https://podfollow.com/{apple-id}` |  |  |
| Podfriend | app |  | `https://podfriend.com/podcast/{apple-id}/{encoded episode-guid}` |  |
| Podkicker | app |  |  | `podkicker://subscribe/{bare feed-url}` |
| PodLP | app | `https://link.podlp.app/{apple-id}`<br>`https://link.podlp.app/{podcast-guid}` |  |  |
| Podnews | page | `https://podnews.net/podcast/{apple-id}`<br>`https://podnews.net/podcast/pi{podcastindex-id}`<br>`https://podnews.net/podcast/{podcast-guid}` |  |  |
| podStation | app | `https://podstation.github.io/subscribe-ext/?feedURL={feed-url}` |  |  |
| Podurama | app | `https://podurama.com/podcast/{slug}-i{apple-id}` |  |  |
| Podverse | app | `https://api.podverse.fm/api/v1/podcast/podcastindex/{podcastindex-id}` |  |  |
| RSSRadio | app |  |  | `rssradio://{bare feed-url}` |
| Sonnet | app | `https://sonnet.fm/p/{apple-id}` |  |  |
| Steno.fm | app | `https://steno.fm/show/{podcast-guid}` | `https://steno.fm/show/{podcast-guid}/episode/{base64url episode-guid}` |  |
| The Podcast App | app | `https://thepodcastapp.dev/p/itunes{apple-id}`<br>`https://thepodcastapp.dev/p/{podcastindex-id}` |  |  |
| TrueFans | app | `https://truefans.fm/{podcast-guid}` |  |  |
| YouTube Music | app | `https://music.youtube.com/library/podcasts?addrssfeed={base64url feed-url}` |  |  |

No app has a form for a link to a time. The apps that share an episode at a
time put the time on a link with their own id of the episode, and none of
them documents the form. A form is data, so you can add your own to the
table, with a key such as `:time` in the ids. For a media file,
`links/at-time` writes the media fragment of the W3C, which a browser plays
from that time.


## The relays of Nostr

The default relays for comments on Nostr are in `comments.nostr/relays`,
which `comments/fetch!` asks when the options give no `:relays`. A thread
whose root names relays asks those first.

The table shows what each relay answered on 29 September 2026, when asked
for the notes about one episode by its NIP-73 id, which Fountain wrote that
day.

| Relay | In `comments.nostr/relays` | The answer |
|---|---|---|
| `wss://relay.fountain.fm` | yes | Three notes about the episode. No answer to a filter that only names kind 1111, and it doesn't read a filter on the tags `I` and `E`. |
| `wss://relay.damus.io` | yes | No note about the episode, but notes about other episodes |
| `wss://relay.primal.net` | yes | One note about the episode |
| `wss://nos.lol` | no | No note about any episode |
| `wss://relay.nostr.band` | no | No connection within ten seconds |

On that day, the relays had notes of kind 1 about episodes, with the id of
the episode in an `i` tag as Fountain writes them, but no comments of kind
1111 on an episode, the form that NIP-22 defines. The reader asks for both.

Choose your own relays with `:relays`:

```clojure
(comments/fetch! thread {:relays ["wss://relay.example"
                                  "wss://relay.fountain.fm"]})
```


## The wallets of the listeners

The wallet namespaces follow NIP-47 in `value.nwc` and the WebLN guide in
`value.webln`, as they read on 29 September 2026. No test used a wallet
with real money, so the table says what the specs say, not what a wallet
answered.

| Spec | Where | What podcast-clj implements |
|---|---|---|
| NIP-47, Nostr Wallet Connect | `github.com/nostr-protocol/nips`, the file `47.md` | The connection URI, the info event, requests and responses, and the commands `pay_invoice`, `make_invoice`, `lookup_invoice`, `get_balance` and `get_info` |
| NWC-04, Keysend Payments | `github.com/nostr-wallet-connect/nwc`, the file `04.md` | The command `pay_keysend`, with custom records in hexadecimal |
| NWC-06, Metadata Conventions | The same repository, the file `06.md` | Nothing of its own: `pay-invoice` takes `:metadata` as the app gives it |
| NIP-44, Encrypted Payloads | `github.com/nostr-protocol/nips`, the file `44.md` | Version 2, including payloads of 65,536 bytes and more |
| WebLN | `webln.guide` | `enable`, `getInfo`, `sendPayment` and `keysend` |

On that day, NIP-47 had a core of five commands, and the rest were
extensions in the repository nostr-wallet-connect/nwc. An older NIP-47 had
`pay_keysend` in its own text.

The options replace these choices:

| Choice | Default | Option |
|---|---|---|
| How long `ask!` waits, after which a request expires | 60 seconds, `nwc/default-limits` | `:timeout-seconds` |
| The relays that a request can go to | The public internet, `nostr/public?` | `:allow?` |
| What sends a request to a relay | `nostr/send!` | `:send` |
| The most bytes of an encrypted text | 65,535, `nip44/default-limits` | `:max-bytes` |
| The WebLN provider | That of the page, `webln/provider` | `:provider` |


## The rules of the apps for downloads

No standard defines how an app downloads episodes and deletes files. The
namespace `downloads` has the kinds of rule that these apps have, and
the table gives the rules of each app as its help pages described them on
29 September 2026, with the matching rule in `downloads`.

| App | The rule of the app | In `downloads` |
|---|---|---|
| [Apple Podcasts](https://support.apple.com/en-us/102243) | Automatic downloads of a number of episodes, or of the episodes of a time | `:limit` and `:age` of `:episodes` |
| Apple Podcasts | No automatic downloads for one show | `:download?` of the rule of the show |
| Apple Podcasts | A played episode goes 24 hours after it's played | `:played` |
| [Pocket Casts](https://support.pocketcasts.com/knowledge-base/archiving-episodes/) | A played episode goes when it's played, or some time later | `:played` |
| Pocket Casts | An episode goes when the listener hasn't played or downloaded it for some time | `:inactive` |
| Pocket Casts | Starred episodes stay, if the listener wants | `:keep` |
| Pocket Casts | A show keeps its newest 1, 2, 5 or 10 episodes | `:limit` of `:episodes`, with `:trim?` |
| [AntennaPod](https://antennapod.org/documentation/automation/automatic-downloads) | Automatic downloads of the episodes in the inbox, for each show | `:download?`, and `:inbox?` of `:episodes` |
| AntennaPod | A filter for a show: a minimum length, and words to include or exclude | `:duration`, `:text` and `:not` of `:episodes` |
| AntennaPod | 50 episodes at most, unless the listener sets another number | `:max-episodes` |
| [AntennaPod](https://antennapod.org/documentation/automation/deletion) | A file goes when its episode is played | `:played` of 0 |
| AntennaPod | Old files go when a download needs room, except those in the queue and the favourites | `:make-room?`, and `:keep` |

The defaults are in `downloads/default-rule` and
`downloads/default-rules`, and they download and delete nothing until
the listener sets a rule.


## The folder around an OPML list

Some apps write the whole subscription list inside one outline, a wrapper
rather than a folder of the listener's own. OPML can't tell the two apart,
so `profile/from-opml` goes by the name: a folder is a wrapper when it holds
every feed of the list and its name is in `profile/wrappers`, whatever
its case.

| Name | App that writes it | Seen in September 2026 |
|---|---|---|
| `feeds` | Pocket Casts | [an export file](https://github.com/razorgoto/podcast/blob/master/podcasts.opml) |
| `feeds` | Overcast | [an export file](https://github.com/vinhnx/podcasts/blob/main/overcast.opml) |

A folder with another name stays a folder, so a listener's own folder that
holds all the shows survives, and an export from `profile/to-opml` comes
back as it went out. For an app that writes another name, give the names
as `:wrappers`, or `(constantly true)` to treat every folder that holds
every feed as a wrapper.


## The requirements of directories

An entry of `feed.validate/requirements` holds the elements that a
directory requires and recommends, for the channel and for each item. An
entry of `check/requirements` holds what it requires of artwork, and the
levels of the diagnostics that the directory changes:

| Key | Meaning |
|---|---|
| `:name` | The name of the directory or the standard |
| `:artwork` | `:sizes`, the smallest and largest width in pixels, `:types`, the media types, `:colors`, the colour models, and `:extension?`, `:alpha?` and `:last-modified?` |
| `:levels` | The level of a diagnostic, by its id |

Both have the profiles `:psp-1` and `:apple`, which follow
[PSP-1](https://github.com/Podcast-Standards-Project/PSP-1-Podcast-RSS-Specification)
of the Podcast Standards Project and Apple's [A Podcaster's Guide to
RSS](https://help.apple.com/itc/podcasts_connect/#/itcb54353390), both as of
30 September 2026.
