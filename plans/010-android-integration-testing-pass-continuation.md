# FR3K Android App — Integration & Testing Pass (continuation)

> **Status:** PLAN (plan-mode). No execution performed. Authoritative spec:
> `~/repos/fr3k-hud/ANDROID-TESTING-PASS.md` (13 sections + completion criteria).
> This plan continues the work the main agent on `.37` (parrot@192.168.1.37) began.
> Prior state: `~/FR3K-BENCHMARK/AGENT-HANDOFF.md`,
> `~/repos/fr3k-hud/docs/android-pass-progress-2026-09-09.md`.

**Goal:** Move the fr3k-hud Android suite + BLACKWAVE companion from a set of
partially-working screens into a single coherent, agent-operated system — one
shared agent session dispatching browser, Termux and BLACKWAVE tools with visible,
explanable, approvable actions — and verify the full matrix on real hardware.

**Architecture target (from §13):**
```
FR3K AGENT → SHARED TOOL/CAPABILITY LAYER → BROWSER | TERMUX | BLACKWAVE | HUD
                                             → REAL DEVICES / SERVICES
```

**Tech stack:** Kotlin + Gradle (GradleAndroid api) Android app
`com.mcpintelligence.fr3k.hud`; multi-module `app/` `core/` `transport/`;
native OpenRouter tool-calling; physical OnePlus 7 GM1900 via adb.

---
## Repo / state map (verified 2026-09-09)

- **HUD app:** `~/repos/fr3k-hud` (branch `master`, HEAD `2f7fb05` v0.4.16).
  Public remote `origin` = `github.com/fr3kchy/fr3k-hud.git`. Dirty tree (many
  modified source files + untracked `ANDROID-TESTING-PASS.md`, `AGENTS.md`,
  `app/src/androidTest/`, `docs/`).
- **BLACKWAVE ecosystem / companion:** `~/repos/fr3k-blackwave`
  (branch `fr3k/research-reconciliation`, no upstream; `upstream` →DISABLED push).
  Companion app source: `apps/android-companion/`.
- **Benchmark:** `~/FR3K-BENCHMARK` — `manifest.json`, `SHA256SUMS`, `evidence/`,
  `test-results/`. `hud-latest-debug.apk` sha `29a23373…` is the current published
  candidate. Refresh via `~/repos/fr3k-blackwave/scripts/sync_benchmark.py` +
  `releases/benchmark-selection.json`.
- **Hardware under test:** OnePlus 7 GM1900. USB adb serial `f4c8d828`;
  Wi-Fi adb host/port flaps (`192.168.1.108:41863` — re-probe each session).
- **Build/test env (.37):** `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64`,
  `ANDROID_HOME=/usr/lib/android-sdk`. Host tests: `./gradlew :core:testDebugUnitTest`
  (+ `:app:…`, 92 tests). Device tests:
  `app/src/androidTest/…/VisibleToolsDeviceTest.kt` (AndroidJUnitRunner over adb).
  Package: `./build.sh`.

### What is already built & verified (do NOT redo)
- **§1 Browser:** compact layout, agent API (open/back/fwd/reload/url/title),
  DOM actions (click/scroll/type/submit/text-extract, selector/visible/form
  validation), Main-thread serialized, observed nav outcomes, compact fixed
  icon sizes, bounded resize. Real-device fixture tests PASS (incl. 404 + bad
  selectors). Collapsible Hermes chat added.
- **§2 Termux:** maximise/minimise/pill + saved geometry; console mirror returning
  real stdout/stderr/exit; callback cleanup/CPU-spin fixed; device-tested exit 7
  parity. Unknown state now NOT_CHECKED (not false OFFLINE).
- **§4 status:** `TermuxHealth` real probe (installed→RUN_COMMAND permission→`echo ok`
  round-trip, 10s cache, never throws) wired to QuickHud OK/WARN/ERR.
- **§5 OpenRouter:** provider + free-router default (`openrouter/free`), key saved
  via SecureStore, masked display, save/replace/remove, VALIDATE via authenticated
  `/key` (invalid-key-200 bug root-caused + fixed), redaction, model selector now
  targets the OpenRouter session (not OpenCode).
