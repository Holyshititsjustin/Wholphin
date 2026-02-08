package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.model.DeviceInfo
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import timber.log.Timber
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import android.util.Log

sealed class SyncPlayMessage {
    data class SyncPlayEnabled(val serverName: String? = null) : SyncPlayMessage()
    data class SyncPlayDisabled(val reason: String = "SyncPlay disabled") : SyncPlayMessage()
    data class GroupJoined(val groupId: UUID, val members: List<String>) : SyncPlayMessage()
    data class StateUpdate(val positionMs: Long, val isPlaying: Boolean) : SyncPlayMessage()
    data class UserJoined(val userName: String) : SyncPlayMessage()
    data class UserLeft(val userName: String) : SyncPlayMessage()
    data class GroupLeft(val reason: String = "User left") : SyncPlayMessage()
    data class CommandSent(val command: String) : SyncPlayMessage()
    data class Error(val error: String) : SyncPlayMessage()
}

sealed class SyncPlayCommand {
    data class Pause(val positionMs: Long, val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand()
    data class Unpause(val positionMs: Long, val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand()
    data class Seek(val positionMs: Long, val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand()
    data class SetPlaybackRate(val rate: Float, val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand()
    data class Play(val itemIds: List<UUID>, val startPositionMs: Long, val startIndex: Int, val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand()
    data class Buffering(val itemId: UUID, val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand() // Buffering/Loading state - wait for all clients ready
    data class Stop(val timestamp: Long = System.currentTimeMillis()) : SyncPlayCommand() // Stop playback and exit video
}

@ActivityScoped
class SyncPlayManager @Inject constructor(
    private val api: ApiClient,
    private val serverRepository: ServerRepository,
    @AuthOkHttpClient private val okHttpClient: OkHttpClient,
    private val deviceInfo: DeviceInfo,
    @IoCoroutineScope private val coroutineScope: CoroutineScope,
) {
    private val _syncPlayMessages = MutableStateFlow<SyncPlayMessage?>(null)
    val syncPlayMessages: StateFlow<SyncPlayMessage?> = _syncPlayMessages

    private val _currentGroupId = MutableStateFlow<UUID?>(null)
    val currentGroupId: StateFlow<UUID?> = _currentGroupId

    private val _isSyncPlayActive = MutableStateFlow(false)
    val isSyncPlayActive: StateFlow<Boolean> = _isSyncPlayActive

    private val _groupMembers = MutableStateFlow<List<String>>(emptyList())
    val groupMembers: StateFlow<List<String>> = _groupMembers.asStateFlow()

    private val _availableGroups = MutableStateFlow<List<GroupInfoDto>>(emptyList())
    val availableGroups: StateFlow<List<GroupInfoDto>> = _availableGroups.asStateFlow()

    // Track if the group is actively playing (has a playing item)
    private val _isGroupPlaying = MutableStateFlow(false)
    val isGroupPlaying: StateFlow<Boolean> = _isGroupPlaying.asStateFlow()

    // Track the actual group state for strict Ready coordination
    // Possible values: "Idle", "Waiting", "Paused", "Playing"
    // "Waiting" = buffering, waiting for all clients to report Ready
    private val _groupState = MutableStateFlow<String?>(null)
    val groupState: StateFlow<String?> = _groupState.asStateFlow()

    // Position reporting for synced playback
    private val _reportingPlaybackPosition = MutableStateFlow(false)
    val reportingPlaybackPosition: StateFlow<Boolean> = _reportingPlaybackPosition.asStateFlow()
    private var positionReportingJob: Job? = null

    // Playback commands from server
    private val _playbackCommands = MutableStateFlow<SyncPlayCommand?>(null)
    val playbackCommands: StateFlow<SyncPlayCommand?> = _playbackCommands.asStateFlow()
    
    // Track if we're waiting for other clients to be ready before playback
    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private var subscriptionJob: Job? = null
    private var driftCheckJob: Job? = null
    private var lastKnownPosition = 0L
    private var lastUpdateTime = System.currentTimeMillis()
    private var webSocket: WebSocket? = null
    private var webSocketConnected = false  // Track actual connection state
    private var lastWebSocketEventTime = 0L
    private var refreshGroupsJob: Job? = null
    private var sessionsPollingJob: Job? = null
    private var disablePolling = false
    private var syncPlayEnabledNotified = false
    private var lastNotifiedGroupId: UUID? = null

    private val json = Json { ignoreUnknownKeys = true }

    // Track last remote item to avoid spamming play commands
    private var lastRemoteItemId: UUID? = null
    
    // Track last emitted Play command to prevent restart loops
    private var lastEmittedPlayCommand: SyncPlayCommand.Play? = null
    private var lastEmittedPlayTime = 0L
    private val PLAY_COMMAND_COOLDOWN_MS = 10000L // 10 seconds between Play commands for same item
    
    // Track last executed command to prevent duplicates
    private var lastExecutedCommand: SyncPlayCommand? = null
    private var lastExecutedCommandTime = 0L
    private val COMMAND_DEDUPE_WINDOW_MS = 5000L // 5 seconds window for deduplication (server can spam commands)
    
    // Track if we're currently executing a command to prevent local actions
    val _isExecutingRemoteCommand = MutableStateFlow(false)
    val isExecutingRemoteCommand: StateFlow<Boolean> = _isExecutingRemoteCommand.asStateFlow()
    private var executionTimeoutJob: Job? = null
    private val EXECUTION_TIMEOUT_MS = 5000L // 5 seconds max for command execution
    
    // Network latency tracking for dynamic drift tolerance
    private var lastPingMs = 0L
    private val _estimatedLatency = MutableStateFlow(50L) // Default 50ms
    val estimatedLatency: StateFlow<Long> = _estimatedLatency.asStateFlow()
    
    // Local command throttling to prevent spam
    private var lastLocalPauseTime = 0L
    private var lastLocalUnpauseTime = 0L
    private val LOCAL_COMMAND_THROTTLE_MS = 500L // Minimum 500ms between same commands
    
    // Position reporting optimization
    private var lastReportedPosition = -1L
    private val MIN_POSITION_CHANGE_MS = 100L // Only report if position changed by 100ms
    
    /**
     * Calculate dynamic drift tolerance based on network latency
     * Minimum 500ms, scales up with network latency
     */
    fun getDriftTolerance(): Long {
        return maxOf(500L, _estimatedLatency.value * 2)
    }
    
    /**
     * Signal that this device is ready to play (has buffered the media).
     * Server will wait for all clients to report ready before syncing playback.
     * This must be called BEFORE pressing the play button.
     */
    fun reportBufferingComplete(itemId: UUID) {
        if (!_isSyncPlayActive.value || _currentGroupId.value == null) return
        
        coroutineScope.launch {
            try {
                val accessToken = serverRepository.current.value?.user?.accessToken
                val baseUrl = currentBaseUrl()
                if (accessToken != null && baseUrl != null) {
                    // Use /SyncPlay/Ready instead of /BufferingDone
                    val url = "$baseUrl/SyncPlay/Ready?api_key=$accessToken"
                    val whenIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                    val playlistItemIdString = resolvePlaylistItemId(itemId)
                    if (playlistItemIdString == null) {
                        Timber.w("❌ Cannot report Ready: missing PlaylistItemId mapping for itemId=%s", itemId)
                        return@launch
                    }
                    // Server expects requestData wrapper
                    val requestBodyJson = """
                        {
                            "requestData": {
                                "When": "$whenIso",
                                "PositionTicks": 0,
                                "IsPlaying": false,
                                "PlaylistItemId": "$playlistItemIdString"
                            }
                        }
                    """.trimIndent()
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Timber.i("✅ Reported ready (buffering complete) for item %s - server will sync all clients", itemId)
                        } else {
                            Timber.w("❌ Failed to report ready: HTTP %d", response.code)
                        }
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error reporting buffering complete")
            }
        }
    }
    
    /**
     * Signal that buffering is complete and we're ready to play.
     * Wrapper for easier use from Composables.
     */
    fun setBufferingComplete(itemId: UUID) {
        _isBuffering.value = false
        reportBufferingComplete(itemId)
    }

    fun setLocalPlaybackItemId(itemId: UUID?) {
        if (itemId == null) return
        if (lastRemoteItemId != itemId) {
            Timber.i("🎬 Setting local SyncPlay item id: %s", itemId)
        }
        lastRemoteItemId = itemId
    }

    private fun currentBaseUrl(): String? = serverRepository.current.value?.server?.url?.trimEnd('/')

    private fun buildUrl(path: String): String? = currentBaseUrl()?.let { "$it$path" }

    private fun parseUUID(idString: String): UUID? {
        return runCatching {
            // Try standard UUID format first (with hyphens)
            UUID.fromString(idString)
        }.getOrNull() ?: runCatching {
            // If no hyphens, parse as raw hex string
            if (idString.length == 32) {
                UUID.fromString(
                    idString.substring(0, 8) + "-" +
                    idString.substring(8, 12) + "-" +
                    idString.substring(12, 16) + "-" +
                    idString.substring(16, 20) + "-" +
                    idString.substring(20)
                )
            } else {
                UUID.fromString(idString)
            }
        }.getOrNull()
    }

    private fun formatSyncPlayId(id: UUID): String = id.toString().replace("-", "")

    private fun resolvePlaylistItemId(itemId: UUID): String? {
        return playlistItemIdByItemId[itemId]
    }

    private fun org.jellyfin.sdk.model.UUID.toJavaUuid(): UUID = UUID.fromString(toString())

    private suspend fun createGroupRemote(): UUID {
        val name = deviceInfo.name?.takeIf { it.isNotBlank() } ?: "Wholphin"
        api.syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName = name))

        // The SDK signature returns Unit, so fetch groups and pick the one containing the current user if present.
        val currentUserName = serverRepository.current.value?.user?.name
        val groups = api.syncPlayApi.syncPlayGetGroups().content
        val targetGroup: GroupInfoDto? =
            currentUserName
                ?.let { nameFilter -> groups.firstOrNull { it.participants.contains(nameFilter) } }
                ?: groups.firstOrNull()

        return targetGroup?.groupId?.toJavaUuid()
            ?: throw IllegalStateException("Created group but could not resolve group id")
    }

    private suspend fun joinGroupRemote(groupId: UUID) {
        Timber.i("joinGroupRemote: Joining group %s via SDK", groupId)
        try {
            api.syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId))
            Timber.i("joinGroupRemote: SDK call completed successfully for group %s", groupId)
        } catch (e: Exception) {
            Timber.e(e, "joinGroupRemote: Exception when joining group %s", groupId)
            throw e
        }
    }

    private suspend fun leaveGroupRemote() {
        api.syncPlayApi.syncPlayLeaveGroup()
    }

    fun refreshGroups() {
        Timber.i("🔄 refreshGroups() called - fetching available groups via Jellyfin SDK")
        coroutineScope.launch {
            try {
                val response = api.syncPlayApi.syncPlayGetGroups()
                val groups = response.content
                _availableGroups.value = groups
                Timber.i("Found %d groups", groups.size)
                if (groups.isNotEmpty()) {
                    // Only update info for the current group if already joined
                    if (_isSyncPlayActive.value) {
                        val currentId = _currentGroupId.value
                        val updatedGroup = groups.find { it.groupId.toJavaUuid() == currentId }
                        if (updatedGroup != null) {
                            applyGroupDto(updatedGroup)
                        }
                    }
                } else {
                    Timber.i("No SyncPlay groups available")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error refreshing SyncPlay groups via SDK")
                _syncPlayMessages.value = SyncPlayMessage.Error(e.message ?: "Failed to load groups")
            }
        }
    }

    fun enableGroupPolling() {
        startPollingGroups()
    }

    /**
     * Start reporting playback position to the SyncPlay group.
     * Call this when playback starts and the TV is in a SyncPlay group.
     * Periodically reports the current playback position so the server can sync other clients.
     *
     * @param getPosition Lambda that returns the current playback position in milliseconds
     */
    fun startPositionReporting(getPosition: suspend () -> Long) {
        if (_reportingPlaybackPosition.value) return // Already reporting

        _reportingPlaybackPosition.value = true
        positionReportingJob = coroutineScope.launch {
            while (isActive && isSyncPlayActive.value && currentGroupId.value != null) {
                try {
                    // FIX: Ensure player access happens on main thread
                    val position = withContext(Dispatchers.Main.immediate) {
                        getPosition()
                    }
                    val groupId = currentGroupId.value ?: return@launch
                    
                    // Optimization: Skip reporting if position hasn't changed significantly
                    val positionChange = Math.abs(position - lastReportedPosition)
                    if (lastReportedPosition >= 0 && positionChange < MIN_POSITION_CHANGE_MS) {
                        Timber.d("⏭️ Skipping position report - change too small: ${positionChange}ms")
                        delay(1000)
                        continue
                    }

                    // Report playback position to server via /SyncPlay/Ready endpoint
                    val positionTicks = position * 10000L // Convert ms to ticks (100ns units)
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    val playlistItemId = lastRemoteItemId
                    if (accessToken != null && baseUrl != null && playlistItemId != null) {
                        val url = "$baseUrl/SyncPlay/Ready?api_key=$accessToken"
                        val playlistItemIdString = resolvePlaylistItemId(playlistItemId)
                        if (playlistItemIdString == null) {
                            Timber.w("❌ Cannot report position: missing PlaylistItemId mapping for itemId=%s", playlistItemId)
                            delay(1000)
                            continue
                        }
                        val whenIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                        // Server expects requestData wrapper
                        val requestBodyJson = """
                            {
                                "requestData": {
                                    "When": "$whenIso",
                                    "PositionTicks": $positionTicks,
                                    "IsPlaying": true,
                                    "PlaylistItemId": "$playlistItemIdString"
                                }
                            }
                        """.trimIndent()
                        val request = Request.Builder()
                            .url(url)
                            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                            .build()
                        
                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                lastReportedPosition = position
                                Timber.d("✅ Reported ready status to /SyncPlay/Ready: position=$position ms ($positionTicks ticks) itemId=$playlistItemId")
                            } else {
                                val errorBody = response.body?.string()
                                Timber.w("❌ Failed to report ready status to group $groupId: HTTP ${response.code} - ${response.message}, body: $errorBody")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot report position: missing access token, base URL, or playlistItemId")
                    }

                } catch (e: Exception) {
                    Log.e("SyncPlayManager", "Error reporting position", e)
                }
                delay(1000) // Report position every 1 second
            }
            _reportingPlaybackPosition.value = false
        }
    }

    /**
     * Stop reporting playback position.
     * Call this when playback stops or the TV leaves the SyncPlay group.
     */
    fun stopPositionReporting() {
        positionReportingJob?.cancel()
        positionReportingJob = null
        _reportingPlaybackPosition.value = false
        lastReportedPosition = -1L // Reset for next session
    }

    /**
     * Stops drift checking to prevent seeking on dead player
     */
    fun stopDriftChecking() {
        driftCheckJob?.cancel()
        driftCheckJob = null
        Timber.i("🔍 Drift checking stopped")
    }

    /**
     * Enable SyncPlay - starts WebSocket connection
     */
    fun enableSyncPlay() {
        Timber.i("🎬 Enabling SyncPlay")
        postSyncPlayCapabilities()
        startListening()
        startPollingGroups()
    }

    private fun postSyncPlayCapabilities() {
        val accessToken = serverRepository.current.value?.user?.accessToken
        val baseUrl = currentBaseUrl()
        if (accessToken.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            Timber.w("🎬 SyncPlay capabilities not sent: missing access token or base URL")
            return
        }

        val url = "$baseUrl/Sessions/Capabilities?api_key=$accessToken"
        val requestBodyJson =
            """
            {
                "PlayableMediaTypes": ["Video"],
                "SupportedCommands": ["DisplayMessage", "SendString"],
                "SupportsMediaControl": true,
                "SupportsSyncPlay": true
            }
            """.trimIndent()
        val request =
            Request.Builder()
                .url(url)
                .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                .build()

        coroutineScope.launch {
            runCatching {
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        Timber.i("✅ Posted SyncPlay capabilities to server (SyncPlayManager)")
                        if (!syncPlayEnabledNotified) {
                            val serverName = serverRepository.current.value?.server?.name
                            _syncPlayMessages.value = SyncPlayMessage.SyncPlayEnabled(serverName)
                            syncPlayEnabledNotified = true
                        }
                    } else {
                        Timber.w(
                            "❌ Failed to post SyncPlay capabilities (SyncPlayManager): HTTP %d - %s",
                            response.code,
                            response.message,
                        )
                    }
                }
            }.onFailure { ex ->
                Timber.e(ex, "❌ Error posting SyncPlay capabilities (SyncPlayManager)")
            }
        }
    }

    /**
     * Disable SyncPlay - closes WebSocket connection
     */
    fun disableSyncPlay() {
        Timber.i("🎬 Disabling SyncPlay")
        webSocket?.close(1000, "SyncPlay disabled")
        webSocket = null
        refreshGroupsJob?.cancel()
        sessionsPollingJob?.cancel()
        _isSyncPlayActive.value = false
        _currentGroupId.value = null
        lastNotifiedGroupId = null
        syncPlayEnabledNotified = false
        _syncPlayMessages.value = SyncPlayMessage.SyncPlayDisabled()
    }

    /**
     * Send seek command to SyncPlay group
     */
    fun seek(positionMs: Long) {
        if (!_isSyncPlayActive.value) return
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                if (groupId != null) {
                    // Send seek command via direct HTTP call (SDK method expects SeekRequestDto)
                    val positionTicks = positionMs * 10000L // Convert ms to ticks (100ns units)
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    if (accessToken != null && baseUrl != null) {
                        val url = "$baseUrl/SyncPlay/Seek?api_key=$accessToken"
                        val requestBodyJson = """
                            {
                                "PositionTicks": $positionTicks
                            }
                        """.trimIndent()
                        val request = Request.Builder()
                            .url(url)
                            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Timber.i("✅ Sent seek command to SyncPlay group: $groupId, position: ${positionMs}ms ($positionTicks ticks)")
                                _syncPlayMessages.value = SyncPlayMessage.CommandSent("Seek to ${positionMs}ms")
                            } else {
                                Timber.w("❌ Failed to send seek command to group $groupId: HTTP ${response.code} - ${response.message}")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot send seek command: missing access token or base URL")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending seek command")
            }
        }
    }

