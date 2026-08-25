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

import com.google.inject.Inject;

import static java.util.Objects.requireNonNull;

public class StaticTokenAuthProvider
        implements UnityCatalogAuthProvider
{
    private final String headerValue;

    @Inject
    public StaticTokenAuthProvider(DeltaLakeUnityCatalogMetastoreConfig config)
    {
        String token = requireNonNull(config.getToken().orElse(null),
                "delta.unity-catalog.token must be set when delta.unity-catalog.security=token");
        this.headerValue = "Bearer " + token;
    }

    @Override
    public String authorizationHeaderValue()
    {
        return headerValue;
    }
}
