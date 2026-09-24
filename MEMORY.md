# ForgeGen Memory & Project Learnings

This file maintains the ongoing memory, architectural decisions, and user preferences for the ForgeGen project. It should be consulted and updated regularly.

## 1. Architectural Decisions & Code Structure
- **State Management:** All settings and app state variables are consolidated in `ForgeModels.kt` (specifically `AppConfig` and `AppState` data classes).
- **Settings Persistence:** Managed centrally by `ForgeSettingsManager.kt`, which handles DataStore read/writes.
- **Queue & Notifications:** Managed by `ForgeQueueManager.kt`. 
- **Foreground Service:** `GenerationService.kt` runs the persistent foreground notification.
- **Updates:** `ForgeUpdateManager.kt` checks for updates and verifies SHA-256 hashes before installation. 
- **Selected checkpoint:** single source of truth is `ForgeModelManager.selectedModel`. `ForgeNetworkManager` (UI lists, `changeCheckpoint`) writes to it and `ForgeQueueManager` reads it for `override_settings`. Never keep a second copy.
- **Model/sampler/LoRA lists:** fetched only by `ForgeNetworkManager` (on connect and on URL change). `ForgeRepository` just rebuilds its Retrofit instance when the URL or timeout changes.
- **Generation state:** `ForgeQueueManager` owns progress, ETA, live preview, status and the queue. The ping loop in `ForgeRepository` pushes server progress via `ForgeQueueManager.updateExternalProgress()`.
- **Timeouts:** the "Connection Timeout" setting applies to ordinary API calls only; `ForgeSettingsManager.createClient` gives `sdapi/v1/txt2img` a 120 min read timeout.
- **Queue pauses:** use `ForgeQueueManager.pauseQueue(reason)`; the UI card in `OomAlertSection` shows the reason and a Resume button for any pause. A queue that becomes empty is unpaused automatically.
- **PNG metadata:** always read through `PngMetadata.readParameters` (tEXt = Latin-1, iTXt = UTF-8, optionally zlib-compressed).
- **Settings persistence:** `loadConfig` must list every `AppConfig` field (covered by `ForgeSettingsManagerConfigTest`); DB writes go through a single-threaded dispatcher to keep their order.

## 2. User Preferences & UI Principles
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
- The Infinite Image Browsing cookie (`IIB_S=...`) is hard-coded in `ForgeApi`, `ForgeNetworkManager`, `ForgeRepository` and `ForgeSettingsManager`; it should become a setting.
- `app/release/` build outputs and `ktlint.jar` (80 MB) are tracked in git; consider untracking them.
