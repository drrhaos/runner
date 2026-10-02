# Changelog

🇬🇧 English | [🇷🇺 Русский](CHANGELOG.md)

Change history for Runner. Version numbers match git tags `v.X.Y.Z` (release `versionName` = `X.Y.Z`).

Current release: **`v.0.1.4`**.

## Unreleased

- Location without Google Play Services: phone GPS only (`LocationManager`), no network location
- Tracking requires precise location; approximate-only gets a clear message
- Live GPS while the screen is on (fixes the "signal lost / ready" flicker)
- Updates install over the previous version: `versionCode` now grows with the version (all release APKs used to have code 1)
- No Google-encrypted dependency block in the APK (needed for F-Droid / IzzyOnDroid)
- Tests: golden backup of the current format and the full DB migration chain

## 0.1.4 — `v.0.1.4`

- More reliable turn detection; fixed GPS timeouts with the screen off
- Track cleaning extracted into its own module; tidier permission requests
- Track time rebuilt when a workout is edited
- Faster map tile rendering and theme application
- Manual release build trigger in CI

## 0.1.3 — `v.0.1.3`

- Same code as `v.0.1.2a1`, released as a regular version

## 0.1.2a1 — `v.0.1.2a1`

- Track starts from the pre-start position; GPS accuracy taken into account
- Battery saving: different GPS and notification profile with the screen off

## 0.1.2 — `v.0.1.2`

- Refreshed workout list cards
- Import of backups made by obfuscated release builds

## 0.1.1 — `v.0.1.1`

- Fixed navigation argument passing and JSON serialization in backups

## 0.1.0 — `v.0.1.0`

- An unfinished workout is restored after the app is closed
- Voice prompts and intervals run from the tracking service (no screen needed)
- More accurate pace and speed, track charts
- Safe Args navigation, internal architecture rework

## 0.0.9 — `v.0.0.9`

- Tracking mode selection and improved voice prompts
- Interval segments are saved with the workout
- More accurate GPS gap detection
- Portrait-only screen orientation

## 0.0.8 — `v.0.0.8`

- iOS-inspired Material 3 theme (palette, flat cards, system bars)
- Workout mode picker: easy run / today’s plan / base templates; plan selected by default when available
- Colored interval segment scale instead of a single progress bar
- Compact GPS and my-location controls; CARTO dark basemap in dark theme
- Softer GPS filtering (fewer false dashed gaps on valid tracks)
- Intensity icons for templates and plans
- Template creation wizard and segment value pickers; route preview in the list
- GPS accuracy and auto-pause removed from settings

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
