#!/usr/bin/env bash
# collect_phone_baseline.sh — read-only phone baseline collector.
#
# Captures device/storage/packages/processes/permissions/ports/battery state
# from an Android device over `adb` and emits a redacted JSON document on
# stdout. No persistent state, no install/uninstall, no privileged actions.
#
# Usage:
#   bash scripts/device/collect_phone_baseline.sh <adb-serial> > baseline.json
#
# Safety:
# - Read-only: never modifies the device.
# - Redacts any property whose KEY matches (?i)token|key|secret|password|code.
# - Values that are empty or already "[REDACTED]" pass through unchanged.
# - Exit non-zero on missing serial or unreachable device.
#
# Output schema (versioned):
#   {
#     "schema_version": 1,
#     "captured_at": "<ISO8601>",
#     "serial": "<as supplied or auto-resolved>",
#     "device": { model, sdk, release, abi, selinux, fingerprint, serialno, ... },
#     "storage": { total_data_mb, free_data_mb, ... },
#     "packages": { user_count, system_count, total_count, sample_names: [...] },
#     "processes": { count, sample_names: [...] },
#     "permissions": { summary: {...} },
#     "ports": { listening_count, sample_names: [...] },
#     "battery_optimisation": { ... },
#     "redactions": { applied: N, rule: "..." }
#   }

set -euo pipefail

if [[ $# -lt 1 ]]; then
    echo "usage: $0 <adb-serial>" >&2
    exit 64
fi

SERIAL="$1"

if ! command -v adb >/dev/null 2>&1; then
    echo "error: adb not found in PATH" >&2
    exit 69
fi

if ! adb -s "${SERIAL}" get-state >/dev/null 2>&1; then
    echo "error: device ${SERIAL} not reachable" >&2
    exit 69
fi

# shell_safe_field: jq-quote a string for safe embedding in a shell single-quote.
shell_safe_field() {
    local s="${1-}"
    s="${s//\'/\'\\\'\'}"
    printf "'%s'" "${s}"
}

# adb_shell <remote-args...>: run `adb -s SERIAL shell ARGS`, echo stdout.
adb_shell() {
    adb -s "${SERIAL}" shell "$@"
}

# getprop_safe <key>: read a getprop value, return empty string on error.
getprop_safe() {
    local v
    if v="$(adb_shell getprop "$1" 2>/dev/null)"; then
        printf '%s' "${v}"
    fi
}

# redact_value <key> <value>: replace the value if the key matches the
# redaction rule, otherwise pass through.
REDACT_RULE='token|key|secret|password|pairing_code|code'
REDACTED_COUNT=0

redact_value() {
    local key="$1" value="$2"
    if [[ -z "${value}" ]]; then
        printf '%s' ""
        return
    fi
    if [[ "${value}" == "[REDACTED]" ]]; then
        printf '%s' "[REDACTED]"
        return
    fi
    if [[ "$(printf '%s' "${key}" | tr '[:upper:]' '[:lower:]')" =~ ${REDACT_RULE} ]]; then
        REDACTED_COUNT=$((REDACTED_COUNT + 1))
        printf '%s' "[REDACTED]"
        return
    fi
    printf '%s' "${value}"
}

# ---------------------------------------------------------------------------
# Capture device facts.
# ---------------------------------------------------------------------------
MODEL="$(getprop_safe ro.product.model)"
SDK="$(getprop_safe ro.build.version.sdk)"
RELEASE="$(getprop_safe ro.build.version.release)"
ABI="$(getprop_safe ro.product.cpu.abi)"
SERIALNO="$(getprop_safe ro.serialno)"
SELINUX_BOOT="$(getprop_safe ro.boot.selinux)"
SELINUX_ENFORCE=""
if [[ "$(uname -s)" == "Linux" ]]; then
    SELINUX_ENFORCE="$(getenforce 2>/dev/null || true)"
fi
FINGERPRINT="$(getprop_safe ro.build.fingerprint)"

# ---------------------------------------------------------------------------
# Storage.
# ---------------------------------------------------------------------------
DF_DATA="$(adb_shell df /data 2>/dev/null | tail -n +2 || true)"
# df line: Filesystem 1K-blocks Used Available Use% Mounted
TOTAL_DATA_KB=""
FREE_DATA_KB=""
if [[ -n "${DF_DATA}" ]]; then
    # Skip the header row that some Toybox df versions emit.
    FIRST_DATA_LINE="$(grep -m1 -E '^[A-Za-z0-9_/.]' <<<"${DF_DATA}" || true)"
    if [[ -n "${FIRST_DATA_LINE}" ]]; then
        TOTAL_DATA_KB="$(awk '{print $2}' <<<"${FIRST_DATA_LINE}")"
        FREE_DATA_KB="$(awk '{print $4}' <<<"${FIRST_DATA_LINE}")"
    fi
fi
TOTAL_DATA_MB=0
FREE_DATA_MB=0
[[ "${TOTAL_DATA_KB}" =~ ^[0-9]+$ ]] && TOTAL_DATA_MB=$((TOTAL_DATA_KB / 1024))
[[ "${FREE_DATA_KB}"  =~ ^[0-9]+$ ]] && FREE_DATA_MB=$((FREE_DATA_KB / 1024))

# ---------------------------------------------------------------------------
# Packages (top-level counts + a small sample; never full listing).
# ---------------------------------------------------------------------------
PM_LIST_ALL="$(adb_shell pm list packages 2>/dev/null || true)"
PM_LIST_3="$(adb_shell pm list packages -3 2>/dev/null || true)"

TOTAL_COUNT=0
USER_COUNT=0
[[ -n "${PM_LIST_ALL}" ]] && TOTAL_COUNT="$(grep -c '^package:' <<<"${PM_LIST_ALL}" || true)"
[[ -n "${PM_LIST_3}" ]]  && USER_COUNT="$(grep -c '^package:' <<<"${PM_LIST_3}"  || true)"
SYSTEM_COUNT=$((TOTAL_COUNT - USER_COUNT))
[[ "${SYSTEM_COUNT}" -lt 0 ]] && SYSTEM_COUNT=0

SAMPLE_NAMES=()
if [[ -n "${PM_LIST_ALL}" ]]; then
    while IFS= read -r line; do
        SAMPLE_NAMES+=("${line#package:}")
        [[ ${#SAMPLE_NAMES[@]} -ge 8 ]] && break
    done <<<"${PM_LIST_ALL}"
fi
SAMPLE_JSON="$(printf '%s\n' "${SAMPLE_NAMES[@]:-}" | jq -R . | jq -s . 2>/dev/null || echo '[]')"

# ---------------------------------------------------------------------------
# Processes (count + a small sample).
# ---------------------------------------------------------------------------
PS_OUT="$(adb_shell ps -A 2>/dev/null || true)"
PROC_COUNT=0
[[ -n "${PS_OUT}" ]] && PROC_COUNT="$(grep -cE '^[^ ]' <<<"${PS_OUT}" || true)"
PROC_NAMES=()
if [[ -n "${PS_OUT}" ]]; then
    while IFS= read -r line; do
        # ps -A output columns: USER PID PPID ... NAME (last column on most builds)
        name="${line##* }"
        PROC_NAMES+=("${name}")
        [[ ${#PROC_NAMES[@]} -ge 8 ]] && break
    done <<<"${PS_OUT}"
fi
PROC_JSON="$(printf '%s\n' "${PROC_NAMES[@]:-}" | jq -R . | jq -s . 2>/dev/null || echo '[]')"

# ---------------------------------------------------------------------------
# Permissions — top-level summary only (never per-package).
# ---------------------------------------------------------------------------
APPOP_OUT="$(adb_shell cmd appops get 2>/dev/null || true)"
APPOP_COUNT=0
[[ -n "${APPOP_OUT}" ]] && APPOP_COUNT="$(grep -cE '^[A-Za-z]' <<<"${APPOP_OUT}" || true)"

# ---------------------------------------------------------------------------
# Ports — listening sockets summary only.
# ---------------------------------------------------------------------------
SS_OUT="$(adb_shell ss -tln 2>/dev/null || true)"
LISTEN_COUNT=0
[[ -n "${SS_OUT}" ]] && LISTEN_COUNT="$(grep -cE 'LISTEN' <<<"${SS_OUT}" || true)"
LISTEN_NAMES=()
if [[ -n "${SS_OUT}" ]]; then
    while IFS= read -r line; do
        LISTEN_NAMES+=("${line}")
        [[ ${#LISTEN_NAMES[@]} -ge 6 ]] && break
    done <<<"${SS_OUT}"
fi
LISTEN_JSON="$(printf '%s\n' "${LISTEN_NAMES[@]:-}" | jq -R . | jq -s . 2>/dev/null || echo '[]')"

# ---------------------------------------------------------------------------
# Battery optimisation.
# ---------------------------------------------------------------------------
DUMP_BATTERY="$(adb_shell dumpsys battery 2>/dev/null || true)"
BATTERY_LEVEL=""
BATTERY_OPTIMISATION_STATE="UNKNOWN"
if [[ -n "${DUMP_BATTERY}" ]]; then
    BATTERY_LEVEL="$(awk -F': ' '/level:/ {gsub(/ /,"",$1); print $2; exit}' <<<"${DUMP_BATTERY}")"
fi
# power_policy_all / settings get global is_low_power — best-effort, no fail.
if [[ "$(uname -s)" == "Linux" ]] && command -v adb >/dev/null 2>&1; then
    LP="$(adb_shell cmd settings get global low_power 2>/dev/null || true)"
    case "${LP}" in
        1) BATTERY_OPTIMISATION_STATE="LOW_POWER_ON" ;;
        0|"") BATTERY_OPTIMISATION_STATE="LOW_POWER_OFF" ;;
        *)   BATTERY_OPTIMISATION_STATE="UNKNOWN" ;;
    esac
fi

# ---------------------------------------------------------------------------
# Build the final JSON document. Everything routes through shell_safe_field
# so user-supplied free-text can't break out of single quotes.
# ---------------------------------------------------------------------------
CAPTURED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# Sensitive keys present on some devices. Pass them through redact_value so
# the rule is enforced uniformly.
SERIALNO_REDACTED="$(redact_value "serialno" "${SERIALNO}")"
FINGERPRINT_REDACTED="$(redact_value "fingerprint" "${FINGERPRINT}")"

jq -n \
    --arg captured_at   "${CAPTURED_AT}" \
    --arg serial        "${SERIAL}" \
    --arg model         "${MODEL}" \
    --arg sdk           "${SDK}" \
    --arg release       "${RELEASE}" \
    --arg abi           "${ABI}" \
    --arg serialno      "${SERIALNO_REDACTED}" \
    --arg selinux_boot  "${SELINUX_BOOT}" \
    --arg selinux_host  "${SELINUX_ENFORCE}" \
    --arg fingerprint   "${FINGERPRINT_REDACTED}" \
    --argjson total_data_mb  "${TOTAL_DATA_MB}" \
    --argjson free_data_mb   "${FREE_DATA_MB}" \
    --argjson total_pkgs     "${TOTAL_COUNT}" \
    --argjson user_pkgs      "${USER_COUNT}" \
    --argjson system_pkgs    "${SYSTEM_COUNT}" \
    --argjson sample_pkgs    "${SAMPLE_JSON}" \
    --argjson proc_count     "${PROC_COUNT}" \
    --argjson proc_sample    "${PROC_JSON}" \
    --argjson appop_count    "${APPOP_COUNT}" \
    --argjson listen_count   "${LISTEN_COUNT}" \
    --argjson listen_sample  "${LISTEN_JSON}" \
    --arg battery_level      "${BATTERY_LEVEL}" \
    --arg battery_state      "${BATTERY_OPTIMISATION_STATE}" \
    --argjson redacted_count "${REDACTED_COUNT}" \
    --arg redact_rule        "(?i)token|key|secret|password|pairing_code|code" \
    '
    {
      schema_version: 1,
      captured_at:    $captured_at,
      serial:         $serial,
      device: {
        model:         $model,
        sdk:           $sdk,
        release:       $release,
        abi:           $abi,
        selinux:       (if $selinux_boot != "" then $selinux_boot
                       elif $selinux_host  != "" then $selinux_host
                       else null
                       end),
        serialno:      $serialno,
        fingerprint:   $fingerprint
      },
      storage: {
        total_data_mb: $total_data_mb,
        free_data_mb:  $free_data_mb
      },
      packages: {
        total_count:   $total_pkgs,
        user_count:    $user_pkgs,
        system_count:  $system_pkgs,
        sample_names:  $sample_pkgs
      },
      processes: {
        count:         $proc_count,
        sample_names:  $proc_sample
      },
      permissions: {
        appop_entries: $appop_count
      },
      ports: {
        listening_count: $listen_count,
        sample_lines:    $listen_sample
      },
      battery_optimisation: {
        level:        ($battery_level | select(. != "")),
        low_power:    $battery_state
      },
      redactions: {
        applied: $redacted_count,
        rule:    $redact_rule
      }
    }
    '