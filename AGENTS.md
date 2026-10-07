# ForgeGen: notes for coding agents

ForgeGen is an Android (Kotlin, Jetpack Compose) client for Stable Diffusion WebUI Forge, built by its owner together
with AI coding agents since March 2026 (Claude Code for most of the work since September 2026). This branch, `codex-context`, is master as of 3.6.2-1
plus the working context of those sessions, so another agent (Codex) can carry on with the same rules, tools and
history. Read, in this order:

1. **This file**: the rules.
2. **`MEMORY.md`**: architecture notes (section 1), the owner's preferences and UI principles (section 2), and the
   design decisions of every release. It is the project's long-term memory: keep it true when the code changes.
3. **`agent-context/README.md`**: how a change was verified before every release (unit tests, the JVM harness, the
   screenshot rig, the R8 build, CI, the release workflow) and how to run each tool.
4. **`agent-context/WORKLOG.md`**: how the work went, the latest releases in detail, lessons learned and open threads.

`CLAUDE.md` holds these rules for Claude Code; when a rule changes, change both files.

## Working with the owner

- **Language:** the owner writes in Polish. Always answer in Polish (the owner's explicit request): no English
  sentences or headings, and Polish words instead of English jargon wherever a natural one exists. Code, file names,
  UI texts, code comments and release notes stay in English, except the Polish comments below.
- **Ask what kind of help first** (owner's rule, 2026-10-04): before starting on a new idea, question or request,
  unless the owner already said which, ask whether they want **reconnaissance** (research and an answer, no code
  changes), **planning** (a plan to approve before any code) or **work right away** (build, test and release). A
  question such as "czy jesteśmy w stanie…" or "czy da się…" is never by itself a go-ahead to build or release.
- **The owner decides** the version kind of a release when they name it ("zrób to jako micro patch"), the designs
  (mockups were offered as two or three options and the owner picked one), and anything that changes saved data,
  signing, the package name or the server's requirements.
- **Polish comments for learning** (owner's request, 3.6.1): the files in `app/src/main/java/com/example/forgegen/
  ui/screens/settings/` and `.../gallery/`, and the `// PL:` notes in `ForgeGalleryManager.kt`, carry Polish
  explanations: a header "Co tu jest / Jak to działa / Do poczytania" and a line before each function. Code added
  there gets Polish comments in the same style; everywhere else comments are English.

## Releasing (part of finishing a change)

- Raise the version in `gradle.properties`: patch for fixes and small changes, minor for new features, major only for
  a clear change across the whole repository or on the owner's command. A micro-patch `x.y.z-n` (`VERSION_MICRO`)
  only on the owner's command; `VERSION_MICRO` goes back to 0 whenever patch, minor or major is raised.
- Add a `## <version>` section at the top of `CHANGELOG.md`, written for the app's user in English. Its first line
  names the kind in bold and sums the release up (`**Polish** · ...`); kinds: **Bugfix**, **Polish**, **Feature**,
  **Overhaul**. Items go under `### New`, `### Changed`, `### Fixed`, in that order, leaving out empty ones.
  `MarkdownTest` checks this. The section is also what the app's "What's New" bar shows after the update.
- A change that adds, removes or renames a feature, a server requirement or a build step also updates `README.md`.
- Verify (see `agent-context/README.md`), push the work branch, wait for its CI (`ci.yml`) to pass, then fast-forward
  master (`git push origin <branch>:master`). `release.yml` then tags `v<version>` and publishes `ForgeGen.apk` and
  `mapping.zip`; agents do not push tags. Never force-push master or rewrite its history.

## Code rules

- **R8:** the published APK is shrunk and optimized by R8 (since 3.4.1), but local builds and every test run unshrunk
  code. Code touching saved data, Gson, reflection, resources or a new library follows "R8 rules" in `MEMORY.md`
  (Gson data classes stay in `com.example.forgegen`, no reflection by name, resources only through `R.`,
  `-dontobfuscate` stays).
- **Scope:** txt2img only (no img2img, inpainting, ControlNet, extras or interrogate). The app does not judge prompts;
  the server's answers decide.
- **UI:** no intrusive UI (no random toasts or pop-up dialogs in normal use); every enter animation has a matching
  exit.
- **Style:** match the surrounding code and comment density. ktlint (`ktlint.jar` at the root) is checked on the lines
  a change touches (`agent-context/scripts/lintchanged.py`); never run `ktlint -F` over whole existing files (new files
  are fine). `@Composable` functions in UpperCamelCase are accepted.
- **Repository:** `ktlint.jar` and `app/release/` are tracked on purpose; don't untrack them.
- **License:** GPL-3.0-or-later, author "xplod24 (Szymon Tempiński)"; new dependencies must be GPL-3.0-compatible.

## Security (never break these)

- The release keystore (`*.p12`, `*.jks`, in `.gitignore`), its password and any key backup are never committed or
  printed. Only CI holds them, as secrets.
- The debug mode's password is never committed: only its PBKDF2 hash is in the app. The harness reads it from
  `FORGEGEN_DEBUG_PASSWORD`; ask the owner for it and never write it into a file.
- Never call IIB's `db/update_image_data` or any other `/db/*` endpoint: it blocks the whole Forge server.
- The old hard-coded IIB cookie value must never come back; the gallery key (3.5.0) is entered by the user and only
  its fingerprint is saved.

## This branch

- It is master at 3.6.2-1 (commit 499e8bc) plus `AGENTS.md` and `agent-context/`. App code changes go to a work
  branch and to master as described above; this branch only carries the context. When a tool changes, update
  `agent-context/` here too.
- Not included on purpose: the raw session transcripts (very large, and they may hold values that must never be
  committed), the tagcomplete tag files the harness's G38 reads (about 25 MB), the debug password and the release key.
