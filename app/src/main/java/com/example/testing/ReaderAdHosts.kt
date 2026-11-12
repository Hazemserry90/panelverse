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

    fun blocks(host: String?): Boolean {
        val name = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return hosts.any { name == it || name.endsWith("." + it) }
    }

    fun blocksMangaKatanaResource(host: String?, path: String?, headers: Map<String, String>): Boolean {
        val name = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return true
        if (blocks(name)) return true
        if (name == "mangakatana.com" || name == "www.mangakatana.com") return false

        // Keep real verification services available without opening unrelated frames.
        if (name == "challenges.cloudflare.com" ||
            (name in recaptchaHosts && path?.startsWith("/recaptcha/") == true) ||
            name == "hcaptcha.com" || name.endsWith(".hcaptcha.com")) return false

        // The provider switches image servers. Match image requests instead of assuming a fixed CDN.
        val destination = headers.entries.firstOrNull { it.key.equals("Sec-Fetch-Dest", true) }?.value
        if (destination != null) return destination != "image"
        val accept = headers.entries.firstOrNull { it.key.equals("Accept", true) }?.value
        if (accept?.trimStart()?.startsWith("image/", ignoreCase = true) == true) return false
        val extension = path?.substringAfterLast('/')?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)
        return extension !in imageExtensions
    }
}
