# Current checkpoint — 2026-09-10

Full original Android pass remains OPEN. No builds or tests are still running at this checkpoint.

- HUD installed/selected SHA256 517bd4dbfdac5aa2592db833b3265c557dc472668227544bd493d6b9d9d11e18. 41 core +55 app host tests passed. Three real-phone native-fixture/browser/Termux tests passed. Additional physical tests passed: real OpenRouter /key authentication; actual openrouter/free native getUrl tool call and exact URL answer (6.09s); Termux connected→permission denied→restored→HUD cold process. No key printed/changed; previous key-status question is resolved by actual authentication evidence.
- Companion installed/selected from fr3k-blackwave/releases/ecosystem-2026-09-09/companion-setup; 12 host tests passed. Real-phone unpaired wizard passed all five steps, invalid QR error, unavailable probe, warning summary, HUD action present. Actual gateway pairing, device discovery/enrollment and control are NOT yet tested or complete.
- Evidence folder /home/parrot/FR3K-BENCHMARK/evidence/android-pass-2026-09-09-usb. Main records: openrouter-live-tool.txt, openrouter-live-health.txt, termux-health-matrix.txt, shared-session-instrumentation.txt, companion-wizard-check.txt. Wizard driver archived to scripts/device/test_companion_wizard.py in HUD repo.
- Phone USB f4c8d828 (OnePlus 7 GM1900), no app data cleared. Temporary USB stay-awake was set from original 0 to2 during UI tests; restored and verified as0 after the tests. RUN_COMMAND permission restored/granted. UI captures of transient USB dialog were replaced with actual companion evidence. One failed driver attempt retained as companion-wizard-check-attempt1.txt (Escape failed to close IME, swipes hit keyboard); corrected driver checks mInputShown before BACK.

Next work: real backend registry/identity projection (see gap below), companion pairing/discovery/Add Device and HUD link, full onboarding/diagnostics, terminal sizing/keyboard/orientation/offline/recovery matrix, download/inspect workflow, alternate-model selection persistence. Keep all 13 original sections in scope. No cached/model metadata may stand in for physical device capabilities or authorization.

---

# Android integration pass — active, not complete

Full requirements: ../ANDROID-TESTING-PASS.md and original attachment. Do not adopt the previous handoff statement that later clusters are omitted. All 13 sections remain in scope.

## Resume state
- Physical OnePlus 7 GM1900 connected over USB, adb serial f4c8d828. HUD 0.4.16/416 installed; Termux RUN_COMMAND granted. BLACKWAVE companion com.fr3k.blackwave.debug installed.
- Preserve dirty working tree and phone data. No clear/uninstall performed.
- Baseline launch and UI hierarchy captured at /home/parrot/FR3K-BENCHMARK/evidence/android-pass-2026-09-09-usb/baseline.xml.
- Prior work: /home/parrot/FR3K-BENCHMARK/AGENT-HANDOFF.md. Prior cluster 4 runtime only partial.

## BUG → ROOT CAUSE → FIX → TEST → RESULT
1. Invalid OpenRouter key validates successfully → health uses public /models → changed health to authenticated /key; missing-key label clarified → actual HTTPS probes using deliberately invalid dummy key: /models 200, /key 401 → root cause reproduced; regression test pending.
2. Default pinned free model instead of free router → literal Llama slug in provider/settings → default changed to openrouter/free (existing explicit selection preserved) → official OpenRouter free-router page verified → build/runtime pending.
3. Free catalogue classification only checks input price → output-priced model could be advertised free → require prompt and completion price zero → regression pending.
4. Provider error might echo API key returned in server error → raw error forwarded to UI → redact active key in ask/health failures → regression pending.

## Confirmed remaining architecture gaps
- AgentToolBus registered tools, but inspected chat/browser chat still invoke standalone Hermes commands. Shared conversation and actual model tool-dispatch need implementation/verification.
- BrowserAgentTool only open/back/forward/reload/URL/title. Missing click, scroll, text, submit, DOM/page state/text/download handoff. Navigation success reported before load outcome.
- TermuxAgentTool calls real bridge but hook writes command only; no stdout/stderr/exit mirrored to visible overlay. Window not shown by hook.
- TermuxHealth Unknown renders OFFLINE; permission-failure claims serviceLive=true without observation; probe exception handling needs review.
- Onboarding previous implementation only welcome/permissions/ready; full provider/tool/BLACKWAVE setup sequence outstanding.
- BLACKWAVE actual companion wizard/integration and real discovery/pairing need audit, not only HUD screens.
- Full diagnostics and cold-start/permission/offline/recovery matrix remain unverified.

