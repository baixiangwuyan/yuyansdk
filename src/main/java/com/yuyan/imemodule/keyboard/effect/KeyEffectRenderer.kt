// 按键动效（缩放回弹）、键内炫光、键缝动效移植自 CyIME
// （github.com/Cyletix/CyIME，GPL-3.0，tag 1.15.94，ui/keyboard/KeyGlow.kt、KeyGapLight.kt），
// 从 Compose 改写成普通 View 的 Canvas 绘制：曲线、时长、颜色、几何都照原样，
// 只保留按键用到的部分（按下往外走、删除往里收），不含空格移光标、语音音量、长按进度等。
package com.yuyan.imemodule.keyboard.effect

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.view.animation.AnimationUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.random.Random

internal const val KEY_GLOW_DURATION_MS = 500
internal const val KEY_CAP_DURATION_MS = 235
internal const val KEY_GLOW_HOLD_ALPHA = 0.5f   // 花花：0.4→0.5，按住时颜色更实
internal const val KEY_GLOW_RELEASE_MS = 180
private const val GAP_LIGHT_MAX_PULSES = 6

/**
 * 一块键盘上的按键视觉效果。只认按键的格子（含键缝）位置，不认具体按键类型，设置页的预览也用它。
 *
 * 用法（都在主线程）：
 *  - [setKeys] 告诉它每个键的格子；键盘换了再调；
 *  - 按下时 [press]，松开时 [release]；
 *  - 画键之前 [drawUnderKeys]（键缝光在键帽下面），画每个键时用 [capScale] 缩放键帽、[drawGlow] 画键内炫光；
 *  - 画完看 [isAnimating]，还在动就再要一帧。
 * 所有效果关着时这些调用都直接返回，不画任何东西。
 */
class KeyEffectRenderer(private val density: Float) {
    var config: KeyEffectConfig? = null

    // ---------------- 键的位置 ----------------
    private var cells = FloatArray(0)
    private var keyCount = 0
    private var gapX = 0f
    private var gapY = 0f
    private var capRadius = 0f
    private var net: GapNet? = null
    private var mask: Path? = null
    /** 键帽半透明（玻璃）时，方框、菱形要裁到键缝里，不然会透过键帽看到。 */
    var translucentCaps = false

    /**
     * [cells] 每个键四个数：格子的左、上、右、下（键帽往外各扩半条键缝）。
     * [halfGapX]/[halfGapY] 是半条键缝宽（键帽离格子边的距离）。
     */
    fun setKeys(cells: FloatArray, count: Int, halfGapX: Float, halfGapY: Float, radius: Float) {
        if (count == keyCount && halfGapX == gapX && halfGapY == gapY && radius == capRadius &&
            cells.size >= count * 4 && this.cells.size >= count * 4 &&
            (0 until count * 4).all { this.cells[it] == cells[it] }) return
        this.cells = cells.copyOf(count * 4)
        keyCount = count
        gapX = halfGapX; gapY = halfGapY; capRadius = radius
        net = null; mask = null
        pulses.clear()
    }

    private fun ensureNet(): GapNet? {
        net?.let { return it }
        if (keyCount == 0) return null
        return runCatching {
            GapNet.build(cells, max(1.5f, gapX), max(1.5f, gapY))
        }.getOrNull().also { net = it }
    }

    // ---------------- 时钟 ----------------
    private fun now(): Long = AnimationUtils.currentAnimationTimeMillis()

    /** 系统「动画时长缩放」，0 = 关了动画，这时什么都不画。 */
    private fun durationScale(): Float =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ValueAnimator.getDurationScale() else 1f

    // ---------------- 按下的键：缩放 + 炫光 ----------------
    private class GlowSquare(val x: Float, val y: Float, val side: Float, val angle: Float, val spin: Float)

    private class Press(val key: Int, val start: Long, val scale: Float, val color: Int, val squares: Array<GlowSquare>?) {
        /** 白键上画小方块用的深一点的同色 */
        val deep: Int = deepen(color)
        var releasedAt = -1L
        var halo: RadialGradient? = null
        var haloW = 0f
        var haloH = 0f
    }

    private val presses = ArrayList<Press>(4)

    private var paletteAccent = 0
    private var themedPalette: IntArray = VIVID_GLOW
    private var lastColorIndex = -1

