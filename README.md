# Enhanced Entity Maps

This provides a Datomic entity-map-like object (as returned from
`datomic.api/entity`) which has a few additional abilities.

### Assoc-ability
You can assoc arbitrary keyword and value entries onto it. 
Lookups will inspect these values first before hitting the database.


### Optional AEVT index use

You can make it use AEVT indexes instead of EAVT for reads.
This makes entity-maps more efficient for map-and-filter style
work that reads a few attributes from many entities.

### Derived attributes

You can implement a multimethod `eem/entity-map-derived-attribute`


# Status

This is very alpha. It needs more test coverage and proper release 
versioning and artifacts. Don't use in production yet.

# Motivations

You have a large codebase already committed to Datomic entity maps as a 
primary means of interacting with datomic. Refactoring to pull and query
would be a large and dangerous undertaking because you don't know what 
code depends on what attributes. This is either because of your own fault 
and poor planning, or because you use a framework which makes it difficult 
to predict what data you might need and the laziness of entity-maps was an 
ergonomic advantage.

Symptoms you may be experiencing:

* Performance on map/filter style code over entities. (Should really be 
  queries.)
* Entity map closedness: you need a little bit extra on an entity sometimes,
  but have to convert it to a map before you pass it along. The conversion
  removes the connection to the database, so attribute lookups on downstream
  code starts to return nil when they shouldn't. (Should really be plain 
  pull with plain augmented maps and you know your code's requirements.)
* Related to closedness above: you can't maintain the "get an attribute"
  interface if you refactor your datomic attributes. For example, you 
  used to materialize an attribute but now want to compute it as part of
  a migration; or you want to provide a common attribute interface.
  (Should really be plain pull and maps; or pull with attribute renaming
  and/or xform.)
* You compute some expensive value which is a function of an entity,
  and you end up computing it multiple times because there's no ergonomic way
  to keep that value with the entity.



