package io.github.edwardlucas.gwamanager.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import android.util.Log
import io.github.edwardlucas.gwamanager.R
import io.github.edwardlucas.gwamanager.data.WebAppConfig
import io.github.edwardlucas.gwamanager.ui.WebAppActivity
import java.util.concurrent.ConcurrentHashMap

class WebAppShortcutManager(context: Context) {
    private val appContext = context.applicationContext
    private val shortcutManager = requireNotNull(
        appContext.getSystemService(ShortcutManager::class.java)
    )
    private val iconLoader = WebAppIconLoader(appContext)
    private val configsById = ConcurrentHashMap<String, WebAppConfig>()

    fun sync(configs: List<WebAppConfig>) {
        val uniqueConfigs = configs.distinctBy { it.id }
        configsById.clear()
        uniqueConfigs.forEach { config ->
            configsById[config.id] = config
        }

        val dynamicLimit = shortcutManager.maxShortcutCountPerActivity
        val dynamicConfigs = uniqueConfigs.take(dynamicLimit)
        shortcutManager.setDynamicShortcuts(dynamicConfigs.map(::createShortcut))

        val pinnedIds = shortcutManager.pinnedShortcuts.map { it.id }.toSet()
        val pinnedUpdates = uniqueConfigs
            .filter { shortcutId(it.id) in pinnedIds }
            .map(::createShortcut)
        if (pinnedUpdates.isNotEmpty()) {
            shortcutManager.updateShortcuts(pinnedUpdates)
        }

        val knownIds = uniqueConfigs.map { shortcutId(it.id) }.toSet()
        val dynamicIds = shortcutManager.dynamicShortcuts.map { it.id }.toSet()
        val staleIds = (dynamicIds + pinnedIds).filter {
            it.startsWith(SHORTCUT_ID_PREFIX) && it !in knownIds
        }
        if (staleIds.isNotEmpty()) {
            shortcutManager.removeDynamicShortcuts(staleIds)
            shortcutManager.removeLongLivedShortcuts(staleIds)
            val stalePinnedIds = staleIds.filter { it in pinnedIds }
            if (stalePinnedIds.isNotEmpty()) {
                shortcutManager.disableShortcuts(
                    stalePinnedIds,
                    appContext.getString(R.string.web_app_shortcut_deleted)
                )
            }
        }

        uniqueConfigs.forEach(::scheduleIconRefresh)
    }

    fun requestPinnedShortcut(config: WebAppConfig): Boolean {
        if (!shortcutManager.isRequestPinShortcutSupported) {
            return false
        }
        val shortcut = createShortcut(config)
        if (shortcutManager.pinnedShortcuts.any { it.id == shortcut.id }) {
            shortcutManager.updateShortcuts(listOf(shortcut))
            scheduleIconRefresh(config)
            return true
        }
        if (!shortcutManager.requestPinShortcut(shortcut, null)) {
            Log.w(TAG, "Launcher rejected pin request for WebApp ${config.id}.")
            return false
        }
        scheduleIconRefresh(config)
        return true
    }

    fun supportsPinnedShortcuts(): Boolean {
        return shortcutManager.isRequestPinShortcutSupported
    }

    fun isPinned(webAppId: String): Boolean {
        return shortcutManager.pinnedShortcuts.any { it.id == shortcutId(webAppId) }
    }

    fun requestIcon(config: WebAppConfig, callback: (Bitmap?) -> Unit) {
        iconLoader.request(config, callback)
    }

    fun remove(webAppId: String) {
        configsById.remove(webAppId)
        val id = shortcutId(webAppId)
        shortcutManager.removeDynamicShortcuts(listOf(id))
        shortcutManager.removeLongLivedShortcuts(listOf(id))
        if (shortcutManager.pinnedShortcuts.any { it.id == id }) {
            shortcutManager.disableShortcuts(
                listOf(id),
                appContext.getString(R.string.web_app_shortcut_deleted)
            )
        }
        iconLoader.clear(webAppId)
    }

    private fun createShortcut(config: WebAppConfig, bitmap: Bitmap? = null): ShortcutInfo {
        val label = config.name.take(MAX_SHORT_LABEL_LENGTH).ifBlank { DEFAULT_LABEL }
        val builder = ShortcutInfo.Builder(appContext, shortcutId(config.id))
            .setActivity(ComponentName(appContext, WebAppActivity::class.java))
            .setShortLabel(label)
            .setLongLabel(config.name.take(MAX_LONG_LABEL_LENGTH).ifBlank { DEFAULT_LABEL })
            .setIntent(WebAppActivity.createIntent(appContext, config.id))
            .setLongLived(true)
        if (bitmap == null) {
            builder.setIcon(Icon.createWithResource(appContext, R.mipmap.ic_launcher))
        } else {
            builder.setIcon(Icon.createWithBitmap(bitmap))
        }
        return builder.build()
    }

    private fun scheduleIconRefresh(config: WebAppConfig) {
        iconLoader.request(config) { bitmap ->
            if (bitmap != null) {
                updateShortcutIcon(config, bitmap)
            }
        }
    }

    private fun updateShortcutIcon(config: WebAppConfig, bitmap: Bitmap) {
        val currentConfig = configsById[config.id] ?: return
        if (currentConfig.url != config.url) {
            return
        }
        val shortcutId = shortcutId(config.id)
        val isKnownShortcut = shortcutManager.dynamicShortcuts.any { it.id == shortcutId } ||
            shortcutManager.pinnedShortcuts.any { it.id == shortcutId }
        if (!isKnownShortcut) {
            return
        }
        try {
            shortcutManager.updateShortcuts(
                listOf(createShortcut(currentConfig, bitmap))
            )
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "Launcher rejected the WebApp icon for ${config.id}.", exception)
        } catch (exception: IllegalStateException) {
            Log.w(TAG, "Launcher state prevented the WebApp icon update for ${config.id}.", exception)
        }
    }

    private fun shortcutId(webAppId: String): String {
        return "$SHORTCUT_ID_PREFIX$webAppId"
    }

    private companion object {
        const val TAG = "WebAppShortcutManager"
        const val SHORTCUT_ID_PREFIX = "webapp_"
        const val MAX_SHORT_LABEL_LENGTH = 25
        const val MAX_LONG_LABEL_LENGTH = 100
        const val DEFAULT_LABEL = "WebApp"
    }
}
