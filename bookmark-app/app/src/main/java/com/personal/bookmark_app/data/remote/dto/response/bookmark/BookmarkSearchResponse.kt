package com.personal.bookmark_app.data.remote.dto.response.bookmark

data class BookmarkSearchResponse(
    val content: List<BookmarkResponse>,
    val page: Int,
    val hasNext: Boolean,
    val sourceCounts: Map<String, Long>?
)
