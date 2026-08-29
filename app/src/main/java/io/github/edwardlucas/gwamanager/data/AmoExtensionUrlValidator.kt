package io.github.edwardlucas.gwamanager.data

import android.net.Uri

object AmoExtensionUrlValidator {
    private const val AMO_HOST = "addons.mozilla.org"

    fun validate(value: String): String? {
        val trimmed = value.trim()
        val uri = Uri.parse(trimmed)
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals(AMO_HOST, ignoreCase = true)
        ) {
            return null
        }
        val pathSegments = uri.pathSegments
        if (pathSegments.size < 5 ||
            pathSegments[0] != "android" ||
            pathSegments[1] != "downloads" ||
            pathSegments[2] != "file" ||
            !pathSegments.last().endsWith(".xpi", ignoreCase = true)
        ) {
            return null
        }
        return trimmed
    }
}