    /** 「跟随主题」时光色取主题强调色两侧的色相；强调色接近灰时用多彩。 */
    var accentColor: Int = 0xFF8F73E2.toInt()
        set(value) {
            field = value
            if (value != paletteAccent) { paletteAccent = value; themedPalette = themedGlowPalette(value) ?: VIVID_GLOW }
        }

    /** 键帽是浅色（白/浅灰键）：小方块不用 Screen 混合（白底上 Screen 等于看不见），改成普通叠加的深一点的同色。 */
    var lightKeys: Boolean = true

    /** 花花：每次按键换一个颜色，不和上一次重复（以前随机，常连着几次都是同一类冷色）。 */
    private fun nextGlowColor(c: KeyEffectConfig): Int {
        val pal = if (c.glowColors == GlowColors.THEME) themedPalette else VIVID_GLOW
        if (pal.size == 1) return pal[0]
        var i = Random.nextInt(pal.size - 1)
        if (i >= lastColorIndex && lastColorIndex in pal.indices) i++
        lastColorIndex = i
        return pal[i]
    }

    /** 按下第 [key] 个键；[delete] 为真时键缝光往里收。 */
    fun press(key: Int, delete: Boolean) {
        val c = config ?: return
        if (!c.anyAnimated || key !in 0 until keyCount) return
        val scale = durationScale()
        if (scale <= 0f) return
        val t = now()
        if (c.motion || c.glow) {
            presses.removeAll { it.key == key }
            if (presses.size >= 6) presses.removeAt(0)
            val squares = if (c.glow) Array(4) { index ->
                GlowSquare(0.2f + (index % 2) * 0.6f + (Random.nextFloat() - 0.5f) * 0.25f,
                    0.15f + (index / 2) * 0.7f + (Random.nextFloat() - 0.5f) * 0.25f,
                    0.85f + Random.nextFloat() * 0.50f,
                    Random.nextFloat() * 60f - 30f, Random.nextFloat() * 100f - 50f)
            } else null
            presses += Press(key, t, scale, if (c.glow) nextGlowColor(c) else 0, squares)
        }
        if (c.gap) addPulse(key, delete, c, scale, t)
    }

    fun release(key: Int) {
        val t = now()
        for (p in presses) if (p.key == key && p.releasedAt < 0) p.releasedAt = t
    }

    fun releaseAll() {
        val t = now()
        for (p in presses) if (p.releasedAt < 0) p.releasedAt = t
    }

    /** 清掉所有进行中的效果（换键盘、收起键盘时）。 */
    fun clear() {
        presses.clear(); pulses.clear()
    }

    private fun pressOf(key: Int): Press? {
        for (i in presses.indices.reversed()) if (presses[i].key == key) return presses[i]
        return null
    }

