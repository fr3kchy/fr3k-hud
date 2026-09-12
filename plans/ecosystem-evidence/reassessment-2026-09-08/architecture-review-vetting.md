# Hermes AutoExpert review disposition

Hermes CLI completed a fresh critical review of plans 008/009 (exit 0). Prompt,
output and stderr are retained as `architecture-critical*`. Safe mode was requested
alongside `--skills autoexpert`; the prompt explicitly supplied its methodology.
This proves a specialist critique occurred, not that every normal skill hook loaded.

Accepted: split connectivity/authentication/compatibility; distinguish pre-submission
cancellation from unknown outcomes; assign execution receipts per action owner;
make receipt retention cover command lifetime; explain the direct pairing use case
and prefer shared codec/profile reuse. Incorporated in 008.

Rejected or narrowed:

- Current source anchors were re-read in this pass; an older report being stale
  does not make newly checked anchors stale. HEAD alone cannot fingerprint dirty
  source. Keep working-tree inventories alongside commits.
- Companion bundled-only initialization was checked directly; retain the finding.
- Zero BLACKWAVE network work when disabled is a legitimate acceptance requirement,
  not a claim that it has already been achieved. Do not weaken it into an aspiration.
- The gate table does not make fleet integration depend on local integration:
  standalone gates mean product-local correctness, not the local adapter.
- Receipt time cannot establish the freshness of a device observation; do not derive
  `live` solely from receipt time or discard producer provenance.
- Gateway-free HUD–Cardputer value is an explicit user requirement. Do not remove
  that pairing because the reviewer speculates demand is low.
- The reviewer calls 008 assessment-complete. Reject that overall conclusion:
  complete OTA/device compatibility, executable acceptance fixtures, dependency
  assessment and comprehensive subsystem coverage are still outstanding.

The test-seam concern is valid. Plan 009 is a specification, not executable evidence;
the fixture seam and an executable acceptance package remain required deliverables.
