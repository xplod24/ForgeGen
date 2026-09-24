# ForgeGen Memory & Project Learnings

This file maintains the ongoing memory, architectural decisions, and user preferences for the ForgeGen project. It should be consulted and updated regularly.

## 1. Architectural Decisions & Code Structure
- **State Management:** All settings and app state variables are consolidated in `ForgeModels.kt` (specifically `AppConfig` and `AppState` data classes).
- **Settings Persistence:** Managed centrally by `ForgeSettingsManager.kt`, which handles DataStore read/writes.
- **Queue & Notifications:** Managed by `ForgeQueueManager.kt`. 
- **Foreground Service:** `GenerationService.kt` runs the persistent foreground notification.
- **Updates:** `ForgeUpdateManager.kt` checks for updates and verifies SHA-256 hashes before installation. 

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

## 4. Current Outstanding Tasks
- (None currently)
