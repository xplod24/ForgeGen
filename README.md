<p align="center">
  <img src="docs/images/logo.png" alt="ForgeGen" width="128" height="128">
</p>

<h1 align="center">ForgeGen</h1>

<p align="center">
  <b>Your Stable Diffusion WebUI Forge server, in your pocket.</b><br>
  A native Android app to write prompts, queue jobs and browse the gallery of your own Forge server.
</p>

<p align="center">
  <a href="https://github.com/xplod24/ForgeGen/actions/workflows/release.yml?query=branch%3Amaster"><img src="https://img.shields.io/github/actions/workflow/status/xplod24/ForgeGen/release.yml?branch=master&label=build&logo=githubactions&logoColor=white" alt="Build status"></a>
  <a href="https://github.com/xplod24/ForgeGen/releases/latest"><img src="https://img.shields.io/github/v/release/xplod24/ForgeGen?label=release&color=4C8DFF" alt="Latest release"></a>
  <a href="https://github.com/xplod24/ForgeGen/releases"><img src="https://img.shields.io/github/downloads/xplod24/ForgeGen/total?color=7F52FF" alt="Downloads"></a>
  <a href="#requirements"><img src="https://img.shields.io/badge/Android-12%2B-3DDC84?logo=android&logoColor=white" alt="Android 12+"></a>
  <a href="#tech-stack"><img src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin and Jetpack Compose"></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0--or--later-BD0000" alt="License: GPL-3.0-or-later"></a>
</p>

<p align="center">
  <a href="https://github.com/xplod24/ForgeGen/releases/latest"><b>Download the latest APK</b></a> ·
  <a href="#features">Features</a> ·
  <a href="#requirements">Requirements</a> ·
  <a href="#installation">Installation</a> ·
  <a href="#building-from-source">Building</a> ·
  <a href="CHANGELOG.md">Changelog</a>
</p>

---

## Contents

