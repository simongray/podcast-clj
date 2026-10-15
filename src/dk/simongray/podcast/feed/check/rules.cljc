(ns dk.simongray.podcast.feed.check.rules
  "The rules that judge the facts of a check: those of the standards, and
  the requirements of a directory.

  The standards come first: HTTP as RFC 9110 has it, the attributes of
  RSS 2.0 and of the podcast namespace, and the CORS rules of the Fetch
  standard, which decide what a web player on another site can read. The
  rules are pure, so the facts of one check can be judged by several
  requirements, and no request goes out again."
  (:require [clojure.string :as str]
            [dk.simongray.podcast.diagnostics :as diagnostics]
            [dk.simongray.podcast.feed.check.rules.impl :as impl]))

(def requirements
  "The requirements that a check adds for a standard or a directory, by name.

  Each can give the size, type, colors and file extension of the artwork
  of an itunes:image, a :media-type-pred of the type that the server gives
  a media file, and the levels that it raises above those of the
  catalogue. They're the requirements of PSP-1, of Apple's A Podcaster's
  Guide to RSS, of Spotify's Podcast Delivery Specification 1.10, and of
  YouTube's Deliver podcasts using an RSS feed, as of October 2026.
  Spotify asks for square artwork of any size, and YouTube makes its
  videos from audio files.

  A map of your own can also give :max-feed-bytes, the largest feed that a
  directory takes. None of these give it, since no directory publishes
  one as of October 2026."
  (let [artwork {:sizes      [1400 3000]
                 :types      #{"image/jpeg" "image/png"}
                 :colors     #{:rgb :indexed}
                 :extension? true}]
    {:psp-1   {:name    "PSP-1 of the Podcast Standards Project"
               :artwork artwork}
     :apple   {:name    "Apple Podcasts"
               :artwork (assoc artwork :alpha? false :last-modified? true)
               :levels  {:check/ranges :error}}
     :spotify {:name            "Spotify"
               :artwork         {:square? true
                                 :types   #{"image/tiff" "image/png"
                                            "image/jpeg"}}
               :media-type-pred #(not (str/starts-with? % "image/"))}
     :youtube {:name            "YouTube"
               :media-type-pred #(str/starts-with? % "audio/")}}))

(defn durations-differ?
  "Whether the `measured` duration of a media file or stream differs from
  the `declared` one by more than 10 seconds and 2 percent, both in
  seconds. It's false unless both are numbers."
  [declared measured]
  (impl/durations-differ? declared measured))

(defn verdicts
  "The diagnostics that the `facts` about `file` call for, by the
  standards and the `chosen` requirements, if any.

  Each diagnostic has the :url of the file and the :path of the element
  that names it. The `chosen` requirements are a name in requirements, or
  a map of the shape of its entries."
  ([file facts]
   (verdicts file facts nil))
  ([{:keys [kind] :as file} facts chosen]
   (let [chosen (if (map? chosen) chosen (get requirements chosen))
         judge  (get impl/judges kind (constantly []))
         found  (cond
                  (:unchecked facts)
                  []

                  (:error facts)
                  [(impl/diagnostic file chosen :check/unreachable
                                    :reason (impl/why facts)
                                    :type (get-in facts [:error :type]))]

                  :else
                  (judge file chosen facts))]
     (mapv diagnostics/describe found))))

(comment
  ;; what the facts of a file call for, with no request
  (def media-file
    {:kind     :media
     :url      "https://example.org/1.mp3"
     :path     [:rss/items 0 :rss/enclosure]
     :declared {:type "audio/mpeg" :length 1000}})

  (def served
    {:head {:status 200 :type "audio/mpeg" :length 1200}
     :get  {:status 200 :type "audio/mpeg" :length 1200}})

  (map :id (verdicts media-file served))
  (map (juxt :id :level) (verdicts media-file served :apple))
  #_.)
