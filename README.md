# MotionAlertSender

Android app that uses the phone’s accelerometer, GPS, and SMS to detect motion events, send alert messages with coordinates, and receive/respond to alerts — without any additional hardware.

- Car flavor: Detects motion, collects GPS, sends an SMS alert to a configured emergency number (with 5s coalescing to prevent spam).
- Ambulance flavor: Focused on receiving incoming SMS alerts, listing them with coordinates, and navigating to the location.

## Tech Stack
- Kotlin, Jetpack Compose (Material 3)
- Sensors: Accelerometer (via `SensorManager`)
- Location: `LocationManager` (GPS provider)
- SMS: `SmsManager` for sending; `BroadcastReceiver` for receiving
- Data persistence: Jetpack DataStore (Preferences)
- Build flavors: `car` and `ambulance`
- SDK: compileSdk=35, targetSdk=35, minSdk=26

## Key Features
- Motion detection with configurable sensitivity.
- Location acquisition and formatting (Google Maps link: `https://www.google.com/maps?q=<lat>,<lon>`).
- SMS queuing/coalescing: sends only one SMS in a 5-second window if multiple motion events occur.
- Incoming alert parsing and internal broadcast to update UI.
- Quick navigation to the location in Google Maps.

## Project Structure
- `app/src/main/java/com/example/motionalertsender/MainActivity.kt` — Motion detection, location, SMS queueing and sending, UI.
- `app/src/main/java/com/example/motionalertsender/AlertSmsReceiver.kt` — Receives SMS, parses coordinates/time, rebroadcasts internally.
- `app/src/main/AndroidManifest.xml` — Permissions and SMS receiver.

## Permissions
The app requests the following at runtime:
- `SEND_SMS` — To send alert SMS.
- `RECEIVE_SMS` — To process incoming alert SMS.
- `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` — To acquire GPS coordinates.

Grant these when prompted on first run. If you deny, you can enable them later in Android Settings → Apps → MotionAlertSender → Permissions.

## Prerequisites
- Android Studio (Giraffe/Koala+ recommended)
- Android SDK Platform 35 (target) and build tools
- A physical Android device is recommended (for real SMS/GPS). Emulators cannot send SMS to external numbers and often have limited GPS support.

## Build Flavors
This project defines a single flavor dimension `role` with two flavors:
- `car` (applicationIdSuffix `.car`, versionNameSuffix `-car`)
- `ambulance` (applicationIdSuffix `.ambulance`, versionNameSuffix `-ambulance`)

Choose the appropriate Build Variant in Android Studio:
1. Open Build Variants tool window.
2. Set `app` → `Active Build Variant` to `carDebug` or `ambulanceDebug` (or corresponding release variants).

## Step-by-Step: Run the App
1. Clone the repo
   - Using Android Studio: Get from VCS → paste repository URL
   - Or via terminal: `git clone https://github.com/sharath507/motion_alert_sender_app.git`
2. Open the project in Android Studio and let Gradle sync.
3. Select a Build Variant
   - Car mode: `carDebug`
   - Ambulance mode: `ambulanceDebug`
4. Connect a physical Android device (recommended) and enable USB debugging.
5. Run the app (Run ▶ or Shift+F10) on the device.
6. Grant permissions when prompted:
   - SMS send/receive and Location.
7. In Car mode
   - Set the Emergency Contact number (tap the card).
   - Adjust Sensitivity as needed.
   - Tap “START MONITORING”. Move/shake device to trigger a motion event.
   - After GPS fix, the app queues and sends one SMS (coalesced within 5s).
8. In Ambulance mode
   - Ensure the device can receive SMS.
   - Incoming messages containing a Google Maps link (`https://www.google.com/maps?q=LAT,LON`) will appear as alerts in the list.
   - Tap “GO TO LOCATION” to open Google Maps.

## How It Works (brief)
- Motion detection: `onSensorChanged` computes acceleration magnitude and compares with a user-defined `sensitivity`. Debounced with a 5s cooldown and a location-in-progress guard.
- Location: On trigger, `requestLocation` starts GPS updates; the first fix is used to compose the alert and then updates stop.
- SMS queueing: `scheduleSms` buffers the latest message and sends one after 5s (`sendSms`).
- Incoming SMS: `AlertSmsReceiver` parses coordinates and optional time from the message and sends an internal broadcast consumed by `MainActivity` to update the UI list.

## Testing Tips
- Permissions: If prompts were denied, enable them in Settings as noted above.
- Car mode:
  - Try different Sensitivity values (higher threshold reduces triggers).
  - Verify only one SMS is sent per 5 seconds even with multiple shakes.
- Ambulance mode:
  - From another phone, send a message containing a Google Maps link like:
    `MOTION ALERT! Location: https://www.google.com/maps?q=12.9716,77.5946 Time: 2024-11-21 10:53:00`
  - The alert should appear in the list; use the action to open Maps.
- Emulators:
  - You can simulate location in the emulator, but SMS to real numbers is not supported. Use a physical device for end-to-end tests.

## Troubleshooting
- No SMS sent/received
  - Ensure SIM is active and device has network service.
  - Verify `SEND_SMS` / `RECEIVE_SMS` permissions are granted.
- Location stuck on “Not available”
  - Ensure Location is enabled on the device and the app has precise location permission.
  - Go outdoors or near a window for better GPS signal.
- Google Maps not opening
  - The app attempts to open the Google Maps app via `geo:` URI and falls back to a web URL if the app is unavailable.

## Diagram (Mermaid)
You can paste this in supported Markdown renderers or use an online Mermaid viewer.

```mermaid
flowchart TD
    A[Accelerometer Sensor] -->|values| B[MainActivity.onSensorChanged]
    B -->|accel > sensitivity & cooldown ok| C[requestLocation()]
    C --> D[LocationManager GPS Provider]
    D -->|onLocationChanged| E[Format location + time]
    E --> F[scheduleSms(message)]
    F -->|coalesce 5s| G[sendSms()]
    G -->|SmsManager| H[Emergency Contact]

    subgraph Incoming SMS Flow
    I[Telephony SMS_RECEIVED] --> J[AlertSmsReceiver.onReceive]
    J -->|parse maps link + time| K[Internal Broadcast ALERT_RECEIVED]
    K --> L[MainActivity.internalAlertReceiver]
    L --> M[alerts list + lastAlert]
    M --> N[Compose UI: Alert cards]
    N -->|Go To Location| O[onGoToLocation()]
    O --> P[External Maps App]
    end
```

## Dependencies (excerpt)
- Compose BOM and Material3
- `androidx.compose.material:material-icons-extended`
- `androidx.datastore:datastore-preferences:1.1.1`

See `app/build.gradle.kts` for full details.

## License
Specify your license here (e.g., MIT, Apache-2.0).
