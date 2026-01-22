# Copilot Instructions for Wholphin

Wholphin is an Android TV client for Jellyfin built with Kotlin, Jetpack Compose, and MVVM architecture. This is a single-Activity app with complex native video playback capabilities.

## Architecture Overview

**Single Activity + MVVM Pattern**: App uses `MainActivity` as the sole Activity with Navigation 3 for screen navigation. ViewModels handle business logic and communicate with services/repositories via Hilt dependency injection.

**Key Technology Stack**:
- **UI**: Jetpack Compose with TV-specific components (`androidx.tv.foundation`, `androidx.tv.material`)
- **Navigation**: Navigation 3 (not the standard Compose Navigation) - see `ApplicationContent.kt` for backstack management
- **DI**: Hilt for dependency injection - all ViewModels use `@HiltViewModel`, services use `@Inject`
- **Data**: Room for local storage, DataStore + Protocol Buffers for settings (not SharedPreferences)
- **Media**: Two playback backends - ExoPlayer/Media3 (default) or MPV (experimental, requires native build)
- **Networking**: Jellyfin Kotlin SDK + OkHttp

**Package Structure**:
- `data/` - Room entities, DAOs, repositories, migrations
- `services/` - Hilt-injectable services (BackdropService, NavigationManager, StreamChoiceService, etc.)
- `ui/` - Composables organized by feature (detail/, main/, setup/, preferences/, etc.)
- `preferences/` - Proto-based settings system (not traditional Android preferences)
- `util/` - Helper classes, extensions
- `api/` - Generated API clients (Seerr integration)

## Critical Development Patterns

### Settings System (Non-Standard)
Settings use Protocol Buffers via DataStore, NOT SharedPreferences. To add a setting:
1. Define in `app/src/main/proto/WholphinDataStore.proto`
2. Add `AppPreference` object in `AppPreference.kt`
3. Register in a `PreferenceGroup`
4. Set default in `AppPreferencesSerializer.kt`
5. Handle upgrades in `AppUpgradeHandler.kt` if needed

Example: `AppPreferences.updatePlaybackPreferences { skipForwardMs = value }`

### ViewModel Pattern with Assisted Injection
Many ViewModels use Hilt assisted injection for parameters:
```kotlin
@HiltViewModel(assistedFactory = MovieViewModel.Factory::class)
class MovieViewModel @AssistedInject constructor(
    private val api: ApiClient,
    @Assisted val itemId: UUID,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(itemId: UUID): MovieViewModel
    }
}

// Usage in Composable:
val viewModel: MovieViewModel = hiltViewModel<MovieViewModel, MovieViewModel.Factory>(
    creationCallback = { it.create(destination.itemId) }
)
```

### Navigation Management
- `NavigationManager` (app content) and `SetupNavigationManager` (initial setup) handle navigation
- Destinations defined as sealed classes in `Destination.kt`
- Backstack managed manually via `NavBackStack` - see `ApplicationContent.kt`
- Navigate via `navigationManager.navigateTo(Destination.Foo)` or `navigateToFromDrawer()` for drawer items

### Native Components (MPV)
JNI code in `app/src/main/jni/` interfaces with libmpv. Requires NDK build:
```bash
export NDK_PATH=/path/to/ndk/29.0.14206865
cd scripts/mpv && ./get_dependencies.sh
PATH="$PATH:$NDK_PATH/toolchains/llvm/prebuilt/darwin-x86_64/bin" ./buildall.sh --arch arm64 mpv
cd ../.. && env PREFIX64="$(realpath scripts/mpv/prefix/arm64)" "$NDK_PATH/ndk-build" -C app/src/main -j
```
Most contributors won't need to rebuild these - prebuilt libs are cached.

## Build & Development

**Standard Build**:
```bash
./gradlew assembleDebug        # Build debug APK
./gradlew installDebug         # Install to connected device
```

**Version Management**: Version code auto-increments from git tags count. Version name from `git describe --tags`.

**Code Style**: ktlint required - version in `.pre-commit-config.yaml`. Install pre-commit hooks: `pre-commit install`

**Testing**: Room migrations have dedicated tests in `androidTest/`. Run with: `./gradlew connectedAndroidTest`

## Common Tasks

**Add a new screen**: 
1. Create destination in `Destination.kt`
2. Add ViewModel in `ui/<feature>/` using `@HiltViewModel`
3. Register in navigation graph (see `ApplicationContent.kt` or setup navigation)
4. Use `hiltViewModel()` in Composable

**Database changes**:
1. Modify entity in `data/`
2. Increment version in `AppDatabase.kt`
3. Create migration in `Migrations.kt`
4. Add test in `TestDbMigrations.kt`
5. Update schema: build generates JSON in `app/schemas/`

**API Integration**:
- Jellyfin SDK accessed via injected `ApiClient`
- Seerr API generated from OpenAPI spec at `app/src/main/seerr/seerr-api.yml`
- Auth handled by `AuthOkHttpClient` with token injection

## Key Services (Hilt Singletons)

- `ServerRepository` - Current server/user state, session management
- `NavigationManager` - App navigation orchestration
- `BackdropService` - Background image management (uses Flow)
- `StreamChoiceService` - Audio/subtitle track selection logic
- `ItemPlaybackRepository` - Playback state persistence
- `ThemeSongPlayer` - Background theme music player
- `DeviceProfileService` - Jellyfin device capabilities reporting

## Gotchas

- **NOT standard Compose Navigation** - uses Navigation 3 with custom serialization
- **TV-first UI** - uses D-pad navigation patterns, not touch. Test with remote control or `adb shell input keyevent`
- **Background loading** - Many ViewModels use `backgroundLoading` separate from initial `loading` state for refresh-while-showing-data
- **Proto defaults** - Proto3 defaults to 0/false/empty, handle in `AppPreferencesSerializer` or `AppUpgradeHandler`
- **FFmpeg/AV1 modules** - Build checks for `libs/lib-decoder-ffmpeg-release.aar` existence, graceful fallback if missing
- **Compose TV Material** - Uses `androidx.tv.material3`, not standard Material3 (button focus, grids differ)

## External Dependencies

- Jellyfin Server 10.10.x/10.11.x required
- Optional Seerr server integration for content discovery
- Build requires JDK 11+ (matches Gradle and compile target)
- Android SDK 23+ (minSdk), compile against SDK 36

## Documentation
See [DEVELOPMENT.md](../DEVELOPMENT.md) for detailed setup and [CONTRIBUTING.md](../CONTRIBUTING.md) for PR guidelines.
