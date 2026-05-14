package com.redclient.keychecker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.redclient.keychecker.ui.screen.HomeScreen
import com.redclient.keychecker.ui.screen.SettingsScreen
import com.redclient.keychecker.ui.theme.KeyCheckerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KeyCheckerTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

private enum class Screen { Home, Settings }

@Composable
private fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.Home) }
    val homeVm: HomeViewModel = viewModel()
    val settingsVm: SettingsViewModel = viewModel()

    when (screen) {
        Screen.Home -> HomeScreen(
            viewModel = homeVm,
            onOpenSettings = { screen = Screen.Settings },
        )
        Screen.Settings -> SettingsScreen(
            viewModel = settingsVm,
            onBack = { screen = Screen.Home },
        )
    }
}
