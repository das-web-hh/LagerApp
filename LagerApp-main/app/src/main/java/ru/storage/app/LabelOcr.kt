package ru.storage.app

import android.content.Context
import android.graphics.Bitmap
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Данные, извлечённые с этикетки. Пустая строка — поле не найдено. */
class LabelData(
    val tracking: String = "",
    val product: String = "",
    val recipient: String = "",
    val street: String = "",
    val zip: String = "",
    val city: String = "",
    val country: String = "",
    val sender: String = "",
    val weight: String = "",
    val routing: String = "",
    val billing: String = "",
    val reference: String = "",
    val services: String = ""
)

/**
 * Разбор этикетки DHL без ИИ: штрих-коды (ML Kit) + текст (ML Kit OCR, работает на телефоне) + регулярные выражения.
 *
 * Понимает все основные макеты:
 *  • «Paket bis 5/10 kg» (Online-Frankierung, Muster) — номер 12 цифр, Identcode 35.871.913052 9, Leitcode 90427.109.044.00.0;
 *  • Common Label «DHL PAKET» (PLG@DHL Business Portal, 2019+) — номер 00340434…(20 цифр), (403)…+99…, Abrechnungsnr;
 *  • англоязычные (From / To / Shipment No. / Billing No. / Routingcode / License Plate), в т.ч. из ПО отправителя;
 *  • новый макет 2020+ (Absendername / Empfängername, Sendungsreferenz, Nachnahme «Betrag», Filial-Routing, GoGreen, 10+ kg);
 *  • JJD… (DHL Paket International / Express), S10 (RR123456789DE).
 * Поля: номер, продукт, получатель, адрес, отправитель, вес, Leitcode, номер расчёта, референс, отметки (услуги).
 * Распознанный текст сохраняется в посылке целиком — эвристики можно донастроить по реальным снимкам.
 */
object LabelParser {
    private val RE_JJD = Regex("JJD\\d{10,22}")
    private val RE_20 = Regex("(?<!\\d)00\\d{18}(?!\\d)")
    private val RE_12 = Regex("(?<!\\d)\\d{12}(?!\\d)")
    private val RE_S10 = Regex("(?<![A-Z0-9])[A-Z]{2}\\d{9}[A-Z]{2}(?![A-Z0-9])")
    private val RE_ZIP = Regex("(?i)^(?:(?:D|DE)[- ]?)?(\\d{5})\\s+(\\p{L}[\\p{L}\\s.\\-/()]*)$")
    private val RE_WEIGHT = Regex("(?i)(?<![\\d.,])(\\d{1,3}(?:[.,]\\d{1,3})?)\\s?kg")
    private val RE_WEIGHT_LABEL = Regex("(?i)gewicht|weight")
    private val RE_STREET = Regex(
        "(?i)stra(ß|ss)e|str\\.|weg\\b|platz|allee|gasse|ring\\b|damm|ufer|chaussee|packstation|postfiliale|postfach"
    )
    /** Строки, которые не бывают именем получателя. */
    private val RE_NOISE = Regex(
        "(?i)dhl|paket|päckchen|paeckchen|gogreen|go green|absender|sender|\\bkg\\b|leitcode|ident|sendung|www\\.|http|@|" +
            "deutsche post|nicht nachsenden|retoure|international|kontakt|tel\\.|abrechnung|billing|gewicht|anzahl|" +
            "weight|piece|empf(ä|ae)nger:|receiver|entgelt|postage|porto"
    )
    /** Строки, которые не бывают частью блока отправителя («Sender Str. 4» — это улица, поэтому слово sender не запрещено). */
    private val RE_SENDER_NOISE = Regex(
        "(?i)kontakt|tel\\.|phone|empf(ä|ae)nger|receiver|abrechnung|billing|gewicht|weight|anzahl|piece|" +
            "sendungsnr|sendungsnummer|shipment|ident|leitcode|routing|gogreen|go green|www\\.|@|entgelt|postage"
    )
    private val RE_SENDER_LABEL = Regex("(?i)^(?:absender|sender|from|von)(?:\\s*/\\s*(?:from|von|absender|sender))?\\s*:")
    private val RE_RCPT_LABEL = Regex("(?i)^(?:an|to)(?:\\s*/\\s*(?:to|an))?\\s*:")
    private val RE_SENDER_PREFIX = Regex("(?i)^(?:absender|sender|from|von)(?:\\s*/\\s*(?:from|von|absender|sender))?\\s*:\\s*")
    private val RE_RCPT_PREFIX = Regex("(?i)^(?:an|to)(?:\\s*/\\s*(?:to|an))?\\s*:\\s*")
    private val RE_TRK_LABEL = Regex("(?i)ndungs?\\s?(?:nr|nummer)|shipment|ident|packst")
    private val RE_NOT_TRK = Regex("(?i)abrechnung|referenz|billing|ref\\.|warenkorb|leitcode|routing")
    private val RE_ROUTE_TXT = Regex("(?i)\\(?\\s*403\\s*\\)?\\s*([\\d ]{8,11}?)\\s*\\+\\s*([\\d ]{12,18})")
    private val RE_LEIT = Regex("(?<!\\d)\\d{5}\\.\\d{3}\\.\\d{3}\\.\\d{2,3}(?:\\.\\d)?(?!\\d)")
    private val RE_BILLING = Regex("(?i)(?:abrechnungsnr|brechnungsnr|billing\\s*no)\\.?\\s*:?\\s*([\\d ]{10,24})")
    private val RE_REF = Regex(
        "(?i)(?:referenznr|eferenznr|sendungsreferenz|ref\\.?\\s*no|warenkorb-id)\\.?\\s*:?\\s*([A-Za-z0-9][A-Za-z0-9\\-/._]{2,40})"
    )
    private val RE_COD = Regex("(?i)betrag:?\\s*([\\d.]+,\\d{2})\\s*(?:EUR|€)")
    private val RE_BIS = Regex("(?i)paket\\s+bis\\s+(\\d{1,2})\\s*kg")

