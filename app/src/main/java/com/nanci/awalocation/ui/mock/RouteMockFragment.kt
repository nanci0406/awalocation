package com.nanci.awalocation.ui.mock

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.R
import com.nanci.awalocation.android.widget.RockerView
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.FragmentRouteMockBinding
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.drawOverOtherAppsEnabled
import com.nanci.awalocation.ext.getHistoricalRoutesList
import com.nanci.awalocation.ext.saveHistoricalRoutesList
import com.nanci.awalocation.ext.selectRoute
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.viewmodel.HomeViewModel
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel
import com.nanci.awalocation.xposed.utils.FakeLoc
import androidx.navigation.findNavController

class RouteMockFragment : Fragment() {
    private var historicalRouteAdapter: HistoricalRouteAdapter? = null
    private var _binding: FragmentRouteMockBinding? = null
    private val binding get() = _binding!!

    private val routeMockViewModel by viewModels<HomeViewModel>()
    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteMockBinding.inflate(inflater, container, false)

        if (mockServiceViewModel.isServiceStart()) {
            binding.switchMock.text = "停止模拟"
            ContextCompat.getDrawable(requireContext(), R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        }

        binding.switchMock.setOnClickListener {
            if (mockServiceViewModel.isServiceStart()) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            if (rocker.isStart) {
                binding.rocker.toggle()
            }
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val checkedTextView = it as CheckedTextView
                checkedTextView.toggle()

                if (!requireContext().drawOverOtherAppsEnabled()) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                lifecycleScope.launch(Dispatchers.Main) {
                    if (checkedTextView.isChecked) {
                        rocker.show()
                    } else {
                        rocker.hide()
                        rockerCoroutineController.pause()
                    }
                }
            }
            rocker.setRockerListener(object : RockerView.Companion.OnMoveListener {
                override fun onAngle(angle: Double) {
                    MockServiceHelper.setBearing(locationManager!!, angle)
                    FakeLoc.bearing = angle
                    FakeLoc.hasBearings = true
                }

                override fun onLockChanged(isLocked: Boolean) {
                    isRockerLocked = isLocked
                }

                override fun onFinished() {
                    if (!isRockerLocked) {
                        rockerCoroutineController.pause()
                    }
                }

                override fun onStarted() {
                    rockerCoroutineController.resume()
                }
            })
            rocker.setRockerAutoListener(object : Rocker.Companion.OnAutoListener {
                override fun onAutoPlay(isPlay: Boolean) {
                    if (isPlay) {
                        routeMockCoroutine.resume()
                    } else {
                        routeMockCoroutine.pause()
                    }
                }

                override fun onAutoLock(isLock: Boolean) {

                }
            })
        }

        requireContext().selectRoute?.let {
            binding.mockRouteName.text = it.name
            mockServiceViewModel.selectedRoute = it
            mockServiceViewModel.routeStage = 0
        }


