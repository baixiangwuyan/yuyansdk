package com.yuyan.imemodule.keyboard.effect

import android.annotation.SuppressLint
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.keyboard.KeyBubbleView
import com.yuyan.imemodule.keyboard.TextKeyboard

/**
 * 花花：「按键效果」设置页顶上的一小块预览键盘（两排键），按一下就能看到气泡和各种动效。
 * 用的就是键盘本身的绘制代码（[TextKeyboard]），只是不接输入、不弹长按菜单。
 */
@SuppressLint("ViewConstructor")
class KeyEffectPreviewView(context: Context) : FrameLayout(context) {
    private val density = resources.displayMetrics.density
    private val bubbleSpace = (56 * density).toInt()
    private val rowHeight = (52 * density).toInt()
    private val bubble = KeyBubbleView(context)
    private val keys = PreviewKeys(context)

    init {
        clipChildren = false
        addView(keys, LayoutParams(LayoutParams.MATCH_PARENT, rowHeight * 2).apply { topMargin = bubbleSpace })
        addView(bubble, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        refreshTheme()
    }

    fun refreshTheme() {
        val theme = ThemeManager.activeTheme
        keys.background = runCatching { KeyboardBackground.of(theme, ThemeManager.prefs.keyBorder.getValue()) }.getOrNull()
        keys.setTheme(theme)
    }

    /** 设置改了以后调：下一帧按新设置画。 */
    fun refresh() {
        keys.invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(bubbleSpace + rowHeight * 2, MeasureSpec.EXACTLY))
    }

    private inner class PreviewKeys(context: Context) : TextKeyboard(context) {
        private val labels = listOf(listOf("花", "花", "输", "入", "法"), listOf("按", "键", "效", "果", ""))
        private var current: SoftKey? = null

        init {
            val rows = labels.mapIndexed { r, row ->
                row.mapIndexed { c, label ->
                    if (r == 1 && c == 4) SoftKey(code = KeyEvent.KEYCODE_DEL, label = "⌫") else SoftKey(label = label)
                }
            }
            setSoftKeyboard(SoftKeyboard(rows))
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), rowHeight * 2)
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val sk = mSoftKeyboard ?: return
            sk.mKeyRows.forEachIndexed { r, row ->
                row.forEachIndexed { c, key ->
                    key.mLeft = w * c / row.size
                    key.mRight = w * (c + 1) / row.size
                    key.mTop = h * r / sk.mKeyRows.size
                    key.mBottom = h * (r + 1) / sk.mKeyRows.size
                }
            }
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(me: MotionEvent): Boolean {
            when (me.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val key = getKeyIndices(me.x.toInt(), me.y.toInt()) ?: return true
                    current?.onReleased()
                    current = key
                    key.onPressed()
                    onKeyPressedVisual(key)
                    val label = bubbleLabel(key)
                    if (KeyEffects.safeConfig().bubble && !label.isNullOrEmpty()) bubble.show(this, label, key.mLeft, key.mTop, key.mRight, key.mBottom)
                    else bubble.hide()
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    current?.let { onKeyReleasedVisual(it); it.onReleased() }
                    current = null
                    bubble.hide()
                    invalidate()
                }
            }
            return true
        }
    }
}
