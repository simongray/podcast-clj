(ns dk.simongray.podcast.json
  "The JSON adapter: strings in, plain data with string keys out, and back,
  on both platforms."
  (:require #?(:clj [clojure.data.json :as json])
            [clojure.string :as str])
  #?(:clj (:import [java.io PushbackReader StringReader])))

#?(:clj
   (defn- long-digits?
     "Whether the string `s` holds a run of more than a thousand digits: a
     number that the reader of the JVM reads in a time that grows with the
     square of its length, minutes for a million digits."
     [^String s]
     (let [n (.length s)]
       (loop [i 0 run 0]
         (cond
           (>= i n)    false
           (> run 1000) true
           :else       (let [c (int (.charAt s i))]
                         (recur (inc i) (if (and (>= c 48) (<= c 57)) (inc run) 0))))))))

(defn read-str
  "Parse the JSON string `s` into Clojure data with string keys. A byte order
  mark before the value is skipped, and anything but white space after it
  is an error, on the JVM as in JavaScript. A value nested deeper than the
  stack reaches is also an error. On the JVM, so is a run of more than a
  thousand digits. A hostile server can send either to exhaust a reader."
  [s]
  (let [s (cond-> s (str/starts-with? s "\uFEFF") (subs 1))]
    #?(:clj  (let [_ (when (long-digits? s)
                       (throw (ex-info "The JSON holds a run of more than a thousand digits" {:type ::too-long})))
                   r (PushbackReader. (StringReader. s) 64)
                   v (try
                       (json/read r)
                       (catch StackOverflowError _
                         (throw (ex-info "The JSON is nested too deep to read" {:type ::too-deep}))))]
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
