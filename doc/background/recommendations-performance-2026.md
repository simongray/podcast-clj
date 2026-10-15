# The performance of recommendations, October 2026

This review measures how long `library/recommend` takes, finds what makes
it slow, and says what was done about it. It was made on 15 October 2026
on an Apple M1 with 16 GB, with JDK 24 and Node 24, while other work kept
the load of the machine at 4 to 5, so a figure varies by about a fifth
from run to run. Unless it says otherwise, a figure is the median, the
90th percentile and the longest of 100 calls, each from a random episode
of the library with the default options.


## The libraries

| Library | Shows | Episodes |
|---|---|---|
| Small: the first 100 feeds of the sweep, the library of `dev/bench.clj` | 99 | 2,170 |
| Medium: the corpus that the inspector recommends from | 31 | 15,587 |
| Large: every feed of the sweep | 573 | 155,070 |

Most listeners have a library of the small or medium kind. The large one
is someone subscribed to hundreds of big shows, and it shows how the time
grows with the library.


## Typical times

With the fixes below:

| Library | JVM | Node |
|---|---|---|
| Small | 5, 11, 107 ms | |
| Medium | 16, 27, 65 ms | 32, 59, 77 ms |
| Large | 61, 114, 211 ms | 165, 279, 388 ms |

In Node, the ClojureScript is compiled without advanced optimizations. A
phone takes longer still. The time grows with the number of
episodes that hold one of the 25 words that the query looks for, e.g. a
median of 38,000 of the 155,070 in the large library, since each is
scored. Choosing the words, matching them and ranking the matches take
about a third of the time each.

Starting from ten episodes takes 68 ms in the large library (81 ms at the
90th percentile), and 62 ms in Node in the medium one. Starting from the
ten that the listener heard last, as the front page does, takes 124 ms
when they've heard 20,000.


## What made it slow

| The large library, on the JVM | Before | After |
|---|---|---|
| One episode | 109, 212, 377 ms | 61, 114, 211 ms |
| One episode, with only episodes under five minutes | 181, 397, 537 ms | 110, 157, 207 ms |
| An episode in a language that few shows use | 359 to 704 ms | 62 to 114 ms |
| Ten episodes | 121, 167, 170 ms | 68, 81, 93 ms |
| The ten heard last, of 20,000 heard | 2,129 ms | 124 ms |

**The history.** To find the episodes heard last, `recommend` built the
entry of every episode in the profile, played mark and archive included,
which took 1.8 s for 20,000 of them. It now sorts the positions by when
they were reported, and builds the entries of the first few only.

**The tests of a result.** Whether a result is an episode, of another
show and of a show in the same language can be told from its id, but the
filter looked up its feed and item first. A lookup builds a map of the
items of the result's feed. For an episode in Turkish or Czech, the
filter rejected tens of thousands of results, and so built a map for
every feed of the library. The tests by id now come first. To make that
possible, `:same-language?` compares the languages that the shows
declare, rather than the languages of each episode's files. A `:language`
among the criteria still checks the files.

**Its own show.** The other episodes of an episode's show matched it
best, since they share the show's words, e.g. its name and its host's
handles. To find ten episodes of other shows, the
ranking read a median of 1,203 results, and up to 7,355. With
`:same-group? false`, new in drop-in-search, the results of its own show
are left out before the ranking, and so are the words that only that show
uses. That also made room for words about the topic, e.g. "gil", "nosql"
and "flask" in place of "talkpython" and "mkennedy" for an episode of
Talk Python. The ranking now reads a median of 27 results.

**The ranking.** To read past the first few results, the ranking sorted
every document found, and again each time it read further. It now sorts
the scores alone, as an array of numbers, and then sorts only the
documents that score at least as high as the last one it needs.

**The number of shows.** The cap of 40% of the shows needs the number of
shows, which was counted from every id of the index on each call, 18 ms
in the large library. The count is now kept with the segments of the
index until they change. On the JVM, the first call after a change hashes
the new segments, which takes 57 ms for the whole of the large library.

**The words of few documents.** To tell whether more than 40% of the
shows use a word, its postings are read until that many shows are seen.
A word in no more documents than that can't be in more shows, so its
postings are no longer read.


## What still takes time

**Many episodes at once.** From 50 episodes, a call takes 300 ms in the
large library, most of it to choose the words. Averaging 50 episodes
favours common words. Some of them, e.g. the "e", "de" and "il" of other
languages, are in many episodes but in fewer shows than the cap, so all
their postings are read to count their shows. Since the episodes of a
show come one after another in an index, the count could skip the rest of
a show once it has seen it. The default of ten episodes takes 68 ms.

**The episodes that the query reads.** All the episodes that hold one of
the words are scored, 38,000 in the large library. Lucene's WAND and
MaxScore skip those that can't reach the best ten, which would be the
next step if libraries that large matter.

**The fields.** With `:fields`, counting the documents of each word reads
its postings, which doubles the time, 141 ms in the large library.
`recommend` doesn't set it.

**ClojureScript.** In Node, a call takes two to three times as long as on
the JVM. In an app with a large library, call `recommend` off the main
thread, e.g. in a web worker, as the inspector refreshes its feeds, and
keep the result for as long as the page shows it.


## How it was measured

The figures come from a REPL with a heap of 8 GB, over libraries built
from the feeds of `dev-resources/corpus/` and its sweep, and from a
scratch test namespace in Node. `(bench/run!)` of `dev/bench.clj` now
times `:recommend` too, from the newest episode of its library.

The fixes are drop-in-search 8cc8b90, released as 0.4.1, and podcast-clj
eee3c28.
