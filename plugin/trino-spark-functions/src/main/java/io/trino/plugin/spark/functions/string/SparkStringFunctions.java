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
package io.trino.plugin.spark.functions.string;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.TrinoException;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

import java.util.Arrays;

import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;

public final class SparkStringFunctions
{
    private SparkStringFunctions() {}

    /**
     * Spark-compatible {@code instr(str, substr)} — returns the 1-based index of the first
     * occurrence of {@code substr} in {@code str}, or {@code 0} if not found.
     *
     * <p>Positions are counted in UTF-16 code units (Java string semantics), not in code points.
     * For ASCII / BMP text this matches Spark; non-BMP characters (emoji, some CJK) may differ
     * by one position per surrogate pair.
     */
    @ScalarFunction("instr")
    @Description("Position of substring in string (1-based, 0 if not found)")
    @SqlNullable
    @SqlType(StandardTypes.BIGINT)
    public static Long instr(
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice str,
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice substr)
    {
        if (str == null || substr == null) {
            return null;
        }
        return (long) (str.toStringUtf8().indexOf(substr.toStringUtf8()) + 1);
    }

    /**
     * Spark-compatible {@code locate(substr, str)} — same as {@link #instr} but with arguments in
     * the opposite order (substr first), matching Spark's signature.
     */
    @ScalarFunction("locate")
    @Description("Position of substring in string (1-based, 0 if not found)")
    @SqlNullable
    @SqlType(StandardTypes.BIGINT)
    public static Long locate(
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice substr,
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice str)
    {
        if (substr == null || str == null) {
            return null;
        }
        return (long) (str.toStringUtf8().indexOf(substr.toStringUtf8()) + 1);
    }

    /**
     * Spark-compatible {@code locate(substr, str, start)} — search starts from {@code start}
     * (1-based). If {@code start} is &lt; 1, search from the beginning.
     */
    @ScalarFunction("locate")
    @Description("Position of substring in string starting from 'start' (1-based, 0 if not found)")
    @SqlNullable
    @SqlType(StandardTypes.BIGINT)
    public static Long locate(
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice substr,
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice str,
            @SqlType(StandardTypes.BIGINT) long start)
    {
        if (substr == null || str == null) {
            return null;
        }
        int from = start <= 0 ? 0 : (int) (start - 1);
        return (long) (str.toStringUtf8().indexOf(substr.toStringUtf8(), from) + 1);
    }

    /**
     * Spark-compatible {@code space(n)} — returns a string of {@code n} space characters.
     */
    @ScalarFunction("space")
    @Description("String of n spaces")
    @SqlType(StandardTypes.VARCHAR)
    public static Slice space(@SqlType(StandardTypes.BIGINT) long n)
    {
        if (n <= 0) {
            return Slices.EMPTY_SLICE;
        }
        if (n > 1_000_000) {
            throw new TrinoException(INVALID_FUNCTION_ARGUMENT, "space length is too large");
        }
        char[] chars = new char[(int) n];
        Arrays.fill(chars, ' ');
        return Slices.utf8Slice(new String(chars));
    }
}
