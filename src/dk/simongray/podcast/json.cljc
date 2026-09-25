(ns dk.simongray.podcast.json
  "The JSON adapter: strings in, plain data with string keys out, and back.
  Everything that reads JSON (chapters, transcripts, Podcast Index) goes
  through here so that the platform choice is made once."
  #?(:clj (:require [clojure.data.json :as json])))

(defn read-str
  "Parse the JSON string `s` into Clojure data with string keys."
  [s]
  #?(:clj  (json/read-str s)
     :cljs (js->clj (js/JSON.parse s))))

(defn write-str
  "Serialize `x` as JSON, indented when `opts` has `:pretty?`."
  ([x]
   (write-str x {}))
  ([x {:keys [pretty?]}]
   #?(:clj  (json/write-str x :indent (boolean pretty?) :escape-slash false)
      :cljs (if pretty?
              (js/JSON.stringify (clj->js x) nil 2)
              (js/JSON.stringify (clj->js x))))))
