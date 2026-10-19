# Releasing Vidi

Releases are built and published by GitHub Actions (`.github/workflows/release.yml`) when a version
tag is pushed. The workflow runs the unit tests, builds the **`release`** build type (never a
debuggable one — those run ~18× slower, see `RUNTIME_PERF_PLAN.md` §E6), signs it with the release
key, verifies the signature, and attaches `Vidi-<version>.apk` plus a `.sha256` checksum to a new
GitHub Release.

## One-time setup: the release signing key

Every release must be signed with **the same key, forever**. Android only installs an update if it's
signed by the same key as the installed app; if the key is lost, nobody can ever update — they'd
have to uninstall (losing their history, subscriptions and playlists) and reinstall.

1. **Create the keystore** (on your own machine, never in CI). `keytool` ships with Android Studio:

   ```
   "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -genkeypair -v -keystore vidi-release.jks -alias vidi -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=vidiapp-dev"
   ```

   It asks for one password: modern keystores (PKCS12) use the same password for the store and the
   key. **`-dname` matters for privacy**: the name fields are embedded in the signing certificate,
   which anyone can read out of the APK — so this sets them to the project handle instead of
   prompting for your real name.

2. **Back it up** — `vidi-release.jks` and its password — somewhere safe and offline (a password
   manager attachment works). Never commit it; `*.jks` is gitignored.

3. **Add four repository secrets** — GitHub → this repo → Settings → Secrets and variables →
   Actions → *New repository secret*:

   | Secret | Value |
   |---|---|
   | `VIDI_KEYSTORE_BASE64` | the keystore file, base64-encoded (see below) |
   | `VIDI_KEYSTORE_PASSWORD` | the keystore password |
   | `VIDI_KEY_ALIAS` | `vidi` |
   | `VIDI_KEY_PASSWORD` | the same password again |

   To base64-encode the keystore straight to the clipboard (PowerShell):

   ```
   [Convert]::ToBase64String([IO.File]::ReadAllBytes("vidi-release.jks")) | Set-Clipboard
   ```

4. **Note the certificate fingerprint** so users can verify downloads:

   ```
   "C:\Program Files\Android\Android Studio\jbr\bin\keytool.exe" -list -v -keystore vidi-release.jks -alias vidi
   ```

   Publish the `SHA256:` line in the README. The release workflow also prints it (via
   `apksigner verify --print-certs`) in every run's log.

## Making a release

1. Build and test the change on a device with a non-debuggable build (`./gradlew installProfile`).
2. Tag the commit and push the tag:

   ```
   git tag v0.1.0
   git push origin v0.1.0
   ```

3. Watch the run under the repo's *Actions* tab. When it finishes, the release is live under
   *Releases*, with notes generated from the commits since the previous tag (editable afterwards).

**Version numbers** come from the tag: `vMAJOR.MINOR.PATCH` becomes versionName `MAJOR.MINOR.PATCH`
and versionCode `MAJOR*10000 + MINOR*100 + PATCH` (so `v0.1.0` → 100, `v1.2.3` → 10203). Minor and
patch must stay below 100, and every release needs a higher version than the last — Android
refuses to "update" to a lower versionCode.

To rebuild an existing release (e.g. after fixing the workflow), run the workflow manually from the
Actions tab and enter the tag; its assets are replaced.

## Installing a release over a development build

A build you installed from Android Studio (`debug` or `profile`) is signed with your local debug
key, not the release key, so Android won't let the release update it — uninstall the development
build first. That loses its local data; the release starts fresh.
