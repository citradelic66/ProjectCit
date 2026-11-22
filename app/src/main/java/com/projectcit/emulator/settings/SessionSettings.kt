package com.projectcit.emulator.settings

import android.content.Context
import com.projectcit.layout.NdsBookMode
import com.projectcit.layout.NdsEmphasis
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class ControllerMode { AUTO, FORCE_ON, FORCE_OFF }

/** In-game preferences, persisted so a layout you picked survives restarts. */
class SessionSettings(context: Context) {

    data class Values(
        val ndsBookMode: NdsBookMode = NdsBookMode.SIDE_BY_SIDE,
        val ndsEmphasis: NdsEmphasis = NdsEmphasis.BALANCED,
        val swapScreens: Boolean = false,
        val integerScaling: Boolean = false,
        val haptics: Boolean = true,
        val padOpacity: Float = 0.55f,
        val debugOverlay: Boolean = false,
        val controllerMode: ControllerMode = ControllerMode.AUTO,
    )

    private val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Values> = _values.asStateFlow()

    fun update(transform: (Values) -> Values) {
        _values.update(transform)
        save(_values.value)
    }

    private fun load(): Values {
        val d = Values()
        fun <T : Enum<T>> enum(key: String, def: T, all: Array<T>) = prefs.getString(key, null)?.let { s -> all.firstOrNull { it.name == s } } ?: def
        return Values(
            ndsBookMode = enum("bookMode", d.ndsBookMode, NdsBookMode.entries.toTypedArray()),
            ndsEmphasis = enum("emphasis", d.ndsEmphasis, NdsEmphasis.entries.toTypedArray()),
            swapScreens = prefs.getBoolean("swap", d.swapScreens),
            integerScaling = prefs.getBoolean("integer", d.integerScaling),
            haptics = prefs.getBoolean("haptics", d.haptics),
            padOpacity = prefs.getFloat("padOpacity", d.padOpacity),
            debugOverlay = prefs.getBoolean("debugOverlay", d.debugOverlay),
            controllerMode = enum("controller", d.controllerMode, ControllerMode.entries.toTypedArray()),
        )
    }

    private fun save(v: Values) {
        prefs.edit()
            .putString("bookMode", v.ndsBookMode.name).putString("emphasis", v.ndsEmphasis.name)
            .putBoolean("swap", v.swapScreens).putBoolean("integer", v.integerScaling)
            .putBoolean("haptics", v.haptics).putFloat("padOpacity", v.padOpacity)
            .putBoolean("debugOverlay", v.debugOverlay).putString("controller", v.controllerMode.name)
            .apply()
    }
}
