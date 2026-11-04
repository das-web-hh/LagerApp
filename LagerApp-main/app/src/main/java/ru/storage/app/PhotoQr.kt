package ru.storage.app

import android.content.Context
import android.webkit.MimeTypeMap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * QR на напечатанном документе со ссылкой на фото этого документа (приём, инвентаризация, брак).
 * Для документа создаётся отдельная папка на Google Диске, открытая по ссылке только на просмотр; в неё кладутся цветные
 * оригиналы фото (на бумаге они чёрно-белые). QR ведёт только в эту папку: другие фото Диска по нему не открываются.
 * Включается в Настройках → Печать. Нужны настроенный Google Диск и обновлённый скрипт Диска (действие mkshare).
 */
object PhotoQr {
    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("photoqr", Context.MODE_PRIVATE)

    fun enabled(ctx: Context) = sp(ctx).getBoolean("on", false)
    fun setEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("on", v).apply()

    private fun toast(ctx: Context, text: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            Toast.makeText(ctx.applicationContext, text, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Блок QR для низа листа. Пустая строка: функция выключена, у документа нет фото, Диск не настроен или нет связи
     * (документ тогда печатается без QR, на экране сообщение).
     */
    suspend fun html(ctx: Context, number: String, photoNames: List<String>): String {
        val app = ctx.applicationContext
        if (!enabled(app)) return ""
        val names = photoNames.filter { it.isNotBlank() }.distinct()
        if (names.isEmpty()) return ""
        if (!DriveCfg.ready(app)) {
            toast(app, "QR с фото не создан: Google Диск не настроен")
            return ""
        }
        return try {
            val url = withContext(Dispatchers.IO) { publish(app, number, names) }
            block(url, number)
        } catch (e: Exception) {
            toast(app, "QR с фото не создан: ${e.message ?: e.javaClass.simpleName}. Проверьте связь и обновите скрипт Диска.")
            ""
        }
    }

    private suspend fun publish(app: Context, number: String, names: List<String>): String {
        val safe = number.filter { it.isLetterOrDigit() || it == '_' }.take(30).ifBlank { "DOC" }
        val (folderId, url) = DriveApi.makeShare(app, safe)
        for (n in names) {
            val f = Media.load(app, n) ?: continue
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "image/jpeg"
            // цветной оригинал как есть; повторная отправка не создаёт дубль
            DriveApi.uploadBytes(app, n, mime, f.readBytes(), folderId)
        }
        return url
    }

    /** QR (векторный), под ним горизонтальная линия, подпись «Фото» и номер документа. */
    fun block(url: String, number: String): String {
        val label = DocLayout.esc(trList("photos").joinToString(" / "))
        return "<style>.pq{display:inline-block;text-align:left}.pq .ln{border-top:1px solid #111;width:112px;margin:3px 0 2px}" +
            ".pq .tx{font-size:10px;color:#222;line-height:1.25}</style>" +
            "<div class='pq'>${DocLayout.qrSvg(url, 96)}<div class='ln'></div>" +
            "<div class='tx'><b>$label</b><br>№ ${DocLayout.esc(number)}</div></div>"
    }
}