    fun play(itemIds: List<UUID>, startPositionMs: Long, startIndex: Int) {
        Timber.i("🎬 play() function called: itemIds=${itemIds.size}, startPositionMs=$startPositionMs, startIndex=$startIndex, isSyncPlayActive=${_isSyncPlayActive.value}")
        if (!_isSyncPlayActive.value) {
            Timber.w("🎬❌ play() BLOCKING: _isSyncPlayActive.value=${_isSyncPlayActive.value} is FALSE - returning early before coroutineScope.launch!")
            return
        }
        Timber.i("🎬✅ play() PASSED the _isSyncPlayActive check, proceeding to coroutineScope.launch")
        coroutineScope.launch {
            Timber.i("🎬 play() coroutineScope.launch executing")
            try {
                val groupId = _currentGroupId.value
                Timber.i("🎬 Inside coroutine: groupId=$groupId")
                if (groupId != null) {
                    // CRITICAL FIX: ALWAYS close and reconnect WebSocket before sending commands
                    // This ensures we don't miss server broadcasts if previous connection became stale
                    Timber.i("🎬 Forcing WebSocket reconnect to ensure fresh connection before play command...")
                    if (webSocket != null) {
                        webSocket?.close(1000, "reconnect for play")
                        webSocket = null
                    }
                    startListening()
                    delay(500) // Give WebSocket time to connect
                    
                    // Send play command via /SyncPlay/SetNewQueue endpoint
                    val positionTicks = startPositionMs * 10000L // Convert ms to ticks
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    Timber.i("🎬 SyncPlay play() called: groupId=$groupId, itemIds=${itemIds.size}, startPositionMs=$startPositionMs, startIndex=$startIndex, accessToken=${accessToken != null}, baseUrl=$baseUrl")
                    if (accessToken != null && baseUrl != null) {
                        val url = "$baseUrl/SyncPlay/SetNewQueue?api_key=$accessToken"
                        val playingQueueItems = itemIds.map { "\"$it\"" }.joinToString(", ")
                        val requestBodyJson = """
                            {
                                "PlayingQueue": [$playingQueueItems],
                                "PlayingItemPosition": $startIndex,
                                "StartPositionTicks": $positionTicks
                            }
                        """.trimIndent()
                        Timber.i("🎬 Sending play command to /SyncPlay/SetNewQueue with body: $requestBodyJson")
                        val request = Request.Builder()
                            .url(url)
                            .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                            .build()
                        
                        okHttpClient.newCall(request).execute().use { response ->
                            Timber.i("🎬 Play command response: HTTP ${response.code} - ${response.message}")
                            if (response.isSuccessful) {
                                Timber.i("✅ Sent play command to /SyncPlay/SetNewQueue: $groupId")
                                _syncPlayMessages.value = SyncPlayMessage.CommandSent("Play")
                                // Send Ready status after successful play command with a small delay
                                val firstItemId = itemIds.getOrNull(startIndex) ?: itemIds.firstOrNull()
                                if (firstItemId != null) {
                                    lastRemoteItemId = firstItemId
                                    sendReadyAfterPlay(firstItemId, positionTicks)
                                }
                            } else {
                                val errorBody = response.body?.string()
                                Timber.w("❌ Failed to send play command to /SyncPlay/SetNewQueue: HTTP ${response.code} - ${response.message}, body: $errorBody")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot send play command: missing access token ($accessToken) or base URL ($baseUrl)")
                    }
                } else {
                    Timber.w("🎬 groupId is null, cannot send play command")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending play command")
            }
        }
    }

    fun joinGroup(groupId: UUID) {
        coroutineScope.launch {
            try {
                Timber.i("🎬🔗 Starting join process for group: $groupId")

                // Ensure server knows this client supports SyncPlay
                postSyncPlayCapabilities()
                
                // Step 1: Join the group via API
                joinGroupRemote(groupId)
                _currentGroupId.value = groupId
                _isSyncPlayActive.value = true
                _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, emptyList())
                lastNotifiedGroupId = groupId
                Timber.i("✅ Joined group via Jellyfin API: $groupId")
                
                // Step 2: Ensure WebSocket is connected
                Timber.i("🔗 Ensuring WebSocket is connected...")
                startListening()
                delay(1000) // Give WebSocket time to establish connection
                
                // Step 3: Start polling for group state
                Timber.i("🔄 Starting sessions polling...")
                startSessionsPolling()
                
                // Step 4: Send Ready message to request current playlist
                Timber.i("📤 Sending Ready message to request playlist...")
                delay(500) // Allow server to process join first
                sendReadyAfterJoin(groupId)
                
                // Step 5: Explicitly request current group info via REST API
                Timber.i("📡 Requesting current group state...")
                delay(500)
                requestGroupState(groupId)
                
                Timber.i("✅ Join process complete - listening for updates")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error joining group")
                _syncPlayMessages.value = SyncPlayMessage.Error(e.message ?: "Unknown error")
            }
        }
    }
    
    /**
     * Explicitly request the current group state from the server
     * This helps sync up with groups that are already playing
     */
    private suspend fun requestGroupState(groupId: UUID) {
        try {
            val accessToken = serverRepository.current.value?.user?.accessToken
            val baseUrl = currentBaseUrl()
            if (accessToken != null && baseUrl != null) {
                // Query current group info
                val url = "$baseUrl/SyncPlay/List?api_key=$accessToken"
                Timber.i("📡 Explicitly requesting group state from: $url")
                val request = Request.Builder().url(url).build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            Timber.i("📡 Group state response: $body")
                            val groupsArray = json.parseToJsonElement(body).jsonArray
                            
                            for (groupElement in groupsArray) {
                                val groupObj = groupElement.jsonObject
                                val gidStr = groupObj["GroupId"]?.jsonPrimitive?.content
                                val gidUuid = gidStr?.let { parseUUID(it) }
                                
                                if (gidUuid == groupId) {
                                    val state = groupObj["State"]?.jsonPrimitive?.content
                                    Timber.i("📡 Found group $groupId with state: $state")
                                    
                                    // If group is Playing, trigger polling to pick up the playlist
                                    if (state == "Playing") {
                                        Timber.i("🎬 Group is already Playing - triggering immediate poll")
                                        queryRemotePlaybackItem(groupId)
                                    }
                                    break
                                }
                            }
                        }
                    } else {
                        Timber.w("⚠️ Failed to request group state: HTTP ${response.code}")
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Error requesting group state")
        }
    }

    fun createGroup() {
        coroutineScope.launch {
            try {
                // Ensure server knows this client supports SyncPlay
                postSyncPlayCapabilities()

                val newGroupId = createGroupRemote()
                Timber.d("Created group via Jellyfin API: $newGroupId")
                // Must explicitly join the created group
                joinGroupRemote(newGroupId)
                _currentGroupId.value = newGroupId
                _isSyncPlayActive.value = true
                _syncPlayMessages.value = SyncPlayMessage.GroupJoined(newGroupId, emptyList())
                lastNotifiedGroupId = newGroupId
                Timber.d("Joined newly created group: $newGroupId")
                startListening()
                startSessionsPolling()
            } catch (e: Exception) {
                Timber.e(e, "Error creating group")
                _syncPlayMessages.value = SyncPlayMessage.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun leaveGroup() {
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                if (groupId != null) {
                    leaveGroupRemote()
                    Timber.d("Leaving SyncPlay group: $groupId")
                    _syncPlayMessages.value = SyncPlayMessage.GroupLeft("Left group")
                }
                _currentGroupId.value = null
                _isSyncPlayActive.value = false
                _groupMembers.value = emptyList()
                lastNotifiedGroupId = null
                subscriptionJob?.cancel()
                sessionsPollingJob?.cancel()
                lastRemoteItemId = null
                Timber.d("Left group via Jellyfin API")
            } catch (e: Exception) {
                Timber.e(e, "Error leaving group")
            }
        }
    }

    fun pause() {
        Timber.i("🎬 pause() called - sending Pause command to server")
        
        // Throttle: Don't send if we just sent a pause recently
        val now = System.currentTimeMillis()
        if (now - lastLocalPauseTime < LOCAL_COMMAND_THROTTLE_MS) {
            Timber.d("⏭️ Throttling pause command - last sent ${now - lastLocalPauseTime}ms ago")
            return
        }
        lastLocalPauseTime = now
        
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                Timber.d("🔍 pause(): groupId=$groupId")
                if (groupId != null) {
                    // Send pause command via direct HTTP call
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    Timber.d("🔍 pause(): accessToken=${accessToken != null}, baseUrl=$baseUrl")
                    if (accessToken != null && baseUrl != null) {
                        val url = "$baseUrl/SyncPlay/Pause?api_key=$accessToken"
                        Timber.i("📤 Sending Pause to: $url")
                        val request = Request.Builder()
                            .url(url)
                            .post(ByteString.EMPTY.toRequestBody("application/json".toMediaType()))
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Timber.i("✅ Sent pause command to SyncPlay group: $groupId (HTTP ${response.code})")
                                _syncPlayMessages.value = SyncPlayMessage.CommandSent("Pause")
                            } else {
                                val errorBody = response.body?.string()
                                Timber.w("❌ Failed to send pause command to group $groupId: HTTP ${response.code} - ${response.message}, body: $errorBody")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot send pause command: missing access token or base URL")
                    }
                } else {
                    Timber.w("❌ Cannot send pause command: not in a SyncPlay group")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error pausing SyncPlay")
            }
        }
    }

    fun unpause() {
        Timber.i("🎬 unpause() called - sending Unpause command to server")
        
        // Throttle: Don't send if we just sent an unpause recently
        val now = System.currentTimeMillis()
        if (now - lastLocalUnpauseTime < LOCAL_COMMAND_THROTTLE_MS) {
            Timber.d("⏭️ Throttling unpause command - last sent ${now - lastLocalUnpauseTime}ms ago")
            return
        }
        lastLocalUnpauseTime = now
        
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                Timber.d("🔍 unpause(): groupId=$groupId")
                if (groupId != null) {
                    // Send unpause command via direct HTTP call
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    Timber.d("🔍 unpause(): accessToken=${accessToken != null}, baseUrl=$baseUrl")
                    if (accessToken != null && baseUrl != null) {
                        val url = "$baseUrl/SyncPlay/Unpause?api_key=$accessToken"
                        Timber.i("📤 Sending Unpause to: $url")
                        val request = Request.Builder()
                            .url(url)
                            .post(ByteString.EMPTY.toRequestBody("application/json".toMediaType()))
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Timber.i("✅ Sent unpause command to SyncPlay group: $groupId (HTTP ${response.code})")
                                _syncPlayMessages.value = SyncPlayMessage.CommandSent("Resume")
                            } else {
                                val errorBody = response.body?.string()
                                Timber.w("❌ Failed to send unpause command to group $groupId: HTTP ${response.code} - ${response.message}, body: $errorBody")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot send unpause command: missing access token or base URL")
                    }
                } else {
                    Timber.w("❌ Cannot send unpause command: not in a SyncPlay group")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error unpausing SyncPlay")
            }
        }
    }

    fun reportPlaybackPosition(positionMs: Long) {
        lastKnownPosition = positionMs
        lastUpdateTime = System.currentTimeMillis()
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                if (groupId != null && _isSyncPlayActive.value) {
                    // Using Jellyfin SDK API client to report playback progress
                    Timber.d("Reporting playback position to SyncPlay: ${positionMs}ms")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error reporting position")
            }
        }
    }
    
