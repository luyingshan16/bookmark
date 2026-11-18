package com.personal.bookmark_app.data.repository

import com.personal.bookmark_app.data.remote.api.BookmarkApi
import com.personal.bookmark_app.data.remote.dto.request.bookmark.MoveBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.request.bookmark.toUpdateRequest
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkResponse
import javax.inject.Inject

class BookmarkRepository internal constructor(
    private val api: BookmarkApi,
    private val notifyChanged: () -> Unit
) {
    @Inject
    constructor(api: BookmarkApi, folderChanges: FolderChanges) : this(api, folderChanges::notifyChanged)

    suspend fun updateBookmark(bookmark: BookmarkResponse, title: String?, note: String?, tags: List<String>?) {
        api.updateBookmark(bookmark.id, bookmark.toUpdateRequest(title, note, tags))
        notifyChanged()
    }

    suspend fun deleteBookmark(bookmarkId: Long) {
        api.deleteBookmark(bookmarkId)
        notifyChanged()
    }

    suspend fun moveBookmark(bookmarkId: Long, destination: Long) {
        api.moveBookmark(bookmarkId, MoveBookmarkRequest(destination))
        notifyChanged()
    }
}
