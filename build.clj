;; Copyright 2026 Georgetown Software House, Inc.
;; SPDX-License-Identifier: Apache-2.0

(ns build
  "Builds the two JVM artifacts with tools.build.

     clojure -T:build jar             both jars, in target/
     clojure -T:build jar-core        com.pebblestream/psosf
     clojure -T:build jar-directives  com.pebblestream/psosf-directives
     clojure -T:build install         both, into the local Maven repository
     clojure -T:build clean

   The version is read from VERSION, which package.json must match."
  (:require [clojure.string :as string]
            [clojure.tools.build.api :as b]))

(def version (string/trim (slurp "VERSION")))
(def scm-url "https://github.com/GeorgetownSoftwareHouse/pebble-stream-psosf")

(def artifacts
  {:core       {:lib       'com.pebblestream/psosf
                :root      "."
                :src-dirs  ["src"]
                :java-dirs ["src/java"]
                :description "Read, write, validate and classify PSOSF archives."}
   :directives {:lib       'com.pebblestream/psosf-directives
                :root      "directives"
                :src-dirs  ["directives/src"]
                :java-dirs []
                :description "The PebbleScript directive registry and directive string parser."}})

(defn- pom-data [description]
  [[:description description]
   [:url scm-url]
   [:licenses
    [:license
     [:name "Apache License, Version 2.0"]
     [:url "https://www.apache.org/licenses/LICENSE-2.0"]
     [:distribution "repo"]]]
   [:organization [:name "Georgetown Software House, Inc."]]])

(defn- jar-path [lib] (format "target/%s-%s.jar" (name lib) version))

(defn- build-jar [{:keys [lib root src-dirs java-dirs description]}]
  (let [class-dir (str "target/classes/" (name lib))
        basis     (b/create-basis {:project (str root "/deps.edn")})]
    (b/delete {:path class-dir})
    (b/write-pom {:class-dir class-dir
                  :lib       lib
                  :version   version
                  :basis     basis
                  :src-dirs  src-dirs
                  :scm       {:url scm-url :tag (str "v" version)}
                  :pom-data  (pom-data description)})
    ;; Clojure libraries ship as source; .cljs files are left out of the jar.
    (b/copy-dir {:src-dirs   (remove #(string/ends-with? % "java") src-dirs)
                 :target-dir class-dir
                 :include    "**.clj*"
                 :ignores    [".*\\.cljs$"]})
    (doseq [f ["LICENSE" "NOTICE"]]
      (b/copy-file {:src f :target (str class-dir "/META-INF/" f)}))
    (when (seq java-dirs)
      (b/javac {:src-dirs   java-dirs
                :class-dir  class-dir
                :basis      basis
                :javac-opts ["--release" "11"]}))
    (b/jar {:class-dir class-dir :jar-file (jar-path lib)})
    (println "Built" (jar-path lib))
    {:lib lib :class-dir class-dir :basis basis :jar-file (jar-path lib)}))

(defn clean [_] (b/delete {:path "target"}))

(defn jar-core [_] (build-jar (:core artifacts)))
(defn jar-directives [_] (build-jar (:directives artifacts)))

(defn jar [_]
  (clean nil)
  (jar-core nil)
  (jar-directives nil))

(defn install [_]
  (clean nil)
  (doseq [a (vals artifacts)]
    (let [{:keys [lib class-dir basis jar-file]} (build-jar a)]
      (b/install {:lib lib :version version :basis basis :class-dir class-dir :jar-file jar-file}))))
