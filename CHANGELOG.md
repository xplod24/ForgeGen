## 2.3.0-2
- **Queue:** a queue that is waiting keeps ForgeGen running in the background with its notification, like a queue at work. Before its first job, a queue waiting for the server (unreachable when the app started) or for its "Start at" time had no such protection, so the phone could freeze or close the app and the queue did not start until you opened it again.
- **Queue:** "Clear" (or removing the last waiting job) on a paused queue now ends the pause, so the next new job runs without "Resume" instead of waiting under the old reason. "Undo" brings the jobs back paused, as they were.

## 2.3.0-1
- Settings: the empty band between the top bar and "Settings" is gone.
- **Updates download in the background:** after "Install Update" the app steps aside and the download goes on with the app closed or the screen locked (it used to stop). The progress is in the notifications, in the Now Bar on Samsung phones with "Show Progress in Now Bar" on, and in Settings > Updates. The full-screen download window is gone.

## 2.3.0
### New settings screen
- **Categories:** the settings open on your server (address, connection, profiles) and seven categories, each with a line on how it is set: Appearance, Notifications, Queue & Background, Privacy & Security, Updates and Backup & Data. Tap one to open its page; Back returns.
- **Search:** type in "Search settings" to find any setting and change it right there in the results.
- **Cleaner look:** settings sit in rounded cards with coloured category icons, in the style of the rest of the app.
- **Server page:** the connection status with Retry, the address, profiles, timeout and diagnostics in one place (they were small chips before).
- Settings moved to where you would look for them: "Keep Screen On" is in Queue & Background; the out-of-memory logs and "Wipe Application Data" are in Backup & Data.
- "Show Progress in Now Bar" is no longer marked "Work in Progress".

## 2.2.0
### Gallery
- **Tabs:** Gallery | Favorites | All Images (newest first). Your favorites and all your images are one tap (or a swipe) away from any folder; the Favorites and All Images folders inside the gallery are gone.
- **Each tab remembers where you were:** how far it was scrolled, and in the Gallery tab, each folder's place. The gallery opens on the tab you used last, in the folder you had open. "Select Image" (picking an image's prompt) opens on All Images.
- **The gallery stays in its folder:** the path bar starts at "Gallery", and the server's folders above it (like "outputs") can no longer be opened.
- The search works in every tab: in Gallery and All Images it looks through the whole gallery, in Favorites through your favorites.
- Back first clears an active search, then goes up one folder in the Gallery tab.

