# Video podcasts, October 2026

This report looks at video podcasts and what podcast-clj needs for them,
written on 10 October 2026, to decide the next steps of the library. It's
frozen as of that date and won't be updated. Facts about other software
come from its documentation, code, releases, issues or feeds, with each
source linked. The state of podcast-clj was checked in its code on the
same day, after the commit that added `resolve/video?` and
`resolve/downloadable?`.

People mostly watch video podcasts on closed platforms in October 2026. Open RSS meanwhile is settling on one way to carry them: an audio enclosure, with an HLS stream (and sometimes an MP4) as `podcast:alternateEnclosure`. More weekly US podcast consumers now watch video than listen to audio only. But YouTube, Spotify, Netflix and Apple's new HLS video all deliver their video outside the feed, so an RSS library never sees it. In open RSS, about 2% of feeds carry video, mostly as old-style MP4 enclosures, and 11 hosts and 7 apps now support the HLS pattern. The apps that handle video well do five things:

- They download the audio and stream the video.
- They let the listener choose between audio and video for each show.
- They don't trust the sizes that feeds declare.
- They find out before playback whether a stream is video.
- They find out the shape of the video, too.

The library already parses every tag involved but Media RSS's `media:subTitle`. The gaps are in what it does with them:

- The function `resolve/video?` counts an HLS alternate without a height as audio.
- Nothing reads HLS playlists or the video track of an MP4.
- There is no audio/video choice for each show.
- Nothing gives a poster or a `podcast:contentLink`.

The recommended order is a batch of small fixes in `resolve` and the episode view first. After that come an audio/video choice for each show in the profile, then an HLS playlist reader, and last, video facts from MP4 files with video rules in `feed/check`.

## Most video watching happens where RSS can't reach

