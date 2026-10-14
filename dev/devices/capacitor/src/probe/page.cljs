(ns probe.page
  "The page of the Capacitor test, in the web view of an iOS app. It sends
  its requests through the native HTTP of Capacitor, shows the results and
  posts them to the local server."
  (:require ["@capacitor/core" :refer [Capacitor CapacitorHttp]]
            [clojure.string :as str]
            [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.wary-fetch.capacitor :as capacitor]
            [probe.checks :as checks]))

(def send
  "A :send over CapacitorHttp."
  (capacitor/sender CapacitorHttp))

(defn- show!
  "Show the `results` on the page."
  [results]
  (set! (.-innerHTML (js/document.getElementById "results"))
        (str/join (for [[check-name ok? detail] results]
                    (str "<li class=\"" (if ok? "pass" "fail") "\">"
                         (if ok? "✓ " "✗ ") check-name "<br><small>"
                         (str/escape detail {\< "&lt;" \& "&amp;"})
                         "</small></li>")))))

(defn- report!
  "Show the `results` and post them to the local server."
  [results]
  (show! results)
  (send {:method     :post
         :url        (str checks/origin "/results")
         :allow-pred (constantly true)
         :headers    {"Content-Type" "application/json"}
         :body       (js/JSON.stringify
                      (clj->js {:platform (str "Capacitor on "
                                               (.getPlatform Capacitor))
                                :results  results}))}))

(defn init
  "Run the checks and report their results."
  []
  (-> (checks/run-all!
       (concat (checks/library-checks {:send send})
               [(checks/store-check "like-minded's IndexedDB store"
                                    (indexeddb/store "probe"))]
               (checks/http-checks send)))
      (.then report!)
      (.catch #(report! [["the page" false (str %)]]))))
