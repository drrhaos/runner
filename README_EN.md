# Runner - Running Workout Tracking App

🇬🇧 English | [🇷🇺 Русский](README.md)

Android application for tracking running workouts with GPS tracking, maps, statistics, data export and import.

Package ID: `com.runner.academy`.

## 📸 Screenshots

<p align="center">
  <img src="screenshot/Workout.png" width="200" alt="Workout tracking" />
  <img src="screenshot/Plan.png" width="200" alt="My plan" />
  <img src="screenshot/SideBar.png" width="200" alt="Side menu" />
  <img src="screenshot/Settings.png" width="200" alt="Settings" />
</p>

## 📱 Description

Runner is a full-featured running app that allows you to:
- Track workouts in real-time using GPS
- View your route on an interactive map
- Follow **training plans** and base interval workouts
- Analyze workout statistics
- Save history of all workouts
- Export and import data (JSON, GPX, CSV)

## ✨ Key Features

### 🏃 Workout Tracking
- **Real-time GPS tracking** with route display on map
- **GPS signal indicator** — compact bars in a translucent circle (subtle on the map)
- **Automatic map centering** on current location
- **My location button** — same size as the GPS indicator; appears when the map is panned
- **Current location** marker on the map
- **Mode selection before start**: easy run → today’s plan workout (default when a planned day exists) → base workout templates
- **Colored interval scale** (warm-up / work / recovery / cool-down) with current status and progress
- **Spoken interval changes** when voice feedback is enabled
- **Countdown timer** before workout start (configurable 3–30 s)
- **Pause and resume** workout
- **Long press** stop button (3 seconds) to prevent accidental stops

### 📊 Workout Metrics
- Workout time (hours:minutes:seconds)
- Distance traveled (km)
- Average speed (km/h)
- Current and average pace (min/km)
- Calories burned
- Heart rate (bpm) — UI placeholder; sensor integration is planned

### 🗺️ Map
- **OpenStreetMap** (Mapnik) in light theme; **CARTO Dark Matter** basemap in dark theme
- Interactive map with zoom and scroll gestures
- Workout track as a red line; GPS gaps shown as dashed segments
- Automatic map orientation based on movement direction
- Smooth animation when centering

### 📈 Statistics and history
- View history of all workouts
- Detailed information about each workout (map, charts)
- Statistics by workout type
- **Favorite routes** — All / Favorites filter on the list
- **Edit** a workout and pick a route from saved workouts

### ⚙️ Settings
- User weight, height, age
- Gender (male/female)
- Unit system (metric/imperial)
- GPS accuracy (high/medium/low)
- Theme mode (light/dark/system)
- Auto-pause on stop
- Voice feedback
- **Start countdown** (3, 5, 10, 15, or 30 seconds)

### 📋 Training plans
- **My plan** — calendar with the active plan, day details, and “Choose training plan”
- **Training plans** — create plans; **base workouts** (templates with segments and intensity icons) live in this section; JSON import/export of plans with base workouts
- On tracking: pick “Plan: …” from the mode list (no separate toggle), colored segment scale, mark the planned day done after save

### 💾 Export and import
On **My Workouts**, the **+** FAB opens a speed dial:

**Export**
- **JSON backup** — full backup of all workouts (including `trackData`), file `runner_workouts_backup_*.json`
- **GPX (ZIP)** — archive of GPX files for workouts that have a track
- Single-workout **GPX** from the detail screen
- Statistics **CSV** export

**Import**
- **JSON backup** — including older `com.example.runner` / `export-all-workouts-example` builds (no `isFavorite`, no `after_gap` on points)
- **Folder of GPX files** — recursive import of `.gpx`

### 🎨 Interface
- Material 3 with an iOS-inspired look (system blue, grouped backgrounds, flat cards)
- Dark and light theme support
- Responsive design
- Intuitive navigation through side menu

## 🔥 Calorie Calculation

The app calculates calories burned based on a simplified formula that takes into account user weight and distance traveled.

### Calculation Formula

```
Calories = Distance (km) × Weight (kg)
```

**Examples:**
- With weight 70 kg and distance 5 km: `5 × 70 = 350 kcal`
- With weight 60 kg and distance 10 km: `10 × 60 = 600 kcal`
- With weight 80 kg and distance 3 km: `3 × 80 = 240 kcal`

