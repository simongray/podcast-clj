(ns dk.simongray.podcast.util.client
  "The requests of podcast-clj, which name it as their User-Agent unless
  they name another, e.g. by the :user-agent option."
  (:require [dk.simongray.wary-fetch :as http]))

(def user-agent
  "The User-Agent of a request unless the request or the options set one."
  "podcast-clj (+https://github.com/simongray/podcast-clj)")

(defn send!
  "Send the `request` as http/send! does, with user-agent as its
  :user-agent unless it has one. It's the :send of every function that
  sends, unless you give another."
  [request]
  (http/send! (update request :user-agent #(or % user-agent))))
