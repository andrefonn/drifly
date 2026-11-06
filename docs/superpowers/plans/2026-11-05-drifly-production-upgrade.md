# DriFly Production Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade DriFly to a production-grade, state-of-the-art driver copilot by refactoring the package identity to `com.drifly.app`, removing test buttons from the UI, adding R$/Hour profitability metrics, implementing Android 11+ silent screenshot OCR fallback via Google MLKit, adding destination Street View facade preview, integrating real-time GPS shift odometry to track empty displacement fuel costs, and verifying the build on the ADB-connected physical device.

**Architecture:** 100% native Android Kotlin architecture with Android Jetpack, Coroutines, SQLite local storage, MLKit on-device vision, Google Play Services Location, AccessibilityService with `takeScreenshot`, and WindowManager overlay keeping DriFly's signature dark glassmorphism UI identity intact.

**Tech Stack:** Kotlin 2.1+, Android SDK 35, Jetpack Core, Coroutines, Play Services Location, MLKit Text Recognition, SQLiteOpenHelper, Material Design 3.

**Spec:** Comparative analysis report in `comparativo_drifly_vs_rydex.md` and user requirements.

## Global Constraints
- Keep DriFly's visual identity, dark glassmorphism theme, fonts, and colors 100% intact.
- Official package and application identity must be `com.drifly.app` (replacing `com.rotacore.app`).
- Remove all simulation/test buttons from the UI.
- Verify every feature directly on the physical phone connected via ADB (`ZF523JBHZW`).
- Zero compilation errors, zero warnings that break the build, 100% passing unit tests.

---

### Task 1: Package Refactoring & Identity Migration (`com.rotacore.app` -> `com.drifly.app`)

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `app/build.gradle.kts`
- Modify: `app/src/main/AndroidManifest.xml`
- Move & Refactor: `app/src/main/java/com/rotacore/app/*` -> `app/src/main/java/com/drifly/app/*`
- Move & Refactor: `app/src/test/java/com/rotacore/app/*` -> `app/src/test/java/com/drifly/app/*`

- [ ] **Step 1: Update settings.gradle.kts and app/build.gradle.kts**
  - Change `rootProject.name = "DriFly"`
  - Change `namespace = "com.drifly.app"`
  - Change `applicationId = "com.drifly.app"`
  - Add `play-services-location` and `play-services-mlkit-text-recognition` dependencies.

- [ ] **Step 2: Move Kotlin source files to new package directory**
  - Move files from `app/src/main/java/com/rotacore/app` to `app/src/main/java/com/drifly/app`.
  - Update `package com.drifly.app` across all Kotlin files.
  - Move test files from `app/src/test/java/com/rotacore/app` to `app/src/test/java/com/drifly/app`.