- **§6/§7 HUD screens:** 18-step wizard (`BlackwaveSetupWizard.kt`), honest main
  screen (`BlackwaveActivity.kt`) with CONFIGURE→ rows, Add-Device 8-transport
  matrix (`BlackwaveAddDevice.kt`), shared probes (`BlackwaveSupport.kt`).
  Runtime VERIFIED-IN-PART (device dropped off mid-pass).
- **§9/§10:** onboarding gate (WELCOME→PERMISSIONS→READY) + `DiagnosticsActivity.kt`
  (5 probes PASS/WARN/FAIL + RUN FULL DIAGNOSTIC) implemented.
- **P key fixes:** `AgentToolBus.kt` (thread-safe registry), shared-api source
  `SharedAgentSession.kt` + BLACKWAVE GET-only fleet/device/role tool IMPLEMENTED
  in source but **NOT YET PACKAGED or device-verified**.

### Known-gaps that gate completion (from progress doc)
1. **SharedAgentSession is source-only.** AgentToolBus is a registry; HUD + browser
   chat still call standalone Hermes/OpenCode commands and never dispatch the tool
   bus. No proof an AI agent actually drives browser/Termux through the shared
   session. → core of §3.
2. **Approvals not surfaced**: pending-action review panel must appear on BOTH
   surfaces; declined actions never execute.
3. **Onboarding incomplete**: only WELCOME/PERMISSIONS/READY; skipped==complete;
   remembered permission booleans can be stale.
4. **Companion app (NOT HUD) is cached-only**: `BlackwaveRoot.kt` still renders
   "Cached/bundled data only · live apply, OTA and identity are disabled". Needs a
   real connected setup path.
5. **§4 permission-failure probes** claim serviceLive=true without observation;
   probe exception handling to review. BLACKWAVE HUD unprobed state FAIL / "local
   ID treated as identity" status bugs pending source validation.
6. **Full §11 matrix** (fresh/returning/offline/broken/recovery) unverified; §12 UX
   audit partial; consolidated BUG→ROOT CAUSE→FIX→TEST→RESULT doc incomplete.

---
## Phase 0 — BACKUP BEFORE ANY CHANGE  *(user-mandated; do FIRST in execution)*
Safety snapshot of everything that will be modified, BEFORE any edit/build/commit:
1. Timestamped tarball of the full dirty working trees + benchmark evidence:
   ```
   TS=$(date +%Y%m%d_%H%M%S); mkdir -p ~/backups/pre-android-pass-$TS
   tar czf ~/backups/pre-android-pass-$TS/fr3k-hud.tgz   -C ~/repos fr3k-hud
   tar czf ~/backups/pre-android-pass-$TS/fr3k-blackwave.tgz -C ~/repos fr3k-blackwave
   tar czf ~/backups/pre-android-pass-$TS/FR3K-BENCHMARK.tgz -C ~ FR3K-BENCHMARK
   ```
2. Record pre-change HEADs + file counts; `sha256sum` the tarballs; verify
   `tar tzf` lists the critical files before trusting them.
3. Commit a git bundle (`git bundle create` incl. working tree via `git stash -u`
   round-trip OR just rely on the tarball) — tarball is authoritative for dirty
   state. Keep `~/backups/latest`-style pointer for easy restore.
> Verdict: ONE backup set on .37 + the git commit in Phase 2 both act as restore
> points. Do not start edits until `tar tzf` confirms the dirty sources are inside.

---
## Phase 1 — Reconnect device, finish pending runtime (small, quick wins)
Blocked earlier when the OnePlus 7 dropped off Wi-Fi adb mid cluster-4.
1. Re-probe device: `adb devices`; try USB (`f4c8d828`), else Wi-Fi (`adb connect`
   to the current `192.168.1.108:PORT` after re-arming on the phone). Note the port
   change and move on — do NOT re-ask the user.
2. Finish Cluster-4 pending runtime (HUD app):
   - §6 `SET UP BLACKWAVE` 18-step wizard entry → Save Config round-trip →
     save-and-restart.
   - §7 manual device-ID LOOKUP full path (fix the IME-dismiss-BACK keyevent being
     consumed as back-navigation — `LaunchedEffect`/focus handling).
   - Record each as BUG→ROOT CAUSE→FIX→TEST→RESULT in
     `test-results/hud-cluster4-runtime-result.md`.
