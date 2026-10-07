# Search design, October 2026

This report is the basis for a generic search mini-library in podcast-clj,
written on 6 October 2026. It records what the search does today, what
other libraries and engines do, the standards and papers to build on, the
design, the tests, the batches to build it in, and the decisions that are
still open. Facts about other software were checked on the primary source
(its documentation or code) unless marked *(unverified)*. Numbers were
measured on this machine (OpenJDK 24.0.1, Node 24.1.0 with ICU 77.1).

The goal, in Simon's words: lean on common search algorithms and data
structures rather than invent, make a generic mini-library that other
projects can reuse (as `util.crdt` is for CRDTs), build the podcast search
on top of it, and fix the existing flaws on the way.


## 1. What search does today

Search lives in three places:

- `search` (`src/dk/simongray/podcast/search.cljc`, 980 lines) holds the
  full-text index and the criteria for filtering and ordering episodes.
- `directory/search!` asks Apple Podcasts, fyyd, gpodder.net and the
  Podcast Index for shows. It's not part of this work, apart from one
  docstring fix (F12).
- `app.library` wraps both: `library/search` queries the index that a
  library keeps when its config has `:search? true`, and `library/episodes`
  applies the criteria across all feeds.

### 1.1 The full-text index

A document is a map of `:id`, `:fields` and `:stored`. Each episode gets
one from `item-document`, with the fields `:title`, `:show`,
`:description` (the notes as text), `:people`, `:keywords`, `:chapters`
(the chapter titles) and `:transcript`; each show gets one from
`feed-document`, with `:title`, `:description`, `:author`, `:people` and
`:categories`.

The index is a plain map that prints as EDN:

```clojure
{:docs     {id {:stored {…} :lengths {field n} :terms [term …]}}
 :postings {term {id positions}}   ; one position, or a vector of them
 :terms    #{…}                    ; a sorted set, for prefix search
 :fields   {field {:no 0 :docs 12 :length 345}}}
```

A position is `field-number × 1000000 + i`, so the positions of one term
in one document are one vector, and two positions one apart are always in
the same field. A field longer than a million terms is cut there.

Analysis is `fold` (lower-case, NFD, combining marks stripped, a map for
æ ø œ ß ð þ ł đ ħ ı ŋ ŧ) and `tokens`, the runs of
`(?u)[\p{L}\p{M}\p{N}]+` in the folded text. A field given as a vector of
strings is taken as terms already, which is the hook for a stemmer of
your own.

A query is a string or a vector of terms. Every word must occur (or any
with `:match :any`), the last word is a prefix unless the query ends in
whitespace, words in quotes must occur in a row, and words joined by
punctuation (`2.0`) are a phrase. Ranking is BM25 per field with Lucene's
k1 = 1.2 and b = 0.75, the field scores summed with the boosts
`{:title 3 :show 2 :author 2 :people 2 :chapters 2 :keywords 2
:categories 1 :description 1 :transcript 1}`. A prefix scores by the best
of its expansions. Results are `{:id :score :stored}`, best first, equal
scores by id, with `:limit`, `:where` (a predicate of a result) and
`:rank` (a sort key instead of the score). For a result list, `snippet`
cuts a window of a text around the first matching words and marks them.

### 1.2 The criteria

The criteria are a map that `where` turns into a predicate of a feed and
an item: `:season`, `:type`, `:duration`, `:published`, `:age`,
`:transcript?`, `:chapters?`, `:explicit?`, `:person`, `:text`, `:not`,
and the criteria about the listener, `:status`, `:starred?`, `:inbox?`,
`:rating` and `:since-subscription?`, which need the profile. The orders
are `:listening`, `:newest`, `:oldest`, `:shortest`, `:longest`, `:title`
and `:recent`, whose sort keys `order` gives. Both apply to one feed or
many through `episodes`, and `problem` checks criteria saved by a newer
version. These stay as they are.

### 1.3 Who uses what

| Function | Used by |
|---|---|
| `index`, `add`, `remove`, `query` | `app.library` (make, reindexed, search), the inspector's service, `doc/building-an-app.md` |
| `item-document`, `feed-document` | `app.library`, the inspector's service, the docs |
| `snippet` | the inspector's page, the docs |
| `episodes`, `where`, `order`, `orders` | `app.library/episodes`, `downloads/plan`, the inspector's feed page, the docs |
| `problem` | `downloads`, `profile` (saved filters), the docs |
| `sorted-by` | `downloads`, three times |
| `fold`, `tokens`, `spans` | the tests only, and `fold` inside `:person` and the `:title` order |

The tests are `test/dk/simongray/podcast/search_test.cljc` (11 deftests,
both platforms) and `test/inspector/service_test.clj` (the search page).

### 1.4 Baseline on the corpus

Measured on the whole corpus in `dev-resources/corpus` (611 feeds, 168,920
episodes, 168,566 documents since episodes without a guid share one id per
feed), with the script in Appendix B. One run; a second run was within 15%
on every line.

| Measure | Value |
|---|---|
| Index build | 27.2 s |
| Terms, postings | 398,133 terms, 16,543,863 postings |
| `clojure` (rare word) | 0.16 ms |
| `"value for value"` (phrase) | 9.4 ms |
| `podcast episode new` | 138 ms |
| `value for va` | 160 ms |
| `the` | 378 ms |
| `th` (prefix) | 436 ms |
| `a` (one-letter prefix) | 1.19 s |
| `s` (one-letter prefix) | 1.57 s |
| Re-adding 50 documents | 37 ms |
| Removing 50 documents | 7.5 ms |

A listener's library of 50 shows is about 10,000 episodes, 15 times
smaller, so every query above lands under 100 ms there. The slow cases
are those that score most of the postings: a common word, or a short
prefix with thousands of expansions.

The corpus has one Chinese feed (`sweep/7001796.xml`, `zh-cn`) and two
Japanese ones (`sweep/8045872.xml`, `sweep/7661378.xml`). Indexed together
(72 episodes, 2,867 terms), the queries `中秋` and `月饼` find nothing,
although both words are in the title `爸爸不在家的中秋节` and in a title
about mooncakes. The query `爸爸` finds that title, but only because it
starts the run. A run of Chinese text is one term today.


## 2. The flaws

| Id | Flaw | Cause | Fixed in |
|---|---|---|---|
| F1 | A word inside a Chinese or Japanese sentence can't be found | A run of CJK text is one term | `util.text/tokens`, bigrams |
| F2 | `-rerun` requires the word instead of excluding it | The parser knows no minus | `util.search/parse` |
| F3 | `don't` is `don`, `t`; `2.0` is a phrase of `2` and `0` | The tokenizer splits at every punctuation mark | `util.text/words`, UAX #29 |
| F4 | `Ｐｏｄｃａｓｔ` (fullwidth, common in Japanese text) doesn't match `podcast`; `ﬁ` doesn't match `fi` | NFD, not NFKD | `util.text/fold` |
| F5 | A word in three fields outranks a word three times in one; `ΧΑΟΣ` doesn't match `χαος` | BM25 saturates per field; lower-casing gives a final sigma | BM25F; `fold` maps ς to σ |
| F6 | For `cloj`, one `clojurians` outranks five `clojure` | A prefix scores by its best expansion, and the rarest wins | prefix expansions as one term |
| F7 | A quoted phrase is highlighted word by word; the window is cut at the first hit even when a later window shows more of the query | `snippet` reads terms only | `util.search/snippet` |
| F8 | An index read back from EDN scans every term for a prefix | The sorted set comes back as a hash set | `util.search/restore` |
| F9 | `query` can't take criteria such as `{:status :unplayed}` | Its `:where` sees only the result | `library/search` with `:where` |
| F10 | `library/episodes` with `:text` reads the notes of every episode on every call | The criterion tokenizes each item | the `:text` criterion uses the index when there is one |
| F11 | No search inside a transcript with the time of each hit | Nothing reads segments | `transcript/matches` |
| F12 | `directory/search!` says `:language` filters the Podcast Index search; its search call has no such parameter | Docstring | `directory` |

