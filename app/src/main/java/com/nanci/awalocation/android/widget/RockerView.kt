package com.nanci.awalocation.android.widget

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import com.google.android.material.color.MaterialColors
import com.nanci.awalocation.R
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

// https://github.com/lqxue/RockerView/blob/master/app/src/main/java/com/zcsj/rockerview/RockerView.java
class RockerView(context: Context, attributeSet: AttributeSet) : View(context, attributeSet) {
    private val density = resources.displayMetrics.density
    private val defaultSizePx = (DEFAULT_SIZE_DP * density).roundToInt()
    private val defaultPaddingPx = (DEFAULT_PADDING_DP * density).roundToInt()

    private val mAreaBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val areaRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val guideRingPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val mRockerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rockerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lockHaloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val directionPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trianglePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trianglePaint2 = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var mRockerPosition = Point()
    private var mCenterPoint = Point()
    private val drawRectF = RectF()
    private val lockBitmap: Bitmap =
        ResourcesCompat.getDrawable(resources, R.drawable.baseline_lock_24, null)!!.toBitmap()
    private var robotBitmap: Bitmap? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lockRunnable: Runnable? = null

    private var mAreaRadius = 0.0f
    private var mRockerInnerCircleRadius = 0.0f
    private var mRockerColor = 0
    private var mAreaColor = 0
    private var isLocked = AtomicBoolean(false)
    private var isAuto = AtomicBoolean(false)
    private var isPositionInitialized = false
    var listener: OnMoveListener? = null