## Next actions
Run provider regression tests, build/phone install when ready, refresh benchmark selection/hashes after successful APK per AGENTS.md. Implement shared session/tool architecture and complete real-device tests. No completion claim until all original requirements verified.

### Test execution checkpoint
Focused provider regression launched: `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=/usr/lib/android-sdk ./gradlew :core:testDebugUnitTest --tests '*OpenRouterProviderTest' --console=plain`. Output `/tmp/fr3k-openrouter-regression.log`; exec session 28614. Last poll: live, core Kotlin compilation reached. Poll before restarting. New test file `core/src/test/java/com/mcpintelligence/fr3k/integrations/openrouter/OpenRouterProviderTest.kt`. No APK built or installed from these new edits yet; benchmark candidate unchanged.

Reference verification: https://openrouter.ai/docs/api/api-reference/api-keys/get-current-key and https://openrouter.ai/openrouter/free/apps . Direct invalid-dummy-key HTTPS probes establish /models=200 versus /key=401.

## Continued implementation checkpoint
- Previous turn classified PROGRESS: reproduced auth bug, edited provider, added tests.
- Provider three focused tests PASSED; subsequent full core + app unit suite PASSED (`/tmp/fr3k-tools-regression.log`, BUILD SUCCESSFUL).
- Browser DOM actions now implemented with JSON-escaped arguments, selector validation, visible-element checks, form validation, text/element extraction. All browser tool calls serialized on Main; load callbacks return observed failure/success, navigation timeout explicit. Compact fixed button sizes actually retained; resize no longer accumulates drag delta and bounds use phone display. New source not yet physically verified.
- Termux bridge busy-spin replaced by cancellable delay and finally cleanup; timeout no longer implies process killed. ResultSlot completion synchronized. Tool cancellation propagates. Terminal tool displays actual command/cwd and mirrors returned stdout/stderr/exit without executing twice. Surface tool registrations removed on service destruction. Unknown Termux state now NOT CHECKED instead of false OFFLINE.
- Added actual-device tests `app/src/androidTest/.../VisibleToolsDeviceTest.kt`: local HTTP fixture drives real WebView; real Termux printf/stderr/exit command checks visible transcript. Test APK not yet built/run.
- Benchmark authoritative selection tool found at `/home/parrot/repos/fr3k-blackwave/scripts/sync_benchmark.py`, selection under that repo releases. This is a separate repo from fr3k-blackwave-tab5-sdr; audit real companion source path before editing.

## Next integration implementation design (not implemented yet)
The shared bus is currently a registry only. Both chat surfaces choose OpenCode before Hermes and never consult OpenRouter or dispatch the tool bus. A tool API alone therefore does not prove agent control.

Implement one application-owned session with a message history and a StateFlow transcript, collected by HUD and browser chat. Use native OpenRouter function calls (safe function names mapped to tool IDs), feed actual tool results back into the same message history, and cap per-turn calls with an explicit continuation message. Preserve assistant explanations before actions. Keep credentials outside messages. Browser page text is untrusted tool output, not a system instruction. Dispatch browser and Termux through the existing bus; register BLACKWAVE read/query tools against the app bridge singleton and retain underlying scope/auth checks. Shell and consequential actions need a reviewable exact command/action request in the app before execution; the session should expose pending approval to both surfaces. Unknown tools and malformed calls return errors, never fabricated success. Model selector must target the provider used by this session (currently it targets OpenCode only).

Onboarding remains WELCOME/PERMISSIONS/READY and a skipped setup is recorded identically to complete. Extend with provider/key/model, Termux probe, browser self-test, BLACKWAVE setup/discovery/link, and full diagnostic status. Persist skipped versus verified steps distinctly and refresh permission state on return (current remembered permission booleans can be stale).

