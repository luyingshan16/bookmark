package com.personal.bookmark_app.data.remote

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val defaultAvatarPath = Regex(
    "/uploads/avatars/[0-9a-f]{8}-(?:[0-9a-f]{4}-){3}[0-9a-f]{12}\\.(?:jpg|png|webp)"
)

internal fun resolveAvatarUrl(avatarUrl: String?, apiBaseUrl: String): String? {
    val url = avatarUrl?.toHttpUrlOrNull() ?: return null
    // The backend uses this origin when BOOKMARK_PUBLIC_BASE_URL is unset.
    if (url.scheme == "http" && url.host == "localhost" && url.port == 8080 &&
        defaultAvatarPath.matches(url.encodedPath) && url.query == null && url.fragment == null &&
        url.username.isEmpty() && url.password.isEmpty()
    ) {
        return apiBaseUrl.toHttpUrl().resolve(url.encodedPath.removePrefix("/"))?.toString()
    }
    return avatarUrl
}
