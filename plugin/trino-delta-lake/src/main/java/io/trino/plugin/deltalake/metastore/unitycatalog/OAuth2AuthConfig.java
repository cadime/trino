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
import io.airlift.units.MinDuration;
import jakarta.validation.constraints.NotNull;

import java.net.URI;

import static io.airlift.units.Duration.succinctDuration;
import static java.util.concurrent.TimeUnit.SECONDS;

public class OAuth2AuthConfig
{
    private URI tokenUri;
    private String clientId;
    private String clientSecret;
    private String scope = "all-apis";
    private Duration refreshSkew = succinctDuration(60, SECONDS);

    @NotNull
    public URI getTokenUri()
    {
        return tokenUri;
    }

    @Config("delta.unity-catalog.oauth2.token-uri")
    @ConfigDescription("OAuth2 token endpoint (e.g. https://<workspace>.cloud.databricks.com/oidc/v1/token)")
    public OAuth2AuthConfig setTokenUri(URI tokenUri)
    {
        this.tokenUri = tokenUri;
        return this;
    }

    @NotNull
    public String getClientId()
    {
        return clientId;
    }

    @Config("delta.unity-catalog.oauth2.client-id")
    @ConfigDescription("OAuth2 client id (Databricks service principal application id)")
    public OAuth2AuthConfig setClientId(String clientId)
    {
        this.clientId = clientId;
        return this;
    }

    @NotNull
    public String getClientSecret()
    {
        return clientSecret;
    }

    @Config("delta.unity-catalog.oauth2.client-secret")
    @ConfigDescription("OAuth2 client secret")
    @ConfigSecuritySensitive
    public OAuth2AuthConfig setClientSecret(String clientSecret)
    {
        this.clientSecret = clientSecret;
        return this;
    }

    @NotNull
    public String getScope()
    {
        return scope;
    }

    @Config("delta.unity-catalog.oauth2.scope")
    @ConfigDescription("OAuth2 scope to request (default: all-apis)")
    public OAuth2AuthConfig setScope(String scope)
    {
        this.scope = scope;
        return this;
    }

    @NotNull
    @MinDuration("0s")
    public Duration getRefreshSkew()
    {
        return refreshSkew;
    }

    @Config("delta.unity-catalog.oauth2.refresh-skew")
    @ConfigDescription("Refresh tokens this long before they actually expire")
    public OAuth2AuthConfig setRefreshSkew(Duration refreshSkew)
    {
        this.refreshSkew = refreshSkew;
        return this;
    }
}
