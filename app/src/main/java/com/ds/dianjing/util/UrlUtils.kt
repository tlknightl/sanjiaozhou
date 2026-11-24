package com.ds.dianjing.util

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * URL helpers used by the setup screen and by link interception in the WebView.
 */
object UrlUtils {

    private val supportedSchemes = setOf("http", "https")

    /**
     * Normalizes user input:
     *  - trims surrounding whitespace,
     *  - prepends `http://` when no scheme was given,
     *  - validates http/https + host + port,
     *  - removes a root trailing slash ("https://example.com/" -> "https://example.com").
     *
     * Returns null for anything that is not a usable HTTP(S) base URL.
     */
    fun normalize(raw: String): String? {
        var value = raw.trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) {
            return null
        }

        if (!value.startsWith("http://", ignoreCase = true) &&
            !value.startsWith("https://", ignoreCase = true)
        ) {
            value = "http://$value"
        }

        val uri = try {
            URI(value)
        } catch (e: URISyntaxException) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme !in supportedSchemes) return null
        if (uri.host.isNullOrBlank()) return null
        if (!uri.userInfo.isNullOrEmpty()) return null
        if (uri.port > 65_535) return null

        val authority = uri.rawAuthority ?: return null
        if (authority.isBlank() || authority.endsWith(":")) return null

        val path = uri.path.orEmpty()
        return if (path.isEmpty() || path == "/") {
            // Do not turn "https://host/api/" into "https://host/api", only
            // strip the root slash.
            value.trimEnd('/')
        } else {
            value
        }
    }

    /**
     * True when [url] belongs to the same site as [serverUrl]. The leading
     * "www." is ignored so a site that redirects between apex and www hosts
     * keeps working inside the native shell.
     */
    fun isSameSite(url: String?, serverUrl: String?): Boolean {
        if (url.isNullOrBlank() || serverUrl.isNullOrBlank()) return false
        val targetHost = hostOf(url) ?: return false
        val serverHost = hostOf(serverUrl) ?: return false
        return targetHost == serverHost
    }

    private fun hostOf(value: String): String? {
        return try {
            URI(value).host
                ?.lowercase(Locale.ROOT)
                ?.removePrefix("www.")
                ?.takeIf { it.isNotBlank() }
        } catch (e: URISyntaxException) {
            null
        }
    }
}
