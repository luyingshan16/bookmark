package com.personal.bookmark_app.data.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AvatarUrlTest {
    private val avatarPath = "uploads/avatars/11111111-1111-1111-1111-111111111111.jpg"
    private val apiBaseUrl = "https://api.example.com/bookmark/"

    @Test
    fun resolvesDefaultBackendAvatarAgainstApiBaseIncludingProxyPrefix() {
        assertEquals(
            apiBaseUrl + avatarPath,
            resolveAvatarUrl("http://localhost:8080/$avatarPath", apiBaseUrl)
        )
        assertEquals(
            "http://10.0.2.2:8080/$avatarPath",
            resolveAvatarUrl("http://localhost:8080/$avatarPath", "http://10.0.2.2:8080/")
        )
    }

    @Test
    fun preservesExplicitPublicAvatarUrl() {
        val avatarUrl = "https://images.example.com/$avatarPath"
        assertEquals(avatarUrl, resolveAvatarUrl(avatarUrl, apiBaseUrl))
    }

    @Test
    fun acceptsMissingAvatar() {
        assertNull(resolveAvatarUrl(null, apiBaseUrl))
    }
}
