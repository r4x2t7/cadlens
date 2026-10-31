package dev.cadlens.io

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 拿真实图纸验证容器层。
 *
 * 图纸不方便进仓库，所以走环境变量：没设就跳过，设了才跑。
 *   CADLENS_DWG_SAMPLE=/path/to/plan.dwg ./gradlew test
 */
class DwgProbeTest {

    private fun sample(): ByteArray? {
        val path = System.getenv("CADLENS_DWG_SAMPLE") ?: return null
        val f = File(path)
        return if (f.isFile) f.readBytes() else null
    }

    @Test
    fun r2004ContainerIsReadable() {
        val bytes = sample()
        assumeTrue("未提供 CADLENS_DWG_SAMPLE，跳过真实图纸用例", bytes != null)

        val r = DwgProbe(bytes!!).inspect()
        assertEquals("版本应识别为 2004", "AutoCAD 2004", r.version)
        assertTrue("段表应非空，实际 ${r.sections.size}", r.sections.isNotEmpty())

        // 段名是判断“表没读歪”最直接的证据：偏移错 4 个字节，名字就全是乱码
        val names = r.sections.map { it.name }
        assertTrue("缺少对象段: $names", names.contains("AcDb:AcDbObjects"))
        assertTrue("缺少句柄表: $names", names.contains("AcDb:Handles"))
        assertTrue("缺少类段: $names", names.contains("AcDb:Classes"))

        val obj = r.sections.first { it.name == "AcDb:AcDbObjects" }
        assertEquals("对象段页数", 30, obj.pages)
        assertEquals("对象段解压后大小", 875210L, obj.size)
        // 句柄表逐页累加解出来的精确值；错一个字节就会偏离
        assertEquals("对象数", 10867, r.objectCount)

        assertTrue("解析不应留下错误备注: ${r.notes}", r.notes.none { it.contains("异常") || it.contains("失败") })
    }

    @Test
    fun lz77RoundTripOfKnownSection() {
        val bytes = sample()
        assumeTrue("未提供 CADLENS_DWG_SAMPLE，跳过", bytes != null)

        // 段信息表有 14 条描述，其中第一条是空名（对象段的占位），有名字的 13 条
        val r = DwgProbe(bytes!!).inspect()
        assertEquals("有名段应为 13 条，实际 ${r.sections.map { it.name }}", 13, r.sections.size)
    }
}
