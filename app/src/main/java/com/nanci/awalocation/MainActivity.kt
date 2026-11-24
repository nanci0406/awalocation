package com.nanci.awalocation

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.ACCESS_LOCATION_EXTRA_COMMANDS
import android.Manifest.permission.ACCESS_NETWORK_STATE
import android.Manifest.permission.ACCESS_WIFI_STATE
import android.Manifest.permission.CHANGE_WIFI_STATE
import android.Manifest.permission.FOREGROUND_SERVICE
import android.Manifest.permission.INTERNET
import android.Manifest.permission.READ_PHONE_STATE
import android.Manifest.permission.VIBRATE
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavOptions
import androidx.navigation.fragment.NavHostFragment
import com.amap.api.services.core.AMapException
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.google.android.material.button.MaterialButton
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.amap.MapSelection
import com.nanci.awalocation.android.permission.RequestPermissions
import com.nanci.awalocation.android.root.ShellUtils
import com.nanci.awalocation.android.ui.MotionUtils
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.ActivityMainBinding
import com.nanci.awalocation.ext.gcj02
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding

    private val requestMultiplePermissions = RequestPermissions(this)

    private val mapViewModel by viewModels<AmapMapViewModel>()
    private val mockServiceViewModel by viewModels<MockServiceViewModel>()

    private fun getRequiredPermissions(): MutableSet<String> {
        val permissions = mutableSetOf(
            ACCESS_FINE_LOCATION,
            ACCESS_COARSE_LOCATION,
            ACCESS_LOCATION_EXTRA_COMMANDS,
            ACCESS_WIFI_STATE,
            CHANGE_WIFI_STATE,
            READ_PHONE_STATE,
            INTERNET,
            ACCESS_NETWORK_STATE,
            VIBRATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            permissions.add(FOREGROUND_SERVICE)
        }
        return permissions
    }

    private fun getMissingPermissions(): Set<String> {
        return getRequiredPermissions().filterTo(mutableSetOf()) { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }
    }

    private fun handleDeniedPermissions(denied: Set<String>) {
        denied.forEach { permission ->
            showPermissionDeniedToast(permission)
        }
    }

    private fun showPermissionDeniedToast(permission: String) {
        val message = when (permission) {
            ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION -> "Awalocation需要完整位置权限"
            ACCESS_LOCATION_EXTRA_COMMANDS -> "Awalocation需要额外位置命令权限和系统交互"
            CHANGE_WIFI_STATE, ACCESS_WIFI_STATE -> "Awalocation需要访问Wi-Fi状态"
            READ_PHONE_STATE -> "Awalocation需要读取设备信息"
            ACCESS_NETWORK_STATE, INTERNET -> "Awalocation需要访问网络"
            VIBRATE -> "Awalocation需要访问传感器"
            else -> "需要 $permission 才能运行"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private suspend fun requestMissingPermissions(): Boolean {
        val missing = getMissingPermissions()
        if (missing.isNotEmpty()) {
            val (_, denied) = requestMultiplePermissions.request(missing)
            if (denied.isNotEmpty()) {
                handleDeniedPermissions(denied)
                return false
            }
        }
        return requireFloatWindows()
    }

    private fun initMainUi() {
        mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        mockServiceViewModel.initRocker(this)
        mockServiceViewModel.refreshMockState()

        ViewCompat.setOnApplyWindowInsetsListener(binding.bottomNavContainer) { navContainer, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            navContainer.updatePadding(bottom = bars.bottom)
            insets
        }

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController

        binding.bottomNav.setOnItemSelectedListener { item ->
            if (navController.currentDestination?.id == item.itemId) {
                return@setOnItemSelectedListener true
            }
            val options = NavOptions.Builder()
                .setLaunchSingleTop(true)
                .setRestoreState(true)
                .setEnterAnim(R.anim.m3_tab_enter)
                .setExitAnim(R.anim.m3_tab_exit)
                .setPopEnterAnim(R.anim.m3_tab_enter)
                .setPopExitAnim(R.anim.m3_tab_exit)
                .setPopUpTo(
                    navController.graph.findStartDestination().id,
                    inclusive = false,
                    saveState = true
                )
                .build()
            runCatching {
                navController.navigate(item.itemId, null, options)
                true
            }.getOrDefault(false)
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            val menuItem = binding.bottomNav.menu.findItem(destination.id)
            if (menuItem != null && !menuItem.isChecked) {
                menuItem.isChecked = true
            }

            val shouldShowBar = destination.id != R.id.nav_route_edit
            val bar = binding.bottomNavContainer
            bar.animate().cancel()
            if (shouldShowBar) {
                if (bar.visibility != View.VISIBLE) {
                    bar.alpha = 0f
                    bar.translationY = 24f * resources.displayMetrics.density
                    bar.visibility = View.VISIBLE
                }
                bar.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220L)
                    .setInterpolator(MotionUtils.EMPHASIZED_DECELERATE)
                    .start()
            } else if (bar.visibility == View.VISIBLE) {
                bar.animate()
                    .alpha(0f)
                    .translationY(24f * resources.displayMetrics.density)
                    .setDuration(150L)
                    .setInterpolator(MotionUtils.EMPHASIZED_ACCELERATE)
                    .withEndAction {
                        bar.visibility = View.GONE
                        bar.alpha = 1f
                        bar.translationY = 0f
                    }
                    .start()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val isNight = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        controller.isAppearanceLightStatusBars = !isNight
        controller.isAppearanceLightNavigationBars = !isNight

        CrashReport.setUserSceneTag(this, 261771)

        // 仅在首次启动（非配置变更/主题重建）时提示无 Root，避免切换主题重复弹窗
        if (savedInstanceState == null) {
            lifecycleScope.launch(Dispatchers.IO) {
                if (!ShellUtils.hasRoot()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "无Root可能导致传感器Hook失效", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }

        initGeocodeSearch()

        // 权限已全部授予时，在 onCreate 中同步 setContentView：
        // 这样主题/深浅色切换 recreate() 时 NavHostFragment 能直接原地恢复当前页面，
        // 不会触发多余的权限弹窗往返，也不会闪出主界面（地图页）。
        if (getMissingPermissions().isEmpty() && OverlayUtils.hasOverlayPermissions(this)) {
            initMainUi()
        } else {
            showNoPermissionView()
            lifecycleScope.launch {
                if (requestMissingPermissions() && !isFinishing && !isDestroyed && !::binding.isInitialized) {
                    initMainUi()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized &&
            getMissingPermissions().isEmpty() &&
            OverlayUtils.hasOverlayPermissions(this)
        ) {
            initMainUi()
        }
    }

    private fun showNoPermissionView() {
        if (isFinishing || isDestroyed || ::binding.isInitialized) return
        setContentView(R.layout.activity_no_permission)
        findViewById<MaterialButton>(R.id.btn_retry_permission)?.setOnClickListener {
            lifecycleScope.launch {
                if (requestMissingPermissions() && !isFinishing && !isDestroyed && !::binding.isInitialized) {
                    initMainUi()
                }
            }
        }
        findViewById<MaterialButton>(R.id.btn_open_settings)?.setOnClickListener {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }.onFailure {
                Toast.makeText(this, "无法打开应用设置，请手动前往设置授权", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun initGeocodeSearch() {
        val mainHandler = Handler(Looper.getMainLooper())
        val loadingPlaceholder = getString(R.string.current_location_loading)
        val fallbackPointName = getString(R.string.map_picked_point)
        mapViewModel.geocodeSearch = runCatching {
            GeocodeSearch(this).apply {
                setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) {
                        mapViewModel.currentAddressGeocodeInFlight = false
                        val queryPoint = result?.regeocodeQuery?.point
                        if (errorCode != AMapException.CODE_AMAP_SUCCESS) {
                            Log.e("MainActivity", "Reverse geocode error: $errorCode")
                            mainHandler.post {
                                if (isDestroyed) return@post
                                updateSelectedMarkerAddress(
                                    queryPoint = queryPoint,
                                    resolvedAddress = null,
                                    loadingPlaceholder = loadingPlaceholder,
                                    fallbackName = fallbackPointName
                                )
                            }
                            return
                        }
                        val address = result?.regeocodeAddress
                        val formatAddress = address?.formatAddress.orEmpty()
                        address?.city?.takeIf { it.isNotBlank() }?.let { mCityString = it }
                            ?: address?.province?.takeIf { it.isNotBlank() }?.let { mCityString = it }

                        val currentGcj = mapViewModel.currentLocationGcj
                        val isCurrentLocQuery = currentGcj != null && queryPoint != null &&
                            kotlin.math.abs(queryPoint.latitude - currentGcj.latitude) < 1e-4 &&
                            kotlin.math.abs(queryPoint.longitude - currentGcj.longitude) < 1e-4
                        val markedGcj = mapViewModel.markedLoc?.gcj02
                        val isMarkedLocQuery = markedGcj != null && queryPoint != null &&
                            kotlin.math.abs(queryPoint.latitude - markedGcj.latitude) < 1e-4 &&
                            kotlin.math.abs(queryPoint.longitude - markedGcj.longitude) < 1e-4

                        if (isCurrentLocQuery && formatAddress.isNotBlank()) {
                            mapViewModel.currentAddress.postValue(formatAddress)
                        }
                        if ((isMarkedLocQuery || !isCurrentLocQuery) && formatAddress.isNotBlank()) {
                            mapViewModel.markName = formatAddress
                        }

                        mainHandler.post {
                            if (isDestroyed) return@post
                            updateSelectedMarkerAddress(
                                queryPoint = queryPoint,
                                resolvedAddress = formatAddress.takeIf { it.isNotBlank() },
                                loadingPlaceholder = loadingPlaceholder,
                                fallbackName = fallbackPointName
                            )
                        }
                    }

                    override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) {}
                })
            }
        }.onFailure {
            Log.e("MainActivity", "Failed to initialize GeocodeSearch", it)
        }.getOrNull()
    }

    private fun updateSelectedMarkerAddress(
        queryPoint: com.amap.api.services.core.LatLonPoint?,
        resolvedAddress: String?,
        loadingPlaceholder: String,
        fallbackName: String
    ) {
        val marker = mapViewModel.selectedMarker ?: return
        val selection = marker.getObject() as? MapSelection ?: return
        val matchesPoint = queryPoint == null || (
            kotlin.math.abs(queryPoint.latitude - selection.gcjLocation.latitude) < 1e-4 &&
                kotlin.math.abs(queryPoint.longitude - selection.gcjLocation.longitude) < 1e-4
            )
        if (!matchesPoint) return

        val finalName = resolvedAddress
            ?: selection.name?.takeUnless { it.isBlank() || it == loadingPlaceholder }
            ?: fallbackName
        if (finalName != selection.name) {
            val updated = selection.copy(name = finalName)
            marker.setObject(updated)
            mapViewModel.selectedSelection.value = updated
        }
    }

    fun switchToMapTab() {
        if (!::binding.isInitialized) return
        binding.bottomNav.selectedItemId = R.id.nav_home
    }

    private fun requireFloatWindows(): Boolean {
        fun requestSettingCanDrawOverlays() {
            kotlin.runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            }.onFailure {
                Log.e("MainActivity", "requestSettingCanDrawOverlays: ", it)
                Toast.makeText(this, "跳转失败，请手动去设置授权", Toast.LENGTH_LONG).show()
            }
        }

        if (!OverlayUtils.hasOverlayPermissions(this)) {
            Toast.makeText(this, "请授予悬浮窗权限后返回", Toast.LENGTH_LONG).show()
            requestSettingCanDrawOverlays()
            return false
        }

        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        mapViewModel.geocodeSearch = null
        mapViewModel.selectedMarker = null
        mapViewModel.currentAddressGeocodeInFlight = false
    }

    companion object {
        internal var mCityString: String? = null
            set(value) {
                if (field != value) {
                    field = value
                    Log.d("MainActivity", "cityString: $value")
                }
            }
    }
}
