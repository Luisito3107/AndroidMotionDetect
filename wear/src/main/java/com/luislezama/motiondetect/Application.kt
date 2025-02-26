package com.luislezama.motiondetect

import com.luislezama.motiondetect.deviceconnection.ConnectionManager

class Application : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        ConnectionManager.initialize(applicationContext)
    }
}