# Agent instructions — juicetext

Project memory for AI agents working in this repository. Follow these rules in
addition to `RELEASING.md`, which is the authoritative release checklist.

## GitHub Release notes format

- The release `name`/`--title` is `juicetext X.Y.Z`. GitHub already renders that
  title as the page heading.
- The notes body MUST NOT repeat the release name or version as a heading. Never
  start the body with `# juicetext X.Y.Z`, `## juicetext ... X.Y.Z`, or any
  heading that duplicates the release title.
- The first body line must be `## 更新内容`. Do not use `#` headings in the body.
- Required structure: `## 更新内容`, then `## 安装`, and optionally `## 验证`.
- Write the notes in Chinese and pass the file with `--notes-file`.
- See `RELEASING.md` → "Release notes format" for the full specification.

## Release policy

- Always follow `RELEASING.md`.
- Never commit APKs, keystores, credentials, `local.properties`, or build output.
- Every release needs one immutable commit, one annotated `vX.Y.Z` tag, the pushed
  tag, and a GitHub Release with the signed APK and `SHA256SUMS`.
