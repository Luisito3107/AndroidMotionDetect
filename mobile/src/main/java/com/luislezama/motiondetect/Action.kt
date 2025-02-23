package com.luislezama.motiondetect

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.luislezama.motiondetect.R

enum class Action(@DrawableRes val icon: Int, @StringRes val stringname: Int, val value: String) {
    STANDING(R.drawable.ic_action_standing, R.string.base_action_standing, "standing"),
    WALKING(R.drawable.ic_action_walking, R.string.base_action_walking, "walking"),
    RUNNING(R.drawable.ic_action_running, R.string.base_action_running, "running"),
    SITTING(R.drawable.ic_action_sitting, R.string.base_action_sitting, "sitting"),
    LYING(R.drawable.ic_action_lying, R.string.base_action_lying, "lying"),
}