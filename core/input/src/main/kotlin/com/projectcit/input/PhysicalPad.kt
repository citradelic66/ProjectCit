package com.projectcit.input

import kotlin.math.abs

/** Android KeyEvent / MotionEvent constants (stable platform ABI), kept here so mapping is JVM-testable. */
object AndroidInput {
    const val KEYCODE_DPAD_UP = 19
    const val KEYCODE_DPAD_DOWN = 20
    const val KEYCODE_DPAD_LEFT = 21
    const val KEYCODE_DPAD_RIGHT = 22
    const val KEYCODE_DPAD_CENTER = 23
    const val KEYCODE_A = 29
    const val KEYCODE_Q = 45
    const val KEYCODE_S = 47
    const val KEYCODE_W = 51
    const val KEYCODE_X = 52
    const val KEYCODE_Z = 54
    const val KEYCODE_SHIFT_RIGHT = 60
    const val KEYCODE_TAB = 61
    const val KEYCODE_SPACE = 62
    const val KEYCODE_ENTER = 66
    const val KEYCODE_DEL = 67
    const val KEYCODE_BUTTON_A = 96
    const val KEYCODE_BUTTON_B = 97
    const val KEYCODE_BUTTON_X = 99
    const val KEYCODE_BUTTON_Y = 100
    const val KEYCODE_BUTTON_L1 = 102
    const val KEYCODE_BUTTON_R1 = 103
    const val KEYCODE_BUTTON_L2 = 104
    const val KEYCODE_BUTTON_R2 = 105
    const val KEYCODE_BUTTON_THUMBL = 106
    const val KEYCODE_BUTTON_THUMBR = 107
    const val KEYCODE_BUTTON_START = 108
    const val KEYCODE_BUTTON_SELECT = 109
    const val KEYCODE_BUTTON_MODE = 110
    const val KEYCODE_ESCAPE = 111
    const val KEYCODE_F1 = 131

    const val AXIS_X = 0
    const val AXIS_Y = 1
    const val AXIS_HAT_X = 15
    const val AXIS_HAT_Y = 16
    const val AXIS_LTRIGGER = 17
    const val AXIS_RTRIGGER = 18
    const val AXIS_GAS = 22
    const val AXIS_BRAKE = 23
}

sealed interface PadAction {
    data class Button(val button: PadButton) : PadAction
    data class Shortcut(val hotkey: Hotkey) : PadAction
}

/**
 * Key code -> action table. Default is *positional* ("Nintendo") mapping: the
 * bottom face button of an Xbox/PlayStation-style pad (Android BUTTON_A) is the
 * GBA/DS **B**, the right one is **A**, so buttons sit where the hardware has them.
 * GBA and DS games never use L2/R2, so those triggers are hotkeys.
 */
class PadBindings(val map: Map<Int, PadAction>) {
    companion object {
        private fun b(button: PadButton) = PadAction.Button(button)
        private fun h(hotkey: Hotkey) = PadAction.Shortcut(hotkey)
        val DEFAULT = PadBindings(
            mapOf(
                AndroidInput.KEYCODE_BUTTON_A to b(PadButton.B),
                AndroidInput.KEYCODE_BUTTON_B to b(PadButton.A),
                AndroidInput.KEYCODE_BUTTON_X to b(PadButton.Y),
                AndroidInput.KEYCODE_BUTTON_Y to b(PadButton.X),
                AndroidInput.KEYCODE_BUTTON_L1 to b(PadButton.L),
                AndroidInput.KEYCODE_BUTTON_R1 to b(PadButton.R),
                AndroidInput.KEYCODE_BUTTON_START to b(PadButton.START),
                AndroidInput.KEYCODE_BUTTON_SELECT to b(PadButton.SELECT),
                AndroidInput.KEYCODE_DPAD_UP to b(PadButton.UP),
                AndroidInput.KEYCODE_DPAD_DOWN to b(PadButton.DOWN),
                AndroidInput.KEYCODE_DPAD_LEFT to b(PadButton.LEFT),
                AndroidInput.KEYCODE_DPAD_RIGHT to b(PadButton.RIGHT),
                AndroidInput.KEYCODE_BUTTON_L2 to h(Hotkey.SWAP_SCREENS),
                AndroidInput.KEYCODE_BUTTON_R2 to h(Hotkey.FAST_FORWARD),
                AndroidInput.KEYCODE_BUTTON_MODE to h(Hotkey.MENU),
                AndroidInput.KEYCODE_BUTTON_THUMBR to h(Hotkey.MENU),
                // Keyboard (Chromebook, Samsung DeX, Bluetooth keyboards)
                AndroidInput.KEYCODE_X to b(PadButton.A),
                AndroidInput.KEYCODE_Z to b(PadButton.B),
                AndroidInput.KEYCODE_S to b(PadButton.X),
                AndroidInput.KEYCODE_A to b(PadButton.Y),
                AndroidInput.KEYCODE_Q to b(PadButton.L),
                AndroidInput.KEYCODE_W to b(PadButton.R),
                AndroidInput.KEYCODE_ENTER to b(PadButton.START),
                AndroidInput.KEYCODE_SHIFT_RIGHT to b(PadButton.SELECT),
                AndroidInput.KEYCODE_DEL to b(PadButton.SELECT),
                AndroidInput.KEYCODE_TAB to h(Hotkey.FAST_FORWARD),
                AndroidInput.KEYCODE_ESCAPE to h(Hotkey.MENU),
                AndroidInput.KEYCODE_F1 to h(Hotkey.SWAP_SCREENS),
            ),
        )
    }
}

