package dev.cadlens.io

import dev.cadlens.model.Bounds
import dev.cadlens.model.CadPath
import dev.cadlens.model.CadScene
import dev.cadlens.model.CadText
import dev.cadlens.model.LayerTable
import dev.cadlens.util.Aci
import java.nio.charset.Charset
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * DXF 读取器（ASCII DXF，R12 起）。
 *
 * 只做“看图”需要的那部分：几何 + 图层 + 文字。不碰 DIMSTYLE 之类的样式细节——
 * 尺寸线在 DXF 里本身就是一组 LINE/SOLID 实体（挂在匿名块 *D1 里），
 * 把块展开画出来比自己算样式更准，也更省事。
 */
class DxfReader {

    private class Pair(val code: Int, val value: String)

    /** 块内实体的中间形态：坐标是块坐标系，图层名留到展开时再解析。 */
    private class Prim(
        val pts: FloatArray,
        val closed: Boolean,
        val color: Int,
        val layer: String = "0",
        val text: String? = null,
        val h: Float = 0f,
        val rot: Float = 0f,
    )

    private class BlockDef(val prims: MutableList<Prim>, val baseX: Float, val baseY: Float)

    private val blocks = HashMap<String, BlockDef>()
    private var current: MutableList<Prim>? = null

    private val layerTable = LayerTable()
    private val paths = ArrayList<CadPath>()
    private val texts = ArrayList<CadText>()
    private val bounds = Bounds()
    private val notes = ArrayList<String>()

    private var codepage = "GBK"

    // POLYLINE / VERTEX / SEQEND 的状态机
    private var accum: ArrayList<Float>? = null
    private var accumLayer = "0"
    private var accumColor = 0
    private var accumClosed = false
    private var accumBulges = ArrayList<Float>()
    private var accumTarget: MutableList<Prim>? = null

    fun read(bytes: ByteArray, title: String): CadScene {
        if (isBinaryDxf(bytes)) {
            throw UnsupportedOperationException(
                "这是二进制 DXF（Binary DXF）。开源这边暂时只解 ASCII DXF，" +
                    "请在 CAD 里另存为“AutoCAD 2004/2007 DXF (ASCII)”再打开。"
            )
        }
        codepage = sniffCodepage(bytes)
        val text = String(bytes, Charset.forName(codepage))
        val pairs = lex(text)

        var i = 0
        var inBlocks = false
        var blockName: String? = null
        var blockBaseX = 0f
        var blockBaseY = 0f
        var layerName = "0"
        var color = 0
        var pending: MutableList<Pair> = ArrayList()
        var currentEntity = ""

        fun flush() {
            parseEntity(currentEntity, pending, layerName, color, if (inBlocks) current else null)
            pending = ArrayList()
            currentEntity = ""
        }

        while (i < pairs.size) {
            val p = pairs[i]
            if (p.code != 0) {
                if (currentEntity.isNotEmpty()) {
                    if (p.code == 8) layerName = p.value.trim()
                    if (p.code == 62) color = p.value.trim().toIntOrNull() ?: 0
                    pending.add(p)
                }
                i++
                continue
            }

            val v = p.value.trim().uppercase()
            if (currentEntity.isNotEmpty()) flush()
            when (v) {
                "SECTION" -> {
                    i++
                    if (i < pairs.size && pairs[i].code == 2) i++
                }
                "ENDSEC" -> { inBlocks = false; i++ }
                "TABLE", "ENDTAB" -> {
                    // 表的标题行（0/TABLE, 2/LAYER）不需要单独处理，LAYER 记录自己带 0/LAYER
                    i++
                    if (i < pairs.size && pairs[i].code == 2 && v == "TABLE") i++
                }
                "BLOCK" -> {
                    inBlocks = true
                    blockName = null
                    blockBaseX = 0f; blockBaseY = 0f
                    current = ArrayList()
                    i++
                    while (i < pairs.size && pairs[i].code != 0) {
                        when (pairs[i].code) {
                            2 -> blockName = pairs[i].value.trim()
                            10 -> blockBaseX = pairs[i].value.trim().toFloatOrNull() ?: 0f
                            20 -> blockBaseY = pairs[i].value.trim().toFloatOrNull() ?: 0f
                        }
                        i++
                    }
                }
                "ENDBLK" -> {
                    val name = blockName
                    val prims = current
                    if (name != null && prims != null) {
                        blocks[name] = BlockDef(prims, blockBaseX, blockBaseY)
                    }
                    current = null
                    inBlocks = false
                    i++
                }
                "LAYER" -> {
                    var nm = ""
                    var c = 7
                    i++
                    while (i < pairs.size && pairs[i].code != 0) {
                        when (pairs[i].code) {
                            2 -> nm = pairs[i].value.trim()
                            62 -> c = pairs[i].value.trim().toIntOrNull() ?: 7
                        }
                        i++
                    }
                    if (nm.isNotEmpty()) layerTable.index(nm, Aci.argb(if (c < 0) 7 else c))
                }
                "ENTITIES" -> i++
                "EOF" -> break
                else -> {
                    currentEntity = v
                    layerName = "0"
                    color = 0
                    i++
                }
            }
        }
        if (currentEntity.isNotEmpty()) flush()

        notes.add(0, "编码 $codepage")
        return CadScene(
            paths = paths,
            texts = texts,
            layers = layerTable.layers,
            bounds = bounds,
            title = title,
            notes = notes,
            path = "",
        )
    }

