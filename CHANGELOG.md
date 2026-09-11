# Changelog

All notable changes to juicetext are recorded here. Release tags use the format `v<versionName>`.

## Unreleased

- Added a selection dialog for the default editor text size.
- Treat Pinch text-size changes as session-local: merely visiting editor options no longer overwrites the temporary size; choosing a size explicitly still applies the new default.
- Added per-tab URI-bound draft loading and fixed new tabs inheriting another tab's unsaved state.
- Added stable directory snapshots, file icons and metadata, external-change detection, manual light/dark theme selection, and paged large-file loading with TextMate syntax highlighting.
- Known limitation: syntax colors for a large file can appear about two seconds after its text becomes visible; reducing this initial TextMate latency is deferred to a later fix.
- Added focused unit coverage for editor state and text-size override behavior.

## [0.3.0] - 2026-09-09

- Added default editor font-size options and Markdown/TextMate assets.
- Fixed new-tab draft isolation and added the latest saved/renamed-file marker.
- Improved large-file loading and editor tab behavior.

## [0.2.4] - 2026-09-08

- Reduced theme and TextMate initialization flashes.

## [0.2.3] - 2026-09-08

- Added persistent manual dark/light theme selection, defaulting to dark.

## [0.2.2] - 2026-09-08

- Added file icons, metadata display, backup-file sniffing, and unsupported-file handling improvements.

## [0.2.1] - 2026-09-08

- Added text-file support classification and external-change behavior improvements.

## [0.2.0] - 2026-09-08

- Added stable file-browser navigation and directory snapshots.

## [0.1.9] - 2026-09-08

- Improved return-to-browser refresh behavior.

## [0.1.8] - 2026-09-08

- Improved cold-start behavior.

## [0.1.7] - 2026-09-08

- Shipped a focused editor hotfix.

## [0.1.6] - 2026-09-08

- Added constant-time dirty state, asynchronous drafts, discard semantics, and provider I/O off the main thread.

## [0.1.5] - 2026-09-08

- Restored the requested launcher icon configuration.
- Uses application ID `com.qiuminal.juicetext` and the long-term juicetext release certificate.

## [0.1.4] - 2026-09-08

- Replaced launcher resources with the provided Android icon resource pack.
- Added density-specific foreground, legacy, and round PNG resources.

## [0.1.3] - 2026-09-08

- Created the independent juicetext application from the specified Fcitx5 Android text-editor plugin baseline.
- Removed Fcitx plugin manifest metadata, intent filters, package probing, and plugin-base/data-descriptor coupling.
- Established the long-term release signing identity.
