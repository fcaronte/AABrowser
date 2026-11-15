package com.fcaronte.aabrowser.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import androidx.compose.runtime.snapshots.Snapshot
import com.fcaronte.aabrowser.BuildConfig
import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.model.FavoritesRepository
import com.fcaronte.aabrowser.settings.AdBlockSettings
import com.fcaronte.aabrowser.settings.AppSettings
import com.fcaronte.aabrowser.settings.SearchEngine
import com.fcaronte.aabrowser.settings.SidebarAction
import com.fcaronte.aabrowser.settings.SidebarHost
import com.fcaronte.aabrowser.settings.TabBarAction
import com.fcaronte.aabrowser.settings.TabBarMode
import com.fcaronte.aabrowser.settings.ThemeMode
import com.fcaronte.aabrowser.smartphone.VirtualSplit
import com.fcaronte.aabrowser.utils.SettingsSchema.Actions
import com.fcaronte.aabrowser.utils.SettingsSchema.Buttons
import com.fcaronte.aabrowser.utils.SettingsSchema.Checks
import com.fcaronte.aabrowser.utils.SettingsSchema.Choice
import com.fcaronte.aabrowser.utils.SettingsSchema.Cond
import com.fcaronte.aabrowser.utils.SettingsSchema.Group
import com.fcaronte.aabrowser.utils.SettingsSchema.Heading
import com.fcaronte.aabrowser.utils.SettingsSchema.Items
import com.fcaronte.aabrowser.utils.SettingsSchema.Row
import com.fcaronte.aabrowser.utils.SettingsSchema.Stepper
import com.fcaronte.aabrowser.utils.SettingsSchema.Switch
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * Le impostazioni come pagina (https://player.local/settings, assets/player/settings.html): la stessa per la Main, che la
 * mostra in una WebView tutta sua, e per la Wide, che non ha schermate. Come le altre pagine di CarWeb Media i dati vogliono il
 * token che la pagina riceve nel suo HTML (LocalPlayer).
 *
 * Cosa mostrare lo dice [SettingsSchema]; qui ci sono i valori di ora ([values], [data]) e come si scrive ogni scelta
 * ([registry]): la pagina non può cambiare altro che quelle chiavi, con valori controllati. Quello che solo il telefono può
 * fare (selettori di file, finestre per indirizzi e password, riavvio, aprire un link) non passa di qui: lo chiede la pagina
 * al ponte della sua WebView (SettingsWebScreen), e dove il ponte non c'è (la Wide, l'auto) la pagina lo dice.
 */
object SettingsApi {
    /** Cosa deve fare o dire la pagina dopo una scelta o un'azione. */
    internal class Outcome(
        val restart: Boolean = false,
        val permission: String? = null,
        val notice: String? = null,
        val modalTitle: String? = null,
        val modalText: String? = null,
        val cacheLabel: String? = null,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            if (restart) put("restart", true)
            permission?.let { put("permission", it) }
            notice?.let { put("notice", it) }
            if (modalText != null) put("modal", JSONObject().put("title", modalTitle.orEmpty()).put("text", modalText))
            cacheLabel?.let { put("cacheLabel", it) }
        }
    }

    // ------------------------------------------------------------------
    // Pagina e richieste
    // ------------------------------------------------------------------

    fun page(context: Context, token: String, theme: String): WebResourceResponse {
        val app = AppLocale.wrap(context)
        // Solo quello che serve prima della prima risposta: il resto dei testi arriva con i dati, nella lingua di ora
        val strings = JSONObject()
            .put("title", app.getString(R.string.settings_title))
            .put("back", app.getString(R.string.back_button))
            .put("error", app.getString(R.string.settings_page_error))
        val config = JSONObject().put("token", token).put("strings", strings)
        val html = app.assets.open("player/settings.html").bufferedReader().use { it.readText() }
            .replace("/*AAB_CONFIG*/", "window.AAB_SETTINGS = $config;")
            .replace("/*AAB_THEME*/", theme)
        return response(html.toByteArray(Charsets.UTF_8), "text/html", 200, "OK")
    }

    fun handle(context: Context, path: String, url: Uri): WebResourceResponse? = when (path) {
        "/api/settings/get" -> json(snapshot(context, withCache = true))
        "/api/settings/set" -> {
            val key = url.getQueryParameter("key")
            val value = url.getQueryParameter("value")
            // Dopo la modifica la pagina riceve lo stato com'è davvero (un valore rifiutato non cambia niente)
            val outcome = if (key != null && value != null) apply(context, key, value) else null
            if (outcome != null) json(snapshot(context, withCache = false).put("result", outcome.toJson()))
            else response(ByteArray(0), "text/plain", 400, "Bad Request")
        }
        "/api/settings/do" -> {
            val name = url.getQueryParameter("act")
            val outcome = if (name != null) act(context, name, url.getQueryParameter("arg").orEmpty()) else null
            if (outcome != null) json(snapshot(context, withCache = false).put("result", outcome.toJson()))
            else response(ByteArray(0), "text/plain", 400, "Bad Request")
        }
        "/api/settings/qr" -> qr(context, url.getQueryParameter("link"))
        else -> null
    }

    /** Gli indirizzi che le Impostazioni aprono (telefono) o mostrano come codice QR (auto): per id, mai un indirizzo dalla pagina. */
    fun linkUrl(context: Context, id: String): String? = when (id) {
        "readme" -> GitHubRepo.readmeUrl()
        "github" -> GitHubRepo.pageUrl()
        "paypal" -> context.getString(R.string.url_paypal)
        "coffee" -> context.getString(R.string.url_buymeacoffee)
        else -> null
    }

    private fun qr(context: Context, link: String?): WebResourceResponse {
        val address = link?.let { linkUrl(context, it) }
        val bitmap = address?.let { QRCodeUtils.generateQRCode(it, 512) }
            ?: return response(ByteArray(0), "text/plain", 404, "Not Found")
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        return response(out.toByteArray(), "image/png", 200, "OK")
    }

    // ------------------------------------------------------------------
    // Scrivere le scelte
    // ------------------------------------------------------------------

    private class Setting(val read: (Context) -> String, val write: (Context, String) -> Outcome?)

    private val OK = Outcome()

    /** "1" / "0" come arrivano dalla pagina; altro = null (rifiutato). */
    internal fun parseFlag(value: String): Boolean? = when (value) {
        "1" -> true
        "0" -> false
        else -> null
    }

    private fun flagText(on: Boolean) = if (on) "1" else "0"

    private fun MutableMap<String, Setting>.flag(key: String, read: () -> Boolean, write: (Context, Boolean) -> Unit) {
        put(key, Setting({ flagText(read()) }) { c, v -> parseFlag(v)?.let { write(c, it); OK } })
    }

    private fun MutableMap<String, Setting>.flagWith(key: String, read: () -> Boolean, write: (Context, Boolean) -> Outcome?) {
        put(key, Setting({ flagText(read()) }) { c, v -> parseFlag(v)?.let { write(c, it) } })
    }

    private fun MutableMap<String, Setting>.pick(key: String, read: () -> String, write: (Context, String) -> Boolean) {
        put(key, Setting({ read() }) { c, v -> if (write(c, v)) OK else null })
    }

    /** Un numero intero di [range], solo i valori che il passo [step] raggiunge partendo dal minimo. */
    private fun MutableMap<String, Setting>.number(
        key: String, range: IntRange, step: Int, read: () -> Int, write: (Context, Int) -> Unit
    ) {
        put(key, Setting({ read().toString() }) { c, v ->
            v.toIntOrNull()?.takeIf { it in range && (it - range.first) % step == 0 }?.let { write(c, it); OK }
        })
    }

    private val registry: Map<String, Setting> = buildMap {
        // --- App ---
        flag("force_theme", { AppSettings.forceTheme.value }) { c, v -> AppSettings.setForceTheme(c, v) }
        pick("theme_mode", { AppSettings.themeMode.value.name }) { c, v ->
            ThemeMode.entries.firstOrNull { it.name == v }?.also { AppSettings.setThemeMode(c, it) } != null
        }
        flag("dashboard_three_columns", { AppSettings.dashboardThreeColumns.value }) { c, v -> AppSettings.setDashboardThreeColumns(c, v) }
        flag("open_dashboard_on_new_tab", { AppSettings.openDashboardOnNewTab.value }) { c, v -> AppSettings.setOpenDashboardOnNewTab(c, v) }
        // Pagina iniziale: acceso sceglie la prima voce dell'elenco (siti, poi app con la stella), spento nessuna
        put("auto_open_enabled", Setting({ flagText(AppSettings.autoOpenFavoriteId.value != null) }) { c, v ->
            when (parseFlag(v)) {
                true -> {
                    if (AppSettings.autoOpenFavoriteId.value != null) OK
                    else autoOpenOptions(c).firstOrNull()?.let { AppSettings.setAutoOpenFavoriteId(c, it.first); OK }
                }
                false -> { AppSettings.setAutoOpenFavoriteId(c, null); OK }
                null -> null
            }
        })
        put("auto_open_favorite", Setting({ AppSettings.autoOpenFavoriteId.value.orEmpty() }) { c, v ->
            if (autoOpenOptions(c).any { it.first == v }) { AppSettings.setAutoOpenFavoriteId(c, v); OK } else null
        })
        number("audio_boost", 0..15, 1, { AppSettings.audioBoostDb.value.roundToInt() }) { c, n -> AppSettings.setAudioBoostDb(c, n.toFloat()) }
        flag("autoplay", { AppSettings.autoplayMedia.value }) { c, v -> AppSettings.setAutoplayMedia(c, v) }
        // Il lettore multimediale e il meteo si applicano al riavvio dell'app: la pagina lo chiede dove può (telefono)
        flagWith("media_player", { AppSettings.mediaPlayerEnabled.value }) { c, v ->
            AppSettings.setMediaPlayerEnabled(c, v)
            Outcome(restart = true)
        }
        flag("player_card_opens_main", { AppSettings.playerCardOpensMain.value }) { c, v -> AppSettings.setPlayerCardOpensMain(c, v) }
        pick("youtube_video_skip", { if (AppSettings.youtubeVideoSkip.value) "skip" else "seek" }) { c, v ->
            if (v == "skip" || v == "seek") { AppSettings.setYouTubeVideoSkip(c, v == "skip"); true } else false
        }
        flag("share_to_carweb", { AppSettings.shareToCarWeb.value }) { c, v -> AppSettings.setShareToCarWeb(c, v) }
        pick("share_always", { if (AppSettings.shareAlways.value) "always" else "car" }) { c, v ->
            if (v == "always" || v == "car") { AppSettings.setShareAlways(c, v == "always"); true } else false
        }
        flagWith("weather_widget", { AppSettings.weatherWidgetEnabled.value && AppSettings.mediaPlayerEnabled.value }) { c, v ->
            if (!AppSettings.mediaPlayerEnabled.value) null
            else {
                AppSettings.setWeatherWidgetEnabled(c, v)
                val located = c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                Outcome(restart = true, permission = if (v && !located) "weather" else null)
            }
        }
        flag("preload_favorites", { AppSettings.preloadFavorites.value }) { c, v -> AppSettings.setPreloadFavorites(c, v) }
        number("preload_favorites_count", 1..8, 1, { AppSettings.preloadFavoritesCount.value }) { c, n -> AppSettings.setPreloadFavoritesCount(c, n) }
        flag("persistent_navigation", { AppSettings.persistentNavigation.value }) { c, v -> AppSettings.setPersistentNavigation(c, v) }
        put("tab_bar_enabled", Setting({ flagText(AppSettings.tabBarMode.value != TabBarMode.OFF) }) { c, v ->
            parseFlag(v)?.let { on ->
                // Già acceso (anche "sempre visibile"): non si cambia la scelta fatta
                if (on && AppSettings.tabBarMode.value == TabBarMode.OFF) AppSettings.setTabBarMode(c, TabBarMode.AUTO_HIDE)
                if (!on) AppSettings.setTabBarMode(c, TabBarMode.OFF)
                OK
            }
        })
        pick("tab_bar_mode", { AppSettings.tabBarMode.value.name }) { c, v ->
            val mode = listOf(TabBarMode.AUTO_HIDE, TabBarMode.ALWAYS_ON).firstOrNull { it.name == v }
            mode?.let { AppSettings.setTabBarMode(c, it) } != null
        }
        pick("tab_bar_actions", { TabBarAction.serialize(AppSettings.tabBarActions.value) }) { c, v ->
            AppSettings.setTabBarActions(c, TabBarAction.parse(v))
            true
        }
        number(
            "tab_bar_visible", 1..TabBarAction.MAX_VISIBLE, 1,
            { TabBarAction.visibleCount(AppSettings.tabBarVisibleCount.value, AppSettings.tabBarActions.value.size) }
        ) { c, n -> AppSettings.setTabBarVisibleCount(c, n) }
        flag("main_dock", { AppSettings.mainDock.value }) { c, v -> AppSettings.setMainDock(c, v) }
        pick("sidebar_actions", { SidebarAction.serialize(AppSettings.sidebarActions.value) }) { c, v ->
            // Home c'è sempre (SidebarAction.parse); un id sconosciuto si salta
            AppSettings.setSidebarActions(c, SidebarAction.parse(v))
            true
        }
        flag("virtual_standby", { AppSettings.virtualStandby.value }) { c, v -> AppSettings.setVirtualStandby(c, v) }
        pick("virtual_app_scale", { AppSettings.virtualAppScale.value.toString() }) { c, v ->
            v.toIntOrNull()?.takeIf { it in VIRTUAL_APP_SCALES }?.let { AppSettings.setVirtualAppScale(c, it) } != null
        }
        flag("virtual_split_enabled", { AppSettings.virtualSplitEnabled.value }) { c, v -> AppSettings.setVirtualSplitEnabled(c, v) }
        number(
            "virtual_split_fraction", VirtualSplit.MIN_PERCENT..VirtualSplit.MAX_PERCENT, VirtualSplit.STEP_PERCENT,
            { AppSettings.virtualSplitFraction.value }
        ) { c, n -> AppSettings.setVirtualSplitFraction(c, n) }
        pick("virtual_split_side", { AppSettings.virtualSplitSide.value.name }) { c, v ->
            VirtualSplit.Side.entries.firstOrNull { it.name == v }?.also { AppSettings.setVirtualSplitSide(c, it) } != null
        }
        number(
            "bt_pointer_speed", BtInput.MIN_SPEED..BtInput.MAX_SPEED, BtInput.SPEED_STEP,
            { AppSettings.btPointerSpeed.value }
        ) { c, n -> AppSettings.setBtPointerSpeed(c, n) }
        flag("force_english", { AppSettings.forceEnglish.value }) { c, v -> AppSettings.setForceEnglish(c, v) }
        number("ui_scale", 50..150, 5, { (AppSettings.uiScale.value * 100).roundToInt() }) { c, n -> AppSettings.setUiScale(c, n / 100f) }
        flag("custom_search_engine", { AppSettings.customSearchEngine.value }) { c, v -> AppSettings.setCustomSearchEngine(c, v) }
        pick("search_engine", { AppSettings.searchEngine.value.name }) { c, v ->
            SearchEngine.entries.firstOrNull { it.name == v }?.also { AppSettings.setSearchEngine(c, it) } != null
        }

        // --- CarWebWide ---
        flag("wide_reopen_last_page", { AppSettings.wideReopenLastPage.value }) { c, v -> AppSettings.setWideReopenLastPage(c, v) }
        pick("wide_sidebar_size", { AppSettings.wideSidebarSize.value.toString() }) { c, v ->
            v.toIntOrNull()?.takeIf { it in AppSettings.wideSidebarSizes }?.let { AppSettings.setWideSidebarSize(c, it) } != null
        }

        // --- Modalità passeggero ---
        flag("passenger_mode", { AppSettings.passengerMode.value }) { c, v -> AppSettings.setPassengerMode(c, v) }
        flag("passenger_keyboard", { AppSettings.passengerKeyboard.value }) { c, v -> AppSettings.setPassengerKeyboard(c, v) }

        // --- Pagine web ---
        flag("dark_pages", { AppSettings.darkPages.value }) { c, v -> AppSettings.setDarkPages(c, v) }
        number("desktop_scale", 25..150, 5, { (AppSettings.desktopScale.value * 100).roundToInt() }) { c, n -> AppSettings.setDesktopScale(c, n / 100f) }
        number("display_scale", 25..150, 5, { (AppSettings.displayScale.value * 100).roundToInt() }) { c, n -> AppSettings.setDisplayScale(c, n / 100f) }
        flag("adblock", { AdBlockSettings.isEnabled.value }) { c, v -> AdBlockSettings.setEnabled(c, v) }
        pick("adblock_list", { AdBlockSettings.listKey.value }) { c, v ->
            val known = AdBlockLists.SOURCES.any { it.key == v }
            // Una lista a piacere vuole prima il suo indirizzo (la finestra del telefono)
            val custom = v == AdBlockLists.CUSTOM_KEY && AdBlockSettings.customListUrl.value.isNotBlank()
            if (known || custom) { AdBlockLists.choose(c, v); true } else false
        }
        flag("adblock_youtube", { AdBlockSettings.isYouTubeEnabled.value }) { c, v -> AdBlockSettings.setYouTubeEnabled(c, v) }
        flag("adblock_youtube_prune", { AdBlockSettings.isYouTubePruneEnabled.value }) { c, v -> AdBlockSettings.setYouTubePruneEnabled(c, v) }
        flag("force_max_youtube_quality", { AppSettings.forceMaxYouTubeQuality.value }) { c, v -> AppSettings.setForceMaxYouTubeQuality(c, v) }
        pick("youtube_quality", { AppSettings.youtubeQuality.value }) { c, v ->
            AppSettings.youtubeQualityOptions.any { it.first == v }.also { if (it) AppSettings.setYouTubeQuality(c, v) }
        }
        flag("sponsorblock", { AppSettings.sponsorBlock.value }) { c, v -> AppSettings.setSponsorBlock(c, v) }
        pick("sponsorblock_level", { AppSettings.sponsorBlockLevel.value }) { c, v ->
            (v in SponsorBlock.LEVELS).also { if (it) AppSettings.setSponsorBlockLevel(c, v) }
        }
        flag("multi_window", { AppSettings.multiWindow.value }) { c, v -> AppSettings.setMultiWindow(c, v) }
        flag("auto_site_permissions", { AppSettings.autoSitePermissions.value }) { c, v -> AppSettings.setAutoSitePermissions(c, v) }

        // Solo build di prova: l'aggiornamento come con una versione vecchia (banner, notifica, download)
        put("debug_update_test", Setting({ c -> flagText(BuildConfig.DEBUG && UpdateManager.isTestingOldVersion(c)) }) { c, v ->
            if (!BuildConfig.DEBUG) null else parseFlag(v)?.let { UpdateManager.setTestingOldVersion(c, it); OK }
        })
    }

    /** Le chiavi che la pagina può scrivere (per il test che le confronta con le righe di [SettingsSchema]). */
    internal val settingKeys: Set<String> get() = registry.keys

    private val VIRTUAL_APP_SCALES = listOf(70, 85, 100, 125, 150)

    /** Applica una scelta della pagina; null se la chiave o il valore non sono validi. */
    internal fun apply(context: Context, key: String, value: String): Outcome? {
        val outcome = registry[key]?.write?.invoke(context, value)
        // Scritto fuori dalla UI di Compose (qui, o nella Wide senza nessuna schermata aperta): chi osserva lo stato lo sa solo
        // dopo la notifica, e la Wide si riadatta da lì (il dock si ricostruisce, la dimensione cambia)
        if (outcome != null) Snapshot.sendApplyNotifications()
        return outcome
    }

    // ------------------------------------------------------------------
    // Azioni (pulsanti)
    // ------------------------------------------------------------------

    /** Un'azione di un pulsante; null se il nome o l'argomento non sono validi. */
    internal fun act(context: Context, name: String, arg: String): Outcome? {
        val app = AppLocale.wrap(context)
        return when (name) {
            "favorites_reset" -> {
                FavoritesRepository(app).resetToDefaults()
                FavoritesRepository.notifyExternalChange()
                Outcome(notice = app.getString(R.string.status_loaded))
            }
            "favorites_add_defaults" -> {
                val added = FavoritesRepository(app).addMissingDefaults()
                if (added > 0) FavoritesRepository.notifyExternalChange()
                Outcome(
                    notice = if (added > 0) app.resources.getQuantityString(R.plurals.favorites_added_count, added, added)
                    else app.getString(R.string.no_new_favorites)
                )
            }
            "iptv_remove" -> {
                val section = sectionOf(arg.substringBefore('|')) ?: return null
                val id = arg.substringAfter('|', "").takeIf { it.isNotEmpty() } ?: return null
                IptvSources.remove(context, section, id)
                OK
            }
            "site_permission_remove" -> {
                val permission = SitePermission.entries.firstOrNull { it.key == arg.substringAfterLast('|') } ?: return null
                val origin = arg.substringBeforeLast('|').takeIf { it.isNotEmpty() } ?: return null
                SitePermissions.init(context)
                SitePermissions.remove(origin, permission)
                OK
            }
            "adblock_update" -> {
                AdBlockLists.refresh(context)
                OK
            }
            "whats_new" -> WhatsNew.latest(app)?.let {
                Outcome(modalTitle = app.getString(R.string.whats_new_title, it.versionName), modalText = it.text)
            }
            "cache_size" -> Outcome(cacheLabel = cacheLabel(context, app))
            "clear_data" -> clearData(context, app, arg.split(',').filter { it.isNotEmpty() }.toSet())
            else -> null
        }
    }

    private fun sectionOf(name: String): IptvSources.Section? =
        IptvSources.Section.entries.firstOrNull { it.name.equals(name, ignoreCase = true) }

    private fun clearData(context: Context, app: Context, what: Set<String>): Outcome? {
        if (what.isEmpty() || !what.all { it in CLEARABLE }) return null
        if ("cache" in what) onMain { AppSettings.clearCache(context) }
        if ("cookies" in what) onMain { AppSettings.clearCookies() }
        if ("certificates" in what) SslExceptions.clear(context)
        if ("permissions" in what) SitePermissions.clear(context)
        return Outcome(notice = app.getString(R.string.data_cleared), cacheLabel = if ("cache" in what) cacheLabel(context, app) else null)
    }

    private val CLEARABLE = setOf("cache", "cookies", "certificates", "permissions")

    private fun cacheLabel(context: Context, app: Context): String = app.getString(R.string.cache_label, CacheSize.calculate(context))

    /** La WebView e i cookie si toccano dal thread principale: la pagina chiede da un thread della WebView e aspetta. */
    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val done = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        done.await(10, TimeUnit.SECONDS)
    }

    // ------------------------------------------------------------------
    // Cosa mostrare
    // ------------------------------------------------------------------

    /** I siti preferiti e le app del telefono con la stella (AutoOpenFavorite), come chiave e nome. */
    private fun autoOpenOptions(context: Context): List<Pair<String, String>> =
        AutoOpenFavorite.options(context, FavoritesRepository(AppLocale.wrap(context)).loadFavorites())

    /** Tutto quello che la pagina disegna e i valori di ora. [withCache]: anche la dimensione della cache (si legge dal disco). */
    internal fun snapshot(context: Context, withCache: Boolean): JSONObject {
        val app = AppLocale.wrap(context)
        val debug = BuildConfig.DEBUG
        return JSONObject()
            .put("strings", strings(app))
            .put("theme", LocalPlayer.themeCss(app))
            .put("sections", sections(app, debug))
            .put("values", values(context, app))
            .put("data", data(context, app, withCache))
            .put("footer", footer(app))
    }

    private fun strings(app: Context): JSONObject = JSONObject()
        .put("title", app.getString(R.string.settings_title))
        .put("back", app.getString(R.string.back_button))
        .put("up", app.getString(R.string.tab_bar_action_up))
        .put("down", app.getString(R.string.tab_bar_action_down))
        .put("mainOnly", app.getString(R.string.sidebar_action_main_only))
        .put("error", app.getString(R.string.settings_page_error))
        .put("ok", app.getString(R.string.ok_button))
        .put("close", app.getString(R.string.close_button))
        .put("areYouSure", app.getString(R.string.are_you_sure))
        .put("restartNeeded", app.getString(R.string.settings_restart_needed))
        .put("weatherPermission", app.getString(R.string.weather_permission_toast))
        .put("allowed", app.getString(R.string.site_permission_allowed))
        .put("blocked", app.getString(R.string.site_permission_blocked))
        .put("siteState", app.getString(R.string.site_permission_state))
        .put("siteRemove", app.getString(R.string.site_permission_remove))
        .put("remove", app.getString(R.string.iptv_remove))
        .put("qrTitle", app.getString(R.string.qr_code_title))
        .put("qrLabel", app.getString(R.string.qr_code_label))
        .put("logsPhoneOnly", app.getString(R.string.share_logs_phone_only))

    private fun footer(app: Context): JSONObject = JSONObject()
        .put("github", app.getString(R.string.settings_footer_github))
        .put("githubLabel", app.getString(R.string.github_label))
        .put("donation", app.getString(R.string.settings_footer_donation))
        .put("paypal", app.getString(R.string.paypal_label))
        .put("coffee", app.getString(R.string.buymeacoffee_label))

    /**
     * Valori che si leggono soltanto: servono alle condizioni, ai riassunti e ai massimi delle righe. Sono qui in un elenco
     * solo perché il test dello schema (SettingsSchemaTest) sappia quali chiavi ci sono oltre a quelle che si scrivono.
     */
    private val derived: Map<String, (Context, Context) -> String> = mapOf(
        "tab_bar_shown_count" to { _, _ -> AppSettings.tabBarActions.value.size.toString() },
        "tab_bar_has_choice" to { _, _ -> flagText(AppSettings.tabBarActions.value.size > 1) },
        "sidebar_actions_count" to { _, _ -> AppSettings.sidebarActions.value.size.toString() },
        "has_whats_new" to { _, app -> flagText(WhatsNew.latest(app) != null) },
        "auto_open_has_options" to { c, _ -> flagText(autoOpenOptions(c).isNotEmpty()) },
        "adblock_custom_url" to { _, _ -> AdBlockSettings.customListUrl.value },
        "adblock_custom_set" to { _, _ -> flagText(AdBlockSettings.customListUrl.value.isNotBlank()) },
        "adblock_list_info" to { _, app -> adBlockInfo(app, AdBlockLists.info.value) },
    )

    internal val derivedKeys: Set<String> get() = derived.keys

    private fun values(context: Context, app: Context): JSONObject {
        val values = JSONObject()
        registry.forEach { (key, setting) -> values.put(key, setting.read(context)) }
        derived.forEach { (key, read) -> values.put(key, read(context, app)) }
        return values
    }

    /** Stato della lista dei siti pubblicitari: fonte, quanti siti, quando è stata scaricata. */
    private fun adBlockInfo(app: Context, info: AdBlockLists.Info): String {
        val locale = app.resources.configuration.locales[0]
        val state = when {
            info.updating -> app.getString(R.string.adblock_lists_updating)
            info.count == 0 -> app.getString(R.string.adblock_lists_none)
            else -> app.getString(
                R.string.adblock_lists_info,
                info.name,
                NumberFormat.getIntegerInstance(locale).format(info.count),
                DateFormat.getDateInstance(DateFormat.SHORT, locale).format(Date(info.updatedAt))
            )
        }
        return if (info.failed) state + " · " + app.getString(R.string.adblock_lists_failed) else state
    }

    private fun options(list: List<Pair<String, String>>) = JSONArray().apply {
        list.forEach { (value, text) -> put(JSONArray().put(value).put(text)) }
    }

    private fun actionsList(app: Context, id: String, label: Int, on: Boolean, essential: Boolean, mainOnly: Boolean) =
        JSONObject().put("id", id).put("label", app.getString(label)).put("on", on).put("essential", essential).put("mainOnly", mainOnly)

    /** Un elenco di una lista di TV, Radio o Podcast: solo nome e tipo, mai indirizzi né password. */
    private fun sourcesList(app: Context, section: IptvSources.Section): JSONArray {
        val isPodcast = section == IptvSources.Section.PODCAST
        return JSONArray().apply {
            IptvSources.list(app, section).forEach { source ->
                put(
                    JSONObject().put("id", source.id).put("title", source.name)
                        .put(
                            "sub",
                            when (source.type) {
                                IptvSources.Type.DEFAULT_M3U -> app.getString(R.string.iptv_source_default)
                                IptvSources.Type.DEFAULT_COUNTRY -> app.getString(R.string.radio_source_default)
                                IptvSources.Type.COUNTRY -> if (isPodcast) "Apple Podcasts" else "Radio Browser"
                                IptvSources.Type.RSS -> "RSS"
                                IptvSources.Type.M3U -> "M3U"
                                IptvSources.Type.LOCAL_M3U -> app.getString(R.string.iptv_source_local_m3u)
                                IptvSources.Type.XTREAM -> "Xtream"
                            }
                        )
                        .put("removable", source.type != IptvSources.Type.DEFAULT_M3U && source.type != IptvSources.Type.DEFAULT_COUNTRY)
                )
            }
        }
    }

    /** Gli elenchi che la pagina disegna: [withCache] = la dimensione della cache si legge dal disco, solo quando serve. */
    private val datasets: Map<String, (Context, Context, Boolean) -> JSONArray> = mapOf(
        "auto_open_options" to { c, _, _ -> options(autoOpenOptions(c)) },
        "wide_sidebar_size_options" to { _, _, _ -> options(AppSettings.wideSidebarSizes.map { it.toString() to "$it%" }) },
        "youtube_quality_options" to { _, _, _ -> options(AppSettings.youtubeQualityOptions) },
        "sponsorblock_options" to { _, app, _ ->
            options(
                listOf(
                    SponsorBlock.LEVEL_SPONSOR to app.getString(R.string.sponsorblock_level_sponsor),
                    SponsorBlock.LEVEL_DEFAULT to app.getString(R.string.sponsorblock_level_default),
                    SponsorBlock.LEVEL_ALL to app.getString(R.string.sponsorblock_level_all),
                )
            )
        },
        "adblock_list_options" to { _, app, _ ->
            options(AdBlockLists.SOURCES.map { it.key to it.name } + (AdBlockLists.CUSTOM_KEY to app.getString(R.string.adblock_lists_custom)))
        },
        // I tasti della barra delle schede e della sidebar: prima quelli mostrati, nell'ordine scelto, poi gli altri
        "tab_bar_actions" to { _, app, _ ->
            val shown = AppSettings.tabBarActions.value
            JSONArray().apply {
                (shown + TabBarAction.DEFAULT.filter { it !in shown }).forEach { action ->
                    put(actionsList(app, action.id, action.labelRes, action in shown, essential = false, mainOnly = false))
                }
            }
        },
        "sidebar_actions" to { _, app, _ ->
            val shown = AppSettings.sidebarActions.value
            JSONArray().apply {
                (shown + SidebarAction.ALL.filter { it !in shown }).forEach { action ->
                    put(
                        actionsList(
                            app, action.id, action.labelRes, action in shown,
                            essential = action.essential, mainOnly = !action.availableIn(SidebarHost.WIDE)
                        )
                    )
                }
            }
        },
        "iptv_tv" to { _, app, _ -> sourcesList(app, IptvSources.Section.TV) },
        "iptv_radio" to { _, app, _ -> sourcesList(app, IptvSources.Section.RADIO) },
        "iptv_podcast" to { _, app, _ -> sourcesList(app, IptvSources.Section.PODCAST) },
        // Siti con una scelta: per ognuno i permessi, con il valore (consentito o bloccato)
        "site_permissions" to { c, app, _ ->
            JSONArray().apply {
                SitePermissions.sites(c).forEach { (origin, choices) ->
                    put(
                        JSONObject().put("id", origin).put("title", SitePermissions.displayName(origin))
                            .put(
                                "children",
                                JSONArray().apply {
                                    choices.entries.sortedBy { it.key.ordinal }.forEach { (permission, allowed) ->
                                        put(
                                            JSONObject().put("id", permission.key)
                                                .put("label", app.getString(permission.titleRes)).put("allowed", allowed)
                                        )
                                    }
                                }
                            )
                    )
                }
            }
        },
        // Cancella dati: la dimensione della cache si legge dal disco, quindi solo quando serve (la pagina tiene l'ultima)
        "clear_options" to { c, app, withCache ->
            JSONArray().apply {
                put(JSONObject().put("id", "cache").apply { if (withCache) put("label", cacheLabel(c, app)) })
                put(JSONObject().put("id", "cookies").put("label", app.getString(R.string.cookies_label)))
                put(JSONObject().put("id", "certificates").put("label", app.getString(R.string.certificates_label, SslExceptions.count(c))))
                put(JSONObject().put("id", "permissions").put("label", app.getString(R.string.site_permissions_clear_label, SitePermissions.count(c))))
            }
        },
    )

    /** Le chiavi degli elenchi che la pagina può chiedere (per il test dello schema). */
    internal val dataKeys: Set<String> get() = datasets.keys

    private fun data(context: Context, app: Context, withCache: Boolean): JSONObject {
        val data = JSONObject()
        datasets.forEach { (key, build) -> data.put(key, build(context, app, withCache)) }
        return data
    }

    // ------------------------------------------------------------------
    // Lo schema come JSON, con i testi nella lingua dell'app
    // ------------------------------------------------------------------

    private fun sections(app: Context, debug: Boolean): JSONArray {
        fun text(res: Int): String? = if (res == 0) null else app.getString(res)

        fun put(target: JSONObject, name: String, value: Any?) {
            if (value != null) target.put(name, value)
        }

        fun conds(list: List<Cond>): JSONArray = JSONArray().apply {
            list.forEach { put(condition(it)) }
        }

        fun row(row: Row): JSONObject {
            val json = JSONObject()
            if (row.showIf.isNotEmpty()) json.put("showIf", conds(row.showIf))
            when (row) {
                is Switch -> {
                    json.put("type", "switch").put("key", row.key).put("label", app.getString(row.label))
                    put(json, "desc", text(row.desc))
                    put(json, "descOn", text(row.descOn))
                    put(json, "descOff", text(row.descOff))
                    row.confirm?.let {
                        json.put(
                            "confirm",
                            JSONObject().put("title", app.getString(it.title)).put("message", app.getString(it.message))
                                .put("ok", app.getString(it.ok)).put("cancel", app.getString(it.cancel))
                        )
                    }
                    row.blockedIf?.let { json.put("blocked", condition(it)) }
                    put(json, "blockedDesc", text(row.blockedDesc))
                }
                is Choice -> {
                    json.put("type", "choice").put("key", row.key).put("label", app.getString(row.label)).put("buttons", row.buttons)
                    put(json, "desc", text(row.desc))
                    if (row.optionsData != null) json.put("optionsData", row.optionsData)
                    else json.put(
                        "options",
                        JSONArray().apply {
                            row.options.forEach { put(JSONArray().put(it.value).put(it.text ?: app.getString(it.label))) }
                        }
                    )
                    row.ask?.let {
                        json.put(
                            "ask",
                            JSONObject().put("value", it.value).put("unless", it.unlessKey).put("nat", it.nat)
                                .put("notPhone", app.getString(it.notPhone))
                        )
                    }
                }
                is Stepper -> {
                    json.put("type", "stepper").put("key", row.key).put("min", row.min).put("max", row.max).put("step", row.step)
                        .put("unit", row.unit)
                    put(json, "label", text(row.label))
                    put(json, "desc", text(row.desc))
                    put(json, "format", text(row.format))
                    put(json, "maxKey", row.maxKey)
                }
                is Buttons -> {
                    json.put("type", "buttons").put("hideWithoutPhone", row.hideWithoutPhone)
                    put(json, "label", text(row.label))
                    put(json, "desc", text(row.desc))
                    put(json, "descKey", row.descKey)
                    put(json, "phoneNote", text(row.phoneNote))
                    json.put(
                        "buttons",
                        JSONArray().apply {
                            row.buttons.forEach { button ->
                                val b = JSONObject().put("text", app.getString(button.text))
                                    .put("outline", button.outline).put("confirmTap", button.confirmTap)
                                put(b, "act", button.act)
                                put(b, "nat", button.nat)
                                put(b, "link", button.link)
                                put(b, "notPhone", text(button.notPhone))
                                put(b)
                            }
                        }
                    )
                }
                is Heading -> {
                    json.put("type", "heading").put("label", app.getString(row.label))
                    put(json, "desc", text(row.desc))
                }
                is Group -> {
                    json.put("type", "group").put("id", row.id).put("label", app.getString(row.label))
                        .put("desc", app.getString(row.desc)).put("summary", app.getString(row.summary))
                        .put("summaryKey", row.summaryKey)
                        .put("rows", JSONArray().apply { row.rows.forEach { put(row(it)) } })
                }
                is Actions -> json.put("type", "actions").put("key", row.key)
                is Items -> {
                    json.put("type", "items").put("data", row.data).put("act", row.act)
                    put(json, "scope", row.scope)
                    put(json, "empty", text(row.empty))
                    put(json, "listLabel", text(row.listLabel))
                }
                is Checks -> {
                    json.put("type", "checks").put("data", row.data).put("act", row.act)
                        .put("label", app.getString(row.label)).put("desc", app.getString(row.desc))
                        .put("button", app.getString(row.button)).put("refresh", app.getString(row.refresh))
                }
            }
            return json
        }

        return JSONArray().apply {
            SettingsSchema.sections(debug).forEach { section ->
                put(
                    JSONObject().put("id", section.id).put("title", app.getString(section.title)).put("plain", section.plain)
                        .put(
                            "cards",
                            JSONArray().apply {
                                section.cards.forEach { card ->
                                    val c = JSONObject().put("rows", JSONArray().apply { card.rows.forEach { put(row(it)) } })
                                    card.id?.let { c.put("id", it) }
                                    if (card.showIf.isNotEmpty()) c.put("showIf", conds(card.showIf))
                                    put(c)
                                }
                            }
                        )
                )
            }
        }
    }

    private fun condition(cond: Cond): JSONObject {
        val json = JSONObject().put("k", cond.key)
        cond.eq?.let { json.put("eq", it) }
        cond.ne?.let { json.put("ne", it) }
        return json
    }

    private fun json(body: JSONObject): WebResourceResponse =
        response(body.toString().toByteArray(Charsets.UTF_8), "application/json", 200, "OK")

    private fun response(bytes: ByteArray, mime: String, status: Int, reason: String): WebResourceResponse =
        WebResourceResponse(
            mime, "utf-8", status, reason,
            mapOf("Cache-Control" to "no-store", "X-Frame-Options" to "DENY"),
            ByteArrayInputStream(bytes)
        )
}
