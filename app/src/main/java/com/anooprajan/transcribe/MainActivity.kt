package com.anooprajan.transcribe

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.anooprajan.transcribe.ui.DetailScreen
import com.anooprajan.transcribe.ui.HomeScreen
import com.anooprajan.transcribe.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { App() } }
    }
}

@Composable
private fun App() {
    // "" = home, "settings", or a recording id.
    var screen by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = screen.isNotEmpty()) { screen = "" }
    when (screen) {
        "" -> HomeScreen(onOpen = { screen = it }, onSettings = { screen = "settings" })
        "settings" -> SettingsScreen(onBack = { screen = "" })
        else -> DetailScreen(id = screen, onBack = { screen = "" })
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
        content = content,
    )
}
