package ru.storage.app

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/**
 * Единое оформление всех документов приложения (приём B-Ware, опись, акты приёмки):
 *  • шапка «LAGER / WARENEINGANG» + адрес из Настройки → Печать — на каждом листе;
 *  • слева значения (отправитель, заказ, машина…), справа название документа и пометка «INTERN – FÜR BÜRO»;
 *  • таблица без сетки: линия только под заголовком, без линии перед «Summe»;
 *  • внизу: QR-код (если есть) и подпись — линия, а надпись под линией;
 *  • нижняя линия и текст «Dies ist ein internes Dokument…» — на каждом листе.
 * Повтор шапки и подвала на каждом листе делают thead/tfoot и position:fixed при печати (Chromium WebView).
 */
object DocLayout {

    const val CSS =
        "@page{margin:15mm}" +
            "*{box-sizing:border-box}" +
            "body{font-family:sans-serif;font-size:12px;color:#111;margin:0;padding:0 4mm}" +
            "table.frame{width:100%;border-collapse:collapse}" +
            "table.frame>thead>tr>td,table.frame>tbody>tr>td,table.frame>tfoot>tr>td{padding:0;border:none}" +
            ".dh{display:flex;justify-content:space-between;align-items:flex-start;gap:12px}" +
            ".b1{font-size:30px;font-weight:bold;line-height:1.05}" +
            ".b2{font-size:22px;font-weight:bold;line-height:1.15}" +
            ".addr{text-align:right;font-size:11px;line-height:1.4;color:#222}" +
            ".rule{border-top:1.5px solid #222;margin:12px 0 18px}" +
            ".top{display:flex;justify-content:space-between;align-items:flex-start;gap:14px}" +
            ".iv{flex:0 0 38%}.iv div{font-size:13px;margin-bottom:12px;overflow-wrap:anywhere}" +
            ".ivl{color:#555;font-size:11px}" +
            ".tt{flex:1 1 auto;text-align:right}" +
            ".t1{font-size:19px;font-weight:bold;text-transform:uppercase}" +
            ".t1s{font-size:13px;font-weight:bold;color:#444;text-transform:uppercase;margin-top:6px}" +
            ".t2{font-size:16px;font-weight:bold}.t2s{font-size:12px;font-weight:bold;color:#444}" +
            ".t3{font-size:14px;font-weight:bold;margin-top:8px}" +
            ".fin{display:flex;flex-direction:column;justify-content:space-between;min-height:218mm}" +
            ".main{padding-top:26px}" +
            ".bot{display:flex;justify-content:space-between;align-items:flex-end;gap:16px;margin-top:30px}" +
            ".qrb{display:flex;align-items:center;gap:10px;font-size:11px;color:#222}" +
            ".sg{width:250px;max-width:60%}" +
            ".line{border-top:1px solid #111;padding-top:3px;font-size:11px;color:#333}" +
            "table.dt{width:100%;border-collapse:collapse}" +
            "table.dt th{text-align:left;font-weight:bold;padding:4px 5px;vertical-align:bottom}" +
            "table.dt tr:first-child th{border-bottom:1.5px solid #222;padding-bottom:6px}" +
            "table.dt td{padding:6px 5px;vertical-align:top}" +
            "table.dt tr.tot th,table.dt tr.tot td{padding-top:14px}" +
            ".n{text-align:right;white-space:nowrap}" +
            ".sub{color:#444;font-size:11px;margin-top:3px}" +
            ".bad{color:#b00020;font-weight:bold}.warn{color:#9a6700;font-weight:bold}.ok{color:#1b6e2d}" +
            "h2{font-size:15px;margin:18px 0 6px}" +
            ".ffix .frule{border-top:1px solid #222;margin-bottom:5px}" +
            ".ffix .frow{display:flex;justify-content:space-between;font-size:10px;color:#222}" +
            ".phrow{break-before:page;page-break-before:always}" +
            "@media screen{.ffix{margin-top:14px}.fspace{display:none}}" +
            "@media print{.ffix{position:fixed;left:0;right:0;bottom:0;background:#fff}.fspace{height:15mm}}" +
            Signature.CSS

    fun esc(s: String) =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** Шапка: слева «LAGER / WARENEINGANG», справа адрес из настроек. Повторяется на каждом листе (thead). */
    fun header(): String {
        val addr = PrintCfg.address.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString("<br>") { esc(it) }
        return "<div class='dh'><div><div class='b1'>LAGER</div><div class='b2'>WARENEINGANG</div></div>" +
            "<div class='addr'>$addr</div></div><div class='rule'></div>"
    }

