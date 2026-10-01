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

## The "Gemini-Powered" Development

I want to be completely honest: my programming knowledge is... let's say "basic." This entire
project exists thanks to my endless arguments with Gemini. We spent hours in a cycle of:

1. Fixing one thing.
2. Breaking three others.
3. Arguing about why it broke.
4. Finally getting it to work.

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

If you like the project, you can buy me a coffee to support the hours of sleep lost writing this code and arguing with Gemini 😂:
* **[Donate via PayPal](https://www.paypal.com/paypalme/FCaronte/2)**
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

## Sviluppo "Gemini-Powered"

Sarò onesto: le mie conoscenze di programmazione sono... chiamiamole "di base". Questo progetto
esiste solo grazie alle infinite discussioni con Gemini. Abbiamo passato ore in un loop infinito di:

1. Aggiustare una cosa.
2. Romperne altre tre.
3. Litigare sul perché si fosse rotto tutto.
4. Finalmente farlo funzionare.

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

Se ti piace il progetto, puoi offrirmi un caffè per supportare le ore di sonno perse a scrivere questo codice a litigare con Gemini 😂:
* **[Donate via PayPal](https://www.paypal.com/paypalme/FCaronte/2)**
* **[Buy Me a Coffee](http://buymeacoffee.com/fcaronte)**

## Licenza

Questo progetto è distribuito sotto licenza MIT - vedi il file [LICENSE](LICENSE) per i dettagli.