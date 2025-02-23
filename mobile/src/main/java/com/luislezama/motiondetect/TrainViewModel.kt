package com.luislezama.motiondetect

import android.content.Context
import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileWriter
import java.io.IOException

enum class TrainStatus {
    IDLE,
    DELAYED_START,
    WAITING,
    RECEIVING
}

class TrainViewModel : ViewModel() {
    // Train session status
    val status = MutableLiveData<TrainStatus>(TrainStatus.IDLE)
    fun setStatus(value: TrainStatus) {
        status.value = value

        if (value == TrainStatus.IDLE) {
            sessionCsvFile = null
        }
    }


    // Session parameters
    val action = MutableLiveData<String>("standing")
    val alias = MutableLiveData<String>()
    val user = MutableLiveData<String>()
    val userHand = MutableLiveData<String>("left")
    val stopAfter = MutableLiveData<Int>(null)
    val samplesPerPacket = MutableLiveData<Int>(10)
    val delayedStart = MutableLiveData<Int>(10)


    // Delayed start counter and management
    val delayedStartCounter = MutableLiveData<Long>()
    val remainingTime = MutableLiveData<Long>()
    private var countDownTimer: CountDownTimer? = null

    fun startDelayedStartCountdown(onFinish: () -> Unit) {
        setStatus(TrainStatus.DELAYED_START)
        delayedStartCounter.value = delayedStart.value!!.toLong()
        countDownTimer = object : CountDownTimer(delayedStartCounter.value!! * 1000, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                remainingTime.value = millisUntilFinished / 1000
            }

            override fun onFinish() {
                remainingTime.value = 0
                onFinish()
            }
        }.start()
    }

    fun cancelDelayedStartCountdown() {
        countDownTimer?.cancel()
        remainingTime.value = 0
        setStatus(TrainStatus.IDLE)
    }


    // Data timeout
    val requestTimeoutInMs: Long = 10000 // Stop receiving data if no data from wear is received in 10 seconds
    var lastSensorDataTime: Long = 0
    fun resetLastSensorDataTime() {
        lastSensorDataTime = System.currentTimeMillis()
    }
    fun startTimeoutChecker(onTimeout: () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(requestTimeoutInMs)
                if (System.currentTimeMillis() - lastSensorDataTime > requestTimeoutInMs) {
                    withContext(Dispatchers.Main) {
                        if (status.value in listOf(TrainStatus.WAITING, TrainStatus.RECEIVING)) {
                            setStatus(TrainStatus.IDLE)
                            onTimeout()
                        }
                    }
                    break
                }
            }
        }
    }


    // CSV file management
    private var sessionCsvFile: File? = null
    val appendedDataCount = MutableLiveData<Int>(0)
    fun createCSVFile(context: Context): Boolean {
        appendedDataCount.value = 0
        val unixTimestamp = System.currentTimeMillis() / 1000

        var sessionAlias = alias.value ?: ""
        if (sessionAlias.isEmpty()) {
            sessionAlias = "noalias"
        }

        val fileName = "${unixTimestamp}_${sessionAlias}.csv"
        val file = File(context.filesDir, fileName) // Store CSV files in the app's internal storage directory

        try {
            val writer = FileWriter(file)
            // Puedes agregar encabezados de columna aquí si es necesario
            // writer.append("Columna1,Columna2,Columna3\n")
            writer.flush()
            writer.close()
            sessionCsvFile = file
            Log.d("TrainViewModel", "CSV file created: $fileName")
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            sessionCsvFile = null
            return false
        }
    }
    fun sessionFileAlreadyExists(context: Context): Boolean {
        val fileList = context.filesDir.listFiles { file -> file.extension == "csv" }?.toMutableList() ?: mutableListOf()
        var sessionAlias = alias.value ?: ""
        var exists = false
        if (sessionAlias.isEmpty()) {
            sessionAlias = "noalias"
        }
        for (file in fileList) {
            if (file.nameWithoutExtension.split("_")[1] == sessionAlias) {
                exists = true
                break
            }
        }
        return exists
    }
    fun appendDataToCSVFile(accData: Array<Float>, gyroData: Array<Float>) : Int? {
        if (sessionCsvFile == null) {
            return null
        }

        val unixTimestamp = System.currentTimeMillis() / 1000
        var sessionAction = action.value ?: "standing"
        var sessionAlias = alias.value ?: ""
        if (sessionAlias.isEmpty()) {
            sessionAlias = "noalias"
        }
        var userName = user.value ?: ""
        if (userName.isEmpty()) {
            userName = "nouser"
        }
        var userHand = userHand.value ?: "left"
        var samplesPerPacket = samplesPerPacket.value ?: 10

        var accDataString = accData.joinToString(",")
        var gyroDataString = gyroData.joinToString(",")

        try {
            val writer = FileWriter(sessionCsvFile, true)
            writer.append("$unixTimestamp,$sessionAction,$sessionAlias,$userName,$userHand,$samplesPerPacket,$accDataString,$gyroDataString\n")
            appendedDataCount.value = appendedDataCount.value!! + 1
            writer.flush()
            writer.close()
            return appendedDataCount.value
        } catch (e: IOException) {
            e.printStackTrace()
            return null
        }
    }
    fun deleteCSVFileIfEmpty() {
        if (sessionCsvFile != null) {
            if (appendedDataCount.value == 0) {
                sessionCsvFile?.delete()
                Log.d("TrainViewModel", "CSV file deleted: ${sessionCsvFile?.name}")
                sessionCsvFile = null
            }
        }
    }
}