# Agent context: tools and checks

How every ForgeGen release since 2.0 was verified before it reached master, and how to run each tool. The rules are
in `../AGENTS.md`, the architecture and the owner's decisions in `../MEMORY.md`, the history in `WORKLOG.md`.

```
agent-context/
├── README.md            this file
├── WORKLOG.md           how the work went, lessons, open threads
├── CHAT_HISTORY.md      the whole conversation with the owner (2026-09-23 to 2026-10-08), secrets redacted
├── harness/             JVM test harness: the app's logic against a mock Forge server (73 classes, 332 tests)
│   ├── relink.sh        links the app's sources into it (run after adding or removing a source file)
│   └── src/             Android stubs, links to the app's code, scenario tests G1-G56
├── rig/                 Robolectric screenshot rig (Compose screens as PNG) and Room migration tests
│   ├── make-rig.sh      builds the rig as a copy of the repository
│   ├── ShotTest.kt      76 screens; MigrationTest.kt: database 13 -> 14 on a real SQLite
│   └── rig.gradle.kts.txt   what the rig adds to its copy of app/build.gradle.kts
└── scripts/
    ├── lintchanged.py   ktlint on the changed lines only
    └── cmpshots.py      compares two folders of screenshots pixel by pixel
```

## Environment

- JDK 21, the Android SDK (`export ANDROID_HOME=...`; compileSdk and build tools as `app/build.gradle.kts` says), and
  network access to Maven Central and Google's Maven for the first build.
- Always `./gradlew` (Gradle 9.6.1 from the wrapper). The harness runs with it too: `./gradlew -p agent-context/harness`.
- **Never run two Gradle builds at once on one machine** (for example the harness and an app build): they compete for
  memory and CPU, and the harness's timing-sensitive tests (pings, queue timings) then fail for no reason.

## Checks before a release, in order

1. **Unit tests:** `./gradlew :app:testDebugUnitTest` (237 tests at 3.6.2-1, about a minute). `MarkdownTest` checks the
   CHANGELOG format, `ForgeSettingsManagerConfigTest` that every `AppConfig` field survives a save and load (every
   field set to a non-default value, so a new default needs the test's value changed too).
2. **Lint the changed lines:** `python3 agent-context/scripts/lintchanged.py` (prints only problems on lines the working
   tree changes, and whole new files). Ignore `standard:function-naming` on `@Composable` functions.
3. **The JVM harness** (below): the classes a change touches while working, the whole suite before the release
   (about 25 minutes).
4. **The screenshot rig** (below) when a screen changes: shots before and after, compared pixel by pixel, and looked at.
   Run `MigrationTest` whenever the database changes.
5. **The R8 build:** `./gradlew :app:assembleDebug -Pforgegen.publish` builds the APK as published (not debuggable,
   shrunk by R8, about 6.8 MB). When a change adds Gson data, check its fields survived:
   ```
   unzip -o -q app/build/outputs/apk/debug/app-debug.apk 'classes*.dex' -d /tmp/dex
   for d in /tmp/dex/classes*.dex; do $ANDROID_HOME/build-tools/<version>/dexdump "$d" \
     | awk '/Class descriptor  : .Lcom\/example\/forgegen\/SavedSession;/{f=1} f&&/name          :/{print} f&&/Direct methods/{exit}'; done
   ```
   `MEMORY.md` ("R8 rules") lists the deeper check for risky changes.
6. **Commit and push the work branch;** `ci.yml` runs the unit tests and the publish build (it skips pushes that change
   only `*.md`). Check it with the GitHub API, for example
   `gh api "repos/xplod24/ForgeGen/actions/runs?branch=<branch>&per_page=3" --jq '.workflow_runs[] | "\(.head_sha[0:7]) \(.status) \(.conclusion)"'`.
7. **Fast-forward master** once CI is green: `git push origin <branch>:master` (only when master is an ancestor of the
   branch). `release.yml` then tests, builds, signs, tags `v<version>` and publishes `ForgeGen.apk` and `mapping.zip`
   (about 10-15 minutes); wait for both files at
   `https://api.github.com/repos/xplod24/ForgeGen/releases/tags/v<version>` before telling the owner it is out.

## The JVM harness (`harness/`)

Runs the app's real logic (the managers, the ViewModel, the network layer, the database code) on a plain JVM against
an in-process mock of the Forge server, without an emulator.

- **How it is made:** `src/main/java/android/**` and `androidx/**` are small hand-written stubs of the Android APIs the
  logic uses (Context, SharedPreferences, notifications, JobScheduler, PackageManager, ...); `src/main/kotlin/stubs`
  stubs Room, Lifecycle and a few Compose types. `src/main/kotlin/real/` holds relative links to the app's own
  sources, so it always tests this checkout's code; `src/test/kotlin/existing/` links some of the app's unit tests.
  Compose screens, activities and services are left out (the rig covers screens).
- **Run:** all of it: `./gradlew -p agent-context/harness test`; one class:
  `./gradlew -p agent-context/harness test --tests 'com.example.forgegen.G17_GalleryTest'`. Every test class runs in a
  fresh JVM (`forkEvery = 1`, the managers are process-wide objects). Results:
  `agent-context/harness/build/test-results/test/*.xml`; read failures from there (the console output is long).
- **Two classes need data that is not in the repository** and are skipped without it:
  `G29_DebugModeTest` needs `FORGEGEN_DEBUG_PASSWORD` (the owner's debug password, never written into a file) and
  `G38_TagSuggestionsTest` needs `TAGCOMPLETE_TAGS`, the `tags` folder of the a1111-sd-webui-tagcomplete extension
  (its `danbooru.csv` and `extra-quality-tags.csv`).
- **When the app changes:**
  - a new or removed source file: run `agent-context/harness/relink.sh` (a missing link shows up as "Unresolved
    reference" when the harness compiles);
  - a new Android API the logic calls: add it to the stubs (the stub returns what a test needs);
  - a new or changed DAO method: implement it in `FakeDb` (`src/test/kotlin/Support.kt`), which keeps every table
    in maps (for SQL `LIKE` queries it has a small LIKE matcher);
  - behaviour a test pinned down changed on purpose: change the test with it and say so in the commit.
- **Writing a test (`src/test/kotlin/Support.kt`):**
  - `TestApp.start(config = { copy(...) }, custom = { exchange, path, body -> handled }, seed = { settings[...] = ... },
    prepare = { /* files in cacheDir, rows in TestApp.db */ })` builds a `FakeApp` (temporary folders), a `FakeDb`, a
    `MockForge` and the real `ForgeViewModel`, and waits until the app reports "Ready".
  - `MockForge` answers Forge's API (`/sdapi/v1/...`: models, samplers, progress, memory, txt2img with real PNGs
    carrying generation data) and records every request (`calls(path)`); `generationMs`, `txt2imgStatus` and
    `custom` shape its answers. GitHub's API points at it too (no release unless a test serves one: G34, G35).
    `MockIib` (G17) and `MockIibFiles` (G43) imitate Infinite Image Browsing.
  - `onMain { }` runs on the stand-in main thread, `awaitUntil("what") { condition }` waits up to 10 s.
- **Gradle:** the harness has its own `settings.gradle.kts` and plugins (Kotlin JVM 2.4.10); it is not part of the
  app's build and CI does not run it.

## The screenshot rig (`rig/`)

Robolectric draws the real Compose screens on the JVM, and `ShotTest` saves each as a PNG.

- **Build it:** `agent-context/rig/make-rig.sh /tmp/forgegen-rig /tmp/shots`. It copies this repository (without
  `.git` and builds), replaces the unit tests with `ShotTest.kt` and `MigrationTest.kt`, and appends
  `rig.gradle.kts.txt` (Robolectric, Compose UI test, the screenshot folder) to the copy's `app/build.gradle.kts`.
  The repository itself is never changed. Run the script again whenever the app's code changes.
- **Run:** `cd /tmp/forgegen-rig && ./gradlew :app:testDebugUnitTest --tests 'com.example.forgegen.ShotTest.z*'`
  (one group) or `--tests 'com.example.forgegen.MigrationTest'`.
- **Before and after:** build the rig at the old commit and save its shots to one folder, then at the new commit to
  another, and run `python3 agent-context/scripts/cmpshots.py <before> <after>`: unchanged screens must be identical,
  the changed ones are then looked at (crop and view them).
- **Names:** the letters roughly follow the releases (`o*`/`p*` 3.0.0, `q*`/`r*` 3.0.0-4, `s*`/`t*` 3.0.1-3.1.0,
  `u*`/`v*` 3.2.0-3.3.0, `k*`/`l*`/`f*` 3.4-3.5, `z0`-`z9` the settings pages after the 3.6.1 split, `z10`/`z11` the
  gallery's tag search). Add a test for each new screen or state.
- **Tips:** a click needs `compose.onNode(...).performClick()` then `compose.mainClock.autoAdvance = true;
  compose.waitForIdle()` before `save(...)`, or the shot shows the screen before the click. The rig's copy keeps its own
  version, and the app's start asks the real GitHub, so screens can show an "Update Available" card when a newer
  release is out: that difference is expected.

## Things that went wrong before (and how to avoid them)

- `pkill -f "<pattern>"` also matched the shell running it and killed the command itself: stop background work
  through the tool that started it, or by process id.
- The harness's start once reached the real GitHub (a newer release posted a notification that a test did not
  expect); it now points GitHub at the mock (`TestApp.start`).
- Room `@Query` strings in Kotlin: `ESCAPE '\\'` in the source is one backslash in SQL; don't double it again.
- A test that counts on a default value breaks when the default changes on purpose (3.6.1 `autoInstallUpdates`):
  change the test's value, not the default.
- Visual changes were never released without looking at the shots; behaviour changes never without a harness
  scenario that shows them.
