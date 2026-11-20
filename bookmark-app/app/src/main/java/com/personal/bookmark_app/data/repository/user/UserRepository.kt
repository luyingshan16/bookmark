package com.personal.bookmark_app.data.repository.user

import com.personal.bookmark_app.data.remote.dto.response.user.UserProfileResponse

interface UserRepository {
    suspend fun getProfile(): Result<UserProfileResponse>

    suspend fun linkGoogle(idToken: String, password: String): Result<UserProfileResponse>

    suspend fun updateAvatar(image: AvatarImage): Result<UserProfileResponse>
}