    /**
     * Название документа справа: первая строка (ключ k1) и вторая (k2) на каждом включённом языке,
     * первый язык крупно, остальные мельче; ниже пометка «INTERN – FÜR BÜRO».
     */
    fun title(k1: String, k2: String? = null): String {
        val a = trList(k1)
        val b = if (k2 != null) trList(k2) else emptyList()
        val sb = StringBuilder()
        a.forEachIndexed { i, t ->
            sb.append("<div class='${if (i == 0) "t1" else "t1s"}'>").append(esc(t)).append("</div>")
            if (i < b.size) sb.append("<div class='${if (i == 0) "t2" else "t2s"}'>").append(esc(b[i])).append("</div>")
        }
        sb.append("<div class='t3'>").append(esc(trList("internal").joinToString(" / "))).append("</div>")
        return sb.toString()
    }

    /** Левый блок: строки (подпись, значение). Пустая подпись — только значение, как в образце. */
    fun info(rows: List<Pair<String, String>>): String {
        val sb = StringBuilder("<div class='iv'>")
        rows.forEach { (label, value) ->
            sb.append("<div>")
            if (label.isNotBlank()) sb.append("<span class='ivl'>").append(esc(label)).append(":</span> ")
            sb.append(if (label.isBlank()) esc(value) else "<b>${esc(value)}</b>").append("</div>")
        }
        return sb.append("</div>").toString()
    }

    fun top(infoHtml: String, titleHtml: String): String =
        "<div class='top'>$infoHtml<div class='tt'>$titleHtml</div></div>"

    /** QR-код как векторная картинка (SVG): печатается чётко и не требует картинок на диске. */
    fun qrSvg(text: String, size: Int = 110): String = try {
        val hints = mapOf<EncodeHintType, Any>(
            EncodeHintType.MARGIN to 0,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val n = m.width
        val full = n + 4
        val sb = StringBuilder()
        sb.append("<svg xmlns='http://www.w3.org/2000/svg' viewBox='-2 -2 $full $full' width='$size' height='$size' ")
            .append("shape-rendering='crispEdges'><rect x='-2' y='-2' width='$full' height='$full' fill='#fff'/>")
            .append("<path fill='#000' d='")
        for (y in 0 until n) for (x in 0 until n) if (m.get(x, y)) sb.append("M$x,${y}h1v1h-1z")
        sb.append("'/></svg>")
        sb.toString()
    } catch (e: Exception) {
        ""
    }

    /** Блок «QR-код + надпись» слева внизу. Пустой текст QR — пустая строка. */
    fun qrBlock(payload: String): String {
        if (payload.isBlank()) return ""
        val svg = qrSvg(payload)
        if (svg.isEmpty()) return ""
        return "<div class='qrb'>$svg<div>${esc(trList("qr_label").joinToString(" / "))}</div></div>"
    }

    /** Нижняя линия + текст под ней + номер листа. На экране — в конце документа, при печати — внизу каждого листа. */
    fun footer(): String {
        return "<div class='ffix'><div class='frule'></div><div class='frow'><span>" +
            esc(trList("footer_note").joinToString(" / ")) + "</span><span></span></div></div>"
    }

    /** Номер листа в поле страницы («Seite 1 von 3»), если движок печати поддерживает поля страницы с счётчиками. */
    private fun pageNumberCss(): String {
        val w = trList("page_word").first().replace("\"", "")
        val o = trList("of_word").first().replace("\"", "")
        return "@page{@bottom-right{content:\"$w \" counter(page) \" $o \" counter(pages);font-size:8pt;color:#222}}"
    }

    /**
     * Готовая страница. top — верхний блок (значения слева, название справа), body — таблицы,
     * bottom — QR и подпись (прижимается к низу листа).
     */
    fun page(top: String, body: String, bottom: String, extraCss: String = "", after: String = ""): String {
        val sb = StringBuilder()
        sb.append("<html><head><meta charset='utf-8'><style>")
            .append(CSS).append(pageNumberCss()).append(extraCss).append("</style></head><body>")
        sb.append("<table class='frame'>")
        sb.append("<thead><tr><td>").append(header()).append("</td></tr></thead>")
        sb.append("<tfoot><tr><td><div class='fspace'></div></td></tr></tfoot>")
        sb.append("<tbody><tr><td><div class='fin'><div>")
        sb.append(top).append("<div class='main'>").append(body).append("</div></div>")
        sb.append("<div class='bot'>").append(bottom).append("</div>")
        sb.append("</div></td></tr>")
        // after — дополнительные листы (фото) внутри той же таблицы: шапка и поле под подвал повторяются и на них
        if (after.isNotBlank()) sb.append("<tr class='phrow'><td>").append(after).append("</td></tr>")
        sb.append("</tbody></table>")
        sb.append(footer())
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Правая часть низа: подпись над линией, надпись «Unterschrift des Empfängers:» под линией. */
    fun signBlock(receiver: String, ms: Long): String =
        "<div class='sg'>" + Signature.cell("sig_recipient", receiver, ms) + "</div>"
}
