package com.yuyan.imemodule.keyboard

import com.yuyan.imemodule.utils.clipboardManager
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.scale
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.get
import androidx.core.view.postDelayed
import com.yuyan.imemodule.R
import com.yuyan.imemodule.application.CustomConstant
import com.yuyan.imemodule.callback.CandidateViewListener
import com.yuyan.imemodule.data.emojicon.YuyanEmojiCompat
import com.yuyan.inputmethod.RimeEngine
import com.yuyan.inputmethod.UserNextWord
import com.yuyan.imemodule.callback.IKeyboardView
import com.yuyan.imemodule.callback.IResponseKeyEvent
import com.yuyan.imemodule.data.emojicon.EmojiconData.SymbolPreset
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.database.DataBaseKT
import com.yuyan.imemodule.database.entry.Phrase
import com.yuyan.imemodule.entity.StringQueue
import com.yuyan.imemodule.entity.keyboard.SoftKey
import com.yuyan.imemodule.keyboard.container.CandidatesContainer
import com.yuyan.imemodule.keyboard.container.ClipBoardContainer
import com.yuyan.imemodule.keyboard.container.SymbolContainer
import com.yuyan.imemodule.keyboard.container.T9TextContainer
import com.yuyan.imemodule.manager.InputModeSwitcher
import com.yuyan.imemodule.prefs.AppPrefs.Companion.getInstance
import com.yuyan.imemodule.prefs.behavior.KeyboardOneHandedMod
import com.yuyan.imemodule.prefs.behavior.PopupMenuMode
import com.yuyan.imemodule.prefs.behavior.SkbMenuMode
import com.yuyan.imemodule.service.DictDecoder
import com.yuyan.imemodule.service.ImeService
import com.yuyan.imemodule.service.InputDispatcher
import com.yuyan.imemodule.singleton.EnvironmentSingleton
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.utils.InputMethodUtil
import com.yuyan.imemodule.utils.KeyboardLoaderUtil
import com.yuyan.imemodule.utils.StringUtils
import com.yuyan.imemodule.view.CandidatesBar
import com.yuyan.imemodule.view.EditPhrasesView
import com.yuyan.imemodule.view.FullDisplayKeyboardBar
import com.yuyan.imemodule.view.popup.PopupComponent
import com.yuyan.imemodule.view.preference.ManagedPreference
import com.yuyan.inputmethod.CustomEngine
import com.yuyan.inputmethod.core.CandidateListItem
import splitties.views.bottomPadding
import splitties.views.rightPadding
import kotlin.math.absoluteValue

/**
 * 输入法主界面。
 * 包含拼音显示、候选词栏、键盘界面等。
 */
@SuppressLint("ViewConstructor")
class InputView(context: Context, private val service: ImeService) : IKeyboardView(context), IResponseKeyEvent {
    private val appPrefs = getInstance()
    private val clipboardItemTimeout = appPrefs.clipboard.clipboardItemTimeout.getValue()
    private var chinesePrediction = true
    var isAddPhrases = false
    private val mChoiceNotifier = ChoiceNotifier()
    var mSkbRoot: RelativeLayout
    private val mFullHandwritingView: FullHandwritingView
    var mSkbCandidatesBarView: CandidatesBar
    private var mHoderLayoutLeft: LinearLayout
    private var mHoderLayoutRight: LinearLayout
    private lateinit var mOnehandHoderLayout: LinearLayout
    var mAddPhrasesLayout: EditPhrasesView
    private var mLlKeyboardBottomHolder: LinearLayout
    private var mInputKeyboardContainer: RelativeLayout
    private lateinit var mRightPaddingKey: ManagedPreference.PInt
    private lateinit var mBottomPaddingKey: ManagedPreference.PInt
    private var mFullDisplayKeyboardBar: FullDisplayKeyboardBar? = null
    var hasSelection = false
    var hasSelectionAll = false
    // 记录删除内容
    private val textBeforeCursors = StringQueue(50)

