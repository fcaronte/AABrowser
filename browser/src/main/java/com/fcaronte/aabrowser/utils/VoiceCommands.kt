package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.model.FavoriteSite
import java.net.URI
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale

/**
 * Comandi vocali: tasto microfono della Main (barra delle schede e menu) e della Wide (sidebar o tasti in alto),
 * e "Ok Google, metti … su AABrowser" (CarMediaService.onPlayFromSearch).
 *
 * Frasi a schema, non linguaggio libero. Valgono le lingue dell'app tutte insieme: il riconoscimento ascolta
 * nella lingua del telefono, che può non essere quella dell'app ("forza inglese"). Quello che non è un comando
 * diventa una ricerca, sul sito aperto quando si può.
 */
object VoiceCommands {

    /** Le tre pagine di CarWeb Media. */
    enum class MediaSection { MEDIA, TV, RADIO }

    sealed interface Command {
        /** "cerca …": sul sito aperto se si può (YouTube, ...), altrimenti col motore di ricerca. */
        data class Search(val query: String) : Command
        /** "cerca … su Google": sempre col motore di ricerca. */
        data class WebSearch(val query: String) : Command
        /** "cerca … su Amazon", "cerca su Wikipedia …": sul sito detto (se non è un sito, una ricerca normale). */
        data class SiteSearch(val site: String, val query: String) : Command
        data class YouTubeSearch(val query: String) : Command
        data class YouTubePlay(val query: String) : Command
        data class YouTubeMusicSearch(val query: String) : Command
        data class YouTubeMusicPlay(val query: String) : Command
        /** "metti …": YouTube, o YouTube Music se è quello aperto; sulle pagine di CarWeb Media, quella aperta. */
        data class Play(val query: String) : Command
        /** "guarda …": un canale della TV, altrimenti come [Play]. */
        data class Watch(val query: String) : Command
        /** "metti … alla radio", "guarda … in tv", "cerca … su media": [play] = parte il risultato migliore. */
        data class MediaRequest(val section: MediaSection, val query: String, val play: Boolean) : Command
        /** "apri …": un preferito per nome, altrimenti un indirizzo, altrimenti una ricerca. */
        data class Open(val target: String) : Command
        data class Navigate(val destination: String) : Command
        data object Back : Command
        data object Reload : Command
        data object Home : Command
        data object Pause : Command
        data object Resume : Command
        data object Next : Command
        data object Previous : Command
        /** Nessuno schema: la frase intera si cerca come con [Search]. */
        data class Unmatched(val text: String) : Command
    }

    /** Cosa fare, uguale per la Main e la Wide: ognuna poi lo esegue a modo suo. */
    sealed interface Plan {
        /** Carica [url]. [playFirst]: alla pagina dei risultati parte il primo (YouTube e YouTube Music). */
        data class Load(
            val url: String,
            val label: String,
            val kind: LoadKind,
            val playFirst: Boolean = false,
            val favorite: FavoriteSite? = null,
            /** Ricerca su un sito ([LoadKind.SITE]): il suo nome, per il messaggio. */
            val siteName: String? = null
        ) : Plan
        data class Navigate(val destination: String) : Plan
        data class Player(val action: PlayerAction) : Plan
        /**
         * Una pagina di CarWeb Media (MediaVoice le porta la richiesta). [query] null = solo la pagina, o con [play] quello
         * che aveva in riproduzione; [action]: un tasto del player, quando il player di Android Auto non è di nessuno.
         * [fallback]: la pagina è stata dedotta dalla frase ("metti radio ga ga"), se non trova niente si fa questo.
         */
        data class Media(
            val section: MediaSection,
            val query: String? = null,
            val play: Boolean = false,
            val action: PlayerAction? = null,
            val fallback: Command? = null
        ) : Plan
        data object Back : Plan
        data object Reload : Plan
        data object Home : Plan
    }

    /** Per il messaggio che conferma il comando. */
    enum class LoadKind { YOUTUBE, YOUTUBE_MUSIC, SEARCH, SITE, PAGE }

    enum class PlayerAction { PLAY, PAUSE, NEXT, PREVIOUS }

    // In ordine di priorità: prima i comandi di una parola (frase intera), poi i più specifici
    // ("… su YouTube Music" prima di "… su YouTube", "metti … su YouTube" prima di "… su YouTube"). La ricerca su un
    // sito viene prima di quella web: "cerca su google maps …" è Google Maps, "cerca … su google" torna web ([WEB_NAMES])
    private enum class Kind {
        BACK, RELOAD, HOME, PAUSE, RESUME, NEXT, PREVIOUS,
        MUSIC_PLAY, MUSIC_SEARCH, YOUTUBE_PLAY, YOUTUBE_SEARCH,
        RADIO_PLAY, RADIO_SEARCH, TV_PLAY, TV_SEARCH, MEDIA_PLAY, MEDIA_SEARCH,
        NAVIGATE, SITE_SEARCH, WEB_SEARCH, OPEN, SEARCH, WATCH, PLAY
    }

