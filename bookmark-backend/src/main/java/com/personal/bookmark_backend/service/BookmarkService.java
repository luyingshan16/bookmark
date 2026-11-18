package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.time.ApplicationTime;
import com.personal.bookmark_backend.dto.request.bookmark.CreateBookmarkRequest;
import com.personal.bookmark_backend.exception.ConflictException;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.BookmarkSource;
import com.personal.bookmark_backend.entity.BookmarkTag;
import com.personal.bookmark_backend.entity.Folder;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import com.personal.bookmark_backend.repository.BookmarkRepository;
import com.personal.bookmark_backend.repository.BookmarkSourceRepository;
import com.personal.bookmark_backend.repository.BookmarkTagRepository;
import com.personal.bookmark_backend.repository.FolderRepository;
import com.personal.bookmark_backend.repository.TagRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;

import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BookmarkService {
    private static final JsonMapper FINGERPRINT_JSON = new JsonMapper();
    private static final int CREATION_REQUEST_RETENTION_DAYS = 7;

    private final BookmarkRepository bookmarkRepository;
    private final BookmarkSourceRepository bookmarkSourceRepository;
    private final FolderRepository folderRepository;
    private final FolderCoverUpdater folderCoverUpdater;
    private final BookmarkTagRepository bookmarkTagRepository;
    private final TagRepository tagRepository;
    private final JdbcTemplate jdbc;

    // 標籤操作交給 TagService 負責
    private final TagService tagService;
    private final DailyPickSelectionService dailyPickSelectionService;

    @Transactional(readOnly = true)
    public long countUserBookmarks(Long userId) {
        return bookmarkRepository.countByUserId(userId);
    }

    @Transactional(readOnly = true)
    public List<BookmarkSource> getBookmarkSources() {
        return bookmarkSourceRepository.findAllByOrderBySortOrderAscCodeAsc();
    }

    @Transactional
    public List<Bookmark> getDailyPicks(Long userId, List<Long> excludeIds) {
        boolean refresh = excludeIds != null && !excludeIds.isEmpty();
        return dailyPickSelectionService.select(userId, refresh, null).picks();
    }

    @Transactional(readOnly = true)
    public Page<Bookmark> searchBookmarks(Long userId, String query, String source, Pageable pageable) {
        return bookmarkRepository.searchForUser(userId, query, source, pageable);
    }

    @Transactional(readOnly = true)
    public Slice<Bookmark> searchBookmarkSlice(Long userId, String query, String source, Pageable pageable) {
        return bookmarkRepository.searchSliceForUser(userId, query, source, pageable);
    }

    @Transactional(readOnly = true)
    public Page<Bookmark> getFolderBookmarkPage(
            Long userId, Long folderId, String query, List<String> tags, Pageable pageable
    ) {
        findUserFolder(userId, folderId);
        return bookmarkRepository.findFolderBookmarkPage(
                userId,
                folderId,
                query.isBlank() ? "" : query,
                queryTags(tags),
                tags.stream().distinct().count(),
                pageable
        );
    }

    @Transactional(readOnly = true)
    public List<Long> getMatchingFolderBookmarkIds(
            Long userId,
            Long folderId,
            String query,
            List<String> tags
    ) {
        findUserFolder(userId, folderId);
        return bookmarkRepository.findMatchingFolderBookmarkIds(
                userId,
                folderId,
                query.isBlank() ? "" : query,
                queryTags(tags),
                tags.stream().distinct().count()
        );
    }

    private List<String> queryTags(List<String> tags) {
        // 空清單不適用於 SQL 的 IN 條件，沒有選標籤時使用佔位值。
        return tags.isEmpty() ? List.of("") : tags;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countSearchResultsBySource(Long userId, String query) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : bookmarkRepository.countSearchResultsBySource(userId, query)) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }


    // =====================================================
    // 1. 查詢收藏夾內的所有收藏
    // =====================================================

    @Transactional(readOnly = true)
    public List<Bookmark> getFolderBookmarks(
            Long userId,
            Long folderId
    ) {

        // 先確認收藏夾屬於這位使用者
        findUserFolder(userId, folderId);

        return bookmarkRepository
                .findAllByUserIdAndFolderIdOrderByCreatedAtDesc(
                        userId,
                        folderId
                );
    }


    // =====================================================
    // 2. 查詢單筆收藏
    // =====================================================

    @Transactional(readOnly = true)
    public Bookmark getBookmark(Long userId, Long bookmarkId) {

        return findUserBookmark(userId, bookmarkId);
    }


    // =====================================================
    // 3. 新增收藏
    // =====================================================

    @Transactional
    public Bookmark createBookmark(Long userId, Long folderId, CreateBookmarkRequest request, UUID requestId) {
        dailyPickSelectionService.lockUser(userId);
        if (requestId == null) return createBookmarkUnderLock(userId, folderId, request);

        String key = requestId.toString();
        String fingerprint = creationFingerprint(folderId, request);
        var previous = jdbc.query("""
                select fingerprint, bookmark_id, expires_at from bookmark_create_requests
                where user_id = ? and request_id = ?
                """, (row, index) -> new CreationRecord(row.getString("fingerprint"), row.getLong("bookmark_id"),
                        row.getObject("expires_at", LocalDateTime.class)),
                userId, key).stream().findFirst();
        if (previous.isPresent()) {
            var saved = previous.get();
            if (saved.expiresAt().isAfter(ApplicationTime.now())) {
                if (!saved.fingerprint().equals(fingerprint)) {
                    throw new ConflictException("此新增識別碼已用於不同收藏內容");
                }
                return bookmarkRepository.findByIdAndUserId(saved.bookmarkId(), userId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.GONE, "原收藏已刪除，請重新開啟新增收藏"));
            }
            jdbc.update("delete from bookmark_create_requests where user_id = ? and request_id = ?", userId, key);
        }

        Bookmark bookmark = createBookmarkUnderLock(userId, folderId, request);
        jdbc.update("""
                insert into bookmark_create_requests (user_id, request_id, fingerprint, bookmark_id, expires_at)
                values (?, ?, ?, ?, ?)
                """, userId, key, fingerprint, bookmark.getId(), ApplicationTime.now().plusDays(CREATION_REQUEST_RETENTION_DAYS));
        return bookmark;
    }

    private record CreationRecord(String fingerprint, long bookmarkId, LocalDateTime expiresAt) {}

    private static String creationFingerprint(Long folderId, CreateBookmarkRequest request) {
        // Keep the order fixed across deployments during the retry window.
        Object[] fields = {folderId, request.url(), request.title(), request.content(), request.note(),
                request.source(), request.imageUrl(), request.tags(), request.addToDailyPicks()};
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(FINGERPRINT_JSON.writeValueAsBytes(fields)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void purgeExpiredCreationRequests() {
        jdbc.update("delete from bookmark_create_requests where expires_at <= ? limit 1000",
                ApplicationTime.now());
    }

    @Transactional
    public Bookmark createBookmark(
            Long userId,
            Long folderId,
            String url,
            String title,
            String content,
            String note,
            String source,
            String imageUrl,
            List<String> tagNames,
            boolean addToDailyPicks
    ) {
        return createBookmark(userId, folderId, new CreateBookmarkRequest(
                url, title, content, note, source, imageUrl, tagNames, addToDailyPicks), null);
    }

    private Bookmark createBookmarkUnderLock(Long userId, Long folderId, CreateBookmarkRequest request) {
        String url = request.url();
        String source = request.source();
        // 確認目標收藏夾屬於目前使用者
        Folder folder = findUserFolder(userId, folderId);

        // 驗證必要欄位
        validateBookmark(url, source);
        validateImageUrl(request.imageUrl());

        Bookmark bookmark = new Bookmark();

        bookmark.setUserId(userId);
        bookmark.setFolderId(folder.getId());
        bookmark.setUrl(url.trim());
        bookmark.setTitle(request.title());
        bookmark.setContent(request.content());
        bookmark.setNote(request.note());
        bookmark.setSource(detectSource(url.trim(), source.trim()));
        bookmark.setImageUrl(request.imageUrl());

        // 儲存收藏，取得資料庫產生的 bookmarkId
        Bookmark savedBookmark = bookmarkRepository.save(bookmark);

        if (request.addToDailyPicks()) dailyPickSelectionService.includeBookmark(userId, savedBookmark);

        // 建立收藏與標籤的關聯
        // 如果標籤不存在，TagService 會自動建立
        tagService.replaceBookmarkTags(
                userId,
                folderId,
                savedBookmark.getId(),
                request.tags()
        );

        // 更新收藏夾封面
        folderCoverUpdater.update(folder);

        return bookmarkRepository.findByIdAndUserId(
                savedBookmark.getId(),
                userId
        ).orElseThrow(() ->
                new ResourceNotFoundException("找不到此收藏")
        );
    }


    // =====================================================
    // 4. 修改收藏內容及標籤
    // =====================================================

    @Transactional
    public Bookmark updateBookmark(
            Long userId,
            Long bookmarkId,
            String url,
            String title,
            String content,
            String note,
            String source,
            String imageUrl,
            List<String> tagNames
    ) {
        return updateBookmark(userId, bookmarkId, url, title, content, note, source, imageUrl, tagNames, null);
    }

    @Transactional
    public Bookmark updateBookmark(
            Long userId, Long bookmarkId, String url, String title, String content,
            String note, String source, String imageUrl, List<String> tagNames,
            List<String> updateFields
    ) {
        Set<String> allowedFields = Set.of("url", "title", "content", "note", "source", "imageUrl", "tags");
        if (updateFields != null && (updateFields.isEmpty() || updateFields.size() > allowedFields.size()
                || updateFields.stream().anyMatch(field -> field == null || !allowedFields.contains(field)))) {
            throw new IllegalArgumentException("更新欄位清單不正確");
        }
        Set<String> fields = updateFields == null ? allowedFields : new LinkedHashSet<>(updateFields);
        if (updateFields != null && fields.contains("tags") && tagNames == null) {
            throw new IllegalArgumentException("更新標籤時須提供清單，清空請傳入 []");
        }

        dailyPickSelectionService.lockUser(userId);
        Bookmark bookmark = findUserBookmark(userId, bookmarkId);

        // 在取得寫入鎖後，以資料庫目前值補足未更新的欄位。
        validateBookmark(fields.contains("url") ? url : bookmark.getUrl(),
                fields.contains("source") ? source : bookmark.getSource());
        if (fields.contains("imageUrl")) validateImageUrl(imageUrl);

        if (fields.contains("url")) bookmark.setUrl(url.trim());
        if (fields.contains("title")) bookmark.setTitle(title);
        if (fields.contains("content")) bookmark.setContent(content);
        if (fields.contains("note")) bookmark.setNote(note);
        if (fields.contains("source")) bookmark.setSource(source.trim());
        if (fields.contains("imageUrl")) bookmark.setImageUrl(imageUrl);

        // 主動更新活動時間
        bookmark.setUpdatedAt(ApplicationTime.now());

        bookmarkRepository.save(bookmark);

        // 替換標籤關聯
        // 修改標籤也會更新 Bookmark.updatedAt
        if (fields.contains("tags")) tagService.replaceBookmarkTags(
                userId,
                bookmark.getFolderId(),
                bookmarkId,
                tagNames
        );

        // 更新所屬收藏夾封面
        Folder folder = findUserFolder(userId, bookmark.getFolderId());

        folderCoverUpdater.update(folder);

        return bookmarkRepository.findByIdAndUserId(
                bookmarkId,
                userId
        ).orElseThrow(() ->
                new ResourceNotFoundException("找不到此收藏")
        );
    }


    // =====================================================
    // 5. 刪除收藏
    // =====================================================

    @Transactional
    public void deleteBookmark(Long userId, Long bookmarkId) {
        dailyPickSelectionService.removeBookmarks(userId, List.of(bookmarkId));

        Bookmark bookmark = findUserBookmark(userId, bookmarkId);

        Long folderId = bookmark.getFolderId();

        // 先記錄這筆收藏原本使用的標籤 ID
        List<Long> oldTagIds = bookmarkTagRepository
                .findAllByBookmarkId(userId, folderId, bookmarkId)
                .stream()
                .map(BookmarkTag::getTagId)
                .distinct()
                .toList();

        // 先刪除收藏與標籤之間的關聯
        bookmarkTagRepository.deleteAllByBookmarkId(
                userId,
                folderId,
                bookmarkId
        );

        bookmarkTagRepository.flush();

        // 刪除收藏本身
        bookmarkRepository.delete(bookmark);
        bookmarkRepository.flush();

        // 清除已經沒有被其他收藏使用的標籤
        for (Long tagId : oldTagIds) {

            long usageCount = bookmarkTagRepository.countByTagId(
                    userId,
                    folderId,
                    tagId
            );

            if (usageCount == 0) {
                tagRepository.deleteById(tagId);
            }
        }

        tagRepository.flush();

        // 更新收藏夾封面
        Folder folder = findUserFolder(userId, folderId);

        folderCoverUpdater.update(folder);
    }


    // =====================================================
    // 6. 搬移收藏到其他收藏夾
    // =====================================================

    @Transactional
    public Bookmark moveBookmark(
            Long userId,
            Long bookmarkId,
            Long targetFolderId
    ) {

        dailyPickSelectionService.lockUser(userId);
        Bookmark bookmark = findUserBookmark(userId, bookmarkId);

        // 確認目標收藏夾屬於目前使用者
        Folder targetFolder = findUserFolder(userId, targetFolderId);

        Long sourceFolderId = bookmark.getFolderId();

        // 如果來源與目標相同，就不需要搬移
        if (sourceFolderId.equals(targetFolderId)) {
            return bookmark;
        }

        // 取得來源收藏夾
        Folder sourceFolder = findUserFolder(userId, sourceFolderId);

        // 搬移收藏，並由 TagService 保留原本的標籤名稱
        tagService.moveBookmarkTags(
                userId,
                bookmark,
                targetFolderId
        );

        // 更新來源收藏夾與目標收藏夾的封面
        folderCoverUpdater.update(sourceFolder);
        folderCoverUpdater.update(targetFolder);

        return bookmark;
    }


    @Transactional
    public List<Long> deleteBookmarkBatch(Long userId, List<Long> ids) {
        return deleteBookmarkBatch(userId, ids, null);
    }

    @Transactional
    public List<Long> deleteBookmarkBatch(Long userId, List<Long> ids, Long sourceFolderId) {
        dailyPickSelectionService.lockUser(userId);
        var owned = bookmarkRepository.findLocationsForDelete(userId, ids, sourceFolderId);
        if (owned.isEmpty()) return List.of();

        List<Long> ownedIds = owned.stream().map(BookmarkRepository.BookmarkLocation::getId).toList();
        dailyPickSelectionService.removeBookmarks(userId, ownedIds);
        var byFolder = owned.stream().collect(Collectors.groupingBy(BookmarkRepository.BookmarkLocation::getFolderId,
                Collectors.mapping(BookmarkRepository.BookmarkLocation::getId, Collectors.toList())));
        for (var entry : byFolder.entrySet()) {
            Long folderId = entry.getKey();
            var bookmarkIds = entry.getValue();
            var tagIds = bookmarkTagRepository.findTagIdsForBookmarks(userId, folderId, bookmarkIds);
            bookmarkTagRepository.deleteByBookmarkIds(userId, folderId, bookmarkIds);
            bookmarkRepository.deleteBatch(userId, folderId, bookmarkIds);
            if (!tagIds.isEmpty()) bookmarkTagRepository.deleteUnusedTags(userId, folderId, tagIds);
            folderCoverUpdater.update(findUserFolder(userId, folderId));
        }
        return ownedIds;
    }

    @Transactional
    public List<Long> moveBookmarkBatch(Long userId, List<Long> ids, Long targetFolderId) {
        return moveBookmarkBatch(userId, ids, targetFolderId, null);
    }

    @Transactional
    public List<Long> moveBookmarkBatch(Long userId, List<Long> ids, Long targetFolderId, Long sourceFolderId) {
        dailyPickSelectionService.lockUser(userId);
        findUserFolder(userId, targetFolderId);
        var owned = sourceFolderId == null
                ? bookmarkRepository.findAllByUserIdAndIdInOrderByCreatedAtDesc(userId, ids)
                : bookmarkRepository.findAllByUserIdAndIdInAndFolderIdInOrderByCreatedAtDesc(
                        userId, ids, List.of(sourceFolderId, targetFolderId));
        Set<Long> affectedFolders = owned.stream().map(Bookmark::getFolderId)
                .filter(folderId -> !folderId.equals(targetFolderId))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (!affectedFolders.isEmpty()) {
            tagService.moveBookmarkTags(userId, owned, targetFolderId);
            affectedFolders.add(targetFolderId);
        }
        for (Long folderId : affectedFolders) {
            folderCoverUpdater.update(findUserFolder(userId, folderId));
        }
        return owned.stream().map(Bookmark::getId).toList();
    }

    // =====================================================
    // 7. 共用方法：查詢使用者自己的收藏夾
    // =====================================================

    private Folder findUserFolder(Long userId, Long folderId) {

        return folderRepository.findByIdAndUserId(folderId, userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("找不到此收藏夾")
                );
    }


    // =====================================================
    // 8. 共用方法：查詢使用者自己的收藏
    // =====================================================

    private Bookmark findUserBookmark(Long userId, Long bookmarkId) {

        return bookmarkRepository.findByIdAndUserId(bookmarkId, userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("找不到此收藏")
                );
    }


    // =====================================================
    // 9. 共用方法：驗證收藏必要欄位
    // =====================================================

    private void validateBookmark(String url, String source) {

        // 檢查收藏網址是否為空
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("收藏網址不能為空");
        }

        String normalizedUrl = url.trim();

        // 檢查收藏網址長度
        if (normalizedUrl.length() > 2048) {
            throw new IllegalArgumentException("收藏網址長度不能超過 2048 個字");
        }

        // 檢查網址格式
        URI uri;

        try {
            uri = URI.create(normalizedUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("收藏網址格式不正確");
        }

        // 只允許 HTTP 或 HTTPS
        String scheme = uri.getScheme();

        if (scheme == null ||
                (!scheme.equalsIgnoreCase("http") &&
                        !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("收藏網址必須使用 HTTP 或 HTTPS");
        }

        // 檢查是否具有主機名稱
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("收藏網址格式不正確");
        }

        // 檢查收藏來源是否為空
        if (source == null || source.trim().isEmpty()) {
            throw new IllegalArgumentException("收藏來源不能為空");
        }

        String normalizedSource = source.trim();
        // 檢查收藏來源長度
        if (normalizedSource.length() > 30) {
            throw new IllegalArgumentException("收藏來源不能超過 30 個字");
        }
        if (!bookmarkSourceRepository.existsById(normalizedSource)) {
            throw new IllegalArgumentException("收藏來源不存在");
        }
    }

    private String detectSource(String url, String fallback) {
        String host = URI.create(url).getHost().toLowerCase(Locale.ROOT);
        String sourceCode = fallback;
        int longestMatch = 0;
        for (BookmarkSource source : getBookmarkSources()) {
            for (String value : source.getDomains().split(",")) {
                String domain = value.trim().toLowerCase(Locale.ROOT);
                if (domain.length() > longestMatch && (host.equals(domain) || host.endsWith("." + domain))) {
                    sourceCode = source.getCode();
                    longestMatch = domain.length();
                }
            }
        }
        return sourceCode;
    }


    // 驗證 ImageUrl
    private void validateImageUrl(String imageUrl) {

        // 預覽圖片網址為選填
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        String normalizedUrl = imageUrl.trim();

        // 檢查長度
        if (normalizedUrl.length() > 2048) {
            throw new IllegalArgumentException("預覽圖片網址長度不能超過 2048 個字");
        }

        URI uri;

        try {
            uri = URI.create(normalizedUrl);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("預覽圖片網址格式不正確");
        }

        String scheme = uri.getScheme();

        // 只允許 HTTP 或 HTTPS
        if (scheme == null ||
                (!scheme.equalsIgnoreCase("http") &&
                        !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("預覽圖片網址必須使用 HTTP 或 HTTPS");
        }

        // 必須有主機名稱
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("預覽圖片網址格式不正確");
        }
    }
}
