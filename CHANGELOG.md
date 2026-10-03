# Changelog

All notable changes to juicetext are recorded here. Release tags use the format `v<versionName>`.

## Unreleased

## [0.3.4] - 2026-10-04

- Added a three-state dark mode (day → night → follow system), replacing the old light/dark toggle, with migration of the previous setting and a toolbar icon that reflects the current mode.
- Added "Set as home page": the file browser always starts from shared storage and can remember a chosen home directory.
- Added "New folder" to the file-browser menu and reordered the menu (new text document, new folder, set as home, settings).
- Added folder aliases, shown in green bold next to the folder name and stored only in app data without touching the file itself.
- Moved the editor Save action from the bottom key bar to the top app bar.
- Moved the autocomplete switch into Editor Options alongside text size, tab width, and font settings.
- Localized the completion popup's secondary label, which previously showed the hardcoded "Identifier", and reworked the popup item layout so the label stays clear of the popup's bottom edge at larger font scales.
- Fixed a 0.3.3 regression that made regular files such as `.txt` and `.md` show the "unsupported file" icon instead of the text icon.
- Fixed duplicated text when using IME voice dictation.
- Tightened the top toolbar icon inset to 12dp and the bottom key bar side padding to 16dp.
- Removed the "change root folder" action now that the browser root is fixed to shared storage.

## [0.3.3] - 2026-10-03

- Added Shizuku UserService access for browsing, reading, editing, and saving files under shared storage, including Android/data.
- Unified Shizuku and ordinary directory browsing and improved directory navigation responsiveness with connection reuse and cached listings.
- Added bounded remote file operations, path validation, external-change detection, and Debug/Release application ID separation.

## [0.3.2] - 2026-10-03

- Request all-files access on first launch, browse shared storage directly, and replace SAF opening and root selection with the built-in file browser.

- Added a "New text file" action to the file-browser menu that creates a `.txt` in the current folder and opens it for editing.
- Added a selection dialog for the default editor text size.
- Treat Pinch text-size changes as session-local: merely visiting editor options no longer overwrites the temporary size; choosing a size explicitly still applies the new default.
- Added per-tab URI-bound draft loading and fixed new tabs inheriting another tab's unsaved state.
- Added stable directory snapshots, file icons and metadata, external-change detection, manual light/dark theme selection, and paged large-file loading with TextMate syntax highlighting.
- Reduced initial syntax-highlighting delay for large files by analyzing a compact initial page before publishing colors.
- Improved YAML key and unquoted-string colors in light and dark themes and disabled costly identifier collection.
- Fixed syntax highlighting disappearing after backgrounding and returning to the editor: system UI-hidden trim callbacks are no longer treated as low-memory conditions.
- Added focused unit coverage for editor state, trim policy, and text-size override behavior.

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