- [About](#about)
- [Features](#features)
- [Requirements](#requirements)
- [Installation](#installation)
- [Building from source](#building-from-source)
- [Project structure](#project-structure)
- [Releases and versioning](#releases-and-versioning)
- [Tech stack](#tech-stack)
- [Privacy](#privacy)
- [Acknowledgements](#acknowledgements)
- [License](#license)

## About

ForgeGen is an Android client for [Stable Diffusion WebUI Forge](https://github.com/lllyasviel/stable-diffusion-webui-forge)
(Forge Neo included). The images are made by your own server; the phone writes the prompts, runs the queue, shows the
progress and browses the results, at home on the same Wi-Fi or from anywhere through a VPN.

- **txt2img only, by design.** No img2img, inpainting, ControlNet or extras: every feature works through txt2img
  parameters, so it works the same way on every Forge server.
- **Your server decides.** The app does not judge prompts. A prompt the server refuses (HTTP 403) is set aside in the
  queue with the server's reason.
- **Built for long queues.** Jobs keep running with the screen off, survive lost connections and can run overnight.

## Features

### Prompt

- Prompt editor with a token count, Undo and Redo, Copy and Clear, a tag editor and the latest prompts under "Recent".
- **Tag suggestions above the keyboard:** Danbooru tags from the tagcomplete extension of your server (with their
  category and popularity), plus wildcards after `__`, LoRAs after `<lora:` and embeddings. The tag list is kept on
  the phone, so it works offline.
- **Wildcards** (`__name__`) kept on the phone, each replaced by one of its entries, picked at random, when a job is
  sent.
- **Embeddings** (Textual Inversion) with Prompt and Negative buttons, and the **server's styles** (`styles.csv`).
- **Presets** of prompts and settings, **Restore Last**, and sharing text to ForgeGen to use it as the prompt.

### Generation

- Model, sampler, schedule, steps, CFG, clip skip, size with aspect ratios that follow the model, batch count and
  size, seed, and hires fix (the Latent upscalers included).
- **Checkpoint types:** SD, SDXL or FLUX, with the VAE and text encoders to send, FLUX's distilled CFG, and optional
  default settings per model.
- **LoRAs:** a searchable list with pictures, a badge with the model each LoRA was trained for ("Fits SDXL"), its
  trigger words, its training details and a strength slider for each one in use.
- **Live preview** while generating, a grid of the whole batch when it is done, and Save on Server or Save to Phone.

### Queue

- A queue that runs in the background with its own notification, also with the screen off.
- **Timeline:** the start time of each job, the progress and remaining time of the running one, and when the whole
  queue should be done. Drag to reorder, Duplicate, Edit, Remove, Undo and **Skip Image**.
- **Start at** a chosen time of day.
- **Overnight Batch Mode:** a failed job is set aside with its reason (Retry is one tap) and the queue goes on. A lost
  connection is retried.
- **Other jobs on the server:** the queue shows how many jobs from Forge's web UI (or another app) run before yours.
- Notifications for finished batches and queues, a vibration, a Quick Settings tile, launcher shortcuts (Generate
  Again, Queue, Gallery) and the progress as a **Live Update** on Android 16 or newer: a chip in the status bar, the
  lock screen, and the Now Bar on Samsung phones.

### Gallery

- Your server's images through [Infinite Image Browsing](https://github.com/zanllp/sd-webui-infinite-image-browsing):
  Gallery, Favorites and All Images tabs, folder covers, a grid of 2 to 5 columns or a list, and pinch to zoom.
- **Search by prompt** across the whole gallery, from an index kept on the phone.
- **Locked galleries:** when the extension has a secret key, the app asks for it once and keeps only its fingerprint.
- **Select many:** save to the phone (or privately), share (optionally without generation data), download as ZIP, add
  to the favorites, delete with Undo, or move and copy to a folder.
- **Jobs from images:** Upscale Selected, More Like This (similar images or neighbouring seeds) and Variance on Seed
  (LoRA weights, CFG and steps varied over a range).
- All Images in newest-first or random order, and **statistics**: images per day, and the models, LoRAs and tags
  used most.

### Server

- A top bar with the connection, the ping and **VRAM and RAM meters**. The Server Memory panel unloads the model or
  **restarts Forge**.
- A server page with Forge's version, the GPU, the system, the extensions, a speed test of the server's endpoints and
  **Share Server Report** for bug reports.
- Server profiles to switch between servers. When the server does not answer, the app keeps working offline and
  tries again when you come back, when the network returns or when you queue a job.

### App

- **Settings** in categories with a search field, and a **Features** page with a switch for each feature: a feature
  switched off leaves the screen and the app stops asking the server for its data.
- **Privacy and security:** App Lock (the phone's PIN or biometrics), hide the app in Recents, block screenshots, hide
  prompts in notifications, save images privately and share them without generation data.
- **Backup and data:** export and import the settings, presets, server profiles, wildcards, gallery favorites and
  queue; the image cache size (512 MB, 1 GB or 2.5 GB); an out-of-memory report with the app's log; and a choice of
  what to wipe.
- **Updates from GitHub:** the app looks for a new release every 6 hours on Wi-Fi, checks the download's SHA-256 and
  installs it in the background where Android allows it, never while the queue works or while you use the app. After
  an update a "What's New" bar offers the release notes.
- Light, dark or system theme.
- The license, its full text and a link to the source code in Settings > Updates > License.

## Requirements

### Phone

- Android 12 (API 31) or newer.
- A network path to the server: the same Wi-Fi, or a VPN such as Tailscale or WireGuard. Plain HTTP is allowed, as
  home servers rarely have HTTPS.

### Server

| What | Needed for | Notes |
| --- | --- | --- |
| Stable Diffusion WebUI Forge or Forge Neo, started with `--api --listen` | Everything | `--api` opens the API, `--listen` lets the phone reach it. AUTOMATIC1111 works too, but of the model settings only an SD model's VAE is sent to it. |
| No API password | Everything | ForgeGen does not sign in, so don't use `--api-auth`. Keep the server on a private network or a VPN. |
| [Infinite Image Browsing](https://github.com/zanllp/sd-webui-infinite-image-browsing) extension | The gallery | Deleting, moving and copying need write access (not `IIB_ACCESS_CONTROL_PERMISSION=read-only`). With `IIB_SECRET_KEY` set, the app asks for the key once; with Forge's login on, the extension needs such a key. |
| [tagcomplete](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete) extension | Tag suggestions | Optional. |
| `--api-server-stop`, Forge started by `webui.bat` or `webui.sh` | Restart Forge | Optional. Without it the button is greyed out. |
| Forge's web UI without a login (not `--nowebui`) | Seeing other jobs on the server | Optional. Without it the progress works as before. |

For example, in `webui-user.bat` (Windows):

```bat
set COMMANDLINE_ARGS=--api --listen --api-server-stop
```

or in `webui-user.sh` (Linux, macOS):

```sh
export COMMANDLINE_ARGS="--api --listen --api-server-stop"
```

## Installation

1. Download [`ForgeGen.apk`](https://github.com/xplod24/ForgeGen/releases/latest/download/ForgeGen.apk) from the
   [latest release](https://github.com/xplod24/ForgeGen/releases/latest).
2. Open it on the phone and allow installing apps from that source. Play Protect may offer to scan it, as it does
   for any app from outside the Play Store.
3. Open ForgeGen and enter your server's address, for example `http://192.168.1.90:7860` (tap the connection in the
   top bar, or go to Settings > Server).
4. That's it: later versions arrive by themselves (Settings > Updates to check now or to turn off automatic installs).

The APK is shrunk and optimized by R8 (about 6.5 MB; up to 3.5.2 it was named `app-debug.apk`). The app's package is
`io.github.xplod24.forgegen.debug` (`io.github.xplod24.forgegen` from 3.5.2-2 on). Since 3.5.1 it is signed with
ForgeGen's own key, certificate SHA-256 `22c6e6add4c03e59b4a7106a6036f4d8781ef7c7559340f87909d6318261c06d`.

## Building from source

You need JDK 21 and the Android SDK with platform 37 (a current Android Studio has both).

```sh
git clone https://github.com/xplod24/ForgeGen.git
cd ForgeGen

./gradlew assembleDebug                      # development build (debuggable, not shrunk, version "x.y.z-DEBUG")
./gradlew assembleDebug -Pforgegen.publish   # as published: not debuggable, shrunk by R8
./gradlew testDebugUnitTest                  # unit tests
java -jar ktlint.jar "app/src/**/*.kt"       # code style check
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

- **Signing:** the published APK is signed with ForgeGen's own key, which is not in the repository, by key rotation
  (APK Signature Scheme v3) from `app/debug.keystore`, which signed it up to 3.5.0:
  [`tools/sign-apk.sh`](tools/sign-apk.sh) with the lineage in `app/signing/forgegen-lineage.bin`. A phone with 3.5.1
  or later accepts updates signed with that key only, so a local build signed with `app/debug.keystore` installs
  only after the app (and its data) is removed. With `RELEASE_KEYSTORE_FILE` and `RELEASE_KEYSTORE_PASSWORD` set
  (environment or `~/.gradle/gradle.properties`), local builds are signed with that key and update the app.
- **R8:** only `-Pforgegen.publish` builds go through R8; local builds and the tests run the code as written. Code
  that touches saved data, Gson, reflection, resources or a new library follows the "R8 rules" in
  [`MEMORY.md`](MEMORY.md).

## Project structure

```
ForgeGen/
├── app/src/main/java/com/example/forgegen/
│   ├── MainActivity.kt           entry point, navigation, app lock, splash
│   ├── MainScreen.kt             the main screen: prompt, generation and LoRA cards
│   ├── ForgeViewModel.kt         UI state for the screens
│   ├── ForgeRepository.kt        database, API client, connection and progress polling
│   ├── ForgeQueueManager.kt      the queue, progress, live preview, retries
│   ├── ForgeNetworkManager.kt    models, samplers, LoRAs, embeddings, styles
│   ├── ForgeGalleryManager.kt    the gallery through IIB, local index, favorites
│   ├── ForgeSettingsManager.kt   settings, presets, server profiles
│   ├── ForgeTagManager.kt        tag list for the suggestions
│   ├── ForgeUpdateManager.kt     updates from GitHub (with SelfUpdate.kt)
│   ├── GenerationService.kt      foreground service while the queue works
│   ├── ForgeApi.kt               the server's API (Retrofit)
│   ├── ForgeModels.kt            data classes and the Room database
│   └── ui/
│       ├── screens/              gallery, queue, settings, presets, wildcards, statistics
│       └── components/           cards, pickers, top bars, dialogs, tag suggestion strip
├── app/src/test/                 JVM unit tests
├── .github/workflows/            ci.yml (work branches) and release.yml (master)
├── tools/sign-apk.sh             signs the published APK with ForgeGen's key (key rotation)
├── CHANGELOG.md                  release notes, also shown in the app after an update
├── MEMORY.md                     architecture notes and the owner's decisions
└── CLAUDE.md                     working rules for AI-assisted development
```

## Releases and versioning

- The version lives in [`gradle.properties`](gradle.properties): `VERSION_MAJOR`, `VERSION_MINOR` and
  `VERSION_PATCH`, plus `VERSION_MICRO` for a micro-patch (`3.0.0-4`). The version code is
  `major × 100 000 000 + minor × 100 000 + patch × 100 + micro`.
- Pushing a new version to `master` with its `## <version>` section in [`CHANGELOG.md`](CHANGELOG.md) makes
  [`release.yml`](.github/workflows/release.yml) run the tests, build and sign the APK, tag `v<version>` and publish the
  release with `ForgeGen.apk` and `mapping.zip` (R8's mapping, to read crash logs). Every other push to `master` runs the tests and the build too (the **build** badge
  above). Work branches and pull requests are checked by [`ci.yml`](.github/workflows/ci.yml).
- Each release names its kind in its first line: **Bugfix**, **Polish**, **Feature** or **Overhaul**, then lists what
  is New, Changed and Fixed.

## Tech stack

| Area | Libraries |
| --- | --- |
| Language | Kotlin |
| Interface | Jetpack Compose, Material 3, Navigation Compose, SplashScreen |
| Storage | Room (SQLite) |
| Network | Retrofit, OkHttp, Gson |
| Images | Coil |
| Build | Android Gradle Plugin, KSP, R8 |
| Tests | JUnit 4, MockK, kotlinx-coroutines-test |

The exact versions are in [`gradle/libs.versions.toml`](gradle/libs.versions.toml).

## Privacy

- ForgeGen talks only to **your server** and to **GitHub's API** (to look for updates and download them).
- No accounts, no analytics, no ads. Settings, the queue, presets, wildcards and the gallery index stay on the phone.

## Acknowledgements

- [Stable Diffusion WebUI Forge](https://github.com/lllyasviel/stable-diffusion-webui-forge) by lllyasviel, and
  Forge Neo.
- [Infinite Image Browsing](https://github.com/zanllp/sd-webui-infinite-image-browsing) by zanllp, which serves the
  gallery.
- [tagcomplete](https://github.com/DominikDoom/a1111-sd-webui-tagcomplete) by DominikDoom, whose tag lists feed the
  suggestions.

## License

ForgeGen is free software, released under the [GNU General Public License v3.0 or later](LICENSE).

```
ForgeGen
Copyright (C) 2026 xplod24 (Szymon Tempiński)

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version.

This program is distributed in the hope that it will be useful, but WITHOUT
ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
```

In short: you may use, study, change and share ForgeGen, also for money, as long as every copy or changed version you
share keeps this license and comes with access to its source code. The app comes as it is, without any warranty.
