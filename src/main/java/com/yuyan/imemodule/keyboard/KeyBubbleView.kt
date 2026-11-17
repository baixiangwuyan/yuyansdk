package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.yuyan.imemodule.data.theme.ThemeManager
import kotlin.math.max

/**
 * 花花：按键气泡（按下字符键时在键上方弹出放大的字）。外形照 CyIME 1.15.94 的点按气泡：
 * 键上方留一小段空隙的胶囊，带一点阴影，键顶边上一个指向按键的小三角。
 *
 * 做成盖在整个输入法界面上的一层，不收触摸，只画一个气泡：
 * 按下时只改几个数再 invalidate，不增删 View、不重新布局，所以和按键高亮在同一帧出来。
 */
@SuppressLint("ViewConstructor")
class KeyBubbleView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pointer = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val body = RectF()
    private val path = Path()
    private var label: String? = null
    private var keyCenterX = 0f
    private var keyTop = 0f
    private var keyWidth = 0f
    private val selfLoc = IntArray(2)
    private val keyLoc = IntArray(2)

    init {
        isClickable = false
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setWillNotDraw(false)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent?): Boolean = false

    /** [keyboard] 里 (left, top, right, bottom) 的键按下了。 */
    fun show(keyboard: View, label: String, left: Int, top: Int, right: Int, bottom: Int) {
        try {
            showInternal(keyboard, label, left, top, right, bottom)
        } catch (e: Throwable) {
            android.util.Log.e("KeyBubbleView", "show failed", e)
            this.label = null
            invalidate()
        }
    }

    private fun showInternal(keyboard: View, label: String, left: Int, top: Int, right: Int, bottom: Int) {
        getLocationInWindow(selfLoc)
        keyboard.getLocationInWindow(keyLoc)
        val dx = (keyLoc[0] - selfLoc[0]).toFloat()
        val dy = (keyLoc[1] - selfLoc[1]).toFloat()
        this.label = label
        keyCenterX = dx + (left + right) / 2f
        keyTop = dy + top
        keyWidth = (right - left).toFloat()
        val keyHeightDp = (bottom - top) / density
        layoutBubble((keyHeightDp / 56f).coerceIn(1f, 1.5f))
        invalidate()
    }

    fun hide() {
        if (label == null) return
        label = null
        invalidate()
    }

    private var bubbleScale = 1f

    private fun layoutBubble(scale: Float) {
        bubbleScale = scale
        val theme = ThemeManager.activeTheme
        val bg = theme.keyBackgroundColor
        // 键色带透明度（透明主题）时气泡改用长按弹窗的底色，保证看得清。
        fill.color = if (Color.alpha(bg) < 0xF0) theme.popupBackgroundColor or (0xFF shl 24) else bg
        text.color = theme.keyTextColor
        pointer.color = (theme.accentKeyBackgroundColor and 0x00FFFFFF) or (0x99 shl 24)
        text.textSize = 20f * density * scale * resources.configuration.fontScale.coerceIn(0.85f, 1.3f)
        val bodyH = 40f * density * scale
        val bodyW = max(text.measureText(label ?: "") + 20f * density * scale, bodyH)
        val gap = 6f * density * scale
        val margin = 4f * density
        var l = keyCenterX - bodyW / 2f
        l = l.coerceIn(margin, max(margin, width - bodyW - margin))
        body.set(l, keyTop - gap - bodyH, l + bodyW, keyTop - gap)
    }

    override fun onDraw(canvas: Canvas) {
        if (label == null) return
        // 花花：气泡绘制出错就不画气泡（并停用效果），绝不让输入法界面崩溃。
        val save = canvas.save()
        try {
            drawBubble(canvas)
        } catch (e: Throwable) {
            label = null
            com.yuyan.imemodule.keyboard.effect.KeyEffects.disable("KeyBubbleView.onDraw", e)
        }
        canvas.restoreToCount(save)
    }

    private fun drawBubble(canvas: Canvas) {
        val s = label ?: return
        val radius = body.height() / 2f
        // 柔和阴影：两圈半透明外扩，硬件加速下不用 setShadowLayer。
        val shadow = 4f * density * bubbleScale
        for (i in 2 downTo 1) {
            val e = shadow * i / 2f
            shadowPaint.color = Color.argb(if (i == 2) 0x0F else 0x18, 0, 0, 0)
            canvas.drawRoundRect(body.left - e, body.top - e + e / 3f, body.right + e, body.bottom + e + e / 3f,
                radius + e, radius + e, shadowPaint)
        }
        canvas.drawRoundRect(body, radius, radius, fill)
        // 键顶边上指向按键的小三角。
        val halfBase = keyWidth * 0.16f
        val depth = halfBase * 1.06f
        path.rewind()
        path.moveTo(keyCenterX - halfBase, keyTop)
        path.lineTo(keyCenterX, keyTop + depth)
        path.lineTo(keyCenterX + halfBase, keyTop)
        path.close()
        canvas.drawPath(path, pointer)
        val fm = text.fontMetrics
        canvas.drawText(s, body.centerX(), body.centerY() - (fm.ascent + fm.descent) / 2f, text)
    }
}
