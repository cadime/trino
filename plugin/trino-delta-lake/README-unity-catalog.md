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
| All mutations throw NOT_SUPPORTED | Read-only v1 — no write paths anywhere |
| UC types parsed for view columns only | Table columns come from the Delta transaction log, where UC column metadata would be redundant |
| OAuth2 + bearer token auth | Production-grade auth without a Databricks SDK dependency |
| Airlift `HttpClient` + `JsonCodec` | Idiomatic Trino — mirrors the Iceberg REST catalog |
| Views run as DEFINER with a generic owner | Lets view access be granted without granting access to the base tables |
| Type mapping mirrors `DeltaLakeSchemaSupport` | A view column type wider than the Delta column inserts a CAST that kills predicate pushdown |
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
| `UnityCatalogViewSupport` | Bridge: UC views → `ConnectorViewDefinition`; owns the DEFINER/owner setting |
| `UnityCatalogViewTranslator` | Interface for Spark SQL → Trino SQL translation |
| `PassThroughViewTranslator` | Tokenizer-based translator (backticks, `SELECT * EXCEPT`, etc.) |
| `UnityCatalogTypeMapping` | UC type text → Trino type, for **view columns only** |
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

## Type mapping

`UnityCatalogTypeMapping` turns UC column type text into Trino types. It is used for **view
columns only** — table columns always come from the Delta transaction log.

**Invariant: for any type name that also exists in Delta, this mapping must produce exactly the
same Trino type as `DeltaLakeSchemaSupport.deserializeType`.**

A view's declared column type is part of its schema. If it is wider than the underlying Delta
column, Trino inserts a `CAST` on the base column to satisfy the view schema. A `CAST` around a
column stops `DomainTranslator` from extracting a domain — it needs one side of a comparison to be
a constant and the other a bare column reference — so no predicate reaches the scan and Delta
file skipping is lost.

The timestamp family, which is where the two mappings are easiest to get wrong:

| UC type text | Trino type | Canonical source |
|---|---|---|
| `timestamp`, `timestamp_ltz` | `timestamp(3) with time zone` (`TIMESTAMP_TZ_MILLIS`) | `DeltaLakeSchemaSupport` — `case "timestamp"` |
| `timestamp_ntz` | `timestamp(6)` (`TIMESTAMP_MICROS`) | `DeltaLakeSchemaSupport` — `case "timestamp_ntz"` |

This was wrong once, and the symptom was not a type error but a silent 200x slowdown. `timestamp`
mapped to `TIMESTAMP_TZ_MICROS` (`timestamp(6) with time zone`), so every view over a Delta
timestamp column lost predicate pushdown. On a real table the scan predicate degraded from

```
datatimestamp BETWEEN timestamp(3) with time zone '...' AND '...'
```

to

```
timestamp(6) with time zone '...' <= CAST(datatimestamp AS timestamp(6) with time zone)
```

and the scan row estimate went from 152,030 rows (23.92 MB) to 31,799,602 rows (4.89 GB) for the
same query. When touching this class, diff it against `DeltaLakeSchemaSupport` first.

Known gap: `variant` exists in the Delta mapping but not here, so a UC view exposing a variant
column fails with `NOT_SUPPORTED`.

## View security model

Views are exposed with `runAsInvoker = false` and owner `system_user` — DEFINER mode. Access
control is then evaluated:

- on the **view**, for the querying user
- on the **base tables**, as `system_user`

`system_user` is expected to hold access to every table, so access to a view can be granted
individually without also granting it on the underlying tables.

Two things to know before changing this: `ConnectorViewDefinition` throws
`IllegalArgumentException` if an owner is present together with `runAsInvoker = true`, so the two
settings always move together; and the owner is a constant
(`UnityCatalogViewSupport.VIEW_OWNER`), not a config property.

## Predicate pushdown and partition pruning

Partitions come from the Delta transaction log, so pruning behaves exactly as it does with a Hive
metastore. The UC backend neither helps nor hinders it — but see the type mapping invariant above,
which is the one UC-specific way to break pushdown.

What does **not** prune, in any Trino release: a view predicate comparing a partition column
against a data column, for example

```sql
where date(date_processed) between date(event_ts) + interval '-7' day
                              and date(event_ts) + interval '1' day
```

`DomainTranslator` requires one side of a comparison to be a constant, so a column-to-column
comparison produces no `TupleDomain` and stays a residual filter above the scan. Trino will not
derive a `date_processed` range from an outer range on `event_ts` either — it does transitive
closure for equi-joins, not for inequalities with arithmetic across two columns.

Pruning needs a constant predicate on the partition column, with the column bare (no function
wrapping it), which means the view has to expose that column. In `EXPLAIN`, a pruned predicate
disappears from `filterPredicate` entirely; one that is still listed there was not pushed down.

## What is NOT supported (read-only v1)

- Creating, dropping, or renaming schemas and tables
- Writing to tables (`commitToExistingTable` throws NOT_SUPPORTED)
- Full view SQL translation (complex Spark SQL may fail to translate)
- Column/table statistics from UC (statistics come from Delta transaction log)
- Partition discovery via UC API (partitions are in the transaction log)
- Unity Catalog RBAC (auth is transport-level only, via token or OAuth2)
- Pruning of view predicates that correlate a partition column with a data column (see above)
- UC `variant` columns in views

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
