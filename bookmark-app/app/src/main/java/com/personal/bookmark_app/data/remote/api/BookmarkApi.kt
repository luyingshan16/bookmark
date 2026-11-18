package com.personal.bookmark_app.data.remote.api

import com.personal.bookmark_app.data.remote.dto.request.bookmark.CreateBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.request.bookmark.MoveBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.request.bookmark.UpdateBookmarkRequest
import com.personal.bookmark_app.data.remote.dto.response.PageResponse
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkResponse
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkSearchResponse
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkSourceResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface BookmarkApi {
    @GET("api/bookmark-sources")
    suspend fun getBookmarkSources(): List<BookmarkSourceResponse>

    @POST("api/folders/{folderId}/bookmarks")
    suspend fun createBookmark(
        @Path("folderId") folderId: Long,
        @Body request: CreateBookmarkRequest,
        @Header("Idempotency-Key") requestId: String? = null
    ): BookmarkResponse

    @GET("api/folders/{folderId}/bookmarks/page")
    suspend fun getFolderBookmarkPage(
        @Path("folderId") folderId: Long,
        @Query("query") query: String,
        @Query("tag") tags: List<String>,
        @Query("newestFirst") newestFirst: Boolean,
        @Query("page") page: Int,
        @Query("size") size: Int
    ): PageResponse<BookmarkResponse>

    @GET("api/folders/{folderId}/bookmarks/ids")
    suspend fun getMatchingFolderBookmarkIds(
        @Path("folderId") folderId: Long,
        @Query("query") query: String,
        @Query("tag") tags: List<String>
    ): List<Long>

    @GET("api/bookmarks/search/page")
    suspend fun searchBookmarks(
        @Query("query") query: String?,
        @Query("source") source: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
        @Query("includeSourceCounts") includeSourceCounts: Boolean
    ): BookmarkSearchResponse

    @GET("api/bookmarks/search/source-counts")
    suspend fun getSearchSourceCounts(@Query("query") query: String?): Map<String, Long>

    @PATCH("api/bookmarks/{bookmarkId}")
    suspend fun updateBookmark(
        @Path("bookmarkId") bookmarkId: Long,
        @Body request: UpdateBookmarkRequest
    ): BookmarkResponse

    @PATCH("api/bookmarks/{bookmarkId}/move")
    suspend fun moveBookmark(
        @Path("bookmarkId") bookmarkId: Long,
        @Body request: MoveBookmarkRequest
    ): BookmarkResponse

    @POST("api/bookmarks/batch/delete")
    suspend fun deleteBookmarkBatch(@Body request: BatchDeleteRequest): BatchResult

    @POST("api/bookmarks/move-jobs/{id}")
    suspend fun submitBatchMove(@Path("id") id: String, @Body request: BookmarkBatchMoveRequest): BookmarkBatchJobStatus

    @POST("api/bookmarks/move-jobs/{id}/delete")
    suspend fun submitBatchDelete(@Path("id") id: String, @Body request: BookmarkBatchDeleteRequest): BookmarkBatchJobStatus

    @GET("api/bookmarks/move-jobs/{id}")
    suspend fun getBatchJob(@Path("id") id: String): BookmarkBatchJobStatus

    @GET("api/bookmarks/move-jobs/{id}/failed-ids")
    suspend fun getBatchJobFailedBookmarkIds(@Path("id") id: String): List<Long>

    @GET("api/bookmarks/move-jobs/latest")
    suspend fun getLatestBatchJob(@Query("includeDelete") includeDelete: Boolean = true): Response<BookmarkBatchJobStatus>

    @DELETE("api/bookmarks/{bookmarkId}")
    suspend fun deleteBookmark(@Path("bookmarkId") bookmarkId: Long)
}

data class BatchDeleteRequest(val ids: List<Long>)
data class BookmarkBatchDeleteRequest(val sourceFolderId: Long, val ids: List<Long>)
data class BookmarkBatchMoveRequest(val ids: List<Long>, val sourceFolderId: Long, val targetFolderId: Long)

data class BookmarkBatchJobStatus(val id: String, val total: Int, val succeeded: Int, val failed: Int, val deleting: Boolean = false) {
    val processed: Int get() = succeeded + failed
    val finished: Boolean get() = processed == total
}
data class BatchResult(val succeededIds: List<Long>)