Two more things are not flaws but were checked: the ids of results compare
by hash so that the order is the same on both platforms (kept), and
`sorted-by` belongs with the criteria and stays in `search`.


## 3. What exists elsewhere

### 3.1 Clojure and ClojureScript

There is no full-text search library that runs from `.cljc` on both
platforms. Clojars, cljdoc and GitHub were searched for full-text, BM25,
inverted index, tokenizer, stemmer and fuzzy.

| Library | Platform | What it is | Status |
|---|---|---|---|
| [clj-fuzzy](https://github.com/Yomguithereal/clj-fuzzy) | cljc | String metrics (Levenshtein, Jaro-Winkler, Dice…), phonetics, the Porter, Lancaster, Lovins and Schinke stemmers. No index. | 0.4.1 of 2017; the README says "Consider this library deprecated for JavaScript" |
| [caponia](https://github.com/mattdw/caponia) | clj | In-memory index `{stem {doc weight}}` on the Porter stemmer; AND/OR; no positions, phrases or prefixes | 0.3.4 of 2023 |
| [memsearch](https://github.com/coderafting/memsearch) | cljs | Index by Soundex code, scored by Jaro-Winkler × frequency | 0.1.1 of 2022 |
| [ballpark](https://github.com/jgdavey/ballpark) | cljc | QuickSilver-style fuzzy ranking of a collection; no index | 0.2.1, pushed 2026 |
| [datalevin](https://github.com/juji-io/datalevin/blob/master/doc/search.md) | JVM | A real search engine on LMDB: inverted lists, positions, phrases, custom analyzers, boolean queries; scoring "lnu.ltn", not BM25 | 1.1.0, active |
| clucie, lucene-clj, clucy | JVM | Lucene wrappers | — |
| stemmers, snowball-stemmer | JVM | Porter; a Snowball wrapper | — |

What this means: we write the library ourselves, as with `util.crdt`, and
lean on existing solutions by implementing the published algorithms and
citing them. clj-fuzzy is a readable source for a Porter stemmer or a
Levenshtein distance, should either be wanted.

### 3.2 JavaScript libraries, as design references

| Library | Ranking | CJK | Prefix, fuzzy | Query | Index |
|---|---|---|---|---|---|
| [MiniSearch](https://github.com/lucaong/minisearch) 7.2 | BM25+ per field, summed with boosts | None (one token per run); an open issue since 2023 | Radix tree; fuzzy by an edit budget on the tree, weighted 0.45 | A string, or nested `{queries, combineWith}` as data | add, remove, replace; JSON |
| [Orama](https://github.com/oramasearch/orama) 3.1 | BM25+ per property, boosts | `Intl.Segmenter` per language, experimental | Radix tree; `tolerance` = bounded Levenshtein | As data | insert, remove; JSON and binary |
| [FlexSearch](https://github.com/nextapps-de/flexsearch) 0.8 | Its own "resolution" and context scoring, not BM25 | One token per character | Forward tokenizer indexes prefixes; "tolerant" mode | A string; a resolver API with and/or/not | add, update, remove; export |
| [Lunr](https://github.com/olivernn/lunr.js) 2.3 | BM25, Lucene-style syntax (`field:`, `^`, `+`, `-`, `~n`, `*`) | lunr-languages: `Intl.Segmenter` words plus bigrams over each Han run | Token-set automata | A string | No updates after build; JSON |
| Fuse.js, uFuzzy | Bitap or regex over the whole collection | Substrings, any script | — | — | No inverted index |
| [Pagefind](https://pagefind.app) 1.5 | Its own | Dictionaries (jieba, lindera, Thai) through charabia | Snowball stemming per language | — | Static, chunked |

The libraries that run in a browser without a dictionary either leave CJK
unsearchable (MiniSearch), index single characters (FlexSearch) or add
bigrams (lunr-languages). Where they have a dictionary, it's the
platform's (`Intl.Segmenter`), whose output differs between engines and
ICU versions.

### 3.3 Engines on CJK without a dictionary

The dictionary-free practice is the same across engines: overlapping
character bigrams over each maximal run of CJK characters.

- Lucene's [CJKBigramFilter](https://lucene.apache.org/core/9_11_0/analysis/common/org/apache/lucene/analysis/cjk/CJKBigramFilter.html)
  bigrams Han, Hiragana, Katakana and Hangul by default, across scripts
  within a run. "By default, when a CJK character has no adjacent
  characters to form a bigram, it is output in unigram form", and
  `outputUnigrams` emits both. A run ends at any gap: space, punctuation,
  a non-CJK token. Lucene's `CJKAnalyzer` chains StandardTokenizer, width
  folding, lower-casing, this filter and English stop words.
- Elasticsearch's [cjk_bigram](https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-cjk-bigram-tokenfilter.html)
  shows it: `東京都は、日本の首都であり` becomes `東京 京都 都は 日本 本の
  の首 首都 都で であ あり`, with the break at the comma and bigrams across
  Han and kana.
- [Groonga's TokenBigram](https://groonga.org/docs/reference/tokenizers/token_bigram.html)
  also emits the last character of a run as a unigram (`日本語の勉強` →
  `日本 本語 語の の勉 勉強 強`) and answers a one-character query by
  prefix search over the tokens. Its documented cost: "you can find
  `world` by `or`".
- Bleve, Xapian (`FLAG_NGRAMS`: unigrams with positions plus bigrams, over
  CJK, Thai, Lao, Myanmar and Khmer), MySQL's ngram parser (n = 2),
  pg_bigm and Manticore (unigrams) do the same family of things.
- At query time the bigrams of a run go in as a phrase (MySQL boolean
  mode, Manticore, Xapian), or as an OR with less precision (Lucene's
  classic parser with `autoGeneratePhraseQueries` off, MySQL natural
  mode).

The known caveats: substring semantics (`京都` matches in `東京都`), more
terms per document, and no help for Thai, Lao, Khmer and Myanmar in
Lucene, which leaves those to the ICU dictionary.

### 3.4 The two platforms' own segmenters

Both platforms ship a word segmenter, and they disagree. Measured on this
machine on titles from the corpus:

| Text | JDK `BreakIterator` (root locale) | Node `Intl.Segmenter` (ICU 77) |
|---|---|---|
| `爸爸不在家的中秋节` | one word | `爸爸 不在家 的 中秋 节` |
| `本番データ消去、AIメモリ管理 ほか` | `本番 データ 消去 AI メモリ 管理 ほか` (splits at script changes only) | the same |
| `ってしまうのか` | one word | `って しまう の か` |
| `don’t stop 2.0 1,000 e-mail São_Paulo` | `don t stop 2.0 1,000 e-mail São_Paulo` | `don’t stop 2.0 1,000 e mail São_Paulo` |
| Thai `ภาษาไทยง่ายนิดเดียว` | one word (root), `ภาษา ไทย ง่าย นิดเดียว` (Thai locale) | by dictionary |

The JDK's iterator is the 1996 Taligent rule set with a dictionary for
Thai only; ICU has dictionaries for Chinese, Japanese, Thai, Lao, Khmer
and Burmese, and the output depends on the ICU version an engine ships.
An index built on one platform must answer queries tokenized on the
other, and the terms must be the same on a phone and a server, so neither
segmenter can be the default. Our own tokenizer with bigrams is
deterministic everywhere.


## 4. The standards and algorithms to build on

### 4.1 Words: UAX #29

[Unicode Standard Annex #29](https://www.unicode.org/reports/tr29/)
defines default word boundaries. The rules that matter to a search
tokenizer, from table 3 in §4.1:

- WB5: don't break between letters.
- WB6, WB7: don't break letters across MidLetter or MidNumLet characters
  with a letter on both sides, e.g. `don't`, `e.g`, `example.com`.
  MidLetter is the colon, the middle dot and a few others; MidNumLet is the
  full stop, the quotes U+2018 and U+2019 and a few others; Single_Quote
  is the ASCII apostrophe.
- WB8, WB9, WB10: don't break between digits, or between a letter and a
  digit (`3a`, `A3`).
- WB11, WB12: don't break digits across MidNum or MidNumLet characters
  with a digit on both sides, e.g. `3.2`, `3,456.789`. MidNum is the
  comma, the semicolon and a few others.
- WB13, WB13a, WB13b: don't break between Katakana, and don't break from
  ExtendNumLet (the underscore).
- WB999: otherwise break everywhere, "including around ideographs".

Han ideographs, Hiragana and the South East Asian scripts are not letters
under these rules, so each character is its own segment. §4 says these
scripts need tailoring, and the conformance text says reliable word
boundaries in Thai, Lao, Chinese or Japanese "requires the use of
dictionary lookup or other mechanisms". Hyphens are not MidLetter, so
`out-of-the-box` is four words. Lucene's StandardTokenizer implements
these rules and emits one token per ideograph, which CJKBigramFilter then
pairs up.

### 4.2 Folding

- [UAX #15](https://www.unicode.org/reports/tr15/): NFD splits a letter
  from its diacritics; NFKD also folds compatibility forms, e.g. `ﬁ` to
  `fi`, fullwidth `Ｐ` to `P`, `①` to `1`. Removing the combining marks
  after decomposition is an application step the annex doesn't define.
- Letters with a stroke or bar (ø, đ, ł, ħ) have no decomposition. The
  withdrawn [UTR #30](https://www.unicode.org/reports/tr30/tr30-4.html)
  defined diacritic folding that covers them, and Lucene's
  ASCIIFoldingFilter has tables for the same.
- [CaseFolding.txt](https://www.unicode.org/Public/UCD/latest/ucd/CaseFolding.txt)
  (Unicode chapter 3, §3.13): full case folding maps ß to ss, final sigma
  ς to σ, the Kelvin sign to k. Plain lower-casing on both platforms gives
  `χαος` with a final ς for `ΧΑΟΣ`, so σ and ς must be folded explicitly.
  The Turkic mappings (dotless i) are locale-specific and excluded by
  default; `İ` folds through NFKD to `i` plus a mark, which is stripped.
- Lucene's ICUFoldingFilter is the full treatment: all UTR #30 foldings,
  case folding and NFKC. CJKWidthFilter folds fullwidth ASCII and halfwidth
  Katakana, which NFKD also does.

### 4.3 Ranking: BM25 and BM25F

The reference is Robertson and Zaragoza, *The Probabilistic Relevance
Framework: BM25 and Beyond*, Foundations and Trends in IR 3(4), 2009
([PDF](https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf)).

BM25 (eq. 3.15): a term's weight in a document is

```
w = idf · tf / (k1 · ((1 − b) + b · dl / avdl) + tf)
```

with `dl` the document length, `avdl` the average, k1 the saturation and b
the length normalization. The document's score is the sum over the query
terms. A factor (k1 + 1) in the numerator doesn't change the ranking
(§3.5.1); Lucene dropped it.

Lucene's [BM25Similarity](https://lucene.apache.org/core/9_11_1/core/org/apache/lucene/search/similarities/BM25Similarity.html)
uses k1 = 1.2, b = 0.75 and

```
idf = ln(1 + (N − n + 0.5) / (n + 0.5))
```

with N the number of documents and n the number that hold the term. A
phrase's idf is the sum of its terms' idfs.

BM25F (§3.6) is for documents with several fields. Scoring each field
separately and summing "seems a little unreasonable", since the saturation
then happens once per field; the paper says to combine "first streams,
then terms", and apply the saturation "to the total evidence for each
term". With a length normalization per field (eq. 3.19 to 3.21):

```
B_s   = (1 − b_s) + b_s · sl_s / avsl_s          ; field s of the document
tf~   = Σ_s v_s · tf_s / B_s                      ; v_s is the field's boost
w     = idf · tf~ / (k1 + tf~)
```

where `sl_s` is the length of field s in the document and `avsl_s` its
average over the collection. The idf is computed over the whole collection,
not per field. With integer boosts this equals plain BM25 on a document
whose fields are repeated v_s times (§3.6.4). Lucene's CombinedFieldQuery
and SQLite FTS5's `bm25()` with column weights implement this idea.

Prefix expansions: scoring each expanded term on its own makes the rarest
expansion rank highest, "often not useful", so Lucene's
[BlendedTermQuery](https://github.com/apache/lucene/blob/main/lucene/core/src/java/org/apache/lucene/search/BlendedTermQuery.java)
scores all expansions with one blended document frequency (the maximum).
The classic query parser gives prefix queries a constant score instead.

### 4.4 Top-k

Lucene's TopScoreDocCollector keeps the best k hits in a bounded priority
queue, ties broken by document id. Dynamic pruning (WAND, Broder et al.
2003; Block-Max WAND, Ding and Suel 2011; MaxScore, Turtle and Flood
1995) skips postings that can't reach the current k-th score. It needs
per-term score bounds and pays off when posting lists are long compared
with k, i.e. hundreds of thousands of postings. At a library's scale,
scoring every matching posting into a k-sized structure is enough.

### 4.5 Query syntax

The syntaxes converge on three things: double quotes for a phrase, a
leading minus to exclude, and a trailing asterisk for a prefix.

- Elasticsearch's [simple_query_string](https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-simple-query-string-query):
  `+` AND, `|` OR, `-` negates, `"` phrase, `*` at the end of a term is a
  prefix, `( )` precedence, `~N` fuzziness or slop. The default operator is
  OR. It "does not return errors for invalid syntax. Instead, it ignores
  any invalid parts of the query string".
- [SQLite FTS5](https://www.sqlite.org/fts5.html#full_text_query_syntax):
  barewords and `"phrases"`, implicit AND, `*` prefix, `^` first token,
  `col:`, `NEAR`, `NOT`/`AND`/`OR`.
- Xapian's QueryParser: `+`/`-` as love/hate, phrases, `NEAR`, field
  prefixes, a trailing `*` with `FLAG_PARTIAL` that treats the last word as
  a prefix "unless it is followed by whitespace", expanding to the 100 most
  frequent terms.
- Lucene's classic parser and lunr add `field:`, `^boost` and `~N`.

Queries as data: Elasticsearch's bool query with `must`, `should`,
`must_not` and `filter` (no score); Lucene's `BooleanClause.Occur`;
MiniSearch's nested `{queries, combineWith}`.

### 4.6 Snippets

[FTS5's snippet()](https://www.sqlite.org/fts5.html#the_snippet_function)
picks the window that holds the most distinct query terms, with a
preference for the start of a column and for sentence starts, and
`highlight()` inserts "a single open and close marker" for each set of
overlapping phrases. Lucene's UnifiedHighlighter scores passages with a
BM25-like formula and its DefaultPassageFormatter merges overlapping hits
into one span. Both re-tokenize the text to find the offsets rather than
store them. CJK bigram hits overlap by design and are merged the same way.

### 4.7 Fuzzy matching, for the optional batch

- Lucene's FuzzyQuery intersects a Levenshtein automaton (Schulz and
  Mihov 2002) with the sorted term dictionary: at most 2 edits,
  transpositions count as one, an optional exact prefix, at most 50
  expansions, each expansion's weight scaled by `1 − edits / length`
  (FuzzyTermsEnum), and the blended rewrite above.
- Elasticsearch's [fuzziness AUTO](https://www.elastic.co/docs/reference/elasticsearch/rest-apis/common-options#fuzziness):
  no edits for terms of 1 or 2 characters, one for 3 to 5, two above 5.
  "AUTO should generally be the preferred value".
- MiniSearch and Orama walk a radix tree with an edit budget; SymSpell
  precomputes deletion neighbourhoods; pg_trgm and FTS5's trigram
  tokenizer use n-grams. With a sorted term set and no tree, the simple
  exact method is a bounded Levenshtein over the terms that share the
  query's first character.


## 5. Design

### 5.1 Namespaces

Two generic namespaces at the bottom with the other `util` namespaces,
which know nothing about podcasts (the architecture test enforces that
they require nothing above them):

- `dk.simongray.podcast.util.text`: text analysis on both platforms, with
  `fold`, `words`, `tokens` and `spans`.
- `dk.simongray.podcast.util.search`: the index and the queries, with
  `index`, `add`, `remove`, `restore`, `parse`, `query`, `snippet`, `k1`
  and `b`.

The podcast namespace `search` keeps `item-document`, `feed-document`,
`boosts`, `index` (see D2), `episodes`, `where`, `order`, `orders`,
`problem` and `sorted-by`. What moves:

| From `search` | To |
|---|---|
| `ligatures`, `marks`, `decompose`, `marked?`, `fold`, `word`, `tokens`, `spans`, `spaced`, `words` | `util.text` |
| `span`, `index`, `remove`, `analyze`, `numbered`, `positions`, `add`, `k1`, `b`, `idf`, `at-positions`, `field-counts`, `weighing`, `weight`, `expansions`, `best-scores`, `term-scores`, `phrase-counts`, `phrase-scores`, `clauses`, `clause-scores`, `matching`, `compare-ids`, `better`, `kept`, `best`, `query`, `snippet` | `util.search` |

Each namespace docstring lists its sources as `util.crdt` does, and each
part cites its source in a short comment where it departs from it
(section 5.6).

### 5.2 util.text

**`fold`**: the string lower-cased (`Locale/ROOT` on the JVM), NFKD
decomposed, its combining marks removed, and the letters without a
decomposition mapped: æ→ae, ø→o, œ→oe, ß→ss, ð→d, þ→th, ł→l, đ→d, ħ→h,
ı→i, ŋ→n, ŧ→t, and ς→σ. Keep the fast path: check for marks with the
`marked?` loop on the decomposed string and skip the regex when there are
none. A string that is all ASCII can skip NFKD too; measure whether that
pays on the longest corpus texts.

Cite UAX #15 for NFKD, Lucene's CJKWidthFilter for the width folding it
gives, UTR #30 and ASCIIFoldingFilter for the stroke letters, and
CaseFolding.txt for ß and ς.

**`words`**: the words of a string as UAX #29 gives them, in a simplified
form. The classes:

- U, the characters of scripts without spaces between words: Han,
  Hiragana, Katakana and U+30FC, Hangul, Thai, Lao, Khmer, Myanmar
  (`\p{sc=Han}` etc., which both platforms support; see D6 for the last
  four).
- W, a word character: a letter, mark, digit or underscore that isn't in
  U.
- J, a joiner: `'` `’` `‘` `.` `:` `·` `,` `;`.

A word is a run of U characters, or a run of W characters with single J
characters between W runs, as one regex with the alternation in that
order and a negative lookahead that keeps U characters out of W:

```
(?u)U+|W+(?:JW+)*
```

Run `re-seq` on it; the pattern has no nested quantifier, so it can't
backtrack badly. Marks at the start of a word are a non-issue after
folding. Deviations from the annex, to name in the docstring: one joiner
class for letters and digits (`a:1` stays one word, where WB6 to WB12
would split it); no handling of Format and ZWJ characters, Hebrew quotes,
regional indicators or emoji; Hangul is bigrammed with the CJK scripts
although the annex treats it as letters, as Lucene does.

A word is kept as the original text; `spans` adds the offsets.

**`tokens`**: the terms of a string for an index or a query: `fold`, then
`words`, then each run of U characters replaced by its bigrams. For a run
of n characters:

- n = 1: the character.
- n ≥ 2: the n − 1 overlapping bigrams, then the last character as a
  unigram, which shares the position of the last bigram.

Bigrams are formed over code points, so a character outside the Basic
Multilingual Plane (CJK Extension B) isn't split into surrogates.

Examples: `爸爸不在家的中秋节` → `爸爸 爸不 不在 在家 家的 的中 中秋 秋节 节`;
`AIメモリ管理` → `ai メモ モリ 管理 理` with `ai` as one word; `好` → `好`.

Positions: `tokens` returns the terms as a vector, and `add` numbers them
in order, except that the trailing unigram of a run takes the position of
the bigram before it. The simplest way: `tokens` returns the terms, and a
second function `positioned` (or a flag) gives `[term position]` pairs for
the index; `add` uses that. Choose at implementation; a vector of terms
with the unigram's position implied is enough if `add` can tell a trailing
unigram apart (one U character right after a bigram that ends in it).

Why the shared position: with its own position, the unigram would sit
between the run and the next word, so a quoted phrase across a run and
the next word (`"天气很好 good"`) would never match. Lucene's
`outputUnigrams` emits unigrams at position increment 0 for the same
reason. Why the trailing unigram at all: a one-character query `好` is a
prefix query `好*`, which finds every bigram that starts with 好, but a
character at the end of a run starts no bigram (Groonga's reason).

Cite Lucene's CJKBigramFilter, Elasticsearch's cjk_bigram and Groonga's
TokenBigram for the rules, UAX #29 §4 for the scripts that need tailoring,
and Xapian's word-breaker for the South East Asian scripts.

**`spans`**: the words of the original text as maps of `:start`, `:end`
and `:term`, where the term is the folded word, and a run of U characters
gives one span per bigram (and per lone character) with its own offsets.
The terms of `spans` equal those of `tokens` for the same text except
where NFKD turns a symbol into letters or digits (`™`, `①`), which only
matters for highlighting. Its only caller is `snippet`.

### 5.3 util.search: the index

The map keeps its shape (section 1.1). With D2, `index` takes options:

```clojure
(index)                       ; empty
(index docs)                  ; of these documents
(index docs {:boosts {…}})    ; the boosts that query uses by default
```

and stores `:boosts` in the map. Both `add` and `remove` stay as they
are, with `add` taking the positions from `util.text` as described above
and keeping the one-string-per-term interning. The rule that a vector is
terms already and any other collection has each string tokenized stays in
`analyze`.

For an index read back from EDN or Transit, `restore` sorts `:terms`
again, i.e. `(update index :terms #(into (sorted-set) %))`. The scan
fallback of `query` for an unsorted set stays, so an index works either
way, only slower. The docstring of `index` points to `restore`.

### 5.4 util.search: parse

A query string becomes clauses as data through `parse`, and `query`
takes either. A clause is one of:

```clojure
{:term "clojure"}
{:term "cloj" :prefix? true}
{:phrase ["value" "for" "value"]}
{:not clause}                       ; a term, prefix or phrase
```

The grammar, lenient like simple_query_string (nothing is an error):

- The string is split into items at Unicode whitespace (all of it, since
  `\s` means ASCII whitespace on the JVM; keep the `spaced` fix).
- An item that starts with `"` runs to the next `"`, or to the end of the
  string when there is none. Its tokens become a phrase; one token is a
  term; none, nothing.
- Any other item is a word. Its tokens become a term if one, a phrase if
  several (`value-for-value`, or a CJK run, whose bigrams must occur in a
  row). A trailing `*` outside quotes marks a term as a prefix; on a
  phrase it's ignored.
- A `-` right before an item wraps the clause in `:not`. A lone `-` or an
  empty quoted item gives nothing.
- A run of one U character is a prefix term even when it isn't last
  (Groonga's rule for one-character queries).
- The last clause becomes a prefix when it's a term, the `:prefix?` option
  holds (default true), the term has at least `:min-prefix` characters
  (default 1), and the string ends neither in whitespace nor in a quote.
- A query given as a vector of strings is one `:term` per string, taken as
  is, as today.

Examples:

```clojure
(parse "value for va")
;; => [{:term "value"} {:term "for"} {:term "va" :prefix? true}]
(parse "\"value for value\" -rerun")
;; => [{:phrase ["value" "for" "value"]} {:not {:term "rerun"}}]
(parse "pod* network")
;; => [{:term "pod" :prefix? true} {:term "network" :prefix? true}]
(parse "天气很好")
;; => [{:phrase ["天气" "气很" "很好"]}]
(parse "好")
;; => [{:term "好" :prefix? true}]
(parse "\"value for")
;; => [{:phrase ["value" "for"]}]
(parse "-")
;; => []
```

### 5.5 util.search: query

```clojure
(query index q)
(query index q opts)
```

The query `q` is a string, a vector of terms or clauses from `parse`.
The options:

- `:limit`, the most results.
- `:match`, `:all` (default) or `:any`, for the positive clauses.
- `:prefix?` and `:min-prefix`, as `parse` takes them.
- `:boosts`, the field weights, over the index's own (D2) or 1 for every
  field; 0 leaves a field out.
- `:k1` and `:b`, over `k1` 1.2 and `b` 0.75.
- `:filter`, a predicate of a result to keep it (today's `:where`,
  renamed because the podcast namespaces use `:where` for criteria).
- `:rank`, a function of a result to order by instead of the score.

The algorithm:

1. Clauses from `q`. The positive clauses score; the `:not` clauses only
   exclude. No positive clause means no results.
2. For each positive clause, the field counts per document, a map of id to
   `{field-number tf}`:
   - a term: from its postings, as `field-counts` does today;
   - a prefix: the expansions from the sorted terms (`subseq`), their
     field counts merged per document, so the prefix is one term whose
     occurrences are all its completions;
   - a phrase: `phrase-counts` today (consecutive positions in one
     field).
3. Each clause's idf with n = the number of documents in its map; for a
   phrase, the sum of its terms' idfs with their own document counts.
   This is where the prefix fix (F6) lands: one n for all expansions, the
   exact count of documents that hold any of them, where Lucene blends by
   the maximum.
4. Each document's weight for the clause by BM25F:
   `tf~ = Σ_s v_s · tf_s / B_s` over its fields, with
   `B_s = (1 − b) + b · sl_s / avsl_s`, `sl_s` from the document's
   `:lengths` and `avsl_s` = `:length` / `:docs` of the field (1 when the
   field has no documents); then `idf · tf~ / (k1 + tf~)`. Fold the
   constants per field once per query, as `weighing` does today. A field
   with boost 0 contributes nothing and a document that matches only
   there is dropped.
5. Candidates: the intersection of the clause maps (iterate the smallest)
   or the union with `:match :any`, minus every id in a `:not` clause's
   map.
6. A result is `{:id :score :stored}` with the score the sum of the
   clause weights. Apply `:filter`, then order with `better` (score
   descending, then id as `compare-ids` orders it, the same on both
   platforms) or `:rank`, and take `:limit` with `best` and `kept` as
   today. A binary heap is the upgrade if a measurement asks for it.

Drop the (k1 + 1) factor; it changes scores, not order.

### 5.6 util.search: snippet

```clojure
(snippet text q)
(snippet text q {:width 160})
```

1. Clauses from `q` with `parse` (or clauses given); only the positive
   ones.
2. The spans of the text, from `spans`.
3. Hits: a span whose term equals a term clause or starts with a prefix
   clause; for a phrase, a run of consecutive spans whose terms equal the
   phrase's, as one hit from the first span's start to the last span's end.
   Each hit remembers its clause.
4. The window: candidates start at the text start and at each hit, moved
   left by a third of the width and then to the nearest span start, as
   today. A candidate's score is the number of distinct clauses with a hit
   inside it; the best wins, the earliest on a tie. With no hits, the
   window is the start of the text. Cut both ends at span boundaries, and
   show a hit wider than the window whole, as today.
5. The parts: `{:text}` pieces with `:match? true` on the hits, where
   overlapping or touching hits (CJK bigrams, a phrase and one of its
   words) merge into one part; `{:text "…"}` where the window cuts the
   text.

The existing `snippets` test keeps every case; the window test with
`:width 30` may pick a different window once windows are scored, which is
the intended change if so.

### 5.7 Deviations from the sources

The namespace docstrings and the comments say where the code departs from
what it cites:

1. In `words`, only WB5 to WB13 with one joiner class, and nothing of
   the Extend, Format, ZWJ, Hebrew, regional indicator and emoji rules.
2. The trailing unigram of a run is Groonga's, not Lucene's, and it shares
   the last bigram's position as Lucene's `outputUnigrams` does; Thai,
   Lao, Khmer and Myanmar are bigrammed as Xapian does, where Lucene uses
   a dictionary.
3. A prefix is one term with the exact document count of its expansions,
   where Lucene blends by the maximum document frequency.
4. BM25F with one b for all fields; the paper allows one per field.
5. In `fold`, NFKD where Lucene's ICUFoldingFilter uses NFKC, the stroke
   letters by a small table instead of UTR #30's data, and case folding
   only for ß and ς.
6. No stop words, where Lucene's CJKAnalyzer drops English ones; the idf
   takes care of them.
7. In `snippet`, windows score by distinct clauses only; FTS5 also
   prefers the start of a column and sentence starts.

### 5.8 The podcast namespaces

**`search`**

- `index`: with D2, `(index docs)` is `util.search/index` with
  `{:boosts boosts}`, an index that ranks podcast documents by its boosts
  without options. Both `app.library/make` and the inspector use it.
- The `:text` criterion with an index: `where` takes `:index` in its
  context. When it's there, the criterion queries it once with
  `{:boosts {:title 1 :description 1}}` (the fields the criterion reads
  today) and keeps the ids in a set; the predicate then tests
  `(episode-id ctx feed item)` for membership. A set of texts is the union
  of their id sets, and `:not` works through `where` as before. Without an
  index, the scan stays, and `library/episodes` passes `:index` from the
  library.
- The docstrings of `where` and `episodes` name `:index`.

**`app.library`**

- `search` takes the options of `util.search/query` plus `:where`
  criteria, `:at` and `:scale`, like `episodes`. Each result gains `:feed`
  and, for an episode, `:item`, looked up by guid in a map built per feed
  on first use (a `delay` per feed guid, made once per call). With
  `:where`, the criteria run as the `:filter` of the query, so they apply
  before the limit, and a show document never meets episode criteria, so
  it's left out. The inspector's `with-items` becomes unnecessary.
- `episodes` passes `:index` (above).
- `make` builds the index with `search/index` (D2).

**`format.transcript`**

With D5, `(matches transcript q)` gives the segments of
`(turns transcript)` whose text holds every clause of `q` (prefixes and
phrases as `snippet` finds them), each with its `:start-time`, `:end-time`
and `:body` as they are and `:parts` from `util.search/snippet` with a
width that covers the body. Matching per turn means a word-level
transcript without speakers matches single words only; the docstring says
so.

**`directory`**

The docstring of `search!` says `:language` filters the Podcast Index;
The search call of `podcastindex-request` sends only `q` and `max`. Move
the line to `fetch-chart!`, which does use it.

**The inspector**

The corpus and per-feed indexes of `web/inspector/service.clj` are built
with `search/index` and queried with `util.search/query`; the page
highlights with `util.search/snippet`. The result limit stays.

**Docs**

- `doc/architecture.md`, "Search": say what the analysis is now (UAX #29
  words, bigrams for the scripts without spaces, NFKD folding), that the
  ranking is BM25F, that the index and queries are generic in
  `util.search`, and why the platform segmenters weren't used. Keep it
  to the decisions and reasons.
- `doc/building-an-app.md`, "Search the episodes": `library/search` with
  `:where`, the `search/index` plus `util.search/query` example for apps
  without the library, `snippet`, and a line on `transcript/matches`.
- `doc/namespaces.md`: the `search` row, and rows for `util.text` and
  `util.search`.
- The README's one mention of full-text search stays true.


## 6. Tests

All in `.cljc` and run on both platforms unless a case is platform-bound.

**`test/dk/simongray/podcast/util/text_test.cljc`**

- `fold`: `Café Ø Straße Århus` → `cafe o strasse arhus` (kept); `Ｐｏｄｃａｓｔ`
  → `podcast`; `ﬁlm` → `film`; `ΧΑΟΣ` → `χαοσ`; `İstanbul` → `istanbul`;
  `Łódź` → `lodz`; nil → `""`.
- `words`: `don't stop 2.0 1,000 e-mail São_Paulo e.g. example.com` →
  `don't`, `stop`, `2.0`, `1,000`, `e`, `mail`, `São_Paulo`, `e.g`,
  `example.com`; `value-for-value` → three words; a run of Han stays one
  word; `AIメモリ管理` → `AI`, `メモリ`, `管理`; the lookahead keeps `abc今天`
  as `abc`, `今天`.
- `tokens`: the three CJK examples of section 5.2; `天気` (two characters)
  → one bigram and the unigram; a Thai run (D6); mixed text from the three
  corpus titles; every kind of whitespace.
- `spans`: offsets of `The Café` (kept); a CJK run gives overlapping spans;
  a fullwidth word.
- Timing (not a test): `fold` and `tokens` on the longest description and
  the longest transcript in the corpus, before and after.

**`test/dk/simongray/podcast/util/search_test.cljc`**

The index, limits-and-ties and snippets tests move here, and these are
added:

- `parse`: each example of section 5.4, plus `-"value for value"`, a `*`
  on a phrase, `:min-prefix`, and a query that is only `:not` clauses
  giving no results.
- `query`: an index of the three CJK corpus titles, where `中秋`, `月饼`,
  `データ` and `メモリ` each find their title, `好` finds a title ending in 好,
  and `"天气很好 good"` as a phrase across a run and a word matches a
  document that has it; `-rerun` excludes; `pod*` as a prefix anywhere;
  a field restricted by a boost of 0 (kept).
- Ranking: a word in the title, show and notes of one document doesn't
  outrank the same word three times in the title of another (BM25F);
  five documents with `clojure` and one with `clojurians` rank the
  `clojure` ones first for `cloj` (F6); the `:k1` and `:b` cases (kept).
- `restore`: an index through `pr-str` and `edn/read-string`, then
  `restore`, answers a prefix query, and `(sorted? (:terms …))`.
- `snippet`: the existing cases; a quoted phrase as one part; a window
  that holds two clauses beats the first hit; CJK hits merged into one
  part.

**`test/dk/simongray/podcast/search_test.cljc`**

- `analysis` changes with the tokenizer: `Podcasting 2.0: the
  value-for-value era!` → `podcasting`, `2.0`, `the`, `value`, `for`,
  `value`, `era`.
- The `:text` criterion with and without `:index` gives the same items;
  `:not {:text …}` with an index.
- `documents-from-the-model` and the criteria tests stay.

**Elsewhere**

- `app/library_test`: `search` with `:where {:status :unplayed}` and with
  `:limit`; results carry `:feed` and `:item`.
- `format/transcript_test`: `matches` on the example transcripts, with
  times.
- `architecture_test`: `util.search` requires only `util.text`.
- `inspector/service_test`: the search page still answers with results.

**Platforms and commands**, from `.github/workflows/test.yml`:

```bash
clojure -X:test
```

```bash
clojure -M:cljs compile test --config-merge '{:autorun false}' && node target/node-tests.js
```

```bash
clojure -M:cljs release test --config-merge '{:autorun false}' && node target/node-tests.js
```

```bash
clojure -Sdeps '{:deps {clj-kondo/clj-kondo {:mvn/version "2026.08.04"}}}' -M -m clj-kondo.main --lint src test dev web server build.clj
```

The release build drops `:pre` and `assert`, so a check that matters is an
explicit throw, as `add` does for a missing id. Run the kondo command after
the last edit of a batch.


## 7. Performance

Targets, measured with Appendix B against section 1.4:

- Index build of the corpus within 10% of 27 s. Bigrams add terms only
  for CJK text; the UAX #29 regex may cost more than the current one, so
  measure `tokens` on the longest texts first (section 6).
- No query slower than its baseline. The scoring loop changes shape
  (BM25F folds the fields into one sum per document), so compare each of
  the eight queries.
- The CJK index of section 1.4 grows; report by how much.
- `library/search` with `:where` on a library of about 50 feeds answers
  within 100 ms, including the guid lookups.

Time every new function on the largest real input before a batch is
reported, as the project does.


## 8. Batches

One batch in the working tree at a time, with a pause after each for
review and a commit.

**Batch 1, `util.text`.** `fold`, `words`, `tokens`, `spans` with their
tests and sources; `search` requires it instead of its own analysis; the
`analysis` test updated; the existing search tests pass with the new
tokens (the index tests don't depend on the split cases). Measure `fold`
and `tokens` on the longest texts.

**Batch 2, `util.search`.** The move; `parse`; BM25F and the prefix fix;
`restore`; the new `snippet`; the tests moved and extended; the corpus
measurement; the CJK queries against the corpus feeds; the namespace
docstring with the sources. Both `search` and `app.library` keep working
through the moved functions; the inspector too.

**Batch 3, the podcast namespaces.** `search/index` (D2), the `:text`
criterion with an index, `library/search` with `:where`, `:feed` and
`:item`, `transcript/matches` (D5), the `directory` docstring, the
inspector, the three doc pages and `namespaces.md`.

**Batch 4, fuzzy matching (D3).** The option `:fuzzy`, off by default:
true for the AUTO rule (0 edits under 3 characters, 1 up to 5, 2 above) or
an integer; applied to term clauses that aren't prefixes; candidates are
the terms with the same first character and a length within the edits,
tested with a bounded Damerau–Levenshtein (optimal string alignment, as
Lucene's transpositions); at most 50 expansions; each candidate's counts
scaled by `1 − edits / length`; the clause scored as a prefix is, with one
document count. Tests on `clojre`, on a two-letter term that stays exact,
and on timing with the corpus terms.

Each batch ends with the four commands of section 6, the measurements of
section 7 that apply, and a report of what changed and what was measured.


## 9. Decisions

| Id | Question | Recommendation | Why |
|---|---|---|---|
| D1 | One namespace or two? | Two, `util.text` and `util.search` | `fold` is useful alone (the `:person` criterion and the `:title` order use it), and analysis has its own sources and tests. One namespace would be about 1,000 lines. |
| D2 | Where do the boosts live? | In the index, set by `search/index` | Then `util.search/query` works on a podcast index without options, and `:boosts` can still be given per query. The alternative is `search/boosts` on every call. |
| D3 | Fuzzy matching, batch 4? | Yes, as an opt-in option | Every reference library has it and the cost is bounded; off by default so search as you type stays exact. |
| D4 | Syntax extent | Quotes, a leading minus, a trailing asterisk, the implicit prefix on the last word | What all syntaxes agree on. `field:` and `OR` are covered as data by `:boosts` with 0 and `:match :any`; nothing hints a listener would type them. |
| D5 | `transcript/matches`? | Yes, in batch 3 | Small, and the only way to give a hit its time; an index of all transcripts would be too large for a phone. |
| D6 | Bigram Thai, Lao, Khmer and Myanmar too? | Yes | Xapian does; without it a Thai sentence is one term, as Chinese is today. Costs a few characters in the regex. |

Decided on 7 October 2026, and built on the branch
`feature/search-library`:

- D1, D2, D5 and D6 as recommended, with `util.search` named
  `util.text.search` to show that it's built on `util.text`. The query
  language, the segments and CIFF later got namespaces of their own, and
  the same day the engine became a library beside podcast-clj,
  `dk.simongray.search`, with `util.text` as `search.analysis`.
- D3: fuzzy matching is on by default, but only for a word that no
  document holds, so a word spelled right stays exact.
- D4: a full boolean language, now in `search.queries`: AND or `&`, OR
  or `|`, NOT, `!` or a leading `-`, parentheses, `field:word` or
  `field=word`, and `word~` for typos, besides quotes and a trailing `*`.
  The spellings are an option, e.g. `OG` for AND in Danish.
- Words split at punctuation as before, instead of the joins of UAX #29
  in section 5.2. Its one class of joining characters would have made
  `comedy,news` one word, and even the exact rules make `Simon's` a word
  that `simon` doesn't find. A query word with punctuation in it becomes a
  phrase, as before. F3 is dropped.
- `transcript/matches` matches passages of at least 60 characters by one
  speaker rather than turns, since a speaker's turn in a caption file can
  last minutes, and gives the time of the cue where the first match is.
- Fuzzy matching weighs a match with typos as MiniSearch does, 0.45
  length / (length + edits), after saturation and by the best match of a
  document, since Lucene's 1 - edits / length let a short title with a
  typo outrank a long one with the word. The word being typed matches
  what it starts, or the words within its edits when it starts none, and
  both with `:fuzzy true`.
- The postings are in segments of sorted int arrays, as in Lucene and
  Datalevin, rather than in nested maps: 50 shows took 304 MB of heap as
  maps and take 61 MB as arrays, with the same results. An `:and` starts
  from its rarest word. A Roaring bitmap per term was measured too and
  saved nothing, since most terms occur in a few documents.

Two details of section 5.2 were wrong. The pairs of a run cross its
scripts, so `AIメモリ管理` gives `メモ モリ リ管 管理`. And a query leaves
out the last character of a run, which only the index has, or a phrase
across a run and the next word would never match.


## 10. Left out, and why

- **Stemming.** Snowball has one algorithm per language (Danish 105
  lines, English 247, 33 languages), applied at index and query time
  alike, and chosen per feed. Prefix matching already covers search as
  you type, and the vector hook leaves stemming to you. A feed's `<language>`
  element would be the way to pick a stemmer if this is ever wanted.
- **Dictionary segmentation for CJK.** Platform-dependent (section 3.4),
  so an index couldn't travel between devices; bigrams over-match
  (`京都` in `東京都`), which every engine accepts.
- **Stop words.** The idf makes `the` nearly worthless already; the cost
  of its postings only shows on the full corpus.
- **Early termination (WAND, MaxScore).** Pays off at hundreds of
  thousands of postings per term, not at a library's size.
- **Field syntax and boolean operators in the string.** As data instead
  (D4).
- **Offsets stored in the index.** Lucene and FTS5 re-tokenize for
  highlighting too; `spans` is cheap for a title or notes.


## Sources

- Unicode Standard Annex #29, Unicode Text Segmentation, §4.1 table 3 and §4:
  <https://www.unicode.org/reports/tr29/>
- Unicode Standard Annex #15, Unicode Normalization Forms:
  <https://www.unicode.org/reports/tr15/>
- CaseFolding.txt and the Unicode Standard chapter 3, §3.13 Default Case
  Algorithms:
  <https://www.unicode.org/Public/UCD/latest/ucd/CaseFolding.txt>
- Unicode Technical Report #30, Character Foldings (withdrawn):
  <https://www.unicode.org/reports/tr30/tr30-4.html>
- Lucene CJKBigramFilter:
  <https://lucene.apache.org/core/9_11_0/analysis/common/org/apache/lucene/analysis/cjk/CJKBigramFilter.html>
- Lucene ASCIIFoldingFilter, ICUFoldingFilter, CJKWidthFilter,
  StandardTokenizer: the 9.11 analysis javadocs.
- Elasticsearch cjk_bigram token filter:
  <https://www.elastic.co/guide/en/elasticsearch/reference/current/analysis-cjk-bigram-tokenfilter.html>
- Groonga TokenBigram:
  <https://groonga.org/docs/reference/tokenizers/token_bigram.html>
- Xapian TermGenerator (FLAG_NGRAMS) and word-breaker.cc:
  <https://xapian.org/docs/apidoc/html/classXapian_1_1TermGenerator.html>
- Robertson and Zaragoza, The Probabilistic Relevance Framework: BM25 and
  Beyond, 2009, §3.5 and §3.6:
  <https://www.staff.city.ac.uk/~sbrp622/papers/foundations_bm25_review.pdf>
- Robertson, Zaragoza and Taylor, Simple BM25 extension to multiple
  weighted fields, CIKM 2004, doi:10.1145/1031171.1031181
- Lucene BM25Similarity:
  <https://lucene.apache.org/core/9_11_1/core/org/apache/lucene/search/similarities/BM25Similarity.html>
- Lucene BlendedTermQuery and MultiTermQuery rewrite methods:
  <https://github.com/apache/lucene/blob/main/lucene/core/src/java/org/apache/lucene/search/BlendedTermQuery.java>
- Lucene TopScoreDocCollector, WANDScorer; Broder et al. 2003
  (doi:10.1145/956863.956944); Ding and Suel 2011
  (doi:10.1145/2009916.2010048); Turtle and Flood 1995
  (doi:10.1016/0306-4573(95)00020-H)
- Elasticsearch simple_query_string:
  <https://www.elastic.co/docs/reference/query-languages/query-dsl/query-dsl-simple-query-string-query>
- SQLite FTS5: query syntax, bm25(), snippet(), highlight():
  <https://www.sqlite.org/fts5.html>
- Xapian QueryParser: <https://xapian.org/docs/queryparser.html>
- Lucene UnifiedHighlighter and DefaultPassageFormatter: the 9.11
  highlighter javadocs and source.
- Lucene FuzzyQuery and LevenshteinAutomata; Schulz and Mihov, Fast string
  correction with Levenshtein automata, 2002
  (doi:10.1007/s10032-002-0082-8)
- Elasticsearch fuzziness:
  <https://www.elastic.co/docs/reference/elasticsearch/rest-apis/common-options#fuzziness>
- MiniSearch: <https://github.com/lucaong/minisearch/blob/master/src/MiniSearch.ts>
- Orama: <https://github.com/oramasearch/orama>
- lunr-languages: <https://github.com/MihaiValentin/lunr-languages>
- clj-fuzzy: <https://github.com/Yomguithereal/clj-fuzzy>
- ECMA-402 §19 Segmenter objects: <https://tc39.es/ecma402/#sec-segmenter-objects>
- ICU boundary analysis: <https://unicode-org.github.io/icu/userguide/boundaryanalysis/>
- OpenJDK BreakIteratorRules and the Thai dictionary:
  <https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/sun/text/resources/BreakIteratorRules.java>
- Snowball: <https://snowballstem.org/algorithms/>


## Appendix A: measured outputs

Node 24.1.0, ICU 77.1, `new Intl.Segmenter(undefined, {granularity: "word"})`,
word-like segments only:

```
[HSK 3] 爸爸不在家的中秋节 - A Mid-Autumn Festival Without Dad at Home
  → HSK 3 爸爸 不在家 的 中秋 节 A Mid Autumn Festival Without Dad at Home
第13回 本番データ消去、AIメモリ管理 ほか
  → 第 13 回 本番 データ 消去 AI メモリ 管理 ほか
なぜオッサンはカルボナーラばかり作ってしまうのか？ トマトソ... 他79件
  → なぜ オッサン は カルボナーラ ばかり 作 って しまう の か トマト ソ 他 79 件
don’t stop 2.0 1,000 e-mail São_Paulo
  → don’t stop 2.0 1,000 e mail São_Paulo
```

OpenJDK 24.0.1, `BreakIterator/getWordInstance Locale/ROOT`, segments with
a letter or digit:

```
[HSK 3] 爸爸不在家的中秋节 - A Mid-Autumn Festival …
  → HSK 3 爸爸不在家的中秋节 A Mid-Autumn Festival Without Dad at Home
第13回 本番データ消去、AIメモリ管理 ほか
  → 第 13 回 本番 データ 消去 AI メモリ 管理 ほか
なぜオッサンはカルボナーラばかり作ってしまうのか？ トマトソ... 他79件
  → なぜ オッサン は カルボナーラ ばかり 作 ってしまうのか トマトソ 他 79 件
don’t stop 2.0 1,000 e-mail São_Paulo
  → don t stop 2.0 1,000 e-mail São_Paulo
ภาษาไทยง่ายนิดเดียว, Thai locale
  → ภาษา ไทย ง่าย นิดเดียว
```

The current `tokens` on the first title: `爸爸不在家的中秋节`, `a`, `mid`, …

Both platforms accept `\p{sc=Han}`, `\p{sc=Hiragana}` and `\p{sc=Thai}`
(JavaScript with the `u` flag, which the `(?u)` prefix gives), and match
a CJK Extension B character as one code point.


## Appendix B: the baseline script

Run against the nREPL with the corpus downloaded (`corpus/download!`).
It parses every feed under `dev-resources/corpus`, builds the index and
times eight queries, the minimum of three runs each.

```clojure
(require '[clojure.java.io :as io]
         '[dk.simongray.podcast.feed.rss :as rss]
         '[dk.simongray.podcast.search :as search])

(defn corpus-feeds
  "The parsed feeds of the corpus by file path, those with items."
  []
  (into {} (for [f    (file-seq (io/file "dev-resources/corpus"))
                 :when (and (.isFile f) (re-find #"\.xml$" (.getName f)))
                 :let  [feed (try (rss/parse (slurp f)) (catch Throwable _))]
                 :when (seq (:rss/items feed))]
             [(.getPath f) feed])))

(defn timed-ms
  "The milliseconds that `f` takes, the minimum of three runs."
  [f]
  (apply min (for [_ (range 3)]
               (let [t (System/nanoTime)]
                 (f)
                 (/ (- (System/nanoTime) t) 1e6)))))

(def queries
  ["value for va" "the" "clojure" "\"value for value\"" "a"
   "podcast episode new" "th" "s"])

(comment
  (def feeds (corpus-feeds))
  (def docs (vec (for [[url feed] feeds, item (:rss/items feed)]
                   (search/item-document feed item {:url url}))))
  (count docs)
  ;; => 168920
  (def idx (time (search/index docs)))
  ;; "Elapsed time: 27221 msecs"
  [(count (:terms idx)) (reduce + (map count (vals (:postings idx))))]
  ;; => [398133 16543863]
  (for [q queries]
    [q (timed-ms #(search/query idx q {:limit 10}))])
  (timed-ms #(reduce search/add idx (take 50 docs)))
  (timed-ms #(reduce search/remove idx (map :id (take 50 docs))))
  #_.)
```
