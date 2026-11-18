package com.personal.bookmark_app.ui

import com.personal.bookmark_app.ui.model.FolderUiModel
import com.personal.bookmark_app.ui.screen.addbookmark.AddBookmarkUiState
import com.personal.bookmark_app.ui.viewmodel.canRestoreDraft
import com.personal.bookmark_app.ui.viewmodel.mergeDraftTags
import com.personal.bookmark_app.ui.viewmodel.resolveDraftFolder
import org.junit.Assert.*
import org.junit.Test

class AddBookmarkDraftTest {
    @Test
    fun privateUrlCanStillBeSavedWithManualTitleWhenPreviewHasStopped() {
        listOf("http://192.168.1.1/", "http://nas.local/", "http://[fd00::1]/").forEach { url ->
            val form = AddBookmarkUiState(title = "內網收藏", sourceUrl = url,
                isLoading = false, selectedFolderId = 1L, isParsing = false,
                parseError = "不提供自動預覽")
            assertTrue(url, form.isFormValid)
        }
    }

    private val default = FolderUiModel(1, "未分類", 0, "", null)
    private val destination = FolderUiModel(2, "旅行", 0, "", null)

    @Test
    fun restoredDestinationWinsAndNewLocalTagsRemainSelected() {
        assertTrue(canRestoreDraft("session-a", "session-a"))
        assertEquals(2L, resolveDraftFolder(2, listOf(default, destination)))
        val restored = mergeDraftTags(listOf("景點", "食記"), linkedSetOf("景點", "新標籤"))
        assertEquals(listOf("景點", "食記", "新標籤"), restored.map { it.id })
    }

    @Test
    fun deletedDestinationDoesNotFallBackAndBlocksSubmissionUntilReselected() {
        val folder = resolveDraftFolder(2, listOf(default))
        assertNull(folder)
        val form = AddBookmarkUiState(title = "草稿", sourceUrl = "https://example.org/",
            isLoading = false, selectedFolderId = folder ?: 0L, selectedTagIds = setOf("新標籤"))
        assertFalse(form.isFormValid)
        assertTrue(form.copy(selectedFolderId = default.id).isFormValid)
        assertEquals(setOf("新標籤"), form.selectedTagIds)
        assertEquals(1L, resolveDraftFolder(null, listOf(destination, default)))
    }

    @Test
    fun changedMissingOrLegacySessionCannotRestore() {
        assertFalse(canRestoreDraft("session-a", "session-b"))
        assertFalse(canRestoreDraft("session-a", null))
        assertFalse(canRestoreDraft(null, "session-a"))
        assertFalse(canRestoreDraft(null, null))
    }
}
