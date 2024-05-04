(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd])
  (:import (java.time ZoneOffset ZonedDateTime)
           (java.time.format DateTimeFormatter)))

(def lib 'net.clojars.favila/enhanced-entity-map)
(def version (format "1.0.%s" (b/git-count-revs nil)))
(def tag (str "v" version))

(def basis (delay (b/create-basis {})))

(def github-url "https://github.com/favila/enhanced-entity-map")

(def jar-opts
  (delay
   ;; pom opts
   {:lib        lib
    :version    version
    :basis      @basis
    :scm        {:tag tag
                 :url github-url}

    ;; copy-dir and jar opts
    :src-dirs   ["src"]
    :target-dir "target/classes"
    :class-dir  "target/classes"
    :jar-file   (format "target/%s-%s.jar" (name lib) version)}))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar [_]
  (clean nil)
  (let [opts @jar-opts]
    (b/write-pom opts)
    (b/copy-dir opts)
    (b/jar opts)))

(defn- today-YMD []
  (-> (ZonedDateTime/now ZoneOffset/UTC)
      (.format DateTimeFormatter/ISO_LOCAL_DATE)))

(defn changelog-header
  [_]
  (println (format "### [%s] - %s" tag (today-YMD)))
  (println (format "[%s]: %s/compare/%s...%s" tag github-url "FIXME" tag)))

(defn git-tag
  [_]
  (println (format "git tag -a '%s' -m '%s'" tag tag)))

(defn deploy
  [_]
  (clean nil)
  (jar nil)
  (let [{:keys [jar-file] :as opts} @jar-opts]
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))}))
  (git-tag nil))
