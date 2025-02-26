package com.luislezama.motiondetect

import com.google.android.material.color.DynamicColors
import com.luislezama.motiondetect.deviceconnection.ConnectionManager

class Application: android.app.Application() {
    override fun onCreate() {
        super.onCreate()

        ConnectionManager.initialize(applicationContext)

        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}