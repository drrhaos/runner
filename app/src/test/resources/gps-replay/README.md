# GPS replay fixtures

Real tracks for `GpsReplayTest`. Every `*.json` file in this folder is replayed through the
live pipeline (`GpsLocationProcessor`) and the save pipeline (`TrackSanitizer`), and the
resulting distance and gap count are checked against the expectations in the file.

## Format

```json
{
  "workoutType": "EASY_RUN",
  "expectedDistanceMeters": 5000,
  "tolerancePercent": 3,
  "maxGaps": 0,
  "track": { "points": [ ... ], "total_distance": 0, "total_duration": 0,
             "avg_speed": 0, "max_speed": 0, "start_time": 0, "end_time": null }
}
```

- `track` is the workout's `trackData` exactly as stored by the app — copy it from a
  JSON backup (Workouts → + → Export JSON backup).
- `expectedDistanceMeters` is the **true** distance (measured route, stadium laps,
  official race distance), not what the app reported.
- `workoutType`, `tolerancePercent` (default 3) and `maxGaps` are optional.

## Limitation

The app stores the already filtered track, not the raw fixes, so fixtures from backups
check that the filters do not degrade a real track further. Raw-fix capture would make
them stronger (not implemented yet).
