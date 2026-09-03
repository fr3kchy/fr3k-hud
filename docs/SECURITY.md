# Security

FR3K HUD will never:

- Capture screenshots, microphone, location, or clipboard silently.
- Upload anything to the cloud without a visible context manifest (§11).
- Modify, hook, or extend third-party applications unless the user explicitly
  enables an opt-in integration (Morphe, LSPatch, Vector — V2 / V3).
- Touch payment, DRM, account integrity, or subscription systems.
- Use Shizuku or root privileges to bypass another app's security.
- Persist in a way that survives uninstallation.
- Send credentials off-device in plaintext.

## Tier model

| Tier | Source | Example | Requirement |
|------|--------|---------|-------------|
| 0 | Android SDK | Hermes HTTPS, share target, command palette, URL sanitiser | None |
| 1 | User grants | Overlay, accessibility, MediaProjection, location, microphone | Runtime grant |
| 2 | Shizuku | Package management, supported system settings | User runs Shizuku |
| 3 | Rootless LSPatch/NPatch | Per-app UI augmentations | User installs compatible framework |
| 4 | Rooted Vector/libxposed | Per-package adapters | Root + user opt-in |

The application detects the available tier at runtime. UI surfaces actions
only for capabilities the current tier actually delivers.

## Encryption

- `DeviceIdentity` lives in `EncryptedSharedPreferences` (AES256-GCM).
- `SecureStore` holds Hermes tokens, API keys, SSH key references — never in
  plain prefs.
- Device fingerprint is a SHA-256 hash of the device id (first 16 hex chars).
- Production logs are free of token / secret strings by construction.

## Capability truthfulness

The capability registry is the single source of truth. Plugins register them at
start; they're removed at stop. The UI never displays a button that requires a
capability that isn't currently registered. If a capability disappears at
runtime (plugin stopped, permission revoked), all dependent commands become
hidden instantly.

## Context firewall (§11)

Every outbound `agent.ask` displays a manifest:

```
SEND TO HERMES
  Application     YES  (com.android.chrome)
  URL             YES  (https://…)
  Selected text   YES  (…truncated…)
  Screenshot      NO
  Location        NO
  Clipboard       NO
  [ SEND ]
```

The user can toggle individual fields before sending. The default profile
(`NORMAL`) may include URL + selected text. Profiles `LOCAL_ONLY` and `PRIVATE`
strip everything that could identify the user; `RESEARCH` allows web tools.

## Plugin failure isolation

Plugins run in their own SupervisorJob. A failing plugin cannot tear down the
process. Its capabilities and commands are removed; the rest of the system
keeps running.

## Threat model (V1)

| Threat | Mitigation |
|--------|-----------|
| Malicious plugin | Capability registry scopes per-owner; teardown is per-owner. |
| Hermes MITM | HTTPS + bearer token (V3 adds envelope signing). |
| Token theft | `EncryptedSharedPreferences`; never logged. |
| Rooted device data exfiltration | V1 doesn't require root; V3 Vector adapter requires per-package opt-in. |
| Privilege escalation via Shizuku | Shizuku integration (V2) only exposes narrowly-scoped APIs. |
| Adversarial third-party app overlay | The HUD orb is the user's own overlay; FR3K never adds overlays to other apps. |

## What we will never build

- Banking-app integration
- DRM / Play Integrity bypass
- Subscription unlocking
- Stealth screenshots
- Hidden keylogging
- Hidden microphone / camera capture
- Covert location collection
- Persistence that survives uninstallation