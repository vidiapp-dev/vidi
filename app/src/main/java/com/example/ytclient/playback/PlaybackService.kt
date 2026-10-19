@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.example.ytclient.playback

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.collect.ImmutableList

/**
 * Thin host for the in-process [MediaSession] created by [PlaybackController]. Its only jobs are to
 * keep the process alive in the foreground while playing and to let the framework post + manage the
 * MediaStyle notification (lockscreen + shade). The ExoPlayer is owned by the player UI, not here.
 */
class PlaybackService : MediaSessionService() {

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(ForegroundSafeNotificationProvider(DefaultMediaNotificationProvider(this)))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        PlaybackController.currentSession()

    override fun onTaskRemoved(rootIntent: Intent?) {
        // If the user swipes the app away and nothing is playing, don't leave a dangling
        // notification / foreground service.
        val player = PlaybackController.currentSession()?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    /**
     * Wraps media3's notification provider to stop one specific exception from crashing the app.
     *
     * Captured on a physical Pixel, 2026-10-17, with the screen just turned off:
     * `FATAL EXCEPTION: main … ForegroundServiceStartNotAllowedException: startForegroundService()
     * not allowed: service …PlaybackService`, from
     * `DefaultMediaNotificationProvider$OnBitmapLoadedFutureCallback.onSuccess` →
     * `MediaNotificationManager.onNotificationUpdated` → `updateNotificationInternal` →
     * `startForeground`.
     *
     * media3 (1.10.1) has two notification-update paths and only guards one. When a notification
     * can be built immediately, `MediaNotificationManager.updateNotification` runs
     * `updateNotificationInternal` inside `try { … } catch (IllegalStateException e)`, and
     * `MediaSessionService` logs it ("Failed to start service into the foreground") and carries on.
     * When the provider has to fetch the ARTWORK first, it calls back later through
     * `onNotificationUpdated`, which reaches the very same `startForeground` with no catch at all —
     * so if the app was backgrounded while the image downloaded, Android 12+ refuses the foreground
     * start and the exception takes down the main thread. (Both paths read directly in the 1.10.1
     * sources: `MediaNotificationManager.java`, `MediaSessionService.java`.)
     *
     * The artwork is what opens the unguarded path, and every session-managed item carries artwork
     * since bf739b8 gave the DASH/progressive items the metadata the lockscreen needs — which is
     * how a previously silent failure on those paths became a crash.
     *
     * Catching works because the callback is synchronous all the way down: DefaultMediaNotification
     * Provider posts its bitmap callback to the session's application handler (the main looper here,
     * where the ExoPlayer is built), and MediaNotificationManager's callback uses `Util.postOrRun`
     * onto that same main looper, which runs inline. If a future media3 posts instead, this catch
     * simply stops being reached — the behaviour degrades to today's, never worse.
     *
     * Swallowing it means accepting what media3's guarded path already accepts: the service stays
     * out of the foreground for now. That isn't a fix for the underlying condition — a session being
     * (re)created while the app is in the background, see PlaybackController — only for the crash.
     */
    private class ForegroundSafeNotificationProvider(
        private val delegate: MediaNotification.Provider,
    ) : MediaNotification.Provider {

        override fun createNotification(
            mediaSession: MediaSession,
            mediaButtonPreferences: ImmutableList<CommandButton>,
            actionFactory: MediaNotification.ActionFactory,
            onNotificationChangedCallback: MediaNotification.Provider.Callback,
        ): MediaNotification = delegate.createNotification(
            mediaSession,
            mediaButtonPreferences,
            actionFactory,
        ) { notification ->
            try {
                onNotificationChangedCallback.onNotificationChanged(notification)
            } catch (e: IllegalStateException) {
                // Only the one exception media3 itself treats as recoverable on its guarded path;
                // anything else is a real bug and must still surface.
                if (Build.VERSION.SDK_INT >= 31 && e is ForegroundServiceStartNotAllowedException) {
                    Log.w(
                        TAG,
                        "artwork-delayed notification update could not start the service in the " +
                            "foreground (app is in the background) — continuing without it",
                    )
                } else {
                    throw e
                }
            }
        }

        override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
            delegate.handleCustomCommand(session, action, extras)

        override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
            delegate.getNotificationChannelInfo()
    }

    private companion object {
        const val TAG = "PlaybackService"
    }
}
