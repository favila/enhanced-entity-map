(ns net.favila.enhanced-entity-map
  "Enhanced Entity Maps.

  Public interface:

  * entity: Make an entity map like d/entity but with special powers:
    * Can support metadata.
    * Can assoc values on to it.
    * Can compute and cache derived attributes. See entity-map-derived-attribute below.
    * Can do database reads using the :aevt index selectively.

  * as-enhanced-entity: Convert a d/entity to an enhanced entity map
    while preserving its cache. Note that unfortunately entity maps and
    enhanced entity maps do not compare equal using clojure.core/= because
    entity maps do an explicit class check.

  * prefer-aevt: Use AEVT index instead of EAVT index for enhanced-entity-map
    database reads done in its body.

  * prefer-eavt: Same, but prefers EAVT to AEVT. (This is the default,
    and what d/entity does.)

  * entity-map-derived-attribute: A multimethod which you can implement to make
    derived, computed attributes. The method accepts an enhanced entity map
    and an attribute (possibly reverse) and returns a computed value, which
    is then cached on the enhanced entity map.

  * entity-map?: Predicate to test for a normal or enhanced entity map."
  (:require
   [clojure.core.cache]
   [clojure.core.cache.wrapped :as cw]
   [datomic.api :as d]
   [datomic.db]
   [datomic.query])
  (:import
   (clojure.core.cache BasicCache)
   (clojure.lang Associative Counted ILookup IObj IPersistentCollection IPersistentSet MapEntry Seqable Util)
   (datomic Datom Entity)
   (datomic.db IDbImpl)
   (datomic.query EMapImpl EntityMap)
   (java.io Writer)))

(def entity-map-derived-attribute-registry (make-hierarchy))

(defn- entity-map-derived-attribute-dispatch
  [_enhanced-entity-map attribute-kw] attribute-kw)

