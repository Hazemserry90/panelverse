package com.example.testing

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderAdHostsTest {
    @Test
    fun allowsProviderAssetsAndImagesFromMultipleServers() {
        assertFalse(ReaderAdHosts.blocksMangaKatanaResource("www.mangakatana.com", "/reader.js", emptyMap()))
        assertFalse(ReaderAdHosts.blocksMangaKatanaResource("image-server.example", "/p/1", mapOf("Sec-Fetch-Dest" to "image")))
        assertFalse(ReaderAdHosts.blocksMangaKatanaResource("backup.example", "/001.webp", emptyMap()))
        assertFalse(ReaderAdHosts.blocksMangaKatanaResource("backup.example", "/page", mapOf("accept" to "image/webp,*/*;q=0.8")))
    }

    @Test
    fun blocksUnapprovedScriptsAndFramesRegardlessOfFileSuffix() {
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("rotating-ad.example", "/widget.js", emptyMap()))
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("rotating-ad.example", "/frame", emptyMap()))
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("rotating-ad.example", "/frame.png", mapOf("sec-fetch-dest" to "iframe")))
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("mangakatana.com.example", "/script.js", emptyMap()))
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("img.adskeeper.com", "/ad.png", mapOf("Sec-Fetch-Dest" to "image")))
    }

    @Test
    fun allowsRecognizedVerificationEndpoints() {
        assertFalse(ReaderAdHosts.blocksMangaKatanaResource("challenges.cloudflare.com", "/challenge", emptyMap()))
        assertFalse(ReaderAdHosts.blocksMangaKatanaResource("www.google.com", "/recaptcha/api2/anchor", emptyMap()))
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("www.google.com", "/other", emptyMap()))
        assertTrue(ReaderAdHosts.blocksMangaKatanaResource("challenges.cloudflare.com.example", "/challenge", emptyMap()))
    }

    @Test
    fun comicsBlockExternalScriptsAndFramesWhileKeepingPagesAndRequiredAssets() {
        val script = mapOf("Sec-Fetch-Dest" to "script")
        val iframe = mapOf("Sec-Fetch-Dest" to "iframe")
        val image = mapOf("Sec-Fetch-Dest" to "image")
        assertTrue(ReaderAdHosts.blocksComicResource("rotating-ad.example", "/widget", script))
        assertTrue(ReaderAdHosts.blocksComicResource("rotating-ad.example", "/frame", iframe))
        assertTrue(ReaderAdHosts.blocksComicResource("rotating-ad.example", "/widget.mjs", emptyMap()))
        assertTrue(ReaderAdHosts.blocksComicResource("readcomicsonline.ru.example", "/widget.js", script))
        assertTrue(ReaderAdHosts.blocksComicResource("img.inspiredot.net", "/ad.jpg", image))
        assertFalse(ReaderAdHosts.blocksComicResource("www.readcomicsonline.ru", "/reader.js", script))
        assertFalse(ReaderAdHosts.blocksComicResource("page-server.example", "/page/1", image))
        assertFalse(ReaderAdHosts.blocksComicResource("challenges.cloudflare.com", "/challenge", iframe))
        assertFalse(ReaderAdHosts.blocksComicResource("code.jquery.com", "/jquery-3.7.1.min.js", script))
        assertTrue(ReaderAdHosts.blocksComicResource("code.jquery.com.example", "/jquery-3.7.1.min.js", script))
    }
}
