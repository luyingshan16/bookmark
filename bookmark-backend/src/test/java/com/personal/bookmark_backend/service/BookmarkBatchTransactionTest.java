package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Folder;
import com.personal.bookmark_backend.exception.ConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BookmarkBatchTransactionTest extends BookmarkTransactionTestSupport {
    @Autowired
    FolderService folderService;
    @Autowired
    BookmarkBatchJobs bookmarkJobs;
    @Autowired
    TagService tags;
    @Autowired
    JdbcTemplate jdbc;

    @Test
    void batchMoveKeepsTagsAndBatchDeleteKeepsTagsStillInUse() {
        var target = new Folder();
        target.setUserId(userId);
        target.setName("target");
        long targetId = folders.save(target).getId();
        var first = service.createBookmark(userId, folderId, "https://example.org/1", "first",
                null, null, "OTHER", "https://example.org/cover", List.of("shared", "unique"), false);
        var second = service.createBookmark(userId, folderId, "https://example.org/2", "second",
                null, null, "OTHER", null, List.of("shared"), false);
        var unrelatedFolder = folderService.createFolder(userId, "unrelated");
        var unrelated = service.createBookmark(userId, unrelatedFolder.getId(), "https://example.org/unrelated", "Unrelated",
                null, null, "OTHER", null, List.of(), false);

        assertEquals(Set.of(first.getId(), second.getId()), Set.copyOf(service.moveBookmarkBatch(
                userId, List.of(first.getId(), second.getId(), unrelated.getId(), Long.MAX_VALUE), targetId, folderId)));
        assertEquals(unrelatedFolder.getId(), books.findById(unrelated.getId()).orElseThrow().getFolderId());
        assertEquals(List.of(first.getId()), service.moveBookmarkBatch(userId, List.of(first.getId()), targetId, folderId));
        assertEquals(targetId, books.findById(first.getId()).orElseThrow().getFolderId());
        assertEquals(targetId, books.findById(second.getId()).orElseThrow().getFolderId());
        assertEquals(Set.of("shared", "unique"),
                Set.copyOf(tags.getBookmarkTagNames(userId, List.of(first.getId(), second.getId())).get(first.getId())));
        assertTrue(tags.getBookmarkTagNames(-1L, List.of(first.getId())).isEmpty());

        assertEquals(List.of(first.getId()), service.deleteBookmarkBatch(userId, List.of(first.getId(), Long.MAX_VALUE)));
        assertTrue(books.findById(first.getId()).isEmpty());
        assertEquals(List.of("shared"), tags.getFolderTags(userId, targetId).stream().map(tag -> tag.getName()).toList());
        assertEquals(List.of("shared"), tags.getBookmarkTagNames(userId, List.of(second.getId())).get(second.getId()));
        assertNull(folders.findById(targetId).orElseThrow().getImageUrl());
    }

    @Test
    void batchDeleteReportsEachResultAndIgnoresBookmarksNowInAnotherFolder() {
        jdbc.execute("""
                create table if not exists bookmark_batch_jobs (
                    id varchar(36) primary key, user_id bigint not null, source_id bigint not null,
                    target_id bigint not null, operation varchar(6) not null,
                    total int not null, succeeded int default 0, failed int default 0,
                    attempts int default 0, created_at datetime(6) default current_timestamp(6),
                    is_pending boolean generated always as (succeeded + failed < total)
                )
                """);
        jdbc.execute("""
                create table if not exists bookmark_batch_items (
                    job_id varchar(36) not null, bookmark_id bigint not null, failed boolean default false,
                    primary key(job_id, bookmark_id)
                )
                """);
        var destination = folderService.createFolder(userId, "destination");
        var toDelete = create(false);
        var moved = create(false);
        var id = UUID.randomUUID().toString();
        var submitted = bookmarkJobs.submitDelete(userId, id, folderId, List.of(toDelete.getId(), moved.getId()));
        assertTrue(submitted.deleting());
        assertEquals(0, submitted.processed());
        // 任務類型由 operation 決定，來源與目的 ID 不參與判斷。
        jdbc.update("update bookmark_batch_jobs set target_id = ? where id = ?", destination.getId(), id);
        assertThrows(ConflictException.class,
                () -> bookmarkJobs.submit(userId, UUID.randomUUID().toString(), folderId,
                        destination.getId(), List.of(moved.getId())));
        service.moveBookmarkBatch(userId, List.of(moved.getId()), destination.getId());

        bookmarkJobs.runPending();

        assertEquals(new BookmarkBatchJobs.Status(id, 2, 1, 1, true), bookmarkJobs.latest(userId, true));
        assertNull(bookmarkJobs.latest(userId, false));
        assertEquals(List.of(moved.getId()), bookmarkJobs.failedIds(userId, id));
        assertTrue(books.findById(toDelete.getId()).isEmpty());
        assertEquals(destination.getId(), books.findById(moved.getId()).orElseThrow().getFolderId());

        var toMove = create(false);
        var moveId = UUID.randomUUID().toString();
        bookmarkJobs.submit(userId, moveId, folderId, destination.getId(), List.of(toMove.getId()));
        bookmarkJobs.runPending();
        assertEquals(new BookmarkBatchJobs.Status(moveId, 1, 1, 0, false), bookmarkJobs.latest(userId, false));
        assertEquals(destination.getId(), books.findById(toMove.getId()).orElseThrow().getFolderId());
    }
}
