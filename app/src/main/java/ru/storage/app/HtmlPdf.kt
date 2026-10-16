package ru.storage.app

import android.content.Context
import android.print.PdfWriter
import android.print.PrintAttributes
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/** HTML → PDF (A4, те же поля, что при печати) без окна печати: нужен, чтобы отправить документ на Google Диск. */
object HtmlPdf {
    private fun margins(mm: Int): PrintAttributes.Margins {
        val mils = (mm * 1000 / 25.4).toInt()
        return PrintAttributes.Margins(mils, mils, mils, mils)
    }

    /** Байты PDF или null, если не получилось (или не уложились в 90 секунд). */
    suspend fun render(ctx: Context, html: String): ByteArray? = withTimeoutOrNull(90_000L) {
        withContext(Dispatchers.Main) {
            val app = ctx.applicationContext
            suspendCancellableCoroutine<ByteArray?> { cont ->
                val wv = WebView(app)
                var started = false
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        if (started) return
                        started = true
                        // небольшая пауза: картинки и вёрстка успевают дорисоваться
                        view.postDelayed({
                            val out = File(app.cacheDir, "bw_${System.nanoTime()}.pdf")
                            val attrs = PrintAttributes.Builder()
                                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                                .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
                                .setMinMargins(margins(12))
                                .build()
                            PdfWriter.write(view.createPrintDocumentAdapter("doc"), attrs, out) { err ->
                                val bytes = if (err == null) try { out.readBytes() } catch (e: Exception) { null } else null
                                try { out.delete() } catch (e: Exception) { }
                                try { view.destroy() } catch (e: Exception) { }
                                if (cont.isActive) cont.resume(bytes)
                            }
                        }, 600)
                    }
                }
                cont.invokeOnCancellation { try { wv.destroy() } catch (e: Exception) { } }
                wv.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
            }
        }
    }
}
