package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.utils.VoiceCommands.Command
import com.fcaronte.aabrowser.utils.VoiceCommands.LoadKind
import com.fcaronte.aabrowser.utils.VoiceCommands.Plan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frasi dei comandi vocali nelle lingue dell'app, come le scrive il riconoscimento vocale. */
class VoiceCommandsTest {

    private fun assertParsed(expected: Command, text: String) =
        assertEquals(text, expected, VoiceCommands.parse(text))

    @Test
    fun italian() {
        assertParsed(Command.YouTubePlay("never gonna give you up"), "Metti never gonna give you up su YouTube")
        assertParsed(Command.YouTubeSearch("gatti divertenti"), "cerca gatti divertenti su youtube")
        assertParsed(Command.YouTubeSearch("gatti divertenti"), "Cerca gatti divertenti su You Tube")
        assertParsed(Command.YouTubeMusicPlay("vasco rossi"), "metti vasco rossi su youtube music")
        assertParsed(Command.Navigate("via roma 10 milano"), "Portami a via Roma 10 Milano")
        assertParsed(Command.Open("whatsapp"), "apri WhatsApp")
        assertParsed(Command.WebSearch("meteo domani"), "cerca meteo domani su google")
        assertParsed(Command.Search("ricetta carbonara"), "cerca ricetta carbonara")
        assertParsed(Command.Play("albachiara"), "metti albachiara")
        assertParsed(Command.Pause, "Metti in pausa")
        assertParsed(Command.Pause, "pausa.")
        assertParsed(Command.Resume, "riprendi")
        assertParsed(Command.Next, "prossimo")
        assertParsed(Command.Previous, "precedente")
        assertParsed(Command.Back, "torna indietro")
        assertParsed(Command.Reload, "ricarica la pagina")
        assertParsed(Command.Home, "pagina iniziale")
        assertParsed(Command.Unmatched("che tempo fa a roma"), "Che tempo fa a Roma?")
        // "aprile" non è "apri …"
        assertParsed(Command.Unmatched("aprile 2026"), "aprile 2026")
    }

    @Test
    fun english() {
        assertParsed(Command.YouTubePlay("lofi hip hop"), "Play lofi hip hop on YouTube")
        assertParsed(Command.YouTubeSearch("cat videos"), "search youtube for cat videos")
        assertParsed(Command.YouTubeMusicPlay("bohemian rhapsody"), "play bohemian rhapsody on youtube music")
        assertParsed(Command.Navigate("the airport"), "Take me to the airport")
        assertParsed(Command.Open("netflix"), "open netflix")
        assertParsed(Command.WebSearch("pizza near me"), "google pizza near me")
        assertParsed(Command.Play("jazz"), "play jazz")
        assertParsed(Command.Resume, "play")
        assertParsed(Command.Pause, "stop")
        assertParsed(Command.Next, "skip")
        assertParsed(Command.Reload, "refresh")
        assertParsed(Command.Home, "go home")
        // "for" non fa parte di quello che si cerca
        assertParsed(Command.YouTubeSearch("cat videos"), "search for cat videos on youtube")
        assertParsed(Command.Search("pizza"), "search for pizza")
    }

    @Test
    fun siteSearch() {
        assertParsed(Command.SiteSearch("amazon", "cuffie bluetooth"), "Cerca cuffie bluetooth su Amazon")
        assertParsed(Command.SiteSearch("amazon", "cuffie bluetooth"), "cerca su amazon cuffie bluetooth")
        assertParsed(Command.SiteSearch("google maps", "ristoranti"), "cerca su google maps ristoranti")
        assertParsed(Command.SiteSearch("amazon", "libri su roma"), "cerca libri su roma su amazon")
        assertParsed(Command.SiteSearch("wikipedia", "leonardo da vinci"), "search wikipedia for leonardo da vinci")
        assertParsed(Command.SiteSearch("amazon", "headphones"), "search for headphones on amazon")
        assertParsed(Command.SiteSearch("ebay", "zapatillas"), "busca zapatillas en ebay")
        assertParsed(Command.SiteSearch("wikipedia", "napoléon"), "cherche napoléon sur wikipedia")
        assertParsed(Command.SiteSearch("amazon", "kopfhörer"), "suche auf amazon nach kopfhörer")
        assertParsed(Command.SiteSearch("amazon", "наушники"), "найди наушники на амазоне")
        assertParsed(Command.SiteSearch("amazon", "fejhallgató"), "keress az amazon oldalon fejhallgató")
        assertParsed(Command.SiteSearch("amazon", "fejhallgató"), "keresd meg fejhallgató az amazon oldalon")
        assertParsed(Command.SiteSearch("wikipedia", "petőfi"), "keresd meg petőfi a wikipedia oldalon")
        assertParsed(Command.SiteSearch("amazon", "fones"), "pesquisa fones na amazon")
        // Gli esempi del suggerimento del microfono, nelle altre lingue
        assertParsed(Command.SiteSearch("amazon", "headphones"), "search headphones on amazon")
        assertParsed(Command.SiteSearch("amazon", "auriculares"), "busca auriculares en amazon")
        assertParsed(Command.SiteSearch("amazon", "casque"), "cherche casque sur amazon")
        assertParsed(Command.SiteSearch("amazon", "kopfhörer"), "suche kopfhörer auf amazon")
        assertParsed(Command.SiteSearch("amazon", "наушники"), "найди наушники на амазон")
        assertParsed(Command.SiteSearch("amazon", "سماعات"), "ابحث عن سماعات في أمازون")
        assertParsed(Command.SiteSearch("amazon", "हेडफोन"), "अमेज़न पर हेडफोन खोजो")
        assertParsed(Command.SiteSearch("amazon", "耳机"), "在亚马逊上搜索耳机")
        // YouTube e Google restano i loro comandi
        assertParsed(Command.YouTubeSearch("gatti"), "cerca gatti su youtube")
        assertParsed(Command.WebSearch("meteo"), "cerca meteo su google")
    }

    @Test
    fun siteSearchPlans() {
        val reddit = VoiceCommands.plan(Command.SiteSearch("reddit", "android auto"), null, favorites, webSearch)
        assertEquals(
            Plan.Load("https://www.reddit.com/search/?q=android%20auto", "android auto", LoadKind.SITE, siteName = "Reddit"),
            reddit
        )
        // Preferito di un sito conosciuto: la ricerca di quel dominio
        val amazonFavorites = favorites + FavoriteSite(id = "5", name = "Amazon", url = "https://www.amazon.it/")
        val amazon = VoiceCommands.plan(Command.SiteSearch("amazon", "cuffie"), null, amazonFavorites, webSearch) as Plan.Load
        assertEquals(LoadKind.SITE, amazon.kind)
        assertTrue(amazon.url, amazon.url.contains("/s?k=cuffie"))
        // Preferito di un sito qualsiasi: il motore di ricerca limitato a quel sito
        val whatsapp = VoiceCommands.plan(Command.SiteSearch("whatsapp", "gruppi"), null, favorites, webSearch) as Plan.Load
        assertEquals("https://www.google.com/search?q=gruppi site:web.whatsapp.com", whatsapp.url)
        assertEquals("WhatsApp", whatsapp.siteName)
        // Indirizzo detto
        val address = VoiceCommands.plan(Command.SiteSearch("repubblica.it", "elezioni"), null, favorites, webSearch) as Plan.Load
        assertEquals("https://www.google.com/search?q=elezioni site:repubblica.it", address.url)
        // "su Google" e simili: ricerca normale
        assertEquals(
            Plan.Load("https://www.google.com/search?q=pizza", "pizza", LoadKind.SEARCH),
            VoiceCommands.plan(Command.SiteSearch("internet", "pizza"), null, favorites, webSearch)
        )
        // Non è un sito: si cerca tutto
        val notASite = VoiceCommands.plan(Command.SiteSearch("via roma", "ristoranti"), null, favorites, webSearch) as Plan.Load
        assertEquals("https://www.google.com/search?q=ristoranti via roma", notASite.url)
        assertEquals(LoadKind.SEARCH, notASite.kind)
    }

    @Test
    fun spanishFrenchGermanPortuguese() {
        assertParsed(Command.YouTubePlay("despacito"), "Pon despacito en YouTube")
        assertParsed(Command.YouTubeSearch("recetas fáciles"), "busca recetas fáciles en youtube")
        assertParsed(Command.Navigate("la estación"), "llévame a la estación")
        assertParsed(Command.Next, "siguiente")

        assertParsed(Command.YouTubePlay("stromae"), "Mets Stromae sur YouTube")
        assertParsed(Command.YouTubeSearch("recette crêpes"), "cherche recette crêpes sur youtube")
        assertParsed(Command.Navigate("la gare"), "emmène-moi à la gare")
        assertParsed(Command.Previous, "précédent")

        assertParsed(Command.YouTubePlay("rammstein"), "Spiele Rammstein auf YouTube")
        assertParsed(Command.YouTubeSearch("katzenvideos"), "suche auf youtube nach katzenvideos")
        assertParsed(Command.Navigate("berlin"), "navigiere nach Berlin")
        assertParsed(Command.Next, "nächstes lied")

        assertParsed(Command.YouTubePlay("anitta"), "toca anitta no youtube")
        assertParsed(Command.Navigate("lisboa"), "leva-me a Lisboa")
        assertParsed(Command.Search("receita de bolo"), "pesquisa receita de bolo")
        assertParsed(Command.Next, "próxima")
    }

    @Test
    fun russianAndHungarian() {
        assertParsed(Command.YouTubePlay("кино"), "включи кино на ютубе")
        assertParsed(Command.YouTubeSearch("котиков"), "найди котиков на ютубе")
        assertParsed(Command.Navigate("москвы"), "проложи маршрут до Москвы")
        assertParsed(Command.WebSearch("погода"), "загугли погода")
        assertParsed(Command.Pause, "пауза")
        assertParsed(Command.Next, "следующий трек")

        assertParsed(Command.YouTubePlay("queen"), "játszd le queen a YouTube-on")
        assertParsed(Command.YouTubeSearch("macskák"), "keresd meg macskák a youtube-on")
        assertParsed(Command.Pause, "szünet")
        assertParsed(Command.Next, "következő")
    }

    @Test
    fun arabicHindiChinese() {
        // Con o senza segni diacritici e hamza
        assertParsed(Command.YouTubePlay("فيروز"), "شغّل فيروز على اليوتيوب")
        assertParsed(Command.YouTubeSearch("وصفات"), "ابحث عن وصفات في يوتيوب")
        assertParsed(Command.Navigate("المطار"), "خذني إلى المطار")
        assertParsed(Command.Open("youtube"), "افتح يوتيوب")
        assertParsed(Command.Pause, "إيقاف مؤقت")
        assertParsed(Command.Next, "التالي")

        assertParsed(Command.YouTubePlay("अरिजीत सिंह के गाने"), "यूट्यूब पर अरिजीत सिंह के गाने चलाओ")
        assertParsed(Command.YouTubeSearch("खाना बनाने की विधि"), "खाना बनाने की विधि यूट्यूब पर खोजो")
        assertParsed(Command.Navigate("दिल्ली"), "दिल्ली ले चलो")
        assertParsed(Command.Pause, "पॉज़")
        assertParsed(Command.Next, "अगला गाना")

        assertParsed(Command.YouTubePlay("周杰伦"), "在YouTube上播放周杰伦")
        assertParsed(Command.YouTubeSearch("猫"), "在 YouTube 上搜索猫")
        assertParsed(Command.Navigate("天安门"), "导航到天安门")
        assertParsed(Command.Open("百度"), "打开百度")
        assertParsed(Command.Resume, "播放")
        assertParsed(Command.Play("音乐"), "播放音乐")
        assertParsed(Command.Pause, "暂停。")
    }

    @Test
    fun alternativesPreferACommand() {
        val texts = listOf("mettiqueen su you tub", "metti queen su youtube")
        assertEquals(Command.YouTubePlay("queen"), VoiceCommands.parse(texts))
    }

