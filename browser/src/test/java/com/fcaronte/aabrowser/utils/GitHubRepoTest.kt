package com.fcaronte.aabrowser.utils

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Il repository ha due nomi (AABrowser, CarWeb) per qualche giorno: aggiornamenti e link devono andare con tutti e due. */
class GitHubRepoTest {

    @Before
    @After
    fun forget() = GitHubRepo.forgetLive()

    private fun official(url: String) = GitHubRepo.isOfficialReleaseUrl(url)

    @Test
    fun apksOfBothNamesAreOfficial() {
        assertTrue(official("https://github.com/fcaronte/CarWeb/releases/download/2.8/CarWeb-v2.8.apk"))
        // Prima del cambio di nome, e dopo per le release che lo hanno ancora
        assertTrue(official("https://github.com/fcaronte/AABrowser/releases/download/2.8/CarWeb-v2.8.apk"))
        assertTrue(official("https://github.com/fcaronte/AABrowser/releases/download/2.3/AABrowser-v2.3.apk"))
        // Come li scrive GitHub nelle risposte
        assertTrue(official("https://github.com/fcaronte/CarWeb/releases/download/2.8/CarWeb-v2.8.apk?foo=1"))
    }

    @Test
    fun caseOfOwnerAndNameDoesNotMatter() {
        // Per GitHub utente e repository sono gli stessi comunque si scrivano
        assertTrue(official("https://github.com/FCaronte/carweb/releases/download/2.8/CarWeb-v2.8.apk"))
        assertTrue(official("https://GitHub.com/fcaronte/aabrowser/releases/download/2.8/CarWeb-v2.8.apk"))
    }

    @Test
    fun otherRepositoriesAreNot() {
        // I progetti da cui CarWeb discende hanno lo stesso nome del vecchio repository
        assertFalse(official("https://github.com/kododake/AABrowser/releases/download/1.0/AABrowser.apk"))
        assertFalse(official("https://github.com/slashmax/AABrowser/releases/download/1.0/AABrowser.apk"))
        assertFalse(official("https://github.com/fcaronte/KingInstaller/releases/download/1.0/KingInstaller.apk"))
        assertFalse(official("https://github.com/fcaronte/CarWebX/releases/download/2.8/CarWeb-v2.8.apk"))
        assertFalse(official("https://github.com/fcaronte/CarWeb-old/releases/download/2.8/CarWeb-v2.8.apk"))
        assertFalse(official("https://github.com/fcaronte2/CarWeb/releases/download/2.8/CarWeb-v2.8.apk"))
    }

    @Test
    fun onlyAReleaseDownloadIs() {
        assertFalse(official("https://github.com/fcaronte/CarWeb/releases"))
        assertFalse(official("https://github.com/fcaronte/CarWeb/releases/download"))
        assertFalse(official("https://github.com/fcaronte/CarWeb/releases/latest/CarWeb-v2.8.apk"))
        assertFalse(official("https://github.com/fcaronte/CarWeb/archive/refs/heads/main.zip"))
        assertFalse(official("https://github.com/fcaronte/CarWeb"))
        assertFalse(official("https://github.com"))
    }

    @Test
    fun onlyPlainHttpsOnGitHub() {
        val path = "/fcaronte/CarWeb/releases/download/2.8/CarWeb-v2.8.apk"
        assertTrue(official("https://github.com$path"))
        assertFalse(official("http://github.com$path"))
        assertFalse(official("https://github.com.evil.example$path"))
        assertFalse(official("https://evil.example$path"))
        assertFalse(official("https://api.github.com$path"))
        assertFalse(official("https://github.com@evil.example$path"))
        assertFalse(official("https://user:password@github.com$path"))
        assertFalse(official("https://github.com:8443$path"))
        assertFalse(official("https://evil.example/#@github.com$path"))
        assertFalse(official("file://$path"))
        assertFalse(official("javascript:alert(1)"))
        assertFalse(official("https://github.com\\@evil.example$path"))
        assertFalse(official("not an address"))
        assertFalse(official(""))
    }

    @Test
    fun dotSegmentsAndEncodedNamesAreRefused() {
        assertFalse(official("https://github.com/fcaronte/CarWeb/releases/download/../../../evil/repo/raw/main/a.apk"))
        assertFalse(official("https://github.com/fcaronte/CarWeb/releases/download/%2e%2e/%2e%2e/evil/a.apk"))
        assertFalse(official("https://github.com/fcaronte/CarWeb/releases/download/./2.8/a.apk"))
        // Un "/" scritto %2F non separa utente e repository
        assertFalse(official("https://github.com/fcaronte%2FCarWeb/releases/download/2.8/a.apk"))
        assertFalse(official("https://github.com/fcaronte//CarWeb/releases/download/2.8/a.apk"))
    }

    @Test
    fun updatesAreLookedForOnTheNewNameFirst() {
        assertEquals(listOf("CarWeb", "AABrowser"), GitHubRepo.NAMES)
        assertEquals("https://api.github.com/repos/fcaronte/CarWeb/releases/latest", GitHubRepo.latestReleaseApiUrl("CarWeb"))
        assertEquals("https://api.github.com/repos/fcaronte/AABrowser/releases/latest", GitHubRepo.latestReleaseApiUrl("AABrowser"))
        assertEquals("https://github.com/fcaronte/CarWeb/releases", GitHubRepo.releasesPageUrl("CarWeb"))
        assertEquals("https://github.com/fcaronte/AABrowser/releases", GitHubRepo.releasesPageUrl("AABrowser"))
    }

    @Test
    fun linksUseTheOldNameUntilTheNewOneAnswers() {
        // Prima del primo controllo: il nome vecchio, che funziona prima e dopo il cambio di nome
        assertEquals("https://github.com/fcaronte/AABrowser", GitHubRepo.pageUrl())
        assertEquals("https://github.com/fcaronte/AABrowser/blob/main/README.md", GitHubRepo.readmeUrl())

        // Il repository è stato rinominato: risponde il nome nuovo
        GitHubRepo.markLive("CarWeb")
        assertEquals("https://github.com/fcaronte/CarWeb", GitHubRepo.pageUrl())
        assertEquals("https://github.com/fcaronte/CarWeb/blob/main/README.md", GitHubRepo.readmeUrl())

        // Un nome che non è del progetto non cambia niente
        GitHubRepo.markLive("Altro")
        assertEquals("https://github.com/fcaronte/CarWeb", GitHubRepo.pageUrl())

        GitHubRepo.markLive("AABrowser")
        assertEquals("https://github.com/fcaronte/AABrowser", GitHubRepo.pageUrl())
    }

    @Test
    fun servicesGetTheNameVersionAndWhereToFindTheApp() {
        val old = GitHubRepo.userAgent()
        assertTrue(old, old.startsWith("CarWeb/"))
        assertTrue(old, old.endsWith(" (https://github.com/fcaronte/AABrowser)"))
        GitHubRepo.markLive("CarWeb")
        assertTrue(GitHubRepo.userAgent().endsWith(" (https://github.com/fcaronte/CarWeb)"))
    }
}
