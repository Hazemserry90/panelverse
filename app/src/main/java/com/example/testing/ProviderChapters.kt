package com.example.testing

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

internal object ProviderChapters {
    data class Chapter(val title: String, val url: String)
    data class Listing(val chapters: List<Chapter>, val complete: Boolean)
    data class PageImage(val bytes: ByteArray, val mime: String)

    private val mangaNumber = Regex("""^c(\d+(?:\.\d+)?)(?:[-_][a-z0-9._-]+)?$""")
    private val issueNumber = Regex("""^(?:issue[-_])?(\d+(?:\.\d+)?)(?:[-_][a-z0-9._-]+)?$""", RegexOption.IGNORE_CASE)
    private val specialIssue = Regex("""^(?:annual|special|giant|preview|fcbd|one[-_]shot)(?:[-_][a-z0-9._-]+|\d+)?$""", RegexOption.IGNORE_CASE)
    private val mangaImageArray = Regex("""\b(?:var|let|const)\s+([\w$]+)\s*=\s*\[([^\[\]]*)]""")
    private val mangaImageReference = Regex("""['"]data-src['"]\s*,\s*([\w$]+)""")
    private val quotedValue = Regex("""['"]([^'"\r\n]+)['"]""")
    private val comicImageAttributes = listOf("data-background-image", "data-cfsrc", "data-lazy-src", "data-src", "data-original", "src")
    private val network = OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                Dns.SYSTEM.lookup(hostname).filter(::publicAddress).ifEmpty { throw UnknownHostException(hostname) }
        })
        .followRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    fun readList(series: MangaItem): Listing {
        val root = seriesRoot(series)
        return parseList(series, fetch(root, "text/html", 3 * 1024 * 1024).bytes.decodeToString())
    }

    fun parseList(series: MangaItem, html: String): Listing {
        val root = seriesRoot(series)
        val manga = root.pathSegments.first() == "manga"
        val doc = Jsoup.parse(html, root.toString())
        val links = if (manga) doc.select("tr:has(.chapter) a[href], .chapter a[href]") else doc.select("a[href]")
        val chapters = links.mapNotNull { link ->
            val url = chapterUrl(series, root.resolve(link.attr("href"))?.toString()) ?: return@mapNotNull null
            val label = link.selectFirst(".text-brand-400")?.text() ?: link.text()
            val segment = url.pathSegments.last()
            val number = entryNumber(segment, manga)
            val fallback = if (manga) "Chapter ${number ?: segment}" else "Issue ${number ?: segment}"
            val title = if (manga) label else label.replace(Regex("""^Chapter\b""", RegexOption.IGNORE_CASE), "Issue")
            Chapter(title.take(140).ifBlank { fallback }, url.toString())
        }.distinctBy { it.url }.sortedBy {
            entryNumber(it.url.substringAfterLast('/'), manga)?.toDoubleOrNull()
                ?: Double.MAX_VALUE
        }
        val status = doc.selectFirst(if (manga) ".value.status" else "div.flex.flex-wrap.gap-2 span.rounded-full")?.text()
        val complete = if (series.id == "bleachtybw") chapters.lastOrNull()?.url?.endsWith("/c686") == true
            else status.equals("Completed", true) || status.equals("Complete", true)
        return Listing(chapters, complete)
    }

    fun chapterUrl(series: MangaItem, candidate: String?): HttpUrl? {
        val root = seriesRoot(series)
        val url = candidate?.let(::safeUrl) ?: return null
        val origin = ReaderLinks.inspect(candidate) as? ReaderLinks.Approved ?: return null
        val rootOrigin = ReaderLinks.inspect(root.toString()) as? ReaderLinks.Approved ?: return null
        val segments = url.pathSegments.dropLastWhile { it.isEmpty() }
        if (origin.provider != rootOrigin.provider ||
            segments.size != 3 || segments.take(2) != root.pathSegments) return null
        val segment = segments.last()
        val manga = root.pathSegments.first() == "manga"
        val number = entryNumber(segment, manga)?.toDoubleOrNull()
        if (number == null && (manga || segment.length > 80 || !specialIssue.matches(segment))) return null
        if (series.id == "bleachtybw" && (number == null || number !in 480.0..686.0)) return null
        return url.newBuilder().host(root.host).encodedPath("/" + url.encodedPathSegments.take(3).joinToString("/"))
            .query(null).fragment(null).build()
    }

    fun comicIssueUrl(series: MangaItem, candidate: String?): HttpUrl? {
        val origin = ReaderLinks.inspect(candidate) as? ReaderLinks.Approved ?: return null
        if (origin.provider != "readcomicsonline.ru") return null
        val url = candidate?.let(::safeUrl) ?: return null
        chapterUrl(series, candidate)?.let { return it }
        val segments = url.encodedPathSegments.dropLastWhile { it.isEmpty() }
        if (segments.size != 4 || !segments.last().matches(Regex("[1-9][0-9]{0,3}"))) return null
        return chapterUrl(series, url.newBuilder().encodedPath("/" + segments.take(3).joinToString("/")).build().toString())
    }

    private fun entryNumber(segment: String, manga: Boolean): String? =
        (if (manga) mangaNumber else issueNumber).matchEntire(segment)?.groupValues?.get(1)

    fun pages(series: MangaItem, chapter: String): List<String> {
        val url = chapterUrl(series, chapter) ?: throw IOException("Unsupported chapter")
        val html = fetch(url, "text/html", 3 * 1024 * 1024).bytes.decodeToString()
        return parsePages(series, url.toString(), html)
    }

    fun parsePages(series: MangaItem, chapter: String, html: String): List<String> {
        val url = chapterUrl(series, chapter) ?: return emptyList()
        val doc = Jsoup.parse(html, url.toString())
        val candidates = if (url.pathSegments.first() == "manga") {
            doc.select("script").asSequence().map { it.data() }.mapNotNull { script ->
                val names = mangaImageReference.findAll(script).map { it.groupValues[1] }.toSet()
                val values = mangaImageArray.findAll(script).firstOrNull { it.groupValues[1] in names }
                    ?.groupValues?.get(2) ?: return@mapNotNull null
                quotedValue.findAll(values).map { it.groupValues[1].replace("\\/", "/") }.take(1001).toList()
            }.firstOrNull().orEmpty()
        } else {
            doc.select("#reader-all img, #all img").map { image ->
                comicImageAttributes.firstNotNullOfOrNull { key -> image.attr(key).takeIf(String::isNotBlank) }.orEmpty()
            }
        }
        if (candidates.size !in 1..1000) return emptyList()
        return candidates.map { raw ->
            if (raw.isBlank() || raw.length > 4096) return emptyList()
            val image = url.resolve(raw)?.toString()?.let(::safeUrl) ?: return emptyList()
            image.toString()
        }
    }

    fun image(url: String, chapter: String): PageImage {
        val source = safeUrl(url) ?: throw IOException("Unsupported page image URL")
        val result = fetch(source, "image/jpeg,image/png,image/webp,application/octet-stream",
            OfflineArchive.MAX_PAGE_BYTES.toInt(), chapter)
        return PageImage(result.bytes, result.mime)
    }

    fun webImageUrl(value: String): String? = safeUrl(value)?.toString()

    private fun seriesRoot(series: MangaItem): HttpUrl {
        val url = series.url.toHttpUrlOrNull() ?: throw IOException("Invalid series")
        if (ReaderLinks.inspect(url.toString()) !is ReaderLinks.Approved || url.pathSegments.size < 2) {
            throw IOException("Unsupported series")
        }
        return url.newBuilder().encodedPath("/" + url.encodedPathSegments.take(2).joinToString("/"))
            .query(null).fragment(null).build()
    }

    private fun safeUrl(value: String): HttpUrl? {
        if (value.length > 4096 || value.any { it == '\\' || it.isISOControl() || it.isWhitespace() }) return null
        return value.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.port == 443 && it.username.isEmpty() && it.password.isEmpty() &&
                '.' in it.host && it.host.any(Char::isLetter) && !it.host.endsWith(".local") &&
                !ReaderAdHosts.blocks(it.host)
        }
    }

    private fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress ||
            address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val first = address.address[0].toInt() and 255
        val second = address.address[1].toInt() and 255
        return when (address) {
            is Inet4Address -> first in 1..223 && !(first == 100 && second in 64..127) &&
                !(first == 198 && second in 18..19)
            is Inet6Address -> first in 0x20..0x3f && !(first == 0x20 && second in 0..2)
            else -> false
        }
    }

    private fun fetch(url: HttpUrl, accept: String, limit: Int, referer: String? = null): PageImage {
        val request = Request.Builder().url(url).header("Accept", accept).apply {
            referer?.let { header("Referer", it) }
        }.build()
        network.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Provider returned HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty provider response")
            if (body.contentLength() > limit) throw IOException("Provider response is too large")
            val output = ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Request interrupted")
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size() > limit - count) throw IOException("Provider response is too large")
                    output.write(buffer, 0, count)
                }
            }
            return PageImage(output.toByteArray(), body.contentType()?.let { "${it.type}/${it.subtype}" }.orEmpty())
        }
    }
}
