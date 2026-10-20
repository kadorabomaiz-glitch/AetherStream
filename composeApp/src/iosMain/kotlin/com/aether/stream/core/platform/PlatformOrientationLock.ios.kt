package com.aether.stream.core.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice
import platform.UIKit.UIWindowScene

@Composable
actual fun EnforceLandscapeCockpitMode() {
    SideEffect {
        val scenes = UIApplication.sharedApplication.connectedScenes
        val windowScene = scenes.firstOrNull() as? UIWindowScene
        windowScene?.let {
            UIApplication.sharedApplication.supportedInterfaceOrientationsForWindow(it.keyWindow)
        }
    }
}

actual fun getPlatformHardwareSeed(): String {
    val vendorId = UIDevice.currentDevice.identifierForVendor?.UUIDString ?: "AETHER-IOS-DEFAULT"
    val model = UIDevice.currentDevice.model
    return "$model-$vendorId"
}