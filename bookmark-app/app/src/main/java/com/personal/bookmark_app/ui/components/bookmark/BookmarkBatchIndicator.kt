package com.personal.bookmark_app.ui.components.bookmark

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun BookmarkBatchIndicator(
    title: String,
    processed: Int,
    total: Int,
    onBackground: (() -> Unit)? = null
) {
    Dialog(
        onDismissRequest = { onBackground?.invoke() },
        properties = DialogProperties(
            dismissOnBackPress = onBackground != null,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainer
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "已處理 $processed／$total 筆",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { processed.toFloat() / total.coerceAtLeast(1) },
                    modifier = Modifier.fillMaxWidth()
                )
                if (onBackground != null) {
                    TextButton(onClick = onBackground, modifier = Modifier.align(Alignment.End)) {
                        Text("在背景執行")
                    }
                }
            }
        }
    }
}
