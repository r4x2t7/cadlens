package dev.cadlens.io

import dev.cadlens.model.CadScene
import java.nio.charset.Charset
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DXF 解析的回归测试。
 *
 * 用例不是“随便画点东西”——每条断言都对应一个真实图纸里会踩到的东西：
 * 中文字体编码、块基准点、INSERT 的旋转缩放、bulge 圆弧、%%c 控制码、MTEXT 换行。
 */
class DxfReaderTest {

    private fun build(lines: List<Any>): String {
        val sb = StringBuilder()
        var i = 0
        while (i + 1 < lines.size) {
            sb.append(lines[i]).append('\n').append(lines[i + 1]).append('\n')
            i += 2
        }
        return sb.toString()
    }

    private fun scene(dxf: String): CadScene =
        DxfReader().read(dxf.toByteArray(Charset.forName("GBK")), "test.dxf")

    private val header = listOf(
        0, "SECTION", 2, "HEADER",
        9, "\$DWGCODEPAGE", 3, "ANSI_936",
        9, "\$INSUNITS", 70, "4",
        0, "ENDSEC",
    )

    private val tables = listOf(
        0, "SECTION", 2, "TABLES",
        0, "TABLE", 2, "LAYER",
        0, "LAYER", 2, "0", 70, "0", 62, "7", 6, "CONTINUOUS",
        0, "LAYER", 2, "墙", 70, "0", 62, "1", 6, "CONTINUOUS",
        0, "ENDTAB",
        0, "ENDSEC",
    )

    /** 匿名尺寸块：一条线 + 一行文字，基准点 (10,20)。 */
    private val blocks = listOf(
        0, "SECTION", 2, "BLOCKS",
        0, "BLOCK", 8, "0", 2, "*D1", 70, "1", 10, "10.0", 20, "20.0", 30, "0.0",
        0, "LINE", 8, "0", 10, "10.0", 20, "20.0", 11, "30.0", 21, "20.0",
        0, "TEXT", 8, "0", 10, "10.0", 20, "22.0", 40, "2.5", 1, "尺寸文字",
        0, "ENDBLK", 8, "0",
        0, "ENDSEC",
    )

    private val entities = listOf(
        0, "SECTION", 2, "ENTITIES",

        0, "LINE", 8, "墙", 10, "0.0", 20, "0.0", 11, "100.0", 21, "0.0",

        0, "CIRCLE", 8, "0", 10, "50.0", 20, "50.0", 40, "10.0",

        0, "ARC", 8, "0", 10, "0.0", 20, "0.0", 40, "20.0", 50, "0.0", 51, "90.0",

        // 正方形，第一段 bulge=1 → 逆时针 180° 半圆，弧顶在弦的右侧 (5,-5)
        0, "LWPOLYLINE", 8, "0", 90, "4", 70, "1",
        10, "0.0", 20, "0.0", 42, "1.0",
        10, "10.0", 20, "0.0",
        10, "10.0", 20, "10.0",
        10, "0.0", 20, "10.0",

        0, "TEXT", 8, "0", 10, "5.0", 20, "5.0", 40, "3.5", 1, "%%c20 排烟机房",

        0, "MTEXT", 8, "0", 10, "5.0", 20, "8.0", 40, "2.5",
        3, "第一行\\P第二行{\\fSimSun|b0;}", 1, "",

        // 块基准点 (10,20) → 局部 (0,0)-(20,0) → 放大 2 → 旋转 90° → 移到 (100,200)
        0, "INSERT", 8, "0", 2, "*D1", 10, "100.0", 20, "200.0", 41, "2.0", 42, "2.0", 50, "90.0",

        // 尺寸标注：块本身在 WCS 里，不做变换
        0, "DIMENSION", 8, "0", 2, "*D1", 70, "1", 10, "0.0", 20, "0.0",

        0, "ATTRIB", 8, "0", 10, "60.0", 20, "0.0", 40, "2.5", 1, "M-1",

        0, "POLYLINE", 8, "0", 66, "1", 70, "0",
        0, "VERTEX", 8, "0", 10, "0.0", 20, "30.0",
        0, "VERTEX", 8, "0", 10, "10.0", 20, "40.0",
        0, "VERTEX", 8, "0", 10, "20.0", 20, "30.0",
        0, "SEQEND", 8, "0",

        0, "ENDSEC",
        0, "EOF",
    )

    private fun full(): CadScene = scene(build(header + tables + blocks + entities))

    @Test
    fun parsesBasicEntities() {
        val s = full()
        assertTrue("应有图形", s.paths.size >= 5)
        // 第一条线原样保留
        val line = s.paths.first()
        assertEquals(4, line.pts.size)
        assertEquals(0f, line.pts[0], 1e-3f)
        assertEquals(100f, line.pts[2], 1e-3f)
    }

    @Test
    fun circleAndArcAreTessellated() {
        val s = full()
        // 圆：半径 10，起点在 (60,50)
        val circ = s.paths.firstOrNull { it.closed && it.pts.size >= 14 && abs(it.pts[0] - 60f) < 0.01f }
        assertNotNull("应解析出圆", circ)
        // 弧：从 (20,0) 逆时针到 (0,20)，终点应落在 (0,20)
        val arc = s.paths.firstOrNull {
            abs(it.pts[0] - 20f) < 0.01f && abs(it.pts[1]) < 0.01f
        }
        assertNotNull("应解析出弧", arc)
        val n = arc!!.pts.size
        assertEquals(0f, arc.pts[n - 2], 0.01f)
        assertEquals(20f, arc.pts[n - 1], 0.01f)
    }

