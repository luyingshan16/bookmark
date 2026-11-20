package com.personal.bookmark_app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import androidx.paging.cachedIn
import androidx.paging.map
import com.personal.bookmark_app.data.remote.PageLoader
import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.api.FolderApi
import com.personal.bookmark_app.data.repository.BookmarkBatchNotificationService
import com.personal.bookmark_app.data.repository.BookmarkBatchStatusRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import com.personal.bookmark_app.data.remote.api.FolderNameRequest
import com.personal.bookmark_app.data.remote.api.FolderJobStatus
import com.personal.bookmark_app.data.remote.api.FolderJobRequest
import com.personal.bookmark_app.data.local.UserPreferencesRepository
import com.personal.bookmark_app.data.repository.user.AvatarImage
import com.personal.bookmark_app.data.repository.user.UserRepository
import com.personal.bookmark_app.data.repository.FolderChanges
import com.personal.bookmark_app.ui.model.FolderDisplayMode
import com.personal.bookmark_app.ui.model.toUiModel
import com.personal.bookmark_app.ui.screen.folderbookmark.JobResultReceipt
import com.personal.bookmark_app.ui.screen.home.HomeBatchJobResult
import com.personal.bookmark_app.ui.screen.home.FolderJobResult
import com.personal.bookmark_app.ui.screen.home.HomeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import retrofit2.HttpException
import java.util.UUID

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val userPreferencesRepository: UserPreferencesRepository,
    private val api: FolderApi,
    private val userRepository: UserRepository,
    private val folderChanges: FolderChanges,
    private val bookmarkApi: BookmarkApi,
    private val batchStatuses: BookmarkBatchStatusRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState = combine(_uiState, userPreferencesRepository.folderDisplayMode) { state, mode ->
        state.copy(displayMode = mode)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HomeUiState())

    private val pages = PageLoader { page, size ->
        api.getFolderPage(page, size).also { pageResponse ->
            _uiState.update { it.copy(folderCount = pageResponse.totalElements) }
        }
    }
    val folders = pages.flow.map { page -> page.map { it.toUiModel() } }.cachedIn(viewModelScope)

    init {
        viewModelScope.launch {
            folderChanges.changes.collect { refreshFolders() }
        }
    }

    private var batchJobRefresh: Job? = null
    private var folderJobRefresh: Job? = null
    private var folderJobSubmission: FolderJobSubmission? = null

    private data class FolderJobSubmission(val folderId: Long, val destination: FolderJobRequest, val id: String)

    fun refreshLatestBatchJob() {
        if (batchJobRefresh?.isActive == true) return
        _uiState.update { it.copy(batchJobStatusUnavailable = false) }
        batchJobRefresh = viewModelScope.launch {
            var trackingBatchJob = false
            try {
                val sessionId = userPreferencesRepository.sessionTokens()?.sessionId ?: return@launch
                val response = bookmarkApi.getLatestBatchJob()
                if (userPreferencesRepository.sessionTokens()?.sessionId != sessionId) return@launch
                if (!response.isSuccessful) return@launch
                val latest = response.body()
                _uiState.update { it.copy(latestBatchJob = latest) }
                if (latest == null) return@launch
                if (!latest.finished) {
                    trackingBatchJob = true
                    BookmarkBatchNotificationService.track(context, latest, sessionId)
                }
                val status = batchStatuses.watch(sessionId, latest.id, latest).first { status ->
                    _uiState.update { it.copy(latestBatchJob = status) }
                    status.finished
                }
                if (!userPreferencesRepository.wasJobResultShown(status.id) &&
                    userPreferencesRepository.sessionTokens()?.sessionId == sessionId
                ) {
                    val result = HomeBatchJobResult(status, JobResultReceipt(sessionId, status.id))
                    _uiState.update { it.copy(batchJobResult = result) }
                    refreshFolders()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.update { state ->
                    if (trackingBatchJob && state.latestBatchJob?.finished == false) {
                        state.copy(latestBatchJob = null, batchJobStatusUnavailable = true)
                    } else state
                }
            }
        }
    }

    suspend fun consumeBatchJobResult(result: HomeBatchJobResult): Boolean {
        val receipt = result.receipt
        val marked = userPreferencesRepository.markJobResultShown(receipt.sessionId, receipt.jobId)
        _uiState.update { if (it.batchJobResult == result) it.copy(batchJobResult = null) else it }
        return marked
    }

    fun updateAvatar(image: AvatarImage) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { userRepository.updateAvatar(image) }
                .onSuccess { profile -> _uiState.update { it.copy(updatedProfile = profile) } }
        }
    }

    suspend fun linkGoogle(idToken: String, password: String): Result<Unit> =
        userRepository.linkGoogle(idToken, password)
            .map { updatedProfile ->
                _uiState.update { it.copy(updatedProfile = updatedProfile) }
            }

    fun refreshFolders() = pages.refresh()

    fun renameFolder(id: Long, name: String) = mutateFolders {
        api.renameFolder(id, FolderNameRequest(name))
    }

    suspend fun deleteFolder(id: Long) {
        try {
            api.deleteFolder(id, onlyIfEmpty = true)
        } catch (error: HttpException) {
            if (error.code() == 409) refreshFolders()
            throw error
        }
        folderChanges.notifyChanged()
    }

    suspend fun getMoveDestinations(sourceId: Long) = api.getFolders().filterNot { it.id == sourceId }

    suspend fun submitFolderJob(id: Long, request: FolderJobRequest) {
        val submission = folderJobSubmission?.takeIf { it.folderId == id && it.destination == request }
            ?: FolderJobSubmission(id, request, UUID.randomUUID().toString())
        folderJobSubmission = submission
        val status = api.submitFolderJob(id, submission.id, request)
        folderJobSubmission = null
        refreshFolderJob(status)
    }

    fun refreshFolderJob() = refreshFolderJob(null)

    private fun refreshFolderJob(submitted: FolderJobStatus?) {
        if (submitted == null && folderJobRefresh?.isActive == true) return
        if (submitted != null) {
            folderJobRefresh?.cancel()
            _uiState.update { it.copy(folderJob = submitted) }
        }
        folderJobRefresh = viewModelScope.launch {
            try {
                val sessionId = userPreferencesRepository.sessionTokens()?.sessionId ?: return@launch
                var status = submitted ?: api.getLatestFolderJob().let { response ->
                    if (!response.isSuccessful) throw HttpException(response)
                    response.body() ?: return@launch
                }
                while (userPreferencesRepository.sessionTokens()?.sessionId == sessionId) {
                    _uiState.update { it.copy(folderJob = status) }
                    if (status.finished) {
                        if (!userPreferencesRepository.wasJobResultShown(status.id, isFolderJob = true)) {
                            folderChanges.notifyChanged()
                            val result = FolderJobResult(status, JobResultReceipt(sessionId, status.id))
                            _uiState.update { it.copy(folderJobResult = result) }
                        }
                        return@launch
                    }
                    delay(2000)
                    try {
                        status = api.getFolderJob(status.id)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (error is HttpException && error.code() in listOf(401, 403, 404)) {
                            _uiState.update { it.copy(folderJob = null) }
                            return@launch
                        }
                        delay(3000)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // 回到首頁時會重新取得伺服器上的任務。
            }
        }
    }

    suspend fun consumeFolderJobResult(result: FolderJobResult): Boolean {
        val receipt = result.receipt
        val marked = userPreferencesRepository.markJobResultShown(receipt.sessionId, receipt.jobId, isFolderJob = true)
        _uiState.update { if (it.folderJobResult == result) it.copy(folderJobResult = null) else it }
        return marked
    }

    suspend fun createFolder(name: String) {
        api.createFolder(FolderNameRequest(name))
        folderChanges.notifyChanged()
    }

    private fun mutateFolders(action: suspend () -> Any?) {
        viewModelScope.launch {
            runCatching { withContext(Dispatchers.IO) { action() } }
                .onSuccess { folderChanges.notifyChanged() }
        }
    }

    // 更新收藏夾顯示方式
    fun updateFolderDisplayMode(
        mode: FolderDisplayMode
    ) {
        viewModelScope.launch {
            userPreferencesRepository.saveFolderDisplayMode(mode)
        }
    }
}
