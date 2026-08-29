package io.github.edwardlucas.gwamanager.browser

import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.edwardlucas.gwamanager.data.AmoExtensionUrlValidator
import io.github.edwardlucas.gwamanager.data.ImportedWebExtension
import io.github.edwardlucas.gwamanager.data.StoredWebExtension
import io.github.edwardlucas.gwamanager.data.WebExtensionRepository
import java.util.concurrent.ConcurrentHashMap
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController

typealias WebExtensionInstallPrompt = (
    WebExtension,
    Array<String>,
    Array<String>,
    Array<String>
) -> GeckoResult<WebExtension.PermissionPromptResponse>

class WebExtensionManager(
    private val repository: WebExtensionRepository,
    private val runtimeProvider: () -> GeckoRuntime
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var installedRuntime: GeckoRuntime? = null
    private var controller: WebExtensionController? = null
    private val installedExtensions = ConcurrentHashMap<String, WebExtension>()
    private val pendingInstallLocations = ConcurrentHashMap.newKeySet<String>()
    private var pendingRemoteInstall: PendingRemoteInstall? = null

    private val promptDelegate = object : WebExtensionController.PromptDelegate {
        override fun onInstallPromptRequest(
            extension: WebExtension,
            permissions: Array<String>,
            origins: Array<String>,
            dataCollectionPermissions: Array<String>
        ): GeckoResult<WebExtension.PermissionPromptResponse> {
            if (extension.id == GLOBAL_EXTENSION_ID) {
                return GeckoResult.fromValue(
                    WebExtension.PermissionPromptResponse(
                        true,
                        false,
                        false
                    )
                )
            }
            val remotePrompt = synchronized(lock) {
                pendingRemoteInstall?.prompt
            }
            if (remotePrompt != null) {
                return remotePrompt(
                    extension,
                    permissions,
                    origins,
                    dataCollectionPermissions
                )
            }
            val approved = synchronized(lock) {
                pendingInstallLocations.isNotEmpty() ||
                    repository.getAll().any { stored -> stored.id == extension.id }
            }
            return GeckoResult.fromValue(
                WebExtension.PermissionPromptResponse(
                    approved,
                    false,
                    false
                )
            )
        }
    }

    fun ensureInstalled(runtime: GeckoRuntime) {
        val activeController = runtime.getWebExtensionController()
        synchronized(lock) {
            if (installedRuntime === runtime) {
                return
            }
            installedRuntime = runtime
            controller = activeController
        }
        activeController.setPromptDelegate(promptDelegate)
        activeController.list().accept(
            { extensions ->
                extensions.orEmpty().forEach { extension ->
                    installedExtensions[extension.id] = extension
                }
                repository.getAll().forEach { stored ->
                    if (extensions.orEmpty().none { it.id == stored.id }) {
                        if (stored.sourceUrl != null) {
                            restoreRemoteExtension(activeController, stored)
                        } else {
                            restoreStoredExtension(activeController, stored)
                        }
                    }
                }
            },
            { exception ->
                Log.e(TAG, "Unable to list installed WebExtensions.", exception)
            }
        )
    }

    fun install(
        imported: ImportedWebExtension,
        callback: (Result<StoredWebExtension>) -> Unit
    ) {
        val activeController = try {
            getController()
        } catch (exception: IllegalStateException) {
            finishInstall(imported, callback, Result.failure(exception))
            return
        } catch (exception: SecurityException) {
            finishInstall(imported, callback, Result.failure(exception))
            return
        }
        val location = imported.file.toURI().toString()
        pendingInstallLocations.add(location)
        try {
            activeController.install(
                location,
                WebExtensionController.INSTALLATION_METHOD_FROM_FILE
            ).accept(
                { extension ->
                    pendingInstallLocations.remove(location)
                    if (extension == null || extension.id.isBlank()) {
                        finishInstall(
                            imported,
                            callback,
                            Result.failure(IllegalStateException("The WebExtension has no valid ID."))
                        )
                        return@accept
                    }
                    val stored = StoredWebExtension(
                        id = extension.id,
                        name = extension.metaData?.name?.takeIf { it.isNotBlank() } ?: imported.name,
                        version = extension.metaData?.version?.takeIf { it.isNotBlank() }
                            ?: imported.version,
                        fileName = imported.file.name
                    )
                    if (!repository.save(stored)) {
                        activeController.uninstall(extension).accept(
                            {
                                finishInstall(
                                    imported,
                                    callback,
                                    Result.failure(
                                        IllegalStateException(
                                            "Unable to save the WebExtension configuration."
                                        )
                                    )
                                )
                            },
                            { uninstallException ->
                                Log.e(
                                    TAG,
                                    "Unable to roll back an unsaved WebExtension.",
                                    uninstallException
                                )
                                finishInstall(
                                    imported,
                                    callback,
                                    Result.failure(
                                        IllegalStateException(
                                            "Unable to save the WebExtension configuration.",
                                            uninstallException
                                        )
                                    )
                                )
                            }
                        )
                        return@accept
                    }
                    installedExtensions[extension.id] = extension
                    finishInstall(imported, callback, Result.success(stored))
                },
                { exception ->
                    pendingInstallLocations.remove(location)
                    finishInstall(
                        imported,
                        callback,
                        Result.failure(
                            exception ?: IllegalStateException("WebExtension installation failed.")
                        )
                    )
                }
            )
        } catch (exception: IllegalArgumentException) {
            pendingInstallLocations.remove(location)
            finishInstall(imported, callback, Result.failure(exception))
        } catch (exception: IllegalStateException) {
            pendingInstallLocations.remove(location)
            finishInstall(imported, callback, Result.failure(exception))
        } catch (exception: SecurityException) {
            pendingInstallLocations.remove(location)
            finishInstall(imported, callback, Result.failure(exception))
        }
    }

    fun installFromUrl(
        location: String,
        prompt: WebExtensionInstallPrompt,
        callback: (Result<StoredWebExtension>) -> Unit
    ) {
        val normalizedLocation = AmoExtensionUrlValidator.validate(location)
        if (normalizedLocation == null) {
            finish(
                callback,
                Result.failure(IllegalArgumentException("Only AMO WebExtension URLs are supported."))
            )
            return
        }
        val activeController = try {
            getController()
        } catch (exception: IllegalStateException) {
            finish(callback, Result.failure(exception))
            return
        } catch (exception: SecurityException) {
            finish(callback, Result.failure(exception))
            return
        }
        synchronized(lock) {
            if (pendingRemoteInstall != null) {
                finish(
                    callback,
                    Result.failure(IllegalStateException("Another WebExtension installation is in progress."))
                )
                return
            }
            pendingRemoteInstall = PendingRemoteInstall(normalizedLocation, prompt)
        }
        pendingInstallLocations.add(normalizedLocation)
        try {
            activeController.install(
                normalizedLocation,
                WebExtensionController.INSTALLATION_METHOD_MANAGER
            ).accept(
                { extension ->
                    clearRemoteInstall(normalizedLocation)
                    if (extension == null || extension.id.isBlank()) {
                        finish(
                            callback,
                            Result.failure(IllegalStateException("The WebExtension has no valid ID."))
                        )
                        return@accept
                    }
                    val stored = StoredWebExtension(
                        id = extension.id,
                        name = extension.metaData?.name?.takeIf { it.isNotBlank() } ?: extension.id,
                        version = extension.metaData?.version?.takeIf { it.isNotBlank() }
                            ?: "unknown",
                        sourceUrl = normalizedLocation
                    )
                    if (!repository.save(stored)) {
                        activeController.uninstall(extension).accept(
                            {
                                finish(
                                    callback,
                                    Result.failure(
                                        IllegalStateException(
                                            "Unable to save the WebExtension configuration."
                                        )
                                    )
                                )
                            },
                            { uninstallException ->
                                Log.e(
                                    TAG,
                                    "Unable to roll back an unsaved WebExtension.",
                                    uninstallException
                                )
                                finish(
                                    callback,
                                    Result.failure(
                                        IllegalStateException(
                                            "Unable to save the WebExtension configuration.",
                                            uninstallException
                                        )
                                    )
                                )
                            }
                        )
                        return@accept
                    }
                    installedExtensions[extension.id] = extension
                    finish(callback, Result.success(stored))
                },
                { exception ->
                    clearRemoteInstall(normalizedLocation)
                    finish(
                        callback,
                        Result.failure(
                            exception ?: IllegalStateException("WebExtension installation failed.")
                        )
                    )
                }
            )
        } catch (exception: IllegalArgumentException) {
            clearRemoteInstall(normalizedLocation)
            finish(callback, Result.failure(exception))
        } catch (exception: IllegalStateException) {
            clearRemoteInstall(normalizedLocation)
            finish(callback, Result.failure(exception))
        } catch (exception: SecurityException) {
            clearRemoteInstall(normalizedLocation)
            finish(callback, Result.failure(exception))
        }
    }

    fun uninstall(
        extension: StoredWebExtension,
        callback: (Result<Unit>) -> Unit
    ) {
        if (extension.id == GLOBAL_EXTENSION_ID) {
            finish(callback, Result.failure(IllegalArgumentException("The built-in extension cannot be removed.")))
            return
        }
        val activeController = try {
            getController()
        } catch (exception: IllegalStateException) {
            finish(callback, Result.failure(exception))
            return
        } catch (exception: SecurityException) {
            finish(callback, Result.failure(exception))
            return
        }
        activeController.list().accept(
            { extensions ->
                val knownExtension = extensions.orEmpty().firstOrNull { it.id == extension.id }
                if (knownExtension == null) {
                    removeStoredExtension(extension, callback)
                    return@accept
                }
                activeController.uninstall(knownExtension).accept(
                    {
                        installedExtensions.remove(extension.id)
                        removeStoredExtension(extension, callback)
                    },
                    { uninstallException ->
                        finish(
                            callback,
                            Result.failure(
                                uninstallException
                                    ?: IllegalStateException("WebExtension removal failed.")
                            )
                        )
                    }
                )
            },
            { exception ->
                finish(
                    callback,
                    Result.failure(
                        exception ?: IllegalStateException("Unable to list WebExtensions.")
                    )
                )
            }
        )
    }

    private fun restoreStoredExtension(
        activeController: WebExtensionController,
        stored: StoredWebExtension
    ) {
        val file = repository.fileFor(stored)
        if (!file.isFile) {
            Log.e(TAG, "Stored WebExtension package is missing: ${stored.id}.")
            repository.remove(stored.id)
            return
        }
        val location = file.toURI().toString()
        restoreExtension(activeController, stored, location, WebExtensionController.INSTALLATION_METHOD_FROM_FILE)
    }

    private fun restoreRemoteExtension(
        activeController: WebExtensionController,
        stored: StoredWebExtension
    ) {
        val location = stored.sourceUrl
            ?: run {
                Log.e(TAG, "Stored remote WebExtension has no source URL: ${stored.id}.")
                return
            }
        restoreExtension(activeController, stored, location, WebExtensionController.INSTALLATION_METHOD_MANAGER)
    }

    private fun restoreExtension(
        activeController: WebExtensionController,
        stored: StoredWebExtension,
        location: String,
        installationMethod: String
    ) {
        pendingInstallLocations.add(location)
        try {
            activeController.install(
                location,
                installationMethod
            ).accept(
                { extension ->
                    pendingInstallLocations.remove(location)
                    if (extension != null) {
                        installedExtensions[extension.id] = extension
                        Log.i(TAG, "Restored WebExtension: ${extension.id}.")
                    }
                },
                { exception ->
                    pendingInstallLocations.remove(location)
                    Log.e(TAG, "Unable to restore WebExtension ${stored.id}.", exception)
                }
            )
        } catch (exception: IllegalArgumentException) {
            pendingInstallLocations.remove(location)
            Log.e(TAG, "Invalid WebExtension location for ${stored.id}.", exception)
        } catch (exception: IllegalStateException) {
            pendingInstallLocations.remove(location)
            Log.e(TAG, "Unable to restore WebExtension ${stored.id}.", exception)
        } catch (exception: SecurityException) {
            pendingInstallLocations.remove(location)
            Log.e(TAG, "WebExtension restore access was denied for ${stored.id}.", exception)
        }
    }

    private fun clearRemoteInstall(location: String) {
        pendingInstallLocations.remove(location)
        synchronized(lock) {
            if (pendingRemoteInstall?.location == location) {
                pendingRemoteInstall = null
            }
        }
    }

    private fun getController(): WebExtensionController {
        return synchronized(lock) {
            controller
        } ?: run {
            runtimeProvider()
            synchronized(lock) {
                requireNotNull(controller) {
                    "GeckoRuntime did not initialize the WebExtension controller."
                }
            }
        }
    }

    private fun finishInstall(
        imported: ImportedWebExtension,
        callback: (Result<StoredWebExtension>) -> Unit,
        result: Result<StoredWebExtension>
    ) {
        if (result.isFailure) {
            repository.discard(imported)
        }
        finish(callback, result)
    }

    private fun removeStoredExtension(
        extension: StoredWebExtension,
        callback: (Result<Unit>) -> Unit
    ) {
        if (repository.remove(extension.id)) {
            finish(callback, Result.success(Unit))
        } else {
            finish(
                callback,
                Result.failure(IllegalStateException("Unable to remove the WebExtension configuration."))
            )
        }
    }

    private fun <T> finish(callback: (Result<T>) -> Unit, result: Result<T>) {
        mainHandler.post {
            callback(result)
        }
    }

    private companion object {
        const val TAG = "WebExtensionManager"
        const val GLOBAL_EXTENSION_ID = "global-extension@gwamanager.local"
    }

    private data class PendingRemoteInstall(
        val location: String,
        val prompt: WebExtensionInstallPrompt
    )
}
