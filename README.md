# EzDent Sensor POC (Unofficial Reverse-Engineered Project)

**⚠️ UNOFFICIAL & REVERSE-ENGINEERED ⚠️**  
This is an independent, unofficial proof-of-concept project that reverse-engineers the Vatech EzSensor intraoral X-ray sensors. It is **NOT** affiliated with, endorsed by, or associated with Vatech in any way.

A proof-of-concept Android application with a fully native C++ image processing pipeline for Vatech EzSensor intraoral X-ray sensors.

## Overview

This project provides a native Android interface to connect to, control, and capture images from a Vatech EzSensor via USB-OTG, bypassing the need for the proprietary Windows-only desktop software. 

It implements the entire sensor communication protocol and a reverse-engineered image processing pipeline natively in C++ via JNI.

## Features
- **Direct USB-OTG Communication**: Connects directly to the EzSensor (PID: 0x2009) via Android's USB Host API.
- **Native Image Processing Pipeline**: A high-performance implementation of the proprietary Vatech pipeline in C++:
  - Custom USB payload descrambling.
  - Flat-Field Calibration (FFC) using embedded dark/gain maps and bad pixel correction.
  - Signal Inversion and Despeckle filtering.
  - AutoContrast using histogram stretching.
  - Multi-pass Unsharp Masking (USM) for edge enhancement.
  - Corner beveling and image flipping/cropping.
- **Interactive UI**: A modern Jetpack Compose UI to arm the sensor, capture X-rays, and view the results in real-time.
- **Non-Destructive Adjustments**: Hardware-accelerated Brightness and Contrast sliders applied via `ColorMatrix` post-capture.

## Project Structure

- `app/` - The Android Studio project.
  - `app/src/main/kotlin/` - Android Kotlin code (USB Host, UI, ViewModel, JNI Bridge).
  - `app/src/main/cpp/` - The core C++ image processing pipeline (`es_process.cpp`, `es_calibration.cpp`, etc.) and JNI bindings.
  - `app/src/main/assets/EzSensor/` - **(NOT INCLUDED)** You must manually place your proprietary Vatech calibration files (`BPM.raw`, `dark.raw`, `A03_02615.raw`) and `EzSensor.ini` configuration here for the pipeline to function.

## Building

The app can be built using standard Android Studio tools or the Gradle wrapper:
```bash
cd app
./gradlew assembleDebug
```

## Usage
1. Install the APK on an Android device supporting USB Host mode.
2. Connect the EzSensor using a USB-OTG adapter.
3. Grant USB permissions when prompted.
4. Tap "Connect & Identify".
5. Tap "Ready / Arm Capture".
6. Expose the sensor to X-rays. The captured image will appear on screen.
7. Use the Brightness/Contrast sliders to fine-tune the image, and save to device storage.

## Disclaimer
This is an unofficial reverse-engineering proof of concept. It is not intended, certified, or approved for clinical diagnostic use. Use entirely at your own risk.
