# Release

## Versioning

- `app/build.gradle.kts` — `versionCode` (integer) + `versionName` (semver).
- Each module may have its own `version` (string) — kept in sync at release time.

## Artifacts

For each release:

```
FR3K-HUD-0.1.0.apk                    ← app/build/outputs/apk/release/app-release.apk
FR3K-HUD-0.1.0.apk.sha256             ← checksum
docs/                                  ← ARCHITECTURE, SECURITY, PROTOCOL, …
schemas/                               ← JSON schemas
examples/                              ← example device manifests, config
```

## Signing

V1 ships a debug-signed APK. Production releases require:

1. Generate a release keystro: `keytool -genkey -v -keystore release.keystore -alias fr3k -keyalg RSA -keysize 4096 -validity 10000`
2. Add `signingConfigs.release` to `app/build.gradle.kts`.
3. `./gradlew :app:assembleRelease`.
4. `apksigner verify --print-certs FR3K-HUD-0.1.0.apk`.

## Checksums

```bash
sha256sum FR3K-HUD-0.1.0.apk > FR3K-HUD-0.1.0.apk.sha256
```

## Release notes template

```
## FR3K HUD 0.1.0 — YYYY-MM-DD

### Added
- …

### Changed
- …

### Fixed
- …

### Security
- …

### Verified on
- Android 14 (API 34) emulator fr3k_default35
- Android 15 (API 35) emulator fr3k_default35
- …

### SHA-256
- app-release.apk: …
```

## Distribution

V1: direct APK + signed URL.
V2: Play Store internal testing track.
V3: Play Store production + F-Droid mirror (TBD).