(ns dk.simongray.podcast.sync.file
  "A folder as a document store, e.g. one that a sync client mirrors.

  The folder can be a Git working tree, or a folder of iCloud Drive,
  Dropbox, Google Drive, OneDrive, Seafile, Syncthing or Nextcloud. Each
  device writes only its own file, so the sync client has no conflicts to
  resolve. The folder is one of these:

  - a path, on the JVM and in Node
  - a FileSystemDirectoryHandle in a browser, e.g. a folder that the
    listener picked with showDirectoryPicker
  - the files of a file input or a drop in a browser, which are read only

  In a browser that can't pick a folder, the listener can still sync
  through one by hand: save app.library/document into the folder, and load
  the folder's files with a file input. A browser's permission for a
  picked folder can lapse between visits, so check it with
  query-permission! before a sync, and renew it with request-permission!
  from a click.

  A file is written whole and replaces the old one, so a sync client never
  sees half of one. The version of a file is its modification time and
  size, which tells two writes apart as finely as the file system keeps
  time. In a browser, the failures that the listener can fix have a :type
  in their ex-data:

  - ::not-allowed, a permission to renew
  - ::gone, a folder that was moved, deleted or unplugged
  - ::read-only, a folder that the browser can't write to"
  (:require #?(:clj [clojure.java.io :as io])
            [clojure.string :as str]
            [dk.simongray.podcast.sync.store :as store]
            [dk.simongray.podcast.util.async :as async])
  #?(:clj (:import [java.io File FileNotFoundException]
                   [java.nio.file CopyOption Files LinkOption StandardCopyOption]
                   [java.util.concurrent ConcurrentHashMap]
                   [java.util.concurrent.locks ReentrantLock]
                   [java.util.function Function])))

(defonce ^{:no-doc true
           :doc    "The puts under way, so that a put checks the version and
  writes in one step: a lock for each file by its canonical path on the
  JVM, and in ClojureScript the last put to each file, where the platform
  has no Web Locks."}
  locks
  #?(:clj (ConcurrentHashMap.) :cljs (atom {})))

(defn- exclusively
  "The result of `f`, called when no other put to the file `k` is under way
  in this process, or in any tab of this site in a browser."
  [k f]
  #?(:clj  (let [l ^ReentrantLock (.computeIfAbsent ^ConcurrentHashMap locks k
                                                    (reify Function (apply [_ _] (ReentrantLock.))))]
             (.lock l)
             (try
               (f)
               (finally
                 (.unlock l))))
     :cljs (if-let [web-locks (some-> js/globalThis .-navigator .-locks)]
             (.request web-locks (str "dk.simongray.podcast.sync.file/" k) (fn [_] (f)))
             (let [result (.then (get @locks k (js/Promise.resolve)) (fn [_] (f)))]
               (swap! locks assoc k (.then result (constantly nil) (constantly nil)))
               result))))

#?(:clj
   (defn- version
     "The version of the file `f`: its modification time, as finely as the
     filesystem keeps it, and its length."
     [^File f]
     (str (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0)) "-" (.length f))))

#?(:clj
   (defn- write-whole!
     "Write `body` to the file `f` in `dir` through a hidden temporary file
     that's moved into place, so that no reader sees a partial file. The
     temporary file is removed when the write fails."
     [^File dir ^File f ^String body]
     (.mkdirs dir)
     (let [tmp (File/createTempFile ".sync-" ".tmp" dir)]
       (try
         (spit tmp body :encoding "UTF-8")
         (Files/move (.toPath tmp) (.toPath f)
                     (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
         (finally
           (.delete tmp))))))

#?(:clj
   (defn- jvm-folder
     "The operations of a store on the directory `dir`, a path or a File."
     [dir]
     (let [dir  (io/file dir)
           file #(io/file dir %)]
       {:name     (str dir)
        :lock-key #(.getCanonicalPath ^File (file %))
        :files    (fn []
                    (for [^File f (or (.listFiles dir) (make-array File 0))
                          :when (.isFile f)]
                      {:name (.getName f) :version (version f)}))
        :read     (fn [name]
                    (let [^File f (file name)]
                      ;; the version before the body: a file replaced in between
                      ;; then reads as changed next time, not as unchanged
                      (try
                        (when (.isFile f)
                          (let [v (version f)]
                            {:body (slurp f :encoding "UTF-8") :version v}))
                        (catch FileNotFoundException _
                          nil))))
        :version  (fn [name]
                    (let [^File f (file name)]
                      (when (.isFile f)
                        (version f))))
        :write    (fn [name body]
                    (let [f (file name)]
                      (write-whole! dir f body)
                      (version f)))
        :delete   (fn [name]
                    (.delete ^File (file name))
                    nil)})))

#?(:cljs
   (defn- node-folder
     "The operations of a store on the directory at `path`, through the file
     system of Node."
     [path]
     (let [fs      (.getBuiltinModule js/process "fs/promises")
           join    (.-join (.getBuiltinModule js/process "path"))
           file    #(join path %)
           version (fn [^js s] (str (.-mtimeNs s) "-" (.-size s)))
           absent  #(when-not (= "ENOENT" (.-code %)) (throw %))
           stat    #(-> (.stat fs (file %) #js {:bigint true})
                        (.then (fn [s] (when (.isFile s) s)))
                        (.catch absent))]
       {:name     path
        :lock-key file
        :files    (fn []
                    (-> (.readdir fs path #js {:withFileTypes true})
                        (.then (fn [entries]
                                 (async/all (for [e entries :when (.isFile e)]
                                              (.then (stat (.-name e))
                                                     (fn [s] {:name (.-name e) :version (version s)}))))))
                        (.catch #(do (absent %) []))))
        :read     (fn [name]
                    (.then (stat name)
                           (fn [s]
                             (when s
                               (.then (.readFile fs (file name) "utf8")
                                      (fn [body] {:body body :version (version s)}))))))
        :version  (fn [name]
                    (.then (stat name) #(some-> % version)))
        :write    (fn [name body]
                    (let [tmp (file (str ".sync-" (random-uuid) ".tmp"))]
                      (-> (.mkdir fs path #js {:recursive true})
                          (.then #(.writeFile fs tmp body "utf8"))
                          (.then #(.rename fs tmp (file name)))
                          (.then #(stat name))
                          (.then version)
                          (.catch (fn [e] (.then (.rm fs tmp #js {:force true}) #(throw e)))))))
        :delete   (fn [name]
                    (-> (.unlink fs (file name))
                        (.then (constantly nil))
                        (.catch absent)))})))

#?(:cljs
   (defn- failure
     "The error `e` of a browser's file system as an ex-info with a :type,
     when the listener can fix it, or else `e`."
     [e]
     (case (some-> e .-name)
       "NotAllowedError" (ex-info "The browser needs the listener's permission for the folder again"
                                  {:type ::not-allowed} e)
       "NotFoundError"   (ex-info "The folder is gone: it was moved or deleted, or its drive was removed"
                                  {:type ::gone} e)
       e)))

#?(:cljs
   (defn- collected
     "A promise of the values of the async iterator `it`, as a vector."
     [it]
     (letfn [(more [acc]
               (.then (.next it) #(if (.-done %) acc (more (conj acc (.-value %))))))]
       (more []))))

#?(:cljs
   (defn- write-handle!
     "Write `body` to the file of the handle `h` as a whole, and give a
     promise of the File it holds then.

     Nothing reaches the file before the stream closes (File System, section
     2.3.2), and a failed write is aborted, so the old file stays."
     [h body]
     (.then (.createWritable h)
            (fn [w]
              (-> (.write w body)
                  (.then #(.close w))
                  (.catch (fn [e] (.then (.abort w) #(throw e) #(throw e))))
                  (.then #(.getFile h)))))))

#?(:cljs
   (defn- handle-folder
     "The operations of a store on the directory handle `dir`, through the
     File System standard."
     [dir]
     (let [version  #(str (.-lastModified %) "-" (.-size %))
           missing? #(contains? #{"NotFoundError" "TypeMismatchError"} (.-name %))
           failed   #(throw (failure %))
           file     #(-> (.getFileHandle dir %)
                         (.then (fn [h] (.getFile h)))
                         (.catch (fn [e] (when-not (missing? e) (failed e)))))]
       {:name     (or (not-empty (.-name dir)) "folder")
        :lock-key identity
        :files    (fn []
                    (-> (collected (.values dir))
                        (.then (fn [handles]
                                 (async/all (for [h handles :when (= "file" (.-kind h))]
                                              (.then (.getFile h)
                                                     (fn [f] {:name (.-name h) :version (version f)}))))))
                        (.catch failed)))
        :read     (fn [name]
                    (.then (file name)
                           (fn [f]
                             (when f
                               (.then (.text f) (fn [body] {:body body :version (version f)}))))))
        :version  (fn [name]
                    (.then (file name) #(some-> % version)))
        :write    (fn [name body]
                    ;; asked of the platform, before getFileHandle makes an empty file
                    (if-not (some-> js/globalThis .-FileSystemFileHandle .-prototype .-createWritable)
                      (throw (ex-info "This browser can't write to the folder" {:type ::read-only}))
                      (-> (.getFileHandle dir name #js {:create true})
                          (.then #(write-handle! % body))
                          (.then version)
                          (.catch failed))))
        :delete   (fn [name]
                    (-> (.removeEntry dir name)
                        (.then (constantly nil))
                        (.catch #(when-not (missing? %) (failed %)))))})))

(defn- folder-store
  "The store over the operations of a `folder`."
  [{:keys [files read version write delete lock-key] :as folder}]
  (let [plain #(if (or (str/blank? (str %)) (re-find #"[/\\]" (str %)) (str/starts-with? (str %) "."))
                 (throw (ex-info "A document's name is a plain file name" {:name %}))
                 %)]
    {:name   (:name folder)
     :fetch  (fn [name] (read (plain name)))
     :put    (fn [name body opts]
               (exclusively (lock-key (plain name))
                            #(async/then (version name)
                                         (fn [current]
                                           (if (and (contains? opts :if-version) (not= (:if-version opts) current))
                                             {:conflict? true}
                                             (async/then (write name (str body)) (fn [v] {:version v})))))))
     :list   (fn []
               (async/then (files)
                           #(->> % (remove (comp (fn [s] (str/starts-with? s ".")) :name)) (sort-by :name) vec)))
     :delete (fn [name] (delete (plain name)))}))

#?(:cljs
   (defn- loaded?
     "Whether `dir` is the files of a file input or a drop, rather than a
     path or a directory handle."
     [dir]
     (not (or (string? dir) (fn? (some-> dir .-getFileHandle))))))

#?(:cljs
   (defn- loaded-store
     "The store that reads `files`, the files of a file input or a drop, and
     can't write them.

     Of a loaded folder, only the files at its top count, and its name is
     the name of the store."
     [files]
     (let [relative #(or (.-webkitRelativePath %) "")
           top      (filter #(and (<= (count (re-seq #"/" (relative %))) 1) (not (str/starts-with? (.-name %) ".")))
                            (js/Array.from files))
           by-name  (into {} (map (juxt #(.-name %) identity)) top)
           version  #(str (.-lastModified %) "-" (.-size %))]
       {:name  (or (some-> (first top) relative (str/split #"/") first not-empty) "files")
        :fetch (fn [name]
                 (when-let [f (get by-name name)]
                   (.then (.text f) (fn [body] {:body body :version (version f)}))))
        :put   (fn [_ _ _]
                 (throw (ex-info "The files of an input are read only" {:type ::read-only})))
        :list  (fn []
                 (vec (sort-by :name (for [[name f] by-name] {:name name :version (version f)}))))})))

(defn store
  "The store over the folder `dir`: a path, or in a browser a directory
  handle or the files of an input.

  A directory at a path is made on the first write, and hidden files
  aren't documents. The files of an input are read only, so their store
  has no delete, and its put fails with ::read-only."
  [dir]
  #?(:clj  (folder-store (jvm-folder dir))
     :cljs (cond
             (string? dir) (folder-store (node-folder dir))
             (loaded? dir) (loaded-store dir)
             :else         (folder-store (handle-folder dir)))))

(defn backend
  "A backend for dk.simongray.podcast.sync/sync! over the folder `dir`,
  with the `opts` of dk.simongray.podcast.sync.store/backend.

  Over the files of an input, which it can't write, the backend only
  pulls."
  ([dir]
   (backend dir {}))
  ([dir opts]
   {:pre [(map? opts)]}
   (store/backend (store dir) #?(:clj  opts
                                 :cljs (cond->> opts (loaded? dir) (merge {:direction :pull}))))))

#?(:cljs
   (defn query-permission!
     "The browser's permission to read and write the folder `dir`: :granted,
     :prompt or :denied, as a promise.

     A path, a folder of the origin private file system and the files of an
     input have nothing to ask for, so they give :granted."
     [dir]
     (if (fn? (some-> dir .-queryPermission))
       (.then (.queryPermission dir #js {:mode "readwrite"}) keyword)
       (js/Promise.resolve :granted))))

#?(:cljs
   (defn request-permission!
     "Ask the listener to let the app read and write the folder `dir`, and
     give the answer as query-permission! does.

     A browser only asks during a click, so call it from one."
     [dir]
     (if (fn? (some-> dir .-requestPermission))
       (.then (.requestPermission dir #js {:mode "readwrite"}) keyword)
       (js/Promise.resolve :granted))))

#?(:clj
   (comment
     (def s (store "/tmp/podcast-sync"))
     ((:put s) "a.edn" "{:version 1 :state {}}" {})
     ((:list s))
     ((:fetch s) "a.edn")
     #_.))
