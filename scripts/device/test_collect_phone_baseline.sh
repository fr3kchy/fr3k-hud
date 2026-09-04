#!/usr/bin/env bash
# Test for collect_phone_baseline.sh
#
# Usage: bash scripts/device/test_collect_phone_baseline.sh
#
# Modes:
#   SERIAL=<adb-serial>  bash scripts/device/test_collect_phone_baseline.sh
#       Runs end-to-end against a real device. Skips with exit 77 if unreachable.
#
#   (no SERIAL)
#       Exercises the collector against a fixture command runner in a tmpdir.
#       Pass/fail is meaningful; no phone required.
#
# Exit codes: 0 PASS, 1 FAIL, 77 SKIP.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
COLLECTOR="${REPO_ROOT}/scripts/device/collect_phone_baseline.sh"

if [[ ! -x "${COLLECTOR}" ]]; then
    echo "RED: collector not found or not executable: ${COLLECTOR}" >&2
    exit 1
fi

# ---------------------------------------------------------------------------
# Fixture builder: synthesises every command the collector invokes.
# ---------------------------------------------------------------------------
build_fixture_runner() {
    local bindir="$1"
    mkdir -p "${bindir}"

    # Stub `adb` that ignores the serial argument and prints canned output
    # for each subcommand the collector is allowed to call.
    cat >"${bindir}/adb" <<'ADB_EOF'
#!/usr/bin/env bash
# fixture adb: prints canned values for each documented subcommand.
sub=""
path=""
for arg in "$@"; do
    case "${arg}" in
        shell|get-state|get-serialno) ;;
        -s|-e|-d) ;;
        *)  case "${arg}" in
                getprop|dumpsys|pm|ps|df|ss|cmd|appops)
                    sub="${arg}"; path="";;
                *)
                    if [[ -n "${sub}" && -z "${path}" ]]; then path="${arg}"; fi
                    ;;
            esac
            ;;
    esac
done

case "${sub}:${path}" in
    getprop:ro.product.model)               echo "Pixel 6 Pro" ;;
    getprop:ro.build.version.sdk)          echo "31" ;;
    getprop:ro.build.version.release)       echo "12" ;;
    getprop:ro.product.cpu.abi)             echo "arm64-v8a" ;;
    getprop:ro.serialno)                   echo "FAKE12345" ;;
    getprop:ro.boot.selinux)               echo "Enforcing" ;;
    getprop:ro.build.fingerprint)          echo "google/raven/raven:12/SA2A.220505.005/1" ;;
    dumpsys:boot)                          echo "boot complete" ;;
    dumpsys:battery)                       echo "  level: 87" ;;
    pm:*list*packages*)                    echo "package:com.android.settings"; echo "package:com.mcpintelligence.fr3k.hud"; echo "package:com.termux" ;;
    pm:*list*packages*-3*)                 echo "package:com.android.settings"; echo "package:com.mcpintelligence.fr3k.hud"; echo "package:com.termux"; echo "package:moe.shizuku.privileged.api" ;;
    ps:*-A*)                               echo "u0_a99 1234 com.android.systemui"; echo "shell 5678 shizuku_server" ;;
    df:*data*)                             printf 'Filesystem     1K-blocks      Used Available Use%% Mounted on\n'; printf '/dev/block/dm-2 233595404 105900292 125689864  46%% /data/user/0\n' ;;
    ss:*-tln*)                             echo "LISTEN 0 16 0.0.0.0:5037 *"; echo "LISTEN 0 16 127.0.0.1:8022 *" ;;
    cmd:appops)                            echo "RUN_IN_ANY_HOUR" ;;
    *)
        # Unrecognised but harmless — print empty so jq still parses.
        echo ""
        ;;
esac
ADB_EOF
    chmod +x "${bindir}/adb"

    # Stub `getprop` (only used when adb is unavailable, but match anyway).
    cat >"${bindir}/getprop" <<'GETPROP_EOF'
#!/usr/bin/env bash
case "$1" in
    ro.boot.selinux) echo "Enforcing" ;;
    *) echo "" ;;
esac
GETPROP_EOF
    chmod +x "${bindir}/getprop"
}

# ---------------------------------------------------------------------------
# Run collector under a clean PATH that prefers the fixture runner.
# ---------------------------------------------------------------------------
run_collector_under_fixture() {
    local tmp
    tmp="$(mktemp -d)"
    trap "rm -rf '${tmp}'" RETURN
    build_fixture_runner "${tmp}/bin"

    local out
    out="$(env -i HOME="${HOME}" PATH="${tmp}/bin:/usr/bin:/bin" \
        bash "${COLLECTOR}" FAKE-SERIAL)"
    echo "${out}"
}

# ---------------------------------------------------------------------------
# Assertions.
# ---------------------------------------------------------------------------
assert_contains() {
    local haystack="$1" needle="$2" label="$3"
    if ! grep -q -- "${needle}" <<<"${haystack}"; then
        echo "FAIL: ${label} — expected substring not found: ${needle}" >&2
        return 1
    fi
}

assert_no_secret_key() {
    local json="$1"
    # jq -e walks every leaf key; if any key matches the secret regex AND its
    # value is non-empty and not the redaction sentinel, fail.
    local bad
    bad="$(jq -r '
        [paths as $p
         | select(getpath($p) | type == "string")
         | select(([$p[-1] | tostring | test("(?i)(token|password|secret|pairing_code|code)")] | any))
         | select((getpath($p) | tostring) != "[REDACTED]")
         | select((getpath($p) | tostring) != "")
         | ($p | join("."))]
        | unique[]
    ' <<<"${json}" 2>/dev/null || true)"
    if [[ -n "${bad}" ]]; then
        echo "FAIL: secret-like key present in output: ${bad}" >&2
        echo "      output:" >&2
        echo "${json}" | jq . >&2 || echo "${json}" >&2
        return 1
    fi
}

run_fixture_assertions() {
    local out
    if ! out="$(run_collector_under_fixture 2>&1)"; then
        echo "FAIL: collector exited non-zero under fixture" >&2
        echo "${out}" >&2
        return 1
    fi

    if ! jq . >/dev/null 2>&1 <<<"${out}"; then
        echo "FAIL: collector output is not valid JSON" >&2
        echo "${out}" >&2
        return 1
    fi

    local top
    top="$(jq -r 'keys | join(",")' <<<"${out}")"

    for required in schema_version device storage packages processes permissions ports battery_optimisation; do
        assert_contains "${top}" "${required}" "top-level key ${required}" || return 1
    done

    # Device sanity.
    local model sdk selinux
    model="$(jq -r '.device.model // ""' <<<"${out}")"
    sdk="$(jq -r '.device.sdk // ""' <<<"${out}")"
    selinux="$(jq -r '.device.selinux // ""' <<<"${out}")"
    [[ "${model}" == "Pixel 6 Pro" ]] || { echo "FAIL: device.model=${model}" >&2; return 1; }
    [[ "${sdk}" == "31" ]]             || { echo "FAIL: device.sdk=${sdk}"     >&2; return 1; }
    [[ "${selinux}" == "Enforcing" ]] || { echo "FAIL: device.selinux=${selinux}" >&2; return 1; }

    # Storage sanity — free_data_mb must be a positive integer > 100000.
    local free_mb
    free_mb="$(jq -r '.storage.free_data_mb // 0' <<<"${out}")"
    [[ "${free_mb}" =~ ^[0-9]+$ ]] || { echo "FAIL: free_data_mb not numeric: ${free_mb}" >&2; return 1; }
    [[ "${free_mb}" -gt 100000 ]]  || { echo "FAIL: free_data_mb=${free_mb} <= 100000"   >&2; return 1; }

    # Secret scan.
    assert_no_secret_key "${out}" || return 1

    echo "PASS: fixture run produced valid redacted baseline JSON"
}

# ---------------------------------------------------------------------------
# Optional physical-device mode.
# ---------------------------------------------------------------------------
run_real_device_mode() {
    local serial="$1"
    if ! command -v adb >/dev/null 2>&1; then
        echo "SKIP: adb not installed"; return 0
    fi
    if ! adb -s "${serial}" get-state >/dev/null 2>&1; then
        echo "SKIP: device ${serial} not reachable"; return 0
    fi
    local out
    if ! out="$(bash "${COLLECTOR}" "${serial}")"; then
        echo "FAIL: collector failed against ${serial}" >&2; return 1
    fi
    if ! jq . >/dev/null 2>&1 <<<"${out}"; then
        echo "FAIL: live JSON invalid" >&2; echo "${out}" >&2; return 1
    fi
    assert_no_secret_key "${out}" || return 1
    echo "PASS: live device ${serial} baseline captured"
}

# ---------------------------------------------------------------------------
# Main.
# ---------------------------------------------------------------------------
if [[ -n "${SERIAL:-}" ]]; then
    run_real_device_mode "${SERIAL}"
else
    run_fixture_assertions
fi