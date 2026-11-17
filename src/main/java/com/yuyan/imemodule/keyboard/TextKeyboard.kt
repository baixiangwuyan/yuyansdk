package com.yuyan.imemodule.keyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Paint.FontMetricsInt
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import androidx.core.graphics.ColorUtils
import com.yuyan.imemodule.keyboard.effect.FrostedBackdrop
import com.yuyan.imemodule.keyboard.effect.KeyEffectConfig
import com.yuyan.imemodule.keyboard.effect.KeyEffectRenderer
import com.yuyan.imemodule.keyboard.effect.KeyEffects
import com.yuyan.imemodule.keyboard.effect.LiquidRim
import android.graphics.Rect
import android.graphics.Typeface
import android.text.TextUtils
import android.view.KeyEvent
import com.yuyan.imemodule.data.theme.Theme
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.data.theme.ThemeManager.prefs
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyToggle
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.singleton.EnvironmentSingleton.Companion.instance
import kotlin.math.min
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.prefs.behavior.SkbStyleMode
import com.yuyan.imemodule.service.InputDispatcher

/**
 * 软件盘视图
 */
open class TextKeyboard(context: Context?) : BaseKeyboardView(context){
    private var mKeyboardChanged = false
    private var mNormalKeyTextSize = 0   //正常按键的文本大小
    private var mNormalKeyTextSizeSmall = 0  //正常按键的文本大小(小值)
    private val mPaint: Paint = Paint()   //绘制按键的画笔
    private val mFmi: FontMetricsInt
    private var isKeyBorder = false // 启用按键边框
    protected lateinit var mActiveTheme: Theme
    private var keyRadius = 0
    private var keyboardFontBold = false
    private var keyboardSymbol = false
    private var keyboardMnemonic = false
    private var skbStyleMode: SkbStyleMode = prefs.skbStyleMode.getValue()

    // 花花：按键效果（气泡以外的视觉效果，见 keyboard/effect）。关着时不画任何东西。
    private val keyEffects = KeyEffectRenderer(resources.displayMetrics.density)
    private var keyEffectsLaidOut = false
    private val keyCap = RectF()
    private val capPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glassEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val liquidRim = LiquidRim()
    private val frosted = FrostedBackdrop()
    private val selfLoc = IntArray(2)
    private val bgLoc = IntArray(2)

    /**
     * 构造方法
     */
    init {
        mPaint.isAntiAlias = true
        mFmi = mPaint.fontMetricsInt
        keyboardFontBold = prefs.keyboardFontBold.getValue()
        keyboardSymbol = prefs.keyboardSymbol.getValue()
        keyboardMnemonic = AppPrefs.getInstance().keyboardSetting.keyboardMnemonic.getValue()
    }

    /**
     * 设置键盘实体
     *
     * @param softSkb 键盘
     */
    override fun setSoftKeyboard(softSkb: SoftKeyboard) {
        super.setSoftKeyboard(softSkb)
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = activeTheme
        mPaint.color = mActiveTheme.keyTextColor
        mKeyboardChanged = true
        keyEffectsLaidOut = false
        keyEffects.clear()
        invalidateView()
    }

