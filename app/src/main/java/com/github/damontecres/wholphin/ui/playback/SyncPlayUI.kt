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

@Composable
fun SyncPlayDialog(
    syncPlayManager: SyncPlayManager,
    onDismiss: () -> Unit,
    preferences: com.github.damontecres.wholphin.preferences.UserPreferences? = null,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val isSyncPlayActive by syncPlayManager.isSyncPlayActive.collectAsState()
    val currentGroupId by syncPlayManager.currentGroupId.collectAsState()
    val groupMembers by syncPlayManager.groupMembers.collectAsState()
    val availableGroups by syncPlayManager.availableGroups.collectAsState()
    val notifyUserJoins =
        preferences?.appPreferences?.interfacePreferences?.syncplayPreferences?.notifyUserJoins == true
    val notifySyncPlayEnabled =
        preferences?.appPreferences?.interfacePreferences?.syncplayPreferences?.notifySyncplayEnabled == true

    // Refresh groups when dialog opens to discover existing groups
    LaunchedEffect(Unit) {
        syncPlayManager.refreshGroups()
    }

    // Handle toast notifications for user joins and command sends
    val syncPlayMessage by syncPlayManager.syncPlayMessages.collectAsState()
    LaunchedEffect(syncPlayMessage) {
        when (syncPlayMessage) {
            is SyncPlayMessage.GroupJoined -> {
                // Group joined toast now handled globally
            }
            is SyncPlayMessage.UserJoined -> {
                // User joined toast now handled globally
            }
            is SyncPlayMessage.UserLeft -> {
                // User left toast now handled globally
            }
            is SyncPlayMessage.GroupLeft -> {
                // Group left toast now handled globally
            }
            is SyncPlayMessage.CommandSent -> {
                // Command sent toast now handled globally
            }
            else -> {
                // Other messages handled globally
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.8f)),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(600.dp)
                .padding(32.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.syncplay_title),
                    style = MaterialTheme.typography.headlineMedium
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (isSyncPlayActive) {
                    // Already in a group
                    Text(
                        text = stringResource(R.string.syncplay_in_group),
                        style = MaterialTheme.typography.bodyLarge
                    )

                    Text(
                        text = "${groupMembers.size} ${stringResource(R.string.syncplay_members)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    // Display participant list
                    if (groupMembers.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            groupMembers.forEach { member ->
                                Text(
                                    text = "• $member",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)
                    ) {
                        Button(onClick = {
                            syncPlayManager.pause()
                        }) {
                            Text("Pause")
                        }

                        Button(onClick = {
                            syncPlayManager.unpause()
                        }) {
                            Text("Resume")
                        }

                        Button(onClick = {
                            syncPlayManager.leaveGroup()
                            onDismiss()
                        }) {
                            Text(stringResource(R.string.syncplay_leave_group))
                        }
                    }
                } else {
                    // Not in a group - show create/join options
                    Text(
                        text = stringResource(R.string.syncplay_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = {
                            syncPlayManager.createGroup()
                        },
                        modifier = Modifier.fillMaxWidth(0.8f)
                    ) {
                        Text(stringResource(R.string.syncplay_create_group))
                    }

                    Text(
                        text = stringResource(R.string.syncplay_or),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    if (availableGroups.isNotEmpty()) {
                        Text(
                            text = "Available Groups:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth(0.9f)
                                .height(200.dp)
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                                    shape = MaterialTheme.shapes.small
                                )
                                .padding(8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(availableGroups) { group ->
                                Button(
                                    onClick = {
                                        // Convert Jellyfin UUID to Java UUID for joining
                                        val javaUuid = java.util.UUID.fromString(group.groupId.toString())
                                        syncPlayManager.joinGroup(javaUuid)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            text = "Group ${group.groupId}",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "${group.participants?.size ?: 0} members",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Text(
                            text = "No groups available",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.close))
                }
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
        ?: return

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SyncPlayDialog(
            syncPlayManager = syncPlayManager,
            onDismiss = {}, // Empty as we're on a full page
            preferences = preferences,
        )
    }
}