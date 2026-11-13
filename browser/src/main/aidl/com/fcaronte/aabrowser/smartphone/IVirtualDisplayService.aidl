// Servizio che gira nel processo avviato da Shizuku con i diritti dell'utente shell (uid 2000), gli stessi di
// "adb shell": possiede i display virtuali delle app del telefono, ci avvia le app e ci inietta i tocchi.
package com.fcaronte.aabrowser.smartphone;

import android.content.ComponentName;
import android.view.MotionEvent;
import android.view.Surface;

interface IVirtualDisplayService {
    // Convenzione di Shizuku: destroy() ha questo numero di transazione, così lo scollegamento può fermare il processo.
    void destroy() = 16777114;

    // Crea un display virtuale fidato che disegna in [surface] (anche null: le app girano, non si disegna niente).
    // [keyboardOnDisplay]: la tastiera si apre sul display (l'auto) o sullo schermo del telefono.
    // Risponde "OK:<id del display>" o "ERROR:<motivo>".
    String createDisplay(in Surface surface, int width, int height, int dpi, boolean keyboardOnDisplay) = 1;

    // Nuova superficie (null: in pausa) e nuove misure. "OK" o "ERROR:<motivo>".
    String updateDisplay(int displayId, in Surface surface, int width, int height, int dpi) = 2;

    // Rilascia il display: le sue attività tornano sullo schermo del telefono. "OK" o "ERROR:<motivo>".
    String releaseDisplay(int displayId) = 3;

    // Avvia l'attività sul display (displayId 0 o meno: lo schermo del telefono), o ci porta il suo task.
    String launch(int displayId, in ComponentName component) = 4;

    // Un tocco in pixel del display: il servizio imposta il display dell'evento e lo inietta.
    oneway void touch(int displayId, in MotionEvent event) = 5;

    // Pressione di un tasto (giù e su) sul display, per esempio KEYCODE_BACK.
    oneway void key(int displayId, int keyCode) = 6;

    // Chiude l'app del display, come toglierla dalle recenti: "ok", "none" (non c'era niente) o l'errore.
    String closeApp(int displayId) = 7;

    // Il tasto della tastiera: se è aperta la chiude (Indietro), altrimenti la apre per il campo di testo che ha il
    // focus (un tocco sul campo). "shown", "hidden", "no field", "old android" o "error: ...".
    String toggleKeyboard(int displayId) = 8;

    // Gruppo del display: 1 = quello del telefono (il telefono deve restare acceso, altrimenti il display si ferma),
    // 0 = un gruppo suo (il telefono si può spegnere), -1 = non si sa.
    int phoneGroup(int displayId) = 9;

    // Tiene sveglio il telefono (lo accende subito) con un blocco dello schermo preso dalla shell; false lo lascia.
    oneway void keepPhoneAwake(boolean on) = 10;

    // Riga di diagnosi: versione di Android, uid, display aperti.
    String info() = 11;
}
