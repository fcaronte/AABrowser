\# AGENT INSTRUCTIONS & PROJECT CONTEXT: AABrowser

---

## 0. Project Memory & Continuity Rule (CRITICAL)
- **File Requirement**: A dynamic memory log file named `PROJECT_MEMORY.md` must be created and strictly maintained in the project's root directory.
- **Update Workflow**: After completing every major block of code, refactoring, or feature integration, the AI agent MUST update `PROJECT_MEMORY.md` with:
  1. **Current Status**: Development milestone reached 
  2. **Modified / Created Files**: List of modified files and their specific role.
  3. **Architectural Decisions**: Technical choices made (e.g., state management, sensor smoothing algorithms).
  4. **Next Steps (TODO)**: Concrete roadmap items for the next session.
- **Agent Initialization**: Every time a new AI agent or session starts, it must first inspect `PROJECT_MEMORY.md` and `AABrowser.md` to resume work seamlessly.

---

\## 1. User Interaction & Language Preference

\- \*\*Primary Communication Language\*\*: Always communicate, answer questions, explain code, and discuss technical decisions with the user in \*\*Italian\*\*.

\- \*\*Release Changelogs Exception\*\*: Official release notes/changelogs generated for the project must remain \*\*bi-lingual\*\* (Italian 🇮🇹 first, followed by English 🇬🇧) as specified in Section 3.

\---

\## 2. Project Overview & Architecture

\*\*AABrowser\*\* is an Android web-browser application designed specifically for \*\*Android Auto\*\*. Its core goal is to provide a seamless, touch-friendly, and responsive web browsing experience on head units using native templates and custom WebView interactions.

\### Primary Tech Stack & Structure

\- \*\*Language\*\*: Kotlin / Android SDK

\- \*\*Dependency Management\*\*: Centralized via \`gradle/libs.versions.toml\` (version catalog)

\- \*\*CI/CD\*\*: GitHub Actions workflows for automated APK compilation and release generation

\- \*\*Logging\*\*: Uses custom \`AppLog\` utility to strip/sanitize sensitive user data from logs prior to export or sharing

\- \*\*Updater/Installer\*\*: Updates are automated via \`DownloadManager\` + \`FileProvider\` and targeted for installation through \*\*KingInstaller\*\* (\`com.example.kinginstaller\`, opened on its MainActivity, which loads the file and lets the user choose how to install; the APK is downloaded into the app's own external folder, because the public Downloads are not readable by the app)

\---

\## 3. Core Modules & Key Features

\### AABrowser Widescreen (Wide Mode)

\- \*\*Concept\*\*: Native Android Auto template-based UI (inspired by apps like Xtream Player) occupying the primary screen space (navigation/map area).

\- \*\*Layout Logic\*\*:

  - Supports Full Screen and Split View modes.

  - Custom fixed sidebar on the left side (\`Left Sidebar\` option).

  - \*Hard Constraint\*: Android Auto stock template framework enforces that the right stock bar MUST keep at least one action button locked at the top-right corner.

  - \*Android Auto Limitations\* (set by Google for Car App Library apps, not bugs; the "Beta" label was dropped in 2.4): Only the full-screen Android Auto keyboard is available for text fields (Car App Library apps cannot show a docked keyboard; Android Auto may lock it while driving); since 2.5 the optional Passenger keyboard (drawn by the app: QWERTY, plus AZERTY, QWERTZ, Russian and Arabic with the 🌐 key for those languages) replaces it in both apps. Double-tap-to-zoom is disabled and drag gestures are not available (the host gives no finger position), for touch stability and safety while driving.

\### Web Engine & Injected JavaScript

\- JS injections are decoupled into modular, readable JavaScript files (located in assets/res).

\- Handles site-specific optimizations (e.g., skip button handling on YouTube playlists across multiple UI languages, touch interactions on Zappr, media auto-pause logic).

\### Passenger Mode (2.5)

\- On the car screens (Main in the car and Wide) the app works only when the car is parked: the driving status comes from Android Auto (the same one that locks its keyboard), no checks of our own. While moving the page is covered by a message that explains how to unlock it; audio, the Android Auto player and voice commands keep working. If Android Auto does not send the status, nothing is locked.

\- Settings "Passenger mode" (unlocks the app while moving) and "Passenger keyboard" (the app's QWERTY keyboard instead of the Android Auto one, also while moving): both off by default, meant for passengers only. Release notes must say the driver must not use them.

\### TV in CarWeb Media (2.5)

\- Page `https://player.local/tv` (assets/player/tv.html, tv.js, tv.css), reached from the TV button of CarWeb Media. `IptvSources` adds the IPTV-org language playlist automatically as the first, non-removable source (`languages/<ISO-639-3>.m3u`, selected from the app locale); unsupported languages use `index.m3u`. User M3U lists can be entered as URLs or selected as local M3U/M3U8 files in Settings on the phone; Xtream accounts are also added there (`iptv_sources`, out of the backup). Local files use a persisted Storage Access Framework read grant. For Xtream, server + user + password or the full link that contains them (`XtreamLink`). The app reads the lists (`IptvLibrary`) and proxies every stream (`IptvProxy`): the page only gets random ids, never addresses or credentials (Xtream puts user and password in the stream URL), and HLS playlists are rewritten so segments go through the proxy too. Playback with hls.js (full build: the light one has no separate audio tracks, about a third of the channels) and mpegts.js, Apache 2.0, bundled in assets/player/lib with their licenses. Headers asked by the lists (`#EXTVLCOPT`, `#EXTHTTP`, `#KODIPROP`, `url|User-Agent=…`, `IptvHeaders`) go with the proxied requests, segments included. DASH is not supported (most listed `.mpd` no longer answer). Never log stream addresses.

