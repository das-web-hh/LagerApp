package ru.storage.app

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.app.PendingIntent
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import kotlin.concurrent.thread

private fun Context.activity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

/**
 * Сканер документа (до 30 листов → PDF) для главного поиска и виджета.
 * После «Далее» скан ставится в очередь на Google Диск и открывается мини-окно: «Автоприём» или «По наименованию».
 * Дальше всё как у любого файла: распознавание номера заказа и отправителя, автосохранение или список для подсчёта.
 * Возвращает функцию запуска сканера.
 */
/** Сканер запущен кнопкой виджета с рабочего стола: если сканирование отменили, приложение сворачивается обратно, а не открывает главное окно. */
object ScanOrigin {
    @Volatile var fromWidget = false
}

@Composable
fun rememberDocScanner(): () -> Unit {
    val ctx = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val fromWidget = ScanOrigin.fromWidget
        ScanOrigin.fromWidget = false
        if (res.resultCode != Activity.RESULT_OK) {
            // крестик в сканере или «Назад»: из виджета — сразу назад на рабочий стол (приложение остаётся в фоне со своим состоянием)
            if (fromWidget) ctx.activity()?.moveTaskToBack(true)
        } else {
            val pdf0 = GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri
            if (pdf0 != null) thread {
                // кривой скан (сшитые листы, угол под степлером) выравнивается; ровный остаётся как есть
                val pdf = ScanFix.fix(ctx, pdf0)
                val db = ProductDb.get(ctx)
                val inv = Media.importUri(ctx, pdf, db, "IN")
                Media.uploadPending(ctx, db)
                // startMode = null → файл попадает в очередь выбора, на экране появляется окно с двумя кнопками
                Incoming.accept(ctx, pdf, null, driveName = inv ?: "")
            }
        }
    }
    // Второй режим: наш сканер (включается в настройках «Сканер документов»)
    val ownLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val fromWidget = ScanOrigin.fromWidget
        ScanOrigin.fromWidget = false
        val pdf = if (res.resultCode == Activity.RESULT_OK) OwnScan.pdfFrom(res.data) else null
        if (pdf == null) {
            if (fromWidget) ctx.activity()?.moveTaskToBack(true)
        } else thread {
            val db = ProductDb.get(ctx)
            val inv = Media.importUri(ctx, pdf, db, "IN")
            Media.uploadPending(ctx, db)
            Incoming.accept(ctx, pdf, null, driveName = inv ?: "")
        }
    }
    return start@{
        val act = ctx.activity() ?: return@start
        if (OwnScan.enabled(ctx)) {
            ownLauncher.launch(OwnScan.intent(act))
            return@start
        }
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(false)
            .setPageLimit(30)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(act)
            .addOnSuccessListener { sender -> launcher.launch(IntentSenderRequest.Builder(sender).build()) }
            .addOnFailureListener {
                ScanOrigin.fromWidget = false
                Toast.makeText(ctx, "Сканер недоступен: " + (it.message ?: ""), Toast.LENGTH_LONG).show()
            }
    }
}

/** Виджет на главный экран телефона: одна кнопка, открывает приложение сразу на сканере документа. */
class ScanWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context))
    }

    private fun views(ctx: Context): RemoteViews {
        val v = RemoteViews(ctx.packageName, R.layout.widget_scan)
        val i = Intent(ctx, MainActivity::class.java)
            .putExtra("open", "scan")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(ctx, 7001, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        v.setOnClickPendingIntent(R.id.widget_root, pi)
        return v
    }
}
