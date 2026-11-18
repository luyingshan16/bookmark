package com.personal.bookmark_backend.controller;

import com.personal.bookmark_backend.exception.GlobalExceptionHandler;
import com.personal.bookmark_backend.service.BookmarkResponseAssembler;
import com.personal.bookmark_backend.service.BookmarkService;
import com.personal.bookmark_backend.service.PasswordResetRateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BookmarkUpdateContractTest {
    private final BookmarkService service = mock(BookmarkService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new BookmarkController(service,
            mock(BookmarkResponseAssembler.class), mock(PasswordResetRateLimiter.class)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    private String body(String fields) {
        return """
                {"url":"https://example.org","title":"title","source":"OTHER","note":null,"tags":[]%s}
                """.formatted(fields);
    }

    @Test
    void forwardsMaskAndExplicitNull() throws Exception {
        mvc.perform(patch("/api/bookmarks/2").principal(new UsernamePasswordAuthenticationToken(1L, null))
                .contentType("application/json").content(body(",\"updateFields\":[\"note\"]")))
                .andExpect(status().isOk());
        verify(service).updateBookmark(1L, 2L, "https://example.org", "title", null, null,
                "OTHER", null, List.of(), List.of("note"));
    }

    @Test
    void absentMaskKeepsLegacyContract() throws Exception {
        mvc.perform(patch("/api/bookmarks/2").principal(new UsernamePasswordAuthenticationToken(1L, null))
                .contentType("application/json").content(body(""))).andExpect(status().isOk());
        verify(service).updateBookmark(1L, 2L, "https://example.org", "title", null, null,
                "OTHER", null, List.of(), null);
    }

    @Test
    void emptyMaskIsBadRequest() throws Exception {
        mvc.perform(patch("/api/bookmarks/2").principal(new UsernamePasswordAuthenticationToken(1L, null))
                .contentType("application/json").content(body(",\"updateFields\":[]")))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void invalidMaskFromServiceIsBadRequest() throws Exception {
        when(service.updateBookmark(1L, 2L, "https://example.org", "title", null, null,
                "OTHER", null, List.of(), List.of("folderId"))).thenThrow(new IllegalArgumentException("invalid mask"));
        mvc.perform(patch("/api/bookmarks/2").principal(new UsernamePasswordAuthenticationToken(1L, null))
                .contentType("application/json").content(body(",\"updateFields\":[\"folderId\"]")))
                .andExpect(status().isBadRequest());
    }
}
