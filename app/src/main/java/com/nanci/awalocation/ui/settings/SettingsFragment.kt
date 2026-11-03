package com.nanci.awalocation.ui.settings

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.R
import com.nanci.awalocation.databinding.FragmentSettingsBinding
import com.nanci.awalocation.xposed.utils.FakeLoc
import com.nanci.awalocation.ext.accuracy
import com.nanci.awalocation.ext.altitude
import com.nanci.awalocation.ext.debug
import com.nanci.awalocation.ext.disableFusedProvider
import com.nanci.awalocation.ext.disableGetCurrentLocation
import com.nanci.awalocation.ext.disableRegisterLocationListener
import com.nanci.awalocation.ext.disableWifiScan
import com.nanci.awalocation.ext.enableStepMock
import com.nanci.awalocation.ext.loopBroadcastlocation
import com.nanci.awalocation.ext.minSatelliteCount
import com.nanci.awalocation.ext.needDowngradeToCdma
import com.nanci.awalocation.ext.needOpenSELinux
import com.nanci.awalocation.ext.reportDuration
import com.nanci.awalocation.ext.speed
import com.nanci.awalocation.ext.stepCadence
import com.nanci.awalocation.service.MockServiceHelper
import com.nanci.awalocation.ui.viewmodel.MockServiceViewModel
import com.nanci.awalocation.ui.viewmodel.SettingsViewModel