Video has caught up with audio in reach. Edison found that in Q3 2025, **82% of weekly US podcast consumers watched video and 78% listened to audio only** ([Podnews, Edison press release](https://podnews.net/press-release/podcast-consumer-2026)). The watching happens on platforms. In Sounds Profitable's survey of August 2026, **40% named YouTube as the service they use most for podcasts, 17% Spotify and 8% Apple Podcasts** ([PPC Land](https://ppc.land/netflix-ranks-third-among-us-podcast-platforms-used-by-21-of-consumers)). In the same survey, 21% had used Netflix in the past year, although Netflix only began carrying video podcasts in January 2026 ([TheWrap](https://www.thewrap.com/media-platforms/tv/netflix-podcasts-january-2026/)). Most of these people still mainly listen: in a 2025 Cumulus survey, 92% of weekly consumers said they mostly listen to their favourite shows, even on YouTube ([Barrett Media](https://barrettmedia.com/2025/11/18/92-of-weekly-podcast-consumers-listen-rather-than-watch-new-cumulus-media-signal-hill-insights-data-shows/)).

None of the big platforms takes its video from a feed:

- Apple launched HLS video with iOS 26.4 on 24 March 2026. Hosts send each episode's playlist to Apple through a partner API whose requirements are under NDA. Apple ignores `podcast:alternateEnclosure`, even for MP4 files ([Podnews](https://podnews.net/article/video-apple-podcasts-details)). From a feed, Apple still plays only MOV, MP4 and M4V enclosures, and it recommends a separate feed for them ([Apple](https://podcasters.apple.com/support/3684-video-podcasts)).
- Spotify only takes video that is uploaded directly or sent through its Distribution API. The RSS feed stays the source for audio ([Spotify for Creators](https://creators.spotify.com/resources/news/expanding-video-partners-and-platforms)).
- YouTube takes only the audio from a feed and turns it into a video of the show art ([YouTube Help](https://support.google.com/youtube/answer/13525207?hl=en)).

For the library, this means that the video most people watch never appears in a feed. At most, a feed can link to it, which is what an item's `podcast:contentLink` is for. The Podcast Standards Project (PSP) proposed using it for YouTube links in February 2026. It had first proposed putting YouTube URLs in `alternateEnclosure`, and dropped that because a watch page isn't a media file ([PSP proposal #2](https://github.com/Podcast-Standards-Project/proposals/issues/2)).

## Open RSS video is moving from MP4 feeds to HLS alternates

By count, the older pattern still dominates. In December 2025, **113,597 feeds in the Podcast Index, 2.3% of the total, had a video file as their newest enclosure, and 94% of those were MP4** ([Podnews](https://podnews.net/article/podcast-apps-supporting-video)). Only 14,627 of 4.74 million feeds declare `podcast:medium` as video ([Podcast Index stats, 10 October 2026](https://stats.podcastindex.org/hourly_counts.json)). The medium therefore misses most video feeds, and telling video by its type, as the library does, is the right approach. The defn feed in the test corpus is a typical case, with MP4 enclosures and no medium.

The pattern that hosts built in 2025 and 2026 keeps the audio file in the enclosure and adds the video as alternates. PSP proposed it in May 2025 ([PSP](https://podstandards.org/2025/05/27/a-new-paradigm-for-video-podcasts-hls-streaming/)). By September 2026, its list had these hosts and apps ([PSP](https://podstandards.org/2026/08/11/podcast-hosting-platforms-and-apps-that-support-hls-video-in-rss/)):

- Hosts that publish it: Transistor, RSS.com, Fountain, True Fans, Captivate, Flightcast, Podigee, Beamly, Podbean, Omny Studio and Blubrry, with Buzzsprout and Spreaker on the way.
- Apps that play it: Pocket Casts, Amazon Music, iHeart, Fountain, True Fans, Podcast Guru and Podcast Addict.

Amazon Music added video in May 2026 and takes it from these alternates, with no private API ([Sounds Profitable](https://soundsprofitable.com/the-download/amazon-music-video-podcasts/)). Use is still small. Livewire counted at least 62 shows with HLS alternates in public feeds by April 2026 ([Livewire](https://livewire.io/hls-video-in-podcasts/)), and no count exists for October. Several apps don't support the pattern:

- Apple Podcasts doesn't read alternates.
- Overcast didn't play video at all in Podnews' test of December 2025 ([Podnews](https://podnews.net/article/podcast-apps-supporting-video)).
- AntennaPod has left alternates undecided since 2021 ([AntennaPod #5608](https://github.com/AntennaPod/AntennaPod/issues/5608)).

Real feeds fill in the pattern in different ways:

| Feed | Enclosure | Video alternates |
|---|---|---|
| Mac Geek Gab ([feed](https://www.macgeekgab.com/feed/podcast/)) | MP3 | An HEVC MP4 and an HLS stream, both with `rel="alternate"`, a height and codecs, and no length. WebVTT captions. |
| Omny Studio ([Triton help](https://help.tritondigital.com/user/docs/where-is-my-rss-feed)) | Audio | An MP4 with a length. An HLS stream typed `application/vnd.apple.mpegurl` with `length="0"` and no height. |
| Podnews Weekly Review on Buzzsprout ([feed](https://rss.buzzsprout.com/1538779.rss)) | MP3 | An HLS stream typed `application/vnd.apple.mpegurl` with `length="0"` and no height, since July 2026. |
| Podcasting 2.0 ([feed](https://mp3s.nashownotes.com/pc20rss.xml)) | MP3 | An HLS stream mistyped as `application.x-mpegURL`, with `rel="alternate"` and `height="720"`, on four episodes in 2025. |
| PSP's demo ([hls-video](https://github.com/Podcast-Standards-Project/hls-video)) | Audio | An HLS stream typed `application/x-mpegURL`, with `length="0"` and `rel="alternate"`. |

These feeds disagree in three ways that matter to a parser:

- **The HLS type has at least four spellings.** The namespace's example uses `application/x-mpegURL`. An open issue asks to change it to the registered `application/vnd.apple.mpegurl` ([#776](https://github.com/Podcastindex-org/podcast-namespace/issues/776)). Pocket Casts for iOS had to accept three types to find all HLS episodes ([#4935](https://github.com/Automattic/pocket-casts-ios/pull/4935)). The HLS draft also identifies a playlist by a path that ends in `.m3u8` or `.m3u` ([draft-pantos-hls-rfc8216bis-22, §4](https://www.ietf.org/archive/id/draft-pantos-hls-rfc8216bis-22.txt)).
- **The feeds use `rel` against the namespace's rule.** The namespace groups an alternate with the enclosure only when `rel` is absent or "default". Any other value starts a separate group of content ([alternate-enclosure.md](https://github.com/Podcastindex-org/podcast-namespace/blob/main/docs/tags/alternate-enclosure.md)). PSP's demo, Mac Geek Gab and the Podcasting 2.0 feed all mark the same content as `rel="alternate"`. A strict reading of the rule would therefore separate the video from its own audio.
- **Streams lack sizes and shapes.** HLS has `length="0"` by design. The Omny and Buzzsprout streams have no height and no codecs, and Mac Geek Gab's MP4 has no length.

## Good video handling means choosing, sizing and describing a file before playback

The apps that handle video best download the audio, stream the video, and let the listener choose at more than one level:

- Apple Podcasts downloads the audio of HLS video episodes by default. Listeners can turn on video downloads for one show or for all shows, and video downloads use Wi-Fi only unless the listener changes that ([Apple](https://podcasters.apple.com/support/1662-automatic-downloads-on-apple-podcasts)).
- Pocket Casts has a global audio-only setting that syncs between devices ([#5572](https://github.com/Automattic/pocket-casts-android/pull/5572); [NamedSettings.kt](https://github.com/Automattic/pocket-casts-android/blob/main/modules/services/servers/src/main/java/au/com/shiftyjelly/pocketcasts/servers/sync/NamedSettings.kt)). It also has a video toggle for each episode, which resets for the next episode ([#4803](https://github.com/Automattic/pocket-casts-ios/pull/4803)). It streams HLS and never offers it for download, since the playlist isn't the media ([#5430](https://github.com/Automattic/pocket-casts-android/pull/5430)).
- AntennaPod and gPodder users have asked since 2021 for a choice of audio or quality for each show ([AntennaPod #5608](https://github.com/AntennaPod/AntennaPod/issues/5608); [gPodder #1164](https://github.com/gpodder/gpodder/issues/1164)).

Declared sizes can't be trusted. In September 2026, a feed pointed at a 44.8 GiB raw MP4 export, and Pocket Casts' background cache cost a listener **€750 in mobile data**. Pocket Casts fixed it in two ways ([#5868](https://github.com/Automattic/pocket-casts-android/pull/5868)):

- Its cache now skips video, and anything that the feed says is over 500 MB.
- It stops any transfer at the cache size, since a length of 0 or a wrong length would get past the first rule.

The kind and shape of a file matter before it plays. Pocket Casts for Android couldn't tell that an HLS stream was video until the stream started ([#5678](https://github.com/Automattic/pocket-casts-android/pull/5678)). Pocket Casts for iOS now opens the video player for every HLS episode ([#4725](https://github.com/Automattic/pocket-casts-ios/pull/4725)). Codecs matter too. Mac Geek Gab publishes HEVC, which Chrome and Firefox support only in part ([caniuse](https://caniuse.com/hevc)). A web app can find out whether a file will play smoothly with `MediaCapabilities.decodingInfo()`, but that call needs the codecs, size, bitrate and frame rate ([MDN](https://developer.mozilla.org/en-US/docs/Web/API/MediaCapabilities/decodingInfo)). A feed gives some of these through the height and codecs of an alternate and through Media RSS. An HLS playlist gives all of them for each variant, and Apple's authoring rules require `CODECS` and `RESOLUTION` ([Apple HLS authoring specification](https://developer.apple.com/documentation/http-live-streaming/hls-authoring-specification-for-apple-devices)).

When the listener switches between audio and video, apps keep the position without checking that it still fits:

- Pocket Casts reloads the other file at the same second ([#5336](https://github.com/Automattic/pocket-casts-ios/pull/5336)).
- Fountain's release notes say that the audio and video must be the same length for the switch to be accurate ([Stacker News](https://stacker.news/items/1435233)).
- Mac Geek Gab trims its video so that the HLS stream runs 4895.9 seconds against the audio's 4896 ([master playlist](https://www.macgeekgab.com/media/cachefly/MGG-Videos/MGG1162-trimmed/master.m3u8); [feed](https://www.macgeekgab.com/feed/podcast/)).

No sync protocol records which file was played. The gpodder.net API names an episode by its media URL ([gpodder.net API](https://gpoddernet.readthedocs.io/en/latest/api/reference/events.html)), and the PortCast draft of May 2026 uses the guid or the enclosure URL ([PortCast](https://www.ietf.org/archive/id/draft-trimplayer-portcast-00.html)).

From this, the work divides between a library and an app:

| Need | In a library | In the app |
|---|---|---|
| Choose audio or video globally, for each show and for each episode | The preferences, and the rule that combines them | Detecting the network, and the toggle for each episode |
| Download the audio and stream the video | The file choice, the download plan and a stream-only label | The download itself |
| Don't trust declared sizes | Limits for each file, estimates and HEAD checks | Stopping a transfer that runs over |
| Know the kind, shape and codecs before playback | Reading feed attributes, HLS playlists and MP4 boxes | Asking the platform whether it can play them |
| Captions | Finding and ranking tracks with `rel="captions"` | Showing them |
| A 16:9 poster | Choosing a `podcast:image` | Grabbing a frame when there is no poster |
| Keep the position across files | Recording the file and comparing durations | Reloading the source |
| Check video files | Rules in a feed checker | Nothing |
| Playback, picture-in-picture, background audio, casting and offline HLS | Nothing | All of it |

## The library parses the video tags but interprets too few

Parsing lacks only `media:subTitle`, and several choices already match what apps learnt in 2026:

- Download plans skip HLS, DASH, torrents and non-HTTP sources, as Pocket Casts does.
- A length of 0 counts as unknown.
- The size estimate from bitrate works. For Mac Geek Gab's MP4, which has no length, bitrate times duration comes within 0.0003% of the size that the server sends ([feed](https://www.macgeekgab.com/feed/podcast/)).

The gaps are in what the library does with the parsed data:

- **An HLS stream without a height counts as audio.** The function `resolve/video?` looks only at the Media RSS medium, an `audio/` or `video/` type and a height. As a result, the HLS alternates of Omny and Buzzsprout feeds count as audio, and an app that filters with it finds no video in those episodes.
- **Height, language and `rel` don't affect the choice.** The enclosure maps keep them, but `resolve/enclosure` takes no maximum height or preferred language. Companion content under another `rel` could also come out as the episode's video.
- **Images lose their type, and there's no poster.** Since `resolve/images` drops the type, a `video/mp4` image looks like a picture. The function `resolve/artwork` picks square art. Nothing picks a 16:9 image or the `poster` purpose, which the namespace added in February 2026 ([PR #729](https://github.com/Podcastindex-org/podcast-namespace/pull/729)). The research found no app that uses the poster token yet.
- **The content link has no accessor.** The item-level `podcast:contentLink` is parsed, but neither `resolve` nor `app.episode/view` gives it.
- **The audio/video choice covers the whole plan.** Download rules are set for each show and sync in the profile. However, the `:enclosure` options of `downloads/plan` apply to every show, and the profile holds no audio/video preference. The play position isn't tied to the file that was played.
- **No file is too big for a plan.** The `:max-bytes` rule limits the total on the device and is unset by default. A plan would therefore download a file like the 44.8 GiB one above.
- **Nothing reads HLS playlists or the video track of an MP4.** The MP4 reader in `format.media` gives the duration, chapters and artwork. It doesn't give the dimensions or codecs, or say whether `moov` comes before `mdat`. Without the playlist, the only bitrate known for a stream is the one in the feed. For Mac Geek Gab, that is the peak of the 720p variant, 1,352,370 bit/s. The playlist's average of 831,345 bit/s puts an episode at about 509 MB rather than 828 MB ([master playlist](https://www.macgeekgab.com/media/cachefly/MGG-Videos/MGG1162-trimmed/master.m3u8)).
- **The feed checker has no video rules.** Beyond status, type, length, ranges, format, duration and integrity, it checks nothing specific to video. It doesn't catch a late `moov`, a playlist that doesn't parse, codecs that differ from the feed's, or missing CORS headers.
- **Analytics doesn't count HLS.** It counts video only when a feed delivers it as files. IAB's guidelines v2.3 also count an HLS download when one listener's segment requests cover a minute of content ([IAB v2.3, §5.5.2](https://github.com/IABTechLab/Podcast-Technical-Measurement/blob/main/PTM-Guidelines_v2.3.md)).
- **The inspector plays video as audio.** It uses an audio element for everything.

## Next steps, ranked

The sizes are rough: small means a day or less, and medium means a few days.

### Small fixes

| | Change | Builds on | Size | Where |
|---|---|---|---|---|
| S1 | Make `resolve/video?` read video codecs (`avc1`, `hvc1`, `hev1`, `av01`, `vp09`, `dvh1`), and return nil for a stream that doesn't say, as `resolve/explicit?` does for an unstated flag. | The height and codecs of alternates (RFC 6381), and the Media RSS medium | Small | Library |
| S2 | Keep the type in `resolve/images`, and add a poster accessor. It picks the `poster` purpose, then a 16/9 image, then the artwork. | The purpose, aspect ratio and type of `podcast:image` | Small | Library |
| S3 | Give the content links of an item or a live item in `resolve` and in the episode view. | `podcast:contentLink`, and PSP proposal #2 | Small | Library |
| S4 | Add a maximum height and preferred languages to `resolve/enclosure`. | The height and language of alternates | Small | Library |
| S5 | Say in the episode view whether an episode has video, and whether that video can be downloaded. | S1 and `resolve/downloadable?` | Small | Library |
| S6 | Add a size limit for each file to the download rules, and allow unknown sizes. | No spec, only Pocket Casts' 500 MB rule | Small | Library. Stopping an oversized transfer is for the app. |
| S7 | Add `media:subTitle` to the registry, and offer it from `resolve/transcript`. | Media RSS 1.5.1 §5.22 | Small | Library |
| S8 | Add Mac Geek Gab, a fresh copy of the Podnews Weekly Review and a PeerTube feed to the corpus. | Nothing | Small | Tests |
| S9 | Play video in a video element in the inspector. | HTML media elements | Small | The bundled inspector |

### Library features

| | Feature | Builds on | Size | Where |
|---|---|---|---|---|
| F1 | An audio/video choice, as a listener preference and as a setting for each show. Both `downloads/plan` and the episode view use it. Audio is the default for episodes that offer both, and the network type is an input. | Per-show download rules, PortCast's rule that a feed's setting beats the global one, and Apple's defaults | Medium | Library. Detecting the network and the toggle for each episode are for the app. |
| F2 | An HLS playlist reader. It gives the variants with `BANDWIDTH`, `AVERAGE-BANDWIDTH`, `CODECS`, `RESOLUTION` and `FRAME-RATE`, and the audio, subtitle and caption renditions. It resolves relative URIs, and reads the duration and VOD status from a media playlist. | RFC 8216, draft-pantos-hls-rfc8216bis-22 §4.4 and RFC 3986 | Medium | Library, with one or two GETs, through the proxy in browsers |
| F3 | Video facts from MP4 files: the width and height, the sample entry and an RFC 6381 codecs string (H.264 first, then HEVC and AV1), and whether `moov` comes before `mdat`. | ISO/IEC 14496-12 (`tkhd` and `stsd`), RFC 6381 §3.3 and the [AV1 ISOBMFF binding](https://aomediacodec.github.io/av1-isobmff/) | Medium | Library |
| F4 | Video rules in the feed checker. It reports a late `moov` as a warning, and checks that playlists are identified and parse. It compares declared codecs and height with the playlist or file, and VOD duration with `itunes:duration`. It also checks CORS on a playlist and a segment, and flags alternates with neither length nor bitrate, and sizes that make no sense. | F2 and F3, Apple's HEAD and range requirements ([Apple](https://podcasters.apple.com/support/823-podcast-requirements)), and HLS draft §4 | Medium | Library |
| F5 | Group alternates by `rel`. Absent, "default" and PSP's "alternate" go with the enclosure, and any other value is companion content. | The namespace's `rel` rule and PSP's demo | Small to medium | Library |
| F6 | Count HLS downloads in `analytics`. | IAB v2.3 §5.5.2, and F2 for segment durations | Medium | Library |
| F7 | Tie the play position to the file it was played in, and compare durations before carrying it to another file. | No spec, since gpodder and PortCast have no field for it | Medium, with a change to the profile | Library |

A few of these need a note:

- **F4.** The codecs check would have caught Mac Geek Gab, whose feed says `hvc1.1.6.L93.B0` and whose playlist says `hvc1.1.6.L93.90` ([master playlist](https://www.macgeekgab.com/media/cachefly/MGG-Videos/MGG1162-trimmed/master.m3u8)). A late `moov` should stay a warning. FFmpeg writes it at the end unless told otherwise ([FFmpeg](https://ffmpeg.org/ffmpeg-formats.html)), but the research found no app bug blamed on it.
- **F5.** This is a judgement call. Taking PSP's value goes beyond the namespace's text, but treating "alternate" as separate content would break every real HLS feed in the research.
- **F7.** The export to gpodder should still use the enclosure URL and guid, whichever file was played.

### What to leave out

- **Playback and platform features.** These are switching sources, picture-in-picture, background audio, casting and showing captions. The library leaves them to apps by design.
- **Offline HLS.** Downloading segments, choosing renditions and joining WebVTT segments into one file is a downloader's job, and Apple's operating systems do it themselves. Neither Pocket Casts nor AntennaPod downloads HLS ([#5430](https://github.com/Automattic/pocket-casts-android/pull/5430); [#6501](https://github.com/AntennaPod/AntennaPod/issues/6501)).
- **Aligning timelines by audio fingerprint.** Pocket Casts does this for chapters ([#5445](https://github.com/Automattic/pocket-casts-android/pull/5445)), but it is audio processing.
- **Apple's HLS API, Spotify's Distribution API and YouTube uploads.** They are partner APIs for hosts, not part of RSS, and Apple's requirements are under NDA.
- **YouTube as a media source.** Channel feeds are Atom, and they link to watch pages rather than to media. The media can only be reached through unofficial front ends such as Invidious ([AntennaPod forum](https://forum.antennapod.org/t/invidious-integration-within-reach/1748)). The library should keep refusing Atom with `:fetch/unsupported-format`, and `podcast:contentLink` covers the link.
- **WebM and DASH readers.** 94% of video feeds use MP4, `video/webm` isn't even in IANA's registry ([IANA](https://www.iana.org/assignments/media-types/media-types.txt)), and no feed in the research used DASH. These are worth another look if the corpus starts to show them.
- **The `timeOffset` attribute.** This proposal would shift transcripts and chapters for an alternate on a different timeline. It has had no answer from a maintainer since November 2025 ([Discussion #733](https://github.com/Podcastindex-org/podcast-namespace/discussions/733)).

### Recommended order

1. Do S1 to S5 and S8 as one batch. They correct how the library reads the feeds that exist today, and the new corpus feeds test them. S6, S7 and S9 can join this batch or wait.
2. Do F1 next. It gives listeners the most visible gain, needs no new I/O, and builds on per-show rules that already sync.
3. Then do F2, and use it in `resolve/video?` and the episode view. For HLS feeds, it replaces the nil answers from S1 with real ones, gives the orientation of the video, and estimates data use from the average bitrate.
4. Do F3 and F4 together, since F4 needs both readers. They serve publishers and anyone who checks feeds.
5. Leave F5 until a feed with companion content turns up. F6 can wait for IAB's v3.0, which is due for public comment in 2027 ([PPC Land](https://ppc.land/iab-tech-lab-finalises-v2-3-podcast-counting-rules-covering-video/)). F7 can wait for evidence that positions drift between files.

## Conclusion

The work left is interpretation, not parsing. In 2026, video in open RSS is shaped less by its specs than by how hosts fill them in: one type spelled four ways, a `rel` value that the namespace doesn't define, and streams with no height or length. Following the specs as written therefore needs a few documented tolerances, namely PSP's `rel`, every HLS type, and nil when the feed doesn't say. It also needs a reader for HLS playlists, which is where the missing facts are published.

The closed platforms limit how much this work is worth. Most watching happens on YouTube, Spotify, Netflix and Apple's partner HLS, none of which appears in a feed, and only about 2% of feeds carry video. That argues for doing the cheap fixes and the per-show choice first, since they help every feed with video, and the format readers after them. It also argues against anything that tries to reach platform video. Two open questions could change the plan: which HLS type the namespace settles on (#776), and what IAB's v3.0 says about video.
