# 008 — Ecosystem contract and companion migration

Assessment supplement, 2026-09-08. Proposal, not implemented behavior. Read with
the reassessment ledger; the September 7 findings include subsequently fixed code.

## Product and authority boundaries

Keep three independent repositories, release pipelines, persistent stores and
startup paths. HUD owns Android interaction, user consent and local capability
presentation. Cardputer owns local gameplay, saves, input, rendering and peripheral
execution. BLACKWAVE owns fleet enrollment, grants, registry, evidence policy,
signing and OTA policy. Connecting products adds projections and authorized
requests, not a shared mandatory runtime.

| Identifier | Owner and meaning | Must not imply |
|---|---|---|
| local peer fingerprint | Device-local key, approved by the other local peer | Fleet enrollment or a model name |
| model_id | BLACKWAVE catalog / hardware type | Unique physical unit or online state |
| fleet device_id | BLACKWAVE enrollment record | Current address or current reachability |
| mobile client_id | BLACKWAVE pairing store | Founder session or device identity |
| transport address | Discovery/session adapter | Trust or permanent identity |
| event_id / command_id | Original producer / request initiator | A new ID at every relay |

Store an optional verified mapping from local fingerprint to fleet device_id;
BLACKWAVE confirms its enrollment side. HUD caches that mapping with provenance.
Do not invent a fourth authoritative registry. Forgetting local pairing must not
silently erase fleet evidence; removing fleet enrollment must leave local saves
and independently approved local pairing intact.

## Existing contract compatibility

| Surface | Checked anchor | Compatibility decision |
|---|---|---|
| Mobile health | BLACKWAVE `src/blackwave_fleet/mobile.py:103` | `/mobile/v1/health`, public reachability only; never authentication proof |
| Mobile authorization | `mobile.py:91` | Bearer credential plus `X-Blackwave-Client`; HUD's former header mismatch has been fixed |
| Role | `mobile.py:113` | Authenticated projection; validate expiry and capability support, not just successful HTTP |
| Pairing | `mobile.py:125` | One-time request/nonce; authenticate operator QR pin before submitting nonce; returned pin alone cannot bootstrap trust |
| Fleet | `mobile.py:148` | Catalog/registry projection is explicitly cached; reachable gateway does not mean live devices |
| Device | `mobile.py:184` | `/devices/{model_id}` is a model projection; do not address physical commands with this model identifier |
| Peer | `src/blackwave_fleet/peer.py:30,97` | Preserve `fr3k-blackwave-peer/1`, destination, signature, freshness, active enrollment and action constraints |
| Configuration | `src/blackwave_fleet/config_image.py:10` | Preserve FR3KCFG2 byte layout; a mobile DTO is not a flash image |
| Device / OTA | Existing schemas and target implementations | Retain existing wire formats behind adapters; full per-target compatibility remains unverified |
| HUD–Cardputer | No authenticated production adapter established | New local contract needed; neither BLE/LAN discovery nor an unavailable LoRa stub satisfies it |

The current peer implementation bounds arguments to 4,096 bytes and 16 keys.
Do not silently raise that limit in a mobile or local adapter. All proposed limits
below require measurement on Cardputer before release.

## Proposed state and delivery contract

Separate `modeEnabled`, `sessionState`, `observationState` and per-action capability.
Represent connectivity separately as OFFLINE, CONNECTING or ONLINE; authentication
as UNPAIRED, AUTHENTICATED, EXPIRED or REVOKED; compatibility as UNKNOWN, SUPPORTED
or INCOMPATIBLE. A disconnected authenticated session cannot execute online actions.
Observation fields: producer identity, observed_at, received_at,
boot_id, sequence, cached, and live. A new receipt time never refreshes an old
observation. Unknown clocks show unknown freshness and deny expiry-sensitive work.

Capability presentation carries `supported`, `authorized`, `available`, a reason,
and optional expiry. Enabling a screen changes none of these authorization fields.
Recheck authorization at the execution owner immediately before side effects.

For a first local interface, propose `fr3k-local-peer/1` over one measured transport.
Reuse the existing peer envelope fields and codec where possible, but use a distinct
trust profile: BLACKWAVE's current peer acceptance requires active fleet enrollment.
Local pairing cannot satisfy that condition. First evaluate a versioned local
profile adapter before adding a separate codec. A phone and Cardputer on a walk
without the fleet gateway is a concrete standalone-pairing use case.
Negotiate protocol major, supported actions and maximum frame size before accepting
requests. Start with status reads and user-approved notifications; defer arbitrary
files, save merging, remote shell and peripheral actuation. Reserve a 4 KiB frame
ceiling, 16 queued events and a 64 KiB mobile JSON response ceiling as initial test
budgets. Paginate catalog data rather than weakening the global bound. Reject
unsupported major versions; ignore unknown optional fields only after structural
validation. These are design budgets, not measured memory guarantees.

Errors use stable codes with a correlation ID: UNAUTHORIZED, FORBIDDEN, EXPIRED,
REVOKED, CAPABILITY_MISSING, OFFLINE, STALE, UNSUPPORTED_VERSION, TOO_LARGE,
INVALID_RESPONSE, CANCELLED, TIMEOUT and OUTCOME_UNKNOWN. Do not expose raw exception
text or credentials in user-visible diagnostics. Preserve legacy HTTP errors via
explicit mappings; don't assert these codes already exist on every wire.

