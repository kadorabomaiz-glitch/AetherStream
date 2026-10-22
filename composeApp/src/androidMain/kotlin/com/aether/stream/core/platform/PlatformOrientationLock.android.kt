package com.aether.stream.core.platform

import android.os.Build
import androidx.compose.runtime.Composable

@Composable
actual fun EnforceLandscapeCockpitMode() {
    // Auf Fire TV / Android TV ist der Bildschirm physisch fest im 16:9-Querformat.
    // Dynamische setRequestedOrientation-Aufrufe werfen auf Fire OS 7/8 im Fullscreen-Modus
    // eine IllegalStateException und werden hier bewusst unterdrückt.
}

actual fun getPlatformHardwareSeed(): String {
    return "${Build.BOARD}-${Build.MODEL}-${Build.MANUFACTURER}-${Build.DEVICE}"
}
