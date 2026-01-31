package com.github.damontecres.wholphin.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import com.github.damontecres.wholphin.MainActivity
import com.github.damontecres.wholphin.services.PlayerFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber

class PlaybackCommandReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Timber.i("PlaybackCommandReceiver: onReceive called with action: $action")
        when (action) {
            ACTION_PLAY_BY_ID -> {
                val mediaId = intent.getStringExtra(EXTRA_MEDIA_ID)
                val positionMs = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                Timber.i("PLAY_BY_ID: $mediaId at $positionMs ms")
                if (mediaId.isNullOrBlank()) {
                    Timber.w("mediaId is null or blank, cannot play by ID")
                } else {
                    val playIntent = Intent(context, MainActivity::class.java).apply {
                        putExtra(MainActivity.INTENT_ITEM_ID, mediaId)
                        putExtra(MainActivity.INTENT_START_PLAYBACK, true)
                        putExtra(MainActivity.INTENT_POSITION_MS, positionMs)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    context.startActivity(playIntent)
                    Timber.i("Started playback activity for item $mediaId")
                }
            }
            ACTION_PAUSE -> {
                Timber.i("Executing PAUSE command")
                if (PlayerFactory.currentPlayer == null) {
                    Timber.w("currentPlayer is null, cannot pause")
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        PlayerFactory.currentPlayer?.pause()
                        Timber.i("Pause command sent to player")
                    }
                }
            }
            ACTION_PLAY -> {
                Timber.i("Executing PLAY command")
                if (PlayerFactory.currentPlayer == null) {
                    Timber.w("currentPlayer is null, cannot play")
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        PlayerFactory.currentPlayer?.play()
                        Timber.i("Play command sent to player")
                    }
                }
            }
            ACTION_SEEK -> {
                val pos = intent.getLongExtra(EXTRA_POSITION_MS, 0L)
                Timber.i("Executing SEEK command to $pos ms")
                if (PlayerFactory.currentPlayer == null) {
                    Timber.w("currentPlayer is null, cannot seek")
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        PlayerFactory.currentPlayer?.seekTo(pos)
                        Timber.i("Seek command sent to player")
                    }
                }
            }
            ACTION_STOP -> {
                Timber.i("Executing STOP command")
                if (PlayerFactory.currentPlayer == null) {
                    Timber.w("currentPlayer is null, cannot stop")
                } else {
                    CoroutineScope(Dispatchers.Main).launch {
                        PlayerFactory.currentPlayer?.pause()
                        Timber.i("Pause command sent to player (via STOP)")
                    }
                }
            }
        }
    }
    companion object {
        const val ACTION_PLAY_BY_ID = "com.wholphin.PLAY_BY_ID"
        const val ACTION_PAUSE = "com.wholphin.PAUSE"
        const val ACTION_PLAY = "com.wholphin.PLAY"
        const val ACTION_SEEK = "com.wholphin.SEEK"
        const val ACTION_STOP = "com.wholphin.STOP"
        const val EXTRA_MEDIA_ID = "mediaId"
        const val EXTRA_POSITION_MS = "positionMs"
    }
}
