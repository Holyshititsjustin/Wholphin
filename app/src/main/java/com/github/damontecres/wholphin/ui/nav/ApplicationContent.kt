package com.github.damontecres.wholphin.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.MaterialTheme
import com.github.damontecres.wholphin.services.SyncPlayManager
import com.github.damontecres.wholphin.services.SyncPlayMessage
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.transitionFactory
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.data.model.JellyfinUser
import com.github.damontecres.wholphin.preferences.BackdropStyle
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.CrossFadeFactory
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.launchIO
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import javax.inject.Inject
import kotlin.time.Duration.Companion.milliseconds

// Top scrim configuration for text readability (clock, season tabs)
private const val TOP_SCRIM_ALPHA = 0.55f
private const val TOP_SCRIM_END_FRACTION = 0.25f // Fraction of backdrop image height

@HiltViewModel
class ApplicationContentViewModel
    @Inject
    constructor(
        val backdropService: BackdropService,
    ) : ViewModel() {
        fun clearBackdrop() {
            viewModelScope.launchIO { backdropService.clearBackdrop() }
        }
    }

/**
 * This is generally the root composable of the of the app
 *
 * Here the navigation backstack is used and pages are rendered in the nav drawer or full screen
 */
@Composable
fun ApplicationContent(
    server: JellyfinServer,
    user: JellyfinUser,
    startDestination: Destination,
    navigationManager: NavigationManager,
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    enableTopScrim: Boolean = true,
    viewModel: ApplicationContentViewModel = hiltViewModel(),
) {
    val backStack: MutableList<NavKey> =
        rememberSerializable(
            server,
            user,
            serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer()),
        ) {
            NavBackStack(startDestination)
        }
    navigationManager.backStack = backStack
    Timber.d("[NAV] navigationManager.backStack initialized: $backStack")
    val backdrop by viewModel.backdropService.backdropFlow.collectAsStateWithLifecycle()
    val backdropStyle = preferences.appPreferences.interfacePreferences.backdropStyle
    
    // SyncPlay enable/disable based on preferences
    val isSyncPlayEnabled = preferences.appPreferences.interfacePreferences.syncplayPreferences.enableSyncplay
    val context = LocalContext.current
    val syncPlayManager = (context as? com.github.damontecres.wholphin.MainActivity)?.syncPlayManager
    
    // Monitor SyncPlay enabled state and enable/disable accordingly
    LaunchedEffect(isSyncPlayEnabled) {
        if (syncPlayManager != null) {
            if (isSyncPlayEnabled) {
                Timber.i("🎬 SyncPlay enabled in preferences, activating...")
                syncPlayManager.enableSyncPlay()
            } else {
                Timber.i("🎬 SyncPlay disabled in preferences, deactivating...")
                syncPlayManager.disableSyncPlay()
            }
        }
    }
    
    // Global SyncPlay notification handler: listen for all commands and show notifications everywhere
    if (syncPlayManager != null) {
        val globalSyncPlayCommand by syncPlayManager.playbackCommands.collectAsStateWithLifecycle()
        val globalSyncPlayMessage by syncPlayManager.syncPlayMessages.collectAsStateWithLifecycle()
        // Get current destination from backstack
        val currentDestination = backStack.lastOrNull() as? Destination
        val isOnPlaybackScreen = currentDestination is Destination.Playback
        
        androidx.compose.runtime.LaunchedEffect(globalSyncPlayCommand) {
            Timber.d("[NAV] LaunchedEffect(globalSyncPlayCommand) fired: $globalSyncPlayCommand")
            when (val cmd = globalSyncPlayCommand) {
                is com.github.damontecres.wholphin.services.SyncPlayCommand.Play -> {
                    val firstItemId = cmd.itemIds.getOrNull(cmd.startIndex) ?: cmd.itemIds.firstOrNull()
                    Timber.i("🎬 Global SyncPlay Play received: items=%d startIndex=%d position=%d", cmd.itemIds.size, cmd.startIndex, cmd.startPositionMs)
                    if (firstItemId != null) {
                        Timber.i("🎬 Navigating to playback item %s at %dms", firstItemId, cmd.startPositionMs)
                        Timber.d("[NAV] Calling navigationManager.navigateTo(Destination.Playback) from ApplicationContent: itemId=%s, positionMs=%d", firstItemId, cmd.startPositionMs)
                        navigationManager.navigateTo(
                            Destination.Playback(
                                itemId = firstItemId,
                                positionMs = cmd.startPositionMs,
                            ),
                        )
                        Timber.d("[NAV] navigationManager.backStack after navigateTo: ${navigationManager.backStack}")
                    }
                }
                is com.github.damontecres.wholphin.services.SyncPlayCommand.Pause -> {
                    Timber.i("🎬 Global SyncPlay Pause: A group member paused playback")
                    if (!isOnPlaybackScreen) {
                        android.widget.Toast.makeText(
                            context,
                            "Group member paused",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                is com.github.damontecres.wholphin.services.SyncPlayCommand.Unpause -> {
                    Timber.i("🎬 Global SyncPlay Unpause: A group member resumed playback")
                    if (!isOnPlaybackScreen) {
                        android.widget.Toast.makeText(
                            context,
                            "Group member resumed",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                is com.github.damontecres.wholphin.services.SyncPlayCommand.Seek -> {
                    Timber.i("🎬 Global SyncPlay Seek: A group member seeked to %d ms", cmd.positionMs)
                    if (!isOnPlaybackScreen) {
                        android.widget.Toast.makeText(
                            context,
                            "Group member seeked",
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                is com.github.damontecres.wholphin.services.SyncPlayCommand.SetPlaybackRate -> {
                    Timber.i("🎬 Global SyncPlay SetPlaybackRate: %.2fx", cmd.rate)
                    if (!isOnPlaybackScreen) {
                        android.widget.Toast.makeText(
                            context,
                            "Playback rate changed to %.2fx".format(cmd.rate),
                            android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
                null -> {} // no command
            }
        }

        // Global SyncPlay message handler: show informational toasts everywhere
        androidx.compose.runtime.LaunchedEffect(globalSyncPlayMessage) {
            when (globalSyncPlayMessage) {
                is com.github.damontecres.wholphin.services.SyncPlayMessage.GroupJoined -> {
                    android.widget.Toast.makeText(context, "Joined SyncPlay group", android.widget.Toast.LENGTH_SHORT).show()
                }
                is com.github.damontecres.wholphin.services.SyncPlayMessage.UserJoined -> {
                    if (preferences.appPreferences.interfacePreferences.syncplayPreferences.notifyUserJoins) {
                        val userName = (globalSyncPlayMessage as com.github.damontecres.wholphin.services.SyncPlayMessage.UserJoined).userName
                        android.widget.Toast.makeText(context, "👋 $userName joined SyncPlay", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                is com.github.damontecres.wholphin.services.SyncPlayMessage.UserLeft -> {
                    if (preferences.appPreferences.interfacePreferences.syncplayPreferences.notifyUserJoins) {
                        val userName = (globalSyncPlayMessage as com.github.damontecres.wholphin.services.SyncPlayMessage.UserLeft).userName
                        android.widget.Toast.makeText(context, "👋 $userName left SyncPlay", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                is com.github.damontecres.wholphin.services.SyncPlayMessage.GroupLeft -> {
                    android.widget.Toast.makeText(context, "Left SyncPlay group", android.widget.Toast.LENGTH_SHORT).show()
                }
                is com.github.damontecres.wholphin.services.SyncPlayMessage.CommandSent -> {
                    val command = (globalSyncPlayMessage as com.github.damontecres.wholphin.services.SyncPlayMessage.CommandSent).command
                    android.widget.Toast.makeText(context, "📤 $command", android.widget.Toast.LENGTH_SHORT).show()
                }
                else -> {
                    // Ignore other message types or null
                }
            }
        }
    }
    Box(
        modifier = modifier,
    ) {
        val baseBackgroundColor = MaterialTheme.colorScheme.background
        if (backdrop.hasColors &&
            (backdropStyle == BackdropStyle.BACKDROP_DYNAMIC_COLOR || backdropStyle == BackdropStyle.UNRECOGNIZED)
        ) {
            val animPrimary by animateColorAsState(
                backdrop.primaryColor,
                animationSpec = tween(1250),
                label = "dynamic_backdrop_primary",
            )
            val animSecondary by animateColorAsState(
                backdrop.secondaryColor,
                animationSpec = tween(1250),
                label = "dynamic_backdrop_secondary",
            )
            val animTertiary by animateColorAsState(
                backdrop.tertiaryColor,
                animationSpec = tween(1250),
                label = "dynamic_backdrop_tertiary",
            )
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .drawBehind {
                            drawRect(color = baseBackgroundColor)
                            // Top Left (Vibrant/Muted)
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        colors = listOf(animSecondary, Color.Transparent),
                                        center = Offset(0f, 0f),
                                        radius = size.width * 0.8f,
                                    ),
                            )
                            // Bottom Right (DarkVibrant/DarkMuted)
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        colors = listOf(animPrimary, Color.Transparent),
                                        center = Offset(size.width, size.height),
                                        radius = size.width * 0.8f,
                                    ),
                            )
                            // Bottom Left (Dark / Bridge)
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        colors =
                                            listOf(
                                                baseBackgroundColor,
                                                Color.Transparent,
                                            ),
                                        center = Offset(0f, size.height),
                                        radius = size.width * 0.8f,
                                    ),
                            )
                            // Top Right (Under Image - Vibrant/Bright)
                            drawRect(
                                brush =
                                    Brush.radialGradient(
                                        colors = listOf(animTertiary, Color.Transparent),
                                        center = Offset(size.width, 0f),
                                        radius = size.width * 0.8f,
                                    ),
                            )
                        },
            )
        }
        if (backdropStyle != BackdropStyle.BACKDROP_NONE) {
            Box(
                modifier = Modifier.fillMaxSize(),
            ) {
                AsyncImage(
                    model =
                        ImageRequest
                            .Builder(LocalContext.current)
                            .data(backdrop.imageUrl)
                            .transitionFactory(CrossFadeFactory(800.milliseconds))
                            .build(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.TopEnd,
                    modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .fillMaxHeight(.7f)
                            .fillMaxWidth(.7f)
                            .alpha(.95f)
                            .drawWithContent {
                                drawContent()
                                // Subtle top scrim for system UI readability (clock, tabs)
                                if (enableTopScrim) {
                                    drawRect(
                                        brush =
                                            Brush.verticalGradient(
                                                colorStops =
                                                    arrayOf(
                                                        0f to Color.Black.copy(alpha = TOP_SCRIM_ALPHA),
                                                        TOP_SCRIM_END_FRACTION to Color.Transparent,
                                                    ),
                                            ),
                                        blendMode = BlendMode.Multiply,
                                    )
                                }
                                drawRect(
                                    brush =
                                        Brush.horizontalGradient(
                                            colors = listOf(Color.Transparent, Color.Black),
                                            startX = 0f,
                                            endX = size.width * 0.6f,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                                drawRect(
                                    brush =
                                        Brush.verticalGradient(
                                            colors = listOf(Color.Black, Color.Transparent),
                                            startY = 0f,
                                            endY = size.height,
                                        ),
                                    blendMode = BlendMode.DstIn,
                                )
                            },
                )
            }
        }
        Timber.d("[NAV] Rendering NavDisplay with backStack: ${navigationManager.backStack}")
        NavDisplay(
            backStack = navigationManager.backStack,
            onBack = { navigationManager.goBack() },
            entryDecorators =
                listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
            entryProvider = { key ->
                Timber.d("[NAV] NavDisplay.entryProvider called for key: $key (backStack: ${navigationManager.backStack})")
                key as Destination
                val contentKey = "${key}_${server?.id}_${user?.id}"
                NavEntry(key, contentKey = contentKey) {
                    if (key.fullScreen) {
                        DestinationContent(
                            destination = key,
                            preferences = preferences,
                            onClearBackdrop = viewModel::clearBackdrop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else if (user != null && server != null) {
                        NavDrawer(
                            destination = key,
                            preferences = preferences,
                            user = user,
                            server = server,
                            onClearBackdrop = viewModel::clearBackdrop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        ErrorMessage("Trying to go to $key without a user logged in", null)
                    }
                }
            },
        )
    }
}
