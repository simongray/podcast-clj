(ns dk.simongray.podcast.feed.validate
  "Validation of a feed map by the tag registry.

  A validation reports what the specifications say is wrong:

  - a required attribute that's missing
  - a value outside an enumeration, or one that doesn't coerce to its type
  - a deprecated element
  - a broken structure, e.g. an alternate enclosure without a source
  - an element that the registry doesn't know

  The :requirements option adds the required and recommended elements of
  a directory or a standard: :psp-1 for the baseline of the Podcast
  Standards Project, and :apple, :spotify and :youtube for those
  directories.

  A diagnostic has the shape of those of the parse report: an :id, a
  :level of :error, :warning or :info, a :path into the feed, and a
  :message. Validation never throws on a value, since a shape that the
  element doesn't take is a diagnostic like any other."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.impl :as feed.impl]
            [dk.simongray.podcast.feed.spec :as spec]
            [dk.simongray.podcast.feed.validate.impl :as impl]
            [dk.simongray.webcrypto.bytes :as bytes]
            [dk.simongray.whatwg-mime :as mime]))

(defn- text-of
  "The text of the value `v` of an element: `v` itself, or the :content of
  an element with attributes."
  [v]
  (if (map? v) (:content v) v))

(defn- characters
  "How many characters the text `s` has, counting one outside the Basic
  Multilingual Plane once."
  [s]
  #?(:clj  (.codePointCount ^String s 0 (count s))
     :cljs (count (js/Array.from s))))

