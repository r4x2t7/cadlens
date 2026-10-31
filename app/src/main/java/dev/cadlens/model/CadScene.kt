package dev.cadlens.model

import kotlin.math.max
import kotlin.math.min

/**
 * 一条可绘制的折线（圆、弧在读入时就细分成折线，渲染只有一条代码路径）。
 */
class CadPath(
    val pts: FloatArray,
    val closed: Boolean,
    val layer: Int,
    val color: Int,          // 0 = 随层
    val width: Float = 0f,
)

class CadText(
    val x: Float,
    val y: Float,
    val height: Float,
    val rotation: Float,
    val content: String,
    val layer: Int,
    val color: Int,
)

class CadLayer(
    val name: String,
    val color: Int,
    var visible: Boolean = true,
)

/** 图纸包围盒。 */
class Bounds {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE

    val valid: Boolean get() = minX <= maxX && minY <= maxY

    fun add(x: Float, y: Float) {
        if (x < minX) minX = x
        if (y < minY) minY = y
        if (x > maxX) maxX = x
        if (y > maxY) maxY = y
    }

    fun expand(x0: Float, y0: Float, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        add(x0, y0); add(x1, y1); add(x2, y2); add(x3, y3)
    }

    fun isEmpty(): Boolean = !valid

    fun width() = if (valid) max(0f, maxX - minX) else 0f
    fun height() = if (valid) max(0f, maxY - minY) else 0f

    fun union(o: Bounds): Bounds {
        if (!o.valid) return this
        val b = Bounds()
        b.minX = min(minX, o.minX); b.minY = min(minY, o.minY)
        b.maxX = max(maxX, o.maxX); b.maxY = max(maxY, o.maxY)
        return b
    }
}

/** 统一的分层编号：图层按名字去重，实体存索引，避免每个实体背一个字符串。 */
class LayerTable {
    private val map = HashMap<String, Int>()
    val layers = ArrayList<CadLayer>()

    fun index(name: String, color: Int = 0xFFE6E6E6.toInt()): Int {
        val key = name.ifEmpty { "0" }
        map[key]?.let { return it }
        val i = layers.size
        map[key] = i
        layers.add(CadLayer(key, color))
        return i
    }

    fun idOf(name: String): Int = index(name)
}

/**
 * 解析结果。渲染和搜索共用这一份数据。
 */
class CadScene(
    val paths: List<CadPath>,
    val texts: List<CadText>,
    val layers: List<CadLayer>,
    val bounds: Bounds,
    val title: String,
    val notes: List<String> = emptyList(),
    val path: String = "",
) {
    val vertexCount: Int = paths.sumOf { it.pts.size / 2 }

    /**
     * 用来“适配视图”的范围。
     *
     * 直接拿 [bounds] 会出事：真实图纸里常有跑到几百万单位外的杂点（解错的实体、
     * 孤立的标注定义点、老图残留），一整张图就缩成一个像素点了。这里按分位数
     * 收缩到绝大多数内容所在的那块，只有当收缩过头（装不下 85% 的点）时才退回全量。
     */
    val viewBounds: Bounds by lazy { robustBounds() }

    private fun robustBounds(): Bounds {
        val total = vertexCount + texts.size
        if (total == 0) return bounds

        val cap = 120_000
        val step = max(1, total / cap)
        val n = (total / step).coerceAtLeast(4)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        var k = 0
        var i = 0
        for (p in paths) {
            var j = 0
            while (j + 1 < p.pts.size && k < n) {
                if (i % step == 0) {
                    xs[k] = p.pts[j]
                    ys[k] = p.pts[j + 1]
                    k++
                }
                i++
                j += 2
            }
        }
        for (t in texts) {
            if (i % step == 0 && k < n) {
                xs[k] = t.x
                ys[k] = t.y
                k++
            }
            i++
        }
        if (k < 8) return bounds

        val sx = xs.copyOf(k).also { it.sort() }
        val sy = ys.copyOf(k).also { it.sort() }
        fun q(a: FloatArray, f: Float) = a[((a.size - 1) * f).toInt().coerceIn(0, a.size - 1)]

        val x0 = q(sx, 0.004f)
        val x1 = q(sx, 0.996f)
        val y0 = q(sy, 0.004f)
        val y1 = q(sy, 0.996f)
        if (!(x1 > x0) || !(y1 > y0)) return bounds

        var inside = 0
        for (j in 0 until k) {
            if (xs[j] in x0..x1 && ys[j] in y0..y1) inside++
        }
        // 分位窗口装不下大部分内容，说明图本来就散，别硬裁
        if (inside.toFloat() / k < 0.85f) return bounds

        val b = Bounds()
        b.add(x0, y0)
        b.add(x1, y1)
        return b
    }

    /** 去掉空白、全角转半角、转小写后匹配，避免“排烟 机房”搜不到“排烟机房”。 */
    fun search(query: String, limit: Int = 200): List<CadText> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        val out = ArrayList<CadText>()
        for (t in texts) {
            if (normalize(t.content).contains(q)) {
                out.add(t)
                if (out.size >= limit) break
            }
        }
        return out
    }

    companion object {
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s) {
                var c = ch
                // 全角 ASCII → 半角
                if (c in '\uFF01'..'\uFF5E') c = (c.code - 0xFEE0).toChar()
                if (c == '\u3000') c = ' '
                if (c.isWhitespace()) continue
                sb.append(c.lowercaseChar())
            }
            return sb.toString()
        }
    }
}
