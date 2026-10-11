package ru.storage.app

import android.content.Context
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
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
    val routing: String = ""
)

/**
 * Разбор этикетки DHL без ИИ: штрих-коды (ML Kit) + текст (ML Kit OCR, работает на телефоне) + регулярные выражения.
 * Форматы номера: JJD… (16+ знаков), 20 цифр (00340…), 12 цифр, S10 (RR123456789DE), Leitcode (14 цифр / 403…).
 * Эвристики можно донастроить по реальным снимкам: распознанный текст сохраняется в посылке целиком.
 */
object LabelParser {
    private val RE_JJD = Regex("JJD\\d{10,22}")
    private val RE_20 = Regex("(?<!\\d)00\\d{18}(?!\\d)")
    private val RE_12 = Regex("(?<!\\d)\\d{12}(?!\\d)")
    private val RE_S10 = Regex("(?<![A-Z0-9])[A-Z]{2}\\d{9}[A-Z]{2}(?![A-Z0-9])")
    private val RE_ZIP = Regex("^(?:D[- ]?)?(\\d{5})\\s+(\\p{L}[\\p{L}\\s.\\-/()]*)$")
    private val RE_WEIGHT = Regex("(\\d{1,2}(?:[.,]\\d{1,3})?)\\s?kg", RegexOption.IGNORE_CASE)
    private val RE_STREET = Regex(
        "(?i)stra(ß|ss)e|str\\.|weg\\b|platz|allee|gasse|ring\\b|damm|ufer|chaussee|packstation|postfiliale|postfach"
    )
    private val RE_NOISE = Regex(
        "(?i)dhl|paket|päckchen|paeckchen|gogreen|absender|sender|\\bkg\\b|leitcode|ident|sendung|www\\.|http|@|" +
            "deutsche post|nicht nachsenden|retoure|international"
    )

    private fun compact(s: String) = s.replace(Regex("\\s"), "").uppercase()

    private fun trackingFrom(s: String): String? {
        RE_JJD.find(s)?.let { return it.value }
        RE_20.find(s)?.let { return it.value }
        return null
    }

    fun parse(text: String, barcodes: List<String>): LabelData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val codes = barcodes.map { it.filter { ch -> ch.isLetterOrDigit() }.uppercase() }

        // ---- номер посылки ----
        var tracking = codes.firstNotNullOfOrNull { trackingFrom(it) } ?: ""
        if (tracking.isEmpty()) tracking = lines.firstNotNullOfOrNull { trackingFrom(compact(it)) } ?: ""
        if (tracking.isEmpty()) {
            tracking = codes.firstOrNull { Regex("^\\d{12}$").matches(it) } ?: ""
        }
        if (tracking.isEmpty()) for (l in lines) {
            if (Regex("(?i)sendung|ident|nr").containsMatchIn(l)) {
                RE_12.find(compact(l))?.let { tracking = it.value }
                if (tracking.isNotEmpty()) break
            }
        }
        if (tracking.isEmpty()) {
            tracking = codes.firstNotNullOfOrNull { RE_S10.find(it)?.value } ?: ""
        }

        // ---- Leitcode / Routingcode ----
        val routing = codes.firstOrNull { c ->
            c != tracking && c.all { it.isDigit() } && (c.startsWith("403") || c.length == 14)
        } ?: ""

        // ---- продукт ----
        val up = text.uppercase()
        val product = when {
            up.contains("KLEINPAKET") -> "DHL Kleinpaket"
            up.contains("PÄCKCHEN") || up.contains("PAECKCHEN") -> "DHL Päckchen"
            up.contains("WARENPOST") -> "Warenpost"
            up.contains("EXPRESS") -> "DHL Express"
            up.contains("RETOURE") -> "DHL Retoure"
            up.contains("PAKET") || up.contains("PARCEL") -> "DHL Paket"
            tracking.startsWith("JJD") || tracking.startsWith("00340") -> "DHL Paket"
            else -> ""
        }

        // ---- вес ----
        val weight = RE_WEIGHT.find(text)?.groupValues?.get(1)?.replace(',', '.') ?: ""

        // ---- отправитель ----
        var sender = ""
        var senderEnd = -1
        val sIdx = lines.indexOfFirst { Regex("(?i)^(absender|sender|from)\\b").containsMatchIn(it) }
        if (sIdx >= 0) {
            val z = (sIdx until minOf(lines.size, sIdx + 6)).firstOrNull { RE_ZIP.matches(lines[it]) }
            if (z != null) {
                senderEnd = z
                sender = lines.subList(sIdx, z + 1).joinToString(", ")
            } else {
                val rest = lines[sIdx].substringAfter(':', "").trim()
                sender = rest.ifEmpty { lines.getOrNull(sIdx + 1) ?: "" }
                senderEnd = sIdx
            }
            sender = sender.replace(Regex("(?i)^(absender|sender|from)\\s*:?\\s*"), "").trim()
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
            if (streetIdx >= 0 && streetIdx > senderEnd &&
                (Regex("\\d").containsMatchIn(lines[streetIdx]) || RE_STREET.containsMatchIn(lines[streetIdx]))
            ) {
                street = lines[streetIdx]
            } else {
                streetIdx = zipIdx
            }
            val names = ArrayList<String>()
            var i = streetIdx - 1
            while (i >= 0 && i > senderEnd && names.size < 2) {
                val l = lines[i]
                if (RE_NOISE.containsMatchIn(l) || l.length < 3 || RE_ZIP.matches(l)) break
                if (l.count { it.isDigit() } * 2 > l.length) break
                names.add(0, l)
                i--
            }
            recipient = names.joinToString(" / ")
            val next = lines.getOrNull(zipIdx + 1)
            country = if (next != null && next.length in 4..24 && next.all { it.isLetter() || it == ' ' } &&
                !RE_NOISE.containsMatchIn(next)
            ) next.trim() else "DE"
        }

        return LabelData(
            tracking = tracking, product = product, recipient = recipient, street = street,
            zip = zip, city = city, country = country, sender = sender, weight = weight, routing = routing
        )
    }
}

/**
 * Фоновое распознавание этикеток: снимки → OCR + штрих-коды → поля посылки → база и Firebase.
 * Партии идут по одной; если приложение закрыли посреди работы, незаконченные посылки добираются при следующем запуске.
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
                val bmp = Media.thumb(f, 1500) ?: continue
                val img = InputImage.fromBitmap(bmp, 0)
                try {
                    texts.add(rec.process(img).awaitIt().text)
                    anyImage = true
                } catch (e: Exception) {
                }
                try {
                    scanner.process(img).awaitIt().forEach { b -> b.rawValue?.let { codes.add(it) } }
                } catch (e: Exception) {
                }
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
        val merged = cur.copy(
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
            rawText = raw
        )
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
