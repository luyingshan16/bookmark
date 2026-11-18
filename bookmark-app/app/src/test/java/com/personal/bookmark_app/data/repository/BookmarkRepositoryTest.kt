package com.personal.bookmark_app.data.repository

import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.dto.request.bookmark.MoveBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.request.bookmark.UpdateBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkResponse
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BookmarkRepositoryTest {
    private val bookmark = BookmarkResponse(1, 2, "https://example.org/", "Title", null, null,
        "OTHER", null, emptyList(), null, null)

    @Test
    fun eachSuccessfulWriteNotifiesOnceAfterApiCall() = runBlocking {
        val events = mutableListOf<String>()
        val api = fakeApi { name, args ->
            events += name
            assertEquals(bookmark.id, args[0])
            when (name) {
                "updateBookmark" -> {
                    val request = args[1] as UpdateBookmarkRequest
                    assertEquals("New note", request.note)
                    assertEquals(listOf("note"), request.updateFields)
                }
                "moveBookmark" -> assertEquals(MoveBookmarkRequest(3), args[1])
            }
            Result.success(if (name == "deleteBookmark") Unit else bookmark)
        }
        val repository = BookmarkRepository(api) { events += "changed" }

        repository.updateBookmark(bookmark, null, "New note", null)
        repository.deleteBookmark(bookmark.id)
        repository.moveBookmark(bookmark.id, 3)

        assertEquals(listOf("updateBookmark", "changed", "deleteBookmark", "changed", "moveBookmark", "changed"), events)
    }

    @Test
    fun transportFailureAndCancellationPropagateWithoutNotifying() = runBlocking {
        var notifications = 0
        for (failure in listOf(IOException("offline"), CancellationException("cancelled"))) {
            val repository = BookmarkRepository(fakeApi { _, _ -> Result.failure(failure) }) { notifications++ }
            val writes: List<suspend () -> Unit> = listOf(
                { repository.updateBookmark(bookmark, "New title", null, null) },
                { repository.deleteBookmark(bookmark.id) },
                { repository.moveBookmark(bookmark.id, 3) }
            )
            for (write in writes) {
                assertSame(failure, runCatching { write() }.exceptionOrNull())
            }
        }
        assertEquals(0, notifications)
    }

    private fun fakeApi(reply: (String, Array<out Any?>) -> Result<Any>): BookmarkApi =
        Proxy.newProxyInstance(BookmarkApi::class.java.classLoader, arrayOf(BookmarkApi::class.java)) { _, method, args ->
            // Complete the suspend call through its continuation as Retrofit does.
            @Suppress("UNCHECKED_CAST")
            val continuation = args.last() as Continuation<Any>
            continuation.resumeWith(reply(method.name, args))
            COROUTINE_SUSPENDED
        } as BookmarkApi
}
