package com.personal.bookmark_backend.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FolderCoverUpdaterTest extends BookmarkTransactionTestSupport {
    @Autowired
    FolderCoverUpdater updater;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void sameSecondUsesHigherIdOnEveryRecalculation() {
        long first = create(false).getId();
        long second = create(false).getId();
        assertTrue(second > first);
        setCoverCandidate(first, "2099-01-01 00:00:00", "https://example.org/first");
        setCoverCandidate(second, "2099-01-01 00:00:00", "https://example.org/second");

        assertRecalculatedCover("https://example.org/second");
    }

    @Test
    void sameSecondHigherIdWithoutImageClearsExistingCover() {
        long first = create(false).getId();
        long second = create(false).getId();
        setCoverCandidate(first, "2099-01-01 00:00:00", "https://example.org/first");
        setCoverCandidate(second, "2099-01-01 00:00:00", null);

        assertRecalculatedCover(null);
    }

    @Test
    void creationTimeTakesPriorityOverId() {
        long first = create(false).getId();
        long second = create(false).getId();
        setCoverCandidate(first, "2099-01-01 00:00:01", "https://example.org/newest");
        setCoverCandidate(second, "2099-01-01 00:00:00", "https://example.org/older");

        assertRecalculatedCover("https://example.org/newest");
    }

    @Test
    void emptyFolderClearsExistingCover() {
        new TransactionTemplate(manager).executeWithoutResult(status ->
                books.deleteAllInFolder(userId, folderId));
        assertRecalculatedCover(null);
    }

    private void setCoverCandidate(long id, String createdAt, String imageUrl) {
        jdbc.update("UPDATE bookmarks SET created_at = ?, image_url = ? WHERE id = ?",
                createdAt, imageUrl, id);
    }

    private void assertRecalculatedCover(String expected) {
        for (int i = 0; i < 3; i++) {
            jdbc.update("UPDATE folders SET image_url = ? WHERE id = ?",
                    "https://example.org/stale", folderId);
            new TransactionTemplate(manager).executeWithoutResult(status ->
                    updater.update(folders.findById(folderId).orElseThrow()));
            assertEquals(expected, folders.findById(folderId).orElseThrow().getImageUrl());
        }
    }
}