Actual companion source: `/home/parrot/repos/fr3k-blackwave/apps/android-companion`; benchmark validation confirms it. Its BlackwaveRoot currently renders 'Cached/bundled data only · live apply, OTA and identity are disabled'. Do not mistake HUD BlackwaveActivity for this companion. Add a functioning connected setup path in the companion using its real transport/auth contracts. Preserve signed envelopes, replay protection, scope/risk ceilings, and measured capability identity. No RF or firmware flashing performed in this Android pass.

## Live build checkpoint
`./build.sh :app:assembleDebugAndroidTest` started, exec session 72814, `/tmp/fr3k-device-test-build.log`. At 15:56 AEST still live, Gradle PID 29548; jstack confirmed downloads/compiler activity. Do not restart solely for quiet output. Once complete, install both app and test APKs with `adb install -r`, run `com.mcpintelligence.fr3k.hud.test/androidx.test.runner.AndroidJUnitRunner` selecting VisibleToolsDeviceTest. Refresh benchmark after successful new app package; keep historical operator evidence.

## Verified device checkpoint — 2026-09-09
Build session 72814 COMPLETE (exit 0), signed APK sha256 29a233736cc3e9f32d8f49380691840d390b736886bf6025e5252fbe1437fa37. App + instrumentation APK installed with `adb install -r`, both Success. Instrumentation exec session 69862 COMPLETE: OK (2 tests), 1.742 seconds. Browser fixture and real Termux output tests passed on the physical phone. See evidence/android-pass-2026-09-09-usb/visible-tools-instrumentation.txt in benchmark. This verifies tool implementations, not an AI agent tool loop (still absent).

BUG → ROOT CAUSE → FIX → TEST → RESULT: browser could be called off main and reported navigation before load → direct WebView methods with immediate success → serialized Main-thread actions await actual callbacks → device fixture navigation and HTTP404 assertions → PASS. Missing DOM actions → navigation-only wrapper → fixed escaped JS operations → real page click/input/form/text/selector failures → PASS. Terminal tool hid results → command-only hook → show console and mirror real bridge result → actual stdout/stderr/exit7 matched visible transcript → PASS. Bridge busy-spin/callback leaks → yield loop and no finally → delay + finally cleanup → existing parser tests and device execution PASS (timeout-specific stress still pending).

Full 92 host tests PASS before final Android-test dependency additions; final app/test APK compiled successfully. Benchmark packaged under fr3k-blackwave/releases/ecosystem-2026-09-09/android-tools, selection and main handoff updated. Previous cluster APKs retained as historical evidence. Hash check first invoked from wrong cwd, then all 12 verified from benchmark directory.

## Shared agent implementation — source checkpoint (not yet packaged)
Previous turn was PROGRESS: packaged/installed browser+Termux fixes and passed actual-device tests. New edits: core SharedAgentSession owns native conversation/tool history, serialized turns, shared transcript/busy/pending-review StateFlows, explicit action decisions, cancellation without replay, bounded loop/history. OpenRouterProvider implements native chat-completion tool schema and result messages. Both HUD and browser send into the application singleton and collect its transcript; one review panel appears on both surfaces. Model button now opens actual OpenRouter settings instead of changing unrelated OpenCode. New BLACKWAVE GET-only fleet/device/role tool uses existing authenticated bridge; it does not enable firmware/control/RF.
Tests added for exact approval, declined action never executing, cancellation recovery and retained conversation. Build/test launched `/tmp/fr3k-session-regression.log`, exec session 15574; observed Kotlin daemon fallback (known environment issue), not terminal at last check. New changes after launch require another compile if not picked up. Latest installed/benchmark APK is still previous 29a2337… candidate until the next successful package. Native API contract checked against https://openrouter.ai/docs/guides/features/tool-calling .

