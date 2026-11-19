package com.personal.bookmark_app.ui.components.bookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse
import com.personal.bookmark_app.ui.components.AddFolderDialog
import com.personal.bookmark_app.ui.components.CreateFolderSheetButton
import com.personal.bookmark_app.ui.components.FolderSheetRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MoveBookmarkSheet(
    selectedCount: Long,
    folders: List<FolderResponse>,
    onDismiss: () -> Unit,
    onMove: (Long) -> Unit,
    onCreateFolder: suspend (String) -> Unit,
    title: String = "移動收藏到",
    description: String = "將 $selectedCount 筆收藏移動至指定收藏夾",
    isLoading: Boolean = false,
    isMoving: Boolean = false,
    errorMessage: String? = null,
    onRetry: (() -> Unit)? = null,
    createAndDelete: Boolean = false
) {
    var showAddFolderDialog by remember { mutableStateOf(false) }
    val moving by rememberUpdatedState(isMoving)
    ModalBottomSheet(
        onDismissRequest = { if (!isMoving) onDismiss() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true,
            confirmValueChange = { !moving || it != SheetValue.Hidden }),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            BottomSheetDefaults.DragHandle(color = MaterialTheme.colorScheme.outlineVariant)
        }
    ) {
        MoveBookmarkContent(
            title = title,
            description = description,
            folders = folders,
            isLoading = isLoading,
            isMoving = isMoving,
            errorMessage = errorMessage,
            onRetry = onRetry,
            onFolderClick = onMove,
            onCreateFolderClick = { showAddFolderDialog = true }
        )
    }

    if (showAddFolderDialog) {
        AddFolderDialog(
            onDismiss = { showAddFolderDialog = false },
            onConfirm = onCreateFolder,
            confirmLabel = if (createAndDelete) "新增並移動" else "新增",
            submittingLabel = if (createAndDelete) "移動中…" else "新增中…",
            failureMessage = if (createAndDelete) "無法新增收藏夾並完成移動，請稍後再試"
                else "新增收藏夾失敗，請稍後再試"
        )
    }
}

@Composable
private fun MoveBookmarkContent(
    title: String,
    description: String,
    folders: List<FolderResponse>,
    isLoading: Boolean,
    isMoving: Boolean,
    errorMessage: String?,
    onRetry: (() -> Unit)?,
    onFolderClick: (Long) -> Unit,
    onCreateFolderClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 20.dp,
                end = 20.dp,
                bottom = 24.dp
            )
    ) {
        // Sheet 標題
        Column(
            modifier = Modifier.padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        CreateFolderSheetButton(onClick = onCreateFolderClick, enabled = !isMoving)

        Spacer(Modifier.height(16.dp))

        if (isLoading || isMoving) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
        }
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
            if (onRetry != null) TextButton(onClick = onRetry) { Text("重新載入") }
            Spacer(Modifier.height(12.dp))
        }

        // 資料夾列表
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(folders.size, key = { folders[it].id }) { index ->
                val folder = folders[index]

                FolderSheetRow(
                    name = folder.name,
                    bookmarkCount = folder.bookmarkCount,
                    enabled = !isMoving,
                    onClick = {
                        onFolderClick(folder.id)
                    }
                )
            }
        }
    }
}
