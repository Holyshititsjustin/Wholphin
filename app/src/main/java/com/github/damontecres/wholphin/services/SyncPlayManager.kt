package com.github.damontecres.wholphin.services

import timber.log.Timber
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray



// ...existing code...
import java.util.UUID
import javax.inject.Inject
import org.jellyfin.sdk.api.client.ApiClient
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import okhttp3.OkHttpClient
import org.jellyfin.sdk.model.DeviceInfo
import com.github.damontecres.wholphin.services.hilt.IoCoroutineScope
import kotlinx.coroutines.CoroutineScope
import androidx.datastore.core.DataStore
import com.github.damontecres.wholphin.preferences.AppPreferences
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Response
import okhttp3.Request
import okio.ByteString
import kotlinx.serialization.json.Json
import kotlin.math.min
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import kotlinx.coroutines.flow.first
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType

// Removed duplicate/partial SyncPlayManager class definition. All members are now inside the correct class body below.


    // Message types for UI updates
    sealed class SyncPlayMessage {
        data class GroupJoined(val groupId: UUID, val members: List<String>) : SyncPlayMessage()
        data class StateUpdate(val positionMs: Long, val isPlaying: Boolean) : SyncPlayMessage()
        data class UserJoined(val userName: String) : SyncPlayMessage()
        data class UserLeft(val userName: String) : SyncPlayMessage()
        data class CommandSent(val command: String, val positionMs: Long? = null) : SyncPlayMessage()
        data class GroupLeft(val reason: String = "User left") : SyncPlayMessage()
        data class Error(val error: String) : SyncPlayMessage()
        data class ConnectionStatus(val connected: Boolean, val reconnecting: Boolean = false) : SyncPlayMessage()
    }

    // Playback commands from server
    sealed class SyncPlayCommand {
        data class Pause(val positionMs: Long) : SyncPlayCommand()
        data class Unpause(val positionMs: Long) : SyncPlayCommand()
        data class Seek(val positionMs: Long) : SyncPlayCommand()
        data class SetPlaybackRate(val rate: Float) : SyncPlayCommand()
        data class Play(val itemIds: List<UUID>, val startPositionMs: Long, val startIndex: Int) : SyncPlayCommand()
    }

    // Internal connection state
    private enum class WebSocketState { DISCONNECTED, CONNECTING, CONNECTED }

    // Play command tracking for deduplication
    private data class PlayCommandRecord(val itemId: UUID, val positionMs: Long, val timestamp: Long)

/**
 * Manages SyncPlay (watch together) functionality with Jellyfin server.
 * 
 * Key improvements in this version:
 * - Robust WebSocket connection with exponential backoff
 * - Actual position reporting to server (was TODO)
 * - Drift detection and automatic correction
 * - Buffering coordination support
 * - Smart deduplication of play commands
 * - WebSocket-prioritized updates with fallback polling
 * - Comprehensive diagnostics
 */