    /**
     * Check if a command is a duplicate of the last executed command
     * within the deduplication window
     */
    private fun isDuplicateCommand(command: SyncPlayCommand): Boolean {
        val now = System.currentTimeMillis()
        val lastCmd = lastExecutedCommand
        
        // Check if within deduplication window
        val timeSinceLastCommand = now - lastExecutedCommandTime
        if (timeSinceLastCommand > COMMAND_DEDUPE_WINDOW_MS) {
            Timber.d("🔍 Not duplicate: time since last command ${timeSinceLastCommand}ms > ${COMMAND_DEDUPE_WINDOW_MS}ms")
            return false
        }
        
        // Check if commands are equivalent
        val driftTolerance = getDriftTolerance()
        val isDuplicate = when {
            command is SyncPlayCommand.Pause && lastCmd is SyncPlayCommand.Pause -> {
                val drift = Math.abs(command.positionMs - lastCmd.positionMs)
                val result = drift < driftTolerance
                Timber.d("🔍 Pause command: drift=${drift}ms, tolerance=${driftTolerance}ms, isDuplicate=$result")
                result
            }
            command is SyncPlayCommand.Unpause && lastCmd is SyncPlayCommand.Unpause -> {
                val drift = Math.abs(command.positionMs - lastCmd.positionMs)
                val result = drift < driftTolerance
                Timber.d("🔍 Unpause command: drift=${drift}ms, tolerance=${driftTolerance}ms, isDuplicate=$result")
                result
            }
            command is SyncPlayCommand.Seek && lastCmd is SyncPlayCommand.Seek -> {
                val drift = Math.abs(command.positionMs - lastCmd.positionMs)
                val result = drift < driftTolerance
                Timber.d("🔍 Seek command: drift=${drift}ms, tolerance=${driftTolerance}ms, isDuplicate=$result")
                result
            }
            command is SyncPlayCommand.Play && lastCmd is SyncPlayCommand.Play -> {
                val result = command.itemIds.firstOrNull() == lastCmd.itemIds.firstOrNull()
                Timber.d("🔍 Play command: same item=$result")
                result
            }
            else -> {
                Timber.d("🔍 Different command types: current=${command::class.simpleName}, last=${lastCmd?.let { it::class.simpleName }}")
                false
            }
        }
        
        if (isDuplicate) {
            Timber.w("⚠️ DUPLICATE COMMAND DETECTED: $command (within ${timeSinceLastCommand}ms of last)")
        }
        
        return isDuplicate
    }
    
