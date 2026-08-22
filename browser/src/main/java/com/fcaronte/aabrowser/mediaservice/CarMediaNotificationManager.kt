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
    private var mCarmediaservice: CarMediaService? = null
    private var mNotificationmanager: NotificationManager? = null

    private var mPlayaction: NotificationCompat.Action? = null
    private var mPauseaction: NotificationCompat.Action? = null
    private var mNextaction: NotificationCompat.Action? = null
    private var mPrevaction: NotificationCompat.Action? = null

    fun setCarMediaService(carMediaService: CarMediaService?) {
        mCarmediaservice = carMediaService
    }

    fun onCreate() {
        if (mCarmediaservice != null) {
            mNotificationmanager =
                mCarmediaservice!!.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager?
            cancel()

            mPlayaction =
                NotificationCompat.Action(
                    R.drawable.ic_play_arrow_black_24dp,
                    mCarmediaservice!!.getString(R.string.label_play),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mCarmediaservice, PlaybackStateCompat.ACTION_PLAY
                    )
                )
            mPauseaction =
                NotificationCompat.Action(
                    R.drawable.ic_pause_black_24dp,
                    mCarmediaservice!!.getString(R.string.label_pause),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mCarmediaservice, PlaybackStateCompat.ACTION_PAUSE
                    )
                )
            mPrevaction =
                NotificationCompat.Action(
                    R.drawable.ic_skip_previous_black_24dp,
                    mCarmediaservice!!.getString(R.string.label_previous),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mCarmediaservice, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS
                    )
                )
            mNextaction =
                NotificationCompat.Action(
                    R.drawable.ic_skip_next_black_24dp,
                    mCarmediaservice!!.getString(R.string.label_next),
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mCarmediaservice, PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                    )
                )
        }
    }

    fun onDestroy() {
        cancel()
        mCarmediaservice = null
        mNotificationmanager = null
        mPlayaction = null
        mPauseaction = null
        mNextaction = null
        mPrevaction = null
    }

    fun notify(notification: Notification?) {
        if (mNotificationmanager != null) mNotificationmanager!!.notify(
            NOTIFICATION_ID,
            notification
        )
    }

    fun cancel() {
        if (mNotificationmanager != null) mNotificationmanager!!.cancel(NOTIFICATION_ID)
    }

    fun getNotification(
        metadata: MediaMetadataCompat?,
        state: PlaybackStateCompat?,
        token: MediaSessionCompat.Token?
    ): Notification? {
        if (mCarmediaservice == null || metadata == null || state == null || token == null) return null

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

        val isPlaying = state.state == PlaybackStateCompat.STATE_PLAYING

        val builder: NotificationCompat.Builder =
            NotificationCompat.Builder(mCarmediaservice!!, CHANNEL_ID)
        builder.setStyle(
            androidx.media.app.NotificationCompat.MediaStyle()
                .setMediaSession(token)
                .setShowActionsInCompactView(0, 1, 2)
                .setShowCancelButton(true)
                .setCancelButtonIntent(
                    MediaButtonReceiver.buildMediaButtonPendingIntent(
                        mCarmediaservice, PlaybackStateCompat.ACTION_STOP
                    )
                )
        )

        builder.setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(description.title)
            .setContentText(description.subtitle)
            .setContentIntent(createContentIntent())
            .setOngoing(isPlaying)

        builder.setDeleteIntent(
            MediaButtonReceiver.buildMediaButtonPendingIntent(
                mCarmediaservice, PlaybackStateCompat.ACTION_STOP
            )
        )

        if ((state.actions and PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) != 0L) builder.addAction(
            mPrevaction
        )

        builder.addAction(if (isPlaying) mPauseaction else mPlayaction)

        if ((state.actions and PlaybackStateCompat.ACTION_SKIP_TO_NEXT) != 0L) builder.addAction(
            mNextaction
        )

        return builder
    }

    private fun createChannel() {
        if (mNotificationmanager != null && mNotificationmanager!!.getNotificationChannel(
                CHANNEL_ID
            ) == null
        ) {
            val channel =
                NotificationChannel(
                    CHANNEL_ID,
                    mCarmediaservice!!.getString(R.string.media_session_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            channel.description = mCarmediaservice!!.getString(R.string.media_session_channel_desc)
            mNotificationmanager!!.createNotificationChannel(channel)
        }
    }

    private fun createContentIntent(): PendingIntent? {
        val openUI = Intent()
        openUI.setClassName("com.fcaronte.aabrowser", "com.fcaronte.aabrowser.MainActivity")
        openUI.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        var flags = PendingIntent.FLAG_CANCEL_CURRENT
        flags =
            flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(mCarmediaservice, REQUEST_CODE, openUI, flags)
    }

    companion object {
        private const val TAG = "CarMediaNotificationManager"

        private const val CHANNEL_ID = "com.fcaronte.aabrowser.mediachannel"
        private const val REQUEST_CODE = 500
        private const val NOTIFICATION_ID = 600
    }
}
