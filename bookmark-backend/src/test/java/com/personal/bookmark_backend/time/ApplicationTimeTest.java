package com.personal.bookmark_backend.time;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.TimeZone;
import static org.junit.jupiter.api.Assertions.*;

class ApplicationTimeTest {
    @Test
    void utcNowDoesNotDependOnHostZoneAndStartupNormalizesJvm() {
        var previous = TimeZone.getDefault();
        try {
            for (String zone : new String[]{"UTC", "Asia/Taipei"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone));
                var before = Instant.now();
                var now = ApplicationTime.now().toInstant(ZoneOffset.UTC);
                var after = Instant.now();
                assertFalse(now.isBefore(before));
                assertFalse(now.isAfter(after));
                ApplicationTime.initializeUtc();
                assertEquals(ZoneOffset.UTC, ZoneId.systemDefault().normalized());
            }
        } finally {
            TimeZone.setDefault(previous);
        }
    }

    @Test
    void apiDatePreservesTaipeiDayAcrossUtcMidnight() {
        assertEquals(LocalDateTime.of(2026, 11, 18, 0, 30),
                ApplicationTime.toTaipei(LocalDateTime.of(2026, 11, 17, 16, 30)));
        assertNull(ApplicationTime.toTaipei(null));
    }
}
