package com.personal.bookmark_backend.controller;

import com.personal.bookmark_backend.exception.GlobalExceptionHandler;
import com.personal.bookmark_backend.mapper.FolderMapper;
import com.personal.bookmark_backend.mapper.UserMapper;
import com.personal.bookmark_backend.service.FolderJobs;
import com.personal.bookmark_backend.service.FolderService;
import com.personal.bookmark_backend.service.GoogleAuthService;
import com.personal.bookmark_backend.service.UserProfileService;
import org.hibernate.exception.ConstraintViolationException;
import org.hibernate.exception.ConstraintViolationException.ConstraintKind;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.sql.SQLException;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ApiErrorContractTest {
    private final FolderService folders = mock(FolderService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final UsernamePasswordAuthenticationToken principal = new UsernamePasswordAuthenticationToken(1L, null);
    private final org.springframework.test.web.servlet.MockMvc mvc = MockMvcBuilders.standaloneSetup(
            new FolderController(folders, mock(FolderMapper.class), mock(FolderJobs.class)),
            new UserController(profiles, mock(GoogleAuthService.class), mock(UserMapper.class)),
            new UploadErrorRoutes())
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void malformedPageMissingParameterAndMissingFileReturn400() throws Exception {
        mvc.perform(get("/api/folders/page").param("page", "abc").principal(principal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("請求參數格式不正確"));
        mvc.perform(get("/test/required"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少必填參數"));
        mvc.perform(multipart("/api/users/me/avatar").principal(principal))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少必填的上傳內容"));
        verifyNoInteractions(folders, profiles);
    }

    @Test
    void uploadLimitExceptionReturns413() throws Exception {
        mvc.perform(get("/test/upload-limit"))
                .andExpect(status().is(413))
                .andExpect(jsonPath("$.message").value("檔案或上傳請求超過大小限制"));
    }

    @Test
    void onlyKnownFolderNameUniqueConstraintsBecome409() throws Exception {
        for (String name : new String[]{"folders.uq_folder_name", "uk_folders_user_name", "PUBLIC.UK_FOLDERS_USER_NAME_INDEX_7"}) {
            doThrow(dbFailure(name, ConstraintKind.UNIQUE)).when(folders).createFolder(1L, "Trip");
            mvc.perform(post("/api/folders").principal(principal).contentType("application/json")
                            .content("{\"name\":\"Trip\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("收藏夾名稱已經存在"));
        }
    }

    @Test
    void unknownIntegrityErrorsRemain500WithoutDatabaseDetails() throws Exception {
        when(folders.createFolder(1L, "Trip")).thenThrow(dbFailure("uq_default_folder", ConstraintKind.UNIQUE));
        when(folders.createFolder(1L, "Other")).thenThrow(dbFailure("uq_folder_name", ConstraintKind.FOREIGN_KEY));
        for (String name : new String[]{"Trip", "Other"}) {
            mvc.perform(post("/api/folders").principal(principal).contentType("application/json")
                            .content("{\"name\":\"" + name + "\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.message").value("伺服器發生未預期的錯誤，請稍後再試"));
        }
    }

    private DataIntegrityViolationException dbFailure(String constraint, ConstraintKind kind) {
        return new DataIntegrityViolationException("internal SQL details",
                new ConstraintViolationException("internal SQL details", new SQLException(), kind, constraint));
    }

    @RestController
    static class UploadErrorRoutes {
        @GetMapping("/test/required")
        String required(@RequestParam("value") String value) {
            return value;
        }

        @GetMapping("/test/upload-limit")
        void uploadLimit() {
            throw new MaxUploadSizeExceededException(10);
        }
    }
}
