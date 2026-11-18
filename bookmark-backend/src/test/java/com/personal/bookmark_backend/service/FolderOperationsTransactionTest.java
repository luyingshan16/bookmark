package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Folder;
import com.personal.bookmark_backend.entity.User;
import com.personal.bookmark_backend.exception.ConflictException;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class FolderOperationsTransactionTest extends BookmarkTransactionTestSupport {
    @Autowired
    FolderService folderService;
    @Autowired
    FolderJobs folderJobs;
    @Autowired
    DailyPickSelectionService picks;
    @Autowired
    TagService tags;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void createFolderJobsTable(@Autowired JdbcTemplate jdbc) throws IOException {
        var schema = new ClassPathResource("db/schema.sql");
        // Hibernate creates the entity tables in H2. The MySQL bootstrap also defines this JDBC-only table.
        var folderJobsDdl = Pattern.compile("(?ms)^CREATE TABLE `folder_jobs` \\(.*?^\\)[^\\r\\n]*;")
                .matcher(schema.getContentAsString(StandardCharsets.UTF_8));
        if (!folderJobsDdl.find()) {
            throw new IllegalStateException("Missing folder_jobs definition in " + schema.getPath());
        }
        jdbc.execute(folderJobsDdl.group());
    }

    @Test
    void folderPagesAndSelectAllUseTheSameFilters() {
        var first = service.createBookmark(
                userId, folderId, "https://example.org/1", "first", null,
                null, "OTHER", null, List.of("一,二", "共同"), false
        );
        var second = service.createBookmark(
                userId, folderId, "https://example.org/2", "second", null,
                null, "OTHER", null, List.of("一,二", "共同"), false
        );
        service.createBookmark(
                userId, folderId, "https://example.org/3", "third", null,
                null, "OTHER", null, List.of("共同"), false
        );

        var tags = List.of("一,二", "共同");
        var sort = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));
        var page = service.getFolderBookmarkPage(userId, folderId, "Other", tags, PageRequest.of(0, 1, sort));
        var next = service.getFolderBookmarkPage(userId, folderId, "Other", tags, PageRequest.of(1, 1, sort));
        assertEquals(2, page.getTotalElements());
        assertEquals(2, page.getTotalPages());
        assertEquals(second.getId(), page.getContent().get(0).getId());
        assertEquals(first.getId(), next.getContent().get(0).getId());
        assertEquals(
                Set.of(first.getId(), second.getId()),
                Set.copyOf(service.getMatchingFolderBookmarkIds(userId, folderId, "Other", tags))
        );
        assertEquals(
                11, service.getFolderBookmarkPage(
                        userId, folderId, "", List.of(),
                        PageRequest.of(0, 2, sort)
                ).getTotalElements()
        );
        assertThrows(
                com.personal.bookmark_backend.exception.ResourceNotFoundException.class,
                () -> service.getMatchingFolderBookmarkIds(-1L, folderId, "", List.of())
        );
    }

    @Test
    void folderPagesKeepTheDefaultFolderFirstAndCountOnlyTheRequestedUser() {
        var defaultFolder = new Folder();
        defaultFolder.setUserId(userId);
        defaultFolder.setName("未分類");
        defaultFolder.setIsDefault(true);
        var defaultId = folders.save(defaultFolder).getId();
        var first = folders.findFolderPage(userId, PageRequest.of(0, 1));
        var second = folders.findFolderPage(userId, PageRequest.of(1, 1));
        assertEquals(2, first.getTotalElements());
        assertEquals(defaultId, first.getContent().get(0).getId());
        assertEquals(folderId, second.getContent().get(0).getId());

        var counts = books.countByFolderIds(userId, List.of(defaultId, folderId));
        assertEquals(1, counts.size());
        assertArrayEquals(new Object[]{folderId, 8L}, counts.get(0));
        assertTrue(books.countByFolderIds(-1L, List.of(folderId)).isEmpty());
    }

    @Test
    void deletingFolderMovesAllBookmarksAndMergesTagsIntoExistingFolder() {
        var target = folderService.createFolder(userId, "existing");
        service.createBookmark(userId, target.getId(), "https://example.org/existing", "Existing",
                null, null, "OTHER", null, List.of("shared"), false);
        var moved = service.createBookmark(userId, folderId, "https://example.org/moved", "Moved",
                null, "keep this note", "OTHER", "https://example.org/cover", List.of("shared", "unique"), true);

        folderService.moveAndDeleteFolder(userId, folderId, target.getId(), null);

        assertTrue(folders.findById(folderId).isEmpty());
        assertEquals(10, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, target.getId()).size());
        assertEquals("keep this note", books.findById(moved.getId()).orElseThrow().getNote());
        assertEquals(Set.of("shared", "unique"), Set.copyOf(tags.getBookmarkTagNames(userId, List.of(moved.getId()))
                .get(moved.getId())));
        assertEquals(2, tags.getFolderTags(userId, target.getId()).size());
        assertEquals("https://example.org/cover", folders.findById(target.getId()).orElseThrow().getImageUrl());
        assertTrue(picks.readCurrent(userId).orElseThrow().picks().stream().anyMatch(item -> item.getId().equals(moved.getId())));
    }

    @Test
    void creatingDestinationAndDeletingSourceRollBackTogether() {
        jdbc.update("""
                insert into bookmarks (user_id, folder_id, url, title, source, created_at, updated_at)
                select ?, ?, 'https://example.org/extra', concat('Extra ', x), 'OTHER', current_timestamp, current_timestamp
                from system_range(1, 501)
                """, userId, folderId);
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            folderService.moveAndDeleteFolder(userId, folderId, null, "new destination");
            var target = folders.findByUserIdAndName(userId, "new destination").orElseThrow();
            assertTrue(folders.findById(folderId).isEmpty());
            assertEquals(509, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, target.getId()).size());
            status.setRollbackOnly();
        });
        assertTrue(folders.findById(folderId).isPresent());
        assertTrue(folders.findByUserIdAndName(userId, "new destination").isEmpty());
        assertEquals(509, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, folderId).size());

        folderService.moveAndDeleteFolder(userId, folderId, null, "new destination");
        assertTrue(folders.findById(folderId).isEmpty());
        var target = folders.findByUserIdAndName(userId, "new destination").orElseThrow();
        assertEquals(509, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, target.getId()).size());
    }

    @Test
    void deletingFolderRejectsInvalidDestinationAndStaleEmptyConfirmation() {
        var anotherUser = new User();
        anotherUser.setEmail(UUID.randomUUID() + "@test.example");
        anotherUser.setPasswordHash("test");
        var inaccessible = folderService.createFolder(users.save(anotherUser).getId(), "another user's folder");
        assertThrows(ResourceNotFoundException.class,
                () -> folderService.moveAndDeleteFolder(userId, folderId, inaccessible.getId(), null));
        assertThrows(IllegalArgumentException.class,
                () -> folderService.moveAndDeleteFolder(userId, folderId, folderId, null));
        assertThrows(ConflictException.class,
                () -> folderService.moveAndDeleteFolder(userId, folderId, null, "test"));
        assertThrows(ConflictException.class,
                () -> folderService.deleteFolder(userId, folderId, false, true));
        assertTrue(folders.findById(folderId).isPresent());
        assertEquals(8, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, folderId).size());
    }

    @Test
    void legacyFolderDeletionCanStillMoveToDefaultFolderOrDeleteEverything() {
        var unclassified = new Folder();
        unclassified.setUserId(userId);
        unclassified.setName("未分類");
        unclassified.setIsDefault(true);
        Long defaultId = folders.save(unclassified).getId();

        folderService.deleteFolder(userId, folderId, false, false);
        assertTrue(folders.findById(folderId).isEmpty());
        assertEquals(8, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, defaultId).size());

        var disposable = folderService.createFolder(userId, "disposable");
        var bookmark = service.createBookmark(userId, disposable.getId(), "https://example.org/delete", "Disposable",
                null, null, "OTHER", null, List.of("tag"), false);
        folderService.deleteFolder(userId, disposable.getId(), true, false);
        assertTrue(folders.findById(disposable.getId()).isEmpty());
        assertTrue(books.findById(bookmark.getId()).isEmpty());
        assertEquals(8, books.countByUserId(userId));
    }

    @Test
    void folderJobsPersistBeforeProcessingAndReportTheirOutcome() {
        var moveId = UUID.randomUUID().toString();
        var move = folderJobs.submit(userId, moveId, folderId, null, "background target", false);
        assertEquals(FolderJobs.State.PENDING, move.state());
        assertEquals(move, folderJobs.submit(userId, moveId, folderId, null, "background target", false));
        assertNull(folderJobs.find(-1L, moveId));
        assertTrue(folders.findById(folderId).isPresent());
        assertTrue(folders.findByUserIdAndName(userId, "background target").isEmpty());

        folderJobs.runPending();
        assertEquals(FolderJobs.State.SUCCEEDED, folderJobs.find(userId, moveId).state());
        assertTrue(folders.findById(folderId).isEmpty());
        var target = folders.findByUserIdAndName(userId, "background target").orElseThrow();
        assertEquals(8, books.countByUserId(userId));

        var delete = folderJobs.submit(userId, UUID.randomUUID().toString(), target.getId(), null, null, true);
        assertTrue(delete.deleting());
        assertEquals(FolderJobs.State.PENDING, delete.state());
        assertEquals(8, books.countByUserId(userId));
        folderJobs.runPending();
        assertEquals(FolderJobs.State.SUCCEEDED, folderJobs.latest(userId).state());
        assertTrue(folders.findById(target.getId()).isEmpty());
        assertEquals(0, books.countByUserId(userId));
    }

    @Test
    void folderJobFailureKeepsSourceAndReportsWhyItCouldNotMove() {
        var destination = folderService.createFolder(userId, "destination");
        var id = UUID.randomUUID().toString();
        folderJobs.submit(userId, id, folderId, destination.getId(), null, false);
        folderService.deleteFolder(userId, destination.getId(), false, true);

        folderJobs.runPending();

        var failed = folderJobs.find(userId, id);
        assertEquals(FolderJobs.State.FAILED, failed.state());
        assertNotNull(failed.errorMessage());
        assertTrue(folders.findById(folderId).isPresent());
        assertEquals(8, books.findAllByUserIdAndFolderIdOrderByCreatedAtDesc(userId, folderId).size());
    }
}
