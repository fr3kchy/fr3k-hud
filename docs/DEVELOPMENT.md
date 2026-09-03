# Development

## Build environment

| Tool | Version | Source |
|------|---------|--------|
| JDK | 17 | `/usr/lib/jvm/java-17-openjdk-amd64` |
| Gradle | 8.11.1 | wrapper |
| AGP | 8.7.3 | wrapper-managed |
| Kotlin | 2.0.21 | wrapper-managed |
| Compose Compiler | 2.0.21 | matches Kotlin |
| Android SDK | 35 | `/usr/lib/android-sdk` |
| Build tools | 35.0.1, 36.0.0 | `/usr/lib/android-sdk/build-tools` |

JDK 25 is installed but AGP 8.7.3 requires JDK 17. The project pins to 17
in `gradle.properties` (`org.gradle.java.home`).

## Build

```bash
./build.sh                        # debug APK
./gradlew :app:assembleRelease    # release APK (signing config required)
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

## Test

```bash
./test.sh                                     # JVM unit tests for :core and :protocol
./gradlew :app:connectedDebugAndroidTest     # instrumentation tests (requires device)
```

## Install on emulator

```bash
emulator -avd fr3k_default35 -no-window -no-audio -no-snapshot -gpu swiftshader_indirect &
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.mcpintelligence.fr3k.hud/.ui.MainActivity
```

## Adding a new module

1. Add `include(":mymodule")` to `settings.gradle.kts`.
2. Create `mymodule/build.gradle.kts` with `id("com.android.library")` and
   `id("org.jetbrains.kotlin.android")`.
3. Add `implementation(project(":mymodule"))` to `app/build.gradle.kts`.
4. Write Kotlin under `mymodule/src/main/java/com/mcpintelligence/fr3k/mymodule/`.

## Adding a new plugin

1. Implement `Fr3kPlugin` in the appropriate module.
2. Register it in `Fr3kApplication.bootstrap()`.
3. The plugin manager handles start / stop and capability registration.

## Debugging

```bash
adb logcat -s FR3K.app FR3K.hud FR3K.plugin FR3K.core
adb shell dumpsys package com.mcpintelligence.fr3k.hud | grep -E "permission|service"
```

The companion diagnostic panel inside the app shows:

- FR3K version
- Android version + SDK level
- Permissions granted / denied
- Foreground-service state
- Overlay state
- Capability inventory
- Transport status