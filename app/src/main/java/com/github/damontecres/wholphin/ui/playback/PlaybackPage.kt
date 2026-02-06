package com.github.damontecres.wholphin.ui.playback

import androidx.activity.compose.BackHandler
import androidx.annotation.Dimension
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.Player
import androidx.media3.ui.SubtitleView
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_SURFACE_VIEW
import androidx.media3.ui.compose.modifiers.resizeWithContentScale
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import androidx.media3.ui.compose.state.rememberPresentationState
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.surfaceColorAtElevation
import timber.log.Timber
import android.util.Log
import com.github.damontecres.wholphin.data.model.ItemPlayback
import com.github.damontecres.wholphin.data.model.Playlist
import com.github.damontecres.wholphin.preferences.PlayerBackend
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.preferences.skipBackOnResume
import com.github.damontecres.wholphin.services.SyncPlayManager
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.OneTimeLaunchedEffect
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.TextButton
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.applyToMpv
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.calculateEdgeSize
import com.github.damontecres.wholphin.ui.preferences.subtitle.SubtitleSettings.toSubtitleStyle
import com.github.damontecres.wholphin.ui.seasonEpisode
import com.github.damontecres.wholphin.ui.skipStringRes
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.util.Media3SubtitleOverride
import com.github.damontecres.wholphin.util.mpv.MpvPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.extensions.ticks
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The actual playback page which shows media & playback controls
 */
