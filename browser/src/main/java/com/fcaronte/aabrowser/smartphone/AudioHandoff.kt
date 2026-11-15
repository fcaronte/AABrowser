package com.fcaronte.aabrowser.smartphone

/**
 * Il passaggio dell'audio tra il browser e un'app del telefono sullo schermo virtuale. Quando l'app comincia a suonare mentre il
 * browser suona, Android toglie il focus al browser da solo (la WebView lo chiede a Chromium, l'app per conto suo) e la pagina
 * va in pausa: «l'app prende il focus» succede già, e non si chiede niente a nome dell'app (ogni richiesta fatta dal nostro
 * pacchetto toglierebbe il focus a Chromium e fermerebbe la pagina). Quello che manca è l'altra metà: quando l'app smette, il
 * browser che suonava riprende.
 *
 * Qui c'è solo la decisione, senza Android (si prova con i test): ricevuti il numero dei lettori di media attivi nel telefono e
 * lo stato del browser, dice quando riprendere. Riprende solo se è sicuro che l'app lo ha interrotto:
 *  - un lettore è partito mentre il browser suonava e il browser si è fermato subito dopo ([windowMs]);
 *  - dopo la pausa è rimasto qualcosa a suonare per almeno [confirmMs], e ha cominciato entro [windowMs] dalla pausa (un lettore
 *    che sparisce subito era del browser stesso, quindi era una pausa dell'utente: mai riprendere dopo una pausa voluta, né
 *    per un audio qualunque che parte più tardi);
 *  - poi il telefono è rimasto in silenzio per [settleMs] (tra un brano e l'altro dell'app non si riprende).
 * Se il browser torna a suonare da solo o per mano dell'utente, non c'è più niente da riprendere; dopo [maxPendingMs] si rinuncia.
 */
class AudioHandoff(
    private val windowMs: Long = WINDOW_MS,
    private val confirmMs: Long = CONFIRM_MS,
    private val settleMs: Long = SETTLE_MS,
    private val maxPendingMs: Long = MAX_PENDING_MS,
) {
    enum class Action { NONE, RESUME_BROWSER }

    private var players = 0
    private var browserPlaying = false

    // Un lettore è partito mentre il browser suonava (candidato a essere l'app)
    private var startedOverAt = NEVER

    // L'app ha interrotto il browser: da quando, da quando suona qualcosa, se è confermato, da quando c'è silenzio
    private var pendingSince = NEVER
    private var externalSince = NEVER
    private var confirmed = false
    private var silentSince = NEVER

    val isPending: Boolean get() = pendingSince != NEVER

    /** Lo stato di partenza (all'apertura della prima app): nessun passaggio in corso, quello che suona già non è un'interruzione. */
    fun reset(playerCount: Int, browserPlaying: Boolean) {
        players = playerCount
        this.browserPlaying = browserPlaying
        startedOverAt = NEVER
        clearPending()
    }

    /** I lettori di media attivi nel telefono (browser compreso) sono [count]. */
    fun onPlayers(count: Int, now: Long) {
        if (browserPlaying && count > players && pendingSince == NEVER) startedOverAt = now
        players = count
        if (pendingSince != NEVER) track(now)
    }

    /** Il browser suona o no, come lo vede il player di Android Auto. */
    fun onBrowser(playing: Boolean, now: Long) {
        if (playing == browserPlaying) return
        browserPlaying = playing
        if (playing) {
            // Suona di nuovo (l'utente, o la pagina da sola): niente da riprendere
            clearPending()
            startedOverAt = NEVER
            return
        }
        // Si è fermato subito dopo che un lettore è partito: l'app gli ha preso l'audio
        if (startedOverAt != NEVER && now - startedOverAt <= windowMs) {
            pendingSince = now
            externalSince = NEVER
            confirmed = false
            silentSince = NEVER
            track(now)
        }
        startedOverAt = NEVER
    }

    /** Da chiamare ogni mezzo secondo circa: dice se il browser deve riprendere adesso. */
    fun tick(now: Long): Action {
        if (startedOverAt != NEVER && now - startedOverAt > windowMs) startedOverAt = NEVER
        if (pendingSince == NEVER) return Action.NONE
        if (now - pendingSince > maxPendingMs) {
            clearPending()
            return Action.NONE
        }
        // Dopo la pausa non è rimasto a suonare niente (o non per abbastanza): era una pausa dell'utente, non un'interruzione
        if (!confirmed && externalSince == NEVER && now - pendingSince > windowMs) {
            clearPending()
            return Action.NONE
        }
        if (!confirmed && externalSince != NEVER && now - externalSince >= confirmMs) confirmed = true
        if (confirmed && players == 0 && silentSince == NEVER) silentSince = now
        if (confirmed && silentSince != NEVER && now - silentSince >= settleMs) {
            clearPending()
            return Action.RESUME_BROWSER
        }
        return Action.NONE
    }

    // Da quando suona qualcosa (senza interruzioni) e da quando c'è silenzio dopo che l'app ha suonato davvero
    private fun track(now: Long) {
        if (players > 0) {
            silentSince = NEVER
            if (externalSince == NEVER) externalSince = now
        } else {
            externalSince = NEVER
            if (confirmed && silentSince == NEVER) silentSince = now
        }
    }

    private fun clearPending() {
        pendingSince = NEVER
        externalSince = NEVER
        confirmed = false
        silentSince = NEVER
    }

    companion object {
        private const val NEVER = -1L

        /** Il browser deve fermarsi entro questo tempo da quando è partito il lettore dell'app. */
        const val WINDOW_MS = 2_500L

        /** Dopo la pausa, l'app deve continuare a suonare almeno questo tempo perché l'interruzione sia sua. */
        const val CONFIRM_MS = 1_500L

        /** Silenzio da aspettare prima di riprendere: tra un brano e l'altro dell'app non si riprende. */
        const val SETTLE_MS = 2_000L

        /** Dopo mezz'ora si rinuncia: l'utente avrà fatto altro. */
        const val MAX_PENDING_MS = 30 * 60 * 1000L
    }
}
