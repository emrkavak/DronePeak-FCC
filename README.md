# DronePeak-FCC

DronePeak-FCC is a branded Android control panel for DJI smart controllers with a screen. It is based on the upstream FreeFCC project, keeps the DUML command behavior, and adds a permanent DronePeak-FCC application identity, redesigned compact UI, Turkish/English language support, and a dedicated DronePeak update channel.

[![License: AGPL-3.0](https://img.shields.io/badge/License-AGPL--3.0-blue.svg)](LICENSE)
[![Android](https://img.shields.io/badge/Android-SDK%2035-3DDC84.svg)](app/build.gradle.kts)
[![Upstream](https://img.shields.io/badge/upstream-doesthings%2FFreeFCC-lightgrey.svg)](https://github.com/doesthings/FreeFCC)

## Important Notice

This software is provided for educational, research, and authorized-use purposes only. Changing radio transmission behavior may be restricted or illegal in some countries or regions. You are responsible for complying with all local aviation, spectrum, and drone regulations.

DronePeak is not affiliated with, endorsed by, or sponsored by DJI. Use of this app may void warranty or support coverage. Do not use this app if you are not legally authorized to operate in the selected radio mode.

DronePeak does not include, support, or promote Remote ID disabling.

## What DronePeak-FCC Does

- Applies FCC radio profiles through local DUML commands.
- Restores CE mode with the bundled CE restore profile.
- Keeps FCC active with a foreground keepalive service.
- Sends 4G activation frames for supported enterprise aircraft and DJI Cellular Dongle 2 setups.
- Controls aircraft LEDs where supported.
- Queries controller/device information.
- Provides Turkish and English UI with a flag selector on the main panel.
- Checks this DronePeak-FCC repository's GitHub Releases for signed APK updates.
- Keeps upstream FreeFCC as a source-level merge remote, not as an in-app APK source.

## DronePeak-FCC Update Model

Users must receive updates from this repository's DronePeak-FCC releases, not from the upstream FreeFCC APK.

Why:

- Installing an upstream FreeFCC APK would replace the DronePeak-FCC app identity and UI.
- DronePeak uses its own package id: `com.dronepeak.app`.
- DronePeak-FCC releases are signed with one persistent release keystore.
- Upstream code changes are merged into this repository at source level, then published as a new DronePeak-FCC APK.

The app reads latest release metadata from:

```text
https://api.github.com/repos/emrkavak/DronePeak-FCC/releases/latest
```

GitHub Actions sets this automatically with `DRONEPEAK_REPO=${{ github.repository }}`. Local builds use `gradle.properties` or `-PdronePeakRepo=owner/repo`.

## Repository Setup

This repository uses two remotes:

```bash
origin   https://github.com/emrkavak/DronePeak-FCC.git
upstream https://github.com/doesthings/FreeFCC.git
```

`origin` is the DronePeak-FCC source and release channel. `upstream` is used only to fetch and merge FreeFCC source updates.

## Upstream Merge Workflow

Fetch upstream changes:

```bash
git fetch upstream --tags
```

Merge upstream into DronePeak:

```bash
git checkout main
git merge upstream/main
```

When conflicts occur, preserve DronePeak-owned files and settings:

- `app/build.gradle.kts`: keep `applicationId` and `namespace` as `com.dronepeak.app`.
- `app/src/main/java/com/dronepeak/app/MainActivity.kt`: keep DronePeak-FCC UI.
- `app/src/main/java/com/dronepeak/app/TextCatalog.kt`: keep Turkish/English language catalog.
- `app/src/main/java/com/dronepeak/app/UpdateChecker.kt`: keep DronePeak-FCC GitHub Releases as the APK update source.
- `app/src/main/res/drawable/dronepeak_icon.png`: keep DronePeak-FCC icon.
- `app/src/main/AndroidManifest.xml`: keep app label `DronePeak-FCC`.
- `.github/workflows/release.yml`: keep signed DronePeak release publishing.

Profile JSON files under `app/src/main/assets/profiles/` can usually be merged from upstream unless a conflict needs manual review.

## Versioning

DronePeak versions track upstream with a DronePeak-FCC suffix:

```text
1.5.3-dp.1
1.5.3-dp.2
1.5.4-dp.1
```

Rules:

- Increase `versionCode` for every published APK.
- Use `versionName = upstreamVersion + "-dp.N"`.
- Tag releases with a leading `v`, for example `v1.5.3-dp.1`.

## Signing

Android updates only work if every release APK is signed with the same keystore. Keep the release keystore private and permanent.

Required GitHub Actions secrets:

- `SIGNING_KEYSTORE_B64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

Do not commit `.jks`, `.keystore`, or `keystore.properties`.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle wrapper included in this repository

Debug build:

```bash
./gradlew assembleDebug
```

Run tests:

```bash
./gradlew test
```

Local debug build with explicit update repo:

```bash
./gradlew assembleDebug -PdronePeakRepo=emrkavak/DronePeak-FCC
```

Release build with local signing configuration:

```bash
cp keystore.properties.example keystore.properties
./gradlew assembleRelease
```

## Publish A Release

1. Confirm tests pass:

```bash
./gradlew assembleDebug test
```

2. Update `versionCode`, `versionName`, and `FccViewModel.APP_VERSION`.

3. Commit and push:

```bash
git add .
git commit -m "Prepare DronePeak release"
git push origin main
```

4. Create and push a tag:

```bash
git tag v1.5.3-dp.1
git push origin v1.5.3-dp.1
```

The `release` workflow builds a signed release APK and uploads it to GitHub Releases. The in-app updater downloads that DronePeak-FCC APK asset.

## Installation On DJI RC Controllers

General flow:

1. Build or download the latest DronePeak-FCC APK from this repository's Releases page.
2. Copy the APK to a microSD card or another controller-accessible location.
3. Install it using the controller's package installer or file manager workflow.
4. Open DronePeak-FCC.
5. Select language from the main screen if needed.
6. Power on and link the aircraft.
7. Tap `Connect`.
8. Tap `Enable FCC Mode`.
9. Enable Keepalive if DJI Fly may reset radio mode during flight.

Some DJI controllers require helper apps or a launcher to sideload APKs. Follow the known RC2/RC Pro sideloading workflow appropriate for the controller firmware.

## Project Structure

```text
app/src/main/assets/profiles/
  fcc.json
  fcc_keepalive.json
  ce_restore.json
  device_info.json
  led_on.json
  led_off.json
  4g.json

app/src/main/java/com/dronepeak/app/
  MainActivity.kt
  FccViewModel.kt
  TextCatalog.kt
  UpdateChecker.kt
  Profiles.kt
  DumlTransport.kt
  FccKeepaliveService.kt
  BootReceiver.kt
```

## License And Attribution

DronePeak-FCC is distributed under the AGPL-3.0 license inherited from the upstream FreeFCC project. The upstream source is maintained at:

```text
https://github.com/doesthings/FreeFCC
```

DronePeak-FCC-specific branding, UI, package identity, release workflow, and language layer are maintained in this repository.
