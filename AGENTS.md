# AGENTS.md

## What this is

Single-module Android app (`:app`) — a rebranded fork of [doesthings/FreeFCC](https://github.com/doesthings/FreeFCC)
(`com.dronepeak.app`). It talks DJI's DUML binary protocol to a controller-local proxy over loopback TCP plus one
abstract Unix datagram socket, and applies a CE/FCC region change to the aircraft. Compose UI, Turkish default with
English available.

All app code lives in `app/src/main/java/com/dronepeak/app/`. ~13 files, ~5.5k lines. There is no `src/main/kotlin`,
no multi-module split, and no code generation.

## Commands

```bash
./gradlew assembleDebug            # debug APK
./gradlew testDebugUnitTest        # what CI runs
./gradlew test                     # unit tests, all variants
./gradlew :app:testDebugUnitTest --tests 'com.dronepeak.app.HardwareLockTest'   # one test class
./gradlew :app:lintDebug            # full lint report (HTML)
./gradlew assembleRelease testDebugUnitTest                                     # what release.yml runs
```

- **Lint exists but no task is declared and CI never calls one.** `assembleRelease` implicitly runs AGP's
  `lintVitalRelease` (fatal-issues-only; currently "No issues found"). `:app:lintDebug` is available for the full
  report: ~20 warnings, 0 errors — obsolete Gradle deps, `ObsoleteSdkInt` (several `>= O` checks are dead because
  `minSdk = 29`), unused resources, missing data-extraction rules. Do not claim lint is clean; check the report.
- Needs **JDK 17** + **Android SDK 35**. `local.properties` is gitignored *and absent on this machine*, so Gradle
  resolves the SDK from `ANDROID_HOME`. Create `local.properties` if the SDK is not on the env.
- `keystore.properties` and `release.jks` exist locally and are gitignored. Never stage them.
- Without `keystore.properties`, `assembleRelease` **silently produces an unsigned APK** — `signingConfig = null`
  rather than a failure (`app/build.gradle.kts`). Check the output before shipping a local release build.
- `-PdronePeakRepo=owner/repo` overrides the release repo baked into `BuildConfig.DRONEPEAK_REPO`.

## Ignore these when searching

- **`.agents/`** — 388 of 440 tracked files. A generic multi-harness agent bundle (122 rules, 94 workflows,
  68 agents, 48 skills) covering Angular, C++, Rust, Flutter. Essentially nothing Kotlin/Android-specific, and
  nothing in the repo loads it — there is no `CLAUDE.md` and no hook references it. Do not grep it and do not
  treat it as project guidance.
- **`.kilo/worktrees/octagonal-height/`** — a full second checkout of this repo at the same commit, registered with
  `git worktree list` and hidden only by `.git/info/exclude` (local-only, *not* in `.gitignore`). Glob and grep will
  happily return stale duplicates. Exclude it explicitly.
- **`fcc/`** — untracked sideloading helper APKs for DJI controllers, not source.
- **`scratch/FccViewModel_diff.txt`** — a one-off merge artifact.

## Architecture

### `HardwareLock` is the central invariant

`HardwareLock.kt` is a process-wide singleton guarding **every** controller- and aircraft-facing DUML write.
`FccViewModel` (UI-triggered) and `FccKeepaliveService` (2-second re-apply loop) are separate Android components with
no shared instance, so this singleton is their only coordination.

- Any new hardware operation must call `claimHardwareOp(label)` / `endHardwareOp()` in a `finally`.
- Never unlock on behalf of another operation — `end()` is a no-op when the caller does not hold it.
- `setLed` is the deliberate exception: it targets port 40007 while keepalive targets 40009, so it does not take
  the lock. Its KDoc explains why. Preserve that reasoning if you touch it.
- `claimHardwareOp` reports contention in `AppState.message`, which the status strip renders in place. There is no
  second channel — a busy path that only calls `log()` is invisible on the page the user is looking at.

### Protocol layer

- Frames are built from JSON in `app/src/main/assets/profiles/*.json` by `Profiles` + `DumlBuilder`. Adding a command
  means adding a profile JSON, not writing Kotlin.
- `Profiles.readProfileJson` prefers `filesDir/upstream_profiles/<name>` over the bundled asset, and
  `UpdateChecker.downloadProfiles` writes there. **On-device, the in-app profile update shadows the repo copy.**
- **One frame per TCP connection** (open → write → read ACK → close) matches the DJI proxy contract. Reusing a
  connection for multiple frames breaks it. Do not "optimise" `sendFrames` into a batch.
- `DumlTransport` scans ports 40009, 40007, 8901-8904 and caches the winner. 40009 is FCC/CE/device-info, 40007 is
  LED. 4G is not TCP at all — it goes to the abstract socket `/duss/mb/0x205`.
- `DumlBuilder.validateResponse` (CRC-8, CRC-16, length, sequence, reversed routing, cmd set/id, response bit) is
  unit-tested but **only called from `sendAndReceive`**, i.e. the device-info path. `sendOneFrame` and
  `sendOneFrameUnix` read ACKs and discard them, so "FCC mode enabled" means "every TCP write succeeded", never
  "the aircraft acknowledged". Do not let copy imply more than that.
- 4G activation is **fire-and-forget by nature** — the socket never acknowledges. `FourGOutcome.WRITTEN` is
  deliberately distinct from a success state. Never render it as confirmed.

### State and tone

`AppState` is one flat data class behind a `MutableStateFlow`; the UI reads `viewModel.state` and mutates only via
ViewModel functions.

- **Never let the UI decide colour by matching user-facing text.** The app used to do `message.contains("failed")`
  / `contains("başarısız")` / `contains("not")` and that silently mis-coloured real failures — `"4G error: …"` and
  `"4G needs the aircraft connected…"` matched none of the keywords. Set `Tone` / `FourGOutcome` in the ViewModel,
  where the outcome is known. `logToneColor` in `MainActivity.kt` is the one remaining exception and applies only to
  the log tab.
- `busyProgress` is shared by connect, FCC and 4G. It is not per-operation.

### i18n

Every user-facing string goes through `TextCatalog.UiText`, with complete TR and EN tables. Adding a field to
`UiText` requires filling in **both** `tr` and `en` — the compiler enforces the constructor arity, so a missing
table is a build error rather than a runtime blank. The only inline `if (language == AppLanguage.TR)` cases left are
inside `FccViewModel`, where building the string needs the current language at call time.

### UI

`MainActivity.kt` is one file, four pager pages (Control / Info / Log / Update).

- **Screen targets, in dp:** DJI RC 2 is a 5.5" 1080x1920 panel at ~400ppi ≈ **432x768dp**. RC Pro 2 / RC Plus 2 are
  11" 2160x2560 ≈ **1130x1340dp**. `WidthClass` picks a bottom bar for compact and a navigation rail for expanded.
  Compact is the binding constraint — everything on the Control page must stay reachable without scrolling.
- Design rules, applied deliberately: flat surfaces, 1dp hairline borders, one corner radius (12dp), **no gradients
  and no glow**, colour only ever spent on meaning, uppercase wide-tracked micro-labels, monospace for technical
  values, no emoji. Do not reintroduce translucent tinted containers or gradient-filled buttons.
- A disabled control must say why. `hardwareBlockedReason` / `linkBlockedReason` / `fourGBlockedReason` return a
  reason string or null, and it is rendered next to the control. Keepalive holds the lock for a few hundred
  milliseconds every two seconds, so blocked controls are a common state, not an edge case.
- Minimum touch target 48dp; primary actions 56dp. The controller is held one-handed, often gloved.

## Testing constraints

- JVM unit tests only. `testImplementation` is `junit:junit:4.13.2` and nothing else — **no Robolectric, no
  `androidTest/`, no instrumented tests, no emulator config in CI.** A test touching `Context`, `BuildConfig`,
  `JSONObject`, or any other Android type will not compile. Keep new tests on pure logic; extract it into an
  `internal object` if it currently lives in the ViewModel (see `SerialResolution`).
- **No hardware in CI.** DUML behaviour is only verifiable on a real RC controller. A green `./gradlew test` is not
  evidence that a protocol change works, and should not be reported as such.
- The update flow has no test seam (real `HttpURLConnection` + `DownloadManager`). Test the pure parts: version
  comparison, profile JSON validation, serial resolution.

## Release

Read this before cutting a tag.

1. `versionName` must equal the tag with the leading `v` stripped — `release.yml` asserts this against
   `output-metadata.json`.
2. **Bump `versionCode` in `app/build.gradle.kts` *and* the hardcoded literal in `.github/workflows/release.yml`.**
   The workflow asserts `versionCode` equals a fixed string. `README.md` and `DRONEPEAK_RELEASE.md` only mention the
   first one, so bumping just that silently breaks every release at the verify step.
3. Tag format `v<versionName>`, e.g. `v1.5.5-dp.1`. Pushing the tag triggers `release.yml`, which builds a signed
   APK and publishes it as a release asset.
4. Never change `applicationId` or `namespace` away from `com.dronepeak.app`. Android will refuse to update over a
   differently-identified package and the in-app updater depends on it.
5. The release keystore must stay identical forever. Required secrets: `SIGNING_KEYSTORE_B64`,
   `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`.

## Upstream merge

`origin` = `emrkavak/DronePeak-FCC` (source of truth and release channel).
`upstream` = `doesthings/FreeFCC` (**source level only** — never an APK source for users).

```bash
git fetch upstream --tags
git checkout main
git merge upstream/main
```

DronePeak-owned on conflict: `app/build.gradle.kts` (namespace/applicationId), `MainActivity.kt`,
`TextCatalog.kt`, `UpdateChecker.kt`, `res/drawable/dronepeak_icon.png`, `AndroidManifest.xml` label,
`.github/workflows/release.yml`. Profile JSON merges clean normally.

**Asymmetry that is easy to "fix" wrongly:** APK updates come from DronePeak releases
(`BuildConfig.DRONEPEAK_REPO`), but profile JSON updates are fetched from **upstream** FreeFCC raw
(`UpdateChecker.UPSTREAM_REPO`). They are meant to be different.

### Behaviours a past merge dropped — check these after every merge

The 2026-09-09 integration of upstream v1.5.5 silently lost four things, all of which showed up as "the button
doesn't work":

| Behaviour | Where it lives now |
| --- | --- |
| Active serial query before the passive listen (`probeSerialActive` then `probeSerial`, 8s not 2s) | `FccViewModel.detectAndCacheSerial` |
| Manual aircraft serial entry, highest priority in resolution | `InfoPage` → `ManualSerialBlock`, `setManualSerial` |
| `isProbingSerial` state so a scan can show progress | `AppState` |
| `MODELS_WITH_4G` used for an advisory log line | `SerialResolution.modelHint` + `send4gActivationFrames` |

`DumlTransport.probeSerialActive()` still exists but had **zero callers** after that merge — a function that is
present is not a function that is wired up. After merging upstream, confirm each of the four is still *called*.

Also check that upstream's KDoc for `send4gActivationFrames` survived. A previous merge replaced it with a comment
describing a model allowlist guard that does not exist in the code, which would mislead the next reader into
"fixing" a guard that was intentionally dropped.

## Scope guardrail

`NO_REMOTE_ID.md` is a hard product constraint: **Remote ID disabling is out of scope and will not be added.** Do not
add it, stub it, or propose it. FCC/CE region switching, keepalive, 4G activation, LED control and device info are in
scope.

## Conventions

- Branches: `feat/…`, `fix/…`, `release/…`, merged into `main` by PR (see `git log --merges`).
- Commits: Conventional Commits — `feat:`, `fix:`, `fix(4g):`, `chore:`, `ci:`, `docs:`.
- Comment style is unusually deliberate: classes and non-obvious branches carry KDoc explaining the *protocol or
  hardware reasoning*, often citing an observed timing or a real failure. Comments that merely restate the code do
  not exist here. Match that when editing — and do not delete a comment that records why something is the way it is.
- If docs and code disagree, the code is the source of truth. `README.md`, `DRONEPEAK_RELEASE.md` and
  `NO_REMOTE_ID.md` have all drifted from behaviour at least once.