    init {
        val typedArray = context.obtainStyledAttributes(attributeSet, R.styleable.RockerView)
        val areaBackground = typedArray.getDrawable(R.styleable.RockerView_areaBackground)
        if (areaBackground != null) {
            if (areaBackground is ColorDrawable) {
                mAreaColor = areaBackground.color
            } else {
                throw IllegalArgumentException("areaBackground only support ColorDrawable")
            }
        }
        val rockerBackground = typedArray.getDrawable(R.styleable.RockerView_rockerBackground)
        if (rockerBackground != null) {
            if (rockerBackground is ColorDrawable) {
                mRockerColor = rockerBackground.color
            } else {
                throw IllegalArgumentException("rockerBackground only support ColorDrawable")
            }
        }
        typedArray.recycle()

        val primaryColor = MaterialColors.getColor(
            this,
            com.google.android.material.R.attr.colorPrimary,
            ContextCompat.getColor(context, R.color.awalocation_primary)
        )
        val white = ContextCompat.getColor(context, R.color.white)

        mAreaBackgroundPaint.color = mAreaColor

        areaRingPaint.style = Paint.Style.STROKE
        areaRingPaint.strokeWidth = 1.5f * density
        areaRingPaint.color = ColorUtils.setAlphaComponent(white, 110)

        guideRingPaint.style = Paint.Style.STROKE
        guideRingPaint.strokeWidth = 1f * density
        guideRingPaint.color = ColorUtils.setAlphaComponent(white, 45)

        tickPaint.style = Paint.Style.STROKE
        tickPaint.strokeWidth = 1.2f * density
        tickPaint.color = ColorUtils.setAlphaComponent(white, 85)

        mRockerPaint.color = mRockerColor
        mRockerPaint.setShadowLayer(4f * density, 0f, 1.5f * density, ColorUtils.setAlphaComponent(0xFF000000.toInt(), 70))

        rockerStrokePaint.style = Paint.Style.STROKE
        rockerStrokePaint.strokeWidth = 2f * density
        rockerStrokePaint.color = primaryColor

        lockHaloPaint.style = Paint.Style.FILL
        lockHaloPaint.color = ColorUtils.setAlphaComponent(primaryColor, 72)

        robotBitmap = ResourcesCompat.getDrawable(resources, R.drawable.baseline_robot_24, null)?.toBitmap()

        directionPaint.textAlign = Paint.Align.CENTER
        directionPaint.isFakeBoldText = true
        directionPaint.color = ColorUtils.setAlphaComponent(white, 230)

        trianglePaint.color = ContextCompat.getColor(context, R.color.red500)
        trianglePaint.style = Paint.Style.FILL

        trianglePaint2.color = ColorUtils.setAlphaComponent(white, 200)
        trianglePaint2.style = Paint.Style.FILL
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = getMySize(widthMeasureSpec)
        val height = getMySize(heightMeasureSpec)
        val minSize = width.coerceAtMost(height)
        setMeasuredDimension(minSize, minSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val availableWidth = width - paddingLeft - paddingRight - defaultPaddingPx
        val availableHeight = height - paddingBottom - paddingTop - defaultPaddingPx
        val cx = availableWidth / 2 + paddingLeft + defaultPaddingPx / 2
        val cy = availableHeight / 2 + paddingTop + defaultPaddingPx / 2
        mCenterPoint[cx] = cy
        mAreaRadius = (min(availableWidth.toDouble(), availableHeight.toDouble()) / 2).toFloat()

        if (!isPositionInitialized) {
            mRockerPosition[mCenterPoint.x] = mCenterPoint.y
            isPositionInitialized = true
        }

        val centerX = mCenterPoint.x.toFloat()
        val centerY = mCenterPoint.y.toFloat()

        // 外圈底座 + 边框 + 内引导圈
        mAreaBackgroundPaint.color = mAreaColor
        canvas.drawCircle(centerX, centerY, mAreaRadius, mAreaBackgroundPaint)
        canvas.drawCircle(centerX, centerY, mAreaRadius - density, areaRingPaint)
        canvas.drawCircle(centerX, centerY, mAreaRadius * 0.52f, guideRingPaint)

        // 12 刻度线
        for (deg in 0 until 360 step 30) {
            if (deg % 90 == 0) continue
            val rad = Math.toRadians(deg.toDouble())
            val startR = mAreaRadius * 0.86f
            val endR = mAreaRadius * 0.94f
            canvas.drawLine(
                centerX + (startR * cos(rad)).toFloat(),
                centerY + (startR * sin(rad)).toFloat(),
                centerX + (endR * cos(rad)).toFloat(),
                centerY + (endR * sin(rad)).toFloat(),
                tickPaint
            )
        }

        drawDirection(canvas, mAreaRadius, centerX, centerY)

        mRockerInnerCircleRadius = mAreaRadius * 0.22f
        val rockerX = mRockerPosition.x.toFloat()
        val rockerY = mRockerPosition.y.toFloat()

        if (isLocked.get()) {
            canvas.drawCircle(rockerX, rockerY, mRockerInnerCircleRadius * 1.38f, lockHaloPaint)
        }

        canvas.drawCircle(rockerX, rockerY, mRockerInnerCircleRadius, mRockerPaint)
        canvas.drawCircle(rockerX, rockerY, mRockerInnerCircleRadius - density, rockerStrokePaint)

        if (isAuto.get()) {
            robotBitmap?.let { bmp ->
                val iconHalf = mRockerInnerCircleRadius * 0.58f
                drawRectF.set(
                    rockerX - iconHalf,
                    rockerY - iconHalf,
                    rockerX + iconHalf,
                    rockerY + iconHalf
                )
                canvas.drawBitmap(bmp, null, drawRectF, lockPaint)
            }
        }
        if (isLocked.get()) {
            drawLock(canvas)
        }
    }

    private fun drawDirection(
        canvas: Canvas,
        circleRadius: Float = mAreaRadius,
        circleX: Float = mCenterPoint.x.toFloat(),
        circleY: Float = mCenterPoint.y.toFloat()
    ) {
        val triangleBase = circleRadius / 9.0f
        directionPaint.textSize = circleRadius / 7.5f
        val fontMetrics = directionPaint.fontMetrics
        val textCenterOffset = -(fontMetrics.ascent + fontMetrics.descent) / 2f

        run {
            canvas.drawText("N", circleX, circleY - circleRadius * 0.58f + textCenterOffset, directionPaint)
            val trianglePath = Path()
            trianglePath.moveTo(circleX, circleY - circleRadius * 0.86f)
            trianglePath.lineTo(circleX - triangleBase / 2, circleY - circleRadius * 0.74f)
            trianglePath.lineTo(circleX + triangleBase / 2, circleY - circleRadius * 0.74f)
            trianglePath.close()
            canvas.drawPath(trianglePath, trianglePaint)
        }
        run {
            canvas.drawText("W", circleX - circleRadius * 0.58f, circleY + textCenterOffset, directionPaint)
            val trianglePath = Path()
            trianglePath.moveTo(circleX - circleRadius * 0.86f, circleY)
            trianglePath.lineTo(circleX - circleRadius * 0.74f, circleY + triangleBase / 2)
            trianglePath.lineTo(circleX - circleRadius * 0.74f, circleY - triangleBase / 2)
            trianglePath.close()
            canvas.drawPath(trianglePath, trianglePaint2)
        }
        run {
            canvas.drawText("E", circleX + circleRadius * 0.58f, circleY + textCenterOffset, directionPaint)
            val trianglePath = Path()
            trianglePath.moveTo(circleX + circleRadius * 0.86f, circleY)
            trianglePath.lineTo(circleX + circleRadius * 0.74f, circleY + triangleBase / 2)
            trianglePath.lineTo(circleX + circleRadius * 0.74f, circleY - triangleBase / 2)
            trianglePath.close()
            canvas.drawPath(trianglePath, trianglePaint2)
        }
        run {
            canvas.drawText("S", circleX, circleY + circleRadius * 0.58f + textCenterOffset, directionPaint)
            val trianglePath = Path()
            trianglePath.moveTo(circleX, circleY + circleRadius * 0.86f)
            trianglePath.lineTo(circleX - triangleBase / 2, circleY + circleRadius * 0.74f)
            trianglePath.lineTo(circleX + triangleBase / 2, circleY + circleRadius * 0.74f)
            trianglePath.close()
            canvas.drawPath(trianglePath, trianglePaint2)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event == null) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                listener?.onStarted()

                val moveX = event.x
                val moveY = event.y

                val (rockerPos, w) = getRockerPositionPoint(
                    mCenterPoint,
                    Point(moveX.toInt(), moveY.toInt()),
                    mAreaRadius,
                    mRockerInnerCircleRadius
                )
                this.mRockerPosition = rockerPos
                moveRocker(rockerPos.x, rockerPos.y)

                if (!w.first && isLocked.get() && lockRunnable == null) {
                    isLocked.set(false)
                    listener?.onLockChanged(false)
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isLocked.get()) {
                    val moveX = event.x
                    val moveY = event.y
                    val (rockerPos, w) = getRockerPositionPoint(
                        mCenterPoint,
                        Point(moveX.toInt(), moveY.toInt()),
                        mAreaRadius,
                        mRockerInnerCircleRadius
                    )
                    this.mRockerPosition = rockerPos
                    moveRocker(rockerPos.x, rockerPos.y)

                    if (w.first && lockRunnable == null && !isAuto.get()) {
                        lockRunnable = Runnable {
                            isLocked.set(true)
                            listener?.onLockChanged(true)
                            Toast.makeText(context, "方向锁定", Toast.LENGTH_SHORT).show()
                            lockRunnable = null
                            invalidate()
                        }
                        handler.postDelayed(lockRunnable!!, 3000)
                    }

                    if (!w.first && lockRunnable != null) {
                        handler.removeCallbacks(lockRunnable!!)
                        lockRunnable = null
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!isLocked.get()) {
                    listener?.onFinished()
                    moveRocker(mCenterPoint.x, mCenterPoint.y)
                    lockRunnable?.let { handler.removeCallbacks(it) }
                    lockRunnable = null
                }
            }
        }
        return true
    }

