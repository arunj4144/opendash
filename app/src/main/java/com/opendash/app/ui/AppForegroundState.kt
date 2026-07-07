package com.opendash.app.ui

/**
 * Shared foreground flag, readable from BccuConnectionService (which outlives
 * MainActivity - it's a foreground service, the Activity is not). Needed
 * because "bring the app back to front on Back-from-idle" cannot depend on
 * MainActivity's own coroutine to notice the button press: if Android has
 * destroyed the backgrounded Activity, nothing is left to react to it.
 */
object AppForegroundState {
    @Volatile
    var isForeground: Boolean = false
}
