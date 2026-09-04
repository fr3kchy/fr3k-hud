# Physical Phone Baseline

> **Why this file exists.** Every later change in the FR3K HUD phone-command
> plane must prove it improved the system rather than merely changed it.
> This document records the *ground truth* of the GM1900 at the moment the
> implementation work started, so subsequent reports can diff against it.

## Captured

`build/reports/phone-baseline.json` was produced by
`scripts/device/collect_phone_baseline.sh 192.168.1.72:<port>` on
**2026-09-04 AEST** during Task 1 of the FR3K HUD phone command plane plan.

## Live device (as captured)

| Field | Value |
|---|---|
| Model | GM1900 |
| SDK | 31 (Android 12) |
| ABI | arm64-v8a |
| SELinux (live state) | **Disabled** |
| `/data` free | 122,743 MB (~120 GB) |
| `/data` total | 228,022 MB (~223 GB) |
| Total packages | 396 (85 user / 311 system) |
| Listening TCP ports | 6 |
| Redactions applied | 0 (no secret-shaped keys present in the captured fields) |

> **⚠ Security regression to track.** The plan assumes SELinux is
> `Enforcing` on the live device. The collector reports `Disabled`. This is
> the live `getenforce` result, not an `ro.boot.selinux` value. The HUD
> itself does not flip this; the device setting was changed outside the
> scope of this task. Treat as a planning item for the security-hardening
> phase (Task 19): the patch lane must NOT rely on SELinux enforcement for
> tamper detection.

## Tooling used

| Tool | Allowed by design |
|---|---|
| `adb -s SERIAL shell getprop` | yes (read device props) |
| `adb -s SERIAL shell dumpsys battery` | yes (read battery state) |
| `adb -s SERIAL shell pm list packages` | yes (count + sample) |
| `adb -s SERIAL shell ps -A` | yes (count + sample names) |
| `adb -s SERIAL shell df /data` | yes (storage counters) |
| `adb -s SERIAL shell ss -tln` | yes (listening sockets) |
| `adb -s SERIAL shell cmd appops get` | yes (top-level summary) |

The collector never invokes `pm install`, `pm uninstall`, `am start`, shell
commands that modify state, or any privileged (`su`) action.

## Redaction rule

Any property whose key matches `(?i)token|key|secret|password|pairing_code|code`
has its value replaced with `[REDACTED]`. The rule is enforced both at
key-by-key capture and by a `jq`-based scan of the final JSON
(`scripts/device/test_collect_phone_baseline.sh` asserts no such key
carries a real value).

## How to refresh

```bash
adb devices                                            # resolve serial
bash scripts/device/collect_phone_baseline.sh <serial> \
    > build/reports/phone-baseline.json
jq . build/reports/phone-baseline.json
```

## How to verify the test still passes

```bash
# Fixture-only — no phone required
bash scripts/device/test_collect_phone_baseline.sh

# Against the live device
SERIAL=192.168.1.72:<port> bash scripts/device/test_collect_phone_baseline.sh
```

The fixture mode uses a stub `adb` shim under a tmpdir, so the test can run
in CI without ever talking to a real phone.

## JSON shape (schema_version=1)

```json
{
  "schema_version": 1,
  "captured_at": "<ISO8601>",
  "serial": "<adb serial>",
  "device": {
    "model": "...",
    "sdk": "31",
    "release": "12",
    "abi": "arm64-v8a",
    "selinux": "Disabled|Enforcing|Permissive|null",
    "serialno": "[REDACTED] or value",
    "fingerprint": "[REDACTED] or value"
  },
  "storage":  { "total_data_mb": N, "free_data_mb": N },
  "packages": { "total_count": N, "user_count": N, "system_count": N, "sample_names": [...] },
  "processes":{ "count": N, "sample_names": [...] },
  "permissions":{ "appop_entries": N },
  "ports":    { "listening_count": N, "sample_lines": [...] },
  "battery_optimisation": { "level": "0-100", "low_power": "LOW_POWER_ON|OFF|UNKNOWN" },
  "redactions": { "applied": N, "rule": "(?i)token|key|secret|password|pairing_code|code" }
}
```

## Next steps tied to this baseline

- Task 2 (phone Hermes manifest): cross-reference `com.termux` is in the
  user package list, `com.morphe.manager` is installed.
- Task 3 (version metadata): the baseline lives in this repo before any
  metadata fix that bumps `versionCode`/`versionName`.
- Task 19 (security hardening): re-capture and verify SELinux state then
  decide how to surface the `Disabled` anomaly to the operator.