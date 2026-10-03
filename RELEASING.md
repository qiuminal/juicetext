# Release process

This document is the operational checklist for maintaining juicetext as a long-term project.

## Source history

Every distributed APK must map to one immutable Git commit and one annotated tag. Never overwrite a release tag. If released behavior must be reverted, restore the old tag on a new branch, increment the version, commit, and tag the new release.

Recommended flow:

```text
main
  └─ feature/<short-name> or fix/<short-name>
       └─ review and validation
            └─ merge to main
                 └─ tag vX.Y.Z
```

## Version rules

- `versionName` is user-facing and increases for every release.
- Android `versionCode` must strictly increase for in-place updates.
- A lower versionCode cannot replace a newer installed APK without uninstalling it.
- Production application ID and signing certificate must remain unchanged.
- Development builds may use descriptive version names, but they are not release tags.
- Update `build-logic/convention/src/main/kotlin/Versions.kt` and `buildVersionName` in `gradle.properties` together; the latter keeps APK and build-metadata versions identical to the release version.

## Signing rules

- Production application ID: `com.qiuminal.juicetext`.
- Required signing alias: `juicetext-release`.
- Expected certificate SHA-256: `e465a0e8af4cca9135dd9668aa9118f7d0b0f5d35fc401fc18b5f050a6892a6f`.
- Keep the keystore and credentials outside this Git repository.
- Maintain at least two encrypted offline backups of both files.
- Do not upload signing material to GitHub until a separately reviewed Actions-based release process is intentionally adopted.

## Validation before release

Verify all of the following:

1. Targeted unit tests pass.
2. Clean Gradle release build succeeds.
3. APK package is `com.qiuminal.juicetext`.
4. APK versionName and versionCode match the release tag and changelog.
5. APK certificate matches the expected release certificate.
6. No Fcitx plugin metadata, plugin manifest action, delete-packages permission, or Fcitx package query is present.
7. Core editor and TextMate assets are packaged.
8. Changed behavior is tested on a connected Android device and relevant logcat output is reviewed.
9. APK SHA-256 and release metadata are archived outside Git.

## Publishing

1. Confirm the working tree contains only intended changes.
2. Review tracked files for secrets and generated artifacts.
3. Commit the release source state on `main`.
4. Create an annotated immutable `vX.Y.Z` tag.
5. Push `main` and the tag.
6. Create a GitHub Release for the tag and attach the verified APK plus checksums, using the notes format below.
7. Never commit APK files, keystores, credentials, `local.properties`, or build directories.

## Release notes format

GitHub renders the release `name` (set with `--title`) as the page heading, so the
notes body must never repeat the release name or version as a heading. Set the
title to `juicetext X.Y.Z` and start the body with `## 更新内容`.

Required structure:

```text
## 更新内容

- <user-visible change>
- ...

## 安装

<download and install paragraph: APK filename, minimum Android version, package
name, versionCode, and which previous release it can upgrade>

## 验证            (optional)

<validation summary>
```

Rules:

- The first body line must be `## 更新内容`. Never start with `# juicetext X.Y.Z`,
  `## juicetext ... X.Y.Z`, or any heading that duplicates the release title.
- `--title` is the only place the release name/version appears as a title.
- Use `##` for section headings; do not use `#` in the notes body.
- Keep the `## 安装` section with the exact APK filename, minimum Android version,
  package name, versionCode, and upgrade path.
- Write the notes in Chinese, matching the existing releases.
- Save the same file as `release-notes.md` in the offline archive directory for the
  version, and pass it to `gh release create`/`gh release edit` with `--notes-file`.

Every future release must be published in this repository with its source commit, annotated tag, signed APK, and checksum file. A locally distributed APK alone does not complete a release.
