# Changelog

All notable changes to Dolphy are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.4.1] - 2026-11-04

Dolphy 1.4.1 is a focused maintenance release that restores reliable IR
catalog refreshes from the **Flipper IRDB Sync** plugin. It preserves the
existing catalog, plugin, and user-data format from 1.4.0.

### Fixed

- **Flipper IRDB Sync startup:** the plugin bridge now accepts Python bound
  methods directly as `PyObject` callbacks for `irdbSync`. Starting a sync no
  longer fails with `Cannot convert method object to
  kotlin.jvm.functions.Function1`.
- **Sync callback delivery:** progress events, completion notices, and errors
  are delivered back to the installed plugin correctly, including the
  already-up-to-date path.

### Compatibility

- Kotlin and JavaScript callers keep the existing `(String) -> Unit` callback
  entry point; the new `PyObject` path is additive.
- No plugin reinstall or data migration is required. Installed plugins,
  settings, favorites, custom remotes, and backups remain compatible with
  1.4.0.

### Upgrade notes

- **Version:** `versionName` 1.4.1, `versionCode` 30401.
- Devices running 1.4.0 can update in place with the release-signed APK.

## [1.4.0] - 2026-11-04

Dolphy 1.4.0 adds native SmartTV casting and makes the optional Flipper-IRDB
update an installable plugin instead of a built-in card. The cast screen now
speaks Chromecast, DIAL, and DLNA/UPnP directly, while IR catalog refreshes go
through the same review, permission, and update flow as every other plugin.

### Added

- **Native SmartTV Cast:** a rebuilt cast screen discovers televisions and
  streaming devices through mDNS and SSDP, shows their advertised protocol and
  endpoint, and keeps the selected media ready for more than one receiver.
- **Chromecast CASTV2:** Dolphy connects to the receiver over TLS, launches the
  Default Media Receiver, loads the selected media through the media namespace,
  and sends a proper stop command when the session ends.
- **DIAL and DLNA/UPnP:** DIAL receivers are launched through their advertised
  application list, while DLNA televisions receive `SetAVTransportURI` and
  `Play` over the AVTransport control URL from their own device description.
- **Local media server:** a per-session NanoHTTPD server serves one selected
  video or image with byte-range support, a small browser fallback page, an
  operating-system-assigned port, and an unguessable path token.
- **Network-aware casting:** the server advertises a Wi-Fi or Ethernet LAN
  address, preferring those transports over VPN, cellular, or virtual links.
- **Flipper IRDB Sync plugin:** the official catalog now includes a 21st plugin
  that downloads `Lucaslhm/Flipper-IRDB` from GitHub, reports its status and
  progress, and refreshes the IR catalog used by the built-in remotes.
- **`irdb` plugin permission:** catalog sync is a distinct capability with its
  own label in plugin previews, the plugin manager, and the permission center.
- **Version-aware IR index cache:** the Flipper index records which catalog it
  was built from and is invalidated after a successful sync, so stale results
  cannot survive an update.

### Changed

- **IR catalog delivery:** the built-in sync card was removed from the Flipper
  IR home. Catalog refresh is now installed and managed through the plugin
  store, while favorites and custom remotes remain untouched.
- **SmartTV Cast receiver handling:** discovery no longer assumes one port or
  one protocol for every television; ports, application URLs, and DLNA control
  URLs come from each receiver's advertisements and device description.
- **Cast status and controls:** the screen now presents preparing, ready,
  connecting, playing, stopped, and failed states, with an explicit stop action
  and a refresh button for discovery.
- **Plugin catalog metadata:** plugin sizes and declared permissions now match
  the shipped files, including the new IRDB plugin and the corrected Radio Lab
  and Integration Hooks manifests.
- **Category view switch:** selecting the mode that is already active no longer
  toggles the list into the other mode.

### Fixed

- **Cast discovery:** Chromecast, DIAL, and DLNA receivers are resolved from
  their advertised services instead of a shared hard-coded port.
- **Cast playback:** media URLs are served from the phone with range requests,
  which lets televisions seek and resume correctly.
- **Cast lifecycle:** changing the selected video, leaving the screen, and
  stopping playback now close the active session and the local server without
  leaving a receiver playing in the background.
- **BLE GATT Explorer:** connection events accept both numeric and named states,
  notification callbacks read the selected characteristic, and the disconnect
  action passes a proper callback.
- **BLE Asset Pack:** stopping an advertisement now waits briefly for the worker
  thread and clears its reference, preventing an orphaned advertiser.
