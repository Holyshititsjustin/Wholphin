# Copilot Instructions for Wholphin

Wholphin is an Android TV client for Jellyfin built with Kotlin, Jetpack Compose, and MVVM architecture. This is a single-Activity app with complex native video playback capabilities and synchronized multi-client playback via SyncPlay.

## Architecture Overview

**Single Activity + MVVM Pattern**: App uses `MainActivity` as the sole Activity with Navigation 3 for screen navigation. ViewModels handle business logic and communicate with services/repositories via Hilt dependency injection.

**Technology Stack**:
- **UI**: Jetpack Compose + `androidx.tv.material3` (TV-specific focus/grid behavior)
- **Navigation**: Navigation 3 with manual backstack (`NavBackStack`) - see [ApplicationContent.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/nav/ApplicationContent.kt)
- **DI**: Hilt - ViewModels use `@HiltViewModel`, services use `@Singleton/@Inject` or `@ActivityScoped`
- **Data**: Room (local DB), DataStore + Protocol Buffers (settings, NOT SharedPreferences)
- **Media**: ExoPlayer/Media3 (default) or MPV (experimental, JNI-based)
- **Networking**: Jellyfin Kotlin SDK 1.7.1 + OkHttp with `AuthOkHttpClient` token injection
- **WebSocket**: OkHttp WebSocket for real-time SyncPlay messaging

**Package Structure**:
- `data/` - Room entities/DAOs, repositories, migrations, models
- `services/` - Hilt singletons: `NavigationManager`, `BackdropService`, `ServerRepository`, `SyncPlayManager`, etc.
- `ui/` - Feature-based Composables: `detail/`, `main/`, `setup/`, `preferences/`, `playback/`
- `preferences/` - Proto-based settings (proto3 definitions in `proto/WholphinDataStore.proto`)
- `api/` - Generated Seerr API client (OpenAPI spec: `seerr/seerr-api.yml`)

## Critical Patterns

### Proto-Based Settings (NOT SharedPreferences)
Add setting workflow:
1. Define in `app/src/main/proto/WholphinDataStore.proto` (e.g., `message PlaybackPreferences { int32 skipForwardMs = 1; }`)
2. Create `AppPreference` object in `AppPreference.kt` with UI definition
3. Register in a `PreferenceGroup` (defined in `AppPreference.kt`)
4. Set default in `AppPreferencesSerializer.kt::defaultValue()`
5. Handle upgrades in `AppUpgradeHandler.kt` if default differs from proto3 defaults (0/false/first enum)

**Example read/write**:
```kotlin
// Read (in Composables)
val preferences by AppPreferences.playbackPreferences.collectAsState()
val skipForwardMs = preferences.skipForwardMs

// Write (in ViewModel or service)
AppPreferences.updatePlaybackPreferences { skipForwardMs = 10000 }

// Nested write (e.g., SyncPlayPreferences inside InterfacePreferences)
AppPreferences.updateInterfacePreferences {
    syncplayPreferences = syncplayPreferences.toBuilder().apply { 
        enableSyncplay = true 
    }.build()
}
```

**Proto3 caveat**: Proto3 defaults to 0/false/empty string/first enum. If your setting needs a non-zero default (e.g., `skipForwardMs = 10000`), you MUST set it in BOTH:
1. `AppPreferencesSerializer.kt::defaultValue` (for new installs)
2. `AppUpgradeHandler.kt` (for upgrades from versions before the setting existed)

### ViewModel Assisted Injection
Many ViewModels inject screen parameters via assisted factories:
```kotlin
@HiltViewModel(assistedFactory = MovieViewModel.Factory::class)
class MovieViewModel @AssistedInject constructor(
    private val api: ApiClient,
    @Assisted val itemId: UUID,
) : ViewModel() {
    @AssistedFactory
    interface Factory { fun create(itemId: UUID): MovieViewModel }
}

// In Composable:
val viewModel: MovieViewModel = hiltViewModel<MovieViewModel, MovieViewModel.Factory>(
    creationCallback = { factory -> factory.create(destination.itemId) }
)
```

