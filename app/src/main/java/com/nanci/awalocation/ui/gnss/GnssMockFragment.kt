package com.nanci.awalocation.ui.gnss

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.GnssStatus
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.tencent.bugly.crashreport.CrashReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nanci.awalocation.R
import com.nanci.awalocation.android.ui.animateChildrenEntrance
import com.nanci.awalocation.android.ui.animateScalePop
import com.nanci.awalocation.android.ui.startLivePulse
import com.nanci.awalocation.android.ui.stopLivePulse
import com.nanci.awalocation.android.widget.SatelliteData
import com.nanci.awalocation.android.window.OverlayUtils
import com.nanci.awalocation.databinding.FragmentGnssMockBinding
import com.nanci.awalocation.ext.enableAGPS
import com.nanci.awalocation.ext.enableGetFromLocation
import com.nanci.awalocation.ext.enableNMEA
import com.nanci.awalocation.ext.enableRequestGeofence
import com.nanci.awalocation.service.MockServiceHelper

class GnssMockFragment : Fragment() {
    private var _binding: FragmentGnssMockBinding? = null
    private val binding get() = _binding!!
    private lateinit var locationManager: LocationManager
    private var gnssStatusCallback: GnssStatus.Callback? = null
    private var keepaliveLocationListener: LocationListener? = null
    private var lastRenderedRunning: Boolean? = null

    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Toast.makeText(requireContext(), "所有权限都已授予", Toast.LENGTH_SHORT).show()
            setupGnssStatusCallback()
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys.joinToString()
            Toast.makeText(requireContext(), "以下权限被拒绝: $deniedPermissions", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("SetTextI18n")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentGnssMockBinding.inflate(inflater, container, false)
        val root: View = binding.root

        val baseTop = resources.getDimensionPixelSize(R.dimen.page_top_padding)
        ViewCompat.setOnApplyWindowInsetsListener(binding.gnssContentRoot) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.updatePadding(top = bars.top + baseTop)
            insets
        }

        locationManager = requireContext().getSystemService(Context.LOCATION_SERVICE) as LocationManager

        binding.chipCompassFollow.setOnCheckedChangeListener { _, isChecked ->
            binding.satelliteRadaView.setUseDeviceOrientation(isChecked)
        }

