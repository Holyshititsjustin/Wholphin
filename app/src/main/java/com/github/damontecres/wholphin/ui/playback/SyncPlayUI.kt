package com.github.damontecres.wholphin.ui.playback

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import java.util.UUID
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.SyncPlayManager
import com.github.damontecres.wholphin.services.SyncPlayMessage
import com.github.damontecres.wholphin.ui.showToast
import kotlinx.coroutines.launch
import org.jellyfin.sdk.model.api.GroupInfoDto
import timber.log.Timber

@Composable
fun SyncPlayGroupDialog(
    syncPlayManager: SyncPlayManager,
    onDismiss: () -> Unit,
    showDialog: Boolean = true,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val availableGroups by syncPlayManager.availableGroups.collectAsState()
    val selectedGroupIdState = remember { mutableStateOf<UUID?>(null) }
    val isSyncPlayActive by syncPlayManager.isSyncPlayActive.collectAsState()
    val groupMembers by syncPlayManager.groupMembers.collectAsState()

    LaunchedEffect(showDialog) {
        if (showDialog) {
            Timber.i("[DIAG] SyncPlayGroupDialog opened")
            // Start periodic refresh
            while (showDialog) {
                syncPlayManager.refreshGroups()
                Timber.i("[DIAG] SyncPlayGroupDialog periodic refresh triggered")
                kotlinx.coroutines.delay(5000) // Refresh every 5 seconds
            }
        } else {
            Timber.i("[DIAG] SyncPlayGroupDialog closed")
        }
    }

    LaunchedEffect(syncPlayManager.playbackCommands) {
        syncPlayManager.playbackCommands.collect { command ->
            Timber.w("[DEBUG][UI] playbackCommands observer received: $command")
        }
    }

    Column(modifier = Modifier.padding(32.dp)) {
        if (isSyncPlayActive) {
            Text("SyncPlay Group Members:", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            if (groupMembers.isEmpty()) {
                Text("No members in group.")
            } else {
                LazyColumn {
                    items(groupMembers) { member ->
                        Text(member, modifier = Modifier.padding(8.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                Timber.i("[DIAG] User requested leave group from UI")
                syncPlayManager.leaveGroup()
                onDismiss()
            }) {
                Text("Leave Group")
            }
        } else {
            Text("Select a SyncPlay Group:", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.height(8.dp))
            LazyColumn {
                items(availableGroups) { group ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .background(if (selectedGroupIdState.value == group.groupId) Color.LightGray else Color.Transparent)
                            .clickable { selectedGroupIdState.value = group.groupId },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(group.groupName, modifier = Modifier.weight(1f))
                        Text("Members: ${group.participants.size}", modifier = Modifier.padding(start = 16.dp))
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = {
                selectedGroupIdState.value?.let { syncPlayManager.joinGroup(it) }
                onDismiss()
            }, enabled = selectedGroupIdState.value != null) {
                Text("Join Selected Group")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    }
}

@Composable
fun SyncPlayStatusIndicator(
    syncPlayManager: SyncPlayManager,
    modifier: Modifier = Modifier
) {
    val isSyncPlayActive by syncPlayManager.isSyncPlayActive.collectAsState()

    if (isSyncPlayActive) {
        Box(
            modifier = modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.9f),
                    shape = MaterialTheme.shapes.small
                )
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(
                text = "🔄 ${stringResource(R.string.syncplay_active)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}
@Composable
fun SyncPlayManagementPage(
    preferences: com.github.damontecres.wholphin.preferences.UserPreferences? = null,
    modifier: Modifier = Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val syncPlayManager = (context as? com.github.damontecres.wholphin.MainActivity)?.syncPlayManager
    if (syncPlayManager == null) return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SyncPlayGroupDialog(
            syncPlayManager = syncPlayManager!!,
            onDismiss = {} // Empty as we're on a full page
        )
    }
}