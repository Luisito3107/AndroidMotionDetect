package com.luislezama.motiondetect.data

import android.Manifest
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.wearable.Node
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import com.luislezama.motiondetect.deviceconnection.PseudoNode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch


class ServiceControlViewModel(private val application: Application? = null) : ViewModel() {
    private val _connectedDevice = MutableStateFlow<PseudoNode?>(null)
    val connectedDevice: StateFlow<PseudoNode?> = _connectedDevice.asStateFlow()

    private val _serviceStatus = MutableStateFlow(WearForegroundService.ServiceStatus.STOPPED)
    val serviceStatus: StateFlow<WearForegroundService.ServiceStatus> = _serviceStatus.asStateFlow()


    fun validateServiceAvailability(context: Context) : Boolean {
        _connectedDevice.value = ConnectionManager.connectedNode
        if (connectedDevice.value == null) {
            _serviceStatus.value = WearForegroundService.ServiceStatus.NOT_CONNECTED
            return false
        }

        var bodySensorPermissionGranted = false
        when (PackageManager.PERMISSION_GRANTED) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BODY_SENSORS
            ) -> { bodySensorPermissionGranted = true }
        }
        if (!bodySensorPermissionGranted) {
            _serviceStatus.value = WearForegroundService.ServiceStatus.PERMISSION_DENIED
            return false
        }

        return true
    }

    private fun checkServiceStatus(context: Context) {
        val canStartService = validateServiceAvailability(context)
        if (!canStartService) {
            return
        }

        _serviceStatus.value = WearForegroundService.getServiceStatus(context)
    }

    fun toggleService(context: Context) {
        val canStartService = validateServiceAvailability(context)
        if (!canStartService) {
            return
        }

        val intent = Intent(context, WearForegroundService::class.java)
        viewModelScope.launch {
            if (_serviceStatus.value in listOf(WearForegroundService.ServiceStatus.RUNNING, WearForegroundService.ServiceStatus.LISTENING)) {
                context.stopService(intent)
            } else if (_serviceStatus.value == WearForegroundService.ServiceStatus.STOPPED) {
                ContextCompat.startForegroundService(context, intent)
            }
            checkServiceStatus(context)
        }
    }


    private val serviceStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.getSerializableExtra("SERVICE_STATUS", WearForegroundService.ServiceStatus::class.java)?.let { status ->
                _serviceStatus.value = status
            }
        }
    }


    init {
        val filter = IntentFilter("com.luislezama.motiondetect.SERVICE_STATUS_CHANGED")
        if (application != null) LocalBroadcastManager.getInstance(application).registerReceiver(serviceStatusReceiver, filter)
    }

    override fun onCleared() {
        super.onCleared()

        if (application != null) LocalBroadcastManager.getInstance(application).unregisterReceiver(serviceStatusReceiver)
    }
}


class ServiceControlViewModelFactory(
    private val application: Application? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ServiceControlViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ServiceControlViewModel(application!!) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}