    private fun compact(s: String) = s.replace(Regex("\\s"), "").uppercase()
    private fun nz(s: String) = s.filter { it.isLetterOrDigit() }.uppercase()
    private fun digits(s: String) = s.filter { it.isDigit() }

    private fun trackingFrom(s: String): String? {
        RE_JJD.find(s)?.let { return it.value }
        RE_20.find(s)?.let { return it.value }
        return null
    }

    private fun trackingFromLabelLine(l: String): String? {
        if (!RE_TRK_LABEL.containsMatchIn(l) || RE_NOT_TRK.containsMatchIn(l)) return null
        val d = digits(l.substringAfter(':', l))
        if (d.length == 20 && d.startsWith("00")) return d
        if (d.length == 12) return d
        return null
    }

    private fun formatRouting(c: String): String =
        if (c.startsWith("403") && c.length == 25) "(403)" + c.substring(3, 11) + "+" + c.substring(11) else c

    private fun findWeight(text: String, lines: List<String>): String {
        for (l in lines) {
            if (RE_WEIGHT_LABEL.containsMatchIn(l)) {
                RE_WEIGHT.find(l)?.let { return it.groupValues[1].replace(',', '.') }
            }
        }
        for (m in RE_WEIGHT.findAll(text)) {
            val before = text.substring(maxOf(0, m.range.first - 8), m.range.first).lowercase()
            // «Paket bis 10 kg», «max. 31,5 kg» — это ограничение тарифа, а не вес посылки
            if (before.contains("bis") || before.contains("max")) continue
            return m.groupValues[1].replace(',', '.')
        }
        return ""
    }

    private fun findServices(text: String): String {
        val up = text.uppercase()
        val out = ArrayList<String>()
        val cod = RE_COD.find(text)
        if (cod != null) out.add("Nachnahme " + cod.groupValues[1] + " EUR")
        else if (up.contains("NACHNAHME")) out.add("Nachnahme")
        if (up.contains("SCHWERES")) out.add("Schweres Paket")
        if (up.contains("SPERRGUT")) out.add("Sperrgut")
        if (up.contains("FILIAL-ROUTING") || up.contains("FILIAL ROUTING") || up.contains("FILIALROUTING")) out.add("Filial-Routing")
        if (up.contains("GOGREEN") || up.contains("GO GREEN")) out.add("GoGreen")
        if (up.contains("EXPRESS")) out.add("Express")
        if (up.contains("RETOURE")) out.add("Retoure")
        if (up.contains("SICHTPR") || Regex("Ü\\s?(16|18)").containsMatchIn(up)) out.add("Alterssichtprüfung")
        if (up.contains("IDENT-CHECK") || up.contains("IDENTCHECK")) out.add("Ident-Check")
        if (up.contains("MUSTER")) out.add("МУСТЕР (образец)")
        return out.joinToString(", ")
    }

