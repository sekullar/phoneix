package app.phoneix

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.view.View
import android.view.animation.DecelerateInterpolator

private const val HIST = 60

/** Kayan çizgi grafik: alanı degradeyle doldurur. */
private fun drawSpark(c: Canvas, h: List<Float>, r: RectF, top: Float, color: Int, line: Paint, fill: Paint, path: Path, fpath: Path) {
    if (h.size < 2) return
    path.reset(); fpath.reset()
    val span = r.width() / (HIST - 1)
    val x0 = r.right - span * (h.size - 1)
    h.forEachIndexed { i, v ->
        val x = x0 + span * i
        val y = r.bottom - (v.coerceIn(0f, top) / top) * r.height()
        if (i == 0) { path.moveTo(x, y); fpath.moveTo(x, r.bottom); fpath.lineTo(x, y) }
        else { val px = x0 + span * (i - 1); val py = r.bottom - (h[i - 1].coerceIn(0f, top) / top) * r.height(); val mx = (px + x) / 2; path.cubicTo(mx, py, mx, y, x, y); fpath.cubicTo(mx, py, mx, y, x, y) }
    }
    fpath.lineTo(r.right, r.bottom); fpath.close()
    fill.shader = LinearGradient(0f, r.top, 0f, r.bottom, (color and 0x00FFFFFF) or 0x66000000, color and 0x00FFFFFF, Shader.TileMode.CLAMP)
    c.drawPath(fpath, fill); line.color = color; c.drawPath(path, line)
}

private fun heat(v: Float) = when { v >= 90f -> Ui.BAD; v >= 75f -> Ui.WARN; else -> Ui.A1 }

/** CPU / GPU için büyük halka göstergesi + altında geçmiş grafiği. */
class GaugeView(ctx: Context, private val title: String) : View(ctx) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = 0x222F8CFF }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dpf(ctx, 2f); strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val fpath = Path(); private val oval = RectF(); private val sp = RectF()
    private val hist = ArrayList<Float>()
    private var shown = 0f
    private var target = -1f
    var sub = ""; var sub2 = ""
    var cores: List<Int> = emptyList()
    private var anim: ValueAnimator? = null

    fun update(v: Float?, line1: String, line2: String = "") {
        sub = line1; sub2 = line2
        if (v == null) { target = -1f; invalidate(); return }
        target = v
        hist.add(v); if (hist.size > HIST) hist.removeAt(0)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(shown, v).apply {
            duration = 700; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start()
        }
    }

    /** Sığmayan metni sonunu "…" yaparak kısaltır (halkanın yanına taşmasın). */
    private fun fit(s: String, maxW: Float): String {
        val n = txt.breakText(s, true, maxW, null)
        return if (n >= s.length) s else s.take(maxOf(0, n - 1)) + "…"
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = Ui.dpf(context, 1f)
        track.color = Ui.TRACK
        txt.color = Ui.DIM; txt.textSize = 12f * d; txt.isFakeBoldText = true; txt.textAlign = Paint.Align.LEFT; txt.letterSpacing = 0.12f
        c.drawText(title.uppercase(), 0f, 12f * d, txt); txt.letterSpacing = 0f
        val sw = 13f * d
        track.strokeWidth = sw; arc.strokeWidth = sw
        // dikey düzen: başlık | halka | iki satır yazı (halkanın ALTINDA, çakışmasın) | geçmiş grafiği
        val sparkH = h * 0.26f
        val textH = 48f * d
        val top = 24f * d
        val dia = minOf(w * 0.68f, h - sparkH - textH - top - sw - 6f * d).coerceAtLeast(60f * d)
        val cx = w / 2; val cy = top + sw / 2 + dia / 2
        oval.set(cx - dia / 2, cy - dia / 2, cx + dia / 2, cy + dia / 2)
        c.drawArc(oval, 135f, 270f, false, track)
        val v = if (target < 0) 0f else shown
        val col = heat(v)
        arc.shader = SweepGradient(cx, cy, intArrayOf(Ui.A2, col, col), floatArrayOf(0f, 0.75f, 1f)).also { val m = android.graphics.Matrix(); m.postRotate(135f, cx, cy); it.setLocalMatrix(m) }
        c.drawArc(oval, 135f, 270f * (v / 100f).coerceIn(0f, 1f), false, arc)
        txt.color = Ui.INK; txt.textAlign = Paint.Align.CENTER; txt.isFakeBoldText = true; txt.textSize = dia * 0.27f
        c.drawText(if (target < 0) "—" else "%" + v.toInt(), cx, cy + dia * 0.1f, txt)
        txt.isFakeBoldText = false; txt.color = Ui.DIM; txt.textSize = 13f * d
        val y1 = cy + dia / 2 + sw / 2 + 20f * d
        c.drawText(fit(sub, w), cx, y1, txt)
        if (sub2.isNotEmpty()) { txt.textSize = 12f * d; c.drawText(fit(sub2, w), cx, y1 + 18f * d, txt) }
        sp.set(0f, maxOf(y1 + 30f * d, h - sparkH), w, h - 2f * d)
        if (cores.isNotEmpty()) { // çekirdek çubukları
            val bw = sp.width() / (cores.size * 1.5f); val gap = bw * 0.5f
            for ((i, cv) in cores.withIndex()) {
                val x = sp.left + i * (bw + gap); val bh = sp.height() * (cv / 100f).coerceIn(0.04f, 1f)
                fill.shader = null; fill.color = if (cv >= 90) Ui.BAD else if (cv >= 70) Ui.WARN else Ui.A1; fill.alpha = 200
                c.drawRoundRect(x, sp.bottom - bh, x + bw, sp.bottom, bw / 3, bw / 3, fill)
            }
        } else drawSpark(c, hist, sp, 100f, col, line, fill, path, fpath)
    }
}

/** Küçük istatistik kartı: başlık, büyük değer, alt satır, isteğe bağlı doluluk çubuğu ve/veya iki seri geçmiş grafiği. */
class StatView(ctx: Context, private val title: String, private val bar: Boolean = true) : View(ctx) {
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val back = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x222F8CFF }
    private val front = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = Ui.dpf(ctx, 1.6f); strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path(); private val fpath = Path(); private val r = RectF()
    var value = "—"; var sub = ""
    private var frac = 0f; private var shown = 0f
    private val a = ArrayList<Float>(); private val b = ArrayList<Float>()
    private var anim: ValueAnimator? = null

    fun update(value: String, sub: String, frac: Float? = null, s1: Float? = null, s2: Float? = null) {
        this.value = value; this.sub = sub
        if (s1 != null) { a.add(s1); if (a.size > HIST) a.removeAt(0) }
        if (s2 != null) { b.add(s2); if (b.size > HIST) b.removeAt(0) }
        if (frac != null) {
            val f = frac.coerceIn(0f, 1f)
            anim?.cancel()
            anim = ValueAnimator.ofFloat(shown, f).apply { duration = 700; interpolator = DecelerateInterpolator(1.6f); addUpdateListener { shown = it.animatedValue as Float; invalidate() }; start() }
            this.frac = f
        } else invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val d = Ui.dpf(context, 1f)
        back.color = Ui.TRACK
        txt.color = Ui.DIM; txt.textSize = 12f * d; txt.isFakeBoldText = true; txt.letterSpacing = 0.12f; txt.textAlign = Paint.Align.LEFT
        c.drawText(title.uppercase(), 0f, 12f * d, txt); txt.letterSpacing = 0f
        txt.color = Ui.INK; txt.textSize = 24f * d
        while (txt.measureText(value) > w && txt.textSize > 12f * d) txt.textSize -= d // dar kartta değer taşmasın
        c.drawText(value, 0f, 42f * d, txt)
        txt.isFakeBoldText = false; txt.color = Ui.DIM; txt.textSize = 12.5f * d
        val subFit = txt.breakText(sub, true, w, null).let { if (it >= sub.length) sub else sub.take(maxOf(0, it - 1)) + "…" }
        c.drawText(subFit, 0f, 62f * d, txt)
        val y0 = 74f * d
        if (bar) {
            r.set(0f, y0 + 4f * d, w, y0 + 11f * d); c.drawRoundRect(r, 4f * d, 4f * d, back)
            r.right = maxOf(r.left + 8f * d, w * shown); front.shader = LinearGradient(0f, 0f, w, 0f, Ui.A2, heat(shown * 100f), Shader.TileMode.CLAMP)
            c.drawRoundRect(r, 4f * d, 4f * d, front)
        }
        if (a.size > 3 && h - y0 > 24f * d) {
            r.set(0f, y0, w, h - 2f * d)
            val top = maxOf(1f, a.max(), if (b.isEmpty()) 0f else b.max())
            drawSpark(c, a, r, top, Ui.A1, line, fill, path, fpath)
            if (b.size > 1) drawSpark(c, b, r, top, Ui.OK, line, fill, path, fpath)
        }
    }
}

/** CPU ve GPU sıcaklığı: iki satır, her biri değer + renk değiştiren çubuk. CPU sıcaklığı yoksa kısa bir ipucu gösterir. */
class TempView(ctx: Context) : View(ctx) {
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG)
    private val back = Paint(Paint.ANTI_ALIAS_FLAG)
    private val front = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private var cpu: Float? = null; private var gpu: Float? = null
    private var sCpu = 0f; private var sGpu = 0f
    var hint = ""
    private var anim: ValueAnimator? = null

    fun update(cpu: Float?, gpu: Float?, hint: String = "") {
        this.cpu = cpu; this.gpu = gpu; this.hint = hint
        anim?.cancel()
        val fc = (cpu ?: 0f) / 100f; val fg = (gpu ?: 0f) / 100f; val c0 = sCpu; val g0 = sGpu
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { val t = it.animatedValue as Float; sCpu = c0 + (fc - c0) * t; sGpu = g0 + (fg - g0) * t; invalidate() }; start()
        }
    }

    private fun row(c: Canvas, y: Float, w: Float, d: Float, name: String, v: Float?, shown: Float) {
        txt.isFakeBoldText = false; txt.textAlign = Paint.Align.LEFT; txt.color = Ui.DIM; txt.textSize = 12.5f * d
        c.drawText(name, 0f, y, txt)
        txt.color = Ui.INK; txt.isFakeBoldText = true; txt.textSize = 20f * d; txt.textAlign = Paint.Align.RIGHT
        c.drawText(if (v == null) "—" else "${v.toInt()} °C", w, y + 1f * d, txt)
        back.color = Ui.TRACK
        r.set(0f, y + 8f * d, w, y + 14f * d); c.drawRoundRect(r, 3f * d, 3f * d, back)
        if (v != null) {
            r.right = maxOf(r.left + 6f * d, w * shown.coerceIn(0f, 1f))
            front.shader = LinearGradient(0f, 0f, w, 0f, Ui.A2, heat(shown * 100f * 1.1f), Shader.TileMode.CLAMP); c.drawRoundRect(r, 3f * d, 3f * d, front)
        }
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val d = Ui.dpf(context, 1f)
        txt.color = Ui.DIM; txt.textSize = 12f * d; txt.isFakeBoldText = true; txt.letterSpacing = 0.12f; txt.textAlign = Paint.Align.LEFT
        c.drawText("SICAKLIK", 0f, 12f * d, txt); txt.letterSpacing = 0f
        row(c, 44f * d, w, d, "CPU", cpu, sCpu)
        row(c, 84f * d, w, d, "GPU", gpu, sGpu)
        if (hint.isNotEmpty()) {
            txt.isFakeBoldText = false; txt.color = Ui.DIM; txt.textSize = 10.5f * d; txt.textAlign = Paint.Align.LEFT
            val n = txt.breakText(hint, true, w, null); c.drawText(if (n >= hint.length) hint else hint.take(maxOf(0, n - 1)) + "…", 0f, 108f * d, txt)
        }
    }
}
