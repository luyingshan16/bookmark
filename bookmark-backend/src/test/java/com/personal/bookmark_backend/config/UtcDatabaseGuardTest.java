package com.personal.bookmark_backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UtcDatabaseGuardTest {
    @Test
    void startsWithUtcSessionWithoutReadingMigrationHistory() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select @@session.time_zone", String.class)).thenReturn("+00:00");

        assertDoesNotThrow(new UtcDatabaseGuard(jdbc)::afterSingletonsInstantiated);
        verify(jdbc).queryForObject("select @@session.time_zone", String.class);
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void rejectsNonUtcSession() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject("select @@session.time_zone", String.class)).thenReturn("+08:00");

        assertThrows(IllegalStateException.class, new UtcDatabaseGuard(jdbc)::afterSingletonsInstantiated);
    }
}
