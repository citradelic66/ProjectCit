package com.projectcit.emulator

import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.projectcit.emulator.ui.AppRoot
import com.projectcit.emulator.ui.theme.ProjectCitTheme
import com.projectcit.input.AndroidInput

class MainActivity : ComponentActivity() {
    private val emulation get() = (application as ProjectCitApp).emulation

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ProjectCitTheme { AppRoot() } }
        // See controller/keyboard input before views do (Compose would otherwise use
        // D-pad keys for focus navigation). Wrapping the window callback is the
        // public hook; ComponentActivity.dispatchKeyEvent is restricted API.
        window.callback = GameInputCallback(window.callback)
    }

    private inner class GameInputCallback(private val base: Window.Callback) : Window.Callback by base {
        /**
         * Physical controllers and keyboards go straight to the game while one is
         * running. Unbound keys (volume, the touch navigation bar's Back) fall through.
         */
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            val fromPhysical = event.device?.isVirtual == false || event.isFromSource(InputDevice.SOURCE_GAMEPAD)
            if (fromPhysical && event.action != KeyEvent.ACTION_MULTIPLE &&
                emulation.onKey(event.keyCode, event.action == KeyEvent.ACTION_DOWN, event.repeatCount)
            ) return true
            return base.dispatchKeyEvent(event)
        }

        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (event.isFromSource(InputDevice.SOURCE_JOYSTICK) && event.action == MotionEvent.ACTION_MOVE) {
                val axes = AXES.associateWith { event.getAxisValue(it) }
                if (emulation.onAxes(event.deviceId, axes)) return true
            }
            return base.dispatchGenericMotionEvent(event)
        }
    }

    private companion object {
        val AXES = listOf(
            AndroidInput.AXIS_X, AndroidInput.AXIS_Y, AndroidInput.AXIS_HAT_X, AndroidInput.AXIS_HAT_Y,
            AndroidInput.AXIS_LTRIGGER, AndroidInput.AXIS_RTRIGGER, AndroidInput.AXIS_GAS, AndroidInput.AXIS_BRAKE,
        )
    }
}
