package com.sample.monochome

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition

class MediaPlaybackService : Service() {

    private lateinit var mediaSession: MediaSessionCompat
    private val channelId = "MediaPlaybackChannel"
    private val notificationId = 1

    private var currentTitle = ""
    private var currentArtist = ""
    private var currentCoverUrl = ""
    private var isPlaying = false
    private var currentDuration = 0L
    private var currentPosition = 0L
    private var isLiked = false
    private var currentCoverBitmap: Bitmap? = null

    companion object {
        const val ACTION_PLAY_PAUSE = "com.sample.monochome.ACTION_PLAY_PAUSE"
        const val ACTION_NEXT = "com.sample.monochome.ACTION_NEXT"
        const val ACTION_PREV = "com.sample.monochome.ACTION_PREV"
        const val ACTION_LIKE = "com.sample.monochome.ACTION_LIKE"
        const val ACTION_SEEK_TO = "com.sample.monochome.ACTION_SEEK_TO"
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.sample.monochome.PLAYBACK_STATE_UPDATED") {
                val newTitle = intent.getStringExtra("title") ?: ""
                val newArtist = intent.getStringExtra("artist") ?: ""
                val newCoverUrl = intent.getStringExtra("coverUrl") ?: ""
                val newIsPlaying = intent.getBooleanExtra("isPlaying", false)
                val newDuration = intent.getLongExtra("duration", 0L)
                val newPosition = intent.getLongExtra("position", 0L)
                val newIsLiked = intent.getBooleanExtra("isLiked", false)

                val contentChanged = currentTitle != newTitle || currentArtist != newArtist
                val likeChanged = isLiked != newIsLiked

                // Only update the cover URL when the song actually changes (title/artist)
                // This prevents dynamic/animated album art from flickering in the notification
                if (contentChanged) {
                    currentCoverUrl = newCoverUrl
                    currentCoverBitmap = null // Reset so we re-fetch for the new song
                }

                currentTitle = newTitle
                currentArtist = newArtist
                isPlaying = newIsPlaying
                currentDuration = newDuration
                currentPosition = newPosition
                isLiked = newIsLiked

                updateMediaSession()
                
                if (contentChanged && currentCoverUrl.isNotEmpty()) {
                    loadCoverImageAndNotify()
                } else if (contentChanged || likeChanged) {
                    updateNotification()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        mediaSession = MediaSessionCompat(this, "MonochromeMediaSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { broadcastAction(ACTION_PLAY_PAUSE) }
                override fun onPause() { broadcastAction(ACTION_PLAY_PAUSE) }
                override fun onSkipToNext() { broadcastAction(ACTION_NEXT) }
                override fun onSkipToPrevious() { broadcastAction(ACTION_PREV) }
                override fun onSeekTo(pos: Long) { broadcastAction(ACTION_SEEK_TO, pos) }
                override fun onCustomAction(action: String?, extras: Bundle?) {
                    if (action == ACTION_LIKE) {
                        broadcastAction(ACTION_LIKE)
                    }
                }
            })
            isActive = true
        }

        ContextCompat.registerReceiver(
            this,
            stateReceiver,
            IntentFilter("com.sample.monochome.PLAYBACK_STATE_UPDATED"),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    private fun broadcastAction(action: String, position: Long = -1L) {
        val intent = Intent(action).setPackage(packageName)
        if (position != -1L) intent.putExtra("position", position)
        sendBroadcast(intent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "START_FOREGROUND") {
            startForegroundWithNotification()
        }
        return START_NOT_STICKY
    }

    private fun loadCoverImageAndNotify() {
        Glide.with(this).asBitmap().load(currentCoverUrl).into(object : CustomTarget<Bitmap>() {
            override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                currentCoverBitmap = resource
                updateMediaSession()
                updateNotification()
            }
            override fun onLoadCleared(placeholder: Drawable?) {
                currentCoverBitmap = null
            }
        })
    }

    private fun updateMediaSession() {
        val playbackState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        
        val likeIcon = if (isLiked) R.drawable.ic_like_filled else R.drawable.ic_like
        val likeTitle = if (isLiked) "Unlike" else "Like"
        
        val likeAction = PlaybackStateCompat.CustomAction.Builder(
            ACTION_LIKE,
            likeTitle,
            likeIcon
        ).build()

        mediaSession.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_SEEK_TO)
                .addCustomAction(likeAction)
                .setState(playbackState, currentPosition, 1.0f)
                .build()
        )

        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, currentDuration)

        if (currentCoverBitmap != null) {
            metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, currentCoverBitmap)
        }

        mediaSession.setMetadata(metadataBuilder.build())
    }

    private fun startForegroundWithNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(notificationId, notification)
        }
    }

    private fun updateNotification() {
        val notification = buildNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(notificationId, notification)
    }

    private fun buildNotification(): android.app.Notification {
        val playPauseIcon = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val playPauseActionText = if (isPlaying) "Pause" else "Play"

        val likeIntent = PendingIntent.getBroadcast(this, 1, Intent(ACTION_LIKE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val prevIntent = PendingIntent.getBroadcast(this, 2, Intent(ACTION_PREV).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val playPauseIntent = PendingIntent.getBroadcast(this, 3, Intent(ACTION_PLAY_PAUSE).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        val nextIntent = PendingIntent.getBroadcast(this, 4, Intent(ACTION_NEXT).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)

        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpenAppIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE)

        val likeIcon = if (isLiked) R.drawable.ic_like_filled else R.drawable.ic_like
        val likeTitle = if (isLiked) "Unlike" else "Like"

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(if (currentTitle.isEmpty()) "Monochrome" else currentTitle)
            .setContentText(currentArtist)
            .setLargeIcon(currentCoverBitmap)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(pendingOpenAppIntent)
            .addAction(likeIcon, likeTitle, likeIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, playPauseActionText, playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .setOngoing(isPlaying)

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Shows media playback controls"
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stateReceiver)
        mediaSession.release()
    }
}
