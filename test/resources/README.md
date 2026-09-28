Fixtures
========
`feeds/namespace-example.xml` and the `chapters/` and `transcripts/` examples are
copied verbatim from the podcast-namespace repository
(https://github.com/Podcastindex-org/podcast-namespace). That repository is
dedicated to the public domain under CC0 1.0.

The other two feeds under `feeds/` are hand-written. `quirky.xml` combines many
real-world quirks, and `broken.xml` is malformed in several ways.

`directory/`, `index/`, `podping/` and `webdav/` hold recorded answers of the
services. `encrypted/` holds an encrypted envelope sealed on each platform.

`comments/` holds recorded answers of Mastodon, Misskey, PeerTube, the
Mastodon API and a Bluesky AppView. The recordings keep only the fields
that `comments` reads. Each file is a map from the URL or at URI of a
request to its answer. Some documents are made by hand, for cases that the
recordings lack. These are the authors of the Mastodon thread, and all but
one comment of the PeerTube video. In the Bluesky thread, a label and a
deleted post are made by hand.

`value/blip-0010.json` holds the records of the examples in the bLIP-10
document of the podcast namespace, by app and action.
`value/lightning-addresses.json` holds the answers that six services gave
about their own Lightning addresses on 2026-09-28, by URL. Their pages of
errors are cut short.

`analytics/user-agents.json` holds entries of the open list of user agents
of the Open Podcast Analytics Working Group. They come from commit 0deded5
of https://github.com/opawg/user-agents-v2, of 2026-09-08. They keep the
order of their files, with at most three examples each. The list is
Copyright (c) 2022 Open Podcast Analytics Working Group, under the MIT
license. `analytics/op3.json` holds the answers that the API of OP3 gave
about the show Podcasting 2.0 on 2026-09-28, by URL. The hashes of the
addresses of the listeners and their audience ids are replaced with made-up
ones. The list of episodes is cut to three, and the second page of the
downloads to two rows, the last.

The corpus of real feeds is outside Git, in `dev-resources/corpus/`. See
`dev/corpus.clj`.
