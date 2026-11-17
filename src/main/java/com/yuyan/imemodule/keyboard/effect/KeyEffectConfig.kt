package com.yuyan.imemodule.keyboard.effect

import android.content.SharedPreferences
import com.yuyan.imemodule.prefs.AppPrefs

/** 键缝动效的样式；[id] 存进设置里，不要改（与 CyIME 同名）。 */
enum class GapLightStyle(val id: String, val title: String) {
    TRACE("trace", "线路"),
    FRAME("frame", "方框"),
    DIAMOND("diamond", "菱形");

    companion object {
        fun of(id: String?): GapLightStyle = entries.firstOrNull { it.id == id } ?: TRACE
    }
}

/**
 * 花花「按键效果」里的视觉效果设置快照。
 *
 * 每次绘制直接读这个对象的字段，不碰 SharedPreferences；设置改了由监听器作废缓存，下一帧重新读。
 * 除按键气泡（沿用原来的 keyboard_balloon_show_enable，默认改为开）以外全部默认关：
 * 关着时键盘的绘制和原来一样，不多画任何东西、不多要一帧。
 */
class KeyEffectConfig(
    val bubble: Boolean,
    val motion: Boolean,
    val glow: Boolean,
    val gap: Boolean,
    val gapStyle: GapLightStyle,
    /** 速度倍数，1 = 一次 200 ms。 */
    val gapSpeed: Float,
    /** 走多远，单位是一个键。 */
    val gapReach: Float,
    /** 线宽（dp），画的时候不超过键缝宽度。 */
    val gapWidthDp: Float,
    /** 0.3～1 */
    val gapBrightness: Float,
    /** 拖尾 0～1 */
    val gapTail: Float,
    /** 1～3 道 */
    val gapLayers: Int,
    val glass: Boolean,
    /** 液态玻璃只在玻璃开着时生效。 */
    val liquid: Boolean,
    val liquidStrength: LiquidGlassStrength,
) {
    /** 有没有需要逐帧推进的动画效果。 */
    val anyAnimated: Boolean get() = motion || glow || gap

    companion object {
        const val SPEED_MIN = 5; const val SPEED_MAX = 20          // ×0.1 倍
        const val REACH_MIN = 10; const val REACH_MAX = 40         // ×0.1 个键
        const val WIDTH_MIN = 10; const val WIDTH_MAX = 40         // ×0.1 dp
        const val BRIGHT_MIN = 30; const val BRIGHT_MAX = 100      // %
        const val TAIL_MIN = 0; const val TAIL_MAX = 100           // %
        const val LAYERS_MIN = 1; const val LAYERS_MAX = 3         // 道
    }
}

object KeyEffects {
    @Volatile
    private var cached: KeyEffectConfig? = null

    /** 设置每改一次就加一；键盘据此知道要重建玻璃缓存等。 */
    @Volatile
    var version = 0
        private set

    // SharedPreferences 只弱引用监听器，这里必须强引用住。
    private var listener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    fun config(): KeyEffectConfig {
        cached?.let { return it }
        val prefs = AppPrefs.getInstance()
        bind(prefs)
        val k = prefs.keyEffect
        val c = KeyEffectConfig(
            bubble = prefs.keyboardSetting.keyboardBalloonShow.getValue(),
            motion = k.motion.getValue(),
            glow = k.glow.getValue(),
            gap = k.gap.getValue(),
            gapStyle = GapLightStyle.of(k.gapStyle.getValue()),
            gapSpeed = k.gapSpeed.getValue().coerceIn(KeyEffectConfig.SPEED_MIN, KeyEffectConfig.SPEED_MAX) / 10f,
            gapReach = k.gapReach.getValue().coerceIn(KeyEffectConfig.REACH_MIN, KeyEffectConfig.REACH_MAX) / 10f,
            gapWidthDp = k.gapWidth.getValue().coerceIn(KeyEffectConfig.WIDTH_MIN, KeyEffectConfig.WIDTH_MAX) / 10f,
            gapBrightness = k.gapBrightness.getValue().coerceIn(KeyEffectConfig.BRIGHT_MIN, KeyEffectConfig.BRIGHT_MAX) / 100f,
            gapTail = k.gapTail.getValue().coerceIn(KeyEffectConfig.TAIL_MIN, KeyEffectConfig.TAIL_MAX) / 100f,
            gapLayers = k.gapLayers.getValue().coerceIn(KeyEffectConfig.LAYERS_MIN, KeyEffectConfig.LAYERS_MAX),
            glass = k.glass.getValue(),
            liquid = k.glass.getValue() && k.liquid.getValue(),
            liquidStrength = LiquidGlassStrength.of(k.liquidStrength.getValue()),
        )
        cached = c
        return c
    }

    @Synchronized
    private fun bind(prefs: AppPrefs) {
        if (listener != null) return
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            cached = null
            version++
        }
        listener = l
        prefs.keyEffect.motion.sharedPreferences.registerOnSharedPreferenceChangeListener(l)
    }
}
