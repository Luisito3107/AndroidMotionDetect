package com.luislezama.motiondetect.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.data.ServiceControlViewModel
import com.luislezama.motiondetect.data.WearForegroundService
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import com.luislezama.motiondetect.deviceconnection.PseudoNode
import com.luislezama.motiondetect.theme.MotionDetectTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


@Preview(showBackground = true, device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
fun ServiceControlScreen(viewModel: ServiceControlViewModel = androidx.lifecycle.viewmodel.compose.viewModel(), isPreview: Boolean = LocalInspectionMode.current) {
    val context = LocalContext.current
    RequestPermissions(context)

    val serviceStatus by viewModel.serviceStatus.collectAsState()
    val connectedDevice: PseudoNode? by viewModel.connectedDevice.collectAsState()

    LaunchedEffect(Unit) {
        if (!isPreview) {
            ConnectionManager.resetConnectedNode()
            viewModel.validateServiceAvailability(context)
        }
    }

    MotionDetectTheme {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp, 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when(serviceStatus) {
                WearForegroundService.ServiceStatus.PERMISSION_DENIED -> {
                    Text(context.getString(R.string.foregroundservice_permission_denied), textAlign = TextAlign.Center)
                }
                WearForegroundService.ServiceStatus.NOT_CONNECTED -> {
                    Text(context.getString(R.string.foregroundservice_device_not_connected), textAlign = TextAlign.Center)
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            CoroutineScope(Dispatchers.IO).launch {
                                ConnectionManager.resetConnectedNode()
                                viewModel.validateServiceAvailability(context)
                            }
                        },
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.width(96.dp).height(36.dp)
                    ) {
                        Text(context.getString(R.string.foregroundservice_device_not_connected_refresh))
                    }
                }
                else -> {
                    Button(
                        onClick = {
                            viewModel.toggleService(context)
                        }
                    ) {
                        val serviceStatusIcon = when (serviceStatus) {
                            WearForegroundService.ServiceStatus.RUNNING -> R.drawable.ic_stop
                            WearForegroundService.ServiceStatus.LISTENING -> R.drawable.ic_stop
                            else -> R.drawable.ic_start
                        }
                        Icon(
                            imageVector = ImageVector.vectorResource(id = serviceStatusIcon),
                            modifier = Modifier,
                            contentDescription = "drawable icons",
                            tint = Color.Unspecified
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    val serviceStatusText = when (serviceStatus) {
                        WearForegroundService.ServiceStatus.RUNNING -> context.getString(R.string.foregroundservice_status_running)
                        WearForegroundService.ServiceStatus.LISTENING -> context.getString(R.string.foregroundservice_status_listening)
                        WearForegroundService.ServiceStatus.STOPPED -> context.getString(R.string.foregroundservice_status_stopped)
                        else -> ""
                    }
                    Text(serviceStatusText, textAlign = TextAlign.Center)

                    val deviceName = connectedDevice?.displayName ?: ""
                    Text(deviceName, textAlign = TextAlign.Center, style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.secondary)
                }
            }

        }
    }
}




@Composable
fun RequestPermissions(context: Context) {
    val permissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions[Manifest.permission.POST_NOTIFICATIONS] == true &&
                permissions[Manifest.permission.BODY_SENSORS] == true

        if (permissions[Manifest.permission.BODY_SENSORS] == true) {
            val intent = Intent("com.luislezama.motiondetect.SERVICE_STATUS_CHANGED").apply {
                putExtra("SERVICE_STATUS", WearForegroundService.ServiceStatus.STOPPED)
            }
            LocalBroadcastManager.getInstance(context).sendBroadcast(intent)
        }
    }

    LaunchedEffect(Unit) {
        val permissionsToRequest = listOf(
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.BODY_SENSORS
        ).filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

        if (permissionsToRequest.isNotEmpty()) {
            permissionsLauncher.launch(permissionsToRequest.toTypedArray())
        }
    }
}