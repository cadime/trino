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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.metastore.TableInfo;
import io.trino.spi.connector.ConnectorViewDefinition;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Glue between {@link UnityCatalogBackedDeltaLakeMetastore} and Trino's {@link ConnectorViewDefinition}.
 * Centralises view listing, view-definition translation, and column type mapping for the UC metastore.
 */
public class UnityCatalogViewSupport
{
    private static final Logger LOG = Logger.get(UnityCatalogViewSupport.class);

    private final UnityCatalogBackedDeltaLakeMetastore metastore;
    private final UnityCatalogViewTranslator translator;
    private final TypeManager typeManager;

    @Inject
    public UnityCatalogViewSupport(
            UnityCatalogBackedDeltaLakeMetastore metastore,
            UnityCatalogViewTranslator translator,
            TypeManager typeManager)
    {
        this.metastore = requireNonNull(metastore, "metastore is null");
        this.translator = requireNonNull(translator, "translator is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
    }

    public List<SchemaTableName> listViews(Optional<String> schemaName)
    {
        ImmutableList.Builder<SchemaTableName> builder = ImmutableList.builder();
        for (String schema : schemasToScan(schemaName)) {
            metastore.getAllTables(schema).stream()
                    .filter(info -> info.extendedRelationType() == TableInfo.ExtendedRelationType.OTHER_VIEW)
                    .map(TableInfo::tableName)
                    .forEach(builder::add);
        }
        return builder.build();
    }

    public Map<SchemaTableName, ConnectorViewDefinition> getViews(Optional<String> schemaName)
    {
        ImmutableMap.Builder<SchemaTableName, ConnectorViewDefinition> result = ImmutableMap.builder();
        for (SchemaTableName name : listViews(schemaName)) {
            getView(name).ifPresent(definition -> result.put(name, definition));
        }
        return result.buildOrThrow();
    }

    public Optional<ConnectorViewDefinition> getView(SchemaTableName viewName)
    {
        Optional<UnityCatalogView> view = metastore.getView(viewName.getSchemaName(), viewName.getTableName());
        if (view.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toConnectorViewDefinition(view.get()));
    }

    private ConnectorViewDefinition toConnectorViewDefinition(UnityCatalogView view)
    {
        String translatedSql;
        try {
            translatedSql = translator.translate(view);
        }
        catch (RuntimeException e) {
            LOG.warn(e, "View translator failed for %s, using raw view definition", view.name());
            translatedSql = view.viewDefinition();
        }

        ImmutableList.Builder<ConnectorViewDefinition.ViewColumn> columns = ImmutableList.builder();
        for (UnityCatalogView.UnityCatalogViewColumn column : view.columns()) {
            Type type;
            try {
                type = UnityCatalogTypeMapping.parse(column.typeText(), typeManager);
            }
            catch (RuntimeException e) {
                LOG.warn(e, "Cannot map Unity Catalog type '%s' for view %s.%s; skipping view",
                        column.typeText(), view.name().getSchemaName(), view.name().getTableName());
                throw e;
            }
            columns.add(new ConnectorViewDefinition.ViewColumn(column.name(), type.getTypeId(), column.comment()));
        }

        return new ConnectorViewDefinition(
                translatedSql,
                Optional.empty(),
                Optional.empty(),
                columns.build(),
                view.comment(),
                Optional.empty(),
                true,
                ImmutableList.of());
    }

    private List<String> schemasToScan(Optional<String> schemaName)
    {
        return schemaName
                .map(List::of)
                .orElseGet(() -> ImmutableList.copyOf(metastore.getAllDatabases()));
    }
}
