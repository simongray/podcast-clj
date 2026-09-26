# Building a podcast app on the library

This is the path from finding a show to playing it, with the function to
call at each step. Everything here is plain data and pure functions; the
app owns the parts that differ between apps, listed at the end. The
bundled inspector, under `web/`, walks the same path and is the worked
example.

An app's state is three values: the player state of
`dk.simongray.podcast.app.player`, one map with the subscriptions, the queue
and the progress per episode; the parsed feeds, kept by feed guid along
with the URL each was fetched from and the validators of the last fetch;
and, when the app searches, an index from `dk.simongray.podcast.app.search`.
All three are values that print as EDN, so storing them is the app's
choice of where, not how.

```clojure
(require '[dk.simongray.podcast :as podcast]
         '[dk.simongray.podcast.app.player :as player]
         '[dk.simongray.podcast.app.search :as search]
         '[dk.simongray.podcast.directory :as directory]
         '[dk.simongray.podcast.feed :as feed]
         '[dk.simongray.podcast.format.chapters :as chapters]
         '[dk.simongray.podcast.format.html :as html]
         '[dk.simongray.podcast.format.opml :as opml]
         '[dk.simongray.podcast.format.transcript :as transcript]
         '[dk.simongray.podcast.podping :as podping]
         '[dk.simongray.podcast.resolve :as resolve]
         '[dk.simongray.podcast.value :as value])

(def now (java.util.Date.))          ; every write to the player state takes the time
```

## 1. Finding a show

The directories answer behind one shape. On the JVM, `search!` asks Apple
Podcasts, fyyd and gpodder.net at once, and the Podcast Index too when
given credentials, and combines the answers into one entry per show.

```clojure
(def found (:results (directory/search! "podcasting 2.0" {:limit 5})))
(map (juxt resolve/title resolve/author :directory/sources) found)
;; => (["Podcasting 2.0" "Podcast Index LLC" {:apple "1584274529" :fyyd 61003 :gpodder "https://…"}] …)

(def url (resolve/self-url (first found)))   ; the feed to subscribe to
```

In a browser, `directory/request` gives the request map and
`directory/response` reads the answer; whether a directory allows the call
from a page is its CORS policy, not the library's.

## 2. Subscribing

Fetch the feed, keep what the next poll needs, and tell the player state.
The feed's identity is its `podcast:guid`, or the one the specification
derives from the URL when it declares none; `feed/feed-guid` answers either
way, and every id in the player state is built on it.

```clojure
(def r (podcast/fetch! url))                         ; {:status 200 :feed … :etag … :last-modified … :url …}
(def show (:feed r))
(def guid (feed/feed-guid show url))

(def state
  (-> (player/state "phone")
      (player/subscribe guid {:url url :title (resolve/title show)} now)
      (player/configure guid {:auto-queue? true :skip-intro 20} now)))

(def library {guid {:url url :feed show :etag (:etag r) :last-modified (:last-modified r)}})
```

`(:url r)` is the final URL after redirects, which is what a permanent
redirect asks you to store. In a browser the fetch is yours, since feeds
seldom allow cross-origin reads; `podcast/parse` takes the XML string.

## 3. Keeping up

`resolve/next-check` says when to look again, from the feed's own
update-frequency rule, its ttl, its syndication period or the cadence of
its recent episodes. A conditional fetch costs nothing when nothing
changed, and `player/refresh` folds the new episodes into the queue for
subscriptions that ask for it.

```clojure
(resolve/next-check show now)                        ; => #inst "…", or nil for a complete feed

(let [{:keys [etag last-modified]} (library guid)
      r2 (podcast/fetch! url {:etag etag :last-modified last-modified})]
  (when-let [fresh (:feed r2)]                       ; absent on a 304
    (let [{:keys [state added]} (player/refresh state show fresh url now)]
      (podcast/report fresh)                         ; what had to be repaired or forgiven
      (:new-feed-url r2)                             ; when present, move the subscription
      added)))                                       ; the ids of the new episodes, oldest first
```

Podping tells you which feeds changed, so that you fetch those and leave
the schedule for the rest. A poller keeps the block it reached and the
writers it has judged, and asks every few seconds for the blocks since.

```clojure
(def step (podping/poll! nil))                                        ; the head of the chain
(def step (podping/poll! (:block step) {:writers (:writers step)}))   ; the pings since
(podping/affected (:pings step) (map :url (vals (player/subscriptions state))))
;; => ("https://feeds.podcastindex.org/pc20.xml")                    ; fetch these now
```

