package com.yuyan.imemodule.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.entity.keyboard.SoftKeyboard
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.prefs.behavior.KeyboardSymbolSlideUpMod
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.view.popup.PopupComponent.Companion.get
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs
import kotlin.math.absoluteValue

/**
 * 键盘根布局
 *
 * 由于之前键盘体验问题，当前基于Android内置键盘[android.inputmethodservice.KeyboardView]进行调整开发。
 */
open class BaseKeyboardView(mContext: Context?) : View(mContext) {
    private val popupComponent: PopupComponent = get()
    protected var mSoftKeyboard: SoftKeyboard? = null
    private var mCurrentKey: SoftKey? = null
    private var mGestureDetector: GestureDetector? = null
    protected var mLongPressKey = false
    private var mAbortKey = false
    private var mHandler: Handler? = null
    protected var mDrawPending = false
    protected var mService: InputView? = null
    fun setResponseKeyEvent(service: InputView) {
        mService = service
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        initGestureDetector()
        if (mHandler == null) {
            mHandler = object : Handler(Looper.getMainLooper()) {
                override fun handleMessage(msg: Message) {
                    when (msg.what) {
                        MSG_REPEAT -> {
                            if (repeatKey()) {
                                val repeat = Message.obtain(this, MSG_REPEAT)
                                sendMessageDelayed(repeat, REPEAT_INTERVAL)
                            }
                        }

                        MSG_LONGPRESS -> openPopupIfRequired()
                    }
                }
            }
        }
    }

    private fun initGestureDetector() {
        if (mGestureDetector == null) {
            mGestureDetector = GestureDetector(context, object : SimpleOnGestureListener() {
                override fun onScroll(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                    if(mLongPressKey && mCurrentKey?.getkeyLabel()?.isNotBlank() == true){
                        popupComponent.changeFocus(currentEvent.x - downEvent!!.x, currentEvent.y - downEvent.y)
                    } else {
                        dispatchGestureEvent(downEvent, currentEvent, distanceX, distanceY)
                    }
                    return true
                }
                override fun onDown(e: MotionEvent): Boolean {
                    currentDistanceY = 0f
                    return super.onDown(e)
                }
            })
            mGestureDetector!!.setIsLongpressEnabled(false)
        }
    }

    fun invalidateKey() {
        mDrawPending = true
        invalidate()
    }

    /** 花花：按下（ACTION_DOWN 当帧）通知子类开始按键效果。 */
    open fun onKeyPressedVisual(key: SoftKey) {}

    /** 花花：松开；[key] 为 null 表示全部松开。 */
    open fun onKeyReleasedVisual(key: SoftKey?) {}

    /** 花花：按键气泡里显示的字；返回 null 不弹气泡（功能键、图标键）。 */
    open fun bubbleLabel(key: SoftKey): String? {
        if (key.isUserDefKey || key.keyIcon != null) return null
        return key.getkeyLabel().takeIf { it.isNotBlank() }
    }
    private fun openPopupIfRequired() {
        if(mCurrentKey != null) {
            val softKey = mCurrentKey!!
            val keyboardSymbol = ThemeManager.prefs.keyboardSymbol.getValue()
            hideBubble()   // 花花：长按弹出选项时收起按键气泡
            if (softKey.getkeyLabel().isNotBlank() && softKey.code != InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ) {
                val keyLabel = if (InputModeSwitcher.isLower) softKey.keyLabel.lowercase() else softKey.keyLabel
                val designPreset = setOf("，", "。", ",", ".")
                val smallLabel = if(designPreset.any { it == keyLabel } || !keyboardSymbol) "" else softKey.getmKeyLabelSmall()
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboard(keyLabel, smallLabel, bounds)
                mLongPressKey = true
            } else if (softKey.code == InputModeSwitcher.USER_KEYCODE_LANG ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_COMMA_EMOJI ||
                    softKey.code == KeyEvent.KEYCODE_SHIFT_LEFT ||
                softKey.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION ||
                softKey.code == KeyEvent.KEYCODE_DEL || softKey.code == KeyEvent.KEYCODE_ENTER){
                val bounds = Rect(softKey.mLeft, softKey.mTop, softKey.mRight, softKey.mBottom)
                popupComponent.showKeyboardMenu(softKey, bounds, currentDistanceY)
                mLongPressKey = true
            } else {
                mLongPressKey = true
                mAbortKey = true
                dismissPreview()
            }
        }
    }

