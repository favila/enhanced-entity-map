(ns net.favila.enhanced-entity-map-test
  (:require
   [net.favila.enhanced-entity-map :as eem]
   [clojure.test :refer [are deftest is use-fixtures]]
   [datomic.api :as d])
  (:import (datomic.query EMapImpl)))


(def ^:dynamic *env* nil)

(defn- test-env* []
  (let [uri (str "datomic:mem://enhanced-entity-map-test-"
                 (System/currentTimeMillis))
        created? (d/create-database uri)
        conn (d/connect uri)]
    (assert created?)
    @(d/transact conn [{:db/ident :my/ref1
                        :db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/one}
                       {:db/ident :my/ref+
                        :db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many}
                       {:db/ident :my/id
                        :db/valueType :db.type/string
                        :db/cardinality :db.cardinality/one
                        :db/unique :db.unique/value}
                       {:db/ident :my/component-ref1
                        :db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/one
                        :db/isComponent true}
                       {:db/ident :my/component-ref+
                        :db/valueType :db.type/ref
                        :db/cardinality :db.cardinality/many
                        :db/isComponent true}
                       {:db/ident :my/counter
                        :db/valueType :db.type/long
                        :db/cardinality :db.cardinality/many}
                       {:db/ident :my/str
                        :db/valueType :db.type/string
                        :db/cardinality :db.cardinality/many}])
    @(d/transact conn [{:db/id "e1"
                        :my/id "e1"}
                       {:db/id "e2"
                        :my/id "e2"}
                       {:db/id "e3"
                        :my/id "e3"}
                       {:db/id "e3"
                        :my/id "e3"
                        :db/ident :enum/e3}
                       {:db/id "refer"
                        :my/id "refer"
                        :my/ref1 "e1"
                        :my/ref+ ["e1" "e2" "e3"]
                        :my/component-ref1 "e1"
                        :my/component-ref+ ["e1" "e2" "e3"]}
                       {:db/id "e4"
                        :my/id "e4"}
                       {:db/id "ident-ref"
                        :my/id "ident-ref"
                        :db/ident :enum/ident-ref
                        :my/ref1 "e4"
                        :my/ref+ ["e4"]
                        :my/component-ref1 "e4"
                        :my/component-ref+ ["e4"]}])
    {:uri  uri
     :conn conn
     :db   (d/db conn)}))

(defn- test-env-fixture [f]
  (let [{:keys [uri conn] :as env} (test-env*)]
    (binding [*env* env]
      (f)
      (d/release conn)
      (d/delete-database uri))))

(use-fixtures :once test-env-fixture)

(defmethod eem/entity-map-derived-attribute :my.derived/str+counter
  [em _]
  (set (for [s (:my/str em)
             c (:my/counter em)]
         (str s c))))

(defmethod eem/entity-map-derived-attribute :my.derived/ref+-non-enum
  [em _]
  (into #{}
        (remove keyword?)
        (:my/ref+ em)))

(defn entity-cache [^EMapImpl m]
  (.cache m))

(deftest basic-entity-map-parity
  (let [{:keys [db]} *env*
        refer-lr [:my/id "refer"]
        refer-em (d/entity db refer-lr)
        refer-eem (eem/entity db refer-lr)
        e4-lr [:my/id "e4"]
        e4-em (d/entity db e4-lr)
        e4-eem (eem/entity db e4-lr)]
    (is (= true
           (eem/entity-map? refer-em)
           (eem/entity-map? refer-eem)))

    (is (= (:db/id refer-em)
           (:db/id refer-eem)))

    (is (= nil
           (::non-existent-attribute-or-multimethod-key refer-em)
           (::non-existent-attribute-or-multimethod-key refer-eem)))


    ;; Ensure reverse-refs return an entity map always,
    ;; even if the other side of the ref is an ident-having entity
    (is (set? (:my/_ref1 e4-em)))
    (is (== 1 (count (:my/_ref1 e4-em))))
    (is (set? (:my/_ref1 e4-eem)))
    (is (== 1 (count (:my/_ref1 e4-eem))))
    (is (= (:db/id (first (:my/_ref1 e4-em))))
        (= (:db/id (first (:my/_ref1 e4-eem)))))

    ;; Same for components
    (is (eem/entity-map? (:my/_component-ref1 e4-em)))
    (is (= (:db/id (:my/_component-ref1 e4-em))
           (:db/id (:my/_component-ref1 e4-eem))))))


(comment
 (def env (test-env*))
 (def db (d/db (:conn env)))


 )

