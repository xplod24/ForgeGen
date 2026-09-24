# ForgeGen Memory & Project Learnings

This file maintains the ongoing memory, architectural decisions, and user preferences for the ForgeGen project. It should be consulted and updated regularly.

## 1. Architectural Decisions & Code Structure
- **State Management:** All settings and app state variables are consolidated in `ForgeModels.kt` (specifically `AppConfig` and `AppState` data classes).
- **Settings Persistence:** Managed centrally by `ForgeSettingsManager.kt`, stored in the Room `app_settings` table.
- **Queue & Notifications:** Managed by `ForgeQueueManager.kt`. 
- **Foreground Service:** `GenerationService.kt` runs the persistent foreground notification. `ACTION_QUEUE_FINISHED` means "the queue stopped" (empty or paused): the service stops, or shows Ready/Paused in persistent mode. "Exit App" is handled by the service (kills the process); MainActivity only closes its task.
- **Notifications:** always go through `ForgeNotifications` (channels created at app start: `forge_high` errors, `forge_default` finished jobs, `forge_low` silent progress; fixed ids for service/queue alert/gallery/Civitai). Each finished job produces at most one notification (error alert, queue completed or batch completed).
- **Reopening:** when the process outlives the activity, `initializeApp` only starts the new ViewModel's `ForgeNetworkManager`; that manager fetches lists itself if already connected (isConnected emits only on changes).
- **Updates:** `ForgeUpdateManager.kt` reads `releases/latest` of `xplod24/ForgeGen` through `GitHubApi` (public repo, no token), offers releases tagged `v<major>.<minor>.<patch>` whose versionCode (`versionCodeFromTag`) is higher than the installed one, downloads the `.apk` asset and checks the SHA-256 `digest` GitHub reports.
- **Versioning:** the version is `VERSION_MAJOR/MINOR/PATCH` in `gradle.properties` (started at 1.0.0). `versionName = "major.minor.patch"`, `versionCode = major*1_000_000 + minor*1_000 + patch` (minor/patch < 1000); `versionCodeFromTag` in `ForgeModels.kt` must use the same formula. Builds up to build-1034 used 1000 + commit count.
- **Signing:** debug builds are signed with the committed `app/debug.keystore` (password `android`) so local and CI builds can update each other. Changing the key forces users to uninstall.
- **Package:** `applicationId = io.github.xplod24.forgegen` (debug: `.debug`, label "ForgeGen"); the code namespace stays `com.example.forgegen`. Changed in build-1033 because builds signed with the old Android Studio key used `com.example.forgegen.debug`; changing it again makes a separate app without the user's data.
- **Releasing:** raise the version in `gradle.properties`, add a `## <version>` section at the top of `CHANGELOG.md` (the release notes) and push to master. `.github/workflows/release.yml` tests, builds and publishes `v<version>` with `app-debug.apk` only if that tag does not exist yet and is newer than the last `v*` tag; other pushes just test and build. `ci.yml` only tests PRs and must never publish a release (it would become "latest"). The session cannot push tags, the workflow creates them.
- **Selected checkpoint:** single source of truth is `ForgeModelManager.selectedModel`. `ForgeNetworkManager` (UI lists, `changeCheckpoint`) writes to it and `ForgeQueueManager` reads it for `override_settings`. Never keep a second copy.
- **Model/sampler/LoRA lists:** fetched only by `ForgeNetworkManager` (on connect and on URL change). `ForgeRepository` just rebuilds its Retrofit instance when the URL or timeout changes.
- **Generation state:** `ForgeQueueManager` owns progress, ETA, live preview, status and the queue. The ping loop in `ForgeRepository` pushes server progress via `ForgeQueueManager.updateExternalProgress()`.
- **Timeouts:** the "Connection Timeout" setting applies to ordinary API calls only; `ForgeSettingsManager.createClient` gives `sdapi/v1/txt2img` a 120 min read timeout.
- **Queue pauses:** use `ForgeQueueManager.pauseQueue(reason)`; the UI card in `OomAlertSection` shows the reason and a Resume button for any pause. A queue that becomes empty is unpaused automatically.
- **PNG metadata:** always read through `PngMetadata.readParameters` (tEXt = Latin-1, iTXt = UTF-8, optionally zlib-compressed).
- **Settings persistence:** `loadConfig` must list every `AppConfig` field (covered by `ForgeSettingsManagerConfigTest`); DB writes go through a single-threaded dispatcher to keep their order. `timeout` is clamped to 1–600 s on load and save.
- **ForgeRepository** only owns the database, the Retrofit client, the ping loop (connection, RAM/VRAM, external jobs) and the service toggle. Queue, gallery, models and prompts live in their managers; don't add delegating copies back.
- **Prompt tag helpers** (`parseTags`, `splitTagWeight`, `withTagWeight`, `adjustTagStrength`) live in the Compose-free `ui/components/PromptTags.kt` (tested by `PromptTagsTest`). LoRA tags are parsed only by `parseActiveLoras` in `ForgeRepository.kt`.
- **Notification modes:** the strings in `GenerationService` must match the options in `SetupScreen` ("Simple", "Verbose", "Disabled").
- **Dead code:** `ForgeModels.kt` has `@file:Suppress("unused")` (for Gson DTO fields), so the IDE won't flag unused classes or DAO methods there; check references by hand.