## 2.1.0
### Gallery
- **Infinite Image Browsing is required:** the gallery shows your Forge server's images through this extension. The app now looks for it when it connects. On a server without it, the gallery says what is missing, with a link to the extension and "Check Again", instead of an empty or broken screen.
- **No more folder settings:** the gallery finds its folder on its own (from Forge's settings, read through the extension). "Server Base Path", "Gallery Server Path" and "Auto-Config Gallery Path" are gone.
- **The index updates itself:** after connecting, when the gallery opens or is refreshed, and a moment after the app made new images. The Sync button, its window and its notification are gone; a thin bar under the path shows when the index is being updated.
- **All Images** always shows every image of the gallery, newest first, whatever the sort order. Images whose generation data could not be read are shown too, and their data is read again later.
- **Layouts:** a new button (where Sync was) switches between a grid of 2, 3, 4 or 5 columns and a small, medium or large list. The list shows each image's name, prompt and date, with buttons to star and share it.
- **Pinch to Zoom** can be switched off in the gallery settings (it also applies to the main screen's image viewer).
- The sort, search and settings panels now slide away when closed (they used to vanish at once), switch smoothly from one to another, and close with a tap outside them or with Back.

## 2.0.3
- A test release for the automatic updates of 2.0.2: nothing else changed.

## 2.0.2
- **Updates install themselves:** every 6 hours, on Wi-Fi, the app looks for a new release on GitHub, also while it is closed, and installs it in the background (on Android 12 and newer without asking, where the phone allows it). Never while the queue works or while you are using the app; a notification says when an update was installed. The first time, or on some phones, Android still asks for a confirmation: a notification leads to it.
- "Install Updates Automatically" in App Updates turns this off; you then only get a notification about a new version.
- The app also looks for updates at every start (at most every 15 minutes) instead of once a day, and "Install Update" in the app installs without the extra installer screen where the phone allows it.

## 2.0.1
- Fixed: the app closed right after the start animation (when the main screen was about to open).

## 2.0.0
### Faster and lighter
- **A new start:** the hammer strikes the anvil and the main screen opens from the middle, in under a second. The app no longer plays a 3-second intro and no longer waits for the server before showing the main screen.
- **Less work in the background:** the server is asked every 2 s while the app is on screen (every second while generating), and not at all in the background when nothing runs. The prompt and the sliders are saved when you pause, not on every keystroke.
- **Less memory for big images:** images are written to the phone while they arrive from the server, the live preview is decoded once at the size of its box, and the session keeps the latest 100 images.
- **Gallery:** the index stays in memory without the prompts (a prompt search asks the database), and the grid scrolls more smoothly.
- **Models and LoRAs** are picked in a sheet with a search field; long lists no longer load every preview at once.

### Connection
- The top bar shows "Connecting... 0:42" while the app looks for your server. After a minute without an answer it stops asking and shows "No connection to the server" with the address, your saved server profiles, a Test button and Retry. Close it to keep working without the server; "Offline" in the top bar opens it again.
- A new minute of tries starts when you come back to the app, when the phone's network comes back, or when you queue a job. A queue that is running never gives up.
- Tap the connection status in the top bar for quick server settings.

### New
- **Undo** after removing a job or clearing the queue.
- **Duplicate** a job (with the same seed or a new one), and **drag** jobs to reorder the queue.
- **Swap** width and height with one tap.
- **Vibration** when a batch finishes while the app is open ("Vibrate on Batch Finish" in Push Notifications).
- **Zoom** in the image viewer: pinch, or double tap.
- **Select** several gallery images with a long press, then save, share or add them to the favorites at once.
- **Shortcuts** on the app icon (long press): Generate Again, Queue, Gallery.
- **Quick Settings tile** with the queue's progress; tap it to pause or resume the queue.
- **Share text** to ForgeGen to use it as the prompt.
- **Backup** in the settings: export and import your settings, presets, server profiles and wildcards.

## 1.6.2
- The app no longer checks prompts itself: your server alone decides what it generates. A prompt it refuses (HTTP 403) is set aside in the queue with "The prompt does not comply with the server's rules." and the server's reason; the other jobs go on.

## 1.6.1
- Civitai sync is gone: model names and previews come from your server alone. Picking a LoRA adds it to the prompt at once (without the trigger-word dialog). The Civitai settings, notifications and the NSFW / "Real person" labels are gone too, and the stored Civitai data is removed from the phone.
- The live preview on the main screen stays blurred until you tap the eye.

## 1.6.0
- **Your server decides what it generates.** The content modes (SFW, NSFW, Unrestricted) are gone, with the blurred images and hidden tags. Prompts go to the server as you write them; a prompt-checking extension on the server can refuse them.
- When the server refuses a prompt (HTTP 403), the app says "The prompt does not comply with the server's rules.", with the server's reason when it gives one. Only that job fails: it is set aside in the queue, and the other jobs go on.
- The app still checks two things before sending, in the same way: sexual content with a minor, and nudity with the LoRA of a real person are never sent.
- Sharing works again in every case; the Privacy settings (hidden prompts in notifications, Recents, screenshots, saving privately, sharing without generation data) stay as they were.
- Civitai previews show the model's first sample image again (never one Civitai itself blocks, nor one it marks as showing a minor above PG); the NSFW and "Real person" labels stay.
- The live preview on the main screen is blurred until you tap the eye, as before 1.3.0.

## 1.5.0
- **Start the queue at a set time:** the clock button in the queue picks a time of day (today, or tomorrow when that time has passed). Until then nothing is sent, also jobs added later; a card on the main screen and in the queue shows when the queue starts, with Start Now and Change. The phone may sleep meanwhile; an alarm wakes it at that time (Android may deliver it a few minutes late to save battery). "Keep Screen On" does not keep the screen on while the queue only waits.
- **When the queue ends:** after the first finished job the app knows how fast your server is, for each model, and the queue shows when it will be done ("Ends around 03:40, 2 h 15 min left"), or how long it will run after its set start.
- "Show Progress in Now Bar" is marked as work in progress: Samsung shows it only with a developer option for now.

## 1.4.1
- Now Bar: the progress notification may always be shown in full on the lock screen (it shows only the image number and the progress, never the prompt), as Samsung requires.
- Under "Show Progress in Now Bar" a checklist shows what the Now Bar needs: whether notifications and live notifications are allowed, and what Samsung needs besides that and the app cannot check ("Live notifications for all apps" in the developer options, and notifications with their content on the lock screen). Buttons open the right settings pages.

## 1.4.0
- The settings show the app version under App Updates.
- A hidden debug mode for maintaining the app, locked with a password. It shows the app's state and offers developer tools: editing all settings as text, HTTP logging, test notifications, saving the full log, and more. It stays hidden during normal use.

## 1.3.2
- The rules that apply in every content mode (no sexual content with a minor, no nudity with the LoRA of a real person, no Civitai preview of a minor above PG) are now kept together in one place in the code, so they are easier to maintain and to extend where a country requires more. Nothing changes in how the app works.

## 1.3.1
- Turning on **Unrestricted** now shows a clear warning: you alone are responsible for what you generate and for following the law where you live, as some content can be a criminal offence. It needs a ticked box to confirm.
- Unrestricted is one-way: once on, it cannot be turned off in the settings. Only wiping "App Settings & State" in the Danger Zone brings back SFW.
- In Unrestricted, images cannot be shared from the app: the share buttons are gone.

## 1.3.0
- New **Content Mode** in the new Content & Privacy section of the settings. It is your choice; the app starts in SFW:
  - **SFW**: prompts with nudity, sex or other adult tags are not sent, such tags are hidden in the tag editor and in texts, and every image is blurred until you tap "Show image" (a safe prompt does not guarantee a safe image).
  - **NSFW**: adult content is allowed. Only extreme tags (non-consent, gore, bestiality and the like) are not sent, and images made with them are blurred.
  - **Unrestricted**: nothing is blocked or blurred.
  - Switching to NSFW or Unrestricted asks you to confirm that you are 18 or older.
- In every mode, also Unrestricted, two things are never sent: sexual content with a minor, and nudity or sex with the LoRA of a real person. An image whose prompt has sexual content with a minor stays blurred and cannot be shown.
- A refused prompt is not queued; a message names the tags that stopped it. Wildcards are checked after they are filled in.
- Civitai: previews follow the content mode, using Civitai's own rating of each image (SFW shows safe previews only, and blurs previews of unknown rating). Models Civitai marks as NSFW or as a real person get a label. The sync uses civitai.com in SFW and civitai.red in the other modes.
- The next Civitai sync downloads the data of the models synced before once more, to get the ratings. If it fails, what was stored before is kept.
- **Hide Prompts in Notifications** (on by default): "Batch Completed" notifications no longer show the prompt, and they never show it on the lock screen.
- **Hide App in Recents**: the recent apps screen shows no picture of the app (Android 13 and newer; always on with the App Lock).
- **Block Screenshots**: no screenshots or screen recordings of the app.
- **Save to Phone Privately**: saved images go to the app's own folder instead of the phone's gallery, so gallery apps and their cloud backup do not see them.
- **Share Without Generation Data**: shared images leave without their prompt, seed and model.
- The out-of-memory report no longer includes the content of requests to the server, which could hold prompts.
- Two quick taps on Generate with different prompts now always queue both jobs in order, each with its own prompt.

## 1.2.1
- Version number only: the app is the same as 1.2.0.

## 1.2.0
- New "Show Progress in Now Bar" option in the Push Notifications settings, for Samsung phones with One UI 8 or newer: the generation progress appears in the pill at the bottom of the lock screen and as a short percentage in the status bar. It stays off until you turn it on, and it is greyed out on other phones. While it is on, the progress notification cannot be swiped away during a generation.
- If the system does not allow ForgeGen to show live notifications, the settings say so and open the right system page.

## 1.1.5
- Overnight Batch Mode now tells the truth: a failed job is set aside in the queue with its reason (retry or remove it there, or from the card on the main screen) and the queue goes on. At the end a notification says how many jobs were done and how many failed. Failed jobs used to disappear silently, followed by "Queue Completed".
- In Overnight Batch Mode a lost connection is retried until the server is back, instead of stopping after three tries.
- Long queues keep running with the screen off: the phone is kept awake for as long as the queue works (it used to be only the first 10 minutes), and on Android 15 and newer the queue is no longer stopped after 6 hours a day.
- "Run in Background" is removed: the app works in the background only while the queue does.
- "Keep Screen On" keeps the screen on only while images are being generated, not whenever the app is open.
- "Show Active Tags UI" now works: turning it off hides the "Edit Tags" row under the prompts.
- Theme: choose System default, Light or Dark. The start screen uses the chosen theme from the first frame. A theme chosen before is kept.
- "Remove Battery Restrictions" opens the system dialog for ForgeGen directly instead of the list of all apps.
- Clearer descriptions in the settings; "Background Service & Advanced" is now "Background & Overnight".

## 1.1.4-1
- "Sync Models Now" works only while the app is connected to the Forge server; otherwise it is greyed out. If the connection is lost during a sync, the sync stops, and the models synchronized until then are kept.
- Version numbers can now carry a micro-patch number, like this one. The updater of 1.1.4 does not recognize it, so this version has to be installed from the release page once; later updates are offered in the app again.

## 1.1.4
- The start screen says "Ready" only when everything is ready to work: settings, wildcards, the saved queue, the gallery favorites and index, and the server with its model, sampler and LoRA lists. It shows each step on the way. If the server cannot be reached, the app still opens, and the start screen says "Server not reachable" instead of "Ready".
- Civitai sync starts only while the Forge server answers with status 200. Otherwise it stops at once and says why: the server is unreachable, or which status it answered with.
- New "Save Logs on Out of Memory" option in the new Permissions section of the settings: when the app or the server runs out of memory, the app saves a report with its log to Downloads (ForgeGen-OOM-date.txt). It stays off until you turn it on, and Android needs no storage permission for it.

## 1.1.3
- Generated images are read from the server's answer one at a time and written straight to storage, so a big batch needs about a third of the memory it used to (8 large images: 28 MB instead of 96 MB) and no longer risks closing the app. If a batch is still too large, the queue pauses with an explanation.
- The live preview is only downloaded while the app is on screen, which saves data and battery during long runs in the background.
- The server's memory statistics are asked for every 5 seconds instead of every second.
- The background service no longer wakes up every second while nothing changes.

## 1.1.2
- When the connection to the server drops, the job stays in the queue and is sent again once the server is back; the queue continues by itself (up to three times per job, then it waits for you to resume it). The job used to be lost, and in overnight mode the whole queue was thrown away within seconds.
- No job is sent while the server is unreachable: queued jobs wait for the connection.
- During an outage the status says "Connection lost, waiting for the server..." instead of the last percentage, and "Connection lost" appears at once instead of after several minutes.
- A job whose answer was lost with the connection no longer holds up the queue for up to two hours: once the server is back and idle, it is sent again after 10 seconds.
- A server behind a proxy that answers with an error counts as unreachable.
- Repeated connection alerts update the notification quietly instead of sounding each time.

## 1.1.1
- The queue sends jobs strictly one after another, and a queued job starts right away. While idle the queue no longer checks for work twice a second, which saves battery.
- When the connection dropped during a generation, the same job could be sent to the server again, up to four times. Each job is now sent once.
- The running job shows as generating and can no longer be removed or overtaken; "Clear" keeps it, since the server is already working on it.
- An older saved queue can no longer overwrite a newer one (finished jobs could come back after a restart), and a job added while the app starts is no longer lost.
- An unexpected error pauses the queue with its reason instead of stopping it until the app restarts.

## 1.1.0
- After an update the app shows what is new in a "What's New" window (this list).
- The gallery grid shows small thumbnails made by the server instead of downloading every full-size image, so it loads much faster and uses a fraction of the data.
- The full-screen viewer shows the thumbnail at once while the full image loads, and loads the next and previous images in advance.
- The gallery index updates itself when you open the gallery: the server reads the generation data and sends only the text, a hundred images at a time, instead of the app downloading every image. Deleted images are removed from the index.
- Search and filters now cover the whole gallery, not only the open folder, and the new "All Images" view lists every image, newest first. "Newest First" and "Oldest First" sort by date.
- New "Save to Phone Automatically" option in the gallery settings: off, favorites, or all new images (on Wi-Fi). Saved images go to Pictures/ForgeGen, the ForgeGen album of the phone's gallery.
- Pinned images are now favorites: one list of bookmarks instead of two.
- Sharing an image no longer leaves a copy in Pictures/ForgeGen_Shared.
- Fixed: the app could close when the connection dropped while indexing the gallery; cancelling indexing reported success; favorites without a date did not load; a slow folder could replace the one opened after it; Back from "Pinned" left the gallery; generated images kept filling the phone's storage; multi-line negative prompts were cut to their first line.
- "Show Grid After Batch" moved to Settings > Appearance & UI.

## 1.0.2
- Unlocking the app no longer restarts it: it returns to the screen you left, with everything as it was.
- Turning the app lock or biometric unlock on or off, and wiping app data, now ask for the phone's PIN, pattern, password or biometrics first.
- Removing the phone's screen lock no longer locks you out of the app.
- Rotating the screen no longer locks the app.
- With the app lock on, the Recents screen no longer shows a preview of the app (Android 13+).
- The "Use Native Security" switch is now called "App Lock".

## 1.0.1
- The "Recovering prompt", "Civitai Sync" and "Server not found" overlays now fade out the same way they fade in (they used to vanish instantly), and the background blur fades with them.
- The start animation begins right away instead of waiting for the app to initialise, and it no longer redraws the whole start screen on every frame.

## 1.0.0
- Versions are numbered now (major.minor.patch) and released as v1.0.0, v1.0.1, …; the start screen shows the version.
- Update messages show version names instead of build numbers.
- If you have build-1034 or older, install this version once by hand from GitHub (it keeps your data); later versions update from the app.

## build-1034
- A failed or out-of-memory generation now shows an alert, and the progress notification no longer freezes on the last percentage when the queue pauses.
- Reopening the app from the notification no longer shows empty model lists and a broken gallery.
- "Exit App" in the notification works even when the app screen is closed.
- A failed last job is no longer reported as "Queue Completed", and a single job gives one notification instead of two.
- Generation progress is silent (it made a sound at every start); errors have their own "Errors" channel.
- Swiping the app away from Recents no longer stops a running queue or the "Run in Background" service.
- "Notify during Civitai Sync" and "Auto-Dismiss" now work (the switches did nothing), and a sync with failed models is no longer reported as successful.
- Notifications posted before the first generation (e.g. gallery indexing in the background) are no longer lost; indexing ends with an "Indexed" notification.
- Model lists reload after switching between two reachable servers and after the server comes back online.
- The progress notification shows the ETA, and the notification mode descriptions match what is shown.

## build-1033
- New package name (io.github.xplod24.forgegen), so this build installs next to older ForgeGen builds instead of failing with a package conflict. It starts with fresh settings; the old app keeps its data until you uninstall it.
- The launcher label is now "ForgeGen", to tell it apart from the old "ForgeGen (Beta)".

## build-1032
- App updates now come from the latest GitHub release (Settings → App Updates → Check for Updates); the Forge server is no longer involved.
- Every build is signed with the same key, so a new release installs over the previous one without losing data.
- Favourite images show their star right after the app starts, not only after something is toggled.
- Turning "background service" on or off in Settings now really starts or stops the persistent notification.
- Notification mode "Disabled" really hides progress; "Simple" is no longer shown as a different mode.
- "Wipe Application Data" can no longer wipe anything with no box ticked, and "App Settings" now resets the settings (server, presets and profiles are kept) instead of only the prompt.
- A timeout outside 1–600 s is corrected instead of breaking every connection (a negative value used to crash the app).
- Renaming a preset to an empty or already used name keeps the old name; typing a preset name no longer loses focus after every letter.
- The Seed field can be cleared and a new seed typed from scratch (it used to jump back to -1).
- Tag weight buttons treat "(tag)" as weight 1.1 instead of nesting brackets; setting the slider to 1.0 removes the weight.
- The queue shows LoRAs with negative weights, and an expanded job card stays expanded when jobs are moved.
- Gallery path breadcrumbs work for servers running on Linux.
- The system Back button closes the Settings overlay.
- The high-VRAM warning appears once when the image size crosses the limit, not on every change.
- The LoRA tags popup no longer shows the previous LoRA's tags while loading.
- No more double "preset loaded" / "defaults updated" messages or two update-download dialogs at once.
- Removed the "Show Foreground Service Notification" switch, which did nothing.
- The app shares only its update folder with the installer instead of the whole storage.
- Removed about 1600 lines of unused code.
- Fixed generations longer than the "Connection Timeout" setting (10 s by default) failing with a timeout and pausing the queue.
- Fixed the checkpoint picked in the UI being ignored: jobs kept forcing the model that was active at app start.
- The progress bar, ETA and live preview now update during generation (in the app and in the notification).
- Notification settings (batch/queue finished, Civitai sync) and the last update check date are no longer reset on every app start.
- Presets saved without prompts no longer overwrite the current prompts when loaded.
- Wildcards (__name__) work right after app start instead of only after opening the Wildcards screen.
- Generation data with Polish or other non-Latin characters is read correctly (no garbage at the start of recovered prompts).
- A regular HTTP 500 error is no longer reported as "server out of memory"; any paused queue now shows the reason and a Resume button.
- A queue left empty after an error is no longer silently paused.
- App lock (device PIN/biometrics) is now enforced on cold start, not only after returning from the background.
- Generation info is available again for images generated in the current session.
- "Recover last prompt" falls back to the last image generated on this device when the gallery is unavailable.
- Cancel buttons for prompt recovery and Civitai sync now actually stop the task.
- Gallery indexing no longer leaks network connections and retries images that failed to download.
- Adding/removing LoRAs keeps the prompt tidy (no ",," or double spaces) and negative LoRA weights are recognised.
- The generating job can no longer be moved in the queue.
- Removed duplicate model/sampler requests and the "Custom API missing" message shown on every start.
- Android 15: the background service stops cleanly when the system time limit for data sync services is reached.

## Earlier
- Completely migrated app settings, state, and queue persistence from Jetpack DataStore to Room Database for more robust local storage.
- Refactored settings architecture into a dedicated manager.
- Fixed a bug where UI section expanded/collapsed states (Prompts, Settings, LoRAs) were forcefully reverting to their defaults upon app restart.
- Replaced the old settings title bar with new collapsible inline sections for Prompts, Settings, and LoRAs.
- Made the Prompts section collapsible like other sections.
- UI elements (Prompts, Settings, LoRAs) now reliably save their expanded/collapsed state to Room DB automatically.
- Removed redundant Settings title and back arrow from the Settings overlay.
- Settings sections are now collapsed by default and their expanded/collapsed state is persisted across app launches.
- Overhauled the Gallery filtering system. It now includes advanced metadata tracking using a hyper-fast, memory-intersected local cache.
- Replaced the simple Model `FilterChip` UI with a collapsible checkbox list.
- Added comprehensive filtering capabilities for LoRAs (collapsible checkbox list).
- Introduced "AND" / "OR" matching toggles for both Models and LoRAs filters, allowing granular multi-tag queries.
- Added textual filter inputs for File Name and Prompt Tags (Positive and Negative).
- Filtering logic is now explicitly applied via a "Confirm" button rather than updating dynamically to prevent stuttering while typing.
- Added robust sorting options: Newest First, Oldest First, A-Z (Alphabetical), and Z-A (Reverse Alphabetical).
- Added Background and Cancel actions to the Gallery Sync process. These options appear 1 second after sync begins.
- Backgrounding the sync removes the blocking UI overlay and displays a silent progress notification in the Android status bar.
- Silenced logcat output for already-indexed images to reduce noise.
- Translated all source code comments from Polish to English.
