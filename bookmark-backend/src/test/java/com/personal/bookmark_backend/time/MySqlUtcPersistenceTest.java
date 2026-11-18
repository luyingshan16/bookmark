package com.personal.bookmark_backend.time;

import com.personal.bookmark_backend.config.UtcDatabaseGuard;
import com.personal.bookmark_backend.entity.Tag;
import com.personal.bookmark_backend.repository.TagRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in only: the isolated fixture from the UTC migration rehearsal, never the application DB. */
@EnabledIfEnvironmentVariable(named = "BOOKMARK_UTC_MYSQL_TEST", matches = "isolated-33379")
@SpringJUnitConfig(MySqlUtcPersistenceTest.Config.class)
class MySqlUtcPersistenceTest {
    @TestConfiguration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.personal.bookmark_backend.repository")
    static class Config {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            ApplicationTime.initializeUtc();
            var ds = new HikariDataSource();
            ds.setJdbcUrl("jdbc:mysql://127.0.0.1:33379/utc_audit?serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true");
            ds.setUsername("root");
            ds.setPassword("");
            ds.setMaximumPoolSize(2);
            ds.setConnectionInitSql("SET time_zone = '+00:00'");
            return ds;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.personal.bookmark_backend.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                    "hibernate.jdbc.time_zone", "UTC",
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean JdbcTemplate jdbcTemplate(DataSource ds) { return new JdbcTemplate(ds); }
    }

    @Autowired TagRepository tags;
    @Autowired JdbcTemplate jdbc;

    @Test
    void mysqlSessionHibernateAndRawDatetimeAgree() {
        assertDoesNotThrow(() -> new UtcDatabaseGuard(jdbc).afterSingletonsInstantiated());
        var tag = new Tag();
        tag.setUserId(1L);
        tag.setFolderId(1L);
        tag.setName("utc-" + UUID.randomUUID().toString().substring(0, 20));
        var saved = tags.saveAndFlush(tag);
        var raw = jdbc.queryForObject("select created_at from tags where id = ?",
                (rs, row) -> rs.getObject(1, LocalDateTime.class), saved.getId());
        var reloaded = tags.findById(saved.getId()).orElseThrow();
        // Schema precision is seconds; drivers may round fractional input to the next second.
        assertTrue(Math.abs(java.time.Duration.between(saved.getCreatedAt(), raw).toMillis()) <= 1000);
        assertEquals(raw, reloaded.getCreatedAt());
        assertEquals(LocalDateTime.of(2026, 11, 17, 0, 30), ApplicationTime.toTaipei(
                jdbc.queryForObject("select created_at from bookmarks where id = 1",
                        (rs, row) -> rs.getObject(1, LocalDateTime.class))));
    }
}
