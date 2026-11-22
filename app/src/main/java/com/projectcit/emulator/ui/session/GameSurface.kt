package com.projectcit.emulator.ui.session

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.projectcit.emulator.emulation.EmulationController

/**
 * The GL output. Only hands its Surface to the process-scoped controller; the
 * emulation thread renders into it. Fold/unfold/rotate just resize or recreate
 * this surface: the game keeps running regardless.
 */
@Composable
fun GameSurface(controller: EmulationController, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            SurfaceView(ctx).apply {
                keepScreenOn = true
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        controller.attachSurface(holder.surface)
                    }
                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        controller.detachSurface()  // blocks until the GL thread let go of it
                    }
                })
            }
        },
    )
}
