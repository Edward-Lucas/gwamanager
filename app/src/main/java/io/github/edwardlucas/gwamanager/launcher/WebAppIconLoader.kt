package io.github.edwardlucas.gwamanager.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Html
import android.util.Log
import io.github.edwardlucas.gwamanager.browser.UserAgentProvider
import io.github.edwardlucas.gwamanager.data.UserAgentMode
import io.github.edwardlucas.gwamanager.data.WebAppConfig
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import org.json.JSONException
import org.json.JSONObject

class WebAppIconLoader(context: Context) {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val iconExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "WebAppIcon").apply {
            isDaemon = true
        }
    }
    private val callbackLock = Any()
    private val pendingCallbacks = mutableMapOf<String, MutableList<(Bitmap?) -> Unit>>()
    private val loadingKeys = mutableSetOf<String>()
    private val iconDirectory = File(appContext.filesDir, ICON_DIRECTORY_NAME)

    fun request(config: WebAppConfig, callback: (Bitmap?) -> Unit) {
        val key = iconKey(config)
        val shouldLoad = synchronized(callbackLock) {
            pendingCallbacks.getOrPut(key) { mutableListOf() }.add(callback)
            loadingKeys.add(key)
        }
        if (!shouldLoad) {
            return
        }

        iconExecutor.execute {
            val bitmap = loadIcon(config)
            mainHandler.post {
                val callbacks = synchronized(callbackLock) {
                    loadingKeys.remove(key)
                    pendingCallbacks.remove(key).orEmpty()
                }
                callbacks.forEach { callback -> callback(bitmap) }
            }
        }
    }

    fun clear(webAppId: String) {
        if (!iconDirectory.isDirectory) {
            return
        }
        iconDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.startsWith("$webAppId-")) {
                file.delete()
            }
        }
    }

    private fun loadIcon(config: WebAppConfig): Bitmap? {
        val cachedFile = iconFile(config)
        if (cachedFile.isFile && cachedFile.length() <= MAX_ICON_BYTES) {
            val cachedBitmap = try {
                cachedFile.inputStream().use { input ->
                    decodeIcon(readAtMost(input, MAX_ICON_BYTES))
                }
            } catch (exception: IOException) {
                Log.w(TAG, "Unable to read the cached WebApp icon for ${config.id}.", exception)
                null
            }
            if (cachedBitmap != null) {
                return cachedBitmap
            }
            cachedFile.delete()
        }

        val downloadedBitmap = downloadBestIcon(config) ?: return null
        val targetSize = targetIconSize(config.userAgentMode)
        val preparedBitmap = scaleToTarget(downloadedBitmap, targetSize)
        saveIcon(config, preparedBitmap)
        return preparedBitmap
    }

    private fun downloadBestIcon(config: WebAppConfig): Bitmap? {
        val page = fetchDocumentSafely(config.url, config)
        if (page != null) {
            val pageLinks = parsePageLinks(String(page.body, Charsets.UTF_8))
            val manifestUrls = buildList {
                pageLinks.manifestLinks.forEach { link ->
                    resolveResourceUrl(page.finalUrl, link.href)?.let(::add)
                }
                MANIFEST_PATHS.forEach { path ->
                    resolveResourceUrl(page.finalUrl, path)?.let(::add)
                }
            }.distinct()

            manifestUrls.forEach { manifestUrl ->
                val manifestCandidates = fetchManifestCandidates(manifestUrl, config)
                val bitmap = downloadCandidates(manifestCandidates, config)
                if (bitmap != null) {
                    return bitmap
                }
            }

            val pageIconCandidates = pageLinks.iconLinks.mapNotNull { link ->
                val iconUrl = resolveResourceUrl(page.finalUrl, link.href) ?: return@mapNotNull null
                IconCandidate(
                    url = iconUrl,
                    declaredSizes = parseDeclaredSizes(link.sizes),
                    isMonochrome = false
                )
            }
            val pageIcon = downloadCandidates(pageIconCandidates, config)
            if (pageIcon != null) {
                return pageIcon
            }
        }

        val fallbackBaseUrl = page?.finalUrl ?: config.url
        val fallbackCandidates = FALLBACK_PATHS.mapNotNull { path ->
            val iconUrl = buildRootResourceUrl(fallbackBaseUrl, path) ?: return@mapNotNull null
            IconCandidate(
                url = iconUrl,
                declaredSizes = emptySet(),
                isMonochrome = false
            )
        }
        return downloadCandidates(fallbackCandidates, config)
    }

    private fun fetchManifestCandidates(
        manifestUrl: String,
        config: WebAppConfig
    ): List<IconCandidate> {
        val manifest = fetchDocumentSafely(manifestUrl, config) ?: return emptyList()
        val manifestJson = try {
            JSONObject(String(manifest.body, Charsets.UTF_8))
        } catch (exception: JSONException) {
            Log.d(TAG, "WebApp manifest is not valid JSON: $manifestUrl.", exception)
            return emptyList()
        }
        val icons = manifestJson.optJSONArray(MANIFEST_ICONS_KEY) ?: return emptyList()
        return buildList(icons.length()) {
            for (index in 0 until icons.length()) {
                val icon = icons.optJSONObject(index) ?: continue
                val source = icon.optString(MANIFEST_SOURCE_KEY).trim()
                val iconUrl = resolveResourceUrl(manifest.finalUrl, source) ?: continue
                val purpose = icon.optString(MANIFEST_PURPOSE_KEY, DEFAULT_ICON_PURPOSE)
                    .split(Regex("\\s+"))
                    .filter { it.isNotBlank() }
                    .map { it.lowercase(Locale.ROOT) }
                add(
                    IconCandidate(
                        url = iconUrl,
                        declaredSizes = parseDeclaredSizes(
                            icon.optString(MANIFEST_SIZES_KEY).takeIf { it.isNotBlank() }
                        ),
                        isMonochrome = purpose.size == 1 && purpose[0] == "monochrome"
                    )
                )
            }
        }
    }

    private fun downloadCandidates(
        candidates: List<IconCandidate>,
        config: WebAppConfig
    ): Bitmap? {
        val targetSize = targetIconSize(config.userAgentMode)
        var fallback: Bitmap? = null
        orderedCandidates(candidates, targetSize).forEach { candidate ->
            val bitmap = try {
                downloadIcon(candidate.url, config)
            } catch (exception: IOException) {
                Log.d(TAG, "WebApp icon request failed for ${candidate.url}.", exception)
                null
            } catch (exception: SecurityException) {
                Log.d(TAG, "WebApp icon access was denied for ${candidate.url}.", exception)
                null
            }
            if (bitmap != null) {
                if (fallback == null ||
                    minOf(bitmap.width, bitmap.height) >
                    minOf(fallback.width, fallback.height)
                ) {
                    fallback?.recycle()
                    fallback = bitmap
                }
                if (minOf(bitmap.width, bitmap.height) >= targetSize) {
                    if (fallback !== bitmap) {
                        fallback?.recycle()
                    }
                    return bitmap
                } else if (fallback !== bitmap) {
                    bitmap.recycle()
                }
            }
        }
        return fallback
    }

    @Throws(IOException::class)
    private fun downloadIcon(iconUrl: String, config: WebAppConfig): Bitmap? {
        val connection = openConnection(iconUrl, config, "image/*") ?: return null
        try {
            if (connection.responseCode !in HTTP_SUCCESS..HTTP_SUCCESS_MAX) {
                return null
            }
            if (connection.contentLengthLong > MAX_ICON_BYTES) {
                return null
            }
            val bytes = connection.inputStream.use { input ->
                readAtMost(input, MAX_ICON_BYTES)
            } ?: return null
            return decodeIcon(bytes)
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun fetchDocument(url: String, config: WebAppConfig): FetchedDocument? {
        val connection = openConnection(url, config, "text/html, application/manifest+json, application/json")
            ?: return null
        try {
            if (connection.responseCode !in HTTP_SUCCESS..HTTP_SUCCESS_MAX) {
                return null
            }
            if (connection.contentLengthLong > MAX_DOCUMENT_BYTES) {
                return null
            }
            val body = connection.inputStream.use { input ->
                readAtMost(input, MAX_DOCUMENT_BYTES)
            } ?: return null
            return FetchedDocument(body, connection.url.toString())
        } finally {
            connection.disconnect()
        }
    }

    private fun fetchDocumentSafely(url: String, config: WebAppConfig): FetchedDocument? {
        return try {
            fetchDocument(url, config)
        } catch (exception: IOException) {
            Log.d(TAG, "WebApp metadata request failed for $url.", exception)
            null
        } catch (exception: SecurityException) {
            Log.d(TAG, "WebApp metadata access was denied for $url.", exception)
            null
        }
    }

    private fun openConnection(
        resourceUrl: String,
        config: WebAppConfig,
        accept: String
    ): HttpURLConnection? {
        val connection = URL(resourceUrl).openConnection() as? HttpURLConnection ?: return null
        connection.instanceFollowRedirects = true
        connection.connectTimeout = NETWORK_TIMEOUT_MS
        connection.readTimeout = NETWORK_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", accept)
        connection.setRequestProperty(
            "User-Agent",
            UserAgentProvider.forMode(config.userAgentMode)
        )
        return connection
    }

    private fun parsePageLinks(html: String): ParsedPageLinks {
        val links = LINK_TAG_PATTERN.findAll(html).mapNotNull { match ->
            parseLinkTag(match.groupValues[1])
        }.toList()
        return ParsedPageLinks(
            manifestLinks = links.filter { it.rel.contains("manifest") },
            iconLinks = links.filter { link ->
                link.rel.contains("icon") ||
                    link.rel.any { token -> token.startsWith("apple-touch-icon") }
            }
        )
    }

    private fun parseLinkTag(attributesText: String): LinkTag? {
        val attributes = ATTRIBUTE_PATTERN.findAll(attributesText).associate { match ->
            val value = match.groups[2]?.value
                ?: match.groups[3]?.value
                ?: match.groups[4]?.value
                ?: ""
            match.groupValues[1].lowercase(Locale.ROOT) to decodeHtml(value)
        }
        val href = attributes["href"]?.trim().orEmpty()
        if (href.isEmpty()) {
            return null
        }
        val rel = attributes["rel"]
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.map { it.lowercase(Locale.ROOT) }
            ?.toSet()
            .orEmpty()
        return LinkTag(
            href = href,
            rel = rel,
            sizes = attributes["sizes"]
        )
    }

    private fun orderedCandidates(
        candidates: List<IconCandidate>,
        targetSize: Int
    ): List<IconCandidate> {
        return candidates
            .distinctBy { it.url }
            .sortedWith(
                compareBy<IconCandidate> { candidateTier(it, targetSize) }
                    .thenBy { candidateDistance(it, targetSize) }
                    .thenBy { if (it.isMonochrome) 1 else 0 }
                    .thenByDescending { it.declaredSizes.maxOrNull() ?: 0 }
            )
    }

    private fun candidateTier(candidate: IconCandidate, targetSize: Int): Int {
        return when {
            targetSize in candidate.declaredSizes -> 0
            candidate.declaredSizes.any { it >= targetSize } -> 1
            candidate.declaredSizes.isNotEmpty() -> 2
            else -> 3
        }
    }

    private fun candidateDistance(candidate: IconCandidate, targetSize: Int): Int {
        return when (candidateTier(candidate, targetSize)) {
            1 -> candidate.declaredSizes.filter { it >= targetSize }.minOrNull()!! - targetSize
            2 -> targetSize - candidate.declaredSizes.maxOrNull()!!
            else -> 0
        }
    }

    private fun parseDeclaredSizes(value: String?): Set<Int> {
        return value.orEmpty()
            .split(Regex("\\s+"))
            .mapNotNull { token ->
                val parts = token.lowercase(Locale.ROOT).split("x")
                if (parts.size != 2) {
                    return@mapNotNull null
                }
                val width = parts[0].toIntOrNull() ?: return@mapNotNull null
                val height = parts[1].toIntOrNull() ?: return@mapNotNull null
                if (width > 0 && width == height) width else null
            }
            .toSet()
    }

    private fun decodeHtml(value: String): String {
        return Html.fromHtml(value, Html.FROM_HTML_MODE_LEGACY).toString()
    }

    private fun decodeIcon(bytes: ByteArray?): Bitmap? {
        if (bytes == null) {
            return null
        }
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            return null
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = calculateSampleSize(bounds.outWidth, bounds.outHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (bitmap.width <= 0 || bitmap.height <= 0) {
            bitmap.recycle()
            return null
        }
        return scaleToMaximum(bitmap, MAX_ICON_SIZE)
    }

    private fun calculateSampleSize(width: Int, height: Int): Int {
        var sampleSize = 1
        while (
            maxOf(width / (sampleSize * 2), height / (sampleSize * 2)) >= MAX_ICON_SIZE
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun scaleToTarget(bitmap: Bitmap, targetSize: Int): Bitmap {
        return scaleToMaximum(bitmap, targetSize)
    }

    private fun scaleToMaximum(bitmap: Bitmap, maximumSize: Int): Bitmap {
        val largestDimension = maxOf(bitmap.width, bitmap.height)
        if (largestDimension <= maximumSize) {
            return bitmap
        }
        val scale = maximumSize.toDouble() / largestDimension
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaledBitmap !== bitmap) {
            bitmap.recycle()
        }
        return scaledBitmap
    }

    private fun saveIcon(config: WebAppConfig, bitmap: Bitmap) {
        try {
            if (!iconDirectory.exists() && !iconDirectory.mkdirs()) {
                Log.w(TAG, "Unable to create the WebApp icon cache directory.")
                return
            }
            val target = iconFile(config)
            iconDirectory.listFiles()?.forEach { file ->
                if (file.isFile &&
                    file.name.startsWith("${config.id}-") &&
                    file != target
                ) {
                    file.delete()
                }
            }
            target.outputStream().use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    Log.w(TAG, "Unable to cache the WebApp icon for ${config.id}.")
                }
            }
        } catch (exception: IOException) {
            Log.w(TAG, "Unable to cache the WebApp icon for ${config.id}.", exception)
        }
    }

    private fun buildRootResourceUrl(sourceUrl: String, path: String): String? {
        val source = Uri.parse(sourceUrl)
        val scheme = source.scheme?.lowercase(Locale.ROOT) ?: return null
        val authority = source.encodedAuthority ?: return null
        if (scheme != "http" && scheme != "https") {
            return null
        }
        return Uri.Builder()
            .scheme(scheme)
            .encodedAuthority(authority)
            .path(path)
            .build()
            .toString()
    }

    private fun resolveResourceUrl(baseUrl: String, resource: String): String? {
        val trimmed = resource.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        return try {
            val resolved = URI(baseUrl).resolve(trimmed)
            val scheme = resolved.scheme?.lowercase(Locale.ROOT)
            if ((scheme != "http" && scheme != "https") || resolved.host.isNullOrBlank()) {
                null
            } else {
                resolved.toString()
            }
        } catch (exception: java.net.URISyntaxException) {
            Log.d(TAG, "Unable to resolve WebApp icon URL $resource.", exception)
            null
        } catch (exception: IllegalArgumentException) {
            Log.d(TAG, "Unable to resolve WebApp icon URL $resource.", exception)
            null
        }
    }

    private fun readAtMost(input: java.io.InputStream, maximumBytes: Long): ByteArray? {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) {
                break
            }
            totalBytes += count
            if (totalBytes > maximumBytes) {
                return null
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun iconFile(config: WebAppConfig): File {
        val mode = config.userAgentMode.name.lowercase(Locale.ROOT)
        return File(iconDirectory, "${config.id}-${config.url.hashCode()}-$mode.png")
    }

    private fun iconKey(config: WebAppConfig): String {
        return "${config.id}\u0000${config.url}\u0000${config.userAgentMode.name}"
    }

    private fun targetIconSize(mode: UserAgentMode): Int {
        return if (mode == UserAgentMode.DESKTOP) {
            DESKTOP_ICON_SIZE
        } else {
            MOBILE_ICON_SIZE
        }
    }

    private data class FetchedDocument(
        val body: ByteArray,
        val finalUrl: String
    )

    private data class LinkTag(
        val href: String,
        val rel: Set<String>,
        val sizes: String?
    )

    private data class ParsedPageLinks(
        val manifestLinks: List<LinkTag>,
        val iconLinks: List<LinkTag>
    )

    private data class IconCandidate(
        val url: String,
        val declaredSizes: Set<Int>,
        val isMonochrome: Boolean
    )

    private companion object {
        const val TAG = "WebAppIconLoader"
        const val ICON_DIRECTORY_NAME = "webapp-icons"
        const val MANIFEST_ICONS_KEY = "icons"
        const val MANIFEST_SOURCE_KEY = "src"
        const val MANIFEST_SIZES_KEY = "sizes"
        const val MANIFEST_PURPOSE_KEY = "purpose"
        const val DEFAULT_ICON_PURPOSE = "any"
        const val MOBILE_ICON_SIZE = 192
        const val DESKTOP_ICON_SIZE = 512
        const val MAX_ICON_SIZE = DESKTOP_ICON_SIZE
        const val MAX_ICON_BYTES = 4 * 1024 * 1024L
        const val MAX_DOCUMENT_BYTES = 1024 * 1024L
        const val BUFFER_SIZE = 8192
        const val NETWORK_TIMEOUT_MS = 5000
        const val HTTP_SUCCESS = 200
        const val HTTP_SUCCESS_MAX = 299
        val FALLBACK_PATHS = listOf(
            "/apple-touch-icon.png",
            "/favicon.png",
            "/favicon.ico",
            "/favicon.svg"
        )
        val MANIFEST_PATHS = listOf("/manifest.json", "/manifest.webmanifest")
        val LINK_TAG_PATTERN = Regex(
            "<link\\b([^>]*?)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val ATTRIBUTE_PATTERN = Regex(
            """([A-Za-z_:][A-Za-z0-9_.:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+))"""
        )
    }
}
