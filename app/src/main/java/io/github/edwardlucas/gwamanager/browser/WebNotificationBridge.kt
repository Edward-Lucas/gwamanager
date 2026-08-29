package io.github.edwardlucas.gwamanager.browser

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.edwardlucas.gwamanager.R
import io.github.edwardlucas.gwamanager.data.WebAppRepository
import io.github.edwardlucas.gwamanager.ui.WebAppActivity
import org.mozilla.geckoview.WebNotification
import org.mozilla.geckoview.WebNotificationDelegate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class WebNotificationBridge(
    private val context: Context,
    private val repository: WebAppRepository
) : WebNotificationDelegate {
    private val notificationManager = NotificationManagerCompat.from(context)
    private val nextNotificationId = AtomicInteger(NOTIFICATION_ID_START)
    private val recordsByKey = ConcurrentHashMap<String, NotificationRecord>()
    private val recordsById = ConcurrentHashMap<Int, NotificationRecord>()

    @Volatile
    private var activeWebAppId: String? = null

    init {
        createNotificationChannel()
    }

    fun setActiveWebApp(webAppId: String) {
        activeWebAppId = webAppId
    }

    fun canPostNotifications(): Boolean {
        if (!notificationManager.areNotificationsEnabled()) {
            return false
        }
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
    }

    fun click(notificationId: Int) {
        val record = recordsById.remove(notificationId) ?: return
        recordsByKey.remove(record.key, record)
        notificationManager.cancel(notificationId)
        record.notification.click()
    }

    override fun onShowNotification(notification: WebNotification) {
        if (!canPostNotifications()) {
            notification.dismiss()
            return
        }

        val webAppId = resolveWebAppId(notification)
        if (webAppId == null) {
            Log.w(TAG, "Unable to associate web notification with a WebApp.")
            notification.dismiss()
            return
        }

        val key = buildKey(webAppId, notification)
        val existing = recordsByKey.remove(key)
        if (existing != null) {
            recordsById.remove(existing.id, existing)
            notificationManager.cancel(existing.id)
            existing.notification.dismiss()
        }

        val notificationId = nextNotificationId.getAndIncrement()
        val record = NotificationRecord(webAppId, key, notificationId, notification)
        recordsByKey[key] = record
        recordsById[notificationId] = record

        val contentIntent = PendingIntent.getActivity(
            context,
            notificationId,
            WebAppActivity.createIntent(context, webAppId, notificationId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val title = notification.title.orEmpty()
        val text = notification.text.orEmpty()
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title.ifBlank { context.getString(R.string.app_name) })
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setSilent(notification.silent)

        try {
            notificationManager.notify(notificationId, builder.build())
            notification.show()
        } catch (exception: SecurityException) {
            recordsById.remove(notificationId, record)
            recordsByKey.remove(key, record)
            notification.dismiss()
        }
    }

    override fun onCloseNotification(notification: WebNotification) {
        val record = recordsById.values.firstOrNull { it.notification === notification }
        if (record != null) {
            recordsById.remove(record.id, record)
            recordsByKey.remove(record.key, record)
            notificationManager.cancel(record.id)
        }
        notification.dismiss()
    }

    private fun resolveWebAppId(notification: WebNotification): String? {
        val source = notification.source.orEmpty().trim()
        val origin = notification.origin.orEmpty().trim()
        val sourceUri = parseWebUri(source)
        val originUri = parseWebUri(origin)
        val referenceUri = sourceUri ?: originUri

        if (referenceUri == null) {
            return if (source.isBlank() && origin.isBlank()) {
                activeWebAppId
            } else {
                null
            }
        }

        val matches = repository.getAll().mapNotNull { config ->
            val configUri = parseWebUri(config.url) ?: return@mapNotNull null
            if (!sameOrigin(configUri, referenceUri)) {
                return@mapNotNull null
            }
            WebAppMatch(
                id = config.id,
                pathLength = sourceUri?.let { pathMatchLength(it, configUri) } ?: 0
            )
        }
        if (matches.isEmpty()) {
            return null
        }

        activeWebAppId?.let { activeId ->
            val activeMatch = matches.firstOrNull { it.id == activeId }
            if (activeMatch != null && (sourceUri == null || activeMatch.pathLength > 0)) {
                return activeId
            }
        }

        val pathMatches = matches.filter { it.pathLength > 0 }
        if (pathMatches.isNotEmpty()) {
            val longestPathLength = pathMatches.maxOf { it.pathLength }
            val bestMatches = pathMatches.filter { it.pathLength == longestPathLength }
            if (bestMatches.size == 1) {
                return bestMatches.single().id
            }
            return null
        }
        return matches.singleOrNull()?.id
    }

    private fun buildKey(webAppId: String, notification: WebNotification): String {
        return listOf(
            webAppId,
            notification.origin.orEmpty(),
            notification.source.orEmpty(),
            notification.tag.orEmpty()
        ).joinToString(separator = "\u0000")
    }

    private fun parseWebUri(value: String): Uri? {
        if (value.isBlank()) {
            return null
        }
        val uri = Uri.parse(value)
        val scheme = uri.scheme?.lowercase()
        return if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()) {
            uri
        } else {
            null
        }
    }

    private fun sameOrigin(first: Uri, second: Uri): Boolean {
        return first.scheme.equals(second.scheme, ignoreCase = true) &&
            first.host.equals(second.host, ignoreCase = true) &&
            effectivePort(first) == effectivePort(second)
    }

    private fun effectivePort(uri: Uri): Int {
        if (uri.port != -1) {
            return uri.port
        }
        return if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80
    }

    private fun pathMatchLength(source: Uri, config: Uri): Int {
        val sourcePath = normalizePath(source.path)
        val configPath = normalizePath(config.path)
        return if (
            configPath == "/" ||
            sourcePath == configPath ||
            sourcePath.startsWith("$configPath/")
        ) {
            configPath.length
        } else {
            0
        }
    }

    private fun normalizePath(path: String?): String {
        val normalized = path.orEmpty().ifBlank { "/" }
        return if (normalized.length > 1) {
            normalized.trimEnd('/')
        } else {
            normalized
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.web_app_notifications),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.web_app_notifications_description)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private data class NotificationRecord(
        val webAppId: String,
        val key: String,
        val id: Int,
        val notification: WebNotification
    )

    private companion object {
        const val TAG = "WebNotificationBridge"
        const val CHANNEL_ID = "web_app_notifications"
        const val NOTIFICATION_ID_START = 1000
    }

    private data class WebAppMatch(
        val id: String,
        val pathLength: Int
    )
}
