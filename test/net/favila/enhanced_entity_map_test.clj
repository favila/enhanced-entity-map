(ns net.favila.enhanced-entity-map-test
  (:require
   [net.favila.enhanced-entity-map :as eem]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [datomic.api :as d])
  (:import (clojure.core.cache BasicCache)
           (datomic.query EMapImpl)
           (net.favila.enhanced_entity_map EnhancedEntityMap)))


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
                        :my/id "e1"
                        :my/str ["e1-entity"]
                        :my/counter 1}
                       {:db/id "e2"
                        :my/id "e2"
                        :my/str ["e2-entity"]
                        :my/counter 2}
                       {:db/id "e3"
                        :my/id "e3"}
                       {:db/id "e3"
                        :my/id "e3"
                        :my/str ["e3-entity"]
                        :my/counter 3
                        :db/ident :enum/e3}
                       {:db/id "refer"
                        :my/id "refer"
                        :my/ref1 "e1"
                        :my/ref+ ["e1" "e2" "e3"]
                        :my/component-ref1 "e1"
                        :my/component-ref+ ["e1" "e2" "e3"]}
                       {:db/id "refer-ident"
                        :my/id "refer-ident"
                        :my/ref1 "e3"
                        :my/component-ref1 "e3"}
                       {:db/id "e4"
                        :my/id "e4"}
                       {:db/id "ident-ref"
                        :my/id "ident-ref"
                        :db/ident :enum/ident-ref
                        :my/ref1 "e4"
                        :my/ref+ ["e4"]
                        :my/component-ref1 "e4"
                        :my/component-ref+ ["e4"]}
                       {:db/id "extra-e4-component-ref"
                        :my/id "extra-e4-component-ref"
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

(defmethod eem/entity-map-derived-attribute :my.derived/str+counter-sorted
  [em _]
  (sort (:my.derived/str+counter em)))

(defmethod eem/entity-map-derived-attribute :my.derived/ref+-non-enum
  [em _]
  (into #{}
        (remove keyword?)
        (:my/ref+ em)))

(defmethod eem/entity-map-derived-attribute :my.derived/circular-dep-a
  [em _]
  (:my.derived/circular-dep-b em))

(defmethod eem/entity-map-derived-attribute :my.derived/circular-dep-b
  [em _]
  (:my.derived/circular-dep-a em))

(deftest basic-entity-map-and-aevt-parity
  ;; This is testing behavior that should be the *same*
  ;; on normal and enhanced entity maps,
  ;; even when the enhanced-entity-map is using :aevt
  (let [{:keys [db]} *env*
        e1-eid (d/entid db [:my/id "e1"])
        refer-lr [:my/id "refer"]
        refer-eid (d/entid db refer-lr)]
    ;; Checking test setup assumptions
    (is (pos-int? e1-eid))
    (is (pos-int? refer-eid))
    (doseq [[entity-ctor index] [[#'d/entity]
                                 [#'eem/entity :eavt]
                                 [#'eem/entity :aevt]]]
      (testing (str "Using " entity-ctor
                    (when index (str " with index" index))
                    "\n")
        (binding [eem/*prefer-index* index]
          (let [refer-em (entity-ctor db refer-lr)
                refer-ident-em (entity-ctor db [:my/id "refer-ident"])
                e4-em (entity-ctor db [:my/id "e4"])]

            (is (true? (eem/entity-map? refer-em))
                "Both kinds of entity-maps should be `entity-map?`")

            (is (= refer-eid (:db/id refer-em))
                ":db/id of both kinds of entity-maps should be the same when created via a lookup ref")

            (is (= nil (:my/str refer-em))
                "Lookup of unasserted attribute should have nil value.")

            (is (= nil (::non-existent-attribute-or-multimethod-key refer-em))
                "Lookup of non-existent key should have nil value.")

            (is (= [:db/id refer-eid] (find refer-em :db/id))
                "Both kinds of entity-map should support find on :db/id")

            (testing "Both kinds of entity-map should support find on a datomic attribute that has not been cached."
              (let [e1-em (entity-ctor db [:my/id "e1"])
                    val-my-id (find e1-em :my/id)
                    val-my-str (find e1-em :my/str)
                    val-my-counter (find e1-em :my/counter)]
                (is (= [:my/id "e1"] val-my-id))
                (is (= [:my/str #{"e1-entity"}] val-my-str))
                (is (= [:my/counter #{1}] val-my-counter))))

            (testing "Cached lookup should produce the same (identical) objects"
              (is (identical? (:my/id refer-em) (:my/id refer-em)))
              (is (identical? (:my/ref1 refer-em) (:my/ref1 refer-em)))
              (is (identical? (:my/ref+ refer-em) (:my/ref+ refer-em))))

            (testing "Cardinality-one forward-refs should return entity-maps if the target entity is not an ident"
              (is (eem/entity-map? (:my/ref1 refer-em)))
              (is (= e1-eid (-> refer-em :my/ref1 :db/id))))

            (testing "Cardinality-one forward-refs should return idents if the target entity is an ident"
              (is (= :enum/e3 (:my/ref1 refer-ident-em)))
              (is (= :enum/e3 (:my/component-ref1 refer-ident-em))))

            (is (= #{(entity-ctor db [:my/id "e1"])
                     (entity-ctor db [:my/id "e2"])
                     :enum/e3}
                   (:my/ref+ refer-em))
                "Cardinality-many forward-refs should return sets of entity-maps or idents.")

            (testing "Non-component reverse-refs should always return entity-map sets, even if the reference has an ident."
              (is (set? (:my/_ref1 e4-em)))
              (is (== 1 (count (:my/_ref1 e4-em))))
              (is (= :enum/ident-ref (:db/ident (first (:my/_ref1 e4-em))))))

            (testing "Component reverse-refs should be a single entity-map, even if there are multiple references, even if the referer has an ident."
              (is (eem/entity-map? (:my/_component-ref1 e4-em)))
              (is (= :enum/ident-ref (:db/ident (:my/_component-ref1 e4-em))))

              (is (eem/entity-map? (:my/_component-ref+ e4-em)))
              (is (= :enum/ident-ref (:db/ident (:my/_component-ref+ e4-em)))))))))))

(deftest failed-lookup-parity
  (let [{:keys [db]} *env*
        lr-em (d/entity db [:my/id "does-not-exist"])
        lr-eem (eem/entity db [:my/id "does-not-exist"])
        kw-em (d/entity db :enum/does-not-exist)
        kw-eem (eem/entity db :enum/does-not-exist)]
    (is (= nil lr-em lr-eem kw-em kw-eem)
        "Unresolvable lookup refs and idents should return nil from `entity`.")))

(deftest ident-lookup-parity
  (let [{:keys [db]} *env*
        em (d/entity db :enum/e3)
        eem (eem/entity db :enum/e3)]
    (is (= "e3" (:my/id em) (:my/id eem))
        "Resolvable idents should return entity-map from `entity`.")))

(deftest history-db-parity
  (let [{:keys [db]} *env*
        hdb (d/history db)]
    (is (thrown? IllegalStateException (d/entity hdb [:my/id "e1"])))
    (is (thrown? IllegalStateException (eem/entity hdb [:my/id "e1"])))))

(deftest hash-and-equality
  (let [{:keys [db]} *env*
        e1-em (d/entity db [:my/id "e1"])
        e1-eem (eem/entity db [:my/id "e1"])]

    (is (= (hash e1-em)
           (hash e1-eem))
        "Hash of both kinds of entity-maps should be equal.")

    ;; Cache an attribute read
    (:my/counter e1-em)
    (:my/counter e1-eem)

    (is (= (hash e1-em)
           (hash e1-eem))
        "Hash of both kinds of entity-maps should not be altered by attribute access.")

    (let [e1-eem-assoced (assoc e1-eem :my/counter (:my/counter e1-eem))]
      (is (not= (hash e1-em)
                (hash e1-eem-assoced))
          "Hash of assoc-ed and not-assoc-ed enhanced entity maps should not be equal, even if the effective value is the same.")

      (is (not= e1-em
                e1-eem-assoced)
          "Assoc-ed enhanced entity map is not equal to non-assoc-ed eem, even if the effective value is the same."))

    (is (= e1-em (d/entity db [:my/id "e1"]))
        "Normal entity maps are equal if their database-id and entity-id are the same.")

    (is (= e1-eem (eem/entity db [:my/id "e1"]))
        "Enhanced entity maps are equal if their database-id and entity-id are the same.")

    (is (not= e1-em e1-eem)
        "Normal and enhanced entity-maps are not equal.")

    (is (= e1-eem (eem/entity db [:my/id "e1"]))
        "Enhanced entity maps are equal if neither was assoc-ed.")

    (is (= (assoc e1-eem :my/counter #{99}
                         :not-an-attr "value")
           (assoc e1-eem :my/counter #{99}
                         :not-an-attr "value"))
        "Assoc-ed enhanced entity-maps are equal to each-other if their assoc-es and entity-maps are equal.")))

(defn- entity-cache [^EMapImpl m]
  (.cache m))

(defn- derived-attr-cache [^EnhancedEntityMap m]
  (.cache ^BasicCache @(.derived_attr_cache m)))

(deftest entity-map-conversion-copies-cache
  (let [{:keys [db]} *env*
        e1-eid (d/entid db [:my/id "e1"])
        e2-eid (d/entid db [:my/id "e2"])
        e1-em (d/entity db [:my/id "e1"])

        ;; Perform cached reads
        _ (:my/str e1-em)

        e1-eem (eem/as-enhanced-entity e1-em)

        refer-em (d/entity db [:my/id "refer"])

        ;; Perform cached read on other entities
        _ (-> refer-em :my/ref1 :my/counter)
        _ (->> refer-em :my/ref+ (mapv :my/id))
        refer-eem (eem/as-enhanced-entity refer-em)]

    (is (= (entity-cache e1-em)
           (entity-cache e1-eem)
           {:db/id e1-eid
            :my/str #{"e1-entity"}})
        "Entity cache is copied shallowly.")

    (testing "deep copy"
      (is (= #{:db/id :my/ref1 :my/ref+}
             (set (keys (entity-cache refer-em)))
             (set (keys (entity-cache refer-eem)))))

      (is (= {:db/id e1-eid :my/counter #{1}}
             (entity-cache (:my/ref1 refer-em))
             (entity-cache (:my/ref1 refer-eem))))

      (is (eem/enhanced-entity-map? (:my/ref1 refer-eem)))

      (is (= #{{:db/id e1-eid :my/id "e1"}
               {:db/id e2-eid :my/id "e2"}
               :enum/e3}
             (into #{} (map (fn [x]
                              (if (keyword? x)
                                x
                                (entity-cache x)))) (:my/ref+ refer-em))
             (into #{} (map (fn [x]
                              (if (keyword? x)
                                x
                                (entity-cache x)))) (:my/ref+ refer-eem))))

      (is (every? #(or (keyword? %) (eem/enhanced-entity-map? %)) (:my/ref+ refer-eem))))))

(deftest enhanced-entity-map-metadata
  (let [{:keys [db]} *env*
        e1 (eem/entity db [:my/id "e1"])
        e1-m (with-meta e1 {:foo :bar})]
    (is (nil? (meta e1)))
    (is (= {:foo :bar} (meta e1-m)))))

(deftest prefer-aevt-caches
  (let [{:keys [db]} *env*
        refer-em (eem/entity db [:my/id "refer"])]

    (is (= {} (dissoc (entity-cache refer-em) :db/id)))

    (eem/prefer-aevt
     (is (= [nil "e1" "e2"]
            (sort (map :my/id (:my/ref+ refer-em))))))

    (is (= #{{:my/id "e1"}
             {:my/id "e2"}
             :enum/e3}
           (into #{} (map (fn [x]
                            (if (keyword? x)
                              x
                              (dissoc (entity-cache x) :db/id))))
                 (:my/ref+ refer-em))))))

(deftest entity-db-works
  (let [{:keys [db]} *env*
        refer-em (eem/entity db [:my/id "refer"])]
    (is (identical? db (d/entity-db refer-em)))
    (is (identical? db (d/entity-db (:my/ref1 refer-em))))))

(deftest touch-works
  (let [{:keys [db]} *env*
        refer-eid (d/entid db [:my/id "refer"])
        refer-em (eem/entity db refer-eid)
        _ (d/touch refer-em)
        refer-ec (entity-cache refer-em)]
    (is (= #{:db/id :my/id :my/ref1 :my/ref+ :my/component-ref1 :my/component-ref+}
           (set (keys refer-ec)))
        "Touch realizes all forward attributes on the entity.")

    (testing "Touch does not follow non-component refs"
      (is (= {} (-> refer-ec :my/ref1 entity-cache (dissoc :db/id))))
      (is (= #{{} :enum/e3}
             (into #{} (map (fn [x]
                              (if (keyword? x)
                                x
                                (dissoc (entity-cache x) :db/id))))
                   (:my/ref+ refer-em)))))

    (testing "Touch follows component refs"
      (is (= {:my/id      "e1"
              :my/str     #{"e1-entity"}
              :my/counter #{1}}
             (-> refer-ec :my/component-ref1 entity-cache (dissoc :db/id))))
      (is (= #{{:my/id      "e1"
                :my/str     #{"e1-entity"}
                :my/counter #{1}}
               {:my/id      "e2"
                :my/str     #{"e2-entity"}
                :my/counter #{2}}
               :enum/e3}
             (into #{} (map (fn [x]
                              (if (keyword? x)
                                x
                                (dissoc (entity-cache x) :db/id))))
                   (:my/component-ref+ refer-em)))
          "Touch follows components recursively."))))

(deftest ident-aliasing-works
  (let [{:keys [db]} *env*
        {db :db-after} (d/with db [[:db/add :my/str :db/ident :my/str-renamed]])
        e1-eem (eem/entity db [:my/id "e1"])]
    (is (= #{"e1-entity"} (:my/str e1-eem)))
    (is (= {:my/str #{"e1-entity"}}
           (dissoc (entity-cache e1-eem) :db/id)))

    (d/touch e1-eem)

    (is (= [:my/str-renamed #{"e1-entity"}]
           (find (entity-cache e1-eem) :my/str-renamed))
        "Touch adds the newer ident when an attribute ident value has changed.")))

(deftest cache-failed-lookups
  (let [{:keys [db]} *env*
        e1-em (d/entity db [:my/id "e1"])
        e1-eem (eem/entity db [:my/id "e1"])
        refer-em (d/entity db [:my/id "refer"])
        refer-eem (eem/entity db [:my/id "refer"])]

    (testing "Failed lookups on completely unknown keys"
      (::non-existent-attribute-or-multimethod-key e1-em)
      (::non-existent-attribute-or-multimethod-key e1-eem)
      (is (= {} (dissoc (entity-cache e1-em) :db/id))
          "Normal entity maps do not cache in entity-cache.")
      (is (= {} (dissoc (entity-cache e1-eem) :db/id))
          "Enhanced entity maps do not cache in entity-cache.")
      (is (= {::non-existent-attribute-or-multimethod-key ::eem/not-implemented}
             (derived-attr-cache e1-eem))
          "Enhanced entity maps cache unknown keys in the derived-attr-cache"))

    (testing "Failed lookups on known attribute idents"
      (:my/ref1 e1-em)
      (:my/ref1 e1-eem)
      (:db/ident e1-em)
      (:db/ident e1-eem)
      (is (= {} (dissoc (entity-cache e1-em) :db/id))
          "Normal entity maps do not cache in forward direction.")
      (is (= {:my/ref1 nil :db/ident nil} (dissoc (entity-cache e1-eem) :db/id))
          "Enhanced entity maps do cache in forward direction")

      (:my/_ref1 refer-em)
      (:my/_ref1 refer-eem)
      (is (= nil (:my/_ref1 refer-em)))
      (is (= {} (dissoc (entity-cache refer-em) :db/id))
          "Normal entity maps cache in forward direction")
      (is (= {:my/_ref1 nil} (dissoc (entity-cache refer-eem) :db/id))
          "Enhanced entity maps do cache in reverse direction"))

    (testing "Derived attribute value caching"
      (:my.derived/str+counter-sorted refer-eem)
      ;; :my/str is read by the derived attr method
      (is (= {:my/str nil}
             (dissoc (entity-cache refer-eem) :db/id :my/_ref1))
          "Derived attributes are not stored in the entity-cache.")

      (is (= {:my.derived/str+counter #{}
              :my.derived/str+counter-sorted ()}
           (derived-attr-cache refer-eem))))))

(deftest derived-attributes-work
  (let [{:keys [db]} *env*
        e1 (eem/entity db [:my/id "e1"])
        refer (eem/entity db [:my/id "refer"])]

    (is (= ["e1-entity1"]
           (:my.derived/str+counter-sorted e1))
        "Second order derived attributes work.")
    (is (= {:my/str #{"e1-entity"} :my/counter #{1}}
           (dissoc (entity-cache e1) :db/id))
        "Intermediate attribute lookups are cached.")
    (is (= {:my.derived/str+counter #{"e1-entity1"}
            :my.derived/str+counter-sorted ["e1-entity1"]}
           (derived-attr-cache e1))
        "Intermediate derived-attribute lookups are cached.")

    (is (= #{"e1" "e2"}
           (into #{}
                 (map :my/id)
                 (:my.derived/ref+-non-enum refer)))
        "Derived attribute implementations can walk to other entities.")))

(deftest derived-attribute-circular-dep
  (let [{:keys [db]} *env*
        e1 (eem/entity db [:my/id "e1"])]
    (is (thrown? StackOverflowError (:my.derived/circular-dep-a e1))
        "Circular derived attribute dependencies will eventually StackOverflow.")))

(comment
 (def env (test-env*))
 (def db (d/db (:conn env)))
 )

;; Below here is code from the readme, here to test the docs actually run.

(comment

 (require '[net.favila.enhanced-entity-map :as eem]
          '[datomic.api :as d])

 ;; How you construct one
 (def enhanced-em (eem/entity db [:my/id "e1"]))

 ;; You can also convert an existing entity-map
 (def normal-em (d/entity db [:my/id "e1"]))
 (d/touch normal-em)
 ;; Conversion will copy the cache of the entity map at the moment you convert it.
 (def enhanced-em-clone (eem/as-enhanced-entity normal-em))

 ;; Enhanced entity maps also support Datomic entity-map functions
 (d/touch enhanced-em)
 (d/entity-db enhanced-em)

 ;; However they can never be equal to each other
 (= enhanced-em normal-em)
 ;; => false

 (= (hash enhanced-em) (hash normal-em))
 ;; => true
 )

(comment
 (meta normal-em)
 ;; => nil
 (with-meta normal-em {:foo :bar})
 ;; class datomic.query.EntityMap cannot be cast to class clojure.lang.IObj

 (meta (with-meta enhanced-em {:foo :bar}))
 ;; => {:foo :bar}

 )

(comment
 ;; You can assoc any value you want, even types not supported by Datomic.
 (def enhanced-em-assoc (assoc enhanced-em :not-a-real-attr [:value]))
 (:not-a-real-attr enhanced-em-assoc)
 ;; => [:value]

 ;; The return value is still an entity-map connected to the database,
 ;; so it can still perform lazy-lookups of values you haven't read yet.

 (:my/id enhanced-em)
 ;; => "e1"

 ;; But note assoc doesn't mutate!
 (:not-a-real-attr enhanced-em)
 ;; => nil

 ;; associng shadows attributes and derived-attributes (discussed below)
 (= :shadowed (:my/id (assoc enhanced-em :my/id :shadowed)))
 ;; => :shadowed

 ;; Associng also adds value-equality semantics.
 ;; An enhanced entity map which has been edited by assoc will never be equal
 ;; to or hash the same as an un-assoced map.

 (= enhanced-em (eem/entity db [:my/id "e1"]))
 (not= enhanced-em enhanced-em-assoc)
 ;; => true
 (not= (hash enhanced-em) (hash enhanced-em-assoc))
 ;; => true

 ;; EVEN IF you assoc an attribute with the *same value it actually has*:
 (not= (assoc enhanced-em :my/id "e1") enhanced-em)
 ;; => true

 (= (:my/id enhanced-em-assoc) (:my/id enhanced-em))

 )

(comment

 (defmethod eem/entity-map-derived-attribute :my.derived/ref+-non-enum
   [em _attr-kw]
   (into #{} (remove keyword?) (:my/ref+ em)))

 (def refer (eem/entity db [:my/id "refer"]))
 (:my/ref+ refer)
 ;; => #{#:db{:id 17592186045419} #:db{:id 17592186045418} :enum/e3}

 (:my.derived/ref+-non-em refer)
 ;; => #{#:db{:id 17592186045419} #:db{:id 17592186045418} :enum/e3}

 ;; The results of derived-attr calls are cached on the entity;
 ;; so are any other reads the method may happen to perform on the entity.

 ;; You can read a derived ref from a derived ref:

 (defmethod eem/entity-map-derived-attribute :my.derived/ref+-non-enum-sorted
   [em _attr-kw]
   (sort-by :my/id (:my.derived/ref+-non-enum em)))

 (:my.derived/ref+-non-enum-sorted refer)
 ;; => ({:db/id 17592186045418, :my/id "e1"} {:db/id 17592186045419, :my/id "e2"})

 ;; Note that reverse refs are not magical like they are for normal attributes,
 ;; but you can implement a method with a reverse-ref-looking attribute.
 (defmethod eem/entity-map-derived-attribute :my.derived/_fake-reverse-ref
   [em _attribute-kw]
   #{(:my/real-forward-ref em)})

 )
