package com.example.testing

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

internal object ReaderLinks {
    sealed interface Check

    data class Approved(val provider: String, val host: String) : Check

    enum class Blocked : Check {
        UNSAFE_FORMAT,
        UNSUPPORTED_HOST
    }

    fun inspect(url: String?): Check {
        if (url.isNullOrEmpty() || url.length > 4096 ||
            url.any { it == '\\' || it.isWhitespace() || isHidden(it) }) {
            return Blocked.UNSAFE_FORMAT
        }

        val uri = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return Blocked.UNSAFE_FORMAT
        }
        if (!"https".equals(uri.scheme, ignoreCase = true) || uri.rawUserInfo != null) {
            return Blocked.UNSAFE_FORMAT
        }

        val host = uri.host?.lowercase(Locale.ROOT) ?: return Blocked.UNSAFE_FORMAT
        val authority = uri.rawAuthority?.lowercase(Locale.ROOT)
        if (authority != host && authority != "$host:443") return Blocked.UNSAFE_FORMAT
        if (listOf(uri.path, uri.query, uri.fragment).any { part ->
                part?.any { it == '\\' || isHidden(it) } == true
            }) return Blocked.UNSAFE_FORMAT

        val provider = when (host) {
            "mangakatana.com", "www.mangakatana.com" -> "mangakatana.com"
            "readcomicsonline.ru", "www.readcomicsonline.ru" -> "readcomicsonline.ru"
            else -> return Blocked.UNSUPPORTED_HOST
        }
        return Approved(provider, host)
    }

    private fun isHidden(character: Char): Boolean =
        character.isISOControl() || Character.getType(character) == Character.FORMAT.toInt()
}
