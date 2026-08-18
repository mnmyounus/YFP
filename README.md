# YFP (Your Files Protector) by MNM Younus

Securely overwrites unallocated (free) storage space on Android so that
deleted files can no longer be recovered by tools like PhotoRec or
DiskDrill. 100% offline, zero ads, zero tracking. Works on Android phones,
tablets, and Android TV.

## How it works

Deleting a file on Android (and most filesystems generally) doesn't erase
its data — it just removes the file's directory entry and marks the space
it occupied as "free." The actual bytes stay on the physical storage medium
until something else happens to write over them, which is exactly what
lets recovery tools scan free space and reconstruct "deleted" files.

YFP closes that window by deliberately writing new data across free space:

1. It creates a small number of large dummy files (1–4 GiB each, per the
   spec — a few large files sustain much higher write throughput than
   millions of tiny ones, avoiding filesystem I/O bottlenecks).
2. It fills them with either an all-zero pattern (fastest) or a
   pseudo-random pattern (defeats naive "look for the old byte pattern"
   recovery attempts and some filesystems' sparse-file shortcuts for long
   zero runs).
3. Once the configured percentage of free space (up to 95%, default 90%) is
   consumed, it stops and the user can review the result.
4. Hitting **Cancel & Delete** at any point immediately deletes every dummy
   file YFP created, giving the space back.

**This is not a forensic-grade wipe claim** (e.g. DoD 5220.22-M). On flash
storage in particular, wear-leveling means the physical NAND cells backing
any given logical sector aren't deterministic — no consumer free-space-wipe
tool on any platform can guarantee every physical cell was touched. What
YFP does guarantee is that the *logical* free space a deleted file's data
used to occupy now contains different data, which is what defeats the
recovery tools named in the spec.

## Privacy

- **No `android.permission.INTERNET`** — omitted from the manifest
  entirely, which means the OS itself prevents this app's process from
  opening a network socket, full stop, regardless of what any code inside
  it tries to do.
- **No third-party SDKs, analytics, or telemetry.** `YfpApplication` is
  intentionally empty.
- "Check for Updates" opens the GitHub releases page in the user's own
  browser via `ACTION_VIEW`; "Contact Developer" opens the user's own mail
  app via `ACTION_SENDTO` (`mailto:`). Both hand off to another app that
  does its own networking under its own permissions — this app's process
  never touches the network.

## Project structure

```
app/src/main/java/com/mnmyounus/yfp/
├── engine/           Core overwrite logic — storage-agnostic, no Android UI deps
│   ├── WipeConfig.kt         Job parameters (target, pattern, fill %, chunk size)
│   ├── FastRandomFiller.kt   Fast non-crypto PRNG for the pseudo-random pattern
│   ├── WipeTarget.kt         Abstracts internal-storage File vs SAF DocumentFile
│   ├── WipeEngine.kt         The write loop: pause/resume/cancel, progress, safety floor
│   └── DummyFileCleaner.kt   Purges all YFP dummy files from a target
├── service/
│   └── WipeService.kt        Foreground service hosting the engine + progress notification
├── ui/
│   ├── common/WipeViewModel.kt   Shared service-binding ViewModel (mobile + TV)
│   ├── mobile/MainActivity.kt    Phone/tablet UI
│   └── tv/TvMainActivity.kt      Android TV / D-pad UI (separate layout & focus handling)
└── util/
    └── StorageInfo.kt        Enumerates storage volumes, formats byte counts
```

Mobile and TV are separate `Activity` classes with separate layouts (touch
widgets like `Spinner`/`RadioGroup` don't translate well to D-pad input),
but both bind to the *same* `WipeService` through the *same*
`WipeViewModel` — there is exactly one implementation of the actual wipe
logic and one job running at a time, regardless of which UI is driving it.

## Building locally

Requires JDK 17 and the Android SDK (API 34).

```bash
./gradlew assembleDebug
```

A local debug build doesn't require any signing secrets — `app/build.gradle.kts`
only attaches the release signing config when the four `YFP_*` environment
variables described below are present, and falls back to an unsigned
release build otherwise (fine for local testing, not for distribution).

## Release signing setup (for maintainers)

The CI workflow (`.github/workflows/build-release.yml`) builds, signs, and
publishes a release APK automatically whenever a tag matching `v*.*.*`
(e.g. `v1.0.0`) is pushed. Before your first tagged release, configure four
repository secrets:

1. **Generate a release keystore** (skip if you already have one):

   ```bash
   keytool -genkeypair -v \
     -keystore yfp-release.jks \
     -alias yfp \
     -keyalg RSA -keysize 2048 -validity 10000
   ```

   Keep `yfp-release.jks` somewhere safe and back it up — losing it means
   you can never publish an update under the same signature again.

2. **Base64-encode the keystore file:**

   ```bash
   base64 -w0 yfp-release.jks > yfp-release.jks.b64   # Linux
   base64 -i yfp-release.jks -o yfp-release.jks.b64   # macOS
   ```

3. **Add four repository secrets** under
   `Settings → Secrets and variables → Actions → Repository secrets`:

   | Secret name              | Value                                      |
   |---------------------------|---------------------------------------------|
   | `YFP_KEYSTORE_BASE64`    | Contents of `yfp-release.jks.b64`           |
   | `YFP_KEYSTORE_PASSWORD`  | The keystore password you set in step 1     |
   | `YFP_KEY_ALIAS`          | `yfp` (or whatever `-alias` you used)       |
   | `YFP_KEY_PASSWORD`       | The key password you set in step 1          |

4. **Cut a release:**

   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

   The workflow builds a signed `YFP-1.0.0.apk`, verifies the signature
   with `apksigner`, and attaches it to a new GitHub Release automatically.
   If any of the four secrets is missing, the build step still succeeds but
   produces an unsigned APK — the "Verify APK was signed" step then fails
   loudly instead of silently publishing something users can't trust.

## License

Add your chosen license here.
