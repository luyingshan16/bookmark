package com.personal.bookmark_backend.repository;

import com.personal.bookmark_backend.entity.BookmarkTag;
import com.personal.bookmark_backend.entity.BookmarkTagId;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface BookmarkTagRepository
        extends JpaRepository<BookmarkTag, BookmarkTagId> {

    // 查詢指定使用者、指定收藏夾中的所有收藏標籤關聯
    List<BookmarkTag> findAllByUserIdAndFolderId(
            Long userId,
            Long folderId
    );

    // 查詢指定收藏的所有標籤關聯
    @Query("""
            SELECT bt
            FROM BookmarkTag bt
            WHERE bt.userId = :userId
              AND bt.folderId = :folderId
              AND bt.id.bookmarkId = :bookmarkId
            """)
    List<BookmarkTag> findAllByBookmarkId(
            @Param("userId") Long userId,
            @Param("folderId") Long folderId,
            @Param("bookmarkId") Long bookmarkId
    );

    interface BookmarkTagName {
        Long getBookmarkId();
        String getTagName();
    }

    @Query("""
            select b.id as bookmarkId, t.name as tagName
            from BookmarkTag bt
            join Bookmark b on b.id = bt.id.bookmarkId
                and b.userId = bt.userId and b.folderId = bt.folderId
            join Tag t on t.id = bt.id.tagId
                and t.userId = bt.userId and t.folderId = bt.folderId
            where bt.userId = :userId and b.id in :bookmarkIds
            order by b.id, t.createdAt, t.id
            """)
    List<BookmarkTagName> findTagNamesForBookmarks(Long userId, List<Long> bookmarkIds);

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from BookmarkTag bt
            where bt.userId = :userId and bt.folderId = :folderId and bt.id.bookmarkId in :bookmarkIds
            """)
    void deleteByBookmarkIds(Long userId, Long folderId, List<Long> bookmarkIds);

    @Modifying(flushAutomatically = true)
    @Query("delete from BookmarkTag bt where bt.userId = :userId and bt.folderId = :folderId")
    void deleteAllInFolder(Long userId, Long folderId);

    @Query("""
            select distinct bt.id.tagId from BookmarkTag bt
            where bt.userId = :userId and bt.folderId = :folderId and bt.id.bookmarkId in :bookmarkIds
            """)
    List<Long> findTagIdsForBookmarks(Long userId, Long folderId, List<Long> bookmarkIds);

    // 批次取得收藏的標籤關聯
    @Query("""
            SELECT bt
            FROM BookmarkTag bt
            WHERE bt.userId = :userId
              AND bt.folderId = :folderId
              AND bt.id.bookmarkId IN :bookmarkIds
            """)
    List<BookmarkTag> findAllByBookmarkIds(
            @Param("userId") Long userId,
            @Param("folderId") Long folderId,
            @Param("bookmarkIds") List<Long> bookmarkIds
    );

    @Modifying(flushAutomatically = true)
    @Query("""
            delete from Tag t
            where t.userId = :userId and t.folderId = :folderId and t.id in :tagIds
              and not exists (
                  select 1 from BookmarkTag bt
                  where bt.userId = t.userId and bt.folderId = t.folderId and bt.id.tagId = t.id
              )
            """)
    void deleteUnusedTags(Long userId, Long folderId, List<Long> tagIds);

    // 計算指定標籤目前有多少筆收藏關聯
    // 如果回傳 0，代表這個標籤已經沒有任何收藏使用
    @Query("""
            SELECT COUNT(bt)
            FROM BookmarkTag bt
            WHERE bt.userId = :userId
              AND bt.folderId = :folderId
              AND bt.id.tagId = :tagId
            """)
    long countByTagId(
            @Param("userId") Long userId,
            @Param("folderId") Long folderId,
            @Param("tagId") Long tagId
    );

    // 刪除指定收藏的所有標籤關聯
    @Modifying(
            flushAutomatically = true,
            clearAutomatically = true
    )
    @Query("""
            DELETE FROM BookmarkTag bt
            WHERE bt.userId = :userId
              AND bt.folderId = :folderId
              AND bt.id.bookmarkId = :bookmarkId
            """)
    int deleteAllByBookmarkId(
            @Param("userId") Long userId,
            @Param("folderId") Long folderId,
            @Param("bookmarkId") Long bookmarkId
    );

}