@OptIn(UnstableApi::class)
@Composable
fun PlaybackPage(
    preferences: UserPreferences,
    destination: Destination,
    modifier: Modifier = Modifier,
    viewModel: PlaybackViewModel = hiltViewModel(),
) {


    LifecycleStartEffect(destination) {
        onStopOrDispose {
            viewModel.release()
        }
    }
    LaunchedEffect(destination) {
        viewModel.init(destination, preferences)
    }

    val loading by viewModel.loading.observeAsState(LoadingState.Loading)
    when (val st = loading) {
        is LoadingState.Error -> {
            ErrorMessage(st, modifier)
        }

        LoadingState.Pending,
        LoadingState.Loading,
        -> {
            LoadingPage(modifier.background(Color.Black))
        }

        LoadingState.Success -> {
            val prefs = preferences.appPreferences.playbackPreferences
            val scope = rememberCoroutineScope()
            val configuration = LocalConfiguration.current
            val density = LocalDensity.current

            val player = viewModel.player
            val mediaInfo by viewModel.currentMediaInfo.observeAsState()
            val userDto by viewModel.currentUserDto.observeAsState()

            var isPlaying by remember { mutableStateOf(player.isPlaying) }
            var playWhenReady by remember { mutableStateOf(player.playWhenReady) }
            var playbackState by remember { mutableStateOf(player.playbackState) }
            androidx.compose.runtime.DisposableEffect(player) {
                val listener =
                    object : Player.Listener {
                        override fun onIsPlayingChanged(isPlayingNow: Boolean) {
                            isPlaying = isPlayingNow
                        }

                        override fun onPlayWhenReadyChanged(playWhenReadyNow: Boolean, reason: Int) {
                            playWhenReady = playWhenReadyNow
                        }

                        override fun onPlaybackStateChanged(playbackStateNow: Int) {
                            playbackState = playbackStateNow
                        }
                    }
                player.addListener(listener)
                onDispose { player.removeListener(listener) }
            }

            val currentPlayback by viewModel.currentPlayback.collectAsState()
            val currentItemPlayback by viewModel.currentItemPlayback.observeAsState(
                ItemPlayback(
                    userId = -1,
                    itemId = UUID.randomUUID(),
                ),
            )
            val currentSegment by viewModel.currentSegment.observeAsState(null)
            var segmentCancelled by remember(currentSegment?.id) { mutableStateOf(false) }

            val cues by viewModel.subtitleCues.observeAsState(listOf())
            var showDebugInfo by remember { mutableStateOf(prefs.showDebugInfo) }

            val nextUp by viewModel.nextUp.observeAsState(null)
            val playlist by viewModel.playlist.observeAsState(Playlist(listOf()))

            val subtitleSearch by viewModel.subtitleSearch.observeAsState(null)
            val subtitleSearchLanguage by viewModel.subtitleSearchLanguage.observeAsState(Locale.current.language)

            var playbackDialog by remember { mutableStateOf<PlaybackDialogType?>(null) }
            OneTimeLaunchedEffect {
                if (prefs.playerBackend == PlayerBackend.MPV) {
                    scope.launch(Dispatchers.IO + ExceptionHandler()) {
                        preferences.appPreferences.interfacePreferences.subtitlesPreferences.applyToMpv(
                            configuration,
                            density,
                        )
                    }
                }
            }
            AmbientPlayerListener(player)
            var contentScale by remember {
                mutableStateOf(
                    if (prefs.playerBackend == PlayerBackend.MPV) {
                        ContentScale.FillBounds
                    } else {
                        prefs.globalContentScale.scale
                    },
                )
            }
            var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
            LaunchedEffect(playbackSpeed) { player.setPlaybackSpeed(playbackSpeed) }

            val subtitleDelay = currentPlayback?.subtitleDelay ?: Duration.ZERO
            LaunchedEffect(subtitleDelay) {
                (player as? MpvPlayer)?.subtitleDelay = subtitleDelay
            }

            // SyncPlay position reporting - only when both SyncPlay is active AND player is playing
            val context = androidx.compose.ui.platform.LocalContext.current
            val syncPlayManager = (context as? com.github.damontecres.wholphin.MainActivity)?.syncPlayManager
            val isSyncPlayActive by syncPlayManager?.isSyncPlayActive?.collectAsState() ?: remember { mutableStateOf(false) }
            val isGroupPlaying by syncPlayManager?.isGroupPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
            var lastReadyItemId by remember { mutableStateOf<UUID?>(null) }
            var pendingPlayCommand by remember { mutableStateOf<com.github.damontecres.wholphin.services.SyncPlayCommand.Play?>(null) }
            
            // DEBUG: Log SyncPlay state changes
            LaunchedEffect(syncPlayManager) {
                Timber.i("🎬 DEBUG: syncPlayManager instance = ${syncPlayManager != null}")
                Log.i("PlaybackPage", "syncPlayManager instance = ${syncPlayManager != null}")
            }
            LaunchedEffect(isSyncPlayActive) {
                Timber.i("🎬 DEBUG: isSyncPlayActive changed to $isSyncPlayActive")
            }
            LaunchedEffect(player.isPlaying) {
                Timber.i("🎬 DEBUG: player.isPlaying changed to ${player.isPlaying}")
            }
            val isPlaybackActive = playWhenReady && playbackState != Player.STATE_IDLE
            LaunchedEffect(isPlaying) {
                Timber.i("🎬 DEBUG: isPlaying state changed to $isPlaying")
            }
            LaunchedEffect(playWhenReady, playbackState) {
                Timber.i("🎬 DEBUG: playWhenReady=$playWhenReady, playbackState=$playbackState, isPlaybackActive=$isPlaybackActive")
            }
            LaunchedEffect(currentPlayback) {
                Timber.i("🎬 DEBUG: currentPlayback changed, itemId = ${currentPlayback?.item?.id}")
            }

            // Ensure SyncPlay knows what item is playing locally so Ready reports can include playlistItemId
            LaunchedEffect(isSyncPlayActive, currentPlayback?.item?.id) {
                if (isSyncPlayActive) {
                    currentPlayback?.item?.id?.let { syncPlayManager?.setLocalPlaybackItemId(it) }
                }
            }
            
            val currentGroupId by syncPlayManager?.currentGroupId?.collectAsState() ?: remember { mutableStateOf(null) }
            
            // When media becomes ready and SyncPlay is active, notify server we're ready
            // This tells server this device has buffered and is ready for synchronized playback
            LaunchedEffect(isSyncPlayActive, playbackState, currentPlayback) {
                val currentItemId = currentPlayback?.item?.id
                if (
                    isSyncPlayActive &&
                    syncPlayManager != null &&
                    currentItemId != null &&
                    playbackState == Player.STATE_READY &&
                    currentItemId != lastReadyItemId
                ) {
                    val pendingPlay = pendingPlayCommand
                    if (pendingPlay != null && pendingPlay.itemIds.contains(currentItemId)) {
                        val targetIndex = pendingPlay.startIndex
                        val targetItemId = pendingPlay.itemIds.getOrNull(targetIndex)
                        if (targetItemId == currentItemId) {
                            Timber.i("🎬✅ Media ready with pending SyncPlay Play - starting playback now")
                            if (player.currentPosition != pendingPlay.startPositionMs) {
                                player.seekTo(pendingPlay.startPositionMs)
                            }
                            player.play()
                            pendingPlayCommand = null
                            lastReadyItemId = currentItemId
                            return@LaunchedEffect
                        }
                    }

                    // CRITICAL: Pause the player to wait for server sync command
                    // Don't auto-play until server confirms all clients are ready
                    if (player.isPlaying) {
                        Timber.i("🎬⏸️ Media ready but in SyncPlay - PAUSING until server confirms all clients ready")
                        player.pause()
                    }

                    Timber.i("🎬 Media ready! Reporting buffering complete for synchronized playback")
                    syncPlayManager.reportBufferingComplete(currentItemId)
                    lastReadyItemId = currentItemId
                    
                    // CRITICAL: Start position reporting immediately after Ready
                    // Server needs continuous Ready reports to calculate group position and send Unpause
                    // Without this, TV reports Ready once and server never sends Unpause command
                    Timber.i("🎬📡 Starting position reporting - server needs continuous Ready to sync group")
                    syncPlayManager.startPositionReporting {
                        withContext(Dispatchers.Main.immediate) {
                            player.currentPosition
                        }
                    }
                }
            }

            LaunchedEffect(isSyncPlayActive) {
                if (!isSyncPlayActive) {
                    lastReadyItemId = null
                }
            }

            // Stop SyncPlay operations when playback ends
            LifecycleStartEffect(Unit) {
                onStopOrDispose {
                    syncPlayManager?.stopPositionReporting()
                    syncPlayManager?.stopDriftChecking()
                }
            }

            // Send pause/unpause commands to SyncPlay when user pauses/plays locally
            // Track previous state to detect user-initiated changes
            val isExecutingRemoteCommand by syncPlayManager?.isExecutingRemoteCommand?.collectAsState() ?: remember { mutableStateOf(false) }
            var previousIsPlaying by remember { mutableStateOf(isPlaying) }
            
            LaunchedEffect(isSyncPlayActive, isPlaying, currentGroupId, isExecutingRemoteCommand) {
                Timber.d("🔍 Local pause/play detection: isSyncPlayActive=$isSyncPlayActive, isPlaying=$isPlaying, previousIsPlaying=$previousIsPlaying, isExecutingRemoteCommand=$isExecutingRemoteCommand, groupId=$currentGroupId")
                
                if (isSyncPlayActive && currentGroupId != null && syncPlayManager != null && !isExecutingRemoteCommand) {
                    // Only send commands if this is a user-initiated change, not a remote command response
                    if (isPlaying != previousIsPlaying) {
                        val position = withContext(Dispatchers.Main.immediate) { player.currentPosition }
                        
                        if (!isPlaying && previousIsPlaying) {
                            // User paused locally - send to server
                            Timber.i("🎬🔴 LOCAL PAUSE DETECTED at ${position}ms - sending Pause to SyncPlay group $currentGroupId")
                            syncPlayManager.pause()
                        } else if (isPlaying && !previousIsPlaying) {
                            // User resumed locally - send to server
                            Timber.i("🎬🟢 LOCAL UNPAUSE DETECTED at ${position}ms - sending Unpause to SyncPlay group $currentGroupId")
                            syncPlayManager.unpause()
                        }
                        previousIsPlaying = isPlaying
                    } else {
                        Timber.d("🔍 No state change detected (isPlaying unchanged)")
                    }
                } else {
                    if (isPlaying != previousIsPlaying) {
                        Timber.d("🔍 State changed but not sending to server: isSyncPlayActive=$isSyncPlayActive, groupId=$currentGroupId, isExecutingRemoteCommand=$isExecutingRemoteCommand")
                        previousIsPlaying = isPlaying
                    }
                }
            }

            // Handle SyncPlay playback commands from server
            val syncPlayCommand by syncPlayManager?.playbackCommands?.collectAsState() ?: remember { mutableStateOf(null) }
            LaunchedEffect(syncPlayCommand) {
                if (syncPlayManager == null) return@LaunchedEffect
                
                syncPlayCommand?.let { command ->
                    // Check if command is too old (more than 5 seconds)
                    val commandAge = System.currentTimeMillis() - when (command) {
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Pause -> command.timestamp
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Unpause -> command.timestamp
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Seek -> command.timestamp
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.SetPlaybackRate -> command.timestamp
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Play -> command.timestamp
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Buffering -> command.timestamp
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Stop -> command.timestamp
                    }
                    
                    if (commandAge > 5000) {
                        Timber.w("🎬⚠️ Ignoring stale SyncPlay command (${commandAge}ms old): $command")
                        return@LaunchedEffect
                    }
                    
                    Timber.i("🎬📥 PlaybackPage received SyncPlay command: $command (age: ${commandAge}ms)")
                    
                    // Set flag to prevent local change detection from triggering
                    Timber.d("🔒 Setting isExecutingRemoteCommand = true")
                    syncPlayManager._isExecutingRemoteCommand.value = true
                    syncPlayManager.startExecutionTimeout() // Start watchdog timer
                    
                    try {
                        when (command) {
                            is com.github.damontecres.wholphin.services.SyncPlayCommand.Pause -> {
                                Timber.i("🎬🔴 PlaybackPage executing Pause command: target position=${command.positionMs}ms")
                                withContext(Dispatchers.Main.immediate) {
                                    val currentPos = player.currentPosition
                                    val drift = Math.abs(currentPos - command.positionMs)
                                    val driftTolerance = syncPlayManager.getDriftTolerance()
                                    Timber.d("🔍 Current position: ${currentPos}ms, drift: ${drift}ms, tolerance: ${driftTolerance}ms")
                                    if (drift > driftTolerance) {
                                        Timber.i("⚠️ Position drift detected: ${currentPos}ms vs ${command.positionMs}ms (drift: ${drift}ms), seeking...")
                                        player.seekTo(command.positionMs)
                                        Timber.d("✅ Seek complete, new position: ${player.currentPosition}ms")
                                    } else {
                                        Timber.d("✅ Position within tolerance (drift: ${drift}ms < ${driftTolerance}ms)")
                                    }
                                    Timber.d("⏸️ Calling player.pause()...")
                                    player.pause()
                                    Timber.d("✅ player.pause() complete")
                                }
                                syncPlayManager.markCommandExecuted(command)
                            }
                            is com.github.damontecres.wholphin.services.SyncPlayCommand.Unpause -> {
                                Timber.i("🎬🟢 PlaybackPage executing Unpause command: target position=${command.positionMs}ms")
                                withContext(Dispatchers.Main.immediate) {
                                    val currentPos = player.currentPosition
                                    val drift = Math.abs(currentPos - command.positionMs)
                                    val driftTolerance = syncPlayManager.getDriftTolerance()
                                    Timber.d("🔍 Unpause: currentPos=${currentPos}ms, targetPos=${command.positionMs}ms, drift=${drift}ms, tolerance=${driftTolerance}ms, isPlaying=${player.isPlaying}")
                                    
                                    // If already playing and drift is HUGE (> 1000ms), this is likely server spam
                                    // with position=0. Don't seek - it would cause a restart. Just ensure playing.
                                    if (player.isPlaying && drift > 1000) {
                                        Timber.w("⚠️ Already playing with huge drift (${drift}ms) - ignoring Unpause position ${command.positionMs}ms to prevent restart loop (staying at ${currentPos}ms)")
                                        // Don't seek, don't call play() - already playing
                                    } else if (drift > driftTolerance) {
                                        // Normal drift correction when NOT already playing, or drift is reasonable
                                        Timber.i("⚠️⏱️ Position drift detected: ${currentPos}ms vs ${command.positionMs}ms (drift: ${drift}ms), seeking...")
                                        player.seekTo(command.positionMs)
                                        Timber.d("✅ Seek complete, new position: ${player.currentPosition}ms")
                                        Timber.d("▶️ Calling player.play()...")
                                        player.play()
                                        Timber.d("✅ player.play() complete")
                                    } else {
                                        // Within tolerance - just ensure playing
                                        Timber.d("✅ Position within tolerance (drift: ${drift}ms < ${driftTolerance}ms)")
                                        Timber.d("▶️ Calling player.play()...")
                                        player.play()
                                        Timber.d("✅ player.play() complete")
                                    }
                                }
                                syncPlayManager.markCommandExecuted(command)
                            }
                            is com.github.damontecres.wholphin.services.SyncPlayCommand.Seek -> {
                                Timber.i("🎬⏩ PlaybackPage executing Seek command: target position=${command.positionMs}ms")
                                withContext(Dispatchers.Main.immediate) {
                                    val currentPos = player.currentPosition
                                    val drift = Math.abs(currentPos - command.positionMs)
                                    val driftTolerance = syncPlayManager.getDriftTolerance()
                                    Timber.d("🔍 Current position: ${currentPos}ms, drift: ${drift}ms, tolerance: ${driftTolerance}ms")
                                    
                                    if (drift > driftTolerance) {
                                        Timber.i("⚠️ Position drift exceeds tolerance - seeking from ${currentPos}ms to ${command.positionMs}ms")
                                        player.seekTo(command.positionMs)
                                        Timber.d("✅ Seek complete, new position: ${player.currentPosition}ms")
                                    } else {
                                        Timber.d("✅ Position within tolerance - skipping seek (drift: ${drift}ms < ${driftTolerance}ms)")
                                    }
                                }
                                syncPlayManager.markCommandExecuted(command)
                            }
                            is com.github.damontecres.wholphin.services.SyncPlayCommand.SetPlaybackRate -> {
                                Timber.i("🎬 PlaybackPage executing SetPlaybackRate command: rate=${command.rate}")
                                withContext(Dispatchers.Main.immediate) {
                                    player.setPlaybackSpeed(command.rate)
                                }
                                syncPlayManager.markCommandExecuted(command)
                            }
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Play -> {
                            // Start new playback - navigate to the first item
                            val firstItemId = command.itemIds.getOrNull(command.startIndex) ?: command.itemIds.firstOrNull()
                            timber.log.Timber.i("🎬 Page-level SyncPlay Play received: items=%d startIndex=%d position=%d", command.itemIds.size, command.startIndex, command.startPositionMs)
                            if (firstItemId != null) {
                                // Check if we're already playing this item (just buffered and waiting)
                                val currentItemId = currentPlayback?.item?.id
                                if (currentItemId == firstItemId && playbackState == Player.STATE_READY) {
                                    // We're already buffered and ready - just press play!
                                    timber.log.Timber.i("🎬✅ Already buffered item %s - pressing play NOW", firstItemId)
                                    withContext(Dispatchers.Main.immediate) {
                                        val currentPos = player.currentPosition
                                        val drift = Math.abs(currentPos - command.startPositionMs)
                                        val driftTolerance = syncPlayManager.getDriftTolerance()
                                        Timber.d("🔍 Play command position check: current=${currentPos}ms, target=${command.startPositionMs}ms, drift=${drift}ms, tolerance=${driftTolerance}ms")
                                        
                                        if (drift > driftTolerance) {
                                            Timber.i("⚠️ Position drift detected on Play - seeking to ${command.startPositionMs}ms")
                                            player.seekTo(command.startPositionMs)
                                        } else {
                                            Timber.d("✅ Position within tolerance for Play command")
                                        }
                                        player.play()
                                    }
                                    syncPlayManager.markCommandExecuted(command)
                                } else {
                                    // Save pending play so we can start once media is ready
                                    pendingPlayCommand = command
                                    // Navigate to new item
                                    timber.log.Timber.i("🎬 Page-level navigate to %s at %dms", firstItemId, command.startPositionMs)
                                    // Enter buffering state first - load media but don't play yet
                                    // Once media is loaded, we'll send BufferingComplete to server
                                    // Server waits for all clients to be ready before syncing playback
                                    viewModel.navigationManager.navigateTo(
                                        com.github.damontecres.wholphin.ui.nav.Destination.Playback(
                                            itemId = firstItemId,
                                            positionMs = command.startPositionMs
                                        ),
                                    )
                                }
                            }
                        }
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Buffering -> {
                            // Intermediate state: device is loading media, waiting for all clients to buffer
                            Timber.i("🎬 🔄 Page-level SyncPlay Buffering received: itemId=%s", command.itemId)
                            // Navigate to playback but keep player paused
                            viewModel.navigationManager.navigateTo(
                                com.github.damontecres.wholphin.ui.nav.Destination.Playback(
                                    itemId = command.itemId,
                                    positionMs = 0
                                ),
                            )
                        }
                        is com.github.damontecres.wholphin.services.SyncPlayCommand.Stop -> {
                            // Stop command from server - exit playback
                            Timber.i("🎬⏹️ Handling remote Stop command - stopping playback and exiting")
                            syncPlayManager.stopPositionReporting()
                            syncPlayManager.stopDriftChecking()
                            withContext(Dispatchers.Main.immediate) {
                                player.stop()
                            }
                            // Exit playback screen
                            viewModel.navigationManager.goBack()
                            Timber.i("🎬⏹️ Stop command handling complete")
                        }
                    }
                    } finally {
                        // Re-enable local change detection after a longer delay
                        // to ensure player state has fully stabilized, especially after seeks
                        syncPlayManager.cancelExecutionTimeout() // Cancel watchdog
                        Timber.d("⏳ Waiting 2000ms for player state to stabilize after remote command...")
                        delay(2000) // Increased from 500ms - seeks can take 1-2 seconds to complete
                        Timber.d("🔓 Setting isExecutingRemoteCommand = false")
                        syncPlayManager._isExecutingRemoteCommand.value = false
                        Timber.d("✅ Remote command execution complete")
                    }
                } ?: Timber.d("🔍 No command to execute (null)")
            }

            val presentationState = rememberPresentationState(player, false)
            val scaledModifier =
                Modifier.resizeWithContentScale(contentScale, presentationState.videoSizeDp)
            val focusRequester = remember { FocusRequester() }
            val playPauseState = rememberPlayPauseButtonState(player)
            val seekBarState = rememberSeekBarState(player, scope)

            LaunchedEffect(Unit) {
                focusRequester.tryRequestFocus()
            }
            val controllerViewState = remember { viewModel.controllerViewState }

            var skipIndicatorDuration by remember { mutableLongStateOf(0L) }
            LaunchedEffect(controllerViewState.controlsVisible) {
                // If controller shows/hides, immediately cancel the skip indicator
                skipIndicatorDuration = 0L
            }
            var skipPosition by remember { mutableLongStateOf(0L) }
            val updateSkipIndicator = { delta: Long ->
                if ((skipIndicatorDuration > 0 && delta < 0) || (skipIndicatorDuration < 0 && delta > 0)) {
                    skipIndicatorDuration = 0
                }
                skipIndicatorDuration += delta
                skipPosition = player.currentPosition
            }
            val keyHandler =
                PlaybackKeyHandler(
                    player = player,
                    controlsEnabled = nextUp == null,
                    skipWithLeftRight = true,
                    seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                    seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                    controllerViewState = controllerViewState,
                    updateSkipIndicator = updateSkipIndicator,
                    skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
                    onInteraction = viewModel::reportInteraction,
                    oneClickPause = preferences.appPreferences.playbackPreferences.oneClickPause,
                    onStop = {
                        player.stop()
                        viewModel.navigationManager.goBack()
                    },
                    onPlaybackDialogTypeClick = { playbackDialog = it },
                )

            val onPlaybackActionClick: (PlaybackAction) -> Unit = {
                when (it) {
                    is PlaybackAction.PlaybackSpeed -> {
                        playbackSpeed = it.value
                    }

                    is PlaybackAction.Scale -> {
                        contentScale = it.scale
                    }

                    PlaybackAction.ShowDebug -> {
                        showDebugInfo = !showDebugInfo
                    }

                    PlaybackAction.ShowPlaylist -> {
                        TODO()
                    }

                    PlaybackAction.ShowVideoFilterDialog -> {
                        TODO()
                    }

                    is PlaybackAction.ToggleAudio -> {
                        viewModel.changeAudioStream(it.index)
                    }

                    is PlaybackAction.ToggleCaptions -> {
                        viewModel.changeSubtitleStream(it.index)
                    }

                    PlaybackAction.SearchCaptions -> {
                        controllerViewState.hideControls()
                        viewModel.searchForSubtitles()
                    }

                    PlaybackAction.Next -> {
                        // TODO focus is lost
                        viewModel.playNextUp()
                    }

                    PlaybackAction.Previous -> {
                        val pos = player.currentPosition
                        if (pos < player.maxSeekToPreviousPosition && playlist.hasPrevious()) {
                            viewModel.playPrevious()
                        } else {
                            player.seekToPrevious()
                        }
                    }
                }
            }

            val showSegment =
                !segmentCancelled && currentSegment != null &&
                    nextUp == null && !controllerViewState.controlsVisible && skipIndicatorDuration == 0L
            BackHandler(showSegment) {
                segmentCancelled = true
            }

            Box(
                modifier
                    .background(if (nextUp == null) Color.Black else MaterialTheme.colorScheme.background),
            ) {
                val playerSize by animateFloatAsState(if (nextUp == null) 1f else .6f)
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize(playerSize)
                            .align(Alignment.TopCenter)
                            .onKeyEvent(keyHandler::onKeyEvent)
                            .focusRequester(focusRequester)
                            .focusable(),
                ) {
                    PlayerSurface(
                        player = player,
                        surfaceType = SURFACE_TYPE_SURFACE_VIEW,
                        modifier = scaledModifier,
                    )
                    if (presentationState.coverSurface) {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(Color.Black),
                        ) {
                            LoadingPage(focusEnabled = false)
                        }
                    }

                    // If D-pad skipping, show the amount skipped in an animation
                    if (!controllerViewState.controlsVisible && skipIndicatorDuration != 0L) {
                        SkipIndicator(
                            durationMs = skipIndicatorDuration,
                            onFinish = {
                                skipIndicatorDuration = 0L
                            },
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 70.dp),
                        )
                        // Show a small progress bar along the bottom of the screen
                        val showSkipProgress = true // TODO get from preferences
                        if (showSkipProgress) {
                            val percent = skipPosition.toFloat() / player.duration.toFloat()
                            Box(
                                modifier =
                                    Modifier
                                        .align(Alignment.BottomStart)
                                        .background(MaterialTheme.colorScheme.border)
                                        .clip(RectangleShape)
                                        .height(3.dp)
                                        .fillMaxWidth(percent),
                            )
                        }
                    }

                    // The playback controls
                    AnimatedVisibility(
                        controllerViewState.controlsVisible,
                        Modifier,
                        slideInVertically { it },
                        slideOutVertically { it },
                    ) {
                        PlaybackOverlay(
                            modifier =
                                Modifier
                                    .padding(WindowInsets.systemBars.asPaddingValues())
                                    .fillMaxSize()
                                    .background(Color.Transparent),
                            item = currentPlayback?.item,
                            playerControls = player,
                            controllerViewState = controllerViewState,
                            showPlay = playPauseState.showPlay,
                            previousEnabled = true,
                            nextEnabled = playlist.hasNext(),
                            seekEnabled = true,
                            seekForward = preferences.appPreferences.playbackPreferences.skipForwardMs.milliseconds,
                            seekBack = preferences.appPreferences.playbackPreferences.skipBackMs.milliseconds,
                            skipBackOnResume = preferences.appPreferences.playbackPreferences.skipBackOnResume,
                            onPlaybackActionClick = onPlaybackActionClick,
                            onClickPlaybackDialogType = { playbackDialog = it },
                            onSeekBarChange = seekBarState::onValueChange,
                            onExit = {
                                player.stop()
                                viewModel.navigationManager.goBack()
                            },
                            showDebugInfo = showDebugInfo,
                            currentPlayback = currentPlayback,
                            chapters = mediaInfo?.chapters ?: listOf(),
                            trickplayInfo = mediaInfo?.trickPlayInfo,
                            trickplayUrlFor = viewModel::getTrickplayUrl,
                            playlist = playlist,
                            onClickPlaylist = {
                                viewModel.playItemInPlaylist(it)
                            },
                            currentSegment = currentSegment,
                            showClock = preferences.appPreferences.interfacePreferences.showClock,
                            syncPlayManager = syncPlayManager,
                        )
                    }

                    // Subtitles
                    if (skipIndicatorDuration == 0L && currentItemPlayback.subtitleIndexEnabled) {
                        val maxSize by animateFloatAsState(if (controllerViewState.controlsVisible) .7f else 1f)
                        AndroidView(
                            factory = { context ->
                                SubtitleView(context).apply {
                                    preferences.appPreferences.interfacePreferences.subtitlesPreferences.let {
                                        setStyle(it.toSubtitleStyle())
                                        setFixedTextSize(Dimension.SP, it.fontSize.toFloat())
                                        setBottomPaddingFraction(it.margin.toFloat() / 100f)
                                    }
                                }
                            },
                            update = {
                                it.setCues(cues)
                                Media3SubtitleOverride(
                                    preferences.appPreferences.interfacePreferences.subtitlesPreferences
                                        .calculateEdgeSize(density),
                                ).apply(it)
                            },
                            onReset = {
                                it.setCues(null)
                            },
                            modifier =
                                Modifier
                                    .fillMaxSize(maxSize)
                                    .align(Alignment.TopCenter)
                                    .background(Color.Transparent),
                        )
                    }
                }

                // Ask to skip intros, etc button
                AnimatedVisibility(
                    showSegment,
                    modifier =
                        Modifier
                            .padding(40.dp)
                            .align(Alignment.BottomEnd),
                ) {
                    currentSegment?.let { segment ->
                        val focusRequester = remember { FocusRequester() }
                        LaunchedEffect(Unit) {
                            focusRequester.tryRequestFocus()
                            delay(10.seconds)
                            segmentCancelled = true
                        }
                        TextButton(
                            stringRes = segment.type.skipStringRes,
                            onClick = {
                                segmentCancelled = true
                                player.seekTo(segment.endTicks.ticks.inWholeMilliseconds)
                            },
                            modifier = Modifier.focusRequester(focusRequester),
                        )
                    }
                }

                // Next up episode
                BackHandler(nextUp != null) {
                    if (player.isPlaying) {
                        scope.launch(ExceptionHandler()) {
                            viewModel.cancelUpNextEpisode()
                        }
                    } else {
                        viewModel.navigationManager.goBack()
                    }
                }
                AnimatedVisibility(
                    nextUp != null,
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter),
                ) {
                    nextUp?.let {
                        var autoPlayEnabled by remember { mutableStateOf(viewModel.shouldAutoPlayNextUp()) }
                        var timeLeft by remember {
                            mutableLongStateOf(
                                preferences.appPreferences.playbackPreferences.autoPlayNextDelaySeconds,
                            )
                        }
                        BackHandler(timeLeft > 0 && autoPlayEnabled) {
                            timeLeft = -1
                            autoPlayEnabled = false
                        }
                        if (autoPlayEnabled) {
                            LaunchedEffect(Unit) {
                                if (timeLeft == 0L) {
                                    viewModel.playNextUp()
                                } else {
                                    while (timeLeft > 0) {
                                        delay(1.seconds)
                                        timeLeft--
                                    }
                                    if (timeLeft == 0L && autoPlayEnabled) {
                                        viewModel.playNextUp()
                                    }
                                }
                            }
                        }
                        NextUpEpisode(
                            title =
                                listOfNotNull(
                                    it.data.seasonEpisode,
                                    it.name,
                                ).joinToString(" - "),
                            description = it.data.overview,
                            imageUrl = LocalImageUrlService.current.rememberImageUrl(it),
                            aspectRatio = it.aspectRatio ?: AspectRatios.WIDE,
                            onClick = {
                                viewModel.reportInteraction()
                                controllerViewState.hideControls()
                                viewModel.playNextUp()
                            },
                            timeLeft = if (autoPlayEnabled) timeLeft.seconds else null,
                            modifier =
                                Modifier
                                    .padding(8.dp)
//                                    .height(128.dp)
                                    .fillMaxHeight(1 - playerSize)
                                    .fillMaxWidth(.66f)
                                    .align(Alignment.BottomCenter)
                                    .background(
                                        MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp),
                                        shape = RoundedCornerShape(8.dp),
                                    ),
                        )
                    }
                }
            }

            subtitleSearch?.let { state ->
                val wasPlaying = remember { player.isPlaying }
                LaunchedEffect(Unit) {
                    player.pause()
                }
                val onDismissRequest = {
                    if (wasPlaying) {
                        player.play()
                    }
                    viewModel.cancelSubtitleSearch()
                }
                Dialog(
                    onDismissRequest = onDismissRequest,
                    properties =
                        DialogProperties(
                            usePlatformDefaultWidth = false,
                        ),
                ) {
                    DownloadSubtitlesContent(
                        state = state,
                        language = subtitleSearchLanguage,
                        onSearch = { lang ->
                            viewModel.searchForSubtitles(lang)
                        },
                        onClickDownload = {
                            viewModel.downloadAndSwitchSubtitles(it.id, wasPlaying)
                        },
                        onDismissRequest = onDismissRequest,
                        modifier =
                            Modifier
                                .widthIn(max = 640.dp)
                                .heightIn(max = 400.dp),
                    )
                }
            }

            playbackDialog?.let { type ->
                PlaybackDialog(
                    type = type,
                    settings =
                        PlaybackSettings(
                            showDebugInfo = showDebugInfo,
                            audioIndex = currentItemPlayback?.audioIndex,
                            audioStreams = mediaInfo?.audioStreams.orEmpty(),
                            subtitleIndex = currentItemPlayback?.subtitleIndex,
                            subtitleStreams = mediaInfo?.subtitleStreams.orEmpty(),
                            playbackSpeed = playbackSpeed,
                            contentScale = contentScale,
                            subtitleDelay = subtitleDelay,
                            hasSubtitleDownloadPermission =
                                remember(userDto) { userDto?.policy?.let { it.isAdministrator || it.enableSubtitleManagement } == true },
                        ),
                    onDismissRequest = {
                        playbackDialog = null
                        if (controllerViewState.controlsVisible) {
                            controllerViewState.pulseControls()
                        }
                    },
                    onControllerInteraction = {
                        controllerViewState.pulseControls(Long.MAX_VALUE)
                    },
                    onClickPlaybackDialogType = {
                        if (it == PlaybackDialogType.SUBTITLE_DELAY) {
                            // Hide controls so subtitles are fully visible
                            controllerViewState.hideControls()
                        }
                        playbackDialog = it
                    },
                    onPlaybackActionClick = onPlaybackActionClick,
                    onChangeSubtitleDelay = { viewModel.updateSubtitleDelay(it) },
                    enableSubtitleDelay = player is MpvPlayer,
                    enableVideoScale = player !is MpvPlayer,
                )
            }
        }
    }
}
