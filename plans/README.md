# FR3K ecosystem assessment and implementation roadmap

**Current-state notice (2026-09-08):** this September 7 assessment predates source
fixes and must not be treated as the current defect list. See the
[reassessment checkpoint](ecosystem-evidence/reassessment-2026-09-08/STATUS.md)
for verified resolutions and outstanding coverage. Full reassessment is incomplete.

Latest additions: [008 — contracts, authority and companion migration](008-ecosystem-contract-and-migration.md),
[009 — seven-configuration acceptance specification](009-seven-configuration-acceptance.md),
[current HUD review](ecosystem-evidence/reassessment-2026-09-08/hud-current.md), and
[fresh Hermes critical-review disposition](ecosystem-evidence/reassessment-2026-09-08/architecture-review-vetting.md).
These additions distinguish proposed behavior from verified implementation. Plan
009 is not yet an executable integration suite. Original plan titles below have
historical off-by-one labels; filenames and this index define their IDs.

Generated 2026-09-07 from the live working trees. This is an assessment and handoff package; it does not implement the proposed fixes. The repositories are intentionally dirty, so every result below is labelled against the working-tree snapshot and the committed SHA.

## Baseline

| Repository | Branch / HEAD | Working-tree signal | Inventory scope |
|---|---|---|---:|
| `fr3k-hud` | `master` / `2f7fb05b` | 1 modified source file; untracked docs/plans/review | 180 nonignored files |
| `fr3k-blackwave` | `fr3k/research-reconciliation` / `b037bc53` | large active source, schema, evidence, build and media changes | 6,805 nonignored files |
| `fr3k-cardputer` | `feature/demon-signal-v4` / `27eb7040` | active renderer/game/assets changes and generated outputs | 278 nonignored files |

The machine-readable inventory and status snapshots are in `plans/ecosystem-evidence/lead/baseline.json` and the three `*-files.json` files. Generated/build/media/vendor material is classified separately and was not treated as independently source-reviewed. No hardware flashing, RF transmission, provisioning, deployment, or production evidence was performed.

## Independent-operation scorecard

| Product | Works alone today | Evidence | Principal gap |
|---|---|---|---|
| HUD | Partial: Android UI and local integrations exist; core unit-test compilation is broken and some “local” AI paths use network profiles | `ecosystem-evidence/hud/gradle-*.log` | startup/test baseline, persistence, permission and URL boundaries |
| BLACKWAVE | Partial-to-strong host control plane; 135 Python tests and contract validation pass | `blackwave/pytest.log`, `contract.log` | physical fleet states are mostly `UNKNOWN`/`N/P`; companion state restoration and grant semantics need proof |
| Cardputer | Partial: 37 host tests, five native targets, asset check, and safe firmware compile pass | `cardputer/*.log` | USB/SD ownership, TLS, file writes, GPS arithmetic, runtime wiring and stale verifier |

Connected value is currently asymmetric: BLACKWAVE has a mobile projection and HUD has a partial client, while Cardputer has no authenticated ecosystem adapter. Pairing must therefore be additive: local HUD↔Cardputer exchange can provide context, notifications, and safe reads without enrolling a device; BLACKWAVE enrollment remains the only fleet authority.

## Vetted findings

