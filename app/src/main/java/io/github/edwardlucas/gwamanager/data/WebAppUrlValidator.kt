package io.github.edwardlucas.gwamanager.data

import android.net.Uri

object WebAppUrlValidator {
    fun validate(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) {
            return null
        }

        val uri = Uri.parse(trimmed)
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return null
        }
        if (uri.host.isNullOrBlank()) {
            return null
        }
        return trimmed
    }
}
