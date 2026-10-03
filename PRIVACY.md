# Privacy Policy

Runner is an open-source Android app for tracking running workouts. This document describes what data the app processes and how it is used.

## Data collected and stored locally

The app stores the following data **only on your device**:

- Workout history (distance, duration, pace, calories, route points)
- User profile settings (weight, height, age, gender, preferences)
- App preferences (theme, language, voice notifications)

Workout routes are stored in a local Room database. No account or cloud sync is required.

## Location data

Runner uses GPS to track workouts. Location access is required for core functionality:

- **Foreground location** — while the tracking screen is open
- **During an active workout** — via a location foreground service (with a visible notification), including when the screen is off. The app does not request background location permission.
- Location data is not transmitted to project servers. Export (GPX/CSV) happens only when you explicitly choose to share a file.
- **GPS diagnostics** (off by default, Settings → "Record GPS diagnostics"): for each new workout the app keeps a file with raw GPS points and satellite signal summaries in its private storage. It is not part of app backups, is deleted together with the workout (recordings of unsaved workouts after a week), and leaves the phone only if you tap "Share GPS diagnostics" in the workout details. The file contains exact coordinates, including where the run started and ended.

## Network usage

The app downloads map tiles from [OpenStreetMap](https://www.openstreetmap.org/) via OSMDroid. Tile requests include a User-Agent identifying the app. No personal workout data is sent with map requests.

GPS updates come from the device's own GPS receiver through the Android `LocationManager`. The app does not use Google Play Services or network-based location.

## Permissions

| Permission | Purpose |
|------------|---------|
| Location (fine/coarse) | GPS workout tracking |
| Foreground service (location) | Keep recording during a workout, including with the screen off |
| Notifications | Foreground workout service notification |
| Physical activity (Android 10+) | Step counter and cadence, for distance when GPS is lost; asked once at the first workout, optional (Settings) |
| Internet, network state | Map tiles |
| Wake lock | Keep tracking active during workouts |

## Data export, import, and deletion

You can export workouts as:
- **JSON backup** (full restore, including routes)
- **GPX** (single workout or ZIP of all tracks)
- **CSV** (statistics)

You can import workouts from a **JSON backup** (including older `com.example.runner` exports) or a **folder of GPX files**.

To delete data, remove individual workouts in the app or clear app data in Android system settings.

## Backups

Android backup may include app preferences. Workout database backup behavior follows Android system backup settings configured in the app manifest.

## Third parties

This app does not include analytics SDKs or advertising. Third-party libraries (OSMDroid) operate under their own terms.

## Open source

Source code is available at https://github.com/drrhaos/runner. You can inspect how data is handled in the repository.

## Contact

For privacy questions, open an issue at https://github.com/drrhaos/runner/issues.
