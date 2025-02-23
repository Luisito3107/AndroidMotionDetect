/* While this template provides a good starting point for using Wear Compose, you can always
 * take a look at https://github.com/android/wear-os-samples/tree/main/ComposeStarter to find the
 * most up to date changes to the libraries and their usages.
 */

package com.luislezama.motiondetect

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModelProvider
import com.luislezama.motiondetect.data.ServiceControlViewModel
import com.luislezama.motiondetect.data.ServiceControlViewModelFactory
import com.luislezama.motiondetect.deviceconnection.WearConnectionManager
import com.luislezama.motiondetect.deviceconnection.WearConnectionViewModel
import com.luislezama.motiondetect.ui.ServiceControlScreen


class MainActivity : ComponentActivity() {
    private val wearConnectionViewModel: WearConnectionViewModel by viewModels()
    private lateinit var serviceControlViewModel: ServiceControlViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)

        wearConnectionViewModel.wearConnectionManager = WearConnectionManager(this)

        val factory = ServiceControlViewModelFactory(wearConnectionViewModel.wearConnectionManager, application)
        serviceControlViewModel = ViewModelProvider(this, factory).get(ServiceControlViewModel::class.java)

        setTheme(android.R.style.Theme_DeviceDefault)

        setContent {
            ServiceControlScreen(serviceControlViewModel)
        }
    }
}



