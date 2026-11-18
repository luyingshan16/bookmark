package com.personal.bookmark_app.ui.viewmodel

import com.personal.bookmark_app.ui.model.FolderUiModel
import com.personal.bookmark_app.ui.model.TagOptionUiModel

internal fun canRestoreDraft(savedSessionId: String?, currentSessionId: String?): Boolean =
    savedSessionId != null && savedSessionId == currentSessionId

internal fun resolveDraftFolder(requestedId: Long?, folders: List<FolderUiModel>): Long? =
    if (requestedId != null) {
        folders.firstOrNull { it.id == requestedId }?.id
    } else {
        (folders.firstOrNull { it.title == "未分類" } ?: folders.firstOrNull())?.id
    }

internal fun mergeDraftTags(folderTags: List<String>, selectedTags: Set<String>): List<TagOptionUiModel> =
    (folderTags + selectedTags).distinct().map { TagOptionUiModel(it, it) }