### Features

- **User weight is considered**: The greater the weight, the more calories burned for the same distance
- **Distance proportionality**: Calories are directly proportional to distance traveled
- **Default value**: If weight is not specified in settings, 70 kg is used
- **Real-time updates**: Calories are recalculated on each GPS coordinate update

### Important

⚠️ **Note**: This is a simplified formula for approximate estimation. The actual number of calories burned depends on many factors:
- Individual metabolism
- Running speed
- Terrain (uphills/downhills)
- Weather conditions
- Physical fitness

For more accurate calculations, it is recommended to use specialized fitness trackers or apps with heart rate sensor integration.

## ⚙️ Application Logic

### Architecture

The app is built on **MVVM (Model-View-ViewModel)** architecture using the following components:

#### 1. **View (UI Layer)**
- `Fragment` - app screens (Tracking, Workouts, Statistics, Settings)
- `Activity` - main activity with navigation
- ViewBinding for connection with XML layouts

#### 2. **ViewModel**
- Manages business logic and UI state
- Uses Kotlin StateFlow for reactive data updates
- Independent of Android components (easily testable)

#### 3. **Model (Data Layer)**
- `Room Database` - local workout storage
- `WorkoutDao` - data access
- `Workout`, `TrackPoint` - data models

#### 4. **Service Layer**
- `WorkoutTrackingService` - background service for GPS tracking
- Works independently of UI (continues working when app is closed)
- Uses Foreground Service with notification

### Data Flow During Tracking

```
GPS → LocationCallback → GpsFilter → WorkoutTrackingService
                                    ↓
                            WorkoutSession (StateFlow)
                                    ↓
                            WorkoutTrackingViewModel
                                    ↓
                            Fragment (UI Update)
```

### GPS Tracking

#### GPS Data Filtering

The app filters GPS coordinates with deliberately soft thresholds (urban GPS is often noisy):

1. **Coordinate Validation**
   - Latitude/longitude range check (-90..90, -180..180)
   - NaN and infinite value checks

2. **Accuracy Filtering**
   - Maximum acceptable accuracy: **150 meters**
   - Points worse than 150 m are rejected

3. **Outlier Filtering**
   - Absolute jump between consecutive points: **800 meters**
   - Calculated speed between points with a ×1.75 margin over the workout-type cap
   - Instantaneous GPS `speed` is barely used (only absurd values ~180+ km/h)
   - Altitude: reject only extreme cliffs (noisy altitude does not break the track)

4. **GPS gaps**
   - After ~**20 s** without a fix, the next point re-anchors with no phantom distance (dashed on the map)
   - Near-duplicate points (&lt; 2 m) do not reset the gap timer

5. **Temporal Validation**
   - Rejection of points with earlier time
   - Expected distance floors on the workout speed cap (does not collapse when reported `speed = 0`)

#### Location Updates

- **Update interval**: 2 seconds (configurable)
- **Minimum distance**: 2 meters between points
- **Adaptive interval**: Update interval can change depending on movement speed

### Workout States

The app manages four workout states:

1. **NOT_STARTED** - workout not started
   - "Start" button is displayed
   - GPS indicator shows signal search status

2. **RUNNING** - workout is active
   - Real-time GPS tracking
   - Metrics update every second
   - Track display on map
   - "Pause" and "Stop" buttons available

3. **PAUSED** - workout on pause
   - Timer stops
   - GPS tracking is paused (points are not added to track)
   - Pause time is not counted in total time
   - "Resume" and "Stop" buttons available

4. **STOPPED** - workout stopped
   - All data is saved to database
   - Workout is available in history
   - Can be exported / imported (JSON, GPX, CSV)

### Background Service

`WorkoutTrackingService` provides continuous tracking:

- **Foreground Service** - works with persistent notification
- **Wake Lock** - prevents device sleep during workout
- **Automatic Wake Lock renewal** every 9 minutes
- **UI independence** - continues working when app is closed
- **Connection with ViewModel** through Binder and callbacks

### Map Operations

#### Automatic Centering

- Map automatically centers on current location
- Auto-centering occurs 2 seconds after user interaction ends
- When user interacts (scroll, zoom) auto-centering is disabled
- Centering button appears when map is shifted

