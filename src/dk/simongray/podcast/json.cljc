(ns dk.simongray.podcast.json
  "The JSON adapter: strings in, plain data with string keys out, and back,
  on both platforms."
  (:require #?(:clj [clojure.data.json :as json])
            [clojure.string :as str])
  #?(:clj (:import [java.io PushbackReader StringReader])))

(defn read-str
  "Parse the JSON string `s` into Clojure data with string keys. A byte order
  mark before the value is skipped, and anything but white space after it
  is an error, on the JVM as in JavaScript."
  [s]
  (let [s (cond-> s (str/starts-with? s "\uFEFF") (subs 1))]
    #?(:clj  (let [r (PushbackReader. (StringReader. s) 64)
                   v (json/read r)]
               (loop []
                 (let [c (.read r)]
                   (cond
                     (= -1 c)          v
                     (#{9 10 13 32} c) (recur)
                     :else             (throw (ex-info "Data after the JSON value" {:type ::trailing-data}))))))
       :cljs (js->clj (js/JSON.parse s)))))

(defn ^:no-doc parse
  "The data in the JSON string `s`, as `read-str` reads it, or nil when `s`
  is not JSON."
  [s]
  (try
    (read-str s)
    (catch #?(:clj Exception :cljs :default) _
      nil)))

(defn ^:no-doc text
  "The JSON value `v` read for a field that holds text: a string as it is, a
  number as its digits, and nil for anything else, such as an object."
  [v]
  (when (or (string? v) (number? v))
    (str v)))

(defn write-str
  "Serialize `x` as JSON, indented when `opts` has `:pretty?`. Characters
  outside ASCII are written as they are, on the JVM as in JavaScript."
  ([x]
   (write-str x {}))
  ([x {:keys [pretty?]}]
   #?(:clj  (json/write-str x :indent (boolean pretty?) :escape-slash false
                            :escape-unicode false :escape-js-separators false)
      :cljs (if pretty?
              (js/JSON.stringify (clj->js x) nil 2)
              (js/JSON.stringify (clj->js x))))))
