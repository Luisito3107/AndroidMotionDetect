package com.luislezama.motiondetect.data

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.Wearable
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.deviceconnection.WearConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch


class WearForegroundService : Service(), CapabilityClient.OnCapabilityChangedListener {

    companion object {
        fun getServiceStatus(context: Context): WearForegroundServiceStatus {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == WearForegroundService::class.java.name }

            val runningServiceStatus = ForegroundServiceHolder.service?.getServiceStatus() // Get the current service status (if any)
            return when {
                !isRunning -> WearForegroundServiceStatus.STOPPED
                runningServiceStatus == WearForegroundServiceStatus.RUNNING -> WearForegroundServiceStatus.RUNNING
                else -> WearForegroundServiceStatus.LISTENING
            }
        }
    }





    private var serviceStatus: WearForegroundServiceStatus = WearForegroundServiceStatus.STOPPED
    private lateinit var wearConnectionManager: WearConnectionManager
    private lateinit var mobileDataListener: MobileDataListener

    private var lastMobileConfirmationTime: Long = 0
    private val requestTimeoutInMs: Long = 10000 // Stop sending data if no mobile confirmation is received in 10 seconds

    private val NOTIFICATION_ID = 1
    private val CHANNEL_ID = "wear_service_channel"

    private var samplesPerPacket = 100
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null



    // Sensor management and data processing
    private val sensorDataBuffer = mutableListOf<String>()
    private var lastAccelerometerData: String? = null
    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            event?.let {
                when (it.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        val accX = it.values[0]
                        val accY = it.values[1]
                        val accZ = it.values[2]
                        processAccelerometerData(accX, accY, accZ)
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val gyroX = it.values[0]
                        val gyroY = it.values[1]
                        val gyroZ = it.values[2]
                        processGyroscopeData(gyroX, gyroY, gyroZ)
                    }
                }
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    fun processAccelerometerData(x: Float, y: Float, z: Float) {
        lastAccelerometerData = "$x,$y,$z"
    }

    fun processGyroscopeData(x: Float, y: Float, z: Float) {
        lastAccelerometerData?.let { accData ->
            val gyroData = "$x,$y,$z"
            sensorDataBuffer.add("$accData;$gyroData") // Store pair of accelerometer and gyroscope data
            lastAccelerometerData = null // Clear accelerometer data for the next pair
            sendDataIfReady()
        }
    }

    private fun sendDataIfReady() {
        if (sensorDataBuffer.size >= samplesPerPacket) {
            val message = sensorDataBuffer.joinToString("|")
            wearConnectionManager.wearMessageQueue.sendMessage( "/sensor_data", message.toByteArray())
            sensorDataBuffer.clear()
        }
    }

    fun startCapturingSensors(samplesPerPacket: Int) {
        this.samplesPerPacket = samplesPerPacket
        accelerometer?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        gyroscope?.let { sensorManager.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_FASTEST) }
        setServiceStatus(WearForegroundServiceStatus.RUNNING)

        lastMobileConfirmationTime = System.currentTimeMillis()
        val timeoutChecker = CoroutineScope(Dispatchers.IO).launch {
            while (serviceStatus == WearForegroundServiceStatus.RUNNING) {
                delay(requestTimeoutInMs)
                if (System.currentTimeMillis() - lastMobileConfirmationTime > requestTimeoutInMs) {
                    Log.d("MobileDataListener", "No confirmation from mobile, stopping capture")
                    stopCapturingSensors()
                    break
                }
            }
        }
    }

    fun stopCapturingSensors() {
        sensorManager.unregisterListener(sensorListener) // Stop accelerometer and gyroscope updates
        setServiceStatus(WearForegroundServiceStatus.LISTENING)
    }

    fun resetLastRequestTime() {
        lastMobileConfirmationTime = System.currentTimeMillis()
    }



    // Service status management
    private fun setServiceStatus(status: WearForegroundServiceStatus) {
        serviceStatus = status
        updateNotification(status)
        val intent = Intent("com.luislezama.motiondetect.SERVICE_STATUS_CHANGED")
        intent.putExtra("SERVICE_STATUS", status)
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun getServiceStatus(): WearForegroundServiceStatus {
        return serviceStatus
    }



    // Notification management
    private fun createNotification(status: WearForegroundServiceStatus = serviceStatus): Notification {
        val notificationText = when (status) {
            WearForegroundServiceStatus.RUNNING -> getString(R.string.foregroundservice_notification_content_running)
            WearForegroundServiceStatus.LISTENING -> getString(R.string.foregroundservice_notification_content_listening)
            else -> ""
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wear OS Service",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(notificationText)
            .setSmallIcon(R.drawable.splash_icon)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true) // Notification cannot be dismissed by the user
            .addAction(R.drawable.ic_stop, getString(R.string.foregroundservice_notification_button_stop), getStopServiceIntent()) // Stop service button
            .addAction(R.drawable.ic_open_app, getString(R.string.foregroundservice_notification_button_open_app), getOpenAppIntent()) // Open app button
            .build()
    }

    private fun updateNotification(status: WearForegroundServiceStatus = serviceStatus) {
        if (status in listOf(WearForegroundServiceStatus.RUNNING, WearForegroundServiceStatus.LISTENING)) {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val notification = createNotification(status)
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun getStopServiceIntent(): PendingIntent { // This intent will stop the service
        val stopIntent = Intent(this, WearForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getOpenAppIntent(): PendingIntent { // This intent will only open the app
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        return PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopSelf() // Stop the service when the stop button in the notification is clicked
        }
        return START_STICKY
    }



    // Service functions
    override fun onCreate() {
        super.onCreate()

        // Start WearConnectionManager
        wearConnectionManager = WearConnectionManager(this)

        // Add MobileDataListener to receive messages from the mobile device
        mobileDataListener = MobileDataListener(this, wearConnectionManager)
        Wearable.getMessageClient(this).addListener(mobileDataListener)
        Wearable.getCapabilityClient(this).addListener(this, "data_capture_service")

        // Initialize sensors
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // Store instance in ForegroundServiceHolder and start as a foreground service
        ForegroundServiceHolder.service = this
        startForeground(1, createNotification()) // Service will run in foreground
        setServiceStatus(WearForegroundServiceStatus.LISTENING)
    }

    override fun onDestroy() {
        stopCapturingSensors()
        setServiceStatus(WearForegroundServiceStatus.STOPPED)

        stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(NOTIFICATION_ID)

        super.onDestroy()

        ForegroundServiceHolder.service = null

        // Remove MobileDataListener to stop receiving messages
        Wearable.getMessageClient(this).removeListener(mobileDataListener)
        Wearable.getCapabilityClient(this).removeListener(this, "data_capture_service")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }



    // On capability changed
    override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.nodes.isEmpty()) {
            Log.d("WearForegroundService", "Mobile device disconnected, stopping service")
            val intent = Intent("com.luislezama.motiondetect.SERVICE_STATUS_CHANGED")
            intent.putExtra("SERVICE_STATUS", WearForegroundServiceStatus.NOT_CONNECTED)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            stopSelf() // Stop the service if the mobile device is disconnected
        } else {
            Log.d("WearForegroundService", "Mobile device connected")
            val intent = Intent("com.luislezama.motiondetect.SERVICE_STATUS_CHANGED")
            intent.putExtra("SERVICE_STATUS", WearForegroundServiceStatus.STOPPED)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            stopSelf()
        }
    }
}