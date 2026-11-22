package com.projectcit.input

/**
 * libretro RETRO_DEVICE_ID_JOYPAD_* ids. The bit layout of [mask] is exactly what
 * the native host expects (bit n = id n), so masks pass straight to setButtons().
 */
enum class PadButton(val id: Int, val label: String) {
    B(0, "B"), Y(1, "Y"), SELECT(2, "SELECT"), START(3, "START"),
    UP(4, "↑"), DOWN(5, "↓"), LEFT(6, "←"), RIGHT(7, "→"),
    A(8, "A"), X(9, "X"), L(10, "L"), R(11, "R"),
    L2(12, "L2"), R2(13, "R2"), L3(14, "L3"), R3(15, "R3");

    val bit: Int get() = 1 shl id

    companion object {
        fun mask(buttons: Iterable<PadButton>): Int = buttons.fold(0) { m, b -> m or b.bit }
        fun fromMask(mask: Int): Set<PadButton> = entries.filterTo(mutableSetOf()) { mask and it.bit != 0 }
    }
}

/** Actions that control the emulator rather than the game. */
enum class Hotkey { MENU, SWAP_SCREENS, FAST_FORWARD }
