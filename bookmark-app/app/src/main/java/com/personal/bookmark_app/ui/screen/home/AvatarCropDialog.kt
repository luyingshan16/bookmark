package com.personal.bookmark_app.ui.screen.home

import android.graphics.Bitmap
import android.graphics.Canvas as BitmapCanvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.personal.bookmark_app.data.repository.user.AvatarImage
import com.personal.bookmark_app.data.repository.user.MAX_AVATAR_ZOOM
import com.personal.bookmark_app.data.repository.user.readAvatar
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun AvatarCropDialog(uri: Uri, onDismiss: () -> Unit, onConfirm: (AvatarImage) -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val result by produceState<Result<AvatarImage>?>(null, uri, resolver) {
        value = try {
            Result.success(withContext(Dispatchers.IO) { resolver.readAvatar(uri) })
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Result.failure(error)
        }
    }
    val image = result?.getOrNull()
    val crop = remember(image) { image?.let { AvatarCrop(it.bitmap.width, it.bitmap.height) } }
    val frame = remember(crop) { crop?.let { mutableStateOf(it.initialFrame) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("調整頭像") },
        text = {
            when {
                image != null && crop != null && frame != null -> Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("雙指或使用滑桿縮放，拖曳照片調整取景")
                    val preview = remember(image) { image.bitmap.asImageBitmap() }
                    Canvas(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .widthIn(max = 256.dp)
                            .aspectRatio(1f)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .border(1.dp, MaterialTheme.colorScheme.outline)
                            .semantics {
                                contentDescription = "頭像取景預覽"
                                customActions = listOf(
                                    "向左移動照片" to Offset(-0.25f, 0f),
                                    "向右移動照片" to Offset(0.25f, 0f),
                                    "向上移動照片" to Offset(0f, -0.25f),
                                    "向下移動照片" to Offset(0f, 0.25f)
                                ).map { (label, pan) ->
                                    CustomAccessibilityAction(label) {
                                        val previous = frame.value
                                        frame.value = crop.transform(previous, pan = pan)
                                        frame.value != previous
                                    }
                                }
                            }
                            .pointerInput(crop) {
                                detectTransformGestures(panZoomLock = true) { focus, pan, zoom, _ ->
                                    val viewport = size.width.toFloat()
                                    frame.value = crop.transform(frame.value, zoom, focus / viewport, pan / viewport)
                                }
                            }
                    ) {
                        val pixels = crop.pixels(frame.value)
                        drawImage(
                            image = preview,
                            srcOffset = IntOffset(pixels.left, pixels.top),
                            srcSize = pixels.size,
                            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
                            filterQuality = FilterQuality.Medium
                        )
                    }
                    Slider(
                        value = (crop.edge / frame.value.edge).coerceIn(1f, MAX_AVATAR_ZOOM),
                        onValueChange = { zoom ->
                            frame.value = crop.transform(frame.value, zoomChange = zoom * frame.value.edge / crop.edge)
                        },
                        valueRange = 1f..MAX_AVATAR_ZOOM,
                        modifier = Modifier
                            .widthIn(max = 256.dp)
                            .fillMaxWidth()
                            .semantics { contentDescription = "頭像縮放比例" }
                    )
                }
                result == null -> CircularProgressIndicator()
                else -> Text(
                    result?.exceptionOrNull()?.let { (it as? IllegalArgumentException)?.message }
                        ?: "無法讀取所選的圖片，請選擇 JPG、PNG 或 WebP 圖片。"
                )
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        confirmButton = {
            TextButton(
                enabled = image != null,
                onClick = {
                    if (image != null && crop != null && frame != null) {
                        val bitmap = cropBitmap(image.bitmap, crop.pixels(frame.value), crop.outputEdge)
                        onConfirm(image.copy(bitmap = bitmap))
                    }
                }
            ) { Text("確認裁切") }
        }
    )
}

private fun cropBitmap(source: Bitmap, crop: IntRect, edge: Int): Bitmap {
    if (crop.width == edge && source.width == edge && source.height == edge) return source

    val result = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
    BitmapCanvas(result).drawBitmap(
        source,
        Rect(crop.left, crop.top, crop.right, crop.bottom),
        Rect(0, 0, edge, edge),
        Paint(Paint.FILTER_BITMAP_FLAG)
    )
    return result
}
