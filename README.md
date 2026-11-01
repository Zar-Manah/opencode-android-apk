<p align="center">
  <img src="opencode-icon.png" width="128" height="128" alt="OpenCode Android Logo" />
</p>

# OpenCode for Android (Unofficial Port) 📱⚡

[![Unofficial Community Port](https://img.shields.io/badge/Status-Unofficial%20Community%20Port-orange?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Release](https://img.shields.io/github/v/release/Zar-Manah/opencode-android-apk?style=for-the-badge&color=blue)](https://github.com/Zar-Manah/opencode-android-apk/releases/latest)
[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%7C%20arm64--v8a-brightgreen?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Connection Required](https://img.shields.io/badge/Internet-Required%20for%20AI%20Models-red?style=for-the-badge)](#-important-requirements)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE.md)

---

> [!IMPORTANT]
> ### ⚠️ Unofficial Community Port & Legal Disclaimer
> **This project is an independent, unofficial community port developed by Zar-Manah.**
> * **Not Official:** This project is **not affiliated with, endorsed by, sponsored by, or maintained by the official OpenCode project or its creators**.
> * **Redesigned for Mobile:** It aims to bring a desktop-like OpenCode agentic coding experience to Android devices, but **it is not the exact same desktop application**. It has been redesigned and adapted specifically for mobile touchscreens, virtual keyboards, and Android system architecture.
> * **Active Internet Connection Required:** This application is **NOT offline**. The AI agent **strictly requires an active internet connection** to query LLMs and cloud AI APIs. While build utilities (JDK, Android SDK build-tools) are stored locally so the agent can build APKs directly on the device, the AI agent itself cannot operate without network access.

---

## 💡 What is this project?

On desktop computers, OpenCode provides a powerful terminal-driven AI coding assistant. However, running AI software development directly on an Android device has always suffered from severe mobile limitations: virtual keyboards glitch or freeze, mobile OSs kill background processes, and mobile interfaces lack native device-testing tools.

This **unofficial Android adaptation** bridges that gap:
1. **Desktop-Like Agentic Workflow for Mobile:** Redesigned interface tailored for Android, enabling an AI coding agent to plan, edit code, and manage workspaces directly on your phone.
2. **Online AI Agent:** Connects via internet to AI model providers (OpenCode models, custom API keys) to interpret prompts and execute tasks.
3. **On-Device Android Compilation:** Packages local build tools (OpenJDK 21, Android SDK platform tools, `gradle-oc`) so that when the online agent writes code, it can compile, package, and test native Android APKs directly on the device without needing an external PC.
4. **Device Interaction Bridge (`pc` CLI):** Enables the agent to inspect its own running apps (via Android accessibility), send test taps/swipes, take screenshots, and verify interfaces.

---

## ⚡ Quick Start: Install & Setup

```
[ Download APK ] ➔ [ Install ] ➔ [ Grant 4 Permissions ] ➔ [ Connect & Code ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest).
2. **Install** on your Android device (Android 8.0+ / `arm64-v8a`).
3. **Open the app** and follow the 4 setup steps to grant the necessary Android permissions.
4. **Connect to the Internet:** Configure your API keys or model endpoints. The AI agent connects online to process your requests and start building.

---

## 🌟 Key Features Adapted for Android

### 1. ⌨️ Mobile-Optimized Input & Voice Dictation
- **Smooth Keyboard Support:** Optimized for native mobile keyboards (Gboard, Samsung Keyboard, SwiftKey) and external hardware keyboards without input lag or cursor jumps.
- **Voice Dictation:** Speak your prompts and coding instructions using your keyboard's microphone.
- **Touch-Friendly Controls:** Multiline editing and straightforward clipboard management.

### 2. 🚀 Native arm64 Execution (No PRoot Container)
- **Direct User-Space Execution:** Operates on Android ARM64 via a lightweight dynamic musl linker (`ld-musl-aarch64`), avoiding slow container emulation overhead.
- **Battery-Conscious:** Efficient execution that minimizes phone heat and unnecessary battery drain.

### 3. 📱 Device Interaction & Testing (No Root Required)
The agent interacts with the device via local accessibility and system bridges to test the applications it develops:
- **Visual Verification (`pc shot` & `pc dump`):** Fast screenshot captures and UI view hierarchy inspection.
- **Interaction Testing (`pc tap` & `pc swipe`):** Sends touch gestures to test UI components.
- **App Management:** Installs generated APKs (`pc install`) and launches apps (`pc open <package>`).

### 4. 🏭 Integrated On-Device Build Tools
Enables the online agent to turn code into runnable Android packages directly on your phone:
- **Bundled Toolchain:** OpenJDK 21, Android SDK (platforms `android-35`, build-tools `35.0.0` and `34.0.0`, `d8`, `apksigner`, `aapt2`), and `gradle-oc`.
- **Autonomous Build-and-Test Loop:** The agent writes code, triggers `gradle-oc` to build the APK, installs it locally via `pc install`, and checks the resulting screen with `pc shot`.

### 5. 🔋 Background Session Management
- **Keep-Alive Toggle (`24/7`):** Uses an optional foreground service wake-lock to prevent Android from killing active builds or indexing operations when the screen turns off.
- **Graceful Shutdown (`Exit`):** Stops background processes and releases memory cleanly with a single tap.

---

## 🏗️ Architecture Overview

```
┌────────────────────────────────────────────────────────┐
│             Android System (Linux Kernel)              │
├──────────────────────────┬─────────────────────────────┤
│   OpenCode Mobile App    │   Native Accessibility      │
│   (UI & Lifecycle)       │   Bridge Server (:4399)     │
├──────────────────────────┴─────────────────────────────┤
│             Native Android User Space                  │
│  ┌──────────────────────────────────────────────────┐  │
│  │ OpenCode Core Engine CLI (musl-aarch64 :4096)    │  │
│  │ Local Cognitive Layer (memory.json, AGENTS.md)   │  │
│  │ Device Bridge CLI (`pc`)                         │  │
│  │ Local Toolchain (JDK 21 + SDK 35/34 + gradle-oc) │  │
│  │ Workspace Root: ~/OpenCode (/sdcard/OpenCode)    │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

## 🛠️ Bridge Command Reference (`pc`)

| Command | Description |
|---|---|
| `pc ping` | Check device bridge status and health |
| `pc shot [path.png]` | Capture screenshot of current screen |
| `pc dump` | Dump active window accessibility view hierarchy XML |
| `pc tap <x> <y>` | Send touch tap to screen coordinate |
| `pc swipe <x1> <y1> <x2> <y2> [ms]` | Send smooth swipe gesture across screen |
| `pc text <string>` | Type text into the active input field |
| `pc key <BACK\|HOME>` | Trigger Android system navigation keys |
| `pc open <package>` | Launch application by package name |
| `pc install <path.apk>` | Install an APK on the device |
| `pc shell <command>` | Execute shell commands within app sandbox permissions |
| `pc list [filter]` | Query installed packages on the device |
| `pc notify <message> [title]` | Post system notification banner |
| `gradle-oc <tasks>` | On-device Gradle runner for builds |

---

## ⚖️ Trademarks & Legal Notice

- **Independent Project:** This is an independent open-source project by **Zar-Manah** (Spain 🇪🇸) and is **not an official product** of OpenCode.
- **Trademarks:** Any product names, logos, brands, and other trademarks referenced within this repository are the property of their respective trademark holders. Reference to them does not imply any affiliation with or endorsement by them.
- **License:** Licensed under the [GNU General Public License v3.0](LICENSE.md).
