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

    fun blocks(host: String?): Boolean {
        val name = host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return false
        return hosts.any { name == it || name.endsWith("." + it) }
    }
}
