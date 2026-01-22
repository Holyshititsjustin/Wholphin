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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.services.SyncPlayManager
import com.github.damontecres.wholphin.services.SyncPlayParticipant
import com.github.damontecres.wholphin.services.SyncPlayState
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * SyncPlay status indicator overlay shown during playback
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SyncPlayStatusIndicator(
    syncPlayManager: SyncPlayManager,
    modifier: Modifier = Modifier,
) {
    val syncState by syncPlayManager.syncPlayState.collectAsState()

    when (val state = syncState) {
        is SyncPlayState.InGroup -> {
            Box(
                modifier =
                    modifier
                        .padding(16.dp)
                        .background(
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                            shape = MaterialTheme.shapes.medium,
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Sync icon
                    Box(
                        modifier =
                            Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(if (state.isPlaying) Color.Green else Color.Gray),
                    )

                    Text(
                        text = stringResource(R.string.syncplay_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    Text(
                        text = "${state.members.size} ${stringResource(R.string.syncplay_members)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    )
                }
            }
        }
        is SyncPlayState.Error -> {
            Box(
                modifier =
                    modifier
                        .padding(16.dp)
                        .background(
                            MaterialTheme.colorScheme.error.copy(alpha = 0.9f),
                            shape = MaterialTheme.shapes.medium,
                        ).padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
        else -> {}
    }
}

/**
 * Dialog for joining or creating a SyncPlay group
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun SyncPlayDialog(
    syncPlayManager: SyncPlayManager,
    coroutineScope: CoroutineScope,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val syncState by syncPlayManager.syncPlayState.collectAsState()
    var selectedGroupIndex by remember { mutableStateOf(0) }

    Surface(
        modifier = modifier.fillMaxSize(),
        colors =
            androidx.tv.material3.SurfaceDefaults.colors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.syncplay_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(24.dp))

            when (val state = syncState) {
                is SyncPlayState.Idle -> {
                    // Show options to create or join a group
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.syncplay_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    syncPlayManager.createGroup()
                                }
                            },
                            modifier = Modifier.width(300.dp),
                        ) {
                            Text(stringResource(R.string.syncplay_create_group))
                        }

                        Text(
                            text = stringResource(R.string.syncplay_or),
                            style = MaterialTheme.typography.bodySmall,
                        )

                        // TODO: Show list of available groups to join
                        Text(
                            text = stringResource(R.string.syncplay_no_groups),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.width(300.dp),
                            colors =
                                ButtonDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                        ) {
                            Text(stringResource(R.string.cancel))
                        }
                    }
                }

                is SyncPlayState.InGroup -> {
                    // Show current group members and controls
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.syncplay_in_group),
                            style = MaterialTheme.typography.titleLarge,
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "${stringResource(R.string.syncplay_members)}: ${state.members.size}",
                            style = MaterialTheme.typography.bodyLarge,
                        )

                        // Member list
                        Column(
                            modifier =
                                Modifier
                                    .fillMaxWidth(0.6f)
                                    .padding(vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            state.members.forEach { member ->
                                SyncPlayMemberItem(member)
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = {
                                coroutineScope.launch {
                                    syncPlayManager.leaveGroup()
                                    onDismiss()
                                }
                            },
                            modifier = Modifier.width(300.dp),
                            colors =
                                ButtonDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.error,
                                ),
                        ) {
                            Text(stringResource(R.string.syncplay_leave_group))
                        }

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.width(300.dp),
                            colors =
                                ButtonDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.secondary,
                                ),
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }

                is SyncPlayState.Error -> {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.error),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.error,
                        )

                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodyMedium,
                        )

                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.width(300.dp),
                        ) {
                            Text(stringResource(R.string.close))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Display a single SyncPlay group member
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SyncPlayMemberItem(
    member: SyncPlayParticipant,
    modifier: Modifier = Modifier,
) {
    ListItem(
        selected = false,
        onClick = { },
        headlineContent = {
            Text(
                text = member.username,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        trailingContent = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (member.isBuffering) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Yellow),
                    )
                    Text(
                        text = stringResource(R.string.syncplay_buffering),
                        style = MaterialTheme.typography.labelSmall,
                    )
                } else if (member.isReady) {
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color.Green),
                    )
                    Text(
                        text = stringResource(R.string.syncplay_ready),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
        },
        modifier = modifier,
    )
}