    @Test
    fun bulgeBecomesArc() {
        val s = full()
        // 第一段 (0,0)→(10,0) 带 bulge=1：逆时针 180° 半圆，半径 5，弧顶在弦右侧 (5,-5)
        val bulged = s.paths.firstOrNull { p ->
            p.pts.size > 20 && abs(p.pts[0]) < 0.01f && abs(p.pts[1]) < 0.01f
        }
        assertNotNull("带 bulge 的多段线应被细分成折线", bulged)
        var minY = 0f
        var i = 0
        while (i + 1 < bulged!!.pts.size) {
            minY = minOf(minY, bulged.pts[i + 1])
            i += 2
        }
        // 方向反了的话弧会往上鼓，minY 就停在 0，所以这一条同时验证了方向
        assertEquals("半圆应下探一个半径", -5f, minY, 0.15f)
    }

    @Test
    fun textCodesAndEncodings() {
        val s = full()
        val t = s.texts.firstOrNull { it.content.contains("排烟机房") }
        assertNotNull("GBK 中文应正确解码", t)
        assertTrue("%%c 应转成直径符号: ${t!!.content}", t.content.startsWith("Ø20"))
        assertEquals(3.5f, t.height, 1e-3f)

        val m = s.texts.firstOrNull { it.content.contains("第一行") }
        assertNotNull("MTEXT 应解析出文字", m)
        assertTrue("\\P 应转成空格: ${m!!.content}", m.content.contains("第二行"))
        assertTrue("字体格式码应被剥离: ${m.content}", !m.content.contains("SimSun"))
    }

    @Test
    fun mtextAlsoHonoursPercentCodes() {
        // MTEXT 里同样会出现 %%c/%%d，不能只给 TEXT 处理
        val r = DxfReader()
        assertEquals("Ø20 钢筋", r.stripMText("%%c20 钢筋"))
        assertEquals("A°B", r.stripMText("A%%dB"))
        assertEquals("45°", r.stripMText("45\\U+00B0"))
        // MTEXT 的段落换行应变成空格，而不是原样留下 \P
        assertEquals("甲 乙", r.stripMText("甲\\P乙"))
    }

    @Test
    fun blockInsertAppliesBaseScaleAndRotation() {
        val s = full()
        // INSERT：块内线 (10,20)-(30,20) 相对基准点 (10,20) 是 (0,0)-(20,0)
        // 放大 2 → (0,0)-(40,0)，旋转 90° → (0,0)-(0,40)，平移 (100,200) → (100,200)-(100,240)
        val inserted = s.paths.any { p ->
            p.pts.size == 4 &&
                abs(p.pts[0] - 100f) < 0.01f && abs(p.pts[1] - 200f) < 0.01f &&
                abs(p.pts[2] - 100f) < 0.01f && abs(p.pts[3] - 240f) < 0.01f
        }
        assertTrue("INSERT 的基准点/缩放/旋转应正确", inserted)
        // DIMENSION 展开的块不做变换，应原样出现
        val dim = s.paths.any { p ->
            p.pts.size == 4 &&
                abs(p.pts[0] - 10f) < 0.01f && abs(p.pts[1] - 20f) < 0.01f &&
                abs(p.pts[2] - 30f) < 0.01f && abs(p.pts[3] - 20f) < 0.01f
        }
        assertTrue("DIMENSION 引用的匿名块应摊平绘制", dim)
    }

    @Test
    fun polylineVertexAndSeqend() {
        val s = full()
        val poly = s.paths.firstOrNull {
            it.pts.size == 6 && abs(it.pts[0]) < 0.01f && abs(it.pts[1] - 30f) < 0.01f
        }
        assertNotNull("POLYLINE/VERTEX 应连成一条折线", poly)
    }

    @Test
    fun layersAndSearch() {
        val s = full()
        val wall = s.layers.firstOrNull { it.name == "墙" }
        assertNotNull("图层表应被读取", wall)
        assertEquals(0, s.layers.indexOfFirst { it.name == "0" })
        // 墙图层上的线用图层色（ACI 1 = 红）
        val onWall = s.paths.filter { it.layer == s.layers.indexOf(wall) }
        assertTrue("墙图层应有实体", onWall.isNotEmpty())

        val hits = s.search("排烟")
        assertEquals(1, hits.size)
        assertTrue(s.search("  排烟 机房 ").isNotEmpty())
        assertTrue("空查询应返回空", s.search("").isEmpty())
        assertEquals(0, s.search("不存在的文字").size)
    }

    @Test
    fun boundsCoverGeometry() {
        val s = full()
        assertTrue(s.bounds.valid)
        assertTrue(s.bounds.minX <= 0f)
        assertTrue(s.bounds.maxX >= 100f)
        assertTrue(s.vertexCount > 30)
    }

    @Test
    fun codepageSniffing() {
        val r = DxfReader()
        assertEquals("GBK", r.sniffCodepage(build(header).toByteArray(Charset.forName("GBK"))))
        val big5 = listOf(9, "\$DWGCODEPAGE", 3, "ANSI_950")
        assertEquals("Big5", r.sniffCodepage(build(big5).toByteArray(Charset.forName("GBK"))))
        assertEquals("GBK", r.sniffCodepage(ByteArray(0)))
    }

    @Test
    fun binaryDxfIsRejectedWithHint() {
        val bin = "AutoCAD Binary DXF\r\n\u001a\u0000".toByteArray(Charset.forName("ISO-8859-1"))
        val err = runCatching { DxfReader().read(bin, "x.dxf") }.exceptionOrNull()
        assertTrue(err is UnsupportedOperationException)
        assertTrue(err!!.message!!.contains("DXF"))
    }
}