    // Schemi: {q} = la parte libera, {site} = il nome di un sito; uno spazio = uno o più spazi;
    // "~" = spazi facoltativi (cinese).
    // Nomi sempre nella forma "youtube", "youtube music", "google": le altre grafie diventano queste ([ALIASES]).
    // Lingue in ordine: it, en, es, fr, de, pt, ru, hu, ar, hi, zh
    private val PHRASES: Map<Kind, List<String>> = mapOf(
        Kind.BACK to listOf(
            "indietro", "torna indietro", "vai indietro", "pagina precedente",
            "back", "go back", "previous page",
            "atrás", "atras", "volver", "vuelve", "vuelve atrás", "volver atrás", "página anterior", "regresa",
            "retour", "reviens", "reviens en arrière", "retour en arrière", "page précédente",
            "zurück", "geh zurück", "gehe zurück", "vorherige seite",
            "voltar", "volta", "volte", "voltar página",
            "назад", "вернись", "вернись назад", "вернуться назад", "предыдущая страница",
            "vissza", "menj vissza", "előző oldal",
            "رجوع", "ارجع", "الرجوع", "عودة", "الصفحة السابقة",
            "पीछे", "पीछे जाओ", "वापस", "वापस जाओ", "पिछला पेज",
            "返回", "后退", "上一页"
        ),
        Kind.RELOAD to listOf(
            "ricarica", "ricarica la pagina", "aggiorna", "aggiorna la pagina",
            "reload", "refresh", "reload the page", "refresh the page", "reload page", "refresh page",
            "recarga", "recargar", "recarga la página", "actualiza", "actualizar", "actualiza la página",
            "recharge", "recharger", "recharge la page", "actualise", "actualiser", "rafraîchis", "rafraîchir",
            "neu laden", "aktualisieren", "seite neu laden", "lade neu", "seite aktualisieren",
            "recarregar", "recarrega", "recarregue", "atualizar", "atualiza", "atualize", "atualizar página",
            "обнови", "обновить", "обнови страницу", "перезагрузи", "перезагрузить", "перезагрузи страницу",
            "frissítés", "frissíts", "frissítsd", "frissítsd az oldalt", "újratöltés", "töltsd újra",
            "تحديث", "حدث", "حدث الصفحة", "اعد التحميل", "اعادة التحميل",
            "रीलोड", "रीलोड करो", "रिफ्रेश", "रिफ्रेश करो", "पेज रिफ्रेश करो",
            "刷新", "重新加载", "刷新页面"
        ),
        Kind.HOME to listOf(
            "home", "pagina iniziale", "vai alla home", "torna alla home", "schermata iniziale",
            "go home", "home page", "homepage", "go to the home page",
            "inicio", "página de inicio", "ve al inicio", "vuelve al inicio", "pantalla de inicio",
            "accueil", "page d'accueil", "va à l'accueil", "retour à l'accueil", "écran d'accueil",
            "startseite", "zur startseite", "geh zur startseite",
            "início", "página inicial", "tela inicial", "ir para o início",
            "домой", "на главную", "главная", "главная страница", "домашняя страница",
            "kezdőlap", "kezdőoldal", "főoldal", "menj a kezdőlapra",
            "الرئيسية", "الصفحة الرئيسية", "اذهب الى الرئيسية", "الشاشة الرئيسية",
            "होम", "होम पेज", "होम पर जाओ", "मुख्य पेज",
            "主页", "首页", "回到主页", "回到首页"
        ),
        Kind.PAUSE to listOf(
            "pausa", "metti in pausa", "ferma", "fermati", "stop", "basta",
            "pause", "pause it", "pause the video", "pause the music", "stop the music",
            "pon pausa", "pausar", "detén", "deten", "para", "parar",
            "mets en pause", "arrête", "arrêter",
            "pausieren", "anhalten", "stopp", "halt",
            "pare", "parar música",
            "пауза", "поставь на паузу", "стоп", "останови", "остановить",
            "szünet", "szüneteltetés", "állj", "állítsd meg",
            "ايقاف مؤقت", "توقف", "اوقف", "ايقاف",
            "रोको", "रुको", "पॉज", "पॉज करो", "बंद करो",
            "暂停", "停止", "停"
        ),
        Kind.RESUME to listOf(
            "riprendi", "continua", "play", "riproduci", "fai ripartire",
            "resume", "continue", "keep playing", "unpause",
            "reanudar", "reanuda", "continúa", "continuar", "sigue", "reproduce", "reproducir",
            "reprends", "reprendre", "continuer", "lecture", "joue", "relance",
            "weiter", "fortsetzen", "weiterspielen", "abspielen", "wiedergabe",
            "retomar", "tocar", "reproduzir",
            "продолжи", "продолжить", "играй", "воспроизведи", "плей",
            "folytatás", "folytasd", "lejátszás", "játszd", "indítsd",
            "استمر", "تابع", "استئناف", "شغل", "تشغيل",
            "चलाओ", "चालू करो", "जारी रखो", "प्ले", "प्ले करो", "फिर से चलाओ",
            "继续", "继续播放", "播放"
        ),
        Kind.NEXT to listOf(
            "prossimo", "prossima", "successivo", "successiva", "avanti", "salta", "prossimo video",
            "prossima canzone", "brano successivo", "canzone successiva", "video successivo",
            "prossimo canale", "canale successivo", "prossima stazione", "stazione successiva", "prossima radio",
            "next", "skip", "next video", "next song", "next track", "skip this", "skip this song",
            "next channel", "channel up", "next station",
            "siguiente", "salta", "saltar", "la siguiente", "siguiente canción", "siguiente vídeo", "siguiente video",
            "siguiente canal", "canal siguiente", "siguiente emisora", "siguiente estación",
            "suivant", "suivante", "passe", "chanson suivante", "vidéo suivante", "morceau suivant",
            "chaîne suivante", "station suivante",
            "nächster", "nächstes", "nächste", "überspringen", "nächstes lied", "nächstes video", "nächster titel",
            "nächster kanal", "nächster sender",
            "próximo", "próxima", "proximo", "proxima", "pular", "pula", "seguinte", "próxima música", "próximo vídeo",
            "próximo canal", "próxima estação", "próxima rádio",
            "следующий", "следующая", "следующее", "дальше", "пропусти", "следующий трек", "следующая песня",
            "следующее видео", "следующий канал", "следующая станция",
            "következő", "ugrás", "ugord át", "következő szám", "következő videó", "következő dal",
            "következő csatorna", "következő adó",
            "التالي", "التالية", "تخطي", "تخطى", "المقطع التالي", "الاغنية التالية", "الفيديو التالي",
            "القناة التالية", "المحطة التالية",
            "अगला", "अगली", "अगला गाना", "अगला वीडियो", "स्किप", "स्किप करो", "अगला चैनल", "अगला स्टेशन",
            "下一个", "下一首", "下一集", "下一个视频", "跳过", "下一个频道", "下一个电台"
        ),
        Kind.PREVIOUS to listOf(
            "precedente", "brano precedente", "canzone precedente", "video precedente",
            "canale precedente", "stazione precedente",
            "previous", "previous song", "previous track", "previous video",
            "previous channel", "channel down", "previous station",
            "anterior", "la anterior", "canción anterior", "vídeo anterior", "video anterior",
            "canal anterior", "emisora anterior", "estación anterior",
            "précédent", "précédente", "chanson précédente", "vidéo précédente", "morceau précédent",
            "chaîne précédente", "station précédente",
            "vorheriger", "vorheriges", "vorherige", "vorheriges lied", "vorheriges video", "vorheriger titel",
            "vorheriger kanal", "vorheriger sender",
            "música anterior", "estação anterior", "rádio anterior",
            "предыдущий", "предыдущая", "предыдущее", "предыдущий трек", "предыдущая песня", "предыдущее видео",
            "предыдущий канал", "предыдущая станция",
            "előző", "előző szám", "előző videó", "előző dal", "előző csatorna", "előző adó",
            "السابق", "السابقة", "الاغنية السابقة", "الفيديو السابق", "المقطع السابق",
            "القناة السابقة", "المحطة السابقة",
            "पिछला", "पिछली", "पिछला गाना", "पिछला वीडियो", "पिछला चैनल", "पिछला स्टेशन",
            "上一个", "上一首", "上一集", "上一个视频", "上一个频道", "上一个电台"
        ),
        Kind.MUSIC_PLAY to listOf(
            "metti {q} su youtube music", "riproduci {q} su youtube music", "ascolta {q} su youtube music",
            "fai partire {q} su youtube music", "youtube music {q}",
            "play {q} on youtube music", "listen to {q} on youtube music", "put on {q} on youtube music",
            "pon {q} en youtube music", "ponme {q} en youtube music", "reproduce {q} en youtube music",
            "mets {q} sur youtube music", "joue {q} sur youtube music", "lance {q} sur youtube music",
            "spiele {q} auf youtube music", "spiel {q} auf youtube music",
            "toca {q} no youtube music", "toque {q} no youtube music", "coloca {q} no youtube music",
            "coloque {q} no youtube music",
            "включи {q} на youtube music", "поставь {q} на youtube music",
            "játszd le {q} a youtube music-on", "játszd le a youtube music-on {q}",
            "شغل {q} على youtube music", "شغل {q} في youtube music",
            "youtube music पर {q} चलाओ", "{q} youtube music पर चलाओ", "youtube music पर {q} बजाओ",
            "{q} youtube music पर बजाओ",
            "在~youtube music~上播放~{q}", "在~youtube music~播放~{q}", "用~youtube music~播放~{q}"
        ),
        Kind.MUSIC_SEARCH to listOf(
            "cerca {q} su youtube music", "trova {q} su youtube music", "cerca su youtube music {q}",
            "search {q} on youtube music", "search for {q} on youtube music", "search youtube music for {q}",
            "find {q} on youtube music",
            "busca {q} en youtube music", "busca en youtube music {q}",
            "cherche {q} sur youtube music", "recherche {q} sur youtube music", "cherche sur youtube music {q}",
            "suche {q} auf youtube music", "such {q} auf youtube music",
            "pesquisa {q} no youtube music", "pesquise {q} no youtube music", "procura {q} no youtube music",
            "pesquisa no youtube music {q}",
            "найди {q} на youtube music", "найди на youtube music {q}",
            "keresd meg {q} a youtube music-on", "keress a youtube music-on {q}",
            "ابحث عن {q} في youtube music", "ابحث عن {q} على youtube music",
            "youtube music पर {q} खोजो", "{q} youtube music पर खोजो",
            "在~youtube music~上搜索~{q}", "在~youtube music~搜索~{q}"
        ),
        Kind.YOUTUBE_PLAY to listOf(
            "metti {q} su youtube", "riproduci {q} su youtube", "fai partire {q} su youtube", "guarda {q} su youtube",
            "ascolta {q} su youtube", "fammi vedere {q} su youtube", "fammi sentire {q} su youtube",
            "metti su youtube {q}",
            "play {q} on youtube", "watch {q} on youtube", "put on {q} on youtube", "listen to {q} on youtube",
            "show me {q} on youtube",
            "pon {q} en youtube", "ponme {q} en youtube", "reproduce {q} en youtube", "quiero ver {q} en youtube",
            "mets {q} sur youtube", "joue {q} sur youtube", "lance {q} sur youtube", "regarde {q} sur youtube",
            "montre-moi {q} sur youtube", "montre moi {q} sur youtube",
            "spiele {q} auf youtube", "spiel {q} auf youtube", "zeig mir {q} auf youtube", "zeige {q} auf youtube",
            "toca {q} no youtube", "toque {q} no youtube", "coloca {q} no youtube", "coloque {q} no youtube",
            "passa {q} no youtube", "mostra {q} no youtube",
            "включи {q} на youtube", "поставь {q} на youtube", "покажи {q} на youtube",
            "játszd le {q} a youtube-on", "játszd le a youtube-on {q}", "indítsd el {q} a youtube-on",
            "شغل {q} على youtube", "شغل {q} في youtube", "اعرض {q} على youtube",
            "youtube पर {q} चलाओ", "{q} youtube पर चलाओ", "youtube पर {q} लगाओ", "{q} youtube पर लगाओ",
            "youtube पर {q} दिखाओ", "{q} youtube पर दिखाओ",
            "在~youtube~上播放~{q}", "在~youtube~播放~{q}", "用~youtube~播放~{q}", "在~youtube~上看~{q}"
        ),
        Kind.YOUTUBE_SEARCH to listOf(
            "cerca {q} su youtube", "cercami {q} su youtube", "trova {q} su youtube", "cerca su youtube {q}",
            "youtube {q}", "{q} su youtube",
            "search {q} on youtube", "search for {q} on youtube", "search youtube for {q}", "find {q} on youtube",
            "look up {q} on youtube", "{q} on youtube",
            "busca {q} en youtube", "buscar {q} en youtube", "búscame {q} en youtube", "busca en youtube {q}",
            "{q} en youtube",
            "cherche {q} sur youtube", "recherche {q} sur youtube", "cherche sur youtube {q}", "{q} sur youtube",
            "suche {q} auf youtube", "such {q} auf youtube", "suche auf youtube nach {q}",
            "such auf youtube nach {q}", "{q} auf youtube",
            "pesquisa {q} no youtube", "pesquise {q} no youtube", "procura {q} no youtube", "procure {q} no youtube",
            "busca {q} no youtube", "busque {q} no youtube", "pesquisa no youtube {q}", "procura no youtube {q}",
            "{q} no youtube",
            "найди {q} на youtube", "поищи {q} на youtube", "найди на youtube {q}", "поиск {q} на youtube",
            "{q} на youtube",
            "keresd meg {q} a youtube-on", "keress a youtube-on {q}", "keresd meg a youtube-on {q}",
            "{q} a youtube-on",
            "ابحث عن {q} في youtube", "ابحث عن {q} على youtube", "ابحث في youtube عن {q}",
            "ابحث على youtube عن {q}", "{q} على youtube", "{q} في youtube",
            "youtube पर {q} खोजो", "youtube पर {q} सर्च करो", "{q} youtube पर खोजो", "{q} youtube पर सर्च करो",
            "youtube पर {q} ढूंढो", "{q} youtube पर ढूंढो",
            "在~youtube~上搜索~{q}", "在~youtube~搜索~{q}", "youtube~搜索~{q}", "在~youtube~上找~{q}",
            "搜索~youtube~上的~{q}"
        ),
        Kind.RADIO_PLAY to listOf(
            "metti {q} alla radio", "metti {q} in radio", "metti {q} sulla radio", "ascolta {q} alla radio",
            "ascolta {q} in radio", "riproduci {q} alla radio", "fammi sentire {q} alla radio", "metti la radio {q}",
            "metti la stazione {q}", "ascolta la radio {q}", "ascolta la stazione {q}", "sintonizza {q}",
            "sintonizzati su {q}", "sintonizza la radio su {q}",
            "play {q} on the radio", "play {q} on radio", "listen to {q} on the radio", "listen to {q} on radio",
            "put on {q} on the radio", "tune to {q}", "tune in to {q}", "tune into {q}", "play the station {q}",
            "play station {q}", "play radio station {q}", "listen to the station {q}", "listen to station {q}",
            "pon {q} en la radio", "ponme {q} en la radio", "reproduce {q} en la radio", "escucha {q} en la radio",
            "sintoniza {q}", "pon la emisora {q}", "pon la estación {q}",
            "mets {q} à la radio", "mets {q} sur la radio", "écoute {q} à la radio", "joue {q} à la radio",
            "mets la station {q}", "écoute la station {q}",
            "spiele {q} im radio", "spiel {q} im radio", "höre {q} im radio", "hör {q} im radio",
            "spiele den sender {q}", "spiel den sender {q}", "schalte auf {q}",
            "toca {q} no rádio", "toque {q} no rádio", "coloca {q} no rádio", "coloque {q} no rádio",
            "põe {q} no rádio", "ouve {q} no rádio", "ouça {q} no rádio", "toca {q} na rádio", "põe {q} na rádio",
            "sintonize {q}", "coloca a estação {q}", "toca a estação {q}", "põe a estação {q}",
            "включи {q} на радио", "поставь {q} на радио", "включи радио {q}", "поставь радио {q}",
            "включи станцию {q}", "поставь станцию {q}",
            "játszd le {q} a rádióban", "játszd {q} a rádióban", "indítsd el {q} a rádióban",
            "kapcsold be a {q} rádiót", "kapcsold be {q} rádiót",
            "شغل {q} على الراديو", "شغل {q} في الراديو", "شغل اذاعة {q}", "شغل راديو {q}", "شغل محطة {q}",
            "रेडियो पर {q} चलाओ", "{q} रेडियो पर चलाओ", "रेडियो पर {q} लगाओ", "{q} रेडियो पर लगाओ",
            "रेडियो पर {q} बजाओ", "{q} रेडियो पर बजाओ", "{q} स्टेशन लगाओ",
            "在~收音机~上播放~{q}", "在~电台~播放~{q}", "用~收音机~播放~{q}", "收听~{q}", "播放~电台~{q}", "打开~电台~{q}"
        ),
        Kind.RADIO_SEARCH to listOf(
            "cerca {q} alla radio", "cerca {q} in radio", "cerca {q} sulla radio", "cerca la radio {q}",
            "cerca la stazione radio {q}", "cerca la stazione {q}", "cerca stazione {q}",
            "search {q} on the radio", "search for {q} on the radio", "search {q} on radio", "search for {q} on radio",
            "find {q} on the radio", "find the station {q}", "find station {q}", "search for the station {q}",
            "search the radio for {q}", "search radio for {q}",
            "busca {q} en la radio", "busca la emisora {q}", "busca la estación {q}",
            "cherche {q} à la radio", "recherche {q} à la radio", "cherche la station {q}", "recherche la station {q}",
            "suche {q} im radio", "such {q} im radio", "suche den sender {q}", "such den sender {q}",
            "pesquisa {q} no rádio", "pesquise {q} no rádio", "procura {q} no rádio", "procure {q} no rádio",
            "procura a estação {q}", "procure a estação {q}", "pesquisa a estação {q}",
            "найди {q} на радио", "поищи {q} на радио", "найди станцию {q}", "найди радио {q}",
            "keresd meg {q} a rádióban", "keress {q} a rádióban",
            "ابحث عن {q} في الراديو", "ابحث عن {q} على الراديو", "ابحث عن محطة {q}", "ابحث عن اذاعة {q}",
            "रेडियो पर {q} खोजो", "{q} रेडियो पर खोजो", "रेडियो पर {q} ढूंढो", "{q} रेडियो पर ढूंढो",
            "在~收音机~上搜索~{q}", "在~电台~搜索~{q}", "搜索~电台~{q}"
        ),
        Kind.TV_PLAY to listOf(
            "metti {q} in tv", "metti {q} in televisione", "metti {q} sulla tv", "metti {q} su iptv",
            "guarda {q} in tv", "guarda {q} in televisione", "guarda {q} sulla tv", "guarda {q} su iptv",
            "riproduci {q} in tv", "fammi vedere {q} in tv", "metti il canale {q}", "guarda il canale {q}",
            "vai sul canale {q}", "passa al canale {q}",
            "play {q} on tv", "play {q} on the tv", "play {q} on iptv", "watch {q} on tv", "watch {q} on the tv",
            "watch {q} on iptv", "put on {q} on tv", "put {q} on tv", "show {q} on tv", "play channel {q}",
            "watch channel {q}", "put on channel {q}", "go to channel {q}", "switch to channel {q}",
            "change to channel {q}",
            "pon {q} en la tele", "pon {q} en la tv", "ponme {q} en la tele", "ponme {q} en la tv",
            "mira {q} en la tele", "mira {q} en la tv", "ver {q} en la tele", "ver {q} en la tv",
            "pon el canal {q}", "ponme el canal {q}", "cambia al canal {q}", "ver el canal {q}",
            "mets {q} à la télé", "mets {q} à la tv", "mets {q} sur la télé", "regarde {q} à la télé",
            "regarde {q} à la tv", "mets la chaîne {q}", "regarde la chaîne {q}", "passe à la chaîne {q}",
            "zeige {q} im fernsehen", "zeig {q} im fernsehen", "schau {q} im fernsehen", "schaue {q} im fernsehen",
            "spiele {q} im fernsehen", "spiel {q} im fernsehen", "spiele {q} im tv", "zeig {q} im tv",
            "schau {q} im tv", "schalte auf kanal {q}", "wechsle zu kanal {q}", "zeige kanal {q}", "zeig kanal {q}",
            "toca {q} na tv", "toque {q} na tv", "coloca {q} na tv", "coloque {q} na tv", "põe {q} na tv",
            "vê {q} na tv", "veja {q} na tv", "assiste {q} na tv", "assista {q} na tv", "coloca o canal {q}",
            "coloque o canal {q}", "põe o canal {q}", "muda para o canal {q}", "vê o canal {q}",
            "включи {q} на тв", "включи {q} по телевизору", "поставь {q} на тв", "покажи {q} на тв",
            "покажи {q} по телевизору", "включи канал {q}", "поставь канал {q}", "переключи на канал {q}",
            "покажи канал {q}",
            "játszd le {q} a tévében", "játszd le {q} a tv-ben", "mutasd {q} a tévében", "mutasd {q} a tv-ben",
            "kapcsold a {q} csatornát", "kapcsold be a {q} csatornát",
            "شغل {q} على التلفزيون", "شغل {q} في التلفزيون", "اعرض {q} على التلفزيون", "شغل قناة {q}", "اعرض قناة {q}",
            "افتح قناة {q}",
            "टीवी पर {q} चलाओ", "{q} टीवी पर चलाओ", "टीवी पर {q} लगाओ", "{q} टीवी पर लगाओ", "टीवी पर {q} दिखाओ",
            "{q} टीवी पर दिखाओ", "{q} चैनल लगाओ", "{q} चैनल चलाओ",
            "在~电视~上播放~{q}", "在~电视~播放~{q}", "用~电视~播放~{q}", "在~电视~上看~{q}", "播放~频道~{q}",
            "切换到~频道~{q}", "打开~频道~{q}"
        ),
        Kind.TV_SEARCH to listOf(
            "cerca {q} in tv", "cerca {q} in televisione", "cerca {q} sulla tv", "cerca il canale {q}", "cerca canale {q}",
            "search {q} on tv", "search for {q} on tv", "search {q} on the tv", "find {q} on tv", "find the channel {q}",
            "find channel {q}", "search channel {q}", "search for the channel {q}", "search for channel {q}",
            "busca {q} en la tele", "busca {q} en la tv", "busca el canal {q}",
            "cherche {q} à la télé", "cherche {q} à la tv", "recherche {q} à la télé", "cherche la chaîne {q}",
            "recherche la chaîne {q}",
            "suche {q} im fernsehen", "such {q} im fernsehen", "suche {q} im tv", "such {q} im tv", "suche kanal {q}",
            "such kanal {q}", "suche den kanal {q}", "such den kanal {q}",
            "pesquisa {q} na tv", "pesquise {q} na tv", "procura {q} na tv", "procure {q} na tv", "procura o canal {q}",
            "procure o canal {q}", "pesquisa o canal {q}",
            "найди {q} на тв", "поищи {q} на тв", "найди канал {q}", "поищи канал {q}",
            "keresd meg {q} a tévében", "keresd meg {q} a tv-ben", "keress {q} a tévében", "keresd meg a {q} csatornát",
            "ابحث عن {q} في التلفزيون", "ابحث عن {q} على التلفزيون", "ابحث عن قناة {q}",
            "टीवी पर {q} खोजो", "{q} टीवी पर खोजो", "टीवी पर {q} ढूंढो", "{q} टीवी पर ढूंढो", "{q} चैनल खोजो",
            "在~电视~上搜索~{q}", "在~电视~搜索~{q}", "搜索~频道~{q}"
        ),
        Kind.MEDIA_PLAY to listOf(
            "metti {q} su media", "metti {q} su carweb media", "riproduci {q} su media", "riproduci {q} su carweb media",
            "ascolta {q} su media", "ascolta {q} su carweb media", "metti {q} dal lettore", "metti {q} sul lettore",
            "riproduci {q} dal lettore", "metti {q} dai miei file", "riproduci {q} dai miei file",
            "metti {q} dalla mia musica", "ascolta {q} dalla mia musica", "riproduci {q} dalla mia musica",
            "play {q} on media", "play {q} on carweb media", "play {q} from media", "play {q} from my music",
            "play {q} from my files", "play {q} in my music", "play {q} on the player", "listen to {q} from my music",
            "listen to {q} on media",
            "pon {q} en media", "pon {q} de mi música", "ponme {q} de mi música", "reproduce {q} en media",
            "reproduce {q} de mi música", "reproduce {q} de mis archivos", "escucha {q} de mi música",
            "pon {q} en el reproductor",
            "mets {q} sur media", "joue {q} sur media", "mets {q} de ma musique", "joue {q} de ma musique",
            "joue {q} de mes fichiers", "écoute {q} de ma musique", "mets {q} sur le lecteur",
            "spiele {q} auf media", "spiel {q} auf media", "spiele {q} aus meiner musik", "spiel {q} aus meiner musik",
            "spiele {q} aus meinen dateien", "spiel {q} aus meinen dateien", "spiele {q} im player",
            "toca {q} no media", "toque {q} no media", "coloca {q} no media", "toca {q} da minha música",
            "toque {q} da minha música", "coloca {q} da minha música", "põe {q} da minha música",
            "toca {q} dos meus arquivos", "toque {q} dos meus arquivos", "ouve {q} da minha música",
            "ouça {q} da minha música",
            "включи {q} на медиа", "поставь {q} на медиа", "включи {q} из моей музыки", "поставь {q} из моей музыки",
            "включи {q} из моих файлов", "поставь {q} из моих файлов", "включи {q} в плеере",
            "játszd le {q} a médiában", "játszd le {q} a zenéim közül", "játszd {q} a zenéim közül",
            "játszd le {q} a fájljaim közül",
            "شغل {q} من موسيقاي", "شغل {q} من ملفاتي", "شغل {q} في الوسائط", "شغل {q} في المشغل",
            "मीडिया पर {q} चलाओ", "{q} मीडिया पर चलाओ", "मेरे गानों में से {q} चलाओ", "{q} मेरे गानों से चलाओ",
            "मेरी फाइलों से {q} चलाओ", "{q} मेरी फाइलों से चलाओ",
            "播放~我的音乐~里的~{q}", "播放~我的音乐~中的~{q}", "在~我的音乐~里播放~{q}", "在~播放器~里播放~{q}",
            "在~媒体~上播放~{q}"
        ),
        Kind.MEDIA_SEARCH to listOf(
            "cerca {q} su media", "cerca {q} su carweb media", "cerca {q} nel lettore", "cerca {q} nei miei file",
            "cerca {q} nella mia musica",
            "search {q} on media", "search for {q} on media", "search {q} in my music", "search for {q} in my music",
            "search {q} in my files", "search for {q} in my files", "find {q} in my music",
            "busca {q} en media", "busca {q} en mi música", "busca {q} en mis archivos", "busca {q} en el reproductor",
            "cherche {q} sur media", "cherche {q} dans ma musique", "cherche {q} dans mes fichiers",
            "suche {q} auf media", "suche {q} in meiner musik", "such {q} in meiner musik", "suche {q} in meinen dateien",
            "pesquisa {q} no media", "pesquisa {q} na minha música", "procura {q} na minha música",
            "procure {q} na minha música", "procura {q} nos meus arquivos",
            "найди {q} на медиа", "найди {q} в моей музыке", "поищи {q} в моей музыке", "найди {q} в моих файлах",
            "keresd meg {q} a médiában", "keresd meg {q} a zenéim között", "keress {q} a zenéim között",
            "ابحث عن {q} في موسيقاي", "ابحث عن {q} في ملفاتي", "ابحث عن {q} في الوسائط",
            "मीडिया पर {q} खोजो", "{q} मीडिया पर खोजो", "मेरे गानों में {q} खोजो", "{q} मेरे गानों में खोजो",
            "在~我的音乐~里搜索~{q}", "在~我的音乐~中搜索~{q}", "在~媒体~上搜索~{q}", "在~播放器~里搜索~{q}"
        ),
        Kind.NAVIGATE to listOf(
            "portami a {q}", "portami in {q}", "portami da {q}", "portami al {q}", "portami alla {q}",
            "naviga verso {q}", "naviga a {q}", "navigazione verso {q}", "andiamo a {q}", "andiamo in {q}",
            "indicazioni per {q}", "percorso per {q}",
            "navigate to {q}", "take me to {q}", "directions to {q}", "get directions to {q}", "drive to {q}",
            "get me to {q}",
            "llévame a {q}", "llevame a {q}", "llévame al {q}", "navega a {q}", "navegar a {q}",
            "cómo llegar a {q}", "como llegar a {q}", "ruta a {q}", "vamos a {q}",
            "emmène-moi à {q}", "emmène moi à {q}", "emmène-moi au {q}", "emmène moi au {q}", "navigue vers {q}",
            "itinéraire vers {q}", "itinéraire pour {q}", "conduis-moi à {q}", "allons à {q}",
            "navigiere zu {q}", "navigiere nach {q}", "bring mich zu {q}", "bring mich nach {q}",
            "fahre zu {q}", "fahr zu {q}", "route zu {q}", "route nach {q}",
            "leva-me a {q}", "leva me a {q}", "me leva para {q}", "me leva até {q}", "navegar para {q}",
            "navega para {q}", "rota para {q}", "como chegar a {q}", "como chegar em {q}", "vamos para {q}",
            "проложи маршрут до {q}", "проложи маршрут к {q}", "маршрут до {q}", "маршрут к {q}",
            "поехали в {q}", "поехали на {q}", "отвези меня в {q}", "отвези меня на {q}", "как доехать до {q}",
            "navigálj ide {q}", "navigálj {q}", "vigyél el {q}", "útvonal ide {q}", "útvonaltervezés {q}",
            "خذني الى {q}", "الطريق الى {q}", "اتجاهات الى {q}", "وجهني الى {q}",
            "{q} ले चलो", "{q} तक ले चलो", "{q} के लिए रास्ता", "{q} का रास्ता दिखाओ", "{q} पर नेविगेट करो",
            "导航到~{q}", "导航去~{q}", "带我去~{q}", "去~{q}~的路线", "到~{q}~怎么走"
        ),
        Kind.WEB_SEARCH to listOf(
            "cerca {q} su google", "cerca su google {q}", "cerca {q} su internet", "cerca {q} sul web", "googla {q}",
            "search {q} on google", "search google for {q}", "google {q}", "search the web for {q}",
            "search the internet for {q}", "search {q} on the internet",
            "busca {q} en google", "busca en google {q}", "busca {q} en internet",
            "cherche {q} sur google", "recherche {q} sur google", "cherche {q} sur internet", "cherche sur google {q}",
            "suche {q} auf google", "suche {q} bei google", "suche bei google nach {q}", "such bei google nach {q}",
            "suche im internet nach {q}", "such im internet nach {q}",
            "pesquisa {q} no google", "pesquise {q} no google", "procura {q} no google", "busca {q} no google",
            "pesquisa no google {q}", "pesquisa {q} na internet",
            "найди {q} в google", "найди в google {q}", "загугли {q}", "найди {q} в интернете",
            "поищи {q} в интернете",
            "keresd meg {q} a google-on", "keress a google-on {q}", "guglizd {q}", "keress rá {q} az interneten",
            "ابحث عن {q} في google", "ابحث عن {q} على google", "ابحث في google عن {q}",
            "ابحث عن {q} في الانترنت", "ابحث عن {q} على الانترنت",
            "google पर {q} खोजो", "{q} google पर खोजो", "google पर {q} सर्च करो", "{q} google पर सर्च करो",
            "इंटरनेट पर {q} खोजो",
            "在~google~上搜索~{q}", "用~google~搜索~{q}", "在网上搜索~{q}", "上网搜索~{q}", "百度一下~{q}"
        ),
        Kind.SITE_SEARCH to listOf(
            "cerca {q} su {site}", "cercami {q} su {site}", "trova {q} su {site}", "cerca su {site} {q}",
            "search {q} on {site}", "search for {q} on {site}", "find {q} on {site}", "look up {q} on {site}",
            "search {site} for {q}", "search on {site} for {q}",
            "busca {q} en {site}", "buscar {q} en {site}", "búscame {q} en {site}", "busca en {site} {q}",
            "cherche {q} sur {site}", "recherche {q} sur {site}", "cherche sur {site} {q}",
            "suche {q} auf {site}", "such {q} auf {site}", "suche {q} bei {site}", "such {q} bei {site}",
            "suche auf {site} nach {q}", "such auf {site} nach {q}",
            "pesquisa {q} no {site}", "pesquise {q} no {site}", "procura {q} no {site}", "procure {q} no {site}",
            "busca {q} no {site}", "pesquisa {q} na {site}", "procura {q} na {site}", "pesquisa no {site} {q}",
            "найди {q} на {site}", "поищи {q} на {site}", "найди на {site} {q}", "поищи на {site} {q}",
            "keresd meg {q} a {site} oldalon", "keress a {site} oldalon {q}", "keress rá {q} a {site} oldalon",
            // Davanti a vocale l'articolo è "az": "az amazon oldalon", "az ebay oldalon"
            "keresd meg {q} az {site} oldalon", "keress az {site} oldalon {q}", "keress rá {q} az {site} oldalon",
            "ابحث عن {q} في {site}", "ابحث عن {q} على {site}", "ابحث في {site} عن {q}", "ابحث على {site} عن {q}",
            "{site} पर {q} खोजो", "{site} पर {q} सर्च करो", "{site} पर {q} ढूंढो",
            "在~{site}~上搜索~{q}", "在~{site}~搜索~{q}", "在~{site}~上找~{q}"
        ),
        Kind.OPEN to listOf(
            "apri {q}", "aprimi {q}", "vai su {q}", "vai a {q}", "apri il sito {q}", "apri il preferito {q}",
            "open {q}", "go to {q}", "open the website {q}", "open website {q}", "open site {q}", "visit {q}",
            "abre {q}", "abrir {q}", "ve a {q}", "ir a {q}", "abre la página {q}", "abre el sitio {q}",
            "ouvre {q}", "ouvrir {q}", "va sur {q}", "aller sur {q}", "ouvre le site {q}",
            "öffne {q}", "öffnen {q}", "geh zu {q}", "gehe zu {q}", "öffne die seite {q}",
            "abra {q}", "vai para {q}", "vá para {q}", "ir para {q}",
            "открой {q}", "открыть {q}", "перейди на {q}", "зайди на {q}", "открой сайт {q}",
            "nyisd meg {q}", "nyisd meg az oldalt {q}",
            "افتح {q}", "اذهب الى {q}", "افتح موقع {q}",
            "{q} खोलो", "{q} ओपन करो", "{q} पर जाओ",
            "打开~{q}", "访问~{q}", "进入~{q}"
        ),
        Kind.SEARCH to listOf(
            "cerca {q}", "cercami {q}", "trova {q}", "ricerca {q}",
            "search {q}", "search for {q}", "find {q}", "look up {q}",
            "busca {q}", "buscar {q}", "búscame {q}", "encuentra {q}",
            "cherche {q}", "recherche {q}", "trouve {q}",
            "suche {q}", "such {q}", "suche nach {q}", "such nach {q}", "finde {q}",
            "pesquisa {q}", "pesquise {q}", "procura {q}", "procure {q}", "busque {q}",
            "найди {q}", "поищи {q}", "поиск {q}", "искать {q}",
            "keresd meg {q}", "keress {q}", "keress rá {q}", "keresés {q}",
            "ابحث عن {q}", "ابحث {q}", "بحث عن {q}",
            "{q} खोजो", "{q} सर्च करो", "{q} ढूंढो",
            "搜索~{q}", "搜~{q}", "查找~{q}", "查一下~{q}", "找~{q}"
        ),
        Kind.WATCH to listOf(
            "guarda {q}", "fammi vedere {q}",
            "watch {q}",
            "mira {q}", "ver {q}",
            "regarde {q}",
            "schau {q}", "schaue {q}",
            "assiste {q}", "assista {q}", "veja {q}",
            "смотри {q}", "посмотри {q}",
            "nézd {q}", "nézd meg {q}",
            "شاهد {q}",
            "{q} देखो",
            "看~{q}", "观看~{q}"
        ),
        Kind.PLAY to listOf(
            "metti {q}", "riproduci {q}", "fai partire {q}", "ascolta {q}", "fammi sentire {q}", "voglio sentire {q}",
            "voglio vedere {q}",
            "play {q}", "put on {q}", "listen to {q}", "i want to hear {q}", "i want to watch {q}",
            "pon {q}", "ponme {q}", "reproduce {q}", "quiero escuchar {q}", "quiero ver {q}",
            "mets {q}", "joue {q}", "lance {q}", "fais-moi écouter {q}", "fais moi écouter {q}",
            "spiele {q}", "spiel {q}", "spiel mir {q}", "ich will {q} hören",
            "toca {q}", "toque {q}", "coloca {q}", "coloque {q}", "quero ouvir {q}", "quero ver {q}",
            "включи {q}", "поставь {q}", "воспроизведи {q}", "хочу послушать {q}", "хочу посмотреть {q}",
            "játszd le {q}", "játszd {q}", "indítsd el {q}",
            "شغل {q}", "اريد ان اسمع {q}",
            "{q} चलाओ", "{q} बजाओ", "{q} लगाओ",
            "播放~{q}", "放~{q}", "我想听~{q}", "我想看~{q}"
        )
    )

