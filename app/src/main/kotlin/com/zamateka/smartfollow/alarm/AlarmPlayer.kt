package com.zamateka.smartfollow.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlin.math.ceil

/**
 * Plays an alarm sound on the alarm stream, in a loop or once, optionally vibrating. The alarm stream
 * is not muted by Do Not Disturb unless the user also silenced alarms. The alarm volume is
 * set for the duration of the alarm and restored on [stop].
 */
class AlarmPlayer(private val context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java).defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Vibrator::class.java)
    }
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()
    private var player: MediaPlayer? = null
    private var savedVolume: Int? = null

    /**
     * Plays [sound], falling back to the default alarm ringtone if it cannot be played: in a loop
     * until [stop], or once when [loop] is false, the vibration then following the sound once.
     */
    fun start(sound: Uri, vibrate: Boolean, volumePercent: Int, loop: Boolean = true) {
        stop()
        savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
        val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
        val volume = ceil(maxVolume * volumePercent / 100.0).toInt().coerceIn(1, maxVolume)
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
            .onFailure { Log.w(TAG, "Cannot set alarm volume", it) }

        player = (play(sound, loop) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)?.let { play(it, loop) })
            ?.apply { if (!loop) setOnCompletionListener { stop() } }

        if (vibrate) {
            val effect = VibrationEffect.createWaveform(VIBRATION_PATTERN, if (loop) 0 else -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(effect, audioAttributes)
            }
        }
    }

    fun stop() {
        player?.run {
            runCatching { stop() }
            release()
        }
        player = null
        vibrator.cancel()
        savedVolume?.let { volume ->
            runCatching { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) }
        }
        savedVolume = null
    }

    private fun play(sound: Uri, loop: Boolean): MediaPlayer? {
        val mediaPlayer = MediaPlayer()
        return runCatching {
            mediaPlayer.apply {
                setAudioAttributes(audioAttributes)
                setDataSource(context, sound)
                isLooping = loop
                prepare()
                start()
            }
        }.onFailure {
            mediaPlayer.release()
            Log.e(TAG, "Cannot play alarm sound $sound", it)
        }.getOrNull()
    }

    private companion object {
        const val TAG = "AlarmPlayer"
        val VIBRATION_PATTERN = longArrayOf(0, 800, 400, 800, 1200)
    }
}