    /** 键帽此刻的缩放（围绕键帽中心）；没有缩放动画时是 1。 */
    fun capScale(key: Int): Float {
        val c = config ?: return 1f
        if (!c.motion || presses.isEmpty()) return 1f
        val p = pressOf(key) ?: return 1f
        val elapsed = (now() - p.start) / p.scale
        return keyGlowScale(elapsed / KEY_GLOW_DURATION_MS)
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val squarePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val screenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN) }
    private val tmpRect = RectF()

    /**
     * 键内炫光：画在键帽底色上面、文字下面，裁在键帽形状里。[cap] 是键帽（视图坐标），[radius] 是圆角。
     */
    fun drawGlow(canvas: Canvas, key: Int, cap: RectF, radius: Float) {
        val c = config ?: return
        if (!c.glow || presses.isEmpty()) return
        val p = pressOf(key) ?: return
        val squares = p.squares ?: return
        val time = now()
        val t = ((time - p.start) / p.scale / KEY_GLOW_DURATION_MS).coerceIn(0f, 1f)
        val hold = if (p.releasedAt < 0) KEY_GLOW_HOLD_ALPHA
            else KEY_GLOW_HOLD_ALPHA * (1f - (time - p.releasedAt) / (KEY_GLOW_RELEASE_MS * p.scale)).coerceIn(0f, 1f)
        if (t >= 1f && hold <= 0f) return
        val w = cap.width(); val h = cap.height()
        if (w <= 0f || h <= 0f) return
        val save = canvas.save()
        tmpPath.rewind()
        tmpPath.addRoundRect(cap, radius, radius, Path.Direction.CW)
        canvas.clipPath(tmpPath)
        glowPaint.color = p.color
        if (t >= 1f) {
            glowPaint.alpha = (hold * 255).toInt()
            canvas.drawRect(cap, glowPaint)
        } else {
            val fade = keyGlowRemaining(t)
            val travel = keyGlowTravel(t)
            glowPaint.alpha = (max(fade * (if (lightKeys) GLOW_FILL_LIGHT else GLOW_FILL_DARK), hold) * 255).toInt()
            canvas.drawRect(cap, glowPaint)
            // 光晕：只有它用渐变，按下时按键大小建一次。
            if (p.halo == null || p.haloW != w || p.haloH != h) {
                p.halo = RadialGradient(0f, 0f, max(w, h).coerceAtLeast(1f) * 0.8f,
                    intArrayOf(withAlpha(p.color, 0.4f), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                p.haloW = w; p.haloH = h
            }
            val gx = cap.left + w * 0.5f
            val gy = cap.top + h * 0.55f
            canvas.save()
            canvas.translate(gx, gy)
            screenPaint.shader = p.halo
            screenPaint.color = Color.WHITE
            screenPaint.alpha = (fade * 255).toInt()
            canvas.drawRect(cap.left - gx, cap.top - gy, cap.right - gx, cap.bottom - gy, screenPaint)
            screenPaint.shader = null
            canvas.restore()
            for (sq in squares) {
                val side = min(w, h) * sq.side * keyGlowSquareSize(t)
                val cx = cap.left + w * sq.x
                val cy = cap.top + h * (sq.y - travel * 0.06f)
                canvas.save()
                canvas.rotate(sq.angle + sq.spin * travel, cx, cy)
                for (layer in 2 downTo 0) {
                    val edge = side * layer * 0.015f
                    val extent = side + edge * 2f
                    tmpRect.set(cx - extent / 2f, cy - extent / 2f, cx + extent / 2f, cy + extent / 2f)
                    val r = side * 0.09f + edge
                    if (lightKeys) {
                        // 白键上：普通叠加更深一点的同色，方块看得见
                        squarePaint.color = p.deep
                        squarePaint.alpha = (fade * (if (layer == 0) 0.30f else 0.06f) * 255).toInt()
                        canvas.drawRoundRect(tmpRect, r, r, squarePaint)
                    } else {
                        screenPaint.color = p.color
                        screenPaint.alpha = (fade * (if (layer == 0) 0.32f else 0.05f) * 255).toInt()
                        canvas.drawRoundRect(tmpRect, r, r, screenPaint)
                    }
                }
                canvas.restore()
            }
        }
        canvas.restoreToCount(save)
    }

    // ---------------- 键缝动效 ----------------
    private class Pulse(
        val pull: Boolean,
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val routes: List<GapRoute>, val reach: Float, val dash: Float,
        val start: Long, val durationMs: Float,
    )

    private val pulses = ArrayList<Pulse>()

    private fun addPulse(key: Int, delete: Boolean, c: KeyEffectConfig, scale: Float, time: Long) {
        val net = ensureNet() ?: return
        if (!net.has(key)) return
        val reach = net.unit * c.gapReach
        val routes = if (c.gapStyle == GapLightStyle.TRACE) net.routes(key, reach) else emptyList()
        if (pulses.size >= GAP_LIGHT_MAX_PULSES) pulses.removeAt(0)
        pulses += Pulse(delete, net.left(key), net.top(key), net.right(key), net.bottom(key), routes, reach,
            dash = net.unit * (.45f + c.gapTail * .9f), start = time,
            durationMs = GAP_LIGHT_PRESS_MS * scale / c.gapSpeed)
    }

    private val linePaint = Paint().apply { isAntiAlias = false }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.MITER
    }
    private val outline = Path()
    private val tmpPath = Path()
    private var viewW = 0f
    private var viewH = 0f

    /** 键缝光：在画所有键之前调，光只从键缝里露出来。 */
    fun drawUnderKeys(canvas: Canvas, width: Int, height: Int, color: Int) {
        val c = config ?: return
        if (!c.gap || pulses.isEmpty()) return
        val time = now()
        pulses.removeAll { time - it.start >= it.durationMs }
        if (pulses.isEmpty()) return
        viewW = width.toFloat(); viewH = height.toFloat()
        linePaint.color = color
        strokePaint.color = color
        val line = c.gapWidthDp * density
        // 键缝里的线不比键缝宽。
        val lineAcross = min(line, gapY * 2f)
        val lineAlong = min(line, gapX * 2f)
        val trace = c.gapStyle == GapLightStyle.TRACE
        if (!trace && translucentCaps) {
            val save = canvas.save()
            canvas.clipPath(gapMask())
            drawPulses(canvas, c, time, line, lineAcross, lineAlong)
            canvas.restoreToCount(save)
        } else drawPulses(canvas, c, time, line, lineAcross, lineAlong)
    }

    private fun drawPulses(canvas: Canvas, c: KeyEffectConfig, time: Long, line: Float, lineAcross: Float, lineAlong: Float) {
        for (pulse in pulses) {
            val raw = ((time - pulse.start).coerceAtLeast(0L) / pulse.durationMs).coerceIn(0f, 1f)
            // 删除是按键的动画倒着放。
            val progress = if (pulse.pull) 1f - raw else raw
            if (c.gapStyle == GapLightStyle.TRACE) drawTrace(canvas, pulse, progress, c, lineAcross, lineAlong)
            else drawEchoes(canvas, pulse, progress, c, line)
        }
    }

    /** 线路：键边一圈先亮，短线沿键缝网往外走，头是实的，后面分级变淡。 */
    private fun drawTrace(canvas: Canvas, pulse: Pulse, progress: Float, c: KeyEffectConfig, lineAcross: Float, lineAlong: Float) {
        if (lineAcross < .5f && lineAlong < .5f) return
        val box = gapLightCut(progress, .35f) * c.gapBrightness
        if (box > 0f) frame(canvas, pulse.left, pulse.top, pulse.right, pulse.bottom, lineAcross, lineAlong, box)
        val reached = pulse.reach * gapLightEase(progress)
        val fade = gapLightCut(progress, .75f) * c.gapBrightness
        if (fade <= 0f) return
        for (layer in 0 until c.gapLayers) {
            val front = reached - layer * pulse.dash * 1.4f
            if (front <= 0f) continue
            val alpha = fade * (1f - layer * .28f)
            for (route in pulse.routes) {
                val meet = route.meet
                val thickness = if (route.ay == route.by) lineAcross else lineAlong
                if (thickness < .5f) continue
                piece(canvas, route.ax, route.ay, route.bx, route.by, route.length, route.da, meet, front, pulse.dash, thickness, alpha)
                piece(canvas, route.bx, route.by, route.ax, route.ay, route.length, route.db, route.length - meet, front, pulse.dash, thickness, alpha)
            }
        }
    }

    private fun piece(canvas: Canvas, ax: Float, ay: Float, bx: Float, by: Float, length: Float, offset: Float, limit: Float,
                      front: Float, dash: Float, thickness: Float, alpha: Float) {
        val head = front - offset
        val from = max(0f, head - dash)
        val to = min(limit, head)
        if (to <= from || length <= 0f) return
        val ux = (bx - ax) / length
        val uy = (by - ay) / length
        val step = dash / GAP_LIGHT_TAIL_STEPS
        for (level in 0 until GAP_LIGHT_TAIL_STEPS) {
            val a = max(from, head - (level + 1) * step)
            val b = min(to, head - level * step)
            if (b <= a) continue
            val x0 = ax + ux * a; val x1 = ax + ux * b
            val y0 = ay + uy * a; val y1 = ay + uy * b
            val shade = alpha * (1f - level.toFloat() / GAP_LIGHT_TAIL_STEPS)
            if (ay == by) block(canvas, min(x0, x1), ay - thickness / 2f, max(x0, x1), ay + thickness / 2f, shade, vertical = false)
            else block(canvas, ax - thickness / 2f, min(y0, y1), ax + thickness / 2f, max(y0, y1), shade, vertical = true)
        }
    }

    /** 方框、菱形：从按下的键往外扩的硬边轮廓，后面跟着几道。 */
    private fun drawEchoes(canvas: Canvas, pulse: Pulse, progress: Float, c: KeyEffectConfig, line: Float) {
        for (layer in 0 until c.gapLayers) {
            val local = progress - layer * .2f
            if (local <= 0f || local >= 1f) continue
            val grow = pulse.reach * gapLightEase(local)
            val alpha = gapLightCut(local, .55f) * c.gapBrightness * (1f - layer * .28f)
            if (alpha <= 0f) continue
            if (c.gapStyle == GapLightStyle.FRAME) {
                frame(canvas, pulse.left - grow, pulse.top - grow, pulse.right + grow, pulse.bottom + grow, line, line, alpha)
            } else {
                val cx = (pulse.left + pulse.right) / 2f
                val cy = (pulse.top + pulse.bottom) / 2f
                val radius = (pulse.right - pulse.left + pulse.bottom - pulse.top) * .3f + grow * 1.25f
                strokePaint.strokeWidth = line
                strokePaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
                outline.rewind()
                outline.moveTo(cx, cy - radius); outline.lineTo(cx + radius, cy)
                outline.lineTo(cx, cy + radius); outline.lineTo(cx - radius, cy)
                outline.close()
                canvas.drawPath(outline, strokePaint)
            }
        }
    }

    private fun frame(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, across: Float, along: Float, alpha: Float) {
        val h = across / 2f
        val v = along / 2f
        if (across >= .5f) {
            block(canvas, left - v, top - h, right + v, top + h, alpha, vertical = false)
            block(canvas, left - v, bottom - h, right + v, bottom + h, alpha, vertical = false)
        }
        if (along >= .5f) {
            block(canvas, left - v, top + h, left + v, bottom - h, alpha, vertical = true)
            block(canvas, right - v, top + h, right + v, bottom - h, alpha, vertical = true)
        }
    }

    /** 一块实色，边对齐到整像素；压在按键区边线上的线整条挪进来，不画到按键区外面。 */
    private fun block(canvas: Canvas, left: Float, top: Float, right: Float, bottom: Float, alpha: Float, vertical: Boolean) {
        var l = round(left); var t = round(top); var r = round(right); var b = round(bottom)
        if (vertical) {
            if (r - l < 1f) r = l + 1f
            if (r <= 0f || l >= viewW) return
            if (l < 0f) { r -= l; l = 0f } else if (r > viewW) { l -= r - viewW; r = viewW }
        } else {
            if (b - t < 1f) b = t + 1f
            if (b <= 0f || t >= viewH) return
            if (t < 0f) { b -= t; t = 0f } else if (b > viewH) { t -= b - viewH; b = viewH }
        }
        if (r <= l || b <= t) return
        linePaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(l, t, r, b, linePaint)
    }

    /** 键缝的形状：每个键的格子减去键帽。只在键帽半透明、样式会画到键帽底下时用。 */
    private fun gapMask(): Path {
        mask?.let { return it }
        val path = Path()
        path.fillType = Path.FillType.EVEN_ODD
        val rect = RectF()
        for (i in 0 until keyCount) {
            val l = cells[i * 4]; val t = cells[i * 4 + 1]; val r = cells[i * 4 + 2]; val b = cells[i * 4 + 3]
            if (r <= l || b <= t) continue
            path.addRect(l, t, r, b, Path.Direction.CW)
            rect.set(l + gapX, t + gapY, r - gapX, b - gapY)
            if (rect.width() > 0f && rect.height() > 0f) path.addRoundRect(rect, capRadius, capRadius, Path.Direction.CW)
        }
        mask = path
        return path
    }

    // ---------------- 帧 ----------------
    /** 还有效果在动：调用方据此 postInvalidateOnAnimation。都走完就不再要帧。 */
    fun isAnimating(): Boolean {
        if (presses.isEmpty() && pulses.isEmpty()) return false
        val time = now()
        val c = config
        presses.removeAll { p ->
            val anim = (time - p.start) / p.scale
            val animDone = anim >= if (c?.glow == true) KEY_GLOW_DURATION_MS else KEY_CAP_DURATION_MS
            val holdDone = c?.glow != true || (p.releasedAt >= 0 && time - p.releasedAt >= KEY_GLOW_RELEASE_MS * p.scale)
            animDone && holdDone && (p.releasedAt >= 0 || c?.glow != true)
        }
        pulses.removeAll { time - it.start >= it.durationMs }
        // 按住不放、炫光已走完只剩底色时不用逐帧重画：松手时视图本来就会重画。
        val moving = presses.any { p ->
            val anim = (time - p.start) / p.scale
            anim < KEY_GLOW_DURATION_MS || (p.releasedAt >= 0)
        }
        return moving || pulses.isNotEmpty()
    }

    companion object {
        /**
         * 花花「多彩」调色盘：CyIME 七彩光（黄、桃、青柠、薰衣草紫、粉、绿、青）的色相，
         * 饱和度提高一截（原来 HSV 饱和度约 .35～.45，叠在白灰键上发淡），并补了橙、天蓝，冷暖各半。
         */
        val VIVID_GLOW = intArrayOf(
            0xFFFFD23F.toInt(), // 柠檬黄
            0xFFFF9E80.toInt(), // 桃
            0xFFB8E83C.toInt(), // 青柠
            0xFFAE8CFF.toInt(), // 薰衣草紫
            0xFFFF7FBF.toInt(), // 粉
            0xFF4FDC8A.toInt(), // 绿
            0xFFFFB347.toInt(), // 橙
            0xFF5BC6FF.toInt(), // 天蓝
            0xFF3FE0D0.toInt(), // 青
        )
        /** 炫光底色的不透明度：浅色键 0.85（原 0.75），深色键 0.65（白字上不刺眼） */
        const val GLOW_FILL_LIGHT = 0.85f
        const val GLOW_FILL_DARK = 0.65f

        /** 饱和度 +0.15、明度 -0.12 的同色 */
        fun deepen(color: Int): Int {
            val hsv = FloatArray(3)
            Color.colorToHSV(color, hsv)
            hsv[1] = (hsv[1] + .15f).coerceAtMost(1f)
            hsv[2] = (hsv[2] - .12f).coerceAtLeast(0f)
            return Color.HSVToColor(hsv)
        }

        private fun withAlpha(color: Int, alpha: Float): Int =
            (color and 0x00FFFFFF) or ((alpha.coerceIn(0f, 1f) * 255).toInt() shl 24)

        /** 强调色两侧 ±40° 的五个色相，统一成柔和高亮；强调色接近灰时返回 null（用七彩光）。 */
        fun themedGlowPalette(accent: Int): IntArray? {
            val hsv = FloatArray(3)
            Color.colorToHSV(accent, hsv)
            if (hsv[1] < .15f) return null
            val saturation = hsv[1].coerceIn(.55f, .75f)   // 花花：原 .35～.55，太淡
            return floatArrayOf(0f, -20f, 20f, -40f, 40f).map { shift ->
                Color.HSVToColor(floatArrayOf((hsv[0] + shift + 360f) % 360f, saturation, .96f))
            }.toIntArray()
        }

        // CyIME 从录屏量出来的键帽宽度（相对静止时），三次 Hermite 插值保持速度连续。
        private val capTimes = floatArrayOf(0f, 17f, 33f, 50f, 67f, 83f, 100f, 117f, 133f, 150f, 167f, 183f, 200f, 217f, 235f)
        private val capScales = floatArrayOf(1f, .952f, .852f, .752f, .686f, .667f, .743f, .867f, .967f, 1.029f, 1.052f, 1.052f, 1.033f, 1.014f, 1f)

        fun keyGlowScale(t: Float): Float {
            val ms = t.coerceIn(0f, 1f) * 500f
            if (ms >= capTimes.last()) return 1f
            var i = 0
            while (i < capTimes.lastIndex - 1 && ms >= capTimes[i + 1]) i++
            val span = capTimes[i + 1] - capTimes[i]
            val x = (ms - capTimes[i]) / span
            fun slope(j: Int): Float = if (j == 0 || j == capTimes.lastIndex) 0f else
                (capScales[j + 1] - capScales[j - 1]) / (capTimes[j + 1] - capTimes[j - 1])
            val x2 = x * x
            val x3 = x2 * x
            return (2f * x3 - 3f * x2 + 1f) * capScales[i] +
                (x3 - 2f * x2 + x) * span * slope(i) +
                (-2f * x3 + 3f * x2) * capScales[i + 1] +
                (x3 - x2) * span * slope(i + 1)
        }

        fun keyGlowSquareSize(t: Float): Float = 0.3f + 0.7f * (1f - t.coerceIn(0f, 1f))

        fun keyGlowRemaining(t: Float): Float {
            val remaining = 1f - t.coerceIn(0f, 1f)
            return remaining * remaining * (3f - 2f * remaining)
        }

        fun keyGlowTravel(t: Float): Float {
            val remaining = 1f - t.coerceIn(0f, 1f)
            return 1f - remaining * remaining * remaining * remaining
        }
    }
}
