Fixtures
========
`feeds/namespace-example.xml` and the `chapters/` and `transcripts/` examples are
copied verbatim from the podcast-namespace repository
(https://github.com/Podcastindex-org/podcast-namespace). That repository is
dedicated to the public domain under CC0 1.0.

The other two feeds under `feeds/` are hand-written. `quirky.xml` combines many
real-world quirks, and `broken.xml` is malformed in several ways.

`directory/`, `index/` and `podping/` hold recorded answers of the services.

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
of the Open Podcast Analytics Working Group. They come from commit 1344b58
of https://github.com/opawg/user-agents-v2, of 2026-10-09. They keep the
order of their files, with at most three examples each. The list is
Copyright (c) 2022 Open Podcast Analytics Working Group, under the MIT
license. `analytics/op3.json` holds the answers that the API of OP3 gave
about the show Podcasting 2.0 on 2026-09-28, by URL. The hashes of the
addresses of the listeners and their audience ids are replaced with made-up
ones. The list of episodes is cut to three, and the second page of the
downloads to two rows, the last.

`secp256k1/bip340-test-vectors.csv` holds the test vectors of BIP-340,
from https://github.com/bitcoin/bips, under the BSD-2-Clause license.
`nostr/fountain-note.json` holds a note about an episode that the app
Fountain wrote, as `wss://relay.primal.net` gave it on 2026-09-29. The
other events of the tests of Nostr are signed by the tests, with the
secret keys 1, 2 and 3.

`nostr/nip44-vectors.json` holds a part of the test vectors of NIP-44,
from the file `nip44.vectors.json` of https://github.com/paulmillr/nip44,
as it was on 2026-09-29. NIP-44 names that file, with its SHA-256. The
part has 11 of the 35 conversation keys, 5 of the 33 message keys, 5 of
the 10 payloads, and all the other vectors but the lengths of texts that
have no payload. The three vectors under `extended_length` are from the
text of NIP-44 itself. The repository names no license for the file of
vectors.

The corpus of real feeds is outside Git, in `dev-resources/corpus/`. See
`dev/corpus.clj`.
