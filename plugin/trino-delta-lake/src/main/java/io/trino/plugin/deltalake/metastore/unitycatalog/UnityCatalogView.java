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
import io.trino.spi.connector.SchemaTableName;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

public record UnityCatalogView(
        SchemaTableName name,
        String viewDefinition,
        List<UnityCatalogViewColumn> columns,
        Optional<String> comment)
{
    public UnityCatalogView
    {
        requireNonNull(name, "name is null");
        requireNonNull(viewDefinition, "viewDefinition is null");
        columns = ImmutableList.copyOf(requireNonNull(columns, "columns is null"));
        requireNonNull(comment, "comment is null");
    }

    public record UnityCatalogViewColumn(String name, String typeText, Optional<String> comment, int position)
    {
        public UnityCatalogViewColumn
        {
            requireNonNull(name, "name is null");
            requireNonNull(typeText, "typeText is null");
            requireNonNull(comment, "comment is null");
        }
    }
}
