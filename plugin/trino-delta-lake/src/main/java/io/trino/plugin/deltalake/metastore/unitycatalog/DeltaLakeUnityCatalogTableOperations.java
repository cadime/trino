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

import io.trino.plugin.deltalake.metastore.DeltaLakeTableOperations;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.SchemaTableName;

import java.util.Optional;

import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;

public class DeltaLakeUnityCatalogTableOperations
        implements DeltaLakeTableOperations
{
    @Override
    public void commitToExistingTable(SchemaTableName schemaTableName, long version, String schemaString, Optional<String> tableComment)
    {
        throw new TrinoException(NOT_SUPPORTED, "Writing to existing tables is not supported with Unity Catalog");
    }
}
