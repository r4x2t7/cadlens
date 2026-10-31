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
