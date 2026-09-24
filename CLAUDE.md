# ForgeGen

Android (Kotlin, Jetpack Compose) client for Stable Diffusion WebUI Forge.

Read `MEMORY.md` before working: it holds the architecture notes and the owner's preferences.

- The owner writes in Polish; answer in Polish.
- Releasing is part of finishing a change: raise the version in `gradle.properties` (patch for fixes and small changes,
  minor for new features, major only when the owner asks), add a `## <version>` section to the top of `CHANGELOG.md`
  and push to master. `.github/workflows/release.yml` then tags `v<version>` and publishes `app-debug.apk`.
- A session cannot push tags; the workflow creates them.
