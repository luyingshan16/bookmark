package com.personal.bookmark_app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.SavedStateHandle
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import com.personal.bookmark_app.data.remote.PageLoader
import com.personal.bookmark_app.data.remote.api.BatchDeleteRequest
import com.personal.bookmark_app.data.local.UserPreferencesRepository
import com.personal.bookmark_app.data.remote.api.BookmarkBatchMoveRequest
import com.personal.bookmark_app.data.remote.api.BookmarkBatchDeleteRequest
import com.personal.bookmark_app.data.remote.api.BookmarkBatchJobStatus
import com.personal.bookmark_app.data.repository.BookmarkBatchNotificationService
import com.personal.bookmark_app.data.repository.BookmarkBatchStatusRepository
import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.api.FolderApi
import com.personal.bookmark_app.data.remote.api.FolderNameRequest
import com.personal.bookmark_app.data.remote.dto.request.bookmark.toUpdateRequest
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkResponse
import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse
import com.personal.bookmark_app.data.repository.FolderChanges
import com.personal.bookmark_app.ui.screen.folderbookmark.BookmarkBatchAction
import com.personal.bookmark_app.ui.screen.folderbookmark.BookmarkBatchProgress
import com.personal.bookmark_app.ui.screen.folderbookmark.FolderBookmarkUiState
import com.personal.bookmark_app.ui.screen.folderbookmark.JobResultReceipt
import com.personal.bookmark_app.ui.utils.MAX_BOOKMARK_TITLE_LENGTH
import com.personal.bookmark_app.ui.utils.MAX_TAG_NAME_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID

@HiltViewModel
@OptIn(ExperimentalCoroutinesApi::class)
class FolderBookmarkViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookmarkApi: BookmarkApi,
    private val folderApi: FolderApi,
    private val folderChanges: FolderChanges,
    private val batchStatuses: BookmarkBatchStatusRepository,
    private val preferences: UserPreferencesRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val folderId = checkNotNull(savedStateHandle.get<Long>("folderId"))
    private val _state = MutableStateFlow(FolderBookmarkUiState())
    val uiState = _state.asStateFlow()

    private data class FolderFilter(
        val query: String = "",
        val tags: Set<String> = emptySet(),
        val newestFirst: Boolean = true
    )

    private var filter = FolderFilter()
    private var filterJob: Job? = null
    private val pages = MutableStateFlow(createPages(filter))
    val bookmarks = pages.flatMapLatest { it.flow }.cachedIn(viewModelScope)

    init {
        loadFolderDetails()
    }

    private fun createPages(filter: FolderFilter) = PageLoader { page, size ->
        bookmarkApi.getFolderBookmarkPage(
            folderId,
            filter.query,
            filter.tags.toList(),
            filter.newestFirst,
            page,
            size
        )
            .also {
                if (this.filter === filter) _state.value =
                    _state.value.copy(totalCount = it.totalElements)
            }
    }

    fun onQueryChange(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
        scheduleFilter()
    }

    fun toggleTag(tag: String) {
        val selected = _state.value.selectedTags
        _state.value =
            _state.value.copy(selectedTags = if (tag in selected) selected - tag else selected + tag)
        scheduleFilter()
    }

    fun clearTags() {
        _state.value = _state.value.copy(selectedTags = emptySet())
        scheduleFilter()
    }

    fun toggleSortOrder() {
        _state.value = _state.value.copy(isNewestFirst = !_state.value.isNewestFirst)
        scheduleFilter()
    }

    private fun scheduleFilter() {
        filterJob?.cancel()
        val state = _state.value
        val nextFilter = FolderFilter(state.searchQuery, state.selectedTags, state.isNewestFirst)
        if (nextFilter == filter) return
        filterJob = viewModelScope.launch {
            delay(250)
            filter = nextFilter
            _state.value = _state.value.copy(totalCount = 0)
            pages.value = createPages(nextFilter)
        }
    }

    private fun updateAvailableTags(tags: List<String>) {
        val selected = _state.value.selectedTags
        val remaining = selected.intersect(tags.toSet())
        _state.value = _state.value.copy(tags = tags, selectedTags = remaining)
        if (remaining != selected) scheduleFilter()
    }

    fun refresh() {
        if (_state.value.isLoading || _state.value.isMutating) return
        pages.value.refresh()
        loadFolderDetails()
    }

    private fun loadFolderDetails() {
        _state.value = _state.value.copy(isLoading = true, error = null)
        viewModelScope.launch {
            try {
                coroutineScope {
                    val folder = async { folderApi.getFolder(folderId) }
                    val tags = async { folderApi.getTags(folderId).map { it.name }.sorted() }
                    val name = folder.await().name
                    val availableTags = tags.await()
                    _state.value = _state.value.copy(folderName = name)
                    updateAvailableTags(availableTags)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = "載入收藏夾失敗，請重試")
            } finally {
                _state.value = _state.value.copy(isLoading = false)
            }
        }
    }

    fun loadMoveFolders(onLoaded: () -> Unit) = mutate {
        _state.value = _state.value.copy(folders = folderChanges.getFolders())
        onLoaded()
    }

    fun isBookmarkFilterApplied(): Boolean {
        val state = _state.value
        return filter.query == state.searchQuery && filter.tags == state.selectedTags &&
                filter.newestFirst == state.isNewestFirst
    }

    fun getMatchingIds(onLoaded: (Set<String>) -> Unit) = mutate {
        val state = _state.value
        val ids = bookmarkApi.getMatchingFolderBookmarkIds(
            folderId,
            state.searchQuery,
            state.selectedTags.toList()
        )
        if (_state.value.searchQuery == state.searchQuery && _state.value.selectedTags == state.selectedTags) {
            onLoaded(ids.map { it.toString() }.toSet())
        }
    }

    private fun mutate(action: suspend () -> Unit) {
        if (_state.value.isLoading || _state.value.isMutating) return
        _state.value = _state.value.copy(isMutating = true, error = null, batchProgress = null)
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(error = "操作失敗，未完成的收藏已保留，請重試")
            } finally {
                _state.value = _state.value.copy(
                    isMutating = false,
                    batchProgress = _state.value.batchProgress?.takeIf { it.finished }
                )
            }
        }
    }

    fun update(
        old: BookmarkResponse,
        title: String? = null,
        note: String? = null,
        tags: List<String>? = null
    ) {
        if (title != null && (title.isBlank() || title.trim().length > MAX_BOOKMARK_TITLE_LENGTH) ||
            tags != null && tags.any { it.isBlank() || it.length > MAX_TAG_NAME_LENGTH }
        ) {
            _state.value = _state.value.copy(
                error = "標題須為 1–$MAX_BOOKMARK_TITLE_LENGTH 字，標籤須為 1–$MAX_TAG_NAME_LENGTH 字"
            )
            return
        }
        mutate {
            bookmarkApi.updateBookmark(old.id, old.toUpdateRequest(title?.trim(), note, tags))
            refreshAfterMutation()
        }
    }

    suspend fun consumeBatchResult(result: BookmarkBatchProgress) {
        result.resultReceipt?.let { receipt ->
            preferences.markJobResultShown(receipt.sessionId, receipt.jobId)
        }
        if (_state.value.batchProgress === result && result.finished) {
            _state.value = _state.value.copy(batchProgress = null)
        }
    }

    fun delete(ids: List<String>, onRemoved: (Set<String>) -> Unit, onSuccess: () -> Unit = {}) = mutate {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return@mutate

        var progress = BookmarkBatchProgress(BookmarkBatchAction.DELETE, total = distinctIds.size)
        _state.value = _state.value.copy(batchProgress = progress)
        val removed = mutableSetOf<String>()
        for (batch in distinctIds.chunked(100)) {
            try {
                val request = BatchDeleteRequest(batch.map(String::toLong))
                val succeeded = bookmarkApi.deleteBookmarkBatch(request).succeededIds
                    .map(Long::toString).toSet()
                batch.filterTo(removed) { it in succeeded }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 保留這一批供重試，並繼續處理後續批次。
            }
            progress = progress.copy(processed = progress.processed + batch.size, succeeded = removed.size)
            _state.value = _state.value.copy(batchProgress = progress)
        }
        onRemoved(removed)
        if (progress.failed == 0) onSuccess()
        if (removed.isNotEmpty()) refreshAfterMutation()
    }

    fun move(
        ids: List<String>,
        destination: Long,
        onRemoved: (Set<String>) -> Unit,
        onSuccess: () -> Unit
    ) = mutate {
        if (destination == folderId || _state.value.folders.none { it.id == destination }) return@mutate
        trackBatch(BookmarkBatchAction.MOVE, ids, onRemoved, onSuccess) { jobId, selected ->
            bookmarkApi.submitBatchMove(jobId, BookmarkBatchMoveRequest(selected, folderId, destination))
        }
    }

    fun deleteBatch(ids: List<String>, onRemoved: (Set<String>) -> Unit, onSuccess: () -> Unit) = mutate {
        trackBatch(BookmarkBatchAction.DELETE, ids, onRemoved, onSuccess) { jobId, selected ->
            bookmarkApi.submitBatchDelete(jobId, BookmarkBatchDeleteRequest(folderId, selected))
        }
    }

    private suspend fun trackBatch(
        action: BookmarkBatchAction,
        ids: List<String>,
        onRemoved: (Set<String>) -> Unit,
        onSuccess: () -> Unit,
        submit: suspend (String, List<Long>) -> BookmarkBatchJobStatus
    ) {
        val selected = ids.distinct()
        if (selected.isEmpty()) return
        val sessionId = checkNotNull(preferences.sessionTokens()?.sessionId)
        val jobId = UUID.randomUUID().toString()
        val selectedIds = selected.map(String::toLong)
        val submitted = try {
            submit(jobId, selectedIds)
        } catch (_: IOException) {
            submit(jobId, selectedIds)
        }
        BookmarkBatchNotificationService.track(context, submitted, sessionId)
        val status = batchStatuses.watch(sessionId, jobId, submitted).first { status ->
            _state.value = _state.value.copy(batchProgress = BookmarkBatchProgress(
                action, status.total, status.processed, status.succeeded, supportsBackground = true,
                resultReceipt = if (status.finished) JobResultReceipt(sessionId, jobId) else null
            ))
            status.finished
        }

        if (status.succeeded > 0) refreshAfterMutation()
        val failed = if (status.failed == 0) emptySet() else
            bookmarkApi.getBatchJobFailedBookmarkIds(jobId).map(Long::toString).toSet()
        onRemoved(selected.filterNot { it in failed }.toSet())
        if (failed.isEmpty()) onSuccess()
    }

    private suspend fun refreshAfterMutation() {
        folderChanges.notifyChanged()
        pages.value.refresh()
        try {
            val tags = folderApi.getTags(folderId).map { it.name }.sorted()
            updateAvailableTags(tags)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            if (_state.value.error == null) _state.value =
                _state.value.copy(error = "載入收藏夾失敗，請重試")
        }
    }

    suspend fun createFolder(name: String) {
        check(!_state.value.isLoading && !_state.value.isMutating)
        _state.value = _state.value.copy(isMutating = true, error = null)
        try {
            val folder = folderApi.createFolder(FolderNameRequest(name.trim()))
            folderChanges.notifyChanged()
            _state.value = _state.value.copy(folders = _state.value.folders + folder)
        } finally {
            _state.value = _state.value.copy(isMutating = false)
        }
    }
}
