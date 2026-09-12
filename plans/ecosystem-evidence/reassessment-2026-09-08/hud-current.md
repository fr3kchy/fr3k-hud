# HUD current-source reassessment — 2026-09-08

Read-only specialist pass against the current dirty working tree, not the committed release. This pass independently reread the findings below and inspected existing unit XML reports; it did not run new builds, emulator tests or device tests. Paths below are relative to HUD. Java package prefix is `com/mcpintelligence/fr3k/` beneath each module's `src/main/java/`.

## Reconciliation

| Prior claim | Current disposition and evidence |
|---|---|
| Plugin unregister cancels parent and never invokes stop | Resolved in source: `core/.../core/Fr3kPlugin.kt:75` gives each plugin a child SupervisorJob; lines 102–115 remove registrations and call stop in finally. Replacement waits for prior cleanup at 80. Seven existing PluginManager tests have zero failures/errors in current debug XML. The older STATUS.md checkpoint is stale. |
| Settings disappear on normal process restart | Resolved at persistence boundary: `app/.../Fr3kApplication.kt:40–45` opens private preferences through AppSettings; serialization includes all Settings fields. Six existing AppSettings tests pass. Android process-death and abrupt-power-loss persistence remain untested; apply() is asynchronous. |
| Transport Result.failure reported successful and fallback duplicates uncertain delivery | Resolved for TransportHub's checked code: `transport/.../transport/TransportHub.kt:78–91` unwraps failure, propagates cancellation, retries only DeliveryNotAttemptedException and rejects incompatible response protocol. This is not durable receiver deduplication. |
| Shared capability removed when one AI plugin unregisters | Resolved by owner-indexed registration in CapabilityRegistry; six current tests cover the registry, including surviving ownership. |
| Wrong gateway route/header | Resolved for inspected health/device paths and X-Blackwave-Client in BlackwaveBridgeClient; four existing client tests pass. Pairing, TLS pinning and complete DTO interoperability are separate gaps. |
| App unit tests omitted from runner | Resolved: `test.sh:7` invokes all-module Gradle test; settings includes app/core/protocol/transport/ui. No new run in this pass. |
| Unprivileged arbitrary-app overlay broadcast | Narrowed by signature permission in manifest lines 6–8 and HudOverlayService lines 142–144. Do not republish the old unrestricted-receiver claim. URL scheme policy remains a separate review boundary. |

## Checked findings

### [HUD-CURRENT-01] Enforce the selected consent profile at execution boundaries

- **Evidence**: `app/.../ui/settings/SettingsActivity.kt:114–115` saves user selection; `core/.../integrations/hermes/HermesAskCommand.kt:32–37` constructs NORMAL and sends current context; `core/.../integrations/opencode/AskOpenCodeCommand.kt:37` also hardcodes NORMAL; `core/.../integrations/opencode/OpenCodePlugin.kt:40` refreshes remote models on startup. `core/.../integrations/opencode/OpenCodeZenProvider.kt:60–88` sends prompt/context without a consent check. Multiple UI context builders likewise use NORMAL (palette:170, ask:174, chat bubble:613).
- **Trigger / impact**: Select LOCAL_ONLY or PRIVATE, then start HUD or ask through these paths. Selection does not prevent the remote model-list request or gate outbound prompt/context. Settings persistence did not repair this privacy boundary.
- **Severity / confidence**: HIGH / HIGH, direct call paths inspected.
- **Effort / risk**: L / HIGH; every UI, automation and provider path must agree.
- **Fix sketch**: Centralize effective policy and apply it before all outbound operations, with explicit local/remote provider classification and context minimization. Test zero network requests in LOCAL_ONLY, including startup refresh and direct overlay paths. AiPolicy's PRIVATE/OFFLINE fallback to any available provider (`AiRouter.kt:42–43`) also needs fail-closed behavior, though this pass did not establish that selector's runtime wiring.

### [HUD-CURRENT-02] Make BLACKWAVE optional and refresh capability state

- **Evidence**: `app/.../Fr3kApplication.kt:98–107` unconditionally registers and starts BLACKWAVE; `core/.../core/AppSettings.kt:48–50` has endpoint/credential-reference/client-ID but no mode flag. `core/.../core/Fr3kPlugin.kt:89` registers capabilities once after start. `core/.../integrations/blackwave/BlackwavePlugin.kt:94–115` updates cachedRole every poll without updating the registry; lines 69–70 advertise discovery when no role exists.
- **Trigger / impact**: Cold start without a gateway still attempts bridge discovery. A later grant or revocation updates the role cache but not the capability snapshot presented to users. Expired state can remain visibly advertised; this is a presentation/availability defect, not evidence that gateway authorization can be bypassed.
- **Severity / confidence**: HIGH product-contract gap / HIGH.
- **Effort / risk**: L / HIGH; affects startup, navigation, registry and lifecycle.
- **Fix sketch**: Persist an explicit mode choice independently of pairing; expose observable unavailable/pairing/authorized/stale/revoked states; update capabilities when role validity changes. Switching off must stop polling and preserve ordinary HUD commands. Keep gateway authorization authoritative.

### [HUD-CURRENT-03] Complete authenticated pairing and bound bridge I/O

