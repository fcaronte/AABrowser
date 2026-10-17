# PROJECT MEMORY - AABrowser

## 1. Current Status
- **Version / Milestone**: Post-2.2 Development Branch
- **Recent Updates**:
  - Implemented YouTube maximum quality settings based on network conditions (`WebViewScriptRouter`, `AppSettings`, `SettingsScreen`).
  - Fixed touch handling and navigation gestures on Widescreen (Beta) sidebar (`WideScreen.kt`).
  - Fixed Zappr web interaction compatibility on Widescreen mode (`BrowserJavascript.kt`, `BrowserScreen.kt`, `WideScreen.kt`).
  - Enhanced JavaScript injection and i18n support across supported site handlers.

## 2. Modified / Created Files
- `AABrowser.md`: Project guidelines, architecture specs, and memory/continuity requirements.
- `browser/src/main/java/com/fcaronte/aabrowser/car/WideScreen.kt`: Widescreen Mode UI template handler, touch gesture mapping, and sidebar logic.
- `browser/src/main/java/com/fcaronte/aabrowser/settings/AppSettings.kt`: SharedPreferences data management including network quality toggle.
- `browser/src/main/java/com/fcaronte/aabrowser/ui/SettingsScreen.kt`: Jetpack Compose UI settings screen for YouTube high quality preference.
- `browser/src/main/java/com/fcaronte/aabrowser/ui/BrowserScreen.kt`: Classic WebView screen layout and JS injection handling.
- `browser/src/main/java/com/fcaronte/aabrowser/utils/WebViewScriptRouter.kt`: Script router for injecting tailored JS scripts (e.g., YouTube quality enforcement).
- `browser/src/main/java/com/fcaronte/aabrowser/utils/BrowserJavascript.kt`: Modular JavaScript injection definitions for web platform workarounds (Zappr, Spotify, YouTube).

## 3. Architectural Decisions
- **Classic vs. Widescreen Mode Separation**: Classic WebView UI (`BrowserScreen.kt`) and Car App Library Widescreen UI (`WideScreen.kt`) are strictly decoupled to avoid regressions across standard display vs. head unit template rendering.
- **Modular JS Injection**: Site-specific JavaScript hacks (YouTube auto-skip/max quality, Zappr touch fix, Spotify UI) are organized in `utils/BrowserJavascript.kt` and `utils/WebViewScriptRouter.kt` with multi-language string fallback.
- **Log Sanitization**: `AppLog` utility encapsulates log output to filter URLs, tokens, and sensitive context before logging.

## 4. Next Steps (TODO)
- Await developer / user instructions for next feature implementation or bugfix.
- Maintain `PROJECT_MEMORY.md` after subsequent major code additions or refactorings.
