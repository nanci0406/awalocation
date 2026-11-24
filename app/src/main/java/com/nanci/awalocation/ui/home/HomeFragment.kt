package com.nanci.awalocation.ui.home

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItemV2
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.poisearch.PoiResultV2
import com.amap.api.services.poisearch.PoiSearchV2
import com.amap.api.services.poisearch.VisualSearchResult
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nanci.awalocation.MainActivity
import com.nanci.awalocation.R
import com.nanci.awalocation.amap.MapSelection
import com.nanci.awalocation.amap.Poi
import com.nanci.awalocation.amap.animateTo
import com.nanci.awalocation.amap.describeAmapError
import com.nanci.awalocation.amap.isValidAmapLocation
import com.nanci.awalocation.amap.setupMap
import com.nanci.awalocation.amap.showSelection
import com.nanci.awalocation.amap.toPoi
import com.nanci.awalocation.amap.zoomIn
import com.nanci.awalocation.amap.zoomOut
import com.nanci.awalocation.android.ui.MotionUtils
import com.nanci.awalocation.android.ui.animateSlideFadeIn
import com.nanci.awalocation.android.ui.animateSlideFadeOut
import com.nanci.awalocation.android.ui.startLivePulse
import com.nanci.awalocation.android.ui.stopLivePulse
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.FragmentHomeBinding
import com.nanci.awalocation.ext.Loc4j
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.drawOverOtherAppsEnabled
import com.nanci.awalocation.ext.gcj02
import com.nanci.awalocation.ext.mapType
import com.nanci.awalocation.ext.rawHistoricalLocations
import com.nanci.awalocation.ext.selectLocation
import com.nanci.awalocation.ext.selectRoute
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.ext.wgs84
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.mock.HistoricalLocation
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.util.Locale

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val mapViewModel by activityViewModels<AmapMapViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    private var hasCenteredOnLocation = false
    private var searchDebounceJob: Job? = null
    private var inputtips: Inputtips? = null
    private var forwardGeocodeSearch: GeocodeSearch? = null
    private var onceLocationClient: AMapLocationClient? = null
    private var activeSearchSeq = 0
    private var activeSearchKeyword = ""
    private var suppressSearchTextChange = false
    private lateinit var searchAdapter: SearchResultAdapter

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        binding.mapView.onCreate(savedInstanceState)
        hasCenteredOnLocation = false

        val topBasePadding = resources.getDimensionPixelSize(R.dimen.home_top_overlay_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.homeTopOverlay) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top + topBasePadding)
            insets
        }

        // 首次定位前先盖住地图，避免先闪一下默认城市。
        val hasInitialTarget = mapViewModel.currentLocation != null ||
            mapViewModel.pendingFocusLocation != null ||
            mapViewModel.markedLoc != null
        binding.homeMapLoading.visibility = if (hasInitialTarget) View.GONE else View.VISIBLE
        binding.homeMapLoading.alpha = 1f

        binding.zoomIn.setOnClickListener { mapViewModel.amap.zoomIn() }
        binding.zoomOut.setOnClickListener { mapViewModel.amap.zoomOut() }

        binding.homeTopOverlay.alpha = 0f
        binding.homeTopOverlay.translationY = -12f
        binding.homeTopOverlay.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(220L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        setupSearch()
        setupBottomDockActions()

        with(mapViewModel) {
            isExists = true
            amap = binding.mapView.map
        }

        val currentMapType = context?.mapType ?: AMap.MAP_TYPE_NORMAL
        with(binding.mapView.map) {
            setupMap(requireContext())
            moveCamera(CameraUpdateFactory.zoomTo(17f))
            setMapType(currentMapType)

            setOnMapClickListener { loc ->
                clearSearchFocus()
                hideSearchResults()
                selectMapPoint(loc.wgs84, loc, moveCamera = false)
            }

            setOnMapLongClickListener { loc ->
                clearSearchFocus()
                hideSearchResults()
                selectMapPoint(loc.wgs84, loc, moveCamera = false)
            }

            setOnMyLocationChangeListener { location ->
                if (!location.isValidAmapLocation()) return@setOnMyLocationChangeListener
                val validLoc = location!!
                val gcjLocation = LatLng(validLoc.latitude, validLoc.longitude)
                val extrasAddress = validLoc.extras?.getString("Address")?.takeIf { it.isNotBlank() }
                val extrasCity = validLoc.extras?.getString("City")?.takeIf { it.isNotBlank() }
                handleLocatedPosition(
                    wgsLocation = validLoc.wgs84,
                    gcjLocation = gcjLocation,
                    directAddress = extrasAddress,
                    directCity = extrasCity,
                    forceCenter = false
                )
            }

            binding.mapTypeGroup.check(
                when (currentMapType) {
                    AMap.MAP_TYPE_NORMAL -> R.id.map_type_normal
                    AMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
                }
            )
        }

        restoreInitialCameraAndSelection()

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mapType = when (checkedId) {
                R.id.map_type_normal -> AMap.MAP_TYPE_NORMAL
                R.id.map_type_satellite -> AMap.MAP_TYPE_SATELLITE
                else -> {
                    Log.e(TAG, "Unknown location view mode: $checkedId")
                    AMap.MAP_TYPE_NORMAL
                }
            }
            binding.mapView.map.setMapType(mapType)
            context?.mapType = mapType
        }

        binding.homeAddressRow.setOnClickListener {
            locateOrMoveToCurrent()
        }

        binding.btnMyLocation.setOnClickListener {
            locateOrMoveToCurrent()
        }

        binding.btnGotoCoords.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.showRoute.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireContext().selectRoute?.route?.takeIf { it.isNotEmpty() }?.let {
                    previewRoute(it)
                    mapViewModel.amap.animateTo(it.first().gcj02, 17f)
                } ?: Toast.makeText(requireContext(), "暂无选中路线", Toast.LENGTH_SHORT).show()
            } else {
                mapViewModel.amap.clear()
                mapViewModel.selectedMarker = null
                mapViewModel.markedLoc?.let { markMap() }
            }
        }

        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        mapViewModel.currentAddress.observe(viewLifecycleOwner) { address ->
            binding.homeCurrentAddress.text = address?.takeIf { it.isNotBlank() }
                ?: getString(R.string.current_location_loading)
        }

        mapViewModel.selectedSelection.observe(viewLifecycleOwner) { selection ->
            if (selection != null) {
                renderSelectionSheet(selection)
            } else {
                hideSelectionSheet()
            }
        }

        mockServiceViewModel.mockRunning.observe(viewLifecycleOwner) {
            updateMockVisualState()
        }

        mockServiceViewModel.rockerShowing.observe(viewLifecycleOwner) { showing ->
            binding.btnLiveRocker.alpha = if (showing) 1f else 0.88f
            binding.btnLiveRocker.text = if (showing) "隐藏摇杆" else getString(R.string.rocker)
        }

        mockServiceViewModel.refreshMockState()

        // 双保险主动定位：冷启动或地址尚未解析时并发发起一次高德高精度单次定位（自带逆地理地址）
        if (mapViewModel.currentLocation == null || mapViewModel.currentAddress.value.isNullOrBlank()) {
            seedFromSystemLastKnownLocation()
            requestFreshLocation(forceCenter = false)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            delay(4000)
            if (_binding == null) return@launch
            hideMapLoading()
            if (mapViewModel.currentAddress.value.isNullOrBlank()) {
                val loc = mapViewModel.currentLocationGcj
                if (loc != null) {
                    triggerCurrentLocationReverseGeocode(loc, force = true)
                } else {
                    binding.homeCurrentAddress.text = getString(R.string.current_location_unknown)
                }
            }
        }
    }

    private fun restoreInitialCameraAndSelection() {
        val pending = mapViewModel.pendingFocusLocation
        val marked = mapViewModel.markedLoc
        val cached = mapViewModel.currentLocation

        if (pending != null) {
            hasCenteredOnLocation = true
            binding.mapView.post {
                if (_binding == null) return@post
                mapViewModel.pendingFocusLocation = null
                selectMapPoint(pending, pending.gcj02, moveCamera = true)
                hideMapLoading()
            }
            return
        }

        if (marked != null) {
            hasCenteredOnLocation = true
            binding.mapView.post {
                if (_binding == null) return@post
                markMap()
                mapViewModel.amap.moveCamera(CameraUpdateFactory.newLatLngZoom(marked.gcj02, 17f))
                hideMapLoading()
            }
        } else if (cached != null) {
            hasCenteredOnLocation = true
            mapViewModel.currentLocationGcj = cached.gcj02
            binding.mapView.post {
                if (_binding == null) return@post
                mapViewModel.amap.moveCamera(CameraUpdateFactory.newLatLngZoom(cached.gcj02, 17f))
                hideMapLoading()
            }
        }

        if (cached != null && mapViewModel.currentAddress.value.isNullOrBlank()) {
            triggerCurrentLocationReverseGeocode(cached.gcj02, force = false)
        }
    }

    private fun handleLocatedPosition(
        wgsLocation: Pair<Double, Double>,
        gcjLocation: LatLng,
        directAddress: String?,
        directCity: String?,
        forceCenter: Boolean
    ) {
        if (_binding == null || !isAdded) return
        mapViewModel.currentLocation = wgsLocation
        mapViewModel.currentLocationGcj = gcjLocation
        directCity?.takeIf { it.isNotBlank() }?.let { MainActivity.mCityString = it }

        if (!directAddress.isNullOrBlank()) {
            mapViewModel.currentAddress.postValue(directAddress)
        } else if (mapViewModel.currentAddress.value.isNullOrBlank()) {
            triggerCurrentLocationReverseGeocode(gcjLocation, force = false)
        }

        if (!hasCenteredOnLocation || forceCenter) {
            hasCenteredOnLocation = true
            val pending = mapViewModel.pendingFocusLocation
            if (pending != null) {
                mapViewModel.pendingFocusLocation = null
                selectMapPoint(pending, pending.gcj02, moveCamera = true)
            } else {
                mapViewModel.amap.animateTo(gcjLocation, 17f)
            }
            hideMapLoading()
        }
    }

    private fun triggerCurrentLocationReverseGeocode(gcjLocation: LatLng, force: Boolean) {
        val geocodeSearch = mapViewModel.geocodeSearch ?: return
        val now = SystemClock.elapsedRealtime()
        if (!force) {
            if (mapViewModel.currentAddressGeocodeInFlight) return
            if (now - mapViewModel.lastCurrentGeocodeAtMs < 4000L) return
        }
        mapViewModel.currentAddressGeocodeInFlight = true
        mapViewModel.lastCurrentGeocodeAtMs = now
        geocodeSearch.getFromLocationAsyn(
            RegeocodeQuery(
                LatLonPoint(gcjLocation.latitude, gcjLocation.longitude),
                200f,
                GeocodeSearch.AMAP
            )
        )
    }

    @SuppressLint("MissingPermission")
    private fun seedFromSystemLastKnownLocation() {
        if (mapViewModel.currentLocation != null) return
        val lm = requireContext().getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val providers = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
        )
        var best: Location? = null
        for (provider in providers) {
            val candidate = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            if (candidate.latitude == 0.0 && candidate.longitude == 0.0) continue
            if (best == null || candidate.time > best.time) {
                best = candidate
            }
        }
        val fallback = best ?: return
        val wgs = fallback.latitude to fallback.longitude
        val gcj = wgs.gcj02
        handleLocatedPosition(
            wgsLocation = wgs,
            gcjLocation = gcj,
            directAddress = null,
            directCity = null,
            forceCenter = false
        )
    }

    private fun locateOrMoveToCurrent() {
        val current = mapViewModel.currentLocation
        if (current != null) {
            mapViewModel.amap.animateTo(current.gcj02, 17f)
            if (mapViewModel.currentAddress.value.isNullOrBlank()) {
                binding.homeCurrentAddress.text = getString(R.string.current_location_loading)
                requestFreshLocation(forceCenter = false)
            }
        } else {
            binding.homeCurrentAddress.text = getString(R.string.current_location_loading)
            Toast.makeText(requireContext(), "正在重新定位…", Toast.LENGTH_SHORT).show()
            requestFreshLocation(forceCenter = true)
        }
    }

    private fun requestFreshLocation(forceCenter: Boolean) {
        val ctx = context?.applicationContext ?: return
        runCatching {
            onceLocationClient?.stopLocation()
            onceLocationClient?.onDestroy()

            val client = AMapLocationClient(ctx)
            val option = AMapLocationClientOption().apply {
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                isOnceLocation = true
                isOnceLocationLatest = true
                isNeedAddress = true
                isLocationCacheEnable = true
                httpTimeOut = 10000L
            }
            client.setLocationOption(option)
            client.setLocationListener { amapLocation ->
                if (_binding == null || !isAdded) {
                    runCatching { client.onDestroy() }
                    return@setLocationListener
                }
                if (amapLocation != null && amapLocation.errorCode == 0 &&
                    !(amapLocation.latitude == 0.0 && amapLocation.longitude == 0.0)
                ) {
                    val gcj = LatLng(amapLocation.latitude, amapLocation.longitude)
                    val wgs = Loc4j.gcj2wgs(amapLocation.latitude, amapLocation.longitude)
                    val address = amapLocation.address?.takeIf { it.isNotBlank() }
                        ?: listOfNotNull(
                            amapLocation.city,
                            amapLocation.district,
                            amapLocation.street,
                            amapLocation.poiName
                        ).filter { it.isNotBlank() }.distinct().joinToString("")
                    val city = amapLocation.city?.takeIf { it.isNotBlank() }
                        ?: amapLocation.province?.takeIf { it.isNotBlank() }
                    handleLocatedPosition(
                        wgsLocation = wgs,
                        gcjLocation = gcj,
                        directAddress = address.takeIf { it.isNotBlank() },
                        directCity = city,
                        forceCenter = forceCenter
                    )
                } else {
                    Log.w(
                        TAG,
                        "AMapLocationClient once fix failed: code=${amapLocation?.errorCode}, info=${amapLocation?.errorInfo}"
                    )
                    if (forceCenter && mapViewModel.currentLocation == null) {
                        Toast.makeText(
                            requireContext(),
                            "定位失败 (${amapLocation?.errorCode ?: -1})，请检查定位权限或网络",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                runCatching {
                    client.stopLocation()
                    client.onDestroy()
                }
                if (onceLocationClient === client) {
                    onceLocationClient = null
                }
            }
            onceLocationClient = client
            client.startLocation()
        }.onFailure {
            Log.e(TAG, "Failed to start AMapLocationClient", it)
        }
    }

    private fun setupSearch() {
        searchAdapter = SearchResultAdapter { item ->
            when (item) {
                is SearchResultItem.Located -> {
                    clearSearchFocus()
                    hideSearchResults()
                    applySelectedPoi(item.poi)
                }

                is SearchResultItem.KeywordSuggestion -> {
                    // 无坐标联想词：回填并立即发起精确 POI 检索
                    suppressSearchTextChange = true
                    binding.homeSearchInput.setText(item.keyword)
                    binding.homeSearchInput.setSelection(item.keyword.length)
                    suppressSearchTextChange = false
                    performFullSearch(item.keyword)
                }
            }
        }
        binding.homeSearchResultsList.layoutManager = LinearLayoutManager(requireContext())
        binding.homeSearchResultsList.adapter = searchAdapter

        inputtips = runCatching {
            Inputtips(requireContext(), Inputtips.InputtipsListener { tipList, errorCode ->
                if (_binding == null || !isAdded) return@InputtipsListener
                val seq = activeSearchSeq
                val keyword = activeSearchKeyword
                if (keyword.isBlank()) return@InputtipsListener

                if (errorCode != AMapException.CODE_AMAP_SUCCESS) {
                    Log.w(TAG, "Inputtips failed: code=$errorCode, fallback to PoiSearchV2")
                    performPoiSearch(keyword, seq, fallbackErrorCode = errorCode)
                    return@InputtipsListener
                }

                val mapped = buildList {
                    tipList.orEmpty().forEach { tip ->
                        val poi = tip.toPoi(mapViewModel.currentLocation)
                        if (poi != null) {
                            add(SearchResultItem.Located(poi))
                        } else if (!tip.name.isNullOrBlank()) {
                            val sub = listOfNotNull(tip.district, tip.address)
                                .filter { it.isNotBlank() }
                                .joinToString(" ")
                                .ifBlank { "点击继续搜索该地点" }
                            add(SearchResultItem.KeywordSuggestion(tip.name, sub))
                        }
                    }
                }

                if (mapped.isEmpty()) {
                    performPoiSearch(keyword, seq, fallbackErrorCode = null)
                } else {
                    renderSearchResults(mapped)
                }
            })
        }.onFailure {
            Log.e(TAG, "Failed to initialize Inputtips", it)
        }.getOrNull()

        forwardGeocodeSearch = runCatching {
            GeocodeSearch(requireContext()).apply {
                setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) {}

                    override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) {
                        if (_binding == null || !isAdded) return
                        if (errorCode != AMapException.CODE_AMAP_SUCCESS) {
                            showSearchStatus(requireContext().describeAmapError(errorCode))
                            return
                        }
                        val items = result?.geocodeAddressList.orEmpty().mapNotNull { addr ->
                            val pt = addr.latLonPoint ?: return@mapNotNull null
                            val wgs = Loc4j.gcj2wgs(pt.latitude, pt.longitude)
                            val title = addr.formatAddress?.takeIf { it.isNotBlank() }
                                ?: listOfNotNull(addr.city, addr.district, addr.township)
                                    .filter { it.isNotBlank() }
                                    .joinToString("")
                                    .ifBlank { activeSearchKeyword }
                            SearchResultItem.Located(
                                Poi(
                                    name = title,
                                    address = listOfNotNull(addr.province, addr.city, addr.district)
                                        .filter { it.isNotBlank() }
                                        .distinct()
                                        .joinToString(" "),
                                    longitude = wgs.second,
                                    latitude = wgs.first,
                                    tag = "地址解析"
                                )
                            )
                        }
                        if (items.isEmpty()) {
                            showSearchStatus("未找到匹配地点，可尝试更完整的名称或直接输入经纬度")
                        } else {
                            renderSearchResults(items)
                        }
                    }
                })
            }
        }.onFailure {
            Log.e(TAG, "Failed to initialize forward GeocodeSearch", it)
        }.getOrNull()

        binding.homeSearchInput.addTextChangedListener { editable ->
            if (suppressSearchTextChange) return@addTextChangedListener
            val query = editable?.toString().orEmpty()
            binding.homeSearchClear.visibility = if (query.isEmpty()) View.GONE else View.VISIBLE
            searchDebounceJob?.cancel()
            val trimmed = query.trim()
            if (trimmed.isEmpty()) {
                activeSearchKeyword = ""
                hideSearchResults()
                return@addTextChangedListener
            }

            // 如果输入的是坐标，无需网络请求直接给出直达结果
            val coordItems = parseCoordinateQuery(trimmed)
            if (coordItems != null) {
                activeSearchSeq++
                activeSearchKeyword = trimmed
                renderSearchResults(coordItems)
                return@addTextChangedListener
            }

            searchDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                delay(320L)
                performLiveSuggestionSearch(trimmed)
            }
        }

        binding.homeSearchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                val query = binding.homeSearchInput.text?.toString()?.trim().orEmpty()
                if (query.isNotEmpty()) {
                    searchDebounceJob?.cancel()
                    clearSearchFocus()
                    performFullSearch(query)
                }
                true
            } else {
                false
            }
        }

        binding.homeSearchIcon.setOnClickListener {
            val query = binding.homeSearchInput.text?.toString()?.trim().orEmpty()
            if (query.isNotEmpty()) {
                searchDebounceJob?.cancel()
                clearSearchFocus()
                performFullSearch(query)
            } else {
                binding.homeSearchInput.requestFocus()
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(binding.homeSearchInput, InputMethodManager.SHOW_IMPLICIT)
            }
        }

        binding.homeSearchClear.setOnClickListener {
            binding.homeSearchInput.setText("")
            activeSearchKeyword = ""
            hideSearchResults()
            clearSearchFocus()
        }
    }

    private fun parseCoordinateQuery(input: String): List<SearchResultItem.Located>? {
        val cleaned = input.replace('，', ',').replace('；', ',').trim()
        val parts = cleaned.split(Regex("[,\\s]+")).filter { it.isNotBlank() }
        if (parts.size != 2) return null
        val first = parts[0].toDoubleOrNull() ?: return null
        val second = parts[1].toDoubleOrNull() ?: return null

        val lat: Double
        val lon: Double
        when {
            first in -90.0..90.0 && second in -180.0..180.0 -> {
                // 常见纬度在前，但如果第一个绝对值 > 90 则为经度在前
                lat = first
                lon = second
            }

            second in -90.0..90.0 && first in -180.0..180.0 -> {
                lat = second
                lon = first
            }

            else -> return null
        }

        val gcjToWgs = Loc4j.gcj2wgs(lat, lon)
        return listOf(
            SearchResultItem.Located(
                Poi(
                    name = String.format(Locale.US, "WGS84 坐标直达 (%.6f, %.6f)", lat, lon),
                    address = "按国际标准 GPS 坐标定位到该点",
                    longitude = lon,
                    latitude = lat,
                    tag = "WGS84"
                )
            ),
            SearchResultItem.Located(
                Poi(
                    name = String.format(Locale.US, "GCJ-02 坐标直达 (%.6f, %.6f)", lat, lon),
                    address = "按高德/火星坐标系转换后定位",
                    longitude = gcjToWgs.second,
                    latitude = gcjToWgs.first,
                    tag = "GCJ-02"
                )
            )
        )
    }

    private fun performLiveSuggestionSearch(keyword: String) {
        val seq = ++activeSearchSeq
        activeSearchKeyword = keyword
        showSearchLoading()

        val tips = inputtips
        if (tips == null) {
            performPoiSearch(keyword, seq, fallbackErrorCode = null)
            return
        }
        try {
            val query = InputtipsQuery(keyword, MainActivity.mCityString.orEmpty())
            query.cityLimit = false
            tips.query = query
            tips.requestInputtipsAsyn()
        } catch (e: Exception) {
            Log.e(TAG, "Inputtips exception, falling back to PoiSearchV2", e)
            performPoiSearch(keyword, seq, fallbackErrorCode = null)
        }
    }

    private fun performFullSearch(keyword: String) {
        val coordItems = parseCoordinateQuery(keyword)
        if (coordItems != null) {
            activeSearchSeq++
            activeSearchKeyword = keyword
            renderSearchResults(coordItems)
            return
        }
        val seq = ++activeSearchSeq
        activeSearchKeyword = keyword
        showSearchLoading()
        performPoiSearch(keyword, seq, fallbackErrorCode = null)
    }

    private fun performPoiSearch(keyword: String, seq: Int, fallbackErrorCode: Int?) {
        val ctx = context ?: return
        try {
            val query = PoiSearchV2.Query(keyword, "", MainActivity.mCityString.orEmpty()).apply {
                pageSize = 20
                pageNum = 1
                cityLimit = false
            }
            val poiSearch = PoiSearchV2(ctx, query)
            poiSearch.setOnPoiSearchListener(object : PoiSearchV2.OnPoiSearchListener {
                override fun onPoiSearched(result: PoiResultV2?, errorCode: Int) {
                    if (_binding == null || !isAdded || seq != activeSearchSeq) return
                    if (errorCode != AMapException.CODE_AMAP_SUCCESS) {
                        Log.e(TAG, "PoiSearchV2 failed: code=$errorCode")
                        showSearchStatus(ctx.describeAmapError(errorCode))
                        return
                    }
                    val pois = result?.pois.orEmpty().mapNotNull { item ->
                        item.toPoi(mapViewModel.currentLocation)?.let { SearchResultItem.Located(it) }
                    }
                    if (pois.isNotEmpty()) {
                        renderSearchResults(pois)
                    } else if (forwardGeocodeSearch != null) {
                        forwardGeocodeSearch?.getFromLocationNameAsyn(
                            GeocodeQuery(keyword, MainActivity.mCityString.orEmpty())
                        )
                    } else {
                        val msg = fallbackErrorCode?.let { ctx.describeAmapError(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?: "未找到匹配地点，可尝试更完整的名称或直接输入经纬度"
                        showSearchStatus(msg)
                    }
                }

                override fun onPoiItemSearched(item: PoiItemV2?, errorCode: Int) {}

                override fun onVisualSearched(result: VisualSearchResult?, errorCode: Int) {}
            })
            poiSearch.searchPOIAsyn()
        } catch (e: Exception) {
            Log.e(TAG, "PoiSearchV2 exception", e)
            showSearchStatus("搜索初始化失败：${e.message ?: "请检查网络与高德配置"}")
        }
    }

    private fun applySelectedPoi(poi: Poi) {
        val wgs = poi.latitude to poi.longitude
        mapViewModel.markName = poi.name.ifBlank { poi.address }
        mapViewModel.markedLoc = wgs
        val selection = MapSelection(wgs, wgs.gcj02, mapViewModel.markName)
        mapViewModel.selectedMarker = mapViewModel.amap.showSelection(
            selection,
            mapViewModel.mMapIndicator,
            showInfoWindow = false
        )
        mapViewModel.amap.animateTo(selection.gcjLocation, 17f)
        mapViewModel.selectedSelection.value = selection
    }

    private fun showSearchLoading() {
        if (_binding == null) return
        binding.homeSearchProgress.visibility = View.VISIBLE
        binding.homeSearchStatusText.visibility = View.GONE
        showSearchResultsCard()
    }

    private fun renderSearchResults(items: List<SearchResultItem>) {
        if (_binding == null) return
        binding.homeSearchProgress.visibility = View.GONE
        binding.homeSearchStatusText.visibility = View.GONE
        binding.homeSearchResultsList.visibility = View.VISIBLE
        searchAdapter.submitList(items)
        showSearchResultsCard()
    }

    private fun showSearchStatus(message: String) {
        if (_binding == null) return
        binding.homeSearchProgress.visibility = View.GONE
        binding.homeSearchResultsList.visibility = View.GONE
        binding.homeSearchStatusText.text = message
        binding.homeSearchStatusText.visibility = View.VISIBLE
        showSearchResultsCard()
    }

    private fun showSearchResultsCard() {
        binding.homeChipRow.animateSlideFadeOut(offsetYDp = -6f, durationMs = 130L)
        binding.homeSearchResultsCard.animateSlideFadeIn(offsetYDp = -10f, durationMs = 200L, startScale = 0.985f)
    }

    private fun hideSearchResults() {
        binding.homeChipRow.animateSlideFadeIn(offsetYDp = -6f, durationMs = 190L, startScale = 0.99f)
        binding.homeSearchProgress.visibility = View.GONE
        binding.homeSearchResultsCard.animateSlideFadeOut(offsetYDp = -8f, durationMs = 140L)
    }

    private fun setupBottomDockActions() {
        binding.btnCloseSelection.setOnClickListener {
            mapViewModel.selectedSelection.value = null
            mapViewModel.markedLoc = null
            mapViewModel.amap.clear()
            mapViewModel.selectedMarker = null
        }

        binding.btnCopyCoords.setOnClickListener {
            val selection = mapViewModel.selectedSelection.value
            val coords = if (selection != null) {
                String.format(
                    Locale.US,
                    "%.6f, %.6f",
                    selection.wgsLocation.first,
                    selection.wgsLocation.second
                )
            } else {
                binding.selectionCoords.text?.toString().orEmpty().removePrefix("WGS84").trim()
            }
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("wgs84_coords", coords))
            Toast.makeText(requireContext(), R.string.coords_copied, Toast.LENGTH_SHORT).show()
        }

        binding.btnSaveSelection.setOnClickListener {
            if (!showAddLocationDialog()) {
                Toast.makeText(requireContext(), "选择位置异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnMockHere.setOnClickListener {
            val selection = mapViewModel.selectedSelection.value ?: return@setOnClickListener
            startOrUpdateMockAt(selection)
        }

        binding.btnLiveRocker.setOnClickListener {
            toggleRockerFromHome()
        }

        binding.btnLiveStop.setOnClickListener {
            stopMockFromHome()
        }
    }

    private fun selectMapPoint(wgsLoc: Pair<Double, Double>, gcjLoc: LatLng, moveCamera: Boolean) {
        mapViewModel.markedLoc = wgsLoc
        mapViewModel.markName = null
        val initialSelection = MapSelection(wgsLoc, gcjLoc, getString(R.string.current_location_loading))
        mapViewModel.selectedMarker = mapViewModel.amap.showSelection(
            initialSelection,
            mapViewModel.mMapIndicator,
            showInfoWindow = false
        )
        mapViewModel.selectedSelection.value = initialSelection
        if (moveCamera) {
            mapViewModel.amap.animateTo(gcjLoc, 17f)
        }
        mapViewModel.geocodeSearch?.getFromLocationAsyn(
            RegeocodeQuery(
                LatLonPoint(gcjLoc.latitude, gcjLoc.longitude),
                200f,
                GeocodeSearch.AMAP
            )
        )
    }

    @SuppressLint("SetTextI18n")
    private fun renderSelectionSheet(selection: MapSelection) {
        val displayAddr = selection.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.none_none)
        binding.selectionTitle.text = displayAddr.substringBefore(",").take(28)
        binding.selectionAddress.text = displayAddr
        binding.selectionCoords.text = String.format(
            Locale.US,
            "WGS84  %.6f, %.6f",
            selection.wgsLocation.first,
            selection.wgsLocation.second
        )
        binding.homeLivePill.animateSlideFadeOut(offsetYDp = 12f, durationMs = 130L)
        binding.homeSelectionSheet.animateSlideFadeIn(offsetYDp = 24f, durationMs = 250L, startScale = 0.97f)
        updateMockVisualState()
    }

    private fun hideSelectionSheet() {
        binding.homeSelectionSheet.animateSlideFadeOut(offsetYDp = 18f, durationMs = 160L) {
            updateMockVisualState()
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateMockVisualState() {
        if (_binding == null) return
        val running = mockServiceViewModel.mockRunning.value == true
        val dotColor = if (running) {
            ContextCompat.getColor(requireContext(), R.color.awalocation_success)
        } else {
            MaterialColors.getColor(binding.homeSearchStatusDot, com.google.android.material.R.attr.colorOutline)
        }
        binding.homeSearchStatusDot.backgroundTintList = ColorStateList.valueOf(dotColor)
        if (running) {
            binding.homeSearchStatusDot.startLivePulse()
        } else {
            binding.homeSearchStatusDot.stopLivePulse()
        }

        binding.btnMockHere.text = getString(
            if (running) R.string.switch_here else R.string.mock_here
        )

        val selectionVisible = mapViewModel.selectedSelection.value != null
        if (running && !selectionVisible) {
            val targetName = mockServiceViewModel.selectedLocation?.name
                ?: requireContext().selectLocation?.name
                ?: mockServiceViewModel.selectedRoute?.name
                ?: getString(R.string.status_running)
            val kmh = String.format(Locale.US, "%.1f km/h", requireContext().speed * 3.6)
            binding.liveStatusTitle.text = targetName
            binding.liveStatusSubtitle.text = "${getString(R.string.status_running)} · $kmh"
            binding.homeLivePill.animateSlideFadeIn(offsetYDp = 18f, durationMs = 230L, startScale = 0.97f)
            binding.liveStatusDot.startLivePulse()
        } else {
            binding.liveStatusDot.stopLivePulse()
            binding.homeLivePill.animateSlideFadeOut(offsetYDp = 12f, durationMs = 150L)
        }
    }

    private fun startOrUpdateMockAt(selection: MapSelection) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        val lm = mockServiceViewModel.locationManager ?: run {
            Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
            return
        }
        if (!MockServiceHelper.isServiceInit()) {
            Toast.makeText(requireContext(), "系统服务注入失败", Toast.LENGTH_SHORT).show()
            return
        }

        val loadingPlaceholder = getString(R.string.current_location_loading)
        val addr = selection.name
            ?.takeUnless { it.isBlank() || it == loadingPlaceholder }
            ?: getString(R.string.map_picked_point)
        val locItem = HistoricalLocation(
            name = addr.take(24),
            address = addr,
            lat = selection.wgsLocation.first,
            lon = selection.wgsLocation.second
        )
        mockServiceViewModel.selectedLocation = locItem
        requireContext().selectLocation = locItem

        binding.btnMockHere.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val context = requireContext()
                val speed = context.speed
                val altitude = context.altitude
                val accuracy = context.accuracy
                val success = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(lm)) {
                        if (!MockServiceHelper.tryOpenMock(lm, speed, altitude, accuracy)) {
                            return@withContext false
                        }
                    }
                    MockServiceHelper.setLocation(lm, locItem.lat, locItem.lon) &&
                        MockServiceHelper.broadcastLocation(lm)
                }
                if (success) {
                    mockServiceViewModel.refreshMockState()
                    Toast.makeText(requireContext(), "已模拟到所选位置", Toast.LENGTH_SHORT).show()
                    mapViewModel.selectedSelection.value = null
                } else {
                    Toast.makeText(requireContext(), "模拟启动或更新失败", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _binding?.btnMockHere?.isEnabled = true
            }
        }
    }

    private fun toggleRockerFromHome() {
        with(mockServiceViewModel) {
            if (locationManager == null) {
                Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                return
            }
            if (!isServiceStart()) {
                Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                return
            }
            if (!requireContext().drawOverOtherAppsEnabled() || !OverlayUtils.hasOverlayPermissions(requireContext())) {
                Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                return
            }
            if (rocker.isStart) {
                rocker.hide()
                rockerCoroutineController.pause()
            } else {
                if (!rocker.show()) {
                    Toast.makeText(requireContext(), "悬浮摇杆打开失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
                }
            }
            refreshMockState()
        }
    }

    private fun stopMockFromHome() {
        val lm = mockServiceViewModel.locationManager ?: return
        if (!MockServiceHelper.isServiceInit()) return
        binding.btnLiveStop.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val closed = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(lm)) return@withContext true
                    MockServiceHelper.tryCloseMock(lm)
                }
                if (closed) {
                    mockServiceViewModel.rocker.autoStatus = false
                    mockServiceViewModel.routeMockCoroutine.pause()
                    mockServiceViewModel.routeStage = 0
                    if (mockServiceViewModel.rocker.isStart) {
                        mockServiceViewModel.rocker.hide()
                        mockServiceViewModel.rockerCoroutineController.pause()
                    }
                    mockServiceViewModel.refreshMockState()
                    Toast.makeText(requireContext(), "已停止位置模拟", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "模拟服务停止失败", Toast.LENGTH_SHORT).show()
                }
            } finally {
                _binding?.btnLiveStop?.isEnabled = true
            }
        }
    }

    private fun clearSearchFocus() {
        binding.homeSearchInput.clearFocus()
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.homeSearchInput.windowToken, 0)
    }

    private fun hideMapLoading() {
        val loading = _binding?.homeMapLoading ?: return
        if (loading.visibility != View.VISIBLE) return
        loading.animate().cancel()
        loading.animate()
            .alpha(0f)
            .scaleX(1.03f)
            .scaleY(1.03f)
            .setDuration(260L)
            .setInterpolator(MotionUtils.EMPHASIZED_DECELERATE)
            .withEndAction {
                loading.visibility = View.GONE
                loading.scaleX = 1f
                loading.scaleY = 1f
            }
            .start()
    }

    private fun previewRoute(points: List<Pair<Double, Double>>) {
        mapViewModel.amap.clear()
        mapViewModel.selectedMarker = null

        if (points.size < 2) return
        val lineColor = MaterialColors.getColor(
            binding.root,
            com.google.android.material.R.attr.colorPrimary,
            Color.argb(200, 30, 100, 240)
        )
        mapViewModel.amap.addPolyline(
            PolylineOptions()
                .color(lineColor)
                .width(12f)
                .addAll(points.map { it.gcj02 })
        )
    }

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddLocationDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_location, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etLocationName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editAddress = dialogView.findViewById<TextInputEditText>(R.id.etLocationAddress)
        editAddress.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editAddress.error = "地址不能为空"
            }
        }
        fun parseLatLon(raw: String): Pair<Double, Double>? {
            val cleaned = raw.trim().removePrefix("WGS84").removePrefix("wgs84").trim()
            val latLonArray = cleaned.split(",")
            if (latLonArray.size != 2) return null
            val lat = latLonArray[0].trim().toDoubleOrNull()
            val lon = latLonArray[1].trim().toDoubleOrNull()
            return if (checkLatLon(lat, lon)) lat!! to lon!! else null
        }

        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)
        editLatLon.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editLatLon.error = "经纬度不能为空"
            } else if (parseLatLon(it.toString()) == null) {
                editLatLon.error = "经纬度格式错误（纬度, 经度）"
            }
        }

        val loadingPlaceholder = getString(R.string.current_location_loading)
        with(mapViewModel) {
            if (markedLoc == null) {
                currentLocation?.let {
                    showDetailView = false
                    val gcj = it.gcj02
                    geocodeSearch?.getFromLocationAsyn(
                        RegeocodeQuery(
                            LatLonPoint(gcj.latitude, gcj.longitude),
                            200f,
                            GeocodeSearch.AMAP
                        )
                    )
                    markedLoc = it
                }
                editName.setText("当前位置-" + System.currentTimeMillis() % 10000)
            } else {
                val baseName = selectedSelection.value?.name
                    ?.takeUnless { it.isBlank() || it == loadingPlaceholder }
                    ?.take(14)
                editName.setText(baseName ?: ("收藏点-" + System.currentTimeMillis() % 10000))
            }

            val lat = BigDecimal.valueOf(markedLoc?.first ?: return false)
            val lon = BigDecimal.valueOf(markedLoc?.second ?: return false)

            val resolvedAddress = selectedSelection.value?.name
                ?.takeUnless { it.isBlank() || it == loadingPlaceholder }
                ?: markName?.takeUnless { it.isBlank() || it == loadingPlaceholder }
                ?: getString(R.string.map_picked_point)
            editAddress.setText(resolvedAddress)
            editLatLon.setText("${lat.toPlainString()}, ${lon.toPlainString()}")

            MaterialAlertDialogBuilder(requireContext())
                .setCancelable(false)
                .setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val parsed = parseLatLon(editLatLon.text?.toString().orEmpty())
                    if (parsed == null) {
                        Toast.makeText(requireContext(), "经纬度格式错误（纬度, 经度）", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val (newLat, newLon) = parsed
                    val baseName = editName.text?.toString()?.trim()
                    val address = editAddress.text?.toString()?.trim()
                    if (baseName.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (address.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "地址不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }

                    fun MutableSet<String>.addLocation(
                        name: String,
                        address: String,
                        lat: Double,
                        lon: Double
                    ): Boolean {
                        if (any { runCatching { HistoricalLocation.fromString(it).name }.getOrNull() == name }) {
                            return false
                        }
                        add(HistoricalLocation(name, address, lat, lon).toString())
                        return true
                    }

                    with(requireContext()) {
                        val locations = rawHistoricalLocations.toMutableSet()
                        var candidateName: String = baseName
                        var count = 0
                        while (!locations.addLocation(candidateName, address, newLat, newLon)) {
                            candidateName = "$baseName(${++count})"
                        }
                        rawHistoricalLocations = locations
                    }

                    Toast.makeText(requireContext(), "位置已保存", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        return true
    }

    @SuppressLint("MissingInflatedId")
    private fun showInputCoordinatesDialog() {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_coordinates, null)

        val latitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLatitude)
        val longitudeEditText = dialogView.findViewById<TextInputEditText>(R.id.editTextLongitude)

        (mapViewModel.markedLoc ?: mapViewModel.currentLocation)?.let {
            latitudeEditText.setText(BigDecimal.valueOf(it.first).toPlainString())
            longitudeEditText.setText(BigDecimal.valueOf(it.second).toPlainString())
        }

        latitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                latitudeEditText.error = "纬度不能为空"
            } else {
                val lat = it.toString().toDoubleOrNull()
                if (lat == null || lat !in -90.0..90.0) {
                    latitudeEditText.error = "纬度格式错误"
                }
            }
        }

        longitudeEditText.addTextChangedListener {
            if (it.isNullOrBlank()) {
                longitudeEditText.error = "经度不能为空"
            } else {
                val lon = it.toString().toDoubleOrNull()
                if (lon == null || lon !in -180.0..180.0) {
                    longitudeEditText.error = "经度格式错误"
                }
            }
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                runCatching {
                    val latitude = latitudeEditText.text.toString()
                    val longitude = longitudeEditText.text.toString()

                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) {
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            throw IllegalArgumentException("Invalid latitude or longitude")
                        }
                        val wgs = lat to lon
                        val gcj = Loc4j.wgs2gcj(lat, lon)
                        selectMapPoint(wgs, LatLng(gcj.first, gcj.second), moveCamera = true)
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT).show()
                    }
                }.onFailure {
                    Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun markMap() = with(mapViewModel) {
        val wgsLoc = markedLoc ?: return
        val gcjLoc = wgsLoc.gcj02
        selectedMarker = amap.showSelection(
            MapSelection(wgsLoc, gcjLoc, markName),
            mMapIndicator,
            showInfoWindow = false
        )
    }

    override fun onResume() {
        super.onResume()
        _binding?.let {
            mapViewModel.amap = it.mapView.map
            mapViewModel.isExists = true
            it.mapView.onResume()
        }
        mockServiceViewModel.refreshMockState()
        val pending = mapViewModel.pendingFocusLocation
        if (pending != null && hasCenteredOnLocation) {
            mapViewModel.pendingFocusLocation = null
            selectMapPoint(pending, pending.gcj02, moveCamera = true)
        }
    }

    override fun onPause() {
        super.onPause()
        _binding?.mapView?.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        _binding?.mapView?.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        searchDebounceJob?.cancel()
        inputtips = null
        forwardGeocodeSearch = null
        runCatching {
            onceLocationClient?.stopLocation()
            onceLocationClient?.onDestroy()
        }
        onceLocationClient = null
        _binding?.let {
            it.homeSearchStatusDot.stopLivePulse()
            it.liveStatusDot.stopLivePulse()
            val map = it.mapView.map
            if (mapViewModel.isExists && mapViewModel.amap === map) {
                mapViewModel.isExists = false
            }
            map.setMyLocationEnabled(false)
            it.mapView.onDestroy()
        }
        mapViewModel.selectedMarker = null
        _binding = null
    }

    private sealed interface SearchResultItem {
        data class Located(val poi: Poi) : SearchResultItem
        data class KeywordSuggestion(val keyword: String, val subtitle: String) : SearchResultItem
    }

    private class SearchResultAdapter(
        private val onClick: (SearchResultItem) -> Unit
    ) : RecyclerView.Adapter<SearchResultAdapter.PoiViewHolder>() {
        private val items = mutableListOf<SearchResultItem>()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newItems: List<SearchResultItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PoiViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.layout_search_poi_item, parent, false)
            return PoiViewHolder(view)
        }

        override fun onBindViewHolder(holder: PoiViewHolder, position: Int) {
            val item = items[position]
            when (item) {
                is SearchResultItem.Located -> {
                    holder.name.text = item.poi.name
                    holder.address.text = item.poi.address.ifBlank { item.poi.name }
                    holder.tag.visibility = if (item.poi.tag.isBlank()) View.GONE else View.VISIBLE
                    holder.tag.text = item.poi.tag
                }

                is SearchResultItem.KeywordSuggestion -> {
                    holder.name.text = item.keyword
                    holder.address.text = item.subtitle
                    holder.tag.visibility = View.VISIBLE
                    holder.tag.text = "检索更多"
                }
            }
            holder.itemView.setOnClickListener { onClick(item) }
        }

        override fun getItemCount(): Int = items.size

        class PoiViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.poi_name)
            val address: TextView = view.findViewById(R.id.poi_address)
            val tag: TextView = view.findViewById(R.id.poi_tag)
        }
    }

    companion object {
        private const val TAG = "HomeFragment"
    }
}
