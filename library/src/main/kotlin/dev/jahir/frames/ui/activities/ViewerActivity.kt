@file:Suppress("DEPRECATION")

package dev.jahir.frames.ui.activities

import android.content.Intent
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.GestureDetector
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
import android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
import android.view.WindowManager
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.fragment.app.DialogFragment
import androidx.palette.graphics.Palette
import com.fondesa.kpermissions.PermissionStatus
import com.google.android.material.navigation.NavigationBarView
import com.ortiz.touchview.TouchImageView
import dev.jahir.frames.R
import dev.jahir.frames.data.Preferences
import dev.jahir.frames.data.models.Wallpaper
import dev.jahir.frames.extensions.context.boolean
import dev.jahir.frames.extensions.context.color
import dev.jahir.frames.extensions.context.compliesWithMinTime
import dev.jahir.frames.extensions.context.findView
import dev.jahir.frames.extensions.context.firstInstallTime
import dev.jahir.frames.extensions.context.isNetworkAvailable
import dev.jahir.frames.extensions.context.isWifiConnected
import dev.jahir.frames.extensions.context.navigationBarLight
import dev.jahir.frames.extensions.context.statusBarLight
import dev.jahir.frames.extensions.context.string
import dev.jahir.frames.extensions.fragments.mdDialog
import dev.jahir.frames.extensions.fragments.message
import dev.jahir.frames.extensions.fragments.positiveButton
import dev.jahir.frames.extensions.fragments.title
import dev.jahir.frames.extensions.frames.buildAuthorTransitionName
import dev.jahir.frames.extensions.frames.buildImageTransitionName
import dev.jahir.frames.extensions.frames.buildTitleTransitionName
import dev.jahir.frames.extensions.resources.asBitmap
import dev.jahir.frames.extensions.resources.hasContent
import dev.jahir.frames.extensions.resources.toReadableTime
import dev.jahir.frames.extensions.utils.MAX_FRAMES_PALETTE_COLORS
import dev.jahir.frames.extensions.utils.bestSwatch
import dev.jahir.frames.extensions.views.gone
import dev.jahir.frames.extensions.views.loadFramesPic
import dev.jahir.frames.extensions.views.setMarginBottom
import dev.jahir.frames.extensions.views.setMarginTop
import dev.jahir.frames.extensions.views.setPaddingLeft
import dev.jahir.frames.extensions.views.setPaddingRight
import dev.jahir.frames.extensions.views.tint
import dev.jahir.frames.extensions.views.visibleIf
import dev.jahir.frames.ui.activities.base.BaseWallpaperApplierActivity
import dev.jahir.frames.ui.fragments.WallpapersFragment
import dev.jahir.frames.ui.fragments.viewer.DetailsFragment
import dev.jahir.frames.ui.fragments.viewer.SetAsOptionsDialog

open class ViewerActivity : BaseWallpaperApplierActivity<Preferences>() {

    override val preferences: Preferences by lazy { Preferences(this) }

    private val toolbar: Toolbar? by findView(R.id.toolbar)
    private val imageView: TouchImageView? by findView(R.id.wallpaper)

    private var firstImageLoad: Boolean = true
    private var transitioned: Boolean = false
    private var closing: Boolean = false
    private var currentWallPosition: Int = 0
    private var favoritesModified: Boolean = false
    private var isInFavorites: Boolean = false
        set(value) {
            field = value
            bottomNavigation?.setSelectedItemId(if (value) R.id.favorites else R.id.details, false)
        }

    private val detailsFragment: DetailsFragment by lazy {
        DetailsFragment.create(shouldShowPaletteDetails = shouldShowWallpapersPalette())
    }

    private var downloadBlockedDialog: AlertDialog? = null
    private var applierDialog: DialogFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        statusBarLight = false
        navigationBarLight = false
        setContentView(R.layout.activity_viewer)
        bottomNavigation?.labelVisibilityMode = NavigationBarView.LABEL_VISIBILITY_LABELED

        supportPostponeEnterTransition()

        currentWallPosition = intent?.extras?.getInt(CURRENT_WALL_POSITION, 0) ?: 0

        val wallpaper =
            intent?.extras?.getParcelable<Wallpaper?>(WallpapersFragment.WALLPAPER_EXTRA)

        if (wallpaper == null) {
            finish()
            return
        }

        if (wallpaper.downloadable == false || !shouldShowDownloadOption())
            bottomNavigation?.removeItem(R.id.download)

        findViewById<View?>(R.id.toolbar_title)?.let {
            (it as? TextView)?.text = wallpaper.name
            ViewCompat.setTransitionName(
                it,
                wallpaper.buildTitleTransitionName(currentWallPosition)
            )
        }
        findViewById<View?>(R.id.toolbar_subtitle)?.let {
            (it as? TextView)?.text = wallpaper.author
            it.visibleIf(wallpaper.author.hasContent())
            ViewCompat.setTransitionName(
                it,
                wallpaper.buildAuthorTransitionName(currentWallPosition)
            )
        }
        imageView?.let {
            ViewCompat.setTransitionName(
                it,
                wallpaper.buildImageTransitionName(currentWallPosition)
            )
        }