;; YouTube Data API, snippet.title and snippet.description of the videos
;; resource, updated 8 October 2026
(defn- youtube-text?
  "Whether the text of the value `v` is one that YouTube takes: without <
  or >, and at most `n` long as `size-fn` counts it."
  [size-fn n v]
  (let [s (text-of v)]
    (and (string? s)
         (not (re-find #"[<>]" s))
         (<= (size-fn s) n))))

(def requirements
  "The required and recommended elements of the channel and of an item,
  for each standard or directory by name, and the levels that it raises
  above those of the catalogue.

  A requirement is a model key, or a vector of a key, a predicate of its
  value, and a description. The key can be a set of keys, of which one
  will do. They're the requirements of these, as of October 2026:

  - PSP-1 of the Podcast Standards Project
  - Apple's A Podcaster's Guide to RSS
  - Spotify's Podcast Delivery Specification 1.10
  - YouTube's Deliver podcasts using an RSS feed, with the limits on the
    title and description of a video of its Data API

  PSP-1 and Apple require a category from Apple's list, which a name from
  before 2019 still is, and Apple keeps only the first of the items that
  share an enclosure URL. YouTube makes a video of each episode from the
  audio and the show's image, and sends a code to the owner's email."
  (let [self-link  [:atom/links
                    #(some (comp #{"self"} :rel) %)
                    "<atom:link rel=\"self\">"]
        enclosure  [:rss/enclosure
                    (comp impl/absolute-url? :url)
                    "<enclosure> with an absolute url"]
        image      [#{:rss/image :itunes/image} nil
                    "<image> or <itunes:image>"]
        categories {:validate/unknown-category  :error
                    :validate/escaped-category  :error
                    :validate/wrong-subcategory :error
                    :validate/category-depth    :error}]
    {:psp-1 {:name    "PSP-1 of the Podcast Standards Project"
             :channel {:required    [self-link :rss/title :rss/description
                                     :rss/link :rss/language :itunes/categories
                                     :itunes/explicit :itunes/image]
                       :recommended [:podcast/locked :podcast/guid
                                     :itunes/author]}
             :item    {:required    [:rss/title enclosure :rss/guid]
                       :recommended [:rss/link :rss/pub-date :rss/description
                                     :itunes/duration :itunes/image
                                     :itunes/explicit :podcast/transcripts]}
             :levels  categories}
     :apple {:name    "Apple Podcasts"
             :channel {:required    [:rss/title :rss/description :itunes/image
                                     :rss/language :itunes/categories
                                     :itunes/explicit]
                       :recommended [:itunes/author :rss/link]}
             :item    {:required    [:rss/title enclosure :rss/guid]
                       :recommended [:rss/pub-date :rss/description
                                     :itunes/duration :rss/link :itunes/image
                                     :itunes/explicit]}
             :levels  (assoc categories
                             :validate/duplicate-enclosure :error)}
     :spotify {:name    "Spotify"
               :channel {:required    [:rss/title :rss/link :rss/description
                                       :itunes/author image]
                         :recommended [:itunes/categories :rss/language
                                       :itunes/explicit]}
               :item    {:required    [:rss/guid :rss/pub-date
                                       [#{:rss/title :media/title} nil
                                        "<title> or <media:title>"]
                                       [#{:rss/description :media/description}
                                        nil
                                        "<description> or <media:description>"]
                                       [#{:rss/enclosure :media/contents} nil
                                        "<enclosure> or <media:content>"]]}}
     :youtube {:name    "YouTube"
               :channel {:required    [:rss/title image
                                       [#{:itunes/owner :rss/managing-editor}
                                        #(some? (if (map? %) (:email %) %))
                                        "<itunes:email> or <managingEditor>"]]
                         :recommended [:rss/link :rss/description]}
               :item    {:required    [[:rss/title
                                        (partial youtube-text? characters 100)
                                        (str "<title> of at most 100"
                                             " characters, without < or >")]
                                       [:rss/enclosure
                                        #(some-> (:type %) mime/essence
                                                 (str/starts-with? "audio/"))
                                        "<enclosure> of an audio file"]]
                         :recommended [[:rss/description
                                        (partial youtube-text?
                                                 (comp bytes/length
                                                       bytes/encode)
                                                 5000)
                                        (str "<description> of at most 5000"
                                             " bytes, without < or >")]]}}}))

(defn validate
  "The diagnostics of `feed` with the `opts` below: the checks of the
  registry, the basics of RSS 2.0, and the requirements that `opts` name.

  - :registry, the tag registry to check against, spec/default by default
  - :requirements, a name in requirements, or a map of the shape of its
    entries
  - :categories and :renamed-categories, the tables of spec that the
    categories of a podcast are checked by

  The feed is normalized first, so the shorthand that a producer can write
  passes too. The chosen requirements can raise the level of a
  diagnostic, e.g. a repeated enclosure URL is an error for Apple. A
  requirement that an error of the other checks already covers isn't
  reported again, so one fix counts once."
  ([feed]
   (validate feed {}))
  ([feed {chosen :requirements
          :keys  [registry categories renamed-categories]
          :or    {registry           spec/default
                  categories         spec/categories
                  renamed-categories spec/renamed-categories}
          :as    opts}]
   {:pre [(map? opts)]}
   (when (and chosen
              (not (map? chosen))
              (not (contains? requirements chosen)))
     (throw (ex-info (str "Unknown requirements " chosen)
                     {:requirements chosen
                      :known        (set (keys requirements))})))
   (let [registry     (spec/registry registry)
         [feed notes] (feed.impl/normalized feed {:registry registry})
         reqs         (if (map? chosen) chosen (get requirements chosen))
         levels       (:levels reqs)
         raised       #(assoc % :level (get levels (:id %) (:level %)))
         checked      (mapv raised
                        (concat (map diagnostics/describe notes)
                                (impl/rss-diagnostics feed)
                                (impl/item-diagnostics feed)
                                (impl/category-diagnostics feed categories
                                                           renamed-categories)
                                (impl/children-diagnostics registry :channel
                                                           feed [])
                                (impl/unknown-key-diagnostics registry :channel
                                                              nil feed [])))
         errors   (into #{} (comp (filter (comp #{:error} :level)) (map :path))
                        checked)]
     (into checked
           (when chosen
             (impl/requirements-diagnostics registry feed reqs errors))))))

(defn location
  "The `path` into a feed of a diagnostic as people read it, by the
  `registry` of spec, spec/default unless given: each element by its XML
  name, one of several by its number from 1, and an attribute by its name.
  The items of the channel as a whole are every item.

      (location [:rss/items 6 :rss/enclosure :url])
      ;; => \"item 7 › enclosure › url\""
  ([path]
   (location path spec/default))
  ([path registry]
   (let [{:keys [parts many?]} (reduce (partial impl/step
                                                (spec/registry registry))
                                       {:parts [] :parent :channel}
                                       path)]
     (cond
       (empty? parts)                  "channel"
       (and many? (= 1 (count parts))) (str "every " (first parts))
       :else                           (str/join " › " parts)))))

(defn report
  "The diagnostics of the `answer` of fetch!, with the `opts` of validate:
  the :error of the fetch or of the parse, the :report of the parse, and
  those of validating its :feed. Give them to diagnostics/verdict for the
  verdict on the feed.

  The :error is an error here, whatever its level elsewhere, e.g. a 404,
  since a feed that can't be read fails as a whole."
  ([answer]
   (report answer {}))
  ([{:keys [error feed] :as answer} opts]
   (concat (when error [(assoc error :level :error)])
           (:report answer)
           (when feed (validate feed opts)))))

(defn valid?
  "Whether `feed` has no errors, with the `opts` of validate."
  ([feed]
   (valid? feed {}))
  ([feed opts]
   (not-any? (comp #{:error} :level) (validate feed opts))))

(comment
  (validate {:rss/version "2.0" :rss/title "T"
             :podcast/values [{:type "lightning" :method "keysend"}]}
            {:requirements :psp-1})
  #_.)
