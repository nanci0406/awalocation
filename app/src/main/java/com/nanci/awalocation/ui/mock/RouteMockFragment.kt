package com.nanci.awalocation.ui.mock

import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.R
import com.nanci.awalocation.android.ui.animateChildrenEntrance
import com.nanci.awalocation.android.ui.animateScalePop
import com.nanci.awalocation.android.ui.animateStrokeColor
import com.nanci.awalocation.android.ui.startLivePulse
import com.nanci.awalocation.android.ui.stopLivePulse
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.FragmentRouteMockBinding
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.drawOverOtherAppsEnabled
import com.nanci.awalocation.ext.getHistoricalRoutesList
import com.nanci.awalocation.ext.routeLoop
import com.nanci.awalocation.ext.saveHistoricalRoutesList
import com.nanci.awalocation.ext.selectRoute
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel

class RouteMockFragment : Fragment() {
    private var historicalRouteAdapter: HistoricalRouteAdapter? = null
    private var _binding: FragmentRouteMockBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var lastRenderedRunning: Boolean? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRouteMockBinding.inflate(inflater, container, false)

        val baseTop = resources.getDimensionPixelSize(R.dimen.page_top_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.routeContentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top + baseTop)
            insets
        }

        binding.switchMock.setOnClickListener {
            if (mockServiceViewModel.isServiceStart()) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        with(mockServiceViewModel) {
            binding.rocker.isChecked = rocker.isStart
            binding.rocker.setOnClickListener {
                if (locationManager == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!isServiceStart()) {
                    Toast.makeText(requireContext(), "请先启动模拟", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                if (!requireContext().drawOverOtherAppsEnabled() || !OverlayUtils.hasOverlayPermissions(requireContext())) {
                    Toast.makeText(requireContext(), "请授权悬浮窗权限", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val checkedTextView = it as CheckedTextView

                lifecycleScope.launch(Dispatchers.Main) {
                    if (!rocker.isStart) {
                        val shown = rocker.show()
                        checkedTextView.isChecked = shown
                        if (!shown) {
                            Toast.makeText(requireContext(), "悬浮摇杆打开失败，请检查悬浮窗权限", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        rocker.hide()
                        rockerCoroutineController.pause()
                        checkedTextView.isChecked = false
                    }
                    refreshMockState()
                }
            }
        }

        val currentRoute = mockServiceViewModel.selectedRoute ?: requireContext().selectRoute
        if (currentRoute != null) {
            renderTargetRoute(currentRoute)
            if (mockServiceViewModel.selectedRoute == null) {
                mockServiceViewModel.selectedRoute = currentRoute
                mockServiceViewModel.resetRouteProgress()
            }
        } else {
            renderEmptyTargetRoute()
        }

        binding.routeLoop.isChecked = requireContext().routeLoop
        binding.routeLoop.setOnCheckedChangeListener { _, isChecked ->
            requireContext().routeLoop = isChecked
            showToast(if (isChecked) "已开启路线循环" else "已关闭路线循环")
        }

        binding.fabAddRoute.setOnClickListener {
            activity?.findNavController(R.id.nav_host_fragment_content_main)
                ?.navigate(R.id.action_route_gallery_to_route_edit)
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
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                @Suppress("DEPRECATION")
                val position = viewHolder.adapterPosition
                val adapter = historicalRouteAdapter ?: return
                if (position == RecyclerView.NO_POSITION || position >= adapter.itemCount) return
                val route = adapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除路线")
                        .setMessage("确定要删除路线(${route.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            if (position < adapter.itemCount) {
                                adapter.removeItem(position)
                            }
                            val list = getHistoricalRoutesList().apply {
                                removeIf { it.name == route.name }
                            }
                            saveHistoricalRoutesList(list)
                            if (mockServiceViewModel.selectedRoute?.name == route.name ||
                                selectRoute?.name == route.name
                            ) {
                                mockServiceViewModel.selectedRoute = null
                                mockServiceViewModel.stopRoutePlayback(resetStage = true)
                                selectRoute = null
                                renderEmptyTargetRoute()
                            }
                            updateEmptyState()
                            showToast("已删除路线")
                        }
                        .setNegativeButton("取消") { _, _ ->
                            adapter.notifyItemChanged(position)
                        }
                        .setOnCancelListener {
                            adapter.notifyItemChanged(position)
                        }
                        .show()
                }
            }
        }).attachToRecyclerView(recyclerView)

        mockServiceViewModel.mockRunning.observe(viewLifecycleOwner) { running ->
            renderRunningState(running == true)
        }
        mockServiceViewModel.rockerShowing.observe(viewLifecycleOwner) { showing ->
            if (_binding != null) {
                binding.rocker.isChecked = showing == true
            }
        }
        mockServiceViewModel.refreshMockState()

        if (savedInstanceState == null) {
            binding.routeContentRoot.animateChildrenEntrance(maxCount = 4, staggerMs = 35L)
        }

        return binding.root
    }

    private fun renderTargetRoute(route: HistoricalRoute) {
        if (_binding == null) return
        binding.mockRouteName.text = route.name
        binding.mockRouteSubtitle.text = "共 ${route.route.size} 个路径点"
    }

    private fun renderEmptyTargetRoute() {
        if (_binding == null) return
        binding.mockRouteName.text = getString(R.string.none_route)
        binding.mockRouteSubtitle.text = getString(R.string.none_none)
    }

    private fun renderRunningState(running: Boolean) {
        if (_binding == null) return
        val stateChanged = lastRenderedRunning != null && lastRenderedRunning != running
        lastRenderedRunning = running
        val ctx = requireContext()
        if (running) {
            binding.routeStatusPill.setBackgroundResource(R.drawable.bg_status_pill_running)
            binding.routeStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.awalocation_success)
            )
            binding.routeStatusDot.startLivePulse()
            binding.routeStatusText.text = getString(R.string.status_running)
            binding.routeStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.awalocation_on_success_container))
            binding.mockLocationCard.animateStrokeColor(
                MaterialColors.getColor(
                    binding.mockLocationCard,
                    com.google.android.material.R.attr.colorPrimary
                ),
                (1.5f * resources.displayMetrics.density).toInt()
            )
            binding.switchMock.text = getString(R.string.stop_mock)
            ContextCompat.getDrawable(ctx, R.drawable.rounded_play_disabled_24)?.let {
                binding.switchMock.icon = it
            }
        } else {
            binding.routeStatusPill.setBackgroundResource(R.drawable.bg_status_pill_idle)
            binding.routeStatusDot.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.routeStatusDot, com.google.android.material.R.attr.colorOutline)
            )
            binding.routeStatusDot.stopLivePulse()
            binding.routeStatusText.text = getString(R.string.status_idle)
            binding.routeStatusText.setTextColor(
                MaterialColors.getColor(binding.routeStatusText, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            binding.mockLocationCard.animateStrokeColor(
                MaterialColors.getColor(
                    binding.mockLocationCard,
                    com.google.android.material.R.attr.colorOutlineVariant
                ),
                (1f * resources.displayMetrics.density).toInt()
            )
            binding.switchMock.text = getString(R.string.start_mock)
            ContextCompat.getDrawable(ctx, R.drawable.rounded_play_arrow_24)?.let {
                binding.switchMock.icon = it
            }
        }
        if (stateChanged) {
            binding.routeStatusPill.animateScalePop(peakScale = 1.08f, durationMs = 220L)
        }
        binding.rocker.isChecked = mockServiceViewModel.rocker.isStart
    }

    private fun updateEmptyState() {
        val isEmpty = (historicalRouteAdapter?.itemCount ?: 0) == 0
        binding.emptyRoutesView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.historicalRouteList.visibility = if (isEmpty) View.GONE else View.VISIBLE
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
        if (selectedRoute.route.isEmpty()) {
            showToast("所选路线为空")
            return
        }
        if (selectedRoute.route.size < 2) {
            showToast("所选路线至少需要 2 个路径点")
            return
        }

        val lm = mockServiceViewModel.locationManager ?: run {
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
            if (speed <= 0.0) {
                showToast("当前模拟速度为 0，请先在设置中调整速度")
                return@launch
            }
            val altitude = context.altitude
            val accuracy = context.accuracy

            button.isClickable = false
            try {
                mockServiceViewModel.resetRouteProgress()
                val initialBearing = RouteStepper.initialBearing(selectedRoute.route, 0.0)
                val started = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.tryOpenMock(lm, speed, altitude, accuracy)) {
                        showToast("模拟服务启动失败")
                        return@withContext false
                    }
                    mockServiceViewModel.refreshMockState()

                    val first = selectedRoute.route[0]
                    if (!MockServiceHelper.setLocation(lm, first.first, first.second, initialBearing)) {
                        showToast("更新位置失败")
                        return@withContext false
                    }
                    true
                }
                if (started) {
                    mockServiceViewModel.startRoutePlayback()
                    showToast("已启动路线模拟，开始沿路线移动")
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    private fun tryCloseService(button: MaterialButton) {
        val lm = mockServiceViewModel.locationManager ?: run {
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
                mockServiceViewModel.stopRoutePlayback(resetStage = true)
                val isClosed = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isMockStart(lm)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    if (MockServiceHelper.tryCloseMock(lm)) {
                        mockServiceViewModel.refreshMockState()
                        return@withContext true
                    } else {
                        showToast("模拟服务停止失败")
                        return@withContext false
                    }
                }
                if (isClosed && mockServiceViewModel.rocker.isStart) {
                    binding.rocker.isClickable = false
                    binding.rocker.isChecked = false
                    mockServiceViewModel.rocker.hide()
                    mockServiceViewModel.rockerCoroutineController.pause()
                    mockServiceViewModel.refreshMockState()
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

    override fun onResume() {
        super.onResume()
        reloadRoutes()
        mockServiceViewModel.refreshMockState()
    }

    override fun onDestroyView() {
        _binding?.routeStatusDot?.stopLivePulse()
        lastRenderedRunning = null
        super.onDestroyView()
        _binding = null
    }

    private fun reloadRoutes() {
        if (_binding == null || context == null) return
        val routes = requireContext().getHistoricalRoutesList()
        val sorted = routes.sortedBy { it.name }.toMutableList()
        historicalRouteAdapter = HistoricalRouteAdapter(sorted) { route, isLongClick ->
            if (isLongClick) {
                Toast.makeText(requireContext(), "已选中路线：${route.name}", Toast.LENGTH_SHORT).show()
            } else {
                renderTargetRoute(route)
                binding.mockLocationCard.animateScalePop(peakScale = 1.02f, durationMs = 200L)
                mockServiceViewModel.selectedRoute = route
                mockServiceViewModel.resetRouteProgress()
                requireContext().selectRoute = route

                val lm = mockServiceViewModel.locationManager
                if (lm != null && MockServiceHelper.isMockStart(lm) && route.route.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val first = route.route[0]
                        val initialBearing = RouteStepper.initialBearing(route.route, 0.0)
                        val updated = withContext(Dispatchers.IO) {
                            MockServiceHelper.setLocation(lm, first.first, first.second, initialBearing)
                        }
                        if (updated) {
                            if (route.route.size >= 2 && requireContext().speed > 0.0) {
                                mockServiceViewModel.startRoutePlayback()
                                Toast.makeText(requireContext(), "已切换路线并开始移动", Toast.LENGTH_SHORT).show()
                            } else {
                                mockServiceViewModel.stopRoutePlayback(resetStage = true)
                                Toast.makeText(requireContext(), "已切换至路线起点", Toast.LENGTH_SHORT).show()
                            }
                        } else {
                            Toast.makeText(requireContext(), "更新位置失败", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        }
        binding.historicalRouteList.adapter = historicalRouteAdapter
        updateEmptyState()
    }
}
