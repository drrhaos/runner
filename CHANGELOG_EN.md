# Changelog

🇬🇧 English | [🇷🇺 Русский](CHANGELOG.md)

Change history for Runner. Version numbers match git tags `v.X.Y.Z` (release `versionName` = `X.Y.Z`).

Current release: **`v.0.1.5`**.

## Unreleased

- False GPS signal (spoofing, jamming): teleport fixes, frozen coordinates and impossible speeds are dropped, no jumps are kept in the track
- A dropped stretch is still counted: steps × stride or the straight line, whichever is longer; the map draws it with its own dashes
- A stretch with no signal at all (tunnel, jamming, GPS switched off by battery saver with the screen off) is counted by steps; stopping before the signal returns keeps that distance
- "Signal unreliable" status in the indicator, the banner and by voice; the map hides the position meanwhile and the distance grows by steps
- Starting with a false signal is allowed: distance counts by steps, the track begins at the first good fix
- Steps for distance (step sensor): the permission is asked once at the first start, with a switch in settings
- Personal stride model: starts from an estimate by height and learns on the good GPS stretches of every run
- A warning when battery saver may cut GPS with the screen off
- If a workout cannot be restored in the background, a notification asks to open the app; the track is kept
- Standing still (a traffic light) no longer counts as a GPS loss
- GPS diagnostics record why each fix was dropped, and the steps and cadence
- Auto-pause (in settings, off by default): the clock stops at traffic lights and water stops by steps or GPS, while the track and distance keep recording; not used in interval workouts. Voice: "Auto-pause" / "Resuming"
- Moving time: the big timer, the list and the pace use it; details and statistics show the elapsed time too
- Elevation gain and loss: from the barometer when there is one, otherwise from GPS (marked approximate) or from the `<ele>` of an imported GPX. In details, on the chart and in statistics
- Average cadence: a tile and a chart in details, a line in statistics
- Personal records for 1 km, 5 km, 10 km, half marathon and marathon: a Records screen with the history of improvements, a congratulation after a run, "N records updated" after an import. A workout can be left out of records; routes with calculated time are left out automatically
- After the update, older workouts are recalculated in the background: elevation, cadence, previews and records appear within a few minutes
- The workout list no longer loads tracks — the route preview is stored separately
- The JSON backup carries moving time and the "don't count in records" flag (format 2; older backups still read)
- TalkBack: detail tiles read as one phrase, charts have summaries; the button on pause is called "Resume"
- Chart colours have more contrast in the light theme; tile captions are 12sp
- Tapping off the line on the elevation and segment charts no longer crashes the app
- Bluetooth LE heart-rate sensor (chest straps and other sensors with the standard Heart Rate profile): a Devices screen in settings — add, replace or forget a sensor, link status, battery level, current heart rate
- Heart rate during a workout: a tile with the value (grey when stale; "--" when the strap is off or the link is lost) and a link icon next to the GPS indicator; without a sensor the tile opens Devices. Voice: "Heart rate sensor lost" after 30 s without data and "Heart rate sensor connected" when it returns; silent on manual pause
- Heart rate is stored in every track point: average and maximum in details and on a chart, in GPX export and import (including other apps' tracks with heart rate)
- The sensor link survives drop-outs, Bluetooth being switched off and an app restart mid-workout; Android 12+ needs the "Nearby devices" permission, without it the workout runs without heart rate
- GPS diagnostics record sensor events (connection, drop-outs, battery, signal strength)

## 0.1.5a1 — `v.0.1.5a1`

- GPS no longer drops out for minutes with the screen off: fixes are delivered without batching, and the CPU stays awake for the duration of a workout
- Slightly higher battery use with the screen off, the price of a continuous track

## 0.1.5 — `v.0.1.5`

- Location without Google Play Services: phone GPS only (`LocationManager`), no network location
- Tracking requires precise location; approximate-only gets a clear message
- Live GPS while the screen is on (fixes the "signal lost / ready" flicker)
- Updates install over the previous version: `versionCode` now grows with the version (all release APKs used to have code 1)
- No Google-encrypted dependency block in the APK
- GPS diagnostics (in settings, off by default): raw points and per-system satellites are written to the workout's file; share it from the workout details
- The back gesture no longer stops a workout — only holding Stop does
- The workout list, statistics and route picker no longer load every workout at once: paging that drops pages scrolled past
- Tests: golden backup of the current format, the full DB migration chain, launchable instrumented tests

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
