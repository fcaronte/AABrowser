package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.R
import com.fcaronte.aabrowser.settings.SearchEngine
import com.fcaronte.aabrowser.settings.TabBarAction
import com.fcaronte.aabrowser.settings.ThemeMode
import com.fcaronte.aabrowser.smartphone.VirtualSplit

/**
 * Le impostazioni come dati: sezioni, schede e righe, ognuna con l'id del suo testo e la chiave del suo valore. Le legge
 * [SettingsApi] per la pagina (https://player.local/settings, la stessa nella Main e nella Wide), che non conosce nessuna
 * voce: disegna quello che riceve. Qui non c'è niente che dipenda da Android a parte gli id delle stringhe, così si
 * controlla con i test (SettingsSchemaTest): ogni chiave ha chi la scrive, ogni testo c'è in tutte le lingue.
 *
 * I valori (SettingsApi.values) sono stringhe: "1" / "0" per gli interruttori. Una riga con [Row.showIf] compare solo se tutte
 * le sue condizioni valgono, e la pagina le ricontrolla da sola a ogni cambio: niente giro con l'app per far comparire la
 * scelta del tema dopo aver acceso "forza il tema".
 */
internal object SettingsSchema {

    /** La riga si mostra se il valore di [key] è [eq] (o non è [ne]). */
    class Cond(val key: String, val eq: String? = null, val ne: String? = null)

    /** Avviso da accettare prima di accendere (la Modalità passeggero). */
    class Confirm(val title: Int, val message: Int, val ok: Int, val cancel: Int)

    /** Una scelta: il valore e il testo, da una stringa o scritto così com'è (nomi propri, sigle). */
    class Option(val value: String, val label: Int = 0, val text: String? = null)

    /**
     * Un pulsante. [act]: l'azione che esegue l'app (SettingsApi.act); [nat]: quella che solo il telefono può fare (selettori
     * di file, finestre per indirizzi e password, riavvio: AndroidBridge.run); [link]: un indirizzo, che il telefono apre e
     * l'auto mostra come codice QR. [notPhone]: dove non c'è il telefono il pulsante dice questo invece di sparire.
     */
    class Btn(
        val text: Int,
        val act: String? = null,
        val nat: String? = null,
        val link: String? = null,
        val outline: Boolean = false,
        val confirmTap: Boolean = false,
        val notPhone: Int = 0,
    )

    /** Se si sceglie [value] e [unlessKey] non vale "1", la scelta passa da una finestra del telefono invece di salvarsi. */
    class Ask(val value: String, val unlessKey: String, val nat: String, val notPhone: Int)

    sealed class Row {
        abstract val showIf: List<Cond>
    }

