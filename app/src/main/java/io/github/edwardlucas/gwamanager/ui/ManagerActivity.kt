package io.github.edwardlucas.gwamanager.ui

import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import io.github.edwardlucas.gwamanager.GwaManagerApplication
import io.github.edwardlucas.gwamanager.R
import io.github.edwardlucas.gwamanager.data.UserAgentMode
import io.github.edwardlucas.gwamanager.data.WebAppConfig
import io.github.edwardlucas.gwamanager.data.WebAppUrlValidator
import io.github.edwardlucas.gwamanager.data.ImportedWebExtension
import io.github.edwardlucas.gwamanager.data.InvalidWebExtensionException
import io.github.edwardlucas.gwamanager.data.StoredWebExtension
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID
import java.io.IOException

class ManagerActivity : AppCompatActivity() {
    private val app: GwaManagerApplication
        get() = application as GwaManagerApplication

    private lateinit var webAppList: LinearLayout
    private lateinit var extensionList: LinearLayout
    private val extensionPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let(::importExtension)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyTaskIdentity()
        setContentView(createContentView())
        renderExtensions()
        renderWebApps()
    }

    override fun onResume() {
        super.onResume()
        if (::webAppList.isInitialized) {
            renderExtensions()
            renderWebApps()
        }
    }

    private fun createContentView(): View {
        val surfaceColor = color(com.google.android.material.R.attr.colorSurface)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surfaceColor)
        }
        val toolbar = MaterialToolbar(this).apply {
            title = getString(R.string.manager_title)
            setTitleTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            setBackgroundColor(surfaceColor)
            elevation = dimension(R.dimen.card_elevation).toFloat()
        }
        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dimension(R.dimen.toolbar_height)
            )
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimension(R.dimen.screen_padding),
                dimension(R.dimen.screen_padding),
                dimension(R.dimen.screen_padding),
                dimension(R.dimen.content_bottom_padding)
            )
        }
        content.addView(
            createSectionHeader(R.string.extensions_title, R.string.extensions_description)
        )
        val extensionActions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val addExtensionButton = createFilledButton(R.string.add_extension) {
            extensionPicker.launch(EXTENSION_MIME_TYPES)
        }
        val browseAmoButton = createOutlinedButton(R.string.browse_amo_extensions) {
            startActivity(ExtensionStoreActivity.createIntent(this@ManagerActivity))
        }
        extensionActions.addView(addExtensionButton, actionButtonLayoutParams())
        extensionActions.addView(
            browseAmoButton,
            actionButtonLayoutParams().apply {
                marginStart = dimension(R.dimen.button_spacing)
            }
        )
        content.addView(
            extensionActions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        extensionList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(
            extensionList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        content.addView(
            createSectionHeader(R.string.web_apps_title, R.string.web_apps_description),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimension(R.dimen.section_spacing)
            }
        )
        val addWebAppButton = createFilledButton(R.string.add_web_app) {
            showEditor(existing = null)
        }
        content.addView(
            addWebAppButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        webAppList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                0,
                dimension(R.dimen.list_item_spacing),
                0,
                dimension(R.dimen.list_item_spacing)
            )
        }
        content.addView(
            webAppList,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val scrollView = ScrollView(this)
        scrollView.addView(
            content,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        scrollView.isFillViewport = true
        root.addView(scrollView, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))
        val licenseLink = TextView(this).apply {
            text = getString(R.string.open_source_licenses)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(color(com.google.android.material.R.attr.colorPrimary))
            gravity = Gravity.CENTER
            paint.isUnderlineText = true
            setPadding(
                dimension(R.dimen.screen_padding),
                dimension(R.dimen.button_spacing),
                dimension(R.dimen.screen_padding),
                dimension(R.dimen.button_spacing)
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { openLicenseLink() }
        }
        root.addView(
            licenseLink,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val toolbarParams = toolbar.layoutParams as LinearLayout.LayoutParams
            if (toolbarParams.topMargin != systemBars.top) {
                toolbarParams.topMargin = systemBars.top
                toolbar.layoutParams = toolbarParams
            }
            val scrollParams = scrollView.layoutParams as LinearLayout.LayoutParams
            if (scrollParams.bottomMargin != 0) {
                scrollParams.bottomMargin = 0
                scrollView.layoutParams = scrollParams
            }
            val footerBottomPadding = dimension(R.dimen.button_spacing) + systemBars.bottom
            if (licenseLink.paddingBottom != footerBottomPadding) {
                licenseLink.setPadding(
                    licenseLink.paddingLeft,
                    licenseLink.paddingTop,
                    licenseLink.paddingRight,
                    footerBottomPadding
                )
            }
            insets
        }
        ViewCompat.requestApplyInsets(root)
        return root
    }

    private fun renderExtensions() {
        extensionList.removeAllViews()
        val extensions = app.webExtensionRepository.getAll()
        if (extensions.isEmpty()) {
            extensionList.addView(createEmptyState(R.string.empty_extensions))
            return
        }

        extensions.forEach { extension ->
            extensionList.addView(createExtensionItem(extension))
        }
    }

    private fun createExtensionItem(extension: StoredWebExtension): View {
        val item = createCard()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.list_item_padding)
            )
        }
        content.addView(
            TextView(this).apply {
                text = extension.name
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
            }
        )
        content.addView(
            TextView(this).apply {
                text = getString(R.string.extension_version, extension.version)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dimension(R.dimen.button_spacing), 0, 0)
            }
        )
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        actions.addView(
            createOutlinedButton(R.string.remove_extension) {
                confirmRemoveExtension(extension)
            }
        )
        content.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimension(R.dimen.button_spacing)
            }
        )
        item.addView(content)
        return item.apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dimension(R.dimen.list_item_spacing)
            layoutParams = params
        }
    }

    private fun renderWebApps() {
        webAppList.removeAllViews()
        val configs = app.webAppRepository.getAll()
        if (configs.isEmpty()) {
            webAppList.addView(createEmptyState(R.string.empty_web_apps))
            return
        }

        configs.forEach { config ->
            webAppList.addView(createWebAppItem(config))
        }
    }

    private fun createWebAppItem(config: WebAppConfig): View {
        val item = createCard().apply {
            setOnClickListener { openWebApp(config) }
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.list_item_padding)
            )
        }

        val title = TextView(this).apply {
            text = config.name
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
        }
        content.addView(title)

        val url = TextView(this).apply {
            text = config.url
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dimension(R.dimen.button_spacing), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        content.addView(url)

        val mode = TextView(this).apply {
            text = getString(
                if (config.userAgentMode == UserAgentMode.DESKTOP) {
                    R.string.desktop_user_agent
                } else {
                    R.string.mobile_user_agent
                }
            )
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(color(com.google.android.material.R.attr.colorOnPrimaryContainer))
            setPadding(
                dimension(R.dimen.button_spacing) * 2,
                dimension(R.dimen.button_spacing),
                dimension(R.dimen.button_spacing) * 2,
                dimension(R.dimen.button_spacing)
            )
            background = GradientDrawable().apply {
                setColor(color(com.google.android.material.R.attr.colorPrimaryContainer))
                cornerRadius = dimension(R.dimen.card_corner_radius).toFloat()
            }
        }
        content.addView(
            mode,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimension(R.dimen.button_spacing)
            }
        )

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }
        val launcherSupported = app.webAppShortcutManager.supportsPinnedShortcuts()
        val launcherPinned = app.webAppShortcutManager.isPinned(config.id)
        val launcherButton = createOutlinedButton(
            when {
                !launcherSupported -> R.string.launcher_add_not_supported
                launcherPinned -> R.string.launcher_added
                else -> R.string.add_to_launcher
            }
        ) {
            if (app.webAppShortcutManager.requestPinnedShortcut(config)) {
                Toast.makeText(
                    this@ManagerActivity,
                    R.string.launcher_add_requested,
                    Toast.LENGTH_SHORT
                ).show()
            } else {
                Toast.makeText(
                    this@ManagerActivity,
                    if (app.webAppShortcutManager.supportsPinnedShortcuts()) {
                        R.string.launcher_add_failed
                    } else {
                        R.string.launcher_add_not_supported
                    },
                    Toast.LENGTH_LONG
                ).show()
            }
            renderWebApps()
        }.apply {
            isEnabled = launcherSupported && !launcherPinned
        }
        actions.addView(launcherButton)
        val editButton = createOutlinedButton(R.string.edit) {
            showEditor(config)
        }
        val deleteButton = createOutlinedButton(R.string.delete) {
            confirmDelete(config)
        }
        actions.addView(editButton)
        actions.addView(deleteButton)
        content.addView(
            actions,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dimension(R.dimen.list_item_spacing)
            }
        )
        item.addView(content)
        return item.apply {
            val params = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = dimension(R.dimen.list_item_spacing)
            layoutParams = params
        }
    }

    private fun createSectionHeader(titleResId: Int, descriptionResId: Int): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                TextView(this@ManagerActivity).apply {
                    text = getString(titleResId)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurface))
                }
            )
            addView(
                TextView(this@ManagerActivity).apply {
                    text = getString(descriptionResId)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
                    setPadding(0, dimension(R.dimen.button_spacing), 0, dimension(R.dimen.list_item_spacing))
                }
            )
        }
    }

    private fun createEmptyState(textResId: Int): TextView {
        return TextView(this).apply {
            text = getString(textResId)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(
                dimension(R.dimen.button_spacing),
                dimension(R.dimen.list_item_padding),
                dimension(R.dimen.button_spacing),
                dimension(R.dimen.list_item_padding)
            )
        }
    }

    private fun createCard(): MaterialCardView {
        return MaterialCardView(this).apply {
            setCardBackgroundColor(
                color(com.google.android.material.R.attr.colorSurfaceContainerLow)
            )
            strokeColor = color(com.google.android.material.R.attr.colorOutlineVariant)
            strokeWidth = dimension(R.dimen.card_stroke_width)
            radius = dimension(R.dimen.card_corner_radius).toFloat()
            cardElevation = dimension(R.dimen.card_elevation).toFloat()
            isClickable = true
            isFocusable = true
        }
    }

    private fun createFilledButton(textResId: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(this).apply {
            text = getString(textResId)
            setOnClickListener { onClick() }
        }
    }

    private fun createOutlinedButton(textResId: Int, onClick: () -> Unit): MaterialButton {
        return MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = getString(textResId)
            setOnClickListener { onClick() }
        }
    }

    private fun actionButtonLayoutParams(): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f
        )
    }

    private fun showEditor(existing: WebAppConfig?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                dimension(R.dimen.list_item_padding),
                0,
                dimension(R.dimen.list_item_padding),
                0
            )
        }
        val nameInput = EditText(this).apply {
            hint = getString(R.string.web_app_name)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setText(existing?.name.orEmpty())
        }
        val urlInput = EditText(this).apply {
            hint = getString(R.string.web_app_url)
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(existing?.url.orEmpty())
        }
        form.addView(nameInput)
        form.addView(urlInput)

        val modeLabel = TextView(this).apply {
            text = getString(R.string.user_agent_mode)
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(0, dimension(R.dimen.list_item_spacing), 0, 0)
        }
        form.addView(modeLabel)

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
        }
        val mobile = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.mobile_user_agent)
        }
        val desktop = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.desktop_user_agent)
        }
        modeGroup.addView(mobile)
        modeGroup.addView(desktop)
        modeGroup.check(
            if (existing?.userAgentMode == UserAgentMode.DESKTOP) {
                desktop.id
            } else {
                mobile.id
            }
        )
        form.addView(modeGroup)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.add_web_app else R.string.edit_web_app)
            .setView(form)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.save, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString().trim()
                if (name.isEmpty()) {
                    nameInput.error = getString(R.string.name_required)
                    return@setOnClickListener
                }

                val url = WebAppUrlValidator.validate(urlInput.text.toString())
                if (url == null) {
                    urlInput.error = getString(
                        if (urlInput.text.toString().trim().isEmpty()) {
                            R.string.url_required
                        } else {
                            R.string.url_invalid
                        }
                    )
                    return@setOnClickListener
                }

                val mode = if (modeGroup.checkedRadioButtonId == desktop.id) {
                    UserAgentMode.DESKTOP
                } else {
                    UserAgentMode.MOBILE
                }
                val config = existing?.copy(
                    name = name,
                    url = url,
                    userAgentMode = mode
                ) ?: WebAppConfig(
                    id = UUID.randomUUID().toString(),
                    name = name,
                    url = url,
                    userAgentMode = mode
                )
                if (!app.webAppRepository.save(config)) {
                    Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show()
                    return@setOnClickListener
                }
                app.webAppShortcutManager.sync(app.webAppRepository.getAll())
                if (existing == null) {
                    app.webAppShortcutManager.requestPinnedShortcut(config)
                }
                dialog.dismiss()
                renderWebApps()
            }
        }
        dialog.show()
    }

    private fun importExtension(uri: Uri) {
        val imported = try {
            app.webExtensionRepository.importPackage(uri)
        } catch (exception: InvalidWebExtensionException) {
            Toast.makeText(this, R.string.extension_file_invalid, Toast.LENGTH_LONG).show()
            return
        } catch (exception: IOException) {
            Toast.makeText(this, R.string.extension_file_unavailable, Toast.LENGTH_LONG).show()
            return
        } catch (exception: SecurityException) {
            Toast.makeText(this, R.string.extension_file_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        showExtensionConfirmation(imported)
    }

    private fun showExtensionConfirmation(imported: ImportedWebExtension) {
        val requestedPermissions = (imported.requiredPermissions + imported.requiredOrigins)
            .distinct()
        val permissionText = if (requestedPermissions.isEmpty()) {
            getString(R.string.extension_no_permissions)
        } else {
            requestedPermissions.joinToString(separator = "\n") { permission -> "- $permission" }
        }
        val message = getString(
            R.string.extension_install_message,
            imported.version,
            permissionText
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(imported.name)
            .setMessage(message)
            .setNegativeButton(R.string.cancel) { _, _ ->
                app.webExtensionRepository.discard(imported)
            }
            .setPositiveButton(R.string.install_extension) { _, _ ->
                installExtension(imported)
            }
            .setOnCancelListener {
                app.webExtensionRepository.discard(imported)
            }
            .show()
    }

    private fun installExtension(imported: ImportedWebExtension) {
        app.webExtensionManager.install(imported) { result ->
            result.fold(
                onSuccess = {
                    renderExtensions()
                    Toast.makeText(
                        this,
                        R.string.extension_install_succeeded,
                        Toast.LENGTH_SHORT
                    ).show()
                },
                onFailure = {
                    Toast.makeText(
                        this,
                        R.string.extension_install_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            )
        }
    }

    private fun confirmRemoveExtension(extension: StoredWebExtension) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.remove_extension_title)
            .setMessage(R.string.remove_extension_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.remove_extension) { _, _ ->
                app.webExtensionManager.uninstall(extension) { result ->
                    result.fold(
                        onSuccess = {
                            renderExtensions()
                            Toast.makeText(
                                this,
                                R.string.extension_removed,
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onFailure = {
                            Toast.makeText(
                                this,
                                R.string.extension_remove_failed,
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    )
                }
            }
            .show()
    }

    private fun confirmDelete(config: WebAppConfig) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.delete_web_app_title)
            .setMessage(R.string.delete_web_app_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                if (app.webAppRepository.delete(config.id)) {
                    app.sessionManager.close(config.id)
                    app.webAppShortcutManager.remove(config.id)
                    renderWebApps()
                } else {
                    Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun openWebApp(config: WebAppConfig) {
        startActivity(WebAppActivity.createIntent(this, config.id))
    }

    private fun openLicenseLink() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MPL_LICENSE_URL)))
        } catch (exception: ActivityNotFoundException) {
            Toast.makeText(this, R.string.license_link_unavailable, Toast.LENGTH_LONG).show()
        } catch (exception: SecurityException) {
            Toast.makeText(this, R.string.license_link_unavailable, Toast.LENGTH_LONG).show()
        }
    }

    private fun applyTaskIdentity() {
        val icon = requireNotNull(
            AppCompatResources.getDrawable(this, R.mipmap.ic_launcher)
        )
        val iconBitmap = Bitmap.createBitmap(
            MANAGER_TASK_ICON_SIZE,
            MANAGER_TASK_ICON_SIZE,
            Bitmap.Config.ARGB_8888
        )
        Canvas(iconBitmap).apply {
            icon.setBounds(0, 0, width, height)
            icon.draw(this)
        }
        setTaskDescription(
            ActivityManager.TaskDescription(
                getString(R.string.manager_title),
                iconBitmap
            )
        )
    }

    private fun dimension(resourceId: Int): Int {
        return resources.getDimensionPixelSize(resourceId)
    }

    private fun color(attributeResId: Int): Int {
        return MaterialColors.getColor(this, attributeResId, 0)
    }

    private companion object {
        const val MANAGER_TASK_ICON_SIZE = 512
        const val MPL_LICENSE_URL = "https://www.mozilla.org/en-US/MPL/2.0/"
        val EXTENSION_MIME_TYPES = arrayOf(
            "application/x-xpinstall",
            "application/zip",
            "application/octet-stream"
        )
    }
}