- **Plugin File Browser:** delete confirmation is rendered as an actual dialog,
  and the browser returns to the file list after the operation.
- **IR Signal Lab:** repeating a pattern now refreshes the result instead of
  leaving the previous status on screen.
- **Plugin bridge:** GATT characteristics expose a formatted `propsStr` value
  alongside the raw property list for Python plugins.
- **Plugin permission labels:** the new `irdb` capability is translated and
  displayed consistently instead of falling back to the generic hooks label.

### Security

- **IR archive validation:** the sync engine accepts only HTTPS URLs on the
  GitHub hosts it expects, validates redirects, rejects archive entries outside
  `Flipper-IRDB-main`, and refuses blank, absolute, or traversal paths.
- **Bounded downloads and extraction:** IRDB sync enforces 256 MB compressed,
  512 MB extracted, and 50,000-file limits, checks for roughly 300 MB of free
  space, and installs the new catalog atomically so a failed sync cannot leave a
  partial database in use.
- **Local cast traffic:** all cast control requests pass through
  `LocalHttpPolicy`, keeping them on the local network, and the media server
  exposes only the selected file through a random per-session token.
- **Plugin consent:** IR catalog changes require the explicit `irdb` permission,
  which is shown during installation and can be reviewed in the permission
  center.

### Upgrade notes

- **Version:** `versionName` 1.4.0, `versionCode` 30400.
- No data migration is required. Settings, favorites, custom remotes, installed
  plugins, and existing backups remain compatible with 1.3.0.
- The bundled IR catalog remains available immediately after upgrading. Install
  **Flipper IRDB Sync** from the plugin store only when an online refresh is
  wanted; the first sync downloads about 21 MB and replaces only the synced copy.
- SmartTV Cast requires the phone and television to be on the same local
  network. It does not request a storage permission; the selected file is copied
  into app-private cache for the duration of the session.
- The old built-in IR sync card is intentionally gone. Catalog status and sync
  controls now live in the plugin's Infrared module.

## [1.3.0] - 2026-11-04

Dolphy 1.3.0 makes navigation and interface changes instantaneous across the
app. It removes the remaining motion from screen transitions, lists, expandable
panels, toasts, and plugin surfaces, and aligns every repository link with the
canonical project home.

### Changed

- **Zero-motion interface:** screen navigation, category paging, bottom-bar
  scrolling, list insertions, expandable settings, toast presentation, and NFC
  success states now render immediately instead of animating.
- **System transition suppression:** Dolphy disables Compose motion at the
  window recomposer, process animator scale, activity and window transitions,
  and Android predictive back, so the platform cannot reintroduce a transition.
- **Plugin surfaces:** plugin preview, plugin host, and safe-mode notices now
  use fixed, immediately visible panels with explicit back and dismiss handling.
- **Picker presentation:** picker surfaces use consistent border widths and
  clipping, and the collapsed expandable picker no longer draws a ripple,
  keeping selection changes consistent with the reduced-motion interface.
- **Repository links:** the in-app GitHub link, plugin catalog download URLs,
  plugin examples link, and documentation site now point to
  `Alaa91H/Dolphy-App`.

### Fixed

- Back navigation from the plugin preview and plugin host screens no longer
  plays a sheet-dismissal animation or leaves an intermediate frame visible.
- Back and outside-tap handling now dismiss the fixed plugin preview panel
  consistently.
- The safe-mode notice and plugin host sheet keep their content scrollable on
  small screens while preserving navigation-bar insets.

### Upgrade notes

- **Version:** `versionName` 1.3.0, `versionCode` 30300.
- No data migration is required. Settings, installed plugins, backups, and
  plugin data remain compatible with 1.2.0.
- Motion is disabled inside Dolphy only; system-wide animation settings are not
  changed.

## [1.2.0] - 2026-11-04

Dolphy 1.2.0 adds a complete, portable backup and restore workflow for all
durable app-managed data, including installed plugins, their settings, SQLite
databases, and custom files.

### Added

- **Complete backup and restore:** a new Settings card creates and restores a
  single JSON backup through the Storage Access Framework, so no storage
  permission is required.
- **Full data coverage:** backups include every `SharedPreferences` file except
  the internal backup history, installed plugin sources and metadata, plugin
  settings (`plugin_prefs_*`), plugin data, permission vetoes, `filesDir`
  content, app-specific external files, SQLite databases, and WebView storage.
- **NFC scan history:** the Room database `nfc_scans.db` and its SQLite sidecar
  files are captured. A WAL checkpoint runs before collection, and the database
  is closed before an in-place restore.
