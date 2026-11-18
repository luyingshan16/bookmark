package com.personal.bookmark_app.ui.components.bookmark

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.personal.bookmark_app.data.remote.dto.response.folder.FolderResponse
import com.personal.bookmark_app.ui.components.AddFolderDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

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
        containerColor = MaterialTheme.colorScheme.surface
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
            modifier = Modifier.padding(bottom = 20.dp),
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
        // 新建收藏夾
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !isMoving, onClick = onCreateFolderClick),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(vertical = 13.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )

                Spacer(Modifier.width(6.dp))

                Text(
                    text = "新建收藏夾",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(Modifier.height(20.dp))

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

                MoveFolderItem(
                    folder = folder,
                    enabled = !isMoving,
                    onClick = {
                        onFolderClick(folder.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun MoveFolderItem(
    folder: FolderResponse,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 資料夾圖示
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Folder,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(21.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            // 資料夾名稱
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(Modifier.weight(1f))

            // 前往箭頭
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
