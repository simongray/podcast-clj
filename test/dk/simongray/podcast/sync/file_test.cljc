(ns dk.simongray.podcast.sync.file-test
  "A folder as a document store: the store's contract on a temporary
  folder of each platform, and two devices kept in step through it. In
  ClojureScript also the files of an input, the permissions, and the
  failures that the listener can fix."
  (:require #?(:clj [clojure.java.io :as io])
            [clojure.test :refer [deftest is #?(:clj testing)]]
            [dk.simongray.podcast.profile :as profile]
            [dk.simongray.podcast.sync :as sync]
            [dk.simongray.podcast.sync.file :as file]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.util.async :as async]
            [dk.simongray.podcast.test-support :refer [t0 t1 t2 t3 a run same? #?(:clj with-temp-dir)]]))

(defn- temp-folder!
  "A new empty folder: a temporary directory on the JVM and in Node, and a
  folder of the origin private file system in a browser, as a promise in
  ClojureScript."
  []
  #?(:clj  (.toFile (java.nio.file.Files/createTempDirectory "podcast-test" (make-array java.nio.file.attribute.FileAttribute 0)))
     :cljs (if-let [storage (some-> js/globalThis .-navigator .-storage)]
             (.then (.getDirectory storage)
                    #(.getDirectoryHandle % (str "podcast-test-" (random-uuid)) #js {:create true}))
             (let [fs   (.getBuiltinModule js/process "fs/promises")
                   os   (.getBuiltinModule js/process "os")
                   path (.getBuiltinModule js/process "path")]
               (.mkdtemp fs (.join path (.tmpdir os) "podcast-test-"))))))

(defn- remove-folder!
  "Remove the folder `dir` of temp-folder! with everything in it."
  [dir]
  #?(:clj  (doseq [f (reverse (file-seq dir))]
             (io/delete-file f true))
     :cljs (if (string? dir)
             (.rm (.getBuiltinModule js/process "fs/promises") dir #js {:recursive true :force true})
             (.then (.getDirectory js/navigator.storage)
                    #(.removeEntry % (.-name dir) #js {:recursive true})))))

(defn- names!
  "The names of everything in the folder `dir`, as the platform lists it."
  [dir]
  #?(:clj  (set (.list ^java.io.File dir))
     :cljs (if (string? dir)
             (.then (.readdir (.getBuiltinModule js/process "fs/promises") dir) set)
             (let [it (.keys dir)]
               (letfn [(more [acc]
                         (.then (.next it) #(if (.-done %) acc (more (conj acc (.-value %))))))]
                 (more #{}))))))

(defn- spit!
  "Write `text` to the file `name` in the folder `dir`, past the store."
  [dir name text]
  #?(:clj  (spit (io/file dir name) text)
     :cljs (if (string? dir)
             (.writeFile (.getBuiltinModule js/process "fs/promises")
                         (.join (.getBuiltinModule js/process "path") dir name) text)
             (.then (.getFileHandle dir name #js {:create true})
                    (fn [h] (.then (.createWritable h) (fn [w] (.then (.write w text) #(.close w)))))))))

(deftest a-folder-store
  (let [dir     (atom nil)
        version (atom nil)
        call    (fn [k & args] (apply (k (file/store @dir)) args))
        failed? #(async/then (async/attempt %) :error)]
    (run #(async/then (temp-folder!) (fn [d] (reset! dir d)))
         #(async/then (call :list) (fn [docs] (is (= [] docs))))
         #(async/then (call :fetch "a.edn") (fn [doc] (is (nil? doc))))
         #(async/then (call :put "a.edn" "one" {})
                      (fn [{v :version}]
                        (is (string? v))
                        (reset! version v)))
         #(async/then (call :fetch "a.edn") (fn [doc] (is (= {:body "one" :version @version} doc))))
         #(async/then (call :list) (fn [docs] (is (= [{:name "a.edn" :version @version}] docs))))
         #(async/then (call :put "a.edn" "two" {:if-version "stale"})
                      (fn [answer] (is (= {:conflict? true} answer))))
         #(async/then (call :put "b.edn" "x" {:if-version @version})
                      (fn [answer] (is (= {:conflict? true} answer) "a version for a file that isn't there")))
         ;; bodies of another size than the first, since Safari keeps the
         ;; modification time of a file to the second
         #(async/then (async/all [(call :put "a.edn" "four" {:if-version @version})
                                  (call :put "a.edn" "three" {:if-version @version})])
                      (fn [answers] (is (= 1 (count (filter :version answers))) "of two puts on one version, one wins")))
         #(async/then (call :fetch "a.edn") (fn [doc] (is (= "four" (:body doc)))))
         #(spit! @dir ".hidden" "x")
         #(async/then (call :list) (fn [docs] (is (= ["a.edn"] (map :name docs)) "hidden files aren't documents")))
         #(async/then (names! @dir) (fn [names] (is (= #{".hidden" "a.edn"} names) "no temporary file is left behind")))
         #(async/then (failed? (fn [] (call :fetch "../a.edn"))) (fn [e] (is e)))
         #(async/then (failed? (fn [] (call :put ".hidden" "x" {}))) (fn [e] (is e)))
         #(async/then (call :delete "a.edn") (fn [_] (call :delete "a.edn")))
         #(async/then (call :list) (fn [docs] (is (= [] docs))))
         #(remove-folder! @dir))))

(deftest a-folder-checked
  (let [dir (atom nil)]
    (run #(async/then (temp-folder!) (fn [d] (reset! dir d)))
         #(async/then (store/check! (file/store @dir))
                      (fn [{:keys [ok? checks]}]
                        (is ok? (pr-str checks))
                        (is (every? :ok? checks))))
         #(async/then (names! @dir) (fn [names] (is (empty? names) "the probe is gone")))
         #(remove-folder! @dir))))

(deftest two-devices-through-a-folder
  (let [dir     (atom nil)
        phone   (atom nil)
        laptop  (atom nil)
        backend #(file/backend @dir {:opml? true})]
    (run #(async/then (temp-folder!) (fn [d] (reset! dir d)))
         #(async/then (sync/sync! (profile/subscribe (profile/make "phone") "feed-a" {:url "https://a/rss" :title "A"} t0)
                                  [(backend)] t1)
                      (fn [step] (reset! phone step)))
         #(async/then (sync/sync! (profile/progress (profile/make "laptop") a 60 3600 t1) [(backend)] t2)
                      (fn [step] (reset! laptop step)))
         #(async/then (sync/sync! (:state @phone) [(backend)] t3 {:cursors (:cursors @phone)})
                      (fn [{:keys [failed report state]}]
                        (is (nil? failed))
                        (is (= [] report))
                        (is (same? (:state @laptop) state))
                        (is (= 60 (:position (profile/episode-entry state a))))))
         #(async/then (names! @dir)
                      (fn [names] (is (= #{"phone.edn" "laptop.edn" "subscriptions.opml"} names))))
         #(remove-folder! @dir))))

#?(:clj
   (deftest a-folder-made-on-the-first-write
     (with-temp-dir
       (fn [dir]
         (let [{:keys [put list]} (file/store (io/file dir "sync"))]
           (is (= [] (list)) "a folder that isn't there yet lists nothing")
           (put "a.edn" "one" {})
           (is (.isDirectory (io/file dir "sync"))))))))

#?(:clj
   (deftest writes-that-race-or-fail
     (with-temp-dir
       (fn [dir]
         (let [{:keys [put fetch]} (file/store dir)]
           (put "podcast.edn" "0" {})
           (testing "of two puts on one version from two threads, one wins"
             (is (every? #(= 1 %)
                         (for [_ (range 50)]
                           (let [version (:version (fetch "podcast.edn"))
                                 latch   (java.util.concurrent.CountDownLatch. 1)
                                 race    #(future (.await latch) (put "podcast.edn" % {:if-version version}))
                                 puts    [(race "a") (race "b")]]
                             (.countDown latch)
                             (count (filter (comp :version deref) puts)))))))
           (testing "a put that fails leaves no temporary file behind"
             (.mkdirs (io/file dir "taken.edn"))
             (is (thrown? Exception (put "taken.edn" "x" {})))
             (is (= #{"podcast.edn" "taken.edn"} (set (.list ^java.io.File dir))))))))))

#?(:cljs
   (defn- picked
     "A file as a file input gives it, at `path` in the folder that the
     listener picked, holding `text`."
     [path text]
     #js {:name               (last (.split path "/"))
          :webkitRelativePath path
          :lastModified       1000
          :size               (count text)
          :text               #(js/Promise.resolve text)}))

#?(:cljs
   (deftest the-files-of-an-input
     (let [laptop (profile/progress (profile/make "laptop") a 60 3600 t1)
           files  [(picked "Podcasts/laptop.edn" (sync/document laptop t2))
                   (picked "Podcasts/notes.txt" "not a document")
                   (picked "Podcasts/old/phone.edn" "in a folder of the folder")]
           s      (file/store files)]
       (run #(is (= "Podcasts" (:name s)) "the picked folder names the store")
            #(is (nil? (:delete s)))
            #(async/then ((:list s))
                         (fn [docs] (is (= ["laptop.edn" "notes.txt"] (map :name docs)) "only the top of the folder")))
            #(async/then ((:fetch s) "laptop.edn")
                         (fn [{:keys [body version]}]
                           (is (= laptop (:state (sync/read-document body))))
                           (is (= "1000-" (subs version 0 5)))))
            #(async/then (async/attempt (fn [] ((:put s) "phone.edn" "x" {})))
                         (fn [{:keys [error]}] (is (= ::file/read-only (:type (ex-data error))))))
            #(async/then (sync/sync! (profile/make "phone") [(file/backend files)] t3)
                         (fn [{:keys [failed state]}]
                           (is (nil? failed) "the backend only pulls")
                           (is (= 60 (:position (profile/episode-entry state a))))))))))

#?(:cljs
   (defn- handle
     "A directory handle as a browser gives it, with `methods` by name."
     [methods]
     (clj->js (merge {:name "Podcasts" :getFileHandle (fn [_] (js/Promise.reject (js/DOMException. "" "NotFoundError")))}
                     methods))))

#?(:cljs
   (deftest permissions
     (let [asked (atom [])
           ask   (fn [answer] (fn [opts] (swap! asked conj (.-mode opts)) (js/Promise.resolve answer)))]
       (run #(async/then (file/query-permission! (handle {:queryPermission (ask "prompt")}))
                         (fn [answer] (is (= :prompt answer))))
            #(async/then (file/request-permission! (handle {:requestPermission (ask "granted")}))
                         (fn [answer] (is (= :granted answer))))
            #(is (= ["readwrite" "readwrite"] @asked))
            #(async/then (async/all [(file/query-permission! (handle {}))
                                     (file/request-permission! "/tmp/podcasts")
                                     (file/query-permission! [])])
                         (fn [answers] (is (= [:granted :granted :granted] answers) "nothing to ask for")))))))

#?(:cljs
   (deftest failures-that-the-listener-can-fix
     (let [refusing (fn [error] (handle {:values (fn [] #js {:next #(js/Promise.reject (js/DOMException. "" error))})}))
           type-of  (fn [s f] (async/then (async/attempt #(f s)) (comp :type ex-data :error)))]
       (run #(async/then (type-of (file/store (refusing "NotAllowedError")) (fn [s] ((:list s))))
                         (fn [t] (is (= ::file/not-allowed t))))
            #(async/then (type-of (file/store (refusing "NotFoundError")) (fn [s] ((:list s))))
                         (fn [t] (is (= ::file/gone t))))
            #(async/then (type-of (file/store (handle {})) (fn [s] ((:fetch s) "a.edn")))
                         (fn [t] (is (nil? t) "a document that isn't there is no failure")))
            (fn []
              (when-not (some-> js/globalThis .-FileSystemFileHandle .-prototype .-createWritable)
                (async/then (type-of (file/store (handle {})) (fn [s] ((:put s) "a.edn" "x" {})))
                            (fn [t] (is (= ::file/read-only t) "a platform that can't write")))))))))
