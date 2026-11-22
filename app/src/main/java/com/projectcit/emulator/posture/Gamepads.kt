package com.projectcit.emulator.posture

import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.InputDevice
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/** True while a physical (USB-C / Bluetooth) gamepad or joystick is attached. Updates on hot-plug. */
@Composable
fun rememberGamepadConnected(): Boolean {
    val context = LocalContext.current
    var connected by remember { mutableStateOf(anyGamepad()) }
    DisposableEffect(context) {
        val im = context.getSystemService(InputManager::class.java)
        val listener = object : InputManager.InputDeviceListener {
            override fun onInputDeviceAdded(deviceId: Int) { connected = anyGamepad() }
            override fun onInputDeviceRemoved(deviceId: Int) { connected = anyGamepad() }
            override fun onInputDeviceChanged(deviceId: Int) { connected = anyGamepad() }
        }
        im.registerInputDeviceListener(listener, Handler(Looper.getMainLooper()))
        onDispose { im.unregisterInputDeviceListener(listener) }
    }
    return connected
}

private fun anyGamepad(): Boolean = InputDevice.getDeviceIds().any { id ->
    val d = InputDevice.getDevice(id) ?: return@any false
    !d.isVirtual && (d.supportsSource(InputDevice.SOURCE_GAMEPAD) || d.supportsSource(InputDevice.SOURCE_JOYSTICK))
}
