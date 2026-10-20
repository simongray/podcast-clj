(ns probe.checks
  "The checks that every device test runs: the library on a real feed, a
  document store, and requests against the local server of
  dev/devices/server.mjs.

  A check is a promise of a vector of its name, whether it passed, and a
  detail to show."
  (:require [clojure.string :as str]
            [dk.simongray.podcast :as podcast]
            [dk.simongray.podcast.app.library :as library]
            [dk.simongray.podcast.feed.resolve :as resolve]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.url :as url]))

(def origin
  "The local server on the Mac, which a simulator reaches at localhost."
  "http://127.0.0.1:8771")

(def feed-url
  "A real feed, with chapters, a transcript and an ID3-tagged media file."
  "https://feeds.podcastindex.org/pc20.xml")

(def show-guid
  "The guid that names the show of feed-url."
  "917393e3-1b1e-5cef-ace4-edaa54e1f810")

(defn outcome
  "A promise of a check named `check-name` of the promise `p`, judged by
  `judge`, a function of its value, or of {:error e} when it rejects, that
  returns whether it passed and a detail."
  [check-name p judge]
  (-> (js/Promise.resolve p)
      (.then judge #(judge {:error %}))
      (.then (fn [[ok? detail]] [check-name (boolean ok?) (str detail)]))
      (.catch (fn [e] [check-name false (str "the check threw: " e)]))))

(defn error-type
  "The :type of the failure `e`: an exception, or a diagnostic map."
  [e]
  (or (:type e) (:type (ex-data e))))

(defn describe
  "The failure `e` in a few words."
  [e]
  (if (map? e)
    (pr-str (select-keys e [:id :type :reason]))
    (str (error-type e) " " (ex-message e))))

(defn library-checks
  "The checks of the library on feed-url, which send with the :send of
  `opts`, and save to its :store, when it gives one."
  [{:keys [store] :as opts}]
  (let [options (select-keys opts [:send])
        fetched (podcast/fetch! feed-url options)
        lib     (.then fetched
                       #(library/subscribe (library/make "probe" {:name "Probe"})
                                           feed-url % {}))
        newest  (.then lib
                       (fn [l]
                         (let [[show item] (first (library/episodes
                                                   l {:order :newest
                                                      :limit 1}))]
                           (library/episode-id l show item))))]
    (cond->
     [(outcome "podcast/fetch! of a real feed"
               fetched
               (fn [{:keys [status feed error]}]
                 [(and (= 200 status) (seq (:rss/items feed)))
                  (or (some-> error describe)
                      (str status " " (resolve/title feed) ", "
                           (count (:rss/items feed)) " items"))]))
      (outcome "library/subscribe names the show by its guid"
               lib
               (fn [l]
                 (let [guids (keys (library/subscriptions l))]
                   [(= [show-guid] guids)
                    (or (some-> (:error l) describe) (pr-str guids))])))
      (outcome "library/read-files! reads the chapters, transcript and media"
               (.then (js/Promise.all #js [lib newest])
                      (fn [[l id]] (library/read-files! l id options)))
               (fn [{:keys [chapters transcript media error]}]
                 [(and (nil? error)
                       (seq (:chapters (:document chapters)))
                       (seq (:segments (:document transcript)))
                       (:duration media))
                  (str (count (:chapters (:document chapters))) " chapters, "
                       (count (:segments (:document transcript)))
                       " segments, " (:duration media) " seconds"
                       (some->> (or error (:error media)) describe
                                (str ", ")))]))
      (outcome "library/search finds an episode"
               (.then lib #(library/search % "boostagram"))
               (fn [results]
                 (if (:error results)
                   [false (describe (:error results))]
                   [(seq results)
                    (str (count results) " results, the first "
                         (pr-str (some-> (first results) :item
                                         :rss/title)))])))]

      store
      (conj (outcome "library/save! to the store and restore! from it"
                     (.then lib
                            (fn [l]
                              (.then (library/save! l store)
                                     (fn [saved]
                                       (.then (library/restore!
                                               (library/make "probe") store)
                                              #(vector saved (:library %)))))))
                     (fn [[saved back :as v]]
                       (if (:error v)
                         [false (describe (:error v))]
                         [(and (nil? (:error saved))
                               (= [show-guid]
                                  (keys (library/subscriptions back))))
                          (str "put " (count (:put saved))
                               " documents, restored "
                               (pr-str (keys (library/subscriptions
                                              back))))])))))))

(defn store-check
  "A check that the document store `store` named `store-name` puts,
  fetches, lists and deletes a document."
  [store-name store]
  (let [put    (:put store)
        fetch  (:fetch store)
        list   (:list store)
        delete (:delete store)]
    (outcome (str store-name " puts, fetches, lists and deletes")
             (-> (js/Promise.resolve (put "probe.edn" "{:x 1}" {}))
                 (.then #(fetch "probe.edn" {}))
                 (.then (fn [got]
                          (.then (js/Promise.resolve (list))
                                 #(vector (str (:body got)) (mapv :name %)))))
                 (.then (fn [[body listed]]
                          (.then (js/Promise.resolve (delete "probe.edn"))
                                 (fn [_]
                                   (.then (js/Promise.resolve (list))
                                          #(vector body listed
                                                   (mapv :name %))))))))
             (fn [[body listed after :as v]]
               (if (:error v)
                 [false (describe (:error v))]
                 [(and (= "{:x 1}" body)
                       (some #{"probe.edn"} listed)
                       (not-any? #{"probe.edn"} after))
                  (pr-str body)])))))

(defn http-checks
  "The checks of requests sent with `send`, a :send of wary-fetch, to the
  local server."
  [send]
  (let [send-local #(send (merge {:allow-pred (constantly true)} %))]
    [(outcome "url/public? refuses the Mac's own network"
              (podcast/fetch! (str origin "/feed.xml")
                              {:send send :allow-pred url/public?})
              (fn [{:keys [error status]}]
                [(= ::http/refused (error-type error))
                 (if error (describe error) (pr-str status))]))
     (outcome "a redirect is followed, with the hops listed"
              (send-local {:url (str origin "/redirect")})
              (fn [{:keys [status url body redirects error]}]
                [(and (= 200 status) (= (str origin "/feed.xml") url)
                      (str/includes? (str body) "Local") (seq redirects))
                 (or (some-> error describe)
                     (str status " " url ", " (count redirects) " hops"))]))
     (outcome ":allow-pred is asked at each hop of a redirect"
              (send {:url        (str origin "/redirect")
                     :allow-pred #(str/ends-with? % "/redirect")})
              (fn [{:keys [error status]}]
                [(= ::http/refused (error-type error))
                 (if error (describe error) (pr-str status))]))
     (outcome ":max-bytes stops a body that's too large"
              (send-local {:url (str origin "/big") :max-bytes (* 1024 1024)})
              (fn [{:keys [error body]}]
                [(= ::http/too-large (error-type error))
                 (if error (describe error) (str "got " (count body)))]))
     (outcome ":timeout-seconds stops a slow answer"
              (send-local {:url (str origin "/slow") :timeout-seconds 2})
              (fn [{:keys [error body]}]
                [(= ::http/timeout (error-type error))
                 (if error (describe error) (str "got " body))]))
     (outcome "a body as bytes"
              (send-local {:url (str origin "/feed.xml") :as :bytes})
              (fn [{:keys [body error]}]
                [(instance? js/Uint8Array body)
                 (or (some-> error describe) (str (.-length body) " bytes"))]))
     (outcome "a body as JSON"
              (send-local {:url (str origin "/json") :as :json})
              (fn [{:keys [body error]}]
                [(= {:a [1 2]} body)
                 (or (some-> error describe) (pr-str body))]))
     (outcome "text decoded by its charset, here ISO-8859-1"
              (send-local {:url (str origin "/latin1")})
              (fn [{:keys [body error]}]
                [(= "Æblegrød" body)
                 (or (some-> error describe) (pr-str body))]))
     (outcome "a POST with a body of bytes and no Content-Type"
              (send-local {:method :post
                           :url    (str origin "/echo")
                           :body   (js/Uint8Array. 1000)})
              (fn [{:keys [body error]}]
                [(= "POST 1000" body)
                 (or (some-> error describe) (pr-str body))]))
     (outcome "the User-Agent reaches the server"
              (send-local {:url (str origin "/ua") :user-agent "probe/1"})
              (fn [{:keys [body error]}]
                [(= "probe/1" body)
                 (or (some-> error describe) (pr-str body))]))
     (outcome "a request whose signal stopped is never sent"
              (let [c (js/AbortController.)]
                (.abort c)
                (send-local {:url    (str origin "/feed.xml")
                             :signal (.-signal c)}))
              (fn [{:keys [error]}]
                [(http/aborted? error)
                 (if error (describe error) "no error")]))]))

(defn run-all!
  "A promise of the results of the `checks`, a vector for each."
  [checks]
  (.then (js/Promise.all (clj->js checks)) js->clj))
