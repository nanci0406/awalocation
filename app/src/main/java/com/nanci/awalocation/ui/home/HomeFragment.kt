package com.nanci.awalocation.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nanci.awalocation.MainActivity
import com.nanci.awalocation.R
import com.nanci.awalocation.amap.MapSelection
import com.nanci.awalocation.amap.animateTo
import com.nanci.awalocation.amap.setupMap
import com.nanci.awalocation.amap.showSelection
import com.nanci.awalocation.amap.zoomIn
import com.nanci.awalocation.amap.zoomOut
import com.nanci.awalocation.databinding.FragmentHomeBinding
import com.nanci.awalocation.ext.Loc4j
import com.nanci.awalocation.ext.gcj02
import com.nanci.awalocation.ext.mapType
import com.nanci.awalocation.ext.rawHistoricalLocations
import com.nanci.awalocation.ext.selectRoute
import com.nanci.awalocation.ext.wgs84
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel
import com.nanci.awalocation.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.math.BigDecimal

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null

    // This property is only valid between onCreateView and
    // onDestroyView.
    private val binding get() = _binding!!

    private val homeViewModel by viewModels<HomeViewModel>()
    private val mapViewModel by activityViewModels<AmapMapViewModel>()
    private var currentLocationGeocoded = false
    private var hasCenteredOnLocation = false

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        val root: View = binding.root
        binding.mapView.onCreate(savedInstanceState)
        currentLocationGeocoded = false
        hasCenteredOnLocation = false

        // 首次定位前先盖住地图，避免先闪一下默认城市。
        binding.homeMapLoading.visibility = View.VISIBLE
        binding.homeMapLoading.alpha = 1f

        binding.homeSearchCard.setOnClickListener {
            (activity as? MainActivity)?.openSearch()
        }
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

        // Fixed the issue that the Fab was opening incorrectly after switching back to Home for Fragments
        homeViewModel.mFabOpened = false

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
                // 高德地图回调为 GCJ-02，内部仍以 WGS84 存储。
                mapViewModel.markedLoc = loc.wgs84
                mapViewModel.showDetailView = false
                mapViewModel.geocodeSearch?.getFromLocationAsyn(
                    RegeocodeQuery(
                        LatLonPoint(loc.latitude, loc.longitude),
                        200f,
                        GeocodeSearch.AMAP
                    )
                )
                markMap()
            }

            setOnMapLongClickListener { loc ->
                mapViewModel.markedLoc = loc.wgs84
                mapViewModel.showDetailView = true
                mapViewModel.geocodeSearch?.getFromLocationAsyn(
                    RegeocodeQuery(
                        LatLonPoint(loc.latitude, loc.longitude),
                        200f,
                        GeocodeSearch.AMAP
                    )
                )
                markMap()
            }

            setOnMyLocationChangeListener { location ->
                if (location == null) return@setOnMyLocationChangeListener
                val gcjLocation = LatLng(location.latitude, location.longitude)
                mapViewModel.currentLocation = location.wgs84
                mapViewModel.currentLocationGcj = gcjLocation

                if (!hasCenteredOnLocation) {
                    hasCenteredOnLocation = true
                    animateTo(gcjLocation, 17f)
                    hideMapLoading()
                }

                val geocodeSearch = mapViewModel.geocodeSearch
                if (!currentLocationGeocoded && geocodeSearch != null) {
                    currentLocationGeocoded = true
                    geocodeSearch.getFromLocationAsyn(
                        RegeocodeQuery(
                            LatLonPoint(location.latitude, location.longitude),
                            200f,
                            GeocodeSearch.AMAP
                        )
                    )
                }
            }

            binding.mapTypeGroup.check(
                when (currentMapType) {
                    AMap.MAP_TYPE_NORMAL -> R.id.map_type_normal
                    AMap.MAP_TYPE_SATELLITE -> R.id.map_type_satellite
                    else -> R.id.map_type_normal
                }
            )
        }

        mapViewModel.currentLocation?.let { cached ->
            hasCenteredOnLocation = true
            mapViewModel.currentLocationGcj = cached.gcj02
            binding.mapView.post {
                mapViewModel.amap.moveCamera(CameraUpdateFactory.newLatLngZoom(cached.gcj02, 17f))
                hideMapLoading()
            }
            if (!currentLocationGeocoded) {
                mapViewModel.geocodeSearch?.let { geocodeSearch ->
                    currentLocationGeocoded = true
                    geocodeSearch.getFromLocationAsyn(
                        RegeocodeQuery(
                            LatLonPoint(cached.gcj02.latitude, cached.gcj02.longitude),
                            200f,
                            GeocodeSearch.AMAP
                        )
                    )
                }
            }
        }

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mapType = when (checkedId) {
                R.id.map_type_normal -> AMap.MAP_TYPE_NORMAL
                R.id.map_type_satellite -> AMap.MAP_TYPE_SATELLITE
                else -> {
                    Log.e("HomeFragment", "Unknown location view mode: $checkedId")
                    AMap.MAP_TYPE_NORMAL
                }
            }
            binding.mapView.map.setMapType(mapType)
            context?.mapType = mapType
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabMyLocation,
                binding.fabGoto,
                binding.fabAdd
            )

            if (!homeViewModel.mFabOpened) {
                homeViewModel.mFabOpened = true

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 0f
                    fab.scaleX = 0f
                    fab.scaleY = 0f
                    fab.translationX = 0f
                    val translationX =
                        ObjectAnimator.ofFloat(fab, "translationX", 0f, 16f + index * 6f)
                    translationX.duration = 200
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 0f, 1f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 0f, 1f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 0f, 1f)
                    alpha.duration = 200
                    animators.add(translationX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            } else {
                homeViewModel.mFabOpened = false

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transX = ObjectAnimator.ofFloat(fab, "translationX", fab.translationX, 0f)
                    transX.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", fab.scaleX, 0f)
                    scaleX.duration = 150
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", fab.scaleY, 0f)
                    scaleY.duration = 150
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", fab.alpha, 0f)
                    alpha.duration = 150
                    animators.add(transX)
                    animators.add(scaleX)
                    animators.add(scaleY)
                    animators.add(alpha)
                }

                val animatorSet = AnimatorSet()
                animatorSet.playTogether(animators.toList())
                animatorSet.interpolator = DecelerateInterpolator()
                animatorSet.addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        subFabList.forEach {
                            it.visibility = View.GONE
                            it.translationX = 0f
                        }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        binding.fabMyLocation.setOnClickListener {
            val current = mapViewModel.currentLocation
            if (current != null) {
                mapViewModel.amap.animateTo(current.gcj02, 17f)
            } else {
                Toast.makeText(requireContext(), "尚未获取到当前位置", Toast.LENGTH_SHORT).show()
            }
        }

        binding.fabGoto.setOnClickListener {
            showInputCoordinatesDialog()
        }

        binding.fabAdd.setOnClickListener {
            if (!showAddLocationDialog()) {
                Toast.makeText(requireContext(), "选择位置异常", Toast.LENGTH_SHORT).show()
            }
        }

        binding.showRoute.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                requireContext().selectRoute?.route?.let {
                    previewRoute(it)
                    // 选中路线后，将视角移动到起点
                    mapViewModel.amap.animateTo(it.first().gcj02, 17f)
                }
            } else {
                mapViewModel.amap.clear()
                mapViewModel.selectedMarker = null
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

        viewLifecycleOwner.lifecycleScope.launch {
            delay(3500)
            if (_binding == null) return@launch
            hideMapLoading()
            if (mapViewModel.currentAddress.value.isNullOrBlank()) {
                binding.homeCurrentAddress.text = getString(R.string.current_location_unknown)
            }
        }
    }

    private fun hideMapLoading() {
        val loading = _binding?.homeMapLoading ?: return
        if (loading.visibility != View.VISIBLE) return
        loading.animate()
            .alpha(0f)
            .setDuration(220L)
            .withEndAction { loading.visibility = View.GONE }
            .start()
    }

    private fun previewRoute(points: kotlin.collections.List<Pair<Double, Double>>) {
        mapViewModel.amap.clear() // 清除之前的所有覆盖物
        mapViewModel.selectedMarker = null

        if (points.size < 2) return
        mapViewModel.amap.addPolyline(
            PolylineOptions()
                .color(Color.argb(178, 0, 78, 255))
                .width(10f)
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
        val editLatLon = dialogView.findViewById<TextInputEditText>(R.id.etLocationLatLon)
        editLatLon.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editLatLon.error = "经纬度不能为空"
            } else {
                val latLonArray = it.toString().split(",")
                if (latLonArray.size != 2) {
                    editLatLon.error = "经纬度格式错误"
                } else {
                    val lon = latLonArray[0].trim().toDoubleOrNull()
                    val lat = latLonArray[1].trim().toDoubleOrNull()
                    if (!checkLatLon(lat, lon)) {
                        editLatLon.error = "经纬度格式错误"
                    }
                }
            }
        }

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
                editName.setText("当前位置-" + System.currentTimeMillis())
            } else {
                editName.setText("标点位置-" + System.currentTimeMillis())
            }

            val lat = BigDecimal.valueOf(markedLoc?.first ?: return false)
            val lon = BigDecimal.valueOf(markedLoc?.second ?: return false)

            editAddress.setText(markName ?: "位置地址")
            editLatLon.setText("${lon.toPlainString()}, ${lat.toPlainString()}")

            val builder = MaterialAlertDialogBuilder(requireContext())
            builder.setTitle(null)
            builder
                .setCancelable(false)
                .setView(dialogView)
                .setPositiveButton("保存") { _, _ ->
                    val latLonArray = editLatLon.text.toString().split(",")
                    val newLon = latLonArray[0].trim().toDoubleOrNull()
                    val newLat = latLonArray[1].trim().toDoubleOrNull()
                    var name = editName.text?.toString()
                    val address = editAddress.text?.toString()
                    if (name.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (address.isNullOrBlank()) {
                        Toast.makeText(requireContext(), "地址不能为空", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    if (!checkLatLon(newLat, newLon)) {
                        Toast.makeText(requireContext(), "经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }

                    fun MutableSet<String>.addLocation(
                        name: String,
                        address: String,
                        lat: Double,
                        lon: Double
                    ): Boolean {
                        if (any { it.split(",")[0] == name }) {
                            return false
                        }
                        add(
                            "$name,$address,${
                                BigDecimal.valueOf(lat).toPlainString()
                            },${BigDecimal.valueOf(lon).toPlainString()}"
                        )
                        return true
                    }

                    with(requireContext()) {
                        val locations = rawHistoricalLocations.toMutableSet()
                        var count = 0
                        while (!locations.addLocation(name!!, address, newLat!!, newLon!!)) {
                            name = "$name(${++count})"
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

        mapViewModel.currentLocation?.let {
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

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle("输入经纬度(WGS84)")
            .setView(dialogView)
            .setPositiveButton("确定") { _, _ ->
                kotlin.runCatching {
                    val latitude = latitudeEditText.text.toString()
                    val longitude = longitudeEditText.text.toString()

                    if (latitude.isNotEmpty() && longitude.isNotEmpty()) with(mapViewModel) {
                        val lat = latitude.toDoubleOrNull()
                        val lon = longitude.toDoubleOrNull()
                        if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                            throw IllegalArgumentException("Invalid latitude or longitude")
                        }

                        this.markedLoc = lat to lon

                        markMap(true)

                        val gcj = Loc4j.wgs2gcj(lat, lon)
                        geocodeSearch?.getFromLocationAsyn(
                            RegeocodeQuery(
                                LatLonPoint(gcj.first, gcj.second),
                                200f,
                                GeocodeSearch.AMAP
                            )
                        )
                    } else {
                        Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                            .show()
                    }
                }.onFailure {
                    Toast.makeText(requireContext(), "请输入有效的经纬度！", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun markMap(moveEyes: Boolean = false) = with(mapViewModel) {
        val wgsLoc = markedLoc ?: return
        val gcjLoc = wgsLoc.gcj02
        selectedMarker = amap.showSelection(
            MapSelection(wgsLoc, gcjLoc, markName),
            mMapIndicator
        )

        if (moveEyes) {
            amap.animateTo(gcjLoc, 17f)
        }
    }

    override fun onResume() {
        super.onResume()

        if (_binding != null) {
            mapViewModel.amap = binding.mapView.map
            mapViewModel.isExists = true
            binding.mapView.onResume()
        }
    }

    override fun onPause() {
        super.onPause()

        if (_binding != null) {
            binding.mapView.onPause()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)

        if (_binding != null) {
            // Switching fragments causes the _binding to be empty, and the method is inexplicably triggered,
            // which does not conform to the google's lifecycle diagram representation
            binding.mapView.onSaveInstanceState(outState)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding?.let {
            val map = it.mapView.map
            // 只有当前 Fragment 的地图仍是共享地图时才清状态，避免切换页面时把新页面覆盖掉。
            if (mapViewModel.isExists && mapViewModel.amap === map) {
                mapViewModel.isExists = false
            }
            map.setMyLocationEnabled(false)
            it.mapView.onDestroy()
        }
        mapViewModel.selectedMarker = null
        _binding = null
    }
}
