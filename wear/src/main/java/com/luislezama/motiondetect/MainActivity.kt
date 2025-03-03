package com.luislezama.motiondetect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.luislezama.motiondetect.data.ServiceControlViewModel
import com.luislezama.motiondetect.data.ServiceControlViewModelFactory
import com.luislezama.motiondetect.ui.ServiceControlScreen


class MainActivity : ComponentActivity() {
    private lateinit var serviceControlViewModel: ServiceControlViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        val factory = ServiceControlViewModelFactory(application)
        serviceControlViewModel = ViewModelProvider(this, factory)[ServiceControlViewModel::class.java]

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            ServiceControlScreen(serviceControlViewModel)
        }
    }
}



