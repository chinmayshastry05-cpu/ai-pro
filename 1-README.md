# AI Pro

Box any area of your Android screen and act on it with on-device AI - an original, free app inspired by Samsung's AI Select, built for every Android phone (Android 11+).

## What it does (v0.1.0)
- Floating dark bubble on your screen edge (drag it anywhere).
- Tap the bubble -> screenshot -> drag a box -> on-device OCR (Google ML Kit).
- Actions: Copy, Share, Search, Translate (English<->Hindi, on-device), Add to calendar (finds date/time in the text).
- Share any image to AI Pro to read it.
- No servers, no accounts, no cost. Everything runs on the phone.

## Build
- Android Gradle Plugin 7.4.2, Kotlin 1.8.21, compileSdk 33, minSdk 30 (Android 11+).
- `gradle assembleDebug` -> app/build/outputs/apk/debug/app-debug.apk (debug-signed, test builds only).
- Source snapshot: ai-pro-source.zip in this repo.

## Status
v0.1.0 test build. Planned: Devanagari OCR, MediaProjection capture for Android 9/10, GIF capture (v1.1+).
