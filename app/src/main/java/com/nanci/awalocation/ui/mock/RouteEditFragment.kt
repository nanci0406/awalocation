package com.nanci.awalocation.ui.mock

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.findNavController
import com.alibaba.fastjson2.JSON
import com.alibaba.fastjson2.JSONObject
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.PolylineOptions
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeQuery
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nanci.awalocation.R
import com.nanci.awalocation.amap.MapSelection
import com.nanci.awalocation.amap.animateTo
import com.nanci.awalocation.amap.isValidAmapLocation
import com.nanci.awalocation.amap.setupMap
import com.nanci.awalocation.amap.showSelection
import com.nanci.awalocation.amap.zoomIn
import com.nanci.awalocation.amap.zoomOut
import com.nanci.awalocation.android.ui.animateScalePop
import com.nanci.awalocation.android.ui.animateSlideFadeIn
import com.nanci.awalocation.android.ui.animateSlideFadeOut
import com.nanci.awalocation.databinding.FragmentRouteEditBinding
import com.nanci.awalocation.ext.gcj02
import com.nanci.awalocation.ext.getHistoricalRoutesList
import com.nanci.awalocation.ext.mapType
import com.nanci.awalocation.ext.saveHistoricalRoutesList
import com.nanci.awalocation.ext.wgs84
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel

class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val mapViewModel by activityViewModels<AmapMapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var hasCenteredOnLocation = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)
        binding.mapView.onCreate(savedInstanceState)
        hasCenteredOnLocation = false
        isDrawing = false

        val topBase = resources.getDimensionPixelSize(R.dimen.home_top_overlay_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.routeEditTopBar) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top + topBase)
            insets
        }
        val bottomBase = resources.getDimensionPixelSize(R.dimen.page_top_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.routeEditBottomDock) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.updatePadding(bottom = bars.bottom + bottomBase)
            insets
        }

        binding.btnBack.setOnClickListener {
            activity?.findNavController(R.id.nav_host_fragment_content_main)?.navigateUp()
        }

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
                if (!location.isValidAmapLocation()) return@setOnMyLocationChangeListener
                val validLoc = location!!
                mapViewModel.currentLocation = validLoc.wgs84
                mapViewModel.currentLocationGcj = LatLng(validLoc.latitude, validLoc.longitude)
                if (!hasCenteredOnLocation) {
                    hasCenteredOnLocation = true
                    animateTo(LatLng(validLoc.latitude, validLoc.longitude), 17f)
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

        binding.mapView.post {
            mapViewModel.currentLocation?.let { cached ->
                hasCenteredOnLocation = true
                mapViewModel.amap.moveCamera(
                    CameraUpdateFactory.newLatLngZoom(cached.gcj02, 17f)
                )
            }
        }
        binding.zoomIn.setOnClickListener { mapViewModel.amap.zoomIn() }
        binding.zoomOut.setOnClickListener { mapViewModel.amap.zoomOut() }

        binding.mapTypeGroup.setOnCheckedChangeListener { _, checkedId ->
            val mapType = when (checkedId) {
                R.id.map_type_normal -> AMap.MAP_TYPE_NORMAL
                R.id.map_type_satellite -> AMap.MAP_TYPE_SATELLITE
                else -> {
                    Log.e("RouteEditFragment", "Unknown location view mode: $checkedId")
                    AMap.MAP_TYPE_NORMAL
                }
            }
            binding.mapView.map.setMapType(mapType)
            context?.mapType = mapType
        }

        binding.routeDrawOverlay.setOnTouchListener { _, event ->
            if (!isDrawing) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_MOVE -> {
                    moveRouteCursor(event.x, event.y)
                    true
                }

                MotionEvent.ACTION_UP -> {
                    moveRouteCursor(event.x, event.y)
                    addRoutePointAtCursor()
                    true
                }

                MotionEvent.ACTION_CANCEL -> true
                else -> false
            }
        }

        binding.fabStart.setOnClickListener {
            beginRouteDrawing()
        }

        binding.fabRollback.setOnClickListener {
            if (mPoints.size > 0) {
                mPoints.removeAt(mPoints.size - 1)
                refresh()
                updateDrawHint()
            }
        }

        binding.fabComplete.setOnClickListener {
            if (mPoints.size < 2) {
                Toast.makeText(requireContext(), "至少需要两个路径点", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (!showAddRouteDialog()) {
                Toast.makeText(requireContext(), "选择路线异常", Toast.LENGTH_SHORT).show()
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

        return binding.root
    }

    private fun beginRouteDrawing() {
        if (isDrawing) {
            centerCursorOnMap()
            return
        }
        isDrawing = true
        mPoints = arrayListOf()
        mapViewModel.amap.clear()
        mapViewModel.selectedMarker = null
        setMapGesturesEnabled(false)

        binding.fabStart.text = "重置光标"
        binding.routeDrawOverlay.visibility = View.VISIBLE
        binding.routeDrawHint.animateSlideFadeIn(offsetYDp = -8f, durationMs = 220L, startScale = 0.96f)
        updateDrawHint()
        binding.routeDrawOverlay.post {
            centerCursorOnMap()
            binding.routeCursor.animateScalePop(peakScale = 1.18f, durationMs = 220L)
        }
    }

    private fun endRouteDrawing() {
        isDrawing = false
        setMapGesturesEnabled(true)
        binding.fabStart.text = "开始绘制"
        binding.routeDrawOverlay.visibility = View.GONE
        _binding?.routeDrawHint?.animateSlideFadeOut(offsetYDp = -8f, durationMs = 150L)
    }

    private fun setMapGesturesEnabled(enabled: Boolean) {
        val settings = mapViewModel.amap.uiSettings
        settings.isScrollGesturesEnabled = enabled
        settings.isZoomGesturesEnabled = enabled
    }

    private fun centerCursorOnMap() {
        if (_binding == null) return
        val target = mapViewModel.amap.cameraPosition.target
        val screenPoint = mapViewModel.amap.projection.toScreenLocation(target)
        moveRouteCursor(screenPoint.x.toFloat(), screenPoint.y.toFloat())
    }

    private fun moveRouteCursor(x: Float, y: Float) {
        if (_binding == null) return
        val overlay = binding.routeDrawOverlay
        val cursor = binding.routeCursor
        if (overlay.width == 0 || cursor.width == 0) return

        val halfWidth = cursor.width / 2f
        val halfHeight = cursor.height / 2f
        cursor.x = (x - halfWidth).coerceIn(0f, (overlay.width - cursor.width).coerceAtLeast(0).toFloat())
        cursor.y = (y - halfHeight).coerceIn(0f, (overlay.height - cursor.height).coerceAtLeast(0).toFloat())
    }

    private fun cursorWgs(): Pair<Double, Double>? {
        if (_binding == null) return null
        val cursor = binding.routeCursor
        val point = Point(
            (cursor.x + cursor.width / 2f).toInt(),
            (cursor.y + cursor.height / 2f).toInt()
        )
        return mapViewModel.amap.projection.fromScreenLocation(point).wgs84
    }

    private fun addRoutePointAtCursor() {
        val wgs = cursorWgs() ?: return
        if (mPoints.lastOrNull() == wgs) return
        mPoints.add(wgs)
        binding.routeCursor.animateScalePop(peakScale = 1.16f, durationMs = 170L)
        refresh()
        updateDrawHint()
    }

    private fun updateDrawHint() {
        if (_binding == null) return
        binding.routeDrawHintText.text = if (mPoints.isEmpty()) {
            getString(R.string.route_draw_hint)
        } else {
            getString(R.string.route_draw_points, mPoints.size)
        }
    }

    private fun refresh() {
        mapViewModel.amap.clear()
        mapViewModel.selectedMarker = null

        if (mPoints.size >= 2) {
            val lineColor = MaterialColors.getColor(
                binding.root,
                com.google.android.material.R.attr.colorPrimary,
                Color.argb(200, 30, 100, 240)
            )
            mapViewModel.amap.addPolyline(
                PolylineOptions()
                    .color(lineColor)
                    .width(12f)
                    .addAll(mPoints.map { it.gcj02 })
            )
        }
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

    @SuppressLint("SetTextI18n", "MissingInflatedId", "MutatingSharedPrefs")
    private fun showAddRouteDialog(): Boolean {
        fun checkLatLon(lat: Double?, lon: Double?): Boolean {
            return (lat != null && lon != null) && lat in -90.0..90.0 && lon in -180.0..180.0
        }

        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_add_route, null)
        val editName = dialogView.findViewById<TextInputEditText>(R.id.etRouteName)
        editName.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editName.error = "名称不能为空"
            }
        }
        val editRoute = dialogView.findViewById<TextInputEditText>(R.id.etRouteSet)
        editRoute.addTextChangedListener {
            if (it.isNullOrBlank()) {
                editRoute.error = "路线经纬度不能为空"
            } else {
                try {
                    val json = it.toString()
                    val points = JSON.parseArray(json)
                    if (points.size < 2) {
                        editRoute.error = "路线经纬度至少需要两个点"
                    }
                    for (point in points) {
                        val jsonObject = point as JSONObject
                        val latitude = jsonObject.getDouble("first")
                        val longitude = jsonObject.getDouble("second")
                        if (!checkLatLon(latitude, longitude)) {
                            editRoute.error = "路线经纬度格式错误"
                            return@addTextChangedListener
                        }
                    }
                } catch (_: Exception) {
                    editRoute.error = "路线经纬度json格式错误"
                }
            }
        }

        editRoute.setText(JSON.toJSONString(mPoints))

        MaterialAlertDialogBuilder(requireContext())
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val routeJson = editRoute.text.toString()
                val name = editName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val points = try {
                    JSON.parseArray(routeJson)
                } catch (_: Exception) {
                    null
                }
                if (points == null || points.size < 2) {
                    Toast.makeText(requireContext(), "路线经纬度格式错误或点数不足2个", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                val parsedPoints = mutableListOf<Pair<Double, Double>>()
                for (point in points) {
                    val jsonObject = point as? JSONObject
                    val latitude = jsonObject?.getDouble("first")
                    val longitude = jsonObject?.getDouble("second")
                    if (latitude == null || longitude == null || !checkLatLon(latitude, longitude)) {
                        Toast.makeText(requireContext(), "路线经纬度格式错误", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    parsedPoints.add(Pair(latitude, longitude))
                }

                with(requireContext()) {
                    val routesList = getHistoricalRoutesList()
                    val existingIndex = routesList.indexOfFirst { it.name == name }
                    val newRoute = HistoricalRoute(name, parsedPoints)
                    if (existingIndex >= 0) {
                        routesList[existingIndex] = newRoute
                    } else {
                        routesList.add(newRoute)
                    }
                    saveHistoricalRoutesList(routesList)
                }

                endRouteDrawing()
                Toast.makeText(requireContext(), "路线已保存", Toast.LENGTH_SHORT).show()
                activity?.findNavController(R.id.nav_host_fragment_content_main)?.navigateUp()
            }
            .setNegativeButton("取消", null)
            .show()

        return true
    }

    override fun onResume() {
        super.onResume()
        _binding?.let {
            mapViewModel.amap = it.mapView.map
            mapViewModel.isExists = true
            it.mapView.onResume()
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
        isDrawing = false
        _binding?.let {
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
}
