(ns dk.simongray.podcast.util.client-test
  "The sender of podcast-clj: the User-Agent that it gives a request, and
  the option that names another."
  (:require [clojure.test :refer [deftest is]]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.test-support :refer [run]]
            [dk.simongray.podcast.util.client :as client]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.async :as async]))

(deftest the-user-agent-of-a-request
  (with-redefs [http/send! identity]
    (is (= client/user-agent (:user-agent (client/send! {:url "https://a.example/"}))))
    (is (= "Mine" (:user-agent (client/send! {:url "https://a.example/" :user-agent "Mine"})))
        "unless the request names another")))

#?(:clj
   (deftest the-version-in-the-user-agent
     (let [version #"\(def version\s+\"[^\"]*\"\s+\"([^\"]+)\""]
       (is (= (str "podcast-clj/" (second (re-find version (slurp "build.clj"))))
              client/user-agent)
           "the version that build.clj builds"))))

(deftest the-user-agent-option
  (let [sent (atom [])
        send (fn [request]
               (swap! sent conj (:user-agent request))
               {:status 404 :url (:url request)})]
    (run #(async/then (podcast/fetch! "https://a.example/feed.xml" {:send send :user-agent "Mine"})
                      (fn [_]
                        (is (= ["Mine"] @sent) "an option of the functions that send"))))))
