# Release signing

Dolphy never ships a release signed with the debug key. `assembleRelease` fails
fast when no release keystore is configured.

## Configure with environment variables (CI)

| Variable | Meaning |
|---|---|
| `DOLPHY_KEYSTORE_FILE` | Path to the `.jks` file (absolute or relative to the repository root) |
| `DOLPHY_KEYSTORE_PASSWORD` | Keystore password |
| `DOLPHY_KEY_ALIAS` | Key alias |
| `DOLPHY_KEY_PASSWORD` | Key password |

Store these as encrypted CI secrets. Never commit them.

## Configure locally

Create `keystore.properties` in the repository root (already git-ignored):

```properties
storeFile=/absolute/path/to/dolphy-release.jks
storePassword=...
keyAlias=dolphy
keyPassword=...
```

## Create a release key (once, offline)

```bash
keytool -genkeypair -v \
  -keystore dolphy-release.jks \
  -alias dolphy \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Dolphy, O=Dolphy, C=SA"
```

Back up the keystore and passwords in a secure vault. Losing them prevents
upgrading installed users. Rotating the key requires a new application ID or
user reinstall.

## Verify a build

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```
