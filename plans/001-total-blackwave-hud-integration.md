# Plan 001: Seamlessly integrate FR3K HUD with BLACKWAVE Fleet

> **Executor instructions:** This plan covers coordinated work in two repositories:
> `/home/parrot/repos/fr3k-hud` and `/home/parrot/repos/fr3k-blackwave`.
> Implement the integration as one coherent system, while preserving each
> repository’s ownership boundaries. Do not copy the BLACKWAVE control plane into
> Android and do not create a second identity, registry, signer, OTA policy, or
> fleet database.

## Status

- **Priority:** P1
- **Effort:** L
- **Risk:** HIGH
- **Depends on:** FR3K HUD full-validation plan
- **Category:** integration / security / migration
- **Planned at:** commit `2f7fb05`, 2026-09-05

## Objective

Make FR3K HUD the seamless Android operator surface for the BLACKWAVE fleet. The
phone should discover, pair with, monitor, query, and control authorized
BLACKWAVE capabilities through one consistent UX, while BLACKWAVE remains the
canonical authority for catalog, identity, authorization, signing, OTA,
revocation, evidence, and hardware operations.

## Non-negotiable architecture

`fr3k-blackwave` remains authoritative for:

- device catalog and immutable device identity
- profiles and `FR3KCFG2` configuration images
- keyring-backed credentials and signing
- peer authorization, risk ceilings, replay protection, and revocation
- OTA manifests, rollback, release provenance, and flash safety
- hardware evidence and validation state
- `blackwave-device/2` event validation

`fr3k-hud` remains authoritative for:

- Android UI, HUD overlays, command palette, sharing, clipboard, browser, and diagnostics
- Android permissions and lifecycle
- local capability presentation and command routing
- Hermes/AI user interaction
- Android-side pairing UX and authenticated gateway session state

The Android app must never mint fleet device keys, lower risk, flash merely
because USB is present, promote BUILD to VERIFIED, or bypass the BLACKWAVE
bridge. Reticulum, BLE, mesh, and Android are bearers or clients; they are not
fleet authority.

## Current integration facts

HUD already has `BlackwavePlugin`, `BlackwaveBridgeClient`, `BlackwaveRoleManifest`,
`DeviceRegistry`, `TransportHub`, and capability-aware UI. See:

- `fr3k-hud/core/src/main/java/com/mcpintelligence/fr3k/integrations/blackwave/`
- `fr3k-hud/core/src/main/java/com/mcpintelligence/fr3k/core/DeviceRegistry.kt`
- `fr3k-hud/transport/src/main/java/com/mcpintelligence/fr3k/transport/`
- `fr3k-hud/protocol/src/main/java/com/mcpintelligence/fr3k/protocol/`

BLACKWAVE provides the loopback bridge, mobile projection contract, catalog,
schemas, registry, signer, OTA policy, and fleet validation. Read:

- `/home/parrot/repos/fr3k-blackwave/docs/apps/android-companion.md`
- `/home/parrot/repos/fr3k-blackwave/docs/integration-contract.md`
- `/home/parrot/repos/fr3k-blackwave/docs/research/invariants.md`
- `/home/parrot/repos/fr3k-blackwave/docs/security/fabric-v2-threat-model.md`
- `/home/parrot/repos/fr3k-blackwave/src/blackwave_fleet/bridge.py`
- `/home/parrot/repos/fr3k-blackwave/src/blackwave_fleet/mobile.py`
- `/home/parrot/repos/fr3k-blackwave/src/blackwave_fleet/registry.py`
- `/home/parrot/repos/fr3k-blackwave/src/blackwave_fleet/authorization.py`

The HUD protocol currently uses `fr3k/1`, while BLACKWAVE device events use
`blackwave-device/2`. Do not conflate these envelopes. Define and document an
explicit mobile gateway/projection adapter between them.

## Scope

In scope:

- one versioned, typed HUD↔BLACKWAVE mobile gateway contract
- authenticated pairing and session lifecycle
- catalog/device/evidence projection into HUD
- capability and command mapping with server-side authorization
- BLACKWAVE events rendered as HUD status, notifications, and diagnostics
- offline queueing limited to safe drafts/evidence; no unsafe implicit execution
- fleet query, device status, authorized device actions, and revocation handling
- consistent error codes and unavailable/blocked states
- Android, Python, protocol, schema, integration, security, and end-to-end tests
- CI/release documentation and reproducible test fixtures
- menu and information-architecture optimization so related fleet/radio actions
  are grouped under compact headings instead of consuming the main HUD