        val permissionsToRequest = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val permissionsNeeded = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsNeeded.isEmpty()) {
            setupGnssStatusCallback()
        } else {
            requestMultiplePermissionsLauncher.launch(permissionsNeeded)
        }

        binding.switchRequestGeofence.isChecked = requireContext().enableRequestGeofence
        binding.switchRequestGeofence.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableRequestGeofence = isChecked
            Toast.makeText(requireContext(), "重启GNSS模拟生效", Toast.LENGTH_SHORT).show()
        }

        binding.switchGetFromLocation.isChecked = requireContext().enableGetFromLocation
        binding.switchGetFromLocation.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableGetFromLocation = isChecked
            Toast.makeText(requireContext(), "重启GNSS模拟生效", Toast.LENGTH_SHORT).show()
        }

        binding.switchEnableAgps.isChecked = requireContext().enableAGPS
        binding.switchEnableAgps.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableAGPS = isChecked
            Toast.makeText(requireContext(), "重启GNSS模拟生效", Toast.LENGTH_SHORT).show()
        }

        binding.switchEnableNmea.isChecked = requireContext().enableNMEA
        binding.switchEnableNmea.setOnCheckedChangeListener { _, isChecked ->
            requireContext().enableNMEA = isChecked
            Toast.makeText(requireContext(), "重启GNSS模拟生效", Toast.LENGTH_SHORT).show()
        }

        if (!::locationManager.isInitialized) {
            showToast("定位服务获取失败")
            return root
        }

        renderGnssStatus(MockServiceHelper.isGnssMockStart(locationManager))

        binding.switchGnssMock.setOnClickListener {
            if (MockServiceHelper.isGnssMockStart(locationManager)) {
                tryCloseService(it as MaterialButton)
            } else {
                tryOpenService(it as MaterialButton)
            }
        }

        if (savedInstanceState == null) {
            binding.gnssContentRoot.animateChildrenEntrance(maxCount = 5, staggerMs = 35L)
        }

        return root
    }

    private fun renderGnssStatus(running: Boolean) {
        if (_binding == null) return
        val stateChanged = lastRenderedRunning != null && lastRenderedRunning != running
        lastRenderedRunning = running
        val ctx = requireContext()
        if (running) {
            binding.gnssStatusPill.setBackgroundResource(R.drawable.bg_status_pill_running)
            binding.gnssStatusDot.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(ctx, R.color.awalocation_success)
            )
            binding.gnssStatusDot.startLivePulse()
            binding.gnssStatusText.text = getString(R.string.status_running)
            binding.gnssStatusText.setTextColor(ContextCompat.getColor(ctx, R.color.awalocation_on_success_container))
            binding.switchGnssMock.text = getString(R.string.stop_mock)
            ContextCompat.getDrawable(ctx, R.drawable.rounded_play_disabled_24)?.let {
                binding.switchGnssMock.icon = it
            }
        } else {
            binding.gnssStatusPill.setBackgroundResource(R.drawable.bg_status_pill_idle)
            binding.gnssStatusDot.backgroundTintList = ColorStateList.valueOf(
                MaterialColors.getColor(binding.gnssStatusDot, com.google.android.material.R.attr.colorOutline)
            )
            binding.gnssStatusDot.stopLivePulse()
            binding.gnssStatusText.text = getString(R.string.status_idle)
            binding.gnssStatusText.setTextColor(
                MaterialColors.getColor(binding.gnssStatusText, com.google.android.material.R.attr.colorOnSurfaceVariant)
            )
            binding.switchGnssMock.text = getString(R.string.start_mock)
            ContextCompat.getDrawable(ctx, R.drawable.rounded_play_arrow_24)?.let {
                binding.switchGnssMock.icon = it
            }
        }
        if (stateChanged) {
            binding.gnssStatusPill.animateScalePop(peakScale = 1.08f, durationMs = 220L)
        }
    }

    private fun tryOpenService(button: MaterialButton) {
        if (!OverlayUtils.hasOverlayPermissions(requireContext())) {
            showToast("请授权悬浮窗权限")
            return
        }

        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val ctx = requireContext().applicationContext
                val started = withContext(Dispatchers.IO) {
                    MockServiceHelper.putConfig(locationManager, ctx)
                    MockServiceHelper.startGnssMock(locationManager)
                }
                if (started) {
                    renderGnssStatus(true)
                } else {
                    showToast("模拟GNSS服务启动失败")
                    return@launch
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    private fun tryCloseService(button: MaterialButton) {
        if (!MockServiceHelper.isServiceInit()) {
            showToast("系统服务注入失败")
            return
        }

        lifecycleScope.launch {
            button.isClickable = false
            try {
                val stopped = withContext(Dispatchers.IO) {
                    if (!MockServiceHelper.isGnssMockStart(locationManager)) {
                        showToast("模拟服务未启动")
                        return@withContext false
                    }

                    if (MockServiceHelper.stopGnssMock(locationManager)) {
                        return@withContext true
                    } else {
                        showToast("模拟GNSS服务停止失败")
                        return@withContext false
                    }
                }
                if (stopped) {
                    renderGnssStatus(false)
                }
            } finally {
                button.isClickable = true
            }
        }
    }

    private fun showToast(message: String) = lifecycleScope.launch(Dispatchers.Main) {
        Toast.makeText(requireContext(), message, Toast.LENGTH_SHORT).show()
    }

    private fun setupGnssStatusCallback() {
        gnssStatusCallback = object : GnssStatus.Callback() {
            @SuppressLint("SetTextI18n")
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                if (_binding == null) return

                val satelliteCount = status.satelliteCount
                var inViewSatelliteCount = 0
                var inUseSatelliteCount = 0
                val signalStrength = arrayListOf<Float>()

                var gpsCount = 0
                var glonassCount = 0
                var beidouCount = 0

                val satellites = mutableListOf<SatelliteData>()
                for (i in 0 until satelliteCount) {
                    val svid = status.getSvid(i)
                    val constellation = status.getConstellationType(i)
                    val carrierToNoise = status.getCn0DbHz(i)
                    val elevation = status.getElevationDegrees(i)
                    val azimuth = status.getAzimuthDegrees(i)
                    val usedInFix = status.usedInFix(i)

                    satellites.add(
                        SatelliteData(
                            prn = svid,
                            snr = carrierToNoise,
                            elevation = elevation,
                            azimuth = azimuth,
                            hasAlmanac = status.hasAlmanacData(i),
                            hasEphemeris = status.hasEphemerisData(i),
                            usedInFix = usedInFix,
                            constellation = constellation
                        )
                    )

                    inViewSatelliteCount += 1
                    if (usedInFix) {
                        inUseSatelliteCount += 1
                        signalStrength.add(carrierToNoise)
                    }

                    when (constellation) {
                        GnssStatus.CONSTELLATION_GPS -> gpsCount += 1
                        GnssStatus.CONSTELLATION_GLONASS -> glonassCount += 1
                        GnssStatus.CONSTELLATION_BEIDOU -> beidouCount += 1
                    }
                }

                val avgSignalStrength =
                    if (signalStrength.isNotEmpty()) signalStrength.average().toFloat() else 0f
                binding.signalStrengthRightItem.text = "%.1f".format(avgSignalStrength)
                binding.inViewRightItem.text = "$inViewSatelliteCount"
                binding.inUseRightItem.text = "$inUseSatelliteCount"

                binding.gpsCount.text = "GPS: $gpsCount"
                binding.glonassCount.text = "GNS: $glonassCount"
                binding.beidouCount.text = "BD: $beidouCount"

                binding.satelliteRadaView.setSatellites(satellites)
            }
        }

        try {
            if (keepaliveLocationListener == null) {
                keepaliveLocationListener = LocationListener { /* keepalive */ }
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L,
                    0f,
                    keepaliveLocationListener!!
                )
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                locationManager.registerGnssStatusCallback(requireContext().mainExecutor, gnssStatusCallback!!)
            } else {
                @Suppress("DEPRECATION")
                locationManager.registerGnssStatusCallback(gnssStatusCallback!!, null)
            }
        } catch (e: SecurityException) {
            CrashReport.postCatchedException(e)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::locationManager.isInitialized) {
            lifecycleScope.launch {
                val running = withContext(Dispatchers.IO) {
                    MockServiceHelper.isGnssMockStart(locationManager)
                }
                renderGnssStatus(running)
            }
        }
    }

    override fun onDestroyView() {
        _binding?.gnssStatusDot?.stopLivePulse()
        lastRenderedRunning = null
        super.onDestroyView()
        _binding = null
        if (gnssStatusCallback != null) {
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback!!)
        }
        keepaliveLocationListener?.let {
            runCatching { locationManager.removeUpdates(it) }
            keepaliveLocationListener = null
        }
    }
}
