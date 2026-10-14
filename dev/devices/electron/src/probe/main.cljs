(ns probe.main
  "The main process of the Electron test: wary-fetch's handlers on the IPC,
  like-minded's folder store on Node, and the results, which it writes to
  results.json before it quits."
  (:require ["electron" :as electron]
            ["fs" :as fs]
            ["os" :as os]
            ["path" :as path]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.wary-fetch.electron :as wf-electron]
            [probe.checks :as checks]))

(def app-dir
  "The folder of the app, where results.json goes."
  (path/dirname js/__dirname))

(defn- finish!
  "Write the `results`, a vector for each check, to results.json and quit."
  [results]
  (let [platform (str "Electron " (.. js/process -versions -electron))]
    (fs/writeFileSync (path/join app-dir "results.json")
                      (js/JSON.stringify
                       (clj->js {:platform platform :results results})
                       nil 1)))
  (.quit (.-app electron)))

(defn- folder-check
  "A promise of the check of the folder store in a temporary folder."
  []
  (let [dir (path/join (.tmpdir os) (str "probe-" (js/Date.now)))]
    (.finally (checks/store-check "like-minded's folder store on Node"
                                  (folder/store dir))
              #(fs/rmSync dir #js {:recursive true :force true}))))

(defn- window!
  "Open the hidden window of the page, which reports its results."
  []
  (let [preferences #js {:preload          (path/join app-dir "preload.js")
                         :contextIsolation true
                         :sandbox          true}
        window      (electron/BrowserWindow. #js {:show           false
                                                  :webPreferences preferences})]
    (.loadFile window (path/join app-dir "index.html"))))

(defn main
  "Set up the handlers, check the folder store and open the page."
  []
  (let [ipc  (.-ipcMain electron)
        main (atom [])]
    (.handle ipc "local-origin" (fn [_] checks/origin))
    (.handle ipc "wary-fetch" (wf-electron/handler))
    (.handle ipc "wary-fetch-local"
             (wf-electron/handler {:allow-pred      (constantly true)
                                   :max-bytes       (* 1024 1024)
                                   :timeout-seconds 2}))
    ;; the page's results cross the IPC as plain arrays
    (.on ipc "results" (fn [_ page] (finish! (into @main (js->clj page)))))
    (js/setTimeout #(finish! (conj @main ["the page" false "timed out"]))
                   120000)
    ;; rather than Electron's dialog, which waits for a click
    (.on js/process "uncaughtException"
         #(finish! (conj @main ["the main process" false (str %)])))
    (-> (.whenReady (.-app electron))
        (.then folder-check)
        (.then (fn [check]
                 (reset! main [check])
                 (window!))))))
