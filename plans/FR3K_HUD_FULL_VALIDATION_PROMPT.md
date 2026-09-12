# FR3K HUD — Full Validation AI Developer Prompt

```text
You are the principal Android engineer responsible for finishing, hardening, and fully validating:

this repository (the fr3k-hud checkout you are already in; no absolute host path is assumed)

Your mission is to test every surface of this application and implement only the missing behavior required by the documented product contracts. The result must be demonstrably buildable, testable, secure, and honest about what requires a real Android device, emulator, companion app, root/Shizuku, Termux, mesh hardware, GPS, overlay permission, or external Hermes service.

This is an existing Android multi-module Kotlin/Compose project. Do not rewrite it as a new app.

First run:

    cd <fr3k-hud repo root>
    git status --short
    git log --oneline -20
    find . -maxdepth 2 -type f | sort

Preserve all existing user changes and untracked files. Never use git reset, git checkout, git clean, destructive deletion, or broad replacement. Do not expose secrets or private keys. Do not install APKs, execute commands on a physical device, grant root, invoke Shizuku, control another app, send mesh traffic, or make network/AI calls without an explicit operator-controlled test setup.

Read before changing anything:

- README.md
- docs/ARCHITECTURE.md
- docs/PROTOCOL.md
- docs/CAPABILITIES.md
- docs/SECURITY.md
- docs/DEVICE_PAIRING.md
- docs/MESH.md
- docs/TERMUX.md
- docs/HERMES.md
- docs/DEVELOPMENT.md
- docs/TESTING.md
- docs/RELEASE.md
- docs/verification/PHONE_BASELINE.md
- docs/verification/PHONE_HERMES_MANIFEST.schema.json
- all Gradle build files
- all existing tests
- review/CHAT-BUBBLE-THOROUGH-REVIEW.md

Product surfaces to validate

Audit and test every surface, not only the existing JVM unit tests:

1. Application startup and lifecycle
   - cold start
   - process restart
   - background/foreground transitions
   - rotation and configuration changes
   - screen off/on
   - process death and restoration
   - battery saver and Doze
   - Android 12 through 15 compatibility

2. Main dashboard and HUD
   - dashboard rendering
   - floating orb
   - tap to open quick HUD
   - long-press radial menu
   - double-tap command palette
   - swipe-up fleet action
   - swipe-down screenshot action
   - drag, edge arc, snap-to-edge, hide, restore, and close target
   - overlay permission denied/granted/revoked
   - foreground-service notification behavior
   - multiple overlay windows and cleanup after stop

3. Chat bubble and assistant interaction
   - open/close
   - text entry
   - send flow
   - loading, success, offline, timeout, malformed response, and error states
   - keyboard/insets behavior
   - accessibility labels and semantics
   - state persistence across recreation
   - prevent duplicate submissions and stuck loading states

4. Command palette and automation
   - fuzzy search
   - capability filtering
   - unavailable capability hiding
   - command execution
   - confirmation for risky operations
   - automation creation, update, deletion, enable/disable, scheduling, and execution
   - invalid arguments, duplicate IDs, exceptions, cancellation, and concurrent execution
   - no arbitrary shell execution

5. Ask About This, sharing, browser, clipboard, and screenshots
   - incoming text share
   - URL share
   - invalid or empty share payloads
   - URL sanitisation and tracking-parameter stripping
   - browser overlay lifecycle
   - clipboard read/write behavior and privacy boundaries
   - screenshot permission flow
   - real MediaProjection implementation where the product contract requires it
   - explicitly label any remaining placeholder capture behavior

6. Integrations panel
   - Termux and Termux:API absent/present/granted/denied
   - Shizuku absent/present, binder unavailable/available, permission denied/granted
   - LSPatch discovery and announcement behavior
   - Morphe asset loading and SHA-256 verification
   - Vector/root detection and safe denial
   - GPS permission and provider states
   - Bluetooth and notification/overlay/special permissions
   - one-tap GRANT ALL flow
   - per-feature permission flow
   - immediate capability refresh after permission revocation
   - no crashes if any integration is absent

7. Hermes and AI routing
   - online Hermes
   - offline Hermes
   - timeout, invalid URL, TLS failure, malformed response, HTTP error, and cancellation
   - local fallback behavior
   - request correlation and retry policy
   - safe handling of untrusted response content
   - no credential leakage in logs, diagnostics, intents, screenshots, or exported reports

8. Device registry and pairing
   - valid and invalid manifests
   - schema validation
   - signature validation
   - identity mismatch
   - duplicate device IDs
   - revoked device
   - expired device
   - unreachable device
   - HTTPS transport failures
   - open-on-device success and denial
   - no trust based solely on alias, IP, BLE address, or QR content

9. Transport and protocol
   - envelope serialization round-trip
   - required fields
   - version mismatch
   - bad signatures
   - replay protection
   - timestamps and expiry
   - wrong sender/destination
   - unsupported capability
   - oversized payload
   - malformed JSON
   - TLS and certificate policy
   - cancellation and timeout behavior

10. Mesh abstraction
    - no adapter installed
    - MeshCore unavailable/available
    - Meshtastic unavailable/available
    - Reticulum unavailable/available
    - offline mode
    - permission denied
    - send failure and recovery
    - capability visibility must match actual availability
    - do not claim mesh support when only a stub exists

11. Diagnostics and export
    - diagnostics screen
    - integration status accuracy
    - export generation
    - redaction of secrets, tokens, private addresses, and sensitive location data
    - malformed or unavailable data
    - share/export failure

12. Settings, identity, and secure storage
    - first-run identity creation
    - stable identity across restart
    - DataStore persistence
    - secure-store failure
    - clear/reset behavior
    - invalid stored values
    - theme and preference persistence
    - no plaintext secrets

13. Android manifest and platform behavior
    - exported components
    - intent filters
    - share receiver
    - foreground service declarations and service types
    - notification behavior
    - runtime permissions
    - Android 12+ pending-intent rules
    - Android 13+ notifications
    - Android 14+ foreground-service restrictions
    - Android 15 behavior
    - network security configuration
    - backup/data extraction rules
    - release and debug differences

14. Accessibility and usability
    - TalkBack labels
    - touch targets
    - keyboard navigation where applicable
    - contrast
    - font scaling
    - dark theme
    - reduced motion
    - small and large screens
    - portrait and landscape
    - no blocking work on the main thread

15. Build, packaging, and release
    - debug APK
    - release APK configuration
    - R8/ProGuard compatibility
    - manifest merge
    - resource shrinking/minification behavior
    - APK signature verification
    - version metadata
    - SHA-256 sidecar
    - release metadata
    - reproducible generation where possible

Baseline commands

Run these before making changes and again afterward:

    ./gradlew --version
    ./gradlew clean test
    ./test.sh
    ./gradlew check
    ./gradlew :protocol:test :core:test :transport:test :app:test
    ./gradlew :app:assembleDebug
    ./build.sh

If `JAVA_HOME` or Android SDK paths are invalid, diagnose the environment and use the installed Java/SDK paths. Do not silently change the project to accommodate a broken shell environment. Record the exact failure and remediation.

Test requirements

Add or improve tests for every real defect found. Use Kotlin/JUnit tests for pure logic, Robolectric or Android test infrastructure for Android-dependent behavior where appropriate, and instrumented tests for lifecycle/UI/platform behavior. Add Compose UI tests for major user flows. Add contract tests for manifest, permissions, exported components, service declarations, and build metadata.

For every screen, service, receiver, adapter, plugin, transport, parser, registry, and command, test:

- success
- empty input
- malformed input
- unavailable dependency
- denied permission
- timeout
- exception
- cancellation
- process recreation
- duplicate invocation
- security-sensitive denial

Do not make tests pass by removing assertions, weakening security, changing expected behavior without documentation, or replacing real behavior with fake success messages.

Security requirements

Verify that:

- arbitrary shell commands cannot enter through intents, URLs, chat, automation, share data, or device messages
- privileged integrations are default-deny
- overlay, root, Shizuku, accessibility, GPS, Bluetooth, notification, and storage permissions are independently checked
- exported activities, services, and receivers validate their callers and inputs
- URL handling blocks unsafe schemes and unintended destinations
- HTTPS transport validates URLs, TLS, timeouts, and response sizes
- device manifests and envelopes are authenticated and freshness-checked
- revoked or expired devices cannot operate
- diagnostics and exports redact secrets and sensitive information
- logs never contain passwords, bearer tokens, private keys, cookies, or full credentials
- untrusted AI/Hermes text cannot become executable instructions without explicit user authorization
- no integration silently escalates privilege

Implementation boundaries

- Match the existing architecture and naming conventions.
- Prefer dependency injection and deterministic pure functions for testability.
- Keep UI state separate from side effects.
- Use lifecycle-aware coroutines.
- Avoid blocking the main thread.
- Preserve public protocol compatibility unless a documented migration is added.
- Keep unsupported V2/V3 functionality explicitly unsupported until genuinely implemented.
- Replace placeholders only when the required platform behavior and safe test strategy are clear.
- Never claim physical-device validation from JVM tests or APK compilation.

Physical/emulator validation

If an emulator or Android device is available, create a validation matrix covering Android API 31, 33, 34, and 35 where practical. Test both clean install and upgrade install. Capture commands, device API, app version, result, and relevant logs.

Test with integrations absent first, then enable each integration independently. Test permission denial and revocation after initial grant. Test offline, Wi-Fi-only, mobile-only, and network transition states. Test overlay and foreground-service behavior on a real device if possible.

Do not report unavailable hardware, root, Shizuku, Termux, mesh, GPS, Hermes, or MediaProjection as passed merely because mocks pass.

Final acceptance gates

The implementation is complete only if:

1. All existing and new unit tests pass.
2. `./gradlew check` passes.
3. `./test.sh` passes.
4. Debug APK builds successfully.
5. `build.sh` completes and verifies the APK signature/hash.
6. No Kotlin compiler warnings hide real defects.
7. Major Compose/UI flows have automated coverage.
8. Lifecycle, permission, intent, service, receiver, and integration behavior is tested.
9. Protocol, transport, pairing, identity, and security denial paths are tested.
10. Placeholder behavior is either implemented or explicitly documented as unsupported.
11. Documentation, manifest, schemas, and tests agree.
12. No secrets or sensitive data are committed or exposed.
13. Every unavailable physical/integration test is recorded as BLOCKED with the exact reason and required setup.
14. APK metadata, checksums, and release artifacts are internally consistent.

Final report

Provide:

- summary of implementation changes
- exact files changed
- all commands run
- pass/fail result for every command
- test count and coverage summary
- device/emulator matrix
- every surface tested
- security findings and fixes
- known limitations and intentionally unsupported features
- blocked physical/integration gates
- reproducible build and test instructions
- release recommendation: READY, READY WITH BLOCKERS, or NOT READY

Never claim “fully validated” unless every software surface has automated or repeatable evidence and every unavailable physical surface is explicitly listed as blocked.
```