### Navigation (Custom, NOT Compose Navigation)
- `NavigationManager` singleton controls app navigation (`SetupNavigationManager` for setup flow)
- Destinations: sealed classes with `@Serializable` in [Destination.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/nav/Destination.kt)
- Backstack: manual `NavBackStack` management in [ApplicationContent.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/nav/ApplicationContent.kt)
- **Navigate**: `navigationManager.navigateTo(Destination.MediaItem(itemId, type))` or `navigateToFromDrawer()` for drawer items

### Room Database Migrations
Required workflow for schema changes:
1. Modify entity in `data/` package
2. Increment version in [AppDatabase.kt](../app/src/main/java/com/github/damontecres/wholphin/data/AppDatabase.kt)
3. Create migration in `Migrations.kt` (see existing for patterns)
4. Add test in [TestDbMigrations.kt](../app/src/androidTest/java/com/github/damontecres/wholphin/test/TestDbMigrations.kt)
5. Build generates schema JSON in `app/schemas/com.github.damontecres.wholphin.data.AppDatabase/`

### Native Components (MPV Player)
JNI code in `app/src/main/jni/` interfaces with libmpv. Build only needed for MPV development:
```bash
export NDK_PATH=~/Library/Android/sdk/ndk/29.0.14206865
cd scripts/mpv && ./get_dependencies.sh && pip install meson jsonschema
PATH="$PATH:$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin" ./buildall.sh --arch arm64 mpv
cd ../.. && env PREFIX64="$(realpath scripts/mpv/prefix/arm64)" "$NDK_PATH/ndk-build" -C app/src/main -j
```
**Most contributors don't need this** - prebuilt libs cached. FFmpeg decoder AAR at `libs/lib-decoder-ffmpeg-release.aar` (optional).

## Build & Development

**Standard Workflow**:
```bash
./gradlew assembleDebug          # Build debug APK
./gradlew installDebug           # Install to device/emulator
./gradlew connectedAndroidTest   # Run instrumented tests (migration tests)
```

**Versioning**: Version code = git tag count, version name = `git describe --tags`. Multi-APK build for armeabi-v7a/arm64-v8a + universal APK.

**Code Style**: ktlint enforced (version in `.pre-commit-config.yaml`). Setup: `pre-commit install`. Also add Compose-specific rules: https://mrmans0n.github.io/compose-rules/ktlint/

**Environment**: Android Studio Ladybug+ (check AGP 8.13.2 compatibility), JDK 11, minSdk 23, compileSdk 36.

**PowerShell Commands** (Windows):
```powershell
# Build and install (separate commands, not chained with &&)
.\gradlew.bat assembleDebug; .\gradlew.bat installDebug

# ADB testing for TV navigation
adb shell input keyevent KEYCODE_DPAD_CENTER  # Select
adb shell input keyevent KEYCODE_DPAD_LEFT    # Navigate left

# Logcat filtering (PowerShell native)
adb logcat -c  # Clear logs first
adb logcat -s Timber:* | Select-String "error|exception" -CaseSensitive:$false

# Multiple device testing
adb devices  # List connected devices
adb -s <serial> logcat  # Target specific device

# File operations
adb pull /sdcard/Android/data/com.github.damontecres.wholphin.debug/files/logs/  # Pull logs
adb push local_file.txt /sdcard/  # Push test files

# App management
adb uninstall com.github.damontecres.wholphin.debug
adb install -r app\build\outputs\apk\debug\app-debug.apk  # -r for reinstall
```

### Windows Development Tips

