package dev.cadlens.io

import java.io.File

/**
 * DWG 的入口：交给内置的 LibreDWG 转成 DXF，再走 [DxfReader]。
 *
 * 之所以绕一圈走 DXF，而不是把 LibreDWG 的完整对象模型映射到 Java：
 * 本地的 DXF 管线已经覆盖了几何、块、标注、文字、编码，并且有单元测试；
 * 让 DWG 复用同一条路径，两种格式看到的图就是完全一致的，也少一套维护成本。
 */
object DwgBridge {

    /** 转换成功时带上 DXF 文本，失败时带上给用户看的原因。 */
    class Result(val dxf: ByteArray?, val error: String?) {
        val ok: Boolean get() = dxf != null
    }

    private var loaded = false
    private var loadError: String? = null

    private fun ensureLoaded(): String? {
        if (loaded) return null
        loadError?.let { return it }
        return try {
            System.loadLibrary("cadlens_dwg")
            loaded = true
            null
        } catch (t: Throwable) {
            // 有些机型/裁剪 ROM 缺 ABI，这里要说清楚，而不是给个空图
            loadError = "本机缺少对应的原生库（${t.message ?: t.javaClass.simpleName}）"
            loadError
        }
    }

    fun toDxf(bytes: ByteArray, cacheDir: File): Result {
        ensureLoaded()?.let { return Result(null, it) }
        if (bytes.size < 64) return Result(null, "文件太小，不像 DWG")

        val status = IntArray(1)
        val dxf = try {
            nativeToDxf(bytes, cacheDir.absolutePath, status)
        } catch (t: Throwable) {
            return Result(null, "原生转换异常：${t.message ?: t.javaClass.simpleName}")
        }

        if (dxf != null && dxf.isNotEmpty()) return Result(dxf, null)
        return Result(null, message(status[0]))
    }

    private fun message(code: Int): String = when (code) {
        1 -> "读不进去：版本太老（R13 以前）或文件不完整"
        2 -> "结构损坏，解码在关键处失败"
        3 -> "能读，但写 DXF 时失败（图元类型暂不支持）"
        4 -> "临时文件读写失败"
        else -> "转换失败（错误码 $code）"
    }

    @JvmStatic
    private external fun nativeToDxf(src: ByteArray, dir: String, status: IntArray): ByteArray?
}
