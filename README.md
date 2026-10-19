# AABrowser: A Modern Revival of the "Old School" Trick

🇮🇹 [Vai alla versione italiana](#aa-browser-una-rinascita-moderna)

## Overview

This project is my personal take on an Android Auto browser. It was born after discovering two
different worlds:

* The modern and sleek [AABrowser by kododake](https://github.com/kododake/AABrowser).
* The ancient but functional [AABrowser by slashmax](https://github.com/slashmax/AABrowser), which
  relies on "old-school" Android Auto tricks to actually work while the car is in motion.

I loved the interface of the new one, but I needed the functionality of the old one. So, I took the
old, ugly, and obsolete base and forced it to become a modern app, blending the best of both worlds.

![AABrowser Demo](assets/demo.gif)

## ⚠️ SAFETY WARNING

**DO NOT USE THIS APP WHILE DRIVING.**
This application is intended for use by passengers only or for testing purposes while the vehicle is
stationary. Using a web browser while operating a vehicle is extremely dangerous and illegal in most
jurisdictions. The developer is not responsible for any accidents, injuries, or damages resulting
from the misuse of this application. Use at your own risk.

## Main features

* **Two apps on Android Auto:** **AABrowser**, the full browser with tabs, and **AAWBrowser
  (Widescreen, Beta)**, which uses the whole map area, in full screen or in split view next to the player
  or the app menu, with a fixed side menu. *Beta limits:* only the full-screen Android Auto keyboard,
  no double-tap zoom, no drag gestures (you seek by tapping the progress bar).
* **Android Auto player connected to the page:** title, artist, cover and controls of what is playing.
  With several tabs the last started playback takes the player. Skip buttons on Spotify, YouTube Music,
  YouTube playlists and on every site that declares its tracks (Media Session).
* **AAB Media (new in 2.3):** player for the audio and video files of a folder on the phone, with separate
  Audio and Video queues, shuffle, repeat, resume, crossfade between songs, animated visual effects (bars,
  wave, circle) and colors that follow the app theme (light, dark with the phone colors, AMOLED).
* **YouTube:** ad skipping, remembered playback speed (ads and music excluded), tap on the progress
  bar to seek.
* **Typing and voice:** keyboard or microphone for text fields, voice search also on YouTube.
* **Home network sites (new in 2.3):** sites with a self-signed or invalid certificate (Home Assistant,
  Proxmox, home proxy) open after "Continue anyway", and the choice is remembered for that site and
  certificate; local network permission on Android 17.
* **Files for web pages:** file and folder picker without storage permissions.
* **And more:** DRM (Widevine L3), weather on the Android Auto player when nothing plays, pause on
  reverse gear, 11 languages, updates through KingInstaller.

## The "Gemini-Powered" Development

I want to be completely honest: my programming knowledge is... let's say "basic." This entire
project exists thanks to my endless arguments with Gemini. We spent hours in a cycle of:

1. Fixing one thing.
2. Breaking three others.
3. Arguing about why it broke.
4. Finally getting it to work.

Since version 2.3 **Claude** (Anthropic), through Claude Code, has joined the arguments too: the Android
Auto player with multiple tabs, AAB Media, the Widescreen layout fixes and a lot of bug hunting came out
of those sessions.

## Disclaimer & Compatibility

* **It’s a bit buggy:** Expect some "surprises" here and there. It's a work in progress!
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

# AABrowser: Una rinascita moderna del "vecchio trucco"

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

![AABrowser Demo](assets/demo.gif)

## ⚠️ AVVERTENZA DI SICUREZZA

**NON USARE QUESTA APP DURANTE LA GUIDA.**
Questa applicazione è destinata esclusivamente all'uso da parte dei passeggeri o per scopi di test a
veicolo fermo. L'utilizzo di un browser web durante la guida di un veicolo è estremamente pericoloso
e illegale nella maggior parte delle giurisdizioni. Lo sviluppatore non è responsabile per eventuali
incidenti, lesioni o danni derivanti dall'uso improprio di questa applicazione. L'utilizzo è a
proprio rischio e pericolo.

## Funzioni principali

* **Due app su Android Auto:** **AABrowser**, il browser completo con le schede, e **AAWBrowser
  (Widescreen, Beta)**, che usa tutta l'area della mappa, a schermo intero o in split accanto al player
  o al menu delle app, con il menu laterale fisso. *Limiti della Beta:* solo la tastiera a schermo intero
  di Android Auto, niente zoom col doppio tocco, niente trascinamenti (nel video ci si sposta toccando la
  barra di avanzamento).
* **Player di Android Auto collegato alla pagina:** titolo, artista, copertina e comandi di quello che
  suona. Con più schede il player va all'ultima riproduzione avviata. Tasti avanti/indietro su Spotify,
  YouTube Music, playlist di YouTube e su ogni sito che dichiara i suoi brani (Media Session).
* **AAB Media (novità 2.3):** lettore per i file audio e video di una cartella del telefono, con code
  separate Audio e Video, casuale, ripeti, ripresa, dissolvenza tra i brani, effetti animati (barre, onda,
  cerchio) e colori che seguono il tema dell'app (chiaro, scuro con i colori del telefono, AMOLED).
* **YouTube:** salto delle pubblicità, velocità di riproduzione ricordata (pubblicità e musica escluse),
  tocco sulla barra di avanzamento per spostarsi nel video.
* **Scrittura e voce:** tastiera o microfono per i campi di testo, ricerca vocale anche su YouTube.
* **Siti della rete di casa (novità 2.3):** i siti con certificato autofirmato o non valido (Home
  Assistant, Proxmox, proxy di casa) si aprono con "Continua comunque" e la scelta resta ricordata per quel
  sito e quel certificato; permesso rete locale su Android 17.
* **File per le pagine web:** scelta di file e cartelle senza permessi di archiviazione.
* **E poi:** DRM (Widevine L3), meteo sul player di Android Auto quando non suona nulla, pausa con la
  retromarcia, 11 lingue, aggiornamenti tramite KingInstaller.

## Sviluppo "Gemini-Powered"

Sarò onesto: le mie conoscenze di programmazione sono... chiamiamole "di base". Questo progetto
esiste solo grazie alle infinite discussioni con Gemini. Abbiamo passato ore in un loop infinito di:

1. Aggiustare una cosa.
2. Romperne altre tre.
3. Litigare sul perché si fosse rotto tutto.
4. Finalmente farlo funzionare.

Dalla versione 2.3 alle discussioni si è aggiunto anche **Claude** (Anthropic), tramite Claude Code: il
player di Android Auto con più schede, AAB Media, le correzioni del layout Widescreen e tanta caccia ai bug
sono nati da quelle sessioni.

## Disclaimer & Compatibilità

* **Ci sono dei bug:** Aspettatevi qualche sorpresa qua e là. È un work in progress!
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