## 4. Showing a show and an episode

`resolve` gives one answer per property where the vocabularies overlap,
and `properties` gives them all at once; `search/episodes` selects and
orders the items by criteria as data, the listener's progress included.

```clojure
(resolve/properties show)
;; => {:title "…" :author "…" :artwork "https://…" :categories [["Technology"]] :medium "podcast" …}

(search/episodes show {:where {:status :unplayed} :order :listening :player state :url url})
(search/episodes show {:where {:season 2 :type "full"} :order :newest})

(def item (first (resolve/episodes show)))
(resolve/properties show item)
;; => {:title "…" :published #inst "…" :duration 3600 :numbering {:type "full" :season 2 :episode 3}
;;     :enclosure {:url "…" :type "audio/mpeg"} :transcript {:url "…" :type "text/vtt"} :people [...] …}
(merge item (resolve/properties show item))         ; the raw fields and the resolved ones side by side

[:div.notes (html/hiccup (resolve/description item))]  ; the show notes, sanitized, as Hiccup for your renderer
```

Chapters come from a linked file, from Podlove chapters embedded in the
feed, or from the timestamps in the notes; transcripts from the reference
that suits the listener, fetched by you and parsed by format.

```clojure
(def doc (or (some-> (resolve/chapters-url item) fetch-body chapters/parse-json)
             (resolve/embedded-chapters item)
             (resolve/inferred-chapters item)))
(chapters/at doc 754)                                ; the chapter playing at 12:34

(let [{:keys [url type]} (resolve/transcript show item {:languages ["da" "en"]})]
  (transcript/at (transcript/parse (fetch-body url) type) 754))
```

## 5. Playing

The player state holds the queue and the progress; the media element is
yours. Every event from it goes through `player/step`, which returns the
new state and the effects to perform: seek the element, or start the next
episode.

```clojure
(def id (player/episode-id show url item))
(def state (player/enqueue state id now))

(player/resume-position state guid id)               ; where to start: where it was left, or past the intro
(resolve/enclosure item {:types ["audio/opus" "audio/mpeg"] :max-bitrate 64000})

(player/step state [:progress id 1234 3600] now)     ; from the element's timeupdate, every few seconds
(player/step state [:skip id :forward] now)          ; => {:state … :effects [[:seek id 1264]]}
(player/step state [:ended id] now)                  ; => {:state … :effects [[:play next-id]]}
```

The inspector's browser player is this in eighty lines of shell around
views that are pure data; its tests mount it on jsdom and click the
buttons.

## 6. Searching the library

The index is a value built from the feeds; rebuild it or `add` and
`remove` documents as feeds refresh. Index a transcript when the app has
fetched it, and index fewer fields in a browser short of memory.

```clojure
(def idx (search/index (for [[_ {:keys [url feed]}] library
                             item (:rss/items feed)]
                         (search/item-document feed item {:url url}))))

(search/query idx "value for va" {:limit 10})        ; every word, the last as a prefix, best first
;; => [{:id [feed-guid item-guid] :score 7.1 :stored {:title "…" :show "…" :published #inst "…"}} …]
(search/snippet (html/text (resolve/description item)) "value for va")
```

## 7. Syncing and taking the subscriptions along

Two devices' states merge into one, the later entry winning everywhere;
the subscriptions go in and out as OPML.

```clojure
(player/merge phone-state laptop-state)
(opml/emit (player/to-opml state))
(player/from-opml state (opml/parse opml-text) now)
```

## 8. Value for value

The value block that applies to an episode, who is paid at a given
moment, and the record a payment carries; the payment itself is the
app's.

```clojure
(def block (resolve/value show item))
(value/payments (:recipients (value/recipients-at block 754)) 1000)   ; 1000 sats split at 12:34
(value/boostagram {:sats 1000 :position 754 :message "Great episode" :feed-guid guid :item-guid (feed/item-guid item)})
```

## What stays yours

Storage, since the state is EDN and any store will do; HTTP in the
browser, where feeds and directories decide what a page may fetch;
playback, through a media element the effects drive; downloads,
notifications and payments; and the appearance of all of it. A Podping
consumer and a sync backend are not in the library yet, by decision; the
pure core of syncing, `player/merge`, is.
