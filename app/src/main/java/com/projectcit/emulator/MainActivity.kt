package com.projectcit.emulator

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.projectcit.emulator.ui.AppRoot
import com.projectcit.emulator.ui.theme.ProjectCitTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ProjectCitTheme { AppRoot() } }
    }
}
