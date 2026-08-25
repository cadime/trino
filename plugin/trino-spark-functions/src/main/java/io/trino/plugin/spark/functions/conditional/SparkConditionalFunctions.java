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
package io.trino.plugin.spark.functions.conditional;

import io.airlift.slice.Slice;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

/**
 * Spark-compatible {@code nvl} (alias of {@code COALESCE} with two arguments). Implemented as a
 * set of typed overloads — Trino's plugin SPI does not expose a clean way to write a single
 * generic {@code nvl} for arbitrary types without re-implementing function dispatch by hand.
 *
 * <p>The covered types match what Spark/Databricks views typically use: BIGINT, INTEGER, DOUBLE,
 * REAL, BOOLEAN, VARCHAR, DATE. For other types, {@code COALESCE} is always available.
 */
public final class SparkConditionalFunctions
{
    private SparkConditionalFunctions() {}

    @ScalarFunction("nvl")
    @Description("Returns a if a is not NULL, otherwise b (alias of COALESCE)")
    @SqlNullable
    @SqlType(StandardTypes.BIGINT)
    public static Long nvlBigint(
            @SqlNullable @SqlType(StandardTypes.BIGINT) Long a,
            @SqlNullable @SqlType(StandardTypes.BIGINT) Long b)
    {
        return a != null ? a : b;
    }

    @ScalarFunction("nvl")
    @SqlNullable
    @SqlType(StandardTypes.INTEGER)
    public static Long nvlInteger(
            @SqlNullable @SqlType(StandardTypes.INTEGER) Long a,
            @SqlNullable @SqlType(StandardTypes.INTEGER) Long b)
    {
        return a != null ? a : b;
    }

    @ScalarFunction("nvl")
    @SqlNullable
    @SqlType(StandardTypes.DOUBLE)
    public static Double nvlDouble(
            @SqlNullable @SqlType(StandardTypes.DOUBLE) Double a,
            @SqlNullable @SqlType(StandardTypes.DOUBLE) Double b)
    {
        return a != null ? a : b;
    }

    @ScalarFunction("nvl")
    @SqlNullable
    @SqlType(StandardTypes.REAL)
    public static Long nvlReal(
            @SqlNullable @SqlType(StandardTypes.REAL) Long a,
            @SqlNullable @SqlType(StandardTypes.REAL) Long b)
    {
        return a != null ? a : b;
    }

    @ScalarFunction("nvl")
    @SqlNullable
    @SqlType(StandardTypes.BOOLEAN)
    public static Boolean nvlBoolean(
            @SqlNullable @SqlType(StandardTypes.BOOLEAN) Boolean a,
            @SqlNullable @SqlType(StandardTypes.BOOLEAN) Boolean b)
    {
        return a != null ? a : b;
    }

    @ScalarFunction("nvl")
    @SqlNullable
    @SqlType(StandardTypes.VARCHAR)
    public static Slice nvlVarchar(
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice a,
            @SqlNullable @SqlType(StandardTypes.VARCHAR) Slice b)
    {
        return a != null ? a : b;
    }

    @ScalarFunction("nvl")
    @SqlNullable
    @SqlType(StandardTypes.DATE)
    public static Long nvlDate(
            @SqlNullable @SqlType(StandardTypes.DATE) Long a,
            @SqlNullable @SqlType(StandardTypes.DATE) Long b)
    {
        return a != null ? a : b;
    }
}
