# Room database schemas

Room exports database schemas here when `exportSchema = true`
(configured via the Room Gradle Plugin `schemaDirectory`).

After installing the Android SDK, generate schemas with:

```bash
./gradlew :app:kspDebugKotlin
```

Commit the generated JSON files under `com.runner.academy.data.WorkoutDatabase/`.
They are required for `WorkoutDatabaseMigrationTest` in instrumented tests.

Current schema version: **5** (`iconKey` on templates and plans).
