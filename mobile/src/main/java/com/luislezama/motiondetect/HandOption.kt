package com.luislezama.motiondetect

import androidx.annotation.StringRes

enum class HandOption(val value: String, @StringRes val displayStringRes: Int) {
    LEFT("left", R.string.train_details_user_hand_left),
    RIGHT("right", R.string.train_details_user_hand_right)
}