(defmulti entity-map-derived-attribute
  "Given an entity map and keyword from entity-map lookup,
   return a derived computed attribute value.

   This method is called implicitly by lookups on enhanced-entity-maps
   if the attribute wasn't manually assoc-ed and does not exist on the database."
  entity-map-derived-attribute-dispatch
  :default ::not-implemented
  :hierarchy #'entity-map-derived-attribute-registry)

(defmethod entity-map-derived-attribute ::not-implemented [_eem _kw]
  ::not-implemented)

(defn- make-derived-attr-cache []
  (cw/basic-cache-factory {}))

(defn- deref-derived-attr-cache [attr-cache]
  (.cache ^BasicCache @attr-cache))

(defn- find-or-miss-derived-attr [attr-cache em attr]
  (let [v (cw/lookup-or-miss attr-cache attr entity-map-derived-attribute em)]
    (when-not (identical? ::not-implemented v)
      (MapEntry/create attr v))))

(defn- not-nil-val [entry]
  (when (some? (val entry))
    entry))

(declare enhanced-entity*)

(def ^:dynamic *prefer-index*
  "Which index to prefer for enhanced-entity-map reads when there is a choice
  between :eavt or :aevt.

  Do not set this directly: use `prefer-aevt` or `prefer-eavt`."
  :eavt)

(defn- iterable-first
  [^Iterable it]
  (let [i (.iterator it)]
    (when (.hasNext i)
      (.next i))))

(defn- not-cempty [^Counted x]
  (when-not (== 0 (.count x))
    x))

(defn- enhanced-entity-or-ident
  [db eid]
  (or (d/ident db eid)
      (enhanced-entity* db eid)))

(defn- lookup-vae [db e attr-rec]
  (let [{:keys [id is-component]} attr-rec
        xs (d/datoms db :vaet e id)]
    ;; Note: reverse refs never produce ident keywords!
    ;; This is what Datomic's EntityMap does.
    (if is-component
      (when-some [^Datom d (iterable-first xs)]
        (enhanced-entity* db (.e d)))
      (not-cempty
       (into #{}
             (map #(enhanced-entity* db (.e ^Datom %)))
             xs)))))

(defn- lookup-eav [db e attr-rec]
  (let [{:keys [id cardinality value-type]} attr-rec
        xs (if (= :aevt *prefer-index*)
             (d/datoms db :aevt id e)
             (d/datoms db :eavt e id))]
    (cond
      (= :db.type/ref value-type)
      (if (= :db.cardinality/one cardinality)
        (when-some [^Datom d (iterable-first xs)]
          (enhanced-entity-or-ident db (.v d)))
        (not-cempty
         (into #{}
               (map #(enhanced-entity-or-ident db (.v ^Datom %)))
               xs)))

      (= :db.cardinality/one cardinality)
      (when-some [^Datom d (iterable-first xs)]
        (.v d))

      :else
      (not-cempty (into #{} (map #(.v ^Datom %)) xs)))))

(defn- find-eav [db e attr]
  ;; reverse-lookup? seems to handle special case of an ident whose name
  ;; actually starts with _
  (let [rlookup? (datomic.db/reverse-lookup? db attr)
        fwd-attr (if rlookup?
                   (datomic.db/reverse-key attr)
                   attr)]
    (when-some [attr-rec (d/attribute db fwd-attr)]
      (if rlookup?
        (when (= :db.type/ref (:value-type attr-rec))
          (MapEntry/create attr (lookup-vae db e attr-rec)))
        (MapEntry/create attr (lookup-eav db e attr-rec))))))

(defn- maybe-touch [x]
  (if (instance? Entity x)
    (d/touch x)
    x))

(defn- rfirst
  ([] nil)
  ([r] r)
  ([_ x] (reduced x)))

(defn- touch-map
  [db eid touch-components?]
  (into {:db/id eid}
        (comp
         (partition-by :a)
         (map (fn [datoms]
                (let [{:keys [ident cardinality value-type is-component]} (d/attribute db (:a (first datoms)))
                      ref? (= :db.type/ref value-type)
                      many? (= :db.cardinality/many cardinality)
                      xform (cond-> (map :v)

                                    ref?
                                    (comp (map (partial enhanced-entity-or-ident db)))

                                    (and touch-components? is-component ref?)
                                    (comp (map maybe-touch)))]
                  [ident
                   (if many?
                     (into #{} xform datoms)
                     (transduce xform rfirst datoms))]))))
        (d/datoms db :eavt eid)))

(defprotocol AsEnhancedEntity
  (-as-enhanced-entity [o] "Coerce object to an enhanced entity map."))

(deftype EnhancedEntityMap
  ;; db is a non-history datomic db
  ;; eid is an entity id (nat-int? eid) => true
  ;; assoc-cache is a PersistentMap
  ;; datomic-attr-cache is a PersistentMap
  ;; derived-attr-cache is a clojure.core.cache.wrapped atom over a BasicCache.
  ;; (The core.cache wrapping is just for stampede protection, not eviction.)
  [db ^long eid assoc-cache ^:unsynchronized-mutable datomic-attr-cache derived-attr-cache metadata]
  AsEnhancedEntity
  (-as-enhanced-entity [this] this)

  IObj
  (meta [_] metadata)
  (withMeta [_ m] (EnhancedEntityMap. db eid assoc-cache datomic-attr-cache derived-attr-cache m))

  Associative
  (entryAt [this attr]
   ;; Like d/entity we return no-entry if the value is nil.
    (if-some [entry (find assoc-cache attr)]
      (not-nil-val entry)
      (if-some [entry (find datomic-attr-cache attr)]
        entry                                               ;; never nil value
        (if-some [new-entry (find-eav db eid attr)]
          ;; Unlike d/entity, we store nil values of "miss" lookups if
          ;; the database recognizes the attr.
          ;; This is to avoid hitting the derived-attr system and a possible
          ;; method-not-implemented exception.
          (do
            (set! datomic-attr-cache (conj datomic-attr-cache new-entry))
            (not-nil-val new-entry))
          (when-some [entry (find-or-miss-derived-attr derived-attr-cache this attr)]
            (not-nil-val entry))))))
  (containsKey [this attr]
    (some? (.entryAt this attr)))
  (assoc [_ attr v]
    (assert (keyword? attr) "attr must be keyword")
    (EnhancedEntityMap. db eid (assoc assoc-cache attr v) datomic-attr-cache derived-attr-cache metadata))

  EMapImpl
  (cache [_] datomic-attr-cache)

  Entity
  (get [this attr] (.valAt this attr))
  (touch [this]
    (let [cache (touch-map db eid true)]
      (set! datomic-attr-cache cache)
      this))
  (keySet [this]
    (into #{}
          (map (comp str key))
          (.seq this)))
  (db [_] db)

  ILookup
  (valAt [this attr]
    (when-some [e (.entryAt this attr)]
      (val e)))
  (valAt [this attr notfound]
    (if-some [e (.entryAt this attr)]
      (val e)
      notfound))

  IPersistentCollection
  (count [this] (count (.seq this)))
  ;; EntityMap does not implement IPersistentCollection.cons
  (empty [_]
    (enhanced-entity* db eid {}))
  (equiv [_ other]
   ;; We can never make this equivalent to EntityMaps
   ;; because EntityMaps do an explicit class check for EntityMap.
    (and
     (instance? EnhancedEntityMap other)
     (== eid (.eid ^EnhancedEntityMap other))
     (= (.getRawId ^IDbImpl db)
        (.getRawId ^IDbImpl (.db ^EnhancedEntityMap other)))
     (= assoc-cache (.-assoc-cache ^EnhancedEntityMap other))))

  Seqable
  (seq [_]
    (let [datomic-attrs (-> (touch-map db eid false)
                            ;; Note: seq of d/entity does not include :db/id!
                            (dissoc :db/id))
          derived-attrs (deref-derived-attr-cache derived-attr-cache)
          not-assoc-attr-or-nil (fn [[k v]] (or (nil? v)
                                                (identical? ::not-implemented v)
                                                (contains? assoc-cache k)))]
      (concat
       assoc-cache
       (remove not-assoc-attr-or-nil datomic-attrs)
       ;; derived-attrs will never contain keys that are also datomic idents
       (remove not-assoc-attr-or-nil derived-attrs))))

  Object
  (hashCode [_]
    (let [hc (Util/hashCombine
              (hash (.getRawId ^IDbImpl db))
              (hash eid))]
      ;; If assoc-cache is empty, hashes the same way as an EntityMap
      ;; Otherwise includes the assoc-cache in its hash
      (if (== 0 (.count ^Counted assoc-cache))
        hc
        (Util/hashCombine hc (hash assoc-cache)))))
  (toString [this]
    (binding [*print-length* 20
              *print-level* 3]
      (pr-str this))))

(defmethod print-method EnhancedEntityMap [^EnhancedEntityMap eem ^Writer w]
  (print-method
   (-> (.-assoc-cache eem)
       (into (remove (comp nil? val)) (.cache eem))
       (into (remove (comp #(identical? ::not-implemented %) val))
             (deref-derived-attr-cache (.-derived-attr-cache eem))))
   w))

(defn- enhanced-entity*
  ([db eid]
   (->EnhancedEntityMap db eid {} {:db/id eid}
                        (make-derived-attr-cache)
                        nil))
  ([db eid attr-cache]
   (->EnhancedEntityMap db eid {}
                        ;; attr-cache should be a copy of another Entity cache
                        ;; so this is expected to have :db/id already
                        attr-cache
                        (make-derived-attr-cache)
                        nil)))

(defn entity
  "Returns an \"enhanced\" entity map satisfying all the interfaces of
  datomic.api/entity, but with additional abilities:

   * It supports metadata.
   * It can optionally use AEVT indexes for database reads if it runs within
     the `prefer-aevt` macro, which makes it more suitable for map/filter-style
     code over many entity maps.
   * Arbitrary keywords and values can be assoc-ed on to the entity.
     The result of the assoc is a new enhanced-entity with that additional entry
     added to its cache.
   * It can be extended with \"virtual\" attributes via the
     `entity-map-derived-attribute` multimethod.
     This multimethod receives this enhanced-entity map and the attribute
     (possibly a reverse-attribute with underscore) and returns a computed
     value which will be cached on this entity.
     Concurrent access to the same derived attributes will only perform work
     once (i.e. no stampedes), and derived attributes may access other derived
     attributes in their method body.

   Keyword lookups are always performed from these sources in this order.
   A nil value is always regarded as \"not found\", but the fact that it was not
   found will still be cached.

   1. Explicitly assoc-ed keys. Nil values will act as \"no entry\" to `find`
      and `get`, but still short-circuit this lookup process.
   2. Datomic attributes or reverse-attributes in the entity-map's database.
   3. Only if *not* a datomic attribute or reverse-attribute: the
      entity-map-derived-attribute multimethod.

   Note that this means you cannot define `entity-map-derived-attribute` methods
   usefully for keywords which are also attribute names in the entity map's
   database."
  [db eid]
  (when (d/is-history db)
    ;; Same exception as d/entity throws.
    (throw (IllegalStateException. "Can't create entity from history")))
  (when-some [dbid (d/entid db eid)]
    (enhanced-entity* db dbid)))

(extend-protocol AsEnhancedEntity
  ;; Datomic's entity maps
  EntityMap
  (-as-enhanced-entity [entity-map]
    (enhanced-entity* (d/entity-db entity-map)
                      (.eid ^EntityMap entity-map)
                      ;; Reuse whatever is already cached
                      (update-vals (.cache ^EntityMap entity-map)
                                   -as-enhanced-entity)))

  ;; What `set?` tests.
  ;; These are presumed to be cardinality-many values inside entity maps.
  IPersistentSet
  (-as-enhanced-entity [xs]
   (into #{} (map -as-enhanced-entity) xs))

  Object
  (-as-enhanced-entity [self] self))

(defn as-enhanced-entity
  "Returns an enhanced-entity from an existing normal d/entity object or
   set of such objects. Copies the cache of any d/entity objects found.

   Returns input unchanged if there are no d/entity objects."
  [entity-map]
  (-as-enhanced-entity entity-map))

(defmacro prefer-aevt
  "Use the :aevt index for enhanced-entity-map lookups which aren't already
  cached.

  This is generally better if performing a read of a few attributes over
  many entity maps, e.g. via map or filter."
  [& body]
  `(binding [*prefer-index* :aevt]
     ~@body))

(defmacro prefer-eavt
  "Use the :eavt index for enhanced-entity-map lookups which aren't already
  cached.

  This is generally better if performing a read of many attributes on one or a
  small number of entities.

  Note that this is the default behavior and the same behavior as native datomic
  entity maps; this macro is only necessary to \"undo\" a containing
  `prefer-aevt`."
  [& body]
  `(binding [*prefer-index* :eavt]
     ~@body))

(defn entity-map?
  "Returns true if x is a normal or enhanced entity map."
  [x]
  (instance? Entity x))

(defn enhanced-entity-map?
  "Returns true if x is an enhanced entity map."
  [x]
  (instance? EnhancedEntityMap x))
