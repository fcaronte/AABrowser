package com.fcaronte.aabrowser.mediaservice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.fcaronte.aabrowser.R

internal class CarMediaNotificationManager {
    private var m_CarMediaService: CarMediaService? = null
    private var m_NotificationManager: NotificationManager? = null

    private var m_PlayAction: NotificationCompat.Action? = null
    private var m_PauseAction: NotificationCompat.Action? = null
    private var m_NextAction: NotificationCompat.Action? = null
    private var m_PrevAction: NotificationCompat.Action? = null

    fun setCarMediaService(carMediaService: CarMediaService?) {
        m_CarMediaService = carMediaService
    }

    fun onCreate() {
        if (m_CarMediaService != null) {
            m_NotificationManager =
                m_CarMediaService!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            cancel()

            m_PlayAction =
                NotificationCompat.Action(
                    R.drawable.ic_play_arrow_black_24dp,
                    m_CarMediaService!!.getString(R.string.label_play),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        m_CarMediaService, PlaybackStateCompat.ACTION_PLAY
                    )
                )
            m_PauseAction =
                NotificationCompat.Action(
                    R.drawable.ic_pause_black_24dp,
                    m_CarMediaService!!.getString(R.string.label_pause),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        m_CarMediaService, PlaybackStateCompat.ACTION_PAUSE
                    )
                )
            m_PrevAction =
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous_black_24dp,
                    m_CarMediaService!!.getString(R.string.label_previous),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        m_CarMediaService, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            m_NextAction =
                NotificationCompat.Action(
                    R.drawable.ic_skip_next_black_24dp,
                    m_CarMediaService!!.getString(R.string.label_next),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        m_CarMediaService, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
        }
    }

    fun onDestroy() {
        cancel()
        m_CarMediaService = null
        m_NotificationManager = null
        m_PlayAction = null
        m_PauseAction = null
        m_NextAction = null
        m_PrevAction = null
    }

    fun notify(notification: Notification?) {
        if (m_NotificationManager != null) m_NotificationManager!!.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    fun cancel() {
        if (m_NotificationManager != null) m_NotificationManager!!.cancel(NOTIFICATION_ID)
    }

    fun getNotification(
        metadata: MediaMetadataCompat?,
        state: PlaybackStateCompat?,
        token: MediaSessionCompat.Token?
    ): Notification? {
        if (m_CarMediaService == null || metadata == null || state == null || token == null) return null

        val description = metadata.getDescription()
        val builder = buildNotification(state, token, description)
        return builder.build()
    }

    private fun buildNotification(
        state: PlaybackStateCompat,
        token: MediaSessionCompat.Token?,
        description: MediaDescriptionCompat
    ): NotificationCompat.Builder {
        createChannel()

        val isPlaying = state.getState() == PlaybackStateCompat.STATE_PLAYING

        val builder: NotificationCompat.Builder =
            NotificationCompat.Builder(m_CarMediaService!!, CHANNEL_ID)
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        m_CarMediaService, PlaybackStateCompat.ACTION_STOP
                    )
                )
        )

        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(description.getTitle())
            .setContentText(description.getSubtitle())
            .setContentIntent(createContentIntent())
            .setOngoing(isPlaying)

        builder.setDeleteIntent(
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                m_CarMediaService, PlaybackStateCompat.ACTION_STOP
            )
        )

        if ((state.getActions() and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0L) builder.addAction(
            m_PrevAction
        )

        builder.addAction(if (isPlaying) m_PauseAction else m_PlayAction)

        if ((state.getActions() and PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0L) builder.addAction(
            m_NextAction
        )

        return builder
    }

    private fun createChannel() {
        if (m_NotificationManager != null && m_NotificationManager!!.getNotificationChannel(
                CHANNEL_ID
            ) == null
        ) {
            val channel =
                NotificationChannel(CHANNEL_ID, m_CarMediaService!!.getString(R.string.media_session_channel_name), NotificationManager.IMPORTANCE_LOW)
            channel.setDescription(m_CarMediaService!!.getString(R.string.media_session_channel_desc))
            m_NotificationManager!!.createNotificationChannel(channel)
        }
    }

    private fun createContentIntent(): PendingIntent? {
        val openUI = Intent()
        openUI.setClassName("com.fcaronte.aabrowser", "com.fcaronte.aabrowser.MainActivity")
        openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        flags =
            flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(m_CarMediaService, REQUEST_CODE, openUI, flags)
    }

    companion object {
        private const val TAG = "CarMediaNotificationManager"

        private const val CHANNEL_ID = "com.fcaronte.aabrowser.mediachannel"
        private const val REQUEST_CODE = 500
        private const val NOTIFICATION_ID = 600
    }
}
