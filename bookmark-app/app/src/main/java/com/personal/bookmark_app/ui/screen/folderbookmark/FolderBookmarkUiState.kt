package com.personal.bookmark_app.ui.screen.folderbookmark

import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse

data class FolderBookmarkUiState(
    val folderName: String = "收藏夾",
    val tags: List<String> = emptyList(),
    val totalCount: Long = 0,
    val folders: List<FolderResponse> = emptyList(),
    val isLoading: Boolean = false,
    val isMutating: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val selectedTags: Set<String> = emptySet(),
    val isNewestFirst: Boolean = true,
    val batchProgress: BookmarkBatchProgress? = null
)

data class JobResultReceipt(val sessionId: String, val jobId: String)

enum class BookmarkBatchAction { MOVE, DELETE }

data class BookmarkBatchProgress(
    val action: BookmarkBatchAction,
    val total: Int,
    val processed: Int = 0,
    val succeeded: Int = 0,
    val supportsBackground: Boolean = false,
    val resultReceipt: JobResultReceipt? = null
) {
    val finished: Boolean get() = processed == total
    val failed: Int get() = processed - succeeded
}
