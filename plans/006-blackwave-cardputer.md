# Plan 005: Add the BLACKWAVE–Cardputer enrolled capability adapter

## Status
- Priority: P2; Effort: L; Risk: HIGH; Depends on: 004, 005; Category: integration/security; Planned at: `2f7fb05b`, 2026-09-07

## Why this matters
BLACKWAVE currently has nine device contract records and a mobile projection, while Cardputer has no fleet adapter. This plan adds optional participation while preserving Cardputer’s local loop and BLACKWAVE’s authority.

## Steps
1. Add a Cardputer adapter for `blackwave-device/2` status/capability events and the mobile projection’s identity fields. Expected: simulator fixtures validate exact device identity, backend, sequence reset, stale/offline, blocked and revoked states.
2. Register Cardputer only through BLACKWAVE enrollment and grants; discovery reports reachability only. Expected: unknown/local-only Cardputer cannot appear enrolled or execute fleet operations.
3. Add event deduplication, bounded queues, reconnect and watchdog-safe command acknowledgements. Expected: gateway restart, duplicate/reordered events, concurrent reads and pending revocation produce no duplicate side effects.

## Done criteria
BLACKWAVE+Cardputer is optional; Cardputer starts, saves and operates locally with gateway absent; all nine fixture classes have explicit status and evidence states; no physical pass is inferred.

## STOP conditions
Stop for missing exact unit identity, live RF, flashing, provisioning, or an authority escalation.
