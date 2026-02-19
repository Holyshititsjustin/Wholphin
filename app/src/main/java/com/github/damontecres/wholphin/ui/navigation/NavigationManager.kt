import timber.log.Timber
import javax.inject.Inject
import com.github.damontecres.wholphin.ui.nav.Destination

class NavigationManager @Inject constructor() {
    fun navigateTo(destination: Destination) {
        Timber.i("[DIAG] NavigationManager.navigateTo called with destination=$destination")
        // ...existing code...
    }
    fun goBack() {
        Timber.i("[DIAG] NavigationManager.goBack called")
        // ...existing code...
    }
}