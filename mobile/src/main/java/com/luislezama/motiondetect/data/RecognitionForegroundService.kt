package com.luislezama.motiondetect.data

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.CountDownTimer
import android.os.IBinder
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
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer
import org.tensorflow.lite.support.tensorbuffer.TensorBufferFloat
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel


object RecognitionForegroundServiceHolder {
    var service: RecognitionForegroundService? = null
}

class RecognitionForegroundService : Service() {
    enum class ServiceStatus {
        STOPPED,
        DELAYED_START,
        WAITING,
        RECEIVING
    }

    enum class ServiceStopReason {
        MANUAL_STOP,
        NO_RESPONSE_FROM_WEAROS,
        MODEL_NOT_FOUND,
        MODEL_NOT_COMPATIBLE,
        RECOGNITION_ERROR,
        WEAR_DEVICE_DISCONNECTED
    }

    companion object {
        fun getServiceStatus(context: Context = ConnectionManager.applicationContext): ServiceStatus {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val isRunning = activityManager.getRunningServices(Integer.MAX_VALUE)
                .any { it.service.className == RecognitionForegroundService::class.java.name }

            return if (!isRunning) ServiceStatus.STOPPED // Service is not running
            else RecognitionForegroundServiceHolder.service?.getServiceStatus() ?: ServiceStatus.STOPPED // Get the current service status (if any)
        }

        fun start(context: Context = ConnectionManager.applicationContext) {
            if (getServiceStatus() != ServiceStatus.STOPPED) return

            val serviceIntent = Intent(context, RecognitionForegroundService::class.java)
            context.startService(serviceIntent)
        }

        fun stop(context: Context = ConnectionManager.applicationContext, stopReason: ServiceStopReason? = null) {
            if (getServiceStatus() == ServiceStatus.STOPPED) return

            if (stopReason != null) {
                RecognitionForegroundServiceHolder.service?.stopReason = stopReason
            }
            val serviceIntent = Intent(context, RecognitionForegroundService::class.java)
            context.stopService(serviceIntent)
        }

        const val WEAR_MESSAGE_TIMEOUT_IN_MS = 15000L // Service will stop after 15 seconds of no message from Wear OS
        private const val CONFIRMATION_TIME_INTERVAL = 10000L // Service will send a confirmation message to the Wear OS device every 10 seconds. This can't be lower than WearForegroundService timeout (wear module).
        private const val SERVICE_NOTIFICATION_CHANNEL_ID = "RecognitionServiceChannel"
        private const val SERVICE_NOTIFICATION_RUNNING_ID = 1
        private const val SERVICE_NOTIFICATION_TIMEOUT_ID = 2
        const val MODELS_STORED_IN_SUBFOLDER = "models"
        const val ACTION_SERVICE_STATUS_CHANGED = "RECOGNITION_SERVICE_STATUS_CHANGED"

        fun getSelectedModelFile(context: Context = ConnectionManager.applicationContext): File? {
            val sharedPreferences = context.getSharedPreferences("motiondetect_prefs", Context.MODE_PRIVATE)
            val selectedModelPath = sharedPreferences.getString("selected_model_path", null)
            selectedModelPath?.let {
                val modelFile = File(it)
                if (modelFile.exists()) return modelFile
                else return null
            }
            return null
        }

        fun selectedModelFileExists(context: Context = ConnectionManager.applicationContext): Boolean {
            getSelectedModelFile(context)?.let {
                return it.exists()
            }
            return false
        }
    }



    // Service status and stop reason
    private val _serviceStatus: MutableStateFlow<ServiceStatus> = MutableStateFlow(ServiceStatus.STOPPED)
    val serviceStatus: StateFlow<ServiceStatus> = _serviceStatus
    private var stopReason: ServiceStopReason? = null

    private lateinit var dataListener: DataListener
    private var lastMessageFromWearTime: Long = 0
    private var lastConfirmationSentTime: Long = 0
    private var lastNotificationUpdateTime: Long = 0


    // Recognition session parameters
    private var userHand: HandOption = HandOption.LEFT
    private var delayedStartInSeconds: Int = 10
    private var samplesPerPacket: Int = 100


