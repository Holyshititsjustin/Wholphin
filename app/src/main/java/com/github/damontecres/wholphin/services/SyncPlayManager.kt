package com.github.damontecres.wholphin.services

import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import dagger.hilt.android.scopes.ActivityScoped
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    data class GroupJoined(val groupId: UUID, val members: List<String>) : SyncPlayMessage()
    data class StateUpdate(val positionMs: Long, val isPlaying: Boolean) : SyncPlayMessage()
    data class UserJoined(val userName: String) : SyncPlayMessage()
    data class UserLeft(val userName: String) : SyncPlayMessage()
    data class GroupLeft(val reason: String = "User left") : SyncPlayMessage()
    data class CommandSent(val command: String) : SyncPlayMessage()
    data class Error(val error: String) : SyncPlayMessage()
}

sealed class SyncPlayCommand {
    data class Pause(val positionMs: Long) : SyncPlayCommand()
    data class Unpause(val positionMs: Long) : SyncPlayCommand()
    data class Seek(val positionMs: Long) : SyncPlayCommand()
    data class SetPlaybackRate(val rate: Float) : SyncPlayCommand()
    data class Play(val itemIds: List<UUID>, val startPositionMs: Long, val startIndex: Int) : SyncPlayCommand()
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

    // Position reporting for synced playback
    private val _reportingPlaybackPosition = MutableStateFlow(false)
    val reportingPlaybackPosition: StateFlow<Boolean> = _reportingPlaybackPosition.asStateFlow()
    private var positionReportingJob: Job? = null

    // Playback commands from server
    private val _playbackCommands = MutableStateFlow<SyncPlayCommand?>(null)
    val playbackCommands: StateFlow<SyncPlayCommand?> = _playbackCommands.asStateFlow()

    private var subscriptionJob: Job? = null
    private var driftCheckJob: Job? = null
    private var lastKnownPosition = 0L
    private var lastUpdateTime = System.currentTimeMillis()
    private var webSocket: WebSocket? = null
    private var webSocketConnected = false  // Track actual connection state
    private var refreshGroupsJob: Job? = null
    private var sessionsPollingJob: Job? = null
    private var disablePolling = false

    private val json = Json { ignoreUnknownKeys = true }

    // Track last remote item to avoid spamming play commands
    private var lastRemoteItemId: UUID? = null

    fun setLocalPlaybackItemId(itemId: UUID?) {
        if (itemId == null) return
        if (lastRemoteItemId != itemId) {
            Timber.i("🎬 Setting local SyncPlay item id: %s", itemId)
        }
        lastRemoteItemId = itemId
    }

