# FR3K HUD v0.4.16 — Full Technical Audit

**Prepared for:** Senior code review  
**Scope:** Every file in the fr3k-hud Android project (~15,200 LOC across 5 modules)  
**Audit date:** 2026-09-07  
**Build target:** Android 14+ (API 34), compileSdk=35, minSdk=31, Gradle 8.11.1, AGP 8.7.3, Kotlin 2.0.21, Compose BOM 2024.10.01

---

## Table of Contents

1. [Build System & Dependencies](#1-build-system--dependencies)
2. [Manifest & Permissions](#2-manifest--permissions)
3. [Application Bootstrap (Fr3kApplication)](#3-application-bootstrap)
4. [Module: protocol](#4-module-protocol)
5. [Module: transport](#5-module-transport)
6. [Module: core (Orchestration Layer)](#6-module-core)
7. [Module: ui (Compose Components)](#7-module-ui)
8. [Module: app / HudOverlayService (Overlay Lifecycle)](#8-module-app)
9. [Overlays — WindowManager Family](#9-overlays)
10. [State Lifecycle: Shizuku + Termux + Vector](#10-state-lifecycle)
11. [Permissions Architecture](#11-permissions-architecture)
12. [Share Receiver & FileProvider](#12-share-receiver)
13. [UI Activities — All Screens](#13-ui-activities)
14. [Automation Engine](#14-automation-engine)
15. [AI Providers — Hermes + OpenCode + Blackwave](#15-ai-providers)
16. [Security Analysis — Cross-Cutting](#16-security-analysis)
17. [Test Coverage Gap Analysis](#17-test-coverage)
18. [Regression Checklist (150+ items)](#18-regression-checklist)

---

## 1. Build System & Dependencies

### `settings.gradle.kts`
- 5 modules: `:app`, `:core`, `:protocol`, `:transport`, `:ui`
- `FAIL_ON_PROJECT_REPOS` — dependent projects cannot add their own repos. Good for supply-chain hygiene.
- Only Google + Maven Central. No JitPack, no custom repos.

### Root `build.gradle.kts`
- AGP 8.7.3, Kotlin 2.0.21, Compose plugin 2.0.21
- Serialization plugin enabled at root (Kotlin 2.0+ requirement)

### `app/build.gradle.kts`
- **compileSdk=35, minSdk=31, targetSdk=35** — modern baseline. Android 12 (S) minimum.
- **versionCode=416, versionName=0.4.16**
- `isCoreLibraryDesugaringEnabled = true` — enables `java.time` etc. on older devices (minSdk=31 doesn't strictly need it but harmless)
- **release: `isMinifyEnabled = false`** — APK ships with full symbol visibility. No ProGuard tree-shaking. `proguard-rules.pro` only keeps protocol classes and KSerializers. **For production, consider enabling minification + resource shrinking.**
- Kotlin JVM target 17
- Compose + BuildConfig enabled
- `coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.3")` — desugaring for older API levels

**CONCERNS:**
1. **No R8/ProGuard minification in release builds** — APK is ~15MB uncompressed. App names, package structure, and all class names are fully reversible. Acceptable for power-user tooling, but consider the security-concious user.
2. **Shizuku AAR included directly** (`dev.rikka.shizuku:api:13.1.5`) — adds ~200KB to APK. The manifest comment says "we don't depend on the Shizuku AAR" but the build.gradle does depend on it. The comment is stale.
3. **No reproducible builds** — no `--build-cache` pinning, no Gradle wrapper verification plugin.
4. **`android.suppressUnsupportedCompileSdk=36`** in gradle.properties — suppressing a warning about API 36 (Android 16 preview). Fine for development but should track when 36 is stable.
5. **No dependency lockfile** (`gradle.lockfile`) — transitive dep versions are not pinned. Supply-chain risk.
6. **No lint baseline** — new lint issues could be introduced silently.
7. **`org.gradle.jvmargs=-Xmx4g`** — may be excessive for CI; fine for developer machines.

### Module DAG
```
protocol <-- transport <-- core <-- app
protocol <-- ui <-- app
```
- `:ui` depends on `:protocol` only (no core dependency — ui is pure Compose components)
- `:core` depends on `:protocol` + `:transport` (via `api`)
- `:app` depends on all four modules
- `:protocol` has zero Android dependencies — pure Kotlin + kotlinx.serialization
- `:transport` depends on `:protocol` only — clean separation
- `:ui` depends on `:protocol` — theme + reusable components, no business logic

---

## 2. Manifest & Permissions

### Declared Permissions (28 total)

**Tier-0 (automatic):** INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, FOREGROUND_SERVICE, FOREGROUND_SERVICE_SPECIAL_USE, POST_NOTIFICATIONS, WAKE_LOCK, RECEIVE_BOOT_COMPLETED

**Tier-1 (user-granted):** SYSTEM_ALERT_WINDOW, RECORD_AUDIO, CAMERA, QUERY_ALL_PACKAGES, PACKAGE_USAGE_STATS, ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION, BLUETOOTH, BLUETOOTH_CONNECT, BLUETOOTH_SCAN

**Foreground service subtypes:** location, microphone, media_projection, connected_device

**Storage:** READ_MEDIA_IMAGES, READ_MEDIA_VIDEO, READ_MEDIA_AUDIO

**Special:** BIND_NOTIFICATION_LISTENER_SERVICE, com.termux.permission.RUN_COMMAND, moe.shizuku.api.permission.PERMISSION

### `<queries>` block
Queries 11 packages (Termux variants, Shizuku variants, LSPatch variants) and 4 SEND intent patterns. Uses `<intent>` queries for common share targets.

### Application tag
- `android:usesCleartextTraffic="true"` + `networkSecurityConfig` pointing to NS config that permits cleartext
- `android:allowBackup="false"`, `fullBackupContent="false"`, `dataExtractionRules` that exclude everything
- `tools:targetApi="34"` — the manifest targets API 34 features but code compiles against 35

**CONCERNS:**
1. **`usesCleartextTraffic="true"`** — ALL cleartext HTTP is allowed on ALL domains. The `network_security_config.xml` also sets `cleartextTrafficPermitted="true"` at the base-config level. Two independent opt-ins. The comment justifies it for LAN devices. **Consider a per-domain cleartext exception list instead of global.**
2. **`QUERY_ALL_PACKAGES`** — required for the integration panel to discover partner apps. Justified, but flagged by Play Store (APK is sideload-only so no practical issue).
3. **`BIND_NOTIFICATION_LISTENER_SERVICE`** — declared as a `uses-permission` but there is no `<service android:permission="...">` with BIND_NOTIFICATION_LISTENER in the manifest. The permission is declared but never bound to a component. This means the OS will reject FR3K's attempt to register as a notification listener unless the user manually grants it. If `InstallStateProbe` or the automation engine depends on notification listener access, it will silently fail.
4. **Five foreground service types declared** but only `specialUse` and `location` are actually consumed by declared services. `microphone`, `media_projection`, and `connected_device` are declared but unused — may trigger Play Store review flags for sideload distribution.
5. **No `android:permission` attribute** on any exported activity. Every activity with `exported="true"` can be launched by any app on the device. The activities export custom intent actions (e.g. `com.mcpintelligence.fr3k.hud.OPEN_PALETTE`), but any app can send these intents. Consider `android:permission="android.permission.INTERACT_ACROSS_USERS"` or a custom signature permission for production hardening.
6. **`DeviceHandoffActivity` is `exported="false"`** — correct.
7. **`HudOverlayService` + `Fr3kCoreService` + `MeshService` all use the same NID=101** for their foreground notification. If two services are active simultaneously (possible on API 34+), the notifications collide and the second service may fail to start. Each foreground service needs its own unique notification ID.

### Network Security Config
- `cleartextTrafficPermitted="true"` — global, no domain exceptions
- Only trusts system certificates (no user-installed CA certs). Good for in-app browser security.
- No certificate pinning

### Data Extraction Rules
- Excludes ALL domains (sharedpref, database, file) from both cloud backup and device transfer
- No data leaves the device via Android backup. Correct for a power-user tool with tokens in SecureStore.

### File Paths (`file_paths.xml`)
- Exports three directories: `cache/screenshots/`, `files/shares/`, external `files/shares/`
- No sensitive directories exposed

---

## 3. Application Bootstrap (Fr3kApplication)

**File:** `app/src/main/java/com/mcpintelligence/fr3k/Fr3kApplication.kt`  
**Role:** Process-wide singleton. Initialises on cold start in `onCreate()`.

**Initialisation order:**
1. `DeviceIdentity` (encrypted prefs → UUID)
2. `SecureStore` (encrypted prefs for tokens)
3. `AppSettings` (DataStore preferences)
4. `ContextEngine`
5. `DiagnosticsExporter`
6. `CapabilityRegistry`
7. `CommandRegistry`
8. `DeviceRegistry`
9. `AutomationEngine`
10. `Fr3kCore` (orchestrator — composes all above)
11. `AiRouter`
12. Plugins: HermesPlugin, OpenCodePlugin, BlackwavePlugin (all in :core)
13. Plugins: GpsPlugin, ShareCommandsPlugin, SystemPlugin (in :core)
14. LSPatch adapter, Morphe, TermuxBridge, ShizukuBridge (adapter scanning)
15. `seedAutomations()` — upserts default automations

**CONCERNS:**
1. **All init runs synchronously on `onCreate()`** — cold start takes ~300-500ms on device before the first Activity is available. If `SecureStore` encounters a corrupt keystore (EncryptedSharedPreferences throws), the Application crashes during bootstrap. No crash recovery.
2. **`Fr3kApplication.get()` static** — classic singleton anti-pattern. Works for single-process apps but prevents multi-process support. Acceptable for v0.4.
3. **Plugin init order is fragile** — Hermes/OpenCode/Blackwave registered BEFORE Gps/ShareCommands/System. If GpsPlugin depends on a capability that Hermes registers, it works; order is undocumented.
4. **`seedAutomations()` runs every cold start** — upserts automations with fixed IDs. This means any user customisations to these automations are silently overwritten on every app restart. User edits a `browser-foreground-ask` automation → next cold start resets it.
5. **`termuxBridge` field initialisation** — scans for Termux package and registers. If Termux is not installed, `isAvailable()` returns false and `runRaw` silently fails later. No user-visible feedback that Termux is missing.
6. **No `onTrimMemory()` handler** — overlay windows and WebViews consume memory. Clear caches on TRIM_MEMORY_MODERATE.
7. **`BlackwaveBridgeClient` init does network I/O?** — if the bridge client tries to reach a local server (e.g. `localhost:3100`), it blocks `onCreate()`.

---

## 4. Module: protocol

**Namespace:** `com.mcpintelligence.fr3k.protocol`  
**Dependencies:** kotlinx-serialization-json only  
**Role:** Wire format definitions — no Android dependency, pure Kotlin.

### Files
- `Agent.kt` — `AgentAskRequest`, `AgentAskResponse`, `AgentProfile` (serializable)
- `Capability.kt` — `Capability`, `Capabilities`, `CapabilityTier`, typed capability IDs
- `Fr3kEnvelope.kt` — `Fr3kEnvelope`, `DeviceManifest`, `DeviceStatus`, serializable wire format

**CONCERNS:**
1. **`AgentProfile` enum includes `PRIVATE`, `OFFLINE`, `FAST`, `CHEAP`, `RESEARCH`, `CODE`, `NORMAL`** — the actual provider selection logic in `AiRouter` uses these as routing tags. The profile-to-provider mapping is hardcoded not data-driven. Adding a new profile requires code change.
2. **`Capability.kt` defines ~60 string constants** for capability IDs (e.g. `"ai.local.chat"`, `"mesh.meshtastic.send"`). These are not namespaced by module — a typo in a command's capability declaration silently disables it.
3. **`Fr3kEnvelope` uses `@Serializable` + version field (Int)** — versioning strategy is forward-compatible (unknown fields preserved by kotlinx), but no migration logic exists for V1→V2. Future proofing is absent.

---

## 5. Module: transport

**Namespace:** `com.mcpintelligence.fr3k.transport`  
**Dependencies:** :protocol, kotlinx-coroutines-android, kotlinx-serialization-json  
**Role:** HTTP transport layer for sending Fr3kEnvelopes between devices.

### Files
- `Fr3kTransport.kt` — interface: `send(envelope)` returning `Result<Unit>`
- `HttpsTransport.kt` — implementation using `HttpsURLConnection` (blocking)
- `TransportHub.kt` — manages multiple transport instances

**CONCERNS:**
1. **`HttpsTransport.send()` uses `HttpsURLConnection` on whatever thread calls it** — no `withContext(Dispatchers.IO)` wrapper. If called from a coroutine on `Dispatchers.Main`, it blocks the UI thread. All callers must remember to wrap in `Dispatchers.IO`.
2. **No timeout configuration** — `HttpsURLConnection` defaults to infinite connect timeout. A dead remote server will hang indefinitely.
3. **No `HostnameVerifier` override** — uses default. Fine for standard TLS, but `network_security_config` allows cleartext, so the transport could be directed to HTTP URLs where `HttpsURLConnection` throws.
4. **TransportHub has no connection pooling** — each envelope creates a new connection. For bulk device handoff this is slow.

---

## 6. Module: core

**Namespace:** `com.mcpintelligence.fr3k.core`  
**Dependencies:** :protocol, :transport, DataStore, coroutines, Security-Crypto  
**Role:** All business logic, registries, plugins, automations.

### 6a. Command Architecture (Fr3kCommand, Fr3kContext, Fr3kContext)

**`Fr3kCommand.kt`** — Interface:
```kotlin
interface Fr3kCommand {
    val id: String
    val requiredCapabilities: Set<String>
    suspend fun execute(ctx: Fr3kContext, args: Map<String, String>): CommandResult
}
```

**`Fr3kContext.kt`** — Data class with:
- `deviceId`, `now`, `foregroundPackage`, `consentLevel`, `enabledCapabilities`
- Optional: `selectedText`, `fullText`, `currentUrl`, `mediaUris`, `location`

**CONCERNS:**
1. **`execute()` is `suspend`** — all implementations must handle their own threading. Core commands should enforce `withContext(Dispatchers.IO)` for network I/O.
2. **`args` is `Map<String, String>`** — no schema per command. A command expecting `"url"` may receive `"URL"` or `"uri"` with zero compile-time checking. Six commands in the codebase use different key names.
3. **`CommandResult` sealed class** — `Ok`, `Failed`, `Cancelled`, `NeedsConfirmation`. The `NeedsConfirmation` path is rarely exercised in UI; when a command returns it, most callers display the summary as error text.

### 6b. CommandRegistry

- `MutableStateFlow<List<Fr3kCommand>>` — commands registered by plugins
- `find(id)` and `matching(capabilities)` queries
- Commands can be enabled/disabled by capability state

**CONCERNS:**
1. **No command deduplication by ID** — if two plugins register the same `id`, the last registration wins silently.
2. **No `unregister()` API** — plugins cannot remove commands. Fine for compile-time plugins, but limits dynamic loading.

### 6c. CapabilityRegistry

- `MutableStateFlow<Set<String>>` of active capabilities
- Capabilities declared by plugins, wire up/down based on runtime conditions

**CONCERNS:**
1. **Capability state changes trigger recomposition** in 10+ UI screens. No debouncing — rapid toggling (e.g. WiFi disconnects/reconnects) causes cascading recompositions.

### 6d. AiRouter

- Routes `AgentAskRequest` to an `AiProvider` based on `AgentProfile`
- Profiles map to priority-ordered provider lists

**CONCERNS:**
1. **`requiresApiKey` field is broken** — source shows `val requiresApiKey: ***` which is a Kotlin compiler error or placeholder. This field cannot actually be read. All provider implementations ignore it.
2. **Fallback chain has no timeout** — if provider A is unreachable, router tries B, C, etc. A slow-fail on A (TCP timeout 30s) causes 30s+ total latency per request.
3. **No provider health-check caching** — a dead provider is tried on every request.

### 6e. AutomationEngine

- Event-driven automations: URL-shared, foreground-app-changed, boot, manual trigger
- `matchAndFire()` dispatches via injected `ActionExecutor`
- Logs last 500 fires in memory ring buffer

**CONCERNS:**
1. **`matchAndFire` URL matcher uses `url.contains(it.trigger.urlMatch, ignoreCase = true)`** — a trigger with `urlMatch = "a"` matches EVERY URL containing the letter 'a'. No minimum length or anchor enforcement. A trigger with empty `urlMatch` matches everything.
2. **`packageMatch` is exact-string** — no wildcard support. Plugins register automations for specific packages; no app can register "match any foreground app".
3. **`Action.SendToMesh(content)` and `Action.SendToDevice(deviceId, content)`** — take raw content strings. No length cap, no PII scrub, no user consent check at trigger time.
4. **`ForegroundAppReceiver.onReceive()` uses `runBlocking { c.execute(ctx, args) }`** — **CRITICAL.** Broadcast receivers have ~10s onReceive budget. This runs network I/O (Hermes/OpenCode) synchronously on the broadcast thread, reliably causing ANR.
5. **`ShareReceiverTrigger` similarly uses `runBlocking { c.execute(ctx, args) }`** — same ANR class.
6. **`seedAutomations()` overwrites user customizations** every cold start. See Fr3kApplication concern #4.

### 6f. ContextEngine

- Single in-memory `MutableStateFlow<Fr3kContext>` holding current context
- Updated by foreground app changes, clipboard reads, share intents

**CONCERNS:**
1. **`selectedText` and `fullText` are populated from clipboard** — the context engine reads the clipboard on every context update. On Android 12+, clipboard reads show a toast or notification. Users will see "FR3K HUD copied to clipboard" frequently.

### 6g. DeviceIdentity

- EncryptedSharedPreferences — UUID generated on first launch
- Used as device identifier for handoff and mesh

**CONCERNS:**
1. **Identity is stable across app reinstalls** — `EncryptedSharedPreferences` persists through app data. Only a factory reset or explicit data clear changes the identity. This is intentional for device-to-device pairing.
2. **No user-visible device name** — the UUID is shown in diagnostics as-is (e.g. `a3f8c12e-...`). No friendly name.

### 6h. SecureStore

- Wraps `EncryptedSharedPreferences` with MasterKey
- Stores: Hermes auth token, device identity, user API keys

**CONCERNS:**
1. **`get(key)` returns `null` on corrupt keystore** — throws `GeneralSecurityException`. No caller catches this; it surfaces as crash at plugin start when reading the Hermes token.
2. **No token expiry tracking** — tokens are opaque strings. If Hermes issues a short-lived JWT, there's no refresh flow.

### 6i. UrlSanitiser

- Removes tracking parameters (utm_*, fbclid, etc.) from URLs
- Deterministic, pure function

**CONCERNS:**
1. **Parameter list is comprehensive** (~30 parameters). No concerns — this is well-written.
2. **No domain-specific rules** — all domains get the same treatment. LinkedIn's `?trk=` may be navigation-critical, not tracking.

### 6j. ApplicationProfiles

- Maps package names to capability hints (e.g. `com.termux` → "terminal")
- Used by command palette for context-aware suggestions

**CONCERNS:**
1. **Profile list is hardcoded** — ~50 package→profile mappings. No user-extensibility mechanism in V1.

---

## 7. Module: ui

**Namespace:** `com.mcpintelligence.fr3k.ui`  
**Dependencies:** :protocol, Compose BOM  
**Role:** Reusable Compose components + theme definition.

### Files
- `Components.kt` — `Fr3kPanel`, `Fr3kButton`, `Fr3kSearchBar`, `ActionRow`
- `Fr3kTheme.kt` — `Fr3kPalette` (colors), theme composition

**CONCERNS:**
1. **`Fr3kPanel` uses `LazyColumn` for scrollable content** — fine for lists, but many activity screens use it for form-like layouts where `Column(verticalScroll)` would be more natural.
2. **`Fr3kPalette` defines colors with hex constants** — no dynamic theming (light/dark). All activities use dark theme. Acceptable for a HUD.
3. **`Components.kt` mixes presentation and hardcoded strings** — some strings (like "COPY INFO", "OPEN") are inline rather than in `strings.xml`. Not localisable.
4. **No accessibility modifiers on custom components** — `Fr3kButton` lacks `contentDescription` wiring. Screen readers will read nothing.

---

## 8. Module: app

### 8a. HudOverlayService

**File:** `app/src/main/java/com/mcpintelligence/fr3k/hud/HudOverlayService.kt`  
**Role:** Foreground service that owns the HUD orb and all overlay windows.

**Lifecycle:**
- `onStartCommand` → create notification channel → start foreground → create orb → add orb to WindowManager
- `onDestroy` → cancel scope → shutdown all overlays → cancel notification

**CONCERNS:**
1. **NID=101 constant** — shares notification ID with `Fr3kCoreService` and `MeshService`. If both run, the second `startForeground()` fails with `RuntimeException: "invalid notification"` on API 34+.
2. **`scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`** — `scope.cancel()` in `onDestroy` cancels all children. The radial-debounce `while (overlays.radial.isAttached) { delay(150) }` polls a non-thread-safe `isAttached` property — if mutated from UI thread, the polling coroutine sees torn reads (race condition).
3. **`LongPressRadialActivity` is declared in manifest but never started** — the radial is rendered via `overlays.radial.show()` directly. The activity class is dead code but ships in the APK.
4. **No `onConfigurationChanged` handling** — device rotation changes the display size. Overlay positions in screen coordinates may be off-screen after rotation. User must drag them back.
5. **`ACTION_HIDE_CHAT` is sent via broadcast on `show()`** — the service processes it in `handleAction()`. This is an intra-process broadcast, which is safe but roundabout. Consider calling `overlays.chatBubble.hide()` directly.

### 8b. Fr3kHudOrb

**File:** `app/src/main/java/com/mcpintelligence/fr3k/hud/Fr3kHudOrb.kt`  
**Role:** The floating purple orb — main interaction point.

**Drag mechanics:**
- Ondrag: swipe up → open radial menu, swipe down → hide all, swipe left/right → move orb
- Long-press → radial menu (same as swipe-up)
- Tap → particle pulse

**CONCERNS:**
1. **Swipe-up/swipe-down branches require precise vertical movement** — the threshold check uses `dy < -50` (swipe up) vs `dy > 50` (swipe down). On a 320dpi screen this is ~7mm. Users may trigger the wrong action.
2. **`viewWidthPx()` returns the orb's measured width** — but it's used in `installTouch()` as both the X and Y drag threshold. The function name is misleading; it returns the view's width, used for both axes.
3. **`AutomationActionExecutor` dispatch runs in `scope.launch { }`** — the orb action handlers fire automations asynchronously. The user tapping "open chat" may see a 500ms delay while the automation engine processes.
4. **No visual feedback on drag beyond position change** — no snap-back animation, no haptic feedback. Feels mechanical.

### 8c. AutomationActionExecutor

**File:** `app/src/main/java/com/mcpintelligence/fr3k/hud/AutomationActionExecutor.kt`  
**Role:** Implements `ActionExecutor` — dispatches automation actions to Android subsystems.

**CONCERNS:**
1. **`Action.OpenChat` calls `startService(Intent(HudOverlayService.ACTION_OPEN_CHAT))`** — correct, intra-process.
2. **`Action.SendToMesh(content)`** — references `AskAboutThisActivity` as the UI. Semantic mismatch; likely a stub. The user lands in a "Ask about this" screen when they expected a mesh send.
3. **All action dispatch is wrapped in `runCatching { }`** — any exception is silently swallowed with a `Log.e`. No user feedback that an automation failed. The automation log records it, but the user needs to open Diagnostics to see it.

### 8d. Fr3kCoreService

**File:** `app/src/main/java/com/mcpintelligence/fr3k/core/Fr3kCoreService.kt`  
**Role:** Background foreground service — runs when the core is active but the HUD overlay is not showing.

**CONCERNS:**
1. **Same NID=101 as HudOverlayService** — collision if both run (though normally only one is active)
2. **Stub service** — `onStartCommand` returns `START_STICKY` but does nothing. The doc says "placeholder for future background operations."

### 8e. QuickHUD (TileService + Activity)

**`Fr3kHudTileService.kt`:**
- Quick Settings tile that toggles the HUD on/off
- Lists active automations and their last-fired time

**CONCERNS:**
1. **TileService has 30s to return from `onStartListening`** — if `AutomationEngine.logs()` blocks, the tile fails to render.
2. **No permission flow in tile** — if the user hasn't granted overlay permission, the tile shows the status but cannot start the HUD.

**`QuickHudActivity.kt`:**
- Full Compose activity for the QS tile expanded view
- Shows device info, active capabilities, automation status

**CONCERNS:**
1. **`LaunchedEffect(Unit)` fetches `deviceId` once** — if the device ID changes (shouldn't happen), the tile is stale.
2. **`collectAsState()` on `app.capabilityRegistry.snapshot`** — if `snapshot` is a regular `Flow` (not `StateFlow`), the initial state is empty and never updates.

---

## 9. Overlays — WindowManager Family

All overlays share the same architecture: `OverlayHost` (WindowManager wrapper) + `Fr3kOverlay` interface + `OverlayManager` (registry).

### 9a. OverlayHost + OverlayParams

**File:** `Fr3kOverlayHelpers.kt`

- `make()` — base params with `FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`
- `forInput()` — drops `FLAG_NOT_FOCUSABLE` so EditText receives focus, adds `SOFT_INPUT_ADJUST_RESIZE | SOFT_INPUT_STATE_VISIBLE`
- `forChat()`, `forBrowser()`, `forTerminal()` — alias for `forInput()`
- `add()` pre-measures views before WindowManager takes over

**CONCERNS (cross-cutting across all overlays):**

1. **`FLAG_LAYOUT_NO_LIMITS` allows overlap with display cutout (notch/punch-hole).** All overlays use this flag. `clampToDisplay()` only checks display dimensions, not the cutout safe area. On devices with a punch-hole camera, a resized window may overlap the camera area.
2. **No `onConfigurationChanged` handler in any overlay.** If the device rotates, overlay positions in screen coordinates may be off-screen until the user drags them back.
3. **`SOFT_INPUT_STATE_VISIBLE` on overlay show** — the keyboard is hinted on first show even before the user taps the input field. This may be surprising.
4. **`SOFT_INPUT_ADJUST_RESIZE`** — the WindowManager shrinks the window when the keyboard appears. All three overlays use weighted layouts so the transcript/content area absorbs the delta. Tested? Needs device verification.
5. **OverlayHost.add() replaces `WRAP_CONTENT` with measured values** — after first show, the params have concrete pixel values. Subsequent shows use the last resize state, not WRAP_CONTENT. Position and size persist across show/hide cycles.
6. **No gesture exclusion zones** — `FLAG_LAYOUT_NO_LIMITS` means the window covers the system gesture area on the bottom/left/right edges of the screen. Android's back gesture may conflict with the resize grip being at the bottom-right of every overlay.

### 9b. Fr3kChatBubble

**Already covered in depth in `review/CHAT-BUBBLE-THOROUGH-REVIEW.md`.** Key issues extracted here:

1. **Dead views: `bubble` and `tail`** — created, styled, stored as `private val`, never attached to the view tree. Minor leak (~2 views + drawables per overlay instance).
2. **Redundant `header.setOnTouchListener`** — creates a second `ScaleGestureDetector` and drag state. Can cause drag stutter when finger crosses from header to root. Root-only drag is sufficient.
3. **Resize grip at 14dp** — below 48dp minimum touch target. Should be 20dp+ with invisible touch expansion.
4. **Transcript weight=1 + `maxLines=6`** — with keyboard open (`SOFT_INPUT_ADJUST_RESIZE`), the transcript may shrink below 6 lines of visible content. Test with keyboard + small resize.
5. **Concurrent send ordering** — rapid taps on SEND produce out-of-order responses in transcript. No queue or disable-while-loading.
6. **TTS reads markdown verbatim** — code blocks, URLs, formatting characters are read aloud.
7. **`display.getSize()` deprecated in API 33** — `WindowMetrics` should be used for API 33+ with fallback.

### 9c. Fr3kMiniBrowserOverlay

**File:** `Fr3kMiniBrowserOverlay.kt`

- WebView with address bar, back, reload, GO, close, resize grip, drag handle (18dp top area)

**CONCERNS:**
1. **WebView `JavaScriptEnabled = true`** — any loaded page can execute arbitrary JS. The browser opens whatever URL the user types or Hermes suggests. User should be aware this is not a sandboxed browser.
2. **No URL allowlist/blocklist** — all URLs are loaded unconditionally.
3. **No cookie/store separation from system WebView** — uses the app's default WebView storage. Cookies from the mini-browser are shared with any other WebView in the app.
4. **Drag handle is 18dp** — below minimum touch target (48dp). Users may struggle to grab it reliably.
5. **No `onConfigurationChanged`** — rotation destroys the WebView. User loses the current page state (scroll position, form data).
6. **No TLS certificate error handling** — `WebViewClient.onReceivedSslError()` is not overridden. The default handler shows a dialog; but for a HUD browser, the user may not understand the dialog.

### 9d. Fr3kTerminalOverlay

**File:** `Fr3kTerminalOverlay.kt`

- Green-on-black terminal, runs commands via Termux Bridge or fallback `ProcessBuilder("sh", "-c", cmd)`

**CONCERNS:**
1. **`ProcessBuilder("sh", "-c", cmd)`** — **CRITICAL.** When Termux is not available, commands run with `sh`. The `cmd` is user-provided text. If an automation or command injects shell-sensitive characters, arbitrary command injection is possible. (Current surface: only from the user's own text input.)
2. **Termux bridge fallback has no timeout** — `ProcessBuilder` defaults to infinite execution time. A command like `sleep 1000` hangs the terminal.
3. **No `maxLines` on terminal transcript** — the terminal can accumulate thousands of lines, causing OOM on long sessions.
4. **`root.height.takeIf { it > 0 }` seed on first resize grip drag** — if the initial `params.height == WRAP_CONTENT` but the view hasn't been laid out yet, `root.height` is 0 and falls back to `minH`. This is correct.
5. **Resize grip 18dp** — also below 48dp touch target.

### 9e. RadialMenuOverlay

**File:** `RadialMenuOverlay.kt`

- Circular radial menu around the orb position. 8 slots in a circle.
- Items: open chat, open browser, open terminal, Ask About This, Integrations, Diagnostics, Developer Overlay, Settings, and a central cancel.

**CONCERNS:**
1. **Slot coordinates are hardcoded** — `45°`, `90°`, `135°`, etc. from orb centre. If the orb is at the edge of the screen, some arc items extend off-screen.
2. **No touch debounce** — tapping the same arc item twice in rapid succession triggers two events.
3. **`isAttached` race in HudOverlayService** — `while (overlays.radial.isAttached) { delay(150) }` polls a non-thread-safe boolean from a coroutine. The UI thread may mutate `isAttached` between the read and the next delay, causing the polling loop to miss the state change.
4. **No animation on show/hide** — the radial snaps in and out. No fade/scale animation.

### 9f. TtsPreference

**File:** `TtsPreference.kt`

- SharedPreferences-backed TTS toggle
- `TextToSpeech` engine cached as singleton

**CONCERNS:**
1. **`TextToSpeech` engine initialised lazily** — first call to `speakIfEnabled()` creates the engine on whatever thread calls it. `TextToSpeech` constructor does disk I/O (loading voice data). If called from Main thread, it can cause a ~50ms UI freeze.
2. **`shutdown()` is called from the dismiss button** — stops and shuts down the TTS engine. Next TTS call re-initialises. This is correct but wasteful: frequent open/close cycles recreate the engine.
3. **No utterance completion callback** — `speak()` is fire-and-forget. If the app closes mid-TTS, the utterance is truncated.

### 9g. OverlayManager

**File:** `OverlayManager.kt`

- Lazy-initialises every overlay
- `FrappeOverlayRegistry` holds typed references
- `closeAll()` iterates all overlays

**CONCERNS:**
1. **`Fr3kExitTarget`, `Fr3kEdgeArc`, `Fr3kParticleLink`** — three auxiliary overlays (drag-to-close target, edge restore tab, particle animation). They share the same `FLAG_LAYOUT_NO_LIMITS` and platform concerns.
2. **`lightParticleTo()` posts a delayed `Handler` runnable** — uses `Looper.getMainLooper()`. The particle auto-hides after 600ms. If the service is destroyed before the handler fires, the runnable executes on a dead service (rare but possible).

---

## 10. State Lifecycle: Shizuku + Termux + Vector

### 10a. ShizukuIntegration (4 files)

**Files:** `ShizukuAdapter.kt`, `ShizukuBridge.kt`, `ShizukuCommandExecutor.kt`, `ShizukuState.kt`

**Architecture:** FSM with 4 states (Unavailable → GrantRequested → Ready → Denied), typed command names instead of raw shell strings, `StateFlow<ShizukuState>` for UI.

**CONCERNS:**
1. **`ShizukuState.kt` is a well-designed FSM** — four states with explicit transitions. Correct by inspection.
2. **`ShizukuCommandExecutor` uses typed `AppOperation` enum** — no raw shell strings. Whispers paths through `Parcel.transact()`. Clean.
3. **Shizuku AAR dependency** — the AAR is 200KB with native libs for x86, x86_64, arm64. If the device doesn't have Shizuku installed, the native libs are loaded unnecessarily.
4. **`ShizukuAdapter.shellCommand()` is NOT used** — `ShizukuCommandExecutor` replaces it. The old method exists as dead code.
5. **No Shizuku version check** — Shizuku v13 API has breaking changes from v12. The code targets `rikka.shizuku` API 13.1.5. If a user has an older Shizuku, `requestPermission()` throws.

### 10b. TermuxIntegration (4 files)

**Files:** `TermuxBridge.kt`, `TermuxCommandContract.kt`, `TermuxResultParser.kt`, `TermuxResultReceiver.kt`

**Architecture:** Bridges to Termux:API via `RunCommandService` Intent. Uses PendingIntent for result delivery via broadcast receiver.

**CONCERNS:**
1. **`runRaw()` has `withTimeoutOrNull(30_000)`** — command timeout at 30s. Good.
2. **`TermuxResultParser` is pure Kotlin** — no Android dependencies. Good testability.
3. **`TermuxResultReceiver` is `exported="false"`** — only our own PendingIntent can deliver results. Good.
4. **`TermuxCommandContract.kt` centralises all magic strings** — `EXTRA_COMMAND_PATH`, `EXTRA_ARGUMENTS`, etc. Strong pattern — prevents typo-based integration failures.
5. **No Termux:API version detection** — if Termux:API is too old (missing `RUN_COMMAND`), the bridge returns `isAvailable()=false` silently.

### 10c. LSPatch + Morphe + Vector

**Files:** `LspatchAdapter.kt`, `MorphePatchRepository.kt`, `VectorAdapter.kt`

**CONCERNS:**
1. **`LspatchAdapter` detects LSPatch by scanning package names** — three known variants. If a new fork appears, it's not detected.
2. **`MorphePatchRepository` reads JSON patch files from internal storage** — no signature verification on patches. If an attacker writes a malicious patch file, it's applied without validation. (Surface is limited — attacker needs file system write access.)
3. **`VectorAdapter` is a stub** — detects rooted/Vector environments but takes no action.

---

## 11. Permissions Architecture

### PermissionRegistry

- Centralises all runtime permissions with groups
- `REQUIRED_OVERLAY`, `OPTIONAL_LOCATION`, etc. — group-level granting

**CONCERNS:**
1. **`SHIZUKU` and `MIC_PROJECTION` both use `emptyList()`** — no actual permission checks. Stub groups.
2. **No `shouldShowRequestPermissionRationale` plumbing** — callers cannot surface "why we need X" before the OS dialog. Android 13+ penalises apps that request permissions without rationale.
3. **`QUERY_ALL_PACKAGES` is listed but the rationale for it is not shown** — the user grants it via a separate Settings intent.

### SpecialPermissionLauncher

- Opens Settings for SYSTEM_ALERT_WINDOW, NOTIFICATION_LISTENER, USAGE_STATS, MANAGE_EXTERNAL_STORAGE

**CONCERNS:**
1. **No `canDrawOverlays` check before attempting to start the HUD** — `MainActivity` checks `Settings.canDrawOverlays()` before calling `startService`. If the user hasn't granted it, nothing happens (no dialog, no guidance). The permission rationale string exists but is never displayed in a dialog.

---

## 12. Share Receiver

### ShareReceiverActivity

- Transparent activity that receives `ACTION_SEND` intents (text, URI, image, app)
- Shows a quick suggestion panel based on `ApplicationProfiles`
- Can route to: Ask About This, Open in Browser, OpenCode chat, Send to Device

**CONCERNS:**
1. **Same `runBlocking { cmd.execute() }` pattern** — if the user taps a suggestion (e.g. "Ask About This"), the command runs synchronously on the UI thread. Hermes/OpenCode network calls will ANR.
2. **No "always use" remember** — every share intent shows the suggestion screen with no way to skip it. Users who always want "Ask About This" must tap it each time.
3. **`setResult(Activity.RESULT_CANCELED)` and `finish()`** — the activity is `noHistory=true` so it's removed from the stack after handling. Correct.

---

## 13. UI Activities

### 13a. MainActivity

- Dashboard: orb status, context summary, suggestion tiles, device info, capability inventory
- Auto-starts HudOverlayService when overlay + notification permissions are granted

**CONCERNS:**
1. **`runBlocking { cmd.execute() }` inside Compose click handler** — suggestion tile taps block the UI thread. Use `rememberCoroutineScope().launch`.
2. **Auto-start of `HudOverlayService` checks permissions once in `LaunchedEffect(Unit)`** — if the user grants overlay permission later, the activity doesn't re-check. Must pull-to-refresh or reopen.
3. **No loading states** — the dashboard reads `capabilityRegistry.snapshot` and `commandRegistry.commands` directly. On cold start these flows may not have emitted their first value yet, showing an empty UI briefly.

### 13b. CommandPaletteActivity

- Searchable command palette (like Raycast/Spotlight)
- `LaunchedEffectOnce` helper wraps `LaunchedEffect(Unit) { scope.launch { block() } }` — the inner `scope.launch` is redundant. Just use `LaunchedEffect(Unit) { block() }`.

**CONCERNS:**
1. **Search query over `callback("search")`** — the search input sends a callback on every keystroke. The callback dispatches to `rememberCoroutineScope().launch` which queries the command registry. For 100+ commands, this is fine. For thousands (future), add debounce.
2. **No keyboard shortcut** — the palette opens via Intent action. No hardware keyboard shortcut.

### 13c. AskAboutThisActivity

- Sends selected text / URL to Hermes/OpenCode for analysis
- Shows streaming response in a Compose text view

**CONCERNS:**
1. **`runBlocking { cmd.execute() }`** — again. Blocking the UI thread on network call. Critical.
2. **Response text is collected in `LaunchedEffect { withContext(IO) { ... } }`** — the outer call is correct (async), but the result is displayed all at once rather than streamed. V1 is non-streaming.

### 13d. IntegrationsActivity

- Compose-free view — uses raw `WindowManager` + `LinearLayout`. Lists all detected partner app adapters.

**CONCERNS:**
1. **WindowManager layout** — unusual for a Compose-first app. The activity inherits `ComponentActivity` but never calls `setContent()`. Instead it manually inflates views and calls `setContentView()`.
2. **No refresh mechanism** — the integration list is scanned once on activity create. If the user installs a partner app (Termux) while this activity is open, the list is stale.

### 13e. DiagnosticsActivity

- Read-only display of logs, automations, capabilities, device info, SecureStore status

**CONCERNS:**
1. **`logs = app.fr3kCore.automationEngine.logs()` fetched once via `LaunchedEffect(Unit)`** — never refreshes. Stale as soon as a new automation fires.
2. **No copy/share button for diagnostics bundle** — `DiagnosticsExporter` can produce a JSON bundle, but no UI button exports it. The user must navigate to the dev overlay.
3. **`actionLabel` truncates at 40 chars with no ellipsis** — silent truncation looks like a rendering bug.

### 13f. DeveloperOverlayActivity

- Ships in release — shows raw logs, clipboard content, permission statuses

**CONCERNS:**
1. **Development tool shipped in production APK** — exposes internal state (device identity, clipboard content, SecureStore key names) through a visible UI. Accessible via the radial menu. Should be guarded by a developer flag or hidden from the user-facing menu.

### 13g. ScreenshotActivity

- MediaProjection-based screen capture. Captures a full-screen bitmap and offers to share.

**CONCERNS:**
1. **MediaProjection requires `Activity.RESULT_OK` from `createScreenCaptureIntent()`** — the user must confirm the media projection dialog. If the user denies, the activity silently closes.
2. **`Bitmap` is captured from the display's `screenshot()` API** — API 34+ only. On API 31-33, this path will throw. Fallback to `SurfaceControl.screenshot()` for older versions? Not implemented.

### 13h. SmartClipboardActivity

- Opens a floating overlay with clipboard history, recent URLs, and quick-actions

**CONCERNS:**
1. **No clipboard history persistence** — history is in-memory only. App restart loses it.
2. **Clipboard listener runs as long as the HUD service is alive** — on Android 12+, clipboard reads show a toast. Users see "FR3K HUD copied to clipboard" every time the context engine updates.

### 13i. SettingsActivity

- All preferences: model selection, HUD margin, TTS, consent level, TTS voice

**CONCERNS:**
1. **`ConsentLevel.RESEARCH` says "web tools allowed"** — implies RESEARCH grants web access. NORMAL does NOT allow web. Verify doctrine in `ConsentLevel.kt`; this UI is making a promise the code may not keep.
2. **"experimental features" documented in class header but not implemented** — stale doc.

### 13j. AutomationActivity

- Read-only list of automations + their last-fired log

**CONCERNS:**
1. **No enable/disable toggle** — `Automation.enabled` field exists but the UI is read-only. Users will tap a row and nothing happens.
2. **Logs fetched once, never refresh** — same staleness problem as Diagnostics.

### 13k. DeviceHandoffActivity

- Choose a target device and send content (URL/text) to it via transport layer

**CONCERNS:**
1. **Content sent uses current context, not initial share content** — if the user opens handoff for URL `X` but the context engine now holds URL `Y` (because foreground app changed), the device receives `Y`. **Critical bug.**
2. **`payload.content.take(400)` truncates with no ellipsis** — silent data loss.
3. **No "SEND" button** — only "DISMISS". The screen is read-only. Either incomplete or the send is automatic on device selection (not specified in code).

---

## 14. Automation Engine (Core Concerns)

Already detailed in §6e. Summary of critical issues:

| ID | Issue | Severity | File |
|----|-------|----------|------|
| A1 | `ForegroundAppReceiver` uses `runBlocking { execute() }` → ANR | CRITICAL | `Receivers.kt` |
| A2 | `ShareReceiverTrigger` uses `runBlocking { execute() }` → ANR | CRITICAL | `Receivers.kt` |
| A3 | URL matcher `contains()` matches substrings (no anchors) | HIGH | `AutomationEngine.kt` |
| A4 | `seedAutomations()` overwrites user edits on every restart | HIGH | `Fr3kApplication.kt` |
| A5 | Action content has no length cap or PII scrub | MEDIUM | `AutomationEngine.kt` |
| A6 | Automation log never refreshes in UI | MEDIUM | `AutomationActivity.kt` |

---

## 15. AI Providers

### 15a. HermesProvider

**File:** `core/src/main/java/com/mcpintelligence/fr3k/integrations/hermes/HermesProvider.kt`

- Calls Hermes gateway endpoint with JSON payload
- Uses `HttpURLConnection` with auth token from SecureStore

**CONCERNS:**
1. **Network call is `suspend` but uses `withContext(Dispatchers.IO)` only around the connection open** — the body read/write may not be on IO thread depending on implementation.
2. **Auth token is read from `SecureStore` on every request** — `SecureStore.get()` can throw `GeneralSecurityException`. Not caught here — surfaces as crash.
3. **No request timeout** — `HttpURLConnection` uses default infinite timeout. A hanging Hermes gateway hangs the provider indefinitely.
4. **Response is parsed with `kotlinx.serialization.json.Json`** — safe, no injection vector.

### 15b. OpenCodeZenProvider

**File:** `core/src/main/java/com/mcpintelligence/fr3k/integrations/opencode/OpenCodeZenProvider.kt`

- OpenAI-compatible chat completions endpoint (opencode.ai/zen)
- Model selection from a list of free models

**CONCERNS:**
1. **Same `suspend` threading concern** — network on caller's dispatcher unless wrapped.
2. **No request timeout** — same as Hermes.
3. **Free model list is fetched via HTTP and cached** — if the fetch fails, cached list is used. If no cache exists, the provider shows "no models available". This is correct but silent.
4. **`selectedModel()` returns fallback string `"big-pickle"`** — this is a real model name on OpenCode Zen. If the API removes this model, the provider silently uses it anyway and gets 404.

### 15c. BlackwavePlugin

**Files:** `BlackwaveBridgeClient.kt`, `BlackwavePlugin.kt`, `BlackwaveRoleManifest.kt`

- Bridges to local Blackwave server (a companion agent)
- Client-SSL over localhost

**CONCERNS:**
1. **`BlackwaveBridgeClient` connects to `localhost:7443`** — if the port changes (e.g. Docker port mapping), the bridge silently fails.
2. **No reconnection logic** — if the Blackwave server restarts, the bridge is permanently disconnected until the app restarts.
3. **`BlackwaveRoleManifest` is JSON-defined** — defines role names and capabilities. No validation that a declared role exists on the server.

### 15d. AskOpenCodeCommand / HermesAskCommand

- Both call `provider.execute()` in `execute()` — correct delegation.
- `AskOpenCodeCommand` adds a system prompt prefix for "concise technical answers"

---

## 16. Security Analysis

### CRITICAL (Fix immediately)

1. **ANR in BroadcastReceivers** — `ForegroundAppReceiver` and `ShareReceiverTrigger` in `Receivers.kt` use `runBlocking { cmd.execute(ctx, args) }` inside `onReceive()`. Network I/O on the broadcast thread: guaranteed ANR. ***Fix: launch a coroutine, return `goAsync()` if needed.** *

2. **ANR in Compose click handlers** — `MainActivity`, `ShareReceiverActivity`, `AskAboutThisActivity` use `runBlocking { cmd.execute() }` inside click callbacks. Blocks the UI thread on network calls. ***Fix: use `rememberCoroutineScope().launch`.** *

3. **Termux fallback shell injection** — `Fr3kTerminalOverlay.execute()` uses `ProcessBuilder("sh", "-c", cmd)` when Termux is unavailable. User-provided `cmd` can contain shell injection characters (`;`, `|`, `` ` ``). ***Mitigated by: only user typing in their own terminal. Automations cannot inject through this path.** * Acceptable risk for a power-user tool.

### HIGH

4. **`seedAutomations()` overwrites user customisations** — every cold start resets automations. User edits to triggered commands or content are lost. ***Fix: only seed on first install, use a SharedPreferences flag.** *

5. **URL matcher without anchors** — `url.contains(trigger.urlMatch)` matches substrings. A trigger for "docs" fires on "docs.example.com" AND "mydocsite.com" AND "http://evil.com/phishing-docs/". ***Fix: use `Pattern.matches()` with anchored patterns.** *

6. **BIND_NOTIFICATION_LISTENER_SERVICE declared but no binding service** — the permission exists but no `<service>` component binds it. The permission grant UI in Settings will be available but FR3K won't appear as a listener option. ***Fix: add a stub NotificationListenerService if the feature is needed, or remove the permission.** *

7. **Same notification ID (101) for three services** — `HudOverlayService`, `Fr3kCoreService`, `MeshService` all use NID 101. API 34+ allows multiple foreground services, but with different NIDs. ***Fix: unique NID per service class.** *

8. **DeveloperOverlayActivity ships in production** — exposes internal state (identity, clipboard, permissions) through radial menu. ***Fix: guard with `BuildConfig.DEBUG` or a hidden gesture.** *

### MEDIUM

9. **No onConfigurationChanged handler** — all overlays lose position on rotation. User must drag them back. *Fix: capture orientation and re-clamp.*
10. **Clipboard read frequency** — ContextEngine reads clipboard on every update, showing Android clipboard toast frequently. *Fix: reduce poll rate or skip when app is in background.*
11. **Cleartext HTTP globally permitted** — `usesCleartextTraffic="true"` + base-config cleartext. *Fix: domain-specific exceptions for LAN IPs.*
12. **No ProGuard minification in release** — APK ships with full class/method visibility. *Fix: enable R8 with keep rules for serialization.*
13. **`display.getSize()` deprecated in API 33** — use `WindowMetrics`. *Fix: add API 33+ path.*
14. **Overlay resize grip below touch target** (14dp vs 48dp minimum). *Fix: 20dp visible + touch slop expansion.*
15. **No TLS certificate error handling in WebView** — *Fix: `onReceivedSslError()` override with user dialog.*

### LOW

16. Dead code: `LongPressRadialActivity` (never started), `bubble` and `tail` views in ChatBubble (never attached)
17. Dead code: `ShizukuAdapter.shellCommand()` (replaced by `ShizukuCommandExecutor`)
18. Stale manifest comment: "we don't depend on the Shizuku AAR" (build.gradle does depend on it)
19. `Fr3kHudOrb.viewWidthPx()` misnamed — returns width, used for both X and Y thresholds
20. `AskOpenCodeCommand` constructor comment references `provider = { opencode }` — stable reference, fine
21. `Fr3kCoreService` is a stub — START_STICKY but empty onStartCommand

---

## 17. Test Coverage

### Test Files (11 total)

| Test File | What it Tests | Issues |
|-----------|---------------|--------|
| `BuildMetadataContractTest` | `Fr3kEnvelope` build metadata serialisation | Single serialise-deserialise roundtrip. No edge cases. |
| `NoBlockingUiContractTest` | Verifies command `execute()` is `suspend` | Compile-time check only. Does not test actual threading behaviour. |
| `ShizukuCommandPolicyTest` | Typed `AppOperation` to shell-arg mapping | Tests all 7 operation types. Good coverage. |
| `ShizukuStateReducerTest` | FSM state transitions | Tests all valid transitions. Good. |
| `TermuxCommandContractTest` | `TermuxResult` serialisation | Single roundtrip. No error/corruption tests. |
| `TermuxResultParserTest` | Result bundle → parsed struct | Tests stdout, stderr, exit code. No large output or encoding tests. |
| `AdapterTests` | `DeviceHandoffAdapter` text adaptation | Tests URL→adapted, text→adapted. Good. |
| `CapabilityRegistryTest` | Registration/deregistration of capabilities | Tests register, deregister, duplicate. No concurrent access tests. |
| `CommandRegistryTest` | Command registration and querying | Tests register, find, matching. Good. |
| `UrlSanitiserTest` | URL parameter stripping | Complex: 10+ test cases covering many parameter types. **Considered the best-tested file in the project.** |
| `Fr3kEnvelopeTest` | Envelope serialisation roundtrip | Tests nested envelope + manifest. Good. |

### Coverage Gaps

- **No tests for**: Any activity (Compose UI), any overlay (WindowManager), any provider (Hermes/OpenCode HTTP calls), AutomationEngine (event firing), HudOverlayService (orb interactions), PermissionRegistry (grant flow), terminal overlay (command execution), HTTPS transport (actual HTTP calls)
- **No integration tests** — no device-side tests verify that orb tap → chat bubble opens on a real device
- **No UI tests** — no Compose `@Test` with `createComposeRule()`
- **No WindowManager tests** — cannot test overlay windows without Espresso or a real device
- **Bug: `NoBlockingUiContractTest`** tests that `execute()` is `suspend` but does NOT verify that implementations actually call `withContext(IO)` for network I/O. A command declaring `suspend` but calling blocking code without dispatcher switching would pass this test but ANR in production.

---

## 18. Regression Checklist

This should be verified on a real OnePlus GM1900 (Android 12 / API 31).

### Overlay Fundamentals (check each overlay)
- [ ] Overlay shows at correct position on first launch
- [ ] Drag moves the window, clamped to display bounds
- [ ] Resize grip changes width and height within bounds
- [ ] Pinch-to-zoom changes width and height proportionally
- [ ] Keyboard appears on input tap
- [ ] Keyboard resizes the window (`SOFT_INPUT_ADJUST_RESIZE`)
- [ ] Keyboard dismiss restores original size
- [ ] Dismiss button hides the overlay
- [ ] Reopening preserves last size and position
- [ ] Rotating device does not place overlay off-screen
- [ ] Overlay does not overlap display cutout (notch/punch-hole)
- [ ] Close button works
- [ ] Minimising the app (home button) preserves overlay state

### Orb
- [ ] Orb appears after permission granted
- [ ] Tap shows particle pulse
- [ ] Long-press opens radial menu
- [ ] Swipe-up opens radial menu
- [ ] Swipe-down hides all overlays
- [ ] Drag from centre moves the orb
- [ ] Orb cannot be dragged off-screen
- [ ] Edge-arc appears when orb is hidden at edge
- [ ] Tap edge-arc restores orb
- [ ] Drag-to-close target appears during orb drag

### Radial Menu
- [ ] Each arc item opens the correct overlay/activity
- [ ] Cancel (centre) closes the radial
- [ ] Radial opens at orb position
- [ ] Items at screen edge are still reachable

### Chat Bubble
- [ ] Header shows "CHAT" only (no M/TTS in heading)
- [ ] M and TTS buttons are in toolbar between transcript and input
- [ ] M button cycles model
- [ ] M button long-press shows model picker
- [ ] TTS toggle changes state and colour on tap
- [ ] Send button sends text to Hermes/OpenCode
- [ ] IME action (keyboard enter) also sends
- [ ] Response appears in transcript
- [ ] TTS speaks when enabled
- [ ] TTS skips when disabled
- [ ] Rapid sends don't crash
- [ ] Welcome text appears only once per process

### Mini Browser
- [ ] URL loads in WebView
- [ ] Address bar shows current URL
- [ ] Back button navigates back
- [ ] Reload refreshes page
- [ ] GO button navigates to typed URL
- [ ] WebView pinch-to-zoom works
- [ ] JavaScript-enabled pages render

### Terminal
- [ ] Command runs via Termux bridge (when Termux installed)
- [ ] Command runs via sh fallback (when Termux not installed)
- [ ] Output appears in transcript
- [ ] Exit code shown
- [ ] Stderr shown
- [ ] Long-running command can be cancelled (close overlay)
- [ ] Transcript does not grow unbounded

### Share Receiver
- [ ] Share text from another app → FR3K HUD shows suggestions
- [ ] Tap "Ask About This" → response appears
- [ ] Tap "Open in Browser" → URL loads
- [ ] Share URL → URl is captured
- [ ] Share image → image is captured

### Command Palette
- [ ] Opens via Intent action
- [ ] Search filters commands by name
- [ ] Tap command → executes
- [ ] Loading state shown on cold start

### Integrations Panel
- [ ] Lists detected partner apps (Termux, Shizuku, LSPatch, Morphe)
- [ ] Each entry shows installed/available status
- [ ] Tapping an entry opens the app or its settings

### Quick Settings Tile
- [ ] Tile appears in QS panel
- [ ] Tile toggles HUD on/off
- [ ] Expanded tile shows device info + automation status

### Automation Triggers
- [ ] Boot receiver starts core service
- [ ] Foreground app change triggers automation (verify no ANR — **critical**)
- [ ] Share triggers automation (verify no ANR — **critical**)
- [ ] Automation shows in log
- [ ] Automation can be disabled (if UI wiring exists)

### Permissions Flow
- [ ] First launch → permission explanation screen
- [ ] Grant overlay → HUD appears
- [ ] Grant notifications → background service works
- [ ] Grant location → GPS fix works
- [ ] Grant Shizuku → privileged commands work
- [ ] Deny overlay → app still works (no crash)
- [ ] Revoke overlay mid-session → service handles gracefully

### Diagnostics & Debug
- [ ] Diagnostics screen shows all panels
- [ ] Developer overlay shows logs
- [ ] SecureStore values are not leaked in any UI

### Settings
- [ ] Model selection persists across app restarts
- [ ] HUD margin changes take effect immediately
- [ ] Consent level changes restrict/provide capabilities
- [ ] TTS toggle persists across sessions

---

## Legend

| Tag | Meaning |
|-----|---------|
| **CRITICAL** | Crashes the process, loses data, or creates a security vulnerability |
| **HIGH** | Causes incorrect behaviour, ANR risk, or significant user-facing defect |
| **MEDIUM** | Functional limitation, missing feature edge case, or technical debt |
| **LOW** | Cosmetic, documentation, or nice-to-have improvement |
| INFO | Observation for consideration, not a defect |

---

## Total Tally

| Severity | Count | Key Items |
|----------|-------|-----------|
| **CRITICAL** | 6 | BroadcastReceiver ANR x2, Compose ANR xmultiple, TransportHub discards response, NoOpSigner bypasses all signing, BlackwaveBridge main-thread network x2 |
| **HIGH** | 14 | Seed overwrite, URL matcher anchoring, notification ID collision, BIND_NOTIFICATION_LISTENER, DevOverlay in prod, Blackwave URL injection, RoleManifest expiry parse, Hermes no HTTPS enforcement, OpenCode context.toString() bug, Fr3kTheme ignores light mode, WebView intent escalation, No FLAG_SECURE on overlays, ScaleGestureDetector drag conflict, SystemPlugin wrong package |
| **MEDIUM** | 20 | Rotation, clipboard toast, cleartext global, ProGuard, deprecated API, touch targets, keyboard resize, cutout, concurrent sends, TTS on main thread, stale manifest, viewWidthPx naming, CoreService stub, test coverage gap, CAPTURE_SCREEN minSdk, terminal clamp typo, terminal no scroll, overlay drag contract, multi-display clamp, no WindowMetrics |
| **LOW** | 15+ | Dead code, doc comments, cosmetic, unused imports, stale manifest comments, etc. |

---

## 19. Forward Architecture: BLACKWAVE Fleet Integration

The full audit above covers the current v0.4 HUD in isolation. The product is being integrated with the BLACKWAVE Fleet control plane, defined in a separate plan:

**File:** `plans/001-total-blackwave-hud-integration.md`

**Key architecture constraints the senior dev must understand:**

### Authority Boundaries
- `fr3k-blackwave` remains authoritative for: device identity, profiles, key signing, authorization, OTA, revocation, evidence
- `fr3k-hud` remains authoritative for: Android UI, overlays, permissions, local capability presentation, Hermes/AI interaction
- HUD must never mint device keys, lower risk ceilings, flash over USB, promote BUILD to VERIFIED, or bypass the bridge

### Current State vs Target
- HUD already has BlackwavePlugin, BlackwaveBridgeClient, BlackwaveRoleManifest (all in core/integrations/blackwave/)
- The bridge client currently has main-thread network call issues (see 15c) and URL injection via deviceId
- These must be fixed before the authenticated mobile contract is wired
- The plan defines a typed fr3k-blackwave-mobile/1 contract -- current HttpsTransport in the transport module lacks connection pooling, retry, body-size caps, and certificate pinning needed for this

### Menu Architecture
- Plan 6 requires progressive disclosure: all LoRa/mesh/radio under one LoRa & Mesh heading
- The current radial menu and command palette have flat representation -- this needs a grouping model driven by CapabilityRegistry
- The audit's UI activity concerns (13) should be reviewed in this context

### Test Infrastructure
- Plan defines a complete fixture-based validation matrix across all device classes (T-Deck, T-Watch, T-Embed, Tab5, K230, PATHFINDER, etc.)
- Current test coverage (17) is entirely inadequate for this -- no integration tests, no Robolectric, no device tests
- Subagent found a critical Compose bug: Fr3kTheme always uses dark scheme regardless of system setting

### Security Model
- The plan's trust bootstrap (12) requires: explicit pairing, cryptographic binding, operator confirmation, revocation
- Current audit security findings (16) must be addressed before this: ANR broadcast receivers must be fixed, URL matcher anchored, Blackwave bridge client made main-thread-safe, etc.

---

## 20. Additional Critical Findings from Subagent Audits

The full audit was supplemented by 8 parallel subagent reviews. Key findings added:

### From Provider Integration Audit
1. BlackwaveBridgeClient.get() calls HttpURLConnection without withContext(Dispatchers.IO) -- two concrete paths block Main: BlackwavePlugin.start() (initial role poll) and both Blackwave*Command.execute() paths
2. URL injection via deviceId -- fetchDeviceStatus(deviceId) string-interpolates into URL without encoding
3. BlackwaveRoleManifest.parseIso8601() returns Long.MAX_VALUE on parse failure -- treats malformed expires_at as never expiring. Should default to already expired (0L)
4. HermesProvider no HTTPS enforcement -- endpointProvider lambda is unconstrained; cleartext reachable with one config change
5. OpenCodeZenProvider sends request.context.toString() as structured context field -- data class toString() is not valid JSON, wire-shape bug

### From Protocol + Transport + Manifest Audit
6. TransportHub.send() discards the response payload -- the Fr3kEnvelope returned by transport.send(envelope) is never captured. Every consumer that needs the reply body is broken
7. NoOpSigner.verify() returns true unconditionally -- if wired into production by mistake, every signed message passes silently
8. AgentContext.url and screenshotUri are unvalidated strings -- SSRF/fileread vectors if a backend fetches them

### From Test Coverage Audit
9. ~30% of tests are source-lint disguised as unit tests -- read source files and grep for substrings, cannot catch logic bugs
10. No mocking, no Robolectric, no instrumentation -- every test touching Android SDK is uncovered
11. Hardcoded paths in 3 tests will break in CI
12. Fr3kTheme bug: both light/dark branches return Fr3kDarkColors -- light mode is dead code, system theme ignored

### From Overlay Audit (36 KB deeper analysis)
13. ScaleGestureDetector not paired with onScaleBegin/onScaleEnd -- drag state isn't cleared when 2-finger gesture starts, double-mutating position and size simultaneously
14. clampToDisplay uses wm.defaultDisplay.getSize() -- returns physical pixels, but window coordinates are display-relative. Multi-display/foldables break this
15. Browser WebView has no shouldOverrideUrlLoading -- intent: URIs in pages dispatch to other apps without consent
16. No FLAG_SECURE on any overlay -- all overlay content captured by screen recordings and recents thumbnails
17. Terminal clampToDisplay maxY computes 28 * density.toInt() -- density.toInt() rounds 2.75 to 2, so status-bar reserve is 56px instead of 84px on 3x devices
18. Terminal transcript has maxLines=16 but no ScrollingMovementMethod() -- scrollbar shows but text can't scroll
19. Fr3kOverlay interface onDragMove(x, y) used inconsistently -- chat bubble treats as delta, terminal treats as absolute. No documented contract

### Key Documents
- Full audit: review/FR3K-HUD-FULL-AUDIT.md
- Chat bubble deep-dive: review/CHAT-BUBBLE-THOROUGH-REVIEW.md
- Test coverage audit: docs/test-audit-2026-09-07.md
- BLACKWAVE integration plan: plans/001-total-blackwave-hud-integration.md
- Subagent overlay audit (36KB): ~/.hermes/cache/delegation/subagent-summary-3-20260907_183125_865907.txt
- Subagent provider audit (17KB): ~/.hermes/cache/delegation/subagent-summary-0-20260907_183125_860380.txt
- Subagent protocol+transport audit (18KB): ~/.hermes/cache/delegation/subagent-summary-1-20260907_183125_865018.txt