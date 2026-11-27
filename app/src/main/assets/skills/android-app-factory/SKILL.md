---
name: android-app-factory
description: Native Android application factory (Kotlin + Gradle) that compiles directly on this device and installs locally. Use to create, build, sign, and install Android apps without an external computer.
---

# android-app-factory — On-Device Android App Factory (Kotlin + Gradle)

Methodology to design, code, build, sign, and install native Android applications entirely on-device using OpenCode.

---

## 0. Toolchain Verification

```bash
open-factory-setup
```

Verifies: JDK 21, RAM-adaptive Gradle runner `gradle-oc`, Android SDK in `$HOME/android-sdk` (platforms `android-35`, build-tools `35.0.0` and `34.0.0` with `d8.jar`, `apksigner.jar`), native ARM `aapt2`, `git`, `adb`, `iconos`, and `$HOME/.gradle/gradle.properties`.

GOLDEN RULE: Never export `LD_LIBRARY_PATH` globally in the environment (it affects `/system/bin` tools). Wrappers configure library paths per process.

---

## 1. Canonical Project Structure

```
MyProject/
├── settings.gradle
├── build.gradle
├── gradle.properties
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/author/myproject/
```

`settings.gradle`:

```groovy
pluginManagement {
    repositories {
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "MyProject"
include(":app")
```

Root `build.gradle` (cached versions: AGP 8.5.2 + Kotlin 1.9.24):

```groovy
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

`gradle.properties`:

```properties
android.suppressUnsupportedCompileSdk=35
```

(`gradle-oc` and `$HOME/.gradle/gradle.properties` automatically configure the optimal `-Xmx` heap for the device's RAM.)

`app/build.gradle`:

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.author.myproject"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.author.myproject"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
```

---

## 2. Compile and Install On-Device

```bash
cd /storage/emulated/0/OpenCode/MyProject
gradle-oc :app:clean assembleDebug
```

Install onto this device via the local bridge (`127.0.0.1:4399`):

```bash
pc install app/build/outputs/apk/debug/*-debug.apk
```

Launch the installed application:

```bash
pc open com.author.myproject
```

Notes:
- Always clean before building when changing signatures/packages.
- Keep `minifyEnabled false` in debug builds for fast iteration.

---

## 3. Visual Verification & Logs

Inspect compiled apps cleanly via MediaProjection (`pc shot`), which works directly on Android without external tools:

```bash
pc open com.author.myproject
pc shot /storage/emulated/0/OpenCode/tmp/app.png
pc shell "logcat -d | grep TAG_OF_THE_APP"
```

Verify APK signature and manifest with `aapt2`:

```bash
aapt2 dump badging app/build/outputs/apk/debug/*-debug.apk | head -n 25
```

---

## 4. Adaptive Icons and Release Signing

Generate adaptive icons using the bundled JDK tool (minimum 1024x1024 base image):

```bash
iconos base.png app/src/main/res/
```

Generate release signing key:

```bash
keytool -genkeypair -keystore release.keystore -alias appkey -keyalg RSA -keysize 2048 -validity 10000
gradle-oc assembleRelease
cp app/build/outputs/apk/release/*-release.apk /sdcard/Download/
```

---

## 5. Performance Guidelines

- Request highest supported refresh rate on Android 11+ displays.
- Keep background work inside proper Foreground Services with notification channels.
