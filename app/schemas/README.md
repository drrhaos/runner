# Room database schemas

Room exports database schemas here when `exportSchema = true`
(configured via the Room Gradle Plugin `schemaDirectory`).

After installing the Android SDK, generate schemas with:

```bash
./gradlew :app:kspDebugKotlin
```

Commit the generated JSON files under `com.runner.academy.data.WorkoutDatabase/`.
They are required for `WorkoutDatabaseMigrationTest` in instrumented tests.

Current schema version: **6** (`intervalSegmentsJson` on workouts).

`1.json` was never exported by Room: it is reconstructed from `2.json` minus the
`trackData` column that `MIGRATION_1_2` adds (version 2 has only the `workouts`
table), so the migration tests can start from version 1. Its `identityHash` is
synthetic (not computed by Room); the helper overwrites it on upgrade. Do not edit the other files
by hand — regenerate them with KSP.
