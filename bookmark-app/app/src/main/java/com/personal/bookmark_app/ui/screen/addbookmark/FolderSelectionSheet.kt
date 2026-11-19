package com.personal.bookmark_app.ui.screen.addbookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.personal.bookmark_app.ui.components.CreateFolderSheetButton
import com.personal.bookmark_app.ui.components.FolderSheetRow
import com.personal.bookmark_app.ui.model.FolderUiModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FolderSelectionSheet(
    folders: List<FolderUiModel>,
    selectedFolderId: Long,
    onDismiss: () -> Unit,
    onFolderSelected: (Long) -> Unit,
    onCreateFolderClick: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(
            topStart = 20.dp,
            topEnd = 20.dp
        ),
        dragHandle = {
            BottomSheetDefaults.DragHandle(
                color = MaterialTheme.colorScheme.outlineVariant
            )
        }
    ) {

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxHeight * 0.8f)
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp)
            ) {
                Text(
                    text = "選擇收藏夾",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "選取欲歸屬的收藏分類",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))

                CreateFolderSheetButton(onClick = onCreateFolderClick)

                if (folders.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(items = folders, key = { it.id }) { folder ->
                            FolderSheetRow(
                                name = folder.title,
                                bookmarkCount = folder.count,
                                isSelected = folder.id == selectedFolderId,
                                onClick = { onFolderSelected(folder.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}
