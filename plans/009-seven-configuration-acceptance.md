# 009 — Seven-configuration acceptance specification

2026-09-08. These are acceptance requirements, not passing integration results.
Existing unit/build evidence does not run these end-to-end scenarios.

## Configuration matrix

| Configuration | Cold start / normal benefit | Missing dependencies / disconnect | Restart / stale information / recovery |
|---|---|---|---|
| HUD | Local settings, clipboard, browser and available Android commands; no mandatory gateway | Denied permissions and absent optional providers produce actionable unavailable states; local commands remain usable | Restore consent and local preferences; never send queued private context at startup; reconnect only approved sessions |
| BLACKWAVE | Local catalog, registry, policy and evidence workflows without HUD/Cardputer | Gateway/device absence distinguishes catalog from observed state; independent console remains useful | Restore credentials/registry and receipt history; invalidate expired sessions; unknown device state remains unknown |
| Cardputer | Rendering, input, local game/save and attached peripherals without companions | No networking prerequisite for local loop; absent GNSS/SD is explicit; USB ownership does not corrupt saves | Load last complete save, recover interrupted edits visibly; discard stale remote authority, preserve local progress |
| HUD + BLACKWAVE | Explicitly enabled fleet views and authenticated supported actions | Disable/unpair/disconnect affects fleet actions only; cached views show producer timestamp | Restore mode separately from trust; expired/revoked credentials deny operations; refresh capabilities before reconnect actions |
| HUD + Cardputer | Proposed direct status and user-approved notification, no gateway | Pairing is local only; absent gateway irrelevant; disconnect preserves both local experiences | Reconnect to approved key, reject substituted peer, reconcile receipt before resending ambiguous request |
| BLACKWAVE + Cardputer | Proposed optional enrolled status/evidence projection | Fleet loss does not stop input/render/save; local pairing does not grant fleet authority | Unit identity survives address change; enrollment revocation wins over cached grant; boot sequence reset is explicit |
| All three | HUD presents local context plus authoritative fleet projection with one execution owner | Each product remains usable when either other product disappears; simultaneous routes do not duplicate actions | Independent process and version restarts retain provenance, deduplication and state ownership; no automatic replay of uncertain mutations |

Today HUD–BLACKWAVE has a partial mobile client. The two Cardputer adapters and
the combined workflow are proposed capabilities. Do not credit a preview/handoff
screen or firmware build as successful delivery.

## Required fixture interface

Implement a disposable test adapter per product with operations `start`, `stop`,
`set_permission`, `set_network`, `pair`, `revoke`, `advance_clock`, `inject_frame`,
`invoke`, `snapshot`, and `restart`. A snapshot reports local loop responsiveness,
effective consent, session/capability state, outbound requests, stored payload hashes,
execution receipts and displayed observation provenance. Use synthetic identities,
fake peripherals and a controllable clock. Tests must never flash, provision a
real unit, transmit RF, or contact an external service.

For each matrix row execute these cases in order:

1. Fresh stores, no optional peers: start, inspect local action availability and
   verify zero unauthorized outbound calls. Start each approved peer independently.
2. Perform the row's smallest useful action and record producer, receiver, ID,
   acknowledgment and visible state. UI preparation alone is not delivery.
3. Inject unsupported major version, malformed JSON/frame, missing required field,
   oversized payload and unknown optional field. Assert bounded rejection for the
   first four and documented compatibility for the last.
4. Expire and revoke credentials during idle and during a pending action. Assert
   execution-owner denial after revocation is observed; mark already-submitted
   uncertain outcomes accurately. Do not imply disconnected revocation is instant.
5. Drop the link before send, after submission, during transfer and before reply.
   Retry automatically only when no submission occurred. Verify bounded memory,
   responsive local loop, explicit partial-transfer cleanup and no truncated save.
6. Duplicate and reorder events; offer direct and gateway routes simultaneously.
   Assert one effect per retained receipt and no privilege escalation via route.
7. Restart producer, relay and receiver separately with a request pending. Assert
   old boot sequence is not treated as new; reconcile outcomes before new execution.
8. Advance observation age and change wall clock. Assert cached data is labelled,
   old observations are not refreshed by receipt time and time-sensitive authority
   fails closed when clock trust is absent.
9. Upgrade just one product to an incompatible contract, then restore its previous
   version. Assert local usability and readable prior data, explicit incompatibility,
   no automatic downgrade of trust and successful supported reconnect.

## Result format and release gate

Record one JSON result per configuration/case with source fingerprints, fixture
version, command, exit status, assertions, evidence paths and evidence class
(`static`, `host`, `emulator`, `build`, `physical`). `NOT_IMPLEMENTED`, `BLOCKED` and
`FAIL` are distinct from PASS. A missing adapter must produce NOT_IMPLEMENTED and
a nonzero suite exit, never skip-to-green. Keep test adapters outside production
firmware until the implementation plan defines their narrow seam.

No executable cross-product adapter is delivered by this specification. This is
an explicit remaining assessment deliverable: authoring a fake runner that merely
asserts the desired state would not supply integration evidence. Physical acceptance
additionally records exact unit, board revision, artifact digest, installation path,
boot/peripheral/save checks, disconnect/recovery and operator-observed results.