    @Test
    fun assistantQueries() {
        assertEquals(Command.Resume, VoiceCommands.forAssistant(""))
        assertEquals(Command.Resume, VoiceCommands.forAssistant(null))
        assertEquals(Command.Play("never gonna give you up"), VoiceCommands.forAssistant("never gonna give you up"))
        assertEquals(Command.Play("gatti"), VoiceCommands.forAssistant("cerca gatti"))
        assertEquals(Command.YouTubeMusicPlay("queen"), VoiceCommands.forAssistant("play queen on youtube music"))
    }

    private val favorites = listOf(
        FavoriteSite(id = "1", name = "YouTube", url = "https://m.youtube.com/"),
        FavoriteSite(id = "2", name = "WhatsApp", url = "https://web.whatsapp.com/"),
        FavoriteSite(id = "3", name = "AAB Media", url = "https://player.local/"),
        FavoriteSite(id = "4", name = "Netflix Italia", url = "https://www.netflix.com/it/")
    )

    private val webSearch: (String) -> String = { "https://www.google.com/search?q=$it" }

    @Test
    fun favoritesByName() {
        assertEquals("2", VoiceCommands.findFavorite("whats app", favorites)?.id)
        assertEquals("2", VoiceCommands.findFavorite("whatsap", favorites)?.id)
        assertEquals("1", VoiceCommands.findFavorite("youtube", favorites)?.id)
        assertEquals("3", VoiceCommands.findFavorite("player", favorites)?.id)
        assertEquals("4", VoiceCommands.findFavorite("netflix", favorites)?.id)
        assertNull(VoiceCommands.findFavorite("meteo", favorites))
    }

    @Test
    fun spokenAddresses() {
        assertEquals("https://repubblica.it", VoiceCommands.addressOf("repubblica punto it"))
        assertEquals("https://repubblica.it", VoiceCommands.addressOf("repubblica.it"))
        assertNull(VoiceCommands.addressOf("la repubblica"))
    }

    @Test
    fun planFollowsTheOpenPage() {
        assertEquals(
            Plan.Load("https://music.youtube.com/search?q=queen", "queen", LoadKind.YOUTUBE_MUSIC, playFirst = true),
            VoiceCommands.plan(Command.Play("queen"), "https://music.youtube.com/watch?v=x", favorites, webSearch)
        )
        val elsewhere = VoiceCommands.plan(Command.Play("queen"), "https://www.repubblica.it/", favorites, webSearch)
        assertEquals(
            Plan.Load("https://www.youtube.com/results?search_query=queen", "queen", LoadKind.YOUTUBE, playFirst = true),
            elsewhere
        )
        val onYouTube = VoiceCommands.plan(Command.Unmatched("gatti"), "https://m.youtube.com/", favorites, webSearch)
        assertEquals(LoadKind.YOUTUBE, (onYouTube as Plan.Load).kind)
        val noPage = VoiceCommands.plan(Command.Unmatched("gatti"), null, favorites, webSearch)
        assertEquals("https://www.google.com/search?q=gatti", (noPage as Plan.Load).url)
        val open = VoiceCommands.plan(Command.Open("whatsapp"), null, favorites, webSearch)
        assertEquals("2", (open as Plan.Load).favorite?.id)
        assertEquals(Plan.Player(VoiceCommands.PlayerAction.PAUSE), VoiceCommands.plan(Command.Pause, null, favorites, webSearch))
    }

    @Test
    fun urls() {
        assertTrue(VoiceCommands.isSearchResultsPage("https://m.youtube.com/results?search_query=x"))
        assertTrue(VoiceCommands.isSearchResultsPage("https://music.youtube.com/search?q=x"))
        assertFalse(VoiceCommands.isSearchResultsPage("https://m.youtube.com/watch?v=x"))
        assertEquals(
            "https://www.youtube.com/results?search_query=rock%20%26%20roll",
            VoiceCommands.youTubeSearchUrl("rock & roll")
        )
        assertEquals("geo:0,0?q=via+roma+10", VoiceCommands.navigationGeoUri("via roma 10"))
    }
}
