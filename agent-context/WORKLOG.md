# Work log

What an agent joining the project needs beyond the code: how the work with the owner goes, the releases of the last
sessions in detail, what was learned and what is still open. `CHANGELOG.md` has every release's notes for the app's
users, `MEMORY.md` the technical decisions behind them.

## Where things stand (2026-10-07)

- **master = 3.6.2-1** (commit 499e8bc), published as `v3.6.2-1` with `ForgeGen.apk` and `mapping.zip`. The Claude
  sessions worked on `claude/gifted-edison-fydmdg`, which equals master.
- The app: package `io.github.xplod24.forgegen` (since 3.5.2-2), signed with ForgeGen's own key (since 3.5.1), shrunk
  by R8 (since 3.4.1), Android 12+. The owner runs it on a Samsung phone with Android 16 against their own Forge
  (Forge Neo) server with Infinite Image Browsing and the tagcomplete extension.
- At 3.6.2-1: unit tests 237/237, harness 73 classes / 332 tests all green; in the rig the shots of the last releases
  (`z0`-`z11`, `q1`) and both migration tests passed (the whole rig of 76 shots was not run again).

## How the work goes

1. The owner writes in Polish, often short. Decide what kind of help it is: **reconnaissance** (answer, no code),
   **planning** (a plan to approve) or **work right away**; when the message does not say, ask (owner's rule since
   2026-10-04). Commands like "Wprowadź…", "Zrób…", "Dodajmy…" are work; "czy da się…", "jak wygląda…",
   "powiedz mi…" are questions.
2. Bigger features start with a plan: options, often as HTML mockups (artifacts such as "ForgeGen Settings Proposals",
   "ForgeGen Statistics Proposal"); the owner picks one. Large releases are built in phases, each its own commit
   ("3.6.0 phase 1: ...", "3.6.1 phase 2: ...").
3. Every change comes with tests: a unit test for pure logic, a harness scenario for behaviour, rig shots for screens.
   Visual changes are looked at before release.
4. The release is part of the change: version, CHANGELOG section, README when a feature changes, MEMORY always for
   decisions worth knowing later. Then the checks of `README.md`, the work branch, CI, master, the release.