    // ------------------------------------------------------------------ 实体

    private fun parseEntity(
        type: String,
        ps: MutableList<Pair>,
        layerName: String,
        colorIdx: Int,
        blockTarget: MutableList<Prim>?,
    ) {
        if (type.isEmpty()) return
        val color = if (colorIdx != 0 && colorIdx != 256) Aci.argb(if (colorIdx < 0) 7 else colorIdx) else 0

        fun f(code: Int, def: Float = 0f): Float =
            ps.firstOrNull { it.code == code }?.value?.trim()?.toFloatOrNull() ?: def

        fun fAll(code: Int): List<Float> =
            ps.filter { it.code == code }.mapNotNull { it.value.trim().toFloatOrNull() }

        fun s(code: Int): String? = ps.firstOrNull { it.code == code }?.value

        val emit: (Prim) -> Unit = { p ->
            if (blockTarget != null) blockTarget.add(p) else emitPrim(p, layerName)
        }

        when (type) {
            "LINE" -> emit(
                Prim(floatArrayOf(f(10), f(20), f(11), f(21)), false, color, layerName)
            )

            "CIRCLE" -> {
                val cx = f(10); val cy = f(20); val r = f(40)
                if (r > 0f) emit(Prim(circle(cx, cy, r, 0f, 360f), true, color, layerName))
            }

            "ARC" -> {
                val cx = f(10); val cy = f(20); val r = f(40)
                if (r > 0f) emit(Prim(circle(cx, cy, r, f(50), f(51)), false, color, layerName))
            }

            "ELLIPSE" -> {
                val cx = f(10); val cy = f(20)
                val mx = f(11); val my = f(21)
                val ratio = if (f(40, 1f) == 0f) 1f else f(40, 1f)
                val t0 = f(41); val t1 = f(42, (Math.PI * 2).toFloat())
                emit(Prim(ellipse(cx, cy, mx, my, ratio, t0, t1), false, color, layerName))
            }

            "LWPOLYLINE" -> {
                val xs = fAll(10)
                val ys = fAll(20)
                val bulges = ArrayList<Float>()
                for (q in ps) if (q.code == 42) bulges.add(q.value.trim().toFloatOrNull() ?: 0f)
                val closed = (f(70).toInt() and 1) != 0
                val pts = polyline(xs, ys, bulges, closed)
                if (pts.size >= 4) emit(Prim(pts, closed, color, layerName))
                emitWidthSegments(ps, xs, ys, color, layerName)
            }

            "POLYLINE" -> {
                accum = ArrayList()
                accumLayer = layerName
                accumColor = color
                accumClosed = (f(70).toInt() and 1) != 0
                accumTarget = blockTarget
                accumBulges = ArrayList()
            }

            "VERTEX" -> {
                val a = accum
                if (a != null) {
                    a.add(f(10)); a.add(f(20))
                    accumBulges.add(if (ps.any { it.code == 42 }) f(42) else 0f)
                }
            }

            "SEQEND" -> {
                val a = accum
                if (a != null && a.size >= 4) {
                    val xs = ArrayList<Float>()
                    val ys = ArrayList<Float>()
                    var k = 0
                    while (k + 1 < a.size) { xs.add(a[k]); ys.add(a[k + 1]); k += 2 }
                    val pts = polyline(xs, ys, accumBulges, accumClosed)
                    if (pts.size >= 4) {
                        val prim = Prim(pts, accumClosed, accumColor, accumLayer)
                        if (accumTarget != null) accumTarget!!.add(prim) else emitPrim(prim, accumLayer)
                    }
                }
                accum = null
            }

            "SOLID", "TRACE", "3DFACE" -> {
                val x = floatArrayOf(f(10), f(11), f(12), f(13))
                val y = floatArrayOf(f(20), f(21), f(22), f(23))
                val has4 = ps.any { it.code == 13 }
                val pts = if (has4) {
                    floatArrayOf(x[0], y[0], x[1], y[1], x[2], y[2], x[3], y[3])
                } else {
                    // SOLID 的第 3、4 点是“之”字形，按 1-2-4-3 连才是正确的四边形
                    floatArrayOf(x[0], y[0], x[1], y[1], x[3], y[3], x[2], y[2])
                }
                emit(Prim(pts, true, color, layerName))
            }

            "POINT" -> emit(Prim(floatArrayOf(f(10), f(20)), false, color, layerName))

            "TEXT" -> {
                val content = decodeDxfText(s(1) ?: "")
                if (content.isNotBlank()) {
                    emit(Prim(floatArrayOf(f(10), f(20)), false, color, layerName,
                        content, f(40, 2.5f), f(50)))
                }
            }

            "MTEXT" -> {
                val sb = StringBuilder()
                for (q in ps) if (q.code == 3 || q.code == 1) sb.append(q.value)
                val content = stripMText(sb.toString())
                if (content.isNotBlank()) {
                    emit(Prim(floatArrayOf(f(10), f(20)), false, color, layerName,
                        content, f(40, 2.5f), f(50)))
                }
            }

            "ATTRIB" -> {
                // 块的属性值：位置是 WCS，直接按文字画
                val content = decodeDxfText(s(1) ?: "")
                if (content.isNotBlank()) {
                    emit(Prim(floatArrayOf(f(10), f(20)), false, color, layerName,
                        content, f(40, 2.5f), f(50)))
                }
            }

            "INSERT", "MINSERT" -> expandBlock(s(2)?.trim(), layerName, color, f(10), f(20),
                f(41, 1f), f(42, 1f), f(50), blockTarget)

            // 尺寸标注的可见部分就是一个匿名块（*D1…），块本身就在 WCS 里，直接摊平
            "DIMENSION" -> expandBlock(s(2)?.trim(), layerName, color, 0f, 0f, 1f, 1f, 0f,
                blockTarget, identity = true)

            // 样条曲线：拿控制点连折线，比什么都不画强
            "SPLINE" -> {
                val xs = fAll(10)
                val ys = fAll(20)
                val pts = polyline(xs, ys, emptyList(), false)
                if (pts.size >= 4) emit(Prim(pts, false, color, layerName))
            }

            "LEADER" -> {
                val xs = fAll(10)
                val ys = fAll(20)
                val pts = polyline(xs, ys, emptyList(), false)
                if (pts.size >= 4) emit(Prim(pts, false, color, layerName))
            }
        }
    }