## Shared session verification checkpoint
- First overlapping compile (15574) failed because BlackwaveAgentTool was added after core had compiled; no artifact from that run. Completed-source rerun (20821) passed: 41 core tests + 55 app tests, Android instrumentation source compiles. Native provider wire-contract test passes; 3 shared-session tests cover exact approval/no execution before approval, decline, and cancellation followed by context-preserving next turn.
- Build/install/phone-test pipeline now running, session 15861. Build log `/tmp/fr3k-session-apk-build.log`, phone output `/home/parrot/FR3K-BENCHMARK/evidence/android-pass-2026-09-09-usb/shared-session-instrumentation.txt` once instrument starts. Uses `-Pkotlin.compiler.execution.strategy=in-process` to avoid Kotlin daemon instability. Poll existing handle, do not restart just because log is quiet.
- New third physical test uses controlled native provider responses over local HTTP, real WebView, real Termux, clicks ALLOW on real shared chat panel, and checks both chat transcripts. It is not a live external OpenRouter inference test. No user key is copied into that fixture.
- Actual companion audit found saved CredentialStore.load is never used by ViewModel; no automatic fleet fetch; offline switch directly sets status without a probe. Existing pinned HTTPS pairing API can be reused for a real setup wizard. Source not yet changed in companion during this checkpoint.

## Companion source changes (UNBUILT at this checkpoint)
In actual fr3k-blackwave/apps/android-companion: ViewModel restores encrypted pairing and makes authenticated pinned fleet/role requests; offline preference no longer asserts connection success. FleetProjection preserves unknown/cached status instead of treating model catalog as live devices. Added prominent SET UP BLACKWAVE and a five-stage controller/network/fingerprint pairing/fleet/summary flow using existing pairing service; summary explicitly retains unmet OTA/device-control prerequisites. ACCESS_NETWORK_STATE + HUD package visibility added. These are source edits only; need companion tests/build/install and continued real discovery/Add Device/HUD credential connection work. No claim that all 18 requested setup requirements are complete.

## Shared session real-device PASS
Pipeline 15861 COMPLETE, app+test install Success, instrument OK (3 tests), 2.519s. APK sha256 517bd4dbfdac5aa2592db833b3265c557dc472668227544bd493d6b9d9d11e18. Packaging under fr3k-blackwave/releases/ecosystem-2026-09-09/android-session; explicit selection and benchmark handoff updated. Native fixture drove browser then reviewed real terminal execution, actual ALLOW button, and both transcripts. No live OpenRouter model/API key tested. BUG→ROOT CAUSE→FIX→TEST→RESULT: isolated chat ignored registered bus/OpenRouter → standalone OpenCode/Hermes commands → app-owned native session and shared collectors/review → 3 session host tests, native request contract and actual-phone workflow → PASS within fixture scope.

## Next physical health matrix preparation
Added instrumentation-only tests (not yet rebuilt): TermuxHealth expected-label probe takes runner argument termuxExpected (default CONNECTED), allowing external harness to revoke/restore RUN_COMMAND and verify truthful status after process recreation. Added optional live OpenRouter authentication test that skips with explicit missing-key reason and never outputs the key. User has a pending asynchronous key-status question; no response assumed. These test-only edits do not change installed HUD app source/APK.
Companion build session 46862 active; `/tmp/fr3k-companion-setup-build.log`. Must poll and inspect current result before retrying. Companion new setup source is still UNBUILT until that command succeeds.

### Latest active handles and exact next commands
- Companion build 46862 still live on last poll; reached `:app:processDebugResources`, no final result yet. Build/test `/tmp/fr3k-companion-setup-build.log`. After success, verify/signature/package companion and refresh explicit benchmark selection before pausing. Installed companion still old until adb install succeeds.
- HUD test-only APK build 92047 still live on last poll; `/tmp/fr3k-health-test-apk.log`. This builds only androidTest; installed main APK remains shared-session 517bd4… candidate.
- After test APK build: `adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`, then `scripts/device/run_termux_health_matrix.sh` from HUD repo. The script verifies initial RUN_COMMAND granted, tests connected/denied/restored/HUD cold process via actual probes, and EXIT-traps permission restoration + MainActivity launch. It does not clear data or force-stop Termux.
- Optional provider check: instrumentation class method `VisibleToolsDeviceTest#configuredOpenRouterAuthenticatesWithoutExposingKey`; reports skipped if missing key, not a pass. A live model tool turn is still a separate pending gate.

