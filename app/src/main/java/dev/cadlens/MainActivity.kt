package dev.cadlens

import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.util.Log
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import dev.cadlens.io.DwgProbe
import dev.cadlens.io.DxfReader
import dev.cadlens.model.CadScene
import dev.cadlens.model.CadText
import dev.cadlens.ui.CadCanvasView
import java.io.FileOutputStream
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var canvas: CadCanvasView
    private lateinit var status: TextView
    private lateinit var searchPanel: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var searchList: ListView
    private lateinit var measureBtn: Button

    private var scene: CadScene? = null
    private var hits: List<CadText> = emptyList()

    private val openDoc = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { load(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        showInfo("点“打开图纸”选一个 DXF 或 DWG 文件")
        if (savedInstanceState == null) {
            val data = intent?.data
            if (data != null) load(data) else loadSample()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { load(it) }
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0E1116.toInt())
        }

        root.addView(buildToolbar(), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ))

        val frame = FrameLayout(this)
        canvas = CadCanvasView(this).apply {
            onMeasureText = { text -> status.text = "量得 $text" }
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        frame.addView(canvas)
        frame.addView(buildSearchPanel())
        root.addView(frame, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        status = TextView(this).apply {
            setTextColor(0xFF8B98A9.toInt())
            textSize = 12f
            setPadding(dp(16), dp(8), dp(16), dp(10))
            setBackgroundColor(0xFF12161D.toInt())
        }
        root.addView(status)
        return root
    }

    private fun buildToolbar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF12161D.toInt())
            setPadding(dp(8), 0, dp(8), 0)
        }
        fun btn(label: String, onClick: () -> Unit): Button =
            Button(this).apply {
                text = label
                textSize = 13f
                isAllCaps = false
                setTextColor(0xFFD8DEE9.toInt())
                background = GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat()
                    setColor(0xFF1C2430.toInt())
                }
                setPadding(dp(14), 0, dp(14), 0)
                setOnClickListener { onClick() }
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)
                ).also { it.marginEnd = dp(8) }
            }

        bar.addView(btn("打开图纸") { openDoc.launch(arrayOf("*/*")) })
        bar.addView(btn("适配") { canvas.fit(); canvas.invalidate() })
        bar.addView(btn("图层") { showLayers() })
        bar.addView(btn("搜索") { toggleSearch() })
        measureBtn = btn("测量") {
            canvas.measureMode = !canvas.measureMode
            measureBtn.setTextColor(if (canvas.measureMode) 0xFFFFD166.toInt() else 0xFFD8DEE9.toInt())
            status.text = if (canvas.measureMode) "测量：点两个点" else "测量已关闭"
        }
        bar.addView(measureBtn)

        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        return scroll
    }

    private fun buildSearchPanel(): View {
        searchPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF151B23.toInt())
            visibility = View.GONE
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(300)
            ).also { it.gravity = Gravity.TOP }
            // 顶部留白，避免和工具栏贴死
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        searchInput = EditText(this).apply {
            hint = "搜索图纸里的文字，例如：排烟机房"
            setHintTextColor(0xFF6B7787.toInt())
            setTextColor(0xFFE6EDF3.toInt())
            textSize = 15f
            background = GradientDrawable().apply {
                cornerRadius = dp(10).toFloat()
                setColor(0xFF0E1116.toInt())
            }
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        searchList = ListView(this).apply { dividerHeight = 1 }
        searchPanel.addView(searchInput, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(44)
        ))
        searchPanel.addView(searchList, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                runSearch(s?.toString().orEmpty())
            }
        })
        return searchPanel
    }

    private fun toggleSearch() {
        if (searchPanel.visibility == View.VISIBLE) {
            searchPanel.visibility = View.GONE
            canvas.highlight = emptyList()
        } else {
            searchPanel.visibility = View.VISIBLE
            searchInput.requestFocus()
        }
    }

    private fun runSearch(q: String) {
        val s = scene
        if (s == null) { status.text = "先打开一张图纸"; return }
        hits = if (q.isBlank()) emptyList() else s.search(q)
        val labels = hits.map { it.content.replace('\n', ' ').take(40) }
        // 深色界面下系统默认列表项是黑字，这里自己给一版配色
        searchList.adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, labels) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val tv = super.getView(position, convertView, parent) as TextView
                tv.setTextColor(0xFFE6EDF3.toInt())
                tv.textSize = 14f
                tv.setPadding(dp(6), dp(10), dp(6), dp(10))
                return tv
            }
        }
        searchList.setOnItemClickListener { _, _, pos, _ ->
            hits.getOrNull(pos)?.let {
                canvas.jumpTo(it)
                status.text = "命中 ${hits.size} 处 · ${it.content.take(30)}"
            }
        }
        status.text = if (q.isBlank()) "" else "命中 ${hits.size} 处"
    }

    private fun showLayers() {
        val s = scene
        if (s == null) { Toast.makeText(this, "先打开一张图纸", Toast.LENGTH_SHORT).show(); return }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val checks = ArrayList<CheckBox>()
        for (layer in s.layers) {
            val cb = CheckBox(this).apply {
                text = "${layer.name}   (${s.paths.count { it.layer == s.layers.indexOf(layer) }} 条)"
                isChecked = layer.visible
                setTextColor(0xFFD8DEE9.toInt())
                textSize = 14f
            }
            checks.add(cb)
            box.addView(cb)
        }
        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("图层（${s.layers.size}）")
            .setView(scroll)
            .setPositiveButton("应用") { _, _ ->
                s.layers.forEachIndexed { i, l -> l.visible = checks[i].isChecked }
                canvas.invalidate()
            }
            .setNeutralButton("全开") { _, _ ->
                s.layers.forEach { it.visible = true }
                canvas.invalidate()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ------------------------------------------------------------------ 载入

    private fun load(uri: Uri) {
        status.text = "读取中…"
        thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("读不到文件")
                val name = queryName(uri) ?: "未命名"
                if (bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII).startsWith("AC10")) {
                    onDwg(bytes, name, uri)
                } else {
                    onDxf(bytes, name, uri)
                }
            } catch (t: Throwable) {
                runOnUiThread {
                    status.text = "打开失败"
                    AlertDialog.Builder(this)
                        .setTitle("打不开这个文件")
                        .setMessage(t.message ?: t.toString())
                        .setPositiveButton("知道了", null)
                        .show()
                }
            }
        }
    }

    private fun onDxf(bytes: ByteArray, name: String, uri: Uri?) {
        runOnUiThread { status.text = "解析中… ${bytes.size / 1024} KB" }
        val scene = DxfReader().read(bytes, name)
        runOnUiThread {
            this.scene = scene
            canvas.scene = scene
            canvas.invalidate()
            status.text = "${scene.title} · ${scene.paths.size} 个图形 · " +
                "${scene.texts.size} 条文字 · ${scene.layers.size} 个图层" +
                if (scene.notes.isNotEmpty()) " · ${scene.notes.joinToString(" ")}" else ""
        }
        currentUri = uri
    }

    private var currentUri: Uri? = null

    private fun onDwg(bytes: ByteArray, name: String, uri: Uri?) {
        runOnUiThread { status.text = "识别 DWG 结构…" }
        val probe = DwgProbe(bytes).inspect()
        runOnUiThread {
            status.text = "DWG · ${probe.version} · ${probe.objectCount} 个对象 · ${probe.sections.size} 个段"
            showDwgDialog(probe, name)
        }
    }

    private fun showDwgDialog(probe: DwgProbe.Result, name: String) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(8))
        }
        fun line(t: String, size: Float = 13f, color: Int = 0xFFB8C4D4.toInt()) =
            box.addView(TextView(this).apply {
                text = t
                textSize = size
                setTextColor(color)
                setPadding(0, dp(2), 0, dp(2))
            })

        line(name, 15f, 0xFFE6EDF3.toInt())
        line("版本：${probe.version}（${probe.versionCode}）")
        line("大小：${size(probe.fileSize.toLong())}")
        line("对象：${probe.objectCount}")
        if (probe.sections.isNotEmpty()) {
            line("段（${probe.sections.size}）：", 13f, 0xFF8B98A9.toInt())
            for (s in probe.sections) {
                line("  ${s.name}  ${size(s.size)} · ${s.pages} 页", 12f, 0xFF8B98A9.toInt())
            }
        }
        probe.preview?.let { bmp ->
            BitmapFactory.decodeByteArray(bmp, 0, bmp.size)?.let { b ->
                box.addView(ImageView(this).apply { setImageBitmap(b); adjustViewBounds = true },
                    LinearLayout.LayoutParams(dp(260), ViewGroup.LayoutParams.WRAP_CONTENT)
                        .also { it.topMargin = dp(10) })
            }
        }
        if (probe.notes.isNotEmpty()) {
            line("")
            for (n in probe.notes) line("· $n", 12f, 0xFF8B98A9.toInt())
        }

        line("")
        line("这一步只读容器，不画实体。", 13f, 0xFFFFD166.toInt())
        line("要直接出图：把图纸另存为 DXF（AutoCAD “另存为”→ AutoCAD 2000/2004 DXF），", 12f, 0xFFB8C4D4.toInt())
        line("本 App 打开 DXF 会画出线条和文字，并能搜索图里的字。", 12f, 0xFFB8C4D4.toInt())

        val scroll = android.widget.ScrollView(this).apply { addView(box) }
        AlertDialog.Builder(this)
            .setTitle("DWG 结构信息")
            .setView(scroll)
            .setPositiveButton("知道了", null)
            .show()
    }

    private fun queryName(uri: Uri): String? {
        val fromProvider = try {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
            }
        } catch (t: Throwable) {
            null
        }
        // file:// 这类 URI 不支持 query，退回取路径最后一段
        return fromProvider?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    }

    /** 首次启动先给一张内置图纸，免得面对空白无从下手。 */
    private fun loadSample() {
        thread {
            try {
                val bytes = assets.open("sample.dxf").use { it.readBytes() }
                onDxf(bytes, "示例图纸", null)
            } catch (t: Throwable) {
                Log.w("CadLens", "sample load failed", t)
            }
        }
    }

    private fun showInfo(msg: String) { status.text = msg }

    /** 小于 1 KB 的段用字节显示，否则“0 KB”看着像没读出来。 */
    private fun size(v: Long): String =
        if (v < 1024) "$v B" else if (v < 1024 * 1024) "${v / 1024} KB" else String.format("%.1f MB", v / 1048576.0)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
