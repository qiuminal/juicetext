# juicetext / 就文本

Independent Android text editor derived from the Fcitx5 Android fx version text-editor plugin, see https://github.com/fxliang/fcitx5-android

- Application ID: `com.qiuminal.juicetext`
- Upstream baseline: `nightly-0.1.3-436-g45ff6b22-20260824-134725`
- Launcher: `FileBrowserActivity`
- Fcitx plugin registration and integration are intentionally removed.
- Default appearance: manually selected dark/light theme; it does not follow the system theme.

## Current development state

Current release: **0.3.4**. Download signed APKs from [GitHub Releases](https://github.com/qiuminal/juicetext/releases).

- Shared storage is browsed directly after granting all-files access; root folders are selected inside the app.
- Large text files use paged loading with TextMate highlighting and optimized YAML colors.
- Returning from the background preserves syntax highlighting.

- Pinch zoom changes text size only for the current editor session.
- Opening editor options and returning without choosing a size preserves the Pinch size.
- Selecting a concrete default size in editor options explicitly applies that size.

The remaining editor issues are tracked and will be fixed individually with device validation before release.

### Shizuku

Start Shizuku and grant the app permission. The normal file browser then uses the
Shizuku UserService automatically when entering protected shared-storage paths,
including `/storage/emulated/0/Android/data/...`. Files are edited and saved in
place; no directory selection or imported copy is required.

The service exposes directory listing, size/modification metadata, bounded 64 KiB
reads and writes, and file truncation. Paths are canonicalized and restricted to
primary shared storage. Directories with more than 512 entries require a narrower input path.
Access errors and a stopped or unauthorized Shizuku service are reported without
preventing ordinary file browsing. Actual access depends on the selected backend.
Device authorization, disconnect and Android/data access depend on the selected
Shizuku backend and its permissions.

## Project structure

Only the standalone editor application and its vendored TextMate implementation are included by Gradle:

- `plugin/text-editor`
- `plugin/text-editor/language-textmate`

Other upstream source directories remain for license and provenance purposes but are not included in `settings.gradle.kts`.

## Build and test

Requirements: JDK 17 and Android SDK 36.
The Shizuku-enabled application requires Android 7.0 (API 24) or newer.

On Termux, install its native `aidl` package and configure the local SDK build-tools
`aidl` executable to use it; the SDK's Linux x86 binary cannot run on ARM Android.
Shizuku API/provider 13.1.5 must be cached once before using `--offline` builds.

```powershell
.\gradlew.bat :plugin:text-editor:testDebugUnitTest
.\gradlew.bat :plugin:text-editor:assembleDebug
```

CI runs the same targeted unit tests and debug build. Release signing is intentionally not configured in GitHub; the long-term key and credentials stay offline.

## Version control and release policy

This is a long-lived project. Every releasable source state must be committed and tagged before its APK is distributed.

- Use short-lived `feature/*` or `fix/*` branches and protect `main` when appropriate.
- Keep one logical change per commit.
- Tag immutable releases as `v<versionName>`.
- Increase both `versionName` and Android `versionCode` for every installable update.
- Push release source and annotated tags to this repository for every release, and publish APKs and checksums through GitHub Releases.
- Production releases must keep package `com.qiuminal.juicetext` and the same long-term signing certificate.
- Never commit the signing key, passwords, local SDK paths, APKs, or generated build output.

See [`RELEASING.md`](RELEASING.md) for the operational checklist.

## License and provenance

The project retains the upstream licensing and copyright notices. See [`LICENSE`](LICENSE) and source-file headers for details.