- **Plugin completeness:** enabled and pinned state, deleted-plugin markers,
  plugin capabilities, and all plugin-created files are preserved.
- **Restore controls:** the UI previews the backup date, settings count, plugin
  count, file count, and size before replacing local state.
- **Localization:** the complete backup and restore experience is available in
  Arabic, English, and Russian.

### Security

- Backups carry a SHA-256 checksum over the canonical JSON payload; modified or
  corrupted files are rejected before any data is changed.
- Restore paths are canonicalized and checked against their roots, blocking
  absolute paths, `..` traversal, and preference names that escape the app's
  private storage.
- Plugin signatures are verified during inspect and restore. Invalid signatures
  are rejected, while unsigned or unknown-key plugins require explicit user
  consent before restore.
- Backup payloads are limited to 256 MB and 20,000 files to avoid unbounded
  memory and storage use.

### Fixed

- Empty files are now valid backup entries. This is required for zero-length
  SQLite `-wal` and `-shm` sidecars created after a checkpoint.

### Upgrade notes

- **Version:** `versionName` 1.2.0, `versionCode` 30200.
- Backups containing database, WebView, or external-file roots require Dolphy
  1.2.0 or newer to restore. Older backups remain readable by 1.2.0.
- Restore replaces the current app state with the state captured in the backup
  and requires a restart. Plugins, settings, files, or database rows created
  after the backup are removed.
- The backup file is plain JSON and is not encrypted. Store it somewhere safe.
- Cache and code cache are intentionally excluded. Android runtime permissions,
  home-screen widget bindings, and the APK itself are owned by the system and
  are not part of the backup.

## [1.1.0] - 2026-11-04

Dolphy 1.1.0 delivers a unified category experience, contextual device
readiness, and a more expressive visual system.

### Added

- **Unified category hubs:** all eight categories now share a swipeable hub
  with hero summaries, service counts, search, pinned services, list and grid
  modes, and consistent navigation.
- **Scrollable bottom navigation:** the floating navigation bar can be dragged
  horizontally and reserves enough width for each label, automatically
  revealing the active category when it is off screen.
- **Contextual device readiness:** every category now probes the hardware and
  permissions it actually uses. Bluetooth, Wi-Fi, IR, NFC, camera, USB, and
  media readiness are surfaced directly in the category hero.
- **One-tap permission actions:** missing permissions and disabled radios expose
  compact, category-specific actions that request the required permission or
  open the relevant system settings.
- **AMOLED theme:** a fourth appearance mode with pure-black backgrounds for
  deeper contrast and reduced power use on OLED displays.
- **Inline appearance pickers:** theme and accent selection now use the same
  expandable, preview-rich picker pattern as language selection.

### Security

- **Plugin integrity:** `.dolphyplugin` packages can carry an ECDSA P-256
  signature over their canonical source. A signature that does not match the
  payload is always rejected, and unsigned or untrusted-key packages require an
  explicit opt-in that is now surfaced in the install preview.
- **Plugin transport:** imports are HTTPS-only. Plain-HTTP links are rejected
  rather than upgraded, redirects are followed manually so a downgrade cannot
  slip through, and the download policy is covered by unit tests.
- **Plugin WebView:** file and content access are disabled, mixed content is
  blocked, popups and autoplay are off, and navigation is restricted to HTTPS.
- Removed the unused `READ_EXTERNAL_STORAGE` permission.

### Changed

- **Category experience:** navigation now uses a horizontal pager, and the
  bottom bar scrolls instead of compressing all destinations into one row.
- **Status semantics:** ready and granted states use a dedicated success green;
  disabled, unavailable, permission-required, and busy states use red.
- **Permission flow:** runtime permissions are requested in context from the
  relevant category instead of being prompted automatically at app startup.
- **Device action chips:** permission and settings actions are compact,
  descriptive, and integrated into the category hero rather than shown as a
  separate card.
- **Appearance:** the settings screen and shared navigation surfaces use the
  refreshed typography, shape, and motion system, with AMOLED labels localized
  in Arabic, English, and Russian.
- **Local-network egress:** every LAN HTTP call site (router manager, LAN and
  camera scanning, IPP printing, TV cast, bandwidth test) now goes through
  `LocalHttpPolicy.requireLocal`, so a bug or a malicious plugin cannot point
  these features at a public host. `network_security_config.xml` pins trust to
  the system CA store, blocking interception through user-installed CAs.
- **Kotlin quality gate:** `detekt` runs on every module with a reviewed
  per-module baseline; new findings fail CI, legacy ones are recorded.
