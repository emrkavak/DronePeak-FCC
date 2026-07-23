# DronePeak Release and Upstream Merge

DronePeak does not install APKs from `doesthings/FreeFCC`. FreeFCC stays as the upstream source remote. Users receive updates only from this repository's signed DronePeak-FCC GitHub Releases, so the DronePeak-FCC package id, UI, icon, language layer, and signing key stay intact.

## Repository Setup

Repository remotes:

```bash
git remote add origin https://github.com/emrkavak/DronePeak-FCC.git
git remote add upstream https://github.com/doesthings/FreeFCC.git
git push -u origin main
```

The app reads releases from the repo configured by `DRONEPEAK_REPO`. GitHub Actions sets this automatically to `${{ github.repository }}`. Local builds can override it:

```bash
./gradlew assembleDebug -PdronePeakRepo=emrkavak/DronePeak-FCC
```

## Upstream Merge Flow

```bash
git fetch upstream --tags
git checkout main
git merge upstream/main
```

Resolve conflicts by preserving DronePeak-owned files and settings:

- `app/build.gradle.kts`: keep `applicationId` and `namespace` as `com.dronepeak.app`.
- `app/src/main/java/com/dronepeak/app/MainActivity.kt`: keep DronePeak-FCC UI.
- `app/src/main/java/com/dronepeak/app/TextCatalog.kt`: keep Turkish/English strings and language selector.
- `app/src/main/java/com/dronepeak/app/UpdateChecker.kt`: keep DronePeak-FCC release repo as the APK update source.
- `app/src/main/res/drawable/dronepeak_icon.png`: keep the DronePeak-FCC icon.
- `app/src/main/AndroidManifest.xml`: keep app label `DronePeak-FCC`.

Upstream profile JSON files under `app/src/main/assets/profiles/` can be merged normally unless a conflict needs manual review.

## Versioning

Use the upstream version plus a DronePeak-FCC suffix:

```text
1.5.3-dp.1
1.5.3-dp.2
1.5.4-dp.1
```

Always increase `versionCode` for every APK that users may install over an older DronePeak-FCC build.

## Signing

Keep one release keystore forever. If the signing key changes, Android will not install updates over the existing DronePeak-FCC app.

Required GitHub Actions secrets:

- `SIGNING_KEYSTORE_B64`
- `SIGNING_STORE_PASSWORD`
- `SIGNING_KEY_ALIAS`
- `SIGNING_KEY_PASSWORD`

## Publish

Create and push a tag:

```bash
git tag v1.5.3-dp.1
git push origin v1.5.3-dp.1
```

The `release` workflow builds a signed APK and uploads it as a GitHub Release asset. The in-app updater downloads that DronePeak-FCC APK asset.