    // Recognition model
    @Volatile
    private var recognitionInProcess = false
    private lateinit var tfliteInterpreter: Interpreter
    private val modelInputShape: IntArray = intArrayOf(1, 100, 6) // 100 groups of 6 coords
    private val modelInputDataType: DataType = DataType.FLOAT32


    private var receivedSensorDataPacketsCount: Int = 0
    fun getReceivedSensorDataPacketsCount(): Int {
        return receivedSensorDataPacketsCount
    }

    private val _totalSensorSamplesCaptured: MutableStateFlow<Int> = MutableStateFlow(0)
    val totalSensorSamplesCaptured: StateFlow<Int> = _totalSensorSamplesCaptured

    private val _currentRecognizedAction: MutableStateFlow<Action?> = MutableStateFlow(null)
    val currentRecognizedAction: StateFlow<Action?> = _currentRecognizedAction






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
                //intent.putExtra("TOTAL_SENSOR_SAMPLES_CAPTURED", _totalSensorSamplesCaptured.value)
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
            ServiceStatus.DELAYED_START -> getString(R.string.recognition_service_notification_content_delayedstart, _delayedStartRemainingTime.value)
            ServiceStatus.WAITING -> getString(R.string.recognition_service_notification_content_waiting)
            ServiceStatus.RECEIVING -> {
                if (_currentRecognizedAction.value == null) getString(R.string.recognition_service_notification_content_receiving_waiting)
                else getString(R.string.recognition_service_notification_content_receiving_recognizing, getString(_currentRecognizedAction.value!!.stringResource))
            }
            else -> ""
        }

        val notificationChannel = NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.recognition_service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(notificationChannel)

        fun getBitmapFromVectorDrawable(context: Context?, drawableId: Int): Bitmap {
            val drawable = ContextCompat.getDrawable(context!!, drawableId)
            val bitmap = Bitmap.createBitmap(
                drawable!!.intrinsicWidth,
                drawable.intrinsicHeight, Bitmap.Config.ARGB_8888
            )
            val canvas = Canvas(bitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return bitmap
        }

        return NotificationCompat.Builder(this, SERVICE_NOTIFICATION_CHANNEL_ID).run {
            setContentTitle(getString(R.string.recognition_service_notification_title))
            setStyle(NotificationCompat.BigTextStyle()
                .bigText(notificationText))
            setSmallIcon(R.drawable.ic_predict)
            if (_currentRecognizedAction.value != null) setLargeIcon(getBitmapFromVectorDrawable(this@RecognitionForegroundService, _currentRecognizedAction.value!!.drawableResource))
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setOngoing(true) // Notification cannot be dismissed by the user
            addAction(R.drawable.ic_stop, getString(R.string.recognition_service_notification_button_stop), getStopServiceIntent()) // Stop service button
            addAction(R.drawable.ic_open_app, getString(R.string.recognition_service_notification_button_open_app), getOpenAppIntent()) // Open app button
            build()
        }

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
        val stopIntent = Intent(this, RecognitionForegroundService::class.java).apply {
            action = "STOP_SERVICE"
        }
        return PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun getOpenAppIntent(): PendingIntent { // This intent will only open the app
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        openIntent?.putExtra("FRAGMENT_TO_OPEN", "recognition")
        return PendingIntent.getActivity(this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun sendTimeoutNotification(context: Context) {
        val notificationChannel = NotificationChannel(
            SERVICE_NOTIFICATION_CHANNEL_ID,
            getString(R.string.recognition_service_notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )

        val notificationManager = context.getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(notificationChannel)

        val notification = NotificationCompat.Builder(context, SERVICE_NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.recognition_service_notification_timeout_title))
            .setContentText(getString(R.string.recognition_service_notification_timeout_content, _totalSensorSamplesCaptured.value, _delayedStartRemainingTime.value))
            .setSmallIcon(R.drawable.ic_predict)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(SERVICE_NOTIFICATION_TIMEOUT_ID, notification)
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
        val sharedPreferences = this.getSharedPreferences("RecognitionSettings", Context.MODE_PRIVATE)
        userHand = HandOption.entries.find { it.value == sharedPreferences.getString("userHand", "left") } ?: HandOption.LEFT
        delayedStartInSeconds = sharedPreferences.getInt("delayedStartInSeconds", 5) + 1
        samplesPerPacket = 100//sharedPreferences.getInt("samplesPerPacket", 100)


        // Create a DataListener instance and register its possible callbacks to receive messages from the Wear OS device
        dataListener = DataListener(ConnectionManager).registerCallbacks(this.dataListenerCallbacks)
        Wearable.getMessageClient(this).addListener(dataListener)
        //Wearable.getCapabilityClient(this).addListener(this, "data_capture_service")


        // Load recognition model
        val modelFile = loadModelFile()
        if (modelFile == null) {
            stopReason = ServiceStopReason.MODEL_NOT_FOUND
            stopSelf()
            return
        } else {
            tfliteInterpreter = Interpreter(modelFile)
            if (!tfliteInterpreter.getInputTensor(0).shape().contentEquals(modelInputShape)) {
                Log.e("RecognitionForegroundService TFLite", "Model input shape (${tfliteInterpreter.getInputTensor(0).shape()}) does not match expected shape ($modelInputShape)")
                stopReason = ServiceStopReason.MODEL_NOT_COMPATIBLE
                stopSelf()
                return
            }

            if (tfliteInterpreter.getInputTensor(0).dataType() != modelInputDataType) {
                Log.e("RecognitionForegroundService TFLite", "Model input data type (${tfliteInterpreter.getInputTensor(0).dataType()}) does not match expected data type ($modelInputDataType)")
                stopReason = ServiceStopReason.MODEL_NOT_COMPATIBLE
                stopSelf()
                return
            }

            val numClasses = tfliteInterpreter.getOutputTensor(0).shape()[1]
            if (numClasses != Action.entries.size) {
                Log.e("RecognitionForegroundService TFLite", "Model output size ($numClasses) does not match Action enum size (${Action.entries.size})")
                stopReason = ServiceStopReason.MODEL_NOT_COMPATIBLE
                stopSelf()
                return
            }
        }



        // Store instance in ForegroundServiceHolder and start as a foreground service
        RecognitionForegroundServiceHolder.service = this
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

        cancelDelayedStartCountdown()
        setServiceStatus(ServiceStatus.STOPPED)

        stopForeground(STOP_FOREGROUND_REMOVE) // Remove notification
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.cancel(SERVICE_NOTIFICATION_RUNNING_ID)

        RecognitionForegroundServiceHolder.service = null

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




    // Process received sensor data string
    private fun prepareSensorData(sensorDataString: String): Array<FloatArray> {
        val sensorDataSamples = sensorDataString.split("|") // Split into individual samples
        val mergedSensorData = Array(sensorDataSamples.size) { FloatArray(6) } // Initialize the output array

        for (i in sensorDataSamples.indices) {
            val sample = sensorDataSamples[i]
            val values = sample.split(";") // Split each sample into acc and gyro parts

            val acc = values[0].split(",") // Split acc into x, y, z
            val gyro = values[1].split(",") // Split gyro into x, y, z

            // Directly assign the parsed float values to the correct indices in the output array
            mergedSensorData[i][0] = acc[0].toFloat() // Accelerometer X
            mergedSensorData[i][1] = acc[1].toFloat() // Accelerometer Y
            mergedSensorData[i][2] = acc[2].toFloat() // Accelerometer Z

            mergedSensorData[i][3] = gyro[0].toFloat() // Gyroscope X
            mergedSensorData[i][4] = gyro[1].toFloat() // Gyroscope Y
            mergedSensorData[i][5] = gyro[2].toFloat() // Gyroscope Z
        }

        return mergedSensorData
    }
    private fun processSensorDataString(sensorDataString: String) {
        if (recognitionInProcess) return

        recognitionInProcess = true

        Thread {
            val inputData = prepareSensorData(sensorDataString)
            val action = recognizeAction(inputData)

            action?.let {
                Log.d("Predict", "Predicted action: ${it.name}")
                _currentRecognizedAction.value = action
            }

            recognitionInProcess = false
        }.start()
    }




    // Load selected model
    private fun loadModelFile(): MappedByteBuffer? {
        val modelFile = getSelectedModelFile() ?: return null

        val inputStream = FileInputStream(modelFile)
        val fileChannel = inputStream.channel
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, modelFile.length())
    }

    /*fun recognizeAction(inputData: Array<Array<FloatArray>>): Action? {
        val output = Array(1) { FloatArray(Action.entries.size) }
        tfliteInterpreter.run(inputData, output)

        val predictedIndex = output[0].indices.maxByOrNull { output[0][it] } ?: return null
        return Action.entries[predictedIndex]
    }*/

    private fun recognizeAction(inputData: Array<FloatArray>): Action? {
        try {
            if (inputData.size != 100) return null // Only allow 100 samples as input

            // Fix tensor input shape (1, 100, 6)
            val inputTensor =
                TensorBufferFloat.createFixedSize(intArrayOf(1, 100, 6), DataType.FLOAT32)

            // Flatten the input data before loading it into the tensor
            val flattenedData = FloatArray(100 * 6) // 100 timesteps, 6 values (coords) each
            for (i in inputData.indices) {
                for (j in inputData[i].indices) {
                    flattenedData[i * 6 + j] = inputData[i][j]
                }
            }

            // Load the flattened data into the tensor
            inputTensor.loadArray(flattenedData, intArrayOf(1, 100, 6))

            // Create the output tensor
            val outputTensor = TensorBufferFloat.createFixedSize(
                intArrayOf(1, Action.entries.size),
                DataType.FLOAT32
            )

            // Run the interpreter
            tfliteInterpreter.run(inputTensor.buffer, outputTensor.buffer)

            // Get the output array
            val predictions = outputTensor.floatArray
            val predictedIndex = predictions.indices.maxByOrNull { predictions[it] } ?: return null

            return Action.entries.getOrNull(predictedIndex) // Return the corresponding action
        } catch (e: Exception) {
            e.printStackTrace()
            stopReason = ServiceStopReason.RECOGNITION_ERROR
            stopSelf()
            return null
        }
    }





    // On capability changed
    /*override fun onCapabilityChanged(capabilityInfo: CapabilityInfo) {
        if (capabilityInfo.nodes.isEmpty()) {
            Log.d("RecognitionForegroundService", "Mobile device disconnected, stopping service")
            val intent = Intent("TRAIN_SERVICE_STATUS_CHANGED")
            intent.putExtra("SERVICE_STATUS", ServiceStatus.NOT_CONNECTED)
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
            stopReason = ServiceStopReason.WEAR_DEVICE_DISCONNECTED
            stopSelf() // Stop the service if the mobile device is disconnected
        } else {
            Log.d("RecognitionForegroundService", "Mobile device connected")
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
            RecognitionForegroundServiceHolder.service?.let { service ->
                if (getServiceStatus() in listOf(ServiceStatus.WAITING)) {
                    Log.d("RecognitionForegroundService DataListener", "Wear OS device confirmed sensor capture started")
                    service.resetLastMessageFromWearTime()
                    service.setServiceStatus(ServiceStatus.RECEIVING)
                }
            }
        },

        "/sensor_data" to { sensorDataString ->
            RecognitionForegroundServiceHolder.service?.let { service ->
                if ((getServiceStatus() in listOf(ServiceStatus.RECEIVING)) && (sensorDataString ?: "").contains("|")) {
                    service.resetLastMessageFromWearTime()
                    service.receivedSensorDataPacketsCount++
                    service.processSensorDataString(sensorDataString ?: "")
                    service.updateNotification()

                    val currentTime = System.currentTimeMillis()
                    if (currentTime - lastConfirmationSentTime >= CONFIRMATION_TIME_INTERVAL) {
                        Log.d("RecognitionForegroundService DataListener", "Sending confirmation to Wear OS device")
                        ConnectionManager.messageQueue.confirmMoreSensorDataNeeded()
                        lastConfirmationSentTime = currentTime
                    }
                }
            }
        },
    )
}