package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.repository.FolderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookmarkBatchInputTest {
    @Test
    void invalidInputCannotLockQueryOrWriteEvenWhenCalledWithoutController() {
        var jdbc = mock(JdbcTemplate.class);
        var tx = mock(TransactionTemplate.class);
        var bookmarks = mock(BookmarkService.class);
        var folders = mock(FolderRepository.class);
        var users = mock(DailyPickSelectionService.class);
        var jobs = new BookmarkBatchJobs(jdbc, tx, bookmarks, folders, users);
        List<List<Long>> invalid = Arrays.asList(null, List.of(), Arrays.asList((Long) null),
                List.of(0L), List.of(-1L), Collections.nCopies(50_001, 1L));
        for (var ids : invalid) {
            assertThrows(IllegalArgumentException.class, () -> jobs.submitDelete(1L, "test", 1L, ids));
            assertThrows(IllegalArgumentException.class, () -> jobs.submit(1L, "test", 1L, 2L, ids));
        }
        verifyNoInteractions(jdbc, tx, bookmarks, folders, users);
    }
}
