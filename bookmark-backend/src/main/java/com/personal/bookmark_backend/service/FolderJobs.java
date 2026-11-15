package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.exception.ConflictException;
import com.personal.bookmark_backend.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;

@Service
@RequiredArgsConstructor
public class FolderJobs {
    private static final Logger logger = LoggerFactory.getLogger(FolderJobs.class);
    private static final int MAX_ATTEMPTS = 3;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final FolderService folders;
    private final DailyPickSelectionService users;

    public enum State { PENDING, SUCCEEDED, FAILED }

    public record Status(String id, State state, boolean deleting, String errorMessage) {}

    @Transactional
    public Status submit(Long userId, String id, Long sourceFolderId, Long targetFolderId,
                         String newFolderName, boolean deleteBookmarks) {
        users.lockUser(userId);
        Status existing = find(userId, id);
        if (existing != null) return existing;
        if (Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists(select 1 from folder_jobs where user_id = ? and state = 'PENDING')
                """, Boolean.class, userId))) {
            throw new ConflictException("已有收藏夾正在處理，請等待完成");
        }
        if (deleteBookmarks) {
            if (targetFolderId != null || newFolderName != null) {
                throw new IllegalArgumentException("刪除全部收藏時不需指定移動目的地");
            }
            folders.validateDeletableFolder(userId, sourceFolderId);
        } else {
            folders.validateFolderMove(userId, sourceFolderId, targetFolderId, newFolderName);
        }
        jdbc.update("""
                insert into folder_jobs (id, user_id, source_folder_id, target_folder_id, new_folder_name, delete_bookmarks)
                values (?, ?, ?, ?, ?, ?)
                """, id, userId, sourceFolderId, targetFolderId, newFolderName == null ? null : newFolderName.trim(),
                deleteBookmarks);
        return new Status(id, State.PENDING, deleteBookmarks, null);
    }

    public Status find(Long userId, String id) {
        return jdbc.query("""
                select id, state, delete_bookmarks, error_message from folder_jobs where user_id = ? and id = ?
                """, FolderJobs::status,
                userId, id).stream().findFirst().orElse(null);
    }

    public Status latest(Long userId) {
        return jdbc.query("""
                select id, state, delete_bookmarks, error_message from folder_jobs where user_id = ?
                order by created_at desc, id desc limit 1
                """, FolderJobs::status,
                userId).stream().findFirst().orElse(null);
    }

    private static Status status(ResultSet row, int index) throws SQLException {
        return new Status(row.getString(1), State.valueOf(row.getString(2)), row.getBoolean(3), row.getString(4));
    }

    private record Pending(String id, long userId, long source, Long target, String newName, boolean deleting, int attempts) {}

    @Scheduled(fixedDelay = 1000)
    public void runPending() {
        var jobs = jdbc.query("""
                select id, user_id, source_folder_id, target_folder_id, new_folder_name, delete_bookmarks, attempts
                from folder_jobs where state = 'PENDING' order by attempts, created_at, id limit 5
                """, (row, index) -> new Pending(row.getString(1), row.getLong(2), row.getLong(3),
                row.getObject(4, Long.class), row.getString(5), row.getBoolean(6), row.getInt(7)));
        for (var job : jobs) {
            try {
                transactions.executeWithoutResult(tx -> {
                    if (!isStillPending(job)) return;
                    if (job.deleting()) {
                        folders.deleteFolder(job.userId(), job.source(), true, false);
                    } else {
                        folders.moveAndDeleteFolder(job.userId(), job.source(), job.target(), job.newName());
                    }
                    jdbc.update("update folder_jobs set state = 'SUCCEEDED' where id = ?", job.id());
                });
            } catch (IllegalArgumentException | ResourceNotFoundException | ConflictException error) {
                recordFailure(job, error.getMessage(), true);
            } catch (RuntimeException error) {
                logger.warn("收藏夾背景處理失敗，任務 {}", job.id(), error);
                recordFailure(job, "收藏夾處理失敗，請稍後再試", false);
            }
        }
    }

    private boolean isStillPending(Pending job) {
        users.lockUser(job.userId());
        return jdbc.query("""
                select attempts from folder_jobs where id = ? and state = 'PENDING' for update
                """, (row, index) -> row.getInt(1), job.id()).stream()
                .anyMatch(attempts -> attempts == job.attempts());
    }

    private void recordFailure(Pending job, String message, boolean permanent) {
        transactions.executeWithoutResult(tx -> {
            if (!isStillPending(job)) return;
            int attempts = job.attempts() + 1;
            if (permanent || attempts >= MAX_ATTEMPTS) {
                jdbc.update("update folder_jobs set state = 'FAILED', attempts = ?, error_message = ? where id = ?",
                        attempts, message, job.id());
            } else {
                jdbc.update("update folder_jobs set attempts = ? where id = ?", attempts, job.id());
            }
        });
    }
}
