package com.luislezama.motiondetect.data

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel



class RecognitionViewModel : ViewModel() {
    // Session parameters
    val userHand = MutableLiveData<HandOption>(HandOption.LEFT)
    val samplesPerPacket = MutableLiveData<Int>(10)
    val delayedStart = MutableLiveData<Int>(5)


    // Recognition service status for UI, will be updated by the intents sent by RecognitionForegroundService
    val serviceStatus: MutableLiveData<RecognitionForegroundService.ServiceStatus> = MutableLiveData(null)
}