- All 18 hand-written dependency coordinates moved into
  `gradle/libs.versions.toml`.
- Removed the obsolete `android.dexOptions.javaMaxHeapSize` and
  `kotlin.jvm.target.validation.mode=ignore` build overrides; a full debug build
  was verified without them.
- CI now runs the unit tests of every module. The previous step executed
  `:app:testDebugUnitTest`, which had no sources, so no test ever ran.
- Added `SECURITY.md`, `CONTRIBUTING.md` and `docs/ARCHITECTURE.md`.

### Fixed

- Removed the automatic Bluetooth permission prompt shown on every launch;
  permission state is synchronized silently and refreshed on resume.
- Prevented bottom-navigation labels from being truncated by making the bar
  horizontally scrollable with a comfortable minimum item width.
- Kept category navigation, search, pinning, and list/grid mode available while
  device permissions or radios are unavailable.
- Corrected positive and negative status colors across the category hub and
  permission center, including permission rows and access badges.

### Upgrade notes

- **Version:** `versionName` 1.1.0, `versionCode` 30100.
- No breaking API or project-format changes are required for this release.

## [1.0.0] - 2026-10-30

Dolphy 1.0.0 establishes the stable release baseline for the plugin-enabled
build. It introduces the in-app plugin platform, local and URL plugin import,
private release signing, and a modern Android toolchain.

### Highlights

- **Plugin platform:** an in-app store, a 20-plugin official catalog, install
  and update flows, enable/disable controls, plugin sharing, and a safe mode
  that activates after repeated plugin crashes.
- **Plugin import:** install any `.dolphyplugin` file from local storage or
  from a direct URL, with a mandatory preview and consent step before install.
- **Private release signing:** release APKs are signed with a dedicated
  RSA-4096 keystore and built by GitHub Actions, so updates install in place.
- **Toolchain modernization:** Android Gradle Plugin 9.5, Gradle 9.8.2,
  Kotlin 2.4.21, and compileSdk 37.

### Added

#### Plugin platform

- In-app plugin store with the complete official catalog of 20 example
  plugins, covering Wi-Fi, BLE, classic Bluetooth, NFC, IR, USB, networking,
  device inspection, sensors, crypto, file browsing, and privileged shells.
- Plugin manager with installed-plugin list, one-tap install and update,
  update-all, enable/disable, pinning, deletion, and sharing.
- Plugin preview screen showing the manifest name, author, version, size,
  runtime, declared capabilities, and detected permissions before installation.
- Plugin permission model covering BLE Spam, Bluetooth, Wi-Fi, infrared, USB,
  network access, plugin files, downloads, root, Shizuku, shell commands, and
  DEX loading.
- JavaScript and Python plugin runtimes with plugin UI rendering, host screen
  and surface extension points, and plugin-to-app bridge APIs.
- Safe mode with a crash log and a user-visible recovery notice when a plugin
  repeatedly fails during startup.

#### Plugin import

- **Choose file:** opens the system document picker and accepts
  `.dolphyplugin` files from local storage.
- **Paste link:** accepts direct HTTP/HTTPS links, normalizes links that omit a
  scheme to HTTPS, downloads the plugin to a temporary cache directory, and
  opens the existing preview and consent flow.
- Import safeguards: 48 MB size limit, `.dolphyplugin` filename enforcement,
  sanitized cache filenames, and automatic cleanup of imports older than
  24 hours.
- Localized import UI in Russian, English, and Arabic.

#### Localization

- Complete Russian, English, and Arabic resources, with Russian as the default
  locale and an explicit locale configuration for Android 13 and later.
- Localized plugin catalog descriptions, plugin manager screens, security
  warnings, and import dialogs.

#### Release engineering

- Dedicated release signing configuration backed by GitHub Actions secrets;
  the keystore is never committed or uploaded as a build artifact.
- Signed release workflow for `v*` tags and manual dispatch, including APK
  signature verification and GitHub release publication.
- Release signing documentation covering secret setup, local builds, and
  keystore backup requirements.

### Changed

- Upgraded the Android toolchain to AGP 9.5.0, Gradle 9.8.2, Kotlin 2.4.21,
  KSP 2.3.13, and compileSdk 37.
- Updated core dependencies, including AndroidX Core 1.19.1, Activity Compose
  1.13.0, Lifecycle 2.11.0, Navigation Compose 2.10.2, Room 2.8.5, Coil 2.7.0,
  Media3 1.11.1, Nordic BLE 2.11.0, Ktor 3.6.1, Rhino 1.9.2, Chaquopy 17.2.0,
  Kotlin coroutines 1.11.0, and kotlinx-datetime 0.8.0.
