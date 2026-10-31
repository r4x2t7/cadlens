package dev.cadlens.io

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * DWG 容器探针：只读“文件是怎么组织的”，不解实体。
 *
 * 能拿到：版本、压缩方式、段表、对象数量、内嵌缩略图。
 * 拿这些做什么？
 *  - 版本决定能不能交给后面的原生引擎；
 *  - 段表 + 对象数让“这个文件有多大、有几万个对象”变成可见的数字；
 *  - 缩略图能给一个不联网的即时预览（很多图都有，这张没有）。
 */
class DwgProbe(private val data: ByteArray) {

    class Section(
        val name: String,
        val pages: Int,
        val size: Long,
        val compressed: Boolean,
        val maxDecomp: Int,
    )

    class Result(
        val version: String,
        val versionCode: String,
        val fileSize: Int,
        val sections: List<Section>,
        val objectCount: Int,
        val preview: ByteArray?,
        val notes: MutableList<String> = mutableListOf(),
    )

    private val buf: ByteBuffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    private val headers = mapOf(
        "AC1009" to "AutoCAD R11/R12",
        "AC1012" to "AutoCAD R13",
        "AC1014" to "AutoCAD R14",
        "AC1015" to "AutoCAD 2000",
        "AC1018" to "AutoCAD 2004",
        "AC1021" to "AutoCAD 2007",
        "AC1024" to "AutoCAD 2010",
        "AC1027" to "AutoCAD 2013",
        "AC1032" to "AutoCAD 2018",
    )

    fun inspect(): Result {
        val code = String(data, 0, 6, Charsets.US_ASCII)
        val version = headers[code] ?: "未知版本 ($code)"
        val notes = ArrayList<String>()
        val sections = ArrayList<Section>()
        var objectCount = 0
        var preview: ByteArray? = null

        if (code == "AC1018" || code == "AC1021" || code == "AC1024" || code == "AC1027" || code == "AC1032") {
            try {
                val map = readSections2004(notes)
                if (map != null) {
                    sections.addAll(map.first)
                    objectCount = map.second
                    preview = map.third
                }
            } catch (t: Throwable) {
                notes.add("段表解析中断：${t.message}")
            }
        } else if (code == "AC1015") {
            notes.add("R2000 未压缩段，结构更简单；当前版本只做容器识别，实体解码走原生引擎。")
        }

        notes.add("文件 ${data.size / 1024} KB")
        return Result(version, code, data.size, sections, objectCount, preview, notes)
    }

    // ---------------------------------------------------------------- R2004+

