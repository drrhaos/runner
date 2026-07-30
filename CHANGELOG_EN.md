# Changelog

🇬🇧 English | [🇷🇺 Русский](CHANGELOG.md)

Change history for Runner. Version numbers match git tags `v.X.Y.Z` (release `versionName` = `X.Y.Z`).

Current release: **`v.0.0.7`**.

## Unreleased

- iOS-inspired Material 3 theme (palette, flat cards, system bars)
- Workout mode picker: easy run / today’s plan / base templates; plan selected by default when available
- Colored interval segment scale instead of a single progress bar
- Compact GPS and my-location controls; CARTO dark basemap in dark theme
- Softer GPS filtering (fewer false dashed gaps on valid tracks)
- Intensity icons for templates and plans

## 0.0.7 — `v.0.0.7`

- Training plans and base workout templates (segments, My plan calendar)
- Import/export of plans together with base workouts
- Intervals on the tracking screen and spoken segment changes

## 0.0.6 — `v.0.0.6`

- Track storage/parsing refactor (`trackData`, legacy compatibility)

## 0.0.5 — `v.0.0.5`

- Import/export all workouts from the list screen (FAB speed dial)
- JSON backup and GPX ZIP; import JSON and GPX folders
- JSON compatible with `com.example.runner` / `export-all-workouts-example` backups

## 0.0.4 — `v.0.0.4`

- `applicationId` / namespace `com.runner.academy`
- Hardened tracking service, workout models and repository

## 0.0.3 — `v.0.0.3`

- More resilient GPS during signal loss (gaps, dashed connectors, LOST banner, manual distance)
- Favorite workouts, All / Favorites filter
- Edit with route picker; workout detail map fixes

## 0.0.2 — `v.0.0.2`

- Intermediate tracking/UI improvements (`dev_1`)

## 0.0.1 — `v.0.0.1`

- CI/CD: GitHub Actions (build and releases on `v*` tags)
