# AI Pro

Box any area of your Android screen and act on it with on-device AI — an original, free app inspired by Samsung's AI Select, built for every Android phone (Android 11+).

## What it does

Enable the AI Pro accessibility service once. After that, opening the app (or the Quick Settings tile, launcher shortcut, edge-panel shortcut, or your assistant button) captures the screen. Drag a box around anything — or just tap a line of text — and AI Pro reads it with on-device machine learning. Nothing you capture ever leaves your phone.

## Features (v0.7.4)

- **Screen capture** via the accessibility service — no root, no PC needed.
- **Smart Select**: drag a box and it snaps to the text lines it touches; tap any text line to select it directly; tap a card, icon, image or figure to auto-select the whole shape; crop-style handles to resize the selection.
- **On-device OCR** — English (Latin script) + Hindi (Devanagari), via Google ML Kit. No internet needed for reading.
- **Text actions**: Translate (English ↔ Hindi, auto-detected), Copy, Share, Search, Add event (finds dates/times in the text and opens your calendar).
- **Image actions**: Pin (floating always-on-top image — drag to move, tap to close), Copy image, Share, Save to Gallery, visual search via Google Lens.
- **Create GIF**: record a small region of the screen as an animated GIF, saved to your Gallery.
- **Share any image** to AI Pro from another app to read it.
- Dark UI with gold accents.

## Install the APK (Samsung phone)

1. On your phone, open the **Releases** page of this repo and download **AI-Pro-v0.7.4.apk**.
2. Open the downloaded file. Android will warn that the app is from an unknown source — this build is **debug-signed**, so the "unknown developer" warning is expected. Tap **Install anyway** / **Settings → Allow from this source** if asked.
3. Open **AI Pro** once and tap **Enable AI Pro** — this takes you to Accessibility settings. Turn the AI Pro service **on**. This is the one permission the app needs to capture the screen.
4. Done. Every time you open the app (or tap its Quick Settings tile), it captures the screen for you to box an area.

## Build from source

- Android Gradle Plugin 7.4.2, Kotlin 1.8.21, compileSdk 33, minSdk 30 (Android 11+), arm64-v8a only.
- Open in Android Studio and run, or `gradle assembleDebug`.

## Honest caveats

- **Debug-signed build.** Android will show an "unknown developer / harmful app" style warning during install. That warning is about the signature, not the code — the full source is in this repo.
- **Screen-capture consent.** Android asks for screen-capture consent each capture session; some apps (banking, DRM video) can block screenshots entirely — you'll see a "Capture blocked" message there.
- **OCR languages:** English and Hindi (Devanagari) only. Handwriting and heavily stylized fonts may misread.
- **Translation** downloads a small on-device language model on first use (needs internet that one time).
- Built and tested on Samsung phones; other Android 11+ phones should work but are untested.

## Credits

- GIF encoding uses Kevin Weiner's public-domain `AnimatedGifEncoder` and Anthony Dekker's NeuQuant quantization (copyright notice retained in source).
- Text recognition, translation, language ID and entity extraction: Google ML Kit (on-device).
