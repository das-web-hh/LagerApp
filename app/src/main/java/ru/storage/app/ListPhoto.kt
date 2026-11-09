package ru.storage.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Rect
import android.util.Size
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as MlText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Обычный снимок → список товаров (артикул, название, штрих-код): экран компьютера (ALPHAPLAN и т. п.),
 * бумажный список или этикетка Ströh.
 *
 * Порядок:
 *  1. телефон: ML Kit находит слова вместе с положением, из них собираются строки и столбцы;
 *     столбец цифр из 13 знаков — штрих-код, столбец уникальных коротких номеров — артикул
 *     (повторяющийся столбец вроде группы товара «WGU 639» артикулом не считается);
 *     если столбцов цифр несколько, артикул выбирается по заголовку «Artikel-Nr.»;
 *  2. если заголовка не видно, столбцы перемешаны, цифры не читаются или строк мало — снимок уходит в ИИ,
 *     предварительно обработанный: обрезан по тексту, цвет фона строк убран (красные, синие строки), уменьшен.
 */
object ListPhoto {
    class Result(val items: List<ScannedLabel>, val viaAi: Boolean)

    private class El(val text: String, val cx: Float, val cy: Float, val w: Float, val h: Float, val angle: Float)
    private class Cell(val text: String, val x: Float, val w: Float, val y: Float, val h: Float)
    private class Item(val article: String, val name: String, val barcode: String)
    private class TableRes(val items: List<Item>, val ok: Boolean, val reason: String)

    private fun keyOf(i: Item): String = when {
        i.barcode.isNotBlank() -> StrohLabel.key(i.barcode)
        i.article.isNotBlank() -> "A" + i.article
        else -> "N" + i.name.lowercase()
    }

