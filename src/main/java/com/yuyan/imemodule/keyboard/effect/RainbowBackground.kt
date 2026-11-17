package com.yuyan.imemodule.keyboard.effect

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.yuyan.imemodule.data.theme.Theme
import kotlin.math.max

/**
 * 花花：彩虹渐变键盘背景（仿 CyIME 的粉-紫-蓝-青绿柔光底）。
 * 一层斜向线性渐变 + 三团柔光，静态 Drawable：只在键盘尺寸变时建一次着色器，打字时不重画。
 * 浅色主题用粉彩，深色主题用压暗的同色系（白字、深色键上不刺眼）。
 */
class RainbowBackgroundDrawable(private val dark: Boolean) : Drawable() {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val blob = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private var blobs: List<RadialGradient> = emptyList()

    // 浅色：青绿 → 天蓝 → 薰衣草 → 粉 → 桃；深色：同色相压暗
    private val light = intArrayOf(0xFFA8DCD5.toInt(), 0xFFBFD8F4.toInt(), 0xFFD8CCF2.toInt(), 0xFFF4C6DC.toInt(), 0xFFF8D6C4.toInt())
    private val night = intArrayOf(0xFF153A3C.toInt(), 0xFF1B2D4E.toInt(), 0xFF2E2552.toInt(), 0xFF46223F.toInt(), 0xFF4A2E2A.toInt())
    private val lightBlobs = intArrayOf(0x99F7B8D6.toInt(), 0x8098E0C8.toInt(), 0x80B7C9FF.toInt())
    private val nightBlobs = intArrayOf(0x66B0407A.toInt(), 0x5530A08C.toInt(), 0x554A5CC8.toInt())

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val w = bounds.width().toFloat().coerceAtLeast(1f)
        val h = bounds.height().toFloat().coerceAtLeast(1f)
        val l = bounds.left.toFloat(); val t = bounds.top.toFloat()
        base.shader = LinearGradient(l, t + h, l + w, t, if (dark) night else light, null, Shader.TileMode.CLAMP)
        val c = if (dark) nightBlobs else lightBlobs
        val r = max(w, h) * 0.55f
        blobs = listOf(
            RadialGradient(l + w * 0.48f, t + h * 0.08f, r, c[0], c[0] and 0x00FFFFFF, Shader.TileMode.CLAMP),   // 上中：粉
            RadialGradient(l + w * 0.02f, t + h * 0.75f, r * 0.9f, c[1], c[1] and 0x00FFFFFF, Shader.TileMode.CLAMP), // 左下：青绿
            RadialGradient(l + w * 0.98f, t + h * 0.35f, r * 0.8f, c[2], c[2] and 0x00FFFFFF, Shader.TileMode.CLAMP), // 右：蓝紫
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.drawRect(b, base)
        for (g in blobs) { blob.shader = g; canvas.drawRect(b, blob) }
    }

    override fun setAlpha(alpha: Int) { base.alpha = alpha; blob.alpha = alpha; invalidateSelf() }
    override fun setColorFilter(colorFilter: ColorFilter?) { base.colorFilter = colorFilter; blob.colorFilter = colorFilter; invalidateSelf() }
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE
}

object KeyboardBackground {
    /** 自定义了背景图的主题保留图片；其余主题在开了彩虹背景时换成彩虹渐变。 */
    fun hasOwnImage(theme: Theme): Boolean = theme is Theme.Custom && theme.backgroundImage != null

    fun rainbowEnabled(): Boolean =
        runCatching { com.yuyan.imemodule.prefs.AppPrefs.getInstance().keyEffect.rainbowBackground.getValue() }.getOrDefault(false)

    fun of(theme: Theme, keyBorder: Boolean): Drawable =
        if (rainbowEnabled() && !hasOwnImage(theme)) RainbowBackgroundDrawable(theme.isDark)
        else theme.backgroundDrawable(keyBorder)
}