    private fun moveRocker(x: Int, y: Int) {
        isPositionInitialized = true
        mRockerPosition[x] = y
        invalidate()
    }

    private fun drawLock(canvas: Canvas) {
        val lockSize = mRockerInnerCircleRadius * 0.55f
        val rockerX = mRockerPosition.x.toFloat()
        val rockerY = mRockerPosition.y.toFloat()
        drawRectF.set(rockerX - lockSize, rockerY - lockSize, rockerX + lockSize, rockerY + lockSize)
        canvas.drawBitmap(lockBitmap, null, drawRectF, lockPaint)
    }

    private fun getMySize(measureSpec: Int): Int {
        val specMode = MeasureSpec.getMode(measureSpec)
        val specSize = MeasureSpec.getSize(measureSpec)
        return when (specMode) {
            MeasureSpec.EXACTLY -> specSize
            MeasureSpec.AT_MOST -> defaultSizePx.coerceAtMost(specSize)
            else -> defaultSizePx
        }
    }

    private fun getRockerPositionPoint(
        centerPoint: Point,
        touchPoint: Point,
        regionRadius: Float,
        rockerRadius: Float
    ): Pair<Point, Pair<Boolean, Double>> {
        val lenX = (touchPoint.x - centerPoint.x).toFloat()
        val lenY = (touchPoint.y - centerPoint.y).toFloat()
        val lenXY = sqrt((lenX * lenX + lenY * lenY).toDouble()).toFloat()
        if (lenXY == 0f) {
            listener?.onAngle(0.0)
            return Point(centerPoint.x, centerPoint.y) to (false to 0.0)
        }
        val radian = acos((lenX / lenXY).toDouble()) * (if (touchPoint.y < centerPoint.y) -1 else 1)
        val tmp = Math.round(radian / Math.PI * 180).toDouble()
        val angle = ((if (tmp >= 0) tmp else 360 + tmp) + 90) % 360
        if (lenXY + rockerRadius <= regionRadius) {
            listener?.onAngle(angle)
            return touchPoint to (false to angle)
        } else {
            val showPointX = (centerPoint.x + (regionRadius - rockerRadius) * cos(radian)).toInt()
            val showPointY = (centerPoint.y + (regionRadius - rockerRadius) * sin(radian)).toInt()
            listener?.onAngle(angle)
            return Point(showPointX, showPointY) to (true to angle)
        }
    }

    fun reset() {
        isPositionInitialized = false
        mRockerPosition = Point()
        isLocked.set(false)
        lockRunnable?.let { handler.removeCallbacks(it) }
        lockRunnable = null
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        lockRunnable?.let { handler.removeCallbacks(it) }
        lockRunnable = null
    }

    fun auto(enable: Boolean) {
        isAuto.set(enable)
        invalidate()
    }

    companion object {
        const val DEFAULT_SIZE_DP: Int = 156
        const val DEFAULT_PADDING_DP: Int = 20

        interface OnMoveListener {
            fun onStarted() {}
            fun onAngle(angle: Double)
            fun onLockChanged(isLocked: Boolean)
            fun onFinished() {}
        }
    }
}
