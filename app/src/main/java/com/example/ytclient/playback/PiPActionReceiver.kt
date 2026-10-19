package com.example.ytclient.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Handles taps on the Picture-in-Picture transport controls (play/pause, next), routing them to the
 * [PlaybackController]. Manifest receivers run [onReceive] on the main thread, so touching the player
 * here is safe.
 */
class PiPActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_TOGGLE -> PlaybackController.togglePlayPause()
            ACTION_NEXT -> PlaybackController.next()
        }
    }

    companion object {
        const val ACTION_TOGGLE = "com.example.ytclient.playback.action.TOGGLE"
        const val ACTION_NEXT = "com.example.ytclient.playback.action.NEXT"
    }
}
