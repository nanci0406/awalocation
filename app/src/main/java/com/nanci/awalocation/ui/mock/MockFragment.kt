package com.nanci.awalocation.ui.mock

import android.annotation.SuppressLint
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
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.MainActivity
import com.nanci.awalocation.R
import com.nanci.awalocation.android.ui.animateChildrenEntrance
import com.nanci.awalocation.android.ui.animateScalePop
import com.nanci.awalocation.android.ui.animateStrokeColor
import com.nanci.awalocation.android.ui.startLivePulse
import com.nanci.awalocation.android.ui.stopLivePulse
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.FragmentMockBinding
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.drawOverOtherAppsEnabled
import com.nanci.awalocation.ext.historicalLocations
import com.nanci.awalocation.ext.rawHistoricalLocations
import com.nanci.awalocation.ext.selectLocation
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.viewmodel.AmapMapViewModel
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel

class MockFragment : Fragment() {
    private var _binding: FragmentMockBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private val mapViewModel by activityViewModels<AmapMapViewModel>()
    private lateinit var historicalLocationAdapter: HistoricalLocationAdapter
    private var lastRenderedRunning: Boolean? = null

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMockBinding.inflate(inflater, container, false)

        val baseTop = resources.getDimensionPixelSize(R.dimen.page_top_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.mockRoot) { view, insets ->
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

        val currentTarget = mockServiceViewModel.selectedLocation ?: requireContext().selectLocation
        if (currentTarget != null) {
            renderTargetLocation(currentTarget)
            mockServiceViewModel.selectedLocation = currentTarget
        } else {
            renderEmptyTargetLocation()
        }

        binding.mockLocationCard.setOnClickListener {
            val target = mockServiceViewModel.selectedLocation ?: requireContext().selectLocation
            if (target != null) {
                mapViewModel.pendingFocusLocation = target.lat to target.lon
                mapViewModel.markName = target.address
            }
            (activity as? MainActivity)?.switchToMapTab()
        }

        binding.btnGoPickMap.setOnClickListener {
            (activity as? MainActivity)?.switchToMapTab()
        }

        val locations = requireContext().historicalLocations
        historicalLocationAdapter = HistoricalLocationAdapter(locations.sortedBy { it.name }.toMutableList()) { loc, isLongClick ->
            if (isLongClick) {
                mapViewModel.pendingFocusLocation = loc.lat to loc.lon
                mapViewModel.markName = loc.address
                (activity as? MainActivity)?.switchToMapTab()
            } else {
                renderTargetLocation(loc)
                binding.mockLocationCard.animateScalePop(peakScale = 1.02f, durationMs = 200L)
                mockServiceViewModel.selectedLocation = loc
                requireContext().selectLocation = loc

                val lm = mockServiceViewModel.locationManager
                if (lm == null) {
                    Toast.makeText(requireContext(), "定位服务加载异常", Toast.LENGTH_SHORT).show()
                    CrashReport.postCatchedException(RuntimeException("运行时mockServiceViewModel.locationManager为空！"))
                    return@HistoricalLocationAdapter
                }

                if (MockServiceHelper.isMockStart(lm)) {
                    if (MockServiceHelper.setLocation(lm, loc.lat, loc.lon)) {
                        Toast.makeText(requireContext(), "位置更新成功", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(requireContext(), "更新位置失败", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        val recyclerView = binding.historicalLocationList
        recyclerView.layoutManager = LinearLayoutManager(requireContext())
        recyclerView.adapter = historicalLocationAdapter
        updateEmptyState()

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean = false

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                @Suppress("DEPRECATION")
                val position = viewHolder.adapterPosition
                if (position == RecyclerView.NO_POSITION || position >= historicalLocationAdapter.itemCount) return
                val location = historicalLocationAdapter[position]
                with(requireContext()) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("删除位置")
                        .setMessage("确定要删除位置(${location.name})吗？")
                        .setPositiveButton("删除") { _, _ ->
                            if (position < historicalLocationAdapter.itemCount) {
                                historicalLocationAdapter.removeItem(position)
                            }
                            rawHistoricalLocations = rawHistoricalLocations.toMutableSet().apply {
                                removeIf { runCatching { HistoricalLocation.fromString(it).name }.getOrNull() == location.name }
                            }
                            if (mockServiceViewModel.selectedLocation?.name == location.name ||
                                selectLocation?.name == location.name
                            ) {
                                mockServiceViewModel.selectedLocation = null
                                selectLocation = null
                                renderEmptyTargetLocation()
                            }
                            updateEmptyState()
                            showToast("已删除位置")
                        }
                        .setNegativeButton("取消") { _, _ ->
                            historicalLocationAdapter.notifyItemChanged(position)
                        }
                        .setOnCancelListener {
                            historicalLocationAdapter.notifyItemChanged(position)
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
            binding.mockRoot.animateChildrenEntrance(maxCount = 4, staggerMs = 35L)
        }

        return binding.root
    }

    @SuppressLint("SetTextI18n")
    private fun renderTargetLocation(loc: HistoricalLocation) {
        binding.mockLocationName.text = loc.name
        binding.mockLocationAddress.text = loc.address
        binding.mockLocationLatlon.text = "${loc.lat.toString().take(8)}, ${loc.lon.toString().take(8)}"
    }

    private fun renderEmptyTargetLocation() {
        if (_binding == null) return
        binding.mockLocationName.text = getString(R.string.none)
        binding.mockLocationAddress.text = getString(R.string.none_none)
        binding.mockLocationLatlon.text = getString(R.string.default_latlon)
    }

    private fun renderRunningState(running: Boolean) {
        if (_binding == null) return
        val stateChanged = lastRenderedRunning != null && lastRenderedRunning != running
        lastRenderedRunning = running
        val ctx = requireContext()
        if (running) {
            binding.mockStatusPill.setBackgroundResource(R.drawable.bg_status_pill_running)
            binding.mockStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.awalocation_success)
            )
            binding.mockStatusDot.startLivePulse()
            binding.mockStatusText.text = getString(R.string.status_running)
            binding.mockStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.awalocation_on_success_container))
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
            binding.mockStatusPill.setBackgroundResource(R.drawable.bg_status_pill_idle)
            binding.mockStatusDot.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.mockStatusDot, com.google.android.material.R.attr.colorOutline)
            )
            binding.mockStatusDot.stopLivePulse()
            binding.mockStatusText.text = getString(R.string.status_idle)
            binding.mockStatusText.setTextColor(
                MaterialColors.getColor(binding.mockStatusText, com.google.android.material.R.attr.colorOnSurfaceVariant)
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
            binding.mockStatusPill.animateScalePop(peakScale = 1.08f, durationMs = 220L)
        }
        binding.rocker.isChecked = mockServiceViewModel.rocker.isStart
    }

    private fun updateEmptyState() {
        val isEmpty = historicalLocationAdapter.itemCount == 0
        binding.emptyLocationsView.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.historicalLocationList.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        val selectedLocation = mockServiceViewModel.selectedLocation ?: run {
            showToast("请选择一个位置")
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
            val altitude = context.altitude
            val accuracy = context.accuracy

            button.isClickable = false
            try {
                mockServiceViewModel.stopRoutePlayback(resetStage = true)
                withContext(Dispatchers.IO) {
                    if (MockServiceHelper.tryOpenMock(lm, speed, altitude, accuracy)) {
                        mockServiceViewModel.refreshMockState()
                    } else {
                        showToast("模拟服务启动失败")
                        return@withContext
                    }

                    if (!MockServiceHelper.setLocation(lm, selectedLocation.lat, selectedLocation.lon)) {
                        showToast("更新位置失败")
                        return@withContext
                    }

                    if (MockServiceHelper.broadcastLocation(lm)) {
                        showToast("已启动位置模拟")
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

    override fun onResume() {
        super.onResume()
        mockServiceViewModel.refreshMockState()
    }

    override fun onDestroyView() {
        _binding?.mockStatusDot?.stopLivePulse()
        lastRenderedRunning = null
        super.onDestroyView()
        _binding = null
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }
}
