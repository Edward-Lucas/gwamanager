package io.github.edwardlucas.gwamanager.ui

import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.edwardlucas.gwamanager.GwaManagerApplication
import io.github.edwardlucas.gwamanager.R
import io.github.edwardlucas.gwamanager.browser.UserAgentProvider
import io.github.edwardlucas.gwamanager.browser.WebExtensionInstallPrompt
import io.github.edwardlucas.gwamanager.data.AmoExtensionUrlValidator
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebResponse

class ExtensionStoreActivity : AppCompatActivity() {
    private val app: GwaManagerApplication
        get() = application as GwaManagerApplication

    private lateinit var geckoView: GeckoView
    private var session: GeckoSession? = null
    private var installInProgress = false
    private var pendingPrompt: GeckoResult<WebExtension.PermissionPromptResponse>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        geckoView = GeckoView(this)
        val surfaceColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurface,
            0
        )
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.extension_store_title)
            setTitleTextColor(
                MaterialColors.getColor(
                    this@ExtensionStoreActivity,
                    com.google.android.material.R.attr.colorOnSurface,
                    0
                )
            )
            setBackgroundColor(surfaceColor)
            navigationIcon = AppCompatResources.getDrawable(
                this@ExtensionStoreActivity,
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )?.apply {
                setTint(
                    MaterialColors.getColor(
                        this@ExtensionStoreActivity,
                        com.google.android.material.R.attr.colorOnSurface,
                        0
                    )
                )
            }
            navigationContentDescription = getString(R.string.navigate_up)
            setNavigationOnClickListener { finish() }
            elevation = resources.getDimension(R.dimen.card_elevation)
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                resources.getDimensionPixelSize(R.dimen.toolbar_height)
            )
        )

        val content = FrameLayout(this).apply {
            addView(
                geckoView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val toolbarParams = toolbar.layoutParams as LinearLayout.LayoutParams
            if (toolbarParams.topMargin != systemBars.top) {
                toolbarParams.topMargin = systemBars.top
                toolbar.layoutParams = toolbarParams
            }
            val contentParams = content.layoutParams as LinearLayout.LayoutParams
            if (contentParams.bottomMargin != systemBars.bottom) {
                contentParams.bottomMargin = systemBars.bottom
                content.layoutParams = contentParams
            }
            insets
        }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )
        setContentView(root)
        ViewCompat.requestApplyInsets(root)

        val storeSession = GeckoSession(
            GeckoSessionSettings.Builder()
                .userAgentOverride(UserAgentProvider.MOBILE_USER_AGENT)
                .build()
        )
        storeSession.setNavigationDelegate(createNavigationDelegate())
        storeSession.setContentDelegate(createContentDelegate())
        storeSession.open(app.getGeckoRuntime())
        session = storeSession
        geckoView.setSession(storeSession)
        storeSession.loadUri(AMO_URL)

        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    val activeSession = session
                    if (activeSession == null) {
                        finish()
                        return
                    }
                    activeSession.processBackPressed().accept(
                        { handled ->
                            if (handled != true && !isFinishing) {
                                finish()
                            }
                        },
                        { exception ->
                            Log.e(TAG, "Unable to process AMO page back press.", exception)
                            if (!isFinishing) {
                                finish()
                            }
                        }
                    )
                }
            }
        )
    }

    override fun onDestroy() {
        pendingPrompt?.complete(deniedPrompt())
        pendingPrompt = null
        if (::geckoView.isInitialized) {
            geckoView.releaseSession()
        }
        session?.close()
        session = null
        super.onDestroy()
    }

    private fun createNavigationDelegate(): GeckoSession.NavigationDelegate {
        return object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                if (isAmoXpiUri(request.uri)) {
                    startInstall(request.uri)
                    return GeckoResult.fromValue(AllowOrDeny.DENY)
                }
                return null
            }
        }
    }

    private fun createContentDelegate(): GeckoSession.ContentDelegate {
        return object : GeckoSession.ContentDelegate {
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                response.body?.close()
                if (isAmoXpiUri(response.uri)) {
                    startInstall(response.uri)
                }
            }
        }
    }

    private fun startInstall(location: String) {
        if (installInProgress) {
            return
        }
        installInProgress = true
        app.webExtensionManager.installFromUrl(
            location = location,
            prompt = ::showInstallPrompt
        ) { result ->
            runOnUiThread {
                installInProgress = false
                result.fold(
                    onSuccess = {
                        Toast.makeText(
                            this,
                            R.string.extension_install_succeeded,
                            Toast.LENGTH_SHORT
                        ).show()
                    },
                    onFailure = { exception ->
                        Log.e(TAG, "Unable to install the AMO WebExtension.", exception)
                        Toast.makeText(
                            this,
                            R.string.extension_install_failed,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                )
            }
        }
    }

    private fun showInstallPrompt(
        extension: WebExtension,
        permissions: Array<String>,
        origins: Array<String>,
        dataCollectionPermissions: Array<String>
    ): GeckoResult<WebExtension.PermissionPromptResponse> {
        val result = GeckoResult<WebExtension.PermissionPromptResponse>()
        runOnUiThread {
            if (isFinishing || isDestroyed) {
                result.complete(deniedPrompt())
                return@runOnUiThread
            }
            pendingPrompt = result
            val requestedPermissions = (
                permissions.asList() +
                    origins.asList() +
                    dataCollectionPermissions.asList()
                ).distinct()
            val permissionText = if (requestedPermissions.isEmpty()) {
                getString(R.string.extension_no_permissions)
            } else {
                requestedPermissions.joinToString(separator = "\n") { permission -> "- $permission" }
            }
            val version = extension.metaData?.version?.takeIf { it.isNotBlank() } ?: "unknown"
            var completed = false
            fun complete(response: WebExtension.PermissionPromptResponse) {
                if (completed) {
                    return
                }
                completed = true
                if (pendingPrompt === result) {
                    pendingPrompt = null
                }
                result.complete(response)
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(extension.metaData?.name?.takeIf { it.isNotBlank() } ?: extension.id)
                .setMessage(getString(R.string.extension_install_message, version, permissionText))
                .setNegativeButton(R.string.cancel) { _, _ ->
                    complete(deniedPrompt())
                }
                .setPositiveButton(R.string.install_extension) { _, _ ->
                    complete(
                        WebExtension.PermissionPromptResponse(
                            true,
                            false,
                            false
                        )
                    )
                }
                .setOnCancelListener {
                    complete(deniedPrompt())
                }
                .show()
        }
        return result
    }

    private fun deniedPrompt(): WebExtension.PermissionPromptResponse {
        return WebExtension.PermissionPromptResponse(
            false,
            false,
            false
        )
    }

    private fun isAmoXpiUri(location: String): Boolean {
        return AmoExtensionUrlValidator.validate(location) != null
    }

    companion object {
        private const val TAG = "ExtensionStoreActivity"
        private const val AMO_URL = "https://addons.mozilla.org/en-US/android/"

        fun createIntent(context: android.content.Context): android.content.Intent {
            return android.content.Intent(context, ExtensionStoreActivity::class.java)
        }
    }
}
