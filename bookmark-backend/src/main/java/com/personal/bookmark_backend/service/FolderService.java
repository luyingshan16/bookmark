package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.*;
import com.personal.bookmark_backend.exception.ConflictException;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import com.personal.bookmark_backend.repository.BookmarkRepository;
import com.personal.bookmark_backend.repository.BookmarkTagRepository;
import com.personal.bookmark_backend.repository.FolderRepository;
import com.personal.bookmark_backend.repository.TagRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class FolderService {
    private static final PageRequest MOVE_BATCH = PageRequest.of(0, 500);

    @PersistenceContext
    private EntityManager entityManager;

    // 收藏夾 Repository
    private final FolderRepository folderRepository;
    private final FolderCoverUpdater folderCoverUpdater;
    private final DailyPickSelectionService dailyPickSelectionService;
    private final TagService tagService;

    // 收藏 Repository
    private final BookmarkRepository bookmarkRepository;

    // 標籤 Repository
    private final TagRepository tagRepository;

    // 收藏與標籤關聯 Repository
    private final BookmarkTagRepository bookmarkTagRepository;


    // =====================================================
    // 1. 查詢使用者的所有收藏夾
    // =====================================================

    @Transactional(readOnly = true)
    public List<Folder> getUserFolders(Long userId) {

        // 依照最近活動時間排序：
        // 最新新增或修改過收藏的收藏夾排在前面。
        // 空收藏夾則依照建立時間排序。
        return folderRepository.findAllByUserIdOrderByLatestActivity(userId);
    }

    @Transactional(readOnly = true)
    public Page<Folder> getFolderPage(Long userId, Pageable pageable) {
        return folderRepository.findFolderPage(userId, pageable);
    }

    @Transactional(readOnly = true)
    public Folder getFolder(Long userId, Long folderId) {
        return findUserFolder(userId, folderId);
    }

    @Transactional(readOnly = true)
    public Map<Long, Long> countBookmarks(Long userId, List<Folder> folders) {
        if (folders.isEmpty()) return Map.of();
        List<Long> ids = folders.stream().map(Folder::getId).toList();
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : bookmarkRepository.countByFolderIds(userId, ids)) {
            counts.put((Long) row[0], (Long) row[1]);
        }
        return counts;
    }


    // =====================================================
    // 2. 新增收藏夾
    // =====================================================

    @Transactional
    public Folder createFolder(Long userId, String name) {

        // 去除名稱前後的空白
        String folderName = availableFolderName(userId, name);

        // 建立新的收藏夾 Entity
        Folder folder = new Folder();

        folder.setUserId(userId);
        folder.setName(folderName);

        // 一般新增的收藏夾不是預設收藏夾
        folder.setIsDefault(false);

        // 儲存至資料庫
        return folderRepository.save(folder);
    }


    // =====================================================
    // 3. 修改收藏夾名稱
    // =====================================================

    @Transactional
    public Folder renameFolder(Long userId, Long folderId, String newName) {

        // 確認新名稱是否合法
        String folderName = validateFolderName(newName);

        // 查詢收藏夾，同時確認它屬於目前使用者
        Folder folder = findUserFolder(userId, folderId);

        // 不允許修改預設收藏夾「未分類」的名稱
        if (Boolean.TRUE.equals(folder.getIsDefault())) {
            throw new IllegalArgumentException("不能修改預設收藏夾的名稱");
        }

        // 檢查是否有其他收藏夾使用相同名稱
        Optional<Folder> existingFolder =
                folderRepository.findByUserIdAndName(userId, folderName);

        if (existingFolder.isPresent()
                && !existingFolder.get().getId().equals(folderId)) {

            throw new ConflictException("收藏夾名稱已經存在");
        }

        // 更新收藏夾名稱
        folder.setName(folderName);

        return folderRepository.save(folder);
    }


    // =====================================================
    // 4. 刪除收藏夾
    // =====================================================

    @Transactional
    public void deleteFolder(Long userId, Long folderId, boolean deleteBookmarks, boolean onlyIfEmpty) {
        dailyPickSelectionService.lockUser(userId);
        validateDeletableFolder(userId, folderId);
        if (onlyIfEmpty) {
            if (bookmarkRepository.existsByUserIdAndFolderId(userId, folderId)) {
                throw new ConflictException("收藏夾已有收藏，請重新選擇處理方式");
            }
        } else if (deleteBookmarks) {
            dailyPickSelectionService.removeBookmarksInFolder(userId, folderId);
            bookmarkTagRepository.deleteAllInFolder(userId, folderId);
            bookmarkRepository.deleteAllInFolder(userId, folderId);
        } else {
            Folder destination = folderRepository.findByUserIdAndIsDefaultTrue(userId)
                    .orElseThrow(() -> new IllegalStateException("找不到使用者的預設收藏夾"));
            moveBookmarks(userId, folderId, destination.getId());
        }
        deleteEmptyFolder(findUserFolder(userId, folderId));
    }

    @Transactional
    public void moveAndDeleteFolder(Long userId, Long folderId, Long targetFolderId, String newFolderName) {
        dailyPickSelectionService.lockUser(userId);
        validateDeletableFolder(userId, folderId);
        Folder destination = findMoveDestination(userId, folderId, targetFolderId, newFolderName);
        if (destination == null) destination = createFolder(userId, newFolderName);
        moveBookmarks(userId, folderId, destination.getId());
        deleteEmptyFolder(findUserFolder(userId, folderId));
    }

    void validateFolderMove(Long userId, Long folderId, Long targetFolderId, String newFolderName) {
        validateDeletableFolder(userId, folderId);
        if (findMoveDestination(userId, folderId, targetFolderId, newFolderName) == null) {
            availableFolderName(userId, newFolderName);
        }
    }

    private Folder findMoveDestination(Long userId, Long folderId, Long targetFolderId, String newFolderName) {
        if ((targetFolderId == null) == (newFolderName == null)) {
            throw new IllegalArgumentException("請選擇既有收藏夾或輸入新收藏夾名稱");
        }
        if (folderId.equals(targetFolderId)) {
            throw new IllegalArgumentException("不能移動到即將刪除的收藏夾");
        }

        return targetFolderId == null ? null : findUserFolder(userId, targetFolderId);
    }

    private String availableFolderName(Long userId, String name) {
        String folderName = validateFolderName(name);
        if (folderRepository.existsByUserIdAndName(userId, folderName)) {
            throw new ConflictException("收藏夾名稱已經存在");
        }
        return folderName;
    }

    void validateDeletableFolder(Long userId, Long folderId) {
        Folder source = findUserFolder(userId, folderId);
        if (Boolean.TRUE.equals(source.getIsDefault())) {
            throw new IllegalArgumentException("不能刪除預設收藏夾");
        }
    }

    private void moveBookmarks(Long userId, Long sourceFolderId, Long targetFolderId) {
        while (true) {
            // 搬完的收藏會從來源移除，每次可以從第一頁接著搬。
            var bookmarks = bookmarkRepository.findByUserIdAndFolderIdOrderByCreatedAtDescIdDesc(
                    userId, sourceFolderId, MOVE_BATCH);
            if (bookmarks.isEmpty()) break;
            tagService.moveBookmarkTags(userId, bookmarks, targetFolderId);
            // 送出 SQL 並釋放已搬完實體的追蹤狀態，資料仍由外層交易一併提交。
            entityManager.flush();
            entityManager.clear();
            if (bookmarks.size() < MOVE_BATCH.getPageSize()) break;
        }
        folderCoverUpdater.update(findUserFolder(userId, targetFolderId));
    }

    private void deleteEmptyFolder(Folder source) {
        // 來源收藏已搬出或刪除，清掉還留在來源收藏夾的標籤。
        tagRepository.deleteAllInFolder(source.getUserId(), source.getId());
        folderRepository.delete(source);
        folderRepository.flush();
    }

    // =====================================================
    // 5. 共用方法：查詢使用者自己的收藏夾
    // =====================================================

    private Folder findUserFolder(Long userId, Long folderId) {

        return folderRepository.findByIdAndUserId(folderId, userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException("找不到此收藏夾")
                );
    }


    // =====================================================
    // 6. 共用方法：驗證收藏夾名稱
    // =====================================================

    private String validateFolderName(String name) {

        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("收藏夾名稱不能為空");
        }

        String folderName = name.trim();

        // Folder Entity 的 name 長度限制為 50
        if (folderName.length() > 50) {
            throw new IllegalArgumentException("收藏夾名稱不能超過 50 個字");
        }

        return folderName;
    }
}