    private fun expandBlock(
        name: String?,
        layerName: String,
        color: Int,
        bx: Float,
        by: Float,
        sx: Float,
        sy: Float,
        rotDeg: Float,
        blockTarget: MutableList<Prim>?,
        identity: Boolean = false,
    ) {
        if (name.isNullOrEmpty()) return
        val def = blocks[name] ?: return
        for (p in def.prims) {
            val moved = if (identity) {
                p
            } else {
                transform(p, def.baseX, def.baseY, bx, by, sx, sy, rotDeg)
            }
            val layer = if (moved.layer == "0") layerName else moved.layer
            val c = if (moved.color == 0) color else moved.color
            val q = Prim(moved.pts, moved.closed, c, layer, moved.text, moved.h, moved.rot)
            if (blockTarget != null) blockTarget.add(q) else emitPrim(q, layer)
        }
    }

    private fun emitPrim(p: Prim, fallbackLayer: String) {
        val layer = layerTable.index(if (p.layer == "0") fallbackLayer else p.layer, Aci.argb(7))
        if (p.text != null) {
            texts.add(CadText(p.pts[0], p.pts[1], if (p.h > 0) p.h else 2.5f, p.rot, p.text, layer, p.color))
            bounds.add(p.pts[0], p.pts[1])
        } else {
            paths.add(CadPath(p.pts, p.closed, layer, p.color))
            var i = 0
            while (i + 1 < p.pts.size) { bounds.add(p.pts[i], p.pts[i + 1]); i += 2 }
        }
    }

