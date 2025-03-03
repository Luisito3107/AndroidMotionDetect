package com.luislezama.motiondetect.data

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel



class TrainViewModel : ViewModel() {
    // Session parameters
    val action = MutableLiveData<Action>(Action.STANDING)
    val alias = MutableLiveData<String>("")
    val user = MutableLiveData<String>("")
    val userHand = MutableLiveData<HandOption>(HandOption.LEFT)
    val stopAfter = MutableLiveData<Int>(null)
    val samplesPerPacket = MutableLiveData<Int>(10)
    val delayedStart = MutableLiveData<Int>(5)



    // Train session status
    val serviceStatus: MutableLiveData<TrainForegroundService.ServiceStatus> = MutableLiveData(null)
}