- connection and trust validation between HUD, BLACKWAVE gateway, and every
  supported device class

Out of scope:

- moving signing keys or OTA authority into Android
- direct Android access to device serial ports as a replacement for the bridge
- automatic flashing, RF transmission, actuator control, credential provisioning,
  or security-fuse changes
- inventing physical validation evidence
- replacing the existing HUD plugin architecture or BLACKWAVE control plane
- making Reticulum, BLE, mesh, or Hermes a trust authority

## Steps

### Step 1: Freeze the cross-repository contract

Define a versioned mobile contract, preferably `fr3k-blackwave-mobile/1`, with
request, response, event, error, capability, evidence, pairing, and revocation
schemas. Specify identity fields, correlation IDs, timestamps, expiry, nonce,
signature/authentication requirements, maximum sizes, pagination, retry rules,
and offline semantics.

Map BLACKWAVE result states and errors to HUD `CommandResult` codes without
losing distinctions such as `UNAUTHORIZED`, `FORBIDDEN`, `CAPABILITY_MISSING`,
`POLICY_DENIED`, `OFFLINE`, `BLOCKED`, and `UNSUPPORTED`.

**Verify:** schema validators reject malformed, unsigned/expired/replayed,
wrong-destination, oversized, and versionless messages; valid fixtures pass in
both repositories.

### Step 2: Implement the BLACKWAVE mobile gateway adapter

In `fr3k-blackwave`, expose only the documented mobile projection endpoints and
operations. Keep credentials in the OS keyring. Enforce loopback binding,
authentication, authorization, request limits, freshness, correlation, and
revocation. Return redacted projections rather than private key material or
raw secret values.

Add deterministic fixtures for catalog, device status, evidence, capability
availability, blocked gates, event streams, and authorization failures.

**Verify:** Python tests cover every endpoint’s success and denial paths;
`uv run pytest -q`, integration contract validation, secret scan, and drift
checks pass.

### Step 3: Implement the typed Android client

In HUD `protocol` and `transport`, add typed models and a transport client for
the mobile contract. Keep protocol models Android-free where possible. Add
timeouts, cancellation, bounded response sizes, TLS/URL validation, structured
errors, backoff only for safe idempotent reads, and no automatic retry for side
effects.

The client must expose observable connection/session state and distinguish
offline, unauthorized, revoked, blocked, unavailable, and server-error states.

**Verify:** JVM tests cover serialization, malformed responses, timeouts,
cancellation, retry rules, URL policy, auth failures, and all error mappings.

### Step 4: Complete pairing and identity flow

Implement the documented pairing flow using an out-of-band QR or equivalent
operator-confirmed exchange. Store only the required fingerprint/session
material securely. Display the exact identity being paired. Require explicit
confirmation and prevent LAN presence, BLE pairing, alias, IP, or QR metadata
alone from becoming authorization.

Implement unpair, revoke, expired-session, changed-key, and re-pair behavior.

**Verify:** tests prove that unknown, changed, revoked, expired, and mismatched
identities cannot restore trust or execute commands.

### Step 5: Wire BLACKWAVE into HUD capabilities and commands

Update `BlackwavePlugin` and the command/capability registries so HUD exposes
only capabilities present in the authenticated BLACKWAVE projection and allowed
by current Android permissions and local policy.

Use one command path for palette, orb, automation, share, chat, and AI-proposed
actions. AI actions remain proposals and require the same policy/confirmation
checks as user actions. Preserve blocked evidence states visibly; do not hide a
blocked operation as though it were successful.

**Verify:** tests cover capability appearance/disappearance after connection,
permission revocation, device revocation, offline transitions, blocked states,
and risky-command confirmation.

### Step 6: Optimize menus and progressive disclosure

Redesign the main HUD, command palette, radial menu, integrations panel, and
fleet screens around task-oriented groups. The main interface must stay compact;
it must not list every LoRa, mesh, radio, device, or transport action as a
separate top-level item.

Use grouped headings such as:

- **BLACKWAVE Fleet** — devices, status, pairing, evidence, OTA status, and
  authorized device actions
- **LoRa & Mesh** — one expandable heading containing LoRa, Meshtastic,
  MeshCore, Reticulum, nodes, channels, messages, routes, location, and radio
  status
