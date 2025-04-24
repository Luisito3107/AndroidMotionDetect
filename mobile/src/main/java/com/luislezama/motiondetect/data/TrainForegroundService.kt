package com.luislezama.motiondetect.data

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.CountDownTimer
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.wearable.Wearable
import com.luislezama.motiondetect.R
import com.luislezama.motiondetect.deviceconnection.ConnectionManager
import com.luislezama.motiondetect.deviceconnection.DataListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileWriter
import java.io.IOException


object TrainForegroundServiceHolder {
    var service: TrainForegroundService? = null
}

class TrainForegroundService : Service() {
    enum class ServiceStatus {
        STOPPED,
        DELAYED_START,
        WAITING,
        RECEIVING
    }

    enum class ServiceStopReason {
        //SERVICE_WAS_NOT_EVEN_RUNNING_LOL,
        MANUAL_STOP,
        STOP_AFTER_SAMPLE_COUNT,
        NO_RESPONSE_FROM_WEAROS,
        WEAR_DEVICE_DISCONNECTED,
        CSV_FILE_NOT_CREATED,
        CSV_FILE_ALREADY_EXISTS
    }

    companion object {
        fun getServiceStatus(context: Context = ConnectionManager.applicationContext): ServiceStatus {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == TrainForegroundService::class.java.name }

            return if (!isRunning) ServiceStatus.STOPPED // Service is not running
            else TrainForegroundServiceHolder.service?.getServiceStatus() ?: ServiceStatus.STOPPED // Get the current service status (if any)
        }

        fun start(context: Context = ConnectionManager.applicationContext) {
            if (getServiceStatus() != ServiceStatus.STOPPED) return

            val serviceIntent = Intent(context, TrainForegroundService::class.java)
            context.startService(serviceIntent)
        }

        fun stop(context: Context = ConnectionManager.applicationContext, stopReason: ServiceStopReason? = null) {
            if (getServiceStatus() == ServiceStatus.STOPPED) return

            if (stopReason != null) {
                TrainForegroundServiceHolder.service?.stopReason = stopReason
            }
            val serviceIntent = Intent(context, TrainForegroundService::class.java)
            context.stopService(serviceIntent)
        }

