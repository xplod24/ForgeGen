# ForgeGen

Android (Kotlin, Jetpack Compose) client for Stable Diffusion WebUI Forge.

Read `MEMORY.md` before working: it holds the architecture notes and the owner's preferences.

- The owner writes in Polish. Always answer in Polish (owner's explicit request): no English sentences or
  headings, and Polish words instead of English jargon wherever a natural one exists. Code, file names, UI texts
  and release notes stay in English.
- Code comments stay in English, except where the owner asked for Polish ones to learn from (since 3.6.1): the files
  in `ui/screens/settings/` and `gallery/`, and the `// PL:` notes in `ForgeGalleryManager.kt`. Code added there
  gets Polish comments too, in the same style (a header "Co tu jest / Jak to działa / Do poczytania", a line before
  each function); older English comments there may stay.
- Releasing is part of finishing a change: raise the version in `gradle.properties` (patch for fixes and small changes,
  minor for new features, major only for a clear change across the whole repository OR on the owner's explicit
  command; a micro-patch `x.y.z-n` via `VERSION_MICRO` only on the owner's command, and `VERSION_MICRO` goes back
  to 0 whenever patch, minor or major is raised), add a `## <version>` section to the top of `CHANGELOG.md`
  and push to master. `.github/workflows/release.yml` then tags `v<version>` and publishes `ForgeGen.apk`. The
  `## <version>` section is also what the app shows in its "What's New" dialog after the update. A change that adds,
  removes or renames a feature, a server requirement or a build step also updates `README.md`.
- Every `## <version>` section (owner's rule since 3.0.0-4) starts with one line naming the release's kind in bold and
  summing it up (`**Bugfix** · a lighter top bar ...`), kinds: **Bugfix** (mainly fixes), **Polish** (small changes
  to the look and use), **Feature** (new features), **Overhaul** (a large rework of a part of the app or all of it).
  Its items then go under `### New`, `### Changed` and `### Fixed`, in that order, leaving out the empty ones.
  MarkdownTest checks this for every section from 3.0.0-4 on.
- A session cannot push tags; the workflow creates them.
- The published APK is shrunk and optimized by R8 (owner's decision since 3.4.1), but local builds and all tests run
  unshrunk code. Code touching saved data, Gson, reflection, resources or a new library follows "R8 rules" in
  `MEMORY.md`.
