package com.personal.bookmark_app.data.repository

import com.personal.bookmark_app.data.remote.api.BookmarkBatchJobStatus
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class BookmarkBatchStatusRepositoryTest {
    @Test
    fun simultaneousObserversShareOneRequest() = runBlocking {
        val requests = AtomicInteger()
        val response = CompletableDeferred<BookmarkBatchJobStatus>()
        val repository = BookmarkBatchStatusRepository({ "session" }) {
            requests.incrementAndGet()
            response.await()
        }
        val screen = async(start = CoroutineStart.UNDISPATCHED) { repository.watch("session", "job").first() }
        val notification = async(start = CoroutineStart.UNDISPATCHED) { repository.watch("session", "job").first() }

        val finished = BookmarkBatchJobStatus("job", total = 3, succeeded = 3, failed = 0, deleting = true)
        response.complete(finished)
        withTimeout(5000) {
            assertEquals(finished, screen.await())
            assertEquals(finished, notification.await())
        }
        assertEquals(1, requests.get())
    }
}
