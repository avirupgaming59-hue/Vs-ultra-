# VS Ultra Private

**Canonical private repository for the complete VS Ultra application source.**

This repository must remain **Private**. It is the home for the Android application, AI integration, Localhost and Workspace runtime, terminal implementation, Gradle/build setup, UI, data layer, resources, tests, and project documentation.

## Source layout

- `app/` — Android application, UI, AI, terminal, workspace, runtime, services, data, security, resources, and tests.
- `gradle/`, `gradlew*`, `build.gradle.kts`, `settings.gradle.kts` — build system and wrapper.
- `.github/workflows/` — CI and APK workflow definitions.
- `assets/`, `public/` — project assets.
- `docs/source/README.md` — original source repository README.
- `MIGRATION-STATUS.md` — exact transfer scope and known exception.

## Security and public companion

- Keep this repository Private.
- Do not publish private application code, credentials, signing materials, or internal runtime implementation to a public repository.
- A public companion, if created, must be a separate repository containing only owner-approved documentation and deliberately non-buildable samples.
- A submodule is only a pinned pointer; it does not mirror or hide repository contents.

## Source relationship

Initial source: `avirupgaming59-hue/Vs-ultra`  
Private destination: `avirupgaming59-hue/Vs-ultra-private-`  
Destination branch: `main`

The source repository has not been deleted or modified by this copy operation.