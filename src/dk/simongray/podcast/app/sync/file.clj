(ns dk.simongray.podcast.app.sync.file
  "A folder as a document store: the documents are the files of a
  directory, one a sync client mirrors, iCloud Drive, Dropbox, Google
  Drive, OneDrive, Seafile, Syncthing or Nextcloud, or a Git working tree.
  Every device writes only its own file, so the client has no conflict to
  resolve, and a file is written whole and moved into place, so the client
  never sees half of one. The version of a file is its modification time
  and its size, which tells two writes apart as finely as the filesystem
  keeps time."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dk.simongray.podcast.app.sync.store :as store])
  (:import [java.io File FileNotFoundException]
           [java.nio.file CopyOption Files LinkOption StandardCopyOption]
           [java.util.concurrent ConcurrentHashMap]
           [java.util.concurrent.locks ReentrantLock]
           [java.util.function Function]))

(defn- version
  "The version of the file `f`: its modification time, as finely as the
  filesystem keeps it, and its length."
  [^File f]
  (str (Files/getLastModifiedTime (.toPath f) (make-array LinkOption 0)) "-" (.length f)))

(defonce ^{:no-doc true
           :doc    "A lock for each file a store in this process writes, by its
  canonical path, so that a put checks the version and writes as one step."}
  locks
  (ConcurrentHashMap.))

(defn- lock
  ^ReentrantLock [^File f]
  (.computeIfAbsent ^ConcurrentHashMap locks (.getCanonicalPath f)
                    (reify Function (apply [_ _] (ReentrantLock.)))))

(defn- file-named
  "The file for the document `name` in `dir`; a name that is not a plain
  file name is refused."
  ^File [^File dir name]
  (when (or (str/blank? (str name)) (re-find #"[/\\]" (str name)) (str/starts-with? (str name) "."))
    (throw (ex-info "A document's name is a plain file name" {:name name})))
  (io/file dir (str name)))

(defn- write-whole!
  "Write `body` to `f` by way of a hidden temporary file in the same
  directory moved into place, so that no reader sees a partial file; the
  temporary file is removed when the write fails."
  [^File dir ^File f ^String body]
  (.mkdirs dir)
  (let [tmp (File/createTempFile ".sync-" ".tmp" dir)]
    (try
      (spit tmp body :encoding "UTF-8")
      (Files/move (.toPath tmp) (.toPath f)
                  (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
      (finally
        (.delete tmp)))))

(defn store
  "The store over the directory `dir`, a path or a File, created on the
  first write; the hidden files of the directory are not documents."
  [dir]
  (let [dir (io/file dir)]
    {:name   (str dir)
     :fetch  (fn [name]
               (let [f (file-named dir name)]
                 ;; the version before the body: a file replaced in between
                 ;; then reads as changed next time, not as unchanged
                 (try
                   (when (.isFile f)
                     (let [v (version f)]
                       {:body (slurp f :encoding "UTF-8") :version v}))
                   (catch FileNotFoundException _
                     nil))))
     :put    (fn [name body opts]
               (let [f (file-named dir name)
                     l (lock f)]
                 (.lock l)
                 (try
                   (let [current (when (.isFile f) (version f))]
                     (if (and (contains? opts :if-version) (not= (:if-version opts) current))
                       {:conflict? true}
                       (do (write-whole! dir f (str body))
                           {:version (version f)})))
                   (finally
                     (.unlock l)))))
     :list   (fn []
               (->> (or (.listFiles dir) (make-array File 0))
                    (filter (fn [^File f] (and (.isFile f) (not (str/starts-with? (.getName f) ".")))))
                    (map (fn [^File f] {:name (.getName f) :version (version f)}))
                    (sort-by :name)
                    vec))
     :delete (fn [name]
               (.delete (file-named dir name))
               nil)}))

(defn backend
  "A backend for `dk.simongray.podcast.app.sync/sync!` over the folder
  `dir`, in one call; `opts` are those of
  `dk.simongray.podcast.app.sync.store/backend`."
  ([dir]
   (backend dir {}))
  ([dir opts]
   {:pre [(map? opts)]}
   (store/backend (store dir) opts)))

(comment
  (def s (store "/tmp/podcast-sync"))
  ((:put s) "a.edn" "{:version 1 :state {}}" {})
  ((:list s))
  ((:fetch s) "a.edn")
  #_.)
