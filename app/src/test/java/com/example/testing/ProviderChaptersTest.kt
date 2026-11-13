package com.example.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderChaptersTest {
    private val manga = MangaItem("bleach", "Bleach", 0, url = "https://mangakatana.com/manga/bleach.47", category = "Manga")
    private val arc = manga.copy(id = "bleachtybw", url = "${manga.url}/c480")
    private val comic = MangaItem("example", "Comic", 0, url = "https://readcomicsonline.ru/comic/example", category = "Comics")

    @Test
    fun selectsArcChaptersAndPreservesOriginalBleach() {
        val html = """
            <div class="value status">Completed</div>
            <table>
              <tr><td class="chapter"><a href="/manga/bleach.47/c686">Chapter 686</a></td></tr>
              <tr><td class="chapter"><a href="/manga/bleach.47/c480">Chapter 480</a></td></tr>
              <tr><td class="chapter"><a href="/manga/bleach.47/c1">Chapter 1</a></td></tr>
              <tr><td class="chapter"><a href="/manga/another.99/c481">Other series</a></td></tr>
              <tr><td class="chapter"><a href="https://mangakatana.com.example/manga/bleach.47/c482">Lookalike</a></td></tr>
            </table>
        """.trimIndent()
        assertEquals(listOf("Chapter 1", "Chapter 480", "Chapter 686"), ProviderChapters.parseList(manga, html).chapters.map { it.title })
        val listing = ProviderChapters.parseList(arc, html)
        assertEquals(listOf("Chapter 480", "Chapter 686"), listing.chapters.map { it.title })
        assertTrue(listing.complete)
        assertNull(ProviderChapters.chapterUrl(arc, "${manga.url}/c1"))
    }

    @Test
    fun extractsOnlyTheMangaPageArray() {
        val html = """
            <script>
              var ad = ['https://ad.example/banner.png'];
              var pages = ['https://cdn.example/001.webp', '//cdn.example/002.jpg'];
              images.attr('data-src', pages[i]);
            </script>
        """.trimIndent()
        assertEquals(listOf("https://cdn.example/001.webp", "https://cdn.example/002.jpg"),
            ProviderChapters.parsePages(manga, "${manga.url}/c1", html))
        assertTrue(ProviderChapters.parsePages(manga, "${manga.url}/c1",
            html.replace("https://cdn.example/001.webp", "http://127.0.0.1/001.webp")).isEmpty())
    }

    @Test
    fun extractsComicIssuesAndReaderImages() {
        val listing = ProviderChapters.parseList(comic, """
            <div class="overflow-hidden border-ink-600">
              <a href="/comic/example/2"><span class="text-brand-400">Issue 2</span></a>
              <a href="/comic/example/1"><span class="text-brand-400">Issue 1</span></a>
            </div>
        """.trimIndent())
        assertEquals(listOf("Issue 1", "Issue 2"), listing.chapters.map { it.title })
        assertFalse(listing.complete)
        val html = """
            <img src="https://ad.example/banner.png">
            <div id="reader-all"><img data-lazy-src="https://cdn.example/1.png" src="/placeholder.png"><img src="https://cdn.example/2.jpg"></div>
        """.trimIndent()
        assertEquals(listOf("https://cdn.example/1.png", "https://cdn.example/2.jpg"),
            ProviderChapters.parsePages(comic, "${comic.url}/1", html))
        assertTrue(ProviderChapters.parsePages(comic, "${comic.url}/1", "<h1>Provider unavailable</h1>").isEmpty())
    }

    @Test
    fun findsComicIssuesOutsideKnownContainersAndKeepsSpecialIssues() {
        val listing = ProviderChapters.parseList(comic, """
            <ul class="issue-list">
                <li><a href="/comic/example/annual-1">Annual #1</a></li>
                <li><a href="/comic/example/issue-2">Chapter 2</a></li>
                <li><a href="/comic/example/issue-1"></a></li>
                <li><a href="/comic/example/about">About</a></li>
                <li><a href="/comic/another/1">Another series</a></li>
            </ul>
        """.trimIndent())
        assertEquals(listOf("Issue 1", "Issue 2", "Annual #1"), listing.chapters.map { it.title })
        assertNull(ProviderChapters.chapterUrl(comic, "https://readcomicsonline.ru.example/comic/example/issue-1"))
    }

    @Test
    fun prefersIssueLabelsOverReadShortcutsAndRepairsCachedShortcuts() {
        val listing = ProviderChapters.parseList(comic, """
            <a href="/comic/example/1">Read First</a>
            <a href="/comic/example/3">Read Latest</a>
            <a href="/comic/example/1/"><span class="text-brand-400">#1</span></a>
            <a href="/comic/example/2">#2</a>
        """.trimIndent())
        assertEquals(listOf("#1", "#2", "Issue 3"), listing.chapters.map { it.title })
        val cached = ProviderChapters.Chapter("Read First", "${comic.url}/1")
        assertEquals("Issue 1", ProviderChapters.comicIssues(listOf(cached)).single().title)
    }

    @Test
    fun recognizesAnIssueFromItsPageByPageUrl() {
        val issue = comic.url + "/132"
        assertEquals(issue, ProviderChapters.comicIssueUrl(comic, "$issue/7/?mode=single#reader")?.toString())
        assertEquals(issue, ProviderChapters.comicIssueUrl(comic, "$issue/")?.toString())
        assertNull(ProviderChapters.chapterUrl(comic, "$issue/7"))
        assertNull(ProviderChapters.comicIssueUrl(comic, "$issue/about"))
        assertNull(ProviderChapters.comicIssueUrl(comic, "https://readcomicsonline.ru/comic/other/132/7"))
        assertNull(ProviderChapters.comicIssueUrl(comic, "https://readcomicsonline.ru.example/comic/example/132/7"))
    }
}
