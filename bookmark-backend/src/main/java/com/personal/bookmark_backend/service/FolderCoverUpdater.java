package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Folder;
import com.personal.bookmark_backend.repository.BookmarkRepository;
import com.personal.bookmark_backend.repository.FolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
@RequiredArgsConstructor
class FolderCoverUpdater {
    private final BookmarkRepository bookmarkRepository;
    private final FolderRepository folderRepository;

    void update(Folder folder) {
        // 封面取自最新一筆收藏；沒有圖片時清空封面。
        String imageUrl = bookmarkRepository.findTopByUserIdAndFolderIdOrderByCreatedAtDesc(
                folder.getUserId(),
                folder.getId()
        ).map(BookmarkRepository.BookmarkCover::getImageUrl).orElse(null);

        if (!Objects.equals(folder.getImageUrl(), imageUrl)) {
            folder.setImageUrl(imageUrl);
            folderRepository.save(folder);
        }
    }
}
