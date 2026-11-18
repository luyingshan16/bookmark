package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.BookmarkTag;
import com.personal.bookmark_backend.entity.Tag;
import com.personal.bookmark_backend.repository.BookmarkRepository;
import com.personal.bookmark_backend.repository.BookmarkTagRepository;
import com.personal.bookmark_backend.repository.FolderRepository;
import com.personal.bookmark_backend.repository.TagRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TagServiceTest {
    private final TagRepository tags = mock(TagRepository.class);
    private final BookmarkRepository books = mock(BookmarkRepository.class);
    private final BookmarkTagRepository links = mock(BookmarkTagRepository.class);
    private final TagService service = new TagService(tags, books, links, mock(FolderRepository.class),
            mock(org.springframework.jdbc.core.JdbcTemplate.class));

    private void givenBookmark() {
        var bookmark = new Bookmark();
        bookmark.setId(3L);
        bookmark.setFolderId(2L);
        when(books.findByIdAndUserId(3L, 1L)).thenReturn(Optional.of(bookmark));
    }

    @Test
    void usesDatabaseIdentityForEquivalentNamesAndPreservesExistingSpelling() {
        givenBookmark();
        var work = tag(10L, "Work");
        var accent = tag(11L, "é");
        // Mock the database's result, not a Java implementation of MySQL collation.
        when(tags.findByUserIdAndFolderIdAndName(1L, 2L, "Work")).thenReturn(Optional.of(work));
        when(tags.findByUserIdAndFolderIdAndName(1L, 2L, "work")).thenReturn(Optional.of(work));
        when(tags.findByUserIdAndFolderIdAndName(1L, 2L, "é")).thenReturn(Optional.of(accent));
        when(tags.findByUserIdAndFolderIdAndName(1L, 2L, "e")).thenReturn(Optional.of(accent));

        service.replaceBookmarkTags(1L, 2L, 3L, List.of("Work", "work", "é", "e"));

        assertEquals(List.of(10L, 11L), savedLinks().stream().map(BookmarkTag::getTagId).toList());
        assertEquals("Work", work.getName());
        assertEquals("é", accent.getName());
        verify(tags, never()).save(any(Tag.class));
    }

    @Test
    void firstNewNameWinsAndLaterEquivalentNameReusesIt() {
        givenBookmark();
        var created = tag(10L, "Work");
        when(tags.findByUserIdAndFolderIdAndName(1L, 2L, "Work")).thenReturn(Optional.empty());
        when(tags.save(any(Tag.class))).thenAnswer(invocation -> {
            Tag value = invocation.getArgument(0);
            assertEquals("Work", value.getName());
            assertEquals(1L, value.getUserId());
            assertEquals(2L, value.getFolderId());
            when(tags.findByUserIdAndFolderIdAndName(1L, 2L, "work")).thenReturn(Optional.of(created));
            return created;
        });

        service.replaceBookmarkTags(1L, 2L, 3L, List.of(" Work ", "work", "Work"));

        verify(tags, times(1)).save(any(Tag.class));
        var associations = savedLinks();
        assertEquals(1, associations.size());
        assertEquals(10L, associations.get(0).getTagId());
        assertEquals(1L, associations.get(0).getUserId());
        assertEquals(2L, associations.get(0).getFolderId());
        assertEquals(3L, associations.get(0).getId().getBookmarkId());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<BookmarkTag> savedLinks() {
        ArgumentCaptor<List<BookmarkTag>> captor = ArgumentCaptor.forClass((Class) List.class);
        verify(links).saveAll(captor.capture());
        return captor.getValue();
    }

    private Tag tag(long id, String name) {
        var tag = new Tag();
        tag.setId(id);
        tag.setUserId(1L);
        tag.setFolderId(2L);
        tag.setName(name);
        return tag;
    }
}