    private fun normCountry(c: String): String = when (c.trim().lowercase()) {
        "germany", "deutschland", "de", "allemagne" -> "Deutschland"
        else -> c.trim().lowercase().replaceFirstChar { it.uppercase() }
    }

    fun parse(text: String, barcodes: List<String>): LabelData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val codes = barcodes.map { it.filter { ch -> ch.isLetterOrDigit() }.uppercase() }

        // ---- номер посылки ----
        var tracking = codes.firstNotNullOfOrNull { trackingFrom(it) } ?: ""
        if (tracking.isEmpty()) tracking = lines.firstNotNullOfOrNull { trackingFrom(nz(it)) } ?: ""
        if (tracking.isEmpty()) tracking = lines.firstNotNullOfOrNull { trackingFromLabelLine(it) } ?: ""
        if (tracking.isEmpty()) tracking = codes.firstOrNull { Regex("^\\d{12}$").matches(it) } ?: ""
        if (tracking.isEmpty()) tracking = codes.firstNotNullOfOrNull { RE_S10.find(it)?.value } ?: ""
        if (tracking.isEmpty()) tracking = lines.firstNotNullOfOrNull { RE_S10.find(compact(it))?.value } ?: ""

        // ---- Leitcode / Routingcode ----
        var routing = codes.firstOrNull { c ->
            c != tracking && c.all { it.isDigit() } && (c.startsWith("403") || c.length == 14)
        }?.let { formatRouting(it) } ?: ""
        if (routing.isEmpty()) {
            RE_ROUTE_TXT.find(text)?.let { m ->
                val a = digits(m.groupValues[1])
                val b = digits(m.groupValues[2])
                if (a.length == 8 && b.length == 14) routing = "(403)$a+$b"
            }
        }
        if (routing.isEmpty()) RE_LEIT.find(text)?.let { routing = digits(it.value) }

        // ---- продукт ----
        val up = text.uppercase()
        val product = when {
            up.contains("KLEINPAKET") -> "DHL Kleinpaket"
            up.contains("PÄCKCHEN") || up.contains("PAECKCHEN") -> "DHL Päckchen"
            up.contains("WARENPOST") -> "Warenpost"
            up.contains("EXPRESS") -> "DHL Express"
            up.contains("RETOURE") -> "DHL Retoure"
            RE_BIS.containsMatchIn(text) -> "DHL Paket bis " + RE_BIS.find(text)!!.groupValues[1] + " kg"
            up.contains("PAKET") || up.contains("PARCEL") -> "DHL Paket"
            tracking.startsWith("JJD") || tracking.startsWith("00340") -> "DHL Paket"
            else -> ""
        }

        // ---- вес ----
        val weight = findWeight(text, lines)

        // ---- номер расчёта и референс ----
        var billing = ""
        var reference = ""
        for (l in lines) {
            if (billing.isEmpty()) RE_BILLING.find(l)?.let { billing = digits(it.groupValues[1]) }
            if (reference.isEmpty()) RE_REF.find(l)?.let { reference = it.groupValues[1].trim() }
        }
        if (billing.length < 10) billing = ""