    init {
        Timber.i("🔄 SyncPlayManager initialized - will keep WebSocket open for group discovery")
        // Keep WebSocket open to receive group notifications from server
        coroutineScope.launch {
            while (true) {
                val hasServer = serverRepository.current.value?.server != null && 
                                 serverRepository.current.value?.user != null
                Timber.d("🔗 WebSocket check: hasServer=%s, webSocket=%s, connected=%s", hasServer, if (webSocket == null) "null" else "exists", webSocketConnected)
                if (hasServer && (webSocket == null || !webSocketConnected)) {
                    Timber.i("🔗 Server connected but WebSocket not ready - opening WebSocket for SyncPlay notifications")
                    startListening()
                } else if (!hasServer) {
                    Timber.d("🔗 No server connected yet, waiting...")
                }
                kotlinx.coroutines.delay(3000) // Check every 3 seconds
            }
        }
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
                    val position = getPosition()
                    val groupId = currentGroupId.value ?: return@launch

                    // Report playback position to server via /SyncPlay/Ready endpoint
                    val positionTicks = position * 10000L // Convert ms to ticks (100ns units)
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    val playlistItemId = lastRemoteItemId
                    if (accessToken != null && baseUrl != null && playlistItemId != null) {
                        val url = "$baseUrl/SyncPlay/Ready?api_key=$accessToken"
                        val playlistItemIdString = formatSyncPlayId(playlistItemId)
                        val whenIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
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
                joinGroupRemote(groupId)
                _currentGroupId.value = groupId
                _isSyncPlayActive.value = true
                _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, emptyList())
                Timber.d("Joined group via Jellyfin API: $groupId")
                Timber.i("CALLING startListening() now...")
                startListening()
                Timber.i("CALLING startSessionsPolling() now...")
                startSessionsPolling()
                
                // RE-ENABLED: Send Ready message after joining
                Timber.i("CALLING sendReadyAfterJoin() now...")
                delay(500) // Allow server to process join first
                sendReadyAfterJoin(groupId)
            } catch (e: Exception) {
                Timber.e(e, "Error joining group")
                _syncPlayMessages.value = SyncPlayMessage.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun createGroup() {
        coroutineScope.launch {
            try {
                val newGroupId = createGroupRemote()
                Timber.d("Created group via Jellyfin API: $newGroupId")
                // Must explicitly join the created group
                joinGroupRemote(newGroupId)
                _currentGroupId.value = newGroupId
                _isSyncPlayActive.value = true
                _syncPlayMessages.value = SyncPlayMessage.GroupJoined(newGroupId, emptyList())
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
                }
                _currentGroupId.value = null
                _isSyncPlayActive.value = false
                _groupMembers.value = emptyList()
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
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                if (groupId != null) {
                    // Send pause command via direct HTTP call
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    if (accessToken != null && baseUrl != null) {
                        val url = "$baseUrl/SyncPlay/Pause?api_key=$accessToken"
                        val request = Request.Builder()
                            .url(url)
                            .post(ByteString.EMPTY.toRequestBody("application/json".toMediaType()))
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Timber.i("✅ Sent pause command to SyncPlay group: $groupId")
                                _syncPlayMessages.value = SyncPlayMessage.CommandSent("Pause")
                            } else {
                                Timber.w("❌ Failed to send pause command to group $groupId: HTTP ${response.code} - ${response.message}")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot send pause command: missing access token or base URL")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Error pausing SyncPlay")
            }
        }
    }

    fun unpause() {
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                if (groupId != null) {
                    // Send unpause command via direct HTTP call
                    val accessToken = serverRepository.current.value?.user?.accessToken
                    val baseUrl = currentBaseUrl()
                    if (accessToken != null && baseUrl != null) {
                        val url = "$baseUrl/SyncPlay/Unpause?api_key=$accessToken"
                        val request = Request.Builder()
                            .url(url)
                            .post(ByteString.EMPTY.toRequestBody("application/json".toMediaType()))
                            .build()

                        okHttpClient.newCall(request).execute().use { response ->
                            if (response.isSuccessful) {
                                Timber.i("✅ Sent unpause command to SyncPlay group: $groupId")
                                _syncPlayMessages.value = SyncPlayMessage.CommandSent("Resume")
                            } else {
                                Timber.w("❌ Failed to send unpause command to group $groupId: HTTP ${response.code} - ${response.message}")
                            }
                        }
                    } else {
                        Timber.w("❌ Cannot send unpause command: missing access token or base URL")
                    }
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
                    // When joining, we're at position 0 and not playing yet (waiting for server state)
                    val requestBodyJson = """
                        {
                            "requestData": {
                                "When": "$whenIso",
                                "PositionTicks": 0,
                                "IsPlaying": false,
                                "PlaylistItemId": ""
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
                    val playlistItemIdString = formatSyncPlayId(itemId)
                    val whenIso = Instant.ofEpochMilli(System.currentTimeMillis()).toString()
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
        Timber.i("startListening: Called - initializing WebSocket connection")
        webSocket?.cancel()
        webSocketConnected = false  // Mark as disconnected when closing
        
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
        webSocket =
            okHttpClient.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        webSocketConnected = true  // Mark as connected
                        Timber.i("WebSocket_onOpen: Connection opened successfully!")
                        
                        // Note: No explicit subscription messages needed - Jellyfin broadcasts automatically
                        // when client is in a SyncPlay group. Server will send SyncPlayGroupUpdate and SyncPlayCommand messages.
                        Timber.i("WebSocket_onOpen: WebSocket is open and ready. Waiting for SyncPlay messages from server...")
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        Timber.i("WebSocket_onMessage_Text: Received message: %s", text)
                        handleSocketMessage(text)
                    }

                    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                        val textMessage = bytes.utf8()
                        Timber.i("WebSocket_onMessage_Bytes: Received message: %s", textMessage)
                        handleSocketMessage(textMessage)
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        webSocketConnected = false  // Mark as disconnected
                        Timber.e(t, "WebSocket_onFailure: Connection failed - will attempt reconnect")
                        _syncPlayMessages.value = SyncPlayMessage.Error("Connection lost")
                        refreshGroupsJob?.cancel()
                        // Set webSocket to null so init loop will reconnect
                        this@SyncPlayManager.webSocket = null
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        webSocketConnected = false  // Mark as disconnected
                        Timber.w("WebSocket_onClosed: Closed with code=%d reason=%s - will attempt reconnect", code, reason)
                        // Set webSocket to null so init loop will reconnect
                        this@SyncPlayManager.webSocket = null
                    }
                    
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        Timber.w("WebSocket_onClosing: Closing with code=%d reason=%s", code, reason)
                        webSocket.close(1000, null)  // Acknowledge close
                    }
                },
            )
        Timber.i("startListening: WebSocket connection request queued")
    }

    private fun sendWebSocketMessage(message: String) {
        coroutineScope.launch {
            if (webSocket != null && webSocketConnected) {
                webSocket?.send(message)
                Timber.i("📤 Sent WebSocket message: %s", message.take(200))
            } else {
                Timber.w("📤 Cannot send WebSocket message - WebSocket not connected")
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
                    
                    if (!syncActive) {
                        Timber.i("🔄⏹️ isSyncPlayActive is false, exiting polling loop")
                        break
                    }
                    
                    try {
                        // Use SDK to get groups and check for playback changes
                        val response = api.syncPlayApi.syncPlayGetGroups()
                        val groups = response.content
                        Timber.d("🔄🔢 SDK returned %d groups", groups.size)
                        
                        if (groupId != null) {
                            val matchingGroup = groups.find { it.groupId.toJavaUuid() == groupId }
                            if (matchingGroup != null) {
                                checkForRemotePlayback(matchingGroup)
                            } else {
                                Timber.w("🔄❌ Matching group not found in SDK response")
                            }
                        } else {
                            Timber.w("🔄⚠️ groupId is null")
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
    
    // Track last playlist received from WebSocket so we don't query REST for empty playlists
    private var lastReceivedPlaylist: List<UUID> = emptyList()
    private var lastReceivedPlaylistIndex: Int = 0
    private var lastReceivedPlaylistPositionMs: Long = 0L

    private fun checkForRemotePlayback(group: GroupInfoDto) {
        try {
            val currentState = group.state?.toString()
            Timber.d("🔄📊 Group state - current=%s, last=%s, state_enum=%s", currentState, lastGroupState, group.state)

            if (currentState != null && currentState != lastGroupState) {
                Timber.i("📡 Group state changed from %s to %s", lastGroupState, currentState)
                lastGroupState = currentState
                
                // Emit pause/unpause commands based on state change
                when (currentState) {
                    "Paused" -> {
                        Timber.i("📡🔴 Detected group state PAUSED - emitting Pause command")
                        // When group is paused, pause the local player
                        // Use lastKnownPosition to resume from where we paused, not position 0
                        _playbackCommands.value = SyncPlayCommand.Pause(lastKnownPosition)
                    }
                    "Playing" -> {
                        Timber.i("📡🟢 Detected group state PLAYING - emitting Unpause command")
                        // When group is playing, unpause the local player
                        // Use lastKnownPosition to resume from where we were
                        _playbackCommands.value = SyncPlayCommand.Unpause(lastKnownPosition)
                    }
                    "Waiting" -> {
                        _isGroupPlaying.value = false
                    }
                    else -> Timber.d("📡 Unknown group state: %s", currentState)
                }
            }

            // Always query playlist details for the current group; lastRemoteItemId prevents replays
            coroutineScope.launch {
                try {
                    queryRemotePlaybackItem(group.groupId.toJavaUuid())
                } catch (e: Exception) {
                    Timber.w(e, "Error querying remote playback item")
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
                    "SyncPlayCommand" -> {
                        if (data != null) {
                            handlePlaybackCommand(data)
                        }
                    }
                    "PlayQueueUpdate" -> {
                        if (data != null) {
                            handlePlayQueueUpdate(data)
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

        // Check for nested PlayQueue update (common in group updates)
        // The server sends PlayQueue in data["Data"] with format:
        // {"Reason":"SetCurrentItem", "Playlist":[...], "PlayingItemIndex":0, ...}
        val nestedPlayQueueData = data["Data"]?.jsonObject
        if (nestedPlayQueueData != null && nestedPlayQueueData["Playlist"] != null) {
            Timber.i("🎵 Detected PlayQueue data in group update")
            handlePlayQueueUpdate(nestedPlayQueueData)
            // Continue to update group info below
        }

        val group = data["Group"]?.jsonObject ?: data
        applyGroupUpdate(group)
    }

    private fun handlePlaybackCommand(data: JsonObject) {
        Timber.i("🎬 handlePlaybackCommand CALLED with data: %s", data.toString().take(500))
        val command = data["Command"]?.jsonPrimitive?.content
        val positionTicks = data["PositionTicks"]?.jsonPrimitive?.content?.toLongOrNull()
        val positionMs = positionTicks?.let { it / 10000 } // Convert ticks to milliseconds
        val playbackRate = data["PlaybackRate"]?.jsonPrimitive?.content?.toFloatOrNull()

        Timber.i("🎬 SyncPlay command: %s position=%s ms rate=%s", command, positionMs, playbackRate)

        when (command) {
            "Pause" -> {
                Timber.i("🎬🔴 PAUSE COMMAND RECEIVED: position=%s ms", positionMs)
                positionMs?.let {
                    _playbackCommands.value = SyncPlayCommand.Pause(it)
                    Log.d("SyncPlayManager", "Received Pause command at $it ms")
                }
            }
            "Unpause", "Play" -> {
                Timber.i("🎬🟢 UNPAUSE/PLAY COMMAND RECEIVED: position=%s ms", positionMs)
                positionMs?.let {
                    _playbackCommands.value = SyncPlayCommand.Unpause(it)
                    Log.d("SyncPlayManager", "Received Unpause command at $it ms")
                }
            }
            "Seek" -> {
                Timber.i("🎬⏩ SEEK COMMAND RECEIVED: position=%s ms", positionMs)
                positionMs?.let {
                    _playbackCommands.value = SyncPlayCommand.Seek(it)
                    Log.d("SyncPlayManager", "Received Seek command to $it ms")
                }
            }
            "SetPlaybackRate" -> {
                Timber.i("🎬⚡ PLAYBACK_RATE COMMAND RECEIVED: rate=%s", playbackRate)
                playbackRate?.let {
                    _playbackCommands.value = SyncPlayCommand.SetPlaybackRate(it)
                    Log.d("SyncPlayManager", "Received SetPlaybackRate command: $it")
                }
            }
            "Stop" -> {
                Timber.i("🎬⏹️ STOP COMMAND RECEIVED")
                // Stop might not need special handling if other logic handles it
            }
            else -> Timber.w("Unknown SyncPlay command: %s", command)
        }
    }

    private fun handlePlayQueueUpdate(data: JsonObject) {
        val reason = data["Reason"]?.jsonPrimitive?.content
        Timber.i("🎵 PlayQueue update reason: %s", reason)

        when (reason) {
            "NewPlaylist", "SetCurrentItem" -> {
                // Extract playlist items - server sends full playlist on SetCurrentItem
                val playlist = data["Playlist"]?.jsonArray
                Timber.i("🎵 Playlist extracted: %s items", playlist?.size ?: 0)
                
                val playingItemPosition = data["PlayingItemPosition"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val startPositionTicks = data["StartPositionTicks"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
                val startPositionMs = startPositionTicks / 10000L // Convert ticks to milliseconds

                val itemIds = playlist?.mapNotNull { element ->
                    val itemId = element.jsonObject["ItemId"]?.jsonPrimitive?.content
                    itemId?.let { parseUUID(it) }
                } ?: emptyList()

                if (itemIds.isNotEmpty()) {
                    // Cache the playlist for future use
                    lastReceivedPlaylist = itemIds
                    lastReceivedPlaylistIndex = playingItemPosition
                    lastReceivedPlaylistPositionMs = startPositionMs
                    
                    Timber.i("🎬 PlayQueue reason=%s: %d items, start at index %d, position %d ms", reason, itemIds.size, playingItemPosition, startPositionMs)
                    _playbackCommands.value = SyncPlayCommand.Play(itemIds, startPositionMs, playingItemPosition)
                    Timber.i("🎬✅ Emitted SyncPlayCommand.Play with %d items starting at index %d", itemIds.size, playingItemPosition)
                } else {
                    Timber.w("🎵 PlayQueue update but no items extracted")
                }
            }
            else -> Timber.d("PlayQueue update reason not handled: %s", reason)
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
                    element.jsonObject["UserName"]?.jsonPrimitive?.content
                        ?: element.jsonObject["Name"]?.jsonPrimitive?.content
                }
                ?.distinct()
                ?: emptyList()

        _currentGroupId.value = groupId
        _groupMembers.value = members
        _isSyncPlayActive.value = true
        _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, members)
    }

    private fun applyGroupDto(group: GroupInfoDto) {
        val groupId = group.groupId.toJavaUuid()
        _currentGroupId.value = groupId
        _groupMembers.value = group.participants
        _isSyncPlayActive.value = true
        _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, group.participants)
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

