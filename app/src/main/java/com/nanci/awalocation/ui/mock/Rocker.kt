package com.nanci.awalocation.ui.mock

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.transition.AutoTransition
import android.transition.TransitionManager
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatImageView
import com.google.android.material.color.DynamicColors
import com.nanci.awalocation.R
import com.nanci.awalocation.android.ui.MotionUtils
import com.nanci.awalocation.android.widget.RockerView
import com.nanci.awalocation.ext.dynamicColorEnabled
import com.nanci.awalocation.ext.rockerCoords
import kotlin.math.hypot

@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
class Rocker(context: Context) : View.OnTouchListener {
    private val appContext = context.applicationContext
    private val themedContext = createThemedOverlayContext(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val root by lazy {
        LayoutInflater.from(themedContext).inflate(R.layout.layout_rocker, null)!!
    }
    private val layoutParams by lazy {
        WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            0,
            0,
            PixelFormat.TRANSPARENT
        )
    }
    private val windowManager by lazy {
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var startX = 0
    private var startY = 0
    private val touchSlop by lazy {
        ViewConfiguration.get(appContext).scaledTouchSlop.toFloat()
    }

    var isStart = false
    var isHide = false
    private var autoCardVisible = false
    private var visibilityGeneration = 0
    @Volatile var autoStatus = false
        get() = field
        set(value) {
            if (field == value) return
            field = value
            mainHandler.post {
                playAuto(root.findViewById(R.id.rocker))
            }
        }
    var autoLockStatus = false
    var autoListener: OnAutoListener? = null

    init {
        layoutParams.flags = (
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
        layoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        layoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        val rockerCoords = appContext.rockerCoords
        layoutParams.format = PixelFormat.RGBA_8888
        layoutParams.gravity = Gravity.LEFT or Gravity.TOP
        layoutParams.x = rockerCoords.first
        layoutParams.y = rockerCoords.second

        root.setOnTouchListener(this)

        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        val autoView = root.findViewById<AppCompatImageView>(R.id.auto)
        val autoCard = root.findViewById<View>(R.id.auto_card)
        val dragHandle = root.findViewById<View>(R.id.drag_handle)
        val moveView = root.findViewById<View>(R.id.move)
        val autoPlayView = root.findViewById<AppCompatImageView>(R.id.auto_play)
        val autoLockView = root.findViewById<AppCompatImageView>(R.id.auto_lock)

        autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE

        autoView.setOnClickListener {
            autoCardVisible = !autoCardVisible
            (root as? ViewGroup)?.let { container ->
                TransitionManager.beginDelayedTransition(
                    container,
                    AutoTransition().setDuration(180L)
                )
            }
            autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE
        }

        // 顶部把手只负责拖动，move 按钮保留收起/展开功能
        attachDrag(dragHandle)
        attachDraggableClick(moveView) {
            isHide = !isHide
            switchHide(rockerView, autoView)
        }

        autoPlayView.setOnClickListener {
            autoStatus = !autoStatus
        }

        autoLockView.setOnClickListener {
            autoLockStatus = !autoLockStatus
            if (autoLockStatus) {
                autoLockView.setImageResource(R.drawable.baseline_lock_24)
            } else {
                autoLockView.setImageResource(R.drawable.baseline_manual_24)
            }
            autoListener?.onAutoLock(autoLockStatus)
        }
    }

    private fun attachDraggableClick(view: View, onClick: () -> Unit) {
        attachDrag(view, onClick)
    }

    private fun attachDrag(view: View, onClick: (() -> Unit)? = null) {
        var downX = 0f
        var downY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = downX.toInt()
                    startY = downY.toInt()
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val currentX = event.rawX
                    val currentY = event.rawY
                    val distance = hypot((currentX - downX).toDouble(), (currentY - downY).toDouble()).toFloat()
                    if (distance > touchSlop) {
                        isDragging = true
                    }
                    val movedX = currentX.toInt() - startX
                    val movedY = currentY.toInt() - startY
                    startX = currentX.toInt()
                    startY = currentY.toInt()

                    moveWindowTo(layoutParams.x + movedX, layoutParams.y + movedY)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        onClick?.invoke()
                    }
                    savePosition()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun moveWindowTo(x: Int, y: Int) {
        if (!isStart) return
        val metrics = appContext.resources.displayMetrics
        val maxX = (metrics.widthPixels - root.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - root.height).coerceAtLeast(0)
        layoutParams.x = x.coerceIn(0, maxX)
        layoutParams.y = y.coerceIn(0, maxY)
        windowManager.updateViewLayout(root, layoutParams)
    }

    private fun playAuto(rockerView: RockerView) {
        val playButton = root.findViewById<AppCompatImageView>(R.id.auto_play)
        if (autoStatus) {
            rockerView.reset()
            playButton.setImageResource(R.drawable.baseline_stop_24)
        } else {
            playButton.setImageResource(R.drawable.baseline_play_24)
            upController()
        }
        autoListener?.onAutoPlay(autoStatus)
        rockerView.auto(autoStatus)
    }

    private fun switchHide(
        rockerView: RockerView,
        autoView: AppCompatImageView
    ) {
        val bar = root.findViewById<LinearLayout>(R.id.rocker_bar)
        val autoCard = root.findViewById<View>(R.id.auto_card)

        (root as? ViewGroup)?.let { container ->
            TransitionManager.beginDelayedTransition(
                container,
                AutoTransition().setDuration(190L)
            )
        }

        if (isHide) {
            // 缩小态：隐藏大摇杆，将控制行排布为横向精简胶囊
            autoCard.visibility = View.VISIBLE
            autoView.visibility = View.GONE
            rockerView.visibility = View.GONE
            bar.orientation = LinearLayout.HORIZONTAL
        } else {
            // 展开态：还原大摇杆及主控按钮，autoCard 还原为用户选择的可见性
            autoView.visibility = View.VISIBLE
            rockerView.visibility = View.VISIBLE
            autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE
            bar.orientation = LinearLayout.VERTICAL
        }
    }

    fun show(): Boolean {
        if (isStart) return true
        visibilityGeneration++
        return runCatching {
            root.animate().cancel()
            if (root.parent == null) {
                windowManager.addView(root, layoutParams)
            }
            isStart = true
            root.alpha = 0f
            root.scaleX = 0.86f
            root.scaleY = 0.86f
            root.post {
                moveWindowTo(layoutParams.x, layoutParams.y)
                root.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(MotionUtils.EMPHASIZED_DECELERATE)
                    .withEndAction(null)
                    .start()
            }
            true
        }.getOrDefault(false)
    }

    fun hide(immediate: Boolean = false) {
        if (!isStart) return
        isStart = false
        val gen = ++visibilityGeneration
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        rockerView.reset()
        root.animate().cancel()
        if (immediate) {
            runCatching {
                if (root.parent != null) {
                    windowManager.removeView(root)
                }
            }
            root.alpha = 1f
            root.scaleX = 1f
            root.scaleY = 1f
            return
        }
        root.animate()
            .alpha(0f)
            .scaleX(0.88f)
            .scaleY(0.88f)
            .setDuration(150L)
            .setInterpolator(MotionUtils.EMPHASIZED_ACCELERATE)
            .withEndAction {
                if (gen != visibilityGeneration || isStart) return@withEndAction
                runCatching {
                    if (root.parent != null) {
                        windowManager.removeView(root)
                    }
                }
                root.alpha = 1f
                root.scaleX = 1f
                root.scaleY = 1f
            }
            .start()
    }

    fun savePosition() {
        appContext.rockerCoords = Pair(layoutParams.x, layoutParams.y)
    }

    fun updateView() {
        if (isStart) {
            windowManager.updateViewLayout(root, layoutParams)
        }
    }

    fun setRockerListener(listener: RockerView.Companion.OnMoveListener) {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        rockerView.listener = listener
    }

    fun setRockerAutoListener(listener: OnAutoListener?) {
        autoListener = listener
    }

    fun invokeOnTouchEvent(joystickX: Float, joystickY: Float) {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        val downEvent = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, joystickX, joystickY, 0)
        val moveEvent = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_MOVE, joystickX, joystickY, 0)
        try {
            rockerView.onTouchEvent(downEvent)
            rockerView.onTouchEvent(moveEvent)
        } finally {
            downEvent.recycle()
            moveEvent.recycle()
        }
        rockerView.auto(true)
    }

