# Contributing to Runner

Thank you for your interest in contributing! This document explains how to set up the project and submit changes.

## Prerequisites

- Android Studio Hedgehog or newer
- JDK 17 (matches CI)
- Android SDK with API 36 and Build Tools 35.0.0
- Git

## Getting started

1. Fork the repository and clone your fork:

```bash
git clone https://github.com/drrhaos/runner.git
cd runner
```

2. Copy the SDK configuration template:

```bash
cp local.properties.example local.properties
```

Edit `local.properties` and set `sdk.dir` to your Android SDK path.

3. Open the project in Android Studio and sync Gradle, or build from the command line:

```bash
./gradlew :app:assembleDebug
```

4. Generate and commit Room schemas (first-time setup after SDK install):

```bash
./gradlew :app:kaptDebugKotlin
git add app/schemas/
```

Required SDK components (same as CI):

```bash
sdkmanager "platforms;android-36" "build-tools;35.0.0"
```

## Before submitting a pull request

Run these checks locally:

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Optional coverage report:

```bash
./gradlew :app:jacocoTestReport
```

Reports are written to `app/build/reports/`.

## Branching and commits

- Create a feature branch from `master`: `git checkout -b feature/my-change`
- Write clear commit messages in English or Russian
- Keep pull requests focused on one topic

## Release signing (maintainers only)

Release builds require a keystore. Copy the template and fill in your values locally (never commit secrets):

```bash
cp keystore.properties.example keystore.properties
```

For CI releases, configure these repository secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

## Code style

- Follow existing Kotlin and XML conventions in the project
- Prefer string resources over hardcoded UI text
- Match the MVVM structure: UI in fragments, logic in ViewModels, persistence in `data/`

## Reporting issues

Use GitHub Issues for bugs and feature requests. Include Android version, device model, and steps to reproduce when reporting bugs.

## Questions

Open a GitHub Issue or Discussion if something in this guide is unclear.
