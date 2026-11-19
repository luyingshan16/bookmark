package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.BookmarkSource;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BookmarkSearchTransactionTest extends BookmarkTransactionTestSupport {
    @Test
    void onlyOtherIsUsedForTheOtherSource() {
        var page = PageRequest.of(0, 20);
        assertEquals(8, service.searchBookmarks(userId, null, "OTHER", page).getTotalElements());
        assertTrue(service.searchBookmarks(userId, null, "WEB", page).isEmpty());
        assertEquals(Map.of("OTHER", 8L), service.countSearchResultsBySource(userId, null));

        assertThrows(
                IllegalArgumentException.class, () -> service.createBookmark(
                        userId,
                        folderId,
                        "https://example.org",
                        "title",
                        null,
                        null,
                        "WEB",
                        null,
                        List.of(),
                        false
                )
        );
        var bookmark = create(false);
        assertThrows(
                IllegalArgumentException.class, () -> service.updateBookmark(
                        userId,
                        bookmark.getId(),
                        bookmark.getUrl(),
                        bookmark.getTitle(),
                        null,
                        null,
                        "WEB",
                        null,
                        List.of()
                )
        );
    }

    @Test
    void readsTheSourceCatalogAndMatchesConfiguredDomains() {
        assertEquals(
                List.of("EXAMPLE", "OTHER"),
                service.getBookmarkSources().stream().map(BookmarkSource::getCode).toList()
        );
        var matched = service.createBookmark(
                userId, folderId, "https://www.example.com/post",
                "Example", null, null, "OTHER", null, List.of(), false
        );
        var unmatched = service.createBookmark(
                userId, folderId, "https://example.com.example.org/post",
                "Unmatched", null, null, "OTHER", null, List.of(), false
        );

        assertEquals("EXAMPLE", matched.getSource());
        assertEquals("OTHER", unmatched.getSource());
        assertEquals(
                1,
                service.searchBookmarks(userId, null, "EXAMPLE", PageRequest.of(0, 20)).getTotalElements()
        );
    }

    @Test
    void searchSlicesFindTheLastPartialPage() {
        var first = service.searchBookmarkSlice(userId, "title", "OTHER", PageRequest.of(0, 5));
        var last = service.searchBookmarkSlice(userId, "title", "OTHER", PageRequest.of(1, 5));
        assertTrue(first.hasNext());
        assertFalse(last.hasNext());
        assertEquals(3, last.getNumberOfElements());
        assertTrue(Collections.disjoint(
                first.getContent().stream().map(Bookmark::getId).toList(),
                last.getContent().stream().map(Bookmark::getId).toList()));
    }

    @Test
    void searchCountsBookmarksOnlyOnceWhenMultipleTagsMatch() {
        var tagged = service.createBookmark(userId, folderId, "https://example.org/tagged", "Tagged",
                null, null, "OTHER", null, List.of("旅遊", "旅館"), false);
        var note = service.createBookmark(userId, folderId, "https://example.org/note", "Note",
                null, "旅", "OTHER", null, List.of(), false);

        var first = service.searchBookmarks(userId, "旅", "other", PageRequest.of(0, 1));
        var slice = service.searchBookmarkSlice(userId, "旅", null, PageRequest.of(0, 20));
        assertEquals(2L, first.getTotalElements());
        assertEquals(2, slice.getNumberOfElements());
        assertEquals(Set.of(tagged.getId(), note.getId()),
                Set.copyOf(slice.getContent().stream().map(Bookmark::getId).toList()));
        assertEquals(Map.of("OTHER", 2L), service.countSearchResultsBySource(userId, "旅"));
        assertEquals(Map.of(), service.countSearchResultsBySource(-1L, "旅"));
        assertEquals(List.of(note.getId()), service.searchBookmarkSlice(userId, "org/no", null, PageRequest.of(0, 20))
                .getContent().stream().map(Bookmark::getId).toList());
    }

    @Test
    void searchesTextWithoutAnyMatchingTagAndKeepsTagMatchesFilteredBySource() {
        var tagged = service.createBookmark(userId, folderId, "https://www.example.com/tagged",
                "Tagged", null, null, "OTHER", null, List.of("旅遊", "旅行"), false);
        var text = service.createBookmark(userId, folderId, "https://example.org/text",
                "中文片段", null, null, "OTHER", null, List.of(), false);
        var page = PageRequest.of(0, 1);

        assertEquals(List.of(text.getId()), service.searchBookmarkSlice(userId, "文片", "other", page)
                .getContent().stream().map(Bookmark::getId).toList());
        var tagMatch = service.searchBookmarkSlice(userId, "旅", "example", page);
        assertEquals(List.of(tagged.getId()), tagMatch.getContent().stream().map(Bookmark::getId).toList());
        assertFalse(tagMatch.hasNext());
        assertTrue(service.searchBookmarkSlice(userId, "旅%", "other", page).isEmpty());
        assertTrue(service.searchBookmarkSlice(userId, "完全沒有命中", null, page).isEmpty());
    }

    @Test
    void searchTreatsLikeWildcardsAsLiteralCharacters() {
        service.createBookmark(userId, folderId, "https://example.org/plain", "旅遊",
                null, null, "OTHER", null, List.of("旅館"), false);
        var literal = service.createBookmark(userId, folderId, "https://example.org/encoded%20url",
                "Literal", null, "確認!", "OTHER", null, List.of("旅_"), false);
        var page = PageRequest.of(0, 20);

        for (String query : List.of("旅_", "%20", "確認!")) {
            assertEquals(List.of(literal.getId()), service.searchBookmarkSlice(userId, query, null, page)
                    .getContent().stream().map(Bookmark::getId).toList());
            assertEquals(1, service.searchBookmarks(userId, query, null, page).getTotalElements());
            assertEquals(Map.of("OTHER", 1L), service.countSearchResultsBySource(userId, query));
        }
        assertTrue(service.searchBookmarkSlice(userId, "旅%", null, page).isEmpty());
    }
}
