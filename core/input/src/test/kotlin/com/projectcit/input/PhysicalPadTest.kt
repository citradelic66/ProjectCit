package com.projectcit.input

import com.projectcit.input.AndroidInput as K
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhysicalPadTest {
    private val pad = PhysicalPad()

    @Test fun libretroIdsMatchTheNativeAbi() {
        // RETRO_DEVICE_ID_JOYPAD_* from libretro.h
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11), listOf(PadButton.B, PadButton.Y, PadButton.SELECT, PadButton.START,
            PadButton.UP, PadButton.DOWN, PadButton.LEFT, PadButton.RIGHT, PadButton.A, PadButton.X, PadButton.L, PadButton.R).map { it.id })
    }

    @Test fun positionalMappingPutsButtonsWhereTheHardwareHasThem() {
        assertEquals(PadButton.B.bit, pad.onKey(K.KEYCODE_BUTTON_A, true)!!.mask)   // bottom face button
        pad.onKey(K.KEYCODE_BUTTON_A, false)
        assertEquals(PadButton.A.bit, pad.onKey(K.KEYCODE_BUTTON_B, true)!!.mask)   // right face button
        pad.onKey(K.KEYCODE_BUTTON_Y, true)
        assertEquals(PadButton.A.bit or PadButton.X.bit, pad.mask)                 // top face button = X
    }

    @Test fun unboundKeysAreLeftToTheSystem() {
        assertNull(pad.onKey(24 /* VOLUME_UP */, true))
        assertFalse(pad.handles(4 /* BACK */))
    }

    @Test fun hotkeysFireOnceAndFastForwardIsHeld() {
        assertEquals(listOf(Hotkey.MENU), pad.onKey(K.KEYCODE_BUTTON_MODE, true)!!.triggered)
        assertEquals(emptyList<Hotkey>(), pad.onKey(K.KEYCODE_BUTTON_MODE, true, repeatCount = 1)!!.triggered)
        assertEquals(listOf(Hotkey.SWAP_SCREENS), pad.onKey(K.KEYCODE_BUTTON_L2, true)!!.triggered)
        assertTrue(pad.onKey(K.KEYCODE_BUTTON_R2, true)!!.fastForwardHeld)
        assertEquals(0, pad.mask)                         // hotkeys never leak into the game
        assertFalse(pad.onKey(K.KEYCODE_BUTTON_R2, false)!!.fastForwardHeld)
    }

    @Test fun stickHasHysteresisAndHatIsDigital() {
        assertEquals(0, pad.onAxes(5, mapOf(K.AXIS_X to 0.45f)).mask)
        assertEquals(PadButton.RIGHT.bit, pad.onAxes(5, mapOf(K.AXIS_X to 0.6f)).mask)
        assertEquals(PadButton.RIGHT.bit, pad.onAxes(5, mapOf(K.AXIS_X to 0.4f)).mask)   // held through the dead band
        assertEquals(0, pad.onAxes(5, mapOf(K.AXIS_X to 0.3f)).mask)
        assertEquals(PadButton.UP.bit or PadButton.LEFT.bit, pad.onAxes(5, mapOf(K.AXIS_HAT_X to -1f, K.AXIS_HAT_Y to -1f)).mask)
    }

    @Test fun analogTriggersActAsHotkeysAndDevicesMerge() {
        assertTrue(pad.onAxes(1, mapOf(K.AXIS_RTRIGGER to 0.9f)).fastForwardHeld)
        assertEquals(listOf(Hotkey.SWAP_SCREENS), pad.onAxes(2, mapOf(K.AXIS_LTRIGGER to 1f)).triggered)
        assertEquals(emptyList<Hotkey>(), pad.onAxes(2, mapOf(K.AXIS_LTRIGGER to 1f)).triggered)  // no repeat while held
        pad.onAxes(3, mapOf(K.AXIS_Y to 1f))
        pad.onKey(K.KEYCODE_X, true)                      // keyboard + controller at once
        assertEquals(PadButton.DOWN.bit or PadButton.A.bit, pad.mask)
        pad.deviceRemoved(3)
        assertEquals(PadButton.A.bit, pad.mask)
    }
}
