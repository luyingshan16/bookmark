package com.personal.bookmark_app.ui.viewmodel

import com.google.gson.Gson
import com.personal.bookmark_app.data.remote.dto.request.bookmark.CreateBookmarkRequest
import java.security.MessageDigest
import java.util.UUID

internal data class BookmarkSubmission(val fingerprint: String, val requestId: String)

private val submissionJson = Gson()

internal fun bookmarkSubmission(
    previous: BookmarkSubmission?,
    folderId: Long,
    request: CreateBookmarkRequest
): BookmarkSubmission {
    val fields = listOf(folderId, request.url, request.title, request.content, request.note,
        request.source, request.imageUrl, request.tags, request.addToDailyPicks)
    val fingerprint = MessageDigest.getInstance("SHA-256")
        .digest(submissionJson.toJson(fields).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
    return previous?.takeIf { it.fingerprint == fingerprint }
        ?: BookmarkSubmission(fingerprint, UUID.randomUUID().toString())
}
