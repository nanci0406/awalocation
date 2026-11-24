package com.nanci.awalocation.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.GnssStatus
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.nanci.awalocation.R
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class SatelliteData(
    val prn: Int,
    val snr: Float,
    val elevation: Float,
    val azimuth: Float,
    val hasAlmanac: Boolean,
    val hasEphemeris: Boolean,
    val usedInFix: Boolean,
    val constellation: Int = GnssStatus.CONSTELLATION_UNKNOWN,
    var screenX: Float = 0f,
    var screenY: Float = 0f
)

class SatelliteRadarView(context: Context, attributeSet: AttributeSet) :
    View(context, attributeSet), SensorEventListener {

    private var satellites = emptyList<SatelliteData>()
    private val density = resources.displayMetrics.density

    private val bgCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val innerRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val northPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satellitePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var centerX: Float = 0f
    private var centerY: Float = 0f
    private var radius: Float = 0f

    private var sensorManager: SensorManager? = null
    private var rotationSensor: Sensor? = null
    private var magneticSensor: Sensor? = null
    private var accelerometerSensor: Sensor? = null

    private var rotationVector = FloatArray(3)
    private var accelerometerValues = FloatArray(3)
    private var magneticFieldValues = FloatArray(3)
    private var currentAzimuth: Float = 0f
    private var useDeviceOrientation: Boolean = false

    private val primaryColor: Int
    private val gpsColor: Int
    private val beidouColor: Int
    private val glonassColor: Int
    private val otherColor: Int
    private val northColor: Int

    init {
        initSensors(context)

        primaryColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(context, R.color.awalocation_primary)
        )
        val surfaceVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorSurfaceVariant,
            ContextCompat.getColor(context, R.color.awalocation_surface_variant)
        )
        val outline = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutline,
            ContextCompat.getColor(context, R.color.awalocation_outline)
        )
        val outlineVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOutlineVariant,
            ContextCompat.getColor(context, R.color.awalocation_outline_variant)
        )
        val onSurfaceVariant = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            ContextCompat.getColor(context, R.color.awalocation_on_surface_variant)
        )

        gpsColor = primaryColor
        beidouColor = ContextCompat.getColor(context, R.color.awalocation_success)
        glonassColor = ContextCompat.getColor(context, R.color.awalocation_warning)
        otherColor = ColorUtils.blendARGB(primaryColor, beidouColor, 0.45f)
        northColor = ContextCompat.getColor(context, R.color.red500)

        bgCirclePaint.style = Paint.Style.FILL
        bgCirclePaint.color = ColorUtils.setAlphaComponent(surfaceVariant, 110)

        outerRingPaint.style = Paint.Style.STROKE
        outerRingPaint.strokeWidth = 1.5f * density
        outerRingPaint.color = ColorUtils.setAlphaComponent(outline, 190)

        innerRingPaint.style = Paint.Style.STROKE
        innerRingPaint.strokeWidth = 1f * density
        innerRingPaint.color = ColorUtils.setAlphaComponent(outlineVariant, 220)
        innerRingPaint.pathEffect = DashPathEffect(floatArrayOf(5f * density, 5f * density), 0f)

        axisPaint.style = Paint.Style.STROKE
        axisPaint.strokeWidth = 1f * density
        axisPaint.color = ColorUtils.setAlphaComponent(outlineVariant, 200)

        labelPaint.textAlign = Paint.Align.CENTER
        labelPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            11f,
            resources.displayMetrics
        )
        labelPaint.isFakeBoldText = true
        labelPaint.color = onSurfaceVariant

        northPaint.textAlign = Paint.Align.CENTER
        northPaint.textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            12f,
            resources.displayMetrics
        )
        northPaint.isFakeBoldText = true
        northPaint.color = northColor

        satellitePaint.style = Paint.Style.FILL
        glowPaint.style = Paint.Style.FILL
    }

    private fun initSensors(context: Context) {
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        rotationSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            accelerometerSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            magneticSensor = sensorManager?.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        registerSensors()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        unregisterSensors()
    }

    private fun registerSensors() {
        if (rotationSensor != null) {
            sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_UI)
        } else {
            accelerometerSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
            magneticSensor?.let {
                sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun unregisterSensors() {
        sensorManager?.unregisterListener(this)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        centerX = w / 2f
        centerY = h / 2f
        radius = (w.coerceAtMost(h) / 2f - 22f * density).coerceAtLeast(24f * density)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val defaultPx = (230 * density).toInt()
        val size = when (widthMode) {
            MeasureSpec.EXACTLY -> widthSize
            MeasureSpec.AT_MOST -> minOf(widthSize, defaultPx)
            else -> defaultPx
        }
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 背景圆盘
        canvas.drawCircle(centerX, centerY, radius, bgCirclePaint)
        // 外环与内侧 30°/60° 虚线环
        canvas.drawCircle(centerX, centerY, radius, outerRingPaint)
        canvas.drawCircle(centerX, centerY, radius * 2f / 3f, innerRingPaint)
        canvas.drawCircle(centerX, centerY, radius / 3f, innerRingPaint)

        // 十字轴与 45° 对角参考线
        val diagOffset = radius * 0.7071f
        canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, axisPaint)
        canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, axisPaint)
        canvas.drawLine(centerX - diagOffset, centerY - diagOffset, centerX + diagOffset, centerY + diagOffset, innerRingPaint)
        canvas.drawLine(centerX - diagOffset, centerY + diagOffset, centerX + diagOffset, centerY - diagOffset, innerRingPaint)

        // 方位角标签（支持随指南针旋转）
        val compassLabels = listOf("N" to 0f, "E" to 90f, "S" to 180f, "W" to 270f)
        val labelDistance = radius + 13f * density
        val textCenterOffset = (labelPaint.descent() + labelPaint.ascent()) / 2f
        for ((label, baseAngle) in compassLabels) {
            val angle = if (useDeviceOrientation) (baseAngle - currentAzimuth) else baseAngle
            val rad = Math.toRadians(angle.toDouble())
            val lx = centerX + labelDistance * sin(rad).toFloat()
            val ly = centerY - labelDistance * cos(rad).toFloat() - textCenterOffset
            canvas.drawText(label, lx, ly, if (label == "N") northPaint else labelPaint)
        }

        // 卫星点绘制
        for (satellite in satellites) {
            val adjustedAzimuth = if (useDeviceOrientation) {
                val angle = (satellite.azimuth - currentAzimuth) % 360f
                if (angle < 0) angle + 360f else angle
            } else {
                satellite.azimuth
            }

            val clampedElev = satellite.elevation.coerceIn(0f, 90f)
            val satelliteRadius = radius * (1f - clampedElev / 90f)
            val rad = Math.toRadians(adjustedAzimuth.toDouble())
            val x = centerX + satelliteRadius * sin(rad).toFloat()
            val y = centerY - satelliteRadius * cos(rad).toFloat()
            satellite.screenX = x
            satellite.screenY = y

            val color = when (satellite.constellation) {
                GnssStatus.CONSTELLATION_GPS -> gpsColor
                GnssStatus.CONSTELLATION_BEIDOU -> beidouColor
                GnssStatus.CONSTELLATION_GLONASS -> glonassColor
                else -> otherColor
            }

            val baseSize = (3.6f + (satellite.snr / 16f).coerceIn(0f, 3.8f)) * density
            if (satellite.usedInFix) {
                glowPaint.color = ColorUtils.setAlphaComponent(color, 58)
                canvas.drawCircle(x, y, baseSize * 1.85f, glowPaint)
                satellitePaint.color = color
            } else {
                satellitePaint.color = ColorUtils.setAlphaComponent(color, 130)
            }
            canvas.drawCircle(x, y, baseSize, satellitePaint)
        }
    }

    fun setSatellites(satellites: List<SatelliteData>) {
        this.satellites = satellites
        invalidate()
    }

    fun setUseDeviceOrientation(use: Boolean) {
        if (this.useDeviceOrientation == use) return
        this.useDeviceOrientation = use
        invalidate()
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                rotationVector = event.values.clone()
                updateOrientation()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                accelerometerValues = event.values.clone()
                updateOrientation()
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                magneticFieldValues = event.values.clone()
                updateOrientation()
            }
        }
    }

    private fun updateOrientation() {
        val rotationMatrix = FloatArray(9)
        val orientationValues = FloatArray(3)
        val hasMatrix = if (rotationSensor != null) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, rotationVector)
            true
        } else if (accelerometerSensor != null && magneticSensor != null) {
            SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerValues, magneticFieldValues)
        } else {
            false
        }
        if (!hasMatrix) return

        SensorManager.getOrientation(rotationMatrix, orientationValues)
        var azimuthInDegrees = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
        if (azimuthInDegrees < 0f) azimuthInDegrees += 360f
        if (abs(azimuthInDegrees - currentAzimuth) > 1.5f) {
            currentAzimuth = azimuthInDegrees
            if (useDeviceOrientation) invalidate()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