        const val WEAR_MESSAGE_TIMEOUT_IN_MS = 15000L // Service will stop after 15 seconds of no message from Wear OS
        private const val CONFIRMATION_TIME_INTERVAL = 10000L // Service will send a confirmation message to the Wear OS device every 10 seconds. This can't be lower than WearForegroundService timeout (wear module).
        private const val SERVICE_NOTIFICATION_CHANNEL_ID = "TrainingServiceChannel"
        private const val SERVICE_NOTIFICATION_RUNNING_ID = 1
        private const val SERVICE_NOTIFICATION_TIMEOUT_ID = 2
        private const val SERVICE_NOTIFICATION_STOPAFTERSAMPLES_ID = 3
        const val SESSIONS_STORED_IN_SUBFOLDER = "trainsessions"
        const val ACTION_SERVICE_STATUS_CHANGED = "TRAIN_SERVICE_STATUS_CHANGED"
    }



    // Service status and stop reason
    private val _serviceStatus: MutableStateFlow<ServiceStatus> = MutableStateFlow(ServiceStatus.STOPPED)
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus
    private var stopReason: ServiceStopReason? = null

    private lateinit var dataListener: DataListener
    private var lastMessageFromWearTime: Long = 0
    private var lastConfirmationSentTime: Long = 0
    private var lastNotificationUpdateTime: Long = 0


    // Train session parameters
    private var action: Action = Action.STANDING
    private var alias: String = ""
    private var user: String = ""
    private var userHand: HandOption = HandOption.LEFT
    private var stopAfter: Int? = null
    private var delayedStartInSeconds: Int = 10
    private var samplesPerPacket: Int = 10

    private var receivedSensorDataPacketsCount: Int = 0
    fun getReceivedSensorDataPacketsCount(): Int {
        return receivedSensorDataPacketsCount
    }

    private val _totalSensorSamplesCaptured: MutableStateFlow<Int> = MutableStateFlow(0)
    val totalSensorSamplesCaptured: StateFlow<Int> = _totalSensorSamplesCaptured






    private fun resetLastMessageFromWearTime() {
        lastMessageFromWearTime = System.currentTimeMillis()
    }
    private fun startWearMessageTimeoutChecker(onTimeout: () -> Unit) {
        lastMessageFromWearTime = System.currentTimeMillis()
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(WEAR_MESSAGE_TIMEOUT_IN_MS)
                if (System.currentTimeMillis() - lastMessageFromWearTime > WEAR_MESSAGE_TIMEOUT_IN_MS) {
                    if (getServiceStatus() in listOf(ServiceStatus.WAITING, ServiceStatus.RECEIVING)) {
                        onTimeout()
                    }
                    break
                }
            }
        }
    }



    // Service status management
    private fun setServiceStatus(status: ServiceStatus) {
        if (status == ServiceStatus.STOPPED && getServiceStatus() in listOf(ServiceStatus.WAITING, ServiceStatus.RECEIVING)) {
            ConnectionManager.messageQueue.requestSensorCaptureStop()
        }

        _serviceStatus.value = status
        val intent = Intent(ACTION_SERVICE_STATUS_CHANGED)
        intent.putExtra("SERVICE_STATUS", status)


        when (status) {
            ServiceStatus.RECEIVING -> {
                updateNotification(status)
                resetLastMessageFromWearTime()
            }

            ServiceStatus.WAITING, ServiceStatus.DELAYED_START -> {
                updateNotification(status)
            }

            ServiceStatus.STOPPED -> {
                intent.putExtra("TOTAL_SENSOR_SAMPLES_CAPTURED", _totalSensorSamplesCaptured.value)
                intent.putExtra("SERVICE_STOP_REASON", stopReason)
            }
        }

        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    fun getServiceStatus(): ServiceStatus {
        return _serviceStatus.value
    }



    // Notification management
    private fun createNotification(status: ServiceStatus = getServiceStatus()): Notification {
        val notificationText = when (status) {
            ServiceStatus.DELAYED_START -> getString(R.string.train_service_notification_content_delayedstart, _delayedStartRemainingTime.value)
            ServiceStatus.WAITING -> getString(R.string.train_service_notification_content_waiting)
            ServiceStatus.RECEIVING -> getString(R.string.train_service_notification_content_receiving, _totalSensorSamplesCaptured.value, getReceivedSensorDataPacketsCount())
            else -> ""
        }

        val notificationChannel = NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.train_service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(notificationChannel)

        return NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.train_service_notification_title))
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText(notificationText))
            .setSmallIcon(R.drawable.ic_train)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOngoing(true) // Notification cannot be dismissed by the user
            .addAction(R.drawable.ic_stop, getString(R.string.train_service_notification_button_stop), getStopServiceIntent()) // Stop service button
            .addAction(R.drawable.ic_open_app, getString(R.string.train_service_notification_button_open_app), getOpenAppIntent()) // Open app button
            .build()
    }

    private fun updateNotification(status: ServiceStatus = getServiceStatus()) {
        fun reallyUpdateNotification() {
            val notificationManager = getSystemService(NotificationManager::class.java)
            val notification = createNotification(status)
            notificationManager.notify(SERVICE_NOTIFICATION_RUNNING_ID, notification)
            if (status == ServiceStatus.RECEIVING) lastNotificationUpdateTime = System.currentTimeMillis()
        }

        //if (status in listOf(ServiceStatus.WAITING, ServiceStatus.RECEIVING)) {
        if (status != ServiceStatus.STOPPED) {
            if (lastNotificationUpdateTime == 0L) reallyUpdateNotification()
            else {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastNotificationUpdateTime >= 5000) {
                    reallyUpdateNotification()
                    lastNotificationUpdateTime = currentTime
                } else {
                    return
                }
            }
        }
    }

    private fun getStopServiceIntent(): PendingIntent { // This intent will stop the service
        val stopIntent = Intent(this, TrainForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getOpenAppIntent(): PendingIntent { // This intent will only open the app
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        openIntent?.putExtra("FRAGMENT_TO_OPEN", "train")
        return PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sendTimeoutNotification(context: Context) {
        val notificationChannel = NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.train_service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)

        val notification = NotificationCompat.Builder(context, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.train_service_notification_timeout_title))
            .setContentText(getString(R.string.train_service_notification_timeout_content, _totalSensorSamplesCaptured.value, (WEAR_MESSAGE_TIMEOUT_IN_MS / 1000)))
            .setSmallIcon(R.drawable.ic_train)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(SERVICE_NOTIFICATION_TIMEOUT_ID, notification)
    }

    private fun sendStopAfterSamplesNotification(context: Context) {
        val notificationChannel = NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.train_service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)

        val notification = NotificationCompat.Builder(context, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.train_service_notification_stopaftersamples_title))
            .setContentText(getString(R.string.train_service_notification_stopaftersamples_content, _totalSensorSamplesCaptured.value))
            .setSmallIcon(R.drawable.ic_train)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(SERVICE_NOTIFICATION_STOPAFTERSAMPLES_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP_SERVICE") {
            stopReason = ServiceStopReason.MANUAL_STOP
            stopSelf() // Stop the service when the stop button in the notification is clicked
        }
        return START_STICKY
    }



    // Service functions
    override fun onCreate() {
        super.onCreate()

        // Get settings from shared preferences
        val sharedPreferences = this.getSharedPreferences("TrainSettings", Context.MODE_PRIVATE)
        action = Action.entries.find { it.value == sharedPreferences.getString("action", "standing") } ?: Action.STANDING
        alias = sharedPreferences.getString("alias", "no_alias") ?: "no_alias"
        user = sharedPreferences.getString("user", "no_user") ?: "no_user"
        userHand = HandOption.entries.find { it.value == sharedPreferences.getString("userHand", "left") } ?: HandOption.LEFT
        stopAfter = sharedPreferences.getInt("stopAfter", -1)
        if (stopAfter == -1) stopAfter = null
        delayedStartInSeconds = sharedPreferences.getInt("delayedStartInSeconds", 5) + 1
        samplesPerPacket = sharedPreferences.getInt("samplesPerPacket", 10)


        // Attempt to create CSV file for this session
        if (sessionFileAlreadyExists()) {
            stopReason = ServiceStopReason.CSV_FILE_ALREADY_EXISTS
            stopSelf()
            return
        } else {
            val fileCreated = createCSVFile()
            if (!fileCreated) {
                stopReason = ServiceStopReason.CSV_FILE_NOT_CREATED
                stopSelf()
                return
            }
        }


        // Create a DataListener instance and register its possible callbacks to receive messages from the Wear OS device
        dataListener = DataListener(ConnectionManager).registerCallbacks(this.dataListenerCallbacks)
        Wearable.getMessageClient(this).addListener(dataListener)
        //Wearable.getCapabilityClient(this).addListener(this, "data_capture_service")


        // Store instance in ForegroundServiceHolder and start as a foreground service
        TrainForegroundServiceHolder.service = this
        startForeground(1, createNotification()) // Service will run in foreground


        // Start delayed start countdown
        startDelayedStartCountdown {
            setServiceStatus(ServiceStatus.WAITING)
            ConnectionManager.messageQueue.requestSensorCaptureStart(samplesPerPacket)
            startWearMessageTimeoutChecker { // If no response from Wear OS is received, stop service
                stopReason = ServiceStopReason.NO_RESPONSE_FROM_WEAROS
                sendTimeoutNotification(this)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        // Remove DataListener to stop receiving messages
        if (::dataListener.isInitialized) Wearable.getMessageClient(this).removeListener(dataListener)
        //Wearable.getCapabilityClient(this).removeListener(this, "data_capture_service")

        deleteCSVFileIfEmpty()
        cancelDelayedStartCountdown()
        setServiceStatus(ServiceStatus.STOPPED)

        stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(SERVICE_NOTIFICATION_RUNNING_ID)

        TrainForegroundServiceHolder.service = null

        // Try to make the device vibrate on service stop
        val vibrator = ContextCompat.getSystemService(this, Vibrator::class.java)
        vibrator?.let {
            val effect = VibrationEffect.createOneShot(1000, VibrationEffect.DEFAULT_AMPLITUDE)
            it.vibrate(effect)
        }

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }




    // Delayed start counter and management
    private val _delayedStartRemainingTime = MutableStateFlow<Long>(0)
    val delayedStartRemainingTime: StateFlow<Long> = _delayedStartRemainingTime
    private var countDownTimer: CountDownTimer? = null

    private fun startDelayedStartCountdown(onFinish: () -> Unit) {
        setServiceStatus(ServiceStatus.DELAYED_START)
        _delayedStartRemainingTime.value = delayedStartInSeconds.toLong()
        countDownTimer = object : CountDownTimer(_delayedStartRemainingTime.value * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _delayedStartRemainingTime.value = millisUntilFinished / 1000
                updateNotification()
            }

            override fun onFinish() {
                _delayedStartRemainingTime.value = 0
                onFinish()
            }
        }.start()
    }

    private fun cancelDelayedStartCountdown() {
        countDownTimer?.cancel()
        _delayedStartRemainingTime.value = 0
    }




    // CSV file management
    private var sessionCsvFile: File? = null
    private fun createCSVFile(): Boolean {
        val context: Context = this
        _totalSensorSamplesCaptured.value = 0
        val unixTimestamp = System.currentTimeMillis() / 1000
        val fileName = "${unixTimestamp}_${alias}.csv"

        val trainSessionsFolder = File(context.filesDir, SESSIONS_STORED_IN_SUBFOLDER)
        if (!trainSessionsFolder.exists()) {
            trainSessionsFolder.mkdirs() // Create directory if it doesn't exist
        }
        val file = File(trainSessionsFolder, fileName) // Store CSV files in the app's internal storage directory

        try {
            val writer = FileWriter(file)
            writer.flush()
            writer.close()
            sessionCsvFile = file
            Log.d("SensorDataReceiverService", "CSV file created: ${file.name}")
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            sessionCsvFile = null
            return false
        }
    }
    private fun sessionFileAlreadyExists(): Boolean {
        val fileList = File(filesDir, SESSIONS_STORED_IN_SUBFOLDER).listFiles { file -> file.extension == "csv" }?.toMutableList() ?: mutableListOf()

        var exists = false
        for (file in fileList) {
            if (file.nameWithoutExtension.split("_", limit = 2)[1] == alias) {
                exists = true
                break
            }
        }
        return exists
    }
    private fun appendDataToCSVFile(accData: Array<Float>, gyroData: Array<Float>) : Int? {
        if (sessionCsvFile == null) {
            return null
        }

        val csvLine = CSVLine(
            action = action,
            sessionName = alias,
            sessionUserName = user,
            sessionUserHand = userHand,
            samplesPerPacket = samplesPerPacket,
            accX = accData[0],
            accY = accData[1],
            accZ = accData[2],
            gyroX = gyroData[0],
            gyroY = gyroData[1],
            gyroZ = gyroData[2]
        )

        try {
            val writer = FileWriter(sessionCsvFile, true)
            writer.append("$csvLine\n")
            _totalSensorSamplesCaptured.value = _totalSensorSamplesCaptured.value + 1
            writer.flush()
            writer.close()
            return _totalSensorSamplesCaptured.value
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }
    private fun deleteCSVFileIfEmpty() {
        if (sessionCsvFile != null) {
            if (_totalSensorSamplesCaptured.value == 0) {
                sessionCsvFile?.delete()
                Log.d("SensorDataReceiverService", "CSV file deleted: ${sessionCsvFile?.name}")
                sessionCsvFile = null
            }
        }
    }



    // Process received sensor data string
    private fun processSensorDataString(sensorDataString: String) {
        val sensorDataSamples = sensorDataString.split("|")
        val stopAfterSamples = stopAfter ?: -1
        for (sample in sensorDataSamples) {
            val values = sample.split(";")

            val acc = values[0].split(",")
            val accData = arrayOf(acc[0].toFloat(), acc[1].toFloat(), acc[2].toFloat())
            val gyro = values[1].split(",")
            val gyroData = arrayOf(gyro[0].toFloat(), gyro[1].toFloat(), gyro[2].toFloat())

            if (stopAfterSamples == -1 || (stopAfterSamples != -1 && _totalSensorSamplesCaptured.value < stopAfterSamples)) {
                val appended = appendDataToCSVFile(accData, gyroData) is Int
                if (appended) {
                    if ((stopAfterSamples != -1 && _totalSensorSamplesCaptured.value >= stopAfterSamples)) {
                        stopReason = ServiceStopReason.STOP_AFTER_SAMPLE_COUNT
                        sendStopAfterSamplesNotification(this)
                        stopSelf()
                        break
                    }
                }
            }

        }
    }




    // On capability changed
    /*override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.nodes.isEmpty()) {
            Log.d("SensorDataReceiverService", "Mobile device disconnected, stopping service")
            val intent = Intent("TRAIN_SERVICE_STATUS_CHANGED")
            intent.putExtra("SERVICE_STATUS", ServiceStatus.NOT_CONNECTED)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            stopReason = ServiceStopReason.WEAR_DEVICE_DISCONNECTED
            stopSelf() // Stop the service if the mobile device is disconnected
        } else {
            Log.d("SensorDataReceiverService", "Mobile device connected")
            val intent = Intent("TRAIN_SERVICE_STATUS_CHANGED")
            intent.putExtra("SERVICE_STATUS", ServiceStatus.STOPPED)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            stopReason = ???
            stopSelf()
        }
    }*/



    // DataListener callbacks
    private val dataListenerCallbacks: Map<String, (String?) -> Unit> = mapOf(
        "/sensor_capture_started" to {
            TrainForegroundServiceHolder.service?.let { service ->
                if (getServiceStatus() in listOf(ServiceStatus.WAITING)) {
                    Log.d("SensorDataReceiverService DataListener", "Wear OS device confirmed sensor capture started")
                    service.resetLastMessageFromWearTime()
                    service.setServiceStatus(ServiceStatus.RECEIVING)
                }
            }
        },

        "/sensor_data" to { sensorDataString ->
            TrainForegroundServiceHolder.service?.let { service ->
                if ((getServiceStatus() in listOf(ServiceStatus.RECEIVING)) && (sensorDataString ?: "").contains("|")) {
                    service.resetLastMessageFromWearTime()
                    service.receivedSensorDataPacketsCount++
                    service.processSensorDataString(sensorDataString ?: "")
                    service.updateNotification()

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastConfirmationSentTime >= CONFIRMATION_TIME_INTERVAL) {
                        Log.d("SensorDataReceiverService DataListener", "Sending confirmation to Wear OS device")
                        ConnectionManager.messageQueue.confirmMoreSensorDataNeeded()
                        lastConfirmationSentTime = currentTime
                    }
                }
            }
        },
    )
}