## 2. User Preferences & UI Principles
- **Releases (owner's standing request):** after finishing a change, Claude publishes the release itself: bump `gradle.properties` (patch for fixes and small changes, minor for new features, major only for a clear change across the whole repository OR on the owner's explicit command), add the `## <version>` section to `CHANGELOG.md` and push to master. Release notes are written for the user of the app, in English like the rest of the UI.
- **Animations:** every enter animation needs a matching exit. Full-screen overlays in `MainActivity` use `AnimatedVisibility` with a 200 ms fade (`OVERLAY_FADE_MS`) and `rememberLastActive` so the final state (tick/cross) stays visible while fading out. Don't read an animating value in composition (e.g. as a `LaunchedEffect` key): that recomposes on every frame.
- **Intrusiveness:** The app must NEVER interrupt the user with random Toasts or pop-up Alert Dialogs during normal use (especially for updates).
- **Silent Background Checks:** App update checks happen silently in the background. The user is notified via an inline banner in the Settings/Setup Screen, not via a popup.
- **UI Blocking for Critical Tasks:** When downloading an update, the UI must be completely blocked using `DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false)` to prevent interference.
- **Tag Editors:** The active tags UI uses a sleek collapsible design (`AnimatedVisibility`) driven by a horizontal separator to save space while keeping it accessible.
- **Clean Settings:** Deprecated features (like Image Previews in notifications and Alert Priorities) are completely ripped out of the backend code, not just hidden from the UI.

## 3. Recent Milestones
- **Builds 195-198:** Extracted settings logic from `ForgeRepository` into `ForgeSettingsManager`, modernized the update flow, implemented SHA-256 validation for existing APKs, and polished the active tags UI.
- All empty legacy directories (`data`, `domain/models`) and temporary scripts have been cleaned up and ignored via `.gitignore`.
- **Testing Architecture:** Integrated `io.mockk:mockk` and `kotlinx-coroutines-test` into the `testImplementation` to allow comprehensive testing of `ForgeQueueManager` (and future managers) without needing an emulator or physical device.
- **Code Formatting:** Downloaded `ktlint.jar` to the project root. It can be run via `java -jar ktlint.jar -F "app/src/**/*.kt"` to auto-format all Kotlin files and remove unused imports.
- **Build 277 fixes:** see CHANGELOG.md (generation timeout, checkpoint override, progress, settings persistence, lock on cold start, PNG metadata, queue pause UX). Unit tests: `PngMetadataTest`, `ForgeSettingsManagerConfigTest`; `ForgeUpdateManagerTest` fixed to the list-based changelog.

## 4. Current Outstanding Tasks
- The Infinite Image Browsing cookie (`IIB_S=...`) is hard-coded in `ForgeApi`, `ForgeNetworkManager`, `ForgeSettingsManager` and `SetupScreen`; it should become a setting.
- `app/release/` build outputs and `ktlint.jar` (80 MB) are tracked in git on purpose (owner's choice for this hobby repo); don't untrack them without asking.
