package com.visorcraft.radioshed.audio

actual fun isMediaPlaybackAvailable(): Boolean = NativeLibVlc.available
