package com.yuyan.imemodule.keyboard.container

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager.activeTheme
import com.yuyan.imemodule.keyboard.InputView
import com.yuyan.imemodule.keyboard.KeyboardManager
import com.yuyan.imemodule.utils.AppUtil
import com.yuyan.imemodule.utils.DevicesUtils
import com.yuyan.imemodule.voice.AsrModelKind
import com.yuyan.imemodule.voice.AsrNative
import com.yuyan.imemodule.voice.VoiceModelManager
import com.yuyan.imemodule.voice.VoicePermissionActivity
import com.yuyan.imemodule.voice.VoiceRecognizer
import splitties.dimensions.dp

/**
 * 花花：语音输入面板（替换键盘区域显示）。
 *
 * 点一下麦克风开始说，再点一下结束并上屏；也可以按住说话、松开上屏。
 * 说话时上方实时显示识别中的文字。左下“键盘”返回打字，右下“退格”删字。
 * Dolphin 方言模型不是流式的：停顿约 1 秒或点结束后才识别这一句，期间显示“识别中…”。
 * 导入了多个模型时，右上角的模型名可以点一下切换。
 */
@SuppressLint("ViewConstructor", "ClickableViewAccessibility")
class VoiceContainer(context: Context, inputView: InputView) : BaseContainer(context, inputView) {

    private val statusView = TextView(context)
    private val modelView = TextView(context)
    private var keepStatus = false
    private val micView = ImageView(context)
    private val micBg = GradientDrawable().apply { shape = GradientDrawable.OVAL }
    private var panelActive = false
    private var pressStartedSession = false
    private var pressDownAt = 0L

    private val listener = object : VoiceRecognizer.Listener {
        override fun onState(state: VoiceRecognizer.State, message: String?) {
            when (state) {
                VoiceRecognizer.State.LOADING -> setStatus("正在加载语音模型…", false)
                VoiceRecognizer.State.LISTENING -> setStatus(
                    if (activeKind() == AsrModelKind.CTC_DOLPHIN) "请说话…（停顿后自动出字，再点一下结束）"
                    else "请说话…（再点一下结束）", true)
                VoiceRecognizer.State.DECODING -> setStatus(message ?: "识别中…", VoiceRecognizer.isRecording)
                VoiceRecognizer.State.IDLE -> if (!VoiceRecognizer.isRecording) {
                    when {
                        message != null -> setStatus(message, false)
                        keepStatus -> { keepStatus = false; setMicColor(false) }
                        else -> setIdle()
                    }
                }
                VoiceRecognizer.State.ERROR -> setStatus(message ?: "语音识别出错", false)
            }
        }

        override fun onPartial(text: String) {
            if (text.isNotEmpty()) statusView.text = text
        }

        override fun onFinal(text: String) {
            if (text.isNotEmpty()) inputView.commitVoiceText(text)
            else { statusView.text = "没有听清，请再说一次"; keepStatus = true }
        }
    }