    suspend fun read(ctx: Context, src: Bitmap, onStatus: (String) -> Unit): Result {
        val bmp = scaled(src, 3000)
        var text: MlText? = null
        var codes: List<Barcode> = emptyList()
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val bs = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_EAN_13, Barcode.FORMAT_EAN_8, Barcode.FORMAT_UPC_A, Barcode.FORMAT_UPC_E).build()
        )
        try {
            text = try { rec.process(InputImage.fromBitmap(bmp, 0)).awaitIt() } catch (e: Exception) { null }
            codes = try { bs.process(InputImage.fromBitmap(bmp, 0)).awaitIt() } catch (e: Exception) { emptyList() }
        } finally {
            try { rec.close() } catch (e: Exception) { }
            try { bs.close() } catch (e: Exception) { }
        }
        val lines = ArrayList<Pair<String, Rect>>()
        if (text != null) for (b in text.textBlocks) for (l in b.lines) {
            val bb = l.boundingBox ?: continue
            lines.add(l.text to bb)
        }

        // 1) этикетки со штрих-кодом (Ströh): артикул и название лежат рядом со штрих-кодом
        val bars = ArrayList<Pair<String, Rect>>()
        for (b in codes) {
            val v = b.rawValue?.trim() ?: continue
            val box = b.boundingBox ?: continue
            if (v.isNotEmpty() && gtinOk(v)) bars.add(v to box)
        }
        if (bars.isNotEmpty() && lines.isNotEmpty()) {
            val parsed = StrohLabel.parseFrame(bars, lines)
            val done = parsed.filter { it.article.isNotEmpty() && it.name.length >= 4 }
            if (done.isNotEmpty() && done.size * 2 >= parsed.size) {
                val out = LinkedHashMap<String, ScannedLabel>()
                for (p in done) out[StrohLabel.key(p.barcode)] = ScannedLabel(StrohLabel.key(p.barcode), p.barcode, p.article, p.name, "phone")
                return Result(out.values.toList(), false)
            }
        }

        // 2) таблица или список: на телефоне
        var why = "Нет текста на снимке"
        if (text != null && lines.size >= 3) {
            val t = try { parseTable(text) } catch (e: Exception) { TableRes(emptyList(), false, "ошибка разбора") }
            if (t.ok) {
                val out = LinkedHashMap<String, ScannedLabel>()
                for (i in t.items) out.putIfAbsent(keyOf(i), ScannedLabel(keyOf(i), i.barcode, i.article, i.name, "phone"))
                return Result(out.values.toList(), false)
            }
            why = t.reason
        }

        // 3) ИИ: картинка сначала обрабатывается, чтобы тратить меньше токенов и чтобы цветной фон не мешал
        if (!AiCfg.ready(ctx)) {
            throw RuntimeException("Телефон не смог надёжно прочитать снимок ($why). Чтобы читал ИИ, добавьте ключ ИИ в настройках.")
        }
        onStatus("Телефон не уверен ($why). Готовлю снимок и отправляю в ИИ…")
        val items = withContext(Dispatchers.IO) { fromAi(ctx, bmp, lines, onStatus) }
        if (items.isEmpty()) throw RuntimeException("ИИ не нашёл товаров на снимке")
        val out = LinkedHashMap<String, ScannedLabel>()
        for (i in items) out.putIfAbsent(keyOf(i), ScannedLabel(keyOf(i), i.barcode, i.article, i.name, "ai"))
        return Result(out.values.toList(), true)
    }

    // ---------- таблица на телефоне ----------

    private fun digitsOf(t: String): String {
        val s = t.replace(" ", "")
        if (s.length < 3) return if (s.isNotEmpty() && s.all { it.isDigit() }) s else ""
        // частые ошибки OCR внутри длинных цифровых ячеек: O→0, l/I/|→1, S→5, B→8
        val fixed = s.map { c ->
            when (c) { 'O', 'o' -> '0'; 'l', 'I', '|' -> '1'; 'S' -> '5'; 'B' -> '8'; else -> c }
        }.joinToString("")
        val real = s.count { it.isDigit() }
        return if (fixed.all { it.isDigit() } && real * 10 >= s.length * 7) fixed else ""
    }

    private fun rotateEls(els: List<El>, deg: Float): List<Cell> {
        val a = Math.toRadians(deg.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        return els.map { r ->
            val x = r.cx * ca + r.cy * sa
            val y = -r.cx * sa + r.cy * ca
            Cell(r.text, x - r.w / 2, r.w, y, r.h)
        }
    }

    private fun rowsOf(cells: List<Cell>, medH: Float): List<List<Cell>> {
        val sorted = cells.sortedBy { it.y }
        val rows = ArrayList<MutableList<Cell>>()
        var meanY = 0f
        for (c in sorted) {
            val last = rows.lastOrNull()
            if (last != null && abs(c.y - meanY) <= medH * 0.6f) {
                last.add(c)
                meanY = (meanY * (last.size - 1) + c.y) / last.size
            } else {
                rows.add(mutableListOf(c))
                meanY = c.y
            }
        }
        return rows
    }

    private fun parseTable(text: MlText): TableRes {
        val els = ArrayList<El>()
        val angles = ArrayList<Float>()
        for (b in text.textBlocks) for (l in b.lines) {
            angles.add(l.angle)
            for (e in l.elements) {
                val r = e.boundingBox ?: continue
                if (e.text.isBlank()) continue
                els.add(El(e.text.trim(), r.exactCenterX(), r.exactCenterY(), r.width().toFloat(), r.height().toFloat(), l.angle))
            }
        }
        if (els.size < 8) return TableRes(emptyList(), false, "мало текста")
        val medH = els.map { it.h }.sorted()[els.size / 2].coerceAtLeast(6f)
        val tilt = angles.sorted()[angles.size / 2]
        // знак наклона заранее неясен: берём тот поворот, при котором строк меньше (текст лучше выровнен)
        val best = listOf(0f, tilt, -tilt).map { d -> rowsOf(rotateEls(els, d), medH) }.minByOrNull { it.size }!!

        // кусочки одной строки, стоящие близко, склеиваются в ячейку («0 114442 280422», слова названия)
        val rows: List<List<Cell>> = best.map { row ->
            val s = row.sortedBy { it.x }
            val out = ArrayList<Cell>()
            for (c in s) {
                val p = out.lastOrNull()
                if (p != null && c.x - (p.x + p.w) < medH * 1.0f) {
                    out[out.size - 1] = Cell(p.text + " " + c.text, p.x, c.x + c.w - p.x, p.y, p.h)
                } else out.add(c)
            }
            out
        }
        fun num(c: Cell) = digitsOf(c.text)
        fun isName(c: Cell) = digitsOf(c.text).isEmpty() && c.text.count { it.isLetter() } >= 3

        // строки данных: слева цифры из 4+ знаков, правее название
        val data = rows.filter { r ->
            val ni = r.indexOfFirst { num(it).length >= 4 }
            ni >= 0 && r.drop(ni + 1).any { isName(it) && it.text.length >= 5 }
        }
        if (data.size < 3) return TableRes(emptyList(), false, "строк со списком меньше трёх")
        val nameOnly = rows.count { r -> r.none { num(it).length >= 4 } && r.any { isName(it) && it.text.length >= 12 } }
        if (nameOnly > data.size * 0.3) return TableRes(emptyList(), false, "у многих строк цифры не прочитались")

        // столбцы цифр: группы ячеек с одинаковым левым краем
        class Col(val cells: List<Pair<Int, Cell>>) {
            val x = cells.map { it.second.x }.average().toFloat()
            val right = cells.map { it.second.x + it.second.w }.average().toFloat()
            val vals = cells.map { digitsOf(it.second.text) }
            val avgLen = vals.map { it.length }.average()
            val distinct = vals.toSet().size.toDouble() / vals.size
        }
        val numCells = ArrayList<Pair<Int, Cell>>()
        data.forEachIndexed { ri, r -> for (c in r) if (num(c).length >= 3) numCells.add(ri to c) }
        val sortedByX = numCells.sortedBy { it.second.x }
        val groups = ArrayList<MutableList<Pair<Int, Cell>>>()
        for (p in sortedByX) {
            val g = groups.lastOrNull()
            if (g != null && p.second.x - g.last().second.x <= medH * 2.2f) g.add(p) else groups.add(mutableListOf(p))
        }
        val cols = groups.filter { it.map { p -> p.first }.toSet().size >= max(3, (data.size * 0.6).toInt()) }.map { Col(it) }
        var barcodeCol: Col? = null
        val artCands = ArrayList<Col>()
        for (c in cols) {
            if (c.avgLen >= 12.5) { if (barcodeCol == null) barcodeCol = c }
            else if (c.avgLen >= 4.0 && c.distinct >= 0.6) artCands.add(c)
        }
        val bc = barcodeCol
        if (bc != null) {
            val bad = bc.vals.count { it.length >= 8 && !gtinOk(it) }
            if (bad > bc.vals.size * 0.3) return TableRes(emptyList(), false, "штрих-коды прочитаны с ошибками")
        }
        // заголовок «Artikel-Nr.» выше первой строки данных
        val firstY = data.minOf { r -> r.minOf { it.y } }
        val header = rows.flatMap { it }.filter { c ->
            c.y < firstY && c.text.lowercase().let { t -> t.contains("artikel") || t.contains("art.-nr") || t.contains("article") || t.contains("артикул") }
        }.minByOrNull { abs(it.y - firstY) }
        var artCol: Col? = null
        when {
            artCands.size == 1 -> artCol = artCands[0]
            artCands.size > 1 -> {
                if (header == null) return TableRes(emptyList(), false, "несколько столбцов с цифрами, заголовка не видно")
                val hl = header.x
                val hr = header.x + header.w
                artCol = artCands.minByOrNull { c ->
                    val mid = (c.x + c.right) / 2
                    if (mid in hl..hr) 0f else min(abs(mid - hl), abs(mid - hr))
                }
            }
        }
        if (artCol == null && bc == null) return TableRes(emptyList(), false, "столбец артикула не найден")

        // позиции: цифры из своего столбца + название правее цифр
        val tol = medH * 2.2f
        val items = ArrayList<Item>()
        for (r in data) {
            fun pick(col: Col?): Cell? = if (col == null) null else r.filter { num(it).length >= 3 && abs(it.x - col.x) <= tol }.minByOrNull { abs(it.x - col.x) }
            val ac = pick(artCol)
            val bcell = pick(bc)
            val edge = listOfNotNull(ac, bcell).maxOfOrNull { it.x + it.w } ?: continue
            val name = r.filter { it.x >= edge - medH * 0.5f && isName(it) }.joinToString(" ") { it.text }
                .replace(Regex("\\s+"), " ").trim()
            val article = ac?.let { num(it) } ?: ""
            val code = bcell?.let { num(it) } ?: ""
            if (name.length < 4 || (article.isEmpty() && code.isEmpty())) continue
            items.add(Item(article, name, if (code.length >= 8 && gtinOk(code)) code else ""))
        }
        if (items.size < 3 || items.size < data.size * 0.75) return TableRes(emptyList(), false, "часть строк не разобрана")
        return TableRes(items, true, "")
    }

    // ---------- ИИ ----------

    private fun scaled(b: Bitmap, side: Int): Bitmap {
        val m = max(b.width, b.height)
        if (m <= side) return b
        val k = side.toFloat() / m
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    /**
     * Подготовка для ИИ: чёрный текст на любом цветном фоне (красные, синие, серые полосы) становится чёрным на белом.
     * Для этого берётся самый яркий из каналов RGB — у цветного фона он высокий, у чёрных букв низкий;
     * затем яркость растягивается: фон — в белый, самый тёмный 1% — в чёрный. Лишнее (чёрные поля экрана) обрезается.
     */
    private fun prep(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val v = IntArray(w * h)
        val hist = IntArray(256)
        for (i in px.indices) {
            val p = px[i]
            val m = max((p shr 16) and 255, max((p shr 8) and 255, p and 255))
            v[i] = m
            hist[m]++
        }
        fun pct(q: Double): Int {
            val target = (w.toLong() * h * q).toLong()
            var acc = 0L
            for (i in 0..255) { acc += hist[i]; if (acc >= target) return i }
            return 255
        }
        val black = pct(0.01)
        val white = max(pct(0.35), black + 40).coerceAtMost(255)
        val span = (white - black).coerceAtLeast(1)
        for (i in px.indices) {
            val g = ((v[i] - black) * 255 / span).coerceIn(0, 255)
            px[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    private fun jpeg(b: Bitmap): ByteArray {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, 82, bos)
        return bos.toByteArray()
    }

    private val PROMPT = """
        This is a photo of a computer screen (an ERP program such as ALPHAPLAN) or of a paper list with products. The picture is already converted to black and white.
        Extract EVERY product row. Answer with JSON only (no markdown, no comments):
        {"rows":[{"article":"","name":"","barcode":""}]}
        Rules:
        - article: the value from the column headed "Artikel-Nr." / "Artikel" / "Art.-Nr." (digits and letters, no spaces). Do NOT take the product-group column (a short code that repeats on many rows, e.g. "WGU 639"), nor stock / quantity / price columns.
          If no header is visible: the digits-only column with fewer than 13 digits whose values differ from row to row is the article.
        - barcode: the EAN / GTIN column with 13 digits (8, 12 or 14 digits are possible too), digits only, no spaces. "" if there is no such column.
        - name: the full text of the "Bezeichnung" / description column exactly as printed, including sizes, colours and units. Keep letters, digits and symbols as they are.
        - Skip the title bar, column headers, filter rows (such as "%horseware"), footers (Lagerplatz, Charge, Bestand, Reserviert ...) and any other column.
        - Never invent or guess values. Use "" when a value is not readable.
    """.trimIndent()

    private val PROMPT_LABEL = """
        This is a photo of one product label of the brand STRÖH (horse stable supplies): logo, a standalone article number (4-8 digits) under the logo,
        the product name (1-3 lines, it may end with a volume or weight such as "591 ml"), the price, a line "% ab 2 Stück nur ..." and a barcode (EAN) with digits.
        Answer with JSON only: {"rows":[{"article":"","name":"","barcode":""}]} with exactly one row for the most complete label in the picture.
        name WITHOUT price, WITHOUT the "% ab 2 Stück" line, WITHOUT logo text; barcode = digits only. Never invent values, use "" when not visible.
    """.trimIndent()

    private fun parseRows(answer: String, out: MutableList<Item>) {
        val s = answer.indexOf('{')
        val e = answer.lastIndexOf('}')
        if (s < 0 || e <= s) return
        val arr = JSONObject(answer.substring(s, e + 1)).optJSONArray("rows") ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            fun v(k: String) = o.optString(k).trim().let { if (it.equals("null", true) || it.equals("none", true)) "" else it }
            val name = v("name")
            if (name.isEmpty()) continue
            val code = v("barcode").filter { it.isDigit() }
            out.add(Item(v("article").filter { it.isLetterOrDigit() }, name, if (code.length >= 8 && gtinOk(code)) code else ""))
        }
    }

    private fun fromAi(ctx: Context, bmp: Bitmap, lines: List<Pair<String, Rect>>, onStatus: (String) -> Unit): List<Item> {
        // обрезаем по тексту (чёрные поля экрана, рамка монитора не нужны)
        var crop: Bitmap = bmp
        if (lines.size >= 3) {
            val l = lines.minOf { it.second.left }
            val t = lines.minOf { it.second.top }
            val r = lines.maxOf { it.second.right }
            val b = lines.maxOf { it.second.bottom }
            val padX = ((r - l) * 0.015f).toInt()
            val padY = ((b - t) * 0.015f).toInt()
            val cl = (l - padX).coerceIn(0, bmp.width - 1)
            val ct = (t - padY).coerceIn(0, bmp.height - 1)
            val cr = (r + padX).coerceIn(cl + 1, bmp.width)
            val cb = (b + padY).coerceIn(ct + 1, bmp.height)
            if (cr - cl >= 100 && cb - ct >= 100) crop = Bitmap.createBitmap(bmp, cl, ct, cr - cl, cb - ct)
        }
        val gray = prep(crop)
        // очень длинный снимок режем на полосы с небольшим нахлёстом: строки остаются читаемыми при уменьшении
        val parts = ((gray.height.toFloat() / (gray.width * 1.5f)).let { kotlin.math.ceil(it.toDouble()).toInt() }).coerceIn(1, 3)
        val out = ArrayList<Item>()
        val ph = gray.height / parts
        val overlap = (gray.height * 0.03f).toInt()
        for (i in 0 until parts) {
            if (parts > 1) onStatus("ИИ читает часть ${i + 1} из $parts…")
            val top = (i * ph - if (i > 0) overlap else 0).coerceAtLeast(0)
            val bottom = ((i + 1) * ph + (if (i < parts - 1) overlap else 0)).coerceAtMost(gray.height)
            var piece = Bitmap.createBitmap(gray, 0, top, gray.width, (bottom - top).coerceAtLeast(1))
            piece = scaled(piece, 1568) // больше ИИ всё равно не использует, лишнее — только токены
            val tmp = ArrayList<Item>()
            try {
                parseRows(Ai.task(ctx, "list_photo", JSONObject(), jpeg(piece), true) { PROMPT }, tmp)
            } catch (e: Exception) {
                if (i == 0 && parts == 1) throw e
            }
            out.addAll(tmp)
        }
        return out
    }
}

// ---------- экран: обычный снимок ----------

private fun decodePhoto(file: File): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var s = 1
        while (max(bounds.outWidth, bounds.outHeight) / s > 3200) s *= 2
        val bmp = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val rot = when (android.media.ExifInterface(file.absolutePath).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        if (rot == 0) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot.toFloat()) }, true)
    } catch (e: Throwable) {
        null
    }
}

