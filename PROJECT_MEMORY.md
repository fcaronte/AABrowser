# PROJECT MEMORY - AABrowser

## 1. Current Status
- **Version / Milestone**: Post-2.2 Development Branch
- **Recent Updates**:
  - **Widescreen Context-Aware Search & Fallback**:
    - Ensured `SiteSearch` in Widescreen Mode retains context-aware search for site-specific platforms (YouTube, YT Music, Spotify, Amazon, Wikipedia, eBay, Reddit, IMDb, Twitch, Netflix, Google Maps, DAZN).
    - Configured `AppSettings.effectiveSearchEngine` (Google, DuckDuckGo, Bing, Yahoo) specifically as the fallback when the user is on the Home page or on generic websites.
  - **Widescreen Settings Integration**: Aligned Widescreen Mode (`WideScreen.kt`, `WideDashboardScreen.kt`) with user preferences configured in `AppSettings`:
    - Added global Desktop Mode support (`AppSettings.desktopMode`) in Widescreen mode.
    - Integrated Force Dark / Algorithmic Darkening (`AppSettings.darkPages`) into Widescreen WebView.
  - Implemented YouTube maximum quality settings based on network conditions (`WebViewScriptRouter`, `AppSettings`, `SettingsScreen`).

## 2. Modified / Created Files
- `AABrowser.md`: Project guidelines, architecture specs, and memory/continuity requirements.
- `PROJECT_MEMORY.md`: Project status, architectural choices, and roadmap.
- `browser/src/main/java/com/fcaronte/aabrowser/car/WideScreen.kt`: Widescreen Mode UI template handler, added `applyDarkMode`, global desktop mode support, and context-aware search engine.
- `browser/src/main/java/com/fcaronte/aabrowser/car/WideDashboardScreen.kt`: `SiteSearch` with context-aware site search (YouTube, Spotify, Amazon, Google Maps, DAZN, etc.) and fallback to `AppSettings.effectiveSearchEngine`.
- `browser/src/main/java/com/fcaronte/aabrowser/settings/AppSettings.kt`: SharedPreferences data management.
- `browser/src/main/java/com/fcaronte/aabrowser/ui/SettingsScreen.kt`: Jetpack Compose UI settings screen.
- `browser/src/main/java/com/fcaronte/aabrowser/ui/BrowserScreen.kt`: Classic WebView screen layout and JS injection handling.
- `browser/src/main/java/com/fcaronte/aabrowser/utils/WebViewScriptRouter.kt`: Script router for injecting tailored JS scripts.
- `browser/src/main/java/com/fcaronte/aabrowser/utils/BrowserJavascript.kt`: Modular JavaScript injection definitions.

## 3. Architectural Decisions
- **Context-Aware Search + Configured Fallback**: In Widescreen, search requests check if the active page belongs to a site with internal search support (YouTube, Spotify, etc.) first. If no specific match is found or if the user is on Home, search queries fall back to `AppSettings.effectiveSearchEngine`.
- **Unified App Settings for Widescreen**: Important global preferences (`desktopMode`, `darkPages`, `effectiveSearchEngine`) are systematically respected in both Classic Mode (`BrowserScreen.kt`) and Widescreen Mode (`WideScreen.kt`).
- **Classic vs. Widescreen Mode Separation**: Classic WebView UI (`BrowserScreen.kt`) and Car App Library Widescreen UI (`WideScreen.kt`) remain cleanly decoupled in layout while sharing underlying data repositories and `AppSettings`.
- **Log Sanitization**: `AppLog` utility encapsulates log output to filter URLs, tokens, and sensitive context before logging.

## 4. Next Steps (TODO)
- Await developer / user feedback on Widescreen settings integration.
- Continue maintaining `PROJECT_MEMORY.md` after future updates.