    init {
        initNavbarBackground(service)
        mSkbRoot = LayoutInflater.from(context).inflate(R.layout.sdk_skb_container, this, false) as RelativeLayout
        addView(mSkbRoot)
        // 全屏手写层：盖住整个输入法窗口（含键盘手写区），笔迹不会在键盘顶部被截断；
        // 落在按键/候选栏上的触摸会放行给下面的键盘。只在开启“全屏手写”且处于手写键盘时生效
        mFullHandwritingView = FullHandwritingView(context, this)
        addView(mFullHandwritingView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        mSkbCandidatesBarView = mSkbRoot.findViewById(R.id.candidates_bar)
        mHoderLayoutLeft = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_left)
        mHoderLayoutRight = mSkbRoot.findViewById(R.id.ll_skb_holder_layout_right)
        mInputKeyboardContainer = mSkbRoot.findViewById(R.id.ll_input_keyboard_container)
        mAddPhrasesLayout = EditPhrasesView(context)
        mLlKeyboardBottomHolder = mSkbRoot.findViewById(R.id.iv_keyboard_holder)
        KeyboardManager.instance.setData(mSkbRoot.findViewById(R.id.skb_input_keyboard_view), this)
        PopupComponent.get().root.let { root ->
            root.parent?.let { (it as ViewGroup).removeView(root) }
            addView(root, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                addRule(ALIGN_BOTTOM, mSkbRoot.id)
                addRule(ALIGN_LEFT, mSkbRoot.id)
            })
        }
        initView(context)
    }

    override fun onCandidateChanged() {
        updateCandidateBar()
        (KeyboardManager.instance.currentContainer as? CandidatesContainer)?.showCandidatesView()
    }

    @SuppressLint("ClickableViewAccessibility")
    fun initView(context: Context) {
        if (isAddPhrases) {
            if (mAddPhrasesLayout.parent == null) {
                addView(mAddPhrasesLayout, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                    addRule(ABOVE, mSkbRoot.id)
                    addRule(ALIGN_LEFT, mSkbRoot.id)
                })
                mAddPhrasesLayout.handleAddPhrasesView()
            }
        } else {
            removeView(mAddPhrasesLayout)
        }
        mSkbCandidatesBarView.initialize(mChoiceNotifier)
        val env = EnvironmentSingleton.instance
        val keyboardSetting = appPrefs.keyboardSetting
        val oneHandedModSwitch = keyboardSetting.oneHandedModSwitch.getValue()
        val oneHandedMod = keyboardSetting.oneHandedMod.getValue()
        if (::mOnehandHoderLayout.isInitialized) mOnehandHoderLayout.visibility = GONE
        if (oneHandedModSwitch) {
            mOnehandHoderLayout = if (oneHandedMod == KeyboardOneHandedMod.LEFT) mHoderLayoutRight else mHoderLayoutLeft
            mOnehandHoderLayout.apply {
                visibility = VISIBLE
                get(0).setOnClickListener { onClick(it) }
                get(1).setOnClickListener { onClick(it) }
                (get(1) as ImageButton).setImageResource(
                    if (oneHandedMod == KeyboardOneHandedMod.LEFT) R.drawable.ic_menu_one_hand_right else R.drawable.ic_menu_one_hand
                )
                layoutParams = layoutParams.apply {
                    width = env.holderWidth
                    height = env.skbHeight
                }
            }
        }
        mLlKeyboardBottomHolder.removeAllViews()
        mLlKeyboardBottomHolder.layoutParams.width = env.skbWidth
        mInputKeyboardContainer.layoutParams.width = env.inputAreaWidth
        if (env.keyboardModeFloat) {
            val isLand = env.isLandscape
            val internal = appPrefs.internal
            mBottomPaddingKey = if (isLand) internal.keyboardBottomPaddingLandscapeFloat else internal.keyboardBottomPaddingFloat
            mRightPaddingKey = if (isLand) internal.keyboardRightPaddingLandscapeFloat else internal.keyboardRightPaddingFloat

            bottomPadding = mBottomPaddingKey.getValue()
            rightPadding = mRightPaddingKey.getValue()
            mSkbRoot.bottomPadding = 0
            mSkbRoot.rightPadding = 0

            mLlKeyboardBottomHolder.minimumHeight = env.heightForKeyboardMove
            val mIvKeyboardMove = ImageView(context).apply {
                setImageResource(R.drawable.ic_horizontal_line)
                isClickable = true
                isEnabled = true
            }
            mLlKeyboardBottomHolder.addView(mIvKeyboardMove)
            mIvKeyboardMove.setOnTouchListener { _, event -> onMoveKeyboardEvent(event) }
        } else {
            val fullDisplayEnable = appPrefs.internal.fullDisplayKeyboardEnable.getValue()
            if (fullDisplayEnable && !env.isLandscape) {
                mFullDisplayKeyboardBar = FullDisplayKeyboardBar(context, this)
                mLlKeyboardBottomHolder.addView(mFullDisplayKeyboardBar)
                mLlKeyboardBottomHolder.minimumHeight = env.heightForFullDisplayBar + env.systemNavbarWindowsBottom
            } else {
                mLlKeyboardBottomHolder.minimumHeight = env.systemNavbarWindowsBottom
            }
            bottomPadding = 0
            rightPadding = 0
            mBottomPaddingKey = appPrefs.internal.keyboardBottomPadding
            mRightPaddingKey = appPrefs.internal.keyboardRightPadding
            mSkbRoot.bottomPadding = mBottomPaddingKey.getValue()
            mSkbRoot.rightPadding = mRightPaddingKey.getValue()
        }
        KeyboardManager.instance.switchKeyboard()
        updateTheme()
    }

    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var rightPaddingValue = 0
    private var bottomPaddingValue = 0
    private var mSkbRootHeight = 0
    private var mSkbRootWidth = 0

    private fun onMoveKeyboardEvent(event: MotionEvent?): Boolean {
        when (event?.action) {
            MotionEvent.ACTION_DOWN -> {
                bottomPaddingValue = mBottomPaddingKey.getValue()
                rightPaddingValue = mRightPaddingKey.getValue()
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                mSkbRootHeight = mSkbRoot.height
                mSkbRootWidth = mSkbRoot.width
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - initialTouchX
                val dy = event.rawY - initialTouchY
                val env = EnvironmentSingleton.instance

                if (dx.absoluteValue > 10) {
                    rightPaddingValue = (rightPaddingValue - dx.toInt()).coerceIn(0, this.width - mSkbRootWidth)
                    initialTouchX = event.rawX
                    if (env.keyboardModeFloat) rightPadding = rightPaddingValue else mSkbRoot.rightPadding = rightPaddingValue
                }
                if (dy.absoluteValue > 10) {
                    bottomPaddingValue = (bottomPaddingValue - dy.toInt()).coerceIn(0, this.height - mSkbRootHeight)
                    initialTouchY = event.rawY
                    if (env.keyboardModeFloat) bottomPadding = bottomPaddingValue else mSkbRoot.bottomPadding = bottomPaddingValue
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mRightPaddingKey.setValue(rightPaddingValue)
                mBottomPaddingKey.setValue(bottomPaddingValue)
            }
        }
        return false
    }

    override fun updateTheme() {
        setBackgroundResource(android.R.color.transparent)
        val activeTheme = ThemeManager.activeTheme
        val keyTextColor = activeTheme.keyTextColor
        val env = EnvironmentSingleton.instance
        val background = activeTheme.backgroundDrawable(ThemeManager.prefs.keyBorder.getValue())
        if (background is BitmapDrawable) {
            val scaledBitmap = background.bitmap.scale(env.skbWidth, env.inputAreaHeight)
            mSkbRoot.background = scaledBitmap.toDrawable(context.resources).apply {
                colorFilter = background.colorFilter
            }
        } else {
            mSkbRoot.background = background
        }
        mSkbCandidatesBarView.updateTheme(keyTextColor)
        if (::mOnehandHoderLayout.isInitialized) {
            (mOnehandHoderLayout[0] as ImageButton).drawable?.setTint(keyTextColor)
            (mOnehandHoderLayout[1] as ImageButton).drawable?.setTint(keyTextColor)
        }
        mFullDisplayKeyboardBar?.updateTheme(keyTextColor)
        mAddPhrasesLayout.updateTheme(activeTheme)
    }

    private fun onClick(view: View) {
        val keyboardSetting = appPrefs.keyboardSetting
        if (view.id == R.id.ib_holder_one_hand_none) {
            keyboardSetting.oneHandedModSwitch.setValue(!keyboardSetting.oneHandedModSwitch.getValue())
        } else {
            val currentMod = keyboardSetting.oneHandedMod.getValue()
            keyboardSetting.oneHandedMod.setValue(if (currentMod == KeyboardOneHandedMod.LEFT) KeyboardOneHandedMod.RIGHT else KeyboardOneHandedMod.LEFT)
        }
        EnvironmentSingleton.instance.initData()
        KeyboardLoaderUtil.instance.clearKeyboardMap()
        KeyboardManager.instance.apply {
            clearKeyboard()
            switchKeyboard()
        }
    }

    override fun responseLongKeyEvent(result: Pair<PopupMenuMode, String>) {
        val (mode, value) = result
        if (mode != PopupMenuMode.None && !DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty) {
            if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
        }

        when (mode) {
            PopupMenuMode.Text -> if (SymbolPreset.containsKey(value)) commitPairSymbol(value) else commitText(value)
            PopupMenuMode.SwitchIME -> InputMethodUtil.showPicker()
            PopupMenuMode.EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            PopupMenuMode.EnglishCell -> {
                val pref = appPrefs.input.abcSearchEnglishCell
                pref.setValue(!pref.getValue())
                KeyboardManager.instance.switchKeyboard()
            }
            PopupMenuMode.Clear -> {
                if (isAddPhrases) mAddPhrasesLayout.clearPhrasesContent()
                else service.getTextBeforeCursor(1000).takeIf { it.isNotEmpty() }?.let {
                    textBeforeCursors.push(it)
                    service.deleteSurroundingText(1000)
                }
            }
            PopupMenuMode.Revertl -> textBeforeCursors.popInReverseOrder()?.takeIf { it.isNotEmpty() }?.let { commitText(it) }
            PopupMenuMode.Enter -> commitText("\n")
            else -> {}
        }
        if (mode == PopupMenuMode.Clear) resetToIdleState()
    }

    override fun responseHandwritingResultEvent(words: Array<CandidateListItem>) {
        DictDecoder.cacheCandidates(words)
    }

    override fun responseKeyEvent(sKey: SoftKey) {
        val keyCode = sKey.code
        if(sKey.isUserDefKey)processUserDefKey(keyCode, sKey.keyLabel)
        else if(sKey.isUniStrKey){
            if (!DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty) chooseAndUpdate()
            sKey.label.takeIf(String::isNotEmpty)?.let {
                if (SymbolPreset.containsKey(it)) commitPairSymbol(it) else commitText(it)
            }
        } else {
            val metaState = when(InputDispatcher.getCurrentRimeSchema()) {
                CustomConstant.SCHEMA_ZH_T9, CustomConstant.SCHEMA_ZH_STROKE, CustomConstant.SCHEMA_ZH_DOUBLE_LX17 -> KeyEvent.META_CAPS_LOCK_ON
                else -> InputModeSwitcher.mToggleStates.modifiers
            }
            processKeyUp(KeyEvent(0, 0, KeyEvent.ACTION_UP, keyCode, 0, metaState, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
        }
    }


    override fun processKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode >= KeyEvent.KEYCODE_A && keyCode <= KeyEvent.KEYCODE_Z) return true
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DEL -> return true
            KeyEvent.KEYCODE_BACK -> return service.isInputViewShown
        }
        return false
    }

    override fun processKeyUp(event: KeyEvent): Boolean {
        if(event.isSystem) return processSystemKeys(event)
        else if(isFunctionKey(event.keyCode)){
            processFunctionKey(event)
            return true
        }
        // 密码框：不走引擎、不走花漫字体，先读 Shift 再重置
        if (isPasswordField() && event.keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
            val m = InputModeSwitcher.mToggleStates.modifiers
            val upper = m == KeyEvent.META_SHIFT_ON || m == KeyEvent.META_CAPS_LOCK_ON
            val ch = ('a' + (event.keyCode - KeyEvent.KEYCODE_A)).toString()
            textBeforeCursors.clear()
            service.commitRaw(if (upper) ch.uppercase() else ch)
            InputModeSwitcher.resetCharCase()   // 上屏后再重置单次 Shift
            return true
        }
        // 中文9键的 @ 键改为繁/简切换键（不限制 isChineseT9，确保能触发）
        if (event.keyCode == KeyEvent.KEYCODE_AT) {
            try {
                val chineseFanTi = appPrefs.input.chineseFanTi.getValue()
                appPrefs.input.chineseFanTi.setValue(!chineseFanTi)
                InputDispatcher.nativeUpdateImeOption()
                KeyboardManager.instance.switchKeyboard()
            } catch (_: Exception) { }
            return true
        }
        val m0 = InputModeSwitcher.mToggleStates.modifiers
        RimeEngine.nextKeyUpper = m0 == KeyEvent.META_SHIFT_ON || m0 == KeyEvent.META_CAPS_LOCK_ON
        InputModeSwitcher.resetCharCase()
        val englishCellDisable = InputModeSwitcher.isEnglish && !appPrefs.input.abcSearchEnglishCell.getValue()
        return when {
            englishCellDisable -> processEnglishKey(event)
            InputModeSwitcher.isEnglish || InputModeSwitcher.isChinese -> processInput(event)
            else -> processEnglishKey(event)
        }
//        return if(appPrefs.input.abcSearchEnglishCell.getValue() || InputModeSwitcherManager.isChinese)processInput(event) else processEnglishKey(event)
    }

    private fun isPasswordField(): Boolean {
        val info = YuyanEmojiCompat.mEditorInfo ?: return false
        val cls = info.inputType and EditorInfo.TYPE_MASK_CLASS
        val v = info.inputType and EditorInfo.TYPE_MASK_VARIATION
        return (cls == EditorInfo.TYPE_CLASS_TEXT && (v == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
            || v == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            || v == EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
            (cls == EditorInfo.TYPE_CLASS_NUMBER && v == EditorInfo.TYPE_NUMBER_VARIATION_PASSWORD)
    }

    private fun processEnglishKey(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()
        var result = true
        when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                sendKeyEvent(keyCode)
            }
            keyCode in (KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) -> {
                textBeforeCursors.clear()
                commitText(label)
            }
            keyCode != 0 -> sendKeyEvent(keyCode)
            label.isNotEmpty() -> if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
            else -> result = false
        }
        return result
    }

    // 系统按键只处理返回键，当点击返回键且软键盘显示时，隐藏键盘并消费事件
    private fun processSystemKeys(event: KeyEvent): Boolean {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (service.isInputViewShown) { requestHideSelf(); true } else false
            else -> false
        }
    }

    fun isFunctionKey(keyCode: Int): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_CLEAR, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_LANGUAGE_SWITCH, KeyEvent.KEYCODE_SYM,
            KeyEvent.KEYCODE_PICTSYMBOLS, KeyEvent.KEYCODE_NUM -> return true
        }
        return false
    }

    private fun processFunctionKey(event: KeyEvent) {
        when (val keyCode = event.keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> {
                if (DictDecoder.isCandidatesEmpty || DictDecoder.isAssociate) {
                    sendKeyEvent(keyCode)
                    resetToIdleState()
                }
                else chooseAndUpdate()
            }
            KeyEvent.KEYCODE_CLEAR -> resetToIdleState()
            KeyEvent.KEYCODE_ENTER -> {
                if (DictDecoder.isCandidatesEmpty || DictDecoder.isAssociate) sendKeyEvent(keyCode)
                else commitDecInfoText(DictDecoder.composingStrForCommit)
                resetToIdleState()
            }
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> {
                if(InputModeSwitcher.isChinese && !InputDispatcher.isFinish) processInput(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_APOSTROPHE, 0, 0, 0, 0, KeyEvent.FLAG_SOFT_KEYBOARD))
                else InputModeSwitcher.processShiftKey(keyCode)
            }
        }
    }


    private fun processUserDefKey(keyCode: Int, label: String) {
        when {
            keyCode == InputModeSwitcher.USER_KEYCODE_CURSOR_DIRECTION -> {
                resetToIdleState()
                return
            }
            !DictDecoder.isAssociate && !DictDecoder.isCandidatesEmpty -> {
                if (InputModeSwitcher.isChinese || InputModeSwitcher.isEnglish) chooseAndUpdate()
            }
        }

        when (keyCode) {
            InputModeSwitcher.USER_KEYCODE_SYMBOL -> {
                KeyboardManager.instance.switchKeyboard(KeyboardManager.KeyboardType.SYMBOL)
                (KeyboardManager.instance.currentContainer as? SymbolContainer)?.setSymbolsView()
            }
            InputModeSwitcher.USER_KEYCODE_EMOJI -> onSettingsMenuClick(SkbMenuMode.Emojicon)
            InputModeSwitcher.USER_KEYCODE_HW_FULL_SCREEN -> {
                // 全屏/半屏手写切换：改开关、换布局（半屏原样式，全屏两行按键），清掉残留笔迹
                val pref = appPrefs.handwriting.handWritingFullScreen
                pref.setValue(!pref.getValue())
                mFullHandwritingView.clearInk()
                KeyboardLoaderUtil.instance.clearKeyboardMap() // 全屏/半屏两套布局，切换时重建
                KeyboardManager.instance.currentContainer?.updateSkbLayout()
                requestLayout() // 触发重新计算可触摸区域（全屏时整窗可写，半屏时只有键盘）
            }
            in InputModeSwitcher.USER_KEYCODE_RETURN..InputModeSwitcher.USER_KEYCODE_LANG -> InputModeSwitcher.switchModeForUserKey(keyCode)
            in InputModeSwitcher.USER_KEYCODE_PASTE..InputModeSwitcher.USER_KEYCODE_CUT -> commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            InputModeSwitcher.USER_KEYCODE_MOVE_START -> service.setSelection(0, if (hasSelection) selEnd else 0)
            InputModeSwitcher.USER_KEYCODE_MOVE_END -> {
                if (hasSelection) {
                    val start = selStart
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    postDelayed(100) { service.setSelection(start, selEnd) }
                } else {
                    commitTextEditMenu(KeyPreset.textEditMenuPreset[InputModeSwitcher.USER_KEYCODE_SELECT_ALL])
                    postDelayed(100) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)}
                }
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_MODE -> {
                hasSelection = !hasSelection
                if (!hasSelection) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
            }
            InputModeSwitcher.USER_KEYCODE_SELECT_ALL -> {
                hasSelectionAll = !hasSelectionAll
                if (!hasSelectionAll) service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                else commitTextEditMenu(KeyPreset.textEditMenuPreset[keyCode])
            }
            else -> {
                if(label.isNotEmpty()){
                    if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                }
            }
        }
    }

    private fun processInput(event: KeyEvent): Boolean {
        val keyCode = event.keyCode
        val keyChar = event.unicodeChar
        val label = keyChar.toChar().toString()

        return when {
            keyCode == KeyEvent.KEYCODE_DEL -> {
                if (DictDecoder.isCandidatesEmpty || DictDecoder.isAssociate) {
                    service.getTextBeforeCursor(1).takeIf { it.isNotEmpty() }?.let { textBeforeCursors.push(it) }
                    sendKeyEvent(keyCode)
                } else {
                    InputDispatcher.deleteAction()
                    updateCandidate()
                }
                true
            }
            (Character.isLetterOrDigit(keyChar) && keyCode != KeyEvent.KEYCODE_0) || keyCode == KeyEvent.KEYCODE_APOSTROPHE || keyCode == KeyEvent.KEYCODE_SEMICOLON -> {
                textBeforeCursors.clear()
                InputDispatcher.inputKeyCode(event)
                updateCandidate()
                true
            }
            keyCode != 0 -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                sendKeyEvent(keyCode)
                resetToIdleState()
                true
            }
            label.isNotEmpty() -> {
                if (!DictDecoder.isCandidatesEmpty && !DictDecoder.isAssociate) chooseAndUpdate()
                if (SymbolPreset.containsKey(label)) commitPairSymbol(label) else commitText(label)
                true
            }
            else -> false
        }
    }

    fun resetToIdleState() {
        resetCandidateWindow()
        if (hasSelectionAll) hasSelectionAll = false
    }

    fun chooseAndUpdate(candId: Int = mSkbCandidatesBarView.getActiveCandNo()) {
        if (InputModeSwitcher.isChineseHandWriting) {
            // 手写选字后清掉旧笔画，下一笔重新开始一个字
            HwStrokes.points.clear()
            HwStrokes.lastUpTime = 0L
            mFullHandwritingView.clearInk()
        }
        val candidate = DictDecoder.getCandidate(candId)
        if (candidate?.comment == "📋") {
            commitDecInfoText(candidate.text)
        } else {
            val choice = DictDecoder.chooseDecodingCandidate(candId)
            if (DictDecoder.isCandidatesEmpty || DictDecoder.isAssociate) {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                commitDecInfoText(choice)
            } else {
                if (!DictDecoder.isCandidatesEmpty) {
                    (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
                    if (InputModeSwitcher.isEnglish) setComposingText(DictDecoder.composingStrForCommit)
                } else {
                    resetToIdleState()
                }
            }
        }
    }

    private fun updateCandidate() {
        DictDecoder.updateDecodingCandidate()
        if (!DictDecoder.isCandidatesEmpty) {
            (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
        } else {
            resetToIdleState()
        }
        if (InputModeSwitcher.isEnglish) setComposingText(DictDecoder.composingStrForCommit)
    }

    fun updateCandidateBar() = mSkbCandidatesBarView.scheduleShowCandidates()

    private fun resetCandidateWindow() {
        DictDecoder.reset()
        InputDispatcher.reset()
        (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
    }

    inner class ChoiceNotifier internal constructor() : CandidateViewListener {
        override fun onClickChoice(choiceId: Int) {
            DevicesUtils.tryPlayKeyDown()
            DevicesUtils.tryVibrate(KeyboardManager.instance.currentContainer)
            chooseAndUpdate(choiceId)
        }

        override fun onClickMore(level: Int) {
            if (level == 0) {
                onSettingsMenuClick(SkbMenuMode.CandidatesMore)
            } else {
                KeyboardManager.instance.switchKeyboard()
                (KeyboardManager.instance.currentContainer as? T9TextContainer)?.updateSymbolListView()
            }
        }

        override fun onClickMenu(skbMenuMode: SkbMenuMode) = onSettingsMenuClick(skbMenuMode)

        override fun onClickClearCandidate() {
            resetToIdleState()
            KeyboardManager.instance.switchKeyboard()
        }

        override fun onClickClearClipBoard() {
            DataBaseKT.instance.clipboardDao().deleteAll()  // 花花：置顶的也一起清
            // 花花：连系统剪贴板一起清空（ROM 自带的剪贴板历史不归输入法管，清不到）
            runCatching {
                val cm = context.clipboardManager
                if (android.os.Build.VERSION.SDK_INT >= 28) cm.clearPrimaryClip()
                else cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""))
            }
            getInstance().internal.clipboardUpdateContent.setValue("")
            (KeyboardManager.instance.currentContainer as? ClipBoardContainer)?.showClipBoardView(SkbMenuMode.ClipBoard)
        }
    }

    fun onSettingsMenuClick(skbMenuMode: SkbMenuMode, extra: Phrase? = null) {
        if (skbMenuMode == SkbMenuMode.AddPhrases) {
            isAddPhrases = true
            KeyboardManager.instance.switchKeyboard(InputModeSwitcher.skbImeLayout)
            initView(context)
            if (extra != null) {
                DataBaseKT.instance.phraseDao().deleteByContent(extra.content)
                mAddPhrasesLayout.setExtraData(extra)
            } else {
                mAddPhrasesLayout.clearPhrasesContent()
            }
        } else {
            onSettingsMenuClick(this, skbMenuMode)
        }
        mSkbCandidatesBarView.initMenuView()
    }

    fun selectPrefix(position: Int) {
        DevicesUtils.tryPlayKeyDown()
        DevicesUtils.tryVibrate(this)
        InputDispatcher.selectPrefix(position)
        updateCandidate()
    }

    override fun showSymbols(symbols: Array<String>) {
        val list = symbols.map { CandidateListItem("📋", it) }.toTypedArray()
        DictDecoder.cacheCandidates(list, true)
    }

    fun requestHideSelf() = service.requestHideSelf(0)

    private fun sendKeyEvent(keyCode: Int) {
        if (isAddPhrases) {
            mAddPhrasesLayout.sendKeyEvent(keyCode)
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                isAddPhrases = false
                initView(context)
                onSettingsMenuClick(SkbMenuMode.Phrases)
            }
        } else {
            when (keyCode) {
                KeyEvent.KEYCODE_ENTER -> service.sendEnterKeyEvent()
                in KeyEvent.KEYCODE_DPAD_UP..KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    service.sendCombinationKeyEvents(keyCode, shift = hasSelection)
                    if (hasSelectionAll) hasSelectionAll = false
                }
                else -> service.sendCombinationKeyEvents(keyCode)
            }
        }
    }

    private fun setComposingText(text: CharSequence) {
        if (!isAddPhrases) service.setComposingText(text)
    }

    private fun commitText(text: String) {
        if (isAddPhrases) mAddPhrasesLayout.commitText(text)
        else service.commitText(StringUtils.converted2FlowerTypeface(text))
    }

    private fun commitPairSymbol(text: String) {
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(text)
        } else {
            if (appPrefs.input.symbolPairInput.getValue()) {
                service.commitText(text + SymbolPreset[text]!!)
                postDelayed(300) { service.sendCombinationKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT) }
            } else {
                service.commitText(text)
            }
        }
    }

    private fun commitTextEditMenu(id: Int?) {
        id?.let { service.commitTextEditMenu(it) }
    }

    fun performEditorAction(editorAction: Int) = service.performEditorAction(editorAction)

    private fun commitDecInfoText(resultText: String?) {
        resultText ?: return
        if (isAddPhrases) {
            mAddPhrasesLayout.commitText(resultText)
        } else {
            service.commitText(StringUtils.converted2FlowerTypeface(resultText))
            if (InputModeSwitcher.isEnglish){
                service.finishComposingText()
                if(appPrefs.input.abcSpaceAuto.getValue()) service.commitText(" ")
                resetToIdleState()
            }
        }
    }

    private fun initNavbarBackground(service: ImeService) {
        service.window.window?.also { win ->
            WindowCompat.setDecorFitsSystemWindows(win, false)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                @Suppress("DEPRECATION")
                win.navigationBarColor = Color.TRANSPARENT
            } else {
                win.insetsController?.apply {
                    hide(WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) win.isNavigationBarContrastEnforced = false
        }

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val env = EnvironmentSingleton.instance
            env.systemNavbarWindowsBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val fullDisplayEnable = appPrefs.internal.fullDisplayKeyboardEnable.getValue()
            mLlKeyboardBottomHolder.minimumHeight = when {
                env.keyboardModeFloat -> 0
                fullDisplayEnable -> env.heightForFullDisplayBar + env.systemNavbarWindowsBottom
                else -> env.systemNavbarWindowsBottom
            }
            insets
        }
    }

    override fun onStartInputView(editorInfo: EditorInfo, restarting: Boolean) {
        if (!restarting) {
            UserNextWord.resetContext()
            resetToIdleState()
            val clipboard = appPrefs.clipboard
            if (clipboard.clipboardSuggestion.getValue()) {
                val internal = appPrefs.internal
                val lastTime = internal.clipboardUpdateTime.getValue()
                if (System.currentTimeMillis() - lastTime <= clipboardItemTimeout * 1000) {
                    val content = internal.clipboardUpdateContent.getValue()
                    if (content.isNotBlank()) {
                        showSymbols(arrayOf(content))
                        internal.clipboardUpdateTime.setValue(0L)
                    }
                }
            }
        }
    }

    override fun onWindowShown() {
        chinesePrediction = appPrefs.input.chinesePrediction.getValue()
        // 在设置页切换了全屏手写：重建手写键盘布局
        if (KeyboardLoaderUtil.instance.refreshHandwritingIfChanged()) {
            KeyboardLoaderUtil.instance.clearKeyboardMap()
            KeyboardManager.instance.clearKeyboard()
            KeyboardManager.instance.switchKeyboard()
        }
    }

    override fun onWindowHidden() {
        if (isAddPhrases) {
            isAddPhrases = false
            mAddPhrasesLayout.addPhrasesHandle()
            initView(context)
        }
        KeyboardManager.instance.switchKeyboard()
        resetToIdleState()
        mFullHandwritingView.clearInk()
        UserNextWord.save()
    }

    override fun updatePosition(anchor: FloatArray) {}

    /** 是否处于全屏手写：开关打开、当前是中文手写键盘、非悬浮键盘、不在编辑常用语 */
    fun isFullHandwriting(): Boolean =
        appPrefs.handwriting.handWritingFullScreen.getValue() && InputModeSwitcher.isChineseHandWriting &&
            !EnvironmentSingleton.instance.keyboardModeFloat && !isAddPhrases

    override fun getKeyboardRect(): IntArray {
        val location = intArrayOf(0, 0).also {if(isAddPhrases) mAddPhrasesLayout.getLocationInWindow(it) else mSkbRoot.getLocationInWindow(it) }
        val height = EnvironmentSingleton.instance.systemNavbarWindowsBottom + EnvironmentSingleton.instance.heightForFullDisplayBar + EnvironmentSingleton.instance.skbHeight + EnvironmentSingleton.instance.heightForCandidatesArea
        return intArrayOf(location[0], location[1], EnvironmentSingleton.instance.inputAreaWidth, height)
    }

    override fun setConfiguration(newConfig: Configuration) {}

    private var selStart = 0
    private var selEnd = 0
    private var oldCandidatesEnd = 0

    override fun onUpdateSelection(oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int, candidatesEnd: Int) {
        selStart = newSelStart
        selEnd = newSelEnd
        if (InputModeSwitcher.isEnglish ) {
            if (oldCandidatesEnd == candidatesEnd) {
                service.finishComposingText()
                resetToIdleState()
            }
            oldCandidatesEnd = candidatesEnd
            return
        }
        if (oldSelStart == newSelStart) return
        when {
            InputModeSwitcher.isNumberSkb -> {
                val textBeforeCursor = service.getTextBeforeCursor(500)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else CustomEngine.parseExpressionAtEnd(textBeforeCursor).let { CustomEngine.expressionCalculator(textBeforeCursor, it).let(::showSymbols) }
            }
            chinesePrediction && InputModeSwitcher.isChinese-> {
                val textBeforeCursor = service.getTextBeforeCursor(10)
                if (textBeforeCursor.isBlank()) resetCandidateWindow()
                else {
                    InputDispatcher.predictAssociationWords(textBeforeCursor)
                    updateCandidate()
                }
            }
            else -> resetCandidateWindow()
        }
    }
}