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

import com.google.inject.Binder;
import com.google.inject.Key;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.trino.metastore.HiveMetastoreFactory;
import io.trino.metastore.cache.CachingHiveMetastore;
import io.trino.plugin.deltalake.AllowDeltaLakeManagedTableRename;
import io.trino.plugin.deltalake.MaxTableParameterLength;
import io.trino.plugin.deltalake.metastore.DeltaLakeMetastore;
import io.trino.plugin.deltalake.metastore.DeltaLakeTableOperationsProvider;

import java.util.Optional;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.airlift.http.client.HttpClientBinder.httpClientBinder;

public class DeltaLakeUnityCatalogMetastoreModule
        extends AbstractConfigurationAwareModule
{
    @Override
    protected void setup(Binder binder)
    {
        configBinder(binder).bindConfig(DeltaLakeUnityCatalogMetastoreConfig.class);
        httpClientBinder(binder).bindHttpClient("unity-catalog", ForUnityCatalog.class);

        DeltaLakeUnityCatalogMetastoreConfig config = buildConfigObject(DeltaLakeUnityCatalogMetastoreConfig.class);
        switch (config.getSecurity()) {
            case TOKEN -> binder.bind(UnityCatalogAuthProvider.class).to(StaticTokenAuthProvider.class).in(Scopes.SINGLETON);
            case OAUTH2 -> {
                configBinder(binder).bindConfig(OAuth2AuthConfig.class);
                binder.bind(UnityCatalogAuthProvider.class).to(OAuth2ClientCredentialsAuthProvider.class).in(Scopes.SINGLETON);
            }
        }

        binder.bind(UnityCatalogBackedDeltaLakeMetastore.class).in(Scopes.SINGLETON);
        binder.bind(DeltaLakeMetastore.class).to(UnityCatalogBackedDeltaLakeMetastore.class).in(Scopes.SINGLETON);
        binder.bind(DeltaLakeTableOperationsProvider.class).to(DeltaLakeUnityCatalogTableOperationsProvider.class).in(Scopes.SINGLETON);

        binder.bind(UnityCatalogViewTranslator.class).to(PassThroughViewTranslator.class).in(Scopes.SINGLETON);
        binder.bind(UnityCatalogViewSupport.class).in(Scopes.SINGLETON);

        binder.bind(Key.get(boolean.class, AllowDeltaLakeManagedTableRename.class)).toInstance(false);
        binder.bind(Key.get(int.class, MaxTableParameterLength.class)).toInstance(4000);
    }

    @Provides
    @Singleton
    public Optional<HiveMetastoreFactory> provideOptionalHiveMetastoreFactory()
    {
        return Optional.empty();
    }

    @Provides
    @Singleton
    public Optional<DeltaLakeMetastore> provideOptionalDeltaLakeMetastore(DeltaLakeMetastore metastore)
    {
        return Optional.of(metastore);
    }

    @Provides
    @Singleton
    public Optional<CachingHiveMetastore> provideOptionalCachingHiveMetastore()
    {
        // Unity Catalog does not use a Hive metastore cache.
        return Optional.empty();
    }

    @Provides
    @Singleton
    public Optional<UnityCatalogViewSupport> provideOptionalViewSupport(UnityCatalogViewSupport viewSupport)
    {
        return Optional.of(viewSupport);
    }

    @Provides
    @Singleton
    public Optional<UnityCatalogBackedDeltaLakeMetastore> provideOptionalUnityCatalogMetastore(UnityCatalogBackedDeltaLakeMetastore metastore)
    {
        return Optional.of(metastore);
    }
}
