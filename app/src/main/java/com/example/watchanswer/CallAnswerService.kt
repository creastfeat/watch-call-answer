package com.example.watchanswer

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.KeyEvent

private const val TAG = "WatchAnswer"
private val WHATSAPP = setOf("com.whatsapp", "com.whatsapp.w4b")
private val ANSWER = Regex("(?i).*(answer|accept).*")
private val DECLINE = Regex("(?i).*(decline|reject).*")

/**
 * Watches for the WhatsApp incoming-call notification. While it is ringing,
 * a temporary media session captures the watch's media buttons:
 *   Play/Pause -> Answer     Next/Stop -> Decline
 * The session is released as soon as the ringing notification goes away,
 * so your normal music controls are not hijacked.
 */
class CallAnswerService : NotificationListenerService() {

    private var session: MediaSession? = null
    private var callKey: String? = null
    private var answer: PendingIntent? = null
    private var decline: PendingIntent? = null

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in WHATSAPP) return
        val actions = sbn.notification.actions
        actions?.forEach { Log.d(TAG, "WhatsApp action: ${it.title}") } // check logcat if nothing happens

        val a = actions?.firstOrNull { it.title.toString().matches(ANSWER) }
        if (a == null) {
            if (sbn.key == callKey) stopSession() // call answered or ended
            return
        }
        callKey = sbn.key
        answer = a.actionIntent
        decline = actions?.firstOrNull { it.title.toString().matches(DECLINE) }?.actionIntent
        startSession()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == callKey) stopSession()
    }

    override fun onListenerDisconnected() = stopSession()

    private fun startSession() {
        if (session == null) {
            session = MediaSession(this, "WatchAnswer").apply {
                setCallback(object : MediaSession.Callback() {
                    @Suppress("DEPRECATION")
                    override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean {
                        val e = mediaButtonIntent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                            ?: return false
                        if (e.action != KeyEvent.ACTION_DOWN) return true
                        when (e.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_MEDIA_PLAY,
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> fire(answer)
                            KeyEvent.KEYCODE_MEDIA_NEXT,
                            KeyEvent.KEYCODE_MEDIA_STOP -> fire(decline)
                        }
                        return true
                    }
                })
            }
        }
        session?.apply {
            setPlaybackState(
                PlaybackState.Builder()
                    .setActions(PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE)
                    .setState(PlaybackState.STATE_PLAYING, 0, 1f)
                    .build()
            )
            isActive = true
        }
    }

    private fun stopSession() {
        session?.isActive = false
        session?.release()
        session = null
        callKey = null
        answer = null
        decline = null
    }

    private fun fire(pi: PendingIntent?) {
        try {
            pi?.send()
        } catch (e: PendingIntent.CanceledException) {
            Log.w(TAG, "Action no longer valid", e)
        }
    }
}

/** Opens the system screen where you grant Notification access. */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        finish()
    }
}