- **Path Separators**: Use forward slashes `/` in code, backslashes `\` only in Windows-specific scripts
- **NDK Path**: `$env:NDK_PATH = "C:\Users\<user>\AppData\Local\Android\Sdk\ndk\29.0.14206865"`
- **Gradle Daemon**: If builds hang, kill daemon: `Get-Process java | Where-Object {$_.MainWindowTitle -match 'Gradle'} | Stop-Process`
- **ADB Server Issues**: `adb kill-server; adb start-server`
- **File Encoding**: Ensure .kt files are UTF-8 without BOM (VS Code default)
- **Git Line Endings**: Set `git config core.autocrlf true` for CRLF→LF conversion
- **Logcat Buffering**: Use `Select-String -AllMatches` for better real-time output
- **Environment Variables**: Set in PowerShell profile (`$PROFILE`) for persistence:
  ```powershell
  $env:ANDROID_HOME = "C:\Users\<user>\AppData\Local\Android\Sdk"
  $env:PATH += ";$env:ANDROID_HOME\platform-tools"
  ```

## Common Tasks

**Add Screen**:
1. Add destination to [Destination.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/nav/Destination.kt) sealed class
2. Create ViewModel in `ui/<feature>/` with `@HiltViewModel`
3. Register route in navigation (see [ApplicationContent.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/nav/ApplicationContent.kt) `when` blocks)
4. Use `hiltViewModel()` in Composable

**ViewModel State Management Patterns**:
```kotlin
// Use MutableLiveData for UI state (legacy pattern in codebase)
val loading = MutableLiveData<LoadingState>(LoadingState.Loading)

// Use MutableStateFlow for modern reactive state
val currentState = MutableStateFlow<State>(State.Initial)

// Background loading pattern (show stale data while refreshing)
val backgroundLoading = MutableStateFlow(false)  // Separate from initial load

// Update LiveData from coroutine
suspend fun MutableLiveData<T>.setValueOnMain(value: T) {
    withContext(Dispatchers.Main) { this@setValueOnMain.value = value }
}

// Launch coroutines in ViewModel
viewModelScope.launchIO {  // Extension function, uses Dispatchers.IO
    // Network/DB work here
}
```

**Cleanup Pattern** - ViewModels automatically cleaned up, but for resources:
```kotlin
@HiltViewModel
class MyViewModel @Inject constructor() : ViewModel() {
    init {
        viewModelScope.launchIO {
            addCloseable { /* cleanup code */ }  // Called on ViewModel clear
        }
    }
}
```

**Add API Endpoint**:
- Jellyfin: use injected `ApiClient` from SDK (org.jellyfin.sdk)
- Seerr: modify `app/src/main/seerr/seerr-api.yml`, rebuild to regenerate client

## Key Services (Hilt Singletons)

Critical services injected throughout app:
- `ServerRepository` - Current Jellyfin server/user, session management
- `NavigationManager` - App navigation state machine
- `BackdropService` - Background image state (Flow-based)
- `ItemPlaybackRepository` - Playback position persistence
- `StreamChoiceService` - Audio/subtitle track selection
- `DeviceProfileService` - Jellyfin device capabilities (codecs, containers)
- `PlayerFactory` - Creates ExoPlayer/MPV instances
- `ThemeSongPlayer` - Background theme music
- `SyncPlayManager` (@ActivityScoped) - Synchronized playback orchestration (WebSocket + Jellyfin API)

### SyncPlay Integration Pattern

`SyncPlayManager` is `@ActivityScoped` and injected in `MainActivity`, not globally available:

```kotlin
// In MainActivity
@Inject
lateinit var syncPlayManager: SyncPlayManager

// In Composables - access via MainActivity reference
val context = LocalContext.current
val syncPlayManager = (context as? MainActivity)?.syncPlayManager

// Listen to state
val isSyncPlayActive by syncPlayManager?.isSyncPlayActive?.collectAsState() ?: mutableStateOf(false)
val groupMembers by syncPlayManager?.groupMembers?.collectAsState() ?: mutableStateOf(emptyList())

