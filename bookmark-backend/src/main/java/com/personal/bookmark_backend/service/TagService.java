package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.BookmarkTag;
import com.personal.bookmark_backend.entity.BookmarkTagId;
import com.personal.bookmark_backend.entity.Tag;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import com.personal.bookmark_backend.repository.BookmarkRepository;
import com.personal.bookmark_backend.repository.BookmarkTagRepository;
import com.personal.bookmark_backend.repository.FolderRepository;
import com.personal.bookmark_backend.repository.TagRepository;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.HashMap;

@Service
@RequiredArgsConstructor
public class TagService {

    private final TagRepository tagRepository;
    private final BookmarkRepository bookmarkRepository;
    private final BookmarkTagRepository bookmarkTagRepository;
    private final FolderRepository folderRepository;


    // =====================================================
    // 1. 查詢收藏夾內的所有標籤
    // =====================================================

    @Transactional(readOnly = true)
    public List<Tag> getFolderTags(Long userId, Long folderId) {

        // 先確認收藏夾存在，而且屬於目前登入的使用者
        folderRepository.findByIdAndUserId(folderId, userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("找不到此收藏夾")
                );

        // 查詢收藏夾內的標籤
        return tagRepository.findAllByUserIdAndFolderIdOrderByCreatedAtAsc(
                userId,
                folderId
        );
    }

    // =====================================================
    // 查詢多筆收藏的標籤名稱
    // =====================================================

    @Transactional(readOnly = true)
    public Map<Long, List<String>> getBookmarkTagNames(Long userId, List<Long> bookmarkIds) {
        if (bookmarkIds.isEmpty()) return Map.of();

        Map<Long, List<String>> names = new HashMap<>();
        for (var row : bookmarkTagRepository.findTagNamesForBookmarks(userId, bookmarkIds)) {
            names.computeIfAbsent(row.getBookmarkId(), id -> new ArrayList<>()).add(row.getTagName());
        }
        return names;
    }

    // =====================================================
    // 2. 替換一筆收藏的標籤
    // =====================================================

    @Transactional
    public void replaceBookmarkTags(
            Long userId,
            Long folderId,
            Long bookmarkId,
            List<String> tagNames
    ) {

        // 確認收藏存在，而且屬於指定使用者及收藏夾
        Bookmark bookmark = bookmarkRepository
                .findByIdAndUserId(bookmarkId, userId)
                .filter(item -> item.getFolderId().equals(folderId))
                .orElseThrow(() ->
                        new ResourceNotFoundException("找不到這筆收藏")
                );

        // 整理使用者傳入的標籤名稱
        // 去除前後空白、排除空字串、移除重複名稱
        Set<String> normalizedNames = normalizeTagNames(tagNames);

        // 先查詢這筆收藏原本的標籤關聯
        List<BookmarkTag> oldAssociations =
                bookmarkTagRepository.findAllByBookmarkId(
                        userId,
                        folderId,
                        bookmarkId
                );

        // 記錄原本使用的標籤 ID
        Set<Long> oldTagIds = oldAssociations.stream()
                .map(BookmarkTag::getTagId)
                .collect(Collectors.toSet());

        // 刪除這筆收藏原本的標籤關聯
        bookmarkTagRepository.deleteAllByBookmarkId(
                userId,
                folderId,
                bookmarkId
        );

        // 先執行刪除，避免後續檢查仍讀到舊關聯
        bookmarkTagRepository.flush();


        // -------------------------------------------------
        // 建立或取得新的標籤
        // -------------------------------------------------

        // 一次查詢這個收藏夾已經存在的標籤
        List<Tag> folderTags =
                tagRepository.findAllByUserIdAndFolderId(userId, folderId);

        // 建立「標籤名稱 -> Tag Entity」對照表
        Map<String, Tag> tagMap = folderTags.stream()
                .collect(Collectors.toMap(
                        Tag::getName,
                        Function.identity()
                ));

        List<BookmarkTag> newAssociations = new ArrayList<>();

        for (String tagName : normalizedNames) {

            Tag tag = tagMap.get(tagName);

            // 如果標籤不存在，就建立新的標籤
            if (tag == null) {

                tag = new Tag();
                tag.setUserId(userId);
                tag.setFolderId(folderId);
                tag.setName(tagName);

                tag = tagRepository.save(tag);

                // 加入對照表，避免同一次操作重複建立
                tagMap.put(tagName, tag);
            }

            // 建立收藏與標籤的關聯
            BookmarkTag association = new BookmarkTag();

            BookmarkTagId associationId = new BookmarkTagId();
            associationId.setBookmarkId(bookmarkId);
            associationId.setTagId(tag.getId());

            association.setId(associationId);
            association.setUserId(userId);
            association.setFolderId(folderId);

            newAssociations.add(association);
        }

        // 儲存新的標籤關聯
        bookmarkTagRepository.saveAll(newAssociations);


        // -------------------------------------------------
        // 清除已經沒有被使用的舊標籤
        // -------------------------------------------------

        deleteOrphanTags(userId, folderId, oldTagIds);


        // -------------------------------------------------
        // 更新收藏活動時間
        // -------------------------------------------------

        // 修改 bookmark_tags 不會自動觸發 Bookmark 的 @PreUpdate，
        // 因此必須主動更新收藏的 updatedAt。
        bookmark.setUpdatedAt(LocalDateTime.now());

        bookmarkRepository.save(bookmark);
    }


