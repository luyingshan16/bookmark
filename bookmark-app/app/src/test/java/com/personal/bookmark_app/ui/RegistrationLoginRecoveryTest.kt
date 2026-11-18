package com.personal.bookmark_app.ui

import com.personal.bookmark_app.data.remote.api.AuthApi
import com.personal.bookmark_app.data.remote.dto.request.auth.LoginRequest
import com.personal.bookmark_app.data.remote.dto.request.auth.RegisterRequest
import com.personal.bookmark_app.data.remote.dto.response.auth.AuthResponse
import com.personal.bookmark_app.data.remote.dto.response.auth.MessageResponse
import com.personal.bookmark_app.data.repository.auth.ApiAuthRepository
import com.personal.bookmark_app.ui.viewmodel.AuthEvent
import com.personal.bookmark_app.ui.viewmodel.AuthViewModel
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

class RegistrationLoginRecoveryTest {
    @Test
    fun networkFailureAfterRegistrationUsesManualLogin() {
        assertManualRecovery(IOException("offline"), "帳號已建立，請使用原密碼登入")
    }

    @Test
    fun throttledAutoLoginAsksUserToWaitBeforeLogin() {
        assertManualRecovery(httpError(429), "帳號已建立，請稍後使用原密碼登入")
    }

    @Test
    fun failedRegistrationStaysOnVerificationAndDoesNotCallLogin() = withFixture { fixture ->
        fixture.registrationError = httpError(400)
        fixture.verify()
        assertNotNull(fixture.vm.verifyEmailUiState.value.errorMessage)
        assertEquals("123456", fixture.vm.verifyEmailUiState.value.verificationCode)
        assertEquals(0, fixture.loginCalls)
        assertEquals(listOf(AuthEvent.NavigateToVerifyEmail), fixture.events)
    }

    @Test
    fun successfulAutoLoginSavesTokensAndAuthenticates() = withFixture { fixture ->
        fixture.verify()
        assertEquals(1, fixture.loginCalls)
        assertEquals(1, fixture.tokenWrites)
        assertEquals(AuthEvent.Authenticated, fixture.events.last())
    }

    private fun assertManualRecovery(error: Throwable, message: String) = withFixture { fixture ->
        fixture.loginError = error
        fixture.verify()
        assertEquals(AuthEvent.RegistrationRequiresLogin(message), fixture.events.last())
        assertEquals("user@example.com", fixture.vm.loginUiState.value.email)
        assertTrue(fixture.vm.loginUiState.value.password.isEmpty())
        assertTrue(fixture.vm.registerUiState.value.password.isEmpty())
        assertFalse(fixture.vm.verifyEmailUiState.value.isVerifyEnabled)
        assertEquals(0, fixture.tokenWrites)

        fixture.vm.verifyEmail()
        fixture.vm.onLoginPasswordChange("Bookmark123")
        fixture.vm.login()
        assertEquals(1, fixture.registerCalls)
        assertEquals(2, fixture.loginCalls)
        assertEquals(1, fixture.tokenWrites)
        assertEquals(AuthEvent.Authenticated, fixture.events.last())
    }

    private fun withFixture(block: (Fixture) -> Unit) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            block(Fixture(scope))
        } finally {
            scope.cancel()
        }
    }

    private class Fixture(scope: CoroutineScope) {
        var registrationError: Throwable? = null
        var loginError: Throwable? = null
        var loginCalls = 0
        var registerCalls = 0
        var tokenWrites = 0
        val events = mutableListOf<AuthEvent>()
        private val api = Proxy.newProxyInstance(AuthApi::class.java.classLoader, arrayOf(AuthApi::class.java)) { _, method, args ->
            val response: Result<Any> = when (method.name) {
                "sendRegistrationCode" -> Result.success(MessageResponse("sent"))
                "register" -> {
                    registerCalls++
                    assertEquals(RegisterRequest("user@example.com", "123456", "Bookmark123"), args[0])
                    registrationError?.let { Result.failure(it) } ?: Result.success(MessageResponse("created"))
                }
                "login" -> {
                    loginCalls++
                    assertEquals(LoginRequest("user@example.com", "Bookmark123"), args[0])
                    val failure = loginError
                    loginError = null
                    failure?.let { Result.failure(it) }
                        ?: Result.success(AuthResponse(1, "user@example.com", "access", "refresh"))
                }
                else -> error("Unexpected API call: ${method.name}")
            }
            @Suppress("UNCHECKED_CAST")
            (args.last() as Continuation<Any>).resumeWith(response)
            COROUTINE_SUSPENDED
        } as AuthApi
        val vm = AuthViewModel(ApiAuthRepository(api, { _, _ -> tokenWrites++ }, { null }), { 1000L }, scope)

        init {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { vm.authEvent.collect { events += it } }
        }

        fun verify() {
            vm.onRegisterEmailChange("user@example.com")
            vm.onRegisterPasswordChange("Bookmark123")
            vm.onConfirmPasswordChange("Bookmark123")
            vm.register()
            vm.onVerificationCodeChange("123456")
            vm.verifyEmail()
        }
    }

    private fun httpError(code: Int) = HttpException(Response.error<Unit>(code, "".toResponseBody()))
}
