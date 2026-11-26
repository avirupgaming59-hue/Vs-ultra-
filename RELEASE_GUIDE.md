# VS Shell — Signed APK & GitHub Releases Guide

This project is pre-configured in `app/build.gradle.kts` to sign Release APKs via environment variables without storing any secrets inside source code.

## Current Build Limitations

- Firebase Gemini requires the owning Firebase project's `app/google-services.json` and matching App Check configuration. Those credentials are not present in this checkout, so Gemini reports that Firebase is not configured instead of attempting a broken request.
- The original release keystore for the existing `v1.0.3` APK is not present here, and no authorized replacement signing credentials were provided. Debug builds use the separate `com.aistudio.vsshell.krmxvp.debug` application ID, so they can be installed alongside the existing app without replacing it or erasing its data. They cannot update the release app. Do not publish a newly generated keystore as if it were the original signing key.
- If Firebase is configured for debug builds, register both the production application ID and the `.debug` application ID in the Firebase project and include both Android clients in `google-services.json`.

## On-Device Credentials and Backup

Saved master and profile passwords are encrypted with an Android Keystore AES-GCM key. The app database and settings file are excluded from Android cloud backup and device transfer because the device-bound key cannot be restored elsewhere. Profiles, chat history, and preferences therefore do not automatically migrate to a new device.

## How Gradle Release Signing Works in This Project

In `app/build.gradle.kts`:
- `signingConfigs.release` reads:
  - `KEYSTORE_PATH` (defaults to `./my-upload-key.jks` if unset)
  - `STORE_PASSWORD`
  - `KEY_PASSWORD`
  - `keyAlias = "upload"` *(fixed in Gradle config — your keystore alias must be `upload`)*
- `buildTypes.release` automatically applies `signingConfigs.release`.
- `gradle/wrapper/gradle-wrapper.properties` targets **Gradle 9.9.2**.
- The app uses compile SDK **37.2** and release version **1.0.4**.

---

## Step 1: Generate Your Release Upload Keystore (One-Time)

Run this command on your computer (with JDK installed) to create `my-upload-key.jks`:

```bash
keytool -genkeypair -v \
  -keystore my-upload-key.jks \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload
```

- When prompted, set your keystore password (`STORE_PASSWORD`) and key password (`KEY_PASSWORD`).
- **Keep `my-upload-key.jks` backed up safely.** It is ignored by `.gitignore` so it will never be committed to Git.

---

## Step 2: Generate a Signed Release APK Locally Using Gradle

Export your signing environment variables and run Gradle:

```bash
export KEYSTORE_PATH="$(pwd)/my-upload-key.jks"
export STORE_PASSWORD="your_store_password"
export KEY_PASSWORD="your_key_password"

./gradlew :app:assembleRelease
```

The signed APK is generated at:
`app/build/outputs/apk/release/app-release.apk`

*(Optional)* Verify the APK signature with Android SDK `apksigner`:

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

---

## Step 3: Configure GitHub Repository Secrets for the Release Pipeline

1. Base64-encode your keystore into a single line:
   - **macOS:** `base64 -i my-upload-key.jks | tr -d '\n'`
   - **Linux:** `base64 -w 0 my-upload-key.jks`
   - **Windows (PowerShell):** `[Convert]::ToBase64String([IO.File]::ReadAllBytes("my-upload-key.jks"))`
2. Open your GitHub repository → **Settings** → **Secrets and variables** → **Actions** → **New repository secret**.
3. Add these secrets:
   - `KEYSTORE_BASE64` — the base64 output from step 1
   - `STORE_PASSWORD` — your keystore password
   - `KEY_PASSWORD` — your key password for alias `upload`
   - `GOOGLE_SERVICES_JSON_BASE64` — base64-encoded `app/google-services.json` from the Firebase project

---

## Step 4: Trigger Automated GitHub Releases

The workflow at `.github/workflows/release-apk.yml` runs only when manually started via **Actions → Build & Publish Release APK → Run workflow**. Supply the intended release tag (for example `v1.0.4`); ordinary source pushes do not publish releases.

On each run, GitHub Actions:
1. Sets up **JDK 21**, Android SDK **37.2**, and **Gradle 9.9.2**.
2. Materializes the Firebase configuration from `GOOGLE_SERVICES_JSON_BASE64` and decodes `KEYSTORE_BASE64` to a temporary runner file.
3. Runs `gradle :app:assembleRelease` with the authorized signing credentials.
4. Publishes the requested tag with `vs-ultra-<tag>-release.apk` attached for direct download.

## Publish a Verified Debug APK When Release Credentials Are Unavailable

The **Verify Debug Build** workflow runs unit tests, lint, debug assembly, and API 36 instrumentation tests. Set **Publish the verified debug APK as a GitHub Release** to `true` and use the tag `v1.0.4-debug` to publish only after all checks pass. Alternatively, pushing a tag matching `v*-debug` triggers the same checks and publishes only if they pass. The release includes the APK and its SHA-256 checksum.

This debug build has application ID `com.aistudio.vsshell.krmxvp.debug`, is signed with a CI debug key, and can be installed alongside the production app. It cannot update the production package or any APK signed with the original release key. Firebase Gemini remains unavailable unless the owning Firebase configuration is supplied.