    // Altre grafie dei nomi che compaiono negli schemi (riconoscimento vocale nelle varie lingue). Solo parole
    // intere ("загугли" non diventa "заgoogleи"), tranne il cinese che non separa le parole
    private const val NO_LETTER_BEFORE = "(?<![\\p{L}\\p{N}\\p{M}])"
    private const val NO_LETTER_AFTER = "(?![\\p{L}\\p{N}\\p{M}])"

    private fun word(pattern: String) = Regex("$NO_LETTER_BEFORE(?:$pattern)$NO_LETTER_AFTER")

    private val ALIASES: List<Pair<Regex, String>> = listOf(
        word("you\\s*tube") to "youtube",
        word("yt\\s+music") to "youtube music",
        word("ютьюб[еау]?|ютуб[еау]?") to "youtube",
        word("(?:ال)?يوتيوب") to "youtube",
        word("यू\\s*ट्यूब|यूटयूब") to "youtube",
        Regex("油管") to "youtube",
        Regex("youtube\\s*(?:(?:музыка|музыке|мьюзик|ميوزك|ميوزيك|म्यूजिक)$NO_LETTER_AFTER|音乐)") to "youtube music",
        word("car\\s+web") to "carweb",
        word("a\\s*a\\s*b\\s+media") to "carweb media",
        word("i\\s*p\\s*t\\s*v") to "iptv",
        word("t\\s+v|tiv[uù]") to "tv",
        word("гугл[еау]?") to "google",
        word("جوجل|غوغل|قوقل") to "google",
        word("गूगल") to "google",
        Regex("谷歌") to "google",
        word("амазон[еау]?") to "amazon",
        word("википеди[яиюе]") to "wikipedia",
        word("нетфликс[еау]?") to "netflix",
        word("спотифа[йе]") to "spotify",
        word("امازون") to "amazon",
        word("ويكيبيديا") to "wikipedia",
        word("نتفليكس") to "netflix",
        word("سبوتيفاي") to "spotify",
        word("अमेजन") to "amazon",
        word("विकिपीडिया") to "wikipedia",
        word("नेटफ्लिक्स") to "netflix",
        word("स्पॉटिफाई") to "spotify",
        Regex("亚马逊") to "amazon",
        Regex("维基百科") to "wikipedia",
        Regex("网飞") to "netflix"
    )

