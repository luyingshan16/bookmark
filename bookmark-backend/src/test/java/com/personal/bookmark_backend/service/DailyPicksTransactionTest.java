package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.entity.Bookmark;
import com.personal.bookmark_backend.entity.DailyPickSelection;
import com.personal.bookmark_backend.repository.DailyPickSelectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class DailyPicksTransactionTest extends BookmarkTransactionTestSupport {
    @Autowired
    DailyPickSelectionService picks;
    @Autowired
    DailyPickSelectionRepository selections;

    @Test
    void uncheckedDoesNotTouchSelections() {
        picks.select(userId, false, null);
        var ids = selections.findAllByUserIdOrderByPositionAsc(userId)
                .stream()
                .map(DailyPickSelection::getId)
                .toList();
        create(false);
        assertEquals(
                ids,
                selections.findAllByUserIdOrderByPositionAsc(userId)
                        .stream()
                        .map(DailyPickSelection::getId)
                        .toList()
        );
    }

    @Test
    void currentDailyPicksUseStoredOrderAndDetectMissingSelections() {
        assertTrue(picks.readCurrent(userId).isEmpty());
        var generated = picks.select(userId, false, null);
        assertEquals(generated.picks().stream().map(Bookmark::getId).toList(),
                picks.readCurrent(userId).orElseThrow().picks().stream().map(Bookmark::getId).toList());
        service.deleteBookmarkBatch(userId, List.of(generated.picks().get(0).getId()));
        assertTrue(picks.readCurrent(userId).isEmpty());
    }

    @Test
    void concurrentGetsAndRefreshesRemainThreeUniqueRows() throws Exception {
        var executor = Executors.newFixedThreadPool(4);
        try {
            var tasks = new ArrayList<Callable<Void>>();
            for (int i = 0; i < 12; i++) {
                final boolean refresh = i % 2 == 0;
                tasks.add(() -> {
                    picks.select(userId, refresh, null);
                    return null;
                });
            }
            for (var result : executor.invokeAll(tasks)) result.get(20, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        var rows = selections.findAllByUserIdOrderByPositionAsc(userId);
        assertEquals(3, rows.size());
        assertEquals(3, rows.stream().map(DailyPickSelection::getBookmarkId).distinct().count());
        assertEquals(List.of(1, 2, 3), rows.stream().map(DailyPickSelection::getPosition).toList());
    }

    @Test
    void failedOuterTransactionRollsBackBookmarkAndSelection() {
        picks.select(userId, false, null);
        long count = books.countByUserId(userId);
        var before = selections.findAllByUserIdOrderByPositionAsc(userId)
                .stream()
                .map(DailyPickSelection::getBookmarkId)
                .toList();
        assertThrows(
                RuntimeException.class,
                () -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                    create(true);
                    var duplicate = new DailyPickSelection();
                    duplicate.setUserId(userId);
                    duplicate.setBookmarkId(before.get(0));
                    duplicate.setPosition(1);
                    duplicate.setSelectionDate(DailyPickSelectionService.selectionDateAt(java.time.Instant.now()));
                    selections.saveAndFlush(duplicate);
                })
        );
        assertEquals(count, books.countByUserId(userId));
        assertEquals(
                before,
                selections.findAllByUserIdOrderByPositionAsc(userId)
                        .stream()
                        .map(DailyPickSelection::getBookmarkId)
                        .toList()
        );
    }

    @Test
    void deletingSelectedBookmarkRemovesRelationAndFillsGap() {
        var previous = picks.select(userId, false, null).picks();
        service.deleteBookmark(userId, previous.get(0).getId());
        assertEquals(2, selections.findAllByUserIdOrderByPositionAsc(userId).size());
        var next = picks.select(userId, false, null).picks();
        assertEquals(3, next.size());
        assertTrue(next.stream().anyMatch(b -> b.getId().equals(previous.get(1).getId())));
    }
}
