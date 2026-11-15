package com.personal.bookmark_app.data.repository

import com.personal.bookmark_app.data.local.UserPreferencesRepository
import com.personal.bookmark_app.data.remote.api.FolderApi
import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FolderChanges @Inject constructor(
    private val api: FolderApi,
    private val preferences: UserPreferencesRepository
) {
    private data class Snapshot(val sessionId: String, val revision: Long, val folders: List<FolderResponse>)

    private val revision = AtomicLong()
    private val loadMutex = Mutex()
    private var snapshot: Snapshot? = null

    suspend fun getFolders(): List<FolderResponse> = loadMutex.withLock {
        val sessionId = checkNotNull(preferences.sessionTokens()?.sessionId)
        val version = revision.get()
        snapshot?.takeIf { it.sessionId == sessionId && it.revision == version }?.folders?.let {
            return@withLock it
        }
        snapshot = null
        val folders = api.getFolders()
        check(preferences.sessionTokens()?.sessionId == sessionId)
        if (revision.get() == version) snapshot = Snapshot(sessionId, version, folders)
        folders
    }

    private val _changes = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val changes = _changes.asSharedFlow()

    fun notifyChanged() {
        revision.incrementAndGet()
        _changes.tryEmit(Unit)
    }
}
