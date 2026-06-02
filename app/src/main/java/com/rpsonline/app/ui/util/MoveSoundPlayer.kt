package com.rpsonline.app.ui.util

import android.content.Context
import android.media.MediaPlayer
import com.rpsonline.app.R
import com.rpsonline.app.data.model.Move
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MoveSoundPlayer(context: Context) {
    private val audioContext = GameAudioContext.wrap(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun play(move: Move, repetitions: Int) {
        val resId = moveSoundResId(move)
        val count = repetitions.coerceIn(1, 3)
        scope.launch {
            repeat(count) { index ->
                playOnce(resId)
                if (index < count - 1) {
                    delay(ROUND_RESOLUTION_BURST_GAP_MS)
                }
            }
        }
    }

    suspend fun playOnce(move: Move) {
        playOnce(moveSoundResId(move))
    }

    private fun moveSoundResId(move: Move): Int = when (move) {
        Move.ROCK -> R.raw.move_rock
        Move.PAPER -> R.raw.move_paper
        Move.SCISSORS -> R.raw.move_scissors
    }

    fun release() {
        // MediaPlayer instances are created per play and released on completion.
    }

    private suspend fun playOnce(resId: Int) {
        suspendCancellableCoroutine { continuation ->
            val player = createPreparedPlayer(resId)
            if (player == null) {
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            continuation.invokeOnCancellation {
                runCatching {
                    if (player.isPlaying) player.stop()
                    player.release()
                }
            }
            player.setOnCompletionListener {
                player.release()
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
            player.setOnErrorListener { mp, _, _ ->
                mp.release()
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
                true
            }
            runCatching { player.start() }.onFailure {
                runCatching { player.release() }
                if (continuation.isActive) {
                    continuation.resume(Unit)
                }
            }
        }
    }

    /** Attributes and data source must be set before [MediaPlayer.prepare]. */
    private fun createPreparedPlayer(resId: Int): MediaPlayer? {
        val player = MediaPlayer()
        return try {
            player.setAudioAttributes(GameAudioContext.gameSoundAttributes())
            audioContext.resources.openRawResourceFd(resId).use { afd ->
                player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            }
            player.prepare()
            player
        } catch (_: Exception) {
            runCatching { player.release() }
            null
        }
    }
}
