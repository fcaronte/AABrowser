package com.fcaronte.aabrowser.model

import java.util.UUID

/**
 * Le modifiche ai preferiti, senza Android: aggiungere, cambiare, togliere, spostare. Le usano la Main (FavoritesViewModel, sulla
 * sua lista) e la home della Wide (HomeApi, sulla lista salvata), così le due fanno le stesse cose allo stesso modo: una pagina
 * di CarWeb o un'app del telefono non diventa un preferito, un indirizzo già presente non si ripete, un preferito di serie
 * (la pagina di CarWeb vera, mai un doppione) non si elimina né cambia indirizzo.
 */
object FavoriteEdits {
    /** Quello che l'utente scrive nel modulo; [desktop], [mobileZoom] e [desktopZoom] null = nessuna scelta (valgono quelli di base). */
    data class Input(
        val name: String,
        val url: String,
        val color: Long = DEFAULT_COLOR,
        val faviconUrl: String? = null,
        val desktop: Boolean? = null,
        val mobileZoom: Float? = null,
        val desktopZoom: Float? = null,
    )

    enum class Result { OK, NOT_ALLOWED, NOT_FOUND, INVALID }

    const val DEFAULT_COLOR = 0xFF2196F3
    const val MIN_ZOOM = 0.25f
    const val MAX_ZOOM = 1.5f

    private val SCHEME = Regex("^[a-z][a-z0-9+.-]*:")
    private val HOST_AND_PORT = Regex("^[^/:]+:\\d+(/|$)")

    // Un sito dopo lo schema e niente spazi: più tollerante di java.net.URI, che non legge l'host di nomi come casa_server
    private val WEB_ADDRESS = Regex("^https?://[^/?#\\s]+([/?#]\\S*)?$", RegexOption.IGNORE_CASE)

    /**
     * L'indirizzo scritto dall'utente come lo salviamo: senza http:// o https:// si parte da https:// (come il modulo della
     * Main), anche con una porta (casa:8123). null se è vuoto o non è un indirizzo web: un altro schema (javascript:, intent:,
     * file:, data:) o nessun sito.
     */
    fun normalizeAddress(text: String): String? {
        val typed = text.trim()
        if (typed.isEmpty()) return null
        val lower = typed.lowercase()
        val address = when {
            lower.startsWith("http://") || lower.startsWith("https://") -> typed
            SCHEME.containsMatchIn(lower) && !HOST_AND_PORT.containsMatchIn(lower) -> return null
            else -> "https://$typed"
        }
        return if (WEB_ADDRESS.matches(address)) address else null
    }

    fun clampZoom(zoom: Float?): Float? = zoom?.coerceIn(MIN_ZOOM, MAX_ZOOM)

    // L'indirizzo pronto da salvare, o null se non c'è un indirizzo web che si possa aggiungere
    private fun checkedAddress(text: String): String? {
        val typed = text.trim()
        if (typed.isNotEmpty() && !canAdd(typed)) return null
        return normalizeAddress(typed)?.takeIf { canAdd(it) }
    }

    // Perché [checkedAddress] ha detto no: una pagina di CarWeb, un'app o uno schema pericoloso non sono consentiti (come nella
    // Main); il resto (vuoto, senza sito) non è un indirizzo
    private fun invalidOrNotAllowed(text: String): Result {
        val typed = text.trim()
        if (typed.isEmpty()) return Result.INVALID
        if (!canAdd(typed)) return Result.NOT_ALLOWED
        return normalizeAddress(typed)?.let { Result.NOT_ALLOWED } ?: Result.INVALID
    }

    /** Si può aggiungere come preferito: non una pagina di CarWeb, non un'app del telefono, non un indirizzo pericoloso. */
    fun canAdd(url: String): Boolean = !FavoritesRepository.isOwnPage(url) && !FavoritesRepository.isUnsafeUrl(url)

    /** Aggiunge in fondo; un indirizzo che c'è già non si ripete (OK, niente da fare). */
    fun add(list: MutableList<FavoriteSite>, input: Input, newId: () -> String = { UUID.randomUUID().toString() }): Result {
        val address = checkedAddress(input.url) ?: return invalidOrNotAllowed(input.url)
        val known = FavoritesRepository.normalizeUrl(address)
        if (list.any { FavoritesRepository.normalizeUrl(it.url) == known }) return Result.OK
        list.add(
            FavoriteSite(
                id = newId(),
                name = input.name.trim().ifEmpty { address },
                url = address,
                color = input.color,
                faviconUrl = input.faviconUrl?.takeIf { it.isNotBlank() },
                isDesktopMode = input.desktop,
                mobileZoom = clampZoom(input.mobileZoom),
                desktopZoom = clampZoom(input.desktopZoom),
            )
        )
        return Result.OK
    }

    /** Cambia il preferito [id]; uno di serie resta sul suo indirizzo. Un indirizzo nuovo vale quanto uno aggiunto. */
    fun update(list: MutableList<FavoriteSite>, id: String, input: Input): Result {
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return Result.NOT_FOUND
        val site = list[index]
        val fixed = FavoritesRepository.isFixed(site, list)
        val address = if (fixed) site.url else (checkedAddress(input.url) ?: return invalidOrNotAllowed(input.url))
        list[index] = site.copy(
            name = input.name.trim().ifEmpty { site.name },
            url = address,
            color = input.color,
            faviconUrl = input.faviconUrl?.takeIf { it.isNotBlank() },
            isDesktopMode = input.desktop,
            mobileZoom = clampZoom(input.mobileZoom),
            desktopZoom = clampZoom(input.desktopZoom),
        )
        return Result.OK
    }

    /** Toglie il preferito [id]; quello di serie no. */
    fun delete(list: MutableList<FavoriteSite>, id: String): Result {
        val site = list.firstOrNull { it.id == id } ?: return Result.NOT_FOUND
        if (FavoritesRepository.isFixed(site, list)) return Result.NOT_ALLOWED
        list.remove(site)
        return Result.OK
    }

    /** Sposta di un posto: [direction] -1 verso l'inizio, +1 verso la fine; ai bordi non cambia niente. */
    fun move(list: MutableList<FavoriteSite>, id: String, direction: Int): Result {
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return Result.NOT_FOUND
        val target = index + direction.coerceIn(-1, 1)
        if (target < 0 || target >= list.size || target == index) return Result.OK
        val site = list.removeAt(index)
        list.add(target, site)
        return Result.OK
    }
}
