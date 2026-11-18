# Edge Reader

Full-screen PDF reader, portrait-first, with landscape support (follows the device's auto-rotate setting). Kotlin + Jetpack Compose + the platform `PdfRenderer`
(no third-party PDF library, no permissions).

## Build & install
1. Open this folder in **Android Studio** (Ladybug or newer, JDK 17). It downloads Gradle 8.9 and syncs.
2. Plug in a device with USB debugging on, press **Run**.
   CLI alternative: `./gradlew installDebug` (generate the wrapper once with `gradle wrapper` if `gradlew` is missing).
3. `./gradlew assembleRelease` gives an installable APK (signed with the debug key; swap in your own before publishing).

## How it reads
- Tap: show page pill + **Fit** / **Open** + right-edge scrubber (auto-hides). Double-tap: fill the page width (2.5x if it already does) / back to fit.
- Pinch zooms about your fingers (1x–10x). One finger pans in all directions with fling. Pan and pinch share one code path, so scrolling between pages and zooming never fight.
- Back: hides controls, then returns to fit, then exits.
- "Open with" from a file manager works too.

## Architecture
| File | Role |
|---|---|
| `pdf/PdfDocument` | Mutex-guarded `PdfRenderer`; renders any region via a matrix; falls back to a cache copy if the URI isn't seekable |
| `reader/PageLayout` | Pages stacked in document space, each fit-to-viewport, first/last centred |
| `reader/ReaderState` | The one transform (`scale`, `tx`, `ty`): pinch, pan, fling, clamping, animations |
| `pdf/PageBitmaps` | LRU base bitmaps + viewport-region "detail" bitmaps for sharp zoom |
| `ui/Gestures` | Unified pan/pinch/fling/tap/double-tap detector |
| `ui/ReaderSurface`, `ui/Chrome`, `ui/ReaderApp` | Canvas drawing, overlays, screens |
| `ReaderViewModel`, `MainActivity` | Open/close, error mapping, immersive mode, cutout handling |

Memory: pages are opened one at a time; at most a handful of fit-size bitmaps are cached, and zoomed
rendering only ever allocates the on-screen region (capped at ~6 MP per page).

## Manual test checklist
Open a PDF → page fits and is centred · pinch in, drag to all four corners of a page · fling past the end (stops cleanly) ·
double-tap in/out · scrub through a 500+ page file · mixed portrait/landscape pages · rotate/fold/split-screen ·
a corrupt file, an encrypted PDF, and a Drive/cloud file (error text / cache fallback).

## Install without Android Studio
**Cloud build (no local tools):** push this folder to a GitHub repo; the included workflow
(`.github/workflows/build-apk.yml`) builds `app-debug.apk`. Download it from the run's *Artifacts*.
**Install:** copy the APK to the phone, open it, allow "Install unknown apps" for that file manager/browser, tap Install.
Or with USB debugging: `adb install app-debug.apk`.