    /**
     * 刷新按键状态
     */
    fun updateStates() {
        var softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_ENTER) as? SoftKeyToggle
        softKey?.enableToggleState( if(mService!!.isAddPhrases)4 else InputModeSwitcher.mToggleStates.imeAction)
        softKey = mSoftKeyboard?.getKeyByCode(KeyEvent.KEYCODE_SHIFT_LEFT) as? SoftKeyToggle
        val shiftState = when (InputModeSwitcher.mToggleStates.modifiers) {
            KeyEvent.META_CAPS_LOCK_ON -> 2   // 全蓝：大写锁定（双击）
            KeyEvent.META_SHIFT_ON -> 1       // 蓝轮廓：大写一个后回小写
            else -> 0                         // 灰：小写
        }
        val isEnglishCell = AppPrefs.getInstance().input.abcSearchEnglishCell.getValue()
        softKey?.enableToggleState(shiftState + if(isEnglishCell) 3 else 0)
        // 花花：只是换了按键状态图标，尺寸不变，不必 requestLayout（以前每次都会触发一轮布局）。
        invalidateKey()
    }

    /**
     * 重置主题
     */
    open fun setTheme(theme: Theme) {
        isKeyBorder = prefs.keyBorder.getValue()
        keyRadius = prefs.keyRadius.getValue()
        mActiveTheme = theme
        mPaint.color = mActiveTheme.keyTextColor
        invalidateView()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        var measuredWidth = 0
        var measuredHeight = 0
        if (null != mSoftKeyboard) {
            measuredWidth = instance.skbWidth +  paddingLeft + paddingRight
            measuredHeight = instance.skbHeight + paddingTop + paddingBottom
        }
        setMeasuredDimension(measuredWidth, measuredHeight)
    }

    private fun invalidateView() {
        requestLayout()
        invalidateKey()
    }

    public override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        keyEffectsLaidOut = false
    }

    /**
     * 花花：直接画在（硬件加速的）画布上。
     *
     * 以前每次按下、松开都把整块键盘在 CPU 上重画进一张全尺寸位图，再把这张位图整张传给 GPU；
     * 现在只记录绘制指令，按下高亮更快出来，按键动效也能逐帧画而不重传位图。
     */
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        mKeyboardChanged = false
        mDrawPending = false
        val softKeyboard = mSoftKeyboard ?: return
        val env = instance
        mNormalKeyTextSize = env.keyTextSize
        mNormalKeyTextSizeSmall = env.keyTextSmallSize
        val keyXMargin = softKeyboard.keyXMargin
        val keyYMargin = (if(skbStyleMode == SkbStyleMode.Google && InputModeSwitcher.isQwert) softKeyboard.keyYMargin * 1.5
            else softKeyboard.keyYMargin.toDouble()).toInt()
        // 花花：效果出任何错都不能让键盘画不出来。先按设置画；效果抛异常就关掉效果（本进程内，改设置后重试），
        // 回到画布初始状态再按“全关”重画一遍——和没有效果时的老画法完全一样。
        val base = canvas.save()
        val effects = KeyEffects.safeConfig()
        try {
            drawKeyboard(canvas, softKeyboard, keyXMargin, keyYMargin, effects)
        } catch (e: Throwable) {
            canvas.restoreToCount(base)
            if (effects === KeyEffectConfig.OFF) {
                // 老画法本身出错：记日志，不让输入法崩溃（键盘区可能空白，但候选栏、收起等仍可用）
                android.util.Log.e("TextKeyboard", "draw keyboard failed", e)
                return
            }
            KeyEffects.disable("TextKeyboard.onDraw", e)
            keyEffects.clear()
            val retry = canvas.save()
            try {
                drawKeyboard(canvas, softKeyboard, keyXMargin, keyYMargin, KeyEffectConfig.OFF)
            } catch (e2: Throwable) {
                canvas.restoreToCount(retry)
                android.util.Log.e("TextKeyboard", "draw keyboard (effects off) failed", e2)
            }
        }
        canvas.restoreToCount(base)
    }

    private fun drawKeyboard(canvas: Canvas, softKeyboard: SoftKeyboard, keyXMargin: Int, keyYMargin: Int,
                             effects: KeyEffectConfig) {
        keyEffects.config = effects
        val glass = effects.glass && isKeyBorder
        if (effects.gap || effects.motion || effects.glow) {
            if (!keyEffectsLaidOut) layoutKeyEffects(softKeyboard, keyXMargin, keyYMargin)
            keyEffects.accentColor = mActiveTheme.accentKeyBackgroundColor
            keyEffects.lightKeys = androidx.core.graphics.ColorUtils.calculateLuminance(mActiveTheme.keyBackgroundColor) > 0.5
            keyEffects.translucentCaps = glass || !isKeyBorder
            keyEffects.drawUnderKeys(canvas, width, height, mActiveTheme.accentKeyBackgroundColor)
        }
        if (glass) updateBackdrop()
        var index = 0
        for (softKeys in softKeyboard.mKeyRows) {
            for (softKey in softKeys) {
                drawSoftKey(canvas, softKey, keyXMargin, keyYMargin, index, effects, glass)
                index++
            }
        }
        if (effects.anyAnimated && keyEffects.isAnimating()) postInvalidateOnAnimation()
    }

    /** 把每个键的格子告诉按键效果（键缝网按它算）。 */
    private fun layoutKeyEffects(softKeyboard: SoftKeyboard, keyXMargin: Int, keyYMargin: Int) {
        val count = softKeyboard.mKeyRows.sumOf { it.size }
        val cells = FloatArray(count * 4)
        var i = 0
        for (row in softKeyboard.mKeyRows) for (key in row) {
            cells[i * 4] = key.mLeft.toFloat(); cells[i * 4 + 1] = key.mTop.toFloat()
            cells[i * 4 + 2] = key.mRight.toFloat(); cells[i * 4 + 3] = key.mBottom.toFloat()
            i++
        }
        keyEffects.setKeys(cells, count, keyXMargin.toFloat(), keyYMargin.toFloat(), keyRadius.toFloat())
        keyEffectsLaidOut = true
    }

    private fun keyIndexOf(key: SoftKey): Int {
        var i = 0
        for (row in mSoftKeyboard?.mKeyRows ?: return -1) for (k in row) { if (k === key) return i; i++ }
        return -1
    }

    override fun onKeyPressedVisual(key: SoftKey) {
        try {
            pressKeyEffects(key)
        } catch (e: Throwable) {
            KeyEffects.disable("TextKeyboard.onKeyPressedVisual", e)
            keyEffects.clear()
        }
    }

    private fun pressKeyEffects(key: SoftKey) {
        val effects = KeyEffects.safeConfig()
        if (!effects.anyAnimated) return
        keyEffects.config = effects
        val softKeyboard = mSoftKeyboard ?: return
        if (!keyEffectsLaidOut) {
            val keyYMargin = (if(skbStyleMode == SkbStyleMode.Google && InputModeSwitcher.isQwert) softKeyboard.keyYMargin * 1.5
                else softKeyboard.keyYMargin.toDouble()).toInt()
            layoutKeyEffects(softKeyboard, softKeyboard.keyXMargin, keyYMargin)
        }
        keyEffects.accentColor = mActiveTheme.accentKeyBackgroundColor
        keyEffects.lightKeys = androidx.core.graphics.ColorUtils.calculateLuminance(mActiveTheme.keyBackgroundColor) > 0.5
        val index = keyIndexOf(key)
        if (index >= 0) keyEffects.press(index, key.code == KeyEvent.KEYCODE_DEL)
    }

    override fun bubbleLabel(key: SoftKey): String? {
        when (key.code) {
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SHIFT_LEFT,
            KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_AT, KeyEvent.KEYCODE_TAB -> return null
        }
        if (key.isUserDefKey || key.keyIcon != null) return null
        val label = if (skbStyleMode == SkbStyleMode.Google) {
            if (InputModeSwitcher.isLower) key.keyLabel.lowercase() else key.keyLabel
        } else {
            if (InputModeSwitcher.isLower && InputModeSwitcher.isEnglish) key.keyLabel.lowercase() else key.keyLabel
        }
        return label.takeIf { it.isNotBlank() }
    }

    override fun onKeyReleasedVisual(key: SoftKey?) {
        // 松手时手指可能已经滑到别的键上，按下过的键一律当作松开，免得炫光底色留在原来那个键上。
        try { keyEffects.releaseAll() } catch (e: Throwable) { KeyEffects.disable("TextKeyboard.onKeyReleasedVisual", e) }
    }

    /** 玻璃效果：找到画着主题背景图的那一层（键盘根布局），算出它相对本视图的位置。 */
    private fun updateBackdrop() {
        var v: android.view.View? = this
        var holder: android.view.View? = null
        while (v != null) {
            if (v.background is BitmapDrawable) { holder = v; break }
            v = v.parent as? android.view.View
        }
        val drawable = holder?.background as? BitmapDrawable
        if (holder == null || drawable == null) { frosted.update(null, 0f, 0f, 0f, 0f); return }
        getLocationInWindow(selfLoc)
        holder.getLocationInWindow(bgLoc)
        val b = drawable.bounds
        frosted.colorFilter = drawable.colorFilter
        frosted.update(drawable.bitmap, (bgLoc[0] - selfLoc[0] + b.left).toFloat(), (bgLoc[1] - selfLoc[1] + b.top).toFloat(),
            b.width().toFloat(), b.height().toFloat())
    }

    /** 键帽底色。玻璃关着时和原来一模一样（同样的圆角矩形、同样的颜色）。 */
    private fun drawCap(canvas: Canvas, color: Int, glass: Boolean, effects: KeyEffectConfig, pressed: Boolean) {
        val radius = keyRadius.toFloat()
        if (!glass) {
            capPaint.shader = null
            capPaint.color = color
            canvas.drawRoundRect(keyCap, radius, radius, capPaint)
            return
        }
        val hasImage = frosted.available
        if (hasImage) frosted.drawFrost(canvas, keyCap, radius)
        val w = keyCap.width(); val h = keyCap.height()
        if (effects.liquid && hasImage) {
            val save = canvas.save()
            canvas.translate(keyCap.left, keyCap.top)
            frosted.drawRefraction(canvas, keyCap.left, keyCap.top, w, h, radius, effects.liquidStrength, resources.displayMetrics.density)
            canvas.restoreToCount(save)
        }
        // 保留原来的键色，只是变成半透明：有背景图时透出下面模糊过的图，纯色背景时透出一点底色。
        val alpha = Color.alpha(color) / 255f * (if (hasImage) 0.5f else 0.72f)
        capPaint.shader = null
        capPaint.color = (color and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
        canvas.drawRoundRect(keyCap, radius, radius, capPaint)
        if (effects.liquid) {
            val save = canvas.save()
            canvas.translate(keyCap.left, keyCap.top)
            val dark = ColorUtils.calculateLuminance(mActiveTheme.keyBackgroundColor or (0xFF shl 24)) < 0.5
            liquidRim.draw(canvas, w, h, radius, dark, effects.liquidStrength, resources.displayMetrics.density, pressed)
            canvas.restoreToCount(save)
        } else {
            // 毛玻璃的一道淡淡的亮边。
            glassEdgePaint.strokeWidth = resources.displayMetrics.density * 0.75f
            glassEdgePaint.color = Color.argb(if (mActiveTheme.isDark) 0x22 else 0x55, 255, 255, 255)
            val inset = glassEdgePaint.strokeWidth / 2f
            canvas.drawRoundRect(keyCap.left + inset, keyCap.top + inset, keyCap.right - inset, keyCap.bottom - inset,
                radius, radius, glassEdgePaint)
        }
    }

    /**
     * 在画布上画一个按键
     *
     * @param canvas     画布
     * @param softKey    需绘制的按键
     * @param keyXMargin 按键左右边间距
     * @param keyYMargin 按键上下边间距
     */
    private fun drawSoftKey(canvas: Canvas, softKey: SoftKey, keyXMargin: Int, keyYMargin: Int, index: Int,
                            effects: KeyEffectConfig, glass: Boolean) {
        val scale = if (effects.motion) keyEffects.capScale(index) else 1f
        val saveCount = canvas.save()
        if (scale != 1f) canvas.scale(scale, scale, (softKey.mLeft + softKey.mRight) / 2f, (softKey.mTop + softKey.mBottom) / 2f)
        keyCap.set((softKey.mLeft + keyXMargin).toFloat(), (softKey.mTop + keyYMargin).toFloat(),
            (softKey.mRight - keyXMargin).toFloat(), (softKey.mBottom - keyYMargin).toFloat())
        val pressedLook = softKey.pressed || (mService?.hasSelection == true && softKey.code == InputModeSwitcher.USER_KEYCODE_SELECT_MODE)
        if (pressedLook) {
            drawCap(canvas, mActiveTheme.keyPressHighlightColor, glass, effects, true)
        } else if (isKeyBorder) {
            val background = when (softKey.code) {
                KeyEvent.KEYCODE_ENTER -> mActiveTheme.accentKeyBackgroundColor
                KeyEvent.KEYCODE_SPACE-> mActiveTheme.functionKeyBackgroundColor
                KeyEvent.KEYCODE_AT -> if (AppPrefs.getInstance().input.chineseFanTi.getValue()) mActiveTheme.accentKeyBackgroundColor else mActiveTheme.keyBackgroundColor
                else  -> mActiveTheme.keyBackgroundColor
            }
            drawCap(canvas, background, glass, effects, false)
        } else if(softKey.code == KeyEvent.KEYCODE_ENTER) {
               capPaint.shader = null
               capPaint.color = mActiveTheme.accentKeyBackgroundColor
               val bgWidth = softKey.width() -  keyXMargin
               val bgHeight = softKey.height() - keyYMargin
               val radius = min(bgWidth, bgHeight)*3/4
               val keyMarginX = (bgWidth - radius)/2
               val keyMarginY = (bgHeight - radius)/2
               canvas.drawOval((softKey.mLeft + keyMarginX).toFloat(), (softKey.mTop + keyMarginY).toFloat(),
                   (softKey.mRight - keyMarginX).toFloat(), (softKey.mBottom - keyMarginY).toFloat(), capPaint)
        }
        if (effects.glow) keyEffects.drawGlow(canvas, index, keyCap, keyRadius.toFloat())
        drawKeyContent(canvas, softKey, keyXMargin)
        canvas.restoreToCount(saveCount)
    }

    private fun drawKeyContent(canvas: Canvas, softKey: SoftKey, keyXMargin: Int) {
        var keyLabel = if(softKey.code == KeyEvent.KEYCODE_AT) {
            // 繁/简键动态显示单字：繁体显示"繁"，简体显示"简"
            if (AppPrefs.getInstance().input.chineseFanTi.getValue()) "繁" else "简"
        } else if(softKey.code == InputModeSwitcher.USER_KEYCODE_HW_FULL_SCREEN) {
            // 手写全屏/半屏切换键：显示点了之后要去的模式
            if (AppPrefs.getInstance().handwriting.handWritingFullScreen.getValue()) "半屏" else "全屏"
        } else if(softKey.code == KeyEvent.KEYCODE_ENTER) {
            // 回车键显示"换行"文字
            "换行"
        } else if(skbStyleMode == SkbStyleMode.Google){
            if(InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel
        } else {
            if(InputModeSwitcher.isLower && InputModeSwitcher.isEnglish) softKey.keyLabel.lowercase() else softKey.keyLabel
        }
        val keyLabelSmall = softKey.getmKeyLabelSmall()
        val keyMnemonic = softKey.keyMnemonic
        var keyIcon = if(softKey.code == KeyEvent.KEYCODE_ENTER) null
            else if(skbStyleMode == SkbStyleMode.Google && softKey.code == KeyEvent.KEYCODE_SPACE) null
            else if(skbStyleMode == SkbStyleMode.Google && softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION && !DictDecoder.isCandidatesEmpty) null
            else softKey.keyIcon
        val weightHeigth = softKey.height() / 4f
        val textColor = mActiveTheme.keyTextColor
        if(softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT && InputModeSwitcher.isChinese && !InputDispatcher.isFinish){
            keyLabel = "分词"
            keyIcon = null
        }
        if (keyboardSymbol && !TextUtils.isEmpty(keyLabelSmall)) {
            mPaint.color = textColor
            mPaint.setTypeface(Typeface.DEFAULT)
            if(skbStyleMode == SkbStyleMode.Samsung)mPaint.alpha = 128
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat()
            val x = when(prefs.skbStyleMode.getValue()){
                SkbStyleMode.Yuyan -> softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabelSmall)) / 2.0f
                SkbStyleMode.Samsung -> softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
                SkbStyleMode.Google -> softKey.mRight - mPaint.measureText(keyLabelSmall) - keyXMargin * 2
            }
            val y = softKey.mTop + weightHeigth * 1.1f
            canvas.drawText(keyLabelSmall, x, y, mPaint)
        }
        val imeName = if (softKey.code == KeyEvent.KEYCODE_SPACE) spaceImeName() else null
        if (imeName != null) {
            drawSpaceWithName(canvas, softKey, imeName, textColor)
        } else if (null != keyIcon) {
            var  intrinsicWidth = keyIcon.intrinsicWidth
            var  intrinsicHeight = keyIcon.intrinsicHeight
            while(softKey.width() < intrinsicWidth || softKey.height() < intrinsicHeight){
                intrinsicWidth /= 2
                intrinsicHeight /= 2
            }
            val marginLeft = (softKey.width() - intrinsicWidth) / 2
            val marginRight = softKey.width() - intrinsicWidth - marginLeft
            val marginTop = (softKey.height() - intrinsicHeight) / 2
            val marginBottom = softKey.height() - intrinsicHeight - marginTop
            if (softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT) {
                keyIcon.setTintList(null)   // 花花图保留原色
            } else {
                keyIcon.setTint(mActiveTheme.keyTextColor)
            }
            keyIcon.setBounds(softKey.mLeft + marginLeft, softKey.mTop + marginTop, softKey.mRight - marginRight, softKey.mBottom - marginBottom)
            keyIcon.draw(canvas)
        } else if (!TextUtils.isEmpty(keyLabel)) { //Label位于中间
            mPaint.color = textColor
            if(keyboardFontBold) mPaint.typeface = Typeface.DEFAULT_BOLD
            mPaint.textSize =  mNormalKeyTextSize.toFloat()
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyLabel)) / 2.0f
            val fontHeight = mFmi.bottom - mFmi.top
            val y = if(keyLabelSmall.isEmpty()) (softKey.mTop + softKey.mBottom) / 2.0f + fontHeight
            else  (softKey.mTop + softKey.mBottom) / 2.0f + fontHeight *1.5f
            canvas.drawText(keyLabel, x, y, mPaint)
        }
        if (keyboardMnemonic && !TextUtils.isEmpty(keyMnemonic)) {  //助记符位于中下方
            mPaint.color = textColor
            mPaint.typeface = Typeface.DEFAULT
            mPaint.textSize = mNormalKeyTextSizeSmall.toFloat() * 0.7f
            val x = softKey.mLeft + (softKey.width() - mPaint.measureText(keyMnemonic)) / 2.0f
            val y = softKey.mTop + weightHeigth * 3 + weightHeigth / 2.0f
            canvas.drawText(keyMnemonic, x, y, mPaint)
        }
    }

    private fun spaceImeName(): String? {
        if (InputModeSwitcher.isNumberSkb || InputModeSwitcher.isTextEditSkb) return null
        if (InputModeSwitcher.isEnglish) return "英文"
        if (!InputModeSwitcher.isChinese) return null
        val schema = InputDispatcher.getCurrentRimeSchema()
        return when {
            schema == CustomConstant.SCHEMA_ZH_T9 -> "拼音9键"
            schema == CustomConstant.SCHEMA_ZH_QWERTY -> "拼音全键"
            schema == CustomConstant.SCHEMA_ZH_HANDWRITING -> "手写"
            schema == CustomConstant.SCHEMA_ZH_STROKE -> "笔画"
            schema == CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> "乱序17"
            schema.endsWith("flypy") -> "小鹤双拼"
            schema.endsWith("mspy") -> "微软双拼"
            schema.endsWith("sogou") -> "搜狗双拼"
            schema.endsWith("ziguang") -> "紫光双拼"
            schema.endsWith("natural") -> "自然码"
            schema.endsWith("abc") -> "智能ABC"
            else -> null
        }
    }

    private val spacePath = android.graphics.Path()

    private fun drawSpaceWithName(canvas: Canvas, softKey: SoftKey, name: String, textColor: Int) {
        val density = resources.displayMetrics.density
        mPaint.color = textColor
        mPaint.style = Paint.Style.FILL
        mPaint.typeface = if (keyboardFontBold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        mPaint.textSize = mNormalKeyTextSize.toFloat()            // 和“换行”一样大
        val symW = min(softKey.width() * 0.18f, 24f * density)     // 空格符宽
        val symH = 3.5f * density                                  // 空格符高
        val cx = softKey.mLeft + softKey.width() / 2f
        // 文字基线：和“换行”等普通按键完全一样的算法，保证居中、对齐
        val baseline = (softKey.mTop + softKey.mBottom) / 2.0f + (mFmi.bottom - mFmi.top)
        canvas.drawText(name, cx - mPaint.measureText(name) / 2f, baseline, mPaint)
        // 空格符：在文字下方，空出一个符号高度；防止超出按键底边
        val descent = mPaint.fontMetrics.descent                   // 汉字在基线下还有一截
        val gap = 5f * density                                     // 文字底到符号顶的空隙，想再松就改大
        val symTop = min(baseline + descent + gap, softKey.mBottom - symH - 2f * density)
        spacePath.reset()
        spacePath.moveTo(cx - symW / 2f, symTop)
        spacePath.lineTo(cx - symW / 2f, symTop + symH)
        spacePath.lineTo(cx + symW / 2f, symTop + symH)
        spacePath.lineTo(cx + symW / 2f, symTop)
        mPaint.style = Paint.Style.STROKE
        mPaint.strokeWidth = 1.3f * density
        mPaint.strokeCap = Paint.Cap.ROUND
        mPaint.strokeJoin = Paint.Join.ROUND
        mPaint.alpha = 160
        canvas.drawPath(spacePath, mPaint)
        mPaint.alpha = 255
        mPaint.style = Paint.Style.FILL
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    override fun closing() {
        super.closing()
        try { keyEffects.clear() } catch (_: Throwable) {}
    }
}
