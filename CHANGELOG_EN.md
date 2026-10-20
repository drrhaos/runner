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
- A Voice coaching screen replaces the single switch: two profiles — "Runs without segments" and "Intervals" (picked automatically by whether the workout has segments). A summary every 0.5 / 1 / 2 / 5 km or 1 / 5 / 10 / 15 min; what to say — time, distance, average pace, pace and average heart rate over the summary stretch, current heart rate. Summaries are off by default in intervals
- The voice speaks on time even without GPS (a summary "every N minutes", a timed segment ending in a tunnel); music is ducked while it speaks, lines don't pile up during a call; the segment beep is heard in silent mode too; no extra summary or repeated beep after an app restart
- Current pace is smoothed over 30 s; when GPS drops out it comes from steps and shows as "≈ 4:52"
- Heart rate zones: 5 zones from the maximum heart rate (220 − age or set by hand in settings). The zone on the heart rate tile, a "Time in zones" card and a zone-coloured heart rate chart in details; older workouts are recalculated when the maximum changes
- A segment target is a pace or a heart rate zone: "None / Pace / Zone" in the template editor, "Zone 4" in the 30-s warning
- Target cues in intervals: "Speed up, pace …" / "Slow down…" / "Above zone, heart rate …" at most every 30 s, and an indicator under the current segment; pace tolerance in settings (±10 s/km by default). The cue pace is averaged over 60 s, so a noisy GPS gives no false cues; no voice cues on a step-based pace
- Heart rate and cadence charts no longer start at zero
- Calories by formula: without heart rate the ACSM equation (distance, elevation gain, moving time); with heart rate and a birth date the Keytel formula (sex, age, weight). Live they leave out the climb, the save adds it; the edit form counts the same way. Older workouts are not recomputed
- TCX import (watches and running services): from picked files (several at once) or from a folder together with GPX. The track keeps heart rate and altitude; pauses the device left out are not counted in the time. A TCX without GPS (treadmill) or with a frozen GPS comes from its lap totals: distance, time, calories, no track
- Imports run in the background (WorkManager) in batches: they go on when the screen is left or the app hidden, with progress in a list card and a notification with Stop; after the process is killed the system runs them again, and workouts already saved are recognised and skipped. Workouts overlapping saved ones or each other in time (say, a GPX and a TCX of one run) stay out of the list, statistics and records and wait on the "Import conflicts" screen (a banner in My Workouts): skip, replace or keep both — one by one or all at once
- Imported files bring the name (GPX), the description in place of "Imported from…" (GPX, TCX), the device and the laps (TCX). A TCX takes its distance and time from the laps, as the device counted them; without a track its heart rate and cadence come from the laps too. Walks, rides, swims and other activities that are no run are skipped and counted in the result ("Skipped, not a run: N"); a file that names no sport counts as a run
- FIT import (files of sports watches and bike computers) with its own reader, no third-party library: from picked files or a folder together with GPX and TCX, the format told by the content. The track keeps heart rate, cadence and altitude, pauses follow the watch timer (auto or manual); distance, time, calories and laps are as the watch counted them; the device is its model name or maker. Treadmill and indoor runs have no track and keep the session heart rate and cadence. A damaged or cut file imports what could be read; multisport and other sports are skipped as "not a run"
- Archive import: `.gz` and ZIP, full exports of running services among them (nested ZIPs, `.fit.gz`, GPX in `.txt`) and the app backup inside an archive. Each file is told by its content, not its extension; photos, summaries and the rest are skipped silently, broken files count as errors. An archive is read as a stream, nothing unpacked to disk; progress shows "N processed", and after the process is killed the import goes on from the same place. A folder is read whole by content, no extension filter. "Import backup" takes a ZIP holding the backup too
- On the workout screen: the name from the file heads the card above the date, the device sits under the type with a watch icon, the note (the file description too) shows under the tiles, four lines until tapped; a "Laps" card (#, km, time, pace, HR) on the Basic tab when there are two laps or more, a treadmill run without a track included. The list shows the name in place of the type, and the workout form has a "Name" field

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
