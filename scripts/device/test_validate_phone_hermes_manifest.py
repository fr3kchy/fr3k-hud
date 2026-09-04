#!/usr/bin/env python3
"""TDD test harness for validate_phone_hermes_manifest.py (Task 2).

Run with:
    python3 -m unittest -v scripts/device/test_validate_phone_hermes_manifest.py

RED cases the plan asks for:
    - missing required tool (python or hermes absent)
    - secret-looking value present and not "[REDACTED]"
    - non-executable Hermes
    - bad JSON

GREEN cases:
    - valid manifest with no secret-shaped keys
    - valid manifest with secret-shaped keys set to "[REDACTED]"
    - validator never echoes raw secret values in diagnostics
"""

from __future__ import annotations

import hashlib
import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
VALIDATOR = REPO_ROOT / "scripts" / "device" / "validate_phone_hermes_manifest.py"
SCHEMA = REPO_ROOT / "docs" / "verification" / "PHONE_HERMES_MANIFEST.schema.json"


def _canonical_sha(doc: dict) -> str:
    body = {k: v for k, v in doc.items() if k != "report_sha256"}
    canonical = json.dumps(body, sort_keys=True, separators=(",", ":")).encode("utf-8")
    return hashlib.sha256(canonical).hexdigest()


def _minimal_valid_manifest() -> dict:
    """A manifest that satisfies every check the validator performs.

    Shape follows the plan's required fields: schema_version=1, captured_at,
    device (architecture + android_sdk), paths (HOME/PREFIX/TMPDIR), tools map
    with executable/version, top-level hermes (path/version/doctor), gateway
    (bind/port/doctor), services, storage, env_var_names only, packages.
    """
    return {
        "schema_version": 1,
        "captured_at": "2026-09-04T10:00:00Z",
        "device": {"architecture": "aarch64", "android_sdk": "31"},
        "paths": {
            "HOME":   "/data/data/com.termux/files/home",
            "PREFIX": "/data/data/com.termux/files/usr",
            "TMPDIR": "/data/data/com.termux/files/usr/tmp",
        },
        "env_var_names": ["HOME", "PREFIX", "TMPDIR", "PATH"],
        "tools": {
            "python": {
                "path": "/data/data/com.termux/files/usr/bin/python",
                "executable": True,
                "version": "3.11.6",
            },
            "hermes": {
                "path": "/data/data/com.termux/files/usr/bin/hermes",
                "executable": True,
                "version": "0.7.0",
            },
        },
        "hermes": {
            "path": "/data/data/com.termux/files/usr/bin/hermes",
            "executable": True,
            "version": "0.7.0",
            "doctor": "ok",
        },
        "gateway": {"bind": "127.0.0.1", "port": 8765, "doctor": "ok"},
        "services": [{"name": "hermes-gateway", "state": "run", "pid": 1234}],
        "storage": {"prefix_free_mb": 120000, "home_free_mb": 50000},
        "packages": ["termux-api", "openssh", "git", "python"],
    }


def _write_manifest(doc: dict, dirpath: Path) -> Path:
    path = dirpath / "manifest.json"
    doc["report_sha256"] = _canonical_sha(doc)
    path.write_text(json.dumps(doc))
    return path


def _run_validator(manifest_path: Path) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, str(VALIDATOR), str(manifest_path)],
        capture_output=True,
        text=True,
    )


class TestValidatePhoneHermesManifest(unittest.TestCase):
    """End-to-end validator harness against the on-disk schema + validator."""

    @classmethod
    def setUpClass(cls) -> None:
        # Skip cleanly when the validator or schema hasn't been written yet.
        if not VALIDATOR.is_file():
            raise unittest.SkipTest(f"RED: validator not yet implemented at {VALIDATOR}")
        if not SCHEMA.is_file():
            raise unittest.SkipTest(f"RED: schema not yet implemented at {SCHEMA}")

    def setUp(self) -> None:
        self._tmp = tempfile.TemporaryDirectory()
        self.tmpdir = Path(self._tmp.name)

    def tearDown(self) -> None:
        self._tmp.cleanup()

    # ------------------------------------------------------------------ GREEN

    def test_clean_manifest_passes(self) -> None:
        doc = _minimal_valid_manifest()
        manifest = _write_manifest(doc, self.tmpdir)
        result = _run_validator(manifest)
        self.assertEqual(
            result.returncode, 0,
            msg=f"validator rejected a clean manifest: stderr={result.stderr!r}",
        )
        self.assertTrue(result.stdout.startswith("VALID phone-hermes-manifest"),
                        msg=f"unexpected stdout: {result.stdout!r}")
        self.assertIn("schema=1", result.stdout)
        self.assertIn("secrets=0", result.stdout)

    def test_redacted_secret_keys_pass(self) -> None:
        doc = _minimal_valid_manifest()
        doc["tools"]["hermes"]["api_token"] = "[REDACTED]"
        manifest = _write_manifest(doc, self.tmpdir)
        result = _run_validator(manifest)
        self.assertEqual(
            result.returncode, 0,
            msg=f"validator rejected redacted manifest: stderr={result.stderr!r}",
        )
        self.assertIn("secrets=1", result.stdout)

    # ------------------------------------------------------------------- RED

    def test_missing_required_tool_rejected(self) -> None:
        doc = _minimal_valid_manifest()
        del doc["tools"]["python"]
        manifest = _write_manifest(doc, self.tmpdir)
        result = _run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID", result.stderr)

    def test_missing_hermes_rejected(self) -> None:
        doc = _minimal_valid_manifest()
        del doc["tools"]["hermes"]
        manifest = _write_manifest(doc, self.tmpdir)
        result = _run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID", result.stderr)

    def test_non_executable_hermes_rejected(self) -> None:
        doc = _minimal_valid_manifest()
        doc["tools"]["hermes"]["executable"] = False
        manifest = _write_manifest(doc, self.tmpdir)
        result = _run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID", result.stderr)
        # Must reference the field, never a path on disk.
        self.assertIn("tools.hermes.executable", result.stderr)

    def test_secret_looking_value_rejected(self) -> None:
        doc = _minimal_valid_manifest()
        doc["tools"]["hermes"]["api_token"] = "ABCDEFG1234567890abcdef"
        manifest = _write_manifest(doc, self.tmpdir)
        result = _run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID", result.stderr)
        # Critical: the raw secret must NOT appear anywhere on stderr/stdout.
        self.assertNotIn("ABCDEFG1234567890abcdef", result.stderr)
        self.assertNotIn("ABCDEFG1234567890abcdef", result.stdout)

    def test_malformed_json_rejected(self) -> None:
        manifest = self.tmpdir / "broken.json"
        manifest.write_text("not even json\n")
        result = _run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID", result.stderr)

    def test_missing_file_rejected(self) -> None:
        missing = self.tmpdir / "does-not-exist.json"
        result = _run_validator(missing)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("INVALID", result.stderr)

    def test_bogus_sha_rejected(self) -> None:
        doc = _minimal_valid_manifest()
        doc["report_sha256"] = "deadbeef" * 8  # 64 hex but wrong value
        manifest = self.tmpdir / "manifest.json"
        manifest.write_text(json.dumps(doc))
        result = _run_validator(manifest)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("report_sha256 mismatch", result.stderr)

    def test_no_args_exits_nonzero(self) -> None:
        result = subprocess.run(
            [sys.executable, str(VALIDATOR)],
            capture_output=True, text=True,
        )
        self.assertNotEqual(result.returncode, 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)