    /**
     * Mark a command as executed for deduplication
     */
    fun markCommandExecuted(command: SyncPlayCommand) {
        lastExecutedCommand = command
        lastExecutedCommandTime = System.currentTimeMillis()
        Timber.d("✅ Marked command as executed: ${command::class.simpleName} at ${lastExecutedCommandTime}")
    }
    
    /**
     * Start execution flag timeout watchdog
     * If command takes too long, force clear the flag to prevent deadlock
     */
    fun startExecutionTimeout() {
        executionTimeoutJob?.cancel()
        executionTimeoutJob = coroutineScope.launch {
            delay(EXECUTION_TIMEOUT_MS)
            if (_isExecutingRemoteCommand.value) {
                Timber.w("⚠️ EXECUTION TIMEOUT: Command took >${EXECUTION_TIMEOUT_MS}ms, forcing flag clear to prevent deadlock")
                _isExecutingRemoteCommand.value = false
            }
        }
    }
    
    /**
     * Cancel execution timeout watchdog
     */
    fun cancelExecutionTimeout() {
        executionTimeoutJob?.cancel()
        executionTimeoutJob = null
    }

    /**
     * Helper function to send Ready status after joining a group.
     * This tells the server we're not buffering and ready to receive the group's current state/playlist.
     */
    private fun sendReadyAfterJoin(groupId: UUID) {
        coroutineScope.launch {
            try {
                val accessToken = serverRepository.current.value?.user?.accessToken
                val baseUrl = currentBaseUrl()
                if (accessToken != null && baseUrl != null) {
                    val url = "$baseUrl/SyncPlay/Ready?api_key=$accessToken"
                    val whenIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                    // Server expects requestData wrapper
                    val requestBodyJson = """
                        {
                            "requestData": {
                                "When": "$whenIso",
                                "PositionTicks": 0,
                                "IsPlaying": false,
                                "PlaylistItemId": "00000000000000000000000000000000"
                            }
                        }
                    """.trimIndent()
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Timber.i("✅ Sent Ready status after joining group: tells server to send current playlist")
                        } else {
                            val errorBody = response.body?.string()
                            Timber.w("❌ Failed to send Ready status after join: HTTP ${response.code} - ${response.message}, body: $errorBody")
                        }
                    }
                } else {
                    Timber.w("❌ Cannot send Ready after join: missing access token or base URL")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending Ready status after join")
            }
        }
    }

    /**
     * Helper function to send Ready status after a successful play command.
     * Waits a small delay to allow server processing before reporting ready.
     */
    private fun sendReadyAfterPlay(itemId: UUID, positionTicks: Long) {
        coroutineScope.launch {
            try {
                delay(500) // Allow server to process play command
                val accessToken = serverRepository.current.value?.user?.accessToken
                val baseUrl = currentBaseUrl()
                if (accessToken != null && baseUrl != null) {
                    val url = "$baseUrl/SyncPlay/Ready?api_key=$accessToken"
                    val playlistItemIdString = resolvePlaylistItemId(itemId)
                    if (playlistItemIdString == null) {
                        Timber.w("❌ Cannot send Ready after play: missing PlaylistItemId mapping for itemId=%s", itemId)
                        return@launch
                    }
                    val whenIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
                    // Server expects requestData wrapper
                    val requestBodyJson = """
                        {
                            "requestData": {
                                "When": "$whenIso",
                                "PositionTicks": $positionTicks,
                                "IsPlaying": true,
                                "PlaylistItemId": "$playlistItemIdString"
                            }
                        }
                    """.trimIndent()
                    val request = Request.Builder()
                        .url(url)
                        .post(requestBodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    
                    okHttpClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            Timber.i("✅ Sent Ready status after play command: itemId=$itemId, position=$positionTicks ticks")
                        } else {
                            val errorBody = response.body?.string()
                            Timber.w("❌ Failed to send Ready status: HTTP ${response.code} - ${response.message}, body: $errorBody")
                        }
                    }
                } else {
                    Timber.w("❌ Cannot send Ready: missing access token or base URL")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error sending Ready status after play")
            }
        }
    }

    private fun startListening() {
        Timber.i("startListening: Called - closing existing WebSocket and creating fresh connection")
        
        // Always close existing WebSocket to ensure session matches current API context
        if (webSocket != null) {
            Timber.i("startListening: Closing existing WebSocket")
            try {
                webSocket?.close(1000, "creating fresh connection")
            } catch (e: Exception) {
                Timber.w(e, "startListening: Error closing WebSocket")
            }
            webSocket = null
        }
        webSocketConnected = false
        
        val accessToken = serverRepository.current.value?.user?.accessToken
        if (accessToken == null) {
            Timber.w("startListening: No access token available, cannot start WebSocket")
            return
        }
        val baseUrl = currentBaseUrl()
        if (baseUrl == null) {
            Timber.w("startListening: No base URL available, cannot start WebSocket")
            return
        }
        val wsUrl =
            baseUrl
                .replaceFirst("http://", "ws://")
                .replaceFirst("https://", "wss://") +
                "/socket?api_key=$accessToken&deviceId=${deviceInfo.id}"

        Timber.i("startListening: Building WebSocket request for URL: $wsUrl")
        val request = Request.Builder().url(wsUrl).build()
        Timber.i("startListening: Calling okHttpClient.newWebSocket()...")
        val connectionStartTime = System.currentTimeMillis()
        webSocket =
            okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        val connectionTime = System.currentTimeMillis() - connectionStartTime
                        webSocketConnected = true
                        lastWebSocketEventTime = System.currentTimeMillis()
                        Timber.i("🔗 WebSocket CONNECTED in ${connectionTime}ms! Response: ${response.code} ${response.message}")
                        Timber.i("🔗 WebSocket ready to receive SyncPlay messages from server")
                        Timber.i("🔗 WebSocket URL: $wsUrl")
                        Timber.i("🔗 WebSocket headers: ${response.headers}")
                        // _syncPlayMessages.value = SyncPlayMessage.Info("Connected to server") // Removed - no Info class
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        lastWebSocketEventTime = System.currentTimeMillis()
                        Timber.i("📨 WebSocket MESSAGE RECEIVED (${text.length} chars): $text")

                        // Parse and log message type for debugging
                        try {
                            val jsonMessage = Json.parseToJsonElement(text).jsonObject
                            val messageType = jsonMessage["MessageType"]?.jsonPrimitive?.content
                            val data = jsonMessage["Data"]?.jsonObject
                            Timber.i("📨 MessageType: $messageType, Data keys: ${data?.keys?.joinToString()}")
                        } catch (e: Exception) {
                            Timber.w("📨 Could not parse WebSocket message as JSON: ${e.message}")
                        }

                        handleSocketMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val textMessage = bytes.utf8()
                        lastWebSocketEventTime = System.currentTimeMillis()
                        Timber.i("📨 WebSocket BYTES MESSAGE RECEIVED (${bytes.size} bytes, ${textMessage.length} chars): $textMessage")
                        handleSocketMessage(textMessage)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        val failureTime = System.currentTimeMillis()
                        webSocketConnected = false
                        lastWebSocketEventTime = failureTime
                        Timber.e(t, "❌ WebSocket FAILURE at ${failureTime}: ${t.message}")
                        Timber.e("❌ WebSocket failure response: ${response?.code} ${response?.message}")
                        Timber.e("❌ WebSocket failure headers: ${response?.headers}")
                        Timber.e("❌ WebSocket will attempt automatic reconnect")
                        _syncPlayMessages.value = SyncPlayMessage.Error("Connection failed: ${t.message}")
                        refreshGroupsJob?.cancel()
                        this@SyncPlayManager.webSocket = null

                        // Log connection state for debugging
                        Timber.i("❌ WebSocket state after failure: connected=$webSocketConnected, webSocket=null")
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        val closeTime = System.currentTimeMillis()
                        webSocketConnected = false
                        lastWebSocketEventTime = closeTime
                        Timber.w("🔌 WebSocket CLOSED at ${closeTime}: code=$code, reason='$reason'")
                        Timber.w("🔌 WebSocket close was ${if (code == 1000) "normal" else "abnormal"}")
                        Timber.w("🔌 WebSocket will attempt automatic reconnect")
                        this@SyncPlayManager.webSocket = null

                        // Log connection state for debugging
                        Timber.i("🔌 WebSocket state after close: connected=$webSocketConnected, webSocket=null")
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        lastWebSocketEventTime = System.currentTimeMillis()
                        Timber.w("🔌 WebSocket CLOSING: code=$code, reason='$reason'")
                        Timber.w("🔌 Acknowledging close with normal closure")
                        webSocket.close(1000, null)  // Acknowledge close
                    }
                },
            )
        Timber.i("startListening: WebSocket connection request queued at ${System.currentTimeMillis()}")
    }

    private fun sendWebSocketMessage(message: String) {
        coroutineScope.launch {
            val sendTime = System.currentTimeMillis()
            Timber.i("📤 Attempting to send WebSocket message at ${sendTime}")
            Timber.i("📤 WebSocket connected: $webSocketConnected, webSocket instance: ${webSocket != null}")

            if (webSocket != null && webSocketConnected) {
                val success = webSocket?.send(message) ?: false
                val sendDuration = System.currentTimeMillis() - sendTime
                if (success) {
                    Timber.i("📤✅ WebSocket message sent successfully in ${sendDuration}ms: ${message.take(200)}")
                } else {
                    Timber.e("📤❌ WebSocket send failed after ${sendDuration}ms: ${message.take(200)}")
                }
            } else {
                Timber.w("📤❌ Cannot send WebSocket message - connected=$webSocketConnected, instance=${webSocket != null}")
                Timber.w("📤❌ Message content: ${message.take(200)}")
            }
        }
    }

    private fun startSessionsPolling() {
        Timber.i("🔄 startSessionsPolling() called - isSyncPlayActive=%s, currentGroupId=%s", _isSyncPlayActive.value, _currentGroupId.value)
        sessionsPollingJob?.cancel()
        Timber.i("🔄 Previous polling job cancelled, launching new one")
        sessionsPollingJob = coroutineScope.launch {
            Timber.i("🔄✅ Polling coroutine launched and started executing!")
            try {
                var iteration = 0
                while (isActive) {
                    iteration++
                    val syncActive = _isSyncPlayActive.value
                    val groupId = _currentGroupId.value
                    Timber.d("🔄 Polling iteration #%d - isActive=%s, isSyncPlayActive=%s, groupId=%s", iteration, isActive, syncActive, groupId)
                    
                    // HEALTH CHECK: Ensure WebSocket is connected and listening
                    // If WebSocket is disconnected, reconnect to ensure we receive server broadcasts
                    if (!webSocketConnected && syncActive) {
                        Timber.i("🔄📡 WebSocket disconnected during polling - reconnecting to ensure we receive server broadcasts")
                        startListening()
                    }
                    
                    if (!syncActive) {
                        Timber.i("🔄⏹️ isSyncPlayActive is false, exiting polling loop")
                        break
                    }
                    
                    try {
                        // Use SDK to get groups and check for playback changes
                        val response = api.syncPlayApi.syncPlayGetGroups()
                        val groups = response.content
                        Timber.d("🔄🔢 SDK returned %d groups", groups.size)

                        val normalizedCurrentGroupId = groupId?.toString()?.replace("-", "")?.lowercase()
                        if (groups.isNotEmpty()) {
                            val groupIdsForLog = groups.mapNotNull { it.groupId?.toString() }
                            Timber.d("🔄🧭 SDK groupIds=%s, currentGroupId=%s", groupIdsForLog, groupId)
                        }

                        val matchingGroup =
                            if (groupId != null) {
                                groups.find { sdkGroup ->
                                    val sdkGroupId = sdkGroup.groupId?.toString()?.replace("-", "")?.lowercase()
                                    sdkGroupId != null && sdkGroupId == normalizedCurrentGroupId
                                }
                            } else {
                                null
                            }

                        when {
                            matchingGroup != null -> {
                                checkForRemotePlayback(matchingGroup)
                            }
                            groupId == null -> {
                                Timber.w("🔄⚠️ groupId is null")
                                if (groups.size == 1) {
                                    val fallbackGroup = groups.first()
                                    Timber.w("🔄🟡 Using single SDK group as fallback: %s", fallbackGroup.groupId)
                                    applyGroupDto(fallbackGroup)
                                    checkForRemotePlayback(fallbackGroup)
                                }
                            }
                            groups.size == 1 -> {
                                val fallbackGroup = groups.first()
                                Timber.w("🔄🟡 Matching group not found, but only one group exists. Using fallback groupId=%s", fallbackGroup.groupId)
                                applyGroupDto(fallbackGroup)
                                checkForRemotePlayback(fallbackGroup)
                            }
                            else -> {
                                Timber.w("🔄❌ Matching group not found in SDK response")
                            }
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "🔄❌ Polling error on iteration %d", iteration)
                    }
                    delay(2000)
                }
                Timber.i("🔄🛑 Polling loop exited - isActive=%s", isActive)
            } catch (e: Exception) {
                Timber.e(e, "🔄💥 Fatal error in polling coroutine")
            }
        }
        Timber.i("🔄✨ Polling job configured")
    }

    // Track last observed group state to detect changes
    private var lastGroupState: String? = null

    // Throttle Ready re-requests when group is waiting but playlist hasn't arrived
    private var lastReadyRequestTime = 0L
    private val READY_REQUEST_COOLDOWN_MS = 10_000L
    
    // Track last playlist received from WebSocket so we don't query REST for empty playlists
    private var lastReceivedPlaylist: List<UUID> = emptyList()
    private var lastReceivedPlaylistIndex: Int = 0
    private var lastReceivedPlaylistPositionMs: Long = 0L

    // Map ItemId -> PlaylistItemId for accurate Ready reporting
    private val playlistItemIdByItemId: MutableMap<UUID, String> = mutableMapOf()
    
    // Track if we've already emitted a Play command for current playlist to prevent restart loops
    private var lastPlaylistEmittedHash: Int = 0

    private fun checkForRemotePlayback(group: GroupInfoDto) {
        try {
            val currentState = group.state?.toString()
            Timber.d("🔄📊 Group state - current=%s, last=%s, state_enum=%s", currentState, lastGroupState, group.state)

            if (currentState != null && currentState != lastGroupState) {
                Timber.i("📡 Group state changed from %s to %s", lastGroupState, currentState)
                lastGroupState = currentState
                _groupState.value = currentState

                // Only update local state from group status; actual pause/unpause
                // should come from SyncPlayCommand messages to avoid false pauses.
                when (currentState) {
                    "Playing" -> {
                        _isGroupPlaying.value = true
                        Timber.i("✅ Group transitioned to Playing - all clients are ready")
                    }
                    "Waiting" -> {
                        _isGroupPlaying.value = false
                        Timber.i("⏳ Group in Waiting state - buffering, waiting for all clients to report Ready")
                    }
                    "Paused" -> {
                        _isGroupPlaying.value = false
                        Timber.i("⏸️ Group in Paused state")
                    }
                    "Idle" -> {
                        _isGroupPlaying.value = false
                        Timber.i("🔵 Group in Idle state")
                    }
                    else -> Timber.d("📡 Unknown group state: %s", currentState)
                }
            }

            // If group is waiting and we haven't received a playlist yet, ensure WS is connected
            // and re-request Ready to prompt the server to send the current PlayQueue.
            if (currentState == "Waiting" && lastReceivedPlaylist.isEmpty()) {
                if (!webSocketConnected) {
                    Timber.i("🔌 WebSocket not connected while group is Waiting - reconnecting")
                    startListening()
                }
                val now = System.currentTimeMillis()
                if (now - lastReadyRequestTime > READY_REQUEST_COOLDOWN_MS) {
                    Timber.i("📤 Re-requesting Ready while Waiting (no playlist yet)")
                    lastReadyRequestTime = now
                    sendReadyAfterJoin(group.groupId.toJavaUuid())
                }
            }

            // Only query playlist if we don't have cached data OR state just changed to Playing
            if (lastReceivedPlaylist.isEmpty() || (currentState == "Playing" && lastGroupState != currentState)) {
                // Always query playlist details for the current group
                coroutineScope.launch {
                    try {
                        queryRemotePlaybackItem(group.groupId.toJavaUuid())
                    } catch (e: Exception) {
                        Timber.w(e, "Error querying remote playback item")
                    }
                }
            } else if (lastReceivedPlaylist.isNotEmpty() && currentState == "Playing") {
                // We have cached playlist and group is Playing
                // Don't emit Play commands from polling when already playing - server sends commands via WebSocket
                // Polling should only detect NEW playback, not maintain existing playback
                if (!_isGroupPlaying.value) {
                    // Group just transitioned to Playing - emit initial Play command
                    val now = System.currentTimeMillis()
                    val playlistHash = lastReceivedPlaylist.hashCode() + lastReceivedPlaylistIndex
                    
                    if (playlistHash != lastPlaylistEmittedHash || now - lastEmittedPlayTime > PLAY_COMMAND_COOLDOWN_MS) {
                        Timber.i("📡 Group transitioned to Playing - emitting initial Play command")
                        val cmd = SyncPlayCommand.Play(lastReceivedPlaylist, lastReceivedPlaylistPositionMs, lastReceivedPlaylistIndex)
                        _playbackCommands.value = cmd
                        lastEmittedPlayCommand = cmd
                        lastEmittedPlayTime = now
                        lastPlaylistEmittedHash = playlistHash
                        lastRemoteItemId = lastReceivedPlaylist.getOrNull(lastReceivedPlaylistIndex)
                    }
                } else {
                    // Already playing - don't emit more Play commands, server controls via WebSocket
                    Timber.d("📡 Group already Playing - skipping redundant Play emission (server sends commands via WebSocket)")
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Error checking remote playback")
        }
    }

    private suspend fun queryRemotePlaybackItem(groupId: UUID, attempt: Int = 0) {
        try {
            val accessToken = serverRepository.current.value?.user?.accessToken
            val baseUrl = currentBaseUrl()
            
            // Note: /SyncPlay/List endpoint doesn't return playlist data - only group state
            // Playlist data comes from WebSocket SyncPlayGroupUpdate messages
            // Skip REST query if we have recent playlist from WebSocket
            if (lastReceivedPlaylist.isNotEmpty()) {
                Timber.d("📡 Using cached playlist from WebSocket: %d items, index=%d, position=%dms", lastReceivedPlaylist.size, lastReceivedPlaylistIndex, lastReceivedPlaylistPositionMs)
                // Emit Play command with cached playlist data if state is Playing
                if (currentGroupId.value == groupId && lastGroupState == "Playing") {
                    Timber.i("📡 Group Playing with cached playlist: %d items", lastReceivedPlaylist.size)
                    if (lastReceivedPlaylist.isNotEmpty()) {
                        _playbackCommands.value = SyncPlayCommand.Play(lastReceivedPlaylist, lastReceivedPlaylistPositionMs, lastReceivedPlaylistIndex)
                        lastRemoteItemId = lastReceivedPlaylist.getOrNull(lastReceivedPlaylistIndex)
                    }
                }
                return
            }
            
            if (accessToken != null && baseUrl != null) {
                // Query the SyncPlay group via REST endpoint to get full details including playlist
                // Note: The SDK's GroupInfoDto doesn't include playlist data, so we must query directly
                val url = "$baseUrl/SyncPlay/List?api_key=$accessToken"
                Timber.i("📡🔎 Querying playback state from: %s (targetGroupId=%s)", url, groupId)
                val request = Request.Builder().url(url).build()
                
                okHttpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string()
                        if (body != null) {
                            Timber.d("📡 Response body size: %d bytes", body.length)
                            Timber.i("📡 FULL Response JSON: %s", body)
                            val groupsArray = json.parseToJsonElement(body).jsonArray
                            
                            var matched = false
                            for (groupElement in groupsArray) {
                                val groupObj = groupElement.jsonObject
                                val gidStr = groupObj["GroupId"]?.jsonPrimitive?.content
                                val gidUuid = gidStr?.let { parseUUID(it) }
                                Timber.d("📡 Inspecting group: raw=%s parsedUuid=%s", gidStr, gidUuid)
                                
                                if (gidUuid == groupId) {
                                    matched = true
                                    // Found the group - extract playing item details
                                    val playlist = groupObj["Playlist"]?.jsonArray ?: emptyList()
                                    val playingIndex = groupObj["PlayingItemIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
                                    val playingPositionTicks = groupObj["PlayingItemPositionTicks"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                                    val playingItemIdStr = groupObj["PlayingItemId"]?.jsonPrimitive?.content
                                    
                                    Timber.i("📡 Playlist state: index=%d, size=%d, position=%d ticks, PlayingItemId=%s", playingIndex, playlist.size, playingPositionTicks, playingItemIdStr)
                                    
                                    if (playingIndex >= 0 && playingIndex < playlist.size) {
                                        val itemObj = playlist[playingIndex].jsonObject
                                        val itemIdStr = itemObj["ItemId"]?.jsonPrimitive?.content
                                        val itemUuid = itemIdStr?.let { parseUUID(it) }
                                        
                                        if (itemUuid != null && itemUuid != lastRemoteItemId) {
                                            lastRemoteItemId = itemUuid
                                            val positionMs = playingPositionTicks / 10000
                                            Timber.i("📡🎯 Detected remote playback: itemId=%s, position=%dms", itemUuid, positionMs)
                                            _playbackCommands.value = SyncPlayCommand.Play(listOf(itemUuid), positionMs, playingIndex)
                                            Timber.i("📡✅ Emitted SyncPlayCommand.Play for %s at %dms (index=%d)", itemUuid, positionMs, playingIndex)
                                        } else {
                                            Timber.d("📡 Item already playing or no change: %s", itemUuid)
                                        }
                                        _isGroupPlaying.value = true
                                    } else {
                                        Timber.w("📡 Invalid playlist index: %d (size=%d)", playingIndex, playlist.size)
                                        // Fallback: if server provided PlayingItemId but no playlist entries, try that once or twice
                                        val fallbackUuid = playingItemIdStr?.let { parseUUID(it) }
                                        if (fallbackUuid != null && fallbackUuid != lastRemoteItemId) {
                                            val positionMs = playingPositionTicks / 10000
                                            lastRemoteItemId = fallbackUuid
                                            Timber.i("📡🎯 Fallback remote playback: itemId=%s, position=%dms (no playlist)", fallbackUuid, positionMs)
                                            _playbackCommands.value = SyncPlayCommand.Play(listOf(fallbackUuid), positionMs, 0)
                                            Timber.i("📡✅ Emitted SyncPlayCommand.Play via fallback for %s at %dms", fallbackUuid, positionMs)
                                            _isGroupPlaying.value = true
                                        } else if (attempt < 3) {
                                            Timber.w("📡⚠️ Playlist empty/invalid. Retrying fetch (attempt %d)...", attempt + 1)
                                            delay(500)
                                            queryRemotePlaybackItem(groupId, attempt + 1)
                                            _isGroupPlaying.value = false
                                        } else {
                                            Timber.w("📡⚠️ Playlist still empty after retries. Falling back to /Sessions to locate active playback.")
                                            fallbackToSessionsPlayback()
                                            _isGroupPlaying.value = false
                                        }
                                    }
                                    break
                                }
                            }
                            if (!matched) {
                                Timber.w("📡⚠️ No group matched targetGroupId=%s in response", groupId)
                            }
                        }
                    } else {
                        Timber.w("📡 Failed to query SyncPlay group: HTTP %d", response.code)
                    }
                }
            } else {
                Timber.w("📡 Missing server credentials or base URL")
            }
        } catch (e: Exception) {
            Timber.e(e, "📡 Error querying remote playback item")
        }
    }

    /**
     * Fallback when SyncPlay/List does not include playlist data.
     * Query /Sessions and start playback based on any participant's NowPlayingItem.
     */
    private suspend fun fallbackToSessionsPlayback() {
        val accessToken = serverRepository.current.value?.user?.accessToken
        val baseUrl = currentBaseUrl()
        if (accessToken.isNullOrBlank() || baseUrl.isNullOrBlank()) {
            Timber.w("📡⚠️ Cannot query /Sessions: missing access token or base URL")
            return
        }

        val url = "$baseUrl/Sessions?api_key=$accessToken"
        Timber.i("📡🔎 Fallback querying sessions: %s", url)
        val request = Request.Builder().url(url).build()

        runCatching {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Timber.w("📡⚠️ /Sessions request failed: HTTP %d - %s", response.code, response.message)
                    return
                }

                val body = response.body?.string() ?: return
                val sessions = json.parseToJsonElement(body).jsonArray

                val participants = _groupMembers.value.toSet()
                val deviceId = deviceInfo.id

                val candidates = sessions.mapNotNull { sessionElement ->
                    val session = sessionElement.jsonObject
                    val userName = session["UserName"]?.jsonPrimitive?.content
                    val sessionDeviceId = session["DeviceId"]?.jsonPrimitive?.content
                    val nowPlaying = session["NowPlayingItem"]?.jsonObject
                    val playState = session["PlayState"]?.jsonObject

                    val itemIdStr = nowPlaying?.get("Id")?.jsonPrimitive?.content
                    val positionTicks = playState?.get("PositionTicks")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

                    if (itemIdStr.isNullOrBlank() || userName.isNullOrBlank()) return@mapNotNull null
                    if (!sessionDeviceId.isNullOrBlank() && sessionDeviceId == deviceId) return@mapNotNull null

                    val itemUuid = parseUUID(itemIdStr) ?: return@mapNotNull null
                    Triple(userName, itemUuid, positionTicks)
                }

                if (candidates.isEmpty()) {
                    Timber.w("📡⚠️ /Sessions fallback found no active NowPlaying sessions")
                    return
                }

                val preferred =
                    candidates.firstOrNull { (userName, _, _) -> participants.isNotEmpty() && userName in participants }
                        ?: candidates.first()

                val (userName, itemUuid, positionTicks) = preferred
                if (itemUuid != lastRemoteItemId) {
                    val positionMs = positionTicks / 10000
                    Timber.i("📡🎯 Fallback session playback: user=%s itemId=%s position=%dms", userName, itemUuid, positionMs)
                    lastRemoteItemId = itemUuid
                    _playbackCommands.value = SyncPlayCommand.Play(listOf(itemUuid), positionMs, 0)
                    _isGroupPlaying.value = true
                }
            }
        }.onFailure { e ->
            Timber.e(e, "📡⚠️ Error querying /Sessions for fallback playback")
        }
    }

    private fun handleSocketMessage(payload: String) {
        Timber.i("WebSocket_handleMessage: Payload %d bytes - first 400 chars: %s", payload.length, payload.take(400))
        runCatching {
            val root = json.parseToJsonElement(payload).jsonObject
            val messageId = root["MessageId"]?.jsonPrimitive?.content
            val type = root["MessageType"]?.jsonPrimitive?.content
            val dataElement = root["Data"]
            
            Timber.i("WebSocket_handleMessage: messageId=%s, type=%s, hasData=%s", messageId, type, dataElement != null)
            
            // Data might be a JSON object or could be other types
            val data = if (dataElement is kotlinx.serialization.json.JsonObject) dataElement else null
            
            if (type != null) {
                // Standard message with MessageType field
                Timber.i("WebSocket_handleMessage: Processing standard message type=%s", type)
                when (type) {
                    "SyncPlayGroupUpdate" -> {
                        if (data != null) handleGroupUpdate(data)
                        else Timber.w("SyncPlayGroupUpdate has non-object data")
                    }
                    "SyncPlayGroupJoined" -> {
                        if (data != null) handleGroupUpdate(data)
                        else Timber.w("SyncPlayGroupJoined has non-object data")
                    }
                    "SyncPlayGroupLeft" -> {
                        _currentGroupId.value = null
                        _groupMembers.value = emptyList()
                        _isSyncPlayActive.value = false
                        _syncPlayMessages.value = SyncPlayMessage.GroupLeft("Server left")
                    }
                    "UserJoinedGroup" -> {
                        val userName = data?.get("UserName")?.jsonPrimitive?.content
                            ?: data?.get("Username")?.jsonPrimitive?.content
                        if (userName != null) {
                            Timber.i("👤 User joined group: %s", userName)
                            _syncPlayMessages.value = SyncPlayMessage.UserJoined(userName)
                        }
                    }
                    "UserLeftGroup" -> {
                        val userName = data?.get("UserName")?.jsonPrimitive?.content
                            ?: data?.get("Username")?.jsonPrimitive?.content
                        if (userName != null) {
                            Timber.i("👤 User left group: %s", userName)
                            _syncPlayMessages.value = SyncPlayMessage.UserLeft(userName)
                        }
                    }
                    "SyncPlayLibraryAccessDeniedUpdate" -> {
                        Timber.e("🚫 Library access denied - user lacks permission to view group content")
                        _syncPlayMessages.value = SyncPlayMessage.Error("Library access denied")
                        // Auto-leave group since we can't access content
                        leaveGroup()
                    }
                    "SyncPlayGroupDoesNotExistUpdate" -> {
                        Timber.e("🚫 Group does not exist - may have been deleted")
                        _syncPlayMessages.value = SyncPlayMessage.Error("Group no longer exists")
                        _currentGroupId.value = null
                        _groupMembers.value = emptyList()
                        _isSyncPlayActive.value = false
                    }
                    "ForceKeepAlive" -> {
                        Timber.d("💓 Received ForceKeepAlive ping from server")
                        // No action needed - just acknowledges connection is alive
                    }
                    "SyncPlayCommand" -> {
                        Timber.i("📥 Received SyncPlayCommand from server")
                        if (data != null) {
                            handlePlaybackCommand(data)
                        } else {
                            Timber.w("⚠️ SyncPlayCommand has no data")
                        }
                    }
                    "PlayQueueUpdate" -> {
                        Timber.i("📥 Received PlayQueueUpdate from server")
                        if (data != null) {
                            handlePlayQueueUpdate(data)
                        } else {
                            Timber.w("⚠️ PlayQueueUpdate has no data")
                        }
                    }
                    "SyncPlayPlayQueueUpdate" -> {
                        Timber.i("📥 Received SyncPlayPlayQueueUpdate from server")
                        if (data != null) {
                            // Data contains GroupId/Type/Data wrapper (PlayQueueUpdate nested in Data)
                            val playQueueData =
                                data["Data"]?.jsonObject
                                    ?: data["PlayQueueUpdate"]?.jsonObject
                                    ?: data
                            handlePlayQueueUpdate(playQueueData)
                        } else {
                            Timber.w("⚠️ SyncPlayPlayQueueUpdate has no data")
                        }
                    }
                    else -> Timber.d("Unhandled SyncPlay message type: %s", type)
                }
            } else if (data != null) {
                // Nested message structure: check if Data contains nested SyncPlay data
                Timber.i("WebSocket_handleMessage: No type, checking nested Data structure")
                val nestedData = data["Data"]?.jsonObject
                Timber.i("WebSocket_handleMessage: nestedData=%s, hasPossiblePlayQueueFields=%s", 
                    nestedData != null,
                    nestedData?.let { (it["Reason"] != null || it["Playlist"] != null) } ?: false)
                
                if (nestedData != null && (nestedData["Reason"]?.jsonPrimitive?.content != null || 
                    nestedData["Playlist"]?.jsonArray != null)) {
                    // This is a PlayQueueUpdate in nested format
                    val reason = nestedData["Reason"]?.jsonPrimitive?.content
                    Timber.i("WebSocket_handleMessage: DETECTED nested PlayQueueUpdate (Reason=%s, playlistSize=%s)", 
                        reason, nestedData["Playlist"]?.jsonArray?.size ?: 0)
                    handlePlayQueueUpdate(nestedData)
                } else if (data["GroupId"] != null) {
                    // This might be a group update in nested format
                    Timber.i("WebSocket_handleMessage: Detected nested GroupUpdate")
                    handleGroupUpdate(data)
                } else {
                    Timber.d("WebSocket_handleMessage: Unknown nested message structure")
                }
            } else {
                Timber.d("WebSocket_handleMessage: No type and no data, ignoring message")
            }
        }.onFailure { e -> 
            Timber.e(e, "WebSocket_handleMessage: Failed to parse message")
        }
    }

    private fun handleGroupUpdate(data: JsonObject?) {
        if (data == null) return

        Timber.i("🎵 handleGroupUpdate called with data keys: ${data.keys}")
        
        // Check for nested PlayQueue update (common in group updates)
        // The server sends PlayQueue in data["Data"] with format:
        // {"Reason":"SetCurrentItem", "Playlist":[...], "PlayingItemIndex":0, ...}
        // BUT sometimes "Data" is just a string value like "Playing" or "Paused"
        val nestedData = data["Data"]
        if (nestedData != null) {
            if (nestedData is JsonObject) {
                Timber.i("🎵 Found nested Data field (object), checking for Playlist...")
                if (nestedData["Playlist"] != null) {
                    Timber.i("🎵 Detected PlayQueue data in group update - processing playlist!")
                    handlePlayQueueUpdate(nestedData)
                } else {
                    Timber.i("🎵 Nested Data found but no Playlist field")
                }
            } else {
                Timber.d("🎵 Nested Data field is not an object (it's ${nestedData::class.simpleName}), skipping")
            }
        } else {
            Timber.d("🎵 No nested Data field found")
        }

        val group = data["Group"]?.jsonObject ?: data
        Timber.i("🎵 Applying group update - keys: ${group.keys}")
        applyGroupUpdate(group)
    }

    private fun handlePlaybackCommand(data: JsonObject) {
        Timber.i("🎬 handlePlaybackCommand CALLED with data: %s", data.toString().take(500))
        val command = data["Command"]?.jsonPrimitive?.content
        val positionTicks = data["PositionTicks"]?.jsonPrimitive?.content?.toLongOrNull()
        val positionMs = positionTicks?.let { it / 10000 } // Convert ticks to milliseconds
        val playbackRate = data["PlaybackRate"]?.jsonPrimitive?.content?.toFloatOrNull()
        val whenValue = data["When"]?.jsonPrimitive?.content
        val emittedAt = data["EmittedAt"]?.jsonPrimitive?.content

        Timber.i("🎬 SyncPlay command: %s position=%s ms rate=%s when=%s emittedAt=%s", command, positionMs, playbackRate, whenValue, emittedAt)

        when (command) {
            "Pause" -> {
                Timber.i("🎬🔴 PAUSE COMMAND RECEIVED: position=%s ms", positionMs)
                positionMs?.let {
                    val cmd = SyncPlayCommand.Pause(it)
                    if (!isDuplicateCommand(cmd)) {
                        Timber.i("📥 Emitting Pause command to playbackCommands flow")
                        _playbackCommands.value = cmd
                        Log.d("SyncPlayManager", "Received Pause command at $it ms")
                    } else {
                        Timber.w("⚠️ Skipping duplicate Pause command")
                    }
                }
            }
            "Unpause", "Play" -> {
                Timber.i("🎬🟢 UNPAUSE/PLAY COMMAND RECEIVED: position=%s ms", positionMs)
                positionMs?.let {
                    val cmd = SyncPlayCommand.Unpause(it)
                    if (!isDuplicateCommand(cmd)) {
                        Timber.i("📥 Emitting Unpause command to playbackCommands flow")
                        _playbackCommands.value = cmd
                        Log.d("SyncPlayManager", "Received Unpause command at $it ms")
                    } else {
                        Timber.w("⚠️ Skipping duplicate Unpause command")
                    }
                }
            }
            "Seek" -> {
                Timber.i("🎬⏩ SEEK COMMAND RECEIVED: position=%s ms", positionMs)
                positionMs?.let {
                    val cmd = SyncPlayCommand.Seek(it)
                    if (!isDuplicateCommand(cmd)) {
                        Timber.i("📥 Emitting Seek command to playbackCommands flow")
                        _playbackCommands.value = cmd
                        Log.d("SyncPlayManager", "Received Seek command to $it ms")
                    } else {
                        Timber.w("⚠️ Skipping duplicate Seek command")
                    }
                }
            }
            "SetPlaybackRate" -> {
                Timber.i("🎬⚡ PLAYBACK_RATE COMMAND RECEIVED: rate=%s", playbackRate)
                playbackRate?.let {
                    Timber.i("📥 Emitting SetPlaybackRate command to playbackCommands flow")
                    _playbackCommands.value = SyncPlayCommand.SetPlaybackRate(it)
                    Log.d("SyncPlayManager", "Received SetPlaybackRate command: $it")
                }
            }
            "Stop" -> {
                Timber.i("🎬⏹️ STOP COMMAND RECEIVED - exiting playback")
                _playbackCommands.value = SyncPlayCommand.Stop()
                Log.d("SyncPlayManager", "Received Stop command from server")
            }
            else -> Timber.w("❓ Unknown SyncPlay command: %s", command)
        }
    }

    private fun handlePlayQueueUpdate(data: JsonObject) {
        val reason = data["Reason"]?.jsonPrimitive?.content
        Timber.i("🎵 PlayQueue update reason: %s, full data keys: %s", reason, data.keys)

        // Extract playlist items when present
        val playlist = data["Playlist"]?.jsonArray ?: data["PlayingQueue"]?.jsonArray
        if (playlist == null) {
            Timber.d("PlayQueue update has no playlist (reason=%s)", reason)
            return
        }

        Timber.i("🎵 Playlist extracted: %s items", playlist.size)

        // Try multiple field names for compatibility
        val playingItemPosition = data["PlayingItemPosition"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: data["PlayingItemIndex"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: data["playingItemIndex"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: 0
        val startPositionTicks = data["StartPositionTicks"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: data["startPositionTicks"]?.jsonPrimitive?.content?.toLongOrNull()
            ?: 0L
        val startPositionMs = startPositionTicks / 10000L // Convert ticks to milliseconds

        Timber.i(
            "🎵 Extracted: playingItemPosition=%d, startPositionTicks=%d, startPositionMs=%d",
            playingItemPosition,
            startPositionTicks,
            startPositionMs,
        )

        val itemIds = playlist.mapNotNull { element ->
            var itemId: String? = null
            var playlistItemId: String? = null

            val obj = element as? JsonObject
            if (obj != null) {
                itemId = obj["ItemId"]?.jsonPrimitive?.content
                    ?: obj["Id"]?.jsonPrimitive?.content
                playlistItemId = obj["PlaylistItemId"]?.jsonPrimitive?.content
                    ?: obj["PlaylistId"]?.jsonPrimitive?.content
            } else {
                val prim = element as? JsonPrimitive
                itemId = prim?.content
            }

            Timber.d("🎵 Queue item: ItemId=%s PlaylistItemId=%s", itemId, playlistItemId)

            val itemUuid = itemId?.let { parseUUID(it) }
            if (itemUuid != null && !playlistItemId.isNullOrBlank()) {
                playlistItemIdByItemId[itemUuid] = playlistItemId.replace("-", "")
            }
            itemUuid
        }

        if (itemIds.isNotEmpty()) {
            // Cache the playlist for future use
            lastReceivedPlaylist = itemIds
            lastReceivedPlaylistIndex = playingItemPosition
            lastReceivedPlaylistPositionMs = startPositionMs

            Timber.i(
                "🎬 PlayQueue reason=%s: %d items, start at index %d, position %d ms",
                reason,
                itemIds.size,
                playingItemPosition,
                startPositionMs,
            )
            Timber.i("🎬 First item ID: %s", itemIds.firstOrNull())
            _playbackCommands.value = SyncPlayCommand.Play(itemIds, startPositionMs, playingItemPosition)
            Timber.i("🎬✅ Emitted SyncPlayCommand.Play with %d items starting at index %d", itemIds.size, playingItemPosition)
        } else {
            Timber.w("🎵 PlayQueue update but no items extracted")
        }
    }

    private fun applyGroupUpdate(group: JsonObject) {
        val idString =
            group["GroupId"]?.jsonPrimitive?.content
                ?: group["Id"]?.jsonPrimitive?.content
        val groupId = idString?.let { parseUUID(it) } ?: return

        val membersJson =
            group["Members"] ?: group["Users"] ?: group["Clients"]
        val members =
            membersJson
                ?.jsonArray
                ?.mapNotNull { element ->
                    val userName = element.jsonObject["UserName"]?.jsonPrimitive?.content
                        ?: element.jsonObject["Name"]?.jsonPrimitive?.content
                    
                    // Track ping for network latency awareness
                    element.jsonObject["Ping"]?.jsonPrimitive?.content?.toLongOrNull()?.let { ping ->
                        if (lastPingMs != ping) {
                            lastPingMs = ping
                            _estimatedLatency.value = ping
                            Timber.d("📡 Updated network latency: ${ping}ms (drift tolerance: ${getDriftTolerance()}ms)")
                        }
                    }
                    
                    userName
                }
                ?.distinct()
                ?: emptyList()

        _currentGroupId.value = groupId
        _groupMembers.value = members
        _isSyncPlayActive.value = true
        if (lastNotifiedGroupId != groupId) {
            _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, members)
            lastNotifiedGroupId = groupId
        }
    }

    private fun applyGroupDto(group: GroupInfoDto) {
        val groupId = group.groupId.toJavaUuid()
        val currentUserName = serverRepository.current.value?.user?.name
        val oldParticipants = _groupMembers.value
        val newParticipants = group.participants
        
        Timber.i("📊 applyGroupDto: old=${oldParticipants.size} participants (${oldParticipants.take(3).joinToString(",")}), new=${newParticipants.size} participants (${newParticipants.take(3).joinToString(",")})")
        
        _currentGroupId.value = groupId
        _groupMembers.value = newParticipants
        _isSyncPlayActive.value = true
        
        // Notify when local user joins a new group
        if (lastNotifiedGroupId != groupId) {
            _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, newParticipants)
            lastNotifiedGroupId = groupId
        }
        
        // Detect users who joined (excluding the local user)
        if (oldParticipants.isNotEmpty()) {
            val joinedUsers = newParticipants.filterNot { it in oldParticipants }
            joinedUsers.forEach { userName ->
                if (userName != currentUserName) {
                    Timber.i("👋 UserJoined event emitted: $userName")
                    _syncPlayMessages.value = SyncPlayMessage.UserJoined(userName)
                }
            }
            
            // Detect users who left
            val leftUsers = oldParticipants.filterNot { it in newParticipants }
            leftUsers.forEach { userName ->
                if (userName != currentUserName) {
                    Timber.i("👋 UserLeft event emitted: $userName (not current user)")
                    _syncPlayMessages.value = SyncPlayMessage.UserLeft(userName)
                } else {
                    Timber.w("👋 Current user in leftUsers list - ignoring")
                }
            }
        } else {
            Timber.d("📊 oldParticipants empty - skipping join/leave detection")
        }
    }

    private fun startPollingGroups() {
        if (disablePolling) return
        if (refreshGroupsJob?.isActive == true) return
        refreshGroupsJob?.cancel()
        refreshGroupsJob = coroutineScope.launch {
            while (isActive) {
                try {
                    refreshGroups()
                    delay(10_000) // Poll every 10 seconds
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // Job was cancelled, exit gracefully
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Error in polling loop")
                    delay(10_000) // Wait before retrying on error
                }
            }
        }
    }
}

