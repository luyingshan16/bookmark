package com.personal.bookmark_app.data.remote.dto.request.bookmark

import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkResponse
import com.personal.bookmark_app.data.remote.dto.response.bookmark.OTHER_SOURCE_CODE

data class UpdateBookmarkRequest(
    val url: String,
    val title: String,
    val content: String?,
    val note: String?,
    val source: String,
    val imageUrl: String?,
    val tags: List<String>,
    val updateFields: List<String>? = null
)

// 保留完整資料格式，但後端只寫入 updateFields 中這次實際編輯的欄位。
internal fun BookmarkResponse.toUpdateRequest(
    title: String?,
    note: String?,
    tags: List<String>?
) = UpdateBookmarkRequest(
    url = url,
    title = title ?: this.title,
    content = content,
    note = note ?: this.note,
    source = source ?: OTHER_SOURCE_CODE,
    imageUrl = imageUrl,
    tags = tags ?: this.tags,
    updateFields = buildList {
        if (title != null) add("title")
        if (note != null) add("note")
        if (tags != null) add("tags")
    }
)