- **Evidence**: `app/.../Fr3kApplication.kt:147–155` constructs bridge from endpoint, credential key and client ID only. `core/.../integrations/blackwave/BlackwaveBridgeClient.kt:89–121` uses HttpURLConnection, bearer header, normal platform trust and unbounded readText; it has timeouts but no cancellation-triggered disconnect or pin configuration.
- **Trigger / impact**: A gateway requiring operator-issued certificate trust cannot be paired through this client. A slow or oversized bridge response occupies an I/O worker or grows memory beyond a product-defined budget; read timeout is not a total-duration or size limit.
- **Severity / confidence**: HIGH integration prerequisite; MED resilience defect / HIGH source confidence. No claim that normal platform TLS is itself insecure.
- **Effort / risk**: L / HIGH; changes trust bootstrap and networking.
- **Fix sketch**: Authenticate the gateway pin from operator QR before submitting its pairing nonce; retain existing companion flow during migration. Bound bytes and total duration, map errors explicitly, disconnect on cancellation. Test malformed/oversized responses, mismatched pin, expired nonce and revoked credentials in isolated fixtures.

### [HUD-CURRENT-04] Distinguish handoff preparation from delivery

- **Evidence**: `app/.../ui/handoff/DeviceHandoffActivity.kt:128–159` renders adapted content as ready to send; `core/.../core/DeviceHandoffAdapter.kt:58–61` returns an empty waypoint. `app/.../hud/AutomationActionExecutor.kt:34–47` maps SendToMesh to the ask activity and SendToDevice to this preview, returning FIRED. `app/.../mesh/MeshService.kt:14–17` explicitly reserves an empty adapter slot.
- **Trigger / impact**: A user executes a send action expecting another device to receive content. Current paths prepare/open UI without delivering it, and no direct Cardputer transport is established by them.
- **Severity / confidence**: MED / HIGH.
- **Effort / risk**: L / HIGH; true delivery requires pairing, contracts, acknowledgments and deduplication.
- **Fix sketch**: Label present behavior as preparation and separate PREPARED/SUBMITTED/ACKNOWLEDGED outcomes. Add direct local HUD–Cardputer pairing and bounded capability-specific payloads without fleet enrollment. A gateway outage must not disable that local route.

### [HUD-CURRENT-05] Represent screenshot capture as unavailable until real capture exists

- **Evidence**: `app/.../ui/screenshot/ScreenshotActivity.kt:58–78` requests capture permission but only stores a synthetic media-projection timestamp string; line 86 renders a placeholder bitmap. The comment explicitly defers ImageReader capture.
- **Trigger / impact**: User grants screen capture but receives a placeholder and context URI without image content; screenshot-assisted local or connected workflows cannot be scored as working.
- **Severity / confidence**: MED / HIGH.
- **Effort / risk**: L / HIGH; Android foreground-service and projection lifecycles need device verification.
- **Fix sketch**: Expose truthful availability immediately; design a real user-approved capture service with lifecycle cleanup and bounded image storage before connecting extraction or remote analysis. Verify cancel/deny/rotation/process restart and content deletion.

## Coverage and evidence limits

| Subsystem inventory group | This pass's status | Required remaining verification |
|---|---|---|
| Five Gradle modules, scripts and tests | Settings module inventory, test.sh and existing core XML inspected | Re-run all modules/lint/assembly only if fresh evidence needed; supply full manifest/hash-bound reports; dependency reachability/advisory review not performed here |
| Startup, plugin lifecycle, settings, capabilities, command registry, AI routing | Targeted source review, regression tests inspected as recorded evidence | Cold start offline on Android, failed/slow providers, process death and concurrent lifecycle stress |
| BLACKWAVE client/plugin/role DTO | Targeted complete plugin and network boundary review | Actual paired gateway fixture, all role transitions, trust bootstrap, payload compatibility and offline views |
| Protocol and transport | Hub send/retry boundary reviewed; previous source fixes reconciled | Envelope authentication, response correlation, version negotiation, restart deduplication; no peer integration proof |
| Context, automations, share, clipboard, profiles, voice, diagnostics, identity, secure storage | File inventory accounted; selected consent/action paths inspected, remaining implementations not independently re-reviewed | Persistence/retention, event duplication, data minimization, identity backup/restore, key invalidation, automation execution outcomes |
| Orb/radial/browser/chat/terminal/overlay manager, tile, receivers | Inventory plus selected consent/URL and signature receiver boundary inspected | Full lifecycle, touch/keyboard, focus, TalkBack/font scaling, background limits and foreground-service behavior; browser accepts javascript/file/data/content schemes at lines 253–254 but external exploitability was not established here |
| Main/settings/integrations/palette/ask/automation/clipboard/diagnostics/developer UI | Inventory plus selected execution/settings paths | Screen-by-screen usability/accessibility and truthful capability states |
| Screenshot and handoff | Directly checked incomplete implementation | Real screenshot flow and actual peer delivery |
| GPS/location, mesh | Mesh explicit stub inspected; location source inventory only | Permission denial, real location updates, battery load, unavailable peripheral behavior |
| Shizuku, Termux, Vector, LSPatch, Morphe | Inventory only in current pass; prior test evidence exists | Independently inspect privileged command policy/result lifecycle and third-party dependency provenance |
| Theme/components/assets/manifest/resources/build configuration/docs/releases | Inventory plus selected manifest and test configuration | APK/startup measurement, full accessibility, release signing/provenance and prior-ADR reconciliation |

The earlier assessment's statement that all first-party classes were reviewed is historical evidence, not this pass's coverage claim. Existing test reports are host evidence, not Android or hardware behavior. Standalone HUD has useful local interface/URL/clipboard/command surfaces, but local-only privacy enforcement and complete screenshot/handoff functionality remain unmet. Pairing adds intended value only after authenticated discovery, truthful capability state and actual delivery are implemented; none of the missing connections should be credited as passing today.
