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
package io.trino.plugin.spark.functions.encoding;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.trino.spi.TrinoException;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

import java.util.Base64;

import static io.trino.spi.StandardErrorCode.INVALID_FUNCTION_ARGUMENT;

public final class SparkEncodingFunctions
{
    private SparkEncodingFunctions() {}

    /**
     * Spark-compatible {@code base64(bin)} — encodes binary data as a Base64 string. Equivalent
     * to Trino's built-in {@code to_base64} but exposed under the Spark name.
     */
    @ScalarFunction("base64")
    @Description("Encode binary data as Base64")
    @SqlType(StandardTypes.VARCHAR)
    public static Slice base64(@SqlType(StandardTypes.VARBINARY) Slice bytes)
    {
        return Slices.utf8Slice(Base64.getEncoder().encodeToString(bytes.getBytes()));
    }

    /**
     * Spark-compatible {@code unbase64(str)} — decodes a Base64 string into binary. Equivalent
     * to Trino's built-in {@code from_base64} but exposed under the Spark name.
     */
    @ScalarFunction("unbase64")
    @Description("Decode a Base64 string into binary")
    @SqlType(StandardTypes.VARBINARY)
    public static Slice unbase64(@SqlType(StandardTypes.VARCHAR) Slice s)
    {
        try {
            return Slices.wrappedBuffer(Base64.getDecoder().decode(s.toStringUtf8()));
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(INVALID_FUNCTION_ARGUMENT, "Invalid Base64 string", e);
        }
    }
}
