(ns dk.simongray.podcast.util.client
  "The requests of podcast-clj, which name it as their User-Agent unless
  they name another, e.g. by the :user-agent option."
  (:require [dk.simongray.wary-fetch :as http]))

;; RFC 9110, section 10.1.5: a product and its version. Hosts behind
;; Cloudflare refuse an agent with a URL in it, as of 2026-10-11.
(def user-agent
  "The User-Agent of a request unless the request or the options set one."
  "podcast-clj/0.1.0-SNAPSHOT")

(defn send!
  "Send the `request` as http/send! does, with user-agent as its
  :user-agent unless it has one. It's the :send of every function that
  sends, unless you give another."
  [request]
  (http/send! (update request :user-agent #(or % user-agent))))