- **AI & Hermes** — ask, research, code, translate, summarize, and local/offline
  AI modes
- **Capture & Context** — selection, URL, clipboard, notification, screen,
  screenshot, camera, and attachments
- **Automations** — saved workflows, schedules, triggers, approvals, and history
- **Android Integrations** — Termux, Shizuku, LSPatch, Morphe, Vector/root, GPS,
  Bluetooth, and special permissions
- **System & Diagnostics** — battery, network, storage, logs, support export, and
  connection health

Implement a single canonical grouping model rather than hard-coding separate
menu lists in each screen. Each group should derive from the capability registry
and expose only currently available actions. Groups with no available actions
should be hidden or shown as clearly unavailable according to the existing HUD
UX convention. A group with blocked or permission-gated actions should show a
compact status indicator and explain the prerequisite after expansion.

Required menu behavior:

- main HUD shows only the highest-value group headings and a small set of pinned
  universal actions;
- all LoRa/radio/mesh capabilities appear under one **LoRa & Mesh** heading;
- individual protocols are second-level sections, not main-screen items;
- device-specific actions appear under the selected device, not globally;
- dangerous actions are never promoted merely because they exist in a group;
- unavailable capabilities are not rendered as executable controls;
- blocked physical actions remain visible as blocked information where useful,
  but cannot be invoked;
- search still finds nested actions by title, protocol, device, and capability ID;
- keyboard, accessibility, automation, share, orb, and AI-proposed commands use
  the same grouping and command registry;
- menu state survives rotation and process recreation where appropriate;
- menu ordering is deterministic and documented;
- no duplicated LoRa/Meshtastic/MeshCore/Reticulum command definitions are added.

Use compact badges for connection state, permission state, and evidence state.
Avoid presenting every backend, frequency, node, channel, or capability as a
top-level navigation destination. Keep the main surface focused on what the
operator can do now; expose detail through expansion, selected-device context,
search, and diagnostics.

Add UI tests for collapsed and expanded groups, empty groups, nested search,
permission changes, offline mode, blocked evidence, accessibility semantics,
large font sizes, narrow screens, and deterministic ordering. Add snapshot or
golden coverage if the project’s test infrastructure supports it.

**Verify:** Compose/UI tests demonstrate that all LoRa and mesh actions are
reachable beneath one top-level heading, no duplicate top-level radio entries
exist, unavailable actions cannot execute, and every existing command remains
reachable through search or its appropriate group.

### Step 7: Add event and status synchronization

Consume authenticated BLACKWAVE events with sequence and uptime handling.
Detect reboot/session boundaries and stale events. Do not silently reorder old
events. Render device state, evidence state, health, transport, and blocked
reasons in dashboard, integrations, fleet, and diagnostics surfaces.

**Verify:** contract tests cover valid events, duplicate events, sequence reset,
gaps, stale timestamps, malformed payloads, wrong device IDs, and unknown event
types.

### Step 8: Implement safe offline behavior

When disconnected, HUD must continue local operation. It may retain read-only
cached projections, drafts, tutorials, and evidence capture. It must label live
fleet status, config apply, OTA, identity verification, and gateway health as
unavailable. Never queue an unsafe side effect for silent later execution.

**Verify:** integration tests simulate connection loss during every read and
side-effect flow; no command reports success without a confirmed response.

### Step 9: Integrate diagnostics, evidence, and support tooling

Add a user-visible integration health screen showing HUD version, gateway
version, contract version, session age, last successful sync, clock status,
transport, device count, and blocked prerequisites. Export a redacted support
bundle with hashes and timestamps but no secrets or unnecessary location data.

**Verify:** redaction tests fail if tokens, private keys, passwords, cookies, or
unapproved sensitive fields appear in exports or logs.

### Step 10: End-to-end validation

Create a local fixture gateway and run the complete flow:

1. clean HUD start
2. gateway discovery/configuration
3. pairing
4. catalog projection
5. device status and capability projection
6. authorized read operation
7. denied operation
8. blocked physical operation
9. event update
10. offline transition
11. reconnect and resynchronization
12. revocation
13. re-pair requirement
14. diagnostics export

Run Android emulator/device tests for API 31, 33, 34, and 35 where available.
Run the BLACKWAVE Python, integration, C++, Android, and WebUI checks.

