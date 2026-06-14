package com.visorcraft.radioshed.car

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Routes hardware key bindings that need Compose UI (station picker, voice
 * search) from [com.visorcraft.radioshed.MainActivity] into [DriveTransportRow].
 * Transport actions (prev/next/play-pause/mute) stay in the Activity for latency.
 */
class DriveKeyDispatcher {
    private val _uiActions = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val uiActions: SharedFlow<String> = _uiActions.asSharedFlow()

    fun dispatchUiAction(actionId: String) {
        _uiActions.tryEmit(actionId)
    }
}