| ID | Finding | Category | Impact | Effort | Fix risk | Confidence | Evidence |
|---|---|---|---|---|---|---|---|
| F-01 | Align mobile route and header contract | integration/correctness | HUD fleet calls fail against the gateway (`/ping`, `/device` and `X-Client-Id` do not match the exposed health, plural device route, and `X-Blackwave-Client`) | M | MED | HIGH | HUD `core/.../BlackwaveBridgeClient.kt:31,63,78,97`; BLACKWAVE `src/blackwave_fleet/mobile.py:93-113,148-185` |
| F-02 | Make HUD BLACKWAVE mode explicit and stateful | architecture/security | plugin polls and exposes a base capability before a user-enabled, authenticated session; ordinary HUD must survive absence | M | MED | HIGH | HUD `BlackwavePlugin.kt:53-68,75-115` |
| F-03 | Repair HUD transport and local-data truthfulness | correctness/security | result wrapping can report success; in-memory settings and forced NORMAL AI profile lose offline/local guarantees | M | MED | HIGH | `transport/.../HttpsTransport.kt:31-65`; `core/AppSettings.kt`; `core/.../HermesAskCommand.kt:34`; `app/.../AskAboutThisActivity.kt:174` |
| F-04 | Close HUD external URL and receiver boundary | security | exported dynamic receiver forwards untrusted URLs to a JavaScript-enabled WebView that accepts `javascript:`, `file:`, and `content:` schemes | M | MED/HIGH | HIGH | `app/.../HudOverlayService.kt:143-153`; `Fr3kMiniBrowserOverlay.kt:253-275` |
| F-05 | Persist and restore BLACKWAVE companion trust/state | correctness/security | companion ViewModel uses bundled/fake cards and transient drafts; paired credentials and Room state are not restored while worker activity remains | M | MED | HIGH | BLACKWAVE `apps/android-companion/.../BlackwaveViewModel.kt`, `.../GatewayClient.kt`, `.../BlackwaveDatabase.kt` |
| F-06 | Enforce grant expiry and provenance | security | `FounderAuthority.authorize` scope-only checks can outlive expiry or active-session/revocation state | M | HIGH | HIGH | BLACKWAVE `src/blackwave_fleet/authorization.py` and `founder.py` (working-tree implementation) |
| F-07 | Serialize Cardputer USB/SD ownership | correctness/resilience | MSC keeps storage mounted while telemetry/GNSS continue writes, risking corruption | M | HIGH | HIGH | Cardputer `src/main.cpp:131-132`, `src/storage/usbsd.cpp`, `src/telemetry/telemetry.cpp:114-127`, `src/gps/gps_service.cpp:104-132` |
| F-08 | Bound Cardputer file edits and writes | correctness | capped reads followed by full overwrite and unchecked short writes can truncate user files | S | MED | HIGH | `src/filemgr.cpp:99-125` |
| F-09 | Restore Cardputer TLS verification | security | sync/WiGLE paths disable peer verification; default Pwn open is plaintext | M | HIGH | HIGH | `src/net/net_io.h:13,37`, `src/wigle.cpp:431,634`, `src/wpasec.cpp:282` |
| F-10 | Fix GPS quality underflow and evidence labels | correctness | unsigned `n-7` underflows for fewer than seven satellites and heuristic SNR labels overstate quality | S | LOW | HIGH | `src/gps/gps_service.cpp:213` |
| F-11 | Wire new Cardputer runtime systems or mark experimental | architecture | actors/combat/event adapter/save systems have tests but no production callers; LoRa adapter remains unavailable | M | MED | HIGH | `src/game/actors/`, `src/game/combat/`, `src/game/events/service_event_adapter.cpp`, `src/game/minigames/`, build graph and source search |
| F-12 | Refresh stale Cardputer verifier | DX/release | `scripts/verify_fr3k.py` fails on old procedural-renderer assertions after atlas cutover, obscuring real regressions | S | LOW | HIGH | `cardputer/verify_fr3k.log` |
| F-13 | Split oversized fleet-console bundle | performance | production build emits a 552 kB JS chunk and warning | S/M | LOW | HIGH | `blackwave/webapp-build.log` |
| F-14 | Fail closed on malformed role expiry | security | invalid `expires_at` becomes `Long.MAX_VALUE`, so a corrupted manifest is treated as never expiring | S | MED | HIGH | HUD `BlackwaveRoleManifest.kt:26-36` |
| F-15 | Enforce role expiry and bridge loss immediately | security/correctness | cached role drives capabilities after expiry and indefinitely across bridge outage; 30 s polling has no revocation push | M | MED | HIGH | HUD `BlackwavePlugin.kt:36,93-113,119` |
| F-16 | Keep command guards symmetric | security | device status does not check a role/null state even though fleet status does | S | MED | HIGH | HUD `BlackwavePlugin.kt:145-150,183-195` |
| F-17 | Configure the documented gateway TLS pin | integration/security | HUD comments claim TOFU but use default `HttpURLConnection` trust, so self-signed pinned gateway deployment fails or policy is undefined | M | HIGH | HIGH | HUD `BlackwaveBridgeClient.kt:89-100`; BLACKWAVE pairing pin `pairing.py:76-82` |
| F-18 | Remove unused competing capability mapping | maintainability | server `capabilities_map` is ignored in favor of a second hardcoded map, creating drift | S | LOW | HIGH | HUD `BlackwaveRoleManifest.kt:24-29`; `BlackwavePlugin.kt:38-51` |
| F-19 | Cancel BLACKWAVE plugin scope | correctness | stop cancels children but leaves the parent scope job active | S | LOW | HIGH | HUD `BlackwavePlugin.kt:82-90` |

