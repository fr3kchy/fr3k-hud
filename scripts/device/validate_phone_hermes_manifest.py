#!/usr/bin/env python3
"""Validate a phone Hermes self-audit manifest (Task 2).

Usage::

    python3 scripts/device/validate_phone_hermes_manifest.py <manifest.json>

Exits 0 and prints ``VALID phone-hermes-manifest schema=1 secrets=N`` when the
manifest is well-formed. Exits 1 and prints an ``INVALID ...`` line to stderr
for any rule violation. The validator never echoes manifest values in
diagnostics — only the safe field path of the offending leaf.

Hard rules
----------

1.  Schema shape must match ``docs/verification/PHONE_HERMES_MANIFEST.schema.json``
    (validated with ``jsonschema``).
2.  Every value whose **key** matches
    ``(?i)token|key|secret|password|cookie|code|auth`` must equal the literal
    string ``[REDACTED]`` or be empty.
3.  Any string value that contains obvious credential material — a JWT, an
    AWS access key, an SSH/Bearer/PEM-shaped token, or a high-entropy secret
    — is rejected even if the key is not secret-shaped.
4.  Required fields per the plan: ``schema_version=1``, ``captured_at``,
    ``device`` (architecture + android_sdk), ``paths`` (HOME/PREFIX/TMPDIR),
    ``tools`` map with ``executable``+``version``, top-level ``hermes`` with
    ``executable=True``, ``gateway`` (bind/port), ``services``, ``storage``,
    ``env_var_names`` only, ``packages``.
5.  ``report_sha256`` must equal SHA-256 of the canonical JSON
    (``sort_keys=True``, ``separators=(",", ":")``) with that field removed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from pathlib import Path

try:
    import jsonschema  # type: ignore[import-not-found]
    HAVE_JSONSCHEMA = True
except ImportError:
    jsonschema = None  # type: ignore[assignment]
    HAVE_JSONSCHEMA = False

SCHEMA_PATH = (
    Path(__file__).resolve().parent.parent.parent
    / "docs" / "verification" / "PHONE_HERMES_MANIFEST.schema.json"
)

SECRET_KEY_RE = re.compile(r"(?i)(token|key|secret|password|cookie|code|auth)")
REDACTED_SENTINEL = "[REDACTED]"
SHA256_RE = re.compile(r"^[a-f0-9]{64}$")

# Credential-shaped regexes applied to every string leaf, regardless of key.
_CRED_PATTERNS: list[tuple[str, re.Pattern[str]]] = [
    ("jwt",        re.compile(r"\beyJ[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\.[A-Za-z0-9_-]{8,}\b")),
    ("aws_key",    re.compile(r"\bAKIA[0-9A-Z]{16}\b")),
    ("github_pat", re.compile(r"\bghp_[A-Za-z0-9]{30,}\b")),
    ("slack",      re.compile(r"\bxox[baprs]-[A-Za-z0-9-]{10,}\b")),
    ("pem_block",  re.compile(r"-----BEGIN [A-Z ]+PRIVATE KEY-----")),
    ("bearer",     re.compile(r"(?i)\bBearer\s+[A-Za-z0-9._\-]{16,}")),
    ("basic_auth", re.compile(r"(?i)\bbasic\s+[A-Za-z0-9+/=]{16,}")),
    ("high_entropy_secret",
        re.compile(r"(?i)\b(token|secret|password|apikey|api_key|access_key)"
                   r"\s*[=:]\s*['\"]?[A-Za-z0-9._\-/+=]{20,}['\"]?")),
]

REQUIRED_TOP_KEYS: tuple[str, ...] = (
    "schema_version", "captured_at", "device", "paths", "env_var_names",
    "tools", "hermes", "gateway", "services", "storage", "packages",
    "report_sha256",
)


class ValidationError(Exception):
    """Raised on any hard-failure with a safe path-style message."""


def _safe_path(parts: list) -> str:
    out: list[str] = []
    for p in parts:
        if isinstance(p, int):
            out.append(f"[{p}]")
        else:
            out.append(str(p))
    return ".".join(out) if out else "<root>"


def _canonical_sha(doc: dict) -> str:
    body = {k: v for k, v in doc.items() if k != "report_sha256"}
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _check_schema(doc: dict, schema: dict) -> None:
    if not HAVE_JSONSCHEMA or jsonschema is None:
        raise ValidationError(
            "jsonschema package not installed; cannot validate "
            "(pip install jsonschema to enable strict schema checking)"
        )
    try:
        jsonschema.validate(doc, schema, format_checker=jsonschema.FormatChecker())
    except jsonschema.ValidationError as exc:
        path = _safe_path(list(exc.absolute_path))
        raise ValidationError(f"schema validation failed at '{path}': {exc.message}")


def _check_required_top_keys(doc: dict) -> None:
    missing = [k for k in REQUIRED_TOP_KEYS if k not in doc]
    if missing:
        raise ValidationError(
            f"manifest missing required top-level keys: {missing}"
        )


def _check_hermes(doc: dict) -> None:
    """Hermes must be executable, whether recorded under ``hermes`` or
    ``tools.hermes``. The plan calls out ``hermes path/version/doctor``; real
    audits may also surface an ``executable`` flag on the ``tools.hermes``
    entry, so accept either location but reject if neither declares True.
    """
    candidates: list[tuple[str, dict]] = []
    hermes_raw = doc.get("hermes")
    if isinstance(hermes_raw, dict):
        candidates.append(("hermes", hermes_raw))
    tools_raw = doc.get("tools")
    if isinstance(tools_raw, dict):
        th_raw = tools_raw.get("hermes")
        if isinstance(th_raw, dict):
            candidates.append(("tools.hermes", th_raw))

    if not candidates:
        raise ValidationError(
            "manifest missing top-level 'hermes' (or tools.hermes) object"
        )

    # The first candidate that declares executable must be True.
    saw_executable = False
    saw_path = False
    saw_version = False
    saw_doctor = False
    for label, entry in candidates:
        if "executable" in entry:
            saw_executable = True
            if not entry.get("executable"):
                raise ValidationError(
                    f"{label}.executable must be True"
                )
        if "path" in entry:
            saw_path = True
            p = entry.get("path")
            if not isinstance(p, str) or not p.startswith("/"):
                raise ValidationError(
                    f"{label}.path must be an absolute path, got {p!r}"
                )
        if "version" in entry:
            saw_version = True
            v = entry.get("version")
            if not isinstance(v, str) or not v:
                raise ValidationError(
                    f"{label}.version must be a non-empty string"
                )
        if "doctor" in entry:
            saw_doctor = True
            if entry.get("doctor") not in ("ok", "warn", "fail"):
                raise ValidationError(
                    f"{label}.doctor must be one of ok|warn|fail"
                )

    # Top-level ``hermes`` is required by the plan; tools.hermes alone is not
    # enough to satisfy the schema_version=1 contract.
    if not isinstance(hermes_raw, dict):
        raise ValidationError("manifest missing top-level 'hermes' object")
    if not saw_executable:
        raise ValidationError("hermes.executable must be True")
    if not saw_path:
        raise ValidationError("hermes.path missing")
    if not saw_version:
        raise ValidationError("hermes.version missing")
    if not saw_doctor:
        raise ValidationError("hermes.doctor must be one of ok|warn|fail")


def _check_tools(doc: dict) -> None:
    tools = doc.get("tools")
    if not isinstance(tools, dict):
        raise ValidationError("manifest 'tools' must be an object")
    missing = [k for k in ("python", "hermes") if k not in tools]
    if missing:
        raise ValidationError(
            f"manifest missing required tools entries: {missing}"
        )
    for name, entry in tools.items():
        if not isinstance(entry, dict):
            raise ValidationError(f"tools.{name} must be an object")
            continue
        p = entry.get("path")
        if not isinstance(p, str) or not p.startswith("/"):
            raise ValidationError(f"tools.{name}.path must be absolute")
        if not isinstance(entry.get("executable"), bool):
            raise ValidationError(f"tools.{name}.executable must be boolean")
        v = entry.get("version")
        if not isinstance(v, str):
            raise ValidationError(f"tools.{name}.version must be string")


def _check_gateway(doc: dict) -> None:
    gw = doc.get("gateway")
    if not isinstance(gw, dict):
        raise ValidationError("manifest missing 'gateway' object")
    bind = gw.get("bind")
    if not isinstance(bind, str) or not bind:
        raise ValidationError("gateway.bind must be a non-empty string")
    port = gw.get("port")
    if not isinstance(port, int) or not 0 <= port <= 65535:
        raise ValidationError("gateway.port must be integer in 0..65535")
    if gw.get("doctor") not in ("ok", "warn", "fail"):
        raise ValidationError("gateway.doctor must be one of ok|warn|fail")


def _check_storage(doc: dict) -> None:
    storage = doc.get("storage")
    if not isinstance(storage, dict):
        raise ValidationError("manifest missing 'storage' object")
    for key in ("prefix_free_mb", "home_free_mb"):
        v = storage.get(key)
        if not isinstance(v, int) or v < 0:
            raise ValidationError(
                f"storage.{key} must be a non-negative integer"
            )


def _count_secret_redactions(doc: dict) -> int:
    redacted = 0

    def walk(node, path: list) -> None:
        nonlocal redacted
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, path + [k])
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, path + [i])
        else:
            key = path[-1] if path else ""
            if isinstance(key, str) and SECRET_KEY_RE.search(key):
                if isinstance(node, str) and node == REDACTED_SENTINEL:
                    redacted += 1
                    return
                if node in ("", None):
                    redacted += 1
                    return
                raise ValidationError(
                    f"secret-like key '{_safe_path(path)}' has non-redacted value"
                )

    walk(doc, [])
    return redacted


def _check_string_credential_material(doc: dict) -> None:
    def walk(node, path: list) -> None:
        if isinstance(node, dict):
            for k, v in node.items():
                walk(v, path + [k])
        elif isinstance(node, list):
            for i, v in enumerate(node):
                walk(v, path + [i])
        elif isinstance(node, str):
            for label, pattern in _CRED_PATTERNS:
                if pattern.search(node):
                    raise ValidationError(
                        f"credential-shaped content at '{_safe_path(path)}' "
                        f"(matched {label})"
                    )
    walk(doc, [])


def _check_report_sha256(doc: dict) -> None:
    declared = doc.get("report_sha256")
    if not isinstance(declared, str) or not SHA256_RE.match(declared):
        raise ValidationError(
            f"report_sha256 missing or not 64-hex: {declared!r}"
        )
    actual = _canonical_sha(doc)
    if actual != declared:
        raise ValidationError(
            f"report_sha256 mismatch: declared={declared[:12]}… "
            f"computed={actual[:12]}…"
        )


def validate_manifest(doc: dict, schema: dict) -> int:
    """Run every check. Returns the count of redacted secret leaves."""
    _check_required_top_keys(doc)
    _check_schema(doc, schema)
    _check_hermes(doc)
    _check_tools(doc)
    _check_gateway(doc)
    _check_storage(doc)
    redacted = _count_secret_redactions(doc)
    _check_string_credential_material(doc)
    _check_report_sha256(doc)
    return redacted


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Validate a phone Hermes self-audit manifest."
    )
    parser.add_argument("manifest", type=Path, help="path to manifest JSON")
    parser.add_argument(
        "--schema", type=Path, default=SCHEMA_PATH,
        help="override schema path",
    )
    args = parser.parse_args()

    if not args.schema.is_file():
        print(
            f"INVALID phone-hermes-manifest: schema file missing: {args.schema}",
            file=sys.stderr,
        )
        return 1
    if not args.manifest.is_file():
        print(
            f"INVALID phone-hermes-manifest: manifest file missing: {args.manifest}",
            file=sys.stderr,
        )
        return 1

    try:
        schema = json.loads(args.schema.read_text())
    except json.JSONDecodeError as exc:
        print(
            f"INVALID phone-hermes-manifest: schema is not valid JSON: {exc}",
            file=sys.stderr,
        )
        return 1
    try:
        doc = json.loads(args.manifest.read_text())
    except json.JSONDecodeError as exc:
        print(
            f"INVALID phone-hermes-manifest: manifest is not valid JSON: {exc}",
            file=sys.stderr,
        )
        return 1

    if not isinstance(doc, dict):
        print(
            "INVALID phone-hermes-manifest: manifest root must be an object",
            file=sys.stderr,
        )
        return 1

    try:
        redacted = validate_manifest(doc, schema)
    except ValidationError as exc:
        print(f"INVALID phone-hermes-manifest: {exc}", file=sys.stderr)
        return 1

    schema_version = doc.get("schema_version")
    print(f"VALID phone-hermes-manifest schema={schema_version} secrets={redacted}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
