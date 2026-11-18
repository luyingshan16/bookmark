package com.personal.bookmark_app.data

import com.google.gson.Gson
import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse
import com.personal.bookmark_app.ui.model.toUiModel
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderCountContractTest {
    @Test
    fun countAboveIntLimitRemainsLongThroughUiMapping() {
        val response = Gson().fromJson(
            """{"id":1,"name":"收藏夾","imageUrl":null,"isDefault":false,"bookmarkCount":2147483648}""",
            FolderResponse::class.java
        )
        assertEquals(2147483648L, response.bookmarkCount)
        assertEquals(2147483648L, response.toUiModel().count)
        assertEquals("2147483648", response.toUiModel().count.toString())
    }
}
