package com.txtpodcast

import android.app.*
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import java.util.Locale

/** Reads text chunks aloud in a foreground service so playback continues with the screen off. */
class TtsService : Service(), TextToSpeech.OnInitListener {

    companion object {
        var chunks: List<String> = emptyList()
        var index = 0
        var repeat = false
        var speed = 1.0f
        var pitch = 0.95f
        var voiceName: String? = null
        var playing = false
        var tts: TextToSpeech? = null
        var ready = false
        var onUpdate: (() -> Unit)? = null
        var onReady: (() -> Unit)? = null

        const val ACTION_PLAY = "play"; const val ACTION_PAUSE = "pause"
        const val ACTION_TOGGLE = "toggle"; const val ACTION_STOP = "stop"
        const val ACTION_NEXT = "next"; const val ACTION_PREV = "prev"
        const val ACTION_FWD = "fwd"; const val ACTION_BACK = "back"
        private const val CH = "playback"

        /** Estimated speaking rate: ~150 words/min (~900 chars) at 1.0x. */
        fun charsPerMin() = 900f * speed
        fun charOffset(i: Int) = chunks.take(i).sumOf { it.length }
        fun totalMin() = chunks.sumOf { it.length } / charsPerMin()
        fun elapsedMin() = charOffset(index) / charsPerMin()
        /** Jump to the chunk nearest the given minute position. */
        fun seekToMin(min: Float) {
            if (chunks.isEmpty()) return
            val target = (min.coerceAtLeast(0f) * charsPerMin()).toInt()
            var acc = 0; var i = 0
            while (i < chunks.size - 1 && acc + chunks[i].length <= target) { acc += chunks[i].length; i++ }
            index = i
        }
        fun fmt(min: Float): String { val t = (min * 60).toInt(); return "%d:%02d:%02d".format(t / 3600, t / 60 % 60, t % 60) }

        /** Splits text into sentence-based chunks under the TTS length limit. */
        fun split(text: String): List<String> {
            val max = 500 // small chunks = fine-grained seeking
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            text.split(Regex("(?<=[.!?\\n])\\s+")).forEach { s ->
                var part = s
                while (part.length > max) { out += part.substring(0, max); part = part.substring(max) }
                if (sb.length + part.length > max) { out += sb.toString(); sb.clear() }
                sb.append(part).append(' ')
            }
            if (sb.isNotBlank()) out += sb.toString()
            return out.filter { it.isNotBlank() }
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        if (tts == null) tts = TextToSpeech(applicationContext, this)
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(CH, "Playback", NotificationManager.IMPORTANCE_LOW))
        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SleepyBOI:tts")
    }

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) return
        ready = true
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onError(id: String?) { onDone(id) }
            override fun onDone(id: String?) {
                if (!playing) return
                index++
                if (index >= chunks.size) {
                    if (repeat) index = 0 else { index = 0; pause(); return }
                }
                speakCurrent()
            }
        })
        onReady?.invoke()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        when (intent?.action) {
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_TOGGLE -> if (playing) pause() else play()
            ACTION_NEXT -> { index = (index + 1).coerceAtMost(chunks.size - 1).coerceAtLeast(0); if (playing) speakCurrent() }
            ACTION_PREV -> { index = (index - 1).coerceAtLeast(0); if (playing) speakCurrent() }
            ACTION_FWD -> { seekToMin(elapsedMin() + 10); if (playing) speakCurrent() }
            ACTION_BACK -> { seekToMin(elapsedMin() - 10); if (playing) speakCurrent() }
            "seek" -> { seekToMin(intent.getFloatExtra("min", 0f)); if (playing) speakCurrent() }
            ACTION_STOP -> { pause(); index = 0; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        onUpdate?.invoke()
        return START_STICKY
    }

    private fun play() {
        if (chunks.isEmpty() || !ready) return
        playing = true
        if (wakeLock?.isHeld == false) wakeLock?.acquire(12 * 60 * 60 * 1000L)
        speakCurrent()
    }

    private fun pause() {
        playing = false
        tts?.stop()
        if (wakeLock?.isHeld == true) wakeLock?.release()
        refresh()
    }

    private fun speakCurrent() {
        val t = tts ?: return
        voiceName?.let { n -> t.voices?.firstOrNull { it.name == n }?.let { t.voice = it } }
        t.setSpeechRate(speed); t.setPitch(pitch)
        t.speak(chunks[index], TextToSpeech.QUEUE_FLUSH, Bundle(), "c$index")
        refresh()
    }

    private fun refresh() {
        getSystemService(NotificationManager::class.java).notify(1, buildNotification())
        onUpdate?.invoke()
    }

    private fun pi(action: String, code: Int) = PendingIntent.getService(
        this, code, Intent(this, TtsService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CH)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("SleepyBOI")
            .setContentText(if (chunks.isEmpty()) "Idle" else "${fmt(elapsedMin())} / ${fmt(totalMin())}" + if (repeat) "  🔁" else "")
            .setContentIntent(open)
            .setOngoing(playing)
            .addAction(android.R.drawable.ic_media_rew, "-10 min", pi(ACTION_BACK, 4))
            .addAction(android.R.drawable.ic_media_previous, "Prev", pi(ACTION_PREV, 1))
            .addAction(if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (playing) "Pause" else "Play", pi(ACTION_TOGGLE, 2))
            .addAction(android.R.drawable.ic_media_next, "Next", pi(ACTION_NEXT, 3))
            .addAction(android.R.drawable.ic_media_ff, "+10 min", pi(ACTION_FWD, 5))
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setShowActionsInCompactView(0, 2, 4))
            .build()
    }

    override fun onDestroy() {
        playing = false
        if (wakeLock?.isHeld == true) wakeLock?.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

/** Human-friendly voice label; quality score helps sort the most natural voices first. */
fun Voice.label(): String = "${locale.displayName} • $name" +
    (if (quality >= Voice.QUALITY_HIGH) " ★" else "") + (if (isNetworkConnectionRequired) " (online)" else "")

fun Voice.score(def: Locale): Int =
    quality + (if (locale.language == def.language) 1000 else 0) - (if (isNetworkConnectionRequired) 50 else 0)
