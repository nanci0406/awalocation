package com.nanci.awalocation.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.R
import com.nanci.awalocation.android.ui.animateChildrenEntrance
import com.nanci.awalocation.databinding.FragmentSettingsBinding
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.debug
import com.nanci.awalocation.ext.disableFusedProvider
import com.nanci.awalocation.ext.disableGetCurrentLocation
import com.nanci.awalocation.ext.disableRegisterLocationListener
import com.nanci.awalocation.ext.disableWifiScan
import com.nanci.awalocation.ext.dynamicColorEnabled
import com.nanci.awalocation.ext.enableStepMock
import com.nanci.awalocation.ext.loopBroadcastlocation
import com.nanci.awalocation.ext.minSatelliteCount
import com.nanci.awalocation.ext.needDowngradeToCdma
import com.nanci.awalocation.ext.needOpenSELinux
import com.nanci.awalocation.ext.reportDuration
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.ext.stepCadence
import com.nanci.awalocation.ext.themeMode
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel
import java.util.Locale

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()
    private var updatingSliderProgrammatically = false
    private var revertingWlanSwitch = false

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val baseTop = resources.getDimensionPixelSize(R.dimen.page_top_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.settingsContentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top + baseTop)
            insets
        }

        val context = requireContext()

        val currentThemeButtonId = when (context.themeMode) {
            AppCompatDelegate.MODE_NIGHT_NO -> R.id.theme_mode_light
            AppCompatDelegate.MODE_NIGHT_YES -> R.id.theme_mode_dark
            else -> R.id.theme_mode_system
        }
        binding.themeModeGroup.check(currentThemeButtonId)
        binding.themeModeGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val newMode = when (checkedId) {
                R.id.theme_mode_light -> AppCompatDelegate.MODE_NIGHT_NO
                R.id.theme_mode_dark -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            if (context.themeMode == newMode) return@addOnButtonCheckedListener
            context.themeMode = newMode
            AppCompatDelegate.setDefaultNightMode(newMode)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            binding.dynamicColorSwitch.isChecked = context.dynamicColorEnabled
            binding.dynamicColorSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
                if (context.dynamicColorEnabled == isChecked) return@setOnCheckedChangeListener
                context.dynamicColorEnabled = isChecked
                buttonView.post { activity?.recreate() }
            }
        } else {
            binding.dynamicColorDivider.visibility = View.GONE
            binding.dynamicColorRow.visibility = View.GONE
        }

        binding.selinuxSwitch.isChecked = context.needOpenSELinux
        binding.selinuxSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.needOpenSELinux = isChecked
            showToast(if (isChecked) "启动模拟时将置为 SELinux 宽容模式" else "已关闭 SELinux 宽容模式切换")
        }

        binding.altitudeValue.text = "%.2f米".format(Locale.US, context.altitude)
        applySpeedUi(context.speed)
        binding.accuracyValue.text = "%.2f米".format(Locale.US, context.accuracy)
        binding.reportDurationValue.text = "%dms".format(Locale.US, context.reportDuration)
        binding.satelliteCountValue.text = "%d颗".format(Locale.US, context.minSatelliteCount)

        binding.speedSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingSliderProgrammatically) return@addOnChangeListener
            applySpeedFromKmh(value.toDouble(), syncRemote = false)
        }
        binding.speedSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                applySpeedFromKmh(slider.value.toDouble(), syncRemote = true)
            }
        })

        binding.presetWalk.setOnClickListener { applySpeedFromKmh(5.0, syncRemote = true) }
        binding.presetBike.setOnClickListener { applySpeedFromKmh(15.0, syncRemote = true) }
        binding.presetDrive.setOnClickListener { applySpeedFromKmh(50.0, syncRemote = true) }

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", "%.2f".format(Locale.US, context.altitude)) {
                val value = it.trim().toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                FakeLoc.altitude = value
                binding.altitudeValue.text = "%.2f米".format(Locale.US, value)
                val lm = mockServiceViewModel.locationManager ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                if (lm != null && MockServiceHelper.isServiceInit()) {
                    lifecycleScope.launch(Dispatchers.IO) {
                        MockServiceHelper.setAltitude(lm, value)
                    }
                }
            }
        }

        binding.speedLayout.setOnClickListener {
            val currentKmh = "%.1f".format(Locale.US, context.speed * 3.6)
            showDialog("设置速度（公里/小时）", currentKmh) {
                val kmh = it.trim().toDoubleOrNull()
                if (kmh == null || kmh < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (kmh > 1200) {
                    showToast("速度不能超过1200公里/小时")
                    return@showDialog
                }
                applySpeedFromKmh(kmh, syncRemote = true)
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", "%.2f".format(Locale.US, context.accuracy)) {
                val value = it.trim().toFloatOrNull()
                if (value == null || value < 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                FakeLoc.accuracy = value
                binding.accuracyValue.text = "%.2f米".format(Locale.US, value)
                if (MockServiceHelper.isServiceInit()) {
                    updateRemoteConfig()
                }
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.debug = isChecked
            showToast(if (isChecked) "已开启调试模式" else "已关闭调试模式")
            updateRemoteConfig()
        }

        binding.dgcSwitch.isChecked = !context.disableGetCurrentLocation
        binding.dgcSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.disableGetCurrentLocation = !isChecked
            showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
            updateRemoteConfig()
        }

        binding.rllSwitch.isChecked = !context.disableRegisterLocationListener
        binding.rllSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.disableRegisterLocationListener = !isChecked
            showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
            updateRemoteConfig()
        }

        binding.dfusedSwitch.isChecked = context.disableFusedProvider
        binding.dfusedSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.disableFusedProvider = isChecked
            showToast(if (isChecked) "已禁用FusedProvider" else "已启用FusedProvider")
            updateRemoteConfig()
        }

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.needDowngradeToCdma = isChecked
            showToast(if (isChecked) "已降级为CDMA" else "已取消降级为CDMA")
            updateRemoteConfig()
        }

        binding.reportDurationLayout.setOnClickListener {
            showDialog("设置上报间隔", context.reportDuration.toString()) {
                val value = it.trim().toIntOrNull()
                if (value == null || value < 20) {
                    Toast.makeText(context, "上报间隔不能小于20ms", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "上报间隔不能大于1s", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                binding.reportDurationValue.text = "%dms".format(Locale.US, value)
                showToast("已更新上报间隔")
            }
        }

        binding.satelliteCountLayout.setOnClickListener {
            showDialog("设置最小模拟卫星数量", context.minSatelliteCount.toString()) {
                val value = it.trim().toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "数量不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 35) {
                    Toast.makeText(context, "卫星数量不能超过35", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.minSatelliteCount = value
                binding.satelliteCountValue.text = "%d颗".format(Locale.US, value)
                showToast("重新启动模拟生效")
                updateRemoteConfig()
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { buttonView, isChecked ->
            if (revertingWlanSwitch) return@setOnCheckedChangeListener
            val appCtx = context.applicationContext
            appCtx.disableWifiScan = isChecked
            lifecycleScope.launch {
                val lm = mockServiceViewModel.locationManager
                    ?: (appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                val errorMsg = withContext(Dispatchers.IO) {
                    if (lm == null) return@withContext "定位服务加载异常"
                    if (!MockServiceHelper.isServiceInit()) {
                        MockServiceHelper.tryInitService(lm)
                    }
                    if (!MockServiceHelper.isServiceInit()) return@withContext "系统服务注入失败"
                    val ok = if (isChecked) {
                        MockServiceHelper.startWifiMock(lm)
                    } else {
                        MockServiceHelper.stopWifiMock(lm)
                    }
                    if (!ok) {
                        if (isChecked) "禁用WLAN扫描失败: 无法连接到系统服务" else "启用WLAN扫描失败: 无法连接到系统服务"
                    } else {
                        null
                    }
                }
                if (errorMsg != null) {
                    appCtx.disableWifiScan = !isChecked
                    revertingWlanSwitch = true
                    buttonView.isChecked = !isChecked
                    revertingWlanSwitch = false
                    showToast(errorMsg)
                }
            }
        }

        binding.stepMockSwitch.isChecked = context.enableStepMock
        binding.stepMockSwitch.setOnCheckedChangeListener { _, isChecked ->
            context.enableStepMock = isChecked
            FakeLoc.enableMockStep = isChecked
            lifecycleScope.launch {
                val lm = mockServiceViewModel.locationManager
                    ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                if (lm == null) {
                    showToast("定位服务加载异常")
                    return@launch
                }

                val ok = try {
                    withContext(Dispatchers.IO) {
                        if (!MockServiceHelper.isServiceInit()) {
                            MockServiceHelper.tryInitService(lm)
                        }
                        when {
                            !MockServiceHelper.isServiceInit() -> false
                            isChecked -> MockServiceHelper.startStepMock(lm, context.stepCadence)
                            else -> MockServiceHelper.stopStepMock(lm)
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                    false
                }
                if (!ok) {
                    showToast(if (isChecked) "步频模拟启动失败" else "步频模拟停止失败")
                }
            }
        }

        applyCadenceUi(context.stepCadence)
        binding.cadenceSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || updatingSliderProgrammatically) return@addOnChangeListener
            binding.stepCadenceValue.text = formatStepCadence(value)
        }
        binding.cadenceSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                applyCadence(slider.value)
            }
        })
        binding.cadenceAutoBtn.setOnClickListener {
            applyCadence(0f)
        }

        binding.stepCadenceLayout.setOnClickListener {
            val current = context.stepCadence
            showDialog("设置模拟步频", if (current <= 0f) "" else "%d".format(Locale.US, current.toInt())) {
                val trimmed = it.trim()
                val value = if (trimmed.isEmpty()) {
                    0f
                } else {
                    trimmed.toFloatOrNull() ?: run {
                        showToast("步频不合法")
                        return@showDialog
                    }
                }
                if (value < 0) {
                    showToast("步频不合法")
                    return@showDialog
                } else if (value > 300) {
                    showToast("步频不能超过300步/分")
                    return@showDialog
                }
                applyCadence(value)
            }
        }

        binding.loopBroadcastLocationSwitch.isChecked = requireContext().loopBroadcastlocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().loopBroadcastlocation = isChecked
            showToast("重启模拟生效")
        }

        if (savedInstanceState == null) {
            binding.settingsContentRoot.animateChildrenEntrance(maxCount = 7, staggerMs = 30L)
        }
        return root
    }

    private fun applySpeedFromKmh(kmh: Double, syncRemote: Boolean) {
        val context = requireContext()
        val mps = kmh / 3.6
        context.speed = mps
        FakeLoc.speed = mps
        applySpeedUi(mps)
        if (syncRemote) {
            val lm = mockServiceViewModel.locationManager
                ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            if (lm != null && MockServiceHelper.isServiceInit()) {
                lifecycleScope.launch(Dispatchers.IO) {
                    MockServiceHelper.setSpeed(lm, mps.toFloat())
                }
            }
        }
    }

    private fun applySpeedUi(mps: Double) {
        binding.speedValue.text = formatSpeed(mps)
        val kmh = (mps * 3.6).toFloat().coerceIn(1f, 100f)
        val snapped = kotlin.math.round(kmh).coerceIn(1f, 100f)
        updatingSliderProgrammatically = true
        binding.speedSlider.value = snapped
        updatingSliderProgrammatically = false
    }

    private fun applyCadence(value: Float) {
        val context = requireContext()
        context.stepCadence = value
        FakeLoc.stepCadence = value
        applyCadenceUi(value)
        lifecycleScope.launch {
            val lm = mockServiceViewModel.locationManager
                ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            if (lm == null) {
                showToast("定位服务加载异常")
                return@launch
            }
            val ok = try {
                withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isServiceInit()) {
                        MockServiceHelper.tryInitService(lm)
                    }
                    when {
                        !MockServiceHelper.isServiceInit() -> false
                        context.enableStepMock -> MockServiceHelper.startStepMock(lm, value)
                        else -> MockServiceHelper.setStepCadence(lm, value)
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (!ok) {
                showToast("步频设置失败")
            }
        }
    }

    private fun applyCadenceUi(value: Float) {
        binding.stepCadenceValue.text = formatStepCadence(value)
        val sliderVal = if (value <= 0f) 120f else (kotlin.math.round(value / 5f) * 5f).coerceIn(60f, 220f)
        updatingSliderProgrammatically = true
        binding.cadenceSlider.value = sliderVal
        updatingSliderProgrammatically = false
        binding.cadenceAutoBtn.alpha = if (value <= 0f) 1f else 0.72f
    }

    private fun formatSpeed(mps: Double): String {
        val kmh = mps * 3.6
        return "%.1f km/h".format(Locale.US, kmh)
    }

    private fun formatStepCadence(cadence: Float): String {
        return if (cadence <= 0f) "自动" else "%d步/分".format(Locale.US, cadence.toInt())
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRemoteConfig() {
        val appCtx = requireContext().applicationContext
        val lm = mockServiceViewModel.locationManager
            ?: (appCtx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
        if (lm == null) {
            showToast("定位服务加载异常")
            return
        }
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                if (!MockServiceHelper.isServiceInit()) {
                    MockServiceHelper.tryInitService(lm)
                }
                if (!MockServiceHelper.isServiceInit()) {
                    return@withContext "系统服务注入失败"
                }
                if (MockServiceHelper.putConfig(lm, appCtx)) {
                    "同步配置成功"
                } else {
                    "更新远程配置失败"
                }
            }
            showToast(result)
        }
    }

    @SuppressLint("MissingInflatedId")
    private fun showDialog(titleText: String, valueText: String, handler: (String) -> Unit) {
        val inflater = LayoutInflater.from(requireContext())
        val dialogView = inflater.inflate(R.layout.dialog_input, null)

        val title = dialogView.findViewById<TextView>(R.id.title)
        title.text = titleText

        val value = dialogView.findViewById<TextInputEditText>(R.id.value)
        value.setText(valueText)

        MaterialAlertDialogBuilder(requireContext())
            .setCancelable(false)
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                handler(value.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
