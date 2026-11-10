package ru.storage.app

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * «Сканирование с ИИ» для партии: бумажный список снимается сканером документа (края находятся сами, лист выравнивается),
 * каждый лист в чёрно-белом виде уходит в ИИ той же задачей «recognize», что и накладные в «Приёме»:
 * она читает название, печатное количество, штрих-код и пометки ручкой (галочка, число). Текст запроса не новый,
 * промпты в Google Script не меняются.
 *  • число, написанное ручкой → столько штук уже найдено;
 *  • галочка ручкой → найдено всё количество позиции;
 *  • крестик / вычеркнутая строка → позиция остаётся в списке без отметки.
 */
object BatchAiScan {
    /** Лист, прочитанный ИИ → строки партии. */
    internal fun rowsOf(items: List<AiItem>): List<BatchRow> = items.map { ai ->
        val plan = batchQty(ai.qty)
        val hand = parseQ(ai.handQty)
        val done = when {
            hand > 0.0 -> fmtQ(hand)
            ai.mark == "check" -> plan
            else -> ""
        }
        BatchRow(ai.name.trim(), plan, ai.barcode, "", done)
    }

    /** Все листы PDF → строки партии. Вызывать из корутины; [onStatus] сообщает, какой лист читается. */
    suspend fun read(ctx: Context, pdf: Uri, onStatus: (String) -> Unit): List<BatchRow> = withContext(Dispatchers.IO) {
        val app = ctx.applicationContext
        val tmp = File(app.cacheDir, "batchscan_" + System.nanoTime() + ".pdf")
        val input = app.contentResolver.openInputStream(pdf) ?: throw RuntimeException("Не удалось открыть скан")
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val out = ArrayList<BatchRow>()
        val doc = openPageSource(tmp)
        try {
            val n = doc.count
            for (i in 0 until n) {
                ensureActive()
                onStatus("ИИ читает лист ${i + 1} из $n…")
                var png = doc.renderBw(i)
                // пустой лист (кроме первого) в ИИ не отправляем: экономия токенов
                if (i > 0 && isBlankPage(png)) continue
                var items: List<AiItem>? = null
                var err: Exception? = null
                AutoReceiveHolder.aiLock.withLock {
                    for (a in 1..3) {
                        try {
                            // со 2-й попытки — серая картинка покрупнее вместо чёрно-белой
                            if (a == 2) png = try { doc.renderBw(i, 1700, false) } catch (e: Exception) { png }
                            items = Ai.recognize(app, png, i + 1, n, "", true).items
                            break
                        } catch (e: Exception) {
                            err = e
                        }
                    }
                }
                val got = items ?: throw RuntimeException("Лист ${i + 1}: " + (err?.message ?: "ошибка ИИ"))
                out.addAll(rowsOf(got))
            }
        } finally {
            try { doc.close() } catch (e: Exception) { }
            tmp.delete()
        }
        out
    }
}

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Сканер документа для партии (сканер Google или наш — как выбрано в настройках «Сканер документов»).
 * Возвращает функцию запуска; готовый PDF приходит в [onPdf], отмена — в [onCancel].
 */
@Composable
fun rememberBatchScanner(onPdf: (Uri) -> Unit, onCancel: () -> Unit): () -> Unit {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pdfCb by rememberUpdatedState(onPdf)
    val cancelCb by rememberUpdatedState(onCancel)
    val gms = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val pdf0 = if (res.resultCode == Activity.RESULT_OK) GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri else null
        if (pdf0 == null) cancelCb()
        else scope.launch {
            // кривой скан выравнивается; ровный остаётся как есть
            val fixed = withContext(Dispatchers.IO) { ScanFix.fix(ctx, pdf0) }
            pdfCb(fixed)
        }
    }
    val own = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val pdf = if (res.resultCode == Activity.RESULT_OK) OwnScan.pdfFrom(res.data) else null
        if (pdf == null) cancelCb() else pdfCb(pdf)
    }
    return start@{
        val act = ctx.findActivity() ?: return@start
        if (OwnScan.enabled(ctx)) {
            own.launch(OwnScan.intent(act))
            return@start
        }
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { sender -> gms.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                Toast.makeText(ctx, "Сканер недоступен: " + (it.message ?: ""), Toast.LENGTH_LONG).show()
                cancelCb()
            }
    }
}
