# PROJECT MEMORY - AABrowser

## 1. Current Status
- **Version / Milestone**: Post-2.2 Development Branch
- **Recent Updates**:
  - **Multi-tab Android Auto player: "last started playback wins"** (replaces the old close-tab/reload/navigate-home workaround):
    - Every WebView is now its own media client: each Main tab (`BrowserScreen`) creates its own `MediaSessionManager`, the Wide keeps its own. `MediaFocusManager` arbitrates between all of them.
    - When a playback starts in a client, that client takes the AA player; the previous owner is paused (PAUSE_SCRIPT) and detached, but its page is never closed, reloaded or sent to home.
    - Only the owner publishes metadata/state and receives AA commands (play/pause/next/prev/seek). With no owner, commands are ignored.
    - Selecting the "Wide:" tab in Main converts it in place into a normal Main tab (same id, same WebView, no reload); the Wide stays on its page.
  - **Root causes fixed** (why hooking the player was unreliable):
    - The JS bridge and WebViewClient in `BrowserScreen` are created once in the `AndroidView` factory, so they captured a frozen `isTabActive`: tabs created in background never reported media, tabs created active kept reporting from background. Now read through `rememberUpdatedState`.
    - A single shared Main `MediaSessionManager` had `onPlay/onPause/...` bound to the last created WebView, so AA commands could reach the wrong tab.
    - Bridge calls ran on the JavaBridge thread; the delayed (500 ms) PLAYING could be published after a later pause or after another client took focus. Now all state changes run on the main thread, the pending PLAYING is coalesced, re-checked and cancelled on focus loss.
    - Metadata updates no longer force STATE_PLAYING (a paused page stays paused on AA).
  - **Self-pause right after a playback starts (page change) fixed**: `CarMediaService` called `requestAudioFocus()` on every metadata update (and on AA play). The WebView (Chromium `AudioFocusDelegate`) already holds audio focus while playing, so the service stole it and Chromium paused the video ~1 s after it started (seen in logcat: WebView requestAudioFocus -> service requestAudioFocus -> `onAudioFocusChange(-1)` to `AudioFocusDelegate` -> page `pause`). Same fix as commit `bd244ca` ("fix spotify auto pause"), which had been undone by `3053222`.

## 2. Modified / Created Files
- `PROJECT_MEMORY.md`: Updated project memory log.
- `CLAUDE.md`: Imports `AABrowser.md` + `PROJECT_MEMORY.md` so every Claude Code session (CLI or Android Studio plugin) loads the project context automatically.
- `browser/src/main/java/com/fcaronte/aabrowser/mediaservice/CarMediaService.kt`: Removed `requestAudioFocus()` from the metadata path and from `onPlay` (audio focus belongs to the WebView).
- `browser/src/main/java/com/fcaronte/aabrowser/mediaservice/MediaFocusManager.kt`: Strict ownership (`isFocusOwner` true only for the real owner), pauses the previous owner on switch, 1 s re-claim guard against play/pause ping-pong, no more `navigateToHome()` of the Wide.
- `browser/src/main/java/com/fcaronte/aabrowser/mediaservice/MediaSessionManager.kt`: One instance per WebView, thread-safe (main thread), `isPlaybackStart` flag, `canTakeFocus` / `fallbackTitle` providers, `releaseMediaFocus()` (clears the AA player if owner), pauses playbacks that cannot own the player.
- `browser/src/main/java/com/fcaronte/aabrowser/ui/BrowserScreen.kt`: Owns its `MediaSessionManager` (created/connected per tab, disconnected on dispose), uses up-to-date `isTabActive`, releases the player on page start, removed the tab-activation "claim" script.
- `browser/src/main/java/com/fcaronte/aabrowser/ui/MainScreen.kt`: Removed the shared media session and the manual player reset on tab close (the closed tab clears the player only if it owned it).
- `browser/src/main/java/com/fcaronte/aabrowser/car/WideScreen.kt`: Sends `isPlaybackStart`, releases the player on every page start (not only home), page title as fallback.
- `browser/src/main/java/com/fcaronte/aabrowser/model/TabManager.kt`: Non-destructive `switchTab` for the wide tab; `findTabIndexByUrlOrHost` ignores the wide mirror tab (opening a site from Main never lands on it).
- `browser/src/main/java/com/fcaronte/aabrowser/utils/BrowserJavascript.kt`: `MEDIA_LISTENERS_JS` reports only audible media (muted previews / background videos can't steal the player), unmute while playing counts as a start, media already playing when listeners attach is reported.

## 3. Architectural Decisions
- **Per-WebView media clients + single arbiter**: `MediaFocusManager` is the only place that decides who owns the AA player; `MediaSessionManager` instances never talk to each other.
- **Who can take the player**: a client claims it only on a real start (JS `play` event / unmute, or a paused→playing transition) and only if it can take focus (visible Main tab or the Wide). Background tabs that start by themselves (autoplay, wide mirror tab) are paused.
- **Stale reports never claim**: periodic updates (`timeupdate`, Spotify polling) from a non-owner that was already playing are treated as late reports and paused (rate limited to 1/s).
- **Focus release**: on page start, tab close, Wide surface/WebView release. The owner sends STATE_NONE + empty metadata so `CarMediaService` goes back to weather/idle.
- **Never request Android audio focus from the app side** (`CarMediaService` or elsewhere): each WebView requests it through Chromium when its media really starts; any other request from our package takes it away and Chromium pauses the page. The WebViews also pause each other natively through audio focus, which is consistent with "last started wins".

## 4. Next Steps (TODO)
- Deploy and verify on device / Android Auto: Main tab A -> tab B -> back to A; Main <-> Wide; "Wide:" tab selection; closing the owner tab; muted autoplay sites while music plays.
- If a site keeps resuming after being paused (ping-pong), check the `MediaFocusManager` logs (`DENIED`) and tune `RECLAIM_GUARD_MS`.
- Continue maintaining `PROJECT_MEMORY.md`.