    private var motionEventQueue: Queue<MotionEvent> = LinkedList()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(me: MotionEvent): Boolean {
        var result = false
        if (mGestureDetector!!.onTouchEvent(me)) {
            return true
        }
        when (val action = me.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val actionIndex = me.actionIndex
                val x = me.getX(actionIndex)
                val y = me.getY(actionIndex)
                val now = me.eventTime
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, me.metaState)
                motionEventQueue.offer(down)
                result = onModifiedTouchEvent(me)
                val keyIndex = getKeyIndices(x.toInt(), y.toInt())
                if(keyIndex != null) {
                    DevicesUtils.tryPlayKeyDown(keyIndex.code)
                    DevicesUtils.tryVibrate(this)
                }
                showPreview(keyIndex)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val now = me.eventTime
                val act = if(action == MotionEvent.ACTION_CANCEL)MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP
                while (!motionEventQueue.isEmpty()) {
                    val first = motionEventQueue.poll()
                    if(first!= null) {
                        result = onModifiedTouchEvent(MotionEvent.obtain(now, now, act, first.x, first.y, me.metaState))
                    }
                }
                dismissPreview()
            }
            else -> {
                result = onModifiedTouchEvent(me)
            }
        }
        return result
    }

    private fun onModifiedTouchEvent(me: MotionEvent): Boolean {
        when (me.action) {
            MotionEvent.ACTION_DOWN -> {
                mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
                mAbortKey = false
                mLongPressKey = false
                if(mCurrentKey != null){
                    if (mCurrentKey!!.repeatable()) {
                        val msg = mHandler!!.obtainMessage(MSG_REPEAT)
                        mHandler!!.sendMessageDelayed(msg, REPEAT_START_DELAY)
                    }
                    val msg = mHandler!!.obtainMessage(MSG_LONGPRESS)
                    mHandler!!.sendMessageDelayed(msg, AppPrefs.getInstance().keyboardSetting.longPressTimeout.getValue().toLong())
                }
            }
            MotionEvent.ACTION_UP -> {
                mCurrentKey?.onReleased()
                mCurrentKey = getKeyIndices(me.x.toInt(), me.y.toInt())
                removeMessages()
                if (!mAbortKey && !mLongPressKey && mCurrentKey != null) {
                    mService?.responseKeyEvent(mCurrentKey!!)
                }
                currentDistanceX = 0F
                currentDistanceY = 0F
            }
            MotionEvent.ACTION_CANCEL -> {
                removeMessages()
                currentDistanceX = 0F
                currentDistanceY = 0F
            }
        }
        return true
    }

    private var lastEventX:Float = -1f
    private var lastEventY:Float = -1f
    private var currentDistanceY:Float = 0f
    private var currentDistanceX:Float = 0f
    private var lastEventActionIndex:Int = 0
    // 处理手势滑动
    private fun dispatchGestureEvent(downEvent: MotionEvent?, currentEvent: MotionEvent, distanceX: Float, distanceY: Float) : Boolean {
        var result = false
        val currentX = currentEvent.x
        val currentY = currentEvent.y
        currentDistanceX = distanceX
        currentDistanceY = distanceY
        val keyLableSmall = mCurrentKey?.getmKeyLabelSmall()
        if(currentEvent.pointerCount > 1) return false    // 避免多指触控导致上屏
        if(lastEventX < 0 || lastEventActionIndex != currentEvent.actionIndex) {   // 避免多指触控导致符号上屏
            lastEventX = currentX
            lastEventY = currentY
            lastEventActionIndex = currentEvent.actionIndex
            return false
        }
        val relDiffX = abs(currentX - lastEventX)
        val relDiffY = abs(currentY - lastEventY)
        val isVertical = relDiffX * 1.5 < relDiffY  //横向、竖向滑动距离接近时，优先触发左右滑动
        val symbolSlideUp = EnvironmentSingleton.instance.heightForCandidatesArea / when(ThemeManager.prefs.symbolSlideUpMod.getValue()){
            KeyboardSymbolSlideUpMod.SHORT -> 3;KeyboardSymbolSlideUpMod.MEDIUM -> 2;else -> 1
        }
        val spaceSwipeMoveCursorSpeed = AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursorSpeed.getValue()
        if (!isVertical && relDiffX > spaceSwipeMoveCursorSpeed) {  // 左右滑动
            val isSwipeKey = mCurrentKey?.code == KeyEvent.KEYCODE_SPACE || mCurrentKey?.code == KeyEvent.KEYCODE_0
            if(mCurrentKey?.code == KeyEvent.KEYCODE_DEL && distanceX > 20){// 左滑删除
                removeMessages()
                mAbortKey = true
                mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_CLEAR))
            } else if (isSwipeKey && AppPrefs.getInstance().keyboardSetting.spaceSwipeMoveCursor.getValue()) {  // 左右滑动
                removeMessages()
                lastEventX = currentX
                lastEventY = currentY
                mAbortKey = true
                mService!!.responseKeyEvent(SoftKey(code = if (distanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT))
                result = true
            }
        } else if(keyLableSmall?.isNotBlank() == true){
            if (isVertical && distanceY > 0 && relDiffY > symbolSlideUp && ThemeManager.prefs.keyboardSymbol.getValue()){   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                removeMessages()
                mService?.responseLongKeyEvent(Pair(PopupMenuMode.Text, keyLableSmall))
                result = true
            }
        } else {  // 菜单
            if (isVertical && relDiffY > symbolSlideUp * 2) {   // 向上滑动
                lastEventX = currentX
                lastEventY = currentY
                lastEventActionIndex = currentEvent.actionIndex
                mLongPressKey = true
                popupComponent.onGestureEvent(distanceY)
            } else {
                if(downEvent != null) popupComponent.changeFocus(currentEvent.x - downEvent.x, currentEvent.y - downEvent.y)
            }
        }
        return result
    }

    private fun repeatKey(): Boolean {
        if (mCurrentKey != null && mCurrentKey!!.repeatable()) {
            if(mCurrentKey!!.code == KeyEvent.KEYCODE_DEL && mLongPressKey) {
                    if (currentDistanceX.absoluteValue >= currentDistanceY.absoluteValue ) {
                        if (currentDistanceX >= -2) {
                            mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
                        } else {
                            mService?.responseLongKeyEvent(Pair(PopupMenuMode.Revertl,  ""))
                        }
                    } else {
                        if (currentDistanceY > 0) {
                            mService?.responseKeyEvent(SoftKey(KeyEvent.KEYCODE_DEL))
                        } else if (currentDistanceY < 0) {
                            mService?.responseLongKeyEvent(Pair(PopupMenuMode.Revertl,  ""))
                        }
                    }
            } else {
                mService?.responseKeyEvent(
                    if (mCurrentKey!!.code == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION) {
                        SoftKey(
                            if (currentDistanceX.absoluteValue >= currentDistanceY.absoluteValue) {
                                if (currentDistanceX > 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
                            } else {
                                if (currentDistanceY < 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP
                            }
                        )
                    } else mCurrentKey!!
                )
            }
        }
        return true
    }

    private fun removeMessages() {
        if (mHandler != null) {
            mHandler!!.removeMessages(MSG_REPEAT)
            mHandler!!.removeMessages(MSG_LONGPRESS)
            mHandler!!.removeMessages(MSG_SHOW_PREVIEW)
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        closing()
    }

    /**
     * 显示短按气泡
     */
    private fun showPreview(key: SoftKey?) {
        mCurrentKey?.onReleased()
        if (key != null) {
            key.onPressed()
            safeVisual { onKeyPressedVisual(key) }
            showBalloonText(key)
        } else {
            hideBubble()
            popupComponent.dismissPopup()
        }
        invalidateKey()
    }

    /**
     * 隐藏短按气泡
     */
    private fun dismissPreview() {
        if (mLongPressKey) {
            mService?.responseLongKeyEvent(popupComponent.triggerFocused())
            mLongPressKey = false
        }
        hideBubble()
        safeVisual { onKeyReleasedVisual(mCurrentKey) }
        if (mCurrentKey != null) {
            mCurrentKey!!.onReleased()
            if(mService == null) return
            invalidateKey()
        }
        popupComponent.dismissPopup()
        lastEventX = -1f
    }

    open fun closing() {
        removeMessages()
    }

    /**
     * 花花：按键气泡。按下当帧就画在输入法界面最上面一层（[KeyBubbleView]），不增删 View、不重新布局。
     * 开关沿用原来的「按键气泡」（keyboard_balloon_show_enable），默认改为开。
     */
    private fun showBalloonText(key: SoftKey) {
        // 花花：气泡出错不能影响按键本身（按下高亮、上屏），出错就停用效果并收起气泡。
        try {
            val bubble = mService?.keyBubble ?: return
            if (!com.yuyan.imemodule.keyboard.effect.KeyEffects.safeConfig().bubble) { bubble.hide(); return }
            val label = bubbleLabel(key)
            if (label.isNullOrEmpty()) { bubble.hide(); return }
            bubble.show(this, label, key.mLeft, key.mTop, key.mRight, key.mBottom)
        } catch (e: Throwable) {
            com.yuyan.imemodule.keyboard.effect.KeyEffects.disable("BaseKeyboardView.showBalloonText", e)
            hideBubble()
        }
    }

    /** 花花：收起按键气泡，永不抛异常。 */
    private fun hideBubble() {
        try { mService?.keyBubble?.hide() } catch (_: Throwable) {}
    }

    /** 花花：按键视觉效果回调出错时停用效果，不影响按键逻辑。 */
    private inline fun safeVisual(block: () -> Unit) {
        try { block() } catch (e: Throwable) {
            com.yuyan.imemodule.keyboard.effect.KeyEffects.disable("BaseKeyboardView.visual", e)
        }
    }

    fun getKeyIndices(x: Int, y: Int): SoftKey? {
        return mSoftKeyboard?.mapToKey(x, y)
    }

    open fun setSoftKeyboard(softSkb: SoftKeyboard) {
        mSoftKeyboard = softSkb
    }

    fun getSoftKeyboard(): SoftKeyboard {
        return mSoftKeyboard!!
    }

    companion object {
        private const val MSG_SHOW_PREVIEW = 1
        private const val MSG_REPEAT = 3
        private const val MSG_LONGPRESS = 4
        private const val REPEAT_INTERVAL = 50L // ~20 keys per second
        private const val REPEAT_START_DELAY = 400L
    }
}
