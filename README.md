<p align="center">
  <img src="opencode-icon.png" width="128" height="128" alt="OpenCode Android Logo" />
</p>

# OpenCode for Android (Unofficial) 📱⚡

[![Platform](https://img.shields.io/badge/Platform-Android%208.0%2B%20%7C%20arm64--v8a-brightgreen?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Status](https://img.shields.io/badge/Status-Unofficial%20Community%20Port-orange?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Internet Required](https://img.shields.io/badge/Internet-Required%20for%20AI%20Agent-blue?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![Crafted with Passion in Spain](https://img.shields.io/badge/Crafted%20with%20Passion%20in-Spain%20%F0%9F%87%AA%F0%9F%87%B8-FFD700?style=for-the-badge)](https://github.com/Zar-Manah/opencode-android-apk)
[![License](https://img.shields.io/badge/License-GPL%20v3-yellow?style=for-the-badge)](LICENSE.md)

> **An unofficial community adaptation of OpenCode for Android. It operates under the same core concept as the desktop version, but redesigned specifically for mobile devices: it is not official and not identical to desktop, but adapted for touchscreens, virtual keyboards, and Android system workflows. The AI agent requires an active internet connection to communicate with language models. Crafted with passion in Spain 🇪🇸.**

---

## ⚡ Quick Start: Install & Setup

Get started with OpenCode on your Android device:

```
[ Download APK ] ➔ [ Tap Install ] ➔ [ Grant 4 Permissions ] ➔ [ Code with AI ]
```

1. **Download `opencode.apk`** from [GitHub Releases](https://github.com/Zar-Manah/opencode-android-apk/releases/latest).
2. **Install the APK** on your Android device (Android 8.0+ / `arm64-v8a`).
3. **Open the app** and complete the 4-step permission setup.
4. **Ready.** Connect to the internet, configure your model API keys, and start working with the AI agent.

---

## 🌟 Mobile-Specific Adaptations

Bringing an agentic development workflow to a mobile phone requires addressing Android-specific constraints:

### 1. ⌨️ Mobile Virtual Keyboard & Voice Dictation
- **Smooth Touch Input**: Works reliably with native mobile keyboards (Gboard, Samsung Keyboard, SwiftKey) and external hardware keyboards without input freezing or cursor jumping.
- **Voice Dictation**: Dictate prompts, architectural plans, or refactoring instructions directly using your keyboard's microphone button.
- **Touch-Friendly Controls**: Natural multiline editing, full clipboard copy-paste, and gesture scrolling.

### 2. 🚀 Native arm64 Execution
- **Direct User-Space Linker**: Runs directly on Android ARM64 via `ld-musl-aarch64`, avoiding slow container emulation or heavy virtual machines.
- **Battery-Conscious**: Fast startup and efficient resource usage during development sessions.

### 3. 📱 Device Interaction & Testing (No Root Required)
The agent includes local tools to interact with the device and verify what it builds:
- **Visual Inspection (`pc shot` & `pc dump`)**: Captures screenshots and inspects the active accessibility UI hierarchy.
- **Interaction Testing (`pc tap` & `pc swipe`)**: Simulates touch taps and gestures to navigate and test interfaces.
- **Application Control**: Launches applications (`pc open <package>`), lists installed packages, and posts system notifications (`pc notify`).

### 4. 🛠️ Integrated On-Device Build Tools
The online AI agent designs and writes code, and can compile and test native Android apps directly on the phone without needing an external desktop PC:
- **Bundled Toolchain**: OpenJDK 21, Android SDK build tools (`d8`, `apksigner`, `aapt2`), and `gradle-oc`.
- **Agent-Driven Workflow**: The connected agent scaffolds the project, triggers compilation via `gradle-oc`, installs the resulting APK (`pc install`), and visually verifies the interface.

### 5. 🧠 Persistent Memory Architecture
- **Episodic Long-Term Memory (`~/.config/opencode/memory/memory.json`)**: Retains project context, preferences, and configurations across sessions so you don't start from scratch every time you open the app.

### 6. 🔋 Background Session Management
- **WakeLock Toggle (`24/7`)**: Keeps background builds or long-running tasks active when the screen turns off.
- **Clean Shutdown (`Exit`)**: Terminates background processes and frees memory with a single tap.

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
│  │ OpenCode Engine CLI (ld-musl-aarch64 :4096)      │  │
│  │ Local Cognitive Layer (memory.json, AGENTS.md)   │  │
│  │ Device Bridge CLI (`pc`)                         │  │
│  │ Local Build Toolchain (JDK 21 + SDK + gradle-oc) │  │
│  │ Workspace Root: ~/OpenCode (/sdcard/OpenCode)    │  │
│  └──────────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────────┘
```

---

## 🚀 Guided Initial Setup

When launching OpenCode for the first time, a streamlined setup screen guides you through the 4 required permissions:

```
┌─────────────────────────────────────────────────────────┐
│                       OpenCode                          │
│ To grant OpenCode device control, follow steps:         │
│                                                         │
│ [OpenCode]       Downloaded apps → OpenCode             │
│ [App info]       Allow restricted settings (3 dots ⋮)   │
│ [Allow files]    Manage all files                       │
│ [Allow installs] Install unknown apps                   │
│                                                         │
│ Bridge: connected  ·  Files: ok  ·  Installs: ok        │
└─────────────────────────────────────────────────────────┘
```

1. **`OpenCode`**: Enable the accessibility service under *Accessibility > Downloaded apps*.
2. **`App info`**: On Android 13+, tap the 3 dots (⋮) in App Info and select *Allow restricted settings*.
3. **`Allow files`**: Grant storage management to save workspaces at `/storage/emulated/0/OpenCode`.
4. **`Allow installs`**: Allow installing apps compiled by the agent for testing.

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

## 🔒 Security & Privacy

- **Local Build Tooling**: Compiles directly on your device without transmitting source code to external build servers.
- **No Root Required**: Operates strictly within standard unrooted Android user space.
- **Transparent Open Source**: All source code and build recipes are publicly verifiable.

---

## 🇪🇸 Crafted with Passion in Spain

**OpenCode for Android** is an independent, unofficial project conceived, engineered, and published from Madrid, Spain 🇪🇸 by **Zar-Manah**.

---

## 📄 License

Licensed under the [GNU General Public License v3.0](LICENSE.md).