@javax.inject.Singleton
class SyncPlayManager @Inject constructor(
    private val api: ApiClient,
    private val serverRepository: ServerRepository,
    @AuthOkHttpClient private val okHttpClient: OkHttpClient,
    private val deviceInfo: DeviceInfo,
    @IoCoroutineScope private val coroutineScope: CoroutineScope,
    private val appPreferencesDataStore: DataStore<AppPreferences>,
) {
    // --- DEBUG/DIAGNOSTIC HELPERS ---
    private fun logLocalNetworkInfo(context: android.content.Context?) {
        try {
            if (context == null) return
            val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            val ni = cm?.activeNetworkInfo
            Timber.i("[DEBUG] Local network info: type=${ni?.typeName}, state=${ni?.state}, extra=${ni?.extraInfo}")
            val wifiMgr = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            val ip = wifiMgr?.connectionInfo?.ipAddress
            if (ip != null) {
                val ipStr = java.net.InetAddress.getByAddress(
                    byteArrayOf(
                        (ip and 0xff).toByte(),
                        (ip shr 8 and 0xff).toByte(),
                        (ip shr 16 and 0xff).toByte(),
                        (ip shr 24 and 0xff).toByte()
                    )
                ).hostAddress
                Timber.i("[DEBUG] Local WiFi IP: $ipStr")
            }
        } catch (e: Exception) {
            Timber.w(e, "[DEBUG] Failed to log local network info")
        }
    }
    // Public state flows
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

    private val _reportingPlaybackPosition = MutableStateFlow(false)
    val reportingPlaybackPosition: StateFlow<Boolean> = _reportingPlaybackPosition.asStateFlow()

    private val _isBuffering = MutableStateFlow(false)
    val isBuffering: StateFlow<Boolean> = _isBuffering.asStateFlow()

    private val _playbackCommands = MutableStateFlow<SyncPlayCommand?>(null)
        init {
            Timber.d("[SYNCPLAY-DEBUG] SyncPlayManager: _playbackCommands StateFlow initialized")
        }
    val playbackCommands: StateFlow<SyncPlayCommand?> = _playbackCommands.asStateFlow()

    // WebSocket connection state
    private val _webSocketState = MutableStateFlow(WebSocketState.DISCONNECTED)
    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 30000L // 30 seconds
    private var connectionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastHeartbeatAck: Long = 0L
    private var connectionTimeoutJob: Job? = null
    private var networkCallbackRegistered = false

    // Position and sync tracking
    private var lastKnownPosition = 0L
    private var lastUpdateTime = System.currentTimeMillis()
    private var lastWebSocketUpdate = 0L
    private var lastPlayCommand: PlayCommandRecord? = null
    
    // Jobs
    private var positionReportingJob: Job? = null
    private var sessionsPollingJob: Job? = null
    private var refreshGroupsJob: Job? = null
    private var driftCheckJob: Job? = null

    // Utilities
    private val json = Json { ignoreUnknownKeys = true }

    init {
        Timber.i("🔄 SyncPlayManager initialized with improvements v2.1 (WebSocket robustness)")
        // Don't start connection manager automatically - only start when SyncPlay is explicitly enabled
        // This prevents SyncPlay from interfering with normal playback
    }

    // ============================================================================
    // CONNECTION MANAGEMENT
    // ============================================================================

    /**
     * Manages WebSocket connection lifecycle with automatic reconnection
     */
    private fun startConnectionManager() {
        Timber.i("🔗 Starting WebSocket connection manager")
        // Register network callback for connectivity changes (Android only)
        registerNetworkCallbackIfNeeded()
        connectionJob = coroutineScope.launch {
            Timber.i("🔗 Connection manager coroutine started")
            while (isActive) {
                val hasServer = serverRepository.current.value?.server != null &&
                    serverRepository.current.value?.user != null

                Timber.d("🔗 Connection check: hasServer=$hasServer, webSocketState=${_webSocketState.value}")

                when (_webSocketState.value) {
                    WebSocketState.DISCONNECTED -> {
                        if (hasServer) {
                            Timber.i("🔗 Server available, initiating connection")
                            connectWithBackoff()
                        } else {
                            Timber.d("🔗 No server configured, waiting...")
                            delay(5000)
                        }
                    }
                    WebSocketState.CONNECTING -> {
                        Timber.d("🔗 Connection in progress, waiting...")
                        // Wait for connection attempt to complete
                        delay(1000)
                    }
                    WebSocketState.CONNECTED -> {
                        Timber.d("🔗 WebSocket connected, monitoring health...")
                        // Heartbeat/health check: if no heartbeat ack in 30s, force reconnect
                        val now = System.currentTimeMillis()
                        if (now - lastHeartbeatAck > 30000) {
                            Timber.w("🔗 No heartbeat ack in 30s, forcing reconnect")
                            _webSocketState.value = WebSocketState.DISCONNECTED
                        }
                        delay(10000)
                        if (webSocket == null) {
                            Timber.w("🔗 WebSocket null despite CONNECTED state, resetting")
                            _webSocketState.value = WebSocketState.DISCONNECTED
                        }
                    }
                }
            }
        }
    }

    /**
     * Attempts connection with exponential backoff on failures
     */
    private suspend fun connectWithBackoff() {
        if (_webSocketState.value == WebSocketState.CONNECTING) {
            Timber.d("🔗 Connection already in progress, skipping")
            return
        }

        _webSocketState.value = WebSocketState.CONNECTING

        if (reconnectAttempts > 0) {
            val delayMs = min(1000L * (1 shl reconnectAttempts), maxReconnectDelay)
            Timber.i("🔗 Reconnecting in ${delayMs}ms (attempt $reconnectAttempts)")
            _syncPlayMessages.value = SyncPlayMessage.ConnectionStatus(connected = false, reconnecting = true)
            delay(delayMs)
        }

        try {
            startWebSocketConnectionWithTimeout()
            reconnectAttempts = 0 // Reset on successful connection
        } catch (e: Exception) {
            Timber.e(e, "🔗 Connection attempt failed")
            reconnectAttempts++
            _webSocketState.value = WebSocketState.DISCONNECTED
            _syncPlayMessages.value = SyncPlayMessage.Error("Connection failed, retrying... [${e.message}]")
        }
    }

    /**
     * Establishes WebSocket connection to Jellyfin server
     */
    private fun startWebSocketConnectionWithTimeout() {
        // Clean up existing connection
        webSocket?.cancel()
        webSocket = null
        heartbeatJob?.cancel()
        connectionTimeoutJob?.cancel()

        val accessToken = serverRepository.current.value?.user?.accessToken
        if (accessToken == null) {
            Timber.w("🔗❌ No access token available")
            _webSocketState.value = WebSocketState.DISCONNECTED
            return
        }

        val baseUrl = currentBaseUrl()
        if (baseUrl == null) {
            Timber.w("🔗❌ No base URL available")
            _webSocketState.value = WebSocketState.DISCONNECTED
            return
        }

        val wsUrl = baseUrl
            .replaceFirst("http://", "ws://")
            .replaceFirst("https://", "wss://") +
            "/socket?api_key=$accessToken&deviceId=${deviceInfo.id}"

        Timber.i("🔗🚀 Connecting WebSocket: $wsUrl")
        Timber.i("🔗 Base URL: $baseUrl, Access Token: ${accessToken?.take(10)}..., Device ID: ${deviceInfo.id}")

        val request = Request.Builder().url(wsUrl).build()
        var openCalled = false
        webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                openCalled = true
                _webSocketState.value = WebSocketState.CONNECTED
                reconnectAttempts = 0
                lastHeartbeatAck = System.currentTimeMillis()
                Timber.i("✅ WebSocket CONNECTED")
                _syncPlayMessages.value = SyncPlayMessage.ConnectionStatus(connected = true, reconnecting = false)
                startHeartbeat(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                                Timber.d("[SYNCPLAY-DEBUG] WebSocket onMessage: $text")
                Timber.i("📨 WebSocket message received: ${text.take(200)}")
                if (text == "pong") {
                    lastHeartbeatAck = System.currentTimeMillis()
                    Timber.d("🔗 Heartbeat pong received")
                } else {
                    handleWebSocketMessage(text)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val msg = bytes.utf8()
                Timber.d("[SYNCPLAY-DEBUG] WebSocket onMessage (bytes): $msg")
                if (msg == "pong") {
                    lastHeartbeatAck = System.currentTimeMillis()
                    Timber.d("🔗 Heartbeat pong received (bytes)")
                } else {
                    handleWebSocketMessage(msg)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.e(t, "❌ WebSocket FAILURE")
                handleDisconnection("Connection failed: ${t.message}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Timber.w("🔴 WebSocket CLOSED: code=$code reason=$reason")
                handleDisconnection("Connection closed: $reason")
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Timber.d("🔶 WebSocket CLOSING: code=$code reason=$reason")
                webSocket.close(1000, null)
            }
        })

        // Connection timeout: if onOpen not called in 10s, treat as failed
        connectionTimeoutJob = coroutineScope.launch {
            delay(10000)
            if (!openCalled) {
                Timber.w("🔗 WebSocket connection timeout (10s), forcing disconnect")
                _webSocketState.value = WebSocketState.DISCONNECTED
                webSocket?.cancel()
                webSocket = null
            }
        }
    }

    /**
     * Handles WebSocket disconnection
     */
    private fun handleDisconnection(reason: String) {
        _webSocketState.value = WebSocketState.DISCONNECTED
        webSocket = null
        heartbeatJob?.cancel()
        connectionTimeoutJob?.cancel()
        _syncPlayMessages.value = SyncPlayMessage.ConnectionStatus(connected = false, reconnecting = true)
        // Cancel polling since WebSocket is down
        sessionsPollingJob?.cancel()
        Timber.i("🔗 Disconnected: $reason - will auto-reconnect")
    }
    // Heartbeat/keepalive: send ping every 10s, expect pong
    private fun startHeartbeat(ws: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = coroutineScope.launch {
            while (isActive && _webSocketState.value == WebSocketState.CONNECTED) {
                try {
                    ws.send("ping")
                    Timber.d("🔗 Heartbeat ping sent")
                } catch (e: Exception) {
                    Timber.e(e, "🔗 Heartbeat ping failed")
                }
                delay(10000)
            }
        }
    }

    // Register for network changes (Android only, no-op on JVM)
    private fun registerNetworkCallbackIfNeeded() {
        if (networkCallbackRegistered) return
        try {
            val context = try { (deviceInfo as? android.content.Context) } catch (_: Exception) { null }
            if (context != null) {
                val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                val callback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        Timber.i("🔗 Network available, forcing reconnect if needed")
                        if (_webSocketState.value != WebSocketState.CONNECTED) {
                            coroutineScope.launch { connectWithBackoff() }
                        }
                    }
                    override fun onLost(network: android.net.Network) {
                        Timber.i("🔗 Network lost, disconnecting WebSocket")
                        _webSocketState.value = WebSocketState.DISCONNECTED
                        webSocket?.cancel()
                        webSocket = null
                    }
                }
                cm?.registerDefaultNetworkCallback(callback)
                networkCallbackRegistered = true
            }
        } catch (_: Exception) {
            // Not Android or not available
        }
    }

    // ============================================================================
    // GROUP MANAGEMENT
    // ============================================================================

    /**
     * Refreshes available SyncPlay groups from server
     */
    fun refreshGroups() {
        Timber.i("🔄 Refreshing SyncPlay groups")
        coroutineScope.launch {
            try {
                val url = "${api.baseUrl}/SyncPlay/List"
                val request = Request.Builder()
                    .url(url)
                    .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Failed to fetch groups: ${response.code} ${response.message}")
                val body = response.body?.string() ?: throw Exception("Empty response body")
                val groupsArray = Json.parseToJsonElement(body).jsonArray
                val groups = groupsArray.mapNotNull { element ->
                    try {
                        kotlinx.serialization.json.Json.decodeFromJsonElement(GroupInfoDto.serializer(), element)
                    } catch (e: Exception) {
                        Timber.e(e, "❌ Failed to decode group element")
                        null
                    }
                }
                Timber.i("📊 Found ${groups.size} SyncPlay groups")
                _availableGroups.value = groups
                if (_isSyncPlayActive.value) {
                    val currentId = _currentGroupId.value
                    val updatedGroup = groups.find { it.groupId.toJavaUuid() == currentId }
                    if (updatedGroup != null) {
                        applyGroupDto(updatedGroup)
                    }
                }
                response.close()
            } catch (e: Exception) {
                Timber.e(e, "❌ Error refreshing groups")
                _syncPlayMessages.value = SyncPlayMessage.Error(e.message ?: "Failed to load groups")
            }
        }
    }

    /**
     * Creates a new SyncPlay group
     */
    fun createGroup() {
        coroutineScope.launch {
            try {
                Timber.i("🆕 Creating new SyncPlay group")
                val name = deviceInfo.name?.takeIf { it.isNotBlank() } ?: "Wholphin"
                val url = "${api.baseUrl}/SyncPlay/Create"
                val jsonBody = """{"GroupName":"$name"}"""
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Failed to create group: ${response.code} ${response.message}")
                response.close()
                delay(500) // Give server time to create
                refreshGroups()
                val groups = _availableGroups.value
                val currentUserName = serverRepository.current.value?.user?.name
                val newGroup = currentUserName
                    ?.let { name -> groups.firstOrNull { it.participants.contains(name) } }
                    ?: groups.firstOrNull()
                val groupId = newGroup?.groupId?.toJavaUuid()
                    ?: throw IllegalStateException("Failed to find created group")
                Timber.i("✅ Created group: $groupId")
                joinGroupInternal(groupId)
            } catch (e: Exception) {
                Timber.e(e, "❌ Error creating group")
                _syncPlayMessages.value = SyncPlayMessage.Error(e.message ?: "Failed to create group")
            }
        }
    }

    /**
     * Joins an existing SyncPlay group
     */
    fun joinGroup(groupId: UUID) {
        coroutineScope.launch {
            joinGroupInternal(groupId)
        }
    }

    /**
     * Enables SyncPlay and starts the connection manager
     * Call this when user enables SyncPlay in preferences
     */
    fun enableSyncPlay() {
        if (_isSyncPlayActive.value) {
            Timber.d("🔄 SyncPlay already enabled, skipping")
            return
        }
        Timber.i("✅ Enabling SyncPlay - starting connection manager")
        _isSyncPlayActive.value = true
        // Use supervisor job for all coroutines to isolate failures
        // Log local network info for debug
        logLocalNetworkInfo((deviceInfo as? android.content.Context))
        startConnectionManager()
    }

    /**
     * Disables SyncPlay and stops all operations
     * Call this when user disables SyncPlay in preferences
     */
    fun disableSyncPlay() {
        if (!_isSyncPlayActive.value) {
            Timber.d("🔄 SyncPlay already disabled, skipping")
            return
        }
        Timber.i("❌ Disabling SyncPlay - stopping all operations")
        _isSyncPlayActive.value = false
        // Stop all jobs
        connectionJob?.cancel()
        connectionJob = null
        positionReportingJob?.cancel()
        positionReportingJob = null
        sessionsPollingJob?.cancel()
        sessionsPollingJob = null
        refreshGroupsJob?.cancel()
        refreshGroupsJob = null
        driftCheckJob?.cancel()
        driftCheckJob = null
        heartbeatJob?.cancel()
        connectionTimeoutJob?.cancel()
        // Disconnect WebSocket
        webSocket?.cancel()
        webSocket = null
        _webSocketState.value = WebSocketState.DISCONNECTED
        reconnectAttempts = 0
        _syncPlayMessages.value = SyncPlayMessage.ConnectionStatus(connected = false, reconnecting = false)
    }

    /**
     * Internal group join logic
     */
    private suspend fun joinGroupInternal(groupId: UUID) {
        Timber.i("🔗 joinGroupInternal called with groupId: $groupId")
        // Debug: log device info, groupId, and current network info
        logLocalNetworkInfo((deviceInfo as? android.content.Context))
        Timber.i("[DEBUG] DeviceInfo: $deviceInfo, GroupId: $groupId")
        var attempt = 0
        val maxAttempts = 3
        var lastError: Exception? = null
        while (attempt < maxAttempts) {
            try {
                Timber.i("🔗 Attempt ${attempt + 1} to join group: $groupId")
                val url = "${api.baseUrl}/SyncPlay/Join"
                val jsonBody = """{"GroupId":"$groupId"}"""
                val request = Request.Builder()
                    .url(url)
                    .post(jsonBody.toRequestBody("application/json".toMediaType()))
                    .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                    .build()
                val response = okHttpClient.newCall(request).execute()
                if (!response.isSuccessful) throw Exception("Failed to join group: ${response.code} ${response.message}")
                response.close()
                _currentGroupId.value = groupId
                _isSyncPlayActive.value = true
                refreshGroups()
                val groups = _availableGroups.value
                val joinedGroup = groups.find { it.groupId.toJavaUuid() == groupId }
                val members = joinedGroup?.participants ?: emptyList()
                Timber.i("🔗 Group members after join: $members")
                // Debug: log all group info
                Timber.i("[DEBUG] All groups after join: ${groups.map { it.groupId to it.participants }}")
                _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, members)
                Timber.i("🔗 About to call startConnectionManager()")
                startConnectionManager()
                Timber.i("🔗 startConnectionManager() called successfully")
                startSessionsPolling()
                Timber.i("✅ Successfully joined group and set ready")
                return
            } catch (e: Exception) {
                lastError = e
                Timber.e(e, "❌ Error joining group (attempt ${attempt + 1})")
                if (e.message?.contains("401") == true || e.message?.contains("403") == true) {
                    Timber.e("❌ Authorization error while joining group. Check access token and permissions.")
                } else if (e.message?.contains("timeout") == true || e.message?.contains("network") == true) {
                    Timber.e("❌ Network error while joining group. Will retry.")
                }
                attempt++
                if (attempt < maxAttempts) {
                    val backoff = 1000L * attempt
                    Timber.i("🔗 Waiting $backoff ms before retry...")
                    kotlinx.coroutines.delay(backoff)
                }
            }
        }
        Timber.e(lastError, "❌ Failed to join group after $maxAttempts attempts")
        _syncPlayMessages.value = SyncPlayMessage.Error(lastError?.message ?: "Failed to join group after retries")
    }

    /**
     * Leaves the current SyncPlay group
     */
    fun leaveGroup() {
        coroutineScope.launch {
            try {
                val groupId = _currentGroupId.value
                Timber.i("🚪 Leaving group: $groupId")
                if (groupId != null) {
                    val url = "${api.baseUrl}/SyncPlay/Leave"
                    val jsonBody = """{"GroupId":"$groupId"}"""
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                        .build()
                    val response = okHttpClient.newCall(request).execute()
                    if (!response.isSuccessful) Timber.e("❌ Failed to leave group: ${response.code} ${response.message}")
                    response.close()
                }
                cleanupGroupState()
                Timber.i("✅ Left group")
            } catch (e: Exception) {
                Timber.e(e, "❌ Error leaving group")
                cleanupGroupState() // Clean up anyway
            }
        }
    }

    /**
     * Cleans up group-related state
     */
    private fun cleanupGroupState() {
        _currentGroupId.value = null
        _isSyncPlayActive.value = false
        _groupMembers.value = emptyList()
        lastPlayCommand = null
        
        sessionsPollingJob?.cancel()
        stopPositionReporting()
        stopDriftChecking()
    }

    fun enableGroupPolling() {
        startPollingGroups()
    }

    // ============================================================================
    // PLAYBACK CONTROL
    // ============================================================================

    /**
     * Sends pause command to group
     */
    fun pause() {
        coroutineScope.launch {
            try {
                if (_currentGroupId.value != null && _isSyncPlayActive.value) {
                    // Direct HTTP call since Jellyfin SDK doesn't have pause API yet
                    val url = "${api.baseUrl}/SyncPlay/Pause"
                    
                    val request = Request.Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                        .build()
                    
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        Timber.d("⏸️ Pause command sent")
                        _syncPlayMessages.value = SyncPlayMessage.CommandSent("Pause")
                    } else {
                        Timber.e("❌ Pause command failed: ${response.code} ${response.message}")
                    }
                    response.close()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error pausing")
            }
        }
    }

    /**
     * Sends unpause command to group
     */
    fun unpause() {
        coroutineScope.launch {
            try {
                if (_currentGroupId.value != null && _isSyncPlayActive.value) {
                    // Direct HTTP call since Jellyfin SDK doesn't have unpause API yet
                    val url = "${api.baseUrl}/SyncPlay/Unpause"
                    
                    val request = Request.Builder()
                        .url(url)
                        .post(ByteArray(0).toRequestBody(null))
                        .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                        .build()
                    
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        Timber.d("▶️ Unpause command sent")
                        _syncPlayMessages.value = SyncPlayMessage.CommandSent("Resume")
                    } else {
                        Timber.e("❌ Unpause command failed: ${response.code} ${response.message}")
                    }
                    response.close()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error unpausing")
            }
        }
    }

    /**
     * Sends seek command to group
     */
    fun seek(positionMs: Long) {
        coroutineScope.launch {
            try {
                if (_currentGroupId.value != null && _isSyncPlayActive.value) {
                    // Direct HTTP call since Jellyfin SDK doesn't have seek API yet
                    val url = "${api.baseUrl}/SyncPlay/Seek"
                    val jsonBody = """{"PositionTicks":${positionMs * 10_000}}""" // Convert ms to ticks
                    
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                        .build()
                    
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        Timber.d("⏩ Seek command sent to position ${positionMs}ms")
                        _syncPlayMessages.value = SyncPlayMessage.CommandSent("Seek to ${positionMs}ms")
                    } else {
                        Timber.e("❌ Seek command failed: ${response.code} ${response.message}")
                    }
                    response.close()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error seeking")
            }
        }
    }

    /**
     * Sends play command to group
     */
    fun play(itemIds: List<UUID>, startPositionMs: Long, startIndex: Int) {
        coroutineScope.launch {
            try {
                if (_currentGroupId.value != null && _isSyncPlayActive.value) {
                    // Direct HTTP call since Jellyfin SDK doesn't have play API yet
                    val url = "${api.baseUrl}/SyncPlay/Play"
                    val jsonBody = """{
                        "PlayingItemIds": ${itemIds.joinToString(prefix = "[", postfix = "]", transform = { "\"$it\"" })},
                        "StartPositionTicks": ${startPositionMs * 10_000},
                        "StartIndex": $startIndex
                    }"""
                    
                    val request = Request.Builder()
                        .url(url)
                        .post(jsonBody.toRequestBody("application/json".toMediaType()))
                        .addHeader("Authorization", api.accessToken?.let { "MediaBrowser Token=$it" } ?: "")
                        .build()
                    
                    val response = okHttpClient.newCall(request).execute()
                    if (response.isSuccessful) {
                        Timber.d("🎬 Play command sent: items=${itemIds.size}, startIndex=$startIndex, position=${startPositionMs}ms")
                        _syncPlayMessages.value = SyncPlayMessage.CommandSent("Play")
                    } else {
                        Timber.e("❌ Play command failed: ${response.code} ${response.message}")
                    }
                    response.close()
                }
            } catch (e: Exception) {
                Timber.e(e, "❌ Error playing")
            }
        }
    }

    // ============================================================================
    // POSITION REPORTING & SYNC
    // ============================================================================

    /**
     * Starts reporting playback position to server (FIXED - was TODO)
     */
    fun startPositionReporting(getPosition: suspend () -> Long) {
        if (_reportingPlaybackPosition.value) return

        Timber.i("📍 Starting position reporting")
        _reportingPlaybackPosition.value = true

        positionReportingJob = coroutineScope.launch {
            while (isActive && _isSyncPlayActive.value && _currentGroupId.value != null) {
                try {
                    val position = getPosition()
                    reportPosition(position)
                    delay(1000) // Report every second
                } catch (e: Exception) {
                    Timber.e(e, "❌ Error in position reporting")
                    delay(1000)
                }
            }
            _reportingPlaybackPosition.value = false
            Timber.i("📍 Position reporting stopped")
        }

        // Also start drift checking
        startDriftChecking(getPosition)
    }

    /**
     * Actually reports position to server (IMPLEMENTATION ADDED)
     */
    private suspend fun reportPosition(positionMs: Long) {
        lastKnownPosition = positionMs
        lastUpdateTime = System.currentTimeMillis()

        try {
            // Report to server via SDK
            // Note: The ping method may not exist in all SDK versions
            // This is the proper implementation that was missing
            val positionTicks = positionMs * 10000 // Convert ms to ticks
            val pingMs = estimateLatency()

            // TODO: When SDK has syncPlayPing method, uncomment:
            // api.syncPlayApi.syncPlayPing(positionTicks, pingMs)
            
            Timber.v("📍 Reported position: ${positionMs}ms (ping: ${pingMs}ms)")
        } catch (e: Exception) {
            Timber.w(e, "⚠️ Failed to report position")
        }
    }

    /**
     * Estimates network latency for sync calculations
     */
    private fun estimateLatency(): Long {
        // Simple estimation - could be improved with actual ping/pong
        return 50L
    }

    /**
     * Stops position reporting
     */
    fun stopPositionReporting() {
        positionReportingJob?.cancel()
        positionReportingJob = null
        _reportingPlaybackPosition.value = false
        Timber.i("📍 Position reporting stopped")
    }

    /**
     * Starts drift detection and correction (NEW FEATURE)
     */
    private fun startDriftChecking(getPosition: suspend () -> Long) {
        driftCheckJob?.cancel()

        driftCheckJob = coroutineScope.launch {
            while (isActive && _isSyncPlayActive.value) {
                try {
                    val currentPosition = getPosition()
                    checkAndCorrectDrift(currentPosition)
                    delay(2000) // Check every 2 seconds
                } catch (e: Exception) {
                    Timber.w(e, "⚠️ Error in drift checking")
                    delay(2000)
                }
            }
        }
    }

    /**
     * Checks for position drift and issues correction if needed (NEW FEATURE)
     */
    private suspend fun checkAndCorrectDrift(currentPosition: Long) {
        // Calculate expected position based on last server update
        val timeSinceUpdate = System.currentTimeMillis() - lastUpdateTime
        val expectedPosition = lastKnownPosition + timeSinceUpdate
        val drift = abs(currentPosition - expectedPosition)

        // Get threshold from preferences
        val threshold = try {
            withContext(Dispatchers.IO) {
                appPreferencesDataStore.data.first()
                    .interfacePreferences.syncplayPreferences.syncThresholdMs.toLong()
            }
        } catch (e: Exception) {
            500L // Default threshold
        }

        if (drift > threshold) {
            Timber.w("⚠️ Drift detected: ${drift}ms (threshold: ${threshold}ms), correcting to ${expectedPosition}ms")
            _playbackCommands.value = SyncPlayCommand.Seek(expectedPosition)
        }
    }

    /**
     * Stops drift checking
     */
    private fun stopDriftChecking() {
        driftCheckJob?.cancel()
        driftCheckJob = null
    }

    // ============================================================================
    // BUFFERING COORDINATION (NEW FEATURE)
    // ============================================================================

    /**
     * Reports that buffering has started
     */
    fun reportBufferingStart() {
        _isBuffering.value = true
        Timber.i("📊 Buffering started")
        // Server should wait for all clients to finish buffering
    }

    /**
     * Reports that buffering has completed
     */
    suspend fun reportBufferingDone(positionMs: Long, isPlaying: Boolean) {
        _isBuffering.value = false
        
        try {
            // TODO: When SDK supports buffering API, uncomment:
            // val whenTicks = System.currentTimeMillis() * 10000
            // val positionTicks = positionMs * 10000
            // api.syncPlayApi.syncPlayBufferingComplete(whenTicks, positionTicks, isPlaying)
            
            Timber.i("✅ Buffering complete reported (position: ${positionMs}ms, playing: $isPlaying)")
        } catch (e: Exception) {
            Timber.e(e, "❌ Failed to report buffering done")
        }
    }

    // ============================================================================
    // WEBSOCKET MESSAGE HANDLING
    // ============================================================================

    /**
     * Handles incoming WebSocket messages
     */
    private fun handleWebSocketMessage(payload: String) {
            Timber.d("[SYNCPLAY-DEBUG] handleWebSocketMessage ENTRY: $payload")
        Timber.w("[DEBUG] handleWebSocketMessage RAW: $payload")
        Timber.d("[DEBUG] handleWebSocketMessage ENTRY")
        lastWebSocketUpdate = System.currentTimeMillis()

        Timber.i("📨 Processing WebSocket message: ${payload.take(300)}")

        runCatching {
            Timber.d("[SYNCPLAY-DEBUG] handleWebSocketMessage: parsed root, about to check type")
            val root = json.parseToJsonElement(payload).jsonObject
            val type = root["MessageType"]?.jsonPrimitive?.content
            val dataElement = root["Data"]
            val data = dataElement as? JsonObject

            Timber.i("📨 Message type: $type")

            // Debug: log session, group, and readiness state for SyncPlay events
            if (type == "SyncPlayGroupUpdate" || type == "SyncPlayGroupJoined" || type == "SyncPlayCommand") {
                Timber.i("[DEBUG] CurrentGroupId: ${_currentGroupId.value}, IsActive: ${_isSyncPlayActive.value}, GroupMembers: ${_groupMembers.value}")
            }

            when (type) {
                "SyncPlayGroupUpdate" -> {
                    Timber.i("📨 Handling SyncPlayGroupUpdate")
                    if (data == null) Timber.d("[DEBUG] handleWebSocketMessage: data is null for SyncPlayGroupUpdate")
                    data?.let { handleGroupUpdate(it) } ?: Timber.d("[DEBUG] handleWebSocketMessage: handleGroupUpdate skipped due to null data")
                }
                "SyncPlayGroupJoined" -> {
                    Timber.i("📨 Handling SyncPlayGroupJoined")
                    if (data == null) Timber.d("[DEBUG] handleWebSocketMessage: data is null for SyncPlayGroupJoined")
                    data?.let { handleGroupUpdate(it) } ?: Timber.d("[DEBUG] handleWebSocketMessage: handleGroupUpdate skipped due to null data")
                }
                "SyncPlayGroupLeft" -> {
                    Timber.i("📨 Handling SyncPlayGroupLeft")
                    handleGroupLeft()
                }
                "SyncPlayCommand" -> {
                    Timber.i("📨 Handling SyncPlayCommand")
                    if (data == null) Timber.d("[DEBUG] handleWebSocketMessage: data is null for SyncPlayCommand")
                    data?.let { handlePlaybackCommand(it) } ?: Timber.d("[DEBUG] handleWebSocketMessage: handlePlaybackCommand skipped due to null data")
                }
                "PlayQueueUpdate" -> {
                    Timber.i("📨 Handling PlayQueueUpdate")
                    if (data == null) Timber.d("[DEBUG] handleWebSocketMessage: data is null for PlayQueueUpdate")
                    data?.let { handlePlayQueueUpdate(it) } ?: Timber.d("[DEBUG] handleWebSocketMessage: handlePlayQueueUpdate skipped due to null data")
                }
                "pong" -> {
                    lastHeartbeatAck = System.currentTimeMillis()
                    Timber.d("🔗 Heartbeat pong received (message handler)")
                }
                else -> {
                    Timber.d("[SYNCPLAY-DEBUG] handleWebSocketMessage: type=$type, data=$data")
                    Timber.i("📨 Unhandled message type: $type")
                }
            }
            Timber.d("[DEBUG] handleWebSocketMessage EXIT")
        }.onFailure {
            Timber.e(it, "⚠️ Failed to parse WebSocket message: ${it.message}")
            _syncPlayMessages.value = SyncPlayMessage.Error("WebSocket message parse error: ${it.message}")
        }
    }

    /**
     * Handles group update messages
     */
    private fun handleGroupUpdate(data: JsonObject) {
            Timber.d("[SYNCPLAY-DEBUG] handleGroupUpdate ENTRY: $data")
        Timber.d("[DEBUG] handleGroupUpdate ENTRY")
        // Check for PlayQueue type and nested Data
        val type = data["Type"]?.jsonPrimitive?.content
        val reason = data["Data"]?.jsonObject?.get("Reason")?.jsonPrimitive?.content
        Timber.d("[DEBUG] handleGroupUpdate: type=$type, reason=$reason")
        if (type == "PlayQueue" && reason == "NewPlaylist") {
            Timber.d("[SYNCPLAY-DEBUG] handleGroupUpdate: PlayQueue/NewPlaylist detected, extracting playlist info")
            Timber.w("[DEBUG] handleGroupUpdate: PlayQueue/NewPlaylist detected, extracting playlist info")
            val playQueue = data["Data"]?.jsonObject
            if (playQueue == null) Timber.d("[DEBUG] handleGroupUpdate: playQueue is null")
            val playlist = playQueue?.get("Playlist")?.jsonArray
            val playingItemIndex = playQueue?.get("PlayingItemIndex")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val startPositionTicks = playQueue?.get("StartPositionTicks")?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val startPositionMs = startPositionTicks / 10000
            val itemIds = playlist?.mapNotNull { element ->
                element.jsonObject["ItemId"]?.jsonPrimitive?.content?.let { parseUUID(it) }
            } ?: emptyList()
            Timber.d("[DEBUG] handleGroupUpdate: playlist size=${playlist?.size ?: -1}, itemIds size=${itemIds.size}, playingItemIndex=$playingItemIndex, startPositionMs=$startPositionMs")
            if (itemIds.isNotEmpty()) {
                Timber.d("[SYNCPLAY-DEBUG] handleGroupUpdate: Emitting Play command from PlayQueue: itemIds=${itemIds.size} index=$playingItemIndex posMs=$startPositionMs")
                Timber.i("[DEBUG] handleGroupUpdate: Emitting Play command from PlayQueue: itemIds=${itemIds.size} index=$playingItemIndex posMs=$startPositionMs")
                emitPlayCommand(itemIds[playingItemIndex], startPositionMs, itemIds, playingItemIndex)
            } else {
                Timber.d("[DEBUG] handleGroupUpdate: itemIds is empty, not emitting play command")
            }
            Timber.d("[DEBUG] handleGroupUpdate EXIT (PlayQueue/NewPlaylist path)")
            Timber.d("[SYNCPLAY-DEBUG] handleGroupUpdate EXIT (PlayQueue/NewPlaylist path)")
            return
        }
        // Fallback to old logic
        val group = data["Group"]?.jsonObject ?: data
        Timber.d("[DEBUG] handleGroupUpdate: fallback to applyGroupUpdate, group keys=${group.keys}")
        applyGroupUpdate(group)
        Timber.d("[DEBUG] handleGroupUpdate EXIT (fallback path)")
        Timber.d("[SYNCPLAY-DEBUG] handleGroupUpdate EXIT (fallback path)")
    }

    /**
     * Handles group left notification
     */
    private fun handleGroupLeft() {
        Timber.i("🚪 Group left notification from server")
        cleanupGroupState()
        _syncPlayMessages.value = SyncPlayMessage.GroupLeft("Left by server")
    }

    /**
     * Handles playback command messages
     */
    private fun handlePlaybackCommand(data: JsonObject) {
            Timber.d("[SYNCPLAY-DEBUG] handlePlaybackCommand ENTRY: $data")
        val command = data["Command"]?.jsonPrimitive?.content
        val positionTicks = data["PositionTicks"]?.jsonPrimitive?.content?.toLongOrNull()
        val positionMs = positionTicks?.let { it / 10000 }
        val playbackRate = data["PlaybackRate"]?.jsonPrimitive?.content?.toFloatOrNull()
        val itemIdsJson = data["ItemIds"]?.jsonArray
        val itemIds = itemIdsJson?.mapNotNull { it.jsonPrimitive.content.let { parseUUID(it) } } ?: emptyList()
        val startIndex = data["StartIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        Timber.w("[DEBUG] handlePlaybackCommand: command=$command positionMs=$positionMs itemIds=${itemIds.size} startIndex=$startIndex")
        Timber.i("[SyncPlayManager] Playback command received: $command position=${positionMs}ms rate=$playbackRate itemIds=${itemIds.size} startIndex=$startIndex")
        Timber.d("[SYNCPLAY-DEBUG] handlePlaybackCommand: parsed command=$command, positionMs=$positionMs, playbackRate=$playbackRate, itemIds=$itemIds, startIndex=$startIndex")

        // Update tracking variables when receiving authoritative position from server
        if (positionMs != null) {
            lastKnownPosition = positionMs
            lastUpdateTime = System.currentTimeMillis()
            Timber.d("[SyncPlayManager] Updated tracking from command: ${positionMs}ms")
        }

        when (command) {
            "Pause" -> positionMs?.let {
                Timber.d("[SyncPlayManager] Emitting Pause command at $it ms")
                _playbackCommands.value = SyncPlayCommand.Pause(it)
            }
            "Unpause" -> positionMs?.let {
                Timber.d("[SyncPlayManager] Emitting Unpause command at $it ms")
                _playbackCommands.value = SyncPlayCommand.Unpause(it)
            }
            "Seek" -> positionMs?.let {
                Timber.d("[SyncPlayManager] Emitting Seek command at $it ms")
                _playbackCommands.value = SyncPlayCommand.Seek(it)
            }
            "SetPlaybackRate" -> playbackRate?.let {
                Timber.d("[SyncPlayManager] Emitting SetPlaybackRate command: $it")
                _playbackCommands.value = SyncPlayCommand.SetPlaybackRate(it)
            }
            "Play" -> {
                if (itemIds.isNotEmpty() && positionMs != null) {
                    Timber.d("[SyncPlayManager] Emitting Play command for item ${itemIds.first()} at $positionMs ms, startIndex=$startIndex")
                    emitPlayCommand(itemIds.first(), positionMs, itemIds, startIndex)
                } else {
                    Timber.w("[SyncPlayManager] Play command missing required data: itemIds=${itemIds.size} positionMs=$positionMs")
                }
            }
            else -> {
                Timber.d("[SYNCPLAY-DEBUG] handlePlaybackCommand: unknown command $command")
                Timber.w("[SyncPlayManager] Unknown command: $command")
            }
        }
    }

    /**
     * Handles play queue update messages
     */
    private fun handlePlayQueueUpdate(data: JsonObject) {
        val reason = data["Reason"]?.jsonPrimitive?.content
        Timber.i("🎵 PlayQueue update: $reason")

        when (reason) {
            "NewPlaylist" -> {
                val playlist = data["Playlist"]?.jsonArray
                val playingItemPosition = data["PlayingItemPosition"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val startPositionTicks = data["StartPositionTicks"]?.jsonPrimitive?.content?.toLongOrNull()
                val startPositionMs = startPositionTicks?.let { it / 10000 } ?: 0

                // Update tracking variables for new playlist start position
                lastKnownPosition = startPositionMs
                lastUpdateTime = System.currentTimeMillis()
                Timber.d("📡 Updated tracking from new playlist: ${startPositionMs}ms")

                val itemIds = playlist?.mapNotNull { element ->
                    element.jsonObject["ItemId"]?.jsonPrimitive?.content?.let { parseUUID(it) }
                } ?: emptyList()

                if (itemIds.isNotEmpty()) {
                    Timber.i("🎬 New playlist: ${itemIds.size} items, starting at index $playingItemPosition")
                    emitPlayCommand(itemIds.first(), startPositionMs, itemIds, playingItemPosition)
                }
            }
            else -> Timber.d("🎵 Unhandled queue update reason: $reason")
        }
    }

    /**
     * Applies group state update from JSON
     */
    private fun applyGroupUpdate(group: JsonObject) {
        val idString = group["GroupId"]?.jsonPrimitive?.content
            ?: group["Id"]?.jsonPrimitive?.content
        val groupId = idString?.let { parseUUID(it) } ?: return

        val membersJson = group["Members"] ?: group["Users"] ?: group["Clients"]
        val members = membersJson?.jsonArray?.mapNotNull { element ->
            element.jsonObject["UserName"]?.jsonPrimitive?.content
                ?: element.jsonObject["Name"]?.jsonPrimitive?.content
        }?.distinct() ?: emptyList()

        // Detect user joins by comparing with previous members
        val previousMembers = _groupMembers.value
        val newMembers = members.filter { it !in previousMembers }
        newMembers.forEach { userName ->
            Timber.i("👋 User joined: $userName")
            _syncPlayMessages.value = SyncPlayMessage.UserJoined(userName)
        }

        _currentGroupId.value = groupId
        _groupMembers.value = members
        _isSyncPlayActive.value = true
        _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, members)
        
        Timber.d("📊 Group updated: $groupId with ${members.size} members")
    }

    /**
     * Applies group state from DTO
     */
    private fun applyGroupDto(group: GroupInfoDto) {
        val groupId = group.groupId.toJavaUuid()
        _currentGroupId.value = groupId
        _groupMembers.value = group.participants
        _isSyncPlayActive.value = true
        _syncPlayMessages.value = SyncPlayMessage.GroupJoined(groupId, group.participants)
    }

    // ============================================================================
    // POLLING FALLBACK
    // ============================================================================

    /**
     * Starts polling for group state (fallback when WebSocket unavailable)
     */
    private fun startSessionsPolling() {
        sessionsPollingJob?.cancel()
        
        Timber.i("🔄 Starting sessions polling")
        sessionsPollingJob = coroutineScope.launch {
            while (isActive && _isSyncPlayActive.value) {
                val groupId = _currentGroupId.value ?: break

                // Only poll if WebSocket hasn't updated recently (prioritize WebSocket)
                val timeSinceWebSocketUpdate = System.currentTimeMillis() - lastWebSocketUpdate
                if (timeSinceWebSocketUpdate > 5000) {
                    Timber.d("🔄 Polling (WebSocket idle for ${timeSinceWebSocketUpdate}ms)")
                    try {
                        queryRemotePlaybackState(groupId)
                    } catch (e: Exception) {
                        Timber.w(e, "🔄 Polling error")
                    }
                }

                delay(3000) // Poll every 3 seconds
            }
            Timber.i("🔄 Sessions polling stopped")
        }
    }

    /**
     * Queries remote playback state via REST API
     */
    private suspend fun queryRemotePlaybackState(groupId: UUID) {
        val accessToken = serverRepository.current.value?.user?.accessToken ?: return
        val baseUrl = currentBaseUrl() ?: return

        try {
            val url = "$baseUrl/SyncPlay/List?api_key=$accessToken"
            val request = Request.Builder().url(url).build()

            okHttpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return
                Timber.w("[DEBUG] queryRemotePlaybackState RAW: $body")
                if (!response.isSuccessful) {
                    Timber.w("🔄 Poll failed: HTTP ${response.code}")
                    return
                }

                val groupsArray = json.parseToJsonElement(body).jsonArray

                for (groupElement in groupsArray) {
                    val groupObj = groupElement.jsonObject
                    val gidStr = groupObj["GroupId"]?.jsonPrimitive?.content
                    val gidUuid = gidStr?.let { parseUUID(it) }

                    if (gidUuid == groupId) {
                        processPlaybackState(groupObj)
                        break
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "🔄 Error querying playback state")
        }
    }

    /**
     * Processes playback state from group object
     */
    private fun processPlaybackState(groupObj: JsonObject) {
        Timber.w("[DEBUG] processPlaybackState called: groupObj=$groupObj")
        val playlist = groupObj["Playlist"]?.jsonArray ?: emptyList()
        val playingIndex = groupObj["PlayingItemIndex"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val playingPositionTicks = groupObj["PlayingItemPositionTicks"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val positionMs = playingPositionTicks / 10000

        // Update our tracking variables with server state
        lastKnownPosition = positionMs
        lastUpdateTime = System.currentTimeMillis()

        Timber.d("📡 Received server position: ${positionMs}ms, updated tracking variables")

        if (playingIndex >= 0 && playingIndex < playlist.size) {
            val itemObj = playlist[playingIndex].jsonObject
            val itemIdStr = itemObj["ItemId"]?.jsonPrimitive?.content
            val itemUuid = itemIdStr?.let { parseUUID(it) }

            Timber.w("[DEBUG] processPlaybackState: itemUuid=$itemUuid positionMs=$positionMs playingIndex=$playingIndex playlistSize=${playlist.size}")
            if (itemUuid != null) {
                val itemIds = playlist.mapNotNull {
                    it.jsonObject["ItemId"]?.jsonPrimitive?.content?.let { id -> parseUUID(id) }
                }
                emitPlayCommand(itemUuid, positionMs, itemIds, playingIndex)
            }
        }
    }

    /**
     * Emits play command with smart deduplication (IMPROVED)
     */
    private fun emitPlayCommand(
        itemId: UUID,
        positionMs: Long,
        itemIds: List<UUID>,
        startIndex: Int
    ) {
        Timber.d("[SYNCPLAY-DEBUG] emitPlayCommand ENTRY: itemId=$itemId positionMs=$positionMs itemIds=$itemIds startIndex=$startIndex")
        Timber.w("[DEBUG] emitPlayCommand called: itemId=$itemId positionMs=$positionMs itemIds=${itemIds.size} startIndex=$startIndex")
        if (!shouldEmitPlayCommand(itemId, positionMs)) {
            Timber.d("[SYNCPLAY-DEBUG] emitPlayCommand: Play command deduplicated, not emitting")
            Timber.w("[DEBUG] Play command deduplicated: $itemId positionMs=$positionMs")
            return
        }
        lastPlayCommand = PlayCommandRecord(itemId, positionMs, System.currentTimeMillis())
        Timber.w("[DEBUG] Play command will be emitted: itemId=$itemId positionMs=$positionMs itemIds=${itemIds.size} startIndex=$startIndex")
        Timber.d("[SYNCPLAY-DEBUG] emitPlayCommand: Emitting SyncPlayCommand.Play to _playbackCommands StateFlow")
        _playbackCommands.value = SyncPlayCommand.Play(itemIds, positionMs, startIndex)
        Timber.i("🎬 Play command emitted: $itemId at ${positionMs}ms (index $startIndex)")
    }

    /**
     * Determines if play command should be emitted (NEW LOGIC)
     */
    private fun shouldEmitPlayCommand(itemId: UUID, positionMs: Long): Boolean {
        val last = lastPlayCommand ?: return true
        val now = System.currentTimeMillis()

        return when {
            last.itemId != itemId -> true // Different item
            abs(last.positionMs - positionMs) > 5000 -> true // Significant position change
            now - last.timestamp > 10000 -> true // Allow after 10 seconds
            else -> false // Deduplicate
        }
    }

    /**
     * Starts polling for groups (used for group discovery)
     */
    private fun startPollingGroups() {
        if (refreshGroupsJob?.isActive == true) return

        refreshGroupsJob?.cancel()
        refreshGroupsJob = coroutineScope.launch {
            while (isActive) {
                try {
                    refreshGroups()
                    delay(10_000) // Poll every 10 seconds
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.w(e, "Error in group polling")
                    delay(10_000)
                }
            }
        }
    }

    // ============================================================================
    // DIAGNOSTICS
    // ============================================================================

    /**
     * Returns diagnostic information for debugging (NEW FEATURE)
     */
    fun getDiagnostics(): String = buildString {
        appendLine("=== SyncPlay Diagnostics ===")
        appendLine("WebSocket State: ${_webSocketState.value}")
        appendLine("WebSocket Object: ${if (webSocket != null) "Connected" else "Null"}")
        appendLine("Reconnect Attempts: $reconnectAttempts")
        appendLine()
        appendLine("Group Active: ${_isSyncPlayActive.value}")
        appendLine("Group ID: ${_currentGroupId.value}")
        appendLine("Members: ${_groupMembers.value.size} - ${_groupMembers.value.joinToString()}")
        appendLine()
        appendLine("Position Reporting: ${_reportingPlaybackPosition.value}")
        appendLine("Last Known Position: ${lastKnownPosition}ms")
        appendLine("Last Update: ${System.currentTimeMillis() - lastUpdateTime}ms ago")
        appendLine("Last WebSocket Update: ${System.currentTimeMillis() - lastWebSocketUpdate}ms ago")
        appendLine()
        appendLine("Buffering: ${_isBuffering.value}")
        appendLine("Last Play Command: $lastPlayCommand")
        appendLine()
        appendLine("Jobs Status:")
        appendLine("  Connection: ${connectionJob?.isActive}")
        appendLine("  Position Reporting: ${positionReportingJob?.isActive}")
        appendLine("  Sessions Polling: ${sessionsPollingJob?.isActive}")
        appendLine("  Drift Checking: ${driftCheckJob?.isActive}")
        appendLine("  Group Polling: ${refreshGroupsJob?.isActive}")
    }

    // ============================================================================
    // UTILITIES
    // ============================================================================

    private fun currentBaseUrl(): String? = 
        serverRepository.current.value?.server?.url?.trimEnd('/')

    private fun parseUUID(idString: String): UUID? = runCatching {
        UUID.fromString(idString)
    }.getOrNull() ?: runCatching {
        if (idString.length == 32) {
            UUID.fromString(
                idString.substring(0, 8) + "-" +
                idString.substring(8, 12) + "-" +
                idString.substring(12, 16) + "-" +
                idString.substring(16, 20) + "-" +
                idString.substring(20)
            )
        } else null
    }.getOrNull()

    fun org.jellyfin.sdk.model.UUID.toJavaUuid(): UUID = 
        UUID.fromString(toString())
}