5. The report to the owner, in Polish: what changed for them, what they must do themselves (for example "a setting you
   saved before keeps its value: switch it in Settings > Updates"), what was verified with numbers, and every caveat or
   failure honestly (a failing test is explained, never hidden).

## Releases, briefly

- **1.0-1.6:** the generation queue, the gallery through Infinite Image Browsing, notifications, app lock, debug mode,
  scheduled queue start. A content filter came in 1.3.0 and went out in 1.6.x: what users generate is up to their
  server (owner's final decision).
- **2.0-2.4:** performance and the connection flow, the start animation, updates from GitHub (2.0.2), the gallery's
  index and tabs, the settings design "B" (2.3.0), jobs from gallery images (Upscale Selected, More Like This, 2.4.0),
  tag suggestions above the keyboard (2.4.2).
- **3.0.x:** model types with VAE and text encoders, per-model defaults, the main screen redesign "A", the queue as a
  timeline, the top bar with memory meters, the CHANGELOG format with release kinds (3.0.0-4).
- **3.1-3.3:** LoRA metadata, embeddings, server styles; changing the gallery's files (delete with Undo, move, copy, ZIP),
  folder covers, Random, statistics; the server's queue, Skip Image, the server page, Restart Forge.
- **3.4.x:** the Features page (a switch per feature), network/battery/gallery performance, the image cache size, R8,
  README and the GPL license.
- **3.5.x:** the IIB key entered by the user instead of a cookie in the code, Live Updates on every Android 16, the own
  signing key, the move to the package without ".debug" through a bridge release, a slow server no longer counted as
  a lost connection.
- **3.6.0:** statistics (gallery and generation), generation history, Group by Model, Unload After the Queue, home
  screen widgets, the server's health.

## The last sessions in detail (2026-10-02 to 2026-10-07)

- **3.6.0-1 (micro-patch "Polish", owner's command; "too small for a full patch"):** the gallery index merged in memory
  instead of re-read after every image, every folder listed once a week instead of daily, three tries for an image
  whose data cannot be read, the idle ping every 4 s instead of 2. Harness G20 and G43 were adjusted to the new timing
  and to the in-memory index.
- **A question about running Forge on the phone** (Snapdragon 8 Gen 3, Illustrious models): answered as reconnaissance
  (not practical on a phone: memory, heat, speed); the owner agreed and stayed with the Forge server.
- **Code review 2026-10-03** in three groups: group 1 (code for users the app can never have) became 3.6.0-2; group 2
  (split `SetupScreen` per page and `ForgeGalleryManager` per area) became 3.6.1, with Polish comments at the owner's
  request; group 3 (dropping the ViewModel's pass-throughs, a helper for the cancellation re-throws, merging two sets
  of path helpers) was advised against: churn without a gain.
- **3.6.0-2 (micro-patch):** removed the 3.5.2-1 move bridge, the database steps 9-13 (only 13 -> 14 is left; older
  databases are rebuilt), the 1.0.2 "pinned images" and the unused `getLoras`.
- **3.6.1 (patch):** the split (every moved line checked against the original, 10 settings shots identical to the
  pixel), and the owner's update wishes: Install Updates Automatically off by default (a saved choice keeps its value),
  one automatic check a day shared by the app's start and the background job (taken before asking GitHub, so a check
  without a connection leaves the next to the next day), one notification per new version, the 6-hour job replaced by
  a daily one. The harness's start now points GitHub at the mock (G26 had counted a real update notification).
- **3.6.2 (patch):** "Install" always asks first; the session (main screen images, the one shown, a paused queue with
  its reason, the run's progress) comes back at the first start of a newer build (`SessionMemory`). Lesson: the owner
  had only asked whether this could be done; it was built and nearly released before they were asked. They accepted
  the release and asked for the "ask what kind of help first" rule, now in `MEMORY.md` and `AGENTS.md`.
- **Reconnaissance 2026-10-04:** how the gallery sorts and filters (sort by date or name only; filters by file name,
  prompt text, models, LoRAs; details such as size, sampler or steps only through the statistics).
- **3.6.2-1 (micro-patch on the owner's command):** gallery search by tags: positive and negative prompt apart, several
  tags (all must match), "Exact Tags" for whole tags as the statistics count them, each tag a chip with a cross above
  the images and in the panel. Statistics tags open an exact search in their own prompt.
- **2026-10-07:** this branch, so Codex has the context.

## Lessons

- **Ask before building.** A question is not a request; a request for a micro-patch is not a patch. When unsure,
  ask once with the options.
- **Defaults vs saved settings:** the whole `AppConfig` is saved, so a new default only reaches new installs. Say so
  and tell the owner what to switch.
- **Features that need the previous version's help** (data saved before an update) work only from the next update.
  Say so in the release notes.
- **Tests that reach the network** (the real GitHub) turn red when the world changes: point them at the mocks.
- **Look at the screens:** every UI change was checked on rig shots; a click in a shot needs the clock advanced
  (see `README.md`).
- **Keep the tools in step with the code:** a new source file needs a harness link (`relink.sh`), a new DAO method a
  `FakeDb` implementation, a changed default a changed test value.
- `MEMORY.md` section 3 still mentions `ktlint -F` over all sources: that is outdated; only changed lines are linted.

## Open threads (none started; ask the owner before any)

- **Session memory after every restart:** it now comes back only after an update. Making it come back after any
  restart is a small change in `SessionMemory.afterUpdate`; offered on 2026-10-04, not decided.
- **Session memory on the phone:** not seen working yet; the first chance is the update after 3.6.2. Ask the owner
  whether the images and a paused queue came back.
- **Gallery sorting and filtering by the indexed details** (size, steps, CFG, sampler, seed, file size): the data is in
  the index, the panel does not offer it; came up in the 2026-10-04 reconnaissance.
- **An install while a job runs** starts that job again after the update (the server may still finish the first
  run): an "after this job" option could avoid it. Not discussed with the owner.
- **Statistics tags since 3.6.2-1** are counted without escapes, and a new line now parts tags: counts may differ a
  little from before.
- The owner's numbered ideas and their state: `MEMORY.md` section 5.
