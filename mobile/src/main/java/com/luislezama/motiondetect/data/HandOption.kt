package com.luislezama.motiondetect.data

import androidx.annotation.StringRes
import com.luislezama.motiondetect.R

enum class HandOption(val value: String, @StringRes val stringResource: Int) {
    LEFT("left", R.string.train_details_user_hand_left),
    RIGHT("right", R.string.train_details_user_hand_right);

    override fun toString(): String {
        return value
    }

    companion object {
        fun fromString(value: String): HandOption {
            return entries.find { it.value == value } ?: LEFT
        }
    }
}