package com.fcaronte.aabrowser

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fcaronte.aabrowser.smartphone.SmartphoneAppsScreen
import com.fcaronte.aabrowser.smartphone.SmartphoneAppsViewModel
import com.fcaronte.aabrowser.ui.AABrowserTheme

class SmartphoneAppsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AABrowserTheme {
                val viewModel: SmartphoneAppsViewModel = viewModel()
                SmartphoneAppsScreen(
                    viewModel = viewModel,
                    onBack = { finish() }
                )
            }
        }
    }
}
