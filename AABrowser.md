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

\- Settings "Passenger mode" (unlocks the app while moving) and "Passenger keyboard" (the app's QWERTY keyboard instead of the Android Auto one, also while moving): both off by default, meant for passengers only. Release notes must say the driver must not use them. They have their own Settings section ("Modalità passeggero", `settings_category_passenger`), usable in the car too while parked (moving, the car screen is covered and Settings cannot be reached); the disclaimer to accept is an overlay in the middle of Settings (`PassengerDisclaimerOverlay`, darkened background, Back or a tap outside = cancel), not a dialog window, so it shows on the car screen too, like the QR code; a card at the bottom of the section stayed out of sight and the switch seemed to do nothing.

\### TV in CarWeb Media (2.5)

\- Page `https://player.local/tv` (assets/player/tv.html, tv.js, tv.css), reached from the TV button of CarWeb Media. `IptvSources` adds the IPTV-org language playlist automatically as the first, non-removable source (`languages/<ISO-639-3>.m3u`, selected from the app locale); unsupported languages use `index.m3u`. User M3U lists can be entered as URLs or selected as local M3U/M3U8 files in Settings on the phone (section "TV, Radio e Podcast", `IptvSourcesCard`); Xtream accounts are also added there (`iptv_sources`, out of the backup). The last entry of the list menu (`assets/player/lists.js`) asks the app (`AndroidBridge.openMediaLists`): on the phone Settings open on that card, in the car (no bridge answer) the page says to do it on the phone. When the lists change the app tells the open pages (`aabSourcesChanged`): the menu updates and playback goes on. Local files use a persisted Storage Access Framework read grant. For Xtream, server + user + password or the full link that contains them (`XtreamLink`). The app reads the lists (`IptvLibrary`) and proxies every stream (`IptvProxy`): the page only gets random ids, never addresses or credentials (Xtream puts user and password in the stream URL), and HLS playlists are rewritten so segments go through the proxy too. Playback with hls.js (full build: the light one has no separate audio tracks, about a third of the channels) and mpegts.js, Apache 2.0, bundled in assets/player/lib with their licenses. Headers asked by the lists (`#EXTVLCOPT`, `#EXTHTTP`, `#KODIPROP`, `url|User-Agent=…`, `IptvHeaders`) go with the proxied requests, segments included. DASH is not supported (most listed `.mpd` no longer answer). Never log stream addresses. Favorites (★) are one list for all the lists: each channel keeps its list (`s`) and plays from it without switching the list in the menu; favorites of a removed list are hidden, not deleted (they come back with the list). The per-list favorites saved up to 2.6 are merged at the first opening.

\### Video Zoom in CarWeb Media

- `assets/player/video-zoom.js` (TV e Media, stili in `player.css`): il tocco sul video mostra la barra (sparisce dopo 4 s): − 1× + (1, 1,5, 2, 3, 4, 6×), modo Adatta / Riempi / Allunga (ricordato in `localStorage`, `aabVideoMode`) e schermo intero del riquadro (`.stage`, non del video, così la barra resta). Il tocco sul video non apre più lo schermo intero da solo, come su YouTube. Il video ingrandito è in un contenitore che scorre: si sposta trascinando (telefono, Main in auto) e nella Wide con lo scorrimento di Android Auto, che con `window.__aabScrollOnly` sposta solo il video (`BrowserJavascript.getWideScrollScript`). Video nuovo = di nuovo 1×. Test: `video-zoom.test.js`.

\### Podcast in CarWeb Media

\- Pagina `https://player.local/podcast` (assets/player/podcast.html, podcast.js, podcast.css, podcast-progress.js); Media, TV, Radio e Podcast hanno tutte le icone per passare alle altre. `PodcastApi`: classifica del Paese (`itunes.apple.com/{cc}/rss/toppodcasts`, poi `lookup` per l'indirizzo del feed; 6 h in memoria) e ricerca (`itunes.apple.com/search`): nessuna chiave. Le liste che chiedono una chiave API (Podcast Index) non servono: decisione del 09/11/2026, Apple + RSS/OPML coprono tutto.
\- Copyright (l'app è open source): da Apple solo nomi e indirizzi dei feed; copertine e audio vengono dal feed e dal server di chi pubblica; nel repository nessun catalogo, nessuna copertina, nessuna chiave.
\- Feed letti in streaming da `PodcastFeed` (SAX: si ferma a 300 episodi e 40 MB, tiene solo gli `item` con `enclosure` audio o video; Expat su Android, Xerces nei test). Provato su 197 feed veri di 8 Paesi.
\- Liste in `IptvSources.Section.PODCAST` (fuori dal backup): classifica del Paese della lingua dell'app (non rimovibile), altri Paesi, feed RSS (letti all'aggiunta: controllo e nome) e file OPML. Nel menu della pagina tutti i feed sono una voce sola, "I miei podcast".
\- Copertine delle righe: il feed fino al primo episodio (`/api/podcast/cover`, tre richieste alla volta, solo righe in vista, ricordate in `localStorage`). Punto d'ascolto per episodio in `localStorage` (`podcast-progress.js`, test con `node --test`). Aprire gli episodi aggiunge una voce alla cronologia: Indietro torna ai podcast.
\- A fine episodio parte il successivo nell'ordine d'ascolto (`itunes:type` "serial": il più nuovo; altrimenti il più vecchio, quello sotto nell'elenco), saltando quelli finiti; lo stesso per «avanti» e «precedente» detti a voce: `NEXT_SCRIPT`/`PREVIOUS_SCRIPT` chiamano `window.aabNextItem`/`aabPreviousItem` della pagina, che non tocca i tasti di Android Auto.
\- `/podcast` non è una pagina a brani: Android Auto mostra −10/+10 s e i tasti al volante fanno lo stesso. Comandi vocali come per Radio e TV (`MediaSection.PODCAST`): «metti il podcast …», «metti l'ultimo episodio di …», «cerca il podcast …», «apri i podcast», «prossimo episodio»; la pagina chiede `/api/podcast/find` (seguiti, mandati dalla pagina, feed aggiunti, classifica, poi Apple; «podcast» nella frase si prova anche senza) e fa partire l'episodio più nuovo non finito (nei podcast a puntate il primo non finito). I feed http suonano direttamente come le radio http (`<audio>`, contenuti misti ammessi).

\### Radio in CarWeb Media

\- Quando cambia il titolo fornito dallo stream, la pagina cerca automaticamente i testi su LRCLIB e li rende disponibili dal pulsante Testi; i risultati sincronizzati sono mostrati senza evidenziazione temporale perché la riproduzione può iniziare a metà brano.
\- Il testo della stazione passa da `assets/player/track-parser.js` (brano in onda, non il successivo; formati `Titolo~Artista~…`, `Song*…`, `text="…" song_spot="M"`; nome della stazione, indirizzi, pubblicità, telefoni). Test: `node --test browser/src/test/js/`, con testi veri campionati da Radio Browser.
\- Se la stazione non invia metadati, il titolo può essere cercato manualmente.
\- Page `https://player.local/radio` (assets/player/radio.html, radio.js, radio.css), linked beside TV in the local player. Station data and stream selection use Radio-Browser.info; the native API keeps stream URLs out of the page until playback is requested, tracks station clicks, and rejects non-public stream hosts. The country follows the app's explicit locale region, or a language-based default (first list, not removable); other Radio Browser countries and the user's M3U lists (URL or file) are added in Settings (`IptvSources.Section.RADIO`) and chosen from the menu at the top. Stations of a user list get an id derived from their address, play like the TV lists (no public-host check) and favorites work across lists. Search, per-station favorites, HLS playback (using the bundled hls.js), and Android Auto Media Session controls are supported. Verify network/provider compatibility on a real device; some stations may be unavailable or use codecs unsupported by WebView.

\### Backup

- Impostazioni > Esporta/Importa (`ui/BackupCard.kt`, `BackupRestoreUtils`, formato 2 con il tipo di ogni valore), solo sul telefono: il file si sceglie con il selettore di Android, quindi si rilegge anche dopo una reinstallazione o su un altro telefono (un file lasciato in Download da un'altra installazione non si legge più). Dentro: `BOOKMARKS`, `aa_browser_settings`, `adblock_prefs`, `site_permissions`, `iptv_sources` (liste con indirizzi e password: solo nel file, mai nel backup di Google) e `media_pages`; fuori certificati accettati, cartella scelta per i file e aggiornamenti. Dopo il ripristino l'app si riavvia. Si leggono anche i file di prima (solo preferiti e impostazioni).
- Lo stato delle pagine di TV, Radio e Podcast (preferiti, seguiti, punto d'ascolto, ultima lista; le copertine no) sta nel localStorage della pagina, fuori da ogni backup, e in copia nell'app (`MediaPages`, `assets/player/store.js`, `/api/pages/save` col token, 10 s dopo un cambio o quando la pagina si chiude): all'apertura vale la copia dell'app (`config.saved`). Niente indirizzi né password, quindi va anche nel backup di Google.

\### Phone Back

- `PhoneBackHandler` (tasto o gesto; in auto non fa niente, `MainCarActivity` non ha il dispatcher): prima chiude quello che è aperto sopra (riquadri della pagina, ricerca, menu radiale, modifica dei preferiti), poi la pagina precedente della scheda, poi la home; sulla home il primo indietro mostra un messaggio e per 3 s il gestore si spegne: il secondo lo fa il sistema ed esce dall'app. Mai chiudere il processo: ci girano anche le app dell'auto. Un nuovo riquadro a tutto schermo deve avere il suo `PhoneBackHandler`.

\### Voice Commands in CarWeb Media

\- `VoiceCommands` capisce le frasi (11 lingue) e decide la pagina; `MediaVoice` tiene la richiesta e la pagina la viene a prendere (`/api/voice`, con il token) quando si apre o quando l'app la avvisa: niente parametri nell'indirizzo, così nessun sito può far partire qualcosa e ricaricare non ripete il comando. `MediaMatcher` (Kotlin, con test) trova i nomi per TV, Radio, Podcast e file.

\### Player Card Tap

\- Android Auto manda il tocco sulla scheda del player al componente che la sessione scrive nell'extra `android.media.session.BROWSE_SERVICE`, se è nel suo menu; senza, alla prima app del pacchetto nel menu: la Wide, che è di categoria navigazione e prende il posto del navigatore aperto. L'opzione "Il tocco sul player apre CarWeb" scrive la Main (`CarMediaService.applyPlayerCardTarget`), tranne quando suona la Wide. Niente componenti in più: un servizio di categoria media riceve il tocco ma aggiunge un'icona al menu, e un'app nascosta dal menu non lo riceve. Android Auto si collega al componente dichiarato anche come MediaBrowser, altrimenti la scheda resta vuota: `CarService` smista per azione e passa queste richieste a `CarMediaService`. Visto nel codice di Android Auto 18.1: una versione che non legge l'extra apre la Wide, come senza opzione.

\### Switching Between the Two Car Apps

- La Main ha il tasto «CarWebWide» in alto nella dashboard (solo in auto) e la home della Wide ha «↗ CarWeb» (`HomePage.OPEN_MAIN`, solo dalla home come gli altri `about:toggle_…`). Main → Wide: `startCarActivity` dell'SDK con il componente della Wide; Wide → Main: `MainHandOver.openMainSoon`. La Wide aperta dalla Main non passa la mano alla Main (`EXTRA_FROM_MAIN` e 5 s dal tasto). Servono entrambe le app nel menu di Android Auto: con la Wide nascosta il tasto della Main non la apre (prova dell'utente del 07/11/2026), e il codice non può togliere la Wide dal menu (è elencata per la sua categoria). Provate e tolte anche la Wide come app POI o meteo (template mappa con pannello): niente terza app.

\### Share to the Car

- Opzione "Condividi su CarWeb" (`AppSettings.shareToCarWeb`, acceso; `shareAlways`, spento): `ShareToCarActivity` è spenta nel manifest e `CarShareTarget` la accende con il telefono collegato all'auto (`CarConnection`, osservato dall'avvio dell'app, `setComponentEnabledSetting`) o sempre. Con "Sempre", lontano dall'auto, `VoiceCommandRouter.openOnPhone` e `MainActivity`: si apre una scheda della Main sul telefono. Android Auto annuncia il collegamento con un broadcast implicito (visto nel suo codice), che raggiunge solo le app già avviate: la voce compare quando CarWeb è avviata in auto (app aperta, o player collegato). Se è rimasta accesa dopo il viaggio (app chiusa durante il viaggio), al primo uso legge il collegamento, avvisa e si spegne. Il primo indirizzo web del testo condiviso (`SharedLink`: punteggiatura finale tolta, link di YouTube senza parametri di tracciamento e su m.youtube.com) va a `VoiceCommandRouter.openInCar` → `Command.OpenLink` → `Plan.Load` di tipo `LINK`, con una scelta diversa da quella dei comandi vocali: la Wide solo se è lei davanti (resumed e Main non resumed; conta anche quando sta nel riquadro mappa della home di Android Auto, l'app non lo distingue), altrimenti la Main, riportata davanti. Con la sola Wide aperta e dietro un'altra schermata la Wide apre la Main (`MainHandOver.openMainSoon`) e il link la aspetta (`pendingTarget`); senza app aperte aspetta 60 s quella che si apre. Un video di YouTube parte da solo (`autoplay`: la Wide toglie il gesto richiesto, la Main con `requestAutoplay` all'inizio della pagina). La Main sul telefono e quella in auto si registrano entrambe: vale quella in auto.
- Foto, video e file audio (`ACTION_SEND`, `image/*`, `video/*` e `audio/*`, uno alla volta, in una scheda: per quelli fissi c'è CarWeb Media): il permesso sul file finisce con l'attività che lo riceve, quindi `SharedMedia` lo chiede per l'app (`grantUriPermission` sul proprio pacchetto, restituito al file successivo). Li mostra `https://player.local/shared/<id>` (`assets/player/shared.html`, in memoria: dopo un riavvio dell'app la pagina lo dice), con i file serviti come quelli di CarWeb Media e il token. HEIC/HEIF convertite in JPEG perché la WebView non le legge. Non si accettano percorsi diretti né i file privati dell'app (l'attività è esportata).

\### AdBlock: Pop-ups and Pop-unders

- Lo script che intercetta `window.open` (`GoogleLoginManager.POPUP_INTERCEPTOR_JS`, in tutti i frame all'inizio della pagina) manda ogni finestra all'app: la Main la apriva in una scheda, la Wide al posto della pagina, quindi sui siti di streaming ogni tocco portava a una pubblicità. Ora lo script ricorda il link sotto l'ultimo tocco vero e chiede all'app (`AndroidBridge.allowsPopup`, `openInNewTab(url, link)`, `openPopup(url, link)`); `PopupGuard` apre solo verso lo stesso sito della pagina (sito = ultimi due pezzi dell'host, tre per co.uk e simili), il sito del link toccato o un accesso (parole all'inizio di un pezzo di host o percorso: oauth, login, signin, auth, sso, account; siti di pagamento). Ai pop-up bloccati lo script dà una finestra finta (con null molti script spostano la pagina stessa). Bloccati anche i link cliccati da uno script (`a.click()`, clic finti) verso altri siti. Nella pagina principale (`shouldOverrideUrlLoading`) mai le pagine delle liste dell'AdBlock, e per 2 s dopo una finestra aperta da uno script nessun altro sito toccato senza link ("tab-under"). Con l'AdBlock spento o lo scudo spento per il sito tutto come prima.
- Senza finestre multiple Blink apre `_blank` nel frame principale (`create_window.cc`): un link `_blank` dentro un riquadro sostituisce tutta la pagina. Lo script lo apre come le altre finestre (`openInNewTab`, solo nei riquadri, non i `download`). Link invisibili sopra il lettore (almeno metà del riquadro, fissi o assoluti, trasparenti, senza testo né immagini, verso un sito che `PopupGuard` non lascerebbe aprire): tolti, e il tocco passa all'elemento sotto. WebView non passa da `shouldOverrideUrlLoading` per gli indirizzi http(s) dei riquadri: le pagine bloccate rispondono 204 (`AdBlockHost.blockedResponse`, `Accept: text/html`) e resta quello che c'era.

\### AdBlock: Downloaded List

- `AdBlockLists`: si sceglie nelle Impostazioni (`AdBlockSettings.listKey`): HaGeZi Multi PRO di serie (`SOURCES`, da jsDelivr), PRO++, NORMAL, AdGuard DNS filter o l'indirizzo di una lista a piacere (`CUSTOM_KEY`, scritto solo dal telefono). Scaricata e mai distribuita con l'app (licenze GPL-3.0), all'avvio se più vecchia di tre giorni o se non è quella scelta, e dal tasto «Aggiorna»; quella di prima resta in uso finché la nuova non è arrivata. Si tengono le impronte FNV-1a a 64 bit dei domini, ordinate (2 MB per 250.000), in `noBackupFilesDir` (fuori dal backup), con indirizzo e nome della lista; un host è bloccato se lui o un dominio che lo contiene è nella lista e nessuno è nelle eccezioni (`@@`). Letti `||dominio^` (anche `$important`), `@@||dominio^`, `@@|host^|`, file hosts e un dominio per riga; `$badfilter` toglie la regola; saltati jolly, espressioni regolari, altre opzioni e le regole per nascondere elementi. Una lista nota con meno di 1000 domini (una scritta dall'utente: vuota) è un errore e non sostituisce quella salvata; al massimo 40 MB e 3 milioni di domini. Oltre alla lista, gli script dei circuiti che cambiano dominio a ogni ora con lo schema `/c7/93/5c/c7935c…(32 esadecimali).js` da un altro sito (`AdBlockHost.isRotatingAdScript`). YouTube resta fuori ([scriptManagedHosts]).

\### SponsorBlock on YouTube

- `assets/scripts/sponsorblock.js`, all'inizio delle pagine di YouTube (`YOUTUBE_ORIGINS`) nella Main e nella Wide; chiede al ponte le categorie (`sponsorBlockCategories`, "[]" se spento) e il messaggio (`sponsorBlockMessage`). Chiede `sponsor.ajay.app/api/skipSegments/<prime 4 cifre dello SHA-256 dell'id>` (CORS permesso, YouTube non limita `connect-src`, niente cookie) e tiene le parti `skip` di quel video lunghe almeno 1 s; salta su `timeupdate` (in cattura sul document) e ogni secondo, mai con `ad-showing`, ogni parte una volta sola, con un messaggio nella pagina (anche a schermo intero). Livelli (`AppSettings.sponsorBlockLevel`, `SponsorBlock.categories`): solo sponsor; più autopromozione e richieste di iscrizione (predefinito); più sigle, finali, anteprime e parti non musicali. Acceso di default. Dati CC BY-NC-SA 4.0 (citati nel README). Test: `node --test browser/src/test/js/`.

\### Gemini and Assistant in the Car

\- La sessione media accetta la ricerca vocale standard (`ACTION_PLAY_FROM_SEARCH`, `onPlayFromSearch` → `VoiceCommandRouter.fromAssistant`), quella del vecchio Assistente. Gemini non la usa per CarWeb: nelle prove del 07/11/2026 non è mai arrivato niente, né ad Android Auto né alla sessione, anche con un'app media "CarWeb" nel menu (opzione provata e tolta). Android Auto passa «metti … su <app>» solo alle app media del suo menu, e il MediaBrowserService di un pacchetto con una car app (la Wide) non ci compare. «Apri CarWeb»: Gemini chiede ad Android Auto il pacchetto (azione MAIN) e Android Auto apre la Wide; con "Il tocco sul player apre CarWeb" la Wide porta davanti la Main e resta aperta (`car/MainHandOver.kt`): chiusa, Android Auto la riapre subito e rimanda indietro la Main. Diagnosi nelle build di debug: tag `CarWebVoiceDebug` (chiamante, testo ed extra di `onPlayFromSearch`, comando, schermata scelta, piano eseguito). Sul telefono Gemini usa solo app partner di Google; AppFunctions (Android 16) è in anteprima privata.

\### Media & Audio Handling

\- \*\*DRM Support\*\*: Widevine L3 implementation.

\- \*\*Audio Focus\*\*: Automatically pauses/ducks playback during reverse gear / rear parking camera activation.

\- \*\*Tasti al volante\*\*: Android Auto li inoltra alla sessione, ma il comportamento predefinito di Android scarta ⏭ e ⏮ se lo stato non li dichiara. Su un video senza brani (stato con `ACTION_SEEK_TO` e senza skip) li gestisce `CarMediaService.onMediaButtonEvent`: ±10 s subito, come i pulsanti di Android Auto, e i tocchi ravvicinati si sommano. Opzione nelle Impostazioni, solo YouTube (`AppSettings.youtubeVideoSkip`, spenta): lo stato dichiara lo skip (`MediaSessionManager.singleVideoAsTracks`), ⏭ apre il primo video suggerito (`ytm-video-with-context-renderer`) e ⏮ torna all'inizio, o alla pagina di prima se è già all'inizio. Un «successivo» detto a voce («avanti», «manda avanti») apre comunque il video suggerito: senza skip dichiarato `onSkipToNext` arriva solo da una richiesta esplicita (`sendPlayerCommand`), mai dai tasti.

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