        // ---- отправитель ----
        var sender = ""
        var senderEnd = -1
        val sIdx = lines.indexOfFirst { RE_SENDER_LABEL.containsMatchIn(it) }
        if (sIdx >= 0) {
            // блок отправителя заканчивается там, где начинается получатель («An:» / «To:»)
            val rIdx = lines.indices.firstOrNull { it > sIdx && RE_RCPT_LABEL.containsMatchIn(lines[it]) } ?: lines.size
            val z = (sIdx until minOf(rIdx, sIdx + 7, lines.size)).firstOrNull { RE_ZIP.matches(lines[it]) }
            if (z != null) {
                senderEnd = z
                val parts = lines.subList(sIdx, z + 1).mapIndexed { i, l ->
                    if (i == 0) l.replace(RE_SENDER_PREFIX, "").trim() else l
                }.filter { it.isNotBlank() && !RE_SENDER_NOISE.containsMatchIn(it) }
                sender = parts.joinToString(", ")
            } else {
                val rest = lines[sIdx].replace(RE_SENDER_PREFIX, "").trim()
                sender = rest.ifEmpty { lines.getOrNull(sIdx + 1) ?: "" }
                senderEnd = sIdx
            }
        } else {
            val l = lines.firstOrNull {
                it.length > 20 && Regex("\\d{5}").containsMatchIn(it) &&
                    Regex("[,·|]").containsMatchIn(it) && !RE_ZIP.matches(it)
            }
            if (l != null) {
                sender = l
                senderEnd = lines.indexOf(l)
            }
        }

        // ---- получатель ----
        var zip = ""
        var city = ""
        var street = ""
        var recipient = ""
        var country = ""
        val zipIdx = lines.indices.firstOrNull { it > senderEnd && RE_ZIP.matches(lines[it]) } ?: -1
        if (zipIdx >= 0) {
            val m = RE_ZIP.find(lines[zipIdx])!!
            zip = m.groupValues[1]
            city = m.groupValues[2].trim()
            var streetIdx = zipIdx - 1
            if (streetIdx >= 0 && streetIdx > senderEnd) {
                val cand = lines[streetIdx].replace(RE_RCPT_PREFIX, "").trim()
                if (cand.isNotEmpty() && (Regex("\\d").containsMatchIn(cand) || RE_STREET.containsMatchIn(cand))) {
                    street = cand
                } else {
                    streetIdx = zipIdx
                }
            } else {
                streetIdx = zipIdx
            }
            val names = ArrayList<String>()
            var i = streetIdx - 1
            while (i >= 0 && i > senderEnd && names.size < 3) {
                val raw = lines[i]
                val hasLabel = RE_RCPT_LABEL.containsMatchIn(raw)
                val l = raw.replace(RE_RCPT_PREFIX, "").trim()
                if (l.isEmpty() || l.length < 3) break
                if (RE_NOISE.containsMatchIn(l) || RE_ZIP.matches(l)) break
                if (l.count { it.isDigit() } * 2 > l.length) break
                names.add(0, l)
                if (hasLabel) break
                i--
            }
            recipient = names.joinToString(" / ")
            val next = lines.getOrNull(zipIdx + 1)
            country = if (next != null && next.length in 4..24 && next.all { it.isLetter() || it == ' ' } &&
                !RE_NOISE.containsMatchIn(next)
            ) normCountry(next) else "Deutschland"
        }

        return LabelData(
            tracking = tracking, product = product, recipient = recipient, street = street,
            zip = zip, city = city, country = country, sender = sender, weight = weight, routing = routing,
            billing = billing, reference = reference, services = findServices(text)
        )
    }
}

private class PageRead(val text: String, val codes: List<String>)

/**
 * Фоновое распознавание этикеток: снимки → OCR + штрих-коды → поля посылки → база и Firebase.
 * Партии идут по одной; если приложение закрыли посреди работы, незаконченные посылки добираются при следующем запуске.
 * Если на снимке номер не найден, страница пробуется ещё повёрнутой на 90/180/270° (этикетку часто снимают боком).
 */
object LabelProcessor {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val queued = ConcurrentHashMap.newKeySet<String>()

    fun enqueue(ctx: Context, id: String) {
        val app = ctx.applicationContext
        if (!queued.add(id)) return
        scope.launch {
            try {
                mutex.withLock { process(app, id) }
            } catch (e: Exception) {
            } finally {
                queued.remove(id)
            }
        }
    }

    /** Добрать посылки, у которых анализ не закончился (только если снимки лежат на этом телефоне). */
    fun resume(ctx: Context) {
        val app = ctx.applicationContext
        scope.launch {
            val db = SendDb.get(app)
            db.allPakets()
                .filter { (it.status == "new" || it.status == "proc") && it.photos.any { n -> Media.file(app, n).exists() } }
                .forEach { enqueue(app, it.id) }
        }
    }

