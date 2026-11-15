package com.personal.bookmark_app.ui.screen.home

import com.personal.bookmark_app.data.remote.api.BookmarkBatchJobStatus
import com.personal.bookmark_app.data.remote.api.FolderJobStatus
import com.personal.bookmark_app.ui.screen.folderbookmark.JobResultReceipt
import com.personal.bookmark_app.data.remote.dto.response.user.UserProfileResponse
import com.personal.bookmark_app.ui.model.FolderDisplayMode

data class HomeUiState(
    val folderCount: Long = 0,
    val displayMode: FolderDisplayMode = FolderDisplayMode.GRID,
    val updatedProfile: UserProfileResponse? = null,
    val latestBatchJob: BookmarkBatchJobStatus? = null,
    val batchJobStatusUnavailable: Boolean = false,
    val batchJobResult: HomeBatchJobResult? = null,
    val folderJob: FolderJobStatus? = null,
    val folderJobResult: FolderJobResult? = null
)

data class HomeBatchJobResult(val status: BookmarkBatchJobStatus, val receipt: JobResultReceipt)

data class FolderJobResult(val status: FolderJobStatus, val receipt: JobResultReceipt)
