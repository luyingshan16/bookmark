package com.personal.bookmark_backend.config;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Verify the database connection uses UTC before scheduled work begins. */
@Component
public class UtcDatabaseGuard implements SmartInitializingSingleton {
    private final JdbcTemplate jdbc;

    public UtcDatabaseGuard(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void afterSingletonsInstantiated() {
        String zone = jdbc.queryForObject("select @@session.time_zone", String.class);
        if (!"+00:00".equals(zone)) {
            throw new IllegalStateException("MySQL session time_zone must be +00:00");
        }
    }
}
