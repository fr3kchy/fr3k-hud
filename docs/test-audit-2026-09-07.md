# fr3k-hud — Test Audit (2026-09-07)

Scope: 11 test files across `app/`, `core/`, `protocol/` test sources.
Style: ~95 % contract / source-lint tests, ~5 % true behavioural unit tests.
Total: 57 `@Test` functions across 11 classes. No `@Before` / `@After` lifecycle,
no mocking framework (no `mockk`, `mockito`, `Robolectric`, `Truth`), no
coroutine-test usage despite one test importing `runTest`.

---

## 1. Coverage map — what IS tested

### A. Build / release process (1 file, 9 tests)
- `BuildMetadataContractTest` — `app/build.gradle.kts` and `build.sh` parsed with regex:
  versionName == `0.4.15`, versionCode >= 415, semver shape, artefact naming,
  no hardcoded version literal, `sha256sum` present, `artifacts/` dir present.

### B. Source-lint / anti-pattern enforcement (5 files, ~17 tests)
These tests `readFile()` on `main/` source and assert *string presence/absence* —
they are static-analysis tests disguised as JUnit, not runtime tests.
- `NoBlockingUiContractTest` — `IntegrationsActivity.kt`, `Fr3kTerminalOverlay.kt`
  must not contain blocking-adapter calls inside click listeners or `onCreate`,
  must use `lifecycleScope` / `Dispatchers.IO`.
- `ShizukuStateReducerTest` (last 3 tests) — adapter/bridge/application source
  lint for `requestPermissions`, listener registration, `ShizukuBridge.start()`.
- `TermuxResultParserTest` (last 2 tests) — `TermuxBridge.kt` must not call
  `CountDownLatch.await`, must not use typed literal extras.
- `ShizukuCommandPolicyTest` — none source-lint (pure unit, see C).
- `TermuxCommandContractTest` — none source-lint (pure unit, see C).

### C. Pure-unit / behavioural tests (5 files, ~31 tests)
- `ShizukuStateReducerTest` (first 9 tests) — exercises `ShizukuStateReducer`
  state machine across all six plan-mandated states plus edge cases
  (idempotent binder receive, dead→restart, truncation-trumps-exit).
- `ShizukuCommandPolicyTest` — 11 tests on `ShizukuCommandExecutor.Operation.from`:
  allow-list (`GetPackageInfo`, `ListPackageSplits`, `InstallApprovedApk`,
  `ReadSystemSetting`), deny-list (`Shell`, `UninstallTestFixture` for non-fixture
  targets, `WriteSystemSetting`, restricted categories), `Denied.reason`
  non-blank invariant.
- `TermuxCommandContractTest` — 9 tests pinning magic strings
  (`PACKAGE`, `SERVICE`, `ACTION`, six `EXTRA_*` constants), uppercase/camelCase
  rule, `CommandSpec` argument preservation, empty/blank validation throws.
- `TermuxResultParserTest` (first 7) — outcome classification: OK, FAILED,
  TRUNCATED, missing-bundle defensive fallback, stderr-from-errmsg fallback,
  exit-zero-with-warning is FAILED, truncation beats non-zero exit.
- `TermuxResultParserTest` (duplicate delivery) — `TermuxBridge.ResultSlot`
  first-write-wins + `onSecondDelivery` hook fires for ignored dups.
- `AdapterTests` — `MorphePatchRepository`: JSON parse, version match,
  fingerprint verify. ONE test method asserting 6 things — see anti-patterns.
- `CapabilityRegistryTest` — register/unregister, `hasAll`/`hasAny`,
  tier filtering, `missingFor` diff.
- `CommandRegistryTest` — register+filter by capability, unregister by plugin,
  fuzzy search.
- `UrlSanitiserTest` — strip UTM / fbclid / gclid, preserve YouTube `v`,
  unparseable input pass-through, empty query.
- `Fr3kEnvelopeTest` — kotlinx.serialization round-trip + `agentAsk` factory.

---

## 2. Coverage gaps — what is NOT tested

### Untested production code (entire classes / modules with zero JVM tests)
- `app/` main sources (production code, 30+ files, 0 behavioural tests):
  - `Fr3kOverlay`, `RadialMenuOverlay`, `Fr3kChatBubble`, `Fr3kMiniBrowserOverlay`,
    `HudOverlayService`, `OverlayManager`, `AutomationActionExecutor`,
    `InstallStateProbe`, all receivers.
  - All `ui/` activities: `MainActivity`, `CommandPaletteActivity`,
    `AskAboutThisActivity`, `SmartClipboardActivity`, `ScreenshotActivity`,
    `AutomationActivity`, `DiagnosticsActivity`, `DeveloperOverlayActivity`,
    `DeviceHandoffActivity`, `SettingsActivity`, `ShareReceiverActivity`,
    `LongPressRadialActivity`, `QuickHudActivity`.
  - Services: `Fr3kCoreService`, `MeshService`, `LocationService`,
    `SpecialPermissionLauncher`.
- `core/` main sources:
  - `Fr3kCore`, `ContextEngine`, `DeviceRegistry`, `DeviceIdentity`,
    `SecureStore`, `Fr3kPlugin`, `AutomationEngine`, `AiRouter`,
    `VoiceIntentPlanner`, `ApplicationProfiles`, `DiagnosticsExporter`,
    `AppSettings`, `Screenshots`, `ShareCommandsPlugin`,
    `DeviceHandoffAdapter`.
  - Plugin integration core (`core/integrations/*`): `HermesPlugin`,
    `HermesProvider`, `HermesAskCommand`, `OpenCodePlugin`,
    `OpenCodeZenProvider`, `OpenCodeZenModelRegistry`, `AskOpenCodeCommand`,
    `GpsPlugin`, `SystemPlugin`, `BlackwavePlugin`,
    `BlackwaveBridgeClient`, `BlackwaveRoleManifest`,
    `share/OpenOnDeviceCommand`, `share/CleanUrlCommand`,
    `IntegrationState`.
- `protocol/` — `Agent.kt`, `Capability.kt` only the *constants* are referenced
  by tests; their behaviour (`Agent` types, tier rules) untested.
- `transport/` — entire module (`Fr3kTransport`, `TransportHub`,
  `HttpsTransport`) has zero tests.
- `ui/` module — entire module has zero tests.

### Untested branches of partially-tested code
- `UrlSanitiser` — no test for: IDN/punycode normalisation, scheme lowercasing
  on mixed-case `HTTP://`, query re-ordering preservation, fragment stripping,
  URL with `?` and `#` together, very long query, `mailto:`/`tel:`/non-http
  schemes, duplicate params (`?a=1&a=2`).
- `CapabilityRegistry` — no test for: duplicate `register()` collision
  semantics, `register` returning whether previously known, `unregister` of
  a single capability (only `unregisterAllByOwner`), empty-input `hasAll`/
  `hasAny`, behaviour after `unregister` of an unknown id.
- `CommandRegistry` — no test for: `search` ranking (only count + title
  contains), empty query, plugin override / duplicate-id behaviour,
  `register` return value.
- `Fr3kEnvelope` — no test for: schema-version rejection, missing required
  fields, very-large payload, custom serializer for non-primitive payload
  types, source/destination unknown routing, `timestamp` negative/zero.
- `ShizukuStateReducer` — happy-path events covered; **not covered**:
  - `PermissionResult` arriving from `Missing` / `ServerStarting` (out-of-order),
  - `BinderDied` arriving from `Missing` / `Denied`,
  - `InstallCheck(present = false)` *after* `Ready` (must not regress to
    `Missing`),
  - `OsProcessSeen(running = false)` (no event variant tested).
- `TermuxResultParser` — no test for: `innerBundlePresent = false` *with* an
  errorMessage (current test has empty errorMessage), `exitCode = -1`
  explicitly from the call site, `stdout` exceeding 50 000 without
  `errmsg` truncation marker (silent truncation assumption), Unicode
  encoding failure.
- `ShizukuCommandPolicy` — no test for: empty `arg`, `arg` containing
  shell metacharacters (`;`, `&&`, `|`), `arg` path-traversal (`../`),
  case-insensitive `request` matching (does `"getpackageinfo"` slip
  through?).
- `TermuxCommandContract.CommandSpec` — no test for: blank `workDir`,
  `sessionAction` outside the allowed set, `background = true` side
  effects, `path` outside `/data/data/com.termux/...` allow-list (if any
  exists), whitespace-only `arguments` entry.
- `MorphePatchRepository` — see anti-patterns below.

---

## 3. Anti-patterns found

### 3.1 Hardcoded baseline values that double as test data
`BuildMetadataContractTest` pins `"0.4.15"` and `415` as both the
expected-value AND the baseline constant. The test can never fail on
the baseline itself. Mitigation: acceptable here because the comment
explicitly says "bump both in the same commit". Severity: low — by design.

### 3.2 Source-lint masquerading as unit tests
At least 17 of the 57 tests are not unit tests at all — they `readFile()`
on production sources and check string presence. They give green checkmarks
in CI but cannot catch:
- logic bugs inside methods that contain the expected substring,
- renames (a renamed method that still semantically does the wrong thing
  passes the lint),
- the regex used to detect the bug regressing (e.g. a new
  `withContext(Dispatchers.Default)` slipping past the lint
  `withContext(Dispatchers.[A-Za-z]+)`).
Severity: medium — useful as guard-rails but inflate coverage metrics.

### 3.3 Brittle regex-based parsing
`NoBlockingUiContractTest` strips `lifecycleScope.launch { ... }` blocks
with a non-greedy regex `[\s\S]*?\}\s*\)` that **requires the closing `}`**
of the block to be followed by `\s*\)`. A trailing semicolon or a nested
lambda will silently fail to strip the block, leaving a false positive in
the outer "no blocking call outside scope" assertion. Same issue for
`withContext(Dispatchers.[A-Za-z]+)`. Severity: medium — false-positive
risk on legitimate refactors.

### 3.4 One-test-many-assertions
`AdapterTests.morphe_patch_loads_and_verifies` makes 6 assertions in
one `@Test`. Any failure is reported as a single test name; individual
cases (load, match-good, match-bad, verify-good, verify-bad) cannot be
identified in the report. Should be split. Severity: low.

### 3.5 Happy-path bias — but partially mitigated
The shizuku/termux suites do test failure modes (Denied, FAILED, TRUNCATED,
blank rejection). But:
- `UrlSanitiserTest` has no test for any input the sanitiser is *supposed*
  to reject or transform (just strip-list positives).
- `CommandRegistryTest.searchFuzzy` only asserts `size == 2` and "all titles
  contain URL" — does not verify ranking, ordering, or empty-query safety.
- `Fr3kEnvelopeTest.roundTrip` is a happy path; no malformed-JSON test.
- `CapabilityRegistryTest` does not test the *collision* case
  (registering the same capability from a different owner).
Severity: medium — happy paths bias but the failure paths in the integration
suites are genuinely covered.

### 3.6 No mocking, no Robolectric, no instrumentation
The whole suite relies on JVM-only production code. Anything touching
`Context`, `Bundle`, `Intent`, `SharedPreferences`, `LiveData`, the
Android SDK, or any I/O — including `TermuxResultReceiver`, the whole
`TermuxBridge` (which the test only *reads* as text), `ShizukuAdapter`,
`ShizukuBridge`, `InstallStateProbe`, all `ui/*` activities — has zero
runtime coverage. The test classes *themselves* admit this in KDoc:
"The reducer is the only piece that can be JVM-tested without booting
Android or the Shizuku AAR — everything else ... is exercised on a
physical device." That device-exercise path is not represented in this
suite. Severity: high for the integration layer; medium for the rest.

### 3.7 Imports unused or single-use
`CommandRegistryTest` imports `runTest` but never calls it — `execute`
in the fake command is `suspend` but no `runBlocking` or `runTest`
wraps the register/filter calls. The fake suspend function is never
invoked through `CommandRegistry.available` (which presumably filters
by capability, not by executing). Dead import. Severity: trivial.

### 3.8 Path-coupling in tests
`NoBlockingUiContractTest` and `ShizukuStateReducerTest` and
`TermuxResultParserTest` resolve source files via hardcoded
`/home/parrot/repos/fr3k-hud/...` (and one via `user.dir`). The
test will silently error in a CI sandbox that mounts the repo
elsewhere. Should use a Gradle test fixture or `${project.rootDir}`
property. Severity: medium for portability.

---

## 4. Summary scorecard

| Dimension | Grade | Why |
|---|---|---|
| Assertion density | A | Every `@Test` has at least one assertion (no empty tests). |
| Failure-path coverage | B- | Integration contracts test deny cases well; core utilities do not. |
| Mocking / isolation | D | None at all; integration code that needs Robolectric is uncovered. |
| Happy-path bias | B- | Mid; mixed. |
| Anti-patterns | C | Source-lint tests inflate coverage metrics; brittle regexes; single-test multi-assert; hardcoded paths. |
| Portability | C- | Hardcoded `/home/parrot/repos/fr3k-hud` paths. |
| Maintenance | B | Tests are clearly written, KDoc explains intent, but no shared helpers / fixtures. |

---

## 5. Top 5 recommended additions
1. `MorphePatchRepository` — split `AdapterTests` into ≥6 tests; add
   malformed-JSON, empty fields, fingerprint-case, unknown-version.
2. `UrlSanitiser` — add tests for fragment, scheme normalisation,
   non-http schemes, duplicate params.
3. `CommandRegistry` — add ranking/order test, empty-query test,
   duplicate-id collision test.
4. Add Robolectric for `TermuxResultReceiver.parseIntent` (currently
   "trivial enough that code review is sufficient" — review-only
   coverage is not test coverage).
5. Replace hardcoded `/home/parrot/repos/fr3k-hud` paths with
   `System.getProperty("user.dir")` + relative resolution (or a
   Gradle test fixture) so CI can run the suite from any checkout
   location.
