package com.personal.bookmark_backend.time;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.TimeZone;

/** Database DATETIME values are UTC; existing public date fields remain Taipei local time. */
public final class ApplicationTime {
    private static final ZoneId TAIPEI = ZoneId.of("Asia/Taipei");

    private ApplicationTime() {}

    public static void initializeUtc() {
        // Run before Spring, JDBC, schedulers and loggers initialize.
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static LocalDateTime toTaipei(LocalDateTime utc) {
        return utc == null ? null : utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(TAIPEI).toLocalDateTime();
    }
}
