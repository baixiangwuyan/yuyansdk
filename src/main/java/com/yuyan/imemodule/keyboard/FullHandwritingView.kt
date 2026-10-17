package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.View
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.container.HandwritingContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.inputmethod.HWEngine
import java.util.LinkedList
import kotlin.math.roundToInt

/**
 * 手写笔迹共用缓冲：键盘手写区和全屏手写层写的笔画放在一起识别，
 * 坐标统一用输入法窗口坐标。
 */
object HwStrokes {
    val points: MutableList<Short?> = LinkedList()
    var lastUpTime: Long = 0L
    /** 两次落笔间隔超过它就上屏上一个字，与键盘手写一致 */
    val times: Long get() = 1200L - AppPrefs.getInstance().handwriting.handWritingSpeed.getValue()

    /** 停笔超时：自动把第一个候选字上屏，不用再点候选 */
    fun autoCommit(inputView: InputView?) {
        if (points.isNotEmpty()) {
            inputView?.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_SELECTED))
            points.clear()
        }
        lastUpTime = 0L
    }
}

/**
 * 全屏手写层：铺在键盘上方，在屏幕上半部分也能写字。
 * 只负责画笔迹、记坐标，识别和上屏沿用原来的手写流程。
 */
@SuppressLint("ViewConstructor")
class FullHandwritingView(context: Context, private val inputView: InputView) : View(context) {

    private val paint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var lastX = 0f
    private var lastY = 0f
    private val loc = IntArray(2)
    // 停笔超时：自动上屏第一个候选，并清掉笔迹
    private val clearRunnable = Runnable {
        HwStrokes.autoCommit(inputView)
        path.reset(); invalidate()
    }
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    // 落在候选栏等区域时先不判定：移动了算写字，没移动算点击（转发给候选栏）
    private var pendingTap = false
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        if (!inputView.isFullHandwriting()) return false
        getLocationInWindow(loc)
        val x = me.x
        val y = me.y
        when (me.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 不等屏幕刷新批量送点，笔迹跟手更紧
                requestUnbufferedDispatch(me)
                when (touchArea(x + loc[0], y + loc[1])) {
                    AREA_KEY -> return false // 按键：交给键盘
                    AREA_OTHER -> { // 候选栏等：先观望
                        pendingTap = true
                        downX = x; downY = y; downTime = me.downTime
                        return true
                    }
                    else -> { pendingTap = false; startStroke(x, y) }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (pendingTap) {
                    if (Math.hypot((x - downX).toDouble(), (y - downY).toDouble()) < touchSlop) return true
                    pendingTap = false
                    startStroke(downX, downY)
                }
                for (i in 0 until me.historySize) moveTo(me.getHistoricalX(i), me.getHistoricalY(i))
                moveTo(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pendingTap) {
                    pendingTap = false
                    if (me.actionMasked == MotionEvent.ACTION_UP) forwardTap(downX + loc[0], downY + loc[1], me.eventTime)
                    return true
                }
                path.lineTo(x, y)
                addPoint(x, y)
                HwStrokes.points.add(-1)
                HwStrokes.points.add(0)
                HwStrokes.lastUpTime = System.currentTimeMillis()
                HWEngine.recognitionData(HwStrokes.points) { items ->
                    postDelayed({ inputView.responseHandwritingResultEvent(items) }, 20)
                }
                postDelayed(clearRunnable, HwStrokes.times)
            }
            else -> return false
        }
        invalidate()
        return true
    }

    private fun startStroke(x: Float, y: Float) {
        removeCallbacks(clearRunnable)
        val last = HwStrokes.lastUpTime
        if (last != 0L && System.currentTimeMillis() - last > HwStrokes.times) {
            // 停笔够久（自动上屏没赶上时）：先把上一个字上屏
            HwStrokes.autoCommit(inputView)
            path.reset()
        }
        val w = AppPrefs.getInstance().handwriting.handWritingWidth.getValue() * 4 / 10f
        paint.strokeWidth = (context.resources.displayMetrics.density * w * 0.75f).coerceAtLeast(2f)
        paint.color = ThemeManager.activeTheme.keyTextColor
        path.moveTo(x, y)
        lastX = x; lastY = y
        addPoint(x, y)
    }

    /** 把一次点击转发给键盘根布局（候选栏点字） */
    private fun forwardTap(wx: Float, wy: Float, upTime: Long) {
        val root = inputView.mSkbRoot
        root.getLocationInWindow(tmpLoc)
        val rx = wx - tmpLoc[0]
        val ry = wy - tmpLoc[1]
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, rx, ry, 0)
        val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, rx, ry, 0)
        root.dispatchTouchEvent(down)
        root.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
    }

    private val tmpLoc = IntArray(2)

    /** 落笔点：按键上交给键盘；候选栏等区域先观望；键盘手写区和键盘上方直接写 */
    private fun touchArea(wx: Float, wy: Float): Int {
        val root = inputView.mSkbRoot
        root.getLocationInWindow(tmpLoc)
        val inRoot = wx >= tmpLoc[0] && wx < tmpLoc[0] + root.width && wy >= tmpLoc[1] && wy < tmpLoc[1] + root.height
        if (!inRoot) return AREA_WRITE // 键盘上方
        val kb = (KeyboardManager.instance.currentContainer as? HandwritingContainer)?.handwritingKeyboardView ?: return AREA_OTHER
        kb.getLocationInWindow(tmpLoc)
        val kx = (wx - tmpLoc[0]).toInt()
        val ky = (wy - tmpLoc[1]).toInt()
        if (kx < 0 || ky < 0 || kx >= kb.width || ky >= kb.height) return AREA_OTHER // 候选栏等
        return if (kb.getKeyIndices(kx, ky) != null) AREA_KEY else AREA_WRITE
    }

    companion object {
        private const val AREA_WRITE = 0
        private const val AREA_KEY = 1
        private const val AREA_OTHER = 2
    }

    private fun moveTo(x: Float, y: Float) {
        // 用中点做二次曲线，笔迹更顺滑
        path.quadTo(lastX, lastY, (x + lastX) / 2, (y + lastY) / 2)
        lastX = x; lastY = y
        addPoint(x, y)
    }

    private fun addPoint(x: Float, y: Float) {
        HwStrokes.points.add((x + loc[0]).roundToInt().toShort())
        HwStrokes.points.add((y + loc[1]).roundToInt().toShort())
    }

    fun clearInk() {
        removeCallbacks(clearRunnable)
        pendingTap = false
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }
}
