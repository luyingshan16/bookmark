package com.personal.bookmark_app.data.repository

import com.personal.bookmark_app.data.local.UserPreferencesRepository
import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.api.BookmarkBatchJobStatus
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import retrofit2.HttpException

@Singleton
class BookmarkBatchStatusRepository internal constructor(
    private val currentSessionId: suspend () -> String?,
    private val fetchStatus: suspend (String) -> BookmarkBatchJobStatus
) {
    @Inject
    constructor(api: BookmarkApi, preferences: UserPreferencesRepository) : this(
        { preferences.sessionTokens()?.sessionId }, api::getBatchJob
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class Key(val sessionId: String, val jobId: String)

    private class Watch(initialStatus: BookmarkBatchJobStatus?) {
        val latest = MutableStateFlow(initialStatus?.let { Result.success(it) })
        var subscribers = 0
        lateinit var poller: Job
    }

    // 同一登入工作階段的同一任務共用一個輪詢，供畫面與通知讀取。
    private val watches = mutableMapOf<Key, Watch>()

    fun watch(
        sessionId: String,
        jobId: String,
        initialStatus: BookmarkBatchJobStatus? = null
    ): Flow<BookmarkBatchJobStatus> = flow {
        if (currentSessionId() != sessionId) throw CancellationException("Session changed")
        val key = Key(sessionId, jobId)
        val watch = synchronized(watches) {
            watches.getOrPut(key) {
                Watch(initialStatus).also { entry ->
                    entry.poller = scope.launch(start = CoroutineStart.LAZY) { poll(key, entry) }
                }
            }.also { entry ->
                entry.subscribers++
                if (initialStatus != null) entry.latest.compareAndSet(null, Result.success(initialStatus))
            }
        }
        // 同一個輪詢只會啟動一次，所有訂閱者讀取同一份狀態。
        watch.poller.start()
        try {
            watch.latest.filterNotNull().collect { emit(it.getOrThrow()) }
        } finally {
            synchronized(watches) {
                watch.subscribers--
                // 最後一個訂閱者離開後，就可以停止發送請求。
                if (watch.subscribers == 0) {
                    watches.remove(key)
                    watch.poller.cancel()
                }
            }
        }
    }

    private suspend fun poll(key: Key, watch: Watch) {
        if (watch.latest.value?.getOrNull()?.finished == true) return
        if (watch.latest.value != null) delay(1000)
        while (true) {
            val status = try {
                if (currentSessionId() != key.sessionId) {
                    watch.latest.value = Result.failure(CancellationException("Session changed"))
                    return
                }
                fetchStatus(key.jobId).also {
                    if (currentSessionId() != key.sessionId) {
                        watch.latest.value = Result.failure(CancellationException("Session changed"))
                        return
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (error is IOException || error is HttpException && (error.code() == 429 || error.code() >= 500)) {
                    delay(3000)
                    continue
                }
                watch.latest.value = Result.failure(error)
                return
            }
            watch.latest.value = Result.success(status)
            if (status.finished) return
            delay(1000)
        }
    }
}
