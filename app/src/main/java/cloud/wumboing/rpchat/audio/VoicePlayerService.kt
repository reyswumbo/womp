package cloud.wumboing.rpchat.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import cloud.wumboing.rpchat.R
import cloud.wumboing.rpchat.ui.MainActivity

/**
 * Service pemutar pesan suara. Berjalan sebagai foreground service supaya
 * pemutaran tetap lanjut walau aplikasi ditutup/di-minimize (mirip pesan
 * suara WhatsApp/Messenger). Status pemutaran dibagikan lewat companion
 * object supaya UI (bubble chat) bisa sinkron tanpa terikat lifecycle Activity.
 */
class VoicePlayerService : Service() {

    data class State(
        val messageId: String? = null,
        val path: String? = null,
        val isPlaying: Boolean = false,
        val positionMs: Int = 0,
        val durationMs: Int = 0
    )

    companion object {
        private const val ACTION_PLAY_OR_TOGGLE = "cloud.wumboing.rpchat.audio.PLAY_OR_TOGGLE"
        private const val ACTION_SEEK = "cloud.wumboing.rpchat.audio.SEEK"
        private const val ACTION_STOP = "cloud.wumboing.rpchat.audio.STOP"
        private const val EXTRA_MESSAGE_ID = "extra_message_id"
        private const val EXTRA_PATH = "extra_path"
        private const val EXTRA_POSITION = "extra_position"

        private const val CHANNEL_ID = "voice_playback"
        private const val NOTIF_ID = 4201

        @Volatile
        var state: State = State()
            private set

        private val listeners = mutableListOf<(State) -> Unit>()

        fun addListener(listener: (State) -> Unit) {
            listeners.add(listener)
        }

        fun removeListener(listener: (State) -> Unit) {
            listeners.remove(listener)
        }

        /** Mulai memutar pesan suara [messageId], atau jeda/lanjutkan jika pesan itu yang sedang aktif. */
        fun playOrToggle(context: Context, messageId: String, path: String) {
            val intent = Intent(context, VoicePlayerService::class.java).apply {
                action = ACTION_PLAY_OR_TOGGLE
                putExtra(EXTRA_MESSAGE_ID, messageId)
                putExtra(EXTRA_PATH, path)
            }
            startCompat(context, intent)
        }

        fun seekTo(context: Context, positionMs: Int) {
            val intent = Intent(context, VoicePlayerService::class.java).apply {
                action = ACTION_SEEK
                putExtra(EXTRA_POSITION, positionMs)
            }
            startCompat(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, VoicePlayerService::class.java).apply {
                action = ACTION_STOP
            }
            startCompat(context, intent)
        }

        private fun startCompat(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                // Abaikan jika service tidak bisa dijalankan (mis. izin ditolak sistem)
            }
        }
    }

    private var mediaPlayer: MediaPlayer? = null
    private val progressHandler = Handler(Looper.getMainLooper())

    private val progressTick = object : Runnable {
        override fun run() {
            val mp = mediaPlayer
            if (mp != null && state.isPlaying) {
                publishState(state.copy(positionMs = mp.currentPosition))
                updateNotification()
                progressHandler.postDelayed(this, 300)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_OR_TOGGLE -> {
                val messageId = intent.getStringExtra(EXTRA_MESSAGE_ID)
                val path = intent.getStringExtra(EXTRA_PATH)
                if (messageId != null && path != null) {
                    if (state.messageId == messageId && mediaPlayer != null) {
                        togglePlayPause()
                    } else {
                        startPlayback(messageId, path)
                    }
                }
            }
            ACTION_SEEK -> {
                val pos = intent.getIntExtra(EXTRA_POSITION, 0)
                try {
                    mediaPlayer?.seekTo(pos)
                } catch (e: Exception) { /* posisi tidak valid, abaikan */ }
                publishState(state.copy(positionMs = pos))
            }
            ACTION_STOP -> {
                releasePlayer()
                stopForeground(true)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun startPlayback(messageId: String, path: String) {
        releasePlayer()
        try {
            val mp = MediaPlayer()
            mp.setDataSource(path)
            mp.setOnPreparedListener { prepared ->
                publishState(
                    State(
                        messageId = messageId,
                        path = path,
                        isPlaying = true,
                        positionMs = 0,
                        durationMs = prepared.duration
                    )
                )
                prepared.start()
                startForeground(NOTIF_ID, buildNotification())
                progressHandler.post(progressTick)
            }
            mp.setOnCompletionListener {
                publishState(state.copy(isPlaying = false, positionMs = 0))
                stopForeground(true)
                stopSelf()
            }
            mp.setOnErrorListener { _, _, _ ->
                releasePlayer()
                stopForeground(true)
                stopSelf()
                true
            }
            mediaPlayer = mp
            mp.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            stopForeground(true)
            stopSelf()
        }
    }

    private fun togglePlayPause() {
        val mp = mediaPlayer ?: return
        if (mp.isPlaying) {
            mp.pause()
            publishState(state.copy(isPlaying = false))
        } else {
            mp.start()
            publishState(state.copy(isPlaying = true))
            progressHandler.post(progressTick)
        }
        updateNotification()
    }

    private fun releasePlayer() {
        progressHandler.removeCallbacks(progressTick)
        mediaPlayer?.let {
            try { it.stop() } catch (e: Exception) { /* sudah berhenti */ }
            it.release()
        }
        mediaPlayer = null
        publishState(State())
    }

    private fun publishState(newState: State) {
        state = newState
        val listenerSnapshot = listeners.toList()
        listenerSnapshot.forEach { it(newState) }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.voice_playback_channel),
                    NotificationManager.IMPORTANCE_LOW
                )
                mgr.createNotificationChannel(channel)
            }
        }
    }

    private fun updateNotification() {
        if (mediaPlayer == null) return
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val playing = state.isPlaying
        val toggleIntent = Intent(this, VoicePlayerService::class.java).apply {
            action = ACTION_PLAY_OR_TOGGLE
            putExtra(EXTRA_MESSAGE_ID, state.messageId)
            putExtra(EXTRA_PATH, state.path)
        }
        val togglePending = PendingIntent.getService(
            this, 0, toggleIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_audio)
            .setContentTitle(getString(R.string.voice_message_playing))
            .setContentText(getString(if (playing) R.string.voice_playing else R.string.voice_paused))
            .setContentIntent(contentIntent)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.action_pause else R.string.action_play),
                togglePending
            )
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        releasePlayer()
    }
}