Cancellation stops pending network work and releases buffers; CANCELLED denotes
confirmed pre-submission cancellation. After submission, missing confirmation is
OUTCOME_UNKNOWN, including timeout or cancellation; neither implies rollback.
Retry reads with bounded jitter (1, 2, 4 seconds,
then stop); do not automatically retry mutations after ambiguous submission.
An execution owner maintains a durable receipt keyed by origin and command_id and
rejects reuse with a different payload digest. On restart, an incomplete mutation
is OUTCOME_UNKNOWN until reconciled. Never promise exactly-once physical execution
where the peripheral cannot atomically commit a receipt with the action.

Relays preserve origin, ID and payload digest. With direct and fleet routes present,
select one route per attempt; fail over only with evidence of no submission. Events
may traverse both routes but are deduplicated by origin, boot_id and sequence.
Bound dedup history and document its expiry; expired commands cannot re-enter after
history eviction: accepted command lifetime plus maximum clock uncertainty must
not exceed receipt retention, and requests below the persisted sequence floor are
rejected. If storage cannot maintain that guarantee, reject new mutations rather
than evicting live receipts. Reconnect resynchronizes snapshots before enabling actions.

Execution receipts belong to HUD for Android-local actions, Cardputer for its local
notifications and peripheral execution, and BLACKWAVE for enrollment/grant changes.
For a fleet-approved Cardputer action, BLACKWAVE records authorization/evidence and
Cardputer records execution under the original command ID. A gateway acknowledgment
must distinguish acceptance from the Cardputer's completed receipt.

## Optional BLACKWAVE mode and migration

1. First make companion trust and state restoration reliable in its existing APK.
   `apps/android-companion/.../ui/BlackwaveViewModel.kt:47` currently initializes
   bundled state; its pairing path persists credentials, but initialization does
   not restore paired UI state. Preserve existing credentials and Room schemas.
2. Extract versioned, Android-compatible domain/network interfaces within BLACKWAVE
   ownership. Reuse QR parsing, SPKI trust, DTO fixtures and validation logic. Keep
   application IDs, navigation, WorkManager registration and stores app-owned.
3. Add HUD's persisted, default-off mode and settings entry. Enable mounts fleet
   navigation; pairing separately establishes a session. Disable cancels only
   BLACKWAVE jobs and removes actionable fleet capabilities. Cached views remain
   explicitly cached and read-only, with a separate erase/unpair action.
4. Initially retain the companion APK and offer explicit navigation to it. Moving
   screens into HUD is incremental: catalog/read-only views, pairing/session,
   evidence, then authorized workflows. Do not import its Application or assume
   another APK's private preferences or Keystore aliases are readable.
5. Data migration requires user-selected export/import or a narrowly authenticated
   same-signer channel. Re-pair when secure keys are non-exportable. Copy drafts and
   caches with schema versions and idempotent migration receipts; never silently
   delete the companion's source data. Rollback must reopen old data safely.
6. Measure APK compressed size, DEX count, cold-start time, first frame, idle jobs,
   RSS and network calls with mode off/on in both APKs. Establish baseline before
   extraction; require zero BLACKWAVE network calls/jobs when mode is off and no
   regression in HUD local startup. No numeric size saving is claimed yet.

This extends the client ownership in BLACKWAVE ADR-020; any move of companion UI
into HUD needs an explicit amendment describing shared dependency ownership and
continued companion support. It does not move fleet authority into Android.

## Execution order and gates

| Package / owner | Prerequisite | Reviewable result and verification gate |
|---|---|---|
| Verification foundation / all maintainers | None | Per-subsystem inventory, exact commands, isolated builds and reproducible fixture runner; untested targets explicitly blocked |
| Standalone correctness / owning product | Baseline | Consent, storage ownership and authorization defects repaired with negative/regression tests; local startup without peers |
| Mobile adapter / BLACKWAVE + HUD | Baseline | Same golden vectors pass JVM and Python, pin-before-nonce TLS test, body bounds/cancellation and expired/revoked tests |
| Local adapter / HUD + Cardputer | Standalone gates | Direct safe read/notification without gateway; wrong key, oversized frame, duplicate and restart fixtures pass |
| Fleet adapter / BLACKWAVE + Cardputer | Contract + standalone gates | Enrollment independent of local pairing; revoked/unknown unit denied; local loop survives gateway loss |
| HUD mode / HUD + companion maintainers | Mobile session gates | Off/on/disable/unpair/process-death tests; migration preserves drafts and old APK; accessibility labels and focus verified |
| Combined resilience / all maintainers | Both adapters | Seven configurations and simultaneous routes tested; no duplicate action; independent version upgrade and rollback fixtures |

Do not make optional HUD fleet mode depend on implementing both Cardputer pairings:
its read-only mobile milestone is independently valuable. The earlier plan 007
dependency list overcouples these deliverables. Full-system acceptance still needs
all pairings. Physical installation/peripheral/recovery evidence is a separate gate.
