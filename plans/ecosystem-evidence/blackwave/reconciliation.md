# BLACKWAVE prior-audit reconciliation

- ADR-020 Android-in-monorepo boundary: **confirmed and accepted**; retain it.
- `blackwave-device/2`, `fr3k-blackwave-peer/1`, FR3KCFG2 and evidence precedence: **confirmed** in invariants and integration contract.
- Mobile pairing, expiry and revocation: **implemented host primitives, integration incomplete**; `PairingStore` persists hashes and checks expiry/revocation, but companion restore and grant provenance remain findings.
- Physical fleet readiness claims: **unverified or blocked** unless tied to exact unit records; build/boot evidence is not production evidence.
- HUD integration plan route/header assumptions: **contradicted by current code** and superseded by Plan 003 adapter.
