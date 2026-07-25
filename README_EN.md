# Runner - Running Workout Tracking App

🇬🇧 English | [🇷🇺 Русский](README.md)

Android application for tracking running workouts with GPS tracking, maps, statistics, and data export.

## 📸 Screenshots

<p align="center">
  <img src="screenshot/Workout.png" width="200" alt="Workout tracking" />
  <img src="screenshot/Run.png" width="200" alt="Active workout" />
  <img src="screenshot/SideBar.png" width="200" alt="Side menu" />
  <img src="screenshot/Settings.png" width="200" alt="Settings" />
</p>

## 📱 Description

Runner is a full-featured running app that allows you to:
- Track workouts in real-time using GPS
- View your route on an interactive map
- Analyze workout statistics
- Save history of all workouts
- Export data to GPX and CSV formats

## ✨ Key Features

### 🏃 Workout Tracking
- **Real-time GPS tracking** with route display on map
- **GPS signal quality indicator** with colored bars (red/orange/yellow/green)
- **Automatic map centering** on current location
- **Centering button** appears when map is shifted (yellow navigation icon)
- **Current location display** with blue circle marker
- **Workout type selection** from dropdown menu before start (Easy run, Tempo run, Interval, etc.)
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
- **OpenStreetMap** for map display
- Interactive map with zoom and scroll gestures
- Workout track displayed as red line
- Automatic map orientation based on movement direction
- Smooth animation when centering

### 📈 Statistics
- View history of all workouts
- Detailed information about each workout
- Statistics by workout type

### ⚙️ Settings
- User weight, height, age
- Gender (male/female)
- Unit system (metric/imperial)
- GPS accuracy (high/medium/low)
- Theme mode (light/dark/system)
- Auto-pause on stop
- Voice feedback
- **Start countdown** (3, 5, 10, 15, or 30 seconds)

### 💾 Data Export
- Export workouts to **GPX format** (compatible with Strava, Garmin, and others)
- Export to **CSV format** for analysis in Excel/Google Sheets
- Save complete trajectory with timestamps

### 🎨 Interface
- Material Design
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

The app uses intelligent GPS coordinate filtering to improve accuracy:

1. **Coordinate Validation**
   - Latitude/longitude range check (-90..90, -180..180)
   - NaN and infinite value checks

2. **Accuracy Filtering**
   - Maximum acceptable accuracy: **100 meters**
   - Minimum acceptable accuracy: **20 meters** (always accepted)
   - Accuracy above 100 m - point is rejected

3. **Outlier Filtering**
   - Maximum distance between points: **500 meters**
   - If distance exceeds 500 m - point is considered an outlier
   - Speed check (maximum 50 km/h for running)

4. **Temporal Validation**
   - Rejection of points with earlier time (temporal anomalies)
   - Calculation of expected maximum distance based on speed

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
   - Can be exported to GPX/CSV

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
}
```

#### TrackPoint

Each track point contains:
- Latitude and longitude
- Timestamp
- GPS accuracy
- Speed
- Altitude (if available)

#### Export

- **GPX**: Standard format for fitness apps
  - Includes metadata (date, workout type, calories)
  - Complete trajectory with timestamps
  - Compatible with Strava, Garmin Connect, etc.

- **CSV**: For analysis in spreadsheets
  - Separate files for each workout
  - Summary file with statistics

## 🛠️ Technologies

- **Language**: Kotlin
- **Minimum Android version**: 8.0 (API 26)
- **Target Android version**: 16 (API 36)
- **Architecture**: MVVM (Model-View-ViewModel)
- **Database**: Room Database
- **Maps**: OSMDroid (OpenStreetMap)
- **Location**: Google Play Services Location API
- **Asynchrony**: Kotlin Coroutines
- **Navigation**: Android Navigation Component
- **UI**: Material Design Components, ViewBinding

## 📦 Dependencies

Main libraries:
- `androidx.room:room-runtime:2.6.1` - database
- `org.osmdroid:osmdroid-android:6.1.18` - maps
- `com.google.android.gms:play-services-location:21.0.1` - GPS
- `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3` - coroutines
- `com.google.code.gson:gson:2.10.1` - JSON serialization

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
2. Wait for GPS signal (indicator will turn green)
3. Select **workout type** from the dropdown in the bottom panel
4. Press the **"Start"** button — the countdown will begin
5. After the countdown finishes, the workout starts automatically
6. During the workout you can:
   - View the map with route
   - See current metrics
   - Expand panel for detailed statistics
   - Pause the workout
   - Stop the workout (hold button for 3 seconds)

### Viewing Workouts
1. Open **"My Workouts"** section
2. Select a workout to view details
3. View the route on the map
4. Export the workout to GPX or CSV

### Settings
1. Open **"Settings"** section from the side menu
2. Configure profile parameters
3. Select app preferences

## 🗂️ Project Structure

```
app/src/main/
├── java/com/drrhaos/runner/
│   ├── data/                    # Data models and Room DAO
│   │   ├── Workout.kt           # Workout model
│   │   ├── TrackPoint.kt        # Track point
│   │   ├── WorkoutDao.kt        # DAO for database operations
│   │   └── WorkoutDatabase.kt   # Room database
│   ├── ui/
│   │   ├── tracking/            # Tracking screen
│   │   │   ├── WorkoutTrackingFragment.kt
│   │   │   └── WorkoutTrackingViewModel.kt
│   │   ├── workout/             # Workout list
│   │   ├── statistics/          # Statistics
│   │   └── settings/            # Settings
│   ├── service/
│   │   └── WorkoutTrackingService.kt  # Background service
│   └── util/                    # Utilities
│       ├── GpxExporter.kt      # GPX export
│       ├── CsvExporter.kt      # CSV export
│       ├── GpsFilter.kt        # GPS point filtering
│       └── FormatUtils.kt      # Data formatting
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

### Version 1.1
- Workout type selection from dropdown menu before start
- Countdown timer before workout start
- Configurable countdown duration in settings
- Movement direction calculated from last 10 track points
- Track polyline connects to current position
- Original location icon (blue circle with arrow)

### Version 1.0
- Initial release
- GPS workout tracking
- Map display with route
- Statistics and workout history
- Export to GPX and CSV
- User settings
- Background tracking service

## 🐛 Known Issues

- Heart rate is shown as a placeholder (`--`) — no BLE/Health Connect integration yet
- Changing `applicationId` from `com.example.runner` to `com.runner.academy` requires a fresh install for existing users

## 🔮 Future Plans

- [ ] Fitness tracker integration
- [ ] Social features (share workouts)
- [ ] Workout plans
- [x] Progress analysis with charts
- [x] Voice prompts during workout
- [ ] Import workouts from other apps (TCX, FIT, GPX)

## 📞 Support

If you have questions or issues, create an issue in the project repository.

---

**Happy running! 🏃‍♂️💨**

