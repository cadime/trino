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

import io.airlift.configuration.Config;
import io.airlift.configuration.ConfigDescription;
import io.airlift.configuration.ConfigSecuritySensitive;
import io.airlift.units.Duration;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.net.URI;
import java.util.Optional;

import static java.util.concurrent.TimeUnit.MINUTES;

public class DeltaLakeUnityCatalogMetastoreConfig
{
    public enum SecurityType
    {
        TOKEN,
        OAUTH2,
    }

    private URI uri;
    private String catalogName;
    private SecurityType security = SecurityType.TOKEN;
    private String token;
    private boolean hideNonDeltaLakeTables;
    private Duration cacheTtl = new Duration(1, MINUTES);
    private long cacheMaximumSize = 10_000;

    @NotNull
    public URI getUri()
    {
        return uri;
    }

    @Config("delta.unity-catalog.uri")
    @ConfigDescription("Base URI of the Unity Catalog REST API (without trailing slash)")
    public DeltaLakeUnityCatalogMetastoreConfig setUri(URI uri)
    {
        this.uri = uri;
        return this;
    }

    @NotNull
    public String getCatalogName()
    {
        return catalogName;
    }

    @Config("delta.unity-catalog.catalog-name")
    @ConfigDescription("Name of the Unity Catalog catalog this connector exposes")
    public DeltaLakeUnityCatalogMetastoreConfig setCatalogName(String catalogName)
    {
        this.catalogName = catalogName;
        return this;
    }

    @NotNull
    public SecurityType getSecurity()
    {
        return security;
    }

    @Config("delta.unity-catalog.security")
    @ConfigDescription("Authentication mode: TOKEN (static personal access token) or OAUTH2 (client credentials)")
    public DeltaLakeUnityCatalogMetastoreConfig setSecurity(SecurityType security)
    {
        this.security = security;
        return this;
    }

    public Optional<String> getToken()
    {
        return Optional.ofNullable(token);
    }

    @Config("delta.unity-catalog.token")
    @ConfigDescription("Personal access token; required when security=token")
    @ConfigSecuritySensitive
    public DeltaLakeUnityCatalogMetastoreConfig setToken(String token)
    {
        this.token = token;
        return this;
    }

    public boolean isHideNonDeltaLakeTables()
    {
        return hideNonDeltaLakeTables;
    }

    @Config("delta.unity-catalog.hide-non-delta-lake-tables")
    @ConfigDescription("Hide tables whose data_source_format is not DELTA")
    public DeltaLakeUnityCatalogMetastoreConfig setHideNonDeltaLakeTables(boolean hideNonDeltaLakeTables)
    {
        this.hideNonDeltaLakeTables = hideNonDeltaLakeTables;
        return this;
    }

    @NotNull
    public Duration getCacheTtl()
    {
        return cacheTtl;
    }

    @Config("delta.unity-catalog.metastore-cache-ttl")
    @ConfigDescription("Duration to cache Unity Catalog metadata; set to 0s to disable (default: 1m)")
    public DeltaLakeUnityCatalogMetastoreConfig setCacheTtl(Duration cacheTtl)
    {
        this.cacheTtl = cacheTtl;
        return this;
    }

    @Min(1)
    public long getCacheMaximumSize()
    {
        return cacheMaximumSize;
    }

    @Config("delta.unity-catalog.metastore-cache-maximum-size")
    @ConfigDescription("Maximum number of entries in the Unity Catalog metadata cache (default: 10000)")
    public DeltaLakeUnityCatalogMetastoreConfig setCacheMaximumSize(long cacheMaximumSize)
    {
        this.cacheMaximumSize = cacheMaximumSize;
        return this;
    }
}
