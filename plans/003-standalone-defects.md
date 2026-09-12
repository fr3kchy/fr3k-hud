# Plan 002: Repair confirmed standalone HUD and Cardputer defects

## Status
- Priority: P1; Effort: L; Risk: HIGH; Depends on: 002; Category: correctness/security; Planned at: `2f7fb05b`, 2026-09-07

## Why this matters
Both products must remain useful without the other two. Current defects can corrupt Cardputer storage, weaken TLS, misreport GPS quality, open unsafe WebView schemes, and make HUD offline/local promises false.

## Scope and current evidence
HUD: `app/.../HudOverlayService.kt:143-153` forwards an exported receiver URL to `Fr3kMiniBrowserOverlay.kt:253-275`; `core/AppSettings.kt` is in-memory; `HermesAskCommand.kt:34` forces `NORMAL`; `HttpsTransport.kt:31-65` wraps results without a structured bounded error state. Cardputer: USB/SD and writers at `src/main.cpp:131-132`, `src/telemetry/telemetry.cpp:114-127`, `src/gps/gps_service.cpp:104-132`; file writes `src/filemgr.cpp:99-125`; TLS `src/net/net_io.h:13,37`, `src/wigle.cpp:431,634`, `src/wpasec.cpp:282`; GPS underflow `src/gps/gps_service.cpp:213`.

## Steps
1. Add characterization tests for HUD transport success/failure, local profile selection, persisted settings, exported receiver authorization, and allowed URL schemes. Expected: tests fail on current behavior and pass only after the intended boundary is implemented.
2. Make receiver and WebView inputs explicit and bounded: non-exported or authenticated receiver, HTTPS/http allowlist as product requires, reject `javascript:`, `file:`, `content:` and malformed/oversized URLs. Expected: negative tests reject each scheme and unauthenticated sender.
3. Persist only approved HUD settings/session metadata using the existing secure store/DataStore conventions; label cached/offline results. Expected: process restart preserves safe settings and never claims network AI is local.
4. Serialize Cardputer USB MSC ownership with telemetry/GNSS/storage, check short writes and file-size boundaries, restore certificate/hostname verification, and replace unsigned GPS arithmetic with bounded logic. Expected: host/native tests cover unplug, short write, low satellite count, bad certificate, and reconnect.

## Done criteria
All new tests pass in the commands from Plan 001; no unsafe URL scheme, unchecked write, disabled peer verification, or unsigned underflow remains in scoped paths; HUD core startup works with no optional service.

## STOP conditions
Stop on a required public API change, hardware-only behavior, or a need to change fleet authority.