    init {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        }
        statusView.apply {
            gravity = Gravity.CENTER
            setTextColor(activeTheme.keyTextColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setPadding(dp(16), dp(8), dp(16), dp(8))
            maxLines = 4
        }
        modelView.apply {
            gravity = Gravity.END
            setTextColor(activeTheme.keyTextColor)
            alpha = 0.7f
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(12), dp(6), dp(12), dp(2))
            setOnClickListener { switchModel() }
        }
        root.addView(modelView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(statusView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val back = sideButton(R.drawable.ic_menu_keyboard, "返回键盘") {
            VoiceRecognizer.cancel()
            KeyboardManager.instance.switchKeyboard()
        }
        val del = sideButton(R.drawable.sdk_skb_key_delete_icon, "退格") {
            DevicesUtils.tryVibrate(this)
            inputView.voiceBackspace()
        }
        micView.apply {
            setImageResource(R.drawable.ic_menu_voice)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = micBg
            contentDescription = "语音输入"
            setOnTouchListener { _, e -> onMicTouch(e) }
        }
        row.addView(back, LinearLayout.LayoutParams(0, dp(56), 1f))
        row.addView(micView, LinearLayout.LayoutParams(dp(76), dp(76)))
        row.addView(del, LinearLayout.LayoutParams(0, dp(56), 1f))
        root.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(16)
        })
        addView(root)
        setMicColor(false)
    }

    private fun sideButton(icon: Int, desc: String, onClick: () -> Unit) = ImageView(context).apply {
        setImageResource(icon)
        setColorFilter(activeTheme.keyTextColor)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        contentDescription = desc
        setOnClickListener { onClick() }
    }

    /** 打开面板时调用 */
    fun showPanel() {
        panelActive = true
        keepStatus = false
        updateModelLabel()
        setIdle()
    }

    private fun activeKind(): AsrModelKind? = VoiceModelManager.activeModel()?.kind

    /** 右上角：当前模型名；导入了两个以上时可点击切换 */
    private fun updateModelLabel() {
        val list = if (AsrNative.available) VoiceModelManager.listModels() else emptyList()
        val cur = VoiceModelManager.activeModel()
        modelView.visibility = if (cur == null) View.GONE else View.VISIBLE
        modelView.text = when {
            cur == null -> ""
            list.size > 1 -> "模型：${cur.shortName}  ⇄ 切换"
            else -> "模型：${cur.shortName}"
        }
        modelView.isClickable = list.size > 1
    }

    private fun switchModel() {
        if (VoiceRecognizer.isRecording || VoiceModelManager.listModels().size < 2) return
        val next = VoiceModelManager.switchToNext() ?: return
        updateModelLabel()
        keepStatus = false
        setIdle()
        Toast.makeText(context, "已切换到：${next.name}", Toast.LENGTH_SHORT).show()
    }

    private fun setIdle() {
        when {
            !AsrNative.available -> setStatus("当前设备不支持离线语音（需要 64 位 arm64 手机）", false)
            !VoiceModelManager.isReady() -> setStatus("还没有导入语音模型\n点麦克风去 设置 → 数据与模型 → 导入语音模型", false)
            !VoicePermissionActivity.hasPermission(context) -> setStatus("需要麦克风权限\n点麦克风去允许", false)
            else -> setStatus("点一下麦克风开始说话\n（也可以按住说、松开上屏）", false)
        }
    }

    private fun setStatus(text: String, listening: Boolean) {
        statusView.text = text
        setMicColor(listening)
    }

    private fun setMicColor(listening: Boolean) {
        micBg.setColor(if (listening) Color.parseColor("#E53935") else activeTheme.accentKeyBackgroundColor)
    }

    private fun onMicTouch(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                DevicesUtils.tryVibrate(this)
                pressDownAt = SystemClock.uptimeMillis()
                pressStartedSession = false
                if (VoiceRecognizer.isRecording) {
                    VoiceRecognizer.stop()          // 正在说 → 点一下结束
                } else if (prepare()) {
                    panelActive = true
                    keepStatus = false
                    VoiceRecognizer.start(listener)
                    pressStartedSession = true
                }
            }
            MotionEvent.ACTION_UP -> {
                // 按住超过 0.6 秒再松开 = “按住说话”，松开即结束
                if (pressStartedSession && SystemClock.uptimeMillis() - pressDownAt > 600) {
                    VoiceRecognizer.stop()
                }
            }
            MotionEvent.ACTION_CANCEL -> if (pressStartedSession) VoiceRecognizer.stop()
        }
        return true
    }

    /** 检查模型和权限；缺什么就引导去补，返回能否开始 */
    private fun prepare(): Boolean {
        if (!AsrNative.available) {
            setIdle()
            return false
        }
        if (!VoiceModelManager.isReady()) {
            AppUtil.launchSettingsToOther(context)
            return false
        }
        if (!VoicePermissionActivity.hasPermission(context)) {
            VoicePermissionActivity.request(context)
            return false
        }
        return true
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (panelActive && !isShown) {
            panelActive = false
            VoiceRecognizer.onPanelHidden()
        }
    }

    /** 收起键盘（输入法窗口隐藏）时也要立刻停麦，否则会在后台一直录音 */
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != View.VISIBLE && panelActive) {
            panelActive = false
            VoiceRecognizer.onPanelHidden()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (panelActive) {
            panelActive = false
            VoiceRecognizer.onPanelHidden()
        }
    }
}
