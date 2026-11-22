package com.projectcit.emulator.posture

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import androidx.window.layout.WindowLayoutInfo
import com.projectcit.layout.HingeInfo
import com.projectcit.layout.HingeOrientation
import com.projectcit.layout.HingeState
import com.projectcit.layout.Insets
import com.projectcit.layout.Rect
import com.projectcit.layout.WindowSpec

/** Lets the hinge visualizer fake a fold on any phone or emulator. */
enum class PostureSource(val label: String) {
    REAL("Real sensor"),
    SIM_NONE("Sim: no fold"),
    SIM_BOOK_FLAT("Sim: book flat"),
    SIM_BOOK_HALF("Sim: book half"),
    SIM_TABLETOP("Sim: tabletop"),
}

/** Raw FoldingFeature fields, kept for the debug HUD. */
data class RawFold(
    val boundsInWindow: android.graphics.Rect,
    val state: String,
    val orientation: String,
    val isSeparating: Boolean,
    val occlusion: String,
)

/**
 * Real-time WindowLayoutInfo stream. androidx.window has no
 * `collectFoldingFeaturesAsState`; this is the equivalent built on
 * WindowInfoTracker + collectAsStateWithLifecycle.
 */
@Composable
fun rememberWindowLayoutInfo(): WindowLayoutInfo? {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val flow = remember(activity) { WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity) }
    val info by flow.collectAsStateWithLifecycle(initialValue = null)
    return info
}

fun WindowLayoutInfo?.foldingFeature(): FoldingFeature? =
    this?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull()

fun FoldingFeature.toRaw() = RawFold(
    boundsInWindow = bounds,
    state = state.toString(),
    orientation = orientation.toString(),
    isSeparating = isSeparating,
    occlusion = occlusionType.toString(),
)

/**
 * Builds the engine input for a layout root at [originInWindow] with [size].
 * FoldingFeature bounds are in *window* coordinates; they are translated into the
 * root's local space here so the engine never sees Android types.
 */
@Composable
fun rememberWindowSpec(
    info: WindowLayoutInfo?,
    originInWindow: Offset,
    size: IntSize,
    source: PostureSource,
): WindowSpec {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val safe = WindowInsets.safeDrawing
    val insets = Insets(
        safe.getLeft(density, dir).toFloat(),
        safe.getTop(density).toFloat(),
        safe.getRight(density, dir).toFloat(),
        safe.getBottom(density).toFloat(),
    )
    val w = size.width.toFloat()
    val h = size.height.toFloat()
    val hinge: HingeInfo? = when (source) {
        PostureSource.REAL -> info.foldingFeature()?.let { f ->
            val b = f.bounds
            HingeInfo(
                bounds = Rect(b.left - originInWindow.x, b.top - originInWindow.y, b.right - originInWindow.x, b.bottom - originInWindow.y),
                orientation = if (f.orientation == FoldingFeature.Orientation.VERTICAL) HingeOrientation.VERTICAL else HingeOrientation.HORIZONTAL,
                state = if (f.state == FoldingFeature.State.HALF_OPENED) HingeState.HALF_OPENED else HingeState.FLAT,
                isSeparating = f.isSeparating,
                occludes = f.occlusionType == FoldingFeature.OcclusionType.FULL,
            )
        }
        PostureSource.SIM_NONE -> null
        PostureSource.SIM_BOOK_FLAT -> HingeInfo(Rect(w / 2, 0f, w / 2, h), HingeOrientation.VERTICAL, HingeState.FLAT, false, false)
        PostureSource.SIM_BOOK_HALF -> HingeInfo(Rect(w / 2, 0f, w / 2, h), HingeOrientation.VERTICAL, HingeState.HALF_OPENED, true, false)
        PostureSource.SIM_TABLETOP -> HingeInfo(Rect(0f, h / 2, w, h / 2), HingeOrientation.HORIZONTAL, HingeState.HALF_OPENED, true, false)
    }
    return WindowSpec(w, h, density.density, insets, hinge)
}

tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No Activity in context chain")
}
