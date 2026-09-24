# ForgeGen Memory & Project Learnings

This file maintains the ongoing memory, architectural decisions, and user preferences for the ForgeGen project. It should be consulted and updated regularly.

## 1. Architectural Decisions & Code Structure
- **State Management:** All settings and app state variables are consolidated in `ForgeModels.kt` (specifically `AppConfig` and `AppState` data classes).
- **Settings Persistence:** Managed centrally by `ForgeSettingsManager.kt`, stored in the Room `app_settings` table.
- **Queue & Notifications:** Managed by `ForgeQueueManager.kt`. 
- **Foreground Service:** `GenerationService.kt` runs the persistent foreground notification.
- **Updates:** `ForgeUpdateManager.kt` reads `releases/latest` of `xplod24/ForgeGen` through `GitHubApi` (public repo, no token), offers releases tagged `build-<versionCode>` newer than the installed build, downloads the `.apk` asset and checks the SHA-256 `digest` GitHub reports.
- **Versioning:** `versionCode = 1000 + git commit count` (`app/build.gradle.kts`), `versionName = "build-<versionCode>"`. Same commit = same code locally and on CI; needs a full clone (CI uses `fetch-depth: 0`).
- **Signing:** debug builds are signed with the committed `app/debug.keystore` (password `android`) so local and CI builds can update each other. Changing the key forces users to uninstall.
- **Releasing:** `.github/workflows/release.yml` runs the unit tests, builds `app-debug.apk` and publishes release `build-<versionCode>` on every push to master (Markdown-only changes skipped), on a pushed `build-<versionCode>` tag, or by hand. Release notes = first block of `CHANGELOG.md` (up to the first empty line), so start a new block at the top after each release. `ci.yml` only tests PRs and must never publish a release (it would become "latest").
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