## Authoritative backend gap found during build wait
Actual `fr3k-blackwave/src/blackwave_fleet/mobile.py` `/mobile/v1/devices/{model_id}` accepts only catalog model IDs and returns model capabilities with NOT REPORTED firmware, live=false/cached=true. `/fleet` collapses enrollments to the first active record per model. `cli.py serve-mobile` calls `build_mobile_app(store=pairing)` without a FleetRegistry, so normal launch cannot project real enrolled devices at all. No registry configuration exists in the current bridge launcher. This is NOT a completed discovery/device-control workflow. Next backend work must wire an explicit authoritative registry, preserve multiple units and cryptographic device IDs, separate model claims from observed capabilities, and keep cached observations out of online/control grants. Existing signed/replay/risk boundaries must remain intact. No backend edits made at this checkpoint.

Previous goal turn classified PROGRESS (shared session installed/tested, companion implementation and tests added). Current live builds re-polled: companion 46862 reached dexBuilder/kspDebugUnitTestKotlin, HUD test-only 92047 reached compileDebugAndroidTestJavaWithJavac. Both live; neither restarted.

## Companion build and health test APK completed
Companion session46862 exit0: BUILD SUCCESSFUL, 12 tests / zero failures; signed v2 verified. Packaged `fr3k-blackwave/releases/ecosystem-2026-09-09/companion-setup`, explicit benchmark selection updated. Runtime install still pending until HUD permission script finishes. HUD test APK92047 exit0 and installed successfully. Health matrix+optional OpenRouter auth pipeline now session67641; logs under evidence/android-pass-2026-09-09-usb/termux-health-matrix.txt and openrouter-live-health.txt. Check outcomes, including restoration trap, before further phone manipulation.

## Physical health matrix PASS and real key confirmed
67641 completed exit0. `termux-health-matrix.txt`: PASS connected/permission denied/permission restored/HUD process recreated. Explicit test outputs for each state retained. Original RUN_COMMAND granted state restored; no data clear/Termux force-stop. `openrouter-live-health.txt` reports test code0 / OK(1 test), NOT skipped: a key is already saved and authenticated /key validation PASSED. User need not answer pending key-status question; do not read/output key. New instrumentation-only test prepared for actual openrouter/free native getUrl call against harmless random local test page, no user config change. Must build test APK then execute. Companion install/entry capture started after health matrix, see current tool handle.

## Registry projection implementation — 2026-09-10, new source not packaged yet
Backend now accepts explicit `serve-mobile --registry /path/fleet.db`, opens worker-local read-only SQLite connections, exposes every enrolled unit (including multiple same-model units and revoked records), supports exact device-ID details, rejects ambiguous model lookup with409, and returns503 for missing configured registry rather than fake empty success. Registered capability claims separated from catalog claims and never marked live/identity-verified. 13 backend mobile/registry tests PASS. Initial test collection caught a stale `fleet_registry` variable in app.state; corrected to retain registry/registry_path explicitly, rerun passed.
Clients updated in source to retain device IDs/capability evidence, select actual units, and show authenticated device detail. Companion fleet no longer displays catalog placeholders as registered devices. HUD device detail no longer paints NOT REPORTED/cached/unknown fields PASS. Both Android apps need rebuild/test/install and benchmark refresh before these new edits are candidates. Installed/selected APKs remain previous versions until builds finish. No device enrollment, provisioning or RF action performed by these changes.

### Registry build/test handles
HUD new app+tests build session55308 log `/tmp/fr3k-hud-registry-build.log`; companion build session5809 log `/tmp/fr3k-companion-registry-build.log`. Both active at latest check. Do not modify their main source until build completion. Added companion instrumentation-only GatewayPairingDeviceTest (not part of running main build) and host scripts/android_gateway_fixture.py. The test uses isolated preferences, actual Android Keystore and pinned HTTPS, two explicitly synthetic same-model registry units, invalid-pin and invalid-credential rejection, and saved-credential recovery. Must build/install test APK, start temporary loopback fixture, adb reverse tcp18879, pass base64 QR to runner, inspect result, then stop fixture/remove reverse. No operator pairing or real device enrollment changed. Fixture is not evidence of physical BLACKWAVE capability discovery.
