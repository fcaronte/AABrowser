package com.fcaronte.aabrowser.utils

import com.fcaronte.aabrowser.model.FavoriteSite
import com.fcaronte.aabrowser.utils.VoiceCommands.Command
import com.fcaronte.aabrowser.utils.VoiceCommands.LoadKind
import com.fcaronte.aabrowser.utils.VoiceCommands.MediaSection
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
        assertParsed(Command.Next, "manda avanti")
        assertParsed(Command.Previous, "manda indietro")
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

    @Test
    fun radioPhrases() {
        val radio = { q: String -> Command.MediaRequest(MediaSection.RADIO, q, play = true) }
        assertParsed(radio("deejay"), "Metti Deejay alla radio")
        assertParsed(radio("rtl 102.5"), "ascolta RTL 102.5 alla radio")
        assertParsed(radio("subasio"), "sintonizza subasio")
        assertParsed(radio("deejay"), "metti la radio deejay")
        assertParsed(radio("bbc"), "play BBC on the radio")
        assertParsed(radio("kiss fm"), "tune to kiss fm")
        assertParsed(radio("los 40"), "pon los 40 en la radio")
        assertParsed(radio("nrj"), "mets nrj à la radio")
        assertParsed(radio("antenne bayern"), "spiele antenne bayern im radio")
        assertParsed(radio("comercial"), "toca comercial no rádio")
        assertParsed(radio("максимум"), "включи максимум на радио")
        assertParsed(radio("petőfi"), "játszd le petőfi a rádióban")
        assertParsed(radio("مكس"), "شغّل مكس على الراديو")
        assertParsed(radio("मिर्ची"), "रेडियो पर मिर्ची चलाओ")
        assertParsed(radio("新闻"), "在收音机上播放新闻")

        val find = { q: String -> Command.MediaRequest(MediaSection.RADIO, q, play = false) }
        assertParsed(find("deejay"), "cerca deejay alla radio")
        assertParsed(find("deejay"), "cerca la stazione deejay")
        assertParsed(find("jazz"), "search for jazz on the radio")
        assertParsed(find("jazz"), "busca jazz en la radio")
        // Con le altre parole di "cerca … su …": il nome del sito è la pagina
        assertParsed(find("jazz"), "cerca jazz su radio")
        assertParsed(find("jazz"), "cherche jazz sur la radio")
        assertParsed(find("deejay"), "cerca su radio deejay")
    }

    @Test
    fun tvPhrases() {
        val tv = { q: String -> Command.MediaRequest(MediaSection.TV, q, play = true) }
        assertParsed(tv("rai 1"), "Metti Rai 1 in TV")
        assertParsed(tv("rai 1"), "guarda rai 1 in televisione")
        assertParsed(tv("rai 1"), "guarda rai 1 su iptv")
        assertParsed(tv("rai 1"), "guarda rai 1 in tivù")
        assertParsed(tv("cinque"), "metti il canale cinque")
        assertParsed(tv("bbc one"), "watch BBC One on TV")
        assertParsed(tv("4"), "switch to channel 4")
        assertParsed(tv("la 1"), "pon la 1 en la tele")
        assertParsed(tv("tf1"), "mets TF1 à la télé")
        assertParsed(tv("zdf"), "schau ZDF im Fernsehen")
        assertParsed(tv("globo"), "coloca globo na tv")
        assertParsed(tv("первый"), "включи первый на тв")
        assertParsed(tv("m1"), "játszd le M1 a tévében")
        assertParsed(tv("الجزيرة"), "شغل الجزيرة على التلفزيون")
        assertParsed(tv("आज तक"), "टीवी पर आज तक चलाओ")
        assertParsed(tv("中央一台"), "在电视上播放中央一台")

        val find = { q: String -> Command.MediaRequest(MediaSection.TV, q, play = false) }
        assertParsed(find("sport"), "cerca sport in tv")
        assertParsed(find("rai"), "cerca il canale rai")
        assertParsed(find("news"), "search for news on tv")
        assertParsed(find("news"), "busca news en la tele")
        assertParsed(find("sport"), "cerca sport su iptv")
    }

    @Test
    fun mediaPhrases() {
        val media = { q: String -> Command.MediaRequest(MediaSection.MEDIA, q, play = true) }
        assertParsed(media("albachiara"), "Metti Albachiara su Media")
        assertParsed(media("albachiara"), "metti albachiara su car web media")
        assertParsed(media("vasco"), "riproduci vasco dalla mia musica")
        assertParsed(media("queen"), "play queen from my music")
        assertParsed(media("shakira"), "pon shakira de mi música")
        assertParsed(media("stromae"), "mets stromae de ma musique")
        assertParsed(media("rammstein"), "spiele rammstein aus meiner musik")
        assertParsed(media("anitta"), "toca anitta da minha música")
        assertParsed(media("кино"), "включи кино из моей музыки")
        assertParsed(media("queen"), "játszd le queen a zenéim közül")
        assertParsed(media("فيروز"), "شغل فيروز من موسيقاي")
        assertParsed(media("अरिजीत"), "मेरे गानों में से अरिजीत चलाओ")
        assertParsed(media("周杰伦"), "在我的音乐里播放周杰伦")

        val find = { q: String -> Command.MediaRequest(MediaSection.MEDIA, q, play = false) }
        assertParsed(find("vasco"), "cerca vasco su media")
        assertParsed(find("vasco"), "cerca vasco nella mia musica")
        assertParsed(find("queen"), "search for queen in my music")
    }

    @Test
    fun nextChannelAndStation() {
        assertParsed(Command.Next, "prossimo canale")
        assertParsed(Command.Next, "stazione successiva")
        assertParsed(Command.Next, "next channel")
        assertParsed(Command.Previous, "canale precedente")
        assertParsed(Command.Previous, "previous station")
        assertParsed(Command.Next, "siguiente canal")
        assertParsed(Command.Next, "chaîne suivante")
        assertParsed(Command.Next, "nächster sender")
        assertParsed(Command.Next, "próximo canal")
        assertParsed(Command.Next, "следующий канал")
        assertParsed(Command.Next, "következő csatorna")
        assertParsed(Command.Next, "القناة التالية")
        assertParsed(Command.Next, "अगला चैनल")
        assertParsed(Command.Next, "下一个频道")
    }

    @Test
    fun mediaSections() {
        assertEquals(MediaSection.RADIO, VoiceCommands.mediaSectionOf("https://player.local/radio"))
        assertEquals(MediaSection.RADIO, VoiceCommands.mediaSectionOf("https://player.local/radio/"))
        assertEquals(MediaSection.TV, VoiceCommands.mediaSectionOf("https://player.local/tv"))
        assertEquals(MediaSection.MEDIA, VoiceCommands.mediaSectionOf("https://player.local/"))
        assertNull(VoiceCommands.mediaSectionOf("https://www.youtube.com/tv"))
        assertNull(VoiceCommands.mediaSectionOf(null))
        MediaSection.entries.forEach {
            assertEquals(it, VoiceCommands.mediaSectionOf(VoiceCommands.mediaSectionUrl(it)))
        }
    }

    private fun plan(text: String, currentUrl: String? = null, playerOwned: Boolean = true) =
        VoiceCommands.plan(VoiceCommands.parse(text), currentUrl, favorites, webSearch, playerOwned = playerOwned)

    @Test
    fun openingThePages() {
        assertEquals(Plan.Media(MediaSection.RADIO), plan("apri la radio"))
        assertEquals(Plan.Media(MediaSection.TV), plan("apri la tv"))
        assertEquals(Plan.Media(MediaSection.TV), plan("open the tv"))
        assertEquals(Plan.Media(MediaSection.TV), plan("открой тв"))
        assertEquals(Plan.Media(MediaSection.MEDIA), plan("apri la mia musica"))
        // Solo il nome
        assertEquals(Plan.Media(MediaSection.RADIO), plan("radio"))
        assertEquals(Plan.Media(MediaSection.TV), plan("televisione"))
        // "Accendila": l'ultima stazione, l'ultimo canale, l'ultimo brano
        assertEquals(Plan.Media(MediaSection.RADIO, play = true), plan("metti la radio"))
        assertEquals(Plan.Media(MediaSection.TV, play = true), plan("guarda la tv"))
        assertEquals(Plan.Media(MediaSection.MEDIA, play = true), plan("play my music"))
    }

    @Test
    fun aFavoriteWithThatNameComesFirst() {
        val withRadio = favorites + FavoriteSite(id = "6", name = "Radio", url = "https://myradio.example/")
        val open = VoiceCommands.plan(Command.Open("radio"), null, withRadio, webSearch) as Plan.Load
        assertEquals("6", open.favorite?.id)
    }

    @Test
    fun theOpenPageDecides() {
        val radioPage = "https://player.local/radio"
        val tvPage = "https://player.local/tv"
        val mediaPage = "https://player.local/"
        assertEquals(Plan.Media(MediaSection.RADIO, "deejay", play = true), plan("metti deejay", radioPage))
        assertEquals(Plan.Media(MediaSection.TV, "rai 1", play = true), plan("metti rai 1", tvPage))
        assertEquals(Plan.Media(MediaSection.MEDIA, "albachiara", play = true), plan("metti albachiara", mediaPage))
        assertEquals(Plan.Media(MediaSection.TV, "rai 1", play = true), plan("guarda rai 1", tvPage))
        assertEquals(Plan.Media(MediaSection.RADIO, "jazz"), plan("cerca jazz", radioPage))
        // Solo il nome: è quello che si vuole sentire
        assertEquals(Plan.Media(MediaSection.RADIO, "deejay", play = true), plan("deejay", radioPage))
        // Un'altra pagina di CarWeb Media detta per nome vince su quella aperta
        assertEquals(Plan.Media(MediaSection.TV, play = true), plan("metti la tv", radioPage))
        assertEquals(Plan.Media(MediaSection.TV, "rai 1", play = true), plan("metti rai 1 in tv", radioPage))
        // YouTube detto per nome resta YouTube
        assertTrue(plan("metti queen su youtube", radioPage) is Plan.Load)
    }

    @Test
    fun playerKeysWithoutAnOwnerGoToTheOpenPage() {
        val radioPage = "https://player.local/radio"
        assertEquals(Plan.Player(VoiceCommands.PlayerAction.PLAY), plan("riprendi", radioPage))
        assertEquals(
            Plan.Media(MediaSection.RADIO, action = VoiceCommands.PlayerAction.PLAY),
            plan("riprendi", radioPage, playerOwned = false)
        )
        assertEquals(
            Plan.Media(MediaSection.RADIO, action = VoiceCommands.PlayerAction.NEXT),
            plan("prossimo", radioPage, playerOwned = false)
        )
        assertEquals(Plan.Player(VoiceCommands.PlayerAction.NEXT), plan("prossimo", "https://example.com/", playerOwned = false))
    }

    @Test
    fun phrasesThatSoundLikeAPage() {
        assertEquals(
            Plan.Media(MediaSection.RADIO, "radio deejay", play = true, fallback = Command.YouTubePlay("radio deejay")),
            plan("metti radio deejay")
        )
        assertEquals(MediaSection.RADIO, (plan("metti virgin radio") as Plan.Media).section)
        assertEquals(MediaSection.RADIO, (plan("metti rai radio 1") as Plan.Media).section)
        assertEquals(MediaSection.TV, (plan("metti canale 5") as Plan.Media).section)
        assertEquals(MediaSection.TV, (plan("guarda rai 1") as Plan.Media).section)
        assertEquals(Command.YouTubePlay("rai 1"), (plan("guarda rai 1") as Plan.Media).fallback)
        // Solo il nome, senza verbo
        val station = plan("radio deejay") as Plan.Media
        assertEquals(MediaSection.RADIO, station.section)
        assertEquals(Command.WebSearch("radio deejay"), station.fallback)
        assertEquals(MediaSection.RADIO, (plan("apri radio deejay") as Plan.Media).section)
        // Le canzoni restano canzoni
        assertTrue(plan("metti video killed the radio star") is Plan.Load)
        assertTrue(plan("metti albachiara") is Plan.Load)
        // Con YouTube aperto "metti …" resta lì
        assertTrue(plan("metti radio ga ga", "https://music.youtube.com/watch?v=x") is Plan.Load)
    }

    @Test
    fun hintExamples() {
        // Gli esempi del suggerimento del microfono, in tutte le lingue (…= il testo libero)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("metti rtl alla radio") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("guarda rai 1 in tv") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("play bbc on the radio") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("watch cnn on tv") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("pon rock en la radio") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("mira rock en la tele") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("mets rock à la radio") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("regarde rock à la télé") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("spiele rock im radio") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("schau rock im fernsehen") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("toca rock no rádio") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("coloca rock na tv") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("включи рок на радио") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("покажи рок на тв") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("játszd le rock a rádióban") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("mutasd rock a tévében") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("شغل روك على الراديو") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("شغل روك على التلفزيون") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("रेडियो पर रॉक चलाओ") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("टीवी पर रॉक लगाओ") as Command.MediaRequest).section)
        assertEquals(MediaSection.RADIO, (VoiceCommands.parse("在收音机上播放摇滚") as Command.MediaRequest).section)
        assertEquals(MediaSection.TV, (VoiceCommands.parse("在电视上看摇滚") as Command.MediaRequest).section)
    }

    private val favorites = listOf(
        FavoriteSite(id = "1", name = "YouTube", url = "https://m.youtube.com/"),
        FavoriteSite(id = "2", name = "WhatsApp", url = "https://web.whatsapp.com/"),
        FavoriteSite(id = "3", name = "CarWeb Media", url = "https://player.local/"),
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

    // Link condivisi dal telefono con "CarWeb in auto": un video di YouTube parte da solo, il resto si apre e basta
    @Test
    fun sharedLinks() {
        val video = VoiceCommands.plan(Command.OpenLink("https://m.youtube.com/watch?v=abc"), null, favorites, webSearch)
        assertEquals(Plan.Load("https://m.youtube.com/watch?v=abc", "youtube.com", LoadKind.LINK, autoplay = true), video)
        val short = VoiceCommands.plan(Command.OpenLink("https://m.youtube.com/shorts/xyz"), null, favorites, webSearch)
        assertTrue((short as Plan.Load).autoplay)
        val channel = VoiceCommands.plan(Command.OpenLink("https://m.youtube.com/@canale"), null, favorites, webSearch)
        assertFalse((channel as Plan.Load).autoplay)
        val page = VoiceCommands.plan(Command.OpenLink("https://www.repubblica.it/a.html"), null, favorites, webSearch)
        assertEquals(Plan.Load("https://www.repubblica.it/a.html", "repubblica.it", LoadKind.LINK), page)
        // Foto o video dalla galleria: si vede il nome del file e la pagina non è quella di Media
        val photo = VoiceCommands.plan(Command.OpenLink("https://player.local/shared/id1", "foto.jpg"), null, favorites, webSearch)
        assertEquals(Plan.Load("https://player.local/shared/id1", "foto.jpg", LoadKind.LINK), photo)
        assertEquals(null, VoiceCommands.mediaSectionOf("https://player.local/shared/id1"))
    }

    @Test
    fun sharedLinksGoToTheMainUnlessTheWideIsInFront() {
        val main = VoiceCommandRouter.Target.MAIN
        val wide = VoiceCommandRouter.Target.WIDE
        fun target(mainOpen: Boolean, mainInFront: Boolean, wideInFront: Boolean) =
            VoiceCommandRouter.shareTarget(mainOpen, mainInFront, wideInFront)
        // Tutte e due sullo schermo (la Wide nello spazio del navigatore): la Main
        assertEquals(main, target(mainOpen = true, mainInFront = true, wideInFront = true))
        assertEquals(main, target(mainOpen = true, mainInFront = true, wideInFront = false))
        assertEquals(wide, target(mainOpen = true, mainInFront = false, wideInFront = true))
        assertEquals(wide, target(mainOpen = false, mainInFront = false, wideInFront = true))
        // Davanti c'è altro (es. il menu delle app): la Main torna davanti
        assertEquals(main, target(mainOpen = true, mainInFront = false, wideInFront = false))
        // La Main non è aperta e la Wide è dietro: deve aprirla la Wide
        assertNull(target(mainOpen = false, mainInFront = false, wideInFront = false))
    }
}
