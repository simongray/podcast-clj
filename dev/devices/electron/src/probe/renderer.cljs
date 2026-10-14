(ns probe.renderer
  "The page of the Electron test, which sends its requests through the
  main process with wary-fetch's electron sender. The main process applies
  its own limits, whatever the page asks for."
  (:require [dk.simongray.like-minded.store.indexeddb :as indexeddb]
            [dk.simongray.wary-fetch :as http]
            [dk.simongray.wary-fetch.electron :as wf-electron]
            [probe.checks :as checks :refer [describe error-type outcome]]))

(def bridge
  "The functions that preload.js gives the page."
  (.-waryFetch js/window))

(def send-public
  "A :send through the main process's handler with its defaults, which
  allow public URLs alone."
  (wf-electron/sender (.-send bridge)))

(def send-local
  "A :send through the main process's handler for the local server, with
  a limit of 1 MB and 2 seconds."
  (wf-electron/sender (.-sendLocal bridge)))

(defn- electron-checks
  "The checks of the limits of the main process, which apply whatever the
  page asks for."
  [origin]
  [(outcome "the main process refuses the Mac's own network"
            (send-public {:url (str origin "/feed.xml")})
            (fn [{:keys [error status]}]
              [(= ::http/refused (error-type error))
               (if error (describe error) (pr-str status))]))
   (outcome "the main process follows a redirect, with the hops listed"
            (send-local {:url (str origin "/redirect")})
            (fn [{:keys [status url redirects error]}]
              [(and (= 200 status) (= (str origin "/feed.xml") url)
                    (seq redirects))
               (or (some-> error describe)
                   (str status " " url ", " (count redirects) " hops"))]))
   (outcome "the main process caps the size that the page asks for"
            (send-local {:url (str origin "/big") :max-bytes (* 10 1024 1024)})
            (fn [{:keys [error body]}]
              [(= ::http/too-large (error-type error))
               (if error (describe error) (str "got " (count body)))]))
   (outcome "the main process caps the time that the page asks for"
            (send-local {:url (str origin "/slow") :timeout-seconds 10})
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
              [(= {:a [1 2]} body) (or (some-> error describe) (pr-str body))]))
   (outcome "text decoded by its charset, here ISO-8859-1"
            (send-local {:url (str origin "/latin1")})
            (fn [{:keys [body error]}]
              [(= "Æblegrød" body)
               (or (some-> error describe) (pr-str body))]))
   (outcome "a POST with a body of bytes"
            (send-local {:method :post
                         :url    (str origin "/echo")
                         :body   (js/Uint8Array. 1000)})
            (fn [{:keys [body error]}]
              [(= "POST 1000" body)
               (or (some-> error describe) (pr-str body))]))
   (outcome "a request whose signal stopped never crosses"
            (let [c (js/AbortController.)]
              (.abort c)
              (send-local {:url (str origin "/feed.xml") :signal (.-signal c)}))
            (fn [{:keys [error]}]
              [(http/aborted? error) (if error (describe error) "no error")]))])

(defn init
  "Run the checks and send their results to the main process."
  []
  (-> (.origin bridge)
      (.then (fn [origin]
               (checks/run-all!
                (concat (checks/library-checks {:send send-public})
                        [(checks/store-check "like-minded's IndexedDB store"
                                             (indexeddb/store "probe"))]
                        (electron-checks origin)))))
      ;; the IPC copies a ClojureScript vector as a stripped object, so the
      ;; results cross as plain arrays
      (.then #(.report bridge (clj->js %)))
      (.catch #(.report bridge #js [#js ["the page" false (str %)]]))))
