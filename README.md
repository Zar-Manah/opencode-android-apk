<p align="center">
  <img src="opencode-icon.png" width="128" height="128" alt="OpenCode Android Logo" />
</p>

# OpenCode APK for Android 📱⚡

[![OpenCode Release](https://img.shields.io/github/v/release/Zar-Manah/opencode-android-apk?style=for-the-badge&color=blue)](https://github.com/Zar-Manah/opencode-android-apk/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%207.0%2B%20%7C%20arm64--v8a-brightgreen?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![No Root Required](https://img.shields.io/badge/Root-NO%20ROOT%20REQUIRED-success?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Crafted with Passion in Spain](https://img.shields.io/badge/Crafted%20with%20Passion%20in-Spain%20%F0%9F%87%AA%F0%9F%87%B8-FFD700?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE.md)

> **The first fully autonomous, native AI software engineering workstation and Android app factory directly on your phone (NO ROOT REQUIRED), with flawless virtual keyboard & voice dictation, zero emulation overhead, persistent cognitive memory, and bundled on-device toolchains (JDK 21, Gradle, Android SDK). Crafted with passion in Spain 🇪🇸.**

---

## ⚡ The Zero-Friction Magic: Install & Code

Transform your Android smartphone or tablet into a state-of-the-art AI software engineering workstation:

```
[ Download opencode.apk ] ➔ [ Tap Install ] ➔ [ Allow Permissions ] ➔ [ Code with AI ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest).
2. **Install** the APK on your Android device (Android 7.0+ / `arm64-v8a`).
3. **Open the app** and grant **Storage access** and **Install built apps** (plus optional **Unrestricted background battery**).
4. **You're ready!** OpenCode automatically creates your initial project workspace and opens straight into Chat (in English or Spanish based on your device language). Works out of the box with free OpenCode models as well as custom API keys.

> **Building from Source vs. Release APK:** Large pre-bundled assets (`sdk/`, `gradle-cache/`, `server/opencode`, and `toolchain/*.tar*`) exceed GitHub's 100 MB file limit and are excluded in `.gitignore`. Download `opencode.apk` from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest) for the complete 495 MB ready-to-install APK; running `./gradlew` from a `git clone` builds only the Android wrapper without those bundled tarballs.

---

## 🌟 Why OpenCode for Android Solves Every Mobile Pain Point

Anyone who has tried coding or running AI agents on Android knows the frustrating reality of existing solutions: virtual keyboards freeze and drop characters, voice dictation crashes the terminal, aggressive Android battery management kills long-running background tasks, PRoot containers run agonizingly slow and overheat the phone, agents are trapped in isolated sandboxes unable to interact with the device, and you still need a desktop PC to compile native apps.

**OpenCode APK for Android was engineered to solve every single one of these problems:**

### 1. ⌨️ Flawless Virtual Keyboard & Voice-to-Text Dictation
- **Zero Input Glitches**: Native WebView interface paired with `mobile-enhancer.js` and an Android IME Enter bridge — works cleanly with Gboard, Samsung Keyboard, SwiftKey, and external hardware keyboards without terminal buffer freezing or cursor jumping.
- **Native Voice Dictation**: Dictate prompts, architectural plans, or code refactors directly using your keyboard's microphone button cleanly in the web composer.
- **Intuitive Touch Controls**: Seamless multiline editing, full clipboard copy-paste, and natural touch navigation.

### 2. 🚀 Pure Native arm64 Execution (Zero PRoot / Container Overhead)
- **Direct User-Space Linker**: Runs natively on Android ARM64 via a high-performance musl dynamic linker (`ld-musl-aarch64`). Completely eliminates slow PRoot containers and `ptrace` system call emulation penalties.
- **Cool & Battery-Friendly**: Keeps your phone cool and avoids emulation overhead during development sessions, delivering responsive native execution.

### 3. 📱 Native App Launch, Screen Capture & Package Bridge (NO ROOT REQUIRED)
OpenCode includes a clean local device bridge (`pc`) operating within standard unrooted Android user space:
- **Eyes (`pc shot`)**: Captures current device screenshots via native Android `MediaProjection` (with persistent permission on Android 14+).
- **App Launch & Packages**: Launches apps (`pc open <package>`), lists installed packages (`pc list`), reads runtime logs (`pc shell`), and delivers system notifications (`pc notify`).
- **Native APK Installation**: Triggers Android's native `PackageInstaller` session for compiled APKs (`pc install <path.apk>`).

> **Note on Device Control:** Earlier releases included Accessibility-based full device control (automated taps, swipes, and UI hierarchy dumps). Due to Google's current Play Protect and Android security policies for sideloaded apps, shipping unrooted device UI control triggers Play Protect blocks and is no longer viable on modern Android, so it had to be removed in favor of compliant `MediaProjection` capture and `PackageInstaller` workflows.

### 4. 🏭 Autonomous On-Device Android App Factory 🔥
Turn your phone into a self-contained mobile software factory capable of designing, scaffolding, compiling, signing, installing, and visually verifying native Android applications:
- **Bundled Toolchain**: Pre-packaged OpenJDK 21, Android SDK (platforms `android-35`, build-tools `35.0.0` and `34.0.0`, native `d8`, `apksigner`, `aapt2`), and `gradle-oc`.
- **Zero PC Required for Builds**: Compiles and signs APKs directly on-device without an external computer, using the bundled toolchain and cached AGP/Kotlin dependencies.
- **Deployment & Inspection Loop**: Triggers Android `PackageInstaller` (`pc install`), launches the app (`pc open`), captures screenshots (`pc shot`), checks logs (`pc shell`), and iterates cleanly.

### 5. 🧠 Persistent Cognitive Memory Architecture
Unlike stateless sessions that forget all context when closed:
- **Episodic Long-Term Memory**: Protected by `plugins/memory.js` (`~/.config/opencode/memory/memory.json` with automatic `.bak` recovery backup, append-only `journal.jsonl`, and nightly consolidation), preserving project context, device configuration, and user preferences across upgrades and sessions.
- **Continuous Learning**: Retains successful patterns and verification strategies learned on the device.

### 6. 🔋 24/7 Background Daemon & Clean Notification Center
Android's aggressive memory cleaner terminates background tasks. OpenCode includes an optimized foreground notification control center:

```
┌─────────────────────────────────────────────────────────┐
│ OpenCode                                                │
│ ┌─────────────────────────┐   ┌───────────────────────┐ │
│ │       24/7: OFF         │   │         Exit          │ │
│ └─────────────────────────┘   └───────────────────────┘ │
└─────────────────────────────────────────────────────────┘
```

- **⚡ `24/7: OFF / ON` (WakeLock Toggle)**: Keeps the CPU awake via partial wake-lock. Off by default (`24/7: OFF`) to save battery when the screen is off; tap to switch to `24/7: ON` for long background builds or overnight runs.
- **🛑 `Exit` (Clean Shutdown)**: Instant one-tap graceful termination: frees all background processes, releases system locks, removes notifications, and restores RAM.

---

## 🏗️ Architecture Overview

```
┌──────────────────────────────────────────────────────────┐
│               Android System (Linux Kernel)              │
├────────────────────────────┬─────────────────────────────┤
│   OpenCode Host App        │   Device Bridge & Capture   │
│   (a.opencode / WebView +  │   (127.0.0.1:4399 +         │
│    mobile-enhancer.js)     │    MediaProjection API)     │
├────────────────────────────┴─────────────────────────────┤
│               Native Android User Space                  │
│  ┌────────────────────────────────────────────────────┐  │
│  │ Official OpenCode Engine (ld-musl-aarch64 :4096)   │  │
│  │ Dynamic $HOME (files/server-home)                  │  │
│  │ Cognitive Layer (plugins/memory.js, AGENTS.md)     │  │
│  │ Authenticated Bridge CLI (`pc`, X-Bridge-Token)    │  │
│  │ Native Toolchain (JDK 21 + SDK 35/34 + gradle-oc)  │  │
│  │ ~/OpenCode -> /storage/emulated/0/OpenCode         │  │
│  └────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────┘
```

---

## 🚀 Guided Initial Setup

When launching OpenCode for the first time, a streamlined setup screen guides you through the required permissions:

```
┌─────────────────────────────────────────────────────────┐
│                       OpenCode                          │
│ Storage access (/OpenCode)                  [  Allow  ] │
│ Install built apps                          [  Allow  ] │
│ Unrestricted background battery             [  Allow  ] │
│                                                         │
│ Storage: OK  ·  Installs: OK  ·  Battery: OK            │
└─────────────────────────────────────────────────────────┘
```

1. **`Storage access (/OpenCode)`**:
   Tap **Allow** to grant permission to manage all files. This allows OpenCode to read and write your workspace at `/storage/emulated/0/OpenCode`.
2. **`Install built apps`**:
   Tap **Allow** to allow installing apps created by OpenCode.
3. **`Unrestricted background battery`** *(optional, recommended)*:
   Tap **Allow** so long background builds and tasks are not paused by battery saver.

Once Storage and Installs are granted, the engine starts in the background and OpenCode launches automatically (or tap **Enter OpenCode →** to continue right away), auto-creating your initial project workspace (`initial-project` / `proyecto-inicial`) and opening straight into Chat!

---

## 🛠️ Built-in Tooling & Bridge Commands

OpenCode includes the `pc` local bridge CLI (authenticated per session with `X-Bridge-Token` via the bundled Bun runtime, zero `curl` dependency), accessible directly from the agent's environment:

| Command | Description |
|---|---|
| `pc ping` | Check device bridge status and readiness |
| `pc shot [path.png]` | Capture screen to PNG via native Android MediaProjection |
| `pc open <package\|url>` | Launch application or URL |
| `pc install <path.apk>` | Launch system PackageInstaller session for compiled APK |
| `pc shell <command>` | Execute shell commands with application permissions |
| `pc list [filter]` | Query installed packages on the device |
| `pc notify <message> [title]` | Post system notification banner |
| `gradle-oc <tasks>` | RAM-adaptive, mksh-safe on-device Gradle runner |

---

## 💻 Autonomous App Development Workflow

1. **Scaffold Project**: The agent creates the project structure in `~/OpenCode/<ProjectName>/` (symlinked to `/storage/emulated/0/OpenCode/<ProjectName>/`).
2. **Build**: Compiles on-device with `gradle-oc :app:clean assembleDebug`.
3. **Install**: Opens the Android system installer prompt via `pc install app/build/outputs/apk/debug/*-debug.apk`.
4. **Launch & Verify**: Opens the app with `pc open <package>`, captures screenshots with `pc shot /storage/emulated/0/OpenCode/tmp/<name>.png`, and inspects logs with `pc shell`.

---

## 🔒 Security

- **No Root Required**: Operates strictly within standard unrooted Android user space.
- **Transparent Open Source**: Android app source code and build configuration are publicly verifiable.

---

## 🇪🇸 Crafted with Passion in Spain

**OpenCode APK for Android** is conceived, engineered, and published from Madrid, Spain 🇪🇸 by **Zar-Manah**.

Our vision is to break the hardware boundary of modern software engineering: empowering developers, students, researchers, and creators worldwide to carry a complete, self-sustaining AI software engineering powerhouse directly in their pocket.

---

## 📄 License

Licensed under the [GNU General Public License v3.0](LICENSE.md).
