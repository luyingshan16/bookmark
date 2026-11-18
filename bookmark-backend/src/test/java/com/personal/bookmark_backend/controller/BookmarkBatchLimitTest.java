package com.personal.bookmark_backend.controller;

import com.personal.bookmark_backend.exception.GlobalExceptionHandler;
import com.personal.bookmark_backend.service.BookmarkBatchJobs;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Collections;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookmarkBatchLimitTest {
    @Test
    void rejectsInvalidListsBeforeCallingServiceForBothOperations() throws Exception {
        var jobs = mock(BookmarkBatchJobs.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BookmarkBatchJobController(jobs))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        for (String suffix : new String[]{"", "/delete"}) {
            for (String ids : new String[]{"null", "[]", "[null]", "[0]", "[-1]",
                    "[" + String.join(",", Collections.nCopies(50_001, "1")) + "]"}) {
                mvc.perform(post("/api/bookmarks/move-jobs/00000000-0000-0000-0000-000000000001" + suffix)
                        .principal(new UsernamePasswordAuthenticationToken(1L, null))
                        .contentType("application/json")
                        .content("{\"sourceFolderId\":1,\"targetFolderId\":2,\"ids\":" + ids + "}"))
                        .andExpect(status().isBadRequest());
            }
        }
        verifyNoInteractions(jobs);
    }

    @Test
    void acceptsOneAndMaximumIncludingRepeatedIds() throws Exception {
        var jobs = mock(BookmarkBatchJobs.class);
        var mvc = MockMvcBuilders.standaloneSetup(new BookmarkBatchJobController(jobs)).build();
        for (String suffix : new String[]{"", "/delete"}) {
            for (int count : new int[]{1, 50_000}) {
                mvc.perform(post("/api/bookmarks/move-jobs/00000000-0000-0000-0000-000000000001" + suffix)
                        .principal(new UsernamePasswordAuthenticationToken(1L, null))
                        .contentType("application/json")
                        .content("{\"sourceFolderId\":1,\"targetFolderId\":2,\"ids\":["
                                + String.join(",", Collections.nCopies(count, "1")) + "]}"))
                        .andExpect(status().isAccepted());
            }
        }
        verify(jobs, times(2)).submitDelete(eq(1L), anyString(), eq(1L), anyList());
        verify(jobs, times(2)).submit(eq(1L), anyString(), eq(1L), eq(2L), anyList());
    }
}
