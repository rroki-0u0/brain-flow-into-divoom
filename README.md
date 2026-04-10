# brain-flow-into-divoom

Muse S Athena signals to Divoom 16x16 display bridge app for Android.

## Overview

This project streams brain and biometrics-derived values from Muse S Athena, converts them into 16x16 LED frames, and sends frames to Divoom devices over Bluetooth.

## Features

- Real-time Muse streaming with profile-based sensor control (`EEG Only` / `Full Biometrics` / `Auto`)
- Parameter-selectable visualization (band powers, Focus/Relax, Heart Rate, Oxygen Proxy)
- Two visual modes:
  - `Oscilloscope`: waveform-focused display
  - `Bubble`: speech-bubble style frame with waveform inside
- Divoom device scanning, selection, connect/disconnect, manual send, and auto-send
- Connection recovery with retry/backoff for Divoom transport

## Requirements

- Android Studio (latest stable recommended)
- Android SDK (configured via `local.properties`)
- JDK 17 (Android Studio bundled JBR is supported)
- Android device with Bluetooth permissions available

## Build (Windows / PowerShell)

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat assembleDebug -x lint
```

Generated APK (debug):

`app/build/outputs/apk/debug/`

## App Usage

1. Grant Bluetooth permissions.
2. Scan and select a Divoom device.
3. Connect Muse and Divoom.
4. Select a waveform parameter and power mode.
5. Enable auto-send or send frames manually.

## Project Structure

```text
app/
  src/main/java/io/rroki/brainflowintodivoom/
    data/
      divoom/
      muse/
    domain/
      model/
      processing/
    presentation/
    MainActivity.kt
```

## Notes

- Muse and Divoom connections are managed independently so each side can reconnect without restarting the app.
- Sensor-heavy parameters (Heart Rate / Oxygen Proxy) require full biometrics streaming.

## License

MIT License. See `LICENSE`.