3. Flip `manifest.json` cluster4 entry `BUILD_TESTED_RUNTIME_UNTESTED` →
   verified and update its validation JSON `not_tested` field once evidence lands.

---
## Phase 2 — SHARED AGENT SESSION: package + device-verify (§3 — the core gap)
Goal: ONE session, ONE transcript across HUD+browser, tools dispatched through the
bus, actions approvable, assistant explanations preserved.
1. Wire HUD chat bubble + browser chat + Termux console into the application-
   owned `SharedAgentSession` singleton (send messages in, render its transcript +
   busy + pending-review StateFlows). Both surfaces share the same review panel.
   Files: `core/…/SharedAgentSession.kt` (+ tests), and both chat surfaces in `app/`.
2. Route tool dispatch THROUGH the bus so it is real, not a demo:
   - browser (`browser.*`) → `Fr3kMiniBrowserOverlay` (via `BrowserAgentTool`).
   - termux (`termux.exec`) → `TermuxAgentTool` → real bridge, mirror actual
     stdout/stderr/exit/cwd into the visible console.
   - blackwave GET/query → app `blackwaveBridgeClient` singleton with scope/auth
     checks retained (fleet/device/role read tools; NOT control/RF/firmware).
   - Shell + consequential actions: surface an exact command/action REVIEW request
     in the app before execution; declined → never executes.
   - Unknown tools / malformed calls → error result, NEVER fabricated success.
3. Native OpenRouter function calls for the session: safe function names → tool
   IDs; feed real tool results back into the same message history; cap per-turn
   calls with an explicit continuation message. Browser page text = untrusted
   output, never a system instruction; keep credentials out of messages.
4. END-TO-END acceptance workflow (drives §3 + §8 + §13):
   "Find latest <device> firmware → download it → hand path to Termux → inspect →
   explain in chat → return result". Must show: chat explains before action,
   browser navigates visibly, file download path handed to Termux, real terminal
   output shown, chat explains result.
   Test: `app/src/androidTest/…/SharedSessionDeviceTest.kt` (extend
   `VisibleToolsDeviceTest` pattern) + a real-model round-trip.
5. Per proposed-design tests: exact-approval granted, declined-action-never-
   executes, cancellation recovery, retained conversation across surfaces.

---
## Phase 3 — OpenRouter completion (§5) + key hygiene (§13)
1. Finish `OpenRouterProviderTest.kt` regression (3 focused tests) + full
   `:core:testDebugUnitTest` + `:app:testDebugUnitTest`; build; install on device.
2. Device-verify Settings panel: save key (masked `sk-or-…abcd`), replace, remove,
   VALIDATE (auth `/key`), model select + USE FREE + REFRESH free list.
3. Verify NO key leakage: grep logs/history/UI for the key pattern; confirm
   ask/health failures redact the active key.
4. Offline/missing-key UX: app stays usable → still reach Settings/diagnostics
   without a valid key.

---
## Phase 4 — Real Termux status matrix (§4, §13)
Make QuickHud reflect reality across transitions. Add the missing states
(CONNECTED / CONNECTING / UNAVAILABLE / PERMISSION REQUIRED / SERVICE STOPPED /
ERROR) and fix the permission-failure probe that claims `serviceLive=true`
without observation + probe exception handling. Device matrix:
cold start · Termux already running · Termux not running · Termux restarted ·
Android process killed/recreated · permission revoked · reconnect after failure.
Each recorded BUG→ROOT CAUSE→FIX→TEST→RESULT.

---
## Phase 5 — BLACKWAVE companion real connected path (§6/§8 companion side)
NOT the HUD — the companion `~/repos/fr3k-blackwave/apps/android-companion`
currently cached-only.
1. Replace cached-only `BlackwaveRoot.kt` with a real "SET UP BLACKWAVE" path using
   the companion's actual transport/auth contracts (preserve signed envelopes,
   replay protection, scope/risk ceilings, capability identity).
2. Pair/discovery: BLE + LAN scan + manual IP + manual device ID wired to its real
   service layer; verify identity (BLACKWAVE ID, model/platform, FW version,
   transport, capabilities: radios/sensors/screen/GPS/OTA/Live-Apply/control).
3. HUD ↔ companion link: HUD "Show my BLACKWAVE devices / which online / open
   device page / check T-Deck firmware / run diagnostics / compare firmware" all
   through the shared session + bridge client.
