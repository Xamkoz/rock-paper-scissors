package com.rpsonline.app.data.preferences

import androidx.annotation.DrawableRes
import com.rpsonline.app.R

enum class AppBackground(
    @DrawableRes val drawableRes: Int,
) {
    TROPHY(R.drawable.bg_trophy),
    ;

    val label: String
        get() = when (this) {
            TROPHY -> "Champion Trophy"
        }

    companion object {
        fun fromPreference(value: String?): AppBackground =
            entries.find { it.name == value } ?: TROPHY
    }
}