#### Map Orientation

- Map automatically rotates based on movement direction
- Uses bearing (azimuth) from GPS data
- Orientation updates on each location update

### Data Storage

#### Workout Data Structure

```kotlin
Workout {
    id: Long
    date: Date
    distance: Float (km)
    duration: Long (ms)
    avgPace: Float (min/km)
    calories: Int?
    notes: String?
    type: WorkoutType
    trackData: String? (JSON with TrackPoint[])
    isFavorite: Boolean
}
```

#### TrackPoint

Each track point contains:
- Latitude and longitude
- Timestamp
- GPS accuracy
- Speed
- Altitude (if available)
- GPS gap flag (`after_gap`) — optional in older backups
- Point source (`source`) — defaults to GPS

#### Export and import

- **JSON backup** (`formatVersion = 1`): full snapshot for migrating between installs / package IDs
  - Compatible with exports from `com.example.runner` (`feature/export-all-workouts-example`)
  - Legacy tracks without `after_gap`/`source` are normalized on import
- **GPX**: standard fitness format (single workout or ZIP of all tracks)
  - Compatible with Strava, Garmin Connect, etc.
- **CSV**: statistics / workout list for spreadsheets

## 🛠️ Technologies

- **Language**: Kotlin
- **Minimum Android version**: 8.0 (API 26)
- **Target Android version**: 16 (API 36)
- **Architecture**: MVVM (Model-View-ViewModel)
- **Database**: Room Database 2.7 + Room Gradle Plugin, codegen via **KSP**
- **Maps**: OSMDroid (OpenStreetMap)
- **Location**: Google Play Services Location API
- **Asynchrony**: Kotlin Coroutines
- **Navigation**: Android Navigation Component
- **UI**: Material Design Components, ViewBinding

## 📦 Dependencies

