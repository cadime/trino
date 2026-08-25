/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.deltalake.metastore.unitycatalog;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.base.Throwables;
import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.google.inject.Inject;
import io.airlift.http.client.HttpClient;
import io.airlift.http.client.Request;
import io.airlift.http.client.StringResponseHandler;
import io.airlift.json.JsonCodec;
import io.trino.cache.EvictableCacheBuilder;
import io.trino.metastore.Database;
import io.trino.metastore.PrincipalPrivileges;
import io.trino.metastore.Table;
import io.trino.metastore.TableInfo;
import io.trino.plugin.deltalake.metastore.DeltaLakeMetastore;
import io.trino.plugin.deltalake.metastore.DeltaMetastoreTable;
import io.trino.plugin.deltalake.metastore.NotADeltaLakeTableException;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.SchemaTableName;
import jakarta.annotation.Nullable;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.net.HttpHeaders.AUTHORIZATION;
import static io.airlift.http.client.Request.Builder.prepareGet;
import static io.airlift.http.client.StringResponseHandler.createStringResponseHandler;
import static io.airlift.json.JsonCodec.jsonCodec;
import static io.trino.cache.CacheUtils.uncheckedCacheGet;
import static io.trino.plugin.deltalake.DeltaLakeErrorCode.DELTA_LAKE_BAD_DATA;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.StandardErrorCode.PERMISSION_DENIED;
import static io.trino.spi.StandardErrorCode.TOO_MANY_REQUESTS_FAILED;
import static java.util.Objects.requireNonNull;

