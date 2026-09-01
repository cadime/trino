---
paths:
  - "**/deltalake/metastore/unitycatalog/*.java"
  - "**/deltalake/metastore/DeltaLakeMetastoreModule.java"
  - "**/deltalake/metastore/DeltaLakeMetastoreTypeConfig.java"
  - "**/deltalake/metastore/file/DeltaLakeFileMetastoreModule.java"
  - "**/deltalake/metastore/glue/DeltaLakeGlueMetastoreModule.java"
  - "**/deltalake/metastore/thrift/DeltaLakeThriftMetastoreModule.java"
---

# Unity Catalog metastore backend for Delta Lake

Fork-specific code. Full reference: `plugin/trino-delta-lake/README-unity-catalog.md`.

## Type mapping must agree with Delta

`UnityCatalogTypeMapping` is used for **view columns only** — table columns come from the Delta
transaction log. For any type name that exists in both, it must produce exactly the same Trino type
as `DeltaLakeSchemaSupport.deserializeType`. Diff the two switch statements before changing either.

A view's declared column type is part of its schema. Declare it wider than the underlying Delta
column and Trino inserts a `CAST` on the base column to satisfy the view schema; a `CAST` around a
column stops `DomainTranslator` extracting a domain, so no predicate reaches the scan and Delta
file skipping is lost. **The symptom is a silent slowdown, not a type error** — this cost a 200x
regression once, with `timestamp` mapped to `TIMESTAMP_TZ_MICROS` instead of `TIMESTAMP_TZ_MILLIS`.

Verify in `EXPLAIN`: a pushed-down predicate disappears from `filterPredicate`. If the column shows
up wrapped in a `CAST` there, pushdown is gone.

## Views run as DEFINER

`UnityCatalogViewSupport` builds `ConnectorViewDefinition` with `runAsInvoker = false` and owner
`UnityCatalogViewSupport.VIEW_OWNER` (`system_user`), so view access can be granted without
granting access to the base tables. `ConnectorViewDefinition` throws if an owner is present with
`runAsInvoker = true` — the two always move together.

Catalog and schema are deliberately `Optional.empty()`: UC view SQL is expected to use
fully-qualified table names.

## Read-only

Every mutation path throws `NOT_SUPPORTED` (`DeltaLakeUnityCatalogTableOperations`, and the
create/drop/rename methods on the metastore). Do not add write paths.

## Adding an `Optional<SomeUCClass>` injection

Guice needs the binding on every metastore path, not just UC:

1. `@Provides Optional<SomeUCClass>` returning the real instance in
   `DeltaLakeUnityCatalogMetastoreModule`
2. `@Provides Optional<SomeUCClass>` returning `Optional.empty()` in **all three** Hive modules —
   file, glue, thrift

Missing step 2 breaks the Hive metastore paths at connector creation, which `TestDeltaLakePlugin`
catches.

## Conventions

- Properties are prefixed `delta.unity-catalog.*`, OAuth2 ones `delta.unity-catalog.oauth2.*`.
  Never `hive.*`.
- Auth goes through Airlift `HttpClient` + `JsonCodec`. No Databricks SDK, no UC OSS client jar.
- View translation is tokenizer-based (`PassThroughViewTranslator`). No Coral dependency.
- HTTP errors in `callAllowing404`: 401/403 → `PERMISSION_DENIED`, 429 →
  `TOO_MANY_REQUESTS_FAILED`, 5xx → `GENERIC_INTERNAL_ERROR`, other non-2xx and JSON parse failure
  → `DELTA_LAKE_BAD_DATA`. Response bodies are truncated to 500 chars.
- The three `EvictableCacheBuilder` caches (schemas, tables, table detail) are disabled at TTL 0
  and read via `CacheUtils.uncheckedCacheGet`. New cache state must be invalidated from both
  `invalidateAll()` and `invalidateTable()`, which back `FlushMetadataCacheProcedure`.

## Not UC's fault

Partitions come from the transaction log, so pruning works as with a Hive metastore. A view
predicate comparing a partition column against a data column never prunes, in any Trino release:
`DomainTranslator` needs one side of a comparison to be constant. Don't go looking for a UC bug
when that is the shape of the predicate.