- [ ] **Step 3: Update AndroidManifest.xml**
  - Update FileProvider authorities to `com.drifly.app.fileprovider`.
  - Update all intent action strings to `com.drifly.app.ACTION_*`.
  - Add required permissions (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`).

---

### Task 2: Remove Test/Simulation Buttons from Production UI

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/drifly/app/MainActivity.kt`

- [ ] **Step 1: Remove `cardSimulationHub` and `btnSimulateRide` from layout**
  - Remove lines 717-795 (`cardSimulationHub`, `btnSimulate99`, `btnSimulateUber`, `btnSimulateDelay5s`).
  - Remove line 1113 (`btnSimulateRide`).

- [ ] **Step 2: Clean up MainActivity.kt**
  - Remove click listeners for the deleted simulation buttons.
  - Retain `RideSimulationReceiver` so ADB commands (`am broadcast`) can still trigger test scenarios for development verification without cluttering user UI.

---

### Task 3: Implement R$/Hour Profitability Metric & Overlay Upgrade

**Files:**
- Modify: `app/src/main/java/com/drifly/app/RideOffer.kt`
- Modify: `app/src/main/java/com/drifly/app/ParsingEngine.kt`
- Modify: `app/src/main/res/layout/overlay_bubble.xml`
- Modify: `app/src/main/java/com/drifly/app/BubbleOverlayService.kt`
- Modify: `app/src/test/java/com/drifly/app/ParsingEngineTest.kt`

- [ ] **Step 1: Add `ratePerHour` to `RideOffer.kt`**
  - Add `ratePerHour: Double` property.
  - Add `destinationAddress: String?` property.

- [ ] **Step 2: Update `ParsingEngine.kt`**
  - Calculate `ratePerHour = if (duration > 0) Math.round((price / (duration / 60.0)) * 100.0) / 100.0 else 0.0`.
  - Extract destination address using address pattern matcher.

- [ ] **Step 3: Update `overlay_bubble.xml` and `BubbleOverlayService.kt`**
  - Add `tvRentabilidadeHora` alongside `tvRentabilidade` in the badge/header area.
  - Color-code `tvRentabilidadeHora`: >= R$ 45/h (Green `#31D477`), >= R$ 30/h (Yellow `#FBBF24`), < R$ 30/h (Red `#FF454D`).

- [ ] **Step 4: Update Unit Tests in `ParsingEngineTest.kt`**
  - Verify that `ratePerHour` calculates accurately for typical trips.

---

### Task 4: Hybrid OCR Screen Reading Fallback via `takeScreenshot()`

**Files:**
- Modify: `app/src/main/java/com/drifly/app/RotaAccessibilityService.kt`

- [ ] **Step 1: Integrate MLKit Text Recognition in RotaAccessibilityService**
  - Lazy initialize `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)`.

- [ ] **Step 2: Implement `tryOcrFallback` using `takeScreenshot` (Android 11+)**
  - When `traverseNodes` yields no offer in supported driver apps, trigger `takeScreenshot()`.
  - Convert hardware buffer to bitmap, process with MLKit.
  - Pass lines to `ParsingEngine.parseNodes()` and trigger offer handling.

---

### Task 5: Destination Street View & Facade Preview

**Files:**
- Create: `app/src/main/java/com/drifly/app/StreetViewHelper.kt`
- Modify: `app/src/main/res/layout/overlay_bubble.xml`
- Modify: `app/src/main/java/com/drifly/app/BubbleOverlayService.kt`

- [ ] **Step 1: Create `StreetViewHelper.kt`**
  - Geocode address with `Geocoder` to obtain `(lat, lng)`.
  - Query Street View metadata or prepare static street preview URL.
  - Calculate camera heading if metadata is available.

- [ ] **Step 2: Add Street View container in `overlay_bubble.xml`**
  - Add collapsible `layoutStreetViewPreview` with an `ImageView` / `WebView` and toggle button "Ver Fachada".

- [ ] **Step 3: Wire `StreetViewHelper` in `BubbleOverlayService.kt`**
  - If `offer.destinationAddress` is present, load and display the facade preview asynchronously.

---

### Task 6: Real-Time GPS Shift Odometry (Empty Driving Cost Tracker)

**Files:**
- Create: `app/src/main/java/com/drifly/app/TripOdometerManager.kt`
- Modify: `app/src/main/java/com/drifly/app/MonitoringService.kt`
- Modify: `app/src/main/java/com/drifly/app/PreferencesManager.kt`

- [ ] **Step 1: Create `TripOdometerManager.kt`**
  - Uses `FusedLocationProviderClient` with accuracy filtering (> 25m discarded, speed < 3 km/h filtered).
  - Accumulates total shift distance `totalShiftDistanceKm`.

- [ ] **Step 2: Integrate into `MonitoringService.kt`**
  - Start GPS tracking when monitoring service is running.
  - Update foreground notification with live shift distance and real fuel burned.
  - Broadcast `ACTION_LOCATION_UPDATE` to update MainActivity live.

---

### Task 7: Build, Test, Deploy & Verify via ADB

**Files:**
- Build: `gradlew.bat assembleDebug`
- Test: `gradlew.bat test`
- Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Run unit tests**
  - Verify all unit tests pass with Gradle.

- [ ] **Step 2: Build APK**
  - Verify APK compiles cleanly with zero errors.

- [ ] **Step 3: Deploy to physical device via ADB**
  - Uninstall old `com.rotacore.app` if necessary.
  - Install new `com.drifly.app`.
  - Launch app on device: `adb shell am start -n com.drifly.app/.MainActivity`.

- [ ] **Step 4: Execute functional verification via ADB**
  - Trigger simulation broadcasts.
  - Verify overlay appears with R$/km, R$/hora, and street view preview.
  - Verify monitoring service starts and stops cleanly.
  - Inspect `logcat` for any exceptions or issues.
