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
package io.trino.client;

import java.util.Set;

import static java.util.Arrays.stream;
import static java.util.stream.Collectors.toUnmodifiableSet;

public enum ClientCapabilities
{
    PATH,

    /**
     * Whether clients support datetime types with variable precision
     * timestamp(p) with time zone
     * timestamp(p) without time zone
     * time(p) with time zone
     * time(p) without time zone
     * interval X(p1) to Y(p2)
     * When this capability is not set, the server returns datetime types with precision = 3
     */
    PARAMETRIC_DATETIME,

    /**
     * Whether client supports the `NUMBER` type. When this capability is not set, the server returns `varchar` for `NUMBER` columns.
     */
    NUMBER,

    /**
     * Whether client supports the `VARIANT` type encoded as JSON values on the wire.
     * When this capability is not set, the server returns `json` for `VARIANT` columns.
     */
    VARIANT_JSON,

    /**
     * Whether client supports the `VARIANT` type encoded as a binary payload on the wire.
     * This capability is opt-in, so clients continue to receive the JSON representation by default.
     */
    VARIANT_BINARY(false),

    /**
     * Whether clients support the session authorization set/reset feature
     */
    SESSION_AUTHORIZATION;

    private final boolean enabledByDefault;

    ClientCapabilities()
    {
        this(true);
    }

    ClientCapabilities(boolean enabledByDefault)
    {
        this.enabledByDefault = enabledByDefault;
    }

    public boolean enabledByDefault()
    {
        return enabledByDefault;
    }

    public static Set<String> defaultClientCapabilities()
    {
        return stream(values())
                .filter(ClientCapabilities::enabledByDefault)
                .map(Enum::name)
                .collect(toUnmodifiableSet());
    }
}