    /** 有宽度的多段线画成两侧边线，墙线才看得见厚度。 */
    private fun emitWidthSegments(
        ps: MutableList<Pair>, xs: List<Float>, ys: List<Float>, color: Int, layerName: String,
    ) {
        val widths = ps.filter { it.code == 43 }.mapNotNull { it.value.trim().toFloatOrNull() }
        if (widths.isEmpty()) return
        val hw = widths.max() / 2f
        if (hw <= 0f) return
        val n = minOf(xs.size, ys.size)
        if (n < 2) return
        val out = FloatArray(n * 4)
        for (k in 0 until n) {
            val k0 = if (k == 0) 0 else k - 1
            val k1 = if (k == n - 1) n - 1 else k + 1
            var dx = xs[k1] - xs[k0]
            var dy = ys[k1] - ys[k0]
            val len = hypot(dx, dy)
            if (len < 1e-6f) { dx = 1f; dy = 0f }
            val nx = -dy / maxOf(hypot(dx, dy), 1e-6f) * hw
            val ny = dx / maxOf(hypot(dx, dy), 1e-6f) * hw
            out[k * 4] = xs[k] + nx
            out[k * 4 + 1] = ys[k] + ny
            out[k * 4 + 2] = xs[k] - nx
            out[k * 4 + 3] = ys[k] - ny
        }
        // 两侧边线
        for (side in 0..1) {
            val line = ArrayList<Float>(n * 2)
            for (k in 0 until n) {
                line.add(out[k * 4 + side * 2]); line.add(out[k * 4 + side * 2 + 1])
            }
            val prim = Prim(line.toFloatArray(), false, color, layerName)
            emitPrim(prim, layerName)
        }
    }

    private fun transform(
        p: Prim, baseX: Float, baseY: Float, bx: Float, by: Float,
        sx: Float, sy: Float, rotDeg: Float,
    ): Prim {
        val r = Math.toRadians(rotDeg.toDouble())
        val cs = cos(r).toFloat(); val sn = sin(r).toFloat()
        val out = FloatArray(p.pts.size)
        var i = 0
        while (i + 1 < p.pts.size) {
            val x = (p.pts[i] - baseX) * sx
            val y = (p.pts[i + 1] - baseY) * sy
            out[i] = bx + x * cs - y * sn
            out[i + 1] = by + x * sn + y * cs
            i += 2
        }
        return Prim(out, p.closed, p.color, p.layer, p.text,
            p.h * abs(sy).coerceAtLeast(0.0001f), p.rot + rotDeg)
    }

    // ------------------------------------------------------------------ 几何

    private fun circle(cx: Float, cy: Float, r: Float, startDeg: Float, endDeg: Float): FloatArray {
        var sweep = endDeg - startDeg
        if (sweep <= 0f) sweep += 360f
        val seg = segments(sweep, r)
        val out = FloatArray((seg + 1) * 2)
        for (k in 0..seg) {
            val a = Math.toRadians((startDeg + sweep * k / seg).toDouble())
            out[k * 2] = cx + (r * cos(a)).toFloat()
            out[k * 2 + 1] = cy + (r * sin(a)).toFloat()
        }
        return out
    }

