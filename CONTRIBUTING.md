# Contributing to Dolphy

Thanks for helping. This document covers the loop you need to run before a pull
request can be merged.

## Prerequisites

- JDK 21 (CI uses Temurin 21).
- Android SDK with platform `android-37.0` and build-tools `37.0.0`.
- Gradle comes from the wrapper (9.8.2); do not install it separately.
- A device or emulator on API 26+ for anything touching radios.

`local.properties` must point at your SDK:

```properties
sdk.dir=/path/to/Android/Sdk
```

## Build and test

```bash
./gradlew :app:assembleDebug                 # APK
./gradlew :app:testDebugUnitTest \
          :core:model:test :core:hal:test \
          :core:security:test :rootd:test \
          :bridge:flipper:test               # unit tests (all modules)
./gradlew detekt                             # static analysis
./gradlew :app:lintVitalRelease              # Android lint (needs a keystore)
```

Release builds need `DOLPHY_KEYSTORE_FILE`, `DOLPHY_KEYSTORE_PASSWORD`,
`DOLPHY_KEY_ALIAS` and `DOLPHY_KEY_PASSWORD` (or `keystore.properties` for local
use, which is git-ignored). See `docs/security/RELEASE_SIGNING.md`.

## Code style

- Kotlin official style, 4-space indentation, trailing commas where the
  formatter would add them.
- `detekt` is the gate: `config/detekt/detekt.yml` holds the rules, and each
  module has a `detekt-baseline.xml` recording pre-existing findings.
  **New code must not add baseline entries.** If you fix legacy code, run
  `./gradlew detektBaseline` and commit the shrunken baseline with your change.
- Prefer `MaterialTheme.colorScheme` / `Colors.kt` tokens over inline
  `Color(0x…)` literals, and `stringResource` over hardcoded user-facing text.
- Every user-visible string goes in `res/values/strings.xml` and, when you can,
  in `res/values-ar/strings.xml`.

## Tests

- Pure logic (policies, parsers, signature checks, protocol codecs) belongs in
  the module's `src/test`, not `app`.
- A bug fix comes with a test that fails before the fix.
- Tests must not need a network, a device or a real keystore. Use fakes.

## Commits and pull requests

- One logical change per commit; the subject says *why*, the body says what.
- Keep the diff focused — no drive-by reformatting.
- Pull requests should describe the behaviour change, the verification you ran,
  and any risk (permissions, plugin ABI, storage migration).
- Do not include keystores, `local.properties`, tokens or captured user data.
  The `Security` workflow runs gitleaks on every push.

## Plugins

Plugin authoring is documented in `plugin-examples/README.md`. Plugins are
JavaScript (Rhino) or Python (Chaquopy) bundles with a manifest, an optional
`__permissions__` block, and an optional ECDSA signature. A plugin that asks for
fewer permissions is easier to review — and review is what users rely on.
