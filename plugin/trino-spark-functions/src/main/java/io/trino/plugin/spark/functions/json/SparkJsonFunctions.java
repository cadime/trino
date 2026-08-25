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
package io.trino.plugin.spark.functions.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

import java.io.IOException;

public final class SparkJsonFunctions
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SparkJsonFunctions() {}

    /**
     * Spark-compatible {@code get_json_object(jsonString, path)}.
     *
     * <p>Differences from Trino's {@code json_extract_scalar}:
     * <ul>
     *   <li>If the path resolves to an array or object, Spark returns the JSON serialisation
     *       of that array/object as a string. Trino returns null. We follow Spark.</li>
     *   <li>If the JSON is malformed or the path does not resolve, Spark returns null
     *       silently (no error). We follow Spark.</li>
     * </ul>
     *
     * <p>Supported path syntax: {@code $}, {@code .field}, {@code [index]}, combined freely
     * (e.g. {@code $.user.addresses[0].street}). Wildcards ({@code [*]}, {@code ..foo}) are not
     * supported — they are uncommon in stored Spark views and can be added later.
     */
    @ScalarFunction("get_json_object")
    @Description("Extracts a scalar value or sub-tree from a JSON string at the given JSON path (Spark-compatible)")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static Slice getJsonObject(
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice json,
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice path)
    {
        if (json == null || path == null) {
            return null;
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json.toStringUtf8());
        }
        catch (IOException e) {
            return null;
        }
        JsonNode value = walk(root, path.toStringUtf8());
        if (value == null || value.isMissingNode() || value.isNull()) {
            return null;
        }
        String text = value.isValueNode() ? value.asText() : value.toString();
        return Slices.utf8Slice(text);
    }

    private static JsonNode walk(JsonNode root, String path)
    {
        if (path.isEmpty() || path.charAt(0) != '$') {
            return null;
        }
        JsonNode current = root;
        int i = 1;
        int len = path.length();
        while (i < len && current != null && !current.isMissingNode()) {
            char c = path.charAt(i);
            if (c == '.') {
                int start = i + 1;
                int end = start;
                while (end < len && path.charAt(end) != '.' && path.charAt(end) != '[') {
                    end++;
                }
                if (end == start) {
                    return null;
                }
                String field = path.substring(start, end);
                current = current.get(field);
                i = end;
            }
            else if (c == '[') {
                int close = path.indexOf(']', i);
                if (close < 0) {
                    return null;
                }
                String idx = path.substring(i + 1, close).trim();
                int n;
                try {
                    n = Integer.parseInt(idx);
                }
                catch (NumberFormatException e) {
                    return null;
                }
                if (current == null || !current.isArray()) {
                    return null;
                }
                current = current.get(n);
                i = close + 1;
            }
            else {
                // Unsupported path syntax (e.g. wildcards, descendant operator).
                return null;
            }
        }
        return current;
    }
}