\### Radio in CarWeb Media

\- Quando cambia il titolo fornito dallo stream, la pagina cerca automaticamente i testi su LRCLIB e li rende disponibili dal pulsante Testi; i risultati sincronizzati sono mostrati senza evidenziazione temporale perché la riproduzione può iniziare a metà brano.
\- Il testo della stazione passa da `assets/player/track-parser.js` (brano in onda, non il successivo; formati `Titolo~Artista~…`, `Song*…`, `text="…" song_spot="M"`; nome della stazione, indirizzi, pubblicità, telefoni). Test: `node --test browser/src/test/js/`, con testi veri campionati da Radio Browser.
\- Se la stazione non invia metadati, il titolo può essere cercato manualmente.
\- Page `https://player.local/radio` (assets/player/radio.html, radio.js, radio.css), linked beside TV in the local player. Station data and stream selection use Radio-Browser.info; the native API keeps stream URLs out of the page until playback is requested, tracks station clicks, and rejects non-public stream hosts. The country follows the app's explicit locale region, or a language-based default. Search, per-station favorites, HLS playback (using the bundled hls.js), and Android Auto Media Session controls are supported. Verify network/provider compatibility on a real device; some stations may be unavailable or use codecs unsupported by WebView.

\### Voice Commands in CarWeb Media

\- `VoiceCommands` capisce le frasi (11 lingue) e decide la pagina; `MediaVoice` tiene la richiesta e la pagina la viene a prendere (`/api/voice`, con il token) quando si apre o quando l'app la avvisa: niente parametri nell'indirizzo, così nessun sito può far partire qualcosa e ricaricare non ripete il comando. `MediaMatcher` (Kotlin, con test) trova i nomi per TV, Radio e file.

\### Player Card Tap

\- Android Auto manda il tocco sulla scheda del player al componente che la sessione scrive nell'extra `android.media.session.BROWSE_SERVICE`, se è nel suo menu; senza, alla prima app del pacchetto nel menu: la Wide, che è di categoria navigazione e prende il posto del navigatore aperto. L'opzione "Il tocco sul player apre CarWeb" scrive la Main (`CarMediaService.applyPlayerCardTarget`), tranne quando suona la Wide. Niente componenti in più: un servizio di categoria media riceve il tocco ma aggiunge un'icona al menu, e un'app nascosta dal menu non lo riceve. Android Auto si collega al componente dichiarato anche come MediaBrowser, altrimenti la scheda resta vuota: `CarService` smista per azione e passa queste richieste a `CarMediaService`. Visto nel codice di Android Auto 18.1: una versione che non legge l'extra apre la Wide, come senza opzione.

\### Switching Between the Two Car Apps

- La Main ha il tasto «CarWebWide» in alto nella dashboard (solo in auto) e la home della Wide ha «↗ CarWeb» (`HomePage.OPEN_MAIN`, solo dalla home come gli altri `about:toggle_…`). Main → Wide: `startCarActivity` dell'SDK con il componente della Wide; Wide → Main: `MainHandOver.openMainSoon`. La Wide aperta dalla Main non passa la mano alla Main (`EXTRA_FROM_MAIN` e 5 s dal tasto). Servono entrambe le app nel menu di Android Auto: con la Wide nascosta il tasto della Main non la apre (prova dell'utente del 07/11/2026), e il codice non può togliere la Wide dal menu (è elencata per la sua categoria). Provate e tolte anche la Wide come app POI o meteo (template mappa con pannello): niente terza app.

\### Gemini and Assistant in the Car

\- La sessione media accetta la ricerca vocale standard (`ACTION_PLAY_FROM_SEARCH`, `onPlayFromSearch` → `VoiceCommandRouter.fromAssistant`), quella del vecchio Assistente. Gemini non la usa per CarWeb: nelle prove del 07/11/2026 non è mai arrivato niente, né ad Android Auto né alla sessione, anche con un'app media "CarWeb" nel menu (opzione provata e tolta). Android Auto passa «metti … su <app>» solo alle app media del suo menu, e il MediaBrowserService di un pacchetto con una car app (la Wide) non ci compare. «Apri CarWeb»: Gemini chiede ad Android Auto il pacchetto (azione MAIN) e Android Auto apre la Wide; con "Il tocco sul player apre CarWeb" la Wide porta davanti la Main e resta aperta (`car/MainHandOver.kt`): chiusa, Android Auto la riapre subito e rimanda indietro la Main. Diagnosi nelle build di debug: tag `CarWebVoiceDebug` (chiamante, testo ed extra di `onPlayFromSearch`, comando, schermata scelta, piano eseguito). Sul telefono Gemini usa solo app partner di Google; AppFunctions (Android 16) è in anteprima privata.

\### Media & Audio Handling

\- \*\*DRM Support\*\*: Widevine L3 implementation.

\- \*\*Audio Focus\*\*: Automatically pauses/ducks playback during reverse gear / rear parking camera activation.

\- \*\*Media Player\*\*: Integrated player with options to disable completely or run in the background. Local audio tracks can look up plain or synchronized lyrics on LRCLIB using their tags; playback-time highlighting is available for synchronized lyrics.

\### Internationalization (i18n)

\- Multilingual resources stored across \`app/src/main/res/values-\*/strings.xml\`.

\- Supported languages include: \`ar\`, \`de\`, \`es\`, \`fr\`, \`hi\`, \`hu\`, \`it\`, \`pt\`, \`ru\`, plus base \`values\`.

\- \*Rule\*: When modifying UI elements or web-scraping/JS hooks relying on localized text (like YouTube "Skip" buttons), ensure multi-language resilience.

\---

\## 4. Rules & Guidelines for AI Agents

When modifying code, refactoring, or generating files for AABrowser, strictly adhere to the following rules:

\### Code Formatting & Maintenance

1\. \*\*Optimize Imports\*\*: Always remove unused imports and keep import blocks clean (\`Ctrl + Alt + O\` / IntelliJ standard).

2\. \*\*Architecture Separation\*\*: Keep Classic Mode (standard WebView) logic cleanly separated from Widescreen Mode (Car App Library templates) to avoid regressions.

3\. \*\*Log Security\*\*: Never log raw URLs, tokens, or personal context using standard \`Log.d\` / \`Log.e\`. Always wrap logs through the \`AppLog\` sanitizer class.

4\. \*\*Dependency Management (TOML)\*\*: Always define dependencies and library versions in \`gradle/libs.versions.toml\` (Version Catalog). Never hardcode dependency strings directly in \`build.gradle\` files.

\### Release Changelog Generation Standard

When tasked with creating release updates, strictly follow this format:

\- \*\*Bi-lingual\*\*: Provide notes in \*\*Italian (🇮🇹)\*\* first, followed by \*\*English (🇬🇧)\*\*.

\- \*\*Widescreen Disclaimer\*\*: If referencing Wide Mode features, highlight the constraints imposed by Android Auto (e.g., keyboard/zoom limits). Since 2.4 Wide Mode is no longer labeled \*\*Beta\*\*.

\- \*\*Donation Section\*\*: End EVERY release note in both languages with styled clickable Markdown links (do NOT output raw URLs in text):

\`\`\`markdown

\# ☕ Supporta il Progetto / Support the Project

Se ti piace usare l'applicazione e desideri supportare lo sviluppo, puoi farlo tramite i seguenti link:

\- \*\*\[Dona tramite PayPal\](https://www.paypal.com/paypalme/FCaronte/4)\*\*

\- \*\*\[Offr

imi un Caffè (Buy Me a Coffee)\](http://buymeacoffee.com/fcaronte)\*\*