Main libraries:
- `androidx.room:room-*:2.7.0` - database (compiler via KSP)
- `com.google.devtools.ksp` / Kotlin 2.0.21 - Room annotations
- `org.osmdroid:osmdroid-android:6.1.18` - maps
- `com.google.android.gms:play-services-location:21.0.1` - GPS
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` - coroutines
- `com.google.code.gson:gson:2.10.1` - JSON serialization
- `androidx.documentfile:documentfile` - folder import (SAF)

## 🚀 Installation

### Requirements
- Android Studio Hedgehog or newer
- JDK 17 or higher
- Android SDK 26+
- Gradle 9.3+

### Installation Steps

1. Clone the repository:
```bash
git clone https://github.com/drrhaos/runner.git
cd runner
```

2. Open the project in Android Studio

3. Sync Gradle dependencies

4. Connect an Android device or start an emulator

5. Run the app (Shift+F10 or Run button)

## 📱 Usage

### First Launch
1. On first launch, the app will request permissions:
   - Location access (required)
   - Notification permission (for background tracking)
   - Activity recognition permission (optional)

2. Fill in profile settings:
   - Weight, height, age, gender
   - This is necessary for calorie calculation

### Starting a Workout
1. Open **"Start Workout"** section from the side menu
2. Wait for a GPS signal (compact indicator in the map corner)
3. Select a **mode**: easy run, today’s plan workout (if any), or a base template
4. Press the **"Start"** button — the countdown will begin
5. After the countdown finishes, the workout starts automatically
6. During the workout you can:
   - View the map with route (dark basemap in dark theme)
   - Follow the interval scale when a plan/base workout is selected
   - See current metrics
   - Expand panel for detailed statistics
   - Pause the workout
   - Stop the workout (hold button for 3 seconds)

### Viewing Workouts
1. Open **"My Workouts"**
2. Optionally filter **Favorites**
3. Select a workout for details (map, charts)
4. From the detail screen: GPX, share, edit, favorite
5. Via **+** on the list: import / export all workouts (JSON or GPX ZIP)

### Migrating from old `com.example.runner`
1. In the old build, export a JSON backup (or GPX files)
2. Install `com.runner.academy`
3. **My Workouts → + → Import** and pick the JSON file or a GPX folder

### Settings
1. Open **"Settings"** section from the side menu
2. Configure profile parameters
3. Select app preferences

## 🗂️ Project Structure

```
app/src/main/
├── java/com/runner/academy/
│   ├── data/                    # Data models and Room DAO
│   │   ├── Workout.kt           # Workout model (+ isFavorite)
│   │   ├── TrackPoint.kt        # Track point / TrackData
│   │   ├── WorkoutDao.kt        # DAO for database operations
│   │   ├── WorkoutRepository.kt
│   │   └── WorkoutDatabase.kt   # Room database
│   ├── ui/
│   │   ├── tracking/            # Tracking screen
│   │   ├── workout/             # List / detail / add / edit
│   │   ├── trainingplan/        # Base workouts and plans
│   │   ├── statistics/          # Statistics
│   │   └── settings/            # Settings
│   ├── service/
│   │   └── WorkoutTrackingService.kt  # Background service
│   └── util/                    # Utilities
│       ├── GpxExporter.kt           # GPX export (single workout)
│       ├── GpxImporter.kt           # GPX import
│       ├── WorkoutGpxBulkExporter.kt # ZIP of all GPX
│       ├── WorkoutBackupFormat.kt   # JSON backup import/export
│       ├── TrainingPlanBackupFormat.kt # Plans + templates JSON
│       ├── TrackDataJson.kt         # Safe track parse (legacy)
│       ├── CsvExporter.kt           # CSV export
│       ├── GpsFilter.kt             # GPS point filtering
│       ├── OsmMapTiles.kt           # Light/dark map basemap
│       ├── OsmMapConfig.kt          # OSM User-Agent and cache
│       └── FormatUtils.kt           # Data formatting
├── res/
│   ├── layout/                  # XML layouts
│   ├── drawable/                # Icons and graphic resources
│   ├── values/                  # Strings, colors, dimensions
│   └── navigation/              # Navigation graphs
└── AndroidManifest.xml
```

## 🔧 Permissions

The app requires the following permissions:
- `ACCESS_FINE_LOCATION` - precise location for GPS tracking
- `ACCESS_COARSE_LOCATION` - approximate location
- `ACCESS_BACKGROUND_LOCATION` - background location
- `POST_NOTIFICATIONS` - workout notifications
- `ACTIVITY_RECOGNITION` - physical activity recognition
- `FOREGROUND_SERVICE` - background tracking service
- `INTERNET` - OpenStreetMap map loading

## 🧪 Testing

The project includes unit tests and instrumental tests:
- Unit tests for ViewModel and utilities
- Instrumental tests for UI components
- Code coverage tracked through JaCoCo

Run tests:
```bash
./gradlew test              # Unit tests
./gradlew connectedAndroidTest  # Instrumental tests
./gradlew jacocoTestReport   # Coverage report
```

## 📄 License

This project is licensed under the [MIT License](LICENSE).

See also [Privacy Policy](PRIVACY.md) and [Contributing Guide](CONTRIBUTING.md).

## 👥 Authors

- [DrHaos](https://github.com/drrhaos)
- [john-krasinski](https://github.com/john-krasinski)

## 🤝 Contributing

Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) for details.

Quick start:
1. Fork the project
2. Create a branch for a new feature (`git checkout -b feature/AmazingFeature`)
3. Commit your changes (`git commit -m 'Add some AmazingFeature'`)
4. Push to the branch (`git push origin feature/AmazingFeature`)
5. Open a Pull Request

## 📝 Changelog

Full version history (aligned with git tags): [CHANGELOG_EN.md](CHANGELOG_EN.md) · [Русский](CHANGELOG.md).

Current release: **`v.0.0.7`**.

## 🐛 Known Issues

- Heart rate is shown as a placeholder (`--`) — no BLE/Health Connect integration yet
- Changing `applicationId` (`com.example.runner` / `com.drrhaos.runner` → `com.runner.academy`) does not migrate data automatically — use **JSON/GPX export** on the old build and **import** on the new one

## 🔮 Future Plans

- [ ] Fitness tracker integration
- [ ] Social features (share workouts)
- [x] Training plans and base interval templates
- [x] Progress analysis with charts
- [x] Voice prompts during workout
- [x] Workout import (JSON backup, GPX)
- [ ] TCX / FIT import

## 📞 Support

If you have questions or issues, create an issue in the project repository.

---

**Happy running! 🏃‍♂️💨**

