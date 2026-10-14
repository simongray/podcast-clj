(ns probe.app
  "The React Native test, an Expo app. It sends its requests with
  wary-fetch's send!, keeps documents in like-minded's stores over MMKV
  and expo-file-system, shows the results and posts them to the local
  server."
  (:require ["@xmldom/xmldom" :as xmldom]
            ["expo" :as expo]
            ["expo-file-system" :refer [Directory File Paths]]
            ["react" :as react]
            ["react-native" :as rn]
            ["react-native-mmkv" :refer [createMMKV]]
            [dk.simongray.like-minded.store.folder :as folder]
            [dk.simongray.like-minded.store.kv :as kv]
            [dk.simongray.wary-fetch :as http]
            [probe.checks :as checks]))

(defn- environment
  "Which globals the JavaScript engine has, before the test installs a
  DOMParser."
  []
  (let [crypto (aget js/globalThis "crypto")]
    {"DOMParser"     (some? (aget js/globalThis "DOMParser"))
     "document"      (some? (aget js/globalThis "document"))
     "WorkerGlobalScope" (some? (aget js/globalThis "WorkerGlobalScope"))
     "TextDecoder"   (some? (aget js/globalThis "TextDecoder"))
     "crypto.subtle" (some? (some-> crypto .-subtle))
     "Hermes"        (some? (aget js/globalThis "HermesInternal"))}))

(def mmkv
  "The MMKV instance of the test."
  (createMMKV #js {:id "probe"}))

(def kv-store
  "like-minded's key-value store over MMKV."
  (kv/store {:get    #(.getString mmkv %)
             :set    #(.set mmkv %1 %2)
             :delete #(.remove mmkv %)
             :keys   #(.getAllKeys mmkv)
             :name   "MMKV"}))

(def folder-dir
  "The folder of the folder store, in the app's documents."
  (Directory. (.-document Paths) "probe-folder"))

(defn- file-of
  "The file `file-name` in folder-dir."
  [file-name]
  (File. folder-dir file-name))

(def folder-store
  "like-minded's folder store over expo-file-system."
  (folder/store
   {:read   (fn [file-name]
              (let [f (file-of file-name)]
                (when (.-exists f) (.bytes f))))
    :write  (fn [file-name bytes]
              (when-not (.-exists folder-dir) (.create folder-dir))
              (.write (file-of file-name) bytes))
    :stat   (fn [file-name]
              (let [f (file-of file-name)]
                (when (.-exists f)
                  {:modified (.-modificationTime f) :size (.-size f)})))
    :names  (fn []
              (if (.-exists folder-dir)
                (->> (.list folder-dir)
                     (filter #(instance? File %))
                     (mapv #(.-name %)))
                []))
    :delete (fn [file-name] (.delete (file-of file-name)))
    :name   "Documents"}))

(defn- check-all!
  "A promise of the environment and the results of every check."
  []
  (let [env (environment)]
    (when-not (aget js/globalThis "DOMParser")
      (set! (.-DOMParser js/globalThis) (.-DOMParser xmldom)))
    (-> (checks/run-all!
         (concat (checks/library-checks {:send http/send! :store kv-store})
                 [(checks/store-check "like-minded's kv store over MMKV"
                                      kv-store)
                  (checks/store-check
                   "like-minded's folder store over expo-file-system"
                   folder-store)]
                 (checks/http-checks http/send!)))
        (.catch #(vector ["the app" false (str %)]))
        (.then (fn [results]
                 {:platform    "React Native in Expo"
                  :environment env
                  :results     results})))))

(defn- report!
  "Post the `outcome` to the local server."
  [outcome]
  (js/fetch (str checks/origin "/results")
            #js {:method  "POST"
                 :headers #js {"Content-Type" "application/json"}
                 :body    (js/JSON.stringify (clj->js outcome))}))

(def title-style
  "The style of the screen's title."
  #js {:fontWeight "bold" :fontSize 17 :marginBottom 10})

(defn- line
  "A line of text, in `color`, with the React key `k`."
  [k text color]
  (react/createElement rn/Text
                       #js {:key k :style #js {:color color :marginBottom 6}}
                       text))

(defn- root
  "The screen, which runs the checks once and shows their results."
  []
  (let [[outcome set-outcome] (react/useState nil)]
    (react/useEffect
     (fn []
       (.then (check-all!) (fn [o] (set-outcome o) (report! o)))
       js/undefined)
     #js [])
    (react/createElement
     rn/ScrollView #js {:style #js {:marginTop 60 :paddingHorizontal 12}}
     (react/createElement rn/Text #js {:style title-style}
                          "podcast-clj in React Native")
     (if outcome
       (into-array
        (for [[check-name ok? detail] (:results outcome)]
          (line check-name (str (if ok? "✓ " "✗ ") check-name "\n  " detail)
                (if ok? "#1a7f37" "#cf222e"))))
       (line "running" "running…" "#555")))))

(defn init
  "Register the screen, which runs the checks."
  []
  (expo/registerRootComponent root))
