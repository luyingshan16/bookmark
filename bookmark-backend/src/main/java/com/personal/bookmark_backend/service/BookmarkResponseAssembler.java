package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.dto.response.bookmark.BookmarkResponse;
import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.mapper.BookmarkMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class BookmarkResponseAssembler {
    private final TagService tagService;
    private final BookmarkMapper bookmarkMapper;

    public BookmarkResponse toResponse(Long userId, Bookmark bookmark) {
        return toResponses(userId, List.of(bookmark)).get(0);
    }

    public List<BookmarkResponse> toResponses(Long userId, List<Bookmark> bookmarks) {
        if (bookmarks.isEmpty()) return List.of();
        List<Long> ids = bookmarks.stream().map(Bookmark::getId).toList();
        Map<Long, List<String>> tagsByBookmark = tagService.getBookmarkTagNames(userId, ids);

        // 依照原本的收藏順序建立回應，沒有標籤的收藏回傳空清單。
        return bookmarks.stream()
                .map(bookmark -> bookmarkMapper.toResponse(
                        bookmark, tagsByBookmark.getOrDefault(bookmark.getId(), List.of())))
                .toList();
    }
}
