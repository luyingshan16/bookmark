package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.controller.BookmarkController;
import com.personal.bookmark_backend.dto.request.bookmark.CreateBookmarkRequest;
import com.personal.bookmark_backend.exception.GlobalExceptionHandler;
import com.personal.bookmark_backend.mapper.BookmarkMapperImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class BookmarkCreationIdempotencyTest extends BookmarkTransactionTestSupport {
    @Autowired JdbcTemplate jdbc;
    @Autowired TagService tags;
    private MockMvc mvc;
    private final JsonMapper json = new JsonMapper();
    private final CreateBookmarkRequest payload = new CreateBookmarkRequest(
            "https://example.org/retry", "Retry", null, null, "OTHER", null, List.of("test"), false);

    @BeforeEach
    void setupIdempotency() {
        jdbc.execute("""
                create table if not exists bookmark_create_requests (
                    user_id bigint not null, request_id varchar(36) not null, fingerprint varchar(64) not null,
                    bookmark_id bigint not null, expires_at timestamp(6) not null, primary key (user_id, request_id))
                """);
        mvc = MockMvcBuilders.standaloneSetup(new BookmarkController(service,
                        new BookmarkResponseAssembler(tags, new BookmarkMapperImpl()), mock(PasswordResetRateLimiter.class)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void retryAfterDiscardedResponseReturnsOriginalIdAndNewKeyCanCreateSameUrl() throws Exception {
        var key = UUID.randomUUID();
        long before = service.countUserBookmarks(userId);
        // Simulate a committed request whose response the client did not receive.
        mvc.perform(create(key, payload)).andExpect(status().isCreated());
        var original = jdbc.queryForObject("""
                select bookmark_id from bookmark_create_requests where user_id = ? and request_id = ?
                """, Long.class, userId, key.toString());
        mvc.perform(create(key, payload)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(original.intValue()));
        assertEquals(before + 1, service.countUserBookmarks(userId));
        mvc.perform(create(UUID.randomUUID(), payload)).andExpect(status().isCreated());
        mvc.perform(create(null, payload)).andExpect(status().isCreated());
        assertEquals(before + 3, service.countUserBookmarks(userId));
    }

    @Test
    void changedPayloadConflictsAndDeletedBookmarkIsNotRecreated() throws Exception {
        var key = UUID.randomUUID();
        var bookmark = service.createBookmark(userId, folderId, payload, key);
        var changed = new CreateBookmarkRequest(payload.url(), "Changed", null, null, "OTHER", null, List.of("test"), false);
        mvc.perform(create(key, changed)).andExpect(status().isConflict());
        service.deleteBookmark(userId, bookmark.getId());
        long before = service.countUserBookmarks(userId);
        mvc.perform(create(key, payload)).andExpect(status().isGone());
        assertEquals(before, service.countUserBookmarks(userId));
    }

    @Test
    void concurrentIdenticalRequestsCreateExactlyOneBookmark() throws Exception {
        var key = UUID.randomUUID();
        long before = service.countUserBookmarks(userId);
        var pool = Executors.newFixedThreadPool(2);
        var gate = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> { gate.await(); return service.createBookmark(userId, folderId, payload, key).getId(); });
            var b = pool.submit(() -> { gate.await(); return service.createBookmark(userId, folderId, payload, key).getId(); });
            gate.countDown();
            assertEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertEquals(before + 1, service.countUserBookmarks(userId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failedTransactionLeavesNeitherRecordAndExpiredKeysCanBeReused() {
        var key = UUID.randomUUID();
        long before = service.countUserBookmarks(userId);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            service.createBookmark(userId, folderId, payload, key);
            throw new IllegalStateException("rollback");
        }));
        assertEquals(before, service.countUserBookmarks(userId));
        assertEquals(0, jdbc.queryForObject("select count(*) from bookmark_create_requests where user_id = ?", Integer.class, userId));
        var original = service.createBookmark(userId, folderId, payload, key).getId();
        expire(key);
        var replacement = service.createBookmark(userId, folderId, payload, key).getId();
        assertNotEquals(original, replacement);
        expire(key);
        service.purgeExpiredCreationRequests();
        assertEquals(0, jdbc.queryForObject("select count(*) from bookmark_create_requests where user_id = ?", Integer.class, userId));
        assertEquals(before + 2, service.countUserBookmarks(userId));
    }

    private void expire(UUID key) {
        jdbc.update("update bookmark_create_requests set expires_at = ? where user_id = ? and request_id = ?",
                LocalDateTime.of(2000, 1, 1, 0, 0), userId, key.toString());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder create(UUID key, CreateBookmarkRequest request) {
        var builder = post("/api/folders/{id}/bookmarks", folderId)
                .principal(new UsernamePasswordAuthenticationToken(userId, null))
                .contentType("application/json").content(json.writeValueAsBytes(request));
        return key == null ? builder : builder.header("Idempotency-Key", key.toString());
    }
}