    private fun readSections2004(notes: MutableList<String>): Triple<List<Section>, Int, ByteArray?>? {
        val hdr = decryptHeader(0x80, 0x78) ?: return null
        if (String(hdr, 0, 11, Charsets.US_ASCII) != "AcFssFcAJMB") {
            notes.add("加密头校验失败")
            return null
        }
        val hb = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
        val sectionMapId = hb.getInt(0x50)
        val sectionMapAddress = hb.getLong(0x54)
        val sectionInfoId = hb.getInt(0x5C)
        val arraySize = hb.getInt(0x60)

        // 段页映射
        val pmStart = sectionMapAddress + 0x100
        if (pmStart < 0 || pmStart + 20 > data.size) return null
        val pmType = buf.getInt(pmStart.toInt())
        if (pmType != 0x41630E3B) {
            notes.add("段页映射标记异常 ${Integer.toHexString(pmType)}")
            return null
        }
        val pmDecomp = buf.getInt(pmStart.toInt() + 4)
        val pmComp = buf.getInt(pmStart.toInt() + 8)
        val pageMap = lz77(data.copyOfRange((pmStart + 20).toInt(), (pmStart + 20 + pmComp).toInt()), pmDecomp)

        val secAddr = HashMap<Int, Pair<Long, Long>>()
        var addr = 0x100L
        var p = 0
        while (p + 8 <= pageMap.size) {
            val num = ByteBuffer.wrap(pageMap, p, 8).order(ByteOrder.LITTLE_ENDIAN).getInt()
            val size = ByteBuffer.wrap(pageMap, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt().toLong() and 0xFFFFFFFFL
            p += 8
            secAddr[num] = addr to size
            if (num <= arraySize) addr += size
            if (num < 0 && p + 16 <= pageMap.size) p += 16
        }
        secAddrs = secAddr

        // 段信息表
        val si = secAddr[sectionInfoId] ?: return null
        val siStart = si.first.toInt()
        if (siStart + 20 > data.size) return null
        val siType = buf.getInt(siStart)
        if (siType != 0x4163003B) {
            notes.add("段信息表标记异常")
            return null
        }
        val siDecomp = buf.getInt(siStart + 4)
        val siComp = buf.getInt(siStart + 8)
        val info = lz77(data.copyOfRange(siStart + 20, siStart + 20 + siComp), siDecomp)

        val ib = ByteBuffer.wrap(info).order(ByteOrder.LITTLE_ENDIAN)
        val numDesc = ib.getInt(0)
        val out = ArrayList<Section>()
        val pageIndex = HashMap<String, List<Int>>()
        var q = 20
        for (i in 0 until numDesc) {
            if (q + 96 > info.size) break
            val size = ib.getLong(q)
            val nsec = ib.getInt(q + 8)
            val maxDecomp = ib.getInt(q + 12)
            val compressed = ib.getInt(q + 20) == 2
            val nameBytes = info.copyOfRange(q + 32, q + 96)
            val name = String(nameBytes, Charsets.ISO_8859_1).substringBefore('\u0000').trim()
            q += 96
            val pages = ArrayList<Int>()
            for (k in 0 until nsec) {
                if (q + 16 > info.size) break
                pages.add(ib.getInt(q))
                q += 16
            }
            if (name.isNotEmpty()) {
                out.add(Section(name, nsec, size, compressed, maxDecomp))
                pageIndex[name] = pages
            }
            if (maxDecomp <= 0) { /* 空段 */ }
        }

        // 对象数量（句柄表逐页累计）
        var objects = 0
        try {
            val handlesInfo = out.firstOrNull { it.name == "AcDb:Handles" }
            if (handlesInfo != null) {
                val hb = readSection(pageIndex["AcDb:Handles"], handlesInfo)
                if (hb != null) objects = countHandles(hb)
            }
        } catch (t: Throwable) {
            notes.add("句柄表读取失败")
        }

        var preview: ByteArray? = null
        try {
            val prevInfo = out.firstOrNull { it.name == "AcDb:Preview" }
            if (prevInfo != null) {
                val prev = readSection(pageIndex["AcDb:Preview"], prevInfo)
                preview = extractBmp(prev)
            }
        } catch (t: Throwable) {
            // 没有缩略图是常态
        }

        return Triple(out, objects, preview)
    }

    private var secAddrs: MutableMap<Int, Pair<Long, Long>> = HashMap()

    private fun readSection(pages: List<Int>?, info: Section): ByteArray? {
        if (pages == null || pages.isEmpty()) return null
        val slot = info.maxDecomp.coerceIn(1024, 1 shl 20)
        val sb = ByteArray(pages.size * slot)
        var written = 0
        for (num in pages) {
            val pa = secAddrs[num] ?: continue
            val page = readPage(pa.first.toInt(), slot, info.compressed) ?: continue
            val off = page.second
            val at = if (off in 0 until sb.size.toLong()) off.toInt() else 0
            val len = minOf(page.first.size, sb.size - at)
            if (len <= 0) continue
            System.arraycopy(page.first, 0, sb, at, len)
            written = maxOf(written, at + len)
        }
        return if (written == 0) null else sb.copyOf(written)
    }

    /** @return 解压后的页数据 + 该页在段缓冲里的槽位偏移 */
    private fun readPage(address: Int, usize: Int, compressed: Boolean): Pair<ByteArray, Long>? {
        if (address < 0 || address + 32 > data.size) return null
        val mask = 0x4164536B.toInt() xor address
        val words = IntArray(8)
        val bb = ByteBuffer.wrap(data, address, 32).order(ByteOrder.LITTLE_ENDIAN)
        for (k in 0 until 8) words[k] = bb.getInt() xor mask
        if (words[0] != 0x4163043B) return null
        val dataSize = words[2]
        val pageSize = words[3]
        val slot = words[4].toLong() and 0xFFFFFFFFL
        if (dataSize <= 0 || address + 32 + dataSize > data.size) return null
        val body = data.copyOfRange(address + 32, address + 32 + dataSize)
        val out = if (compressed) lz77(body, usize) else body.copyOf(minOf(pageSize, body.size))
        return out to slot
    }

    /**
     * 句柄表：每页 = 2 字节页长 + 若干 (UMC 句柄增量, MC 地址增量) + 2 字节 CRC。
     *
     * 关键点：累加器在**每页开头归零**。地址是段内全局字节偏移（不是页内偏移），
     * 但它是从 0 重新累加的，跨页接着累会一路飘到几百万，一个对象都对不上。
     */
    private fun countHandles(hb: ByteArray): Int {
        val b = BitCursor(hb)
        var total = 0
        while (b.p + 2 <= hb.size) {
            val pageSize = ((hb[b.p].toInt() and 0xFF) shl 8) or (hb[b.p + 1].toInt() and 0xFF)
            if (pageSize <= 2 || pageSize > 2040) break
            val start = b.p
            val end = start + pageSize
            b.p += 2
            while (b.p < end && b.p < hb.size) {
                b.umc()   // 句柄增量
                b.mc()    // 地址增量（这里只数个数，不累加）
                total++
            }
            b.p = end + 2
        }
        return total
    }

    /** 只为句柄表准备的最小位读取器：按字节走，够用。 */
    private class BitCursor(val b: ByteArray) {
        var p = 0

        fun rc(): Int {
            if (p >= b.size) throw IndexOutOfBoundsException()
            return b[p++].toInt() and 0xFF
        }

        fun umc(): Int {
            var v = 0
            var sh = 0
            while (true) {
                val x = rc()
                v = v or ((x and 0x7F) shl sh)
                sh += 7
                if (x and 0x80 == 0) return v
            }
        }

        fun mc(): Int {
            var v = 0
            var sh = 0
            var last: Int
            while (true) {
                last = rc()
                v = v or ((last and 0x7F) shl sh)
                sh += 7
                if (last and 0x80 == 0) break
            }
            return if (last and 0x40 != 0) -(v and (0x40 shl (sh - 7)).inv()) else v
        }
    }

    private fun extractBmp(prev: ByteArray?): ByteArray? {
        if (prev == null || prev.size < 32) return null
        // 前 16 字节是 sentinel，后面是 BMP
        var start = -1
        for (i in 16 until minOf(prev.size - 2, 256)) {
            if (prev[i] == 'B'.code.toByte() && prev[i + 1] == 'M'.code.toByte()) { start = i; break }
        }
        if (start < 0) return null
        val bmp = prev.copyOfRange(start, prev.size)
        return if (bmp.size > 64) bmp else null
    }

    // ---------------------------------------------------------------- 工具

    private fun decryptHeader(offset: Int, size: Int): ByteArray? {
        if (offset + size > data.size) return null
        var rseed = 1
        val out = ByteArray(size)
        for (i in 0 until size) {
            rseed = rseed * 0x343FD + 0x269EC3
            out[i] = (data[offset + i].toInt() xor ((rseed ushr 16) and 0xFF)).toByte()
        }
        return out
    }

    companion object {
        /** R2004 的 LZ77 变体。 */
        fun lz77(src: ByteArray, decompSize: Int): ByteArray {
            val out = ArrayList<Byte>(decompSize.coerceAtMost(1 shl 22))
            var pos = 0
            val n = src.size

            fun rc(): Int {
                if (pos >= n) throw IndexOutOfBoundsException("压缩流结束")
                return src[pos++].toInt() and 0xFF
            }

            fun literalLength(op: Int): Int {
                var low = op and 0x0F
                if (low == 0) {
                    var last: Int
                    while (true) {
                        last = rc()
                        if (last != 0) break
                        low += 0xFF
                    }
                    low += 0x0F + last
                }
                return low + 3
            }

            fun compressedBytes(op: Int, bits: Int): Int {
                var cb = op and bits
                if (cb == 0) {
                    var last: Int
                    while (true) {
                        last = rc()
                        if (last != 0) break
                        cb += 0xFF
                    }
                    cb += last + bits
                }
                return cb + 2
            }

            try {
                var op1 = rc()
                if (op1 and 0xF0 == 0) {
                    val ll = literalLength(op1)
                    repeat(ll) { out.add(rc().toByte()) }
                    op1 = rc()
                }
                while (pos < n && out.size < decompSize && op1 != 0x11) {
                    var off = 0
                    var cb: Int
                    if (op1 < 0x10 || op1 >= 0x40) {
                        cb = (op1 shr 4) - 1
                        val op2 = rc()
                        off = (((op1 shr 2) and 3) or (op2 shl 2)) + 1
                    } else if (op1 < 0x20) {
                        cb = compressedBytes(op1, 7)
                        off = (op1 and 8) shl 11
                        val f = rc(); val s = rc()
                        op1 = f
                        off = off or (f shr 2) or (s shl 6)
                        off += 0x4000
                    } else {
                        cb = compressedBytes(op1, 0x1F)
                        val f = rc(); val s = rc()
                        op1 = f
                        off = off or (f shr 2) or (s shl 6)
                        off += 1
                    }
                    if (cb > 0) {
                        val start = out.size - off
                        if (start < 0) break
                        for (k in 0 until cb) out.add(out[start + k])
                    }
                    var ll = op1 and 3
                    if (ll == 0) {
                        op1 = rc()
                        if (op1 and 0xF0 == 0) ll = literalLength(op1)
                    }
                    if (ll > 0) {
                        repeat(ll) { out.add(rc().toByte()) }
                        op1 = rc()
                    }
                }
            } catch (t: Throwable) {
                // 流结束即止
            }
            val arr = ByteArray(minOf(out.size, decompSize))
            for (i in arr.indices) arr[i] = out[i]
            return arr
        }
    }
}
