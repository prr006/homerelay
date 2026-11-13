# BUILDING.md

How to build, test, package and sign HomeRelay from a clean checkout.

## Prerequisites

| Tool | Version (pinned) |
|---|---|
| JDK | 17 (Temurin recommended) |
| Android SDK | Platform `android-36`, build-tools `36.0.0` (installed by CI automatically; locally via SDK Manager or `sdkmanager`) |
| Gradle | 9.7.1 via the checked-in wrapper (`./gradlew`) — do not install Gradle separately |
| Android Studio | Optional but recommended (Koala/Ladybug or newer); Gradle Kotlin DSL project, no extra plugins required |

Environment variables: `ANDROID_HOME` (or `ANDROID_SDK_ROOT`) pointing at your SDK.

## Open in Android Studio

1. File → Open → select the repository root (not the `app/` folder).
2. When asked to install the SDK Platform 36, accept.
3. Run configuration: `app` — target any Android 8.0+ device or the M01/M36.

## Command-line build

```sh
# Debug APK (signed with the Android debug key — installable)
./gradlew :app:assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# Release APK
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release-unsigned.apk   (unless signing is configured — see below)

# Core framework-free test suite (36 tests)
./tools/run-tests.sh
```

`tools/run-tests.sh` compiles `app/src/main/java` (the Java core) plus `tests/` against an
Android platform jar and runs a plain `main()`-style test runner — it needs only a JDK 11+.
If `$ANDROID_HOME/platforms/*/android.jar` exists it is used with `javac`; otherwise set
`HOMERELAY_TOOLCHAIN` to a directory containing `android-31-api.jar` (+ `ecj.jar` to build
with the Eclipse compiler). In CI, `javac` and the runner's preinstalled SDK are used.

## Signing builds

### Debug
Nothing to do — `isMinifyEnabled = false`, signed with the standard debug keystore.

### Release
Release signing is **off by default and no keys are committed** (`.gitignore` must keep
`*.jks`/`*.keystore` out; never commit one). Two ways to sign:

1. **CI / GitHub Actions:** set repository secrets `HOMERELAY_RELEASE_STORE_FILE`
   (absolute path to the keystore *inside the runner*, e.g. produced by an earlier step),
   `HOMERELAY_RELEASE_STORE_PASSWORD`, `HOMERELAY_RELEASE_KEY_ALIAS`,
   `HOMERELAY_RELEASE_KEY_PASSWORD`. When all four are present and non-empty, the build
   applies the signing config automatically; otherwise the release APK is left **unsigned**
   (this is deliberate — the workflow never invents a key).
2. **Local:** create your own keystore and export the same four variables before running
   `./gradlew :app:assembleRelease`:

```sh
mkdir -p ~/keystores
keytool -genkeypair -v -keystore ~/keystores/homerelay-release.jks \
    -alias homerelay -keyalg RSA -keysize 2048 -validity 10000
export HOMERELAY_RELEASE_STORE_FILE="$HOME/keystores/homerelay-release.jks"
export HOMERELAY_RELEASE_STORE_PASSWORD='…'
export HOMERELAY_RELEASE_KEY_ALIAS='homerelay'
export HOMERELAY_RELEASE_KEY_PASSWORD='…'
./gradlew :app:assembleRelease
```

If you lost your release key, Android (correctly) refuses to install an "update" over an
install signed with a different key — uninstall first or keep your keystore safe.

## CI workflow (`.github/workflows/build.yml`)

Jobs:

1. **Core JVM tests** — checks out the repo, JDK 17, ensures a platform jar exists, runs
   `./tools/run-tests.sh`. Fast and always required before the APK build runs.
2. **Build APKs** — JDK 17, Gradle cache, SDK platform 36 (+ build-tools) if missing, then
   `gradlew :app:assembleDebug` and `:app:assembleRelease`, collects both APKs into `out/`,
   uploads them as the `homerelay-apks` artifact. On failure it posts the Gradle log tail as a
   comment on issue #1 (readable diagnostics). Requires `contents: read` and `issues: write`.
3. **Publish GitHub release** — only on `v*` tags; downloads the artifacts, writes
   `SHA256SUMS.txt`, and creates/updates the release with both APKs and their checksums.
   Permissions: `contents: write`.

The workflow never stores or echoes keystores/passwords. Gradle caches via
`gradle/actions/setup-gradle@v4`.

Cutting a release locally:

```sh
# everything green on main first
git tag v1.0.0
git push origin v1.0.0
```

The tag triggers job 3, which attaches `homerelay-debug.apk`, `homerelay-release.apk` and
`SHA256SUMS.txt` to the release. Verify downloads there; do not trust APKs from anywhere else.

## Toolchain quirks worth knowing

- AGP 9.3.2 uses built-in Kotlin for Android modules; there is intentionally **no**
  `org.jetbrains.kotlin.android` plugin. Compose support comes from
  `org.jetbrains.kotlin.plugin.compose` (2.3.0). A top-level `kotlin { }` block in
  `app/build.gradle.kts` is **not** supported under this AGP — do not re-add it.
- `release` uses R8 (`proguard-rules.pro`, kept intentionally minimal — no reflection-based
  serialization). If you add a library that needs `-keep` rules, add them there.
- The Java core under `app/src/main/java` is plain Java compiled against the Android SDK —
  it is also what the JVM test suite exercises, so keep it Android-framework-free.
- `dist/` contains compiled test classes and is a build artifact — it is not source.
