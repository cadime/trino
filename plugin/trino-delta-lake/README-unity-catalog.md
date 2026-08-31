# Unity Catalog Integration for Trino Delta Lake

This document describes the Databricks Unity Catalog (UC) metastore backend for the Trino Delta Lake connector, implemented on top of Trino R483.

## Overview

The integration exposes a Unity Catalog as a read-only Trino connector. Schema and table discovery uses the UC REST API; data reading goes through the Delta transaction log as usual (no UC-specific data path).

## Architecture

### Package

All UC-specific code lives in:
```
plugin/trino-delta-lake/src/main/java/io/trino/plugin/deltalake/metastore/unitycatalog/
```

### Entry point

`DeltaLakeMetastoreModule` reads `delta.metastore.type`. When set to `unity-catalog` it installs `DeltaLakeUnityCatalogMetastoreModule`, which wires up all UC components.

### Key design decisions

| Decision | Rationale |
|---|---|
| Internal `DeltaLakeMetastoreType` enum | Keeps `MetastoreTypeConfig` in trino-hive untouched |
| Views return empty / NOT_SUPPORTED for mutations | Read-only v1; no SQL preprocessing needed |
| No UC type parser | Types come from the Delta transaction log — UC column metadata is redundant |
| OAuth2 + bearer token auth | Production-grade auth without a Databricks SDK dependency |
| Airlift `HttpClient` + `JsonCodec` | Idiomatic Trino — mirrors the Iceberg REST catalog |
| Single branch `unity_catalog_480` | All work is on one branch, no feature flags |

## Files

| File | Role |
|---|---|
| `DeltaLakeUnityCatalogMetastoreConfig` | All `delta.unity-catalog.*` properties |
| `DeltaLakeUnityCatalogMetastoreModule` | Guice wiring for the whole UC stack |
| `UnityCatalogBackedDeltaLakeMetastore` | Core: REST client, caching, `DeltaLakeMetastore` impl |
| `UnityCatalogAuthProvider` | Interface for auth header generation |
| `StaticTokenAuthProvider` | Bearer token auth |
| `OAuth2ClientCredentialsAuthProvider` | OAuth2 client-credentials with token caching |
| `OAuth2AuthConfig` | `delta.unity-catalog.oauth2.*` properties |
| `UnityCatalogViewSupport` | Bridge: UC views → `ConnectorViewDefinition` |
| `UnityCatalogViewTranslator` | Interface for Spark SQL → Trino SQL translation |
| `PassThroughViewTranslator` | Tokenizer-based translator (backticks, `SELECT * EXCEPT`, etc.) |
| `UnityCatalogTypeMapping` | Hive/Spark type text → Trino type |
| `UnityCatalogView` | Record: view definition + columns |
| `DeltaLakeUnityCatalogTableOperations` | Table ops — only `commitToExistingTable` throws NOT_SUPPORTED |
| `ForUnityCatalog` | Guice binding annotation for the UC HTTP client |

## Configuration

### Minimal (token auth)

```properties
connector.name=delta_lake
delta.metastore.type=unity-catalog
delta.unity-catalog.uri=https://<workspace>.azuredatabricks.net/api/2.1/unity-catalog
delta.unity-catalog.catalog-name=<catalog>
delta.unity-catalog.security=token
delta.unity-catalog.token=dapi...
```

### OAuth2 (service principal)

```properties
connector.name=delta_lake
delta.metastore.type=unity-catalog
delta.unity-catalog.uri=https://<workspace>.azuredatabricks.net/api/2.1/unity-catalog
delta.unity-catalog.catalog-name=<catalog>
delta.unity-catalog.security=oauth2
delta.unity-catalog.oauth2.token-uri=https://<workspace>.azuredatabricks.net/oidc/v1/token
delta.unity-catalog.oauth2.client-id=<app-id>
delta.unity-catalog.oauth2.client-secret=<secret>
```

### Cache (optional, recommended for production)

```properties
delta.unity-catalog.metastore-cache-ttl=1m
delta.unity-catalog.metastore-cache-maximum-size=10000
```

### All properties

| Property | Default | Description |
|---|---|---|
| `delta.unity-catalog.uri` | (required) | UC REST API base URI, no trailing slash |
| `delta.unity-catalog.catalog-name` | (required) | UC catalog name to expose |
| `delta.unity-catalog.security` | `token` | Auth mode: `token` or `oauth2` |
| `delta.unity-catalog.token` | — | Personal access token (required when security=token) |
| `delta.unity-catalog.hide-non-delta-lake-tables` | `false` | Hide tables with non-DELTA format |
| `delta.unity-catalog.metastore-cache-ttl` | `1m` | Metadata cache TTL; `0s` to disable |
| `delta.unity-catalog.metastore-cache-maximum-size` | `10000` | Max cache entries |
| `delta.unity-catalog.oauth2.token-uri` | (required for oauth2) | Token endpoint |
| `delta.unity-catalog.oauth2.client-id` | (required for oauth2) | Service principal app ID |
| `delta.unity-catalog.oauth2.client-secret` | (required for oauth2) | Service principal secret |
| `delta.unity-catalog.oauth2.scope` | `all-apis` | OAuth2 scope |
| `delta.unity-catalog.oauth2.refresh-skew` | `60s` | Refresh tokens this early before expiry |

## Caching

Three independent caches, all with the same TTL:

- **schemasCache** — list of schemas in the catalog (1 entry max)
- **tablesCache** — list of tables per schema (keyed by schema name)
- **tableCache** — table/view detail per `SchemaTableName` (includes 404 misses)

Cache can be flushed manually:
```sql
CALL system.flush_metadata_cache();                           -- flush all
CALL system.flush_metadata_cache('schema', 'table');         -- flush one table
```

## What is NOT supported (read-only v1)

- Creating, dropping, or renaming schemas and tables
- Writing to tables (`commitToExistingTable` throws NOT_SUPPORTED)
- Full view SQL translation (complex Spark SQL may fail to translate)
- Column/table statistics from UC (statistics come from Delta transaction log)
- Partition discovery via UC API (partitions are in the transaction log)
- Unity Catalog RBAC (auth is transport-level only, via token or OAuth2)

## Table type handling

| UC `table_type` | `data_source_format` | Exposed as |
|---|---|---|
| `EXTERNAL` / `MANAGED` | `DELTA` | Delta table |
| `STREAMING_TABLE` with reconciliation_query | any | Trino view (wraps the reconciliation query) |
| `STREAMING_TABLE` without reconciliation_query | any | Delta table (reads backing table directly) |
| `MATERIALIZED_VIEW` | `DELTA` | Delta table |
| `VIEW` | — | Trino view (translated from Spark SQL) |
| any | non-DELTA (CSV, JSON, …) | `NotADeltaLakeTableException` |

## Error codes

| HTTP status | Trino error code |
|---|---|
| 401, 403 | `PERMISSION_DENIED` |
| 404 | `Optional.empty()` (table/schema not found) |
| 429 | `TOO_MANY_REQUESTS_FAILED` |
| 5xx | `GENERIC_INTERNAL_ERROR` |
| other non-2xx | `DELTA_LAKE_BAD_DATA` |
| unparseable JSON | `DELTA_LAKE_BAD_DATA` |
