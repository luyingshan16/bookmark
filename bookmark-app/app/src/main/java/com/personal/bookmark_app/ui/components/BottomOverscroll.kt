package com.personal.bookmark_app.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun BottomOverscroll(enabled: Boolean, content: @Composable (Modifier) -> Unit) {
    val scope = rememberCoroutineScope()
    val maxStretch = with(LocalDensity.current) { 72.dp.toPx() }
    val effect = remember(scope, maxStretch, enabled) {
        object : NestedScrollConnection {
            var translation by mutableFloatStateOf(0f)
                private set
            private var settleJob: Job? = null
            private var flingingTowardBottom = false

            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || translation >= 0f) return Offset.Zero
                settleJob?.cancel()
                val restored = available.y.coerceIn(0f, -translation)
                translation += restored
                return Offset(0f, restored)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0f) return Offset.Zero
                val dragging = source == NestedScrollSource.UserInput
                if (!dragging && !flingingTowardBottom) return Offset.Zero

                val remaining = 1f + translation / maxStretch
                translation = (translation + available.y * 0.4f * remaining).coerceAtLeast(-maxStretch)
                return if (dragging) Offset(0f, available.y) else Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                flingingTowardBottom = available.y < 0f && translation == 0f
                settle()
                return Velocity.Zero
            }

            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
                flingingTowardBottom = false
                if (settleJob?.isActive != true) settle()
                return Velocity.Zero
            }

            private fun settle() {
                if (translation == 0f) return
                settleJob?.cancel()
                settleJob = scope.launch {
                    animate(
                        initialValue = translation,
                        targetValue = 0f,
                        animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
                    ) { value, _ -> translation = value.coerceAtMost(0f) }
                }
            }
        }
    }
    val modifier = if (enabled) {
        Modifier.clipToBounds().nestedScroll(effect).graphicsLayer { translationY = effect.translation }
    } else {
        Modifier
    }
    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
        content(modifier)
    }
}