// Respond to playback commands from server
val syncPlayCommand by syncPlayManager?.playbackCommands?.collectAsState()
LaunchedEffect(syncPlayCommand) {
    when (val cmd = syncPlayCommand) {
        is SyncPlayCommand.Play -> { /* Navigate to playback */ }
        is SyncPlayCommand.Pause -> player.pause()
        // ...
    }
}
```

**SyncPlay lifecycle**: `SyncPlayManager` maintains persistent WebSocket connection for group discovery even when not actively in a group. Enable/disable via preferences triggers connection setup in `ApplicationContent.kt`.

**Key files**: [SyncPlayManager.kt](../app/src/main/java/com/github/damontecres/wholphin/services/SyncPlayManager.kt), [ApplicationContent.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/nav/ApplicationContent.kt), [PlaybackPage.kt](../app/src/main/java/com/github/damontecres/wholphin/ui/playback/PlaybackPage.kt)

## Threading & Concurrency Patterns

**Critical**: Media3/ExoPlayer requires main thread access for player state:

```kotlin
// ❌ WRONG - causes IllegalStateException
viewModelScope.launch(Dispatchers.IO) {
    val pos = player.currentPosition  // Crashes!
}

// ✅ CORRECT - switch to main thread
viewModelScope.launch(Dispatchers.IO) {
    val pos = withContext(Dispatchers.Main.immediate) {
        player.currentPosition  // Safe
    }
}

