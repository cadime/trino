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
package io.trino.plugin.spark.functions.datetime;

import io.airlift.slice.Slice;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.function.Description;
import io.trino.spi.function.ScalarFunction;
import io.trino.spi.function.SqlNullable;
import io.trino.spi.function.SqlType;
import io.trino.spi.type.StandardTypes;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.WeekFields;
import java.util.Locale;

public final class SparkDateTimeFunctions
{
    private SparkDateTimeFunctions() {}

    /**
     * Spark-compatible {@code datediff(end, start)} — returns the number of days between
     * {@code end} and {@code start}, computed as {@code end - start}.
     */
    @ScalarFunction("datediff")
    @Description("Number of days between two dates (end - start)")
    @SqlType(StandardTypes.BIGINT)
    public static long datediff(
            @SqlType(StandardTypes.DATE) long end,
            @SqlType(StandardTypes.DATE) long start)
    {
        return end - start;
    }

    /**
     * Spark-compatible {@code date_add(date, days)} — adds {@code days} to {@code date}.
     * Negative values subtract.
     */
    @ScalarFunction("date_add")
    @Description("Add a number of days to a date")
    @SqlType(StandardTypes.DATE)
    public static long dateAdd(
            @SqlType(StandardTypes.DATE) long date,
            @SqlType(StandardTypes.INTEGER) long days)
    {
        return date + days;
    }

    @ScalarFunction("date_sub")
    @Description("Subtract a number of days from a date")
    @SqlType(StandardTypes.DATE)
    public static long dateSub(
            @SqlType(StandardTypes.DATE) long date,
            @SqlType(StandardTypes.INTEGER) long days)
    {
        return date - days;
    }

    /**
     * Spark-compatible {@code add_months(date, months)} — adds {@code months}; if the resulting
     * day of month does not exist in the new month, snaps to the last valid day.
     */
    @ScalarFunction("add_months")
    @Description("Add a number of months to a date")
    @SqlType(StandardTypes.DATE)
    public static long addMonths(
            @SqlType(StandardTypes.DATE) long date,
            @SqlType(StandardTypes.INTEGER) long months)
    {
        return LocalDate.ofEpochDay(date).plusMonths(months).toEpochDay();
    }

    /**
     * Spark-compatible {@code to_date(string)} — parses a date string in ISO format
     * ({@code yyyy-MM-dd}). Also accepts {@code yyyy-MM-dd HH:mm[:ss[.fff]]} by truncating to
     * the first 10 characters. Returns NULL for unparseable inputs (matching Spark behaviour).
     */
    @ScalarFunction("to_date")
    @Description("Parse a date from an ISO-format string (yyyy-MM-dd[ HH:mm:ss])")
    @SqlNullable
    @SqlType(StandardTypes.DATE)
    public static Long toDate(@SqlNullable @SqlType(StandardTypes.VARCHAR) Slice s)
    {
        if (s == null) {
            return null;
        }
        String text = s.toStringUtf8().trim();
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay();
        }
        catch (DateTimeParseException ignore) {
            // Fall through to truncated form.
        }
        if (text.length() >= 10) {
            try {
                return LocalDate.parse(text.substring(0, 10), DateTimeFormatter.ISO_LOCAL_DATE).toEpochDay();
            }
            catch (DateTimeParseException ignore) {
                // Fall through.
            }
        }
        return null;
    }

    /**
     * Spark-compatible {@code weekofyear(date)} — ISO 8601 week of year (1–53). Identical to
     * Trino's {@code week} but exposed under the Spark name.
     */
    @ScalarFunction("weekofyear")
    @Description("ISO 8601 week of year (1-53)")
    @SqlType(StandardTypes.BIGINT)
    public static long weekOfYear(@SqlType(StandardTypes.DATE) long date)
    {
        return LocalDate.ofEpochDay(date).get(WeekFields.ISO.weekOfWeekBasedYear());
    }

    /**
     * Spark-compatible {@code dayofweek(date)} — uses Spark's convention where 1 = Sunday,
     * 2 = Monday, …, 7 = Saturday. (ISO uses 1 = Monday … 7 = Sunday — different.)
     */
    @ScalarFunction("dayofweek")
    @Description("Day of week with 1 = Sunday, 7 = Saturday (Spark convention)")
    @SqlType(StandardTypes.BIGINT)
    public static long dayOfWeek(@SqlType(StandardTypes.DATE) long date)
    {
        int iso = LocalDate.ofEpochDay(date).getDayOfWeek().getValue(); // 1=Mon..7=Sun
        return (iso % 7) + 1;
    }

    @ScalarFunction("dayofmonth")
    @Description("Day of month (1-31)")
    @SqlType(StandardTypes.BIGINT)
    public static long dayOfMonth(@SqlType(StandardTypes.DATE) long date)
    {
        return LocalDate.ofEpochDay(date).getDayOfMonth();
    }

    @ScalarFunction("dayofyear")
    @Description("Day of year (1-366)")
    @SqlType(StandardTypes.BIGINT)
    public static long dayOfYear(@SqlType(StandardTypes.DATE) long date)
    {
        return LocalDate.ofEpochDay(date).getDayOfYear();
    }

    /**
     * Spark-compatible {@code last_day(date)} — last day of the month containing {@code date}.
     */
    @ScalarFunction("last_day")
    @Description("Last day of the month containing the given date")
    @SqlType(StandardTypes.DATE)
    public static long lastDay(@SqlType(StandardTypes.DATE) long date)
    {
        LocalDate d = LocalDate.ofEpochDay(date);
        return d.withDayOfMonth(d.lengthOfMonth()).toEpochDay();
    }

    /**
     * Spark-compatible {@code unix_timestamp()} — current Unix epoch in seconds.
     */
    @ScalarFunction("unix_timestamp")
    @Description("Current Unix timestamp in seconds")
    @SqlType(StandardTypes.BIGINT)
    public static long unixTimestamp(ConnectorSession session)
    {
        return session.getStart().getEpochSecond();
    }

    /**
     * Spark-compatible {@code unix_timestamp(string)} — parse a date/time string in the default
     * Spark format {@code yyyy-MM-dd HH:mm:ss} and return its Unix epoch in seconds.
     * Accepts also pure date strings ({@code yyyy-MM-dd}, midnight assumed). Returns NULL for
     * unparseable inputs (matching Spark behaviour). Treats the input as UTC.
     */
    @ScalarFunction("unix_timestamp")
    @Description("Parse a yyyy-MM-dd[ HH:mm:ss] string as UTC and return Unix seconds")
    @SqlNullable
    @SqlType(StandardTypes.BIGINT)
    public static Long unixTimestampParse(@SqlNullable @SqlType(StandardTypes.VARCHAR) Slice s)
    {
        if (s == null) {
            return null;
        }
        String text = s.toStringUtf8().trim();
        try {
            return java.time.LocalDateTime.parse(
                    text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT))
                    .toEpochSecond(java.time.ZoneOffset.UTC);
        }
        catch (DateTimeParseException ignore) {
            // Fall through.
        }
        try {
            return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE)
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toEpochSecond();
        }
        catch (DateTimeParseException ignore) {
            return null;
        }
    }
}
