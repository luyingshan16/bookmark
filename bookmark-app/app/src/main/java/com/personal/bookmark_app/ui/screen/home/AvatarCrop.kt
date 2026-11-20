package com.personal.bookmark_app.ui.screen.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import com.personal.bookmark_app.data.repository.user.AVATAR_EDGE
import com.personal.bookmark_app.data.repository.user.MAX_AVATAR_ZOOM
import kotlin.math.roundToInt

internal class AvatarCrop(private val width: Int, private val height: Int) {
    val edge = minOf(width, height).toFloat()
    val outputEdge = minOf(width, height, AVATAR_EDGE)
    val initialFrame = AvatarFrame(Offset((width - edge) / 2f, (height - edge) / 2f), edge)

    fun transform(
        frame: AvatarFrame,
        zoomChange: Float = 1f,
        focus: Offset = Offset(0.5f, 0.5f),
        pan: Offset = Offset.Zero
    ): AvatarFrame {
        val nextEdge = (frame.edge / zoomChange).coerceIn(edge / MAX_AVATAR_ZOOM, edge)
        val origin = frame.origin + focus * (frame.edge - nextEdge) - pan * nextEdge
        return AvatarFrame(
            Offset(origin.x.coerceIn(0f, width - nextEdge), origin.y.coerceIn(0f, height - nextEdge)),
            nextEdge
        )
    }

    fun pixels(frame: AvatarFrame): IntRect {
        val size = frame.edge.roundToInt().coerceAtLeast(1)
        val left = frame.origin.x.roundToInt().coerceAtMost(width - size)
        val top = frame.origin.y.roundToInt().coerceAtMost(height - size)
        return IntRect(left, top, left + size, top + size)
    }
}

internal data class AvatarFrame(val origin: Offset, val edge: Float)