    private suspend fun readPage(bmp: Bitmap, rec: TextRecognizer, scanner: BarcodeScanner, rot: Int): PageRead {
        val img = InputImage.fromBitmap(bmp, rot)
        var text = ""
        val codes = ArrayList<String>()
        try {
            text = rec.process(img).awaitIt().text
        } catch (e: Exception) {
        }
        try {
            scanner.process(img).awaitIt().forEach { b -> b.rawValue?.let { codes.add(it) } }
        } catch (e: Exception) {
        }
        return PageRead(text, codes)
    }

    private suspend fun process(app: Context, id: String) {
        val db = SendDb.get(app)
        val start = db.getPaket(id) ?: return
        SendCloud.savePaketLocal(db, start.copy(status = "proc"))

        val texts = ArrayList<String>()
        val codes = ArrayList<String>()
        var anyImage = false
        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_ALL_FORMATS).build()
        )
        try {
            for (n in start.photos) {
                if (!Media.isImage(n)) continue
                val f = Media.load(app, n) ?: continue
                val bmp = Media.thumb(f, 1800) ?: continue
                var best = readPage(bmp, rec, scanner, 0)
                if (LabelParser.parse(best.text, best.codes).tracking.isBlank()) {
                    for (rot in intArrayOf(90, 180, 270)) {
                        val r = readPage(bmp, rec, scanner, rot)
                        if (LabelParser.parse(r.text, r.codes).tracking.isNotBlank()) {
                            best = r
                            break
                        }
                    }
                }
                if (best.text.isNotBlank() || best.codes.isNotEmpty()) anyImage = true
                texts.add(best.text)
                codes.addAll(best.codes)
            }
        } finally {
            rec.close()
            scanner.close()
        }

        // посылка могла измениться, пока шёл анализ (правка руками) — берём свежую
        val cur = db.getPaket(id) ?: return
        if (!anyImage) {
            SendCloud.savePaket(db, cur.copy(status = "error"))
            return
        }
        val raw = texts.joinToString("\n\n")
        val d = LabelParser.parse(raw, codes)
        fun pick(old: String, new: String) = if (old.isNotBlank()) old else new
        var merged = cur.copy(
            tracking = pick(cur.tracking, d.tracking),
            product = pick(cur.product, d.product),
            recipient = pick(cur.recipient, d.recipient),
            street = pick(cur.street, d.street),
            zip = pick(cur.zip, d.zip),
            city = pick(cur.city, d.city),
            country = pick(cur.country, d.country),
            sender = pick(cur.sender, d.sender),
            weight = pick(cur.weight, d.weight),
            routing = pick(cur.routing, d.routing),
            billing = pick(cur.billing, d.billing),
            reference = pick(cur.reference, d.reference),
            services = pick(cur.services, d.services),
            rawText = raw
        )
        // тот же номер уже есть в списке — помечаем, чтобы не отправить одну посылку дважды
        if (merged.tracking.isNotBlank() && merged.note.isBlank()) {
            val dup = db.allPakets().firstOrNull { it.id != id && it.tracking == merged.tracking }
            if (dup != null) merged = merged.copy(note = "Повтор: этот номер уже сканировали (" + fmtTime(dup.createdAt) + ")")
        }
        SendCloud.savePaket(db, merged.copy(status = if (merged.tracking.isBlank()) "check" else "done"))
    }
}

/** Снимки этикеток хранятся на телефоне двое суток; дальше копия остаётся только на Google Диске. */
object SendRetention {
    const val KEEP_MS = 48L * 60 * 60 * 1000

    /** Удаляет с телефона файлы старше 48 часов. Файл, ещё не ушедший на Диск, не удаляется — иначе он пропал бы совсем. */
    fun sweep(ctx: Context): Int {
        val app = ctx.applicationContext
        val now = System.currentTimeMillis()
        val pending = ProductDb.get(app).pendingUploads().toSet()
        var removed = 0
        for (p in SendDb.get(app).allPakets()) {
            for (n in p.photos) {
                if (n in pending) continue
                val f = Media.file(app, n)
                if (f.exists() && now - f.lastModified() > KEEP_MS && f.delete()) removed++
            }
        }
        if (removed > 0) Media.version++
        return removed
    }
}
