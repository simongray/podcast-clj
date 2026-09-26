(ns dk.simongray.podcast.search-test
  "Filtering and ordering by criteria as data, and the full-text index."
  (:require #?(:clj [clojure.java.io :as io])
            #?(:cljs [shadow.resource :as rc])
            #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is testing]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.player :as player]
            [dk.simongray.podcast.search :as search]))

(def example
  (delay (podcast/parse #?(:clj  (slurp (io/resource "feeds/namespace-example.xml"))
                           :cljs (rc/inline "feeds/namespace-example.xml")))))

(def t0 #inst "2026-03-01T00:00:00Z")
(def t1 #inst "2026-03-02T00:00:00Z")

(def show
  "A serial show with a trailer, two seasons and a bonus, hand-built."
  {:rss/title       "Test Show"
   :podcast/guid    "feed-guid"
   :itunes/type     "serial"
   :itunes/explicit true
   :podcast/people  [{:content "Host Person" :role "host"}]
   :rss/items
   [{:rss/title "Trailer" :rss/guid {:content "t"} :rss/pub-date #inst "2026-01-01T00:00:00Z"
     :itunes/episode-type "trailer" :itunes/duration 60}
    {:rss/title "Season one, episode one: Café" :rss/guid {:content "s1e1"}
     :rss/pub-date #inst "2026-01-08T00:00:00Z" :itunes/season 1 :itunes/episode 1 :itunes/duration 1800
     :rss/description "<p>Notes about coffee.</p>"
     :podcast/transcripts [{:url "https://x/t.vtt" :type "text/vtt"}]}
    {:rss/title "Season one, episode two" :rss/guid {:content "s1e2"}
     :rss/pub-date #inst "2026-01-15T00:00:00Z" :itunes/season 1 :itunes/episode 2 :itunes/duration 3600
     :itunes/explicit false
     :podcast/people [{:content "Guest Nørgaard"}]
     :rss/description "00:00 Intro\n10:00 Coffee\n20:00 Outro"}
    {:rss/title "Season two, episode one" :rss/guid {:content "s2e1"}
     :rss/pub-date #inst "2026-02-01T00:00:00Z" :itunes/season 2 :itunes/episode 1 :itunes/duration 900
     :podcast/chapters {:url "https://x/c.json" :type "application/json+chapters"}}
    {:rss/title "Bonus" :rss/guid {:content "b"} :rss/pub-date #inst "2026-02-05T00:00:00Z"
     :itunes/season 2 :itunes/episode-type "bonus"}]})

(def listened
  "A player that has started one episode and finished another."
  (-> (player/state "phone")
      (player/progress ["feed-guid" "s1e1"] 600 1800 t1)
      (player/mark-played ["feed-guid" "s1e2"] t0)))

(defn- titles
  [opts]
  (mapv :rss/title (search/episodes show opts)))

(deftest analysis
  (is (= "cafe o strasse arhus" (search/fold "Café Ø Straße Århus")))
  (is (= ["podcasting" "2" "0" "the" "value" "for" "value" "era"]
         (search/tokens "Podcasting 2.0: the value-for-value era!")))
  (is (= [] (search/tokens nil)))
  (is (= [{:start 0 :end 3 :term "the"} {:start 4 :end 8 :term "cafe"}]
         (search/spans "The Café"))))

(deftest criteria
  (is (= ["Trailer" "Season one, episode one: Café" "Season one, episode two" "Season two, episode one" "Bonus"]
         (titles {}))
      "the listening order of a serial show, by default")
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:season 1}})))
  (is (= ["Season one, episode one: Café" "Season one, episode two" "Season two, episode one"]
         (titles {:where {:type "full"}})))
  (is (= ["Trailer" "Bonus"] (titles {:where {:type #{"trailer" "bonus"}}})))
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:duration [1000 nil]}})))
  (is (= ["Trailer" "Season two, episode one"] (titles {:where {:duration [nil 900]}})) "no duration is no match")
  (is (= ["Season one, episode one: Café" "Season one, episode two" "Season two, episode one"]
         (titles {:where {:published [#inst "2026-01-08T00:00:00Z" #inst "2026-02-01T00:00:00Z"]}})))
  (is (= ["Season one, episode one: Café"] (titles {:where {:transcript? true}})))
  (is (= ["Season one, episode two" "Season two, episode one"] (titles {:where {:chapters? true}}))
      "timestamps in the notes count, and so does a linked file")
  (is (= ["Season one, episode two"] (titles {:where {:explicit? false}}))
      "the feed's flag is inherited unless the item says otherwise")
  (is (= ["Season one, episode two"] (titles {:where {:person "norgaard"}})) "names are folded")
  (is (= ["Trailer" "Season one, episode one: Café" "Season two, episode one" "Bonus"]
         (titles {:where {:person "host"}}))
      "the feed's people, where the item names none")
  (is (= ["Season one, episode one: Café" "Season one, episode two"] (titles {:where {:text "coff"}}))
      "words in the title or the notes, the last as a prefix")
  (is (= ["Season one, episode one: Café"] (titles {:where {:text "cafe"}})))
  (is (= ["Season one, episode one: Café"] (titles {:where {:status :in-progress} :player listened})))
  (is (= ["Trailer" "Season two, episode one" "Bonus"] (titles {:where {:status :unplayed} :player listened})))
  (is (= ["Season one, episode two"] (titles {:where {:status :played} :player listened})))
  (is (= ["Season one, episode one: Café" "Season one, episode two"]
         (titles {:where {:status #{:played :in-progress} :season 1} :player listened})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/where {:colour "red"})))
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/where {:status :played}))
      ":status without a player is a mistake, not an empty list"))

(deftest orders
  (is (= ["Bonus" "Season two, episode one" "Season one, episode two" "Season one, episode one: Café" "Trailer"]
         (titles {:order :newest})))
  (is (= (reverse (titles {:order :newest})) (titles {:order :oldest})))
  (is (= ["Trailer" "Season two, episode one" "Season one, episode one: Café" "Season one, episode two" "Bonus"]
         (titles {:order :shortest}))
      "the one without a duration last")
  (is (= ["Season one, episode two" "Season one, episode one: Café" "Season two, episode one" "Trailer" "Bonus"]
         (titles {:order :longest})))
  (is (= ["Bonus" "Season one, episode one: Café" "Season one, episode two" "Season two, episode one" "Trailer"]
         (titles {:order :title})))
  (is (= ["Season one, episode one: Café" "Season one, episode two" "Trailer" "Season two, episode one" "Bonus"]
         (titles {:order :recent :player listened}))
      "last touched first, the untouched in feed order after")
  (is (= ["Bonus" "Trailer"] (titles {:order (fn [_ item] (count (:rss/title item))) :limit 2}))
      "a function is a key of its own; limit applies after ordering")
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/order :random)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (search/order :recent))))

(deftest across-feeds
  (let [other {:rss/title "Other" :podcast/guid "other"
               :rss/items [{:rss/title "Elsewhere" :rss/guid {:content "e"} :rss/pub-date #inst "2026-01-20T00:00:00Z"}]}
        pairs (search/episodes [show other] {:where {:type "full"} :order :newest})]
    (is (= ["Season two, episode one" "Elsewhere" "Season one, episode two" "Season one, episode one: Café"]
           (mapv (comp :rss/title second) pairs)))
    (is (= ["Test Show" "Other" "Test Show" "Test Show"] (mapv (comp :rss/title first) pairs)))
    (is (= 2 (count (search/episodes [show other] {:limit 2}))))
    (is (= ["Season one, episode one: Café"]
           (mapv (comp :rss/title second)
                 (search/episodes [show other] {:where {:status :in-progress} :player listened
                                                :url (fn [feed] (str "https://x/" (:podcast/guid feed)))})))
        "the url as a function of the feed, for the identities the player uses")))

(deftest the-index
  (let [idx (search/index [{:id 1 :stored {:title "Clojure…"}
                            :fields {:title "Clojure and the value of simplicity" :description "A talk about data."}}
                           {:id 2 :fields {:title "Rust"
                                           :description "Systems programming, and a little Clojure for the value for value era."}}
                           {:id 3 :fields {:title "Gardening" :description "Tomatoes."}}])
        ids (fn [q & [opts]] (mapv :id (search/query idx q (or opts {}))))]
    (testing "every word must occur; a word in the title weighs more"
      (is (= [1 2] (ids "clojure")))
      (is (= [1 2] (ids "clojure value")))
      (is (= [] (ids "clojure tomatoes")))
      (is (= #{1 2 3} (set (ids "clojure tomatoes" {:match :any}))))
      (is (= [] (ids "")))
      (is (= [] (ids "   "))))
    (testing "the last word as a prefix, unless the query ends in a space"
      (is (= [1 2] (ids "cloj")))
      (is (= [] (ids "cloj ")))
      (is (= [] (ids "cloj" {:prefix? false})))
      (is (= [2] (ids "systems cloj")) "only the last word"))
    (testing "words in quotes must occur in a row"
      (is (= [2] (ids "\"value for value\"")))
      (is (= [1 2] (ids "value")))
      (is (= [1] (ids "\"value of\""))))
    (testing "what is stored comes back; limit and where apply"
      (is (= {:title "Clojure…"} (:stored (first (search/query idx "clojure")))))
      (is (nil? (:stored (second (search/query idx "clojure")))))
      (is (= 1 (count (search/query idx "clojure" {:limit 1}))))
      (is (= [2] (ids "clojure" {:where #(= 2 (:id %))}))))
    (testing "the ranking is an option"
      (is (= [2] (ids "clojure" {:boosts {:title 0 :description 1}})) "a field boosted by zero does not match")
      (is (= [2 1] (ids "clojure" {:rank (fn [r] (- (:id r)))})) "a rank function orders the results instead")
      (is (= (ids "clojure") (ids "clojure" {:k1 1.2 :b 0.75})) "the defaults")
      (is (not= (:score (first (search/query idx "clojure")))
                (:score (first (search/query idx "clojure" {:k1 2.0 :b 0.5})))))
      (is (= [] (ids "clo" {:min-prefix 4})) "too short a word to complete")
      (is (= [1 2] (ids "cloj" {:min-prefix 4})))
      (is (= (take 1 (ids "clojure")) (ids "clojure" {:limit 1})) "a limit keeps the head of the full order"))
    (testing "documents replaced and removed"
      (let [idx (search/add idx {:id 1 :fields {:title "Haskell"}})]
        (is (= [2] (mapv :id (search/query idx "clojure"))))
        (is (= [1] (mapv :id (search/query idx "haskell"))))
        (is (= 3 (count (:docs idx))))
        (let [idx (search/remove idx 1)]
          (is (= 2 (count (:docs idx))))
          (is (= [] (search/query idx "haskell")))
          (is (= [2] (mapv :id (search/query idx "clojure")))))
        (is (= idx (search/remove idx :nobody)))))
    (testing "fields and queries given as terms are taken as is"
      (let [idx (search/index [{:id :a :fields {:body ["kaffe" "te"]}}])]
        (is (= [:a] (mapv :id (search/query idx ["kaffe"]))))
        (is (= [:a] (mapv :id (search/query idx "Kaffe"))))
        (is (= [] (search/query idx ["Kaffe"])) "no analysis, so no folding")))
    (is (thrown? #?(:clj Exception :cljs js/Error) (search/add idx {:fields {:title "no id"}})))))

(deftest documents-from-the-model
  (let [feed (deref example)
        item (first (:rss/items feed))
        doc  (search/item-document feed item {:url "https://example.com/feed.xml"})]
    (is (= ["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"] (:id doc)) "the identity the player uses")
    (is (= "Episode 3 - The Future" (get-in doc [:fields :title])))
    (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :show])))
    (is (= "A look into the future of podcasting and how we get to Podcasting 2.0!" (get-in doc [:fields :description])))
    (is (= "Adam Curry Dave Jones Becky Smith" (get-in doc [:fields :people])))
    (is (= {:title "Episode 3 - The Future" :show "Podcasting 2.0 Namespace Example"
            :guid "https://example.com/ep0003" :url "https://example.com/feed.xml"
            :published #inst "2020-10-09T04:30:38Z"}
           (:stored doc)))
    (is (= "Rain Dawn" (get-in (search/item-document feed item {:chapters {:chapters [{:title "Rain"} {:title "Dawn"}]}})
                               [:fields :chapters]))
        "chapters handed in are indexed by title")
    (is (= "Al: Hello there" (get-in (search/item-document feed item {:transcript {:segments [{:speaker "Al" :body "Hello there"}]}})
                                     [:fields :transcript])))
    (let [idx (search/index (map #(search/item-document feed % {:url "https://example.com/feed.xml"}) (:rss/items feed)))]
      (is (= 3 (count (:docs idx))))
      (is (= [["y0ur-gu1d-g035-h3r3" "https://example.com/ep0003"]] (mapv :id (search/query idx "futu"))))
      (is (= [["y0ur-gu1d-g035-h3r3" "https://example.com/ep0002"]] (mapv :id (search/query idx "\"the present\""))))
      (is (= "Episode 3 - The Future" (:title (:stored (first (search/query idx "future")))))))
    (let [doc (search/feed-document feed)]
      (is (= "y0ur-gu1d-g035-h3r3" (:id doc)))
      (is (= "Podcasting 2.0 Namespace Example" (get-in doc [:fields :title])))
      (is (= {:title "Podcasting 2.0 Namespace Example" :author "John Doe"} (:stored doc))))))

(deftest snippets
  (let [text "The quick brown fox jumps over the lazy dog, and the fox is quick."]
    (is (= [{:text "The quick brown "} {:text "fox" :match? true} {:text " jumps over the lazy dog, and the "}
            {:text "fox" :match? true} {:text " is quick."}]
           (search/snippet text "fox")))
    (is (= [{:text "…"} {:text "over the "} {:text "lazy" :match? true} {:text " dog, and the fox"} {:text "…"}]
           (search/snippet text "lazy" {:width 30}))
        "a window around the first match, cut at word boundaries")
    (is (= [{:text "The quick brown "} {:text "fox" :match? true} {:text " jumps over the lazy dog, and the "}
            {:text "fox" :match? true} {:text " is quick."}]
           (search/snippet text "fo")) "the last word as a prefix")
    (is (= [{:text "The quick brown fox jumps over the lazy dog, and the fox is quick."}]
           (search/snippet text "cat")) "no match, the start of the text")
    (is (= [{:text "Café"}] (search/snippet "Café" "tea")))
    (is (= [] (search/snippet nil "tea")) "no text, no parts")
    (is (= [{:text "Café" :match? true}] (search/snippet "Café" "cafe")) "folded like the index")))
