package com.personal.bookmark_app.ui

import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.dto.request.bookmark.CreateBookmarkRequest
import com.personal.bookmark_app.ui.viewmodel.BookmarkSubmission
import com.personal.bookmark_app.ui.viewmodel.bookmarkSubmission
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test
import retrofit2.http.Header

class BookmarkSubmissionTest {
    private val request = CreateBookmarkRequest("https://example.org/", "Title", null, "OTHER", listOf("tag"))

    @Test
    fun restoresTheSameKeyForAnIdenticalRetry() {
        val first = bookmarkSubmission(null, 1, request)
        val restored = BookmarkSubmission(first.fingerprint, first.requestId)
        assertEquals(first, bookmarkSubmission(restored, 1, request.copy()))
        assertEquals(first.requestId, UUID.fromString(first.requestId).toString())
    }

    @Test
    fun changedContentDestinationAndNewDraftHaveNewKeys() {
        val first = bookmarkSubmission(null, 1, request)
        assertNotEquals(first.requestId, bookmarkSubmission(first, 2, request).requestId)
        assertNotEquals(first.requestId, bookmarkSubmission(first, 1, request.copy(note = "changed")).requestId)
        assertNotEquals(first.requestId, bookmarkSubmission(null, 1, request).requestId)
    }

    @Test
    fun retrofitSendsTheIdempotencyHeader() {
        val create = BookmarkApi::class.java.methods.single { it.name == "createBookmark" }
        val header = create.parameterAnnotations.flatten().filterIsInstance<Header>().single()
        assertEquals("Idempotency-Key", header.value)
    }
}