// ✅ Also works for already main-scoped
LaunchedEffect(key) {
    val pos = player.currentPosition  // LaunchedEffect runs on main by default
}
```

**Player Threading Rules**:
- `player.currentPosition`, `player.duration`, `player.isPlaying` → **Main thread only**
- `player.play()`, `player.pause()`, `player.seekTo()` → **Main thread only**  
- Room database operations → **IO dispatcher** (never main)
- Network calls (ApiClient) → **IO dispatcher** via `viewModelScope.launchIO`

**MPV vs ExoPlayer**:
- MPV: Uses internal thread pool, most operations safe from any thread
- ExoPlayer: Strict main thread requirement for all player interactions
- When supporting both, always use `Dispatchers.Main.immediate` for player access

## Gotchas & Quirks

- **Navigation 3**: Custom serialization, NOT `androidx.navigation.compose`. Don't import Compose Navigation.
- **TV-First UI**: D-pad navigation only. Test with `adb shell input keyevent KEYCODE_DPAD_*`. Focus management via `androidx.tv.foundation`.
- **Background Loading Pattern**: ViewModels use separate `backgroundLoading` state for refresh-while-showing-data (distinct from initial `loading`).
- **Proto3 Defaults**: Proto3 defaults to 0/false/empty/"first enum". Set explicit defaults in `AppPreferencesSerializer` or handle in `AppUpgradeHandler`.
- **FFmpeg Module**: Build checks `libs/lib-decoder-ffmpeg-release.aar`, graceful fallback if missing. AV1 module similar.
- **Compose TV Material**: Uses `androidx.tv.material3`, NOT `androidx.compose.material3`. Button focus, grids, layouts differ.
- **SyncPlay**: **EXPERIMENTAL - NOT YET FUNCTIONAL**. Implementation exists but syncing/playback not working. See testing section below for debugging approach.
- **Activity-Scoped Services**: `SyncPlayManager` is `@ActivityScoped` (not singleton). Access via `MainActivity` instance, not direct injection in Composables.
- **Versioning**: Version code = git tag count (`git tag --list "v*" | wc -l`), version name = `git describe --tags --long --match=v*`

## External Dependencies

- **Jellyfin Server**: 10.10.x or 10.11.x required (tested on 10.11)
- **Seerr**: Optional integration for content discovery
- **Build Tools**: JDK 11+, Android SDK 23+ (minSdk), SDK 36 (compileSdk)

## Testing

### Standard Tests
- **Unit Tests**: Standard JUnit + Mockk in `test/`
- **Instrumented Tests**: `androidTest/` - primarily Room migration tests in [TestDbMigrations.kt](../app/src/androidTest/java/com/github/damontecres/wholphin/test/TestDbMigrations.kt)
- **TV Testing**: Connect Android TV device or use emulator (API 23+). D-pad testing essential.

### SyncPlay Debugging (Current Status: Non-Functional)

**Problem**: SyncPlay UI/preferences exist but no actual syncing occurs between clients.

**Debug Checklist**:

1. **WebSocket Connection** - Check if WebSocket establishes:
   ```powershell
   # Clear logs first, then filter for WebSocket messages
   adb logcat -c
   adb logcat -s Timber:* | Select-String "WebSocket|SyncPlay|Connected|CONNECTED"
   ```
   Look for: `WebSocket CONNECTED`, `WebSocket message received`, connection state changes

2. **Position Reporting** - Verify player position is being captured:
   ```powershell
   adb logcat -s Timber:* | Select-String "startPositionReporting|reportPosition|currentPosition"
   ```
   Key issue: `player.currentPosition` must be called on **Main thread** (see [PlaybackPage.kt line 197](../app/src/main/java/com/github/damontecres/wholphin/ui/playback/PlaybackPage.kt)):
   ```kotlin
   syncPlayManager.startPositionReporting {
       withContext(Dispatchers.Main.immediate) {
           player.currentPosition  // ExoPlayer requires main thread access
       }
   }
   ```

3. **Command Reception** - Check if server commands reach client:
   ```powershell
   adb logcat -s Timber:* | Select-String "SyncPlayCommand|Pause|Unpause|Seek"
   ```
   Expected flow: Server sends → WebSocket receives → `_playbackCommands` emits → `PlaybackPage` reacts

4. **Jellyfin Server Version** - Confirm server supports SyncPlay:
   - Required: Jellyfin 10.10.x or 10.11.x
   - Check: Settings → Dashboard → About

5. **Common Issues**:
   - **Threading errors**: ExoPlayer/MPV `player.currentPosition` must use `Dispatchers.Main.immediate`
   - **WebSocket not connecting**: Check `AuthOkHttpClient` token injection and server URL format
   - **Commands not applied**: Verify `syncPlayCommand` LaunchedEffect in `PlaybackPage.kt` is triggering
   - **ActivityScoped lifecycle**: `SyncPlayManager` only exists while `MainActivity` is alive

6. **Manual Testing Steps**:
   ```powershell
   # First, list connected devices
   adb devices
   
   # Terminal 1 - Device A logs (replace <serial> with actual device ID)
   adb -s <device_A_serial> logcat -c
   adb -s <device_A_serial> logcat -s Timber:* | Select-String "SyncPlay|WebSocket"
   
   # Terminal 2 - Device B logs (in separate PowerShell window)
   adb -s <device_B_serial> logcat -c
   adb -s <device_B_serial> logcat -s Timber:* | Select-String "SyncPlay|WebSocket"
   
   # Create group on Device A, join on Device B
   # Play video on Device A - Device B should mirror actions
   ```

7. **Verify Core Components**:
   - [ ] `SyncPlayManager` constructor called (search logs for "SyncPlayManager initialized")
   - [ ] WebSocket URL correct: `ws(s)://server/socket?api_key=...&deviceId=...`
   - [ ] Position reporting job running (`startPositionReporting` called)
   - [ ] PlaybackPage receives commands (`LaunchedEffect(syncPlayCommand)` triggers)

**Likely Root Causes**:
- Server not broadcasting commands to group members
- Client not sending position updates to server (check `reportPlaybackPosition` implementation)
- Threading violations causing silent failures in position capture
- WebSocket message parsing errors (check message format matches SDK expectations)

## References

- [DEVELOPMENT.md](../DEVELOPMENT.md) - Detailed setup, build scripts, native components
- [CONTRIBUTING.md](../CONTRIBUTING.md) - PR guidelines, code of conduct
- [docs/SYNCPLAY_IMPLEMENTATION.md](../docs/SYNCPLAY_IMPLEMENTATION.md) - SyncPlay protocol research

````