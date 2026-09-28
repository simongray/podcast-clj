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

The corpus of real feeds is outside Git, in `dev-resources/corpus/`. See
`dev/corpus.clj`.
