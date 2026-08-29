package io.github.edwardlucas.gwamanager.ui

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.edwardlucas.gwamanager.GwaManagerApplication
import io.github.edwardlucas.gwamanager.R
import io.github.edwardlucas.gwamanager.browser.MediaPlaybackController
import io.github.edwardlucas.gwamanager.data.UserAgentMode
import io.github.edwardlucas.gwamanager.data.WebAppConfig
import io.github.edwardlucas.gwamanager.data.WebAppUrlValidator
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.MediaSession
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebRequestError
import java.lang.ref.WeakReference

class WebAppActivity : AppCompatActivity() {
    private val app: GwaManagerApplication
        get() = application as GwaManagerApplication

    private lateinit var geckoView: GeckoView
    private lateinit var contentRoot: FrameLayout
    private var session: GeckoSession? = null
    private var webAppId: String? = null
    private var activeUrl: String? = null
    private var activeUserAgentMode: UserAgentMode? = null
    private var lastNormalTopInset = 0
    private var isWebContentFullscreen = false
    private var pendingNotificationPermission: GeckoResult<Int>? = null
    private var isActivityStarted = false
    private var mediaNotificationPermissionQueued = false
    private var mediaNotificationPermissionRequestInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()

        val requestedWebAppId = intent.getStringExtra(EXTRA_WEB_APP_ID)
        if (requestedWebAppId.isNullOrBlank()) {
            showMissingWebApp()
            return
        }
        val config = app.webAppRepository.findById(requestedWebAppId)
        if (config == null) {
            showMissingWebApp()
            return
        }
        if (WebAppUrlValidator.validate(config.url) == null) {
            showInvalidWebAppUrl()
            return
        }

