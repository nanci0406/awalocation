package com.nanci.awalocation.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Point
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.nanci.awalocation.R
import com.nanci.awalocation.amap.MapSelection
import com.nanci.awalocation.amap.animateTo
import com.nanci.awalocation.amap.setupMap
import com.nanci.awalocation.amap.showSelection
import com.nanci.awalocation.amap.zoomIn
import com.nanci.awalocation.amap.zoomOut
import com.nanci.awalocation.databinding.FragmentRouteEditBinding
import com.nanci.awalocation.ext.gcj02
import com.nanci.awalocation.ext.getHistoricalRoutesList
import com.nanci.awalocation.ext.saveHistoricalRoutesList
import com.nanci.awalocation.ext.mapType
import com.nanci.awalocation.ext.wgs84
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel
import com.nanci.awalocation.ui.viewmodel.HomeViewModel


class RouteEditFragment : Fragment() {
    private var _binding: FragmentRouteEditBinding? = null
    private val binding get() = _binding!!

    private val routeEditViewModel by viewModels<HomeViewModel>()
    private val mapViewModel by activityViewModels<AmapMapViewModel>()

    private var mPoints: ArrayList<Pair<Double, Double>> = arrayListOf()
    private var isDrawing = false
    private var hasCenteredOnLocation = false


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)
        binding.mapView.onCreate(savedInstanceState)
        hasCenteredOnLocation = false
        isDrawing = false

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
                if (location == null) return@setOnMyLocationChangeListener
                mapViewModel.currentLocation = location.wgs84
                mapViewModel.currentLocationGcj = LatLng(location.latitude, location.longitude)
                if (!hasCenteredOnLocation) {
                    hasCenteredOnLocation = true
                    animateTo(LatLng(location.latitude, location.longitude), 17f)
                }
            }

            binding.mapTypeGroup.check(
                when (currentMapType) {
                    AMap.MAP_TYPE_NORMAL -> com.nanci.awalocation.R.id.map_type_normal
                    AMap.MAP_TYPE_SATELLITE -> com.nanci.awalocation.R.id.map_type_satellite
                    else -> com.nanci.awalocation.R.id.map_type_normal
                }
            )
        }

        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabStart,
                binding.fabRollback,
                binding.fabComplete,
                binding.fabMyLocation
            )

            if (!routeEditViewModel.mFabOpened) {
                routeEditViewModel.mFabOpened = true

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
                routeEditViewModel.mFabOpened = false

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
                com.nanci.awalocation.R.id.map_type_normal -> AMap.MAP_TYPE_NORMAL
                com.nanci.awalocation.R.id.map_type_satellite -> AMap.MAP_TYPE_SATELLITE
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
            // 撤回上一个点并且刷新地图
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
            endRouteDrawing()
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

        binding.routeDrawOverlay.visibility = View.VISIBLE
        binding.routeDrawHint.visibility = View.VISIBLE
        binding.routeDrawHint.alpha = 0f
        binding.routeDrawHint.animate().alpha(1f).setDuration(180L).start()
        updateDrawHint()
        binding.routeDrawOverlay.post { centerCursorOnMap() }
    }

    private fun endRouteDrawing() {
        isDrawing = false
        setMapGesturesEnabled(true)
        binding.routeDrawOverlay.visibility = View.GONE
        _binding?.routeDrawHint?.animate()
            ?.alpha(0f)
            ?.setDuration(140L)
            ?.withEndAction {
                _binding?.routeDrawHint?.apply {
                    visibility = View.GONE
                    alpha = 1f
                }
            }
            ?.start()
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
        mapViewModel.amap.clear() // 清除之前的所有覆盖物
        mapViewModel.selectedMarker = null

        if (mPoints.size >= 2) {
            mapViewModel.amap.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .addAll(mPoints.map { it.gcj02 })
            )
        }
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
                    // 转为 LatLng 数组
                    val points = JSON.parseArray(json)
                    if (points.size < 2) {
                        editRoute.error = "路线经纬度至少需要两个点"
                    }
                    // 循环检查每个点的经纬度是否合法
                    for (point in points) {
                        val jsonObject = point as JSONObject
                        val latitude = jsonObject.getDouble("first")
                        val longitude = jsonObject.getDouble("second")
                        if (!checkLatLon(latitude, longitude)) {
                            editRoute.error = "路线经纬度格式错误"
                            return@addTextChangedListener
                        }
                    }
                } catch (e: Exception) {
                    editRoute.error = "路线经纬度json格式错误"
                }
            }
        }

        editRoute.setText(JSON.toJSONString(mPoints))

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val routeJson = editRoute.text.toString()

                var name = editName.text?.toString()
                if (name.isNullOrBlank()) {
                    Toast.makeText(requireContext(), "名称不能为空", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val points = JSON.parseArray(routeJson)
                if (points.size < 2) {
                    Toast.makeText(requireContext(), "路线经纬度至少需要两个点", Toast.LENGTH_SHORT)
                        .show()
                    return@setPositiveButton
                }

                // 循环检查每个点的经纬度是否合法
                for (point in points) {
                    val jsonObject = point as JSONObject
                    val latitude = jsonObject.getDouble("first")
                    val longitude = jsonObject.getDouble("second")
                    if (!checkLatLon(latitude, longitude)) {
                        Toast.makeText(requireContext(), "路线经纬度格式错误", Toast.LENGTH_SHORT)
                            .show()
                        return@setPositiveButton
                    }
                }

                val parsedPoints = mutableListOf<Pair<Double, Double>>()
                for (point in points) {
                    val jsonObject = point as JSONObject
                    val latitude = jsonObject.getDouble("first") ?: 0.0
                    val longitude = jsonObject.getDouble("second") ?: 0.0
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
