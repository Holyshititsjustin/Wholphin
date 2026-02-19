import timber.log.Timber
import androidx.compose.runtime.Composable
import com.github.damontecres.wholphin.ui.nav.Destination

@Composable
fun DestinationContent(
    destination: Destination,
    navigation: (Destination) -> Unit,
    content: @Composable () -> Unit
) {
    Timber.i("[DIAG] DestinationContent called with destination=$destination")
    content()
    when (destination) {
        is Destination.PlaybackList -> {
            Timber.i("[DIAG] Navigating to PlaybackList: itemId=${destination.itemId}, startIndex=${destination.startIndex}, shuffle=${destination.shuffle}")
            navigation(destination)
        }
        is Destination.Playback -> {
            Timber.i("[DIAG] Navigating to Playback: itemId=${destination.itemId}, positionMs=${destination.positionMs}, forceTranscoding=${destination.forceTranscoding}")
            navigation(destination)
        }
        else -> {
            Timber.i("[DIAG] DestinationContent: no navigation for $destination")
        }
    }
}