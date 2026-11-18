package com.personal.bookmark_app.ui.viewmodel

import android.os.Bundle
import com.personal.bookmark_app.SharedBookmark
import com.personal.bookmark_app.data.repository.extractSharedUrl
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.personal.bookmark_app.data.local.UserPreferencesRepository
import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.api.FolderApi
import com.personal.bookmark_app.data.remote.api.FolderNameRequest
import com.personal.bookmark_app.data.remote.dto.request.bookmark.CreateBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.response.bookmark.OTHER_SOURCE_CODE
import com.personal.bookmark_app.data.repository.BookmarkBrowserChallengeException
import com.personal.bookmark_app.data.repository.BookmarkHttpException
import com.personal.bookmark_app.data.repository.BookmarkMetadata
import com.personal.bookmark_app.data.repository.BookmarkMetadataParser
import com.personal.bookmark_app.data.repository.BookmarkPreviewUnavailableException
import com.personal.bookmark_app.data.repository.BookmarkPrivateNetworkException
import com.personal.bookmark_app.data.repository.FolderChanges
import com.personal.bookmark_app.data.repository.browserPostLink
import com.personal.bookmark_app.ui.model.toUiModel
import com.personal.bookmark_app.ui.model.TagOptionUiModel
import com.personal.bookmark_app.ui.screen.addbookmark.AddBookmarkUiState
import com.personal.bookmark_app.ui.utils.MAX_TAG_NAME_LENGTH
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject
import retrofit2.HttpException

@HiltViewModel
class AddBookmarkViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val folderApi: FolderApi,
    private val bookmarkApi: BookmarkApi,
    private val parser: BookmarkMetadataParser,
    private val folderChanges: FolderChanges,
    private val preferences: UserPreferencesRepository
) : ViewModel() {
    private var parseJob: Job? = null
    private var parseGeneration = 0
    private var debounceJob: Job? = null
    private var draftSessionId: String? = null
    private var requestedFolderId: Long? = null
    private var submission: BookmarkSubmission? = null
    private var folderLoadJob: Job? = null
    private var tagLoadJob: Job? = null
    private var pendingShare: SharedBookmark? = null
    private var pendingBrowserResult: (() -> Unit)? = null
    private val _state = MutableStateFlow(AddBookmarkUiState())
    val uiState = _state.asStateFlow()

    private fun restoreUiState(draft: Bundle): AddBookmarkUiState {
        val restoredBrowserUrl = savedState.get<String>("automaticBrowserImport")
        requestedFolderId = draft.getLong("folderId").takeIf { it > 0L }
        val fingerprint = draft.getString("submissionFingerprint")
        val requestId = draft.getString("submissionId")
        if (fingerprint != null && requestId != null) submission = BookmarkSubmission(fingerprint, requestId)
        val selectedTags = draft.getStringArrayList("tags").orEmpty().toSet()
        val url = draft.getString("url").orEmpty()
        val browserImportPending = restoredBrowserUrl != null ||
                savedState.get<Bundle>("browserImport") != null
        return AddBookmarkUiState(
            sourceUrl = url,
            title = draft.getString("title").orEmpty(),
            note = draft.getString("note").orEmpty(),
            content = draft.getString("content").orEmpty(),
            imageUrl = draft.getString("image"),
            selectedTagIds = selectedTags,
            tags = mergeDraftTags(emptyList(), selectedTags),
            isParsing = browserImportPending,
            automaticBrowserUrl = restoredBrowserUrl,
            parseError = if (!browserImportPending && savedState.get<String>("browserFallbackUrl") == url.trim()) {
                "尚未匯入文章，可點「重新解析」再試一次"
            } else null,
            isAddedToDailyPicks = draft.getBoolean("daily", true)
        )
    }

    init {
        loadFolders()
    }

    fun edit(transform: (AddBookmarkUiState) -> AddBookmarkUiState) {
        if (draftSessionId != null && !_state.value.isSaving && !_state.value.isSaved) {
            val form = transform(_state.value)
            _state.value = form.copy(errorMessage = if (!form.isLoading && form.selectedFolderId == 0L &&
                requestedFolderId != null && form.folders.none { it.id == requestedFolderId }) {
                "原本選擇的收藏夾已不存在，請重新選擇"
            } else null)
            persistDraft()
        }
    }

    private fun persistDraft() {
        val sessionId = draftSessionId ?: return
        val form = _state.value
        savedState["draft"] = Bundle().apply {
            putString("sessionId", sessionId)
            requestedFolderId?.let { putLong("folderId", it) }
            putStringArrayList("tags", ArrayList(form.selectedTagIds))
            submission?.let {
                putString("submissionFingerprint", it.fingerprint)
                putString("submissionId", it.requestId)
            }
            putString("url", form.sourceUrl); putString("title", form.title); putString(
            "note",
            form.note
        )
            putString("content", form.content); putString(
            "image",
            form.imageUrl
        ); putBoolean("daily", form.isAddedToDailyPicks)
        }
    }

    fun receiveShare(share: SharedBookmark) {
        if (draftSessionId == null) {
            pendingShare = share
            return
        }
        if (savedState.get<String>("shareId") == share.id) return
        savedState["shareId"] = share.id
        submission = null
        debounceJob?.cancel()
        parseJob?.cancel()
        parseGeneration++
        resetBrowserImport()
        val url = extractSharedUrl(share.text)
        _state.value = _state.value.copy(
            sourceUrl = url.orEmpty(), title = share.title,
            note = "", selectedTagIds = emptySet(), isParsing = false,
            content = "", imageUrl = null, isSaved = false, isSaving = false,
            parseError = if (url == null) "分享內容沒有有效的 HTTP／HTTPS 網址，請手動輸入" else null
        )
        persistDraft()
        if (url != null) parseUrl()
    }

    fun changeUrl(value: String) {
        if (draftSessionId == null || _state.value.isSaving) return
        debounceJob?.cancel()
        parseJob?.cancel()
        parseGeneration++
        resetBrowserImport()
        val validUrl = value.isNotBlank() && extractSharedUrl(value.trim()) == value.trim()
        edit {
            it.copy(
                sourceUrl = value,
                content = "",
                imageUrl = null,
                isParsing = validUrl,
                parseError = null
            )
        }
        if (validUrl) {
            debounceJob = viewModelScope.launch { delay(700); parseUrl() }
        }
    }

    fun reparseOrOpenBrowser(): String? {
        if (draftSessionId == null) return null
        val form = _state.value
        if (form.isSaving || form.isSaved || form.isParsing) return null
        val url = form.sourceUrl.trim()
        if (savedState.get<String>("browserFallbackUrl") == url) return beginBrowserImport(url)
        parseUrl()
        return null
    }

    private fun beginBrowserImport(url: String): String? {
        val form = _state.value
        if (form.sourceUrl.trim() != url || form.isSaving || form.isSaved ||
            savedState.get<Bundle>("browserImport") != null
        ) return null
        savedState["browserImport"] = Bundle().apply {
            putString("url", url)
            putString("title", form.title)
        }
        _state.value = form.copy(isParsing = true, parseError = null)
        return url
    }

    fun completeBrowserImport(result: BookmarkMetadata?) {
        if (draftSessionId == null) {
            pendingBrowserResult = { completeBrowserImport(result) }
            return
        }
        val request = savedState.remove<Bundle>("browserImport") ?: return
        completeBrowserImport(
            request.getString("url").orEmpty(), request.getString("title").orEmpty(), result,
            "尚未匯入文章，可點「重新解析」再試一次"
        )
    }

    fun completeAutomaticBrowserImport(url: String, result: BookmarkMetadata?) {
        if (_state.value.automaticBrowserUrl != url) return
        val originalTitle = savedState.remove<String>("automaticBrowserTitle").orEmpty()
        setAutomaticBrowserUrl(null)
        completeBrowserImport(
            url,
            originalTitle,
            result,
            "背景解析未取得貼文，可點「重新解析」開啟貼文頁"
        )
    }

    private fun completeBrowserImport(
        url: String,
        originalTitle: String,
        result: BookmarkMetadata?,
        failureMessage: String
    ) {
        val form = _state.value
        if (url != form.sourceUrl.trim() || form.isSaving || form.isSaved) return
        if (result == null || result.url != url) {
            _state.value = form.copy(isParsing = false, parseError = failureMessage)
            return
        }
        resetBrowserImport()
        applyMetadata(result, originalTitle)
        _state.value = _state.value.copy(isParsing = false, parseError = null)
    }

    private fun queueBrowserImport(url: String, originalTitle: String) {
        savedState["browserFallbackUrl"] = url
        savedState["automaticBrowserTitle"] = originalTitle
        setAutomaticBrowserUrl(url)
    }

    private fun resetBrowserImport() {
        savedState.remove<String>("automaticBrowserTitle")
        setAutomaticBrowserUrl(null)
        savedState.remove<String>("browserFallbackUrl")
        savedState.remove<Bundle>("browserImport")
    }

    private fun setAutomaticBrowserUrl(url: String?) {
        savedState["automaticBrowserImport"] = url
        _state.value = _state.value.copy(automaticBrowserUrl = url)
    }

    private fun applyMetadata(result: BookmarkMetadata, originalTitle: String) {
        val form = _state.value
        _state.value = form.copy(
            sourceUrl = result.url,
            title = if (form.title == originalTitle) result.title else form.title,
            content = result.content,
            imageUrl = result.imageUrl
        )
        persistDraft()
    }

    fun parseUrl() {
        debounceJob?.cancel()
        if (draftSessionId == null || _state.value.isSaving) return
        val url = _state.value.sourceUrl.trim()
        if (extractSharedUrl(url) != url || url.isBlank()) {
            _state.value = _state.value.copy(parseError = "請先輸入有效的 HTTP／HTTPS 網址")
            return
        }
        parseJob?.cancel()
        val generation = ++parseGeneration
        val originalTitle = _state.value.title
        _state.value = _state.value.copy(isParsing = true, parseError = null)
        parseJob = viewModelScope.launch {
            try {
                val result = parser.parse(url)
                if (generation == parseGeneration) {
                    val titleWasEdited = _state.value.title != originalTitle
                    applyMetadata(result, originalTitle)
                    if (result.content.isBlank() && browserPostLink(result.url) != null) {
                        val replaceableTitle =
                            if (titleWasEdited) originalTitle else _state.value.title
                        queueBrowserImport(result.url, replaceableTitle)
                    } else {
                        savedState.remove<String>("browserFallbackUrl")
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (generation == parseGeneration) {
                    val browserFallback = browserPostLink(url) != null &&
                            (e is BookmarkBrowserChallengeException || e is BookmarkPreviewUnavailableException ||
                                    e is BookmarkHttpException && e.status in setOf(403, 429))
                    if (e is BookmarkPrivateNetworkException) {
                        resetBrowserImport()
                        _state.value = _state.value.copy(
                            parseError = "此網址指向內網或非公開位址，不提供自動預覽；可手動輸入標題後儲存"
                        )
                    } else if (browserFallback) {
                        queueBrowserImport(url, originalTitle)
                    } else {
                        val action = if (e is BookmarkBrowserChallengeException) {
                            "，可手動輸入標題後儲存"
                        } else {
                            "，可重新解析或手動輸入標題後儲存"
                        }
                        val message = if (e is IOException) {
                            "無法連線至來源網站"
                        } else {
                            e.message ?: "解析失敗"
                        }
                        _state.value = _state.value.copy(parseError = message + action)
                    }
                }
            } finally {
                if (generation == parseGeneration && _state.value.automaticBrowserUrl == null &&
                    savedState.get<Bundle>("browserImport") == null
                ) {
                    _state.value = _state.value.copy(isParsing = false)
                }
            }
        }
    }

    private fun request(resetBusy: Boolean = true, action: suspend () -> Unit): Job =
        viewModelScope.launch {
            try {
                action()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _state.value = _state.value.copy(
                    isLoading = if (resetBusy) false else _state.value.isLoading,
                    isSaving = if (resetBusy) false else _state.value.isSaving,
                    errorMessage = "操作失敗，請檢查網路後重試"
                )
            }
        }
    fun loadFolders() {
        if (_state.value.isSaving || folderLoadJob?.isActive == true) return
        _state.value = _state.value.copy(isLoading = true, selectedFolderId = 0, errorMessage = null)
        folderLoadJob = request {
            val sessionId = preferences.sessionTokens()?.sessionId
            if (sessionId != draftSessionId || draftSessionId == null) {
                initializeDraft(sessionId)
            }
            checkNotNull(sessionId)
            val folders = folderChanges.getFolders().map { it.toUiModel() }
            if (preferences.sessionTokens()?.sessionId != sessionId) {
                initializeDraft(null)
                _state.value = _state.value.copy(isLoading = false)
                return@request
            }
            val destination = resolveDraftFolder(requestedFolderId, folders)
            _state.value = _state.value.copy(
                folders = folders,
                isLoading = false,
                errorMessage = if (destination == null && requestedFolderId != null) {
                    "原本選擇的收藏夾已不存在，請重新選擇"
                } else null
            )
            destination?.let(::selectFolder)
        }
    }

    private fun initializeDraft(sessionId: String?) {
        parseJob?.cancel()
        debounceJob?.cancel()
        tagLoadJob?.cancel()
        parseGeneration++
        draftSessionId = sessionId
        requestedFolderId = null
        submission = null
        val draft = savedState.get<Bundle>("draft")
        if (draft != null && canRestoreDraft(draft.getString("sessionId"), sessionId)) {
            _state.value = restoreUiState(draft)
            pendingBrowserResult?.invoke()
        } else {
            _state.value = AddBookmarkUiState()
            savedState.remove<Bundle>("draft")
            savedState.remove<String>("shareId")
            resetBrowserImport()
        }
        pendingBrowserResult = null
        val share = pendingShare
        pendingShare = null
        if (share != null && share.id != savedState.get<String>("shareId")) {
            if (sessionId != null) receiveShare(share)
        } else if (!_state.value.isParsing && savedState.get<String>("browserFallbackUrl") == null &&
            _state.value.sourceUrl.isNotBlank() && _state.value.content.isBlank()
        ) {
            parseUrl()
        }
    }

    fun selectFolder(id: Long) {
        val sessionId = draftSessionId ?: return
        if (_state.value.isSaving || _state.value.isSaved || _state.value.folders.none { it.id == id }) return
        requestedFolderId = id
        edit { it.copy(selectedFolderId = id, tags = mergeDraftTags(emptyList(), it.selectedTagIds)) }
        tagLoadJob?.cancel()
        tagLoadJob = request(resetBusy = false) {
            val tags = folderApi.getTags(id).map { it.name }
            if (preferences.sessionTokens()?.sessionId != sessionId) return@request
            if (_state.value.selectedFolderId == id)
                _state.value = _state.value.copy(tags = mergeDraftTags(tags, _state.value.selectedTagIds))
        }
    }

    suspend fun createFolder(name: String) {
        val sessionId = checkNotNull(draftSessionId)
        check(!_state.value.isLoading && !_state.value.isSaving)
        _state.value = _state.value.copy(isLoading = true, errorMessage = null)
        try {
            check(preferences.sessionTokens()?.sessionId == sessionId)
            val folder = folderApi.createFolder(FolderNameRequest(name.trim()))
            folderChanges.notifyChanged()
            check(preferences.sessionTokens()?.sessionId == sessionId)
            _state.value = _state.value.copy(
                folders = _state.value.folders + folder.toUiModel().copy(count = 0)
            )
            selectFolder(folder.id)
        } finally {
            _state.value = _state.value.copy(isLoading = false)
        }
    }

    fun toggleTag(id: String) = edit {
        it.copy(
            selectedTagIds = if (id in it.selectedTagIds) it.selectedTagIds - id
            else if (it.selectedTagIds.size < 5) it.selectedTagIds + id else it.selectedTagIds
        )
    }

    fun addTag(name: String) {
        val tag = name.trim()
        if (tag.isEmpty() || tag.length > MAX_TAG_NAME_LENGTH || _state.value.isTagLimitReached) return
        edit {
            it.copy(
                tags = (listOf(TagOptionUiModel(tag, tag)) + it.tags).distinctBy { it.id },
                selectedTagIds = it.selectedTagIds + tag
            )
        }
    }

    fun save() {
        val sessionId = draftSessionId ?: return
        val form = _state.value
        if (!form.isFormValid) return
        _state.value = form.copy(isSaving = true, errorMessage = null)
        request {
            if (preferences.sessionTokens()?.sessionId != sessionId) {
                initializeDraft(null)
                _state.value = _state.value.copy(isLoading = false, errorMessage = "登入狀態已變更，請重新開啟新增收藏")
                return@request
            }
            val payload = CreateBookmarkRequest(
                url = form.sourceUrl.trim(),
                title = form.title.trim(),
                note = form.note.trim().ifBlank { null },
                source = OTHER_SOURCE_CODE,
                tags = form.selectedTagIds.toList(),
                content = form.content.take(500).ifBlank { null },
                imageUrl = form.imageUrl,
                addToDailyPicks = form.isAddedToDailyPicks
            )
            val attempt = bookmarkSubmission(submission, form.selectedFolderId, payload)
            submission = attempt
            persistDraft()
            try {
                bookmarkApi.createBookmark(form.selectedFolderId, payload, attempt.requestId)
            } catch (error: HttpException) {
                val message = when (error.code()) {
                    409 -> "提交內容與先前不同，請重新開啟新增收藏"
                    410 -> "原收藏已刪除，請重新開啟新增收藏"
                    else -> throw error
                }
                _state.value = _state.value.copy(isSaving = false, errorMessage = message)
                return@request
            }
            savedState.remove<Bundle>("draft")
            folderChanges.notifyChanged()
            _state.value = _state.value.copy(isSaving = false, isSaved = true)
        }
    }
}
