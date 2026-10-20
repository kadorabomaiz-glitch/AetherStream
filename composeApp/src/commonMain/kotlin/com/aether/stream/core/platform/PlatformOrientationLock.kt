package com.aether.stream.core.platform

import androidx.compose.runtime.Composable

@Composable
expect fun EnforceLandscapeCockpitMode()

expect fun getPlatformHardwareSeed(): String