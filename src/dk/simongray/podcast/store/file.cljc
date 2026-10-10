(ns dk.simongray.podcast.store.file
  "A folder as a document store, e.g. one that a sync client mirrors.

  The folder can be a Git working tree, or a folder of iCloud Drive,
  Dropbox, Google Drive, OneDrive, Seafile, Syncthing or Nextcloud. Each
  device writes only its own file, so the sync client has no conflicts to
  resolve. The folder is one of these:

  - a path, on the JVM and in Node
  - a FileSystemDirectoryHandle in a browser, e.g. a folder that the
    listener picked with showDirectoryPicker, or the site's own folder of
    the origin private file system, from navigator.storage.getDirectory(),
    for what an app keeps on the device
  - the files of a file input or a drop in a browser, which are read only

  A body is kept as it was put, text or bytes, and a fetch with
  {:as :bytes} reads it as bytes.

  In a browser that can't pick a folder, the listener can still sync
  through one by hand: save the device's sync document into the folder,
  and load the folder's files with a file input. A browser's permission
  for a picked folder can lapse between visits, so check it before a
  sync, and renew it from a click.

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
            [dk.simongray.wary-fetch.async :as async])
  #?(:clj (:import [java.io File FileNotFoundException]
                   [java.nio.file
                    CopyOption Files LinkOption OpenOption StandardCopyOption]
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
  #?(:clj  (let [new-lock (reify Function
                            (apply [_ _] (ReentrantLock.)))
                 held     ^ConcurrentHashMap locks
                 l        ^ReentrantLock (.computeIfAbsent held k new-lock)]
             (.lock l)
             (try
               (f)
               (finally
                 (.unlock l))))
     :cljs (if-let [web-locks (some-> js/globalThis .-navigator .-locks)]
             (.request web-locks (str "dk.simongray.podcast.store.file/" k)
                       (fn [_] (f)))
             (let [before  (get @locks k (js/Promise.resolve))
                   result  (.then before (fn [_] (f)))
                   settled (.then result (constantly nil) (constantly nil))]
               (swap! locks assoc k settled)
               result))))

#?(:clj
   (defn- version
     "The version of the file `f`: its modification time, as finely as the
     filesystem keeps it, and its length."
     [^File f]
     (let [modified (Files/getLastModifiedTime (.toPath f)
                                               (make-array LinkOption 0))]
       (str modified "-" (.length f)))))

#?(:clj
   (defn- write-whole!
     "Write `body` to the file `f` in `dir` through a hidden temporary file
     that's moved into place, so that no reader sees a partial file. The
     temporary file is removed when the write fails."
     [^File dir ^File f body]
     (.mkdirs dir)
     (let [tmp (File/createTempFile ".sync-" ".tmp" dir)]
       (try
         (if (bytes? body)
           (Files/write (.toPath tmp)
                        ^bytes body
                        ^"[Ljava.nio.file.OpenOption;"
                        (make-array OpenOption 0))
           (spit tmp body :encoding "UTF-8"))
         (Files/move (.toPath tmp) (.toPath f)
                     (into-array CopyOption
                                 [StandardCopyOption/ATOMIC_MOVE
                                  StandardCopyOption/REPLACE_EXISTING]))
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
        :read     (fn [name opts]
                    (let [^File f (file name)]
                      ;; the version before the body: a file replaced in between
                      ;; then reads as changed next time, not as unchanged
                      (try
                        (when (.isFile f)
                          (let [v (version f)]
                            {:body    (if (= :bytes (:as opts))
                                        (Files/readAllBytes (.toPath f))
                                        (slurp f :encoding "UTF-8"))
                             :version v}))
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
                        (.catch absent))
           listed  (fn [name]
                     (.then (stat name)
                            (fn [s] {:name name :version (version s)})))]
       {:name     path
        :lock-key file
        :files    (fn []
                    (-> (.readdir fs path #js {:withFileTypes true})
                        (.then (fn [entries]
                                 (async/all (for [e entries
                                                  :when (.isFile e)]
                                              (listed (.-name e))))))
                        (.catch #(do (absent %) []))))
        :read     (fn [name opts]
                    (.then (stat name)
                           (fn [s]
                             (when s
                               ;; without an encoding, a Buffer, which is a
                               ;; Uint8Array
                               (.then (.readFile fs
                                                 (file name)
                                                 (if (= :bytes (:as opts))
                                                   #js {}
                                                   "utf8"))
                                      (fn [body]
                                        {:body body :version (version s)}))))))
        :version  (fn [name]
                    (.then (stat name) #(some-> % version)))
        :write    (fn [name body]
                    (let [tmp (file (str ".sync-" (random-uuid) ".tmp"))]
                      (-> (.mkdir fs path #js {:recursive true})
                          (.then #(.writeFile fs tmp body "utf8"))
                          (.then #(.rename fs tmp (file name)))
                          (.then #(stat name))
                          (.then version)
                          (.catch (fn [e]
                                    (.then (.rm fs tmp #js {:force true})
                                           #(throw e)))))))
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
       "NotAllowedError"
       (ex-info (str "The browser needs the listener's permission for the "
                     "folder again")
                {:type ::not-allowed}
                e)

       "NotFoundError"
       (ex-info (str "The folder is gone: it was moved or deleted, or its "
                     "drive was removed")
                {:type ::gone}
                e)

       e)))

#?(:cljs
   (defn- collected
     "A promise of the values of the async iterator `it`, as a vector."
     [it]
     (letfn [(more [acc]
               (.then (.next it)
                      #(if (.-done %)
                         acc
                         (more (conj acc (.-value %))))))]
       (more []))))

#?(:cljs
   (defn- blob-body
     "A promise of the body of the File `f`, as text, or as a Uint8Array with
     :as :bytes in `opts`."
     [f opts]
     (if (= :bytes (:as opts))
       (.then (.arrayBuffer f) #(js/Uint8Array. %))
       (.text f))))

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
           missing? #(contains? #{"NotFoundError" "TypeMismatchError"}
                                (.-name %))
           failed   #(throw (failure %))
           file     #(-> (.getFileHandle dir %)
                         (.then (fn [h] (.getFile h)))
                         (.catch (fn [e] (when-not (missing? e) (failed e)))))
           listed   (fn [h]
                      (.then (.getFile h)
                             (fn [f] {:name (.-name h) :version (version f)})))]
       {:name     (or (not-empty (.-name dir)) "folder")
        :lock-key identity
        :files    (fn []
                    (-> (collected (.values dir))
                        (.then (fn [handles]
                                 (async/all (for [h handles
                                                  :when (= "file" (.-kind h))]
                                              (listed h)))))
                        (.catch failed)))
        :read     (fn [name opts]
                    (.then (file name)
                           (fn [f]
                             (when f
                               (.then (blob-body f opts)
                                      (fn [body]
                                        {:body body :version (version f)}))))))
        :version  (fn [name]
                    (.then (file name) #(some-> % version)))
        :write    (fn [name body]
                    ;; asked of the platform, before getFileHandle makes an
                    ;; empty file
                    (if-not (some-> js/globalThis
                                    .-FileSystemFileHandle
                                    .-prototype
                                    .-createWritable)
                      (throw (ex-info "This browser can't write to the folder"
                                      {:type ::read-only}))
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
  (let [plain   #(if (or (str/blank? (str %))
                         (re-find #"[/\\]" (str %))
                         (str/starts-with? (str %) "."))
                   (throw (ex-info "A document's name is a plain file name"
                                   {:name %}))
                   %)
        hidden? #(str/starts-with? (:name %) ".")
        stale?  (fn [opts current]
                  (and (contains? opts :if-version)
                       (not= (:if-version opts) current)))
        bytes?  #?(:clj bytes? :cljs #(instance? js/Uint8Array %))
        put!    (fn [name body opts]
                  (async/then (version name)
                              (fn [current]
                                (if (stale? opts current)
                                  {:conflict? true}
                                  (async/then (write name (cond-> body
                                                            (not (bytes? body))
                                                            str))
                                              (fn [v] {:version v}))))))]
    {:name   (:name folder)
     :fetch  (fn [name opts] (read (plain name) opts))
     :put    (fn [name body opts]
               (exclusively (lock-key (plain name))
                            #(put! name body opts)))
     :list   (fn []
               (async/then (files)
                           #(->> % (remove hidden?) (sort-by :name) vec)))
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
           top?     #(and (<= (count (re-seq #"/" (relative %))) 1)
                          (not (str/starts-with? (.-name %) ".")))
           top      (filter top? (js/Array.from files))
           by-name  (into {} (map (juxt #(.-name %) identity)) top)
           version  #(str (.-lastModified %) "-" (.-size %))
           folder   (some-> (first top)
                            relative
                            (str/split #"/")
                            first
                            not-empty)]
       {:name  (or folder "files")
        :fetch (fn [name opts]
                 (when-let [f (get by-name name)]
                   (.then (blob-body f opts)
                          (fn [body] {:body body :version (version f)}))))
        :list  (fn []
                 (vec (sort-by :name (for [[name f] by-name]
                                       {:name name :version (version f)}))))})))

(defn store
  "The store over the folder `dir`: a path, or in a browser a directory
  handle or the files of an input.

  A directory at a path is made on the first write, and hidden files
  aren't documents. The files of an input are read only, so their store
  has no put or delete."
  [dir]
  #?(:clj  (folder-store (jvm-folder dir))
     :cljs (cond
             (string? dir) (folder-store (node-folder dir))
             (loaded? dir) (loaded-store dir)
             :else         (folder-store (handle-folder dir)))))

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
     give the answer, :granted, :prompt or :denied, as a promise.

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
     ((:fetch s) "a.edn" {})
     #_.))
