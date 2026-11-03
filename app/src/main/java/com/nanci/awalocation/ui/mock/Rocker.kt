package com.nanci.awalocation.ui.mock

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.widget.AppCompatImageView
import com.nanci.awalocation.R
import com.nanci.awalocation.android.widget.RockerView
import com.nanci.awalocation.ext.rockerCoords
import kotlin.math.hypot

@SuppressLint("RtlHardcoded", "ClickableViewAccessibility")
class Rocker(private val activity: Activity) : View.OnTouchListener {
    private val root by lazy {
        LayoutInflater.from(activity).inflate(R.layout.layout_rocker, null)!!
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
        activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    private var startX = 0
    private var startY = 0
    private val touchSlop by lazy {
        ViewConfiguration.get(activity).scaledTouchSlop.toFloat()
    }

    var isStart = false
    var isHide = false
    private var autoCardVisible = false
    @Volatile var autoStatus = false
        get() = field
        set(value) {
            if (field == value) return
            field = value
            activity.runOnUiThread {
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

        val rockerCoords = activity.rockerCoords
        layoutParams.format = PixelFormat.RGBA_8888
        layoutParams.gravity = Gravity.LEFT or Gravity.TOP
        layoutParams.x = rockerCoords.first
        layoutParams.y = rockerCoords.second

        root.setOnTouchListener(this)

        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        val autoView = root.findViewById<AppCompatImageView>(R.id.auto)
        val expandMenuView = root.findViewById<AppCompatImageView>(R.id.expand_menu)
        val autoCard = root.findViewById<View>(R.id.auto_card)
        val dragHandle = root.findViewById<View>(R.id.drag_handle)
        val moveView = root.findViewById<View>(R.id.move)
        val autoPlayView = root.findViewById<AppCompatImageView>(R.id.auto_play)
        val autoLockView = root.findViewById<AppCompatImageView>(R.id.auto_lock)

        autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE

        expandMenuView.setOnClickListener {
            Toast.makeText(activity, "暂不支持", Toast.LENGTH_SHORT).show()
        }

        autoView.setOnClickListener {
            autoCardVisible = !autoCardVisible
            autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE
        }

        // 顶部把手只负责拖动，move 按钮保留收起/展开功能
        attachDrag(dragHandle)
        attachDraggableClick(moveView) {
            isHide = !isHide
            switchHide(rockerView, autoView, expandMenuView)
        }

        autoPlayView.setOnClickListener {
            autoStatus = !autoStatus
        }

        root.findViewById<View>(R.id.auto_status).setOnClickListener {
            // 保留扩展
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

    /**
     * 为视图绑定“兼顾拖动与点击”的手势监听：
     * 手指滑动超过阈值时拖拽悬浮窗，未滑动时触发点击。
     */
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
        val metrics = activity.resources.displayMetrics
        val maxX = (metrics.widthPixels - root.width).coerceAtLeast(0)
        val maxY = (metrics.heightPixels - root.height).coerceAtLeast(0)
        layoutParams.x = x.coerceIn(0, maxX)
        layoutParams.y = y.coerceIn(0, maxY)
        windowManager.updateViewLayout(root, layoutParams)
    }

    private fun playAuto(rockerView: RockerView) {
        val playButton = root.findViewById<AppCompatImageView>(R.id.auto_play)
        if (autoStatus) {
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
        autoView: AppCompatImageView,
        expandMenuView: AppCompatImageView
    ) {
        val bar = root.findViewById<LinearLayout>(R.id.rocker_bar)
        val autoCard = root.findViewById<View>(R.id.auto_card)

        if (isHide) {
            // 缩小态：隐藏大摇杆及次要按钮，将控制行排布为横向精简胶囊
            autoCard.visibility = View.VISIBLE
            autoView.visibility = View.GONE
            rockerView.visibility = View.GONE
            expandMenuView.visibility = View.GONE
            bar.orientation = LinearLayout.HORIZONTAL
        } else {
            // 展开态：还原大摇杆及主控按钮，autoCard 还原为用户选择的可见性
            autoView.visibility = View.VISIBLE
            rockerView.visibility = View.VISIBLE
            expandMenuView.visibility = View.VISIBLE
            autoCard.visibility = if (autoCardVisible) View.VISIBLE else View.GONE
            bar.orientation = LinearLayout.VERTICAL
        }
    }

    fun show() {
        windowManager.addView(root, layoutParams)
        isStart = true
        root.post { moveWindowTo(layoutParams.x, layoutParams.y) }
    }

    fun hide() {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        rockerView.reset()
        windowManager.removeView(root)
        isStart = false
    }

    fun savePosition() {
        activity.rockerCoords = Pair(layoutParams.x, layoutParams.y)
    }

    fun updateView() {
        windowManager.updateViewLayout(root, layoutParams)
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
        // 模拟摇杆移动 调用onTouchEvent
        rockerView.onTouchEvent(
            MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_DOWN, joystickX, joystickY, 0)
        )
        rockerView.onTouchEvent(
            MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_MOVE, joystickX, joystickY, 0)
        )
        rockerView.auto(true)
    }

    fun upController() {
        val rockerView = root.findViewById<RockerView>(R.id.rocker)
        rockerView.onTouchEvent(
            MotionEvent.obtain(1000, 1000, MotionEvent.ACTION_UP, 0f, 0f, 0)
        )
        rockerView.auto(true)
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
        interface OnAutoListener {
            fun onAutoPlay(isPlay: Boolean)
            fun onAutoLock(isLock: Boolean)
        }
    }
}
