(ns build
  "Build and release the library, and build the podcast server:

      clojure -T:build jar       ; target/podcast-<version>.jar with its pom
      clojure -T:build install   ; into the local Maven repository
      clojure -T:build deploy    ; to Clojars, with CLOJARS_USERNAME and
                                 ; CLOJARS_PASSWORD (a deploy token) set
      clojure -T:build server    ; target/podcast-server.jar, to run with
                                 ; java -jar"
  (:require [clojure.tools.build.api :as b]))

(def lib
  "The Maven coordinate of the library."
  'dk.simongray/podcast-clj)

(def version
  "The version to build; bump it with each release on GitHub."
  "0.1.0-SNAPSHOT")

(def class-dir
  "Where the jar's contents are staged."
  "target/classes")

(def jar-file
  "The jar to build."
  (format "target/%s-%s.jar" (name lib) version))

(def basis
  "The project basis, computed on first use."
  (delay (b/create-basis {:project "deps.edn"})))

(def scm-url
  "The repository, for the pom."
  "https://github.com/simongray/podcast-clj")

(defn clean
  "Remove everything built."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Write the pom and build the jar from the sources in src."
  [_]
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     @basis
                :src-dirs  ["src"]
                :scm       {:url                 scm-url
                            :connection          "scm:git:git://github.com/simongray/podcast-clj.git"
                            :developerConnection "scm:git:ssh://git@github.com/simongray/podcast-clj.git"
                            :tag                 (str "v" version)}
                :pom-data  [[:description "A Clojure(Script) library for writing podcast software."]
                            [:url scm-url]
                            [:licenses
                             [:license
                              [:name "MIT License"]
                              [:url "https://opensource.org/license/mit"]]]]})
  (b/copy-dir {:src-dirs   ["src"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file}))

(defn install
  "Build the jar and install it in the local Maven repository."
  [_]
  (jar nil)
  (b/install {:basis     @basis
              :lib       lib
              :version   version
              :jar-file  jar-file
              :class-dir class-dir}))

(defn deploy
  "Build the jar and deploy it to Clojars."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  (b/resolve-path jar-file)
    :pom-file  (b/pom-path {:lib lib :class-dir class-dir})}))

(def server-jar
  "The jar of the podcast server, with everything that it needs."
  "target/podcast-server.jar")

(defn server
  "Build the podcast server as one jar, compiled ahead of time so that it
  starts fast."
  [_]
  (let [basis     (b/create-basis {:project "deps.edn" :aliases [:server]})
        class-dir "target/server-classes"]
    (b/delete {:path class-dir})
    ;; with the jsoup adapter, which the library only loads when a feed
    ;; needs it, so that nothing else would compile it
    (b/compile-clj {:basis      basis
                    :ns-compile '[podcast-server.service dk.simongray.podcast.util.xml.jsoup]
                    :class-dir  class-dir})
    (b/uber {:class-dir class-dir
             :uber-file server-jar
             :basis     basis
             :main      'podcast-server.service})))