**Verify:** every flow has recorded logs/results and no step depends on guessed
hardware, hidden credentials, or unverified physical evidence.

### Step 11: Release and maintenance integration

Document the contract, local gateway setup, pairing, offline behavior, error
codes, support bundle, compatibility policy, and rollback procedure. Add CI
jobs or scripts that validate both repositories against shared fixtures. Pin
contract versions and reject incompatible versions clearly.

**Verify:** clean checkouts can run the documented tests and produce matching
schema/fixture validation results.

### Step 12: Validate trust and connectivity across the entire fleet

Because HUD and BLACKWAVE are being developed together, implement the trust
bootstrap and connection tests as one coordinated vertical slice. Trust should
be straightforward to establish through explicit pairing, but it must still be
cryptographically bound, operator-confirmed, revocable, and testable.

The supported connection matrix must include:

| Source | Destination | Required validation |
|---|---|---|
| FR3K HUD | BLACKWAVE loopback bridge | authenticated session, health, catalog, capabilities, errors |
| FR3K HUD | BLACKWAVE mobile projection | pairing, sync, events, offline/reconnect, revocation |
| BLACKWAVE gateway | T-Deck Plus / ABYSS | identity, status, event contract, authorized command |
| BLACKWAVE gateway | T-Watch Ultra / VIGIL | identity, status, event contract, authorized command |
| BLACKWAVE gateway | T-Watch S3 2020 | manifest, status, capability projection |
| BLACKWAVE gateway | T-Embed CC1101 | manifest, status, capability projection |
| BLACKWAVE gateway | T-Deck Pro 4G / RELAY | manifest, status, transport state |
| BLACKWAVE gateway | UniHiker K10 / SCOUT | manifest, status, blocked actuator state |
| BLACKWAVE gateway | M5Stack Tab5 / NEXUS | manifest, status, audio/UI capability state |
| BLACKWAVE gateway | T-Display S3 / PULSE | manifest, status, event contract |
| BLACKWAVE gateway | T-Display K230 / SPECTRE | host/overlay status, provenance-blocked release state |
| BLACKWAVE gateway | PATHFINDER / RNode | bearer reachability, identity, route state, no authority escalation |
| FR3K HUD | each projected device | display, filtering, authorization, status freshness, safe errors |

Use the exact device IDs and contract names from BLACKWAVE. Never identify a
device by a guessed alias, USB path, BLE address, IP address, or codename alone.
Where a device is not physically available, use a deterministic simulator or
recorded fixture and label the result `SIMULATED`/`BLOCKED`; do not claim the
physical device passed.

Implement a trust bootstrap sequence:

1. Start BLACKWAVE on loopback with a fresh test keyring namespace.
2. Start a fixture mobile projection with a known test certificate/key pair.
3. Configure HUD with the gateway endpoint through an explicit operator/test
   action, never hidden discovery alone.
4. Exchange version, nonce, identity fingerprint, and supported contract list.
5. Require explicit pairing confirmation showing both identities and fingerprints.
6. Store only the approved trust material in Android secure storage.
7. Perform an authenticated health request and catalog sync.
8. Validate one safe read operation.
9. Validate one denied operation and one blocked physical operation.
10. Revoke the HUD session/device and prove access stops immediately.
11. Re-pair explicitly and prove old trust material cannot silently return.

Trust tests must prove:

- a fresh unpaired HUD cannot query private fleet data or execute commands;
- pairing succeeds only with matching fingerprints and explicit confirmation;
- changed gateway/device keys require re-pairing;
- expired sessions, invalid signatures, wrong destinations, stale timestamps,
  replayed nonces, and revoked identities fail closed;
- LAN discovery, BLE pairing, mDNS, or a shared name never establishes trust;
- trust survives safe process restart but not uninstall/reset unless documented;
- gateway restart and network transition recover without duplicating side effects;
- a device disappearing does not make another device trusted;
- the HUD cannot elevate a device’s risk ceiling or evidence state;
- BLACKWAVE remains the final authorization decision point.

For every fleet device class, create a connection fixture containing:

- exact contract device ID
- model and backend
- public identity/fingerprint placeholder or test key reference
- supported capabilities
- expected evidence state
- expected transports
- valid status event
- valid capability response
- denied command response
- stale/offline response
- revocation response

