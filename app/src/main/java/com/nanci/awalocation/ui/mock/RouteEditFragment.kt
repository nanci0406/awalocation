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
    private var lastPoint: Pair<Double, Double>? = null


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteEditBinding.inflate(inflater, container, false)
        binding.mapView.onCreate(savedInstanceState)

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

        mapViewModel.amap.setOnMapTouchListener { event ->
            if (isDrawing) {
                // 用触摸点而不是地图中心作为路径点，否则绘制出的路线会始终挤在屏幕中心。
                val currentPoint = mapViewModel.amap.projection
                    .fromScreenLocation(Point(event.x.toInt(), event.y.toInt()))
                    .wgs84

                when (event.action) {
                    MotionEvent.ACTION_DOWN -> { // 新增 DOWN 事件处理
                        if (mPoints.size <= 0) {
                            mPoints.add(currentPoint)
                        }
                        lastPoint = currentPoint
                    }

                    MotionEvent.ACTION_MOVE -> {
                        if (lastPoint == null) {
                            lastPoint = currentPoint
                        }
                        lastPoint?.let { lp -> drawLine(lp, currentPoint) }
                    }

                    MotionEvent.ACTION_UP -> {
                        mPoints.add(currentPoint)
                        lastPoint = null // 关键修改：重置起点
                    }
                }
            }
        }

        binding.fabStart.setOnClickListener {
            isDrawing = true;
            mPoints = arrayListOf()
            lastPoint = null; // 重置上一个点
        }

        binding.fabRollback.setOnClickListener {
            // 撤回上一个点并且刷新地图
            if (mPoints.size > 0) {
                mPoints.removeAt(mPoints.size - 1)
                refresh()
            }
        }

        binding.fabComplete.setOnClickListener {
            isDrawing = false
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

    private fun refresh() {
        mapViewModel.amap.clear() // 清除之前的所有覆盖物
        mapViewModel.selectedMarker = null

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            mapViewModel.amap.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(mPoints[i].gcj02, mPoints[i + 1].gcj02)
            )
        }
    }

    private fun drawLine(start: Pair<Double, Double>, end: Pair<Double, Double>) {
        mapViewModel.amap.clear() // 清除之前的所有覆盖物
        mapViewModel.selectedMarker = null

        // 绘制之前记录的点到点的线
        for (i in 0 until mPoints.size - 1) {
            mapViewModel.amap.addPolyline(
                PolylineOptions()
                    .color(Color.argb(178, 0, 78, 255))
                    .width(10f)
                    .add(mPoints[i].gcj02, mPoints[i + 1].gcj02)
            )
        }

        mapViewModel.amap.addPolyline(
            PolylineOptions()
                .color(Color.argb(178, 0, 78, 255))
                .width(10f)
                .add(start.gcj02, end.gcj02)
        )
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
