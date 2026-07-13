package com.navigator.app.audio

import android.content.ComponentName
import android.content.Context
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import com.navigator.app.notifications.AppNotificationListener

/**
 * Play/pause/next/previous for whichever app currently holds an active
 * media session - the same mechanism a Bluetooth headset's physical buttons
 * use, so it works with any app that supports media sessions without us
 * needing to know anything about that app. Requires notification-listener
 * access (already needed for notification mirroring) since
 * getActiveSessions() requires a bound NotificationListenerService component.
 */
class MediaControlBridge(private val context: Context) {

    private fun activeController(): android.media.session.MediaController? {
        val manager = context.getSystemService(MediaSessionManager::class.java) ?: return null
        val component = ComponentName(context, AppNotificationListener::class.java)
        return try {
            manager.getActiveSessions(component).firstOrNull()
        } catch (e: SecurityException) {
            null
        }
    }

    fun playPause() {
        val controller = activeController() ?: return
        val isPlaying = controller.playbackState?.state == PlaybackState.STATE_PLAYING
        if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
    }

    fun next() {
        activeController()?.transportControls?.skipToNext()
    }

    fun previous() {
        activeController()?.transportControls?.skipToPrevious()
    }
}
