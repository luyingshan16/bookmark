package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.exception.ConflictException;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import com.personal.bookmark_backend.repository.FolderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookmarkBatchJobs {
    private static final Logger logger = LoggerFactory.getLogger(BookmarkBatchJobs.class);
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final BookmarkService bookmarks;
    private final FolderRepository folders;
    private final DailyPickSelectionService users;

    private enum Operation { MOVE, DELETE }

    public record Status(String id, int total, int succeeded, int failed, boolean deleting) {
        public int processed() { return succeeded + failed; }
    }

    @Transactional
    public Status submit(Long userId, String id, long source, long target, List<Long> ids) {
        if (source == target) throw new IllegalArgumentException("請選擇不同的收藏夾");
        return submitBatch(userId, id, Operation.MOVE, source, target, ids);
    }

    @Transactional
    public Status submitDelete(Long userId, String id, long folderId, List<Long> ids) {
        return submitBatch(userId, id, Operation.DELETE, folderId, folderId, ids);
    }

    private Status submitBatch(Long userId, String id, Operation operation, long source, long target, List<Long> ids) {
        users.lockUser(userId);
        var existing = find(userId, id);
        if (existing != null) return existing;
        for (long folderId : operation == Operation.MOVE ? List.of(source, target) : List.of(source)) {
            folders.findByIdAndUserId(folderId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("找不到此收藏夾"));
        }
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from bookmark_batch_jobs
                              where user_id = ? and is_pending = true)
                """, Boolean.class, userId))) {
            throw new ConflictException("已有收藏批量操作正在處理");
        }

        var distinct = ids.stream().distinct().toList();
        jdbc.update("""
                insert into bookmark_batch_jobs (id, user_id, operation, source_id, target_id, total)
                values (?, ?, ?, ?, ?, ?)
                """, id, userId, operation.name(), source, target, distinct.size());
        updateItems("insert into bookmark_batch_items (job_id, bookmark_id) values (?, ?)", id, distinct, 500);
        return new Status(id, distinct.size(), 0, 0, operation == Operation.DELETE);
    }

    public Status find(Long userId, String id) {
        return jdbc.query("""
                select id, total, succeeded, failed, operation
                from bookmark_batch_jobs where user_id = ? and id = ?
                """, BookmarkBatchJobs::status, userId, id).stream().findFirst().orElse(null);
    }

    public Status latest(Long userId, boolean includeDelete) {
        return jdbc.query("""
                select id, total, succeeded, failed, operation
                from bookmark_batch_jobs where user_id = ? and (? or operation = ?)
                order by created_at desc, id desc limit 1
                """, BookmarkBatchJobs::status, userId, includeDelete, Operation.MOVE.name())
                .stream().findFirst().orElse(null);
    }

    private static Status status(ResultSet row, int index) throws SQLException {
        var operation = Operation.valueOf(row.getString("operation"));
        return new Status(row.getString("id"), row.getInt("total"),
                row.getInt("succeeded"), row.getInt("failed"), operation == Operation.DELETE);
    }

    public List<Long> failedIds(Long userId, String id) {
        return jdbc.queryForList("""
                select i.bookmark_id from bookmark_batch_items i join bookmark_batch_jobs j on j.id = i.job_id
                where j.user_id = ? and j.id = ? and i.failed = true order by i.bookmark_id
                """, Long.class, userId, id);
    }

    private record Pending(String id, long userId, int processed) {}

    // 每輪先選出待處理任務，再為每批開啟交易，讓收藏異動與進度一起提交。
    // 批次失敗時先回滾，再用另一個交易記錄重試次數或失敗項目。
    @Scheduled(fixedDelay = 1000)
    public void runPending() {
        var jobs = jdbc.query("""
                select id, user_id, succeeded + failed as processed from bookmark_batch_jobs
                where is_pending = true order by attempts, created_at, id limit 20
                """, (row, index) -> new Pending(row.getString(1), row.getLong(2), row.getInt(3)));
        for (var job : jobs) {
            // 每輪每個任務最多處理五批。
            var pending = job;
            for (int i = 0; i < 5; i++) {
                var batch = pending;
                try {
                    Integer processed = transactions.execute(tx -> runBatch(batch));
                    if (processed == null) break;
                    pending = new Pending(job.id(), job.userId(), processed);
                } catch (RuntimeException error) {
                    logger.warn("收藏批量操作失敗，任務 {}", job.id(), error);
                    recordFailure(batch);
                    break;
                }
            }
        }
    }

    private record LockedJob(Operation operation, long source, long target, int processed, int attempts) {}

    private LockedJob lock(Pending batch) {
        users.lockUser(batch.userId());
        // 排程讀取與取得鎖之間，其他程序可能已處理這批。只有進度仍相同才繼續。
        return jdbc.query("""
                select operation, source_id, target_id, succeeded + failed as processed, attempts
                from bookmark_batch_jobs where id = ? and is_pending = true for update
                """, (row, index) -> new LockedJob(Operation.valueOf(row.getString("operation")),
                row.getLong("source_id"), row.getLong("target_id"), row.getInt("processed"), row.getInt("attempts")),
                batch.id()).stream().filter(job -> job.processed() == batch.processed()).findFirst().orElse(null);
    }

    private List<Long> pendingIds(String id) {
        return jdbc.queryForList("""
                select bookmark_id from bookmark_batch_items
                where job_id = ? and failed = false order by bookmark_id limit 100
                """, Long.class, id);
    }

    private Integer runBatch(Pending batch) {
        var job = lock(batch);
        if (job == null) return null;

        var ids = pendingIds(batch.id());
        if (ids.isEmpty()) return null;
        List<Long> succeeded = switch (job.operation()) {
            case DELETE -> bookmarks.deleteBookmarkBatch(batch.userId(), ids, job.source());
            case MOVE -> folders.findByIdAndUserId(job.target(), batch.userId()).isPresent()
                    ? bookmarks.moveBookmarkBatch(batch.userId(), ids, job.target(), job.source()) : List.of();
        };
        finishBatch(batch.id(), ids, succeeded);
        return batch.processed() + ids.size();
    }

    private void recordFailure(Pending batch) {
        transactions.executeWithoutResult(tx -> {
            var job = lock(batch);
            if (job == null) return;
            if (job.attempts() < 2) {
                jdbc.update("update bookmark_batch_jobs set attempts = attempts + 1 where id = ?", batch.id());
            } else {
                finishBatch(batch.id(), pendingIds(batch.id()), List.of());
            }
        });
    }

    private void finishBatch(String id, List<Long> ids, List<Long> succeeded) {
        var successfulIds = new java.util.HashSet<>(succeeded);
        var failures = ids.stream().filter(bookmarkId -> !successfulIds.contains(bookmarkId)).toList();
        if (!succeeded.isEmpty()) {
            updateItems("delete from bookmark_batch_items where job_id = ? and bookmark_id = ?", id, succeeded, 100);
        }
        if (!failures.isEmpty()) {
            updateItems("update bookmark_batch_items set failed = true where job_id = ? and bookmark_id = ?",
                    id, failures, 100);
        }
        jdbc.update("""
                update bookmark_batch_jobs set succeeded = succeeded + ?, failed = failed + ?, attempts = 0
                where id = ?
                """, succeeded.size(), failures.size(), id);
    }

    private void updateItems(String sql, String jobId, List<Long> bookmarkIds, int batchSize) {
        jdbc.batchUpdate(sql, bookmarkIds, batchSize, (statement, bookmarkId) -> {
            statement.setString(1, jobId);
            statement.setLong(2, bookmarkId);
        });
    }
}
