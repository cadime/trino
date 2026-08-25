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
package io.trino.plugin.spark.functions.array;

import io.trino.spi.block.Block;
import io.trino.spi.block.SqlMap;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.function.TypeParameter;
import io.trino.spi.type.StandardTypes;

public final class SparkArrayFunctions
{
    private SparkArrayFunctions() {}

    /**
     * Spark-compatible {@code size(array)} — returns the number of elements, or {@code -1}
     * for a NULL array (Trino's {@code cardinality} returns NULL instead).
     */
    @ScalarFunction("size")
    @Description("Number of elements in an array; -1 if the array is NULL (Spark semantics)")
    @TypeParameter("T")
    @SqlType(StandardTypes.BIGINT)
    public static long sizeArray(@SqlNullable @SqlType("array(T)") Block array)
    {
        return array == null ? -1L : array.getPositionCount();
    }

    /**
     * Spark-compatible {@code size(map)} — number of entries, {@code -1} for NULL.
     */
    @ScalarFunction("size")
    @Description("Number of entries in a map; -1 if the map is NULL (Spark semantics)")
    @TypeParameter("K")
    @TypeParameter("V")
    @SqlType(StandardTypes.BIGINT)
    public static long sizeMap(@SqlNullable @SqlType("map(K,V)") SqlMap map)
    {
        return map == null ? -1L : map.getSize();
    }
}
