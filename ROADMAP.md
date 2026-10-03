# Roadmap

Planned work that is not part of the current release. Items here are design
notes, not commitments to a specific version.

## Built-in package-name → Chinese app-name alias seeds

### Motivation

`/Android/data/` is a flat list of opaque package directories
(`com.tencent.mm`, `com.tencent.mobileqq`, `tv.danmaku.bili`, …). The folder
alias feature already lets a user rename any directory for display only, but
the first visit is still intimidating: the user has to know what each package
is before they can label it.

This feature ships a curated mapping of mainstream app package names to their
Chinese names inside the app, and uses it to pre-fill a suggested alias the
first time such a directory is displayed. The user keeps full control: a
manually edited alias always wins, and any suggestion can be replaced or
cleared.

### Scope

- Bundle an offline mapping table (package name → display name) as an app
  asset. No network access at runtime.
- When rendering a directory whose entries look like package folders and which
  has no stored alias yet, show the suggested name after the package name,
  visually distinct from a user alias (for example a muted/parenthesised form)
  so it is clearly a hint rather than a committed label.
- Opening the alias editor pre-fills the suggestion, so accepting it is one tap.
- A user-saved alias (including an intentionally empty one) permanently
  suppresses the suggestion for that path.
- Keep the mapping data separate from the alias store
  (`folder_aliases` SharedPreferences) so updating the bundled table never
  overwrites user choices.

### Suggested data format

A single JSON asset keeps the data reviewable and easy to regenerate:

```json
{
  "version": 1,
  "apps": [
    { "package": "com.tencent.mm", "name": "微信" },
    { "package": "com.tencent.mobileqq", "name": "QQ" },
    { "package": "tv.danmaku.bili", "name": "哔哩哔哩" }
  ]
}
```

Lookup is exact-match on the directory name; directories that are not in the
table fall back to today's behaviour.

### Data collection

Collecting and maintaining the table is its own task:

- Seed with the most common apps in the target market, verified against each
  app's Play Store / official listing.
- Prefer Simplified Chinese names; add a Traditional Chinese column if the
  mapping is surfaced in `zh-rTW`.
- Re-verify package names before each release that ships a table update;
  package names occasionally change (for example following a rebrand).
- Record the source and the date checked for each entry so stale rows can be
  audited.

### Open questions

- Whether the suggestion should be shown immediately or only after the user
  taps into the alias editor.
- Whether to also cover `/Android/media/` and `/Android/obb/`.
- Whether to allow a user to opt out of suggestions entirely.
