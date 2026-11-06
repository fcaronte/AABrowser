# CarWeb: A Modern Revival of the "Old School" Trick

🇮🇹 [Vai alla versione italiana](#aa-browser-una-rinascita-moderna)

## Overview

This project is my personal take on an Android Auto browser. It was born after discovering two
different worlds:

* The modern and sleek [AABrowser by kododake](https://github.com/kododake/AABrowser).
* The ancient but functional [AABrowser by slashmax](https://github.com/slashmax/AABrowser), which
  relies on "old-school" Android Auto tricks to actually work while the car is in motion.

I loved the interface of the new one, but I needed the functionality of the old one. So, I took the
old, ugly, and obsolete base and forced it to become a modern app, blending the best of both worlds.

![CarWeb Demo](assets/demo.gif)

## ⚠️ SAFETY WARNING

**DO NOT USE THIS APP WHILE DRIVING.**
This application is intended for use by passengers only or for testing purposes while the vehicle is
stationary. Using a web browser while operating a vehicle is extremely dangerous and illegal in most
jurisdictions. The developer is not responsible for any accidents, injuries, or damages resulting
from the misuse of this application. Use at your own risk.

## Main features

* **Two apps on Android Auto:** **CarWeb**, the full browser with tabs, and **CarWebWide
  (Widescreen)**, which uses the whole map area, in full screen or in split view next to the player
  or the app menu, with a fixed side menu. *Limits set by Android Auto for this kind of app:* only the
  full-screen Android Auto keyboard (or the app's Passenger keyboard), no double-tap zoom, no drag gestures
  (you seek by tapping the progress bar).
* **Android Auto player connected to the page:** title, artist, cover and controls of what is playing. With both apps
  in the Android Auto menu, tapping the player opens CarWebWide: an option in Settings opens CarWeb instead.
  With several tabs the last started playback takes the player. Skip buttons on Spotify, YouTube Music,
  YouTube playlists and on every site that declares its tracks (Media Session).
* **CarWeb Media:** player for the audio and video files of a folder on the phone, with separate
  Audio and Video queues, shuffle, repeat, resume, crossfade between songs, animated visual effects (bars,
  wave, circle), lyrics from LRCLIB for local audio, and colors that follow the app theme (light, dark with
  the phone colors, AMOLED).
* **Radio in CarWeb Media:** browse and play stations from Radio-Browser.info at `/radio`. The country
  is chosen from the app language and, when available, its region (for example, English (UK) selects
  the UK). Search and favorites are available; when stream metadata reports a song title, lyrics are
  fetched automatically from LRCLIB and shown from the Lyrics button. The text sent by the station is cleaned first
  ("Now playing: A - B, next: C - D", its own name, ads and phone numbers), and artist and title are tried in both
  orders. Radio lyrics are not time-synchronized because a live stream can join midway through a song. Playback
  controls are also sent to the Android Auto player.
* **TV in CarWeb Media (new in 2.5):** an IPTV-org playlist is added automatically for the app language
  (the general playlist is used for unsupported languages), alongside your own M3U lists (URL or M3U/M3U8
  file selected on the phone) and Xtream accounts (added in Settings; for Xtream the full link with username
  and password is enough). Groups, search and favorites; HLS and MPEG-TS streams, channel name and logo on the
  Android Auto player, ⏮ ⏭ to change channel. Addresses and passwords stay on the phone (not in the backup).
  Channels with AC3/E-AC3 audio may play without sound (WebView limit).
* **AdBlock:** blocks ads on the pages; the shield turns it off for the site you are on (new in 2.4: in the
  tab bar and the navigation menu of CarWeb, at the top right of the Widescreen).
* **YouTube:** ad skipping, video quality of your choice from 480p to 4K (new in 2.4), remembered playback
  speed (ads and music excluded), tap on the progress bar to seek.
* **Voice commands (new in 2.4):** a microphone button in both apps (and on the Android Auto player while it
  shows the weather), or a double tap on ⏮ at the steering wheel, to play songs and videos, search, open
  favorites and sites and control the player. See [Voice commands](#voice-commands).
* **Typing and voice:** keyboard or microphone for text fields, voice search also on YouTube.
* **Home network sites:** sites with a self-signed or invalid certificate (Home Assistant,
  Proxmox, home proxy) open after "Continue anyway", and the choice is remembered for that site and
  certificate; local network permission on Android 17.
* **Files for web pages:** file and folder picker without storage permissions.
* **Location for websites (optional):** Google Maps and the other sites in the browser can see where you are.
* **Site permissions (new in 2.5):** microphone, location and, in the car, the files picked on the phone go to a
  site only after it asks: "Always allow", "Only this time" or "Don't allow". The choices are listed in
  Settings > Web Pages, where you can remove them one by one or all together (Clear data).
* **Passenger mode (new in 2.5):** on the car screen the app works only when the car is parked (Android Auto
  tells when it moves); audio, the Android Auto player and voice commands keep working. With *Passenger mode*
  in Settings it works while moving too, and with *Passenger keyboard*, a keyboard of the app (QWERTY, and with
  🌐 AZERTY, QWERTZ, Russian or Arabic for those languages; numbers and symbols, accented letters), you can type
  while moving, in both apps. For passengers only: the
  driver must not use them.
* **And more:** weather on the Android Auto player when nothing plays (only in the car),
  pause on reverse gear, 11 languages, log sharing from Settings (long press on the GitHub logo), updates
  through KingInstaller.

## Voice commands

*New in 2.4.* Say what you want instead of typing it.

**How to start listening**

* **CarWeb:** the 🎤 button in the tab bar (left of the shield), in the round navigation menu and next to
  search on the home page.
* **CarWebWide (Widescreen):** the 🎤 button in the fixed side menu, or at the top right next to search
  when the side menu is off.
* **Android Auto player:** in the car, while the "CarWeb Media" player shows the weather, it has a 🎤 button, which
  works even when nothing has played yet (with a page playing, the player keeps its own buttons: use the double
  tap).
* **Steering wheel:** a quick double tap on ⏮ (previous) opens the microphone of the open app (CarWeb or
  Widescreen, also in the background). It needs the Android Auto player enabled in Settings, and it works
  once something has played in CarWeb (also if paused afterwards): Android sends the media keys to the app
  that last played audio, not to the card shown by Android Auto, and the weather card plays nothing on
  purpose so it does not take the car audio away from the radio. When the car radio or another app is
  playing, the keys go to them (for example the radio changes station). A single press still means
  "previous", after a short wait.
* While listening, the Android Auto player shows "🎤 Listening…" with the words understood so far.
* Commands work with the app behind too: the player ones ("pause", "next"…) stay in the background, the others
  bring the app back to the front to show and play what you asked.

The first time, allow the microphone on the phone: the car screen cannot show the permission request.

**What you can say** (examples: the commands are understood in all 11 app languages, whatever language the
app is set to)

| Say | What happens |
|---|---|
| "play *song* on YouTube", "play *song* on YouTube Music" | searches and plays the first result |
| "play *song*" | the same, on YouTube Music if it is open, otherwise on YouTube |
| "search *something* on YouTube" | YouTube results |
| "search *shoes* on Amazon", "search Google Maps for *pizzeria*" | the site's own search: Amazon and eBay of your country, Wikipedia in your language, Reddit, IMDb, Twitch, Netflix, Google Maps, Google News, Booking, Tripadvisor, Subito, GitHub, X, Facebook, TikTok, Pinterest, or one of your favorites |
| "search *something*" | search on the open site (YouTube, YouTube Music) or with your search engine |
| "search *something* on Google" | search with your search engine |
| "open *favorite*", "open repubblica dot it" | opens a favorite (by name or site) or a spoken address |
| "back", "reload", "home" | page navigation |
| "pause", "resume", "next", "previous", "next channel", "next station" | the Android Auto player |
| "play *station* on the radio", "tune to *station*", "play radio *name*" | plays the station in CarWeb Media Radio, also outside your country's list; "play the radio" resumes the last one |
| "watch *channel* on TV", "put on channel *name*", "watch *channel*" | plays the channel in CarWeb Media TV, looking in all your lists; "watch the TV" resumes the last one |
| "play *song* from my music", "play *song* on Media" | your files, by title, artist or folder: an artist or a folder plays all its tracks in order |
| "search *something* on the radio", "… on TV", "… on Media" | shows the results on that page without starting anything |
| "open the radio", "open the TV", "open my music" | opens the page |

With Radio, TV or Media open, "play …" and "search …" act on that page, and saying just a name plays it. Names are
understood as they are spoken: "rai uno" finds "Rai 1 HD", "la sette" finds "La7". A favorite channel or station
comes first. If "play radio …" or "watch …" finds nothing, the phrase goes to YouTube as usual.

Anything else is searched on the open site or with your search engine.

**Google Assistant and Gemini:** the app also accepts the standard Android media voice search ("Ok Google,
play *song* on CarWeb"), but for now Gemini replies that the app does not support its commands: use the
🎤 button or the double tap on ⏮.

## The "Gemini-Powered" Development

I want to be completely honest: my programming knowledge is... let's say "basic." This entire
project exists thanks to my endless arguments with Gemini. We spent hours in a cycle of:

1. Fixing one thing.
2. Breaking three others.
3. Arguing about why it broke.
4. Finally getting it to work.

Since version 2.3 **Claude** (Anthropic), through Claude Code, has joined the arguments too: the Android
Auto player with multiple tabs, CarWeb Media, the Widescreen layout fixes and a lot of bug hunting came out
of those sessions.

## Disclaimer & Compatibility

* **It’s a bit buggy:** Expect some "surprises" here and there. It's a work in progress!
* **Protected content (DRM):** the Android WebView only offers Widevine L3, the software protection level. Sites that require the hardware level (Widevine L1) for their content, as live sports and paid movie and TV services often do, cannot work: it is a technical limit of the Android WebView, not of the app. Content protected at L3, like Spotify's music, plays normally.
* **Visibility on Android Auto:** The app is currently only visible if you use an "unlocking device" like **AAWireless**, an open-source solution like [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs) (which can be installed on a Raspberry Pi and includes app unlocking among its features), or if it's installed via [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) (to bypass Play Store restrictions).
* **Compatibility & Installation via KingInstaller:**
  * **Samsung & Pixel:** Works directly without requiring Shizuku.
  * **Other devices:** Works using Shizuku integration in KingInstaller.
  * **Note for Xiaomi / POCO / Redmi:** On recent devices from the Xiaomi, POCO, and Redmi family running MIUI or HyperOS, strict manufacturer customizations block alternative installation methods. On these devices, the Shizuku method does not work, making Root permissions the only working method.
* **Important:** Remember to enable **"Unknown sources"** in the Android Auto developer options on your phone.
* **Testing:** Primarily tested on a **Samsung S24 Ultra**.

## Support the project

If you like the project, you can buy me a coffee to support the hours of sleep lost writing this code and arguing with Gemini and Claude 😂:
* **[Donate via PayPal](https://www.paypal.com/paypalme/FCaronte/4)**
* **[Buy Me a Coffee](http://buymeacoffee.com/fcaronte)**

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---

<a id="aa-browser-una-rinascita-moderna"></a>

# CarWeb: Una rinascita moderna del "vecchio trucco"

🇬🇧 [Go to the English version](#aa-browser-a-modern-revival-of-the-old-school-trick)

## Panoramica

Questo progetto è la mia personale reinterpretazione di un browser per Android Auto. È nato dopo
aver scoperto due mondi diversi:

* Il moderno ed esteticamente curato [AABrowser di kododake](https://github.com/kododake/AABrowser).
* Il vecchio [AABrowser di slashmax](https://github.com/slashmax/AABrowser), che utilizza i "vecchi
  trucchi" di Android Auto per funzionare anche con l'auto in movimento.

Mi piaceva l'interfaccia di quello nuovo, ma avevo bisogno della funzionalità di quello vecchio.
Così, ho preso la base vecchia, brutta e obsoleta e l'ho trasformata in un'app moderna, unendo il
meglio dei due mondi.

![CarWeb Demo](assets/demo.gif)

## ⚠️ AVVERTENZA DI SICUREZZA

**NON USARE QUESTA APP DURANTE LA GUIDA.**
Questa applicazione è destinata esclusivamente all'uso da parte dei passeggeri o per scopi di test a
veicolo fermo. L'utilizzo di un browser web durante la guida di un veicolo è estremamente pericoloso
e illegale nella maggior parte delle giurisdizioni. Lo sviluppatore non è responsabile per eventuali
incidenti, lesioni o danni derivanti dall'uso improprio di questa applicazione. L'utilizzo è a
proprio rischio e pericolo.

## Funzioni principali

* **Due app su Android Auto:** **CarWeb**, il browser completo con le schede, e **CarWebWide
  (Widescreen)**, che usa tutta l'area della mappa, a schermo intero o in split accanto al player
  o al menu delle app, con il menu laterale fisso. *Limiti imposti da Android Auto a questo tipo di app:*
  solo la tastiera a schermo intero di Android Auto (o la Tastiera passeggero dell'app), niente zoom col doppio
  tocco, niente trascinamenti (nel video ci si sposta toccando la barra di avanzamento).
* **Player di Android Auto collegato alla pagina:** titolo, artista, copertina e comandi di quello che
  suona. Con più schede il player va all'ultima riproduzione avviata. Tasti avanti/indietro su Spotify,
  YouTube Music, playlist di YouTube e su ogni sito che dichiara i suoi brani (Media Session). Con le due app nel
  menu di Android Auto il tocco sul player apre CarWebWide: un'opzione nelle Impostazioni apre invece CarWeb.
* **CarWeb Media:** lettore per i file audio e video di una cartella del telefono, con code
  separate Audio e Video, ricerca, casuale, ripeti, ripresa, dissolvenza tra i brani, effetti animati (barre, onda,
  cerchio), testi da LRCLIB per l'audio locale e colori che seguono il tema dell'app (chiaro, scuro con i colori del
  telefono, AMOLED).
* **Radio in CarWeb Media:** cerca e ascolta le stazioni di Radio-Browser.info da `/radio`. Il Paese
  viene scelto in base alla lingua dell'app e, se presente, alla relativa regione (per esempio, inglese
  (Regno Unito) seleziona il Regno Unito). Sono disponibili ricerca e preferiti; quando lo stream indica il titolo
  del brano, il testo viene cercato su LRCLIB e si apre dal pulsante Testi. Il testo mandato dalla stazione viene
  prima ripulito ("Brano attuale: A - B, brano successivo: C - D", il suo nome, pubblicità e numeri di telefono), e
  artista e titolo si provano nei due ordini. I testi della radio non scorrono a tempo perché la riproduzione può
  iniziare a metà brano. I controlli funzionano anche dal player di Android Auto.
* **TV in CarWeb Media (novità 2.5):** viene aggiunta automaticamente la playlist IPTV-org nella lingua
  dell'app (per le lingue non supportate viene usata la playlist generale), insieme alle tue liste M3U (da URL
  o da file M3U/M3U8 scelto sul telefono) e agli account Xtream (si aggiungono nelle Impostazioni; per Xtream
  basta il link completo con utente e password). Gruppi, ricerca e preferiti; stream HLS e MPEG-TS, nome e logo
  del canale sul player di Android Auto, ⏮ ⏭ per cambiare canale. Indirizzi e password restano sul telefono
  (fuori dal backup). I canali con audio AC3/E-AC3 possono restare senza audio (limite della WebView).
* **AdBlock:** blocca le pubblicità nelle pagine; lo scudo lo spegne per il sito che stai guardando (novità
  2.4: nella barra delle schede e nel menu di navigazione di CarWeb, in alto a destra nella Widescreen).
* **YouTube:** salto delle pubblicità, qualità video a scelta da 480p a 4K (novità 2.4), velocità di
  riproduzione ricordata (pubblicità e musica escluse), tocco sulla barra di avanzamento per spostarsi nel
  video.
* **Comandi vocali (novità 2.4):** pulsante microfono nelle due app (e sul player di Android Auto quando mostra
  il meteo), o doppio tocco su ⏮ al volante, per far partire canzoni e video, cercare, aprire preferiti e siti
  e comandare il player. Vedi [Comandi vocali](#comandi-vocali).
* **Scrittura e voce:** tastiera o microfono per i campi di testo, ricerca vocale anche su YouTube.
* **Siti della rete di casa:** i siti con certificato autofirmato o non valido (Home
  Assistant, Proxmox, proxy di casa) si aprono con "Continua comunque" e la scelta resta ricordata per quel
  sito e quel certificato; permesso rete locale su Android 17.
* **File per le pagine web:** scelta di file e cartelle senza permessi di archiviazione.
* **Posizione per i siti web (facoltativa):** Google Maps e gli altri siti nel browser possono vedere dove sei.
* **Permessi dei siti (novità 2.5):** microfono, posizione e, in auto, i file scelti sul telefono arrivano a un
  sito solo dopo che lo ha chiesto: "Consenti sempre", "Solo questa volta" o "Non consentire". Le scelte sono in
  Impostazioni > Pagine Web, dove si tolgono una per una o tutte insieme (Cancella dati).
* **Modalità passeggero (novità 2.5):** sullo schermo dell'auto l'app funziona solo da fermo (se l'auto si
  muove lo dice Android Auto); audio, player di Android Auto e comandi vocali continuano a funzionare. Con
  *Modalità passeggero* nelle Impostazioni funziona anche in movimento, e con *Tastiera passeggero*, una
  tastiera dell'app (QWERTY, e con 🌐 AZERTY, QWERTZ, russa o araba per quelle lingue; numeri e simboli, lettere
  accentate), si scrive anche in movimento, nelle due app. Solo per i passeggeri: chi guida non deve usarle.
* **E poi:** meteo sul player di Android Auto quando non suona nulla (solo in auto), pausa
  con la retromarcia, 11 lingue, condivisione del log dalle Impostazioni (tieni premuto il logo GitHub),
  aggiornamenti tramite KingInstaller.

## Comandi vocali

*Novità 2.4.* Dì quello che vuoi invece di scriverlo.

**Come avviare l'ascolto**

* **CarWeb:** il pulsante 🎤 nella barra delle schede (a sinistra dello scudo), nel menu di navigazione
  rotondo e accanto alla ricerca nella pagina iniziale.
* **CarWebWide (Widescreen):** il pulsante 🎤 nel menu laterale fisso, oppure in alto a destra accanto
  alla ricerca se il menu laterale è spento.
* **Player di Android Auto:** in auto, quando il player "CarWeb Media" mostra il meteo, ha un tasto 🎤 che funziona
  anche quando non ha ancora suonato niente (con una pagina che suona il player tiene i suoi tasti: usa il doppio
  tocco).
* **Volante:** un doppio tocco veloce su ⏮ (indietro) apre il microfono dell'app aperta (CarWeb o
  Widescreen, anche in secondo piano). Serve il player di Android Auto attivo nelle impostazioni, e funziona
  dopo che in CarWeb ha suonato qualcosa (anche se poi l'hai messo in pausa): Android manda i tasti
  multimediali all'ultima app che ha riprodotto audio, non alla scheda mostrata da Android Auto, e la scheda
  del meteo apposta non riproduce niente per non togliere l'audio alla radio. Se suonano la radio dell'auto o
  un'altra app, i tasti vanno a loro (per esempio la radio cambia stazione). Un tocco singolo resta
  "indietro", dopo un attimo di attesa.
* Durante l'ascolto il player di Android Auto mostra "🎤 In ascolto…" con le parole capite fin lì.
* I comandi funzionano anche con l'app dietro: quelli del player ("pausa", "prossimo"…) restano in secondo
  piano, gli altri riportano l'app in primo piano per mostrare e far partire quello che hai chiesto.

La prima volta consenti il microfono sul telefono: lo schermo dell'auto non può mostrare la richiesta.

**Cosa puoi dire** (esempi: i comandi si capiscono in tutte le 11 lingue dell'app, qualunque sia la lingua
impostata)

| Frase | Cosa succede |
|---|---|
| "metti *canzone* su YouTube", "metti *canzone* su YouTube Music" | cerca e fa partire il primo risultato |
| "metti *canzone*" | lo stesso, su YouTube Music se è aperto, altrimenti su YouTube |
| "cerca *qualcosa* su YouTube" | risultati di YouTube |
| "cerca *scarpe* su Amazon", "cerca su Google Maps *pizzeria*" | la ricerca del sito: Amazon ed eBay del tuo paese, Wikipedia nella tua lingua, Reddit, IMDb, Twitch, Netflix, Google Maps, Google News, Booking, Tripadvisor, Subito, GitHub, X, Facebook, TikTok, Pinterest, o un tuo preferito |
| "cerca *qualcosa*" | ricerca sul sito aperto (YouTube, YouTube Music) o col motore di ricerca |
| "cerca *qualcosa* su Google" | ricerca col motore di ricerca scelto |
| "apri *preferito*", "apri repubblica punto it" | apre un preferito (per nome o sito) o un indirizzo detto a voce |
| "indietro", "ricarica", "home" | navigazione della pagina |
| "pausa", "riprendi", "prossimo", "precedente", "prossimo canale", "stazione successiva" | il player di Android Auto |
| "metti *stazione* alla radio", "sintonizza *stazione*", "metti radio *nome*" | fa partire la stazione nella Radio di CarWeb Media, anche fuori dall'elenco del tuo Paese; "metti la radio" riprende l'ultima |
| "guarda *canale* in tv", "metti il canale *nome*", "guarda *canale*" | fa partire il canale nella TV di CarWeb Media, cercando in tutte le tue liste; "guarda la tv" riprende l'ultimo |
| "metti *canzone* dalla mia musica", "metti *canzone* su Media" | i tuoi file, per titolo, artista o cartella: un artista o una cartella fanno partire tutti i loro brani in ordine |
| "cerca *qualcosa* alla radio", "… in tv", "… su Media" | mostra i risultati in quella pagina senza far partire niente |
| "apri la radio", "apri la tv", "apri la mia musica" | apre la pagina |

Con Radio, TV o Media aperte, "metti …" e "cerca …" valgono per quella pagina, e basta dire un nome per farlo partire.
I nomi si capiscono come si dicono: "rai uno" trova "Rai 1 HD", "la sette" trova "La7". Un canale o una stazione tra i
preferiti ha la precedenza. Se "metti radio …" o "guarda …" non trovano niente, la frase va su YouTube come sempre.

Tutto il resto viene cercato sul sito aperto o col motore di ricerca.

**Assistente Google e Gemini:** l'app accetta anche la ricerca vocale standard dei player di Android ("Ok
Google, metti *canzone* su CarWeb"), ma per ora Gemini risponde che l'app non supporta i suoi comandi: usa
il pulsante 🎤 o il doppio tocco su ⏮.

## Sviluppo "Gemini-Powered"

Sarò onesto: le mie conoscenze di programmazione sono... chiamiamole "di base". Questo progetto
esiste solo grazie alle infinite discussioni con Gemini. Abbiamo passato ore in un loop infinito di:

1. Aggiustare una cosa.
2. Romperne altre tre.
3. Litigare sul perché si fosse rotto tutto.
4. Finalmente farlo funzionare.

Dalla versione 2.3 alle discussioni si è aggiunto anche **Claude** (Anthropic), tramite Claude Code: il
player di Android Auto con più schede, CarWeb Media, le correzioni del layout Widescreen e tanta caccia ai bug
sono nati da quelle sessioni.

## Disclaimer & Compatibilità

* **Ci sono dei bug:** Aspettatevi qualche sorpresa qua e là. È un work in progress!
* **Contenuti protetti (DRM):** la WebView di Android offre solo Widevine L3, il livello di protezione software. I siti che per i loro contenuti richiedono il livello hardware (Widevine L1), come spesso fanno le dirette sportive e i servizi di film e serie a pagamento, non possono funzionare: è un limite tecnico della WebView di Android, non dell'app. I contenuti protetti a livello L3, come la musica di Spotify, si riproducono normalmente.
* **Visibilità su Android Auto:** L'app al momento si vede solo se utilizzi un dispositivo di "sblocco" tipo **AAWireless**, una soluzione open source come [aa-proxy-rs](https://github.com/aa-proxy/aa-proxy-rs) (installabile su Raspberry Pi o simili, che tra le sue funzioni include lo sblocco delle app), o se viene installata con [KingInstaller](https://github.com/fcaronte/KingInstaller/releases) (per bypassare le restrizioni del Play Store).
* **Compatibilità & Installazione tramite KingInstaller:**
  * **Samsung & Pixel:** Funziona direttamente senza necessità di Shizuku.
  * **Altri dispositivi:** Funziona tramite l'integrazione con Shizuku in KingInstaller.
  * **Nota per Xiaomi / POCO / Redmi:** Sui dispositivi recenti della famiglia Xiaomi, POCO e Redmi con MIUI o HyperOS, le rigide personalizzazioni del produttore bloccano i metodi di installazione alternativi. Su questi dispositivi il metodo Shizuku non funziona, rendendo i permessi di Root l'unico metodo realmente funzionante.
* **Importante:** Ricordarsi di abilitare le **"Origini sconosciute"** nelle impostazioni sviluppatore di Android Auto sul telefono.
* **Test:** Testata principalmente su **Samsung S24 Ultra**.

## Supporta il progetto

Se ti piace il progetto, puoi offrirmi un caffè per supportare le ore di sonno perse a scrivere questo codice a litigare con Gemini e Claude 😂:
* **[Donate via PayPal](https://www.paypal.com/paypalme/FCaronte/4)**
* **[Buy Me a Coffee](http://buymeacoffee.com/fcaronte)**

## Licenza

Questo progetto è distribuito sotto licenza MIT - vedi il file [LICENSE](LICENSE) per i dettagli.