package com.personal.bookmark_app.data.remote.api

import com.personal.bookmark_app.data.remote.dto.response.PageResponse

import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface FolderApi {
    @GET("api/folders")
    suspend fun getFolders(): List<FolderResponse>

    @GET("api/folders/page")
    suspend fun getFolderPage(
        @Query("page") page: Int,
        @Query("size") size: Int
    ): PageResponse<FolderResponse>

    @GET("api/folders/{folderId}")
    suspend fun getFolder(@Path("folderId") folderId: Long): FolderResponse

    @GET("api/folders/{folderId}/tags")
    suspend fun getTags(@Path("folderId") folderId: Long): List<FolderTagResponse>

    @POST("api/folders")
    suspend fun createFolder(@Body request: FolderNameRequest): FolderResponse

    @PATCH("api/folders/{folderId}")
    suspend fun renameFolder(
        @Path("folderId") folderId: Long,
        @Body request: FolderNameRequest
    ): FolderResponse

    @DELETE("api/folders/{folderId}")
    suspend fun deleteFolder(
        @Path("folderId") folderId: Long,
        @Query("deleteBookmarks") deleteBookmarks: Boolean = false,
        @Query("onlyIfEmpty") onlyIfEmpty: Boolean = false
    )

    @POST("api/folders/{folderId}/jobs/{jobId}")
    suspend fun submitFolderJob(
        @Path("folderId") folderId: Long,
        @Path("jobId") jobId: String,
        @Body request: FolderJobRequest
    ): FolderJobStatus

    @GET("api/folders/jobs/{jobId}")
    suspend fun getFolderJob(@Path("jobId") jobId: String): FolderJobStatus

    @GET("api/folders/jobs/latest")
    suspend fun getLatestFolderJob(): Response<FolderJobStatus>
}

data class FolderJobRequest(
    val targetFolderId: Long? = null,
    val newFolderName: String? = null,
    val deleteBookmarks: Boolean = false
)

data class FolderJobStatus(val id: String, val state: String, val deleting: Boolean, val errorMessage: String?) {
    val finished: Boolean get() = state == "SUCCEEDED" || state == "FAILED"
    val succeeded: Boolean get() = state == "SUCCEEDED"
}

data class FolderNameRequest(val name: String)

data class FolderTagResponse(val id: Long, val name: String)
