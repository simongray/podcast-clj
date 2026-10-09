(ns dk.simongray.podcast.util.json
  "The JSON adapter (RFC 8259): strings in, plain data with string keys out,
  and back, on both platforms."
  (:require #?(:clj [clojure.data.json :as json])
            [clojure.string :as str])
  #?(:clj (:import [java.io PushbackReader StringReader])))

#?(:clj
   ;; The reader of the JVM reads such a number in a time that grows with
   ;; the square of its length, minutes for a million digits
   (defn- long-digits?
     "Whether a number in the JSON string `s` holds a run of more than a
     thousand digits. The digits in a string of the JSON are text, and
     don't count."
     [^String s]
     (let [n (.length s)]
       (loop [i 0 run 0 quoted? false]
         (cond
           (> run 1000)
           true

           (>= i n)
           false

           :else
           (let [c (int (.charAt s i))]
             (cond
               (= c 34)                  (recur (inc i) 0 (not quoted?))
               (and quoted? (= c 92))    (recur (+ i 2) 0 true)
               quoted?                   (recur (inc i) 0 true)
               (and (>= c 48) (<= c 57)) (recur (inc i) (inc run) false)
               :else                     (recur (inc i) 0 false))))))))

(defn read-str
  "The JSON string `s` as Clojure data with string keys.

  A byte order mark before the value is skipped. Anything but white space
  after the value is an error, on the JVM as in JavaScript. A value nested
  deeper than the stack reaches is also an error. On the JVM, so is a
  number with a run of more than a thousand digits. A hostile server can
  send such values to exhaust a reader."
  [s]
  (let [s (cond-> s (str/starts-with? s "\uFEFF") (subs 1))]
    #?(:clj  (let [_ (when (long-digits? s)
                       (throw (ex-info (str "The JSON holds a number of more"
                                            " than a thousand digits")
                                       {:type ::too-long})))
                   r (PushbackReader. (StringReader. s) 64)
                   v (try
                       (json/read r)
                       (catch StackOverflowError _
                         (throw (ex-info "The JSON is nested too deep to read"
                                         {:type ::too-deep}))))]
               (loop []
                 (let [c (.read r)]
                   (cond
                     (= -1 c)
                     v

                     (#{9 10 13 32} c)
                     (recur)

                     :else
                     (throw (ex-info "Data after the JSON value"
                                     {:type ::trailing-data}))))))
       :cljs (js->clj (js/JSON.parse s)))))

(defn ^:no-doc parse
  "The data in the JSON string `s`, or nil when `s` isn't JSON."
  [s]
  (try
    (read-str s)
    (catch #?(:clj Exception :cljs :default) _
      nil)))

(defn ^:no-doc object
  "The JSON object in the string `s`, or nil when `s` holds no JSON
  object."
  [s]
  (let [x (parse s)]
    (when (map? x) x)))

(defn ^:no-doc text
  "The JSON value `v` read for a field that holds text: a string as it is, a
  number as its digits, and nil for anything else, such as an object."
  [v]
  (when (or (string? v) (number? v))
    (str v)))

(defn ^:no-doc whole
  "The JSON value `v` read for a field that holds a whole number. It's `v`
  as an integer when it's a number without a fraction that JavaScript
  holds exactly, and nil for anything else."
  [v]
  (when (and (number? v)
             (== v (Math/floor v))
             (< (Math/abs (double v)) 9e15))
    (long v)))

(defn write-str
  "The JSON of `x`, indented when `opts` has :pretty?. Characters outside
  ASCII are written as they are, on the JVM as in JavaScript."
  ([x]
   (write-str x {}))
  ([x {:keys [pretty?] :as opts}]
   #?(:clj  (json/write-str x
                            :indent               (boolean pretty?)
                            :escape-slash         false
                            :escape-unicode       false
                            :escape-js-separators false)
      :cljs (if pretty?
              (js/JSON.stringify (clj->js x) nil 2)
              (js/JSON.stringify (clj->js x))))))

(comment
  (read-str "{\"title\": \"An episode\", \"duration\": 1800}")
  (write-str {"title" "An episode" "chapters" [{"startTime" 0}]}
             {:pretty? true})

  ;; nil for what is no JSON, and for JSON that is no object
  (parse "<html>")
  (object "[1, 2]")

  ;; a whole number, as JSON writes one
  (map whole [3 3.0 3.5 "3" 1e30])
  #_.)
