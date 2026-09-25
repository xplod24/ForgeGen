# ForgeGen

Android (Kotlin, Jetpack Compose) client for Stable Diffusion WebUI Forge.

Read `MEMORY.md` before working: it holds the architecture notes and the owner's preferences.

- The owner writes in Polish. Always answer in Polish (owner's explicit request): no English sentences or
  headings, and Polish words instead of English jargon wherever a natural one exists. Code, file names, UI texts
  and release notes stay in English.
- Releasing is part of finishing a change: raise the version in `gradle.properties` (patch for fixes and small changes,
  minor for new features, major only for a clear change across the whole repository OR on the owner's explicit
  command; a micro-patch `x.y.z-n` via `VERSION_MICRO` only on the owner's command, and `VERSION_MICRO` goes back
  to 0 whenever patch, minor or major is raised), add a `## <version>` section to the top of `CHANGELOG.md`
  and push to master. `.github/workflows/release.yml` then tags `v<version>` and publishes `app-debug.apk`. The
  `## <version>` section is also what the app shows in its "What's New" dialog after the update.
- A session cannot push tags; the workflow creates them.