/**
 * Physical controller + keyboard state, merged across devices. Sticks and hats
 * drive the D-pad (GBA/DS have no analog input) with hysteresis so a stick
 * resting near the threshold doesn't chatter. Analog triggers act like L2/R2.
 */
class PhysicalPad(private val bindings: PadBindings = PadBindings.DEFAULT) {

    data class Update(val mask: Int, val fastForwardHeld: Boolean, val triggered: List<Hotkey>)

    private val keysDown = HashSet<Int>()
    private val axisMasks = HashMap<Int, Int>()          // deviceId -> dpad bits from sticks/hats
    private val triggerDown = HashMap<Int, Pair<Boolean, Boolean>>()  // deviceId -> (L2, R2) analog

    val mask: Int
        get() = keysDown.fold(0) { m, k -> (bindings.map[k] as? PadAction.Button)?.let { m or it.button.bit } ?: m } or
            axisMasks.values.fold(0) { m, v -> m or v }

    val fastForwardHeld: Boolean
        get() = keysDown.any { (bindings.map[it] as? PadAction.Shortcut)?.hotkey == Hotkey.FAST_FORWARD } ||
            triggerDown.values.any { it.second && bindings.map[AndroidInput.KEYCODE_BUTTON_R2] == PadAction.Shortcut(Hotkey.FAST_FORWARD) }

    fun handles(keyCode: Int): Boolean = keyCode in bindings.map

    /** @return null if the key is not bound (let the system handle it). */
    fun onKey(keyCode: Int, down: Boolean, repeatCount: Int = 0): Update? {
        val action = bindings.map[keyCode] ?: return null
        val triggered = mutableListOf<Hotkey>()
        if (down) {
            val isNew = keysDown.add(keyCode) && repeatCount == 0
            if (isNew && action is PadAction.Shortcut && action.hotkey != Hotkey.FAST_FORWARD) triggered += action.hotkey
        } else {
            keysDown.remove(keyCode)
        }
        return Update(mask, fastForwardHeld, triggered)
    }

    /** Feed the latest axis values of one device (unset axes = 0). */
    fun onAxes(deviceId: Int, axes: Map<Int, Float>): Update {
        val prev = axisMasks[deviceId] ?: 0
        val x = axes[AndroidInput.AXIS_X] ?: 0f
        val y = axes[AndroidInput.AXIS_Y] ?: 0f
        val hx = axes[AndroidInput.AXIS_HAT_X] ?: 0f
        val hy = axes[AndroidInput.AXIS_HAT_Y] ?: 0f
        var m = 0
        m = m or dir(x, prev, PadButton.LEFT, PadButton.RIGHT) or dir(hx, prev, PadButton.LEFT, PadButton.RIGHT, hat = true)
        m = m or dir(y, prev, PadButton.UP, PadButton.DOWN) or dir(hy, prev, PadButton.UP, PadButton.DOWN, hat = true)
        axisMasks[deviceId] = m

        val (wasL, wasR) = triggerDown[deviceId] ?: (false to false)
        val l = maxOf(axes[AndroidInput.AXIS_LTRIGGER] ?: 0f, axes[AndroidInput.AXIS_BRAKE] ?: 0f)
        val r = maxOf(axes[AndroidInput.AXIS_RTRIGGER] ?: 0f, axes[AndroidInput.AXIS_GAS] ?: 0f)
        val nowL = if (wasL) l > RELEASE else l > PRESS
        val nowR = if (wasR) r > RELEASE else r > PRESS
        triggerDown[deviceId] = nowL to nowR
        val triggered = mutableListOf<Hotkey>()
        if (nowL && !wasL) (bindings.map[AndroidInput.KEYCODE_BUTTON_L2] as? PadAction.Shortcut)?.hotkey
            ?.takeIf { it != Hotkey.FAST_FORWARD }?.let(triggered::add)
        return Update(mask, fastForwardHeld, triggered)
    }

    fun deviceRemoved(deviceId: Int) {
        axisMasks.remove(deviceId)
        triggerDown.remove(deviceId)
    }

    fun reset() {
        keysDown.clear(); axisMasks.clear(); triggerDown.clear()
    }

    private fun dir(v: Float, prev: Int, neg: PadButton, pos: PadButton, hat: Boolean = false): Int {
        if (hat) return when { v < -0.5f -> neg.bit; v > 0.5f -> pos.bit; else -> 0 }
        val wasNeg = prev and neg.bit != 0
        val wasPos = prev and pos.bit != 0
        return when {
            v < 0 && abs(v) > (if (wasNeg) RELEASE else PRESS) -> neg.bit
            v > 0 && v > (if (wasPos) RELEASE else PRESS) -> pos.bit
            else -> 0
        }
    }

    private companion object {
        const val PRESS = 0.5f
        const val RELEASE = 0.35f
    }
}
