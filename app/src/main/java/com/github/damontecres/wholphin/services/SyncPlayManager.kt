package com.github.damontecres.wholphin.services

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.github.damontecres.wholphin.data.ServerRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.ReadyRequestDto
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateMessage
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manages SyncPlay functionality for synchronized playback across multiple clients.
 * Handles joining/leaving groups, coordinating playback commands, and time synchronization.
 */
@ActivityScoped
class SyncPlayManager
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val api: ApiClient,
        private val serverRepository: ServerRepository,
    ) : DefaultLifecycleObserver {
        private val _syncPlayState = MutableStateFlow<SyncPlayState>(SyncPlayState.Idle)
        val syncPlayState: StateFlow<SyncPlayState> = _syncPlayState.asStateFlow()

        private var listenJob: Job? = null
        private var lastServerPosition: Long = 0L
        private var lastServerTimestamp: Long = 0L

        /**
         * Join an existing SyncPlay group
         */
        suspend fun joinGroup(groupId: UUID) {
            try {
                Timber.i("Joining SyncPlay group: $groupId")
                val response = api.syncPlayApi.syncPlayJoinGroup(
                    data = JoinGroupRequestDto(groupId = groupId)
                )
                
                if (response.status.isSuccess()) {
                    _syncPlayState.value = SyncPlayState.InGroup(
                        groupId = groupId,
                        members = emptyList(),
                        isPlaying = false,
                        positionMs = 0L
                    )
                    setupListeners()
                } else {
                    throw Exception("Join request failed with status ${response.status}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to join SyncPlay group")
                _syncPlayState.value = SyncPlayState.Error("Failed to join group: ${e.message}")
            }
        }

        /**
         * Create a new SyncPlay group and become the host
         */
        suspend fun createGroup(groupName: String = "SyncPlay Group") {
            try {
                Timber.i("Creating new SyncPlay group: $groupName")
                val response = api.syncPlayApi.syncPlayCreateGroup(
                    data = NewGroupRequestDto(groupName = groupName)
                )
                
                if (response.status.isSuccess()) {
                    response.content.groupId.let { groupId ->
                        _syncPlayState.value = SyncPlayState.InGroup(
                            groupId = groupId,
                            members = emptyList(),
                            isPlaying = false,
                            positionMs = 0L
                        )
                        setupListeners()
                    }
                } else {
                    throw Exception("Create group failed with status ${response.status}")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to create SyncPlay group")
                _syncPlayState.value = SyncPlayState.Error("Failed to create group: ${e.message}")
            }
        }

        /**
         * Leave the current SyncPlay group
         */
        suspend fun leaveGroup() {
            try {
                val currentState = _syncPlayState.value
                if (currentState is SyncPlayState.InGroup) {
                    Timber.i("Leaving SyncPlay group: ${currentState.groupId}")
                    api.syncPlayApi.syncPlayLeaveGroup()
                    _syncPlayState.value = SyncPlayState.Idle
                    listenJob?.cancel()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to leave SyncPlay group")
            }
        }

        /**
         * Send a playback command to sync with the group
         */
        suspend fun sendPlaybackCommand(
            command: SyncPlayCommand,
            positionMs: Long,
        ) {
            try {
                val currentState = _syncPlayState.value
                if (currentState is SyncPlayState.InGroup) {
                    Timber.d("Sending SyncPlay command: $command at $positionMs ms")
                    when (command) {
                        SyncPlayCommand.PLAY -> api.syncPlayApi.syncPlayUnpause()
                        SyncPlayCommand.PAUSE -> api.syncPlayApi.syncPlayPause()
                        SyncPlayCommand.STOP -> api.syncPlayApi.syncPlayStop()
                        else -> Timber.w("Command $command not yet implemented")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to send SyncPlay command")
            }
        }

        /**
         * Report ready to play (after buffering complete)
         */
        suspend fun reportReady(when: Long = System.currentTimeMillis(), isPlaying: Boolean = false, positionTicks: Long = 0L) {
            try {
                Timber.d("Reporting ready to SyncPlay group")
                api.syncPlayApi.syncPlayReady(
                    data = ReadyRequestDto(
                        `when` = when.toString(),
                        positionTicks = positionTicks,
                        isPlaying = isPlaying
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "Failed to report ready")
            }
        }

        /**
         * Calculate position with latency compensation
         */
        fun calculateSyncPosition(): Long {
            val currentState = _syncPlayState.value
            if (currentState !is SyncPlayState.InGroup) return 0L

            val elapsedSinceUpdate = System.currentTimeMillis() - lastServerTimestamp
            return if (currentState.isPlaying) {
                lastServerPosition + elapsedSinceUpdate
            } else {
                lastServerPosition
            }
        }

        /**
         * Estimate network latency (simplified)
         */
        private fun estimateLatency(): Long {
            // TODO: Implement proper round-trip time measurement
            return 50L // Assume 50ms latency for now
        }

        private fun setupListeners() {
            listenJob?.cancel()
            Timber.v("Setting up SyncPlay WebSocket listeners")

            // Listen for SyncPlay group updates using actual SDK types
            listenJob =
                try {
                    api.webSocket
                        .subscribe<SyncPlayGroupUpdateMessage>()
                        .onEach { message ->
                            Timber.d("Received SyncPlayGroupUpdateMessage: ${message.data}")
                            handleSyncPlayGroupUpdate(message.data)
                        }.launchIn(kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO))
                } catch (e: Exception) {
                    Timber.e(e, "Failed to subscribe to SyncPlay updates")
                    null
                }
        }

        /**
         * Handle incoming SyncPlay updates from server using actual SDK GroupUpdate types
         */
        private fun handleSyncPlayGroupUpdate(groupUpdate: org.jellyfin.sdk.model.api.GroupUpdate) {
            Timber.d("Received GroupUpdate: type=${groupUpdate.type}, groupId=${groupUpdate.groupId}")
            
            lastServerTimestamp = System.currentTimeMillis()
            
            when (groupUpdate) {
                is org.jellyfin.sdk.model.api.SyncPlayGroupJoinedUpdate -> {
                    val groupInfo = groupUpdate.data
                    _syncPlayState.value = SyncPlayState.InGroup(
                        groupId = groupUpdate.groupId,
                        members = groupInfo.participants.map {
                            SyncPlayParticipant(
                                userId = it.sessionId,
                                username = it.userName.orEmpty(),
                                isReady = it.isReady,
                                isBuffering = it.isBuffering
                            )
                        },
                        isPlaying = groupInfo.state == org.jellyfin.sdk.model.api.GroupStateType.PLAYING,
                        positionMs = groupInfo.positionTicks?.div(10000) ?: 0L
                    )
                }
                
                is org.jellyfin.sdk.model.api.SyncPlayUserJoinedUpdate,
                is org.jellyfin.sdk.model.api.SyncPlayUserLeftUpdate -> {
                    // Refresh group state - would need to fetch current participants
                    Timber.d("User joined/left group")
                }
                
                is org.jellyfin.sdk.model.api.SyncPlayStateUpdate -> {
                    val stateData = groupUpdate.data
                    val currentState = _syncPlayState.value
                    if (currentState is SyncPlayState.InGroup) {
                        _syncPlayState.value = currentState.copy(
                            isPlaying = stateData.state == org.jellyfin.sdk.model.api.GroupStateType.PLAYING
                        )
                    }
                }
                
                is org.jellyfin.sdk.model.api.SyncPlayGroupLeftUpdate -> {
                    _syncPlayState.value = SyncPlayState.Idle
                    listenJob?.cancel()
                }
                
                else -> {
                    Timber.w("Unhandled GroupUpdate type: ${groupUpdate.type}")
                }
            }
        }

        override fun onResume(owner: LifecycleOwner) {
            val currentState = _syncPlayState.value
            if (currentState is SyncPlayState.InGroup) {
                setupListeners()
            }
        }

        override fun onPause(owner: LifecycleOwner) {
            listenJob?.cancel()
        }

        override fun onDestroy(owner: LifecycleOwner) {
            listenJob?.cancel()
        }
    }

/**
 * Represents the current state of SyncPlay
 */
sealed class SyncPlayState {
    /** Not in any SyncPlay group */
    data object Idle : SyncPlayState()

    /** Currently in a SyncPlay group */
    data class InGroup(
        val groupId: UUID,
        val members: List<SyncPlayParticipant>,
        val isPlaying: Boolean,
        val positionMs: Long,
    ) : SyncPlayState()

    /** Error occurred */
    data class Error(val message: String) : SyncPlayState()
}

/**
 * Represents a participant in a SyncPlay group
 */
data class SyncPlayParticipant(
    val userId: UUID,
    val username: String,
    val isReady: Boolean,
    val isBuffering: Boolean,
)

/**
 * Commands that can be sent to SyncPlay group
 */
enum class SyncPlayCommand {
    PLAY,
    PAUSE,
    SEEK,
    STOP,
    NEXT_TRACK,
    PREVIOUS_TRACK,
}
