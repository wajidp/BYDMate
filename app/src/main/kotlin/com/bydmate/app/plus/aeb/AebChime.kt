package com.bydmate.app.plus.aeb

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * The soft "ding" that says Keep AEB off just switched AEB off. Synthesized, so the APK carries no
 * sound file: a 1046 Hz bell (C6) with a quiet octave overtone, a few ms attack and a smooth decay,
 * about 0.4 s. Played as a notification sound, so it follows the car's notification volume.
 */
internal object AebChime {
    private const val RATE = 44_100
    private const val SECONDS = 0.4
    private const val FUNDAMENTAL_HZ = 1046.5
    private const val PEAK = 0.35
    private const val ATTACK_SECONDS = 0.004
    private const val DECAY_PER_SECOND = 9.0

    /** 16-bit mono PCM of the chime. Pure, so a test can check its shape. */
    fun pcm(): ShortArray {
        val n = (RATE * SECONDS).toInt()
        return ShortArray(n) { i ->
            val t = i.toDouble() / RATE
            val attack = (t / ATTACK_SECONDS).coerceAtMost(1.0)
            val wave = sin(2 * PI * FUNDAMENTAL_HZ * t) + 0.25 * sin(4 * PI * FUNDAMENTAL_HZ * t)
            (wave / 1.25 * attack * exp(-DECAY_PER_SECOND * t) * PEAK * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /** Plays the chime once; never throws, a missing audio route is simply silent. */
    @Suppress("TooGenericExceptionCaught") // audio output is optional, whatever fails stays silent
    fun play() {
        try {
            val samples = pcm()
            val track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(samples.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
            track.write(samples, 0, samples.size)
            track.setNotificationMarkerPosition(samples.size)
            track.setPlaybackPositionUpdateListener(object : AudioTrack.OnPlaybackPositionUpdateListener {
                override fun onMarkerReached(t: AudioTrack) = t.release()
                override fun onPeriodicNotification(t: AudioTrack) = Unit
            })
            track.play()
        } catch (e: Exception) {
            android.util.Log.w("AebChime", "no chime: ${e.message}")
        }
    }
}
