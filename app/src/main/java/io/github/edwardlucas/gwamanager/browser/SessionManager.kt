package io.github.edwardlucas.gwamanager.browser

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import io.github.edwardlucas.gwamanager.data.UserAgentMode

class SessionManager {
    private val lock = Any()
    private val sessions = mutableMapOf<SessionKey, SessionEntry>()

    fun getOrCreate(
        webAppId: String,
        userAgentMode: UserAgentMode,
        runtime: GeckoRuntime
    ): GeckoSession {
        val key = SessionKey(webAppId, userAgentMode)
        return synchronized(lock) {
            val existing = sessions[key]
            if (existing != null && existing.session.isOpen) {
                Log.d(TAG, "Reusing GeckoSession for WebApp $webAppId ($userAgentMode).")
                existing.session
            } else {
                val settings = GeckoSessionSettings.Builder()
                    .userAgentOverride(UserAgentProvider.forMode(userAgentMode))
                    .suspendMediaWhenInactive(false)
                    .build()
                val session = GeckoSession(settings)
                session.open(runtime)
                sessions[key] = SessionEntry(session)
                Log.d(TAG, "Created GeckoSession for WebApp $webAppId ($userAgentMode).")
                session
            }
        }
    }

    fun loadUrl(
        webAppId: String,
        userAgentMode: UserAgentMode,
        url: String,
        session: GeckoSession
    ) {
        synchronized(lock) {
            val entry = sessions[SessionKey(webAppId, userAgentMode)]
            if (entry == null || entry.session !== session || entry.lastLoadedUrl == url) {
                return
            }
            entry.lastLoadedUrl = url
        }
        session.loadUri(url)
    }

    fun markLoadFailed(session: GeckoSession) {
        synchronized(lock) {
            sessions.values.firstOrNull { it.session === session }?.lastLoadedUrl = null
        }
    }

    fun close(webAppId: String) {
        synchronized(lock) {
            val keys = sessions.keys.filter { it.webAppId == webAppId }
            keys.forEach { key ->
                sessions.remove(key)?.session?.takeIf { it.isOpen }?.close()
            }
        }
    }

    private data class SessionKey(
        val webAppId: String,
        val userAgentMode: UserAgentMode
    )

    private data class SessionEntry(
        val session: GeckoSession,
        var lastLoadedUrl: String? = null
    )

    private companion object {
        const val TAG = "SessionManager"
    }
}
