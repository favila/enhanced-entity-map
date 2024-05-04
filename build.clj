(ns build
  (:refer-clojure :exclude [test])
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'net.clojars.favila/enhanced-entity-map)
(def version (format "1.0.%s" (b/git-count-revs nil)))

(def basis (delay (b/create-basis {})))

(def jar-opts
  (delay
   ;; pom opts
   {:lib        lib
    :version    version
    :basis      @basis
    :scm        {:tag (str "v" version)
                 :url "https://github.com/favila/enhanced-entity-map"}

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

(defn deploy
  "Deploy the JAR to Clojars."
  [_]
  (let [{:keys [jar-file] :as opts} @jar-opts]
    (dd/deploy {:installer :remote
                :artifact (b/resolve-path jar-file)
                :pom-file (b/pom-path (select-keys opts [:lib :class-dir]))})))
