package com.visorcraft.radioshed.permissions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

@Composable
actual fun rememberLocationGate(): LocationGate =
    remember {
        object : LocationGate {
            override val granted: Boolean = true

            override fun request() { /* stub on iOS */ }
        }
    }
