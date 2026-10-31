# Architecture

Dolphy is a single Android application (`:app`) that delegates reusable,
testable logic to small Kotlin/JVM modules. Everything below is verified against
the tree at `1.0.x` (`main`).

## Module map

| Module | Type | Responsibility | Tests |
|---|---|---|---|
| `:app` | Android app | Compose UI, navigation, feature screens, plugin host, local servers | `src/test` (Robolectric-free JVM tests) |
| `:core:model` | JVM | `HardwareDescriptor`, `Transport`, `SafetyProfile`, `Signal`, `LabSession` | in `:core:hal` |
| `:core:hal` | JVM | Device registry, capability index, lease manager, driver SPI | `DeviceRegistryTest`, `DeviceLeaseManagerTest`, `HardwareManagerTest` |
| `:core:security` | JVM + Android | AES-256-GCM envelope, `AndroidKeyStore` keys, secure prefs, local HTTP policy | `SecureEnvelopeTest`, `LocalHttpPolicyTest` |
| `:rootd` | JVM | Root operation model, wire protocol, audit log, `su` broker, `RootdService` | `RootdProtocolTest` |
| `:bridge:flipper` | JVM | Flipper Zero CLI adapter, USB CDC transport, hotplug monitor | `FlipperCliAdapterTest` |

`settings.gradle.kts` uses `FAIL_ON_PROJECT_REPOS`, so dependencies may only be
declared in `gradle/libs.versions.toml` and consumed through `libs.*`.

## Data flow

```
Compose screen ──► ViewModel (StateFlow) ──► feature engine ──► Android radio APIs
      │                                              │
      └──► HardwareRuntime ──► :core:hal (registry/leases) ──► driver ──► transport
                     │
                     └──► :bridge:flipper (USB CDC) / :rootd (su broker)
```

- **Hardware path.** `HardwareRuntime` owns a `HardwareManager` plus the Flipper
  `UsbMonitor`. Screens observe `DeviceRegistry` state (`DETECTED`, `READY`, …)
  and request a device through `DeviceLeaseManager`, which prevents two features
  from driving the same transport at once.
- **Privileged path.** `:rootd` never runs shell strings from UI code directly:
  callers build a `RootOperation`, `PrivilegedBroker` executes it with the user's
  consent and appends to `RootAuditLog`.
- **Plugin path.** `PluginManager` validates and persists plugin manifests,
  `PluginSignature` verifies the ECDSA P-256 signature over the canonical source,
  `PluginUrlPolicy` enforces HTTPS imports, and `PluginSession` runs the plugin in
  Rhino (JavaScript) or Chaquopy (Python) with `PluginPermissions` guarding every
  host API in `PluginDeviceApis` / `PluginAndroidApis`. Compose UI produced by a
  plugin is rendered by `PluginUiRenderer`, not injected into the host tree.
- **Local server path.** `KtorServerManager` (Netty) serves the QR/tools pages on
  the LAN; `LocalRequestPolicy` restricts origin/host, frame size, methods and
  redacts input from logs.

## UI structure

- `MainActivity` owns navigation state and the app-wide `DolphyTheme`
  (accent-driven tonal scheme, dynamic color on API 31+, expressive typography,
  UI scale, optional animated background).
- `DesignSystem.kt` + `ExpressiveComponents.kt` hold the shared composables;
  `Colors.kt` holds the palette tokens. Prefer these over inline literals.
- Feature packages live next to their engine (`ble/`, `bluetooth/`, `nfc/`,
  `network/`, `printer/`, `qr/`, `tvcast/`, `hid/`, `hardware/`, `plugin/`).

## Build and release

- Gradle 9.8.2, AGP 9.5.0, Kotlin 2.4.21, `compileSdk`/`targetSdk` 37,
  `minSdk` 26, JVM target 11 with core-library desugaring.
- Python support comes from Chaquopy 17.2 (`version = "3.11"`).
- `:app` packages four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) into a
  single universal APK; `jniLibs.useLegacyPackaging` keeps the Python `.so` files
  loadable on API 26–29.
- Release signing is mandatory (`docs/security/RELEASE_SIGNING.md`); the release
  workflow publishes a signed APK and a `.sha256`.

## Quality gates

| Gate | Where | Fails on |
|---|---|---|
| Unit tests | `Android CI → Build and test` | any test failure |
| Debug build | `Android CI → Build and test` | compile/resource errors |
| Detekt | `Android CI → Static analysis` | any finding outside the per-module baseline |
| Android lint (`lintVitalRelease`) | `Android CI → Lint` | error-severity lint issues |
| Secret scan (gitleaks) | `Security` workflow | committed credentials |
| Dependency updates | Dependabot | n/a (weekly grouped PRs) |

## Known boundaries

- Plugin isolation is a permission model, not an OS sandbox: JS/Python plugins
  run in the app process. Process isolation is tracked in
  `docs/security/P2-PLAN.md`.
- `:rootd` exposes a broker and a `RootdService`, not an AIDL-bound daemon yet.
- Cleartext HTTP is enabled app-wide because cameras, routers, printers and TVs
  only speak plain HTTP on the LAN. Android cannot express "RFC1918 only" in
  `network_security_config.xml`, so the restriction lives in
  `LocalHttpPolicy.requireLocal`, which every LAN HTTP call site now goes
  through; `network_security_config.xml` pins the trust store to system CAs.
- `MainActivity` is the largest file in the repo; it is under a detekt baseline
  entry and is a candidate for incremental extraction, not a rewrite.
