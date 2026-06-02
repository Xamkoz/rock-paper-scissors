package com.rpsonline.app.ui.util

import android.content.Context
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class ClockTickPlayer(context: Context) {
    private val audioContext = GameAudioContext.wrap(context)
    private var clockTrack: AudioTrack? = null
    private var readyTrack: AudioTrack? = null

    fun playTick() {
        playFromTrack { clockTrack ?: createAudioTrack(CLOCK_AMPLITUDE).also { clockTrack = it } }
    }

    /** Same waveform as [playTick], but louder for pre-game ready confirmations. */
    fun playReadyTick() {
        playFromTrack { readyTrack ?: createAudioTrack(READY_AMPLITUDE).also { readyTrack = it } }
    }

    private inline fun playFromTrack(trackProvider: () -> AudioTrack?) {
        try {
            val track = trackProvider() ?: return
            if (track.state != AudioTrack.STATE_INITIALIZED) {
                release()
                return
            }
            track.stop()
            track.reloadStaticData()
            track.play()
        } catch (_: Exception) {
            release()
        }
    }

    fun stop() {
        try {
            listOf(clockTrack, readyTrack).forEach { track ->
                track?.let {
                    if (it.state == AudioTrack.STATE_INITIALIZED &&
                        it.playState == AudioTrack.PLAYSTATE_PLAYING
                    ) {
                        it.stop()
                    }
                }
            }
        } catch (_: Exception) {
            release()
        }
    }

    fun release() {
        clockTrack?.run {
            stop()
            release()
        }
        clockTrack = null
        readyTrack?.run {
            stop()
            release()
        }
        readyTrack = null
    }

    private fun createAudioTrack(amplitude: Double): AudioTrack {
        val samples = tickSamples(amplitude)
        val builder = AudioTrack.Builder()
            .setAudioAttributes(GameAudioContext.gameSoundAttributes())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(samples.size * Short.SIZE_BYTES)
            .setTransferMode(AudioTrack.MODE_STATIC)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setContext(audioContext)
        }
        return builder.build().apply {
            write(samples, 0, samples.size)
        }
    }

    private fun tickSamples(amplitude: Double): ShortArray {
        val numSamples = SAMPLE_RATE * TICK_DURATION_MS / 1000
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val t = i.toDouble() / SAMPLE_RATE
            val attack = (t / ATTACK_SECONDS).coerceAtMost(1.0)
            val decay = exp(-(t - ATTACK_SECONDS).coerceAtLeast(0.0) * 280)
            val envelope = attack * decay
            val body = sin(2 * PI * 1650 * t) * 0.55 + sin(2 * PI * 820 * t) * 0.35
            val transient = sin(2 * PI * 3200 * t) * exp(-t * 520) * 0.45
            val value = ((body + transient) * envelope * amplitude * Short.MAX_VALUE).toInt()
            samples[i] = value.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
        return samples
    }

    private companion object {
        const val SAMPLE_RATE = 44_100
        const val TICK_DURATION_MS = 28
        const val ATTACK_SECONDS = 0.0015
        const val CLOCK_AMPLITUDE = 0.95
        const val READY_AMPLITUDE = 1.45
    }
}