public class UnityCatalogBackedDeltaLakeMetastore
        implements DeltaLakeMetastore
{
    static final JsonCodec<SchemaListResponse> SCHEMA_LIST_CODEC = jsonCodec(SchemaListResponse.class);
    static final JsonCodec<TableListResponse> TABLE_LIST_CODEC = jsonCodec(TableListResponse.class);
    static final JsonCodec<TableResponse> TABLE_CODEC = jsonCodec(TableResponse.class);

    /**
     * Property keys (under the UC table response's {@code properties} map) that hold the
     * physical storage path for DLT-managed tables, where the top-level {@code storage_location}
     * is empty. Tried in order; first non-blank value wins.
     */
    private static final List<String> DLT_STORAGE_LOCATION_KEYS = ImmutableList.of(
            "spark.internal.streaming_table.backing_table_path",
            "spark.internal.pipelines.backing_table_path");

    /** Table types that DLT manages but Trino can still read as plain Delta. */
    private static final Set<String> DLT_TABLE_TYPES = ImmutableSet.of("STREAMING_TABLE", "MATERIALIZED_VIEW");

    /**
     * For STREAMING_TABLE entries, Unity Catalog stores a SELECT statement that hides DLT
     * internal columns and filters out logically-deleted rows. We re-expose this as a Trino view
     * over the physical backing table so SELECT * shows the right schema and the right rows.
     */
    private static final String STREAMING_TABLE_RECONCILIATION_QUERY_KEY =
            "spark.internal.streaming_table.reconciliation_query";

    private static final String SCHEMAS_CACHE_KEY = "";
    private static final int BODY_TRUNCATION_LENGTH = 500;

    private final URI baseUri;
    private final String catalogName;
    private final UnityCatalogAuthProvider authProvider;
    private final HttpClient httpClient;
    private final boolean hideNonDeltaLakeTables;

    @Nullable private final Cache<String, List<String>> schemasCache;
    @Nullable private final Cache<String, List<TableInfo>> tablesCache;
    @Nullable private final Cache<SchemaTableName, Optional<TableResponse>> tableCache;

    @Inject
    public UnityCatalogBackedDeltaLakeMetastore(
            DeltaLakeUnityCatalogMetastoreConfig config,
            UnityCatalogAuthProvider authProvider,
            @ForUnityCatalog HttpClient httpClient)
    {
        requireNonNull(config, "config is null");
        this.baseUri = config.getUri();
        this.catalogName = config.getCatalogName();
        this.authProvider = requireNonNull(authProvider, "authProvider is null");
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.hideNonDeltaLakeTables = config.isHideNonDeltaLakeTables();

        long ttlMillis = config.getCacheTtl().toMillis();
        if (ttlMillis > 0) {
            Duration ttl = Duration.ofMillis(ttlMillis);
            this.schemasCache = EvictableCacheBuilder.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(1)
                    .build();
            this.tablesCache = EvictableCacheBuilder.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(config.getCacheMaximumSize())
                    .build();
            this.tableCache = EvictableCacheBuilder.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(config.getCacheMaximumSize())
                    .build();
        }
        else {
            this.schemasCache = null;
            this.tablesCache = null;
            this.tableCache = null;
        }
    }

    @Override
    public List<String> getAllDatabases()
    {
        if (schemasCache == null) {
            return fetchAllDatabases();
        }
        try {
            return uncheckedCacheGet(schemasCache, SCHEMAS_CACHE_KEY, this::fetchAllDatabases);
        }
        catch (UncheckedExecutionException e) {
            Throwables.throwIfInstanceOf(e.getCause(), TrinoException.class);
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Error loading schemas from Unity Catalog", e.getCause());
        }
    }

    private List<String> fetchAllDatabases()
    {
        URI uri = URI.create(baseUri + "/schemas?catalog_name=" + encode(catalogName));
        SchemaListResponse parsed = call(uri, SCHEMA_LIST_CODEC, "list schemas in catalog '" + catalogName + "'");
        return parsed.schemas().stream()
                .map(NamedEntity::name)
                .collect(toImmutableList());
    }

    @Override
    public Optional<Database> getDatabase(String databaseName)
    {
        return Optional.of(Database.builder()
                .setDatabaseName(databaseName)
                .setOwnerName(Optional.empty())
                .setOwnerType(Optional.empty())
                .setComment(Optional.empty())
                .setParameters(ImmutableMap.of())
                .build());
    }

    @Override
    public List<TableInfo> getAllTables(String databaseName)
    {
        if (tablesCache == null) {
            return fetchAllTables(databaseName);
        }
        try {
            return uncheckedCacheGet(tablesCache, databaseName, () -> fetchAllTables(databaseName));
        }
        catch (UncheckedExecutionException e) {
            Throwables.throwIfInstanceOf(e.getCause(), TrinoException.class);
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Error loading tables from Unity Catalog for schema: " + databaseName, e.getCause());
        }
    }

    private List<TableInfo> fetchAllTables(String databaseName)
    {
        URI uri = URI.create(baseUri + "/tables?catalog_name=" + encode(catalogName) + "&schema_name=" + encode(databaseName));
        TableListResponse parsed = call(uri, TABLE_LIST_CODEC, "list tables in '%s.%s'".formatted(catalogName, databaseName));
        return parsed.tables().stream()
                .filter(t -> !hideNonDeltaLakeTables || "DELTA".equals(t.dataSourceFormat()) || isViewLikeType(t.tableType()))
                .map(t -> new TableInfo(
                        new SchemaTableName(databaseName, t.name()),
                        isViewLikeType(t.tableType())
                                ? TableInfo.ExtendedRelationType.OTHER_VIEW
                                : TableInfo.ExtendedRelationType.TABLE))
                .collect(toImmutableList());
    }

    private static boolean isViewLikeType(String tableType)
    {
        // STREAMING_TABLE is physically a Delta table, but exposing it as a view lets us inject
        // the DLT reconciliation_query (column projection + soft-delete filtering).
        return "VIEW".equals(tableType) || "STREAMING_TABLE".equals(tableType);
    }

    @Override
    public Optional<Table> getRawMetastoreTable(String databaseName, String tableName)
    {
        // Unity Catalog does not expose Hive-format Table metadata; DeltaLakeMetadata falls back to getTable().
        return Optional.empty();
    }

    @Override
    public Optional<DeltaMetastoreTable> getTable(String databaseName, String tableName)
    {
        Optional<TableResponse> response = fetchTable(databaseName, tableName);
        if (response.isEmpty()) {
            return Optional.empty();
        }
        TableResponse table = response.get();
        if ("VIEW".equals(table.tableType())) {
            // Plain views are handled separately via getView(); pretend the table is missing so Trino routes view lookups properly.
            return Optional.empty();
        }
        if ("STREAMING_TABLE".equals(table.tableType()) && hasReconciliationQuery(table)) {
            // Streaming tables with a reconciliation_query are exposed as views by getView() so the
            // DLT-internal columns and soft-deleted rows are hidden. Tell Trino it's not a table here.
            return Optional.empty();
        }
        // Reject only when Unity Catalog explicitly tells us the data is in a non-Delta format
        // (CSV, JSON, …). Streaming tables and materialized views often come back with a NULL or
        // empty `data_source_format`; in that case we defer to the Delta transaction log reader.
        String format = table.dataSourceFormat();
        if (format != null && !format.isBlank() && !"DELTA".equalsIgnoreCase(format)) {
            throw new NotADeltaLakeTableException(databaseName, tableName);
        }
        String location = resolveStorageLocation(table);
        if (location == null) {
            throw new TrinoException(DELTA_LAKE_BAD_DATA,
                    "Unity Catalog table %s.%s has no storage_location".formatted(databaseName, tableName));
        }
        String tableType = table.tableType();
        boolean managed = "MANAGED".equalsIgnoreCase(tableType)
                || (tableType != null && DLT_TABLE_TYPES.contains(tableType.toUpperCase(java.util.Locale.ROOT)));
        return Optional.of(new DeltaMetastoreTable(
                new SchemaTableName(databaseName, tableName),
                managed,
                location,
                false));
    }

    private static String resolveStorageLocation(TableResponse table)
    {
        if (table.storageLocation() != null && !table.storageLocation().isBlank()) {
            return table.storageLocation();
        }
        Map<String, String> properties = table.properties();
        if (properties != null) {
            for (String key : DLT_STORAGE_LOCATION_KEYS) {
                String value = properties.get(key);
                if (value != null && !value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }

    public Optional<UnityCatalogView> getView(String databaseName, String viewName)
    {
        Optional<TableResponse> response = fetchTable(databaseName, viewName);
        if (response.isEmpty()) {
            return Optional.empty();
        }
        TableResponse table = response.get();
        if ("STREAMING_TABLE".equals(table.tableType()) && hasReconciliationQuery(table)) {
            return Optional.of(buildStreamingTableView(table, databaseName, viewName));
        }
        if (!"VIEW".equals(table.tableType())) {
            return Optional.empty();
        }
        if (table.viewDefinition() == null || table.viewDefinition().isBlank()) {
            // Materialized views also have table_type=VIEW in some UC variants; without a definition we cannot translate.
            return Optional.empty();
        }
        List<UnityCatalogView.UnityCatalogViewColumn> columns = table.columns().stream()
                .sorted(java.util.Comparator.comparingInt(c -> c.position() == null ? 0 : c.position()))
                .map(c -> new UnityCatalogView.UnityCatalogViewColumn(
                        c.name(),
                        c.typeText(),
                        Optional.ofNullable(c.comment()).filter(s -> !s.isBlank()),
                        c.position() == null ? 0 : c.position()))
                .collect(toImmutableList());
        return Optional.of(new UnityCatalogView(
                new SchemaTableName(databaseName, viewName),
                table.viewDefinition(),
                columns,
                Optional.ofNullable(table.comment()).filter(s -> !s.isBlank())));
    }

    /** Returns raw Unity Catalog table/view metadata, or empty for 404. Results are cached when caching is enabled. */
    public Optional<TableResponse> fetchTable(String databaseName, String tableName)
    {
        SchemaTableName key = new SchemaTableName(databaseName, tableName);
        if (tableCache == null) {
            return fetchTableFromApi(databaseName, tableName);
        }
        try {
            return uncheckedCacheGet(tableCache, key, () -> fetchTableFromApi(databaseName, tableName));
        }
        catch (UncheckedExecutionException e) {
            Throwables.throwIfInstanceOf(e.getCause(), TrinoException.class);
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Error loading table metadata from Unity Catalog for: " + key, e.getCause());
        }
    }

    private Optional<TableResponse> fetchTableFromApi(String databaseName, String tableName)
    {
        URI uri = URI.create(baseUri + "/tables/" + encode(catalogName) + "." + encode(databaseName) + "." + encode(tableName));
        return callAllowing404(uri, TABLE_CODEC, "fetch table '%s.%s.%s'".formatted(catalogName, databaseName, tableName));
    }

    public void invalidateAll()
    {
        if (schemasCache != null) {
            schemasCache.invalidateAll();
        }
        if (tablesCache != null) {
            tablesCache.invalidateAll();
        }
        if (tableCache != null) {
            tableCache.invalidateAll();
        }
    }

    public void invalidateTable(String databaseName, String tableName)
    {
        if (tablesCache != null) {
            tablesCache.invalidate(databaseName);
        }
        if (tableCache != null) {
            tableCache.invalidate(new SchemaTableName(databaseName, tableName));
        }
    }

    private static boolean hasReconciliationQuery(TableResponse table)
    {
        Map<String, String> properties = table.properties();
        if (properties == null) {
            return false;
        }
        String query = properties.get(STREAMING_TABLE_RECONCILIATION_QUERY_KEY);
        return query != null && !query.isBlank();
    }

    private UnityCatalogView buildStreamingTableView(TableResponse table, String databaseName, String tableName)
    {
        String reconciliationQuery = table.properties().get(STREAMING_TABLE_RECONCILIATION_QUERY_KEY);
        List<UnityCatalogView.UnityCatalogViewColumn> columns = table.columns().stream()
                .sorted(java.util.Comparator.comparingInt(c -> c.position() == null ? 0 : c.position()))
                .map(c -> new UnityCatalogView.UnityCatalogViewColumn(
                        c.name(),
                        c.typeText(),
                        Optional.ofNullable(c.comment()).filter(s -> !s.isBlank()),
                        c.position() == null ? 0 : c.position()))
                .collect(toImmutableList());
        return new UnityCatalogView(
                new SchemaTableName(databaseName, tableName),
                reconciliationQuery,
                columns,
                Optional.ofNullable(table.comment()).filter(s -> !s.isBlank()));
    }

    @Override
    public void createDatabase(Database database)
    {
        throw new TrinoException(NOT_SUPPORTED, "Creating schemas is not supported with Unity Catalog");
    }

    @Override
    public void dropDatabase(String databaseName, boolean deleteData)
    {
        throw new TrinoException(NOT_SUPPORTED, "Dropping schemas is not supported with Unity Catalog");
    }

    @Override
    public void createTable(Table table, PrincipalPrivileges principalPrivileges)
    {
        throw new TrinoException(NOT_SUPPORTED, "Creating tables is not supported with Unity Catalog");
    }

    @Override
    public void replaceTable(Table table, PrincipalPrivileges principalPrivileges)
    {
        throw new TrinoException(NOT_SUPPORTED, "Replacing tables is not supported with Unity Catalog");
    }

    @Override
    public void dropTable(SchemaTableName schemaTableName, String tableLocation, boolean deleteData)
    {
        throw new TrinoException(NOT_SUPPORTED, "Dropping tables is not supported with Unity Catalog");
    }

    @Override
    public void renameTable(SchemaTableName from, SchemaTableName to)
    {
        throw new TrinoException(NOT_SUPPORTED, "Renaming tables is not supported with Unity Catalog");
    }

    private <T> T call(URI uri, JsonCodec<T> codec, String description)
    {
        return callAllowing404(uri, codec, description)
                .orElseThrow(() -> new TrinoException(DELTA_LAKE_BAD_DATA,
                        "Unity Catalog returned 404 for %s".formatted(description)));
    }

    private <T> Optional<T> callAllowing404(URI uri, JsonCodec<T> codec, String description)
    {
        Request request = prepareGet()
                .setUri(uri)
                .setHeader(AUTHORIZATION, authProvider.authorizationHeaderValue())
                .build();
        StringResponseHandler.StringResponse response = httpClient.execute(request, createStringResponseHandler());
        int statusCode = response.getStatusCode();
        if (statusCode == 404) {
            return Optional.empty();
        }
        if (statusCode == 401 || statusCode == 403) {
            throw new TrinoException(PERMISSION_DENIED,
                    "Unity Catalog access denied for %s (HTTP %d): %s".formatted(description, statusCode, truncateBody(response.getBody())));
        }
        if (statusCode == 429) {
            throw new TrinoException(TOO_MANY_REQUESTS_FAILED,
                    "Unity Catalog rate limit exceeded for %s: %s".formatted(description, truncateBody(response.getBody())));
        }
        if (statusCode >= 500) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR,
                    "Unity Catalog server error for %s (HTTP %d): %s".formatted(description, statusCode, truncateBody(response.getBody())));
        }
        if (statusCode < 200 || statusCode >= 300) {
            throw new TrinoException(DELTA_LAKE_BAD_DATA,
                    "Unity Catalog unexpected response for %s (HTTP %d): %s".formatted(description, statusCode, truncateBody(response.getBody())));
        }
        try {
            return Optional.of(codec.fromJson(response.getBody()));
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(DELTA_LAKE_BAD_DATA,
                    "Unity Catalog returned unparseable JSON for %s: %s".formatted(description, truncateBody(response.getBody())));
        }
    }

    private static String truncateBody(String body)
    {
        if (body == null || body.isBlank()) {
            return "(empty response body)";
        }
        return body.length() > BODY_TRUNCATION_LENGTH ? body.substring(0, BODY_TRUNCATION_LENGTH) + "..." : body;
    }

    private static String encode(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record SchemaListResponse(@JsonProperty("schemas") List<NamedEntity> schemas)
    {
        @JsonCreator
        public SchemaListResponse
        {
            schemas = schemas == null ? ImmutableList.of() : ImmutableList.copyOf(schemas);
        }
    }

    public record NamedEntity(@JsonProperty("name") String name)
    {
        @JsonCreator
        public NamedEntity {}
    }

    public record TableListResponse(@JsonProperty("tables") List<TableSummary> tables)
    {
        @JsonCreator
        public TableListResponse
        {
            tables = tables == null ? ImmutableList.of() : ImmutableList.copyOf(tables);
        }
    }

    public record TableSummary(
            @JsonProperty("name") String name,
            @JsonProperty("data_source_format") String dataSourceFormat,
            @JsonProperty("table_type") String tableType)
    {
        @JsonCreator
        public TableSummary {}
    }

    public record TableResponse(
            @JsonProperty("name") String name,
            @JsonProperty("data_source_format") String dataSourceFormat,
            @JsonProperty("storage_location") String storageLocation,
            @JsonProperty("table_type") String tableType,
            @JsonProperty("view_definition") String viewDefinition,
            @JsonProperty("comment") String comment,
            @JsonProperty("columns") List<UnityCatalogColumnInfo> columns,
            @JsonProperty("properties") Map<String, String> properties)
    {
        @JsonCreator
        public TableResponse
        {
            columns = columns == null ? ImmutableList.of() : ImmutableList.copyOf(columns);
            properties = properties == null ? ImmutableMap.of() : ImmutableMap.copyOf(properties);
        }
    }

    public record UnityCatalogColumnInfo(
            @JsonProperty("name") String name,
            @JsonProperty("type_text") String typeText,
            @JsonProperty("comment") String comment,
            @JsonProperty("position") Integer position)
    {
        @JsonCreator
        public UnityCatalogColumnInfo {}
    }
}
