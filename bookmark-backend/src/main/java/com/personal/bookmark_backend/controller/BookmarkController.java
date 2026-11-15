package com.personal.bookmark_backend.controller;

import com.personal.bookmark_backend.dto.request.bookmark.CreateBookmarkRequest;
import com.personal.bookmark_backend.dto.request.bookmark.MoveBookmarkRequest;
import com.personal.bookmark_backend.dto.request.bookmark.UpdateBookmarkRequest;
import com.personal.bookmark_backend.dto.response.bookmark.BookmarkResponse;
import com.personal.bookmark_backend.dto.response.PageResponse;
import com.personal.bookmark_backend.dto.response.bookmark.BookmarkSearchResponse;
import com.personal.bookmark_backend.dto.response.bookmark.DailyPicksResponse;
import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.BookmarkSource;
import com.personal.bookmark_backend.service.BookmarkService;
import com.personal.bookmark_backend.service.BookmarkResponseAssembler;
import com.personal.bookmark_backend.service.PasswordResetRateLimiter;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class BookmarkController {

    private final BookmarkService bookmarkService;
    private final BookmarkResponseAssembler bookmarkResponses;
    private final PasswordResetRateLimiter rateLimiter;

    // =====================================================
    // 共用方法：取得登入者 ID
    // =====================================================

    private Long getUserId(Authentication authentication) {

        return (Long) authentication.getPrincipal();
    }


    // =====================================================
    // 1. 查詢收藏夾內的所有收藏
    // =====================================================

    @GetMapping("/folders/{folderId}/bookmarks")
    public ResponseEntity<List<BookmarkResponse>> getFolderBookmarks(
            @PathVariable Long folderId, Authentication authentication
    ) {

        // 從登入狀態取得目前使用者 ID
        Long userId = getUserId(authentication);

        // 查詢收藏夾內的所有收藏
        rateLimiter.checkSource("folder-bookmark-list", userId.toString(), 60);
        List<Bookmark> bookmarks = bookmarkService.getFolderBookmarks(userId, folderId);

        return ResponseEntity.ok(bookmarkResponses.toResponses(userId, bookmarks));
    }

    @GetMapping("/folders/{folderId}/bookmarks/page")
    public PageResponse<BookmarkResponse> getFolderBookmarkPage(
            @PathVariable Long folderId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam MultiValueMap<String, String> parameters,
            @RequestParam(defaultValue = "true") boolean newestFirst,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        Long userId = getUserId(authentication);
        Sort.Direction direction = newestFirst ? Sort.Direction.DESC : Sort.Direction.ASC;
        List<String> tags = validatedTags(parameters, query);
        Sort sort = Sort.by(
                new Sort.Order(direction, "createdAt"),
                Sort.Order.desc("id")
        );
        PageRequest request = PageRequest.of(
                Math.max(0, page),
                Math.max(1, Math.min(size, 50)),
                sort
        );
        var bookmarks = bookmarkService.getFolderBookmarkPage(
                userId,
                folderId,
                query,
                tags,
                request
        );
        return PageResponse.from(
                bookmarks,
                bookmarkResponses.toResponses(userId, bookmarks.getContent())
        );
    }

    @GetMapping("/folders/{folderId}/bookmarks/ids")
    public List<Long> getMatchingFolderBookmarkIds(
            @PathVariable Long folderId,
            @RequestParam(defaultValue = "") String query,
            @RequestParam MultiValueMap<String, String> parameters,
            Authentication authentication
    ) {
        Long userId = getUserId(authentication);
        List<String> tags = validatedTags(parameters, query);
        rateLimiter.checkSource("folder-bookmark-ids", userId.toString(), 60);
        return bookmarkService.getMatchingFolderBookmarkIds(
                userId,
                folderId,
                query,
                tags
        );
    }

    private List<String> validatedTags(MultiValueMap<String, String> parameters, String query) {
        List<String> tags = parameters.getOrDefault("tag", List.of());
        if (query.length() > 255 || tags.size() > 50 || tags.stream().anyMatch(tag -> tag.length() > 30)) {
            throw new IllegalArgumentException("收藏篩選條件過長或過多");
        }
        return tags;
    }

    @GetMapping("/bookmarks/daily-picks")
    public ResponseEntity<DailyPicksResponse> getDailyPicks(
            // 舊版 API 仍接受此參數，推薦由後端管理。
            @RequestParam(defaultValue = "0") int rotation,
            @RequestParam(required = false) List<Long> excludeIds,
            Authentication authentication
    ) {
        Long userId = getUserId(authentication);
        List<Bookmark> bookmarks = bookmarkService.getDailyPicks(userId, excludeIds);
        List<BookmarkResponse> picks = bookmarkResponses.toResponses(userId, bookmarks);

        return ResponseEntity.ok(
                new DailyPicksResponse(bookmarkService.countUserBookmarks(userId), picks)
        );
    }

    @GetMapping("/bookmark-sources")
    public List<BookmarkSource> getBookmarkSources() {
        return bookmarkService.getBookmarkSources();
    }

    @GetMapping("/bookmarks/search")
    public ResponseEntity<BookmarkSearchResponse> searchBookmarks(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        Long userId = getUserId(authentication);
        String normalizedQuery = normalizeSearchTerm(query, 255);
        String normalizedSource = normalizeSearchTerm(source, 30);

        Page<Bookmark> results = bookmarkService.searchBookmarks(
                userId,
                normalizedQuery,
                normalizedSource,
                searchPageRequest(page, size)
        );

        List<BookmarkResponse> content = bookmarkResponses.toResponses(
                userId,
                results.getContent()
        );
        Map<String, Long> sourceCounts = bookmarkService.countSearchResultsBySource(
                userId,
                normalizedQuery
        );

        return ResponseEntity.ok(
                new BookmarkSearchResponse(
                        content,
                        results.getNumber(),
                        results.getSize(),
                        results.getTotalElements(),
                        results.getTotalPages(),
                        sourceCounts
                )
        );
    }

    @GetMapping("/bookmarks/search/page")
    public SearchPage searchBookmarkPage(
            @RequestParam(required = false) String query,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "true") boolean includeSourceCounts,
            Authentication authentication
    ) {
        Long userId = getUserId(authentication);
        String normalizedQuery = normalizeSearchTerm(query, 255);
        var results = bookmarkService.searchBookmarkSlice(userId, normalizedQuery,
                normalizeSearchTerm(source, 30), searchPageRequest(page, size));
        var counts = includeSourceCounts
                ? bookmarkService.countSearchResultsBySource(userId, normalizedQuery) : null;
        return new SearchPage(bookmarkResponses.toResponses(userId, results.getContent()),
                results.getNumber(), results.hasNext(), counts);
    }

    @GetMapping("/bookmarks/search/source-counts")
    public Map<String, Long> searchSourceCounts(
            @RequestParam(required = false) String query, Authentication authentication
    ) {
        return bookmarkService.countSearchResultsBySource(
                getUserId(authentication), normalizeSearchTerm(query, 255));
    }

    public record SearchPage(
            List<BookmarkResponse> content, int page, boolean hasNext, Map<String, Long> sourceCounts
    ) {
    }

    private static String normalizeSearchTerm(String term, int maxLength) {
        if (term == null || term.isBlank()) return null;
        String normalized = term.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException("收藏搜尋條件過長");
        }
        return normalized;
    }

    private static PageRequest searchPageRequest(int page, int size) {
        return PageRequest.of(Math.max(0, page), Math.max(1, Math.min(size, 50)));
    }

    @PostMapping("/bookmarks/batch/delete")
    public BatchResult deleteBookmarkBatch(
            @Valid @RequestBody BatchDeleteRequest request, Authentication authentication
    ) {
        return new BatchResult(bookmarkService.deleteBookmarkBatch(getUserId(authentication), request.ids()));
    }

    @PostMapping("/bookmarks/batch/move")
    public BatchResult moveBookmarkBatch(
            @Valid @RequestBody BatchMoveRequest request, Authentication authentication
    ) {
        return new BatchResult(bookmarkService.moveBookmarkBatch(
                getUserId(authentication), request.ids(), request.targetFolderId()));
    }

    public record BatchDeleteRequest(@NotEmpty @Size(max = 100) List<@NotNull @Positive Long> ids) {
    }

    public record BatchMoveRequest(
            @NotEmpty @Size(max = 100) List<@NotNull @Positive Long> ids,
            @NotNull @Positive Long targetFolderId
    ) {
    }

    public record BatchResult(List<Long> succeededIds) {
    }

    // 查詢單筆收藏
    @GetMapping("/bookmarks/{bookmarkId}")
    public ResponseEntity<BookmarkResponse> getBookmark(
            @PathVariable Long bookmarkId,
            Authentication authentication
    ) {

        Long userId = getUserId(authentication);

        // 查詢指定收藏
        Bookmark bookmark = bookmarkService.getBookmark(userId, bookmarkId);

        // 查詢標籤並轉換成 Response DTO
        return ResponseEntity.ok(bookmarkResponses.toResponse(userId, bookmark));
    }


    // =====================================================
    // 3. 新增收藏
    // =====================================================

    @PostMapping("/folders/{folderId}/bookmarks")
    public ResponseEntity<BookmarkResponse> createBookmark(
            @PathVariable Long folderId,
            @Valid @RequestBody CreateBookmarkRequest request,
            Authentication authentication
    ) {

        Long userId = getUserId(authentication);

        // 建立收藏
        Bookmark bookmark = bookmarkService.createBookmark(
                userId,
                folderId,
                request.url(),
                request.title(),
                request.content(),
                request.note(),
                request.source(),
                request.imageUrl(),
                request.tags(),
                request.addToDailyPicks()
        );

        BookmarkResponse response = bookmarkResponses.toResponse(userId, bookmark);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }


    // =====================================================
    // 4. 修改收藏
    // =====================================================

    @PatchMapping("/bookmarks/{bookmarkId}")
    public ResponseEntity<BookmarkResponse> updateBookmark(
            @PathVariable Long bookmarkId,
            @Valid @RequestBody UpdateBookmarkRequest request,
            Authentication authentication
    ) {

        Long userId = getUserId(authentication);

        // 修改收藏內容及標籤
        Bookmark bookmark = bookmarkService.updateBookmark(
                userId,
                bookmarkId,
                request.url(),
                request.title(),
                request.content(),
                request.note(),
                request.source(),
                request.imageUrl(),
                request.tags()
        );

        return ResponseEntity.ok(bookmarkResponses.toResponse(userId, bookmark));
    }


    // =====================================================
    // 5. 刪除收藏
    // =====================================================

    @DeleteMapping("/bookmarks/{bookmarkId}")
    public ResponseEntity<Void> deleteBookmark(
            @PathVariable Long bookmarkId,
            Authentication authentication
    ) {

        Long userId = getUserId(authentication);

        bookmarkService.deleteBookmark(userId, bookmarkId);

        return ResponseEntity.noContent().build();
    }


    // =====================================================
    // 6. 搬移收藏到其他收藏夾
    // =====================================================

    @PatchMapping("/bookmarks/{bookmarkId}/move")
    public ResponseEntity<BookmarkResponse> moveBookmark(
            @PathVariable Long bookmarkId,
            @Valid @RequestBody MoveBookmarkRequest request,
            Authentication authentication
    ) {

        Long userId = getUserId(authentication);

        // 搬移收藏
        Bookmark bookmark = bookmarkService.moveBookmark(
                userId,
                bookmarkId,
                request.targetFolderId()
        );

        return ResponseEntity.ok(bookmarkResponses.toResponse(userId, bookmark));
    }
}
