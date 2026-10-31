package dev.cadlens.io

import dev.cadlens.model.CadPath
import dev.cadlens.model.CadScene
import dev.cadlens.model.CadText
import dev.cadlens.model.LayerTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 视图范围要扛得住“离群点”。
 *
 * 真实图纸里时不时会有跑到几十万甚至上百万单位外的杂点（解错的实体、孤立的
 * 定义点、老图残留）。如果直接拿全量包围盒去适配，整张图会缩成屏幕上一个像素，
 * 用户看到的就是一片空白。
 */
class ViewBoundsTest {

    private fun sceneOf(vararg pts: Float, stray: Boolean = false): CadScene {
        val layers = LayerTable()
        val layer = layers.index("0")
        val paths = ArrayList<CadPath>()
        paths.add(CadPath(pts.copyOf(), false, layer, 0))
        if (stray) {
            // 一个跑得极远的孤立点
            paths.add(CadPath(floatArrayOf(1_200_000f, 560_000f), false, layer, 0))
        }
        val b = dev.cadlens.model.Bounds()
        for (p in paths) {
            var i = 0
            while (i + 1 < p.pts.size) {
                b.add(p.pts[i], p.pts[i + 1]); i += 2
            }
        }
        return CadScene(paths, emptyList(), layers.layers, b, "t")
    }

    @Test
    fun outlierDoesNotBlowUpView() {
        // 一排 0..100 的密集点，外加一个 120 万处的杂点
        val pts = ArrayList<Float>()
        for (i in 0..200) {
            pts.add(i * 0.5f); pts.add((i % 7) * 0.5f)
        }
        val s = sceneOf(*pts.toFloatArray(), stray = true)

        assertEquals("全量范围确实被杂点撑大了", true, s.bounds.maxX > 1_000_000f)
        assertTrue("视图范围不能被杂点带跑：${s.viewBounds.maxX}", s.viewBounds.maxX < 200f)
        assertTrue("视图范围应覆盖真实内容", s.viewBounds.maxX >= 95f)
    }

    @Test
    fun plainSceneKeepsFullRange() {
        val s = sceneOf(0f, 0f, 50f, 0f, 50f, 30f, 0f, 30f)
        assertEquals(0f, s.viewBounds.minX, 0.01f)
        assertEquals(50f, s.viewBounds.maxX, 0.01f)
        assertEquals(30f, s.viewBounds.maxY, 0.01f)
    }

    @Test
    fun textOnlySceneWorks() {
        val layers = LayerTable()
        val l = layers.index("TEXT")
        val b = dev.cadlens.model.Bounds()
        val texts = (0 until 40).map {
            CadText(it * 10f, it * 4f, 3f, 0f, "字$it", l, 0).also { t -> b.add(t.x, t.y) }
        }
        val s = CadScene(emptyList(), texts, layers.layers, b, "t")
        assertTrue(s.viewBounds.valid)
        assertTrue("纯文字图也要能算出范围", s.viewBounds.width() > 0f)
    }
}
