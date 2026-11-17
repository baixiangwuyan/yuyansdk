// 液态玻璃的强度档位、亮边几何和图片背景的边缘折射着色器移植自 CyIME
// （github.com/Cyletix/CyIME，GPL-3.0，tag 1.15.94，ui/theme/LiquidGlass.kt、LiquidGlassStrength.kt），
// 从 Compose 改写成普通 Canvas 绘制。毛玻璃的背景模糊是花花自己的做法（见 [FrostedBackdrop]）。
package com.yuyan.imemodule.keyboard.effect

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.max
import kotlin.math.min

/**
 * 液态玻璃强度。[STANDARD] 是 CyIME 加强度之前的保守版，也是默认。
 * 数值与 CyIME 1.15.94 一致。[id] 存进设置，不要改。
 */
enum class LiquidGlassStrength(
    val id: String,
    val label: String,
    val strokeDp: Float,
    private val rimDark: FloatArray,
    private val rimLight: FloatArray,
    val bandDp: Float,
    private val bandAlpha: Float,
    private val shadeAlpha: Float,
    private val sheenAlpha: Float,
    val bevelDp: Float,
    val bendDp: Float,
    val spread: Float,
) {
    STANDARD("standard", "标准", .75f, floatArrayOf(.45f, .10f, .06f, .22f), floatArrayOf(.75f, .10f, .06f, .35f),
        0f, 0f, 0f, 0f, 5f, 9.5f, .03f),
    STRONG("strong", "加强", 1.2f, floatArrayOf(.72f, .16f, .10f, .40f), floatArrayOf(.90f, .16f, .10f, .50f),
        3f, .13f, .10f, .06f, 8f, 16f, .05f),
    STRONGEST("strongest", "最强", 1.7f, floatArrayOf(.95f, .24f, .14f, .58f), floatArrayOf(1f, .24f, .14f, .65f),
        4.5f, .22f, .16f, .11f, 11f, 24f, .08f);

    fun rimAlphas(dark: Boolean): FloatArray = if (dark) rimDark else rimLight
    fun bandAlpha(dark: Boolean): Float = (bandAlpha * if (dark) 1f else 1.5f).coerceAtMost(1f)
    fun shadeAlpha(dark: Boolean): Float = if (dark) 0f else shadeAlpha
    fun sheenAlpha(dark: Boolean): Float = (sheenAlpha * if (dark) 1f else 1.6f).coerceAtMost(1f)

    companion object {
        fun of(id: String?): LiquidGlassStrength = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

private const val LIQUID_BAND_STEPS = 3

private fun white(alpha: Float) = Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), 255, 255, 255)
private fun black(alpha: Float) = Color.argb((alpha.coerceIn(0f, 1f) * 255f).toInt(), 0, 0, 0)

/**
 * 一个键的液态玻璃亮边。按键大小不变就复用（同一行的键大多同样大），不每帧新建渐变。
 * 朝光的左上亮，对角右下一道弱一些的回光；加强、最强两档多一圈厚度和键面上半的淡高光。
 */
class LiquidRim {
    private val path = Path()
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val heldPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var keyW = -1f
    private var keyH = -1f
    private var keyRadius = -1f
    private var keyDark = false
    private var keyStrength: LiquidGlassStrength? = null
    private var keyDensity = 0f
    private var stroke = 0f
    private var bandWidths = FloatArray(0)
    private var hasBand = false
    private var hasSheen = false
    private var visible = false

    private fun prepare(w: Float, h: Float, radius: Float, dark: Boolean, strength: LiquidGlassStrength, density: Float) {
        if (w == keyW && h == keyH && radius == keyRadius && dark == keyDark && strength == keyStrength && density == keyDensity) return
        keyW = w; keyH = h; keyRadius = radius; keyDark = dark; keyStrength = strength; keyDensity = density
        stroke = strength.strokeDp * density
        val inset = stroke / 2f
        val corner = (radius - inset).coerceIn(0f, min(w, h) / 2f)
        path.rewind()
        visible = w - 2 * inset > 0f && h - 2 * inset > 0f
        if (visible) path.addRoundRect(RectF(inset, inset, w - inset, h - inset), corner, corner, Path.Direction.CW)
        val rim = strength.rimAlphas(dark)
        rimPaint.shader = LinearGradient(0f, 0f, w, h, IntArray(rim.size) { white(rim[it]) }, null, Shader.TileMode.CLAMP)
        val band = strength.bandDp * density
        val bandAlpha = strength.bandAlpha(dark) / LIQUID_BAND_STEPS
        hasBand = band > 0f && bandAlpha > 0f
        if (hasBand) {
            val shade = strength.shadeAlpha(dark) / LIQUID_BAND_STEPS
            val back = if (shade > 0f) intArrayOf(black(shade * .35f), black(shade))
                else intArrayOf(white(bandAlpha * .12f), white(bandAlpha * .55f))
            bandPaint.shader = LinearGradient(0f, 0f, w, h,
                intArrayOf(white(bandAlpha), white(bandAlpha * .22f), back[0], back[1]), null, Shader.TileMode.CLAMP)
            // 画的时候裁在 path 里面，每圈只露出里面一半：最宽的一圈正好 band 宽。
            bandWidths = FloatArray(LIQUID_BAND_STEPS) { band * 2f * (LIQUID_BAND_STEPS - it) / LIQUID_BAND_STEPS }
        }
        val sheen = strength.sheenAlpha(dark)
        hasSheen = sheen > 0f
        if (hasSheen) sheenPaint.shader = LinearGradient(0f, 0f, 0f, h * .5f, white(sheen), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        heldPaint.color = white(if (dark) .04f else .08f)
    }

    /** 画在键帽底色上面、文字下面。坐标系原点已平移到键帽左上角。 */
    fun draw(canvas: Canvas, w: Float, h: Float, radius: Float, dark: Boolean, strength: LiquidGlassStrength,
             density: Float, held: Boolean) {
        prepare(w, h, radius, dark, strength, density)
        if (!visible) return
        val save = canvas.save()
        canvas.clipPath(path)
        if (held) canvas.drawPath(path, heldPaint)
        if (hasSheen) canvas.drawPath(path, sheenPaint)
        if (hasBand) for (width in bandWidths) { bandPaint.strokeWidth = width; canvas.drawPath(path, bandPaint) }
        rimPaint.strokeWidth = if (held) stroke * 1.6f else stroke
        canvas.drawPath(path, rimPaint)
        canvas.restoreToCount(save)
    }
}

/**
 * 键盘背景图（主题里的自定义图片）。键盘能拿到这张图的像素，所以：
 *  - 液态玻璃（Android 13+）：键帽外圈弯折这张图，带一点色散。
 * 花花：键面改为不透明的主题原色后，毛玻璃底图（[drawFrost]）不再画；没有背景图（纯色主题）时只有亮边。
 *
 * 模糊图只在背景图换了时算一次：先缩到 1/8，再做三遍盒式模糊，画的时候双线性放大，
 * 每次按键不多做任何计算。
 */
class FrostedBackdrop {
    private var source: Bitmap? = null
    private var blurred: Bitmap? = null
    private val blurPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private var refractionImage: BitmapShader? = null
    private val refractionPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** 背景图左上角在键盘视图坐标里的位置，以及视图像素到图片像素的比例。 */
    var originX = 0f; private set
    var originY = 0f; private set
    var pxPerX = 1f; private set
    var pxPerY = 1f; private set
    var colorFilter: ColorFilter? = null
        set(value) {
            field = value
            blurPaint.colorFilter = value
            refractionPaint.colorFilter = value
        }

    val available: Boolean get() = source != null

    fun update(bitmap: Bitmap?, originX: Float, originY: Float, drawnWidth: Float, drawnHeight: Float) {
        if (bitmap == null || bitmap.isRecycled || drawnWidth <= 0f || drawnHeight <= 0f) {
            source = null; blurred = null; refractionImage = null
            return
        }
        if (bitmap !== source) {
            source = bitmap
            blurred = runCatching { blur(bitmap) }.getOrNull()
            refractionImage = null
            blurred?.let { blurPaint.shader = BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        }
        this.originX = originX; this.originY = originY
        pxPerX = bitmap.width / drawnWidth
        pxPerY = bitmap.height / drawnHeight
        val small = blurred ?: return
        matrix.reset()
        matrix.setScale(drawnWidth / small.width, drawnHeight / small.height)
        matrix.postTranslate(originX, originY)
        blurPaint.shader?.setLocalMatrix(matrix)
    }

    /** 在键帽形状里画模糊过的背景（当前画布坐标与键盘视图坐标一致）。 */
    fun drawFrost(canvas: Canvas, cap: RectF, radius: Float) {
        if (blurred == null) return
        canvas.drawRoundRect(cap, radius, radius, blurPaint)
    }

    /**
     * 图片背景的边缘折射：只在键帽外圈取样并弯折。原点已平移到键帽左上角，[capLeft]/[capTop] 是键帽在键盘视图里的位置。
     * 只在硬件加速的画布、Android 13 以上画。[alpha] 是整体不透明度（花花：键面不透明后画在键面上面，用它减淡）。
     */
    fun drawRefraction(canvas: Canvas, capLeft: Float, capTop: Float, w: Float, h: Float, radius: Float,
                       strength: LiquidGlassStrength, density: Float, alpha: Float = 1f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !canvas.isHardwareAccelerated) return
        val src = source ?: return
        drawRefraction33(canvas, src, capLeft, capTop, w, h, radius, strength, density, alpha)
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun drawRefraction33(canvas: Canvas, src: Bitmap, capLeft: Float, capTop: Float, w: Float, h: Float,
                                 radius: Float, strength: LiquidGlassStrength, density: Float, alpha: Float) {
        val shader = RefractionShader.shared ?: return
        val image = refractionImage ?: BitmapShader(src, Shader.TileMode.DECAL, Shader.TileMode.DECAL).also {
            it.filterMode = BitmapShader.FILTER_MODE_LINEAR
            refractionImage = it
        }
        RefractionShader.bind(shader, image, w, h, radius, strength.bevelDp * density, strength.bendDp * density,
            strength.spread, originX - capLeft, originY - capTop, pxPerX, pxPerY)
        refractionPaint.shader = shader
        refractionPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        canvas.drawRect(0f, 0f, w, h, refractionPaint)
        refractionPaint.shader = null
        refractionPaint.alpha = 255
    }

    private fun blur(bitmap: Bitmap): Bitmap {
        val w = max(1, bitmap.width / 8)
        val h = max(1, bitmap.height / 8)
        val small = Bitmap.createScaledBitmap(bitmap, w, h, true)
        val src = if (small.config == Bitmap.Config.ARGB_8888 && small.isMutable) small
            else small.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        repeat(3) {
            boxBlur(pixels, tmp, w, h, 3, horizontal = true)
            boxBlur(tmp, pixels, w, h, 3, horizontal = false)
        }
        src.setPixels(pixels, 0, w, 0, 0, w, h)
        return src
    }

    private fun boxBlur(input: IntArray, output: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val len = if (horizontal) w else h
        val div = 2 * r + 1
        for (line in 0 until lines) {
            var a = 0; var red = 0; var g = 0; var b = 0
            fun at(i: Int): Int {
                val c = i.coerceIn(0, len - 1)
                return if (horizontal) input[line * w + c] else input[c * w + line]
            }
            for (i in -r..r) { val p = at(i); a += p ushr 24; red += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff }
            for (i in 0 until len) {
                val idx = if (horizontal) line * w + i else i * w + line
                output[idx] = ((a / div) shl 24) or ((red / div) shl 16) or ((g / div) shl 8) or (b / div)
                val out = at(i - r); val inn = at(i + r + 1)
                a += (inn ushr 24) - (out ushr 24)
                red += ((inn shr 16) and 0xff) - ((out shr 16) and 0xff)
                g += ((inn shr 8) and 0xff) - ((out shr 8) and 0xff)
                b += (inn and 0xff) - (out and 0xff)
            }
        }
    }
}

/** 全进程只编译一次；各键依次设参数再画（每次改参数系统会生成新的着色器快照，共用安全）。 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private object RefractionShader {
    private const val SOURCE = """
        uniform shader image;
        uniform float2 iSize;
        uniform float radius;
        uniform float bevel;
        uniform float strength;
        uniform float spread;
        uniform float2 imageOrigin;
        uniform float2 pixelsPerUnit;
        half4 main(float2 p) {
            float2 c = iSize * 0.5;
            float r = min(radius, min(c.x, c.y));
            float2 rel = p - c;
            float2 q = abs(rel) - c + r;
            float d = length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            if (d > 0.5 || d <= -bevel) return half4(0.0);
            float2 g = (q.x > 0.0 && q.y > 0.0) ? normalize(q) : ((q.x > q.y) ? float2(1.0, 0.0) : float2(0.0, 1.0));
            float2 n = g * sign(rel);
            float e = 1.0 - clamp(-d / bevel, 0.0, 1.0);
            float2 uv = (p - imageOrigin) * pixelsPerUnit;
            float2 off = -n * (strength * e * e * e) * pixelsPerUnit;
            half4 red = image.eval(uv + off);
            half4 green = image.eval(uv + off * (1.0 + spread));
            half4 blue = image.eval(uv + off * (1.0 + 2.0 * spread));
            half coverage = min(red.a, min(green.a, blue.a));
            return half4(min(half3(red.r, green.g, blue.b), half3(coverage)), coverage)
                * half(e * clamp(0.5 - d, 0.0, 1.0));
        }
    """

    val shared: RuntimeShader? by lazy { try { RuntimeShader(SOURCE) } catch (_: Throwable) { null } }

    fun bind(shader: RuntimeShader, image: Shader, width: Float, height: Float, radius: Float, bevel: Float,
             strength: Float, spread: Float, left: Float, top: Float, pixelsPerX: Float, pixelsPerY: Float) {
        shader.setInputShader("image", image)
        shader.setFloatUniform("iSize", width, height)
        shader.setFloatUniform("radius", radius)
        shader.setFloatUniform("bevel", bevel)
        shader.setFloatUniform("strength", strength)
        shader.setFloatUniform("spread", spread)
        shader.setFloatUniform("imageOrigin", left, top)
        shader.setFloatUniform("pixelsPerUnit", pixelsPerX, pixelsPerY)
    }
}