4. Runtime-verify on the phone + record results in
   `FR3K-BENCHMARK/test-results/blackwave-companion-result.md` (currently
   BUILD_TESTED_RUNTIME_UNTESTED).

---
## Phase 6 — First-run onboarding, full sequence (§9)
Extend `OnboardingActivity.kt` from WELCOME/PERMISSIONS/READY to:
WELCOME → AI PROVIDER → OPENROUTER KEY → MODEL → TERMUX → BROWSER SELF-TEST →
BLACKWAVE SETUP → DEVICE DISCOVERY → HUD → SYSTEM TEST → READY.
- Persist SKIPPED vs VERIFIED distinctly (`onboardingDone` alone is not enough).
- Refresh permission state on return (current remembered booleans can be stale).
- Each optional step genuinely skippable; show completion/status clearly.

---
## Phase 7 — Full system test matrix + recovery (§11)
Run on the physical device, recording results:
- FRESH install (no config/key/devices).
- RETURNING install (saved OpenRouter config, paired devices, HUD config).
- OFFLINE (no internet, Termux available, local BLACKWAVE devices).
- BROKEN config (invalid key, unreachable device, Termux unavailable, perms
  denied, disconnected Wi-Fi/BT).
- RECOVERY: each dependency becomes available again WITHOUT reinstall.
Log all in `FR3K-BENCHMARK/test-results/`.

---
## Phase 8 — UX screen audit (§12)
Audit every major screen (browser, Termux, chat, quick-HUD, Settings,
Diagnostics, BLACKWAVE main/add/setup, onboarding) for wasted vertical space,
oversized headers/cards, duplicate status, excessive permanent controls → collapse
into drawers/sheets/expandable panels. Browser + terminal must dedicate most of the
screen to content. Measure, don't eyeball (screenshots + `uiautomator` dumps).

---
## Phase 9 — Diagnostics completion (§10)
`DiagnosticsActivity` exists; ensure each probe reads the SAME authoritative state
used by the HUD (Termux in particular: probe → statusLabel parity; §4 fixes flow
through). Add anything missing (BLACKWAVE discovery transports, HUD link). RUN
FULL DIAGNOSTIC must reproduce real breakages currently detectable.

---
## Phase 10 — Document + publish (§13 completion criteria + process rules)
1. Consolidate ALL BUG → ROOT CAUSE → FIX → TEST → RESULT entries into a single
   reviewable doc in the repo (e.g. `docs/android-pass-bugs-2026-09-XX.md`).
2. Move the benchmark: after each successful APK, update
   `releases/benchmark-selection.json`, run `sync_benchmark.py`, verify
   `FR3K-BENCHMARK/SHA256SUMS`, update `AGENT-HANDOFF.md` + `manifest.json`.
   Never edit a published binary in place; never present an unbuilt source change
   as shipped.
3. Commit progress to `fr3k-hud` master and push to `origin`.

---
## Completion gate (do not declare done until ALL hold)
Browser agent-controlled + collapsible chat + big space win · Termux reliable +
accurate HUD status + agent-triggered visible exec · chat explains browser/Termux ·
OpenRouter functional + free routing + model switch · BLACKWAVE first-run setup +
discovery/add · HUD↔BLACKWAVE works · disabled caps expose missing prereqs ·
diagnostics identify real breakages · cold/restart/offline/recovery pass · tested
on the physical OnePlus 7 (not emulator) · full BUG→FIX doc written.

---
## Risks / tradeoffs / open questions
- **GitHub write auth is BLOCKED on .37** (no HTTPS token, no registered SSH key,
  `gh` token invalid; repo is public-readable only). ANY push requires a credential
  re-auth/token from the user. Local commits can proceed regardless.
- `adb connect` target flaps — every session re-probe; assume the port moved.
- The dirty working trees are large; Phase 0 backup MUST land before Phase 2 edits.
- The companion's `upstream` remote has push DISABLED and no upstream branch — its
  push path (and whether to target `blackwave-fleet`) must be decided before
  anything but local commits there.
- AgentOpenRouter tool-calling is native; verify against live OpenRouter contract
  (checked against https://openrouter.ai/docs/guides/features/tool-calling).
- Physical-device runs are the slow step; batch §6/§7/§9/§10 verifies into one
  install session.