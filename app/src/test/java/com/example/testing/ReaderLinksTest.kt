package com.example.testing

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderLinksTest {
    @Test
    fun acceptsSupportedProviderAndNormalEncoding() {
        assertEquals(
            ReaderLinks.Approved("mangakatana.com", "www.mangakatana.com"),
            ReaderLinks.inspect("HTTPS://WWW.MANGAKATANA.COM:443/manga/a%20title/chapter-1?x=a%2Fb#page-2")
        )
        assertEquals(
            ReaderLinks.Approved("readcomicsonline.ru", "readcomicsonline.ru"),
            ReaderLinks.inspect("https://readcomicsonline.ru/comic/example/1")
        )
    }

    @Test
    fun rejectsDeceptiveOrAmbiguousUrls() {
        listOf(
            null, "", " http://mangakatana.com", "http://mangakatana.com",
            "https://mangakatana.com@other.example/chapter",
            "https://reader@www.mangakatana.com/chapter",
            "https://mangakatana.com:444/chapter", "https://mangakatana.com:/chapter",
            "https://mangakatana.com:0443/chapter", "https://mangakatana%2ecom/chapter",
            "https://mangakatana.com\\@other.example/chapter",
            "https://mangakatana.com/chapter%0d%0a",
            "https://mangakatana.com/chapter%5cnext",
            "https://mangakatana.com/chapter?name=%E2%80%AE",
            "https://mangakatana.com/chapter\u202E",
            "https://mangakatana.com/" + "x".repeat(4100)
        ).forEach { url ->
            assertEquals(url, ReaderLinks.Blocked.UNSAFE_FORMAT, ReaderLinks.inspect(url))
        }
    }

    @Test
    fun rejectsLookalikeAndUnlistedHosts() {
        listOf(
            "https://mangakatana.com.other.example/chapter",
            "https://notmangakatana.com/chapter",
            "https://mangakatana.com./chapter",
            "https://static.mangakatana.com/chapter",
            "https://xn--example-ova.example/chapter"
        ).forEach { url ->
            assertEquals(url, ReaderLinks.Blocked.UNSUPPORTED_HOST, ReaderLinks.inspect(url))
        }
    }
}
