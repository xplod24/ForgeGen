# ForgeGen Memory & Project Learnings

This file maintains the ongoing memory, architectural decisions, and user preferences for the ForgeGen project. It should be consulted and updated regularly.

## 1. Architectural Decisions & Code Structure
- **State Management:** All settings and app state variables are consolidated in `ForgeModels.kt` (specifically `AppConfig` and `AppState` data classes).
- **Settings Persistence:** Managed centrally by `ForgeSettingsManager.kt`, stored in the Room `app_settings` table.
- **Queue:** `ForgeQueueManager` has one worker (`startQueueWorker`) that waits for `nextJob()` (first job, queue not paused, server not busy), claims it atomically (status GENERATING) and runs `executeGeneration`; nothing else may start a job. The GENERATING job cannot be removed, moved or cleared. The queue is saved only by `startQueueWriter` (conflated channel). txt2img goes through `ForgeRepository.generationApi`, whose client has `retryOnConnectionFailure(false)`: OkHttp silently re-sent dropped txt2img requests. Connection loss never costs a job: `nextJob()` also waits for `isConnected`; a network error of the request (only `ConnectionLost`, not other IOExceptions such as a full disk) keeps the job first as SUSPENDED and pauses with `CONNECTION_LOST_REASON`; `startReconnectWatcher` resumes 5 s after the server is back and idle, at most `MAX_AUTO_RETRIES` (3) per job. `requestWithWatchdog` cancels a request orphaned by an outage (server back and idle for 10 s). HTTP errors from the server remove the job and pause the queue (overnight mode sets the job aside instead, see below); the reason (pause card and "Queue paused"/"Generation failed" alert, a BigTextStyle) carries the server's own text since 2.4.1: `serverError` reads Forge's error answer (`error`: `errors`, else FastAPI's `detail`, max 300 chars). A queue with no runnable job left is unpaused by `finishJob`, so a single failed job leaves no pause, only the alert. Every job is sent as `payload.forServer()`: with hires fix it adds `hr_additional_modules = ["Use same choices"]` (Forge's API fails the hires pass with TypeError/HTTP 500 without it; the owner's "Upscale" hit it in 2.4.0); it is not stored with the job, so old saved queues get it too. `generateImage` is `@Streaming` (`ResponseBody`): `readImages` reads the answer with a JsonReader and writes each image to the cache at once (never parse the whole answer into objects: a batch of big images took ~3x the memory). The watchdog stops once the server has answered. The ping asks for the live preview only while the main screen shows it and for memory stats every 5-10 s only while the meters show (3.4.0); `GenerationService` updates its notification from a flow, not a 1 s loop.
- **Foreground Service:** `GenerationService` (type `specialUse`: `dataSync` stops after 6 h a day on Android 15+, which cut overnight queues) runs only while `ForgeQueueManager.isQueueActive` (a job runs, or runnable jobs wait and the queue is not stopped; a `CONNECTION_LOST_REASON` pause counts). The queue starts it (`ACTION_START_GENERATION`) whenever `isQueueActive` turns true (`startServiceWatcher`, 2.3.0-2: before, only a job started it, so a queue waiting for the server since the app started, or for "Start at", had no service in the background) and again per job; it stops itself when the queue is no longer active (never before `startForeground`, which would crash), holds a non-reference-counted wake lock renewed every 5 min while active, and returns `START_NOT_STICKY` (a restarted empty service did nothing). "Run in Background" (a permanent idle service) was removed in 1.1.5 at the owner's request. "Exit App" is handled by the service (kills the process); MainActivity only closes its task.
- **Notifications:** always go through `ForgeNotifications` (channels created at app start: `forge_high` errors, `forge_default` finished jobs, `forge_low` silent progress; fixed ids for service/queue alert/gallery). Each finished job produces at most one notification (error alert, queue completed or batch completed).
- **Start ("Ready"):** `ForgeViewModel.initializeApp` runs once per ViewModel (a `Deferred` in `viewModelScope`, so a recreated screen waits for the same start) and returns when the phone's part is loaded, each step awaited: database/settings, wildcards (`ForgePromptManager.init`), API clients and ping, the saved queue (`ForgeQueueManager.start`), gallery favorites/index (`ForgeGalleryManager.start`), What's New; then `isInitialized` is set. The server's part (`awaitServer`, see "Start and connection (2.0.0)") runs after it without holding the screen: the first ping (`ForgeRepository.awaitServerCheck`, at most the connection timeout, capped at 10 s, + 1 s) and, when connected, the first fetch of the lists (`ForgeNetworkManager.awaitServerData`, at most 15 s); `awaitServerCheck()` of the ViewModel waits for it. The status is "Ready" only if the models and samplers were loaded; otherwise "Server not reachable", "Connected, but the model list failed to load" or "Connected, model lists still loading". The update check stays in the background.
- **Reopening:** when the process outlives the activity, `initializeApp` only starts the new ViewModel's `ForgeNetworkManager` and waits for the server as above; that manager fetches lists itself if already connected (isConnected emits only on changes).
- **No Civitai (1.6.1, owner's decision):** the app has no Civitai sync any more (model data belongs to the server). Model lists come only from the server (`/customapi/v1/all-models-hashes`, else `sd-models`/`loras`), with the server's names and previews; `MIGRATION_11_12` drops the old `civitai_models` table (`MIGRATION_10_11` still alters it on the way from older versions). Picking a LoRA adds `<lora:name:1.0>` to the prompt (`ForgeRepository.addLora`); the trigger-word dialogs, the NSFW/"Real person" labels and the sync overlay, settings and notifications are gone.
- **Out-of-memory logs:** `OomLogs` writes a report (reason, app/device/memory info, job settings without prompts, server answer, then the app's own `logcat -d --pid` streamed into the file) to Downloads as `ForgeGen-OOM-<date>.txt` through MediaStore (no permission needed on Android 10+), only when `AppConfig.saveOomLogs` is on (Settings > Backup & Data). Called for server OOM and the app's `OutOfMemoryError` in `executeGeneration`, and by the default uncaught-exception handler (`OomLogs.install`, from the ViewModel) for OOM crashes, which then continue to the previous handler.
- **Updates:** `ForgeUpdateManager.kt` reads `releases/latest` of `xplod24/ForgeGen` through `GitHubApi` (public repo, no token), offers releases tagged `v<major>.<minor>.<patch>` whose versionCode (`versionCodeFromTag`) is higher than the installed one, downloads the `.apk` asset and checks the SHA-256 `digest` GitHub reports.
- **Versioning:** the version is `VERSION_MAJOR/MINOR/PATCH/MICRO` in `gradle.properties` (started at 1.0.0). `versionName = "major.minor.patch"`, or `"major.minor.patch-micro"` for a micro-patch (micro > 0, owner's command only; reset to 0 when raising anything else). Since 1.1.4-1 `versionCode = major*100_000_000 + minor*100_000 + patch*100 + micro` (major <= 20, minor/patch < 1000, micro < 100); up to 1.1.4 it was `major*1_000_000 + minor*1_000 + patch`, and every new code is higher. `versionCodeFromTag` in `ForgeModels.kt` must use the same formula. The 1.1.4 updater cannot parse `-micro` tags, so 1.1.4 users install 1.1.4-1 by hand once. Builds up to build-1034 used 1000 + commit count. Only local (debuggable) builds end in `-DEBUG`; since 3.5.2 the published `-Pforgegen.publish` APK shows the plain version (owner: "DEBUG" misled users about a finished, signed build). Code that reads the installed version strips a `-DEBUG` (`ForgeViewModel.installedVersion` for What's New, `ForgeUpdateManager`'s "up to date" toast).
- **Signing:** ForgeGen's own key (PKCS12, alias `forgegen`, cert SHA-256
  `22c6e6add4c03e59b4a7106a6036f4d8781ef7c7559340f87909d6318261c06d`, made 2026-09-24): never in the repo; the owner
  has it (backup zip with password and the old lineage, 2026-09-30) and it is in the repository secrets
  `RELEASE_KEYSTORE_BASE64` / `RELEASE_KEYSTORE_PASSWORD` (Actions, repository level; added by the owner, the session
  cannot read or set secrets). `tools/sign-apk.sh` re-signs the built APK with that key alone (`apksigner sign
  --v1-signing-enabled false`: a v3 block only, as minSdk is 31) and checks that the only signer is that key and that
  there is no lineage (newer apksigner prints "V3.0 Signer: certificate ...", older "Signer #1 certificate ...").
  release.yml runs it before publishing (a missing or wrong secret stops the release), ci.yml on every push to a
  work branch. Local builds: with `RELEASE_KEYSTORE_FILE` / `RELEASE_KEYSTORE_PASSWORD` set, Gradle signs the debug
  builds with that key; otherwise with the committed `app/debug.keystore` (password `android`, alias
  `androiddebugkey`, cert `0dc1e860…`), and those do not install over the published app. Never commit `*.p12` /
  `*.jks` (.gitignore). History: up to 3.5.0 releases were signed with the public debug key (anyone could sign an
  update); 3.5.1 to 3.5.2-1 (package `.debug`) by v3 rotation debug -> release key (lineage without the rollback
  capability; the owner confirmed 3.5.1 updated fine); since 3.5.2-2 (new package) the release key alone, and
  `app/signing/forgegen-lineage.bin` was removed.
- **Package:** `applicationId = io.github.xplod24.forgegen` for every build since 3.5.2-2 (owner's command 2026-09-30: "debug" in the package hurt the eye; label "ForgeGen"); the code namespace stays `com.example.forgegen`. Up to 3.5.2-1 the debug build type added `.debug` (the published app was `io.github.xplod24.forgegen.debug`), and before build-1033 it was `com.example.forgegen`. Changing it again makes another separate app.
- **Move to the package without `.debug` (owner's command 2026-09-30, then the owner chose this "bridge"):** a new package is a
  separate app for Android, and the 3.5.2 updater opens its PackageInstaller session with `setAppPackageName`, so it
  would fail on it again and again. So two micro-patches: **3.5.2-1** (still `.debug`, signed by rotation as 3.5.1)
  makes the backup carry the favorites (`FavoriteImageEntity`, read field by field) and the queue (`QueuedGeneration`;
  `ForgeQueueManager.importJobs` pauses the queue with `IMPORTED_REASON` first) as format 2, and teaches the updater
  the move: `ReadyUpdate.movesTo` = the downloaded APK's package when it is not this app's
  (`getPackageArchiveInfo`); then nothing installs it (`checkInBackground` returns, `installUpdate` refuses), one
  notification (`notifyMove`, `move_notified_version`), and Settings > Updates shows `UpdateMoveCard` (Export,
  Install = `SelfUpdate.installerIntent`, the system's installer; Open, once `isInstalled` sees it through the
  manifest's `<queries>`; Uninstall = the app info page, `ACTION_DELETE` would need a permission). **3.5.2-2** drops
  `applicationIdSuffix`, and `tools/sign-apk.sh` signs with the release key alone (no lineage: the public debug key
  keeps no say in the new app: `apksigner sign --ks <release> --v1-signing-enabled false`, then check a v3 signer
  22c6e6ad alone and that `apksigner lineage` finds none; remove `app/signing/forgegen-lineage.bin`), and updates
  build.gradle.kts comments, README (package, "Coming from 3.5.2-1 or older?" steps, signing) and this file; the
  manifest's `<queries>` went away with it (it named the app itself). Released after the owner confirmed 3.5.2-1
  (2026-09-30). The move code stays for a later move; the new app's first self-update asks for a confirmation once
  (the old app or the system's installer is its installer of record).
- **Releasing:** raise the version in `gradle.properties`, add a `## <version>` section at the top of `CHANGELOG.md` (the release notes, also shown in the app's "What's New" dialog) and push to master. `.github/workflows/release.yml` tests, builds, signs (`tools/sign-apk.sh`, 3.5.1) and publishes `v<version>` with `ForgeGen.apk` (`app-debug.apk` up to 3.5.2; the updater takes any `.apk` of a release) and, since 3.5.0, R8's `mapping.zip` only if that tag does not exist yet and is newer than the last `v*` tag; other pushes just test and build. `ci.yml` tests and builds pull requests and pushes to work branches (so a change is known to compile before master) and must never publish a release (it would become "latest"). AGP 9 creates unit tests only for the debug variant (`testDebugUnitTest`). The session cannot push tags, the workflow creates them.
- **What's New:** the build copies `CHANGELOG.md` into the assets (`copyAppAssets` in `app/build.gradle.kts`, which since 3.4.2 also copies `LICENSE`). After an update `ForgeViewModel.checkWhatsNew` takes the sections newer than the last version seen (`whats_new_last_version` setting; without it only the current version, and nothing after a fresh install) into `whatsNew` and sets `whatsNewBar`. Since 3.0.0 (owner's request) `WhatsNewBar` (MainActivity's overlay, top centre, once unlocked and past the start) floats half-transparent at the top: slides in, bobs (infinite transition, ±3 dp), shakes lightly every 5 s (`Animatable` in a `LaunchedEffect`), and after `WHATS_NEW_BAR_MS` = 30 s flies up off the screen (`hideWhatsNewBar`, which also drops the notes). The version counts as seen when the bar shows (`markWhatsNewSeen`). "Show" = `openWhatsNew` (`whatsNewOpen`) -> `WhatsNewDialog`, rendered by `Markdown.parse` + `MarkdownText`; OK = `dismissWhatsNew`. Debug "Show What's New" shows the bar again. Keep the changelog in simple Markdown (`## version`, `- ` items, **bold**, `code`, [links](url)). Since 3.0.0-4 (owner's rule, see CLAUDE.md) every section starts with `**<Kind>** · summary` (Bugfix, Polish, Feature, Overhaul) and has `### New` / `### Changed` / `### Fixed`; MarkdownTest checks it. Tests: G18.
  The "Update Available" card in Settings > Updates draws the release notes the same way since 3.0.0-2:
  `parseReleaseNotes` keeps the kind line, the `### ` headings and the `- ` items as Markdown lines (3.0.0-4),
  `releaseNotesMarkdown(notes, maxItems = 3)` -> `MarkdownText(textStyle = bodySmall)` (no heading left without an
  item), "Show All (releaseNoteCount)" -> `WhatsNewDialog(title = "What's New in <version>")`. Any text shown from the
  changelog goes through MarkdownText.
- **App lock:** state lives in `ForgeViewModel.isLocked` (on = `useNativeSecurity` and not unlocked since the activity last stopped; rotation does not lock). `MainActivity` draws the lock as a `Dialog` over the app and must never replace the UI tree (that recreated the NavController and restarted the app from "welcome"). Prompts go through `AppLock.authenticate` (phone PIN/pattern/password, plus BIOMETRIC_STRONG if allowed); a failure or cancel only closes the prompt. Changing the lock switches and wiping data require that check; without a phone lock the app unlocks instead of locking the user out. It hides the UI only, the data is not encrypted.
- **Gallery:** `ForgeGalleryManager` browses the server through Infinite Image Browsing (IIB). The grid uses IIB thumbnails (`image-thumbnail`, 512 px); image URLs must always carry `t` (IIB answers 422 without it). The local index (`gallery_images`) covers the gallery root (`galleryPath`) and is filled with generation data IIB reads on the PC (`image_geninfo_batch`, 100 per request; fallback `image_geninfo`, then the start of the PNG). Do not call IIB's `db/update_image_data`: it blocks the whole Forge server while it runs. Search and filters work on the index (whole gallery), folder browsing on the live listing. Infotext is parsed only by `Infotext.parse`. Saving to the phone goes through `DeviceImages` (Pictures/ForgeGen, name `<folder>_<file>`, duplicates detected by name); sharing uses temporary copies in `cache/shared` via the FileProvider. "Save to Phone Automatically": off / favorites / all new images (newer than `autoSaveSince`, Wi-Fi only). Pins were merged into favorites in 1.1.0.
- **Gallery key (3.5.0, owner's request: no key in the code):** IIB locked with `IIB_SECRET_KEY` (its `.env` or the
  environment) compares the cookie `IIB_S` with sha256(key + "_ciallo") (`scripts/iib/api.py` `verify_secret`; its own
  web page asks for the key on 401 and makes the cookie). No endpoint hands it out. `GalleryKey`: `fingerprint(key)`
  (key trimmed), kept per server in `AppConfig.galleryKeys` (server address lowercased without the trailing "/" ->
  hash; never the key; `Backup.write` leaves them out and an import keeps the phone's). `ForgeSettingsManager.createClient`
  sends `Cookie: IIB_S=<hash>` only to gallery paths (`GALLERY_PREFIXES`) and only when one is saved; a gallery 401 with
  `detail.type` "secret_verification_failed" calls `GalleryKey.reportRefused()` and `ForgeGalleryManager` asks the
  extension again. `askForExtension`: 401 + that type -> `Extension.LOCKED` (message when a saved key stopped working),
  400 "secret_key_required" (Forge has `--gradio-auth`, IIB no key) -> `KEY_NOT_SET`. `tryKey` saves, re-detects and
  restores the old one when still LOCKED ("The server did not accept this key."); `forgetKey`. UI: `GalleryLockedPanel`
  / `GalleryKeyNotSetPanel` in the gallery, Settings > Server > "Gallery Key" (`GalleryKeyDialog`). Up to 3.4.2 the
  owner's fingerprint was hard-coded in three places (public in git history: the owner was asked to change the key).
  Tests: `GalleryKeyTest`, harness G46 (MockIibKey).
- **Gallery 2.1.0 (owner's request):** IIB is required. `detectExtension()` (a child of `ForgeNetworkManager.fetchApiData`, so on every connect and server change; `onServerChanged` resets it; "Check Again" = `checkGalleryExtension`) asks `<prefix>/global_setting` for each of `ForgeSettingsManager.GALLERY_PREFIXES`: 404/405 -> next prefix, none left -> MISSING; 401/403/other errors -> FAILED; no answer -> UNKNOWN; 200 -> READY after saving `serverBasePath` = `sd_cwd` and `galleryPath` = `outputFolder()` (`outdir_samples`, else `outdir_txt2img_samples`, else `outputs/txt2img-images`; relative ones under `sd_cwd` with its separator). The user never edits these paths any more (the rows, dialogs and `fetchAutoConfig` are gone); the gallery settings only show the folder. `openGallery(mode)` (main screen, shortcut, prompt picker) opens `readyRoot()` or leaves the path empty, and `GalleryScreen` shows `ExtensionStatusPanel` (missing: link `EXTENSION_URL` + Check Again). Indexing is automatic (`requestSync`, @Synchronized, needs READY): after READY, when the gallery opens (`autoSyncGallery`, 30 s throttle), on Refresh (`refreshGallery`, which re-lists `requestedPath`), 2 s after a new newest session image; every folder is listed once per 24 h (`gallery_full_sync_at`), otherwise only changed/recent folders. No Sync button, dialog, notification or toasts: the result goes to the log (`lastSyncMessage`), a failure to `indexError` (search panel). Images whose data cannot be read are indexed with `savedAt` = 0 (`getUnreadPaths`) and read again later, so "All Images" shows every image; it is always sorted NEWEST (sort button disabled there). Layout: `AppConfig.galleryView` (`GalleryView`: GRID_2..GRID_5, LIST_SMALL/MEDIUM/LARGE), chosen with `GalleryViewButton` where Sync was; the list shows name, prompt (`positivePrompt`, row by row, LRU of 300 cleared by `reloadIndex`), date (`displayDate`), star and share. `AppConfig.pinchToZoom` switches `Modifier.zoomable(key, enabled)` in both viewers. The slide-down panels keep their content while closing (`rememberLastActive` + `AnimatedContent`) and a scrim closes them. Tests: G17 (18), `GalleryOutputFolderTest`.
- **Gallery tabs (2.2.0, owner's design):** `GalleryScreen` shows `GalleryTabs` (PrimaryTabRow: "Gallery", star "Favorites", clock "All Images" + "(newest first)") over a `HorizontalPager` (no swiping while images are selected). Lists: `ForgeGalleryManager.folderView` (`FolderView`: the open folder or the search's results) and `favoriteImages` / `allImages` (`ImagesView`), all built from `search` (the filters plus the index under the root; `hits` null without a search), each carrying the filters it was made with. The virtual folders (`virtual://favorites`, `virtual://all`) are gone. `AppConfig.galleryTab` (`GalleryTab`) is the tab used last (`selectTab` saves it only in NORMAL mode); `openGallery` opens it (PROMPT_PICKER: ALL_IMAGES) in the last folder if it is still inside the root (both confirmed by the owner after 2.2.0). The Gallery tab never leaves the root: `fetchGalleryFolder` replaces a path outside it with the root, `breadcrumb(path, root)` starts at "Gallery" and cuts the paths from the server's own path, `parentFolder` is null at the root (Back closes the gallery); Back clears a search first. Scroll memory: `ForgeViewModel.galleryScroll` (keys "favorites", "all", "folder:<path>", `GalleryScrollPosition`, kept while the process lives); one hoisted `LazyGridState` per tab (the list layout is a one-column grid, so switching layouts keeps the place); `RecordScroll` writes positions, the Gallery tab restores a folder's place when `folder.path` changes (guarded by `restoredFolder`), `ScrollToTopOnNewFilters` goes to the top when a list made with other filters arrives. Tests: G17 (21), `GalleryFoldersTest`.
- **Jobs from gallery images (2.4.0, the owner's ideas 2 and 3; the app stays txt2img only):** `ImageJobs` (pure)
  remakes an image's txt2img job from its infotext (`remake`: prompt, negative, steps, sampler, `Schedule type`, CFG,
  seed, size, clip skip, ENSD/RNG as `override_settings`, the image's hires fix and variation seed) and finds its model
  in the server's list by the short hash (a full SHA-256 of the owner's endpoint, or "[hash]" in a title), else by
  name; data without a model uses the selected one; images without data, unreadable, or with a model the server lacks
  are `Skipped` with a reason. "Upscale Selected" (gallery selection bar; "Upscale" in the viewer's bottom bar):
  `upscale` = the same job with `enable_hr`, the chosen scale (×1.5-×3, relative to the first pass; images already
  that large are left out), upscaler (default: the hires fix one of AppState) and denoising (0.35). "More Like This"
  (viewer): `moreLikeThis` = "Similar" (seed kept, `subseed` = seed +1, -1, +2, ... within ±10, `subseed_strength`
  0.05-0.3, default 0.15) or "Neighbouring Seeds" (seed itself ±k); 2-20 images, seeds kept within 0..2^32-1.
  `ForgeGalleryManager.requestImageJobs` reads the data through `InfoReader` (100 per request), `imageJobs` is the
  dialog's state (`ImageJobsDialog`, closed when the gallery is left), `queueUpscales`/`queueMoreLikeThis` call
  `ForgeQueueManager.queueJobs` (labelled `QueuedGeneration.label`, shown in the queue card's header; results saved
  by the server, so they reach the gallery). New payload fields: `subseed`, `subseed_strength`, `hr_second_pass_steps`
  (old saved queues read 0, which changes nothing), and `hr_additional_modules` (2.4.1, set only by `forServer()`,
  see "Queue"). Tests: `ImageJobsTest`, harness G37 (its mock fails a hires fix without that field, as Forge does).
- **Tag suggestions (2.4.2, the owner's idea 1; a patch at the owner's command):** the owner's Forge Neo (Gradio
  4.40) runs DominikDoom's a1111-sd-webui-tagcomplete. `ForgeTagManager` reads it as the extension's own script does:
  `file=tmp/tagAutocompletePath.txt` (its tags folder, as_posix, may hold spaces; 404 = no extension), then
  `file=<folder>/<tac_tagFile>` and the `tac_extra.extraFile` (via `ForgeApi.getServerFile`, `fileUrl` encodes the
  path; the log interceptor logs `/file=` without body). The tac options come with `getOptions` (`OptionsResponseDto`,
  read as text; note tagcomplete's own spelling `tac_undersocreReplacementExclusionList`); `ForgeNetworkManager`
  calls `onServerOptions` on every connect. The list is kept in `filesDir/tags` (`tags.csv`, `extra.csv`,
  `tags.json` with server, file, date and the insert rules) and downloaded again only for another server or file,
  after 7 days, or on Settings > Appearance > "Tag List" (`reload`); a server without the extension keeps the saved
  list (MISSING). `AppConfig.tagSuggestions` (default on) frees/loads it. Pure logic in `TagSuggestions.kt`:
  `TagList` (CSV parse, sorted by count, search = prefix, then a later word after a non-letter, then alias; ~2 ms on
  140k tags on the JVM), `TagInsertRules` (underscores to spaces except emoticons/tagcomplete's exclusion list,
  brackets escaped), `PromptTypingRules` (`fragmentAt`: text from the last comma/newline to the caret, 2+ chars,
  `(` of a weight skipped, `__` = wildcard (odd count), `<lora:` = LoRA; `insert` adds ", " or reuses the comma, adds
  nothing before `:)]}>`, keeps a LoRA's strength), `Suggestions`, `TypingLayout`. UI: `UndoRedoTextField` keeps a
  `TextFieldValue` (caret) and, for the main screen's prompts (`LocalPromptTyping`), reports focus/value to
  `PromptTyping` and has autocorrect off. `MainScreen` (a `BoxWithConstraints`) while a prompt is typed with the
  keyboard up: content bottom padding = keyboard + 44 dp strip (always reserved then, so nothing jumps; the strip
  shows a hint when there are no chips), `TagSuggestionStrip` docked with `windowInsetsPadding(ime)`. `TypingLayout`
  from the space above the keyboard (window - status bar ignoring visibility - `imeAnimationTarget`): preview folded
  (height 0, keeps its blur) below 340 dp, top bar hidden below 180 dp (the real top bar is 64 dp; the preview used
  56 dp, hence 332/172 there), one bar (text end + chips, 36-44 dp) below 116 dp, status bar hidden below 44 dp
  (`HideStatusBarWhile`). Tests: `TagSuggestionsTest`, harness G38 (the real danbooru.csv from a folder with a space).
- **Main screen (3.0.0, the owner chose design "A" of three mockups, artifact "ForgeGen Main Screen Proposals"):**
  `MainScreen` is a `Scaffold`: `MainTopBar` (since 3.0.0-4, see below), then a scrolling column (16 dp sides) with the
  preview (`PreviewSection`, `PREVIEW_HEIGHT` 220 dp, radius 20, surface colour, prev/next pill with "n / m"), and
  the cards of `MainCards.kt` in the settings' style (`MainSectionLabel` = blue uppercase label + action,
  `MainCard` = rounded surface card, rows 16/12 dp, 40 dp icon tiles tint 0.18, dividers in surfaceVariant starting
  at 70 dp): `PromptCard` (frameless `UndoRedoTextField` with a footer: `TokenCount` "n / 75 tokens" (next chunk past
  75), Undo, Redo, Copy, Clear; the tag editor; the negative prompt as a folding row; "Recent" = `RecentPromptsSheet`),
  `GenerationCard` (Model row: picker `ResourcePickerSheet` with `onRefresh`, the layers button opens
  `ModelSettingsSheet`; Sampling; Size & Batch with `SizePresets` chips, swap, sliders, `SeedField` + dice menu; Hires
  fix switch with upscaler/scale/denoising), `LorasCard` ("LoRAs · n" + Add). Sliders are `ValueSlider` (value pill
  in secondaryContainer, values rounded to their step); lists are `OptionPickerSheet`. `GenerateBar` is the
  Scaffold's bottom bar (queue count, Stop while generating, Add to Queue filled by progress + ETA, ⋯ = Presets,
  Restore Last, Reset to Defaults, Save on Server, Save to Phone), hidden while any keyboard is up or the settings
  overlay is open. Open rows are kept in `AppConfig.mainOpenRows` (`MainRows`: negative, sampling, size); the old
  `bottomSheetExpandedByDefault` and `main*Expanded` are gone (Gson ignores them in old saves). Content bottom padding =
  max(bar/nav bar, keyboard (+ strip while suggesting)).
- **Queue screen, status strip, top bars (3.0.0-1, micro-patch; the owner picked C, B, C of three mockups each,
  artifact "ForgeGen 3.0.0-1 Proposals"):** `QueueScreen` is a timeline: `StartRow` (chip "Starts now" = time picker;
  while waiting for "Start at": menu Start Now / Change Time; summary "n jobs · m images"), `TimelineRow` per waiting
  job (start time from `queueJobEnds` of the job before, "now" for the running one, "next" otherwise; the rail is drawn
  behind the row at `RAIL_CENTER`, `TIME_WIDTH` 62 dp fits "12:15 PM"), `JobCard` (prompt, `SettingsText` with the
  job's label in blue, progress for the running job; tap opens `JobDetails` with Duplicate/Edit/Remove; the handle
  drags, drop targets are only waiting jobs), `EndRow` ("All done" + time), then "Set aside · n" (Remove All) with
  `FailedJobCard`s (Retry). The main screen's old `OomAlertSection` and `QueueScheduleCard` are gone:
  `QueueStatusStrip` (MainCards.kt) sits in the Scaffold's bottom bar above `GenerateBar` (hidden with it while
  typing) and shows, in order, server OOM (Resume/OK), a paused queue with jobs (Resume), jobs set aside with none
  waiting (Remove/Retry), a scheduled start (Start Now); a tap opens the queue; `GenerateBar(queueStopped)` turns the
  queue button red with a pause icon. `FloatingTopBar` (ui/components/TopBars.kt): a 56 dp pill (radius 28, surface,
  12 dp sides) under the status bar with back, title 17 sp, optional subtitle and actions; used by the queue, presets
  ("n saved"), wildcards and gallery (selection mode: close icon, secondaryContainer). The settings keep their own
  bar. Screenshots are checked with a Robolectric rig in the scratchpad (not in the repo).
- **API features plan (owner-approved after 3.0.0-4; mockups: artifact "ForgeGen API Features Proposals"):** four
  releases, each accepted by the owner installing it on the phone; on the owner's "OK" the next one starts without
  asking. 1) 3.0.1 Bugfix: latent hires modes, VAE refresh, model/LoRA pictures (done). 2) 3.1.0 Feature (done): LoRA
  metadata (base model badge, trigger words, details sheet, "fits the model" filter), embeddings (list, suggestions,
  Prompt/Negative), server styles (`/sdapi/v1/prompt-styles` + the txt2img `styles` field) - **off by default, turned
  on in the settings** (owner's decision). 3) 3.2.0 Feature (done): gallery via IIB: delete with a ~6 s Undo before the
  request (only where the server allows writing: IIB answers 403 without write permission), move/copy/mkdirs, ZIP,
  folder covers (`batch_top_4_media_info`), favorites gone from the server (`check_path_exists`), Random and
  statistics from the app's own index (never IIB's `/db/*`, whose index build blocks Forge). 4) 3.3.0 Feature (done): Skip
  Image (`/sdapi/v1/skip`), own task id (`force_task_id`) + `/internal/progress` and `/internal/pending-tasks` (fall
  back to `/sdapi/v1/progress` on 401/404), server page (`/internal/sysinfo`, `/sdapi/v1/extensions`, `cmd-flags`),
  Restart Forge (`server-restart`, only with `--api-server-stop`; 501 when not started by webui.bat/webui.sh).
- **3.0.1 (Bugfix):** `HiresUpscalers` (ForgeModels): `/sdapi/v1/latent-upscale-modes` (else the six usual names) go
  first in hires fix's Upscaler picker (`OptionPickerSheet(groups = ...)`, sections LATENT/UPSCALERS) and in "Upscale
  Selected"; `/upscalers` lists them without the latent ones. `ForgeNetworkManager.refreshModules` (refresh icon in
  the VAE and text encoder pickers): `POST /sdapi/v1/refresh-vae`, then `fetchModules()` again. On Forge Neo that
  endpoint only rescans A1111's VAE list; `sd-modules` (`main_entry.module_list`) is rebuilt only by the web UI's
  Refresh or a restart, so the toast says so. `ResourcePreviews` + `ResourcePreview` (ResourcePicker.kt): a model's or
  LoRA's picture via `/sd_extra_networks/thumb?filename=` trying `SUFFIXES` (.preview.png, .png, .jpg, .jpeg, .webp,
  .preview.jpg/.jpeg/.webp) in turn; only an HTTP error moves on (no network: tried again later); the working index
  or NONE is remembered per model while the app runs, `forget()` on model/LoRA list refresh.
  `ForgeRepository.previewCandidates` replaced `getPreviewUrl` ("file=...preview.png"). Tests:
  `ResourcePreviewsTest`, `HiresUpscalersTest`, harness G41.
- **3.1.0 (Feature): LoRA metadata, embeddings, server styles.** `LoraMetadata.readList` (LoraMetadata.kt) reads
  `/sdapi/v1/loras` with a streaming `JsonReader` (the answer carries every LoRA's whole metadata, megabytes) and keeps
  per LoRA: `LoraBase` from `ss_base_model_version` / `modelspec.architecture` / `ss_v2` (Forge Neo does not detect a
  LoRA's model itself), the top 12 tags of `ss_tag_frequency` (an object, or its JSON text), resolution and epochs.
  `LoraInfoIndex.of(name, path)` finds it by path (case and slashes ignored), else by name. `fits(modelType)` is null
  for Auto or an unknown base: then nothing is filtered, only badges shown. `ForgeNetworkManager.fetchLoraInfo` runs
  in the background after the customapi LoRA list (and is the fallback list source); `fetchEmbeddings` also after a
  checkpoint change (the loaded/skipped split depends on the model); `fetchPromptStyles`. UI (ui/components/
  PromptExtras.kt): `LoraPickerSheet` (tabs LoRA / Embeddings, filters Fits / All / In use, misfits section),
  `LoraTriggers` under each LoRA in `LorasCard` (3 compact chips; the 48 dp touch frame is turned off with
  `LocalMinimumInteractiveComponentSize`, else two rows of chips gape), `LoraDetailsSheet` (tap the LoRA's name),
  `StylesRow` + `StylesSheet` in `PromptCard`. `PromptEdits.addTags` adds only the missing tags. Embeddings are
  suggested first in the tag strip (`Suggestions.EMBEDDING`, up to 3 by prefix). Styles: `AppState.styles`, sent as
  txt2img `styles` only via `PromptStyles.forJob(config.serverStyles, ...)` (null = left out of the JSON);
  `AppConfig.serverStyles` is **false by default** (owner's decision), switch in Settings > Appearance; "Paste into
  Prompt" merges them like the web UI (`{prompt}` or ", " after) and clears the choice; taking a prompt from an image
  clears it too. Tests: `LoraMetadataTest` (+ PromptEdits/PromptStyles/EmbeddingSuggestions), harness G42.
- **3.3.0 (Feature): the server's queue, Skip Image, server page, Restart Forge.** Every job is sent with
  `force_task_id` = `ServerTasks.idFor(job.id)` ("task(forgegen-<12 alnum>)", only at send time, never saved in the
  queue; `ForgeQueueManager.runningTaskId`). While a job runs, the ping loop (still `/sdapi/v1/progress` for
  connection, busy, preview and job counts) also POSTs `/internal/progress` {id_task}: `queued` -> jobs ahead from
  `/internal/pending-tasks` (index of ours + 1 for the running one; else "In queue: i/n" -> i), `serverJobsAhead` set,
  progress 0 and no preview (the other job's), status "Waiting for the server: N other jobs go first"; `active` or
  `completed` -> not asked again for that task. 401/403/404/405/422 (no web UI with --nowebui, or its login) ->
  `taskProgressSupported` false until the API client is rebuilt. UI: amber note in the queue, strip state, the generate
  bar says "Waiting for the server...". Skip: `POST /sdapi/v1/skip` (queue's running card when n_iter > 1, and ⋮ of the
  generate bar). Server page (SettingsPage.SERVER groups "Server Info", "Extensions · N", "Control"):
  `ForgeRepository.loadServerInfo` (once per server; cmd-flags + extensions first, then `/internal/sysinfo`, slow: pip
  freeze) -> `ServerInfo` via `ServerInfoParser` (pure; "Version", "Platform", "Python", "Torch env info"
  os/torch_version/nvidia_gpu_models; extension purposes: IIB, tagcomplete, prompt-all-in-one); Share Server Report =
  `DeviceImages.shareTextIntent`. Restart: `POST /sdapi/v1/server-restart` (404: needs --api-server-stop, 501: not
  started by webui.bat/sh, no answer/IOException: restarting since Forge os._exit()s); `restartingSince` extends the
  search window to `restartWaitMs` (3 min) via `startSearch`, `followRestart` ends it when Forge answers after it was
  gone (or after 20 s if it never went). `cmd-flags.api_server_stop` greys the button (`RestartForgeButton`,
  `RestartForgeDialog` in ui/components/ServerPanels.kt); `ConnectionStatus(restartingSince)` shows "Restarting
  Forge... m:ss". Tests: `ServerControlTest`, harness G44 (7).
- **3.2.0 (Feature): changing the gallery's files, covers, favorites gone, Random, statistics.** IIB endpoints (all
  POST with `ForgeApi` `@Url`): `delete_files` {file_paths}, `move_files`/`copy_files` {file_paths, dest,
  create_dest_folder, continue_on_error=true} -> {errors: ["Error moving file <path> to ..."]}, `mkdirs`
  {dest_folder}, `batch_top_4_media_info` {paths} -> folder -> newest 4, `check_path_exists` {paths} -> bool. Write
  endpoints answer 403 when IIB may only read; `global_setting.is_readonly` says so up front ->
  `ExtensionStatus.writable`/`canWrite` (a 403 also sets it false, "Check Again" re-reads it). Delete: images hidden at
  once (`hiddenPaths` filters all three tab flows), `PendingDelete` (AtomicInteger state so Undo and the 6 s timer
  cannot both win), sent after `DELETE_DELAY_MS`; a second delete sends the earlier one at once; on success
  `forgetFiles` (listing, index, favorites), on error the folder is listed again. Move: `GalleryTransfer.plan` leaves
  out images already there and names taken in dest (IIB/shutil would overwrite silently), then `followMoved` renames
  index rows and favorites (`movePath`/`moveFavorite`, UPDATE OR REPLACE) so no generation data is read again. Copy:
  `mkdirs(dest)` first (shutil.copy into a missing folder makes a FILE of that name). ZIP is made on the phone
  (`DeviceImages.saveArchive`, Downloads or private), not with IIB's `/zip` (needs write permission and leaves
  `zip_temp` files on the server). Folder covers cached per session in `folderCovers` (cleared on Refresh and for
  folders a change touched; an older IIB without the endpoint -> plain icons); counts from the index
  (`GalleryFolders.imageCounts`, subfolders included). Favorites check when the Favorites tab shows (every 5 min at
  most). All Images order `AllImagesOrder` (seeded shuffle). Statistics screen (route `gallery_stats`, `GalleryStatsScreen`)
  from `indexedImages` + prompts read 2000 at a time (`getPrompts`), `GalleryStatistics` (pure). DB 13:
  `gallery_images.size` (bytes, from the listing's `bytes`), `MIGRATION_12_13` also deletes `gallery_full_sync_at` so
  the first sync after the update lists every folder and `updateSizes` fills old rows. UI: `SelectionMoreMenu`,
  `UndoDeleteBar` (gallery and viewer), `FolderPickerSheet`, `FolderCover`, `MissingFavoritesNote`,
  `AllImagesOrderRow` (ui/components/GalleryActions.kt). Tests: `GalleryEditsTest`, harness G43 (13), Robolectric rig
  `MigrationTest` (real Room, 12 -> 13).
- **3.4.0 (Feature, the owner's "full scan + performance + feature switches", one minor release at the owner's word;
  the plan: artifact "Plan dostrojenia ForgeGen", https://claude.ai/artifact/24dqpSCRykhFXmwHV85S2w).** Settings >
  Features (`SettingsPage.FEATURES`, second after Server; `FeatureSwitches.summary` "n of 11 on") holds every feature
  switch, each an `AppConfig` field (on by default except `serverStyles`): `tagSuggestions` (+ Tag List) and
  `serverStyles` (moved from Appearance), `embeddings`, `loraDetails`, `resourcePictures`, `livePreview`,
  `memoryMeters`, `folderCovers`, `favoritesCheck`, `imageJobs`, `serverQueue`. Off, a feature leaves the screen AND its
  requests stop: styles/embeddings not fetched (`fetchApiData`, `changeCheckpoint`), LoRA metadata not kept
  (`fetchLoraInfo` stores `_loraInfo` only with `loraDetails`; the fallback list still reads /sdapi/v1/loras), pictures:
  `LocalResourcePictures` (provided around `AppNavigation` in MainActivity) makes `ResourcePreview` show only its
  placeholder, preview: `skip_current_image`, meters: an icon (`MainTopBar(showMeters)`) opens Server Memory, which
  reads it once (`readServerMemory`), covers: `requestCovers` returns, favorites: `checkFavorites` returns, image jobs:
  `SelectionMoreMenu(onUpscale = null)` and no viewer buttons, server queue: `jobsAheadOfOurs` returns 0.
  `ForgeNetworkManager.onFeaturesChanged` (the config collector) fetches a list switched on and drops one switched off.
  **Bugs fixed:** `claim()` checks the start conditions again from the StateFlows' current values (nextJob's
  `combine` could see Undo's restored queue before the pause set just before it and send one job of a paused queue);
  `loadConfig` did not list `serverStyles` (3.1.0), so it was off after every start;
  `ForgeSettingsManagerConfigTest` now checks by reflection that every `AppConfig` field differs from its default in
  the test, so a field missing in `loadConfig` or the test fails it. **Performance:** the live preview only while
  `ForgeQueueManager.previewShown` (MainScreen's `DisposableEffect`: not under the settings overlay, not folded while
  typing, not on other screens) and `livePreview`; background ping while generating 2 s (`pingDelay`); memory by time
  (5 s generating, 10 s idle), only on screen with the meters on, and fresh before a server OOM report
  (`OomLogs.report(readServerMemory = true)`); LoRA metadata only when the customapi LoRA list (`loraInfoKey`: names +
  paths) changed (Refresh and a server change reset it); `ResourcePreviews` saved in `app_settings`
  `resource_previews` (max 2000, `changes` flow, 2 s after the last change); gallery sync after new session images only
  while `galleryVisible` (GalleryScreen's `DisposableEffect`) or AUTO_SAVE_ALL, else `missedNewImages` makes the next
  `autoSyncGallery` skip its throttle; `doSync` reloads the index only after a change; `indexedImageCount`,
  `folderImageCounts`, `availableModels/Loras` are `WhileSubscribed` flows of `inGalleryIndex` (index + galleryPath;
  tests must collect them); `getIndexedImages` is `ORDER BY date DESC, name DESC` (All Images no longer sorts; the
  harness FakeDb sorts the same); the index loads in the background after `start()` (`indexLoaded`, All Images shows
  placeholders); MainScreen passes `generationState` (`derivedStateOf`, state without prompts/styles) to
  GenerationCard and GenerateBar, the VRAM warning follows a `snapshotFlow`, LorasCard reads the prompt only when it
  shows it; `parseTags` once per change (`TokenCount(tokens)`, `countTokens` removed). **Image cache:**
  `AppConfig.imageCacheMb` (512/1024/2560, default 2560 = the old fixed size, the owner's "2.5 GB max"), mirrored to
  SharedPreferences `ui`/`image_cache_mb` (`ImageCache`, read by `ForgeApp.newImageLoader`, used from the next start),
  Settings > Backup & Data > Storage (size dialog, used bytes, Clear Image Cache). **Build:** `isDebuggable = false`
  for the debug variant when `-Pforgegen.publish` is given (release.yml and ci.yml pass it; local builds stay
  debuggable): the APK carries `assets/dexopt/baseline.prof` and is ~52 MB instead of ~71 MB. R8 was tried in the
  scratchpad and came as its own release, 3.4.1 (see "Release build type" in section 4). Tests: `FeatureSwitchesTest`,
  `ResourcePreviewsSavedTest`, the config test, harness G45 (9).
- **Main screen top bar (3.0.0-4, micro-patch "Bugfix"; the owner picked B of three mockups, same artifact):**
  `MainTopBar` (TopBars.kt) is the same pill, 4 dp above and below so it stays 64 dp (`TypingLayout.TOP_BAR_DP`):
  "ForgeGen" over `ConnectionStatus` (the whole block opens `openServerDialog`), `MemoryMeters` (VRAM = primary,
  RAM = secondary, 36x4 dp bars, `ServerMemory.compact` "5.1/8.0" / "12.3/32", orange #FFA726 from
  `ServerMemory.ALMOST_FULL` 90 %; only while CONNECTED and reported), gallery, settings. The meters open
  `ServerMemorySheet` (VRAM/RAM with `ServerMemory.detail`, the selected model, "Unload Model" = `unloadCheckpoint`,
  disabled while an image is made, spinner while `unloadingModel`); it replaced the Unload icon and its dialog.
  `ForgeRepository.serverMemory` (`ServerMemory`, GB) replaced the `vramUsage` string: read every 5th ping by
  `refreshServerMemory()`, and right after a successful unload; `summary()` is the OOM report's line.
  Unload is `POST /sdapi/v1/unload-checkpoint` (checked in Forge and Forge Neo sources): Neo drops the model from
  VRAM and RAM (`FakeInitialModel`) and `forge_model_reload` loads it on the next job; old Forge only leaves VRAM.
- **Theme colours (3.0.0):** both schemes in MainActivity set every role (secondary #8FB4FF / light #3A5BA0,
  secondaryContainer #1D2B47 / #D6E3FF, tertiary cyan, neutral surfaceContainer* and outline*, onPrimary white); the
  roles left out used to fall back to Material's purple (lavender buttons, tinted dialogs). primaryContainer stays
  black / #F2F2F2 (top bars). Use the roles, not hard-coded greys.
- **Model settings (3.0.0, the owner's ideas 4 and 6):** `ModelSettings.kt`: `AppConfig.modelSettings` maps
  `ModelSettingsRules.key(model)` (file name without folder, extension, "[hash]") to `ModelSettings` (type AUTO/SD/SDXL/
  FLUX, `vae`, `textEncoders`, `distilledCfg` 3.5, `useDefaults`, `defaults: ModelDefaults`). Modules: on connect
  `ForgeNetworkManager` asks `/sdapi/v1/sd-modules` (Forge: `[{model_name, filename}]`, kind from the folder:
  VAE / text_encoder|clip|t5 / other) -> `ModuleSupport.FORGE`, else `/sdapi/v1/sd-vae` -> A1111, else NONE
  (`ForgeModelManager.modules/moduleSupport`). `ForgeQueueManager` passes every new job (main screen and gallery jobs)
  through `ForgeModelManager.withModelSettings` (the job's own checkpoint, else the selected one) ->
  `ModelSettingsRules.applyTo`: AUTO unchanged; FORGE: `override_settings.forge_additional_modules` = SD [vae?],
  SDXL [], FLUX [vae?] + encoders (Forge restores its modules after the job); A1111: `sd_vae` = the VAE for SD,
  "Automatic" for SDXL, nothing for FLUX; FLUX also `distilled_cfg_scale` (an image's own, remade from "Distilled CFG
  Scale", wins). Defaults: "Save Current Settings" (`saveModelDefaults`, does not switch them on), "Use Model Defaults"
  (switching it on without saved defaults saves them); `changeCheckpoint` applies them (`applyDefaults`, aspect
  "Custom") with a toast. UI: `ModelSettingsSheet`; FLUX's distilled CFG slider is in Sampling. Tests:
  `ModelSettingsTest`, harness G39 (Forge), G40 (A1111).
- **Variance on Seed (3.0.0, the owner's idea 5):** the "Variance on Seed" tab of the gallery selection's dialog
  (`ImageJobsDialog` -> `SelectionJobsDialog`, tabs Upscale / Variance, `setImageJobsKind`). `ImageJobs.VarianceSpec`:
  per LoRA a `Spread(plus, step)` (±0.1/0.2/0.3/0.5 in 0.05/0.1/0.2), CFG ±0.5..2 in 0.5, steps ±5/10 in 5;
  `variance()` = the cartesian product per image (LoRA -4..4, CFG 1..30, steps 1..150), the image's own combination
  left out, seed kept, label "Variance · seed · detail 0.7 · CFG 7 · 30 steps"; `varianceCount` > `MAX_VARIANCE_JOBS`
  (100) is not queued (`queueVariance`). Tests: `ImageJobsTest`, harness G39.
- **Settings screen (2.3.0, the owner chose design "B" of three mockups):** `SetupScreen` has a main page (`SettingsHome`: "Settings", a search field, `ServerCard`, one card of `CategoryRow`s with a summary line each, the version) and one page per `SettingsPage` (`SettingsPageContent`: back arrow + title, then the page's groups, each a `SettingsCard` with `SectionLabel`), switched by `AnimatedContent` (slide + fade, `SETTINGS_PAGE_MS`); Back: page -> main, then clears the search, then closes. Every setting is a `SettingItem(page, group, words, content)` in one list built in `SetupScreen`; pages show their items, the search shows the matching items themselves (real switches) grouped by page. Pages: Server (status + Retry, address, profiles, timeout, diagnostics), Features (3.4.0, the feature switches), Appearance, Notifications (Alerts, Progress with the Now Bar checklist), Queue & Background (overnight, Keep Screen On, battery), Privacy & Security (Privacy, App Lock), Updates (update card, App Version = debug taps, auto install, check), Backup & Data (Backup, Logs, Storage since 3.4.0, Danger Zone), Debug (only when unlocked; leaving debug mode returns to the main page). `SwitchPreference`/`TextPreference` are the rows (DebugPanel uses them too). Shown over MainScreen it gets `belowTopBar = true` (its Scaffold adds no status-bar inset; 2.3.0 left a wide band above "Settings"); the "setup" nav route keeps the insets. `AppState.setupExpandedSections` (the old collapsible sections) is gone. Dialogs are unchanged. The mockups: artifact "ForgeGen Settings Proposals".
- **Selected checkpoint:** single source of truth is `ForgeModelManager.selectedModel`. `ForgeNetworkManager` (UI lists, `changeCheckpoint`) writes to it and `ForgeQueueManager` reads it for `override_settings`. Never keep a second copy.
- **Model/sampler/LoRA lists:** fetched only by `ForgeNetworkManager` (on connect and on URL change). `ForgeRepository` just rebuilds its Retrofit instance when the URL or timeout changes.
- **Generation state:** `ForgeQueueManager` owns progress, ETA, live preview, status and the queue. The ping loop in `ForgeRepository` pushes server progress via `ForgeQueueManager.updateExternalProgress()`.
- **Timeouts:** the "Connection Timeout" setting applies to ordinary API calls only; `ForgeSettingsManager.createClient` gives `sdapi/v1/txt2img` a 120 min read timeout.
- **Queue pauses:** use `ForgeQueueManager.pauseQueue(reason)`; the main screen's `QueueStatusStrip` (3.0.0-1) shows the reason and a Resume button for any pause. A queue with no runnable job left is unpaused automatically: when the last job ends (`finishJob`) and, since 2.3.0-2, when the user removes the last jobs to run (`clearQueue`, `removeFromQueue` via `liftPauseIfNothingToRun`; before, the next new job waited for Resume under the old reason). `RemovedJobs.liftedPause` keeps that reason and `restoreJobs` (Undo) pauses again with it. `oomAlert` is separate (its card's OK). Tests (with the service watcher): G36.
- **Overnight mode (1.1.5):** an HTTP error, an unexpected error or the phone running out of memory sets the job aside instead of pausing: status `FAILED` with `error`, moved to the end of the queue, skipped by `nextJob()` (`claim()` moves the running job first, so failed jobs moved above it by the user never block). No alert per job; when no runnable job is left, `notifyFailedJobs` posts "N done, M failed" (the run counters reset then and on Clear). Failed jobs are saved with the queue, can be retried (`retryFailed`) or removed (`removeFailedJobs`, per job with Remove); the main screen's status strip offers Remove/Retry for them. Server OOM still pauses. Lost connections are retried without limit, 5 s × losses up to 60 s apart (normal mode: 3 retries, 5 s).
- **PNG metadata:** always read through `PngMetadata.readParameters` (tEXt = Latin-1, iTXt = UTF-8, optionally zlib-compressed).
- **Theme:** `AppConfig.themeMode` (`THEME_SYSTEM`/`THEME_LIGHT`/`THEME_DARK`) replaced `isDarkMode` in 1.1.5; `loadConfig` maps an old `isDarkMode` to Dark/Light. The mode is also cached in SharedPreferences (`ui`/`theme_mode`) and applied in `MainActivity.onCreate` before the settings load, so the first frames use it; `res/values-night` makes the window/splash follow the system.
- **Live Updates (1.2.0 as Samsung's Now Bar, every Android 16 phone since 3.5.0):** `LiveUpdates` (was `NowBar`)
  decides: `isSupported` = Android 16+ of any maker (or `DebugMode.forceLiveUpdates`); used only when the user turned
  on `AppConfig.nowBarProgress` (the saved name stays; UI "Show Progress as Live Update", off by default as Google's
  rules want a Live Update the user asked for) and the notification mode is not "Disabled". Then the service's progress
  notification (and an update's download) is a Live Update: ongoing, `setRequestPromotedOngoing(true)`,
  `NotificationCompat.ProgressStyle` instead of the classic bar, `setShortCriticalText("NN%")` for the status bar chip,
  `VISIBILITY_PUBLIC` (no prompt in it); permission `POST_PROMOTED_NOTIFICATIONS`; channel "Progress" is
  IMPORTANCE_LOW (MIN would disqualify it). Never custom views or `setColorized`. Makers add rules: Samsung (One UI
  8/8.5) shows other companies' Live Updates only for apps on its own list unless "Live notifications for all apps" is
  on in the developer options (`LiveUpdates.isSamsung` shows that hint and button only on Samsung). So
  `LiveUpdates.notePromotion` (called by `GenerationService` before each progress update while generating, at most
  every 5 s once the notification is seen) reads `FLAG_PROMOTED_ONGOING` of the posted notification (only the system
  sets it) into SharedPreferences `ui`/`live_update_promoted`; the settings' `LiveUpdateChecklist` shows notifications
  allowed, live notifications allowed (`canPostPromotedNotifications`, button to
  `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`) and "Shown as a Live Update: yes / no / not checked yet"; turning the
  option on again forgets the old answer. Harness G27, G29.
- **Keep Screen On** holds `FLAG_KEEP_SCREEN_ON` only while `isQueueActive || isServerBusy` (3 s grace between jobs). **Show Active Tags UI** hides the "Edit Tags" row of `HybridPromptEditor`.
- **The server decides (1.6.0-1.6.2, the owner's final decision):** the app sends and receives and judges nothing itself: no content modes (SFW/NSFW/Unrestricted of 1.3.0-1.5.0 were removed with the blur, masked tags, the one-way Unrestricted and its sharing lock; a stored `contentMode` is ignored), no Civitai (1.6.1), no prompt check of its own (`BlockingApi`, a pre-send check of 1.3.2-1.6.1, was removed in 1.6.2 after the owner confirmed the server refuses such prompts). The owner's Forge server runs a prompt-checking extension (the owner plans a dedicated one) that answers a refused prompt with HTTP 403; `ForgeQueueManager` sets that job aside (FAILED) with `PROMPT_REFUSED` ("The prompt does not comply with the server's rules.") plus the server's `detail` (`serverDetail`), shows it as a toast and the queue goes on. The live preview on the main screen is blurred until the eye is tapped (as before 1.3.0; the owner likes it). `queueGeneration` reads the state when the button is pressed and adds jobs on a single-threaded dispatcher (two quick taps used to swap order).
- **Privacy options (1.3.0):** `hidePromptsInNotifications` (default on; finished-batch notifications are `VISIBILITY_PRIVATE` with a prompt-free public version anyway), `hideInRecents` (`setRecentsScreenshotEnabled`, Android 13+, forced on with the App Lock), `blockScreenshots` (`FLAG_SECURE`), `savePrivately` (`DeviceImages` saves to `getExternalFilesDir(Pictures)/ForgeGen` instead of MediaStore; `locationName()` for messages), `shareWithoutMetadata` (`MetadataStripper` removes PNG text chunks, JPEG APP1/APP13/COM, WebP EXIF/XMP from the shared copy). The OOM report leaves out OkHttp lines and "API ERROR [" messages with their continuation lines (`OomLogs.filterLog`).
- **Debug mode (1.4.0, owner's request):** `DebugMode` (8 taps within 2 s each on "App Version" in Settings > Updates, then the password; the app holds only a salted PBKDF2-HMAC-SHA256 hash, 120 000 iterations, 5 wrong tries = 1 min wait). The password must never be committed (the owner knows it; the harness test keeps it in the scratchpad). The state lives in SharedPreferences `debug`, outside AppConfig; wiping "App Settings & State" locks it (`ForgeViewModel.wipeSettings`). `DebugPanel` (the Settings > Debug page): status, HTTP logging (`enableLogging`, which has no other switch), Live Updates offered on any phone (`DebugMode.forceLiveUpdates`, was "Force Now Bar"), raw settings JSON (`loadConfig` checks it), test notifications (`ForgeQueueManager.debugNotify`), What's New again, reinstall the latest release (`checkForUpdates(offerAnyRelease)`), full log (`OomLogs.saveDebugLog`, HTTP content included, no consent needed), rebuild model lists.
- **Queue schedule and estimate (1.5.0):** "Start at" = `ForgeQueueManager.scheduledStart` (saved as `queue_scheduled_start`; a time that passed while the app was closed starts the queue at the next start); `nextJob()` waits while it is set. The service runs (isQueueActive is true; since 2.3.0-2 it is started for the wait too, also when no job ran before) but holds no wake lock while `isWaitingForSchedule`, so the process lives until the alarm; "Keep Screen On" ignores the waiting queue. `QueueSchedule` sets an alarm (`setExactAndAllowWhileIdle` when `canScheduleExactAlarms()`, SCHEDULE_EXACT_ALARM declared, else `setAndAllowWhileIdle`); `QueueScheduleReceiver` takes a 30 s wake lock and calls `onScheduledTime()` (ignores alarms more than 1 s early); a watcher coroutine covers an awake phone. The app is not started by the alarm from a dead process (the managers need the ViewModel). `QueueEstimate`: work = megapixels × steps × images (+ hires pass × scale² × denoising), running average (new job 30 %) of seconds per unit per checkpoint and for all (`*`), saved as `queue_speed`; interrupted jobs do not count; `queueSecondsLeft` uses the server's ETA for the running job. UI: `QueueStartTimeDialog` (`QueueScheduleComponents.kt`); since 3.0.0-1 the queue's timeline shows the times (`QueueEstimate.ends` -> `ForgeQueueManager.queueJobEnds`: seconds from now to each job's end, failed null, null from the first job that cannot be estimated; the running job by the server's ETA) and the main screen's `QueueStatusStrip` a scheduled start (Start Now).
- **Settings persistence:** `loadConfig` must list every `AppConfig` field (covered by `ForgeSettingsManagerConfigTest`, which since 3.4.0 fails for any field left out; set every new field to a non-default value there); DB writes go through a single-threaded dispatcher to keep their order. `timeout` is clamped to 1–600 s on load and save.
- **ForgeRepository** only owns the database, the Retrofit client, the ping loop (connection, RAM/VRAM, external jobs) and the service toggle. Queue, gallery, models and prompts live in their managers; don't add delegating copies back.
- **Prompt tag helpers** (`parseTags`, `splitTagWeight`, `withTagWeight`, `adjustTagStrength`) live in the Compose-free `ui/components/PromptTags.kt` (tested by `PromptTagsTest`). LoRA tags are parsed only by `parseActiveLoras` in `ForgeRepository.kt`.
- **Notification modes:** the strings in `GenerationService` must match the options in `SetupScreen` ("Simple", "Verbose", "Disabled").
- **Start and connection (2.0.0, owner's design):** no welcome screen. The Android 12+ splash (`Theme.ForgeGen.Starting`,
  `drawable/splash_anvil_animated.xml` over `splash_anvil.xml`) stays at least `SPLASH_MIN_MS` (1.3 s) and until `viewModel.isStarted` (= `ForgeSettingsManager.isInitialized`: the phone's part of the start)
  and the main screen's first frame, at most `SPLASH_MAX_MS` (then `StartupScreen` shows `initStatus`). It leaves by
  scaling/fading while `MainActivity.circularReveal` opens the content from the middle. `initializeApp()` returns after
  the phone's part; `awaitServerCheck()` waits for the server's part (statuses "Connecting to Server..." -> "Ready").
  `syncSplashNightMode` (UiModeManager.setApplicationNightMode on stop) makes the next splash follow the app's theme.
  **Splash animation (2.3.0-3, owner's design from an HTML preview, font Roboto Black):** the hammer strikes (380 ms),
  eight sparks fly out and snake down both sides of the anvil (385-860 ms, `@interpolator/splash_spark`, with trails),
  then light up as the letters of "ForgeGen" under the anvil (F o r g from the left, e G e n from the right), which
  cool from the sparks' colours to the anvil's steel; about 1.34 s (`windowSplashScreenAnimationDuration`). The old
  scene is scaled to 0.8 and lifted so the word fits the 192 dp circle the system shows. All of it is generated by
  `tools/splash/gen_splash.py` (never edit the XML by hand): `splash_anvil.xml` + `splash_anvil_animated.xml` (base
  routes) and `splash_anvil_animated_1..6.xml` (lightly random routes, seeded like the preview). `pickNextSplashRoute`
  sets one of `Theme.ForgeGen.Starting.Route1..6` for the next start (`setSplashScreenTheme`, API 31+; never the same
  twice, `ui`/`splash_route`). `StartupScreen` shows the static drawing (the word is hidden at rest). Test:
  `SplashDrawablesTest`.
  `ForgeRepository.connection`: CONNECTED / SEARCHING (every `searchPingMs` = 2 s until `searchEndsAt`, 1 min) /
  OFFLINE (no pings). A loss, `reconnect()` (foreground, network back via `isOnline`, "Retry"/"Connect", a queued job,
  the scheduled start) starts a new minute. An active queue (`queueNeedsServer`: active and not waiting for its
  scheduled start) never goes OFFLINE and backs off 5/10/30/60 s. `pingDelay`: 1 s generating, 2 s on screen, 10 s in
  the background with work; `awaitPingNeeded` sends nothing in the background without work. Pings use a 3 s connect
  timeout (`isPingPath`). UI: `ConnectionStatus` in `MainTopBar` (tap = `openServerDialog`), `ServerConnectionDialog`
  (address, profiles, Test = `testServer`, Connect/Retry = `connectTo`, Settings, Close) shown by MainActivity when
  OFFLINE (closable, `offlineDialogClosed`) or asked for. Tests: G31 (window shortened), G21/G22 (start).
- **Self update (2.0.2, owner's request):** `SelfUpdate`: `UpdateCheckJob` (JobScheduler, every 6 h, unmetered, persisted:
  RECEIVE_BOOT_COMPLETED; scheduled by `ForgeApp.onCreate`) runs `checkInBackground`: `decide(installed, latest,
  autoInstall, appOnScreen, queueWorking)` -> NONE (nothing newer, or the app is on screen: its own dialog offers it) /
  NOTIFY ("Install Updates Automatically" off) / WAIT (queue active or generating: installing kills the process) /
  INSTALL (`download`: reuses a verified file, SHA-256 against GitHub's digest; then `install`). `install` = a
  PackageInstaller session with `USER_ACTION_NOT_REQUIRED` (Android 12+, UPDATE_PACKAGES_WITHOUT_USER_ACTION; allowed
  for an app updating itself) and `setRequestUpdateOwnership` (14+); `UpdateStatusReceiver` shows the system's
  confirmation when it still wants one (at once if the app is on screen, else a notification) or a failure;
  `UpdatedReceiver` (MY_PACKAGE_REPLACED) posts "ForgeGen updated to X" when `installing_version` was set by us.
  The setting is mirrored to SharedPreferences `updates`/`auto_install` (the job has no database). The in-app check
  runs at every start, throttled to 15 min (`updates`/`last_check_ms`; `lastUpdateCheckDate` is no longer used), and
  "Install Update" goes through `SelfUpdate.install` (the old ACTION_VIEW screen only if a session cannot be opened).
  Tested by the owner with 2.0.3 (an empty release) on the Samsung phone: the silent update works; only Google Play
  Protect shows its prompt (sideloaded, debug-signed, debuggable app). Since 3.4.0 the published APK is not
  debuggable, since 3.5.1 it is signed with the own key and since 3.5.2-2 its package has no ".debug"; whether Play
  Protect then prompts less is not known yet. `GitHubApi.baseUrl` is overridable for tests (G34).
  From the app it is two steps since 3.0.0-3 (owner's bug report: after the download the card offered "Install Update"
  again and each tap started another install while the app was open, a loop). "Download" =
  `ForgeUpdateManager.downloadUpdate` -> `UpdateDownloadService` (foreground service, type dataSync +
  FOREGROUND_SERVICE_DATA_SYNC, a 20-min partial wake lock) runs only `SelfUpdate.download` (SHA-256 checked) and
  then `SelfUpdate.markReady` (`readyUpdate`; prefs `ready_update` = "<versionCode>:<length>", `refreshReady` on every
  check finds it again after a restart; `notifyReady` when the app is not on screen). Progress:
  `SelfUpdate.downloadProgress` (`DownloadProgress`: version, done, total) in Settings > Updates and notification
  `ID_DOWNLOAD_NOTIFICATION` (ProgressStyle + setRequestPromotedOngoing + short critical text when
  `LiveUpdates.isSupported` and `nowBarProgress`). "Install" (card "Update Ready", a dialog first while the queue works) =
  `installUpdate(sendToBackground)`: `installing` is set at once (no button while it is set: the card shows
  "Installing", and "Confirm Install" when `UpdateStatusReceiver` kept the system's confirmation in
  `pendingConfirm`), the file's SHA-256 is checked again (damaged: `clearReady`, toast), the app goes to the
  background (`moveTaskToBack`, the owner's request), then `SelfUpdate.install`. `notifyFailed` clears `installing`
  (Install can be tapped again, the file stays ready); `onUpdated` clears `readyUpdate`. If PackageInstaller cannot be
  used, `offerInstallerScreen` posts a notification with the system installer. A failed download posts "ForgeGen update
  failed". Tests: G35 (5).
- **Compose animations outside composition (2.0.1):** `Animatable.animateTo` (and anything using `withFrameNanos`) needs
  Compose's frame clock: run it in a `LaunchedEffect` or with `AndroidUiDispatcher.Main`, never in a plain
  `lifecycleScope`/`Dispatchers.Main` coroutine. 2.0.0 did that for the splash's reveal and crashed at every start
  (tests on the JVM cannot catch it). The reveal also ends fully shown whatever happens.
- **Performance rules (2.0.0):** one OkHttp client (`ForgeSettingsManager.createClient`: timeouts, gallery cookie, HTTP
  log without image/txt2img bodies, error bodies logged up to 64 KB) shared by the APIs and Coil; one Coil ImageLoader
  (`ForgeApp`). AppState is written by a debounced writer (500 ms, `flushState` on stop). txt2img images are streamed
  to files by `Txt2ImgImages` (never a whole base64 string); the live preview is a `LivePreview` (bytes decoded once,
  same text skipped) downsampled by the UI; the session keeps `MAX_SESSION_IMAGES` = 100 (never the newest batch)
  and deletes older files. The gallery keeps `IndexedImage` (no prompts) in memory; prompt search =
  `findPathsByPrompt` (SQL LIKE, escaped); sync reads `getAllPaths`. Grid and list: `GalleryThumbnail` (AsyncImage) +
  `Modifier.shimmer()` (draw phase). Models/LoRAs: `ResourcePickerSheet` (lazy, searchable). Undo history: 100 steps,
  typing grouped (800 ms). `PromptHighlighting` is an object. Tests: G32 (session, debounce, answer order).
- **R8 rules (since 3.4.1; the owner confirmed the shrunk app works and made R8 permanent):** only the APK built with
  `-Pforgegen.publish` (release.yml, ci.yml) goes through R8 (setup in `app/build.gradle.kts`, rules in
  `app/proguard-rules.pro`). Android Studio builds, the unit tests, the JVM harness and the Robolectric rig all run
  the unshrunk code, so none of them can catch an R8 fault: it shows only in the published app. When coding:
  - **Gson data stays in `com.example.forgegen`** (any subpackage): settings, presets, the saved queue, backups and the
    server's answers. The keep rule keeps every class there with all its fields and constructors, so a new data class
    there needs nothing. A class Gson reads outside that package, or a library's class, needs its own `-keep` rule.
  - **Names stay:** field names are the JSON keys of saved data and of the server's answers, so `-dontobfuscate` must
    stay. Renaming a field of saved data still needs `@SerializedName("oldName")` (as without R8).
  - **Generic types for Gson** only as `object : TypeToken<List<X>>() {}.type`, as the app does now (TypeToken
    subclasses and the `Signature` attribute are kept).
  - **No reflection on methods or by name:** fields and constructors of app classes are kept, methods are not (R8
    inlines, merges or removes them). No `getMethod`/`getDeclaredMethod`/`Class.forName` on names in strings, no
    kotlin-reflect (`memberProperties`; not a dependency). If it is ever needed, add a keep rule for exactly that and
    check it in the dex. `e.javaClass.simpleName` in messages is fine (names are not obfuscated).
  - **Resources only through `R.`** (`R.drawable.x`, `R.string.x`): `isShrinkResources` removes resources the code does
    not refer to, so never look one up by name (`getIdentifier`); if that is ever needed, list it with `tools:keep` in
    `res/raw/keep.xml`. Files in `assets/` (the CHANGELOG) are never removed.
  - **Libraries:** Retrofit, Room, OkHttp, Coil, coroutines and Compose ship their own rules (Gson's own cover only
    `@SerializedName` fields and TypeTokens, hence the app's keep rule); a new
    endpoint in `ForgeApi` needs nothing (the generic return types keep their signatures). Manifest components
    (activities, services, `QueueTileService`, `UpdateCheckJob`) are kept automatically. A new library: check whether
    its AAR ships consumer rules and whether it reads classes by reflection (Moshi/Jackson without codegen, Gson on
    the library's own classes); if so, add rules.
  - **Crash logs from the published APK:** class and method names are real, but the line numbers are R8's (e.g. line 8
    for line 45) and inlined methods are missing. Since 3.5.0 every release has `mapping.zip` (release.yml): unzip that
    version's `mapping.txt` and run `~/android-sdk/cmdline-tools/latest/bin/retrace mapping.txt <trace file>`. For an
    older version, rebuild its commit with `./gradlew assembleDebug -Pforgegen.publish` (R8 gives the same output for
    the same commit: 3.4.1 built here and on GitHub had the same size to the byte) and use
    `app/build/outputs/mapping/debug/mapping.txt`.
  - **Check before a release** that adds Gson data outside the package, reflection, a new library or resources looked
    up by name (routine changes need only the CI build): build with `-Pforgegen.publish`, read
    `app/build/outputs/mapping/debug/usage.txt` (what R8 removed: unused methods such as `copy`/`componentN` are
    expected, a class or field of `com.example.forgegen` never), compare `javap -p` of
    `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes` with `dexdump` of the APK, and check
    resources with `apkanalyzer`. 3.4.1 was checked this way (all 830 classes, 3514 fields and every constructor kept).
    No emulator here, so also ask the owner to try the published APK.
  - **Don't** turn R8 off, obfuscate names, narrow the keep rules for size, or switch to the release build type
    without the owner.
- **2.0.0 conveniences:** queue Undo (`RemovedJobs`, `restoreJobs`), `duplicateJob` (before failed jobs), drag
  (`moveQueueItem`, the running job stays first); `AppState.withSwappedSize`; `vibrateOnFinish` (`batchFinished`,
  VIBRATE); `Modifier.zoomable` (pinch/double tap, 1x swipes left to the pager; images decoded at
  `zoomableImageSizePx`); gallery multi-select (`downloadImages`, `addFavorites`, `shareImages`/`shareManyIntent`);
  dynamic launcher shortcuts (`publishShortcuts`, actions `ACTION_OPEN_QUEUE`/`OPEN_GALLERY`/`GENERATE_AGAIN`);
  `QueueTileService` (tap: `pauseByUser` with `USER_PAUSED_REASON` / `resumeQueue` / open the app); shared
  `text/plain` = the prompt; `Backup` (write/read JSON with config + wildcards; import keeps `lastUpdateCheckDate`,
  replaces config, adds wildcards). Intents reach the screen through Channels (`navEvents`, `pendingIntents`): the
  shared flows lost what came at a cold start. Tests: G33.
- **Dead code:** `ForgeModels.kt` has `@file:Suppress("unused")` (for Gson DTO fields), so the IDE won't flag unused classes or DAO methods there; check references by hand.

## 2. User Preferences & UI Principles
- **What users generate is up to their server (owner's final decision, 1.6.0):** the app does not judge prompts or hide content; the server enforces its rules (HTTP 403, 1.6.2 removed the app's last own check). Don't add prompt checks back to the app; a refusal is the server's answer.
- **txt2img only (owner, after 2.3.0-3):** the app is dedicated to txt2img. No img2img, inpainting, ControlNet, the
  extras/upscale endpoints or interrogate (prompt from an image); new ideas must work through txt2img parameters.
- **Language (owner's explicit request):** always talk to the owner in Polish, with no English sentences or headings and Polish words instead of English jargon where a natural one exists; code, UI texts and release notes stay in English.
- **Releases (owner's standing request):** after finishing a change, Claude publishes the release itself: bump `gradle.properties` (patch for fixes and small changes, minor for new features, major only for a clear change across the whole repository OR on the owner's explicit command), add the `## <version>` section to `CHANGELOG.md` and push to master. Release notes are written for the user of the app, in English like the rest of the UI. Since 3.0.0-4 (owner's request) each section names its kind (**Bugfix**, **Polish**, **Feature**, **Overhaul**) in its first line and sorts its items into New, Changed and Fixed, so a reader knows at once what the update is (rule in CLAUDE.md).
- **R8 from now on (owner, after 3.4.1 ran without faults):** every published APK is shrunk and optimized by R8, and
  new code must work with it: follow "R8 rules" in section 1 whenever code touches saved data, Gson, reflection,
  resources or a new library.
- **README.md (owner's request after 3.4.1):** the repository's front page, in English: the app icon as its logo
  (`docs/images/logo.png`, made from `app/src/main/ic_launcher-playstore.png` with rounded corners; make it again if
  the icon changes), a **build** badge (shields.io status of `release.yml` on master, which runs the tests and the
  build on every push there; `ci.yml` never runs on master), the features, the server's requirements, installation,
  building, structure and releases. Keep it true: a change that adds, removes or renames a feature, a requirement or
  a build step updates README.md in the same commit.
- **License (owner's choice after 3.4.1):** GPL-3.0-or-later, author "xplod24 (Szymon Tempiński)", since 2026.
  `LICENSE` is the FSF's text unchanged (so GitHub recognises it); the copyright notice and the "or later" wording are
  in README's License section. Source files carry no headers. Since 3.4.2 (patch "Polish", owner's request)
  Settings > Updates > License (`AppLicense`: notice, `paragraphs()` joins the file's wrapped lines; `LicenseDialog`:
  notice, Full License from the assets, Source Code opens the repository); `AppLicenseTest` checks that the app's
  notice matches README's. New dependencies must be GPL-3.0-compatible (Apache-2.0,
  MIT, BSD are; GPL-2.0-only is not). Code others contribute stays theirs under the GPL, so relicensing later would
  need their consent.
- **Animations:** every enter animation needs a matching exit. Full-screen overlays in `MainActivity` use `AnimatedVisibility` with a 200 ms fade (`OVERLAY_FADE_MS`) and `rememberLastActive` so the final state (tick/cross) stays visible while fading out. Don't read an animating value in composition (e.g. as a `LaunchedEffect` key): that recomposes on every frame.
- **Intrusiveness:** The app must NEVER interrupt the user with random Toasts or pop-up Alert Dialogs during normal use (especially for updates). The one exception, requested by the owner: "What's New" once after an update, since 3.0.0 as the floating bar (the notes open only on "Show").
- **Silent Background Checks:** App update checks happen silently in the background. The user is notified via an inline banner in the Settings/Setup Screen, not via a popup.
- **Update downloads (owner's request, 2.3.0-1; replaces the old "block the UI while downloading" rule):** no blocking dialog. "Download" starts `UpdateDownloadService` and the app stays open (2.3.0-3: 2.3.0-1 moved it to the background with `moveTaskToBack` during the download, which the owner did not like); its progress is a notification (a Live Update in the Now Bar when supported and "Show Progress in Now Bar" is on) and a card in Settings > Updates. Installing is a separate "Install" tap, and that one does send the app to the background (owner's request, 3.0.0-3), so Android can replace it.
- **Tag Editors:** The active tags UI uses a sleek collapsible design (`AnimatedVisibility`) driven by a horizontal separator to save space while keeping it accessible.
- **Clean Settings:** Deprecated features (like Image Previews in notifications and Alert Priorities) are completely ripped out of the backend code, not just hidden from the UI.

## 3. Recent Milestones
- **Builds 195-198:** Extracted settings logic from `ForgeRepository` into `ForgeSettingsManager`, modernized the update flow, implemented SHA-256 validation for existing APKs, and polished the active tags UI.
- All empty legacy directories (`data`, `domain/models`) and temporary scripts have been cleaned up and ignored via `.gitignore`.
- **Testing Architecture:** Integrated `io.mockk:mockk` and `kotlinx-coroutines-test` into the `testImplementation` to allow comprehensive testing of `ForgeQueueManager` (and future managers) without needing an emulator or physical device.
- **Code Formatting:** Downloaded `ktlint.jar` to the project root. It can be run via `java -jar ktlint.jar -F "app/src/**/*.kt"` to auto-format all Kotlin files and remove unused imports.
- **Build 277 fixes:** see CHANGELOG.md (generation timeout, checkpoint override, progress, settings persistence, lock on cold start, PNG metadata, queue pause UX). Unit tests: `PngMetadataTest`, `ForgeSettingsManagerConfigTest`; `ForgeUpdateManagerTest` fixed to the list-based changelog.

## 4. Current Outstanding Tasks
- **Release build type:** since 3.4.0 (owner's "Tak") the published APK is the debug variant built with
  `-Pforgegen.publish`: not debuggable, the same app id as local builds (no `.debug` since 3.5.2-2), published as
  `ForgeGen.apk` since 3.5.2-1. Since 3.4.1 (patch
  "Polish", after the owner confirmed 3.4.0 ran smoothly) R8 shrinks it too (`isMinifyEnabled`/`isShrinkResources`
  only with the property; ~6.5 MB instead of 52): `proguard-rules.pro` has `-dontobfuscate` +
  `SourceFile,LineNumberTable` (crash logs, OOM reports and the debug log keep real names) and keeps every class of
  `com.example.forgegen` with its fields and constructors (Gson reads them by name; R8 drops fields it sees written
  but never read, and the no-arg constructor gives an older save the defaults of new fields), `Signature`/annotations
  and TypeToken subclasses. The owner confirmed 3.4.1 works without faults; the rules for new code are "R8 rules" in
  section 1. Don't switch to the release build type or a new key without the owner.
- **Live Updates beyond Samsung (3.5.0):** not seen on a non-Samsung phone yet (no emulator here); the checklist's
  "Shown as a Live Update" line tells. The owner confirmed the Samsung Now Bar at 2.0.3.
- `app/release/` build outputs and `ktlint.jar` (80 MB) are tracked in git on purpose (owner's choice for this hobby repo); don't untrack them without asking.

## 5. Ideas Backlog (numbered by the owner; not started until the owner says so)
1. **Danbooru tag suggestions above the keyboard**: done in 2.4.2 (the owner asked for a patch, not a minor; the data is
   the tagcomplete extension of the owner's server, no bundled list; see "Tag suggestions" in section 1). The agreed
   design (44 dp strip, what gives way as the keyboard grows, 69% fewer key presses on the top 1000 tags) is in the
   preview artifact "ForgeGen Tag Suggestions" (https://claude.ai/artifact/C4LixCf4mJPLAH3ejPVnDN).
2. **"More Like This" in the gallery**: done in 2.4.0 (the owner chose "Similar", variation seeds, as the default).
3. **"Upscale Selected" in the gallery**: done in 2.4.0 (defaults ×2, denoising 0.35, the hires fix upscaler).
4. **Checkpoint type with its modules**: done in 3.0.0 (see "Model settings" in section 1).
5. **"Variance on seed"**: done in 3.0.0 as the second tab of the gallery selection's Upscale dialog (see "Variance on
   Seed" in section 1).
6. **Default options per model**: done in 3.0.0 ("Use Model Defaults" in the model's settings, off unless switched on).
   Released together with the main screen redesign (the owner's pick "A") and the What's New bar as the major 3.0.0.
