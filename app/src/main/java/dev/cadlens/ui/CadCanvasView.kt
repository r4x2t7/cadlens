package dev.cadlens.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import dev.cadlens.model.CadScene
import dev.cadlens.model.CadText
import dev.cadlens.util.Aci
import kotlin.math.abs
import kotlin.math.min

/**
 * 画布：世界坐标里画折线，文字在屏幕坐标里画（保证任何缩放下都清晰）。
 *
 * 视图变换：screen = (world * s) 水平，垂直翻转（CAD 的 Y 向上，屏幕 Y 向下）。
 */
class CadCanvasView(context: Context) : View(context) {

    var scene: CadScene? = null
        set(value) {
            field = value
            rebuild()
            if (value != null && value.viewBounds.valid) fit()
            invalidate()
        }

    /** 搜索命中要高亮的文字。 */
    var highlight: List<CadText> = emptyList()
        set(value) { field = value; invalidate() }

    var measureMode = false
    var onMeasureText: ((String) -> Unit)? = null

    private var scale = 1f
    private var tx = 0f
    private var ty = 0f

    /** 最近一次“跳到某段文字”的目标，画布尺寸变化后要重新居中到它。 */
    private var anchor: CadText? = null

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0f          // 0 = 1px 细线，CAD 的正确观感
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD8DEE9.toInt()
    }
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0xFFFFD166.toInt()
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF6B6B.toInt() }

    private val pathsByLayer = HashMap<Int, MutableList<Path>>()
    private val colorsByLayer = HashMap<Int, MutableList<Int>>()

    private var mScale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val f = d.scaleFactor
            val ns = (scale * f).coerceIn(1e-6f, 1e7f)
            // 以手指位置为锚点缩放
            val fx = d.focusX
            val fy = d.focusY
            tx = fx - (fx - tx) * (ns / scale)
            ty = fy - (fy - ty) * (ns / scale)
            scale = ns
            anchor = null
            invalidate()
            return true
        }
    })

    private val mGesture = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            tx -= dx
            ty -= dy
            anchor = null
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            anchor = null
            fit()
            invalidate()
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (measureMode) pick(e.x, e.y)
            return true
        }
    })

    private var mPrev: FloatArray? = null

    private fun pick(sx: Float, sy: Float) {
        val w = screenToWorld(sx, sy) ?: return
        val p = mPrev
        if (p == null) {
            mPrev = floatArrayOf(w.first, w.second)
            invalidate()
            return
        }
        val d = Math.hypot((w.first - p[0]).toDouble(), (w.second - p[1]).toDouble())
        onMeasureText?.invoke(formatLength(d.toFloat()))
        mPrev = null
        invalidate()
    }

    private fun formatLength(v: Float): String {
        return if (v >= 1000f) String.format("%.1f m (%.0f mm)", v / 1000f, v)
        else String.format("%.1f mm", v)
    }

    private fun rebuild() {
        pathsByLayer.clear()
        colorsByLayer.clear()
        val s = scene ?: return
        for (cp in s.paths) {
            if (cp.pts.size < 4) continue
            val path = Path()
            path.moveTo(cp.pts[0], cp.pts[1])
            var i = 2
            while (i + 1 < cp.pts.size) {
                path.lineTo(cp.pts[i], cp.pts[i + 1])
                i += 2
            }
            if (cp.closed) path.close()
            val layerColor = s.layers.getOrNull(cp.layer)?.color ?: 0xFFE8E8E8.toInt()
            val col = if (cp.color != 0) cp.color else layerColor
            pathsByLayer.getOrPut(cp.layer) { ArrayList() }.add(path)
            colorsByLayer.getOrPut(cp.layer) { ArrayList() }.add(col)
        }
    }

    fun fit() {
        anchor = null
        val s = scene ?: return
        // 用剔除离群点后的范围，否则一个远处杂点就能把整张图缩成像素
        val b = s.viewBounds
        if (!b.valid || width == 0 || height == 0) return
        val w = (b.maxX - b.minX).coerceAtLeast(1e-6f)
        val h = (b.maxY - b.minY).coerceAtLeast(1e-6f)
        s.let {
            scale = min(width / w, height / h) * 0.92f
            val cx = (b.minX + b.maxX) / 2f
            val cy = (b.minY + b.maxY) / 2f
            tx = width / 2f - cx * scale
            ty = height / 2f + cy * scale
        }
    }

    fun screenToWorld(sx: Float, sy: Float): Pair<Float, Float>? {
        if (scale == 0f) return null
        return ((sx - tx) / scale) to ((ty - sy) / scale)
    }

    fun jumpTo(t: CadText) {
        anchor = t
        centerOn(t)
        highlight = listOf(t)
        invalidate()
    }

    /** 放大到文字能看清，并把它放到屏幕中心。 */
    private fun centerOn(t: CadText) {
        if (width == 0 || height == 0) return
        scale = (height / (t.height.coerceAtLeast(0.001f) * 40f)).coerceIn(1e-4f, 1e6f)
        tx = width / 2f - t.x * scale
        ty = height / 2f + t.y * scale
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val a = anchor
        if (a != null) centerOn(a) else if (scene?.viewBounds?.valid == true) fit()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        mScale.onTouchEvent(event)
        mGesture.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(0xFF0E1116.toInt())
        val s = scene ?: run {
            drawEmpty(canvas)
            return
        }
        if (scale == 0f) fit()

        canvas.save()
        canvas.translate(tx, ty)
        canvas.scale(scale, -scale)

        val b = s.bounds

        for ((layerIdx, paths) in pathsByLayer) {
            val layer = s.layers.getOrNull(layerIdx) ?: continue
            if (!layer.visible) continue
            val cols = colorsByLayer[layerIdx] ?: continue
            var curColor = Int.MIN_VALUE
            for (i in paths.indices) {
                val c = cols[i]
                if (c != curColor) { linePaint.color = c; curColor = c }
                canvas.drawPath(paths[i], linePaint)
            }
        }
        canvas.restore()

        // 文字：屏幕坐标，先剔除不可见
        val minPx = 5f
        val maxPx = height * 1.2f
        for (t in s.texts) {
            val layer = s.layers.getOrNull(t.layer) ?: continue
            if (!layer.visible) continue
            val sx = t.x * scale + tx
            val sy = ty - t.y * scale
            if (sx < -200 || sx > width + 400 || sy < -200 || sy > height + 200) continue
            val px = t.height * scale
            if (px < minPx || px > maxPx) continue
            textPaint.textSize = px
            textPaint.color = if (t.color != 0) t.color else layer.color
            if (abs(t.rotation) > 0.01f) {
                canvas.save()
                canvas.rotate(-t.rotation, sx, sy)
                canvas.drawText(t.content, sx, sy, textPaint)
                canvas.restore()
            } else {
                canvas.drawText(t.content, sx, sy, textPaint)
            }
        }

        // 搜索高亮
        for (t in highlight) {
            val sx = t.x * scale + tx
            val sy = ty - t.y * scale
            val w = t.content.length * t.height * scale * 0.62f
            val h = t.height * scale
            canvas.drawRect(sx - 2, sy - h, sx + w + 2, sy + h * 0.3f, hlPaint)
        }

        // 量测起点
        mPrev?.let { p ->
            val sx = p[0] * scale + tx
            val sy = ty - p[1] * scale
            canvas.drawCircle(sx, sy, 8f, markPaint)
        }

        if (b.isEmpty()) drawEmpty(canvas)
    }

    private fun drawEmpty(canvas: Canvas) {
        textPaint.textSize = 34f
        textPaint.color = 0xFF4A5568.toInt()
        canvas.drawText("打开一张 DXF / DWG", 40f, height / 2f - 10f, textPaint)
        textPaint.textSize = 28f
        canvas.drawText("DXF 直接出图 · DWG 先看结构信息", 40f, height / 2f + 34f, textPaint)
    }

    companion object {
        fun layerColor(scene: CadScene?, idx: Int): Int {
            val l = scene?.layers?.getOrNull(idx) ?: return 0xFFE8E8E8.toInt()
            return if (l.color == 0) Aci.argb(7) else l.color
        }
    }
}
