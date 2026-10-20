package com.aether.stream.core.platform

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext

@Composable
actual fun EnforceLandscapeCockpitMode() {
    val context = LocalContext.current
    SideEffect {
        val activity = context as? Activity ?: return@SideEffect
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }
}

actual fun getPlatformHardwareSeed(): String {
    return "${Build.BOARD}-${Build.MODEL}-${Build.MANUFACTURER}-${Build.DEVICE}"
}