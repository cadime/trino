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
package io.trino.plugin.deltalake.metastore;

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import jakarta.validation.constraints.NotNull;

public class DeltaLakeMetastoreTypeConfig
{
    public enum DeltaLakeMetastoreType
    {
        /**
         * Use a Hive-style metastore: thrift, file, or glue (selected by {@code hive.metastore}).
         */
        HIVE,
        /**
         * Use Databricks Unity Catalog as the metastore.
         */
        UNITY_CATALOG,
    }

    private DeltaLakeMetastoreType metastoreType = DeltaLakeMetastoreType.HIVE;

    @NotNull
    public DeltaLakeMetastoreType getMetastoreType()
    {
        return metastoreType;
    }

    @Config("delta.metastore.type")
    @ConfigDescription("Metastore backing the Delta Lake connector: hive (default; chooses thrift/file/glue via hive.metastore) or unity-catalog")
    public DeltaLakeMetastoreTypeConfig setMetastoreType(DeltaLakeMetastoreType metastoreType)
    {
        this.metastoreType = metastoreType;
        return this;
    }
}
