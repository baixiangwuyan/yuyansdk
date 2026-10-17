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
    private val clearRunnable = Runnable { path.reset(); invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        if (!inputView.isFullHandwriting()) return false
        getLocationInWindow(loc)
        val x = me.x
        val y = me.y
        when (me.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (shouldPassThrough(x + loc[0], y + loc[1])) return false
                removeCallbacks(clearRunnable)
                val last = HwStrokes.lastUpTime
                if (last != 0L && System.currentTimeMillis() - last > HwStrokes.times) {
                    // 停笔够久：先把上一个字上屏
                    inputView.responseKeyEvent(SoftKey(InputModeSwitcher.USER_KEYCODE_SELECTED))
                    HwStrokes.points.clear()
                    path.reset()
                }
                val w = AppPrefs.getInstance().handwriting.handWritingWidth.getValue() * 4 / 10f
                paint.strokeWidth = (context.resources.displayMetrics.density * w * 0.75f).coerceAtLeast(2f)
                paint.color = ThemeManager.activeTheme.keyTextColor
                path.moveTo(x, y)
                lastX = x; lastY = y
                addPoint(x, y)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until me.historySize) moveTo(me.getHistoricalX(i), me.getHistoricalY(i))
                moveTo(x, y)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
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

    private val tmpLoc = IntArray(2)

    /** 落笔点在候选栏、按键上时不接管，交给下面的键盘；落在键盘手写空白区或键盘上方则由本层书写 */
    private fun shouldPassThrough(wx: Float, wy: Float): Boolean {
        val root = inputView.mSkbRoot
        root.getLocationInWindow(tmpLoc)
        val inRoot = wx >= tmpLoc[0] && wx < tmpLoc[0] + root.width && wy >= tmpLoc[1] && wy < tmpLoc[1] + root.height
        if (!inRoot) return false // 键盘上方：全屏书写
        val kb = (KeyboardManager.instance.currentContainer as? HandwritingContainer)?.handwritingKeyboardView ?: return true
        kb.getLocationInWindow(tmpLoc)
        val kx = (wx - tmpLoc[0]).toInt()
        val ky = (wy - tmpLoc[1]).toInt()
        if (kx < 0 || ky < 0 || kx >= kb.width || ky >= kb.height) return true // 候选栏等其它区域
        return kb.getKeyIndices(kx, ky) != null // 按在按键上就放行
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
        path.reset()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!path.isEmpty) canvas.drawPath(path, paint)
    }
}
