package com.projectcit.layout

/** Native video geometry per console. The DS is modelled as two 256×192 screens. */
enum class Platform(val screenWidth: Int, val screenHeight: Int, val screenCount: Int) {
    GB(160, 144, 1),
    GBC(160, 144, 1),
    GBA(240, 160, 1),
    NDS(256, 192, 2);

    val isDualScreen: Boolean get() = screenCount == 2
}

enum class HingeOrientation { VERTICAL, HORIZONTAL }
enum class HingeState { FLAT, HALF_OPENED }

/**
 * Platform-neutral copy of androidx.window FoldingFeature, already converted into
 * the layout root's local pixel space. A Galaxy Z Fold reports a zero-width rect
 * on its seam; dual-panel devices report a non-zero (occluding) rect.
 */
data class HingeInfo(
    val bounds: Rect,
    val orientation: HingeOrientation,
    val state: HingeState,
    val isSeparating: Boolean,
    val occludes: Boolean,
)

enum class SizeClass { COMPACT, MEDIUM, EXPANDED }

data class WindowSpec(
    val widthPx: Float,
    val heightPx: Float,
    val density: Float,
    val insets: Insets = Insets.ZERO,
    val hinge: HingeInfo? = null,
) {
    val widthDp: Float get() = widthPx / density
    val heightDp: Float get() = heightPx / density

    /** Material window-size-class breakpoints (width 600/840dp, height 480/900dp). */
    val widthClass: SizeClass
        get() = when {
            widthDp < 600f -> SizeClass.COMPACT
            widthDp < 840f -> SizeClass.MEDIUM
            else -> SizeClass.EXPANDED
        }
    val heightClass: SizeClass
        get() = when {
            heightDp < 480f -> SizeClass.COMPACT
            heightDp < 900f -> SizeClass.MEDIUM
            else -> SizeClass.EXPANDED
        }
}

enum class Posture {
    /** Candybar phone or Fold cover screen held upright; no usable hinge. */
    PORTRAIT,
    /** Any landscape window without a usable hinge (incl. flat Fold with horizontal seam). */
    LANDSCAPE,
    /** Fold inner screen fully open, seam runs top→bottom. */
    BOOK_FLAT,
    /** Fold half-open like a book, seam runs top→bottom. */
    BOOK_HALF_OPENED,
    /** Flex mode: half-open with a horizontal seam (laptop/clamshell). */
    TABLETOP,
}

/** Only meaningful for NDS when a vertical hinge splits the window. */
enum class NdsBookMode {
    /** DS top screen in left pane, touchscreen in right pane. */
    SIDE_BY_SIDE,
    /** Large primary screen in left pane, small touchscreen above controls in right pane. */
    HYBRID,
}

/** Which DS screen gets the extra space on single-pane layouts. */
enum class NdsEmphasis { BALANCED, TOP_LARGE, BOTTOM_LARGE }

data class LayoutOptions(
    val platform: Platform,
    val ndsBookMode: NdsBookMode = NdsBookMode.SIDE_BY_SIDE,
    val ndsEmphasis: NdsEmphasis = NdsEmphasis.BALANCED,
    /** Put the DS bottom screen where the top screen would normally go (and vice versa). */
    val swapScreens: Boolean = false,
    val integerScaling: Boolean = false,
    /** When a gamepad is connected the touch controls disappear and screens grow. */
    val physicalController: Boolean = false,
    /**
     * Actual size of the core's video frame for single-screen systems, when known.
     * mGBA outputs 256×224 (game + Super Game Boy border) for SGB-enhanced Game Boy
     * games, not 160×144; laying out with the nominal size would stretch the image.
     */
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** Number of round toolbar buttons (fast-forward, swap, layout, menu) to place; 0 = none. */
    val toolbarButtons: Int = 0,
) {
    /** Source size of one screen: the real frame when known (single-screen), else the console's native size. */
    val sourceWidth: Int get() = if (!platform.isDualScreen && frameWidth > 0 && frameHeight > 0) frameWidth else platform.screenWidth
    val sourceHeight: Int get() = if (!platform.isDualScreen && frameWidth > 0 && frameHeight > 0) frameHeight else platform.screenHeight
}

enum class ScreenRole { MAIN, TOP, BOTTOM }

/**
 * Destination quad for one emulated screen, plus the normalized region of the core's
 * video frame it shows. For melonDS DS (forced to its "top-bottom" layout, 256×384)
 * the top screen is V 0..0.5 and the touchscreen V 0.5..1. The Phase 3 renderer
 * samples this region; [PointerMapper] inverts it for touch input.
 */
data class ScreenViewport(
    val role: ScreenRole,
    val rect: Rect,
    val sourceVTop: Float,
    val sourceVBottom: Float,
    val sourceULeft: Float = 0f,
    val sourceURight: Float = 1f,
) {
    val acceptsTouch: Boolean get() = role == ScreenRole.BOTTOM
}

enum class ControlCluster { DPAD, FACE_BUTTONS, SHOULDER_L, SHOULDER_R, SELECT, START }

data class ControlZone(val cluster: ControlCluster, val rect: Rect)

data class LayoutSpec(
    val posture: Posture,
    val content: Rect,
    val panes: List<Rect>,
    val hinge: HingeInfo?,
    val screens: List<ScreenViewport>,
    val controls: List<ControlZone>,
    /** Where the toolbar goes; never overlaps a screen, a control zone or the hinge. Taller than wide = vertical column. */
    val toolbar: Rect? = null,
) {
    fun screen(role: ScreenRole): ScreenViewport? = screens.firstOrNull { it.role == role }
}
