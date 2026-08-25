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
package io.trino.plugin.spark.functions;

import com.google.common.collect.ImmutableSet;
import io.trino.plugin.spark.functions.array.SparkArrayFunctions;
import io.trino.plugin.spark.functions.conditional.SparkConditionalFunctions;
import io.trino.plugin.spark.functions.datetime.SparkDateTimeFunctions;
import io.trino.plugin.spark.functions.encoding.SparkEncodingFunctions;
import io.trino.plugin.spark.functions.json.SparkJsonFunctions;
import io.trino.plugin.spark.functions.string.SparkStringFunctions;
import io.trino.spi.Plugin;

import java.util.Set;

/**
 * Plugin that registers Spark/Databricks-compatible function aliases as native Trino functions.
 *
 * <p>The goal is to let queries and stored views written for Spark SQL run unchanged on Trino —
 * particularly views imported from Unity Catalog whose definitions reference functions that exist
 * in Spark but not in Trino, or that use the Spark name for a function whose Trino equivalent has
 * a different name (e.g. {@code get_json_object} vs {@code json_extract_scalar}).
 *
 * <p>Each registered function is a thin Java implementation that mirrors the Spark semantics. We
 * deliberately re-implement (rather than delegate to Trino built-ins) because Trino plugin SPI does
 * not expose a clean way to call other built-in functions, and because some Spark functions differ
 * subtly from their Trino counterparts (default formats, NULL handling, type coercion).
 */
public class SparkFunctionsPlugin
        implements Plugin
{
    @Override
    public Set<Class<?>> getFunctions()
    {
        return ImmutableSet.<Class<?>>builder()
                .add(SparkJsonFunctions.class)
                .add(SparkStringFunctions.class)
                .add(SparkDateTimeFunctions.class)
                .add(SparkArrayFunctions.class)
                .add(SparkConditionalFunctions.class)
                .add(SparkEncodingFunctions.class)
                .build();
    }
}