Add a matrix runner that executes the same test suite against every fixture so
new devices cannot be added without connection coverage. Add an optional
hardware runner that consumes a unit-specific validation record and refuses to
run destructive operations unless an exact authorized unit is selected.

End-to-end trust/connection scenarios:

- HUD ↔ local BLACKWAVE bridge, online
- HUD ↔ bridge restart during request
- HUD ↔ bridge offline then reconnect
- HUD ↔ simulated ABYSS/VIGIL and every catalog device
- gateway ↔ each available physical device with exact identity confirmation
- gateway ↔ PATHFINDER bearer without granting PATHFINDER authority
- fleet-wide status refresh with one device offline
- fleet-wide refresh with revoked, blocked, stale, and unknown devices
- concurrent safe reads across all devices
- denied high-risk requests across all devices
- event sequence reset after device reboot
- malformed and oversized events from each simulated device
- trust revocation while a request is pending

**Verify:** the matrix runner passes all simulated device fixtures; physical
results are recorded separately by exact unit; every negative trust test fails
closed; and no test changes production credentials, hardware, RF state, or
evidence classification.

## Required test matrix

Cover:

- clean install, upgrade, uninstall/reinstall
- no gateway, gateway online, gateway offline, gateway restart
- valid/invalid/expired/revoked pairing
- valid/invalid signatures and nonces
- stale, duplicate, reordered, and reset event sequences
- all Android permission states
- all BLACKWAVE evidence states, especially `BLOCKED`
- every HUD screen, overlay, service, receiver, plugin, command, and adapter
- AI-proposed actions versus explicit user-confirmed actions
- transport timeout, cancellation, TLS failure, malformed response, and size limit
- process death, rotation, backgrounding, Doze, and network transitions
- redacted diagnostics and support export
- API 31–35 compatibility where infrastructure permits

## Done criteria

- [ ] A documented `fr3k-blackwave-mobile/1` contract exists and is validated in both repositories.
- [ ] BLACKWAVE remains the sole authority for identity, authorization, signing, OTA, revocation, and evidence.
- [ ] HUD has typed client/session/error handling with bounded, cancellable transport.
- [ ] Pairing, unpairing, revocation, key change, expiry, and re-pair are tested.
- [ ] Catalog, capabilities, status, evidence, events, and blocked states synchronize correctly.
- [ ] All commands share one policy-checked execution path.
- [ ] Main menus use grouped progressive disclosure; all LoRa/radio/mesh actions
      are reachable under one **LoRa & Mesh** heading rather than repeated on the
      main interface.
- [ ] Nested menu actions remain searchable, accessible, deterministic, and
      capability-aware.
- [ ] Offline mode is explicit and cannot silently execute unsafe queued actions.
- [ ] Diagnostics/support exports are redacted and tested.
- [ ] Python, integration, Android, protocol, transport, UI, and end-to-end tests pass.
- [ ] CI validates both repositories and shared fixtures.
- [ ] HUD ↔ BLACKWAVE trust bootstrap is explicit, authenticated, revocable,
      and covered by positive and negative tests.
- [ ] A connection matrix covers the gateway, HUD, and every supported device
      class, including simulated fixtures for unavailable hardware.
- [ ] Device identity, backend, capability, status, offline, blocked, denied,
      and revocation behavior are validated per device fixture.
- [ ] Physical validation is separated from simulator results and never inferred
      from a passing host or Android test.
- [ ] Documentation and release metadata match the implementation.
- [ ] Physical-only gates remain clearly marked `BLOCKED` until separately evidenced.

## STOP conditions

Stop and report instead of improvising if:

- the two repositories define incompatible identity or authorization semantics;
- implementing a feature requires moving signing/OTA authority into Android;
- a proposed transport bypasses the authenticated gateway;
- a physical test, credential, RF action, actuator, or active serial device is required;
- the current code differs materially from this plan’s described architecture;
- a test would require weakening a security assertion or changing a documented invariant;
- a contract change would break existing clients without an explicit migration.

## Maintenance notes

Any future BLACKWAVE protocol change must update the shared contract fixtures,
Python validators, Android protocol models, compatibility tests, and release
documentation together. Any future HUD capability must declare its source,
permissions, risk, offline semantics, confirmation requirements, and mapped
BLACKWAVE authority. Reviewers should especially inspect identity binding,
revocation, replay protection, unsafe retry behavior, redaction, and whether a
UI success state is backed by a confirmed gateway result.
