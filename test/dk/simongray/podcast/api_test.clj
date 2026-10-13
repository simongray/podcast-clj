(ns dk.simongray.podcast.api-test
  "The shape that the public functions share, checked against their
  arglists: a map of options is the last parameter, a function that takes
  one can be called without it, and a function that ends in ! reads the
  clock instead of taking the time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]))

(def required-options
  "The functions that can't be called without their options, and why."
  '{dk.simongray.podcast.directory.podcastindex/request
    "it reads no clock, so the options give the :auth-date"
    dk.simongray.podcast.directory.podcastindex/remote-item-requests
    "as request"
    dk.simongray.podcast.directory.podcastindex/remote-value-requests
    "as request"
    dk.simongray.podcast.value.nwc/request-event
    "it reads no clock and makes no random bytes, so the options give them"
    dk.simongray.podcast.nostr.signer/request-event
    "as value.nwc/request-event"
    dk.simongray.podcast.value.recipients/pay!
    "it pays through the listener's wallet, whose functions the options give"
    dk.simongray.podcast.sync.gpodder.api/request
    "a call names the listener and the device, which the options give"
    dk.simongray.podcast.sync.gpodder.server/handler
    "a handler serves the :state and checks credentials with :authorized-pred"
    dk.simongray.podcast.comments.activitypub/requests
    "comments/requests calls it with the limits"
    dk.simongray.podcast.comments.atproto/requests
    "comments/requests calls it with the limits"
    dk.simongray.podcast.comments.nostr/requests
    "comments/requests calls it with the limits"
    dk.simongray.podcast.comments.atproto/readable?
    "comments/readable? calls it with the options"
    dk.simongray.podcast.comments.nostr/readable?
    "comments/readable? calls it with the options"})

(def options-before-the-end
  "The functions whose options aren't their last parameter: the readers of
  the comments, which comments/requests calls with a predicate after them."
  '#{dk.simongray.podcast.comments.activitypub/requests
     dk.simongray.podcast.comments.atproto/requests
     dk.simongray.podcast.comments.nostr/requests})

(defn- namespaces
  "The namespaces of the source that the JVM loads."
  []
  (for [^java.io.File file (file-seq (io/file "src"))
        :when (re-find #"\.cljc?$" (.getName file))]
    (-> (.getPath file)
        (str/replace #"^src/|\.cljc?$" "")
        (str/replace "/" ".")
        (str/replace "_" "-")
        symbol)))

(defn- param-name
  "The name of the parameter `param` of an arglist, which for a
  destructured parameter is its :as name."
  [param]
  (cond
    (symbol? param) param
    (map? param)    (:as param)
    (vector? param) (let [[k name] (take-last 2 param)]
                      (when (= :as k) name))))

(def functions
  "The documented public functions of the source by qualified name, each
  with its arglists as vectors of parameter names. A parameter after an
  ampersand can be left out, so the arglist ends before it."
  (delay
    (into (sorted-map)
          (for [ns  (namespaces)
                var (do (require ns) (vals (ns-publics ns)))
                :let [{:keys [name arglists no-doc macro]} (meta var)]
                :when (and arglists (not no-doc) (not macro))]
            [(symbol (str ns) (str name))
             (for [arglist arglists]
               (mapv param-name (take-while #(not= '& %) arglist)))]))))

(deftest options-are-the-last-parameter
  (doseq [[name arglists] @functions
          arglist         arglists
          :when (and (some #{'opts} arglist)
                     (not (contains? options-before-the-end name)))]
    (is (= 'opts (peek arglist)) (str name " takes " arglist)))
  (doseq [name options-before-the-end]
    (is (some #(and (some #{'opts} %) (not= 'opts (peek %))) (get @functions name))
        (str name " is listed, and its options are the last parameter"))))

(deftest options-can-be-left-out
  (let [required? (fn [arglists]
                    (and (seq arglists) (every? #(some #{'opts} %) arglists)))]
    (doseq [[name arglists] @functions
            :when (not (contains? required-options name))]
      (is (not (required? arglists))
          (str name " can't be called without its options: add the shorter arity, or list it with the reason")))
    (doseq [name (keys required-options)]
      (is (required? (get @functions name))
          (str name " is listed, and it can be called without its options")))))

(deftest only-pure-functions-take-the-time
  (doseq [[name arglists] @functions
          :when (str/ends-with? (str name) "!")
          arglist arglists]
    (is (not-any? #{'at} arglist)
        (str name " takes the time as " arglist ": read the clock, with :at in the options to fix it"))))