    fun upController() {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        val upEvent = MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_UP, 0f, 0f, 0)
        try {
            rockerView.onTouchEvent(upEvent)
        } finally {
            upEvent.recycle()
        }
        rockerView.auto(false)
    }

    override fun onTouch(v: View?, event: MotionEvent?): Boolean {
        if (v == null || event == null) {
            return false
        }
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX.toInt()
                startY = event.rawY.toInt()
                savePosition()
            }
            MotionEvent.ACTION_MOVE -> {
                val nowX = event.rawX.toInt()
                val nowY = event.rawY.toInt()
                val movedX = nowX - startX
                val movedY = nowY - startY
                startX = nowX
                startY = nowY
                moveWindowTo(layoutParams.x + movedX, layoutParams.y + movedY)
            }
            MotionEvent.ACTION_UP -> {
                savePosition()
            }
            else -> {}
        }
        return false
    }

    companion object {
        private fun createThemedOverlayContext(base: Context): Context {
            val config = Configuration(base.resources.configuration)
            val appConfigCtx = base.applicationContext.createConfigurationContext(config)
            val themed = ContextThemeWrapper(appConfigCtx, R.style.AppTheme)
            return if (base.dynamicColorEnabled && DynamicColors.isDynamicColorAvailable()) {
                DynamicColors.wrapContextIfAvailable(themed)
            } else {
                themed
            }
        }

        interface OnAutoListener {
            fun onAutoPlay(isPlay: Boolean)
            fun onAutoLock(isLock: Boolean)
        }
    }
}
