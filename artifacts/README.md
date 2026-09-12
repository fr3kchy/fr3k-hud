# Artifact provenance

This directory holds **hashes and metadata only**. APK binaries are intentionally
excluded by `.gitignore` (`*.apk`).

| File | Provenance |
|---|---|
| `FR3K-HUD-0.1.0.apk.sha256` | Historical (pre-recovery) recorded hash. |
| `FR3K-HUD-0.4.15.apk.meta.json`, `FR3K-HUD-0.4.15.apk.sha256` | Historical (pre-recovery) recorded hash/metadata. |
| `FR3K-HUD-0.4.16.apk.meta.json`, `FR3K-HUD-0.4.16.apk.sha256` | **Historical** build recorded on the desktop-parrot checkout (`builtAt` 2026-09-09T10:57:44Z, `versionCode` 416, sha256 `517bd4db…d11e18`). Recovered by the HUD reconciliation job; **not** built or re-verified by it. |
| `screenshots/` | Historical UI captures from the same operator pass. |

Rules:

- A recorded hash proves only that a file with that digest existed when the entry
  was written. It does not prove the binary still exists, that it is installed on
  any device, or that it is physically accepted.
- Artifacts produced by a later build must state the **source commit** they were
  built from; a hash without a source commit is not traceable provenance.
- Never promote a historical entry to "current candidate" without rebuilding from
  a known commit and re-hashing the output.
