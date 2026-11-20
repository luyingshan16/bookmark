package com.personal.bookmark_app.ui.screen.home

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Test

class AvatarCropTest {
    @Test
    fun landscapeDragMovesAcrossSourcePixelsAndStopsAtEitherEdge() {
        val crop = AvatarCrop(width = 1200, height = 800)
        val center = crop.initialFrame

        assertEquals(IntRect(200, 0, 1000, 800), crop.pixels(center))
        assertEquals(100, crop.pixels(crop.transform(center, pan = Offset(0.125f, 0f))).left)
        assertEquals(0, crop.pixels(crop.transform(center, pan = Offset(1f, 1f))).left)
        assertEquals(400, crop.pixels(crop.transform(center, pan = Offset(-1f, -1f))).left)
    }

    @Test
    fun portraitAndSquareRemainAlignedWithTheFixedFrame() {
        val portrait = AvatarCrop(width = 120, height = 180)
        val frame = portrait.transform(portrait.initialFrame, pan = Offset(0.5f, -0.5f))
        assertEquals(IntRect(0, 60, 120, 180), portrait.pixels(frame))

        val square = AvatarCrop(120, 120)
        assertEquals(square.initialFrame, square.transform(square.initialFrame, pan = Offset(0.5f, 0.5f)))
    }

    @Test
    fun zoomKeepsPinchFocusAndAllowsPanningOnBothAxesWithoutChangingOutputSize() {
        val crop = AvatarCrop(900, 900)
        val zoomed = crop.transform(crop.initialFrame, zoomChange = 2f, focus = Offset(0.25f, 0.75f))

        assertEquals(IntRect(113, 338, 563, 788), crop.pixels(zoomed))
        val panned = crop.transform(zoomed, pan = Offset(-1f, 1f))
        assertEquals(IntRect(450, 0, 900, 450), crop.pixels(panned))
        assertEquals(512, crop.outputEdge)
    }

    @Test
    fun zoomStopsAtThreeTimesAndReturnsToFullFrame() {
        val crop = AvatarCrop(300, 300)
        val maxZoom = crop.transform(crop.initialFrame, zoomChange = 10f)

        assertEquals(IntRect(100, 100, 200, 200), crop.pixels(maxZoom))
        assertEquals(crop.initialFrame, crop.transform(maxZoom, zoomChange = 0.1f))
        assertEquals(300, crop.outputEdge)
    }
}
