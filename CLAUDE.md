# Trino — Claude guidance

**Before writing Java code, you must first read [`.github/DEVELOPMENT.md`](.github/DEVELOPMENT.md)
in full** — it's the authoritative source for code-style rules (mocks, `var`, switch statements,
method naming, `format()`, `TrinoException` error codes, AssertJ, Guava immutables, and more).
This file intentionally does not duplicate those rules; skipping the read means missing them.
Violations are also caught mechanically by modernizer
([`.mvn/modernizer/violations.xml`](.mvn/modernizer/violations.xml)), checkstyle (from Airbase),
and IntelliJ inspections via `mcp__idea__get_file_problems`.

For other topics not covered here (Web UI build, release process, Vector API, IDE setup rationale),
see the same `DEVELOPMENT.md`.

## JetBrains MCP server

Trino is developed in IntelliJ. If you run Claude Code with the JetBrains MCP server enabled,
the assistant can drive the IDE directly. Install: https://github.com/JetBrains/mcp-jetbrains.

When available, Claude should prefer these over shell equivalents:
- `mcp__idea__get_file_problems` before committing — surfaces IntelliJ inspection results
  (error-prone, unused imports, nullability) without a full Maven build.
- `mcp__idea__search_symbol` / `mcp__idea__get_symbol_info` for symbol navigation in a codebase
  with many overloaded names like `Metadata`, `Session`, `Block`.
- `mcp__idea__rename_refactoring` for API renames — safer than text substitution.

## Java formatting

Run `mvnd airstyle:format` after Java edits — the `airstyle-maven-plugin` (`io.airlift:airstyle-maven-plugin`)
applies the canonical Airstyle scheme, which is what CI checks. Scope a single file with
`mvnd -pl <module> airstyle:format -Dincludes=**/FileName.java`, and use `airstyle:check` to verify
without rewriting. Rules not covered by the formatter:

- No wildcard imports (e.g. `import io.trino.spi.*`) — checkstyle catches these on build; easier
  to avoid writing them.
- Braces required around single-statement `if` / `for` / `while` bodies — the formatter does not
  add missing braces.
- No `@author` in JavaDoc — commit history is the record.

Topic-specific conventions live under [`.claude/rules/`](.claude/rules/) and auto-load when Claude
reads matching files (e.g. `*Config.java` triggers the config-properties rule).

---

# Trino Unity Catalog fork — CLAUDE.md

This is a fork of Trino R483 on branch `unity_catalog_480`. Its sole purpose is adding a **Databricks Unity Catalog (UC) metastore backend** to the Delta Lake connector. All UC-specific code lives in the `unitycatalog` subpackage of the Delta Lake metastore. The rest of the codebase is vanilla Trino R483.

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
      UnityCatalogViewSupport.java                   # UC views → ConnectorViewDefinition; DEFINER + VIEW_OWNER
      UnityCatalogViewTranslator.java                # Spark SQL → Trino SQL interface
      PassThroughViewTranslator.java                 # tokenizer-based translator
      UnityCatalogTypeMapping.java                   # UC type text → Trino type (view columns only)
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

Dev catalog configs: `testing/trino-server-dev/etc/catalog/`. Example catalogs for the Docker
image, with secrets read from the environment: `docker/lakehouse/catalog/`.

## Architectural constraints — do not violate

1. **No changes to `MetastoreTypeConfig` or anything in trino-hive.** The UC type lives in `DeltaLakeMetastoreTypeConfig` (delta-lake-internal enum).
2. **All mutations throw `NOT_SUPPORTED`.** This is a read-only connector; do not add write paths.
3. **UC types are parsed for view columns only.** Table columns come from the Delta transaction
   log; UC column metadata is redundant there. `UnityCatalogTypeMapping` exists solely because a
   view has to declare its own column types — see constraint 7.
4. **Auth via Airlift `HttpClient` only.** No Databricks SDK, no UC OSS client jar.
5. **Views are v1/pass-through.** `getView`/`listViews` work but full Coral-based SQL translation is out of scope. Don't add a Coral dependency.
6. **All work on branch `unity_catalog_480`.** No feature flags, no new branches for UC work.
7. **`UnityCatalogTypeMapping` must agree with `DeltaLakeSchemaSupport`.** For any type name that
   exists in both, the Trino type must be identical. A wider view column type makes Trino insert a
   `CAST` on the base column, and a `CAST` around a column blocks domain extraction in
   `DomainTranslator` — the symptom is a silent loss of predicate pushdown, not a type error.
8. **Views run as DEFINER with owner `system_user`.** Do not switch back to invoker: access is
   granted per view without granting it on the base tables. `ConnectorViewDefinition` rejects an
   owner together with `runAsInvoker = true`, so both settings move together.

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

To run a local server with the UC catalogs configured, use the `trino-server-dev` module with a
catalog properties file under `testing/trino-server-dev/etc/catalog/`.

### Slim Docker image

`core/trino-server-lakehouse` is a provisio package carrying only delta-lake (plus `trino-hdfs`),
postgresql and spark-functions on top of `trino-server-core` — 10 plugins instead of the 48 in the
full `trino-server`. See `docker/lakehouse/README.md`.

**Provisio resolves the artifacts it packages from `~/.m2` at package time, not through
`<dependencies>`.** `-am` therefore does not rebuild them, and the package will silently assemble
whatever is already installed. After changing a plugin, the engine, or the Web UI, `install` that
module before packaging, then verify the jar actually landed:

```bash
mvn install -pl plugin/trino-delta-lake -am -DskipTests
mvn install -pl core/trino-server-lakehouse -DskipTests
./core/docker/build.sh -p trino-server-lakehouse -a arm64 -t trino-uc
```

For a Web UI change the chain is
`core/trino-web-ui,core/trino-server-main,core/trino-server-core,core/trino-server-lakehouse`.
