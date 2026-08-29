package io.github.edwardlucas.gwamanager.browser

import android.util.Log
import org.mozilla.geckoview.GeckoRuntime

object GlobalExtensionInstaller {
    private const val EXTENSION_LOCATION = "resource://android/assets/global-extension/"
    private const val EXTENSION_ID = "global-extension@gwamanager.local"

    private var installedRuntime: GeckoRuntime? = null
    private val lock = Any()

    fun ensureInstalled(runtime: GeckoRuntime) {
        synchronized(lock) {
            if (installedRuntime === runtime) {
                return
            }
            installedRuntime = runtime
        }

        runtime.getWebExtensionController()
            .ensureBuiltIn(EXTENSION_LOCATION, EXTENSION_ID)
            .accept(
                { extension ->
                    Log.i(TAG, "Global WebExtension is ready: ${extension?.id}.")
                },
                { exception ->
                    synchronized(lock) {
                        if (installedRuntime === runtime) {
                            installedRuntime = null
                        }
                    }
                    Log.e(TAG, "Unable to install the global WebExtension.", exception)
                }
            )
    }

    private const val TAG = "GlobalExtension"
}
