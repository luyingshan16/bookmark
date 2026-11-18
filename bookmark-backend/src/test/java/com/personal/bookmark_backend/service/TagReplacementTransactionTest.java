package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// H2 verifies persistence and rollback here; it does not reproduce MySQL collation.
class TagReplacementTransactionTest extends BookmarkTransactionTestSupport {
    @Autowired TagService tags;
    @Autowired TagRepository tagRepository;

    @Test
    void replacementReusesExistingIdAndDeletesOnlyUnusedOldTags() {
        var first = create(false);
        var second = create(false);
        tags.replaceBookmarkTags(userId, folderId, first.getId(), List.of("keep", "unused", "shared"));
        tags.replaceBookmarkTags(userId, folderId, second.getId(), List.of("shared"));
        var original = tagRepository.findByUserIdAndFolderIdAndName(userId, folderId, "keep").orElseThrow();

        tags.replaceBookmarkTags(userId, folderId, first.getId(), List.of("keep", "keep", "new"));

        assertEquals(original.getId(), tagRepository.findByUserIdAndFolderIdAndName(userId, folderId, "keep").orElseThrow().getId());
        assertEquals(Set.of("keep", "shared", "new"), Set.copyOf(tags.getFolderTags(userId, folderId).stream().map(t -> t.getName()).toList()));
        assertEquals(Set.of("keep", "new"), Set.copyOf(tags.getBookmarkTagNames(userId, List.of(first.getId())).get(first.getId())));
        assertEquals(List.of("shared"), tags.getBookmarkTagNames(userId, List.of(second.getId())).get(second.getId()));
    }

    @Test
    void rollbackRestoresOldLinksAndRemovesNewTag() {
        var bookmark = create(false);
        tags.replaceBookmarkTags(userId, folderId, bookmark.getId(), List.of("original"));
        var original = tagRepository.findByUserIdAndFolderIdAndName(userId, folderId, "original").orElseThrow();

        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
            tags.replaceBookmarkTags(userId, folderId, bookmark.getId(), List.of("replacement"));
            throw new IllegalStateException("simulate failure after replacement");
        }));

        assertEquals(List.of("original"), tags.getBookmarkTagNames(userId, List.of(bookmark.getId())).get(bookmark.getId()));
        assertEquals(original.getId(), tags.getFolderTags(userId, folderId).get(0).getId());
        assertTrue(tagRepository.findByUserIdAndFolderIdAndName(userId, folderId, "replacement").isEmpty());
    }
}
