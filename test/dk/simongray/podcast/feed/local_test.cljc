(ns dk.simongray.podcast.feed.local-test
  "The feed of a show of local files: the episodes in the order of their
  names, their titles, dates and media from the tags, and the guids that
  two devices with the same files agree on, on both platforms."
  (:require #?(:cljs [dk.simongray.podcast.test-setup])
            [clojure.test :refer [deftest is]]
            [dk.simongray.podcast.feed.local :as local]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.podcast.test-support.media :refer [jpeg]]
            [dk.simongray.webcrypto.bytes :as bytes]))

(defn- file
  "A local file of the `name`, read as an MP3 file with the `tags`."
  [name tags & {:as more}]
  (merge {:url   (str "file:///Users/ann/Books/Dune/" name)
          :name  name
          :media {:format :mp3 :duration 600.4 :bytes 9600000 :tags tags}}
         more))

(def dune
  {:album "Dune" :artist "Frank Herbert"})

(deftest a-show-of-local-files
  (let [files [(file "Chapter 10.mp3" dune)
               (file "Chapter 2.mp3" (assoc dune :title "The sietch" :date "1965"))
               (file "chapter 1.mp3" (assoc dune :album "Dune (unabridged)"))]
        feed  (local/feed files)
        items (:rss/items feed)]
    (is (= ["chapter 1" "The sietch" "Chapter 10"] (map :rss/title items))
        "in the order of their names as a person reads them, titled by their tags or else their names")
    (is (= [1 2 3] (map :itunes/episode items)))
    (is (= "serial" (:itunes/type feed)) "so that an app plays them in order")
    (is (= ["Dune" "Frank Herbert"] [(:rss/title feed) (:itunes/author feed)]) "the album and artist that most files name")
    (is (= {:url "file:///Users/ann/Books/Dune/chapter 1.mp3" :type "audio/mpeg" :length 9600000}
           (:rss/enclosure (first items))))
    (is (= [600 "chapter 1.mp3"] ((juxt :itunes/duration (comp :content :rss/guid)) (first items))))
    (is (= [nil #inst "1965-01-01T00:00:00Z" nil] (map :rss/pub-date items)) "a date of a year alone")
    (is (= (:podcast/guid feed) (:podcast/guid (local/feed (reverse files))))
        "the same show from the same files, in any order, as on another device")
    (is (not= (:podcast/guid feed) (:podcast/guid (local/feed files {:title "Other"}))))))

(deftest what-the-app-gives
  (let [feed (local/feed [(file "a.m4a" {} :type "audio/x-m4a" :modified #inst "2026-01-02T00:00:00Z"
                                :media {:format :mp4 :tags {} :artwork {:type "image/jpeg" :data (bytes/of jpeg)}})]
                         {:title "Recordings" :author "Ann" :guid "my-guid"})]
    (is (= ["my-guid" "Recordings" "Ann"] ((juxt :podcast/guid :rss/title :itunes/author) feed)))
    (is (= ["audio/x-m4a" #inst "2026-01-02T00:00:00Z"]
           ((juxt (comp :type :rss/enclosure) :rss/pub-date) (first (:rss/items feed))))
        "a type of its own, and the time the file changed when its tags give no date")
    (is (= (str "data:image/jpeg;base64," (bytes/base64 (bytes/of jpeg))) (resolve/artwork feed))
        "the artwork of the first file that has one"))
  (is (thrown? #?(:clj clojure.lang.ExceptionInfo :cljs js/Error) (local/feed [(file "a.mp3" {})]))
      "no title from the app or the tags"))
