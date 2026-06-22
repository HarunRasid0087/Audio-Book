# Offline Reader (Text-to-Speech) — Build & Install Guide

A fully **offline** Android app that reads documents aloud using the device's
built-in Text-to-Speech engine. Open **TXT, PDF, or EPUB** files (or paste text),
pick a **language and voice**, adjust speed/pitch, and play.
No internet, no servers, no accounts.

---

## What's included

```
AudioBook App/
├── app/
│   ├── build.gradle.kts                 # deps: pdfbox-android, jsoup, mlkit, media
│   ├── proguard-rules.pro
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/sample.txt
│       ├── java/com/example/offlinetts/
│       │   ├── MainActivity.kt          # UI + wiring, progress, skip, sleep timer, resume
│       │   ├── TtsManager.kt            # offline TTS wrapper: chunking, pause/skip/resume
│       │   ├── DocumentParser.kt        # TXT / PDF / EPUB → plain text (offline)
        │   │   ├── EpubExtractor.kt         # self-contained EPUB (ZIP) parser, no JitPack dep
│       │   ├── OcrHelper.kt             # ML Kit OCR fallback for scanned PDFs
│       │   ├── PlaybackService.kt       # foreground media service + lock-screen controls
│       │   └── ReaderPrefs.kt           # persists session + settings (SharedPreferences)
│       └── res/
│           ├── layout/activity_main.xml # Material 3 card-based UI
│           ├── drawable/*.xml           # vector icons + adaptive launcher
│           ├── mipmap-*/ic_launcher*    # adaptive + legacy launcher icons
│           ├── values-night/colors.xml  # dark theme palette
│           └── values/{strings,arrays,colors,themes}.xml
├── build.gradle.kts
├── settings.gradle.kts                  # google() + mavenCentral() + jitpack
├── gradle.properties
├── gradlew  /  gradlew.bat              # wrapper scripts (self-bootstrapping)
├── gradle/wrapper/
│   ├── gradle-wrapper.jar               # ✅ included (Gradle 8.7)
│   └── gradle-wrapper.properties
└── .github/workflows/build.yml          # auto-builds APK in the cloud
```

---

## ✨ Features
- **Multi-format input:** TXT, **PDF** (via PDFBox-Android), **EPUB** (via the built-in EpubExtractor + Jsoup — no external EPUB lib).
- **OCR fallback:** scanned/image-only PDFs are recognised on-device with
  **ML Kit** (bundled model — still 100% offline).
- **Language picker:** lists every language your TTS engine has installed.
- **Voice picker:** offline voices for the chosen language, with a quality rating (★).
- **Speed & Pitch** sliders (0.5×–2.0×), via Material 3 sliders.
- **Transport controls:** Play / Pause-Resume / Stop **+ Skip ◀ ▶** by chunk.
- **Live progress bar** with percentage and "Reading X / Y" status.
- **Resume across restarts:** the open document, settings, and exact reading
  position are saved — reopen the app and tap Play to continue where you left off.
- **Background playback + lock-screen controls:** a foreground media service
  keeps reading when the app is backgrounded and shows a **MediaStyle
  notification** (Previous / Play-Pause / Next / Stop) on the lock screen,
  driven by a `MediaSessionCompat` (also responds to hardware media buttons).
- **Sleep timer:** auto-pause after 5–60 minutes.
- **Open / Share integration:** open TXT/PDF/EPUB directly from a file manager
  or share them into the app from any other app.
- **Persisted preferences:** speed, pitch, language and voice are remembered.
- **Modern Material 3 UI:** card-based layout, adaptive launcher icon,
  full light/dark theme support.
- **100% offline** playback (network-only voices are filtered out).

---

## Option A — Build the APK in the cloud (no PC setup needed) ✅ recommended

1. Create a free account at **github.com** and a new repository.
2. Upload **all files in this project**, keeping the folder structure intact.
3. GitHub runs `.github/workflows/build.yml` automatically.
   Open the **Actions** tab → latest run → wait for the green check.
4. In the finished run → **Artifacts** → download **`app-debug`**.
5. Unzip → **`app-debug.apk`** → copy to your phone and tap to install
   (enable *Install unknown apps* when prompted).

> The Gradle wrapper JAR is already committed, and the workflow also
> re-downloads it as a safety net, so the build is self-contained.

---

## Option B — Build locally

### With Android Studio
1. Install **Android Studio**, then `File > Open` → select this folder.
2. Let Gradle sync (downloads dependencies — needs internet **once**).
3. Run ▶ on a device/emulator, or `Build > Build APK(s)` for a shareable APK
   at `app/build/outputs/apk/debug/app-debug.apk`.

### From the command line
```bash
cd "AudioBook App"
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
```
The `gradlew` script auto-downloads the wrapper JAR if it is ever missing,
so it works on any machine with a JDK 17+ installed.

---

## Using the app
1. **Open File (TXT / PDF / EPUB)** — or type/paste text.
2. Choose **Language** and **Voice** from the dropdowns.
3. Adjust **Speed** and **Pitch**.
4. **Play** to listen offline; **Stop** to halt.

### Offline voice data (one-time)
**Settings → Accessibility → Text-to-speech output → ⚙ → Install voice data**,
then download your language. After that no internet is ever required.

---

## Notes & limits
- `minSdk 21` (Android 5.0+), `targetSdk 34`, JDK 17, Gradle 8.7, AGP 8.5.2.
- Scanned/image-only PDFs have no extractable text (no OCR) — you'll get a clear
  message instead of silence.
- Encrypted/DRM PDFs and DRM EPUBs cannot be read.
- Android's TTS has no true *pause*; **Stop** flushes the current queue.
- Parsing large PDFs/EPUBs runs on a background thread so the UI stays responsive.
```
