package com.personal.bookmark_app.data

import com.google.gson.Gson
import com.personal.bookmark_app.data.remote.dto.request.bookmark.toUpdateRequest
import com.personal.bookmark_app.data.remote.dto.response.bookmark.BookmarkResponse
import org.junit.Assert.*
import org.junit.Test

class BookmarkUpdateContractTest {
    private val original = BookmarkResponse(
        1, 2, "https://example.org", "old title", "content", "old note", "OTHER",
        null, listOf("old tag"), null, null
    )

    @Test
    fun editingNoteMarksOnlyNoteDespiteOldTitleInPayload() {
        val request = original.toUpdateRequest(null, "new note", null)
        val json = Gson().toJsonTree(request).asJsonObject
        assertEquals("new note", json["note"].asString)
        assertEquals("old title", json["title"].asString)
        assertEquals(listOf("note"), json["updateFields"].asJsonArray.map { it.asString })
    }

    @Test
    fun emptyNoteAndEmptyTagsAreExplicitUpdates() {
        assertEquals(listOf("note"), original.toUpdateRequest(null, "", null).updateFields)
        val request = original.toUpdateRequest(null, null, emptyList())
        assertEquals(listOf("tags"), request.updateFields)
        assertTrue(Gson().toJsonTree(request).asJsonObject["tags"].asJsonArray.isEmpty)
    }

    @Test
    fun titleAndMultipleEditsHaveMatchingMasks() {
        assertEquals(listOf("title"), original.toUpdateRequest("new", null, null).updateFields)
        assertEquals(listOf("title", "note", "tags"),
            original.toUpdateRequest("new", "new note", listOf("new tag")).updateFields)
    }
}
