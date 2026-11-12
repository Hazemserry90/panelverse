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
}
