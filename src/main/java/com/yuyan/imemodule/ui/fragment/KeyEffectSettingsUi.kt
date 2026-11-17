package com.yuyan.imemodule.ui.fragment

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import com.yuyan.imemodule.keyboard.effect.GapLightStyle
import com.yuyan.imemodule.keyboard.effect.GlowColors
import com.yuyan.imemodule.keyboard.effect.KeyEffectConfig
import com.yuyan.imemodule.keyboard.effect.KeyEffectPreviewView
import com.yuyan.imemodule.keyboard.effect.LiquidGlassStrength
import com.yuyan.imemodule.prefs.AppPrefs
import com.yuyan.imemodule.view.preference.ManagedPreference
import splitties.dimensions.dp
import splitties.resources.styledColor

/**
 * 花花：「按键效果」页里的视觉效果部分（声音、振动在它上面）。
 * 每项单独开关；除按键气泡外默认都关。改动立即写入设置，键盘下次画的时候就按新设置画。
 */
class KeyEffectSettingsUi(private val ctx: Context) {
    private val prefs = AppPrefs.getInstance()
    private val k = prefs.keyEffect
    private val preview = KeyEffectPreviewView(ctx)
    private val gapDetails = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val liquidDetails = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val liquidRow: View
    private val margin = ctx.dp(30)

    val root: LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    private val glowDetails = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    init {
        // 花花：按键音色（声音开关和音量在上面）
        root.addView(header("按键音色"))
        root.addView(choices(listOf("cyime" to "CyIME 风格", "system" to "花花原声"), k.soundStyle) {
            com.yuyan.imemodule.prefs.InputFeedbacks.preloadSounds()
            com.yuyan.imemodule.utils.DevicesUtils.tryPlayKeyDown()
        })
        root.addView(hint("CyIME 风格：机械键盘实录（Rain75 紫薇轴，来自开源输入法 CyIME，GPL-3.0）；花花原声：系统按键音"))

        root.addView(header("视觉效果"))
        root.addView(hint("在下面的小键盘上按一下试试"))
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = ctx.dp(16); rightMargin = ctx.dp(16); bottomMargin = ctx.dp(8)
        })
        root.addView(switchRow("按键气泡", "按下字符键时在上方弹出放大的字", prefs.keyboardSetting.keyboardBalloonShow))
        root.addView(switchRow("按键动效", "按下时键帽缩小再回弹", k.motion))
        root.addView(switchRow("键内炫光", "按下时键里闪过半秒的渐变光，每次换一个颜色", k.glow) { on ->
            glowDetails.visibility = if (on) View.VISIBLE else View.GONE
        })
        glowDetails.addView(label("炫光颜色"))
        glowDetails.addView(choices(GlowColors.entries.map { it.id to it.title }, k.glowColors))
        glowDetails.addView(hint("多彩：黄、桃、青柠、紫、粉、绿、橙、天蓝、青轮换；跟随主题：主题强调色附近的颜色"))
        root.addView(glowDetails)
        glowDetails.visibility = if (k.glow.getValue()) View.VISIBLE else View.GONE
        root.addView(switchRow("彩虹渐变背景", "键盘底换成粉、紫、蓝、青绿的柔和渐变，炫光更艳；浅色、深色主题各有一套；用了自定义背景图的主题不换", k.rainbowBackground) {
            preview.refreshTheme()
        })
        root.addView(switchRow("键缝动效", "光线沿键缝从按下的键往外走，删除时往回收；颜色跟随主题", k.gap) { on ->
            gapDetails.visibility = if (on) View.VISIBLE else View.GONE
        })
        buildGapDetails()
        root.addView(gapDetails)
        gapDetails.visibility = if (k.gap.getValue()) View.VISIBLE else View.GONE

        root.addView(header("玻璃"))
        root.addView(switchRow("玻璃效果", "键面保持主题原色（不透明），只在键边加亮边", k.glass) { on ->
            liquidRow.isEnabled = on
            liquidRow.alpha = if (on) 1f else .4f
            liquidDetails.visibility = if (on && k.liquid.getValue()) View.VISIBLE else View.GONE
        })
        liquidRow = switchRow("液态玻璃", "按键一道细亮边，按住更亮；自定义背景图时键边折射背景（Android 13 及以上）", k.liquid) { on ->
            liquidDetails.visibility = if (on && k.glass.getValue()) View.VISIBLE else View.GONE
        }
        root.addView(liquidRow)
        liquidRow.alpha = if (k.glass.getValue()) 1f else .4f
        liquidRow.isEnabled = k.glass.getValue()
        liquidDetails.addView(label("液态玻璃强度"))
        liquidDetails.addView(choices(LiquidGlassStrength.entries.map { it.id to it.label }, k.liquidStrength))
        root.addView(liquidDetails)
        liquidDetails.visibility = if (k.glass.getValue() && k.liquid.getValue()) View.VISIBLE else View.GONE
        root.addView(View(ctx), LinearLayout.LayoutParams(1, ctx.dp(24)))
    }

    private fun buildGapDetails() {
        gapDetails.addView(label("样式"))
        gapDetails.addView(choices(GapLightStyle.entries.map { it.id to it.title }, k.gapStyle))
        gapDetails.addView(slider("速度", k.gapSpeed, KeyEffectConfig.SPEED_MIN, KeyEffectConfig.SPEED_MAX) { "%.1f 倍".format(it / 10f) })
        gapDetails.addView(slider("范围", k.gapReach, KeyEffectConfig.REACH_MIN, KeyEffectConfig.REACH_MAX, step = 5) { "%.1f 个键".format(it / 10f) })
        gapDetails.addView(slider("线宽", k.gapWidth, KeyEffectConfig.WIDTH_MIN, KeyEffectConfig.WIDTH_MAX, step = 5) { "%.1f dp".format(it / 10f) })
        gapDetails.addView(slider("亮度", k.gapBrightness, KeyEffectConfig.BRIGHT_MIN, KeyEffectConfig.BRIGHT_MAX, step = 5) { "$it%" })
        gapDetails.addView(slider("拖尾", k.gapTail, KeyEffectConfig.TAIL_MIN, KeyEffectConfig.TAIL_MAX, step = 5) { "$it%" })
        gapDetails.addView(slider("层数", k.gapLayers, KeyEffectConfig.LAYERS_MIN, KeyEffectConfig.LAYERS_MAX) { "$it 道" })
    }

    private fun header(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(ctx.styledColor(android.R.attr.colorAccent))
        setPadding(margin, ctx.dp(16), margin, ctx.dp(6))
    }

    private fun hint(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 12f
        alpha = .7f
        setPadding(margin, 0, margin, ctx.dp(6))
    }

    private fun label(text: String) = TextView(ctx).apply {
        this.text = text
        textSize = 15f
        setPadding(margin, ctx.dp(8), margin, ctx.dp(2))
    }

    private fun switchRow(title: String, summary: String, pref: ManagedPreference.PBool,
                          onChanged: ((Boolean) -> Unit)? = null): View {
        val sw = SwitchCompat(ctx).apply { isChecked = pref.getValue(); isClickable = false; isFocusable = false }
        val texts = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(ctx).apply { text = title; textSize = 16f })
            addView(TextView(ctx).apply { text = summary; textSize = 12f; alpha = .7f })
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ctx.dp(56)
            setPadding(margin, ctx.dp(8), margin, ctx.dp(8))
            isClickable = true
            isFocusable = true
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = ctx.dp(12) })
            addView(sw)
            setOnClickListener {
                if (!isEnabled) return@setOnClickListener
                val on = !pref.getValue()
                pref.setValue(on)
                sw.isChecked = on
                onChanged?.invoke(on)
                preview.refresh()
            }
        }
    }

    private fun choices(items: List<Pair<String, String>>, pref: ManagedPreference.PString,
                        onChanged: ((String) -> Unit)? = null): View {
        val group = RadioGroup(ctx).apply {
            orientation = RadioGroup.HORIZONTAL
            setPadding(margin - ctx.dp(6), 0, margin, ctx.dp(4))
        }
        val current = pref.getValue()
        items.forEachIndexed { index, (id, title) ->
            val button = RadioButton(ctx).apply {
                this.id = View.generateViewId()
                text = title
                tag = id
            }
            group.addView(button, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (id == current || (index == 0 && items.none { it.first == current })) group.check(button.id)
        }
        group.setOnCheckedChangeListener { g, checkedId ->
            val id = g.findViewById<RadioButton>(checkedId)?.tag as? String ?: return@setOnCheckedChangeListener
            pref.setValue(id)
            preview.refresh()
            onChanged?.invoke(id)
        }
        return group
    }

    private fun slider(title: String, pref: ManagedPreference.PInt, min: Int, max: Int, step: Int = 1,
                       format: (Int) -> String): View {
        val value = TextView(ctx).apply { textSize = 14f; setTextColor(ctx.styledColor(android.R.attr.colorAccent)) }
        val bar = SeekBar(ctx).apply { this.max = (max - min) / step }
        fun show(v: Int) { value.text = format(v) }
        val start = pref.getValue().coerceIn(min, max)
        bar.progress = (start - min) / step
        show(start)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                show(v)
                if (fromUser) { pref.setValue(v); preview.refresh() }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
        val top = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(ctx).apply { text = title; textSize = 15f }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(value)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(margin, ctx.dp(6), margin, 0)
            addView(top)
            addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
    }
}
