# fr3k-hud — BLACKWAVE Cluster 4 BUG→ROOT CAUSE→FIX→TEST→RESULT

Date: 2026-09-09. Artefact: `hud-0.4.16-cluster4-debug.apk`
sha256 `64246184d29d50d09c7acf6e32f55201579de989deeb682ce58bc222ac4dec6d` (63,264,194 bytes).
Scope: §6 BLACKWAVE SETUP wizard + §7 ADD DEVICE transport matrix.

---

## 1. Compile: bare `startActivity(Intent(...))` unresolved inside `@Composable`

**BUG** — `grep`/compile failure: `Unresolved reference: startActivity` at every navigation
point inside the BLACKWAVE composables.

**ROOT CAUSE** — Kotlin `@Composable` functions are not `Context` receivers. A bare
`startActivity(...)` only resolves inside a `Context` scope (Activity/Service); inside a
composable it must use the ambient `Context` explicitly.

**FIX** — Rewrote every call site to `LocalContext.current.startActivity(...)`:
- `ui/blackwave/BlackwaveActivity.kt` — 6 edits (SET UP BLACKWAVE, ADD DEVICE, Configure →
  OTA/Live Apply actions).
- `ui/blackwave/BlackwaveSetupWizard.kt` — 2 edits (permission round-trip + save-config launch).

**TEST** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL (no unresolved references).

**RESULT** — Build GREEN; all navigation reachable on device (SET UP BLACKWAVE / ADD DEVICE /
Configure → all render with correct launch targets). No runtime crash on the four verified
entry points (ADD DEVICE fully exercised; SET UP BLACKWAVE + Configure → rendered but not
tapped before device went offline).

---

## 2. Compile: `fun settings()` referenced by `LaunchedEffect` before its local declaration

**BUG** — Compile failure: `Unresolved reference: settings` inside the
`LaunchedEffect(session.stepIndex)` block in BlackwaveSetupWizard.kt.

**ROOT CAUSE** — Kotlin does **not** hoist local functions for resolution inside a lambda
defined earlier in the same scope — a local `fun` must be declared textually *before* the
lambda that references it.

**FIX** — Moved `fun settings()` **above** the `LaunchedEffect(session.stepIndex)` that calls it.

**TEST** — `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL.

**RESULT** — Build GREEN. (These two mirrors the CLI-typed launch-list failures from the
earlier, now-abandoned delegated attempt; fixed in-file this pass.)

---

## 3. Runtime: manual device ID LOOKUP + deterministic UI quirks

**BUG (observed, partially test-blocking)** — `keyevent 4` (BACK) used to dismiss the IME
while the Manual Device ID field had focus was consumed as **activity back-navigation**,
finishing `BlackwaveAddDeviceActivity` and returning to the §6 main screen. The LOOK UP
action therefore never executed (no `fetchDeviceStatus` logcat evidence).

**ROOT CAUSE** — Device input quirk, not an app bug: with the software keyboard already
dismissed (or focus released), BACK re-fires as navigation. The dialogs do not trap BACK.

**FIX (test-side, not app-side)** — Do not use BACK to dismiss the IME inside wizard
activities. Use `input keyevent 111` (ESC) to close the keyboard, re-dump the hierarchy for
fresh bounds, then tap the target button. Verified: manual IP probe succeeded once this
pattern was used (`Failed to connect to /192.168.1.250:8878` → honest REACHABLE FAIL).

**TEST** — Manual IP probe full path executed cleanly with IME dismissed via ESC;
`probeIp()` empty-host guard (returns early when the field is blank) confirmed as the reason
the earlier empty-host PROBE BRIDGE tap was a silent no-op — by design.

**RESULT** — Manual IP REACHABLE FAIL + ROLE WARN + FLEET 0/0 WARN all rendered honestly,
logcat evidence captured. Manual device ID LOOKUP still pending (device offline).

---

## 4. Runtime quirk (app-behaviour, honest by design)

**BUG (none — verified behaviour)** — LAN probe of `https://blackwave.local:8878/mobile/v1/health`
with no bridge on LAN → `Unable to resolve host "blackwave.local"` +
`bridge not available, skipping role poll` → ENDPOINT FAIL + FLEET 0/0 WARN. Honest failure,
no fake success, no hidden warnings — matches §13 doctrine.

**RESULT** — PASS (broken-config scenario behaves correctly on device).

---

## Evidence
- UI dumps: `FR3K-BENCHMARK/evidence/hud-0.4.16-cluster4-runtime/ui7-ui27.xml`, `current_screen.png`
- Full record: `FR3K-BENCHMARK/test-results/hud-cluster4-runtime-result.md`
- Build: `FR3K-BENCHMARK/evidence/hud-0.4.16-cluster4-debug.apk.validation.json`

## Pending (device offline — Wi-Fi ADB dropped, port may have changed)
- Manual device ID LOOK UP full path (`fetchDeviceStatus`).
- SET UP BLACKWAVE 18-step wizard entry + Save Config round-trip (save-and-restart).
- Recovery scenario (bridge becomes reachable → REPROBE returns to operational).