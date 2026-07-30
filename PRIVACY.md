# Privacy Policy

Runner is an open-source Android app for tracking running workouts. This document describes what data the app processes and how it is used.

## Data collected and stored locally

The app stores the following data **only on your device**:

- Workout history (distance, duration, pace, calories, route points)
- User profile settings (weight, height, age, gender, preferences)
- App preferences (theme, language, GPS accuracy, voice notifications)

Workout routes are stored in a local Room database. No account or cloud sync is required.

## Location data

Runner uses GPS to track workouts. Location access is required for core functionality:

- **Foreground location** — while the tracking screen is open
- **Background location** — while a workout is active via a foreground service
- Location data is not transmitted to project servers. Export (GPX/CSV) happens only when you explicitly choose to share a file.

## Network usage

The app downloads map tiles from [OpenStreetMap](https://www.openstreetmap.org/) via OSMDroid. Tile requests include a User-Agent identifying the app. No personal workout data is sent with map requests.

Google Play Services Location is used for GPS updates. Google's privacy policy applies to that component.

## Permissions

| Permission | Purpose |
|------------|---------|
| Location (fine/coarse/background) | GPS workout tracking |
| Notifications | Foreground workout service notification |
| Activity recognition | Optional activity detection |
| Internet | Map tiles |
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

This app does not include analytics SDKs or advertising. Third-party libraries (OSMDroid, Google Play Services Location) operate under their own terms.

## Open source

Source code is available at https://github.com/drrhaos/runner. You can inspect how data is handled in the repository.

## Contact

For privacy questions, open an issue at https://github.com/drrhaos/runner/issues.
