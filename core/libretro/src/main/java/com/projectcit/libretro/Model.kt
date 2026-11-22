package com.projectcit.libretro

class LibretroException(message: String) : RuntimeException(message)

data class CoreSystemInfo(
    val libraryName: String,
    val libraryVersion: String,
    val validExtensions: List<String>,
    /** Core opens the file itself and cannot take a memory buffer. */
    val needFullPath: Boolean,
)

/** Pixel format of frames handed to the renderer (legacy 0RGB1555 is converted to RGB565). */
enum class FrameFormat { RGB565, XRGB8888 }

data class AvInfo(
    val baseWidth: Int,
    val baseHeight: Int,
    val maxWidth: Int,
    val maxHeight: Int,
    val aspectRatio: Float,
    /** Exact console rate, e.g. 59.7275 (GBA) / 59.8261 (NDS). Drives Phase 3 pacing. */
    val fps: Double,
    val sampleRate: Double,
)

data class FrameInfo(val width: Int, val height: Int, val format: FrameFormat, val sequence: Long)

/** Front frame as ARGB_8888 ints, ready for Bitmap.createBitmap(pixels, width, height, ARGB_8888). */
class ArgbFrame(val width: Int, val height: Int, val pixels: IntArray)

data class AudioStats(val bufferedFrames: Long, val capacityFrames: Long, val droppedFrames: Long)

data class CoreOption(
    val key: String,
    val value: String,
    val defaultValue: String,
    val description: String,
    val values: List<String>,
    val visible: Boolean,
)

/** libretro RETRO_DEVICE_ID_JOYPAD_* ids. Build masks with [mask]. */
enum class RetroButton(val id: Int) {
    B(0), Y(1), SELECT(2), START(3), UP(4), DOWN(5), LEFT(6), RIGHT(7),
    A(8), X(9), L(10), R(11), L2(12), R2(13), L3(14), R3(15);

    val mask: Int get() = 1 shl id

    companion object {
        fun mask(vararg buttons: RetroButton): Int = buttons.fold(0) { acc, b -> acc or b.mask }
    }
}