        geckoView = GeckoView(this)
        val root = FrameLayout(this)
        contentRoot = root
        root.addView(
            geckoView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            if (!isWebContentFullscreen) {
                lastNormalTopInset = systemBars.top
            }
            applyGeckoViewInsets(systemBars)
            insets
        }
        setContentView(root)
        ViewCompat.requestApplyInsets(root)
        attachWebApp(config)

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
                            Log.e(TAG, "Unable to process web content back press.", exception)
                            if (!isFinishing) {
                                finish()
                            }
                        }
                    )
                }
            }
        )
        handleNotificationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val requestedWebAppId = intent.getStringExtra(EXTRA_WEB_APP_ID)
        if (!requestedWebAppId.isNullOrBlank()) {
            val config = app.webAppRepository.findById(requestedWebAppId)
            if (config == null) {
                showMissingWebApp()
                return
            }
            if (WebAppUrlValidator.validate(config.url) == null) {
                showInvalidWebAppUrl()
                return
            }
            if (requestedWebAppId != webAppId || sessionNeedsRefresh(config)) {
                attachWebApp(config)
            } else {
                applyTaskIdentity(config)
                requestTaskIcon(config)
            }
        }
        handleNotificationIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        isActivityStarted = true
        webAppId?.let { id ->
            app.webAppRepository.findById(id)?.let { config ->
                if (sessionNeedsRefresh(config)) {
                    attachWebApp(config)
                }
                session?.setActive(true)
                app.webNotificationBridge.setActiveWebApp(id)
                applyTaskIdentity(config)
                requestTaskIcon(config)
            }
        }
        if (MediaPlaybackController.snapshot().webAppId != null) {
            ensureMediaNotificationPermission()
        }
    }

    override fun onStop() {
        isActivityStarted = false
        session?.setActive(false)
        super.onStop()
    }

    override fun onDestroy() {
        pendingNotificationPermission?.complete(
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
        )
        pendingNotificationPermission = null
        isActivityStarted = false
        mediaNotificationPermissionQueued = false
        mediaNotificationPermissionRequestInFlight = false
        session?.setActive(false)
        if (::geckoView.isInitialized) {
            geckoView.releaseSession()
        }
        super.onDestroy()
    }

    private fun attachWebApp(config: WebAppConfig) {
        if (isWebContentFullscreen) {
            setWebContentFullscreen(false)
        }
        pendingNotificationPermission?.complete(
            GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
        )
        pendingNotificationPermission = null
        session?.setActive(false)
        if (::geckoView.isInitialized) {
            geckoView.releaseSession()
        }

        val currentSession = app.sessionManager.getOrCreate(
            webAppId = config.id,
            userAgentMode = config.userAgentMode,
            runtime = app.getGeckoRuntime()
        )
        currentSession.setContentDelegate(createContentDelegate())
        currentSession.setPermissionDelegate(createPermissionDelegate())
        currentSession.setNavigationDelegate(createNavigationDelegate())
        currentSession.setMediaSessionDelegate(createMediaSessionDelegate(config.id))
        currentSession.setActive(true)
        session = currentSession
        webAppId = config.id
        activeUrl = config.url
        activeUserAgentMode = config.userAgentMode
        applyTaskIdentity(config)
        requestTaskIcon(config)
        geckoView.setSession(currentSession)
        app.sessionManager.loadUrl(
            webAppId = config.id,
            userAgentMode = config.userAgentMode,
            url = config.url,
            session = currentSession
        )
    }

    private fun sessionNeedsRefresh(config: WebAppConfig): Boolean {
        return webAppId != config.id ||
            activeUrl != config.url ||
            activeUserAgentMode != config.userAgentMode
    }

    private fun showMissingWebApp() {
        Toast.makeText(this, R.string.web_app_not_found, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun showInvalidWebAppUrl() {
        Toast.makeText(this, R.string.web_app_url_invalid, Toast.LENGTH_LONG).show()
        finish()
    }

    private fun applyGeckoViewInsets(systemBars: androidx.core.graphics.Insets) {
        val topInset = if (isWebContentFullscreen) 0 else lastNormalTopInset
        val layoutParams = geckoView.layoutParams as FrameLayout.LayoutParams
        if (layoutParams.topMargin != topInset ||
            layoutParams.bottomMargin != systemBars.bottom
        ) {
            layoutParams.setMargins(0, topInset, 0, systemBars.bottom)
            geckoView.layoutParams = layoutParams
        }
    }

    private fun setWebContentFullscreen(fullscreen: Boolean) {
        if (isWebContentFullscreen == fullscreen) {
            return
        }
        isWebContentFullscreen = fullscreen

        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        insetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (fullscreen) {
            insetsController.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            insetsController.show(WindowInsetsCompat.Type.statusBars())
        }

        val currentInsets = ViewCompat.getRootWindowInsets(contentRoot)
        currentInsets?.let {
            applyGeckoViewInsets(it.getInsets(WindowInsetsCompat.Type.systemBars()))
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    private fun createPermissionDelegate(): GeckoSession.PermissionDelegate {
        val activityReference = WeakReference(this)
        return object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(
                session: GeckoSession,
                permission: GeckoSession.PermissionDelegate.ContentPermission
            ): GeckoResult<Int>? {
                val activity = activityReference.get()
                    ?: return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                if (permission.permission != GeckoSession.PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION) {
                    return null
                }
                if (permission.value == GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY) {
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                    )
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                ) {
                    return GeckoResult.fromValue(
                        GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                    )
                }

                activity.pendingNotificationPermission?.let { return it }
                return GeckoResult<Int>().also { result ->
                    activity.pendingNotificationPermission = result
                    activity.requestPermissions(
                        arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                        REQUEST_NOTIFICATION_PERMISSION
                    )
                }
            }

        }
    }

    private fun createMediaSessionDelegate(webAppId: String): MediaSession.Delegate {
        val appContext = applicationContext
        val activityReference = WeakReference(this)
        return object : MediaSession.Delegate {
            override fun onActivated(
                session: GeckoSession,
                mediaSession: MediaSession
            ) {
                MediaPlaybackController.onActivated(appContext, webAppId, mediaSession)
                activityReference.get()?.let { activity ->
                    activity.runOnUiThread {
                        activity.ensureMediaNotificationPermission()
                    }
                }
            }

            override fun onDeactivated(
                session: GeckoSession,
                mediaSession: MediaSession
            ) {
                MediaPlaybackController.onDeactivated(appContext, mediaSession)
            }

            override fun onMetadata(
                session: GeckoSession,
                mediaSession: MediaSession,
                metadata: MediaSession.Metadata
            ) {
                val generation = MediaPlaybackController.onMetadata(appContext, mediaSession, metadata)
                if (generation < 0L) {
                    return
                }
                metadata.artwork?.getBitmap(MEDIA_ARTWORK_SIZE)?.accept(
                    { bitmap ->
                        bitmap?.let {
                            MediaPlaybackController.onArtwork(
                                appContext,
                                mediaSession,
                                generation,
                                it
                            )
                        }
                    },
                    { exception ->
                        Log.w(TAG, "Unable to load WebApp media artwork.", exception)
                    }
                )
            }

            override fun onFeatures(
                session: GeckoSession,
                mediaSession: MediaSession,
                features: Long
            ) {
                MediaPlaybackController.onFeatures(appContext, mediaSession, features)
            }

            override fun onPositionState(
                session: GeckoSession,
                mediaSession: MediaSession,
                state: MediaSession.PositionState
            ) {
                MediaPlaybackController.onPositionState(appContext, mediaSession, state)
            }

            override fun onPlay(session: GeckoSession, mediaSession: MediaSession) {
                MediaPlaybackController.onPlay(appContext, mediaSession)
            }

            override fun onPause(session: GeckoSession, mediaSession: MediaSession) {
                MediaPlaybackController.onPause(appContext, mediaSession)
            }

            override fun onStop(session: GeckoSession, mediaSession: MediaSession) {
                MediaPlaybackController.onStop(appContext, mediaSession)
            }
        }
    }

    private fun createNavigationDelegate(): GeckoSession.NavigationDelegate {
        val activityReference = WeakReference(this)
        return object : GeckoSession.NavigationDelegate {
            override fun onLoadRequest(
                session: GeckoSession,
                request: GeckoSession.NavigationDelegate.LoadRequest
            ): GeckoResult<AllowOrDeny>? {
                if (request.isRedirect) {
                    Log.d(TAG, "WebApp redirect: ${request.triggerUri} -> ${request.uri}")
                }
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    Log.w(TAG, "WebApp requested a new window for ${request.uri}.")
                }
                return null
            }

            override fun onLoadError(
                session: GeckoSession,
                uri: String?,
                error: WebRequestError
            ): GeckoResult<String>? {
                app.sessionManager.markLoadFailed(session)
                Log.e(
                    TAG,
                    "WebApp page load failed for $uri " +
                        "(code=${error.code}, category=${error.category}).",
                    error
                )
                val activity = activityReference.get() ?: return null
                val messageResId = when (error.code) {
                    WebRequestError.ERROR_UNKNOWN_HOST,
                    WebRequestError.ERROR_NET_TIMEOUT,
                    WebRequestError.ERROR_CONNECTION_REFUSED,
                    WebRequestError.ERROR_NET_INTERRUPT,
                    WebRequestError.ERROR_NET_RESET,
                    WebRequestError.ERROR_OFFLINE -> R.string.network_error

                    WebRequestError.ERROR_SECURITY_SSL,
                    WebRequestError.ERROR_SECURITY_BAD_CERT,
                    WebRequestError.ERROR_BAD_HSTS_CERT -> R.string.secure_connection_failed

                    WebRequestError.ERROR_MALFORMED_URI,
                    WebRequestError.ERROR_UNKNOWN_PROTOCOL,
                    WebRequestError.ERROR_PORT_BLOCKED -> R.string.url_unavailable

                    else -> R.string.page_load_failed
                }
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        messageResId,
                        Toast.LENGTH_LONG
                    ).show()
                }
                return null
            }
        }
    }

    private fun createContentDelegate(): GeckoSession.ContentDelegate {
        val activityReference = WeakReference(this)
        return object : GeckoSession.ContentDelegate {
            override fun onCrash(session: GeckoSession) {
                Log.e(TAG, "GeckoSession crashed.")
                val activity = activityReference.get() ?: return
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        R.string.web_content_crashed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onKill(session: GeckoSession) {
                Log.e(TAG, "GeckoSession was killed.")
                val activity = activityReference.get() ?: return
                activity.runOnUiThread {
                    Toast.makeText(
                        activity,
                        R.string.web_content_unavailable,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }

            override fun onCloseRequest(session: GeckoSession) {
                val activity = activityReference.get() ?: return
                activity.runOnUiThread {
                    if (!activity.isFinishing) {
                        activity.finish()
                    }
                }
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                val activity = activityReference.get() ?: return
                activity.runOnUiThread {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        activity.setWebContentFullscreen(fullScreen)
                    }
                }
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            val mediaPermissionWasWaiting =
                mediaNotificationPermissionQueued || mediaNotificationPermissionRequestInFlight
            mediaNotificationPermissionQueued = false
            mediaNotificationPermissionRequestInFlight = false
            val result = pendingNotificationPermission
            pendingNotificationPermission = null
            val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            result?.complete(
                if (granted) {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW
                } else {
                    GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY
                }
            )
            if (granted) {
                MediaPlaybackController.refresh(applicationContext)
            } else if (mediaPermissionWasWaiting) {
                Toast.makeText(
                    this,
                    R.string.media_notification_permission_required,
                    Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    private fun ensureMediaNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            mediaNotificationPermissionQueued = false
            return
        }

        mediaNotificationPermissionQueued = true
        if (!isActivityStarted ||
            isFinishing ||
            isDestroyed ||
            pendingNotificationPermission != null ||
            mediaNotificationPermissionRequestInFlight
        ) {
            return
        }

        mediaNotificationPermissionRequestInFlight = true
        requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATION_PERMISSION
        )
    }

    private fun handleNotificationIntent(intent: Intent) {
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, INVALID_NOTIFICATION_ID)
        if (notificationId != INVALID_NOTIFICATION_ID) {
            app.webNotificationBridge.click(notificationId)
            intent.removeExtra(EXTRA_NOTIFICATION_ID)
        }
    }

    private fun requestTaskIcon(config: WebAppConfig) {
        val activityReference = WeakReference(this)
        app.webAppShortcutManager.requestIcon(config) { bitmap ->
            val activity = activityReference.get() ?: return@requestIcon
            if (bitmap == null ||
                activity.isFinishing ||
                activity.isDestroyed ||
                activity.webAppId != config.id
            ) {
                return@requestIcon
            }
            val currentConfig = activity.app.webAppRepository.findById(config.id)
                ?: return@requestIcon
            if (currentConfig.url != config.url ||
                currentConfig.userAgentMode != config.userAgentMode
            ) {
                return@requestIcon
            }
            activity.applyTaskIdentity(currentConfig, bitmap)
        }
    }

    private fun applyTaskIdentity(config: WebAppConfig, iconBitmap: Bitmap? = null) {
        title = config.name
        val taskDescription = if (iconBitmap == null) {
            ActivityManager.TaskDescription(config.name)
        } else {
            ActivityManager.TaskDescription(config.name, iconBitmap)
        }
        setTaskDescription(taskDescription)
    }

    companion object {
        private const val TAG = "WebAppActivity"
        private const val EXTRA_WEB_APP_ID = "web_app_id"
        const val EXTRA_NOTIFICATION_ID = "notification_id"
        private const val REQUEST_NOTIFICATION_PERMISSION = 1001
        private const val INVALID_NOTIFICATION_ID = -1
        private const val MEDIA_ARTWORK_SIZE = 512
        private const val WEB_APP_URI_SCHEME = "gwamanager"
        private const val WEB_APP_URI_HOST = "webapp"

        fun createIntent(
            context: Context,
            webAppId: String,
            notificationId: Int? = null
        ): Intent {
            return Intent(context, WebAppActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                data = Uri.Builder()
                    .scheme(WEB_APP_URI_SCHEME)
                    .authority(WEB_APP_URI_HOST)
                    .appendPath(webAppId)
                    .build()
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_DOCUMENT or
                        Intent.FLAG_ACTIVITY_RETAIN_IN_RECENTS
                )
                putExtra(EXTRA_WEB_APP_ID, webAppId)
                notificationId?.let { putExtra(EXTRA_NOTIFICATION_ID, it) }
            }
        }

        fun createPendingIntent(context: Context, webAppId: String): android.app.PendingIntent {
            return android.app.PendingIntent.getActivity(
                context,
                webAppId.hashCode(),
                createIntent(context, webAppId),
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    android.app.PendingIntent.FLAG_IMMUTABLE
            )
        }
    }
}
