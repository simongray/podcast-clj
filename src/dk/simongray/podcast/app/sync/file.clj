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
  (:import [java.io File]
           [java.nio.file CopyOption Files StandardCopyOption]))

(defn- version
  [^File f]
  (str (.lastModified f) "-" (.length f)))

(defn- file-named
  "The file for the document `name` in `dir`; a name that is not a plain
  file name is refused."
  ^File [^File dir name]
  (when (or (str/blank? (str name)) (re-find #"[/\\]" (str name)) (str/starts-with? (str name) "."))
    (throw (ex-info "A document's name is a plain file name" {:name name})))
  (io/file dir (str name)))

(defn- write-whole!
  "Write `body` to `f` by way of a hidden temporary file in the same
  directory moved into place, so that no reader sees a partial file."
  [^File dir ^File f ^String body]
  (.mkdirs dir)
  (let [tmp (File/createTempFile ".sync-" ".tmp" dir)]
    (spit tmp body :encoding "UTF-8")
    (Files/move (.toPath tmp) (.toPath f)
                (into-array CopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))))

(defn store
  "The store over the directory `dir`, a path or a File, created on the
  first write; the hidden files of the directory are not documents."
  [dir]
  (let [dir (io/file dir)]
    {:name   (str dir)
     :fetch  (fn [name]
               (let [f (file-named dir name)]
                 (when (.isFile f)
                   {:body (slurp f :encoding "UTF-8") :version (version f)})))
     :put    (fn [name body opts]
               (let [f       (file-named dir name)
                     current (when (.isFile f) (version f))]
                 (if (and (contains? opts :if-version) (not= (:if-version opts) current))
                   {:conflict? true}
                   (do (write-whole! dir f (str body))
                       {:version (version f)}))))
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
