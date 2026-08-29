package io.github.edwardlucas.gwamanager

import android.app.Application
import io.github.edwardlucas.gwamanager.browser.GlobalExtensionInstaller
import io.github.edwardlucas.gwamanager.browser.SessionManager
import io.github.edwardlucas.gwamanager.browser.WebNotificationBridge
import io.github.edwardlucas.gwamanager.browser.WebExtensionManager
import io.github.edwardlucas.gwamanager.data.WebAppRepository
import io.github.edwardlucas.gwamanager.data.WebExtensionRepository
import io.github.edwardlucas.gwamanager.launcher.WebAppShortcutManager
import org.mozilla.geckoview.GeckoRuntime

class GwaManagerApplication : Application() {
    lateinit var webAppRepository: WebAppRepository
        private set

    lateinit var webAppShortcutManager: WebAppShortcutManager
        private set

    lateinit var webExtensionRepository: WebExtensionRepository
        private set

    lateinit var webExtensionManager: WebExtensionManager
        private set

    val sessionManager: SessionManager = SessionManager()
    lateinit var webNotificationBridge: WebNotificationBridge
        private set

    private val runtimeLock = Any()
    private var runtime: GeckoRuntime? = null

    override fun onCreate() {
        super.onCreate()
        webAppRepository = WebAppRepository(this)
        webAppShortcutManager = WebAppShortcutManager(this)
        webAppShortcutManager.sync(webAppRepository.getAll())
        webExtensionRepository = WebExtensionRepository(this)
        webExtensionManager = WebExtensionManager(webExtensionRepository) {
            getGeckoRuntime()
        }
        webNotificationBridge = WebNotificationBridge(this, webAppRepository)
    }

    fun getGeckoRuntime(): GeckoRuntime {
        return synchronized(runtimeLock) {
            runtime ?: GeckoRuntime.create(this).also {
                runtime = it
                it.setWebNotificationDelegate(webNotificationBridge)
                GlobalExtensionInstaller.ensureInstalled(it)
                webExtensionManager.ensureInstalled(it)
            }
        }
    }

    fun warmUpGeckoRuntime() {
        getGeckoRuntime().warmUp()
    }
}
