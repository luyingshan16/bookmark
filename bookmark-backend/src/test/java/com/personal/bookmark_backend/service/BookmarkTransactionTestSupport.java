package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.BookmarkSource;
import com.personal.bookmark_backend.entity.Folder;
import com.personal.bookmark_backend.entity.User;
import com.personal.bookmark_backend.repository.BookmarkRepository;
import com.personal.bookmark_backend.repository.BookmarkSourceRepository;
import com.personal.bookmark_backend.repository.FolderRepository;
import com.personal.bookmark_backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// 各功能測試共用 Spring 設定，每個測試各自建立使用者及其收藏。
@SpringJUnitConfig(BookmarkTransactionTestSupport.Config.class)
abstract class BookmarkTransactionTestSupport {
    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackages = "com.personal.bookmark_backend.repository")
    @Import({DailyPickSelectionService.class, BookmarkService.class, FolderService.class, TagService.class,
            FolderCoverUpdater.class, PasswordResetRateLimiter.class, FolderJobs.class, BookmarkBatchJobs.class})
    static class Config {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(
                    "jdbc:h2:mem:daily;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
                    "sa",
                    ""
            );
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(ds);
            factory.setPackagesToScan("com.personal.bookmark_backend.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto",
                    "create-drop",
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy"
            ));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(jakarta.persistence.EntityManagerFactory emf) {
            return new JpaTransactionManager(emf);
        }

        @Bean
        TransactionTemplate transactionTemplate(PlatformTransactionManager manager) {
            return new TransactionTemplate(manager);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }
    }

    @Autowired
    BookmarkService service;
    @Autowired
    UserRepository users;
    @Autowired
    FolderRepository folders;
    @Autowired
    BookmarkRepository books;
    @Autowired
    BookmarkSourceRepository sources;
    @Autowired
    PlatformTransactionManager manager;
    Long userId, folderId;

    @BeforeEach
    void setup() {
        sources.saveAll(List.of(
                new BookmarkSource("OTHER", "Other", 100, ""),
                new BookmarkSource("EXAMPLE", "Example", 10, "example.net,example.com")
        ));
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var user = new User();
            user.setEmail(UUID.randomUUID() + "@test.example");
            user.setPasswordHash("test");
            userId = users.save(user).getId();
            var folder = new Folder();
            folder.setUserId(userId);
            folder.setName("test");
            folderId = folders.save(folder).getId();
        });
        for (int i = 0; i < 8; i++) create(false);
    }

    Bookmark create(boolean include) {
        return service.createBookmark(
                userId,
                folderId,
                "https://example.org",
                "title",
                null,
                null,
                "OTHER",
                null,
                List.of(),
                include
        );
    }
}
