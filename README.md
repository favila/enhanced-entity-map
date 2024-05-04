# Enhanced Entity Maps

This provides a Datomic entity-map-like object (as returned from
`datomic.api/entity`) which has a few additional abilities.

## Installation

Arifacts are hosted on clojars.

## Status

This has not been used in production, but has extensive test coverage to ensure
interface compatibility and behavior parity with normal Datomic entity maps.
Evaluate this carefully before using for anything serious.

## Motivations

You have a large codebase already committed to Datomic entity maps as a 
primary means of interacting with Datomic. Refactoring to pull and query
would be a large and dangerous undertaking because you don't know what 
code depends on what attributes. This is either because of your own fault 
and poor planning, or because you use a framework which makes it difficult 
to predict what data you might need and the laziness of entity-maps was an 
ergonomic advantage.

Symptoms you may be experiencing:

* You have poor performance on map/filter style code over entities
  because of EAVT index use.
  (Should really be queries, but sometimes refactors are nontrivial.)
* You are struggling against entity map closedness: you need a little bit extra 
  on an entity sometimes (maybe just some metadata), but have to convert it to 
  a map before you pass it along.
  The conversion removes the connection to the database, so attribute 
  lookups on downstream  code starts to return nil when they shouldn't.
  (Should really be plain pull with plain augmented maps, and you know your 
  code's requirements.)
* Related to closedness above: you can't maintain the "get an attribute"
  interface if you refactor your Datomic attributes. For example, you 
  used to materialize an attribute but now want to compute it as part of
  a migration; or you want to provide a common attribute interface.
  (Should really be plain pull and maps; or pull with attribute renaming
  and/or xform.)
* You compute some expensive value which is a pure function of an entity,
  and you end up computing it multiple times because there's no ergonomic way
  to keep that value with the entity.
  (Should just use [Pathom].)

[Pathom]: https://pathom3.wsscode.com/

## Abilities

So what is "enhanced" about enhanced entity-maps vs normal Datomic entity-maps?

### Same Interfaces

First lets talk about what is the same.

Enhanced entity maps are a drop-in replacement for normal Datomic entity maps.
It implements the same Entity interface and all the same behavior as normal
entity maps, even the quirky stuff.
(See the `basic-entity-map-and-aevt-parity` test.)

```clojure
(require '[net.favila.enhanced-entity-map :as eem]
         '[datomic.api :as d])

;; How you construct an enhanced entity map
(def enhanced-em (eem/entity db [:my/id "e1"]))

;; You can also convert an existing entity-map
(def normal-em (d/entity db [:my/id "e1"]))
(d/touch normal-em)
;; Conversion will copy the cache of the entity map at the moment you convert it.
(def enhanced-em-clone (eem/as-enhanced-entity normal-em))

;; Enhanced entity maps also support Datomic entity-map functions
(d/touch enhanced-em)
(d/entity-db enhanced-em)

;; However normal and enhanced entity maps can never be equal to each other
(= enhanced-em normal-em)
;; => false

;; But they do hash the same
(= (hash enhanced-em) (hash normal-em))
;; => true

;; However you should be really cautious about equality of even normal Datomic
;; entity maps--its semantics are a bit surprising.

;; Also assoc-ability changes equality and hash semantics; see below!
```

### Metadata

Enhanced entity maps support metadata.

```clojure
(meta normal-em)
;; => nil
(with-meta normal-em {:foo :bar})
;; class datomic.query.EntityMap cannot be cast to class clojure.lang.IObj

(meta (with-meta enhanced-em {:foo :bar}))
;; => {:foo :bar}
```

### Assoc-ability

You can assoc arbitrary keyword and value entries onto it,
even keywords that are attribute idents.
Lookups will inspect these values first before hitting the database.

```clojure
;; You can assoc any value you want, even types not supported by Datomic
;; such as nil.
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

;; ... even if you assoc an attribute with the *same value it actually has*!
(not= (assoc enhanced-em :my/id "e1") enhanced-em)
;; => true
(= (:my/id enhanced-em-assoc) (:my/id enhanced-em))
;; => true
```

Associng can be handy for:

* adding novelty to an entity--completely new attributes and values the
  database doesn't know about.
* Precaching existing attributes, e.g. from tabular results of a query into
  entity maps where you know most downstream code probably won't need
  any other values. (This avoids looking the value up from indexes twice.)
* Shadowing or overriding actual attribute values the entity has.

## Optional AEVT index use

Datomic entity maps only use two indexes for their reads:
EAVT for forward attributes and VAET for reverse attributes.

Normally EAVT is the right choice: if you are reading an attribute from an 
entity map, you are most likely to want another attribute from the same 
entity map, so EAVT will amortize the IO cost of that next read by using the 
same index segment.

However, some code walks over many entities but only reads a few attributes 
from each. For example:

```clojure
(->> (:my/high-cardinality-ref some-entity)
     (mapcat :my/other-ref)
     (map :my/scalar)
     (filter my-pred?))
```

Code like this can get really slow with entity maps because of all the
EAVT access.
This *should* be a datalog query which will prefer AEVT indexes in most 
circumstances, but sometimes the refactor is nontrivial. 

Enhanced entity maps can selectively use AEVT indexes instead of EAVT for reads.
This makes entity-maps more efficient for map-and-filter style
work that reads a few attributes from many entities.

The example above can be rewritten like this:

```clojure
(eem/prefer-aevt
 (->> (:my/high-cardinality-ref some-entity)
      (mapcat :my/other-ref)
      (map :my/scalar)
      (filter my-pred?)
      ;; The "preference" is implemented with a dynamic binding,
      ;; so make sure you aren't lazy! 
      vec))
```

You can switch in and out of aevt mode at any level:

```clojure
(eem/prefer-aevt
 (->> (eem/prefer-eavt (:my/high-cardinality-ref some-entity))
      (mapcat :my/other-ref)
      (map :my/card1-ref)
      (filter #(eem/prefer-eavt (my-pred-that-reads-lots-of-attrs? %)))
      ;; `prefer-X` is implemented with a dynamic binding,
      ;; so look out for laziness.
      vec))
```

Any values read while in any mode are cached on the entity map like normal,
so you never have to pay to read the same value twice.

### Derived attributes

Very often there's some value which is a pure function of an entity:
for example, it's a normalized, defaulted, filtered or sorted view of an 
existing attribute, or it's a combination of two attribute's values.

If you have such a value, you can now express that value as a "derived" 
attribute. No one has to know it isn't a real Datomic attribute!

Implement the multimethod `eem/entity-map-derived-attribute` for your 
fully-qualified attribute. This method accepts the current enhanced entity map
and the attribute you are looking up.

```clojure
;; To do this, implement the multimethod for your attribute:
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
```

This multimethod is only called if the attribute does not exist in the 
entity map's *database*!
As a consequence, you can't use this feature compute a value for an 
existing attribute.

## Testing and Building

(This is just to remind myself.)

```shell
clojure -Xtest
clojure -T:build clean
clojure -T:build jar
clojure -T:build deploy
```

## License

MIT License

Copyright © 2024 Francis Avila
