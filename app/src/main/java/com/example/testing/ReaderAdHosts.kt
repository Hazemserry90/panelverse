package com.example.testing

import java.util.Locale

internal object ReaderAdHosts {
    private val hosts = setOf(
        "2mdn.net",
        "adnxs.com",
        "adsrvr.org",
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "pubmatic.com",
        "adsterra.com",
        "adskeeper.com",
        "inspiredot.net",
        "weddinginseychelles.com",
        "chemicalandequipmentlimited.com",
        "mgid.com",
        "clickadu.com",
        "exoclick.com",
        "hilltopads.net",
        "popads.net",
        "popcash.net",
        "propellerads.com",
        "trafficjunky.net"
    )
    private val imageExtensions = setOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    private val recaptchaHosts = setOf("www.google.com", "www.recaptcha.net", "www.gstatic.com")
    private val externalScriptOrFrame = setOf("script", "iframe", "frame", "object", "embed")

    fun blocks(host: String?): Boolean {
        val name = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return hosts.any { name == it || name.endsWith("." + it) }
    }

    fun blocksMangaKatanaResource(host: String?, path: String?, headers: Map<String, String>): Boolean {
        val name = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return true
        if (blocks(name)) return true
        if (name == "mangakatana.com" || name == "www.mangakatana.com") return false

        // Keep real verification services available without opening unrelated frames.
        if (isVerificationResource(name, path)) return false

        // The provider switches image servers. Match image requests instead of assuming a fixed CDN.
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        if (destination != null) return destination != "image"
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        if (accept?.trimStart()?.startsWith("image/", ignoreCase = true) == true) return false
        val extension = path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return extension !in imageExtensions
    }

    fun blocksComicResource(host: String?, path: String?, headers: Map<String, String>): Boolean {
        val name = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return true
        if (blocks(name)) return true
        if (name == "readcomicsonline.ru" || name.endsWith(".readcomicsonline.ru") ||
            isVerificationResource(name, path) || isReaderLibrary(name, path)) return false
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        if (destination != null) return destination in externalScriptOrFrame
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        val extension = path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return accept?.trimStart()?.startsWith("text/html", ignoreCase = true) == true ||
            extension == "js" || extension == "mjs"
    }

    private fun isVerificationResource(host: String, path: String?): Boolean =
        host == "challenges.cloudflare.com" ||
            (host in recaptchaHosts && path?.startsWith("/recaptcha/") == true) ||
            host == "hcaptcha.com" || host.endsWith(".hcaptcha.com")

    private fun isReaderLibrary(host: String, path: String?): Boolean = when (host) {
        "code.jquery.com" -> path?.startsWith("/jquery-") == true
        "ajax.googleapis.com" -> path?.startsWith("/ajax/libs/jquery/") == true
        "cdnjs.cloudflare.com" -> path?.startsWith("/ajax/libs/jquery/") == true ||
            path?.startsWith("/ajax/libs/bootstrap/") == true
        else -> false
    }
}
