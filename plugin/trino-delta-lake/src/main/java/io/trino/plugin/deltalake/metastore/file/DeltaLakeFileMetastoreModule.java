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
package io.trino.plugin.deltalake.metastore.file;

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.metastore.HiveMetastoreFactory;
import io.trino.plugin.deltalake.AllowDeltaLakeManagedTableRename;
import io.trino.plugin.deltalake.MaxTableParameterLength;
import io.trino.plugin.deltalake.metastore.DeltaLakeMetastore;
import io.trino.plugin.deltalake.metastore.DeltaLakeTableOperationsProvider;
import io.trino.plugin.deltalake.metastore.unitycatalog.UnityCatalogBackedDeltaLakeMetastore;
import io.trino.plugin.deltalake.metastore.unitycatalog.UnityCatalogViewSupport;
import io.trino.plugin.hive.metastore.file.FileMetastoreModule;

import java.util.Optional;

public class DeltaLakeFileMetastoreModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        install(new FileMetastoreModule());
        binder.bind(DeltaLakeTableOperationsProvider.class).to(DeltaLakeFileMetastoreTableOperationsProvider.class).in(Scopes.SINGLETON);
        binder.bind(Key.get(boolean.class, AllowDeltaLakeManagedTableRename.class)).toInstance(true);
        binder.bind(Key.get(int.class, MaxTableParameterLength.class)).toInstance(Integer.MAX_VALUE);
    }

    @Provides
    @Singleton
    public Optional<HiveMetastoreFactory> provideOptionalHiveMetastoreFactory(HiveMetastoreFactory hiveMetastoreFactory)
    {
        return Optional.of(hiveMetastoreFactory);
    }

    @Provides
    @Singleton
    public Optional<DeltaLakeMetastore> provideOptionalDeltaLakeMetastore()
    {
        return Optional.empty();
    }

    @Provides
    @Singleton
    public Optional<UnityCatalogViewSupport> provideOptionalViewSupport()
    {
        return Optional.empty();
    }

    @Provides
    @Singleton
    public Optional<UnityCatalogBackedDeltaLakeMetastore> provideOptionalUnityCatalogMetastore()
    {
        return Optional.empty();
    }
}
