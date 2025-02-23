package com.luislezama.motiondetect.deviceconnection

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class WearConnectionViewModel : ViewModel() {
    lateinit var wearConnectionManager: WearConnectionManager

    var captureStarted = MutableLiveData<Boolean>()
    var samplesPerPacket = MutableLiveData<Int>()
    var recievedSensorMessages = MutableLiveData<Int>()
    var sensorDataString = MutableLiveData<String>()
}