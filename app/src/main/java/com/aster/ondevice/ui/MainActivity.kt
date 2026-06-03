package com.aster.ondevice.ui

import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aster.ondevice.ui.screens.AnalyserScreen
import com.aster.ondevice.ui.screens.ChatScreen
import com.aster.ondevice.ui.screens.HomeScreen
import com.aster.ondevice.ui.screens.SettingsScreen
import com.aster.ondevice.ui.screens.VoiceScreen
import com.aster.ondevice.ui.theme.AsterTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestManageStoragePermission()
        setContent {
            AsterTheme {
                AsterNavigation()
            }
        }
    }

    private fun requestManageStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()) {
            val intent = android.content.Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}

@Composable
private fun AsterNavigation() {
    val navController = rememberNavController()
    val items = listOf(
        Triple("home",     Icons.Default.Home,       "Home"),
        Triple("chat",     Icons.Default.Chat,       "Chat"),
        Triple("voice",    Icons.Default.Mic,        "Voice"),
        Triple("analyser", Icons.Default.QueryStats, "Analyser"),
        Triple("settings", Icons.Default.Settings,   "Settings"),
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val current = navController.currentBackStackEntryAsState().value?.destination?.route
                items.forEach { (route, icon, label) ->
                    NavigationBarItem(
                        selected = current == route,
                        onClick  = { navController.navigate(route) { launchSingleTop = true } },
                        icon     = { Icon(icon, contentDescription = label) },
                        label    = { Text(label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(navController, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home")     { HomeScreen() }
            composable("chat")     { ChatScreen() }
            composable("voice")    { VoiceScreen() }
            composable("analyser") { AnalyserScreen() }
            composable("settings") { SettingsScreen() }
        }
    }
}
