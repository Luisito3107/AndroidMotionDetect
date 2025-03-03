package com.luislezama.motiondetect.data

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import com.luislezama.motiondetect.R

enum class Action(val value: String, @DrawableRes val drawableResource: Int, @StringRes val stringResource: Int) {
    STANDING("standing", R.drawable.ic_action_standing, R.string.base_action_standing),
    WALKING("walking", R.drawable.ic_action_walking, R.string.base_action_walking),
    RUNNING("running", R.drawable.ic_action_running, R.string.base_action_running),
    SITTING("sitting", R.drawable.ic_action_sitting, R.string.base_action_sitting),
    LYING("lying", R.drawable.ic_action_lying, R.string.base_action_lying);

    override fun toString(): String {
        return value
    }

    companion object {
        fun fromString(value: String): Action {
            return entries.find { it.value == value } ?: STANDING
        }
    }
}