    /** Un interruttore. [descOn] / [descOff]: il testo cambia con il valore. [blockedIf]: finché vale, tocca solo dare l'avviso. */
    class Switch(
        val key: String,
        val label: Int,
        val desc: Int = 0,
        val descOn: Int = 0,
        val descOff: Int = 0,
        val confirm: Confirm? = null,
        val blockedIf: Cond? = null,
        val blockedDesc: Int = 0,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    /** Una scelta tra valori: a pulsanti affiancati ([buttons]) o in un elenco che si apre. [optionsData]: le voci vengono dall'app. */
    class Choice(
        val key: String,
        val label: Int,
        val desc: Int = 0,
        val options: List<Option> = emptyList(),
        val optionsData: String? = null,
        val buttons: Boolean = false,
        val ask: Ask? = null,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    /**
     * Un numero con − e +, al posto del cursore (nella Wide non si può trascinare). [format]: il testo con il numero
     * ("%d dB"); senza, il numero con [unit]. [maxKey]: il massimo lo dà l'app.
     */
    class Stepper(
        val key: String,
        val min: Int,
        val max: Int,
        val step: Int,
        val label: Int = 0,
        val desc: Int = 0,
        val format: Int = 0,
        val unit: String = "",
        val maxKey: String? = null,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    /** Righe con pulsanti: il testo ([desc] o il valore di [descKey]) e uno o più pulsanti. */
    class Buttons(
        val buttons: List<Btn>,
        val label: Int = 0,
        val desc: Int = 0,
        val descKey: String? = null,
        val phoneNote: Int = 0,
        val hideWithoutPhone: Boolean = false,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    /** Titolo e testo di una scheda. */
    class Heading(val label: Int, val desc: Int = 0, override val showIf: List<Cond> = emptyList()) : Row()

    /** Un gruppo che si apre e si chiude; chiuso mostra [summary] con il numero di [summaryKey]. */
    class Group(
        val id: String,
        val label: Int,
        val desc: Int,
        val summary: Int,
        val summaryKey: String,
        val rows: List<Row>,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    /** L'elenco dei tasti (barra delle schede, sidebar): quali mostrare e in che ordine. I dati sono in `data[key]`. */
    class Actions(val key: String, override val showIf: List<Cond> = emptyList()) : Row()

    /**
     * Un elenco dall'app (`data[data]`) con un tasto per togliere ogni voce ([act], con [scope] davanti all'id). Con
     * [listLabel] (un testo con il numero delle voci) l'elenco si apre e si chiude, e ogni voce ha le sue scelte da togliere
     * una per una (i siti con un permesso); [empty] è il testo quando non c'è niente.
     */
    class Items(
        val data: String,
        val act: String,
        val scope: String? = null,
        val empty: Int = 0,
        val listLabel: Int = 0,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    /** Caselle da spuntare ([data]) e un pulsante che esegue [act] su quelle scelte. */
    class Checks(
        val data: String,
        val act: String,
        val label: Int,
        val desc: Int,
        val button: Int,
        val refresh: Int,
        override val showIf: List<Cond> = emptyList(),
    ) : Row()

    class Card(val rows: List<Row>, val id: String? = null, val showIf: List<Cond> = emptyList())

    /** [plain]: niente intestazione che si chiude, le schede stanno in fondo come sono. */
    class Section(val id: String, val title: Int, val cards: List<Card>, val plain: Boolean = false)

    private fun on(key: String) = Cond(key, eq = "1")
    private fun off(key: String) = Cond(key, eq = "0")
    private fun eq(key: String, value: String) = Cond(key, eq = value)

    /** Le impostazioni, nell'ordine in cui si mostrano. [debug]: la build di prova ha anche la scelta per provare gli aggiornamenti. */
    fun sections(debug: Boolean): List<Section> = listOf(app(), wide(), passenger(), media(), web(), more(debug))

    private fun app() = Section(
        "app", R.string.settings_category_app,
        listOf(
            Card(
                listOf(
                    Switch("force_theme", R.string.theme_force_label, descOn = R.string.theme_force_desc_on, descOff = R.string.theme_force_desc_off),
                    Choice(
                        "theme_mode", R.string.theme_selection_label,
                        options = ThemeMode.entries.map { Option(it.name, text = it.name) },
                        buttons = true, showIf = listOf(on("force_theme"))
                    ),
                )
            ),
            Card(listOf(Switch("dashboard_three_columns", R.string.dashboard_columns_label, R.string.dashboard_columns_desc))),
            Card(listOf(Switch("open_dashboard_on_new_tab", R.string.open_dashboard_on_new_tab_label, R.string.open_dashboard_on_new_tab_desc))),
            Card(
                listOf(
                    Switch("auto_open_enabled", R.string.auto_open_favorite_label, R.string.auto_open_favorite_desc),
                    Choice(
                        "auto_open_favorite", R.string.selected_favorite_label,
                        optionsData = "auto_open_options", showIf = listOf(on("auto_open_enabled"), on("auto_open_has_options"))
                    ),
                )
            ),
            Card(listOf(Stepper("audio_boost", 0, 15, 1, label = R.string.audio_boost_label, desc = R.string.audio_boost_desc, format = R.string.audio_boost_value))),
            Card(listOf(Switch("autoplay", R.string.autoplay_label, R.string.autoplay_desc))),
            Card(
                listOf(
                    Switch("media_player", R.string.media_player_label, R.string.media_player_desc),
                    Switch("player_card_opens_main", R.string.player_card_main_label, R.string.player_card_main_desc, showIf = listOf(on("media_player"))),
                    Choice(
                        "youtube_video_skip", R.string.player_single_video_title,
                        options = listOf(
                            Option("seek", R.string.player_single_video_seek),
                            Option("skip", R.string.player_single_video_skip)
                        ),
                        showIf = listOf(on("media_player"))
                    ),
                )
            ),
            Card(
                listOf(
                    Switch("share_to_carweb", R.string.share_carweb_label, R.string.share_carweb_desc),
                    Choice(
                        "share_always", R.string.share_carweb_when,
                        options = listOf(
                            Option("car", R.string.share_carweb_car_only),
                            Option("always", R.string.share_carweb_always)
                        ),
                        showIf = listOf(on("share_to_carweb"))
                    ),
                )
            ),
            Card(
                listOf(
                    Switch(
                        "weather_widget", R.string.weather_widget_label, R.string.weather_widget_desc,
                        blockedIf = off("media_player"), blockedDesc = R.string.media_player_disabled_hint
                    )
                )
            ),
            Card(
                listOf(
                    Switch("preload_favorites", R.string.preload_favorites_label, R.string.preload_favorites_desc),
                    Stepper("preload_favorites_count", 1, 8, 1, format = R.string.preload_favorites_count_label, showIf = listOf(on("preload_favorites"))),
                )
            ),
            Card(
                listOf(
                    Buttons(
                        label = R.string.reset_favorites_label, desc = R.string.add_missing_favorites_desc,
                        buttons = listOf(
                            Btn(R.string.reset_button, act = "favorites_reset", confirmTap = true),
                            Btn(R.string.add_button, act = "favorites_add_defaults", outline = true),
                        )
                    )
                )
            ),
            // Il selettore dei file si apre solo sul telefono: in auto la scheda non c'è
            Card(
                listOf(
                    Buttons(
                        label = R.string.export_settings_label, desc = R.string.export_settings_desc, hideWithoutPhone = true,
                        buttons = listOf(
                            Btn(R.string.export_button, nat = "backup_export"),
                            Btn(R.string.import_button, nat = "backup_import", outline = true, confirmTap = true),
                        )
                    )
                )
            ),
            Card(listOf(Switch("persistent_navigation", R.string.persistent_nav_label, R.string.persistent_nav_desc))),
            Card(
                listOf(
                    Switch("tab_bar_enabled", R.string.tab_bar_enable_label, R.string.tab_bar_enable_desc),
                    Choice(
                        "tab_bar_mode", R.string.tab_bar_mode_label,
                        options = listOf(
                            Option("AUTO_HIDE", R.string.tab_bar_mode_auto),
                            Option("ALWAYS_ON", R.string.tab_bar_mode_always)
                        ),
                        buttons = true, showIf = listOf(on("tab_bar_enabled"))
                    ),
                )
            ),
            // Quali pulsanti a destra della barra delle schede, in che ordine e quanti se ne vedono insieme
            Card(
                listOf(
                    Group(
                        "tab_bar_actions", R.string.tab_bar_actions_label, R.string.tab_bar_actions_desc,
                        R.string.tab_bar_actions_visible, "tab_bar_visible",
                        listOf(
                            Actions("tab_bar_actions"),
                            Stepper(
                                "tab_bar_visible", 1, TabBarAction.MAX_VISIBLE, 1,
                                format = R.string.tab_bar_actions_visible, maxKey = "tab_bar_shown_count",
                                showIf = listOf(on("tab_bar_has_choice"))
                            ),
                        )
                    )
                ),
                showIf = listOf(on("tab_bar_enabled"))
            ),
            // La sidebar (il dock della Wide) anche nella Main, e i suoi tasti
            Card(
                listOf(
                    Switch("main_dock", R.string.main_dock_label, R.string.main_dock_desc),
                    Group(
                        "sidebar_actions", R.string.sidebar_actions_label, R.string.sidebar_actions_desc,
                        R.string.sidebar_actions_count, "sidebar_actions_count",
                        listOf(Actions("sidebar_actions"))
                    ),
                )
            ),
            // Altre App: le app del telefono sullo schermo dell'auto (Shizuku)
            Card(
                listOf(
                    Switch("virtual_standby", R.string.vapp_standby_label, R.string.vapp_standby_desc),
                    Choice(
                        "virtual_app_scale", R.string.vapp_scale_label, R.string.vapp_scale_desc,
                        options = listOf(70, 85, 100, 125, 150).map { Option(it.toString(), text = "$it%") },
                        buttons = true
                    ),
                    // Schermo diviso con il browser: la larghezza dell'app e il lato si scelgono qui (niente trascinamento)
                    Switch("virtual_split_enabled", R.string.vapp_split, R.string.vapp_split_desc),
                    Stepper(
                        "virtual_split_fraction", VirtualSplit.MIN_PERCENT, VirtualSplit.MAX_PERCENT, VirtualSplit.STEP_PERCENT,
                        format = R.string.virtual_split_fraction, showIf = listOf(on("virtual_split_enabled"))
                    ),
                    Choice(
                        "virtual_split_side", R.string.virtual_split_side,
                        options = listOf(
                            Option(VirtualSplit.Side.RIGHT.name, R.string.virtual_split_side_right),
                            Option(VirtualSplit.Side.LEFT.name, R.string.virtual_split_side_left)
                        ),
                        buttons = true, showIf = listOf(on("virtual_split_enabled"))
                    ),
                )
            ),
            Card(listOf(Switch("force_english", R.string.force_english_label, R.string.force_english_desc))),
            Card(listOf(Stepper("ui_scale", 50, 150, 5, label = R.string.ui_scale_label, desc = R.string.ui_scale_desc, unit = "%"))),
            Card(
                listOf(
                    Switch(
                        "custom_search_engine", R.string.search_engine_label,
                        descOn = R.string.search_engine_custom_on, descOff = R.string.search_engine_custom_off
                    ),
                    Choice(
                        "search_engine", R.string.search_engine_selection_label,
                        options = SearchEngine.entries.map { Option(it.name, text = it.name) },
                        buttons = true, showIf = listOf(on("custom_search_engine"))
                    ),
                )
            ),
            Card(
                listOf(
                    Buttons(
                        label = R.string.change_language, desc = R.string.change_language_desc,
                        buttons = listOf(Btn(R.string.change_button, nat = "change_language", notPhone = R.string.not_available_in_car))
                    )
                )
            ),
        )
    )

    // Quello che prima stava nella home della Wide: la dimensione della sidebar e il video a schermo intero
    private fun wide() = Section(
        "wide", R.string.wide_app_name,
        listOf(
            Card(
                listOf(
                    Switch("wide_reopen_last_page", R.string.wide_reopen_last_page),
                    Choice("wide_sidebar_size", R.string.wide_sidebar_size, optionsData = "wide_sidebar_size_options", buttons = true),
                    Choice(
                        "wide_video_menu_on_tap", R.string.wide_video_menu_label,
                        options = listOf(Option("0", R.string.video_menu_always), Option("1", R.string.video_menu_touch)),
                        buttons = true
                    ),
                )
            )
        )
    )

    // In movimento l'app va solo con la modalità passeggero, e la tastiera dell'app sostituisce quella di Android Auto: si
    // accendono dopo l'avviso (anche in auto, da fermi)
    private fun passenger(): Section {
        val disclaimer = Confirm(
            R.string.passenger_disclaimer_title, R.string.passenger_disclaimer_message,
            R.string.passenger_disclaimer_accept, R.string.ssl_warning_cancel
        )
        return Section(
            "passenger", R.string.settings_category_passenger,
            listOf(
                Card(
                    listOf(
                        Switch("passenger_mode", R.string.passenger_mode_label, R.string.passenger_mode_desc, confirm = disclaimer),
                        Switch("passenger_keyboard", R.string.passenger_keyboard_label, R.string.passenger_keyboard_desc, confirm = disclaimer),
                    )
                )
            )
        )
    }

    // Le liste di TV, Radio e Podcast: si vedono e si tolgono dovunque, si aggiungono dal telefono (indirizzi e password non
    // passano dalla pagina: le finestre per scriverli sono quelle dell'app)
    private fun media() = Section(
        "media", R.string.settings_category_media,
        listOf(
            Card(
                listOf(
                    Heading(R.string.iptv_title, R.string.iptv_desc),
                    Items("iptv_tv", "iptv_remove", scope = "tv"),
                    Buttons(
                        phoneNote = R.string.iptv_add_on_phone,
                        buttons = listOf(
                            Btn(R.string.iptv_add_m3u, nat = "iptv_m3u:tv", outline = true),
                            Btn(R.string.iptv_add_m3u_file, nat = "iptv_file:tv", outline = true),
                            Btn(R.string.iptv_add_xtream, nat = "iptv_xtream:tv", outline = true),
                        )
                    ),
                ),
                id = "tv"
            ),
            Card(
                listOf(
                    Heading(R.string.radio_lists_title, R.string.radio_lists_desc),
                    Items("iptv_radio", "iptv_remove", scope = "radio"),
                    Buttons(
                        phoneNote = R.string.iptv_add_on_phone,
                        buttons = listOf(
                            Btn(R.string.iptv_add_m3u, nat = "iptv_m3u:radio", outline = true),
                            Btn(R.string.iptv_add_m3u_file, nat = "iptv_file:radio", outline = true),
                            Btn(R.string.radio_add_country, nat = "iptv_country:radio", outline = true),
                        )
                    ),
                ),
                id = "radio"
            ),
            Card(
                listOf(
                    Heading(R.string.podcast_lists_title, R.string.podcast_lists_desc),
                    Items("iptv_podcast", "iptv_remove", scope = "podcast"),
                    Buttons(
                        phoneNote = R.string.iptv_add_on_phone,
                        buttons = listOf(
                            Btn(R.string.podcast_add_rss, nat = "iptv_rss:podcast", outline = true),
                            Btn(R.string.podcast_add_opml, nat = "iptv_opml:podcast", outline = true),
                            Btn(R.string.radio_add_country, nat = "iptv_country:podcast", outline = true),
                        )
                    ),
                ),
                id = "podcast"
            ),
        )
    )

    private fun web() = Section(
        "web", R.string.settings_category_web,
        listOf(
            Card(listOf(Switch("dark_pages", R.string.dark_pages_label, R.string.dark_pages_desc))),
            Card(
                listOf(
                    Switch("desktop_mode", R.string.desktop_mode_label, R.string.desktop_mode_desc),
                    Stepper("desktop_scale", 25, 150, 5, format = R.string.desktop_zoom_label, showIf = listOf(on("desktop_mode"))),
                    Stepper("display_scale", 25, 150, 5, format = R.string.mobile_zoom_label, showIf = listOf(off("desktop_mode"))),
                )
            ),
            Card(
                listOf(
                    Switch("adblock", R.string.adblock_label, R.string.adblock_desc),
                    // La lista dei siti pubblicitari: quale usare (anche una a piacere), quanti siti, quando è stata scaricata
                    Choice(
                        "adblock_list", R.string.adblock_lists_choice, optionsData = "adblock_list_options",
                        ask = Ask("custom", "adblock_custom_set", "adblock_custom", R.string.adblock_lists_custom_phone),
                        showIf = listOf(on("adblock"))
                    ),
                    Buttons(
                        label = R.string.adblock_lists_custom, descKey = "adblock_custom_url", hideWithoutPhone = true,
                        buttons = listOf(Btn(R.string.adblock_lists_custom_change, nat = "adblock_custom")),
                        showIf = listOf(on("adblock"), eq("adblock_list", "custom"))
                    ),
                    Buttons(
                        label = R.string.adblock_lists_label, descKey = "adblock_list_info",
                        buttons = listOf(Btn(R.string.adblock_lists_update, act = "adblock_update")),
                        showIf = listOf(on("adblock"))
                    ),
                    Switch("adblock_youtube", R.string.adblock_youtube_label, R.string.adblock_youtube_desc),
                    Switch(
                        "adblock_youtube_prune", R.string.adblock_youtube_prune_label, R.string.adblock_youtube_prune_desc,
                        showIf = listOf(on("adblock_youtube"))
                    ),
                )
            ),
            Card(
                listOf(
                    Switch("force_max_youtube_quality", R.string.force_max_youtube_quality_label, R.string.force_max_youtube_quality_desc),
                    Choice(
                        "youtube_quality", R.string.youtube_quality_selected_label, optionsData = "youtube_quality_options",
                        buttons = true, showIf = listOf(on("force_max_youtube_quality"))
                    ),
                )
            ),
            Card(
                listOf(
                    Switch("sponsorblock", R.string.sponsorblock_label, R.string.sponsorblock_desc),
                    Choice(
                        "sponsorblock_level", R.string.sponsorblock_level_label, optionsData = "sponsorblock_options",
                        showIf = listOf(on("sponsorblock"))
                    ),
                )
            ),
            Card(listOf(Switch("multi_window", R.string.multi_window_label, R.string.multi_window_desc))),
            // Microfono, posizione e file del telefono: ogni sito chiede e la scelta resta per quel sito
            Card(
                listOf(
                    Switch("auto_site_permissions", R.string.site_permissions_auto_label, R.string.site_permissions_auto_desc),
                    Items(
                        "site_permissions", "site_permission_remove",
                        empty = R.string.site_permissions_empty, listLabel = R.string.site_permissions_list_label
                    ),
                )
            ),
            Card(
                listOf(
                    Checks(
                        "clear_options", "clear_data",
                        label = R.string.clear_data_label, desc = R.string.clear_data_desc,
                        button = R.string.confirm_button, refresh = R.string.refresh_cache
                    )
                )
            ),
        )
    )

    // Sciolte, in fondo: la prova degli aggiornamenti (solo build di prova), le novità, il README
    private fun more(debug: Boolean) = Section(
        "more", R.string.settings_title,
        buildList {
            if (debug) {
                add(Card(listOf(Switch("debug_update_test", R.string.debug_update_test_label, R.string.debug_update_test_desc))))
            }
            add(
                Card(
                    listOf(
                        Buttons(
                            label = R.string.whats_new_label, desc = R.string.whats_new_desc,
                            buttons = listOf(Btn(R.string.whats_new_label, act = "whats_new"))
                        )
                    ),
                    showIf = listOf(on("has_whats_new"))
                )
            )
            add(
                Card(
                    listOf(
                        Buttons(
                            label = R.string.open_readme, desc = R.string.open_readme_desc,
                            buttons = listOf(Btn(R.string.open_readme, link = "readme"))
                        )
                    )
                )
            )
        },
        plain = true
    )

    // ------------------------------------------------------------------
    // Per i test e per SettingsApi: che chiavi ci sono
    // ------------------------------------------------------------------

    /** Le righe di una scheda, anche quelle dentro un gruppo. */
    fun flatten(rows: List<Row>): List<Row> = rows.flatMap { row -> if (row is Group) listOf(row) + flatten(row.rows) else listOf(row) }

    fun allRows(debug: Boolean = true): List<Row> = sections(debug).flatMap { it.cards }.flatMap { flatten(it.rows) }

    /** Le chiavi che una riga permette di cambiare: ognuna dev'essere scrivibile (SettingsApi.settingKeys). */
    fun interactiveKeys(debug: Boolean = true): Set<String> = allRows(debug).mapNotNull { row ->
        when (row) {
            is Switch -> row.key
            is Choice -> row.key
            is Stepper -> row.key
            is Actions -> row.key
            else -> null
        }
    }.toSet()

    /** Gli elenchi che la pagina chiede all'app (`data[…]`): voci delle scelte, elenchi, caselle, tasti. */
    fun dataKeys(debug: Boolean = true): Set<String> = allRows(debug).mapNotNull { row ->
        when (row) {
            is Choice -> row.optionsData
            is Items -> row.data
            is Checks -> row.data
            is Actions -> row.key
            else -> null
        }
    }.toSet()

    /** Tutte le chiavi dei valori che la pagina legge: condizioni, testi dei valori, massimi e riassunti. */
    fun referencedKeys(debug: Boolean = true): Set<String> {
        val keys = mutableSetOf<String>()
        fun conds(list: List<Cond>) = list.forEach { keys += it.key }
        sections(debug).forEach { section ->
            section.cards.forEach { card ->
                conds(card.showIf)
                flatten(card.rows).forEach { row ->
                    conds(row.showIf)
                    when (row) {
                        is Switch -> {
                            keys += row.key
                            row.blockedIf?.let { keys += it.key }
                        }
                        is Choice -> {
                            keys += row.key
                            row.ask?.let { keys += it.unlessKey }
                        }
                        is Stepper -> {
                            keys += row.key
                            row.maxKey?.let { keys += it }
                        }
                        is Buttons -> row.descKey?.let { keys += it }
                        is Group -> keys += row.summaryKey
                        is Actions -> keys += row.key
                        else -> Unit
                    }
                }
            }
        }
        return keys
    }

    /** Gli id delle stringhe di tutta la pagina, per controllare che esistano in tutte le lingue. */
    fun stringIds(debug: Boolean = true): Set<Int> {
        val ids = mutableSetOf<Int>()
        fun add(id: Int) {
            if (id != 0) ids += id
        }
        sections(debug).forEach { section ->
            add(section.title)
            section.cards.flatMap { flatten(it.rows) }.forEach { row ->
                when (row) {
                    is Switch -> {
                        add(row.label); add(row.desc); add(row.descOn); add(row.descOff); add(row.blockedDesc)
                        row.confirm?.let { add(it.title); add(it.message); add(it.ok); add(it.cancel) }
                    }
                    is Choice -> {
                        add(row.label); add(row.desc)
                        row.options.forEach { add(it.label) }
                        row.ask?.let { add(it.notPhone) }
                    }
                    is Stepper -> { add(row.label); add(row.desc); add(row.format) }
                    is Buttons -> {
                        add(row.label); add(row.desc); add(row.phoneNote)
                        row.buttons.forEach { add(it.text); add(it.notPhone) }
                    }
                    is Heading -> { add(row.label); add(row.desc) }
                    is Group -> { add(row.label); add(row.desc); add(row.summary) }
                    is Items -> { add(row.empty); add(row.listLabel) }
                    is Checks -> { add(row.label); add(row.desc); add(row.button); add(row.refresh) }
                    is Actions -> Unit
                }
            }
        }
        return ids
    }
}
