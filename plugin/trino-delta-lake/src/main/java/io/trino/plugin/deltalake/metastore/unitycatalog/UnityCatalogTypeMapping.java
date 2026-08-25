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
import io.trino.spi.TrinoException;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MICROS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MICROS;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;

/**
 * Parses Unity Catalog type strings (Hive/Spark dialect) into Trino types.
 *
 * <p>Format examples:
 * <pre>
 *   string                       -&gt; varchar
 *   int                          -&gt; integer
 *   decimal(28,6)                -&gt; decimal(28,6)
 *   array&lt;string&gt;                -&gt; array(varchar)
 *   map&lt;string,bigint&gt;           -&gt; map(varchar, bigint)
 *   struct&lt;a:int,b:array&lt;string&gt;&gt; -&gt; row(a integer, b array(varchar))
 * </pre>
 */
public final class UnityCatalogTypeMapping
{
    private UnityCatalogTypeMapping() {}

    public static Type parse(String typeText, TypeManager typeManager)
    {
        if (typeText == null || typeText.isBlank()) {
            throw new TrinoException(NOT_SUPPORTED, "Unity Catalog column has no type");
        }
        return parseInternal(typeText.trim().toLowerCase(java.util.Locale.ROOT), typeManager);
    }

    private static Type parseInternal(String typeText, TypeManager typeManager)
    {
        // Complex types — match prefix and parse the inside with bracket-aware splitting.
        if (typeText.startsWith("array<") && typeText.endsWith(">")) {
            String inner = typeText.substring("array<".length(), typeText.length() - 1).trim();
            return new ArrayType(parseInternal(inner, typeManager));
        }
        if (typeText.startsWith("map<") && typeText.endsWith(">")) {
            String inner = typeText.substring("map<".length(), typeText.length() - 1).trim();
            List<String> parts = splitTopLevel(inner, ',');
            if (parts.size() != 2) {
                throw new TrinoException(NOT_SUPPORTED, "Invalid map<…> in Unity Catalog type: " + typeText);
            }
            Type key = parseInternal(parts.get(0).trim(), typeManager);
            Type value = parseInternal(parts.get(1).trim(), typeManager);
            return new MapType(key, value, typeManager.getTypeOperators());
        }
        if (typeText.startsWith("struct<") && typeText.endsWith(">")) {
            String inner = typeText.substring("struct<".length(), typeText.length() - 1).trim();
            List<RowType.Field> fields = new ArrayList<>();
            for (String fieldSpec : splitTopLevel(inner, ',')) {
                int colon = findTopLevel(fieldSpec, ':');
                if (colon < 0) {
                    throw new TrinoException(NOT_SUPPORTED, "Invalid struct<…> field in Unity Catalog type: " + fieldSpec);
                }
                String fieldName = fieldSpec.substring(0, colon).trim();
                String fieldType = fieldSpec.substring(colon + 1).trim();
                fields.add(new RowType.Field(Optional.of(fieldName), parseInternal(fieldType, typeManager)));
            }
            return RowType.from(fields);
        }

        // decimal(p, s) / decimal(p)
        if (typeText.startsWith("decimal")) {
            int open = typeText.indexOf('(');
            if (open < 0) {
                return createDecimalType(38, 18);
            }
            int close = typeText.lastIndexOf(')');
            if (close < open) {
                throw new TrinoException(NOT_SUPPORTED, "Invalid decimal type: " + typeText);
            }
            List<String> args = splitTopLevel(typeText.substring(open + 1, close), ',');
            int precision = Integer.parseInt(args.get(0).trim());
            int scale = args.size() > 1 ? Integer.parseInt(args.get(1).trim()) : 0;
            return createDecimalType(precision, scale);
        }

        // Primitives
        return switch (typeText) {
            case "boolean" -> BOOLEAN;
            case "byte", "tinyint" -> TINYINT;
            case "short", "smallint" -> SMALLINT;
            case "int", "integer" -> INTEGER;
            case "long", "bigint" -> BIGINT;
            case "float", "real" -> REAL;
            case "double" -> DOUBLE;
            case "string", "varchar" -> createUnboundedVarcharType();
            case "binary", "varbinary" -> VARBINARY;
            case "date" -> DATE;
            // Spark TIMESTAMP is wall-clock with session zone; Databricks defaults to TIMESTAMP_LTZ.
            case "timestamp", "timestamp_ltz" -> TIMESTAMP_TZ_MICROS;
            case "timestamp_ntz" -> TIMESTAMP_MICROS;
            default -> throw new TrinoException(NOT_SUPPORTED, "Unsupported Unity Catalog type: " + typeText);
        };
    }

    /** Split a string by {@code delim} ignoring delimiters inside angle brackets or parentheses. */
    private static List<String> splitTopLevel(String input, char delim)
    {
        ImmutableList.Builder<String> parts = ImmutableList.builder();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            }
            else if (c == '>' || c == ')') {
                depth--;
            }
            else if (c == delim && depth == 0) {
                parts.add(input.substring(start, i));
                start = i + 1;
            }
        }
        parts.add(input.substring(start));
        return parts.build();
    }

    /** Find the first occurrence of {@code ch} at depth 0 (outside any brackets), or -1. */
    private static int findTopLevel(String input, char ch)
    {
        int depth = 0;
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '<' || c == '(') {
                depth++;
            }
            else if (c == '>' || c == ')') {
                depth--;
            }
            else if (c == ch && depth == 0) {
                return i;
            }
        }
        return -1;
    }
}
