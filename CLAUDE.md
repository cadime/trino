# Trino Unity Catalog fork — CLAUDE.md

This is a fork of Trino R480 on branch `unity_catalog`. Its sole purpose is adding a **Databricks Unity Catalog (UC) metastore backend** to the Delta Lake connector. All UC-specific code lives in the `unitycatalog` subpackage of the Delta Lake metastore. The rest of the codebase is vanilla Trino R480.

## What this fork does

- Exposes a Unity Catalog as a **read-only** Trino Delta Lake connector
- Schema/table discovery via UC REST API; data reading via the Delta transaction log (unchanged)
- Supports token auth and OAuth2 client-credentials

Reference docs: `plugin/trino-delta-lake/README-unity-catalog.md`

## Unity Catalog file locations

```
plugin/trino-delta-lake/src/main/java/io/trino/plugin/deltalake/
  metastore/
    DeltaLakeMetastoreModule.java          # routes HIVE vs UNITY_CATALOG
    DeltaLakeMetastoreTypeConfig.java      # delta.metastore.type config
    unitycatalog/
      DeltaLakeUnityCatalogMetastoreConfig.java      # all delta.unity-catalog.* properties
      DeltaLakeUnityCatalogMetastoreModule.java      # Guice wiring
      UnityCatalogBackedDeltaLakeMetastore.java      # REST client + cache + DeltaLakeMetastore impl
      UnityCatalogAuthProvider.java                  # auth interface
      StaticTokenAuthProvider.java                   # bearer token
      OAuth2ClientCredentialsAuthProvider.java       # OAuth2 with token refresh
      OAuth2AuthConfig.java                          # delta.unity-catalog.oauth2.* properties
      UnityCatalogViewSupport.java                   # UC views → ConnectorViewDefinition
      UnityCatalogViewTranslator.java                # Spark SQL → Trino SQL interface
      PassThroughViewTranslator.java                 # tokenizer-based translator
      UnityCatalogTypeMapping.java                   # Hive/Spark type text → Trino type
      UnityCatalogView.java                          # view metadata record
      DeltaLakeUnityCatalogTableOperations.java      # all mutations throw NOT_SUPPORTED
      ForUnityCatalog.java                           # Guice binding annotation

  procedure/
    FlushMetadataCacheProcedure.java       # wired to invalidate UC cache on flush

  metastore/file/DeltaLakeFileMetastoreModule.java   # provides Optional<UC> = empty
  metastore/glue/DeltaLakeGlueMetastoreModule.java   # provides Optional<UC> = empty
  metastore/thrift/DeltaLakeThriftMetastoreModule.java # provides Optional<UC> = empty
```

Integration points in the main connector:
- `DeltaLakeMetadata` — `getViews()` / `getView()` call `UnityCatalogViewSupport`
- `DeltaLakeMetadataFactory` — injects `Optional<UnityCatalogViewSupport>`

Dev catalog configs: `testing/trino-server-dev/etc/catalog/` (lakehouse.properties, raw.properties)

## Architectural constraints — do not violate

1. **No changes to `MetastoreTypeConfig` or anything in trino-hive.** The UC type lives in `DeltaLakeMetastoreTypeConfig` (delta-lake-internal enum).
2. **All mutations throw `NOT_SUPPORTED`.** This is a read-only connector; do not add write paths.
3. **No UC type parser.** Types come from the Delta transaction log. UC column metadata is redundant for Delta tables.
4. **Auth via Airlift `HttpClient` only.** No Databricks SDK, no UC OSS client jar.
5. **Views are v1/pass-through.** `getView`/`listViews` work but full Coral-based SQL translation is out of scope. Don't add a Coral dependency.
6. **All work on branch `unity_catalog`.** No feature flags, no new branches for UC work.

If a request would violate any of these, flag it and ask for confirmation before proceeding.

## Property naming convention

All UC properties are prefixed `delta.unity-catalog.*`. OAuth2 properties are `delta.unity-catalog.oauth2.*`. Do not use `hive.*` or generic prefixes for UC config.

## Caching pattern

`UnityCatalogBackedDeltaLakeMetastore` uses three `EvictableCacheBuilder` caches (from `io.trino.cache`):
- `schemasCache` — list of schemas (1 entry)
- `tablesCache` — tables per schema (keyed by schema name)
- `tableCache` — table/view detail per `SchemaTableName` (caches 404s as `Optional.empty()`)

Cache is disabled when TTL = 0. Access via `CacheUtils.uncheckedCacheGet`. Invalidation is exposed via `invalidateAll()` / `invalidateTable()` and wired into `FlushMetadataCacheProcedure`.

## Error handling convention

All HTTP errors in `callAllowing404` map to specific Trino error codes:
- 401/403 → `PERMISSION_DENIED`
- 429 → `TOO_MANY_REQUESTS_FAILED`
- 5xx → `GENERIC_INTERNAL_ERROR`
- JSON parse failure → `DELTA_LAKE_BAD_DATA`
- Response body is truncated to 500 chars in error messages.

## Adding a new UC module or provider

When adding a new `Optional<SomeUCClass>` injection:
1. Add `@Provides Optional<SomeUCClass>` returning the real instance in `DeltaLakeUnityCatalogMetastoreModule`
2. Add `@Provides Optional<SomeUCClass>` returning `Optional.empty()` in all three Hive modules (file, glue, thrift)

## What is out of scope (do not add without explicit agreement)

- Write support (create/drop/rename schema or table)
- Coral-based view translation
- Partition discovery via UC API
- UC RBAC / fine-grained access control
- Databricks SDK dependency
- Changes to trino-hive plugin

## Building

```bash
mvn compile -pl plugin/trino-delta-lake -am --no-transfer-progress
```

To run a local server with the UC catalogs configured:
```bash
# Use the trino-server-dev module with the lakehouse.properties or raw.properties catalog
```