Severity and remediation notes: F-01 CRITICAL/M (adapter aliases and vectors); F-02 HIGH/M (explicit mode/session owner); F-03 HIGH/M (typed Result, persistence, profile consent); F-04 HIGH/M (receiver and scheme allowlist); F-05 HIGH/M (secure migration and Room restore); F-06 CRITICAL/M (expiry, issuer, active grant and revocation checks); F-07 HIGH/M (single SD owner state machine); F-08 HIGH/S (bounded temp-file/short-write handling); F-09 HIGH/M (system trust store or pinned certificate); F-10 MEDIUM/S (saturating arithmetic and honest labels); F-11 MEDIUM/M (caller wiring or clearly gated experimental feature); F-12 LOW/S (update verifier fixtures); F-13 LOW/S-M (route-level code splitting); F-14 HIGH/S (invalid expiry is expired); F-15 HIGH/M (clear/degrade cached role on expiry/outage and add revocation signal); F-16 HIGH/S (common role guard); F-17 HIGH/M (SPKI pin and hostname policy); F-18 LOW/S (one documented capability source); F-19 LOW/S (cancel parent scope). Regression risks are respectively contract compatibility, lifecycle races, local UX changes, WebView consumers, credential migration, authorization false negatives, storage availability during MSC, edit semantics, certificate deployment, GPS UI, runtime memory, verifier drift, bundle loading, session availability, stale UX, command access, LAN trust, scope drift, and coroutine cancellation; each is gated by the named tests in Plans 001–006.

Earlier HUD integration plan `plans/001-total-blackwave-hud-integration.md` is retained as a direction document. Its authority boundaries, explicit pairing, `fr3k-blackwave-mobile/1`, blocked evidence and no unsafe retries are confirmed. Its assumed routes/headers and “HUD replaces generic mesh” implementation are contradicted by the live gateway and should be superseded by Plan 003 below. BLACKWAVE ADR-020 is accepted and confirms the Android client remains a client in the BLACKWAVE monorepo; it supersedes any proposal to create a competing Android authority. Prior claims of Cardputer LoRa availability are unverified/contradicted by the unavailable adapter and are treated as missing functionality.

## Seven configurations