    private val ARABIC_MARKS = Regex("[\\u064B-\\u0652\\u0670\\u0640]")
    private val EDGE_PUNCTUATION = Regex("^[\\p{P}\\p{S}\\s]+|[\\p{P}\\p{S}\\s]+$")
    private val SPACES = Regex("\\s+")
    private val QUOTES = Regex("^[\"'«»“”„‘’]+|[\"'«»“”„‘’]+$")

    /** [slots]: le parti libere nell'ordine dello schema; [siteBeforeQuery]: "{site} {q}" senza niente in mezzo. */
    private class Rule(val kind: Kind, val regex: Regex, val slots: List<String>, val siteBeforeQuery: Boolean)

    // Nello stesso comando prima gli schemi con più testo fisso: "search for {q} on {site}" deve vincere su
    // "search {q} on {site}", che altrimenti cercherebbe "for …"
    private val RULES: List<Rule> by lazy {
        Kind.entries.flatMap { kind ->
            PHRASES[kind].orEmpty()
                .sortedByDescending { it.replace("{q}", "").replace("{site}", "").length }
                .map { compile(kind, it) }
        }
    }

    /** Il primo comando riconosciuto tra le trascrizioni (la più probabile per prima), altrimenti la prima. */
    fun parse(alternatives: List<String>): Command {
        val parsed = alternatives.asSequence().map(::parse)
        return parsed.firstOrNull { it !is Command.Unmatched } ?: parse(alternatives.firstOrNull().orEmpty())
    }

    fun parse(text: String): Command {
        val normalized = normalize(text)
        if (normalized.isEmpty()) return Command.Unmatched("")
        for (rule in RULES) {
            val match = rule.regex.matchEntire(normalized) ?: continue
            val values = rule.slots.withIndex().associate { (i, slot) -> slot to cleanQuery(match.groupValues[i + 1]) }
            if (values.values.any { it.isEmpty() }) continue
            return toCommand(rule, values) ?: continue
        }
        return Command.Unmatched(normalized)
    }

    /**
     * "Ok Google, metti … su AABrowser": arriva solo quello da riprodurre. Vuoto = riprendi; una frase che è già
     * un comando (es. "… su YouTube Music") vale come tale, il resto si riproduce.
     */
    fun forAssistant(query: String?): Command {
        val text = query?.trim().orEmpty()
        if (text.isEmpty()) return Command.Resume
        return when (val command = parse(text)) {
            is Command.Unmatched -> Command.Play(cleanQuery(text))
            is Command.Search -> Command.Play(command.query)
            else -> command
        }
    }

    private fun toCommand(rule: Rule, values: Map<String, String>): Command? {
        val q = values["q"].orEmpty()
        return when (rule.kind) {
            Kind.SITE_SEARCH -> {
                // "cerca su amazon cuffie bluetooth": dove finisce il nome del sito ("google maps" ha due parole)
                val (site, query) = if (rule.siteBeforeQuery) {
                    splitSiteFirst("${values["site"].orEmpty()} $q") ?: return null
                } else {
                    values["site"].orEmpty() to q
                }
                val section = sectionNamed(site)
                when {
                    section != null -> Command.MediaRequest(section, query, play = false)
                    site in WEB_NAMES -> Command.WebSearch(query)
                    else -> Command.SiteSearch(site, query)
                }
            }
            else -> toCommand(rule.kind, q)
        }
    }

    private fun toCommand(kind: Kind, q: String): Command = when (kind) {
        Kind.BACK -> Command.Back
        Kind.RELOAD -> Command.Reload
        Kind.HOME -> Command.Home
        Kind.PAUSE -> Command.Pause
        Kind.RESUME -> Command.Resume
        Kind.NEXT -> Command.Next
        Kind.PREVIOUS -> Command.Previous
        Kind.MUSIC_PLAY -> Command.YouTubeMusicPlay(q)
        Kind.MUSIC_SEARCH -> Command.YouTubeMusicSearch(q)
        Kind.YOUTUBE_PLAY -> Command.YouTubePlay(q)
        Kind.YOUTUBE_SEARCH -> Command.YouTubeSearch(q)
        Kind.RADIO_PLAY -> Command.MediaRequest(MediaSection.RADIO, q, play = true)
        Kind.RADIO_SEARCH -> Command.MediaRequest(MediaSection.RADIO, q, play = false)
        Kind.TV_PLAY -> Command.MediaRequest(MediaSection.TV, q, play = true)
        Kind.TV_SEARCH -> Command.MediaRequest(MediaSection.TV, q, play = false)
        Kind.MEDIA_PLAY -> Command.MediaRequest(MediaSection.MEDIA, q, play = true)
        Kind.MEDIA_SEARCH -> Command.MediaRequest(MediaSection.MEDIA, q, play = false)
        Kind.NAVIGATE -> Command.Navigate(q)
        Kind.WEB_SEARCH -> Command.WebSearch(q)
        Kind.SITE_SEARCH -> Command.Search(q)
        Kind.OPEN -> Command.Open(q)
        Kind.SEARCH -> Command.Search(q)
        Kind.WATCH -> Command.Watch(q)
        Kind.PLAY -> Command.Play(q)
    }

    /**
     * Cosa fare. [currentUrl] = pagina aperta (decide YouTube o YouTube Music e la ricerca sul sito);
     * [webSearchUrl] = ricerca col motore scelto; [siteSearchUrl] = ricerca sul sito aperto quando lo permette;
     * [playerOwned] = il player di Android Auto è di una pagina, che riceve i suoi tasti. Sennò i tasti detti a voce
     * vanno alla pagina di CarWeb Media aperta: può non aver ancora suonato niente.
     */
    fun plan(
        command: Command,
        currentUrl: String?,
        favorites: List<FavoriteSite>,
        webSearchUrl: (String) -> String,
        siteSearchUrl: (String) -> String = { q -> defaultSiteSearchUrl(currentUrl, q, webSearchUrl) },
        playerOwned: Boolean = true
    ): Plan = when (command) {
        is Command.Search -> mediaSectionOf(currentUrl)?.let { Plan.Media(it, command.query) }
            ?: searchPlan(siteSearchUrl(command.query), command.query)
        is Command.Unmatched -> unmatchedPlan(command.text, currentUrl, siteSearchUrl)
        is Command.MediaRequest -> Plan.Media(command.section, command.query, command.play)
        is Command.WebSearch -> Plan.Load(webSearchUrl(command.query), command.query, LoadKind.SEARCH)
        is Command.SiteSearch -> sitePlan(command, favorites, webSearchUrl)
            ?: "${command.query} ${command.site}".let { searchPlan(siteSearchUrl(it), it) }
        is Command.YouTubeSearch -> Plan.Load(youTubeSearchUrl(command.query), command.query, LoadKind.YOUTUBE)
        is Command.YouTubePlay -> Plan.Load(youTubeSearchUrl(command.query), command.query, LoadKind.YOUTUBE, playFirst = true)
        is Command.YouTubeMusicSearch ->
            Plan.Load(youTubeMusicSearchUrl(command.query), command.query, LoadKind.YOUTUBE_MUSIC)
        is Command.YouTubeMusicPlay ->
            Plan.Load(youTubeMusicSearchUrl(command.query), command.query, LoadKind.YOUTUBE_MUSIC, playFirst = true)
        is Command.Play -> playPlan(command.query, currentUrl)
        is Command.Watch -> watchPlan(command.query, currentUrl)
        is Command.Open -> {
            val favorite = findFavorite(command.target, favorites)
            val address = if (favorite == null) addressOf(command.target) else null
            val section = sectionNamed(command.target)
            val implied = impliedSection(command.target)
            when {
                favorite != null -> Plan.Load(favorite.url, favorite.name, LoadKind.PAGE, favorite = favorite)
                address != null -> Plan.Load(address, command.target, LoadKind.PAGE)
                section != null -> Plan.Media(section)
                implied != null -> Plan.Media(implied, command.target, play = true, fallback = Command.WebSearch(command.target))
                else -> searchPlan(siteSearchUrl(command.target), command.target)
            }
        }
        is Command.Navigate -> Plan.Navigate(command.destination)
        Command.Back -> Plan.Back
        Command.Reload -> Plan.Reload
        Command.Home -> Plan.Home
        Command.Pause -> playerPlan(PlayerAction.PAUSE, currentUrl, playerOwned)
        Command.Resume -> playerPlan(PlayerAction.PLAY, currentUrl, playerOwned)
        Command.Next -> playerPlan(PlayerAction.NEXT, currentUrl, playerOwned)
        Command.Previous -> playerPlan(PlayerAction.PREVIOUS, currentUrl, playerOwned)
    }

    private fun playerPlan(action: PlayerAction, currentUrl: String?, playerOwned: Boolean): Plan {
        val open = if (playerOwned) null else mediaSectionOf(currentUrl)
        return if (open != null) Plan.Media(open, action = action) else Plan.Player(action)
    }

    // "metti la radio" = l'ultima ascoltata; sulle pagine di CarWeb Media "metti …" cerca lì; "metti radio deejay" da
    // un'altra parte prova la radio e, se non c'è una stazione così ("radio ga ga"), torna a YouTube
    private fun playPlan(query: String, currentUrl: String?): Plan {
        sectionNamed(query)?.let { return Plan.Media(it, play = true) }
        mediaSectionOf(currentUrl)?.let { return Plan.Media(it, query, play = true) }
        val youTube = youTubePlayPlan(query, currentUrl)
        if (isYouTube(currentUrl)) return youTube
        return impliedSection(query)?.let { Plan.Media(it, query, play = true, fallback = Command.YouTubePlay(query)) } ?: youTube
    }

    // "guarda rai 1": prima la TV; se non c'è un canale così è un video
    private fun watchPlan(query: String, currentUrl: String?): Plan {
        sectionNamed(query)?.let { return Plan.Media(it, play = true) }
        mediaSectionOf(currentUrl)?.let { return Plan.Media(it, query, play = true) }
        if (isYouTube(currentUrl)) return youTubePlayPlan(query, currentUrl)
        return Plan.Media(MediaSection.TV, query, play = true, fallback = Command.YouTubePlay(query))
    }

    private fun youTubePlayPlan(query: String, currentUrl: String?): Plan.Load =
        if (isYouTubeMusic(currentUrl)) {
            Plan.Load(youTubeMusicSearchUrl(query), query, LoadKind.YOUTUBE_MUSIC, playFirst = true)
        } else {
            Plan.Load(youTubeSearchUrl(query), query, LoadKind.YOUTUBE, playFirst = true)
        }

    // Solo un nome: "radio" apre la pagina, "radio deejay" prova la stazione; su una pagina di CarWeb Media è quello
    // che si vuole sentire lì
    private fun unmatchedPlan(text: String, currentUrl: String?, siteSearchUrl: (String) -> String): Plan {
        sectionNamed(text)?.let { return Plan.Media(it) }
        mediaSectionOf(currentUrl)?.let { return Plan.Media(it, text, play = true) }
        val search = searchPlan(siteSearchUrl(text), text)
        if (isYouTube(currentUrl)) return search
        return impliedSection(text)?.let { Plan.Media(it, text, play = true, fallback = Command.WebSearch(text)) } ?: search
    }

    /**
     * Ricerca su un sito: con la ricerca del sito se la conosciamo (anche per un preferito o un indirizzo di quel
     * sito, es. amazon.it), altrimenti col motore di ricerca limitato al sito ("site:"). null = non è un sito.
     */
    private fun sitePlan(command: Command.SiteSearch, favorites: List<FavoriteSite>, webSearchUrl: (String) -> String): Plan.Load? {
        // "su Google", "su internet": la ricerca normale col motore scelto
        if (command.site in WEB_NAMES) return Plan.Load(webSearchUrl(command.query), command.query, LoadKind.SEARCH)
        KNOWN_SITES.firstOrNull { command.site in it.names }?.let { site ->
            return Plan.Load(site.searchUrl(null, command.query), command.query, LoadKind.SITE, siteName = site.label)
        }
        val favorite = findFavorite(command.site, favorites)
        val address = favorite?.url ?: addressOf(command.site)
        val host = address?.let { parseUri(it)?.host?.lowercase()?.removePrefix("www.")?.removePrefix("m.") }
            ?: return null
        val url = KNOWN_SITES.firstOrNull { it.matchesHost(host) }?.searchUrl(host, command.query)
            ?: webSearchUrl("${command.query} site:$host")
        return Plan.Load(url, command.query, LoadKind.SITE, siteName = favorite?.name ?: host)
    }

    private fun searchPlan(url: String, label: String): Plan.Load {
        val kind = when {
            isYouTubeMusic(url) -> LoadKind.YOUTUBE_MUSIC
            isYouTube(url) -> LoadKind.YOUTUBE
            else -> LoadKind.SEARCH
        }
        return Plan.Load(url, label, kind)
    }

    /** Ricerca della Main: su YouTube e YouTube Music resta lì, altrove il motore di ricerca. */
    fun defaultSiteSearchUrl(currentUrl: String?, query: String, webSearchUrl: (String) -> String): String = when {
        isYouTubeMusic(currentUrl) -> youTubeMusicSearchUrl(query)
        isYouTube(currentUrl) -> youTubeSearchUrl(query)
        else -> webSearchUrl(query)
    }

    // ------------------------------------------------------------------
    // Pagine di CarWeb Media
    // ------------------------------------------------------------------

    private const val PLAYER_HOST = "player.local"

    // Come si chiamano nelle varie lingue, con o senza articolo. Lingue in ordine: it, en, es, fr, de, pt, ru, hu, ar, hi, zh
    private val SECTION_NAMES: Map<String, MediaSection> by lazy {
        buildMap {
            fun add(section: MediaSection, vararg names: String) = names.forEach { put(foldChars(it), section) }
            add(
                MediaSection.RADIO,
                "radio", "la radio", "le radio", "the radio", "das radio", "o rádio", "a rádio", "rádio", "rádió",
                "a rádió", "rádiót", "a rádiót", "радио", "راديو", "الراديو", "रेडियो", "收音机", "电台", "广播"
            )
            add(
                MediaSection.TV,
                "tv", "la tv", "televisione", "la televisione", "iptv", "la iptv",
                "the tv", "television", "the television",
                "tele", "la tele", "televisión", "la televisión",
                "télé", "la télé", "télévision", "la télévision",
                "fernsehen", "das fernsehen", "fernseher", "den fernseher",
                "a tv", "televisão", "a televisão",
                "тв", "телевизор", "телевидение",
                "tévé", "a tévé", "tévét", "a tévét", "televízió", "a televízió",
                "تلفزيون", "التلفزيون", "التلفاز", "टीवी", "电视"
            )
            add(
                MediaSection.MEDIA,
                "media", "carweb media", "lettore", "il lettore", "la mia musica", "i miei file",
                "player", "the player", "my music", "my files",
                "reproductor", "el reproductor", "mi música", "mis archivos",
                "lecteur", "le lecteur", "ma musique", "mes fichiers",
                "meine musik", "meine dateien",
                "reprodutor", "o reprodutor", "minha música", "a minha música", "meus arquivos",
                "плеер", "медиа", "моя музыка", "мою музыку", "мои файлы",
                "média", "a média", "lejátszó", "a lejátszó", "a zeném", "a zenéim", "zenéim", "a fájljaim",
                "المشغل", "الوسائط", "موسيقاي", "ملفاتي",
                "मीडिया", "प्लेयर", "मेरा संगीत", "मेरे गाने", "मेरी फाइलें",
                "媒体", "播放器", "我的音乐", "我的文件"
            )
        }
    }

    private val RADIO_WORDS by lazy { setOf("radio", "rádio", "rádió", "радио", "راديو", "रेडियो", "电台").map(::foldChars).toSet() }
    private val CHANNEL_WORDS by lazy {
        setOf("canale", "channel", "canal", "chaîne", "kanal", "канал", "csatorna", "قناة", "चैनल", "频道").map(::foldChars).toSet()
    }

    private fun sectionNamed(text: String): MediaSection? = SECTION_NAMES[foldChars(text.trim())]

    // Una frase che si capisce da sola: "radio deejay", "virgin radio", "rai radio 1", "canale 5". Con la parola più
    // avanti è probabile una canzone ("video killed the radio star"): lì si cerca dove si cercherebbe comunque
    private fun impliedSection(text: String): MediaSection? {
        val words = foldChars(text).split(SPACES).filter { it.isNotEmpty() }
        if (words.size < 2) return null
        val radio = words.indexOfFirst { it in RADIO_WORDS }
        return when {
            radio == 0 || radio == 1 || radio == words.lastIndex -> MediaSection.RADIO
            words.first() in CHANNEL_WORDS -> MediaSection.TV
            else -> null
        }
    }

    /** La pagina di CarWeb Media di un indirizzo; null se è un altro sito. */
    fun mediaSectionOf(url: String?): MediaSection? {
        val uri = parseUri(url) ?: return null
        if (uri.host?.lowercase() != PLAYER_HOST) return null
        return when (uri.path.orEmpty().trimEnd('/')) {
            "/tv" -> MediaSection.TV
            "/radio" -> MediaSection.RADIO
            else -> MediaSection.MEDIA
        }
    }

    fun mediaSectionUrl(section: MediaSection): String = when (section) {
        MediaSection.MEDIA -> "https://$PLAYER_HOST/"
        MediaSection.TV -> "https://$PLAYER_HOST/tv"
        MediaSection.RADIO -> "https://$PLAYER_HOST/radio"
    }

    // ------------------------------------------------------------------
    // Siti con la loro ricerca ("cerca … su Amazon")
    // ------------------------------------------------------------------

    /** [names]: come si dice il sito dopo [ALIASES]; [searchUrl] riceve l'host del preferito, se c'è. */
    private class KnownSite(
        val label: String,
        val names: List<String>,
        val matchesHost: (String) -> Boolean,
        val searchUrl: (host: String?, query: String) -> String
    )

    private val KNOWN_SITES = listOf(
        KnownSite("YouTube Music", listOf("youtube music"), { it == "music.youtube.com" }) { _, q -> youTubeMusicSearchUrl(q) },
        KnownSite("YouTube", listOf("youtube"), { it == "youtube.com" || it.endsWith(".youtube.com") }) { _, q ->
            youTubeSearchUrl(q)
        },
        KnownSite("Spotify", listOf("spotify"), { it.endsWith("spotify.com") }) { _, q ->
            "https://open.spotify.com/search/${encode(q)}"
        },
        KnownSite("Amazon", listOf("amazon"), { it.startsWith("amazon.") }) { host, q ->
            "https://www.${host ?: amazonDomain()}/s?k=${encode(q)}"
        },
        KnownSite("eBay", listOf("ebay"), { it.startsWith("ebay.") }) { host, q ->
            "https://www.${host ?: ebayDomain()}/sch/i.html?_nkw=${encode(q)}"
        },
        KnownSite("Wikipedia", listOf("wikipedia"), { it.endsWith("wikipedia.org") }) { host, q ->
            "https://${host ?: wikipediaHost()}/w/index.php?search=${encode(q)}"
        },
        KnownSite("Reddit", listOf("reddit"), { it.endsWith("reddit.com") }) { _, q ->
            "https://www.reddit.com/search/?q=${encode(q)}"
        },
        KnownSite("IMDb", listOf("imdb"), { it.endsWith("imdb.com") }) { _, q -> "https://www.imdb.com/find/?q=${encode(q)}" },
        KnownSite("Twitch", listOf("twitch"), { it.endsWith("twitch.tv") }) { _, q ->
            "https://www.twitch.tv/search?term=${encode(q)}"
        },
        KnownSite("Netflix", listOf("netflix"), { it.endsWith("netflix.com") }) { _, q ->
            "https://www.netflix.com/search?q=${encode(q)}"
        },
        KnownSite(
            "Google Maps",
            listOf("google maps", "maps", "mappe", "mapas", "carte", "karten", "карты", "térkép", "خرائط", "मैप्स", "地图"),
            { false }
        ) { _, q -> "https://www.google.com/maps/search/${encode(q)}" },
        KnownSite("DAZN", listOf("dazn"), { it.endsWith("dazn.com") }) { _, q -> "https://www.dazn.com/search/${encode(q)}" },
        KnownSite("Google News", listOf("google news", "google notizie", "news"), { it == "news.google.com" }) { _, q ->
            "https://news.google.com/search?q=${encode(q)}"
        },
        KnownSite("Booking", listOf("booking", "booking.com"), { it.endsWith("booking.com") }) { _, q ->
            "https://www.booking.com/searchresults.html?ss=${encode(q)}"
        },
        KnownSite("Tripadvisor", listOf("tripadvisor", "trip advisor"), { it.contains("tripadvisor.") }) { host, q ->
            "https://www.${host ?: "tripadvisor.com"}/Search?q=${encode(q)}"
        },
        KnownSite("Subito", listOf("subito", "subito.it"), { it.endsWith("subito.it") }) { _, q ->
            "https://www.subito.it/annunci-italia/vendita/usato/?q=${encode(q)}"
        },
        KnownSite("GitHub", listOf("github", "git hub"), { it.endsWith("github.com") }) { _, q ->
            "https://github.com/search?q=${encode(q)}"
        },
        KnownSite("X", listOf("twitter"), { it == "x.com" || it.endsWith("twitter.com") }) { _, q ->
            "https://x.com/search?q=${encode(q)}"
        },
        KnownSite("Facebook", listOf("facebook"), { it.endsWith("facebook.com") }) { _, q ->
            "https://www.facebook.com/search/top?q=${encode(q)}"
        },
        KnownSite("TikTok", listOf("tiktok", "tik tok"), { it.endsWith("tiktok.com") }) { _, q ->
            "https://www.tiktok.com/search?q=${encode(q)}"
        },
        KnownSite("Pinterest", listOf("pinterest"), { it.contains("pinterest.") }) { _, q ->
            "https://www.pinterest.com/search/pins/?q=${encode(q)}"
        }
    )

