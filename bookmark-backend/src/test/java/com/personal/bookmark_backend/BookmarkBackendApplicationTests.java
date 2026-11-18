package com.personal.bookmark_backend;

import com.personal.bookmark_backend.config.UtcDatabaseGuard;
import com.personal.bookmark_backend.security.GoogleIdTokenVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verifyNoInteractions;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:bookmark_context;MODE=MySQL",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.hikari.connection-init-sql=SELECT 1",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.mail.username=test@example.invalid",
        "spring.mail.password=",
        "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "bookmark.scheduling.enabled=false"
})
class BookmarkBackendApplicationTests {
    @Autowired DataSource dataSource;
    @Autowired ApplicationContext context;
    @MockitoBean JavaMailSender mail;
    @MockitoBean GoogleIdTokenVerifier google;
    @MockitoBean(enforceOverride = true) UtcDatabaseGuard utcGuard;

    @Test
    void contextLoads() throws Exception {
        try (var connection = dataSource.getConnection()) {
            assertTrue(connection.getMetaData().getURL().startsWith("jdbc:h2:mem:bookmark_context"));
        }
        assertTrue(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class).isEmpty());
        verifyNoInteractions(mail, google);
    }

}
