package com.personal.bookmark_app.data.repository.user

import android.graphics.Bitmap
import com.personal.bookmark_app.data.remote.api.UserApi
import com.personal.bookmark_app.data.remote.backendMessage
import com.personal.bookmark_app.data.remote.dto.request.auth.LinkGoogleRequest
import com.personal.bookmark_app.data.remote.dto.response.user.UserProfileResponse
import kotlinx.coroutines.CancellationException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException
import javax.inject.Inject

class ApiUserRepository @Inject constructor(
    private val api: UserApi
) : UserRepository {

    override suspend fun getProfile(): Result<UserProfileResponse> = runCatching {
        api.getProfile()
    }

    override suspend fun linkGoogle(
        idToken: String,
        password: String
    ): Result<UserProfileResponse> = runCatching {
        api.linkGoogle(LinkGoogleRequest(idToken, password))
    }.fold(
        onSuccess = { Result.success(it) },
        onFailure = { error ->
            if (error is CancellationException) throw error
            val message = (error as? HttpException)?.backendMessage()
            Result.failure(IllegalStateException(message ?: "無法綁定 Google，請稍後再試", error))
        }
    )

    override suspend fun updateAvatar(
        image: AvatarImage
    ): Result<UserProfileResponse> = runCatching {
        val isJpeg = image.format == Bitmap.CompressFormat.JPEG
        val extension = if (isJpeg) "jpg" else "png"
        val mimeType = if (isJpeg) "image/jpeg" else "image/png"
        val bytes = LimitedAvatarOutputStream("處理後的圖片不可超過 10 MB").use {
            val compressed = image.bitmap.compress(image.format, 98, it)
            it.checkLimit()
            check(compressed) { "無法轉換頭像圖片" }
            it.toByteArray()
        }
        val imagePart = MultipartBody.Part.createFormData(
            "file",
            "avatar.$extension",
            bytes.toRequestBody(mimeType.toMediaType())
        )
        api.updateAvatar(imagePart)
    }
}
