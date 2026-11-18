package com.personal.bookmark_backend.service;

import com.personal.bookmark_backend.controller.AuthController;
import com.personal.bookmark_backend.entity.User;
import com.personal.bookmark_backend.exception.GlobalExceptionHandler;
import com.personal.bookmark_backend.mapper.AuthMapperImpl;
import com.personal.bookmark_backend.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringJUnitConfig(PasswordResetTransactionTest.Config.class)
@TestPropertySource(properties = {
        "spring.mail.username=test@example.com",
        "jwt.secret=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
})
class LoginPrivacyTest {
    private static final String PASSWORD = "TestPassword123";
    private static final String INVALID_LOGIN =
            "Email 或密碼錯誤。若你原本使用 Google 建立帳號，請選擇 Google 登入";

    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(
                auth, mock(GoogleAuthService.class), new AuthMapperImpl(), new PasswordResetRateLimiter()))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void unknownWrongPasswordGoogleOnlyAndUnverifiedReturnSameFailureBody() throws Exception {
        String expected = failedLogin(newEmail());
        assertEquals(expected, failedLogin(account(true, true).getEmail()));
        assertEquals(expected, failedLogin(account(false, true).getEmail()));
        assertEquals(expected, failedLogin(account(true, false).getEmail()));
    }

    @Test
    void correctPasswordStillSignsInAndOnlyThenRevealsVerificationRequirement() throws Exception {
        User verified = account(true, true);
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(payload(verified.getEmail(), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(verified.getId()))
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.refreshToken").isNotEmpty());
        mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(payload(account(true, false).getEmail(), PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("請先完成 Email 驗證"));
    }

    private String failedLogin(String email) throws Exception {
        var response = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(payload(email, "WrongPassword123")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(INVALID_LOGIN))
                .andReturn().getResponse();
        // 回應時間戳本來就不同，其餘所有欄位必須一致。
        var body = new JsonMapper().readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        ((tools.jackson.databind.node.ObjectNode) body).remove("timestamp");
        return body.toString();
    }

    private User account(boolean password, boolean verified) {
        var user = new User();
        user.setEmail(newEmail());
        user.setPasswordHash(password ? encoder.encode(PASSWORD) : null);
        user.setEmailVerifiedAt(verified ? LocalDateTime.now(ZoneOffset.UTC) : null);
        return users.save(user);
    }

    private String newEmail() {
        return UUID.randomUUID() + "@example.com";
    }

    private String payload(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }
}