    // =====================================================
    // 3. 搬移收藏時，保留原本的標籤名稱
    // =====================================================

    @Transactional
    public void moveBookmarkTags(Long userId, Bookmark bookmark, Long targetFolderId) {
        moveBookmarkTags(userId, List.of(bookmark), targetFolderId);
    }

    @Transactional
    public void moveBookmarkTags(Long userId, List<Bookmark> bookmarks, Long targetFolderId) {
        var bySource = bookmarks.stream()
                .filter(bookmark -> !bookmark.getFolderId().equals(targetFolderId))
                .collect(Collectors.groupingBy(Bookmark::getFolderId, LinkedHashMap::new, Collectors.toList()));
        if (bySource.isEmpty()) return;

        Map<String, Tag> destinationTags = new HashMap<>();
        List<BookmarkTag> newAssociations = new ArrayList<>();
        for (var entry : bySource.entrySet()) {
            Long sourceFolderId = entry.getKey();
            var movingBookmarks = entry.getValue();
            var bookmarkIds = movingBookmarks.stream().map(Bookmark::getId).toList();
            var oldAssociations = bookmarkTagRepository.findAllByBookmarkIds(userId, sourceFolderId, bookmarkIds);
            var oldTagIds = oldAssociations.stream().map(BookmarkTag::getTagId).distinct().toList();
            if (!oldTagIds.isEmpty()) {
                var sourceTags = tagRepository.findAllById(oldTagIds).stream()
                        .filter(tag -> userId.equals(tag.getUserId()) && sourceFolderId.equals(tag.getFolderId()))
                        .collect(Collectors.toMap(Tag::getId, Tag::getName));
                var namesToLoad = sourceTags.values().stream().distinct()
                        .filter(name -> !destinationTags.containsKey(name)).toList();
                if (!namesToLoad.isEmpty()) {
                    tagRepository.findAllByUserIdAndFolderIdAndNameIn(userId, targetFolderId, namesToLoad)
                            .forEach(tag -> destinationTags.put(tag.getName(), tag));
                }
                for (var oldAssociation : oldAssociations) {
                    String name = sourceTags.get(oldAssociation.getTagId());
                    if (name == null) throw new IllegalStateException("找不到收藏原本的標籤，無法完成搬移");
                    Tag targetTag = destinationTags.computeIfAbsent(name, key -> tagRepository
                            .findByUserIdAndFolderIdAndName(userId, targetFolderId, key)
                            .orElseGet(() -> {
                                var tag = new Tag();
                                tag.setUserId(userId);
                                tag.setFolderId(targetFolderId);
                                tag.setName(key);
                                return tagRepository.save(tag);
                            }));
                    var newId = new BookmarkTagId();
                    newId.setBookmarkId(oldAssociation.getBookmarkId());
                    newId.setTagId(targetTag.getId());
                    var association = new BookmarkTag();
                    association.setId(newId);
                    association.setUserId(userId);
                    association.setFolderId(targetFolderId);
                    newAssociations.add(association);
                }

                // 複合外鍵要求先移除舊關聯，才能變更收藏的 folder_id。
                bookmarkTagRepository.deleteByBookmarkIds(userId, sourceFolderId, bookmarkIds);
                bookmarkTagRepository.deleteUnusedTags(userId, sourceFolderId, oldTagIds);
            }
            movingBookmarks.forEach(bookmark -> bookmark.setFolderId(targetFolderId));
        }

        bookmarkRepository.flush();
        bookmarkTagRepository.saveAll(newAssociations);
    }

    // =====================================================
    // 4. 共用方法：清除孤立標籤
    // =====================================================

    private void deleteOrphanTags(
            Long userId,
            Long folderId,
            Set<Long> tagIds
    ) {

        for (Long tagId : tagIds) {

            // 確認這個標籤是否還有其他收藏使用
            long usageCount = bookmarkTagRepository.countByTagId(
                    userId,
                    folderId,
                    tagId
            );

            // 如果沒有任何收藏使用，就刪除標籤
            if (usageCount == 0) {
                tagRepository.deleteById(tagId);
            }
        }

        // 將刪除操作送到資料庫
        tagRepository.flush();
    }


    // =====================================================
    // 5. 共用方法：整理標籤名稱
    // =====================================================

    private Set<String> normalizeTagNames(List<String> tagNames) {

        Set<String> normalizedNames = new LinkedHashSet<>();

        if (tagNames == null) {
            return normalizedNames;
        }

        for (String name : tagNames) {

            if (name == null || name.trim().isEmpty()) {
                continue;
            }

            String normalizedName = name.trim();

            // Tag Entity 的 name 長度限制為 30
            if (normalizedName.length() > 30) {
                throw new IllegalArgumentException(
                        "標籤名稱不能超過 30 個字"
                );
            }

            // LinkedHashSet 可以保留輸入順序並自動排除重複值
            normalizedNames.add(normalizedName);
        }

        return normalizedNames;
    }
}
