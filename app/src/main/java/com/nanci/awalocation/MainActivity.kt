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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.AdapterView.OnItemClickListener
import android.widget.ImageView
import android.widget.SimpleAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.core.widget.ImageViewCompat
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavController.OnDestinationChangedListener
import androidx.navigation.NavDestination
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.amap.api.services.core.AMapException
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.google.android.material.navigation.NavigationView
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.amap.MapSelection
import com.nanci.awalocation.amap.Poi
import com.nanci.awalocation.amap.animateTo
import com.nanci.awalocation.amap.showSelection
import com.nanci.awalocation.amap.toPoi
import com.nanci.awalocation.android.permission.RequestPermissions
import com.nanci.awalocation.android.root.ShellUtils
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.ActivityMainBinding
import com.nanci.awalocation.ext.gcj02
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel

class MainActivity : AppCompatActivity() {
    private lateinit var appBarConfiguration: AppBarConfiguration
    private lateinit var binding: ActivityMainBinding

    /* Permission */
    private val requestMultiplePermissions = RequestPermissions(this)

    /* AMap */
    private var inputtips: Inputtips? = null
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

    private suspend fun checkPermission(): Boolean {
        val permissions = getRequiredPermissions()
        val (_, denied) = requestMultiplePermissions.request(permissions)
        if (denied.isNotEmpty()) {
            handleDeniedPermissions(denied)
            return false
        }
        return requireFloatWindows()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge（Android 15/16 起强制）：系统栏透明，内容由布局避让
        enableEdgeToEdge()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        val nightMode = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        controller.isAppearanceLightStatusBars = nightMode != Configuration.UI_MODE_NIGHT_YES

        CrashReport.setUserSceneTag(this, 261771)

        lifecycleScope.launch(Dispatchers.IO) {
            // su 检查可能阻塞，放到独立 IO 线程，不能阻塞权限申请和 UI 初始化。
            if (!ShellUtils.hasRoot()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "无Root可能导致传感器Hook失效", Toast.LENGTH_LONG).show()
                }
            }
        }

        lifecycleScope.launch {
            if (!checkPermission()) {
                if (!isFinishing) {
                    setContentView(R.layout.activity_no_permission)
                }
                return@launch
            }

            mockServiceViewModel.locationManager = getSystemService(LOCATION_SERVICE) as? LocationManager

            binding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(binding.root)
            mockServiceViewModel.initRocker(this@MainActivity)

            setSupportActionBar(binding.appBarMain.toolbar)

            // 顶栏整体避让状态栏，Toolbar 保持固定高度，避免标题和导航图标被压缩。
            ViewCompat.setOnApplyWindowInsetsListener(binding.appBarMain.appBarLayout) { barView, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                barView.updatePadding(top = bars.top)
                insets
            }
            ViewCompat.setOnApplyWindowInsetsListener(binding.appBarMain.contentMain.root) { contentView, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
                contentView.updatePadding(bottom = bars.bottom)
                insets
            }

            val drawerLayout: DrawerLayout = binding.drawerLayout
            val navView: NavigationView = binding.navView
            val navController = findNavController(R.id.nav_host_fragment_content_main)

            // Passing each menu ID as a set of Ids because each
            // menu should be considered as top level destinations.
            appBarConfiguration = AppBarConfiguration(
                setOf(
                    R.id.nav_home, R.id.nav_mock, R.id.nav_gnss_mock, R.id.nav_route_gallery, R.id.nav_settings
                ), drawerLayout
            )

            setupActionBarWithNavController(navController, appBarConfiguration)
            navView.setupWithNavController(navController)

            binding.appBarMain.toolbar.navigationIcon?.colorFilter = PorterDuffColorFilter(
                ContextCompat.getColor(this@MainActivity, R.color.theme_appbar_icon_color), PorterDuff.Mode.SRC_IN
            )

            navController.addOnDestinationChangedListener(object: OnDestinationChangedListener {
                val menuIdMapping = mapOf(
                    R.id.nav_home to R.id.action_search,
                    //R.id.nav_settings to R.id.action_info
                )

                override fun onDestinationChanged(
                    controller: NavController,
                    destination: NavDestination,
                    arguments: Bundle?
                ) {
                    menuIdMapping.forEach { (key, value) ->
                        val menu = binding.appBarMain.toolbar.menu
                        menu.findItem(value)?.isVisible = key == destination.id
                    }
                }
            })
        }

        mapViewModel.geocodeSearch = GeocodeSearch(this).apply {
            setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) {
                    if (errorCode != AMapException.CODE_AMAP_SUCCESS) {
                        Log.e("MainActivity", "Reverse geocode error: $errorCode")
                        return
                    }
                    val address = result?.regeocodeAddress ?: return
                    val formatAddress = address.formatAddress.orEmpty()
                    val queryPoint = result?.regeocodeQuery?.point
                    mapViewModel.markName = formatAddress
                    address.city?.takeIf { it.isNotBlank() }?.let { mCityString = it }

                    val currentGcj = mapViewModel.currentLocationGcj
                    if (currentGcj != null && queryPoint != null &&
                        kotlin.math.abs(queryPoint.latitude - currentGcj.latitude) < 1e-4 &&
                        kotlin.math.abs(queryPoint.longitude - currentGcj.longitude) < 1e-4
                    ) {
                        mapViewModel.currentAddress.postValue(formatAddress)
                    }

                    runOnUiThread {
                        mapViewModel.selectedMarker?.let { marker ->
                            val selection = marker.getObject() as? MapSelection
                            if (selection != null &&
                                queryPoint != null &&
                                kotlin.math.abs(queryPoint.latitude - selection.gcjLocation.latitude) < 1e-5 &&
                                kotlin.math.abs(queryPoint.longitude - selection.gcjLocation.longitude) < 1e-5
                            ) {
                                marker.setObject(selection.copy(name = formatAddress))
                                marker.showInfoWindow()
                            }
                        }
                    }
                }

                override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) {}
            })
        }
    }

    private fun requireFloatWindows(): Boolean {
        fun requestSettingCanDrawOverlays() {
            kotlin.runCatching {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            }.onFailure {
                Log.e("MainActivity", "requestSettingCanDrawOverlays: ", it) // boom in Redmi K60
                Toast.makeText(this, "跳转失败，请手动去设置授权", Toast.LENGTH_LONG).show()
            }
            finish()
        }

        if (!OverlayUtils.hasOverlayPermissions(this)) {
            Toast.makeText(this, "快给我悬浮窗权限", Toast.LENGTH_LONG).show()
            requestSettingCanDrawOverlays()
            return false
        }

        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        val searchItem: MenuItem = menu.findItem(R.id.action_search)
        val searchView = searchItem.actionView as SearchView

        val searchClose = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_close_btn)
        val searchBack = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_go_btn)
        val voiceBack = searchView.findViewById<ImageView>(androidx.appcompat.R.id.search_voice_btn)
        val color = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.theme_appbar_icon_color))
        ImageViewCompat.setImageTintList(searchClose, color)
        ImageViewCompat.setImageTintList(searchBack, color)
        ImageViewCompat.setImageTintList(voiceBack, color)
        searchView.findViewById<TextView>(androidx.appcompat.R.id.search_src_text)?.apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.theme_appbar_text_color))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.awalocation_hint_color))
        }

        val mSearchList = binding.appBarMain.searchListView
        mSearchList.onItemClickListener = OnItemClickListener { _, view, _, _ ->
            val lngText = (view.findViewById<View>(R.id.poi_longitude) as TextView).text.toString()
            val latText = (view.findViewById<View>(R.id.poi_latitude) as TextView).text.toString()
            with(mapViewModel) {
                markName = (view.findViewById<View>(R.id.poi_name) as TextView).text.toString()

                val lng = lngText.toDouble() // wgs84
                val lat = latText.toDouble()
                markedLoc = lat to lng
                if (isExists) {
                    amap.animateTo(markedLoc!!.gcj02)
                } else {
                    Toast.makeText(this@MainActivity, "地图未加载", Toast.LENGTH_SHORT).show()
                }

                markMap()

                hideSearchResults()
                searchItem.collapseActionView()
            }
        }
        if (inputtips == null) {
            inputtips = Inputtips(this, Inputtips.InputtipsListener { tipList, errorCode ->
                if (!isFinishing && !isDestroyed) {
                    if (errorCode != AMapException.CODE_AMAP_SUCCESS || tipList.isNullOrEmpty()) {
                        Toast.makeText(this@MainActivity, "未搜索到相关位置", Toast.LENGTH_SHORT).show()
                    } else {
                        val data = tipList?.mapNotNull { tip ->
                            tip.toPoi(mapViewModel.currentLocation)?.toMap()
                        } ?: emptyList()
                        if (data.isEmpty()) {
                            Toast.makeText(this@MainActivity, "未搜索到相关位置", Toast.LENGTH_SHORT).show()
                        } else {
                            val simAdapt = SimpleAdapter(
                                this@MainActivity, data,
                                R.layout.layout_search_poi_item,
                                arrayOf(Poi.KEY_NAME, Poi.KEY_ADDRESS, Poi.KEY_LONGITUDE_RAW, Poi.KEY_LATITUDE_RAW, Poi.KEY_TAG),
                                intArrayOf(R.id.poi_name, R.id.poi_address, R.id.poi_longitude, R.id.poi_latitude, R.id.poi_tag)
                            )
                            mSearchList.setAdapter(simAdapt)
                            showSearchResults()
                        }
                    }
                }
            })
        }

        searchView.setOnQueryTextListener(object: SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                if (query.isNullOrBlank()) return false
                try {
                    val keyword = query
                    inputtips?.apply {
                        this.query = InputtipsQuery(keyword, mCityString.orEmpty())
                        requestInputtipsAsyn()
                    }

                    if (mapViewModel.isExists) {
                        mapViewModel.amap.clear()
                        mapViewModel.selectedMarker = null
                    }
                    hideSearchResults()
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "搜索出错", Toast.LENGTH_SHORT).show()
                    Log.e("MainActivity", "Search error: ${e.stackTraceToString()}")
                }
                return true
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                if (!newText.isNullOrBlank()) {
                    try {
                        inputtips?.apply {
                            this.query = InputtipsQuery(newText, mCityString.orEmpty())
                            requestInputtipsAsyn()
                        }
                    } catch (e: Exception) {
                        Toast.makeText(this@MainActivity, "搜索出错", Toast.LENGTH_SHORT).show()
                        Log.e("MainActivity", "Search error: ${e.stackTraceToString()}")
                    }
                } else {
                    hideSearchResults()
                }
                return true
            }
        })
        return true
    }

    /**
     * 从首页的悬浮搜索框打开顶部 SearchView，复用现有的高德 Inputtips 搜索。
     */
    fun openSearch() {
        if (!::binding.isInitialized) return
        invalidateOptionsMenu()
        binding.appBarMain.toolbar.post {
            val searchItem = binding.appBarMain.toolbar.menu.findItem(R.id.action_search)
                ?: return@post
            searchItem.isVisible = true
            searchItem.expandActionView()
            (searchItem.actionView as? SearchView)?.apply {
                isIconified = false
                setQuery("", false)
                requestFocus()
            }
        }
    }

    private fun showSearchResults() {
        val card = binding.appBarMain.searchLinear
        if (card.visibility == View.VISIBLE && card.alpha >= 0.99f) return
        card.animate().cancel()
        card.alpha = 0f
        card.translationY = -10f
        card.visibility = View.VISIBLE
        card.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(180L)
            .start()
    }

    private fun hideSearchResults() {
        val card = binding.appBarMain.searchLinear
        if (card.visibility != View.VISIBLE) return
        card.animate().cancel()
        card.animate()
            .alpha(0f)
            .translationY(-8f)
            .setDuration(140L)
            .withEndAction {
                card.visibility = View.GONE
                card.alpha = 1f
                card.translationY = 0f
            }
            .start()
    }

    private fun markMap() = with(mapViewModel) {
        if (!isExists || markedLoc == null) return

        val wgsLoc = markedLoc!!
        selectedMarker = amap.showSelection(
            MapSelection(wgsLoc, wgsLoc.gcj02, markName),
            mMapIndicator
        )
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment_content_main)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onDestroy() {
        super.onDestroy()

        inputtips = null
        mapViewModel.geocodeSearch = null
        mapViewModel.selectedMarker = null
    }

    companion object {
        internal var mCityString: String? = null
            set(value) {
                if (field != value)  {
                    field = value
                    Log.d("HomeViewModel", "cityString: $value")
                }
            }
    }
}