| Configuration | Current benefit | Failure/absence behavior to preserve | Acceptance target |
|---|---|---|---|
| HUD alone | overlays, sharing, browser, Android integrations, local providers | missing optional services must not block startup; offline/local labels must be truthful | clean start with no gateway and local actions/tests pass |
| BLACKWAVE alone | catalog, registry, bridge, signing/OTA policy, WebUI and companion workflows | unknown physical state stays `UNKNOWN`/`N/P`; gateway remains authoritative | host control plane, WebUI, companion and schema tests pass without HUD/Cardputer |
| Cardputer alone | local game/render/input, saves, GNSS/files/peripherals | SD/USB, network loss and bad writes fail safely | local main loop, saves, assets, native targets and safe build pass |
| HUD + BLACKWAVE | optional fleet status and authorized reads | absent/revoked/expired gateway leaves HUD usable; cached views say cached | explicit mode + pairing + capability projection + denial/offline/reconnect tests |
| HUD + Cardputer | direct local context/handoff, notifications, safe device reads | no gateway and no fleet grant; disconnect does not break HUD | local pairing identity distinct from fleet identity; bounded payloads and reconnect |
| BLACKWAVE + Cardputer | optional enrolled telemetry/status/evidence | Cardputer local loop continues if gateway disappears; no authority escalation | simulator fixture and capability/status adapter with no duplicate execution |
| all three | one operator surface plus fleet evidence and local context | one command owner, deduped events, stale/blocked/revoked states visible | end-to-end fixture sequence with independent restart and revocation |

## Coverage ledger

| Area | Inspected | Tested/build evidence | Status / limit |
|---|---|---|---|
| HUD modules, Android manifest, overlays, commands, providers, transport, protocol, schemas, docs | yes; inventory in `hud/baseline.json` | Gradle disposable copy; compile/test failure and lint failure recorded | static complete; physical/emulator blocked |
| BLACKWAVE bridge, mobile, identity/auth, registry, schemas, integration validators, Python, WebUI, Android sources, device/shared targets | yes; 6,805-file inventory and AGENTS/research docs | 135 pytest pass; contract pass; WebUI tests 40 pass/builds; target logs | target-specific builds mixed/blocked; no hardware |
| Cardputer boot/main/render/input/game/save/GNSS/files/network/events/assets/build variants | yes; `cardputer/inventory.txt` | 37 host pass; five native pass; assets pass; safe firmware pass; verifier fail | no physical/RF/recovery evidence |
| Cross-product live transport and seven flows | contract/source only | no production integration exists; fixture acceptance specified in Plan 003/006 | blocked pending adapter implementation |
| provenance, generated assets, caches, binaries, release evidence | classified, not source-reviewed independently | hashes/counts in lead inventories; BLACKWAVE evidence remains unit-specific | explicit non-coverage; do not generalize |

## Roadmap

| Plan | Title | Priority | Effort | Depends on | Status |
|---|---|---:|---:|---|---|
| 001 | Existing HUD–BLACKWAVE integration plan (superseded assumptions) | P1 | L | — | TODO (superseded by 004, 007) |
| 002 | Establish reproducible three-repository verification and coverage gates | P1 | M | — | TODO |
| 003 | Repair confirmed standalone HUD and Cardputer defects | P1 | L | 002 | TODO |
| 004 | Freeze and adapt the BLACKWAVE mobile contract | P1 | L | 002 | TODO |
| 005 | Add direct HUD–Cardputer local pairing and handoff | P2 | M | 002,003 | TODO |
| 006 | Add BLACKWAVE–Cardputer enrolled capability adapter | P2 | L | 004,005 | TODO |
| 007 | Ship optional HUD BLACKWAVE mode and full-system resilience | P1 | L | 003,004,005,006 | TODO |

Plans are self-contained handoffs with file scope, verification commands, acceptance criteria and stop conditions. Physical flashing, live RF, provisioning, deployments and promotion of evidence remain out of scope.

## AutoExpert critical review

Hermes AutoExpert was invoked with the `autoexpert` skill over the live route, header, pairing, role and authority excerpts; the invocation was constrained to read-only specialist critique. Its checked review identified F-01 and F-14–F-19 and confirmed that the authority split and no-extra-registry direction are sound. The output is retained at `plans/ecosystem-evidence/lead/autoexpert-specialist.txt` with its prompt and stderr. Lead reappraisal found the key unsupported assumptions: route/header compatibility, Cardputer integration existence, physical readiness, and grant provenance. The roadmap therefore uses adapters and simulated fixtures, keeps all physical gates blocked, and avoids a fourth registry or protocol rewrite.
