package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BookmarkPartialUpdateTransactionTest extends BookmarkTransactionTestSupport {
    @Autowired TagService tags;

    @Test
    void independentClientsKeepEachOthersEditsAndUnselectedTags() {
        var initial = create(false);
        tags.replaceBookmarkTags(userId, folderId, initial.getId(), List.of("original"));
        // Separate repository transactions return the same old snapshot to both clients.
        var clientA = books.findById(initial.getId()).orElseThrow();
        var clientB = books.findById(initial.getId()).orElseThrow();
        update(clientA, "new title", "ignored", List.of("ignored"), List.of("title"));
        update(clientA, "old title", "ignored", List.of("new tag"), List.of("tags"));
        update(clientB, clientB.getTitle(), "new note", List.of("original"), List.of("note"));

        var result = books.findById(initial.getId()).orElseThrow();
        assertEquals("new title", result.getTitle());
        assertEquals("new note", result.getNote());
        assertEquals(clientB.getUrl(), result.getUrl());
        assertEquals(List.of("new tag"), tags.getBookmarkTagNames(userId, List.of(initial.getId())).get(initial.getId()));
    }

    @Test
    void selectedNullClearsNullableFieldsAndEmptyTagsClearsAssociations() {
        var bookmark = service.createBookmark(userId, folderId, "https://example.org", "title",
                "content", "note", "OTHER", "https://example.org/image.png", List.of("tag"), false);
        service.updateBookmark(userId, bookmark.getId(), bookmark.getUrl(), bookmark.getTitle(),
                null, null, "OTHER", null, List.of(), List.of("content", "note", "imageUrl", "tags"));
        var result = books.findById(bookmark.getId()).orElseThrow();
        assertNull(result.getContent());
        assertNull(result.getNote());
        assertNull(result.getImageUrl());
        assertTrue(tags.getFolderTags(userId, folderId).isEmpty());
    }

    @Test
    void nullTagsWithoutSelectionAreIgnoredButSelectedNullTagsAreRejected() {
        var bookmark = create(false);
        tags.replaceBookmarkTags(userId, folderId, bookmark.getId(), List.of("keep"));
        update(bookmark, "title", "saved note", null, List.of("note"));
        assertThrows(IllegalArgumentException.class,
                () -> update(bookmark, "title", "bad note", null, List.of("note", "tags")));
        assertEquals("saved note", books.findById(bookmark.getId()).orElseThrow().getNote());
        assertEquals(List.of("keep"), tags.getBookmarkTagNames(userId, List.of(bookmark.getId())).get(bookmark.getId()));
    }

    @Test
    void invalidFieldMasksCannotWrite() {
        var bookmark = create(false);
        for (var mask : List.of(List.<String>of(), List.of("folderId"), Arrays.asList("note", null))) {
            assertThrows(IllegalArgumentException.class,
                    () -> update(bookmark, "changed", "changed", List.of(), mask));
        }
        assertEquals(bookmark.getTitle(), books.findById(bookmark.getId()).orElseThrow().getTitle());
    }

    @Test
    void legacyRequestStillReplacesAllFieldsAndNullTagsClears() {
        var bookmark = create(false);
        tags.replaceBookmarkTags(userId, folderId, bookmark.getId(), List.of("old"));
        update(bookmark, "legacy title", "legacy note", null, null);
        var result = books.findById(bookmark.getId()).orElseThrow();
        assertEquals("legacy title", result.getTitle());
        assertEquals("legacy note", result.getNote());
        assertTrue(tags.getFolderTags(userId, folderId).isEmpty());
    }

    @Test
    void partialUpdateStillRequiresOwnership() {
        var bookmark = create(false);
        assertThrows(ResourceNotFoundException.class, () -> service.updateBookmark(
                -1L, bookmark.getId(), bookmark.getUrl(), "changed", null, null,
                "OTHER", null, List.of(), List.of("title")));
        assertEquals(bookmark.getTitle(), books.findById(bookmark.getId()).orElseThrow().getTitle());
    }

    private void update(Bookmark snapshot, String title, String note, List<String> tagNames, List<String> fields) {
        service.updateBookmark(userId, snapshot.getId(), snapshot.getUrl(), title, snapshot.getContent(),
                note, snapshot.getSource(), snapshot.getImageUrl(), tagNames, fields);
    }
}