    // Nomi che vogliono dire "il web": ricerca col motore scelto, non su un sito
    private val WEB_NAMES = setOf(
        "google", "internet", "web", "il web", "the web", "the internet", "интернете", "интернет", "الانترنت",
        "इंटरनेट", "网上", "网络", "互联网"
    )

    // I nomi lunghi prima: "google maps ristoranti" è Google Maps, non "google"
    private val SITE_NAMES: List<String> by lazy {
        (KNOWN_SITES.flatMap { it.names } + WEB_NAMES).sortedByDescending { it.length }
    }

    /** "amazon cuffie bluetooth" → ("amazon", "cuffie bluetooth"); un sito sconosciuto è la prima parola. */
    private fun splitSiteFirst(text: String): Pair<String, String>? {
        val site = SITE_NAMES.firstOrNull { text.startsWith("$it ") } ?: text.substringBefore(' ')
        val query = text.removePrefix(site).trim()
        return if (site.isEmpty() || query.isEmpty()) null else site to query
    }

    // Amazon ed eBay del paese del telefono, Wikipedia nella sua lingua
    private fun country(): String = Locale.getDefault().country.uppercase(Locale.ROOT)

    private fun amazonDomain(): String = when (country()) {
        "IT" -> "amazon.it"
        "DE", "AT", "CH" -> "amazon.de"
        "FR" -> "amazon.fr"
        "BE" -> "amazon.com.be"
        "ES" -> "amazon.es"
        "GB", "IE" -> "amazon.co.uk"
        "NL" -> "amazon.nl"
        "PL" -> "amazon.pl"
        "SE" -> "amazon.se"
        "TR" -> "amazon.com.tr"
        "IN" -> "amazon.in"
        "JP" -> "amazon.co.jp"
        "BR" -> "amazon.com.br"
        "MX" -> "amazon.com.mx"
        "CA" -> "amazon.ca"
        "AU" -> "amazon.com.au"
        "AE" -> "amazon.ae"
        "SA" -> "amazon.sa"
        "EG" -> "amazon.eg"
        else -> "amazon.com"
    }

    private fun ebayDomain(): String = when (country()) {
        "IT" -> "ebay.it"
        "DE" -> "ebay.de"
        "AT" -> "ebay.at"
        "CH" -> "ebay.ch"
        "FR" -> "ebay.fr"
        "ES" -> "ebay.es"
        "GB" -> "ebay.co.uk"
        "IE" -> "ebay.ie"
        "NL" -> "ebay.nl"
        "PL" -> "ebay.pl"
        "AU" -> "ebay.com.au"
        "CA" -> "ebay.ca"
        else -> "ebay.com"
    }

    private fun wikipediaHost(): String = "${Locale.getDefault().language.ifEmpty { "en" }}.wikipedia.org"

    fun youTubeSearchUrl(query: String) = "https://www.youtube.com/results?search_query=${encode(query)}"

    fun youTubeMusicSearchUrl(query: String) = "https://music.youtube.com/search?q=${encode(query)}"

    /** Indicazioni di Google Maps nel browser, dalla posizione attuale (Main). */
    fun mapsDirectionsUrl(destination: String) =
        "https://www.google.com/maps/dir/?api=1&destination=${encode(destination)}"

    /**
     * Destinazione per il navigatore di Android Auto (Wide, CarContext.ACTION_NAVIGATE). Spazi come "+", nel
     * formato degli esempi della documentazione di startCarApp ("geo:0,0?q=123+Main+St").
     */
    fun navigationGeoUri(destination: String) = "geo:0,0?q=${URLEncoder.encode(destination, "UTF-8")}"

    /** Pagina dei risultati di una ricerca di YouTube o YouTube Music (dove parte il primo risultato). */
    fun isSearchResultsPage(url: String?): Boolean {
        val uri = parseUri(url) ?: return false
        val host = uri.host?.lowercase() ?: return false
        val path = uri.path.orEmpty()
        return when {
            host == "music.youtube.com" -> path.startsWith("/search")
            host == "youtube.com" || host.endsWith(".youtube.com") -> path.startsWith("/results")
            else -> false
        }
    }

    private fun isYouTubeMusic(url: String?): Boolean = parseUri(url)?.host?.lowercase() == "music.youtube.com"

    /** Pagina di YouTube o YouTube Music: lì il video di "metti …" parte anche con l'autoplay spento. */
    fun isYouTubeUrl(url: String?): Boolean = isYouTube(url)

    private fun isYouTube(url: String?): Boolean {
        val host = parseUri(url)?.host?.lowercase() ?: return false
        return host == "youtube.com" || host.endsWith(".youtube.com") || host == "youtu.be"
    }

    private fun parseUri(url: String?): URI? = try {
        url?.let { URI(it) }
    } catch (_: Exception) {
        null
    }

    // %20 e non "+" per gli spazi: va bene sia nelle query sia nei percorsi (Spotify, Maps, DAZN)
    private fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

    // ------------------------------------------------------------------
    // Preferiti e indirizzi ("apri …")
    // ------------------------------------------------------------------

    /** Il preferito che si chiama come quello detto (o il cui sito ha quel nome), altrimenti null. */
    fun findFavorite(target: String, favorites: List<FavoriteSite>): FavoriteSite? {
        val key = simplify(target)
        if (key.isEmpty()) return null
        val compact = key.replace(" ", "")
        val named = favorites.map { it to simplify(it.name) }

        // Stesso nome, anche senza spazi ("whats app" = "WhatsApp")
        named.firstOrNull { (_, name) -> name == key || name.replace(" ", "") == compact }?.let { return it.first }
        // Stesso sito ("youtube" = m.youtube.com)
        favorites.firstOrNull { siteName(it.url) == compact }?.let { return it }
        if (compact.length < 3) return null
        // Il nome comincia così o ne contiene la parola ("netflix" = "Netflix Italia"): il più corto
        named.filter { (_, name) -> name.startsWith(key) || name.split(' ').contains(key) }
            .minByOrNull { it.second.length }?.let { return it.first }
        // Una lettera capita male ("netflics")
        if (compact.length >= 5) {
            named.firstOrNull { (_, name) -> editDistance(name.replace(" ", ""), compact) <= 1 }?.let { return it.first }
        }
        return null
    }

    private val DOT_WORDS = Regex("\\s+(punto|dot|point|punkt|ponto|точка|pont|نقطة|डॉट)\\s+")
    private val CHINESE_DOT = Regex("(?<=[a-z0-9])\\s*点\\s*(?=[a-z])")

    /** "repubblica punto it" o "repubblica.it" → indirizzo; null se non lo è. */
    fun addressOf(target: String): String? {
        val candidate = target
            .replace(DOT_WORDS, ".")
            .replace(CHINESE_DOT, ".")
            .replace(Regex("\\s*\\.\\s*"), ".")
            .trim()
        return UrlUtils.asUrlOrNull(candidate)
    }

    // Nome del sito senza "www." e dominio di primo livello: "https://web.whatsapp.com" → "whatsapp"
    private fun siteName(url: String): String? {
        val labels = parseUri(url)?.host?.lowercase()?.split('.')?.filter { it.isNotEmpty() } ?: return null
        return when {
            labels.size >= 2 -> labels[labels.size - 2]
            labels.size == 1 -> labels[0]
            else -> null
        }
    }

    private fun simplify(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{InCombiningDiacriticalMarks}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
            .trim()

    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    // ------------------------------------------------------------------
    // Testo e schemi
    // ------------------------------------------------------------------

    // Minuscole, segni diacritici arabi e varianti di alef, nukta hindi: scritture diverse della stessa parola
    private fun foldChars(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFC).lowercase()
            .replace(ARABIC_MARKS, "")
            .replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
            .replace("़", "")
            .replace('’', '\'')

    private fun normalize(text: String): String {
        var result = foldChars(text)
        for ((regex, replacement) in ALIASES) result = result.replace(regex, replacement)
        return result.replace(EDGE_PUNCTUATION, "").replace(SPACES, " ")
    }

    private fun cleanQuery(query: String): String =
        query.trim().replace(QUOTES, "").replace(EDGE_PUNCTUATION, "").trim()

    private fun compile(kind: Kind, pattern: String): Rule {
        val folded = foldChars(pattern)
        val slots = mutableListOf<String>()
        // Con il sito in fondo ("cerca {q} su {site}") il testo va fino all'ultimo "su": "cerca libri su roma su amazon"
        val queryBeforeSite = folded.indexOf("{q}").let { it >= 0 && it < folded.indexOf("{site}") }
        val regex = StringBuilder()
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                regex.append(Regex.escape(literal.toString()))
                literal.clear()
            }
        }
        var i = 0
        while (i < folded.length) {
            when {
                folded.startsWith("{q}", i) -> {
                    flush()
                    regex.append(if (queryBeforeSite) "(.+)" else "(.+?)")
                    slots.add("q")
                    i += 3
                }
                folded.startsWith("{site}", i) -> {
                    flush()
                    regex.append("(.+?)")
                    slots.add("site")
                    i += 6
                }
                folded[i] == ' ' -> {
                    flush()
                    regex.append("\\s+")
                    i++
                }
                folded[i] == '~' -> {
                    flush()
                    regex.append("\\s*")
                    i++
                }
                else -> {
                    literal.append(folded[i])
                    i++
                }
            }
        }
        flush()
        return Rule(kind, Regex(regex.toString()), slots, siteBeforeQuery = folded.contains("{site} {q}"))
    }
}
