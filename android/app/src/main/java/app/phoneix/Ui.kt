package app.phoneix

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.LinearLayout

/** Phoneix'in açık mavi teması: renkler, cam kartlar, degrade düğmeler ve hareketli aurora arka planı. */
object Ui {
    /** Koyu tema: Store.theme ("dark" | "light" | "system"); her ekran oluşturulurken [init] ile belirlenir. */
    @JvmStatic var dark = true
        private set
    fun init(ctx: Context) {
        dark = when (Store(ctx).theme) {
            "light" -> false
            "system" -> (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            else -> true
        }
    }
    /** Pencere arka planı ve durum/gezinme çubuğu simgelerini temaya uydurur. */
    fun window(a: android.app.Activity) {
        a.window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(BG1))
        if (android.os.Build.VERSION.SDK_INT >= 30) a.window.insetsController?.setSystemBarsAppearance(
            if (dark) 0 else (android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS),
            android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
    }
    private fun c(light: Long, night: Long) = (if (dark) night else light).toInt()

    val INK get() = c(0xFF0B2A4A, 0xFFE8F2FF)
    val DIM get() = c(0xFF5B7A99, 0xFF8FA9C6)
    val A1 get() = c(0xFF2F8CFF, 0xFF4A9EFF)
    val A2 get() = c(0xFF5AC8FA, 0xFF5AC8FA)
    val OK get() = c(0xFF1FB97A, 0xFF34D399)
    val WARN get() = c(0xFFF5A524, 0xFFFBBF24)
    val BAD get() = c(0xFFE5484D, 0xFFFF6B70)
    val BG1 get() = c(0xFFEAF6FF, 0xFF0A1626)
    val BG2 get() = c(0xFFD3EAFF, 0xFF0F2342)
    val CARD get() = c(0xA6FFFFFF, 0xB3162842)
    val CARD_LINE get() = c(0xCCFFFFFF, 0x2EFFFFFF)
    val GLASS get() = c(0x99FFFFFF, 0x24FFFFFF)
    val GLASS2 get() = c(0xCCFFFFFF, 0x33FFFFFF)
    val LINE get() = c(0x332F8CFF, 0x33FFFFFF)
    val TRACK get() = c(0x222F8CFF, 0x33FFFFFF)

    fun dp(ctx: Context, v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()
    fun dpf(ctx: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun round(ctx: Context, color: Int, radiusDp: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(ctx, radiusDp.toFloat()); if (stroke != 0) setStroke(dp(ctx, 1), stroke)
    }

    private fun ripple(ctx: Context, content: GradientDrawable, rippleColor: Int, radiusDp: Int) =
        RippleDrawable(ColorStateList.valueOf(rippleColor), content, round(ctx, Color.WHITE, radiusDp))

    /** Basılınca hafifçe küçülüp geri yaylanan düğme. */
    fun pressable(v: View, scale: Float = 0.95f) {
        v.setOnTouchListener { view, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> view.animate().scaleX(scale).scaleY(scale).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(android.view.animation.OvershootInterpolator(2.2f)).start()
            }
            false
        }
    }

    private class UiButton(ctx: Context) : Button(ctx) {
        override fun setEnabled(enabled: Boolean) { super.setEnabled(enabled); alpha = if (enabled) 1f else 0.45f }
    }

    fun button(ctx: Context, text: String, primary: Boolean = false): Button = UiButton(ctx).apply {
        this.text = text; isAllCaps = false; textSize = 15f; stateListAnimator = null; elevation = 0f
        minHeight = dp(ctx, 48); minimumHeight = dp(ctx, 48)
        setPadding(dp(ctx, 16), 0, dp(ctx, 16), 0)
        if (primary) {
            setTextColor(Color.WHITE)
            val g = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(A1, A2)).apply { cornerRadius = dpf(ctx, 14f) }
            background = ripple(ctx, g, 0x55FFFFFF, 14)
        } else {
            setTextColor(INK)
            background = ripple(ctx, round(ctx, GLASS2, 14, LINE), LINE, 14)
        }
        pressable(this)
    }

    fun card(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        background = round(ctx, CARD, 20, CARD_LINE)
        setPadding(dp(ctx, 16), dp(ctx, 14), dp(ctx, 16), dp(ctx, 16))
        elevation = dpf(ctx, 3f)
    }

    /** Görünümleri sırayla aşağıdan yukarı süzülerek getirir. */
    fun enter(views: List<View>) {
        views.forEachIndexed { i, v ->
            v.alpha = 0f; v.translationY = 40f
            v.animate().alpha(1f).translationY(0f).setStartDelay(60L * i).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        }
    }
}

/** Yavaşça akan mavi ışık lekeleri: tüm ekranların arka planı. */
class AuroraView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint()
    private var t = 0f
    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 26000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private class Blob(val color: Int, val fx: Float, val fy: Float, val px: Float, val py: Float, val r: Float)
    private val blobs = if (Ui.dark) listOf(
        Blob(0xFF1D5FBF.toInt(), 0.2f, 0.15f, 0f, 0.3f, 0.55f),
        Blob(0xFF0E7490.toInt(), 0.85f, 0.4f, 1.7f, 0f, 0.5f),
        Blob(0xFF4338CA.toInt(), 0.45f, 0.95f, 3.1f, 1.2f, 0.6f)
    ) else listOf(
        Blob(0xFF7CC4FF.toInt(), 0.2f, 0.15f, 0f, 0.3f, 0.55f),
        Blob(0xFF9FE7FF.toInt(), 0.85f, 0.4f, 1.7f, 0f, 0.5f),
        Blob(0xFFA9B9FF.toInt(), 0.45f, 0.95f, 3.1f, 1.2f, 0.6f)
    )

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = android.graphics.LinearGradient(0f, 0f, w * 0.4f, h.toFloat(), Ui.BG1, Ui.BG2, Shader.TileMode.CLAMP)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); anim.start() }
    override fun onDetachedFromWindow() { anim.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(c: Canvas) {
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
        val m = maxOf(width, height).toFloat()
        val a = (t * 2 * Math.PI).toFloat()
        for (b in blobs) {
            val cx = width * b.fx + Math.sin((a + b.px).toDouble()).toFloat() * width * 0.18f
            val cy = height * b.fy + Math.cos((a * 1.3f + b.py).toDouble()).toFloat() * height * 0.14f
            val r = m * b.r
            p.shader = RadialGradient(cx, cy, r, intArrayOf((b.color and 0x00FFFFFF) or 0x99000000.toInt(), b.color and 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            c.drawCircle(cx, cy, r, p)
        }
    }
}
