# Room database schemas

Room exports database schemas here when `exportSchema = true`.

After installing the Android SDK, generate schemas with:

```bash
./gradlew :app:kaptDebugKotlin
```

Commit the generated JSON files under `com.drrhaos.runner.data.WorkoutDatabase/`.
They are required for `WorkoutDatabaseMigrationTest` in instrumented tests.
