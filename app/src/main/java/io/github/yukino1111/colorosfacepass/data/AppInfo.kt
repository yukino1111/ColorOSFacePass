package io.github.yukino1111.colorosfacepass.data

import android.graphics.drawable.Drawable

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable?,
    val isSystem: Boolean,
    var isSelected: Boolean = false,
)
