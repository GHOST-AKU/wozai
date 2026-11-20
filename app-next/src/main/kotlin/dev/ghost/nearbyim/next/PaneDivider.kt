package dev.ghost.nearbyim.next

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.ghost.nearbyim.core.AdaptiveLayout

/** The shared policy measures physical divider coordinates, including the navigation rail. */
internal class PaneWidths(
    val width: Int,
    private val height: Int,
    private val density: Float,
    private val fontScale: Float,
    val rtl: Boolean,
) {
    private fun plan(split: Float) = AdaptiveLayout.compute(width, height, density, fontScale, rtl, true, null, split)
    private val base = plan(Float.NaN)
    val dual = base.dual()
    val railWidth = base.navigation().width()
    private fun edge(plan: AdaptiveLayout.Plan) = (if (rtl) plan.list().left() else plan.list().right()).toFloat()
    val initial = edge(base)
    val min = edge(plan(0f))
    val max = edge(plan(1f))
    val center = (width / 2f).coerceIn(min, max)

    fun divider(fraction: Float): Float =
        if (fraction.isFinite()) (width * (if (rtl) 1f - fraction else fraction)).coerceIn(min, max) else initial

    fun fraction(position: Float): Float {
        val physical = position.coerceIn(min, max) / width
        return if (rtl) 1f - physical else physical
    }

    fun snap(position: Float, latched: Float = Float.NaN): Float =
        AdaptiveLayout.snapDivider(position.coerceIn(min, max),
            (width / 2f).takeIf { it in min..max } ?: Float.NaN, initial, density, latched)
}

@Composable
internal fun PaneDivider(widths: PaneWidths, position: Float, onMove: (Float) -> Unit, onCommit: (Float) -> Unit) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val label = t("paneDivider")
    val centerLabel = t("paneDividerCenter")
    val activePosition by rememberUpdatedState(position)
    val move by rememberUpdatedState(onMove)
    val commit by rememberUpdatedState(onCommit)
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    var dragging by remember { mutableStateOf(false) }
    val handleWidthPx = with(density) { 48.dp.roundToPx() }
    val step = with(density) { 24.dp.toPx() }

    Box(Modifier.absoluteOffset { IntOffset(position.toInt() - handleWidthPx / 2, 0) }
        .width(48.dp).fillMaxHeight().testTag("pane-divider")
        .pointerInput(widths) {
            var original = 0f
            var raw = 0f
            var current = 0f
            var latched = Float.NaN
            detectHorizontalDragGestures(
                onDragStart = {
                    original = activePosition
                    raw = original
                    current = original
                    latched = Float.NaN
                    dragging = true
                },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    raw += amount
                    current = widths.snap(raw, latched)
                    val anchor = current == widths.initial || current == widths.width / 2f
                    if (anchor && current != latched) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    latched = if (anchor) current else Float.NaN
                    move(current)
                },
                onDragCancel = { move(original); dragging = false },
                onDragEnd = { commit(current); dragging = false },
            )
        }
        .onPreviewKeyEvent { event ->
            val direction = when (event.key) {
                Key.DirectionLeft -> -1
                Key.DirectionRight -> 1
                else -> 0
            }
            if (direction != 0 && event.type == KeyEventType.KeyDown) {
                onCommit((position + direction * step).coerceIn(widths.min, widths.max))
                true
            } else false
        }
        .clickable(interactionSource = interactions, indication = null, onClickLabel = centerLabel) {
            onCommit(widths.center)
        }
        .semantics {
            contentDescription = label
            progressBarRangeInfo = ProgressBarRangeInfo(position / widths.width, widths.min / widths.width..widths.max / widths.width)
            setProgress { value ->
                if (!value.isFinite()) false
                else { onCommit((value * widths.width).coerceIn(widths.min, widths.max)); true }
            }
        }, contentAlignment = Alignment.Center) {
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(Modifier.width(4.dp).height(32.dp).background(
            if (dragging || focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline, CircleShape))
    }
}
