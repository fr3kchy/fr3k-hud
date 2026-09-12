# BLACKWAVE assessment (working tree, 2026-09-07)

Baseline: branch `fr3k/research-reconciliation` at `b037bc53a9664844b45240e82ac9e563ec4d622c`; extensive modified and untracked source, schemas, evidence, builds and media. Inventory: 6,805 nonignored files. Research invariants require exact unit evidence and forbid physical operations.

| Subsystem | Static | Verification | Assessment |
|---|---|---|---|
| Python fleet bridge/mobile/identity/auth/registry/OTA | reviewed source and schemas | 135 pytest pass | host control plane useful; grant provenance and mobile adapter need work |
| contract/integration validators | reviewed `integration/` | contract v2 validates 9 devices | device events and exact IDs are authoritative; physical enrollment mostly absent |
| fleet console/WebUI | reviewed packages | TypeScript/Vite builds; 40 WebUI tests pass | bundle warning and compatibility fixtures remain |
| Android companion/network/security/storage/UI | reviewed current and untracked packages | no physical evidence; companion test/build status retained in ledger | ViewModel/cache restoration gap; preserve ADR-020 placement |
| shared firmware and every target pointer | inventoried source/pointers/provenance | target-specific results mixed; no flashing | exact target/unit evidence is `BLOCKED` until separately run |
| generated/release/research/vendor | classified in lead inventory | hashes/status retained | not independently source-reviewed |

Checked findings F-05, F-06 and F-13 are in the root index. Existing evidence remains unit-specific and cannot be generalized to model families.