- Split CI into parallel **Build and test** and **Lint** jobs to shorten
  feedback time and isolate failures.
- Replaced the full `lintDebug` CI gate with `lintVitalRelease`, which checks
  release-blocking issues without the multi-hour analysis cost of the full
  debug lint on this single-module project.
- Release version codes now include a compatibility offset so the 1.0.0 line
  can update existing 2.7.0 test installations in place. Version 1.0.0 uses
  versionCode 30000.

### Fixed

- Removed every build warning: no deprecated Gradle features, AGP options,
  manifest attributes, Room schema exports, or Kotlin/Compose deprecations
  remain in the CI logs. Legacy BLE/WiFi/NFC platform APIs that minSdk 26 still
  requires are suppressed at file level with the reason documented.
- Silenced the GitHub Actions Node.js 20 and `ubuntu-latest` migration warnings
  by moving to the Node 24 action releases and pinning `ubuntu-24.04`.
- Fixed Android SDK setup on CI runners by removing the obsolete default
  `tools` package from `android-actions/setup-android`.
- Removed 16 stale English-only strings that blocked `lintVitalRelease` and
  prevented release builds from completing.
- Fixed the CI lint job failing because release signing was not configured by
  provisioning a temporary, job-scoped keystore used only for lint analysis.
- Completed the localization cleanup and resolved inconsistent Arabic and
  English strings across the app.
- Resolved source compatibility issues exposed by the AGP 9 and Kotlin 2.4
  upgrade.

### Security

- Release APKs are signed with a private RSA-4096 key and are never signed
  with the Android debug key.
- Signing credentials are stored outside the repository and injected into CI
  through encrypted GitHub Actions secrets.
- Plugins are treated as executable user code with app-level privileges.
  Installation requires an explicit preview that lists the plugin's declared
  capabilities, and the app warns users to install only trusted plugins.
- Added and updated security documentation, including the threat model,
  P0 hardening notes, and release signing runbook.

### Build and CI

- CI installs Android platform 37.0 and build-tools 37.0.0 for both build and
  lint jobs.
- The release workflow verifies the generated APK with `apksigner` before
  uploading it and publishing the GitHub release.
- Added a CI-only temporary keystore for lint so the release variant can be
  analyzed on branches and pull requests without exposing production secrets.

### Upgrade notes

- **Version:** `versionName` 1.0.0, `versionCode` 30000.
- **In-place updates:** versionCode 30000 is higher than the previous 2.7.0
  test build (20700), so release-signed installations can update normally.
- **Debug builds:** a debug-signed APK cannot be installed over a release-signed
  APK. Uninstall the debug build once before installing a release build.
- **Keystore:** keep the release keystore and its credentials backed up. Losing
  them makes future in-place updates impossible.
- **Plugin size:** plugins larger than 48 MB are rejected by the importer.
- **URL imports:** links must point directly to a `.dolphyplugin` file; HTML
  download pages are not parsed.

### Known limitations

- Full `lintDebug` remains slow on this large single-module project; CI uses
  `lintVitalRelease` as the release gate.
- Plugin URL import does not follow HTML landing pages or extract links from
  them; use the direct file URL.
- The plugin catalog currently ships with the official example plugins; it does
  not yet support third-party catalog indexes.

### Documentation

- [`docs/security/RELEASE_SIGNING.md`](docs/security/RELEASE_SIGNING.md) —
  release keystore, GitHub secrets, and local signing instructions.
- [`docs/security/THREAT-MODEL.md`](docs/security/THREAT-MODEL.md) — security
  model and trust boundaries.
- [`docs/security/P0-HARDENING.md`](docs/security/P0-HARDENING.md) — hardening
  work and verification notes.
- [`plugin-examples/README.md`](plugin-examples/README.md) — plugin catalog and
  example descriptions.

[1.4.1]: https://github.com/Alaa91H/Dolphy-App/releases/tag/v1.4.1
[1.4.0]: https://github.com/Alaa91H/Dolphy-App/releases/tag/v1.4.0
[1.3.0]: https://github.com/Alaa91H/Dolphy-App/releases/tag/v1.3.0
[1.2.0]: https://github.com/Alaa91H/Dolphy-App/releases/tag/v1.2.0
[1.1.0]: https://github.com/Alaa91H/Dolphy-App/releases/tag/v1.1.0
[1.0.0]: https://github.com/Alaa91H/Dolphy-App/releases/tag/v1.0.0