    private fun ellipse(
        cx: Float, cy: Float, mx: Float, my: Float, ratio: Float, t0: Float, t1: Float,
    ): FloatArray {
        var sweep = t1 - t0
        if (sweep <= 0f) sweep += (Math.PI * 2).toFloat()
        val major = hypot(mx, my)
        val minor = major * ratio
        val rot = atan2(my, mx)
        val seg = segments(Math.toDegrees(sweep.toDouble()).toFloat(), major)
        val out = FloatArray((seg + 1) * 2)
        for (k in 0..seg) {
            val t = t0 + sweep * k / seg
            val px = major * cos(t.toDouble())
            val py = minor * sin(t.toDouble())
            out[k * 2] = cx + (px * cos(rot.toDouble()) - py * sin(rot.toDouble())).toFloat()
            out[k * 2 + 1] = cy + (px * sin(rot.toDouble()) + py * cos(rot.toDouble())).toFloat()
        }
        return out
    }

    /** 采样密度：弦高误差控制在 0.02 个绘图单位以内，同时限制上限避免大弧爆点数。 */
    private fun segments(sweepDeg: Float, r: Float): Int {
        val rr = abs(r).coerceAtLeast(0.001f)
        val est = (Math.toRadians(sweepDeg.toDouble()) / Math.sqrt(8.0 * 0.02 / rr)).toInt() + 2
        return est.coerceIn(6, 128)
    }

    private fun polyline(
        xs: List<Float>, ys: List<Float>, bulges: List<Float>, closed: Boolean,
    ): FloatArray {
        val n = minOf(xs.size, ys.size)
        if (n == 0) return FloatArray(0)
        if (n == 1) return floatArrayOf(xs[0], ys[0])
        val out = ArrayList<Float>(n * 2)

        fun addArc(x1: Float, y1: Float, x2: Float, y2: Float, bulge: Float, skipFirst: Boolean) {
            val arc = bulgeArc(x1, y1, x2, y2, bulge)
            var i = if (skipFirst) 2 else 0
            while (i + 1 < arc.size) { out.add(arc[i]); out.add(arc[i + 1]); i += 2 }
        }

        for (k in 0 until n - 1) {
            val b = bulges.getOrElse(k) { 0f }
            if (abs(b) < 1e-6f) {
                if (out.isEmpty()) { out.add(xs[k]); out.add(ys[k]) }
                out.add(xs[k + 1]); out.add(ys[k + 1])
            } else {
                addArc(xs[k], ys[k], xs[k + 1], ys[k + 1], b, out.isNotEmpty())
            }
        }
        if (closed) {
            val b = bulges.getOrElse(n - 1) { 0f }
            if (abs(b) < 1e-6f) {
                out.add(xs[0]); out.add(ys[0])
            } else {
                addArc(xs[n - 1], ys[n - 1], xs[0], ys[0], b, true)
            }
        }
        return out.toFloatArray()
    }

    /** bulge = tan(圆心角/4)，正值为逆时针。 */
    private fun bulgeArc(x1: Float, y1: Float, x2: Float, y2: Float, bulge: Float): FloatArray {
        val theta = 4.0 * atan(bulge.toDouble())
        val dx = (x2 - x1).toDouble()
        val dy = (y2 - y1).toDouble()
        val c = hypot(dx, dy)
        if (c < 1e-9) return floatArrayOf(x1, y1)
        val s = sin(theta / 2.0)
        if (abs(s) < 1e-9) return floatArrayOf(x1, y1, x2, y2)
        val r = c / (2.0 * s)
        val mx = (x1 + x2) / 2.0
        val my = (y1 + y2) / 2.0
        val h = r * cos(theta / 2.0)
        val cx = mx + (-dy / c) * h
        val cy = my + (dx / c) * h
        val a0 = atan2(y1 - cy, x1 - cx)
        val seg = segments(Math.toDegrees(abs(theta)).toFloat(), r.toFloat())
        val out = FloatArray((seg + 1) * 2)
        for (k in 0..seg) {
            val a = a0 + theta * k / seg
            out[k * 2] = (cx + r * cos(a)).toFloat()
            out[k * 2 + 1] = (cy + r * sin(a)).toFloat()
        }
        return out
    }

    // ------------------------------------------------------------------ 文本

