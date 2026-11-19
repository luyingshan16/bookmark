package com.personal.bookmark_backend.repository;

import com.personal.bookmark_backend.entity.Bookmark;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface BookmarkRepository extends JpaRepository<Bookmark, Long> {

    @Query(value = "select * from bookmarks where user_id = :userId order by rand(:seed)", nativeQuery = true)
    List<Bookmark> findDailyPicks(
            @Param("userId") Long userId,
            @Param("seed") long seed,
            Pageable pageable
    );

    @Query(value = "select * from bookmarks where user_id = :userId and id not in (:excludeIds) order by rand(:seed)", nativeQuery = true)
    List<Bookmark> findDailyPicksExcluding(
            @Param("userId") Long userId,
            @Param("excludeIds") List<Long> excludeIds,
            @Param("seed") long seed,
            Pageable pageable
    );

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from Bookmark b where b.userId = :userId and b.folderId = :folderId and b.id in :ids")
    void deleteBatch(Long userId, Long folderId, List<Long> ids);

    @Modifying(flushAutomatically = true)
    @Query("delete from Bookmark b where b.userId = :userId and b.folderId = :folderId")
    void deleteAllInFolder(Long userId, Long folderId);

    boolean existsByUserIdAndFolderId(Long userId, Long folderId);

    long countByUserId(Long userId);

    @Query("""
            select b.folderId, count(b) from Bookmark b
            where b.userId = :userId and b.folderId in :folderIds
            group by b.folderId
            """)
    List<Object[]> countByFolderIds(Long userId, List<Long> folderIds);

    // 分頁與全選共用篩選條件，所選標籤必須全部符合。
    String FOLDER_BOOKMARK_FILTER = """
            from Bookmark b
            left join BookmarkSource s on lower(s.code) = lower(coalesce(b.source, 'OTHER'))
            where b.userId = :userId and b.folderId = :folderId
              and (:query = '' or locate(lower(:query), lower(b.title)) > 0
                   or locate(lower(:query), lower(case
                       when b.content is null or trim(b.content) = '' then b.url else b.content end)) > 0
                   or locate(lower(:query), lower(b.note)) > 0
                   or locate(lower(:query), lower(coalesce(s.displayName, b.source, 'OTHER'))) > 0)
              and (:tagCount = 0 or :tagCount = (
                   select count(distinct t.id) from BookmarkTag bt, Tag t
                   where bt.id.bookmarkId = b.id and bt.id.tagId = t.id
                     and bt.userId = :userId and bt.folderId = :folderId
                     and t.name in :tags))
            """;

    @Query(value = "select b " + FOLDER_BOOKMARK_FILTER, countQuery = "select count(b) "
            + FOLDER_BOOKMARK_FILTER)
    Page<Bookmark> findFolderBookmarkPage(
            Long userId, Long folderId, String query, List<String> tags, long tagCount, Pageable pageable);

    @Query("select b.id " + FOLDER_BOOKMARK_FILTER)
    List<Long> findMatchingFolderBookmarkIds(
            Long userId,
            Long folderId,
            String query,
            List<String> tags,
            long tagCount
    );

    List<Bookmark> findAllByUserIdAndIdInOrderByCreatedAtDesc(Long userId, List<Long> ids);

    List<Bookmark> findAllByUserIdAndIdInAndFolderIdInOrderByCreatedAtDesc(
            Long userId, List<Long> ids, List<Long> folderIds);

    interface BookmarkLocation {
        Long getId();
        Long getFolderId();
    }

    @Query("""
            select b.id as id, b.folderId as folderId from Bookmark b
            where b.userId = :userId and b.id in :ids
              and (:sourceFolderId is null or b.folderId = :sourceFolderId)
            order by b.createdAt desc
            """)
    List<BookmarkLocation> findLocationsForDelete(Long userId, List<Long> ids, Long sourceFolderId);

    String SEARCH_TEXT_FILTER = """
            where b.userId = :userId
              and (:query is null or :query = ''
                   or lower(b.title) like lower(concat('%', :query, '%')) escape '!'
                   or lower(b.url) like lower(concat('%', :query, '%')) escape '!'
                   or lower(b.content) like lower(concat('%', :query, '%')) escape '!'
                   or lower(b.note) like lower(concat('%', :query, '%')) escape '!'
                   or lower(b.source) like lower(concat('%', :query, '%')) escape '!'
            """;

    String SEARCH_TAG_NAME_MATCH = "lower(t.name) like lower(concat('%', :query, '%')) escape '!'";
    String SEARCH_TAG_FILTER = "bt.id.tagId = t.id and bt.userId = :userId and " + SEARCH_TAG_NAME_MATCH;

    // 先確認此帳號有命中的標籤。找到足夠結果後仍可提早結束分頁查詢。
    String SEARCH_PAGE_FILTER = "from Bookmark b " + SEARCH_TEXT_FILTER + """
                   or (
                       exists (
                           select 1 from Tag t where t.userId = :userId and
            """ + SEARCH_TAG_NAME_MATCH + """
                       ) and exists (
                       select 1 from BookmarkTag bt, Tag t
                       where bt.id.bookmarkId = b.id and
            """ + SEARCH_TAG_FILTER + ")))";

    // 統計要掃描全部候選收藏，先彙總並去重標籤命中。
    String SEARCH_COUNT_FILTER = """
            from Bookmark b
            left join (
                select distinct bt.id.bookmarkId as bookmarkId
                from Tag t, BookmarkTag bt
                where :query is not null and :query <> '' and
            """ + SEARCH_TAG_FILTER + """
            ) matchingTags on matchingTags.bookmarkId = b.id
            """ + SEARCH_TEXT_FILTER + """
                   or matchingTags.bookmarkId is not null)
            """;

    String SEARCH_SOURCE_FILTER = """
              and (:source is null or :source = ''
                   or lower(b.source) = lower(:source))
            """;

    String ORDERED_SEARCH = "select b " + SEARCH_PAGE_FILTER + SEARCH_SOURCE_FILTER + """
            order by b.createdAt desc, b.id desc
            """;

    @Query(value = ORDERED_SEARCH, countQuery = "select count(b) " + SEARCH_COUNT_FILTER + SEARCH_SOURCE_FILTER)
    Page<Bookmark> searchForUser(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("source") String source,
            Pageable pageable
    );

    @Query(ORDERED_SEARCH)
    Slice<Bookmark> searchSliceForUser(
            @Param("userId") Long userId,
            @Param("query") String query,
            @Param("source") String source,
            Pageable pageable
    );

    @Query("select b.source, count(b) " + SEARCH_COUNT_FILTER + """
            group by b.source
            """)
    List<Object[]> countSearchResultsBySource(
            @Param("userId") Long userId,
            @Param("query") String query
    );

    // 查詢指定使用者在某個收藏夾中的所有收藏
    // 依照建立時間由新到舊排序
    List<Bookmark> findAllByUserIdAndFolderIdOrderByCreatedAtDesc(
            Long userId,
            Long folderId
    );

    // 查詢指定使用者的某一筆收藏
    Optional<Bookmark> findByIdAndUserId(Long id, Long userId);

    @Query("""
            select b.id from Bookmark b where b.userId = :userId and b.folderId = :folderId
            order by b.createdAt desc, b.id desc
            """)
    List<Long> findFolderMoveBatchIds(Long userId, Long folderId, Pageable pageable);

    @Modifying(flushAutomatically = true)
    @Query("""
            update Bookmark b set b.folderId = :targetFolderId, b.updatedAt = :updatedAt
            where b.userId = :userId and b.folderId = :sourceFolderId and b.id in :ids
            """)
    int moveFolderBatch(Long userId, Long sourceFolderId, Long targetFolderId,
                        List<Long> ids, LocalDateTime updatedAt);

    interface BookmarkCover {
        String getImageUrl();
    }

    Optional<BookmarkCover> findTopByUserIdAndFolderIdOrderByCreatedAtDescIdDesc(
            Long userId,
            Long folderId
    );

}