        binding.fab.setOnClickListener { view ->
            val subFabList = arrayOf(
                binding.fabAddRoute
            )

            if (!routeMockViewModel.mFabOpened) {
                routeMockViewModel.mFabOpened = true

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 0f, 90f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    fab.visibility = View.VISIBLE
                    fab.alpha = 0f
                    fab.scaleX = 0f
                    fab.scaleY = 0f
                    fab.translationY = 24f
                    val transY = ObjectAnimator.ofFloat(fab, "translationY", 24f, 0f)
                    transY.duration = 200
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", 0f, 1f)
                    scaleX.duration = 200
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", 0f, 1f)
                    scaleY.duration = 200
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", 0f, 1f)
                    alpha.duration = 200
                    animators.add(transY)
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
                routeMockViewModel.mFabOpened = false

                val rotateMainFab = ObjectAnimator.ofFloat(view, "rotation", 90f, 0f)
                rotateMainFab.duration = 200

                val animators = arrayListOf<ObjectAnimator>()
                animators.add(rotateMainFab)
                subFabList.forEachIndexed { index, fab ->
                    val transY = ObjectAnimator.ofFloat(fab, "translationY", fab.translationY, 24f)
                    transY.duration = 150
                    val scaleX = ObjectAnimator.ofFloat(fab, "scaleX", fab.scaleX, 0f)
                    scaleX.duration = 150
                    val scaleY = ObjectAnimator.ofFloat(fab, "scaleY", fab.scaleY, 0f)
                    scaleY.duration = 150
                    val alpha = ObjectAnimator.ofFloat(fab, "alpha", fab.alpha, 0f)
                    alpha.duration = 150
                    animators.add(transY)
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
                            it.translationY = 0f
                        }
                        view.isClickable = true
                    }
                })
                view.isClickable = false
                animatorSet.start()
            }
        }

        binding.fabAddRoute.setOnClickListener {
            activity?.findNavController(R.id.nav_host_fragment_content_main)?.navigate(R.id.nav_route_edit)
        }

        val recyclerView = binding.historicalRouteList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        reloadRoutes()

        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return false
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.adapterPosition
                val location = historicalRouteAdapter?.get(position) ?: return
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除路线")
                        .setMessage("确定要删除路线(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            historicalRouteAdapter?.removeItem(position)
                            val list = getHistoricalRoutesList().apply {
                                removeIf { it.name == location.name }
                            }
                            saveHistoricalRoutesList(list)
                            showToast("已删除路线")
                        }
                        .setNegativeButton("取消", { _, _ ->
                            historicalRouteAdapter?.notifyItemChanged(position)
                        })
                        .show()
                }
            }
        }).attachToRecyclerView(recyclerView)

        return binding.root
    }


    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        val selectedRoute = mockServiceViewModel.selectedRoute ?: run {
            showToast("请选择一个路线")
            return
        }

        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            val context = requireContext()
            val speed = context.speed
            val altitude = context.altitude
            val accuracy = context.accuracy

            button.isClickable = false
            try {
                mockServiceViewModel.routeStage = 0
                withContext(Dispatchers.IO) {
                    if (MockServiceHelper.tryOpenMock(
                            mockServiceViewModel.locationManager!!,
                            speed,
                            altitude,
                            accuracy
                        )
                    ) {
                        updateMockButtonState(
                            button,
                            "停止模拟",
                            R.drawable.rounded_play_disabled_24
                        )
                    } else {
                        showToast("模拟服务启动失败")
                        return@withContext
                    }

                    val first = selectedRoute.route[0]
                    if (MockServiceHelper.setLocation(
                            mockServiceViewModel.locationManager!!,
                            first.first,
                            first.second
                        )
                    ) {
                        showToast("更新路线起点位置成功")
                    } else {
                        showToast("更新位置失败")
                    }
                }
            } finally {
                button.isClickable = true
            }
        }


    }

    private fun tryCloseService(button: MaterialButton) {
        if (mockServiceViewModel.locationManager == null) {
            showToast("定位服务加载异常")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val isClosed = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(mockServiceViewModel.locationManager!!)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    if (MockServiceHelper.tryCloseMock(mockServiceViewModel.locationManager!!)) {
                        updateMockButtonState(button, "开始模拟", R.drawable.rounded_play_arrow_24)
                        return@withContext true
                    } else {
                        showToast("模拟服务停止失败")
                        return@withContext false
                    }
                }
                if (isClosed && mockServiceViewModel.rocker.isStart) {
                    binding.rocker.isClickable = false
                    binding.rocker.toggle()
                    mockServiceViewModel.rocker.hide()
                    mockServiceViewModel.rockerCoroutineController.pause()
                    binding.rocker.isClickable = true
                }
            } finally {
                button.isClickable = true
            }
        }
    }


    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun updateMockButtonState(button: MaterialButton, text: String, iconRes: Int) =
        lifecycleScope.launch(Dispatchers.Main) {
            button.text = text
            ContextCompat.getDrawable(requireContext(), iconRes)?.let {
                button.icon = it
            }
        }


    override fun onResume() {
        super.onResume()
        reloadRoutes()
    }

    override fun onDestroyView() {
        runCatching { mockServiceViewModel.rocker.setRockerAutoListener(null) }
        super.onDestroyView()
        _binding = null
    }

    private fun reloadRoutes() {
        if (_binding == null || context == null) return
        val routes = requireContext().getHistoricalRoutesList()
        val sorted = routes.sortedBy { it.name }.toMutableList()
        historicalRouteAdapter = HistoricalRouteAdapter(sorted) { route, isLongClick ->
            if (isLongClick) {
                Toast.makeText(requireContext(), "长按", Toast.LENGTH_SHORT).show()
            } else {
                binding.mockRouteName.text = route.name
                mockServiceViewModel.selectedRoute = route
                mockServiceViewModel.routeStage = 0
                requireContext().selectRoute = route

                val lm = mockServiceViewModel.locationManager
                if (lm != null && MockServiceHelper.isMockStart(lm) && route.route.isNotEmpty()) {
                    val first = route.route[0]
                    if (MockServiceHelper.setLocation(lm, first.first, first.second)) {
                        Toast.makeText(requireContext(), "位置更新成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "更新位置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        binding.historicalRouteList.adapter = historicalRouteAdapter
    }
}