        initDownload(wallpaper)
        detailsFragment.wallpaper = wallpaper

        setSupportActionBar(toolbar)
        supportActionBar?.let {
            it.setHomeButtonEnabled(true)
            it.setDisplayHomeAsUpEnabled(true)
            it.setDisplayShowHomeEnabled(true)
        }
        initWindow()
        toolbar?.tint(color(R.color.white))

        imageView?.setOnDoubleTapListener(object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent?): Boolean {
                toggleSystemUI()
                return super.onSingleTapConfirmed(e)
            }
        })
        loadWallpaper(wallpaper)

        isInFavorites =
            intent?.extras?.getBoolean(WallpapersFragment.WALLPAPER_IN_FAVS_EXTRA, false)
                ?: wallpaper.isInFavorites

        wallpapersViewModel.observeFavorites(this) {
            this.isInFavorites = it.any { wall -> wall.url == wallpaper.url }
        }

        bottomNavigation?.setSelectedItemId(
            if (isInFavorites) R.id.favorites else R.id.details,
            false
        )
        loadWallpapersData()

        bottomNavigation?.setOnNavigationItemSelectedListener {
            handleNavigationItemSelected(it.itemId, wallpaper)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(CURRENT_WALL_POSITION, currentWallPosition)
        outState.putBoolean(CLOSING_KEY, closing)
        outState.putBoolean(TRANSITIONED_KEY, transitioned)
        outState.putBoolean(IS_IN_FAVORITES_KEY, isInFavorites)
        outState.putBoolean(FAVORITES_MODIFIED, favoritesModified)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        this.currentWallPosition = savedInstanceState.getInt(CURRENT_WALL_POSITION, 0)
        this.closing = savedInstanceState.getBoolean(CLOSING_KEY, false)
        this.transitioned = savedInstanceState.getBoolean(TRANSITIONED_KEY, false)
        this.isInFavorites = savedInstanceState.getBoolean(IS_IN_FAVORITES_KEY, false)
        this.favoritesModified = savedInstanceState.getBoolean(FAVORITES_MODIFIED, false)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) supportFinishAfterTransition()
        return super.onOptionsItemSelected(item)
    }

    override fun finish() {
        imageView?.setZoom(1F)
        super.finish()
    }

    override fun onFinish() {
        super.onFinish()
        setResult(
            if (favoritesModified) FAVORITES_MODIFIED_RESULT
            else FAVORITES_NOT_MODIFIED_RESULT,
            Intent().apply {
                putExtra(FAVORITES_MODIFIED, favoritesModified)
            }
        )
    }

    private fun dismissApplierDialog() {
        try {
            applierDialog?.dismiss()
        } catch (e: Exception) {
        }
        applierDialog = null
    }

    private fun dismissDownloadBlockedDialog() {
        try {
            downloadBlockedDialog?.dismiss()
        } catch (e: Exception) {
        }
        downloadBlockedDialog = null
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissApplierDialog()
        dismissDownloadBlockedDialog()
    }

    private fun generatePalette(drawable: Drawable? = null) {
        supportStartPostponedEnterTransition()
        findViewById<View?>(R.id.loading)?.gone()
        if (!shouldShowWallpapersPalette()) {
            setBackgroundColor()
            return
        }
        (drawable ?: imageView?.drawable)?.asBitmap()?.let { bitmap ->
            Palette.from(bitmap)
                .maximumColorCount(MAX_FRAMES_PALETTE_COLORS * 2)
                .generate {
                    setBackgroundColor(it?.bestSwatch?.rgb ?: 0)
                    detailsFragment.palette = it
                }
        } ?: run {
            setBackgroundColor()
        }
    }

    private fun setBackgroundColor(@ColorInt color: Int = 0) {
        findViewById<View?>(R.id.activity_root_view)?.setBackgroundColor(color)
    }

    private fun loadWallpaper(wallpaper: Wallpaper?) {
        var placeholder: Drawable? = null
        try {
            openFileInput(SHARED_IMAGE_NAME)?.use {
                placeholder = BitmapDrawable(resources, it)
            }
        } catch (e: Exception) {
        }
        wallpaper?.let {
            imageView?.loadFramesPic(
                wallpaper.url,
                wallpaper.thumbnail,
                placeholder,
                forceLoadFullRes = true,
                cropAsCircle = false,
                saturate = false
            ) {
                if (firstImageLoad) {
                    firstImageLoad = false
                    imageView?.resetZoomAnimated()
                }
                generatePalette(it)
            }
        }
        supportStartPostponedEnterTransition()
    }

    private fun initWindow() {
        window.decorView.systemUiVisibility =
            SYSTEM_UI_FLAG_LAYOUT_STABLE or SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)

        val params: WindowManager.LayoutParams = window.attributes
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS.inv()
        window.attributes = params

        appbar?.let { appbar ->
            ViewCompat.setOnApplyWindowInsetsListener(appbar) { _, insets ->
                appbar.setMarginTop(insets.systemWindowInsetTop)
                appbar.setPaddingLeft(
                    if (boolean(R.bool.is_landscape)) insets.systemWindowInsetLeft
                    else 0
                )
                appbar.setPaddingRight(
                    if (boolean(R.bool.is_landscape)) insets.systemWindowInsetRight
                    else 0
                )
                insets
            }
        }

        bottomNavigation?.let { bottomNavigation ->
            ViewCompat.setOnApplyWindowInsetsListener(bottomNavigation) { _, insets ->
                bottomNavigation.setMarginBottom(insets.systemWindowInsetBottom)
                insets
            }
        }

        window.statusBarColor = color(R.color.viewer_bars_colors)
        window.navigationBarColor = color(R.color.viewer_bars_colors)
    }

    open fun handleNavigationItemSelected(itemId: Int, wallpaper: Wallpaper?): Boolean {
        wallpaper ?: return false
        when (itemId) {
            R.id.details -> detailsFragment.show(this, "DETAILS_FRAG")
            R.id.download -> checkForDownload()
            R.id.apply -> applyWallpaper(wallpaper)
            R.id.favorites -> {
                if (canModifyFavorites()) {
                    this.favoritesModified = true
                    if (isInFavorites) removeFromFavorites(wallpaper)
                    else addToFavorites(wallpaper)
                } else onFavoritesLocked()
            }
        }
        return false
    }

    private fun hasValidNetworkAvailable(): Boolean {
        val downloadUsingWiFiOnly = preferences.shouldDownloadOnWiFiOnly
        val isConnected = isNetworkAvailable()
        val usingMobileData = (downloadUsingWiFiOnly && !isWifiConnected) && isConnected
        val shouldShowNetworkDialog = !isConnected || usingMobileData
        if (shouldShowNetworkDialog) {
            dismissDownloadBlockedDialog()
            downloadBlockedDialog = mdDialog {
                title(R.string.error)
                message(
                    if (usingMobileData) R.string.data_error_network_wifi_only
                    else R.string.data_error_network
                )
                positiveButton(android.R.string.ok) { it.dismiss() }
            }
            downloadBlockedDialog?.show()
            return false
        }
        return true
    }

    private fun checkForDownload() {
        if (!shouldShowDownloadOption()) return
        val actuallyComplies =
            if (intent?.getBooleanExtra(LICENSE_CHECK_ENABLED, false) == true)
                compliesWithMinTime(MIN_TIME) || boolean(R.bool.allow_immediate_downloads)
            else true
        if (actuallyComplies) {
            if (!hasValidNetworkAvailable()) return
            requestStoragePermission()
        } else {
            val elapsedTime = System.currentTimeMillis() - firstInstallTime
            val timeLeft = MIN_TIME - elapsedTime
            val timeLeftText = timeLeft.toReadableTime()

            dismissDownloadBlockedDialog()
            downloadBlockedDialog = mdDialog {
                title(R.string.prevent_download_title)
                message(string(R.string.prevent_download_content, timeLeftText))
                positiveButton(android.R.string.ok) { it.dismiss() }
            }
            downloadBlockedDialog?.show()
        }
    }

    override fun internalOnPermissionsGranted(result: List<PermissionStatus>) {
        super.internalOnPermissionsGranted(result)
        startDownload()
    }

    private fun applyWallpaper(wallpaper: Wallpaper?) {
        wallpaper ?: return
        dismissApplierDialog()
        applierDialog = SetAsOptionsDialog()
        applierDialog?.show(supportFragmentManager, SetAsOptionsDialog.TAG)
    }

    private fun shouldShowWallpapersPalette(): Boolean =
        boolean(R.bool.show_wallpaper_palette_details, true)

    open fun shouldShowDownloadOption() = true
    override fun shouldLoadCollections(): Boolean = false
    override val shouldChangeStatusBarLightStatus: Boolean = false
    override val shouldChangeNavigationBarLightStatus: Boolean = false

    override fun canToggleSystemUIVisibility(): Boolean =
        intent?.getBooleanExtra(CAN_TOGGLE_SYSTEMUI_VISIBILITY_KEY, true) ?: true

    companion object {
        internal const val MIN_TIME: Long = 3L * 60L * 60000L
        internal const val REQUEST_CODE = 10
        internal const val FAVORITES_MODIFIED = "favorites_modified"
        internal const val FAVORITES_MODIFIED_RESULT = 1
        internal const val FAVORITES_NOT_MODIFIED_RESULT = 0
        internal const val CURRENT_WALL_POSITION = "curr_wall_pos"
        internal const val LICENSE_CHECK_ENABLED = "license_check_enabled"
        internal const val CAN_TOGGLE_SYSTEMUI_VISIBILITY_KEY = "can_toggle_visibility"
        internal const val SHARED_IMAGE_NAME = "thumb.jpg"
        private const val CLOSING_KEY = "closing"
        private const val TRANSITIONED_KEY = "transitioned"
        private const val IS_IN_FAVORITES_KEY = "is_in_favorites"
    }
}