class SettingsFragment : Fragment() {
    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private val mockServiceViewModel by activityViewModels<MockServiceViewModel>()

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val settingsViewModel =
            ViewModelProvider(this)[SettingsViewModel::class.java]

        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val context = requireContext()
        binding.selinuxSwitch.isChecked = context.needOpenSELinux
        binding.selinuxSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needOpenSELinux = isChecked
                showToast(if (isChecked) "已开启SELinux，下次启动模拟生效" else "已关闭SELinux")
            }
        })

        binding.altitudeValue.text = "%.2f米".format(context.altitude)
        binding.speedValue.text = formatSpeed(context.speed)
        binding.accuracyValue.text = "%.2f米".format(context.accuracy)
        binding.reportDurationValue.text = "%dms".format(context.reportDuration)
        binding.satelliteCountValue.text = "%d颗".format(context.minSatelliteCount)

        binding.altitudeLayout.setOnClickListener {
            showDialog("设置海拔高度", binding.altitudeValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toDoubleOrNull()
                if (value == null || value < 0.0) {
                    showToast("海拔高度不合法")
                    return@showDialog
                } else if (value > 10000) {
                    showToast("海拔高度不能超过10000米")
                    return@showDialog
                }
                context.altitude = value
                binding.altitudeValue.text = "%.2f米".format(value)
            }
        }

        binding.speedLayout.setOnClickListener {
            val currentKmh = "%.1f".format(context.speed * 3.6)
            showDialog("设置速度（公里/小时）", currentKmh) {
                val kmh = it.toDoubleOrNull()
                if (kmh == null || kmh < 0.0) {
                    showToast("速度不合法")
                    return@showDialog
                } else if (kmh > 1200) {
                    showToast("速度不能超过1200公里/小时")
                    return@showDialog
                }
                val mps = kmh / 3.6
                context.speed = mps
                FakeLoc.speed = mps
                val lm = mockServiceViewModel.locationManager ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
                if (lm != null && MockServiceHelper.isServiceInit()) {
                    MockServiceHelper.setSpeed(lm, mps.toFloat())
                }
                binding.speedValue.text = formatSpeed(mps)
            }
        }

        binding.accuracyLayout.setOnClickListener {
            showDialog("设置精度", binding.accuracyValue.text.toString().let { it.substring(0, it.length - 1) }) {
                val value = it.toFloatOrNull()
                if (value == null || value < 0.0) {
                    Toast.makeText(context, "精度不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "精度不能超过1000米", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.accuracy = value
                binding.accuracyValue.text = "%.2f米".format(value)
                if (MockServiceHelper.isServiceInit()) {
                    updateRemoteConfig()
                }
            }
        }

        binding.debugSwitch.isChecked = context.debug
        binding.debugSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.debug = isChecked
                showToast(if (isChecked) "已开启调试模式" else "已关闭调试模式")
                updateRemoteConfig()
            }
        })

        binding.dgcSwitch.isChecked = !context.disableGetCurrentLocation
        binding.dgcSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableGetCurrentLocation = !isChecked
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.rllSwitch.isChecked = !context.disableRegisterLocationListener
        binding.rllSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableRegisterLocationListener = !isChecked
                showToast(if (!isChecked) "禁止应用使用该方法" else "已允许应用使用该方法")
                updateRemoteConfig()
            }
        })

        binding.dfusedSwitch.isChecked = context.disableFusedProvider
        binding.dfusedSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.disableFusedProvider = isChecked
                showToast(if (isChecked) "已禁用FusedProvider" else "已启用FusedProvider")
                updateRemoteConfig()
            }
        })

        binding.cdmaSwitch.isChecked = context.needDowngradeToCdma
        binding.cdmaSwitch.setOnCheckedChangeListener(object: CompoundButton.OnCheckedChangeListener {
            override fun onCheckedChanged(
                buttonView: CompoundButton,
                isChecked: Boolean
            ) {
                context.needDowngradeToCdma = isChecked
                showToast(if (isChecked) "已降级为CDMA" else "已取消降级为CDMA")
                updateRemoteConfig()
            }
        })

        binding.reportDurationLayout.setOnClickListener {
            showDialog("设置上报间隔", binding.reportDurationValue.text.toString().let {
                it.substring(0, it.length - 2)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 20) {
                    Toast.makeText(context, "上报间隔不能小于20ms", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 1000) {
                    Toast.makeText(context, "上报间隔不能大于1s", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.reportDuration = value
                binding.reportDurationValue.text = "%dms".format(value)
                showToast("重新启动APP生效")
            }
        }

        binding.satelliteCountLayout.setOnClickListener {
            showDialog("设置最小模拟卫星数量", binding.satelliteCountValue.text.toString().let {
                it.substring(0, it.length - 1)
            }) {
                val value = it.toIntOrNull()
                if (value == null || value < 0) {
                    Toast.makeText(context, "数量不合法", Toast.LENGTH_SHORT).show()
                    return@showDialog
                } else if (value > 35) {
                    Toast.makeText(context, "卫星数量不能超过35", Toast.LENGTH_SHORT).show()
                    return@showDialog
                }
                context.minSatelliteCount = value
                binding.satelliteCountValue.text = "%d颗".format(value)
                showToast("重新启动模拟生效")
                updateRemoteConfig()
            }
        }

        binding.disableWlanScanSwitch.isChecked = requireContext().disableWifiScan
        binding.disableWlanScanSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().disableWifiScan = isChecked
            val lm = mockServiceViewModel.locationManager
                ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
            if (lm == null) {
                showToast("定位服务加载异常")
                return@setOnCheckedChangeListener
            }
            if (!MockServiceHelper.isServiceInit()) {
                MockServiceHelper.tryInitService(lm)
            }
            if (!MockServiceHelper.isServiceInit()) {
                showToast("系统服务注入失败")
                return@setOnCheckedChangeListener
            }
            if (isChecked) {
                if (!MockServiceHelper.startWifiMock(lm)) {
                    showToast("禁用WLAN扫描失败: 无法连接到系统服务")
                }
            } else {
                if (!MockServiceHelper.stopWifiMock(lm)) {
                    showToast("启用WLAN扫描失败: 无法连接到系统服务")
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

        binding.stepCadenceValue.text = formatStepCadence(context.stepCadence)
        binding.stepCadenceLayout.setOnClickListener {
            val current = context.stepCadence
            showDialog("设置模拟步频", if (current <= 0f) "" else "%d".format(current.toInt())) {
                val value = it.toFloatOrNull() ?: 0f
                if (value < 0) {
                    showToast("步频不合法")
                    return@showDialog
                } else if (value > 300) {
                    showToast("步频不能超过300步/分")
                    return@showDialog
                }
                context.stepCadence = value
                FakeLoc.stepCadence = value
                binding.stepCadenceValue.text = formatStepCadence(value)
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
        }

        binding.loopBroadcastLocationSwitch.isChecked = requireContext().loopBroadcastlocation
        binding.loopBroadcastLocationSwitch.setOnCheckedChangeListener { _, isChecked ->
            requireContext().loopBroadcastlocation = isChecked
            showToast("重启模拟生效")
        }
        return root
    }

    @SuppressLint("SetTextI18n")
    private fun formatSpeed(mps: Double): String {
        val kmh = mps * 3.6
        return "%.1f 公里/小时".format(kmh)
    }

    @SuppressLint("SetTextI18n")
    private fun formatStepCadence(cadence: Float): String {
        return if (cadence <= 0f) "自动" else "%d步/分".format(cadence.toInt())
    }

    private fun showToast(message: String) {
        lifecycleScope.launch(Dispatchers.Main) {
            Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRemoteConfig() {
        val context = requireContext()
        val lm = mockServiceViewModel.locationManager
            ?: (context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager)
        if (lm == null) {
            showToast("定位服务加载异常")
            return
        }
        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }
        if (!MockServiceHelper.putConfig(lm, context)) {
            showToast("更新远程配置失败")
        } else {
            showToast("同步配置成功")
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

        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(null)
        builder
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