    /** DXF 的 %%d %%c %%p %%u 等控制码。 */
    internal fun decodeDxfText(s: String): String {
        if (!s.contains("%%")) return s.trim()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            if (i + 2 < s.length && s[i] == '%' && s[i + 1] == '%') {
                when (s[i + 2].lowercaseChar()) {
                    'd' -> { sb.append('°'); i += 3; continue }
                    'c' -> { sb.append('Ø'); i += 3; continue }
                    'p' -> { sb.append('±'); i += 3; continue }
                    '%' -> { sb.append('%'); i += 3; continue }
                    'u', 'o' -> { i += 3; continue }
                }
            }
            sb.append(s[i]); i++
        }
        return sb.toString().trim()
    }

    /** MTEXT 内联格式码：\P 换行、\fSimSun|b0; 字体、{\...} 分组、\H2.5; 字高。 */
    internal fun stripMText(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'P', 'p' -> { sb.append(' '); i += 2 }
                    '~' -> { sb.append(' '); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '{', '}' -> { i += 2 }
                    'f', 'F', 'H', 'h', 'W', 'w', 'Q', 'q', 'A', 'a', 'C', 'c', 'T', 't', 'p' -> {
                        i += 2
                        while (i < s.length && s[i] != ';') i++
                        if (i < s.length) i++
                    }
                    // \U+00B0 这类 Unicode 转义，图里出现特殊符号时很常见
                    'U', 'u' -> {
                        if (i + 6 < s.length && s[i + 2] == '+') {
                            val cp = s.substring(i + 3, i + 7).toIntOrNull(16)
                            if (cp != null) { sb.append(cp.toChar()); i += 7 }
                            else { sb.append(s, i, i + 2); i += 2 }
                        } else { i += 2 }
                    }
                    else -> { i += 2 }
                }
            } else if (c == '{' || c == '}') {
                i++
            } else {
                sb.append(c); i++
            }
        }
        // MTEXT 里同样允许 %%c %%d 这类控制码
        return decodeDxfText(sb.toString().replace("\n", " "))
    }

    // ------------------------------------------------------------------ 词法

    private fun isBinaryDxf(b: ByteArray): Boolean {
        val sig = "AutoCAD Binary DXF"
        if (b.size < sig.length) return false
        for (i in sig.indices) if (b[i] != sig[i].code.toByte()) return false
        return true
    }

    internal fun sniffCodepage(bytes: ByteArray): String {
        val key = "\$DWGCODEPAGE".toByteArray()
        outer@ for (i in 0..(bytes.size - key.size - 4)) {
            var ok = true
            for (k in key.indices) if (bytes[i + k] != key[k]) { ok = false; break }
            if (!ok) continue
            // $DWGCODEPAGE 后面是“组码行 + 值行”，把两行一起看，省得猜格式
            var p = i + key.size
            while (p < bytes.size && bytes[p] != '\n'.code.toByte()) p++
            val lines = ArrayList<String>(2)
            val cur = StringBuilder()
            while (p < bytes.size && lines.size < 2) {
                val b = bytes[p]
                if (b == '\n'.code.toByte() || b == '\r'.code.toByte()) {
                    if (cur.isNotEmpty()) { lines.add(cur.toString()); cur.setLength(0) }
                } else {
                    cur.append(b.toInt().toChar())
                }
                p++
            }
            val v = lines.joinToString(" ").trim().uppercase()
            return when {
                v.contains("936") -> "GBK"
                v.contains("950") -> "Big5"
                v.contains("932") -> "Shift_JIS"
                v.contains("949") -> "EUC-KR"
                v.contains("1251") -> "windows-1251"
                v.contains("UTF") -> "UTF-8"
                v.contains("1252") || v.contains("1250") || v.contains("ANSI") -> "windows-1252"
                else -> "GBK"
            }
        }
        return "GBK"
    }

    private fun lex(text: String): List<Pair> {
        val out = ArrayList<Pair>(text.length / 8)
        var i = 0
        val n = text.length

        // 只吃“一个”换行：值为空的行在 DXF 里就是一个空行，多跳一个就把下一行的组码当成了值。
        fun eatEol() {
            if (i < n && text[i] == '\r') i++
            if (i < n && text[i] == '\n') i++
        }

        fun readLine(): String {
            val start = i
            while (i < n && text[i] != '\n' && text[i] != '\r') i++
            val line = text.substring(start, i)
            eatEol()
            return line
        }

        while (i < n) {
            val codeStr = readLine().trim()
            if (codeStr.isEmpty()) continue
            val code = codeStr.toIntOrNull() ?: continue
            out.add(Pair(code, readLine()))
        }
        return out
    }
}
