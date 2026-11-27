# OpenCode — Autonomous AI Coding Agent for Android

OpenCode is an autonomous on-device AI coding agent running natively in Android user space (`arm64-v8a`).

## Device Environment & Architecture

- **Operating System**: Android (Linux kernel + Android runtime)
- **Home Directory**: `$HOME` (resolved dynamically to the app's private `files/server-home`)
- **Workspace Root**: `$HOME/OpenCode` (symlink to `/storage/emulated/0/OpenCode`)
- **Native Toolchain**:
  - JDK 21: `$HOME/toolchain/usr/lib/jvm/java-21-openjdk` (wrappers in `$HOME/bin` and `$HOME/toolchain/bin`)
  - Android SDK: `$HOME/android-sdk` (`ANDROID_HOME` & `ANDROID_SDK_ROOT` pre-configured)
  - SDK Platforms: `android-35`
  - Build Tools: `35.0.0` and `34.0.0` (with `d8.jar`, `apksigner.jar`, and native ARM `aapt2`)
  - Gradle Runner: `gradle-oc` (RAM-aware mksh-safe wrapper scaling `-Xmx` to device memory)
  - Native utilities: `git`, `adb`, `aapt2`, `apksigner`, `d8`, `rg`, `iconos` in `$HOME/bin`, `$HOME/toolchain/bin`, and `PATH`
  - Global Gradle config: `$HOME/.gradle/gradle.properties` includes `android.suppressUnsupportedCompileSdk=35`
- **Local Android Bridge (`http://127.0.0.1:4399` via `pc`)**:
  - `pc ping`: Check local bridge readiness
  - `pc shot [path.png]`: Capture current device screen to PNG via Android `MediaProjection`
  - `pc open <package_name|url>`: Launch application or URL
  - `pc install <path_to_apk>`: Launch system installer session (`PackageInstaller`) for compiled APKs
  - `pc shell <command>`: Execute shell commands within the app sandbox
  - `pc list [filter]`: Query installed packages
  - `pc notify <message> [title]`: Post system notification
- **Launch & Visual Verification**:
  - Launch with `pc open <package_name>` and inspect UI visually via `pc shot /storage/emulated/0/OpenCode/tmp/app.png`.
  - Inspect app logs via `pc shell "logcat -d | grep <tag>"`.

## File Hygiene (permanent rule)

- The app cache (`cache/opencode`) holds disposable data only; Android can wipe it at any time.
- All work scratch lives in `<Project>/tmp/` (or `~/OpenCode/tmp/`). Never under the app cache.
- Shared storage (`/storage/emulated/0`) is FUSE: no symlinks (`ln -s` fails there) and no exec bit — run workspace scripts as `sh script.sh`, never `./script.sh`.

## Local Persistent Memory

OpenCode maintains persistent memory locally in `$HOME/.config/opencode/memory/memory.json` (with `.bak` backup and append-only `journal.jsonl`), managed by `plugins/memory.js`.

## Maintenance

Run `sh ~/bin/opencode-maintenance [days] [vacuum]` for WAL checkpoint (safe with app open), `tool-output` pruning, cache pruning, and log rotation (rotation and `vacuum` only with the app closed).
Verify the harness any time with: `java -version`, `gradle-oc --version`, `iconos`, `pc ping`.