private fun lifecycleOwnerOf2(ctx: Context): LifecycleOwner? {
    var c: Context? = ctx
    while (c is android.content.ContextWrapper) {
        if (c is LifecycleOwner) return c
        c = c.baseContext
    }
    return null
}

/**
 * Камера «Этикетки и списки»:
 *  • идёте камерой по этикеткам Ströh — каждая получает отметку на экране и записывается в список (LiveLabels);
 *    нажатие на отметку добавляет ещё одну такую же строку (у другой этикетки того же товара будет другая дата);
 *  • список с монитора или бумаги снимается обычным снимком кнопкой «Снять список»; нажатие на экран — фокус.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoCamera(
    onShot: (Bitmap) -> Unit,
    onCancel: () -> Unit,
    engine: LiveLabels,
    found: List<ScannedLabel>,
    marks: LiveMarks?,
    info: String,
    pending: Int,
    allowAnother: Boolean,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val capture = remember { arrayOfNulls<ImageCapture>(1) }
    val cam = remember { arrayOfNulls<Camera>(1) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    var busy by remember { mutableStateOf(false) }
    var liveOff by remember { mutableStateOf(false) }
    var box by remember { mutableStateOf(IntSize.Zero) }
    // сначала отвязать камеру, потом гасить поток, иначе кадр уходит в закрытый executor
    DisposableEffect(Unit) {
        onDispose {
            engine.alive = false
            try { ProcessCameraProvider.getInstance(context).get().unbindAll() } catch (e: Exception) { }
            executor.shutdown()
        }
    }
    val ratio = remember { ResolutionSelector.Builder().setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY).build() }
    Box(Modifier.fillMaxSize().onSizeChanged { box = it }) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                val pv = PreviewView(ctx)
                pv.setOnTouchListener { v, ev ->
                    if (ev.action == MotionEvent.ACTION_UP) {
                        try {
                            val p = pv.meteringPointFactory.createPoint(ev.x, ev.y)
                            cam[0]?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(p).build())
                        } catch (e: Exception) { }
                        v.performClick()
                    }
                    true
                }
                val future = ProcessCameraProvider.getInstance(ctx)
                future.addListener({
                    try {
                        val provider = future.get()
                        val preview = Preview.Builder().setResolutionSelector(ratio).build()
                        preview.setSurfaceProvider(pv.surfaceProvider)
                        val ic = ImageCapture.Builder()
                            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                            .setResolutionSelector(ratio)
                            .build()
                        capture[0] = ic
                        // для текста на этикетках нужна чёткая картинка: 1600x1200 вместо стандартных 640x480
                        val analysis = ImageAnalysis.Builder()
                            .setResolutionSelector(
                                ResolutionSelector.Builder()
                                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                                    .setResolutionStrategy(ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                                    .build()
                            )
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                        analysis.setAnalyzer(executor) { proxy ->
                            if (engine.alive) engine.analyze(proxy) else proxy.close()
                        }
                        val owner = lifecycleOwnerOf2(ctx)
                        if (owner == null) { onCancel(); return@addListener }
                        provider.unbindAll()
                        try {
                            cam[0] = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic, analysis)
                        } catch (e: Exception) {
                            // телефон не тянет три потока сразу: остаётся только снимок списка, без живых отметок
                            provider.unbindAll()
                            liveOff = true
                            cam[0] = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
                        }
                    } catch (e: Exception) {
                        onCancel()
                    }
                }, ContextCompat.getMainExecutor(ctx))
                pv
            }
        )

        // отметки на этикетках: серая — читаю, оранжевая — читает ИИ, зелёная — записана, красная — не распознана
        val m = marks
        if (m != null && box.width > 0 && box.height > 0 && !liveOff) {
            val scale = max(box.width.toFloat() / m.w, box.height.toFloat() / m.h)
            val ox = (box.width - m.w * scale) / 2f
            val oy = (box.height - m.h * scale) / 2f
            for (mk in m.items) {
                val base = mk.key
                val item = found.firstOrNull { it.key == base }
                val cnt = found.count { it.key.substringBefore('#') == base }
                val color = when {
                    item == null -> androidx.compose.ui.graphics.Color(0xFF9E9E9E)
                    item.via == "wait" -> androidx.compose.ui.graphics.Color(0xFFFFA000)
                    item.via == "none" -> androidx.compose.ui.graphics.Color(0xFFE53935)
                    else -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
                }
                val label = when {
                    item == null -> "…"
                    item.via == "wait" -> "ИИ…"
                    item.via == "none" -> "?"
                    cnt > 1 -> "✓ ×$cnt"
                    else -> "✓"
                }
                val l = (mk.rect.left * scale + ox).toInt()
                val t = (mk.rect.top * scale + oy).toInt()
                val w = (mk.rect.width() * scale).toInt().coerceAtLeast(1)
                val h = (mk.rect.height() * scale).toInt().coerceAtLeast(1)
                Box(
                    Modifier
                        .offset { IntOffset(l, t) }
                        .size(with(density) { w.toDp() }, with(density) { h.toDp() })
                        .border(3.dp, color, androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                        .combinedClickable(
                            enabled = item != null && item.via != "wait",
                            onClick = { if (allowAnother) engine.addAnother(base) },
                            onLongClick = { engine.removeLabel(base) }
                        )
                ) {
                    Text(
                        label, color = androidx.compose.ui.graphics.Color.White, style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.align(Alignment.TopStart).background(color).padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }
        }

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Закрыть") }
                if (found.isNotEmpty() || pending > 0) {
                    Button(enabled = pending == 0, onClick = onFinish) { Text(if (pending == 0) "Готово (${found.size})" else "Читаю…") }
                }
            }
            if (info.isNotBlank()) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp), color = androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.6f)) {
                    Text(info, color = androidx.compose.ui.graphics.Color.White, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), maxLines = 4)
                }
            }
            if (liveOff) {
                Text("Живые отметки на этом телефоне недоступны: снимайте этикетки и списки кнопкой.", color = androidx.compose.ui.graphics.Color.White,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Button(
            enabled = !busy,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp).fillMaxWidth(0.7f).height(60.dp),
            onClick = {
                val ic = capture[0] ?: return@Button
                busy = true
                val f = File(context.cacheDir, "inv_list_" + System.currentTimeMillis() + ".jpg")
                ic.takePicture(
                    ImageCapture.OutputFileOptions.Builder(f).build(),
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                            val b = decodePhoto(f)
                            try { f.delete() } catch (e: Exception) { }
                            busy = false
                            if (b != null) onShot(b) else Toast.makeText(context, "Не удалось открыть снимок", Toast.LENGTH_SHORT).show()
                        }

                        override fun onError(e: ImageCaptureException) {
                            busy = false
                            Toast.makeText(context, "Не удалось снять: ${e.message ?: ""}", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        ) { Text(if (busy) "…" else "Снять список", style = MaterialTheme.typography.titleMedium) }
    }
}

/** Окно «+» в Инвентаризации: проход камерой по этикеткам и снимки списков → список с проверкой → «Добавить». */
@Composable
fun ListPhotoScreen(
    onDone: (List<ScannedLabel>) -> Unit, onCancel: () -> Unit, catalogMode: Boolean = false,
    /** отдельный черновик камеры (например «партия»), чтобы не смешивать с каталогом и инвентаризацией */
    draftTag: String = "",
    /** подпись кнопки завершения вместо «В импорт» */
    doneLabel: String? = null
) {
    val context = LocalContext.current
    val app = context.applicationContext
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) launcher.launch(Manifest.permission.CAMERA) }
    BackHandler { onCancel() }
    if (!hasPermission) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Нужен доступ к камере")
            Spacer(Modifier.height(12.dp))
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Разрешить") }
            TextButton(onClick = onCancel) { Text("Закрыть") }
        }
        return
    }

    var stage by remember { mutableStateOf("camera") } // camera | result
    // записанное камерой хранится на телефоне: при возврате в камеру список продолжается с того же места
    val found = remember { mutableStateListOf<ScannedLabel>().also { it.addAll(LabelDraft.load(app, catalogMode, draftTag)) } }
    LaunchedEffect(Unit) {
        var first = true // первое значение — то, что только что загрузили: срок черновика не продлеваем
        snapshotFlow { found.toList() }.collect { if (first) first = false else LabelDraft.save(app, catalogMode, it, draftTag) }
    }
    val errors = remember { mutableStateListOf<String>() }
    var marks by remember { mutableStateOf<LiveMarks?>(null) }
    var shots by remember { mutableIntStateOf(0) }
    var photoPending by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("") }
    var anyAi by remember { mutableStateOf(false) }
    val mutex = remember { Mutex() }
    val scope = rememberCoroutineScope()
    val engine = remember { LiveLabels(app, found) { marks = it } }
    DisposableEffect(Unit) { onDispose { engine.close() } }
    val pending = photoPending + found.count { it.via == "wait" }

    // снимок списка читается сразу, пока вы листаете дальше; повторы на стыках снимков склеиваются
    fun addShot(b: Bitmap) {
        shots++
        photoPending++
        val n = shots
        scope.launch {
            try {
                mutex.withLock {
                    val r = withContext(Dispatchers.Default) { ListPhoto.read(app, b) { status = "снимок $n: $it" } }
                    if (r.viaAi) anyAi = true
                    for (it in r.items) if (found.none { f -> f.key.substringBefore('#') == it.key }) found.add(it)
                    status = ""
                }
            } catch (e: Exception) {
                errors.add("Снимок $n: " + (e.message ?: "не удалось распознать"))
            } finally {
                photoPending--
            }
        }
    }

    when (stage) {
        "camera" -> PhotoCamera(
            onShot = { addShot(it) },
            onCancel = onCancel,
            engine = engine,
            found = found,
            marks = marks,
            info = (if (catalogMode) {
                if (found.isEmpty() && shots == 0) "Ведите камерой по этикеткам: каждая отметится и запишется. Список с монитора или бумаги — кнопка «Снять список»"
                else "Записано: ${found.size}"
            } else {
                if (found.isEmpty() && shots == 0) "Ведите камерой по этикеткам: каждая отметится и запишется. Нажатие на отметку — ещё такая же строка, долгое нажатие — убрать. Список с монитора — кнопка «Снять список»"
                else "Записано: ${found.size}. Нажатие на отметку — ещё строка, долгое — убрать"
            }) + (if (photoPending > 0) " · читаю ${status.ifBlank { "…" }}" else ""),
            pending = pending,
            allowAnother = !catalogMode,
            onFinish = { stage = "result" }
        )
        else -> Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                if (found.isEmpty()) "Результат" else "Найдено: ${found.size}" + (if (anyAi) " · часть прочитал ИИ" else ""),
                style = MaterialTheme.typography.titleLarge
            )
            if (found.isEmpty() && errors.isEmpty()) {
                Text("Ничего не найдено. Подойдите ближе, держите ровно, без бликов.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            errors.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            if (engine.ignoredCount > 0) {
                TextButton(onClick = { engine.restoreIgnored() }) { Text("Вернуть удалённые этикетки (${engine.ignoredCount})") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) {
                items(found.toList(), key = { it.key }) { l ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                l.name.ifBlank { "Название не прочитано — введёте после" },
                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                            val sub = listOf(
                                if (l.article.isNotBlank()) "арт. ${l.article}" else "",
                                if (l.barcode.isNotBlank()) "ШК ${l.barcode}" else ""
                            ).filter { it.isNotEmpty() }.joinToString(" · ")
                            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { found.removeAll { it.key == l.key } }) { Text("✕") }
                    }
                    HorizontalDivider()
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = { stage = "camera" }) { Text("Ещё снимать") }
                Button(modifier = Modifier.weight(1f), enabled = found.isNotEmpty(), onClick = {
                    val done = found.filter { it.via != "wait" }.toList()
                    LabelDraft.clear(app, catalogMode, draftTag)
                    onDone(done)
                }) { Text((doneLabel ?: if (catalogMode) "В импорт" else "Добавить") + " (${found.size})") }
            }
        }
    }
}
