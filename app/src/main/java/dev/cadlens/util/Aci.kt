package dev.cadlens.util

/**
 * AutoCAD 索引色（ACI）→ ARGB。
 * 1–9 固定，10–249 按 24 个色相 × 10 个明暗档生成，250–255 灰阶——与 AutoCAD 调色板一致。
 */
object Aci {

    private val FIXED = intArrayOf(
        0x000000, // 0 by block
        0xFF0000, // 1 red
        0xFFFF00, // 2 yellow
        0x00FF00, // 3 green
        0x00FFFF, // 4 cyan
        0x0000FF, // 5 blue
        0xFF00FF, // 6 magenta
        0xFFFFFF, // 7 white/black
        0x808080, // 8 dark gray
        0xC0C0C0, // 9 light gray
    )

    private val VALUE = floatArrayOf(1f, 1f, 0.65f, 0.65f, 0.5f, 0.5f, 0.3f, 0.3f, 0.15f, 0.15f)
    private val SAT = floatArrayOf(1f, 0.5f, 1f, 0.5f, 1f, 0.5f, 1f, 0.5f, 1f, 0.5f)
    private val GRAY = intArrayOf(51, 80, 102, 128, 179, 204)

    private val cache = HashMap<Int, Int>()

    fun argb(index: Int, byLayer: Int = 0xFFE8E8E8.toInt()): Int {
        if (index == 256 || index == 0) return byLayer
        cache[index]?.let { return it }
        val rgb = compute(index)
        val c = 0xFF000000.toInt() or rgb
        cache[index] = c
        return c
    }

    private fun compute(index: Int): Int {
        if (index in 0..9) return FIXED[index]
        if (index in 250..255) {
            val g = GRAY[index - 250]
            return (g shl 16) or (g shl 8) or g
        }
        if (index < 10 || index > 249) return 0xFFFFFF
        val n = index - 10
        val hue = (n / 10) * 15f
        val v = VALUE[n % 10]
        val s = SAT[n % 10]
        return hsv(hue, s, v)
    }

    private fun hsv(h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hh = h / 60f
        val x = c * (1 - kotlin.math.abs(hh % 2 - 1))
        var r = 0f
        var g = 0f
        var b = 0f
        when (hueSector(hh)) {
            0 -> { r = c; g = x }
            1 -> { r = x; g = c }
            2 -> { g = c; b = x }
            3 -> { g = x; b = c }
            4 -> { r = x; b = c }
            else -> { r = c; b = x }
        }
        val m = v - c
        val ri = ((r + m) * 255f).toInt().coerceIn(0, 255)
        val gi = ((g + m) * 255f).toInt().coerceIn(0, 255)
        val bi = ((b + m) * 255f).toInt().coerceIn(0, 255)
        return (ri shl 16) or (gi shl 8) or bi
    }

    private fun hueSector(hh: Float): Int {
        val i = hh.toInt()
        return when {
            i <= 0 -> 0
            i >= 6 -> 5
            else -> i
        }
    }
}
