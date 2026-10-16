package ru.storage.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Настройки печати: на каких языках печатается документ (немецкий, русский, английский — можно все сразу)
 * и включена ли автоподпись принимающего. Хранится на телефоне и в настройках аккаунта.
 */
object PrintCfg {
    var de by mutableStateOf(true)
    var ru by mutableStateOf(true)
    var en by mutableStateOf(true)
    var sign by mutableStateOf(true)
    /** Адрес в шапке документов (несколько строк). Пусто — шапка без адреса. */
    var address by mutableStateOf("")

    private fun sp(ctx: Context) = ctx.applicationContext.getSharedPreferences("print_cfg", Context.MODE_PRIVATE)

    fun load(ctx: Context) {
        val s = sp(ctx)
        de = s.getBoolean("de", true)
        ru = s.getBoolean("ru", true)
        en = s.getBoolean("en", true)
        sign = s.getBoolean("sign", true)
        address = s.getString("address", "") ?: ""
        if (!de && !ru && !en) ru = true
    }

    private fun store(ctx: Context) {
        sp(ctx).edit().putBoolean("de", de).putBoolean("ru", ru).putBoolean("en", en).putBoolean("sign", sign)
            .putString("address", address).apply()
    }

    fun setAddress(ctx: Context, v: String) {
        address = v.lines().map { it.trim() }.filter { it.isNotEmpty() }.take(4).joinToString("\n")
        store(ctx)
    }

    fun isOn(code: String) = when (code) {
        "de" -> de
        "ru" -> ru
        else -> en
    }

    /** Включить/выключить язык. Хотя бы один язык всегда остаётся. */
    fun setLang(ctx: Context, code: String, on: Boolean) {
        val n = listOf(de, ru, en).count { it }
        if (!on && isOn(code) && n <= 1) return
        when (code) {
            "de" -> de = on
            "ru" -> ru = on
            else -> en = on
        }
        store(ctx)
    }

    fun setSign(ctx: Context, on: Boolean) {
        sign = on
        store(ctx)
    }

    /** Коды включённых языков в порядке печати: немецкий, русский, английский. */
    fun codes(): List<String> = buildList {
        if (de) add("de")
        if (ru) add("ru")
        if (en) add("en")
    }

    /** Применить значения из аккаунта (Firebase). */
    fun applyCloud(ctx: Context, langs: List<*>?, signOn: Boolean?, addr: String? = null) {
        if (langs != null) {
            val l = langs.mapNotNull { it as? String }
            if (l.isNotEmpty()) {
                de = "de" in l
                ru = "ru" in l
                en = "en" in l
            }
        }
        if (signOn != null) sign = signOn
        if (addr != null) address = addr
        store(ctx)
    }

    internal fun pick(a: Array<String>): List<String> = buildList {
        if (de) add(a[0])
        if (ru) add(a[1])
        if (en) add(a[2])
    }
}

private val T: Map<String, Array<String>> = mapOf(
    "title_full" to arrayOf("Wareneingang – Gesamtprotokoll", "Приёмка товара — общий акт", "Goods receipt – full report"),
    "title_diff" to arrayOf("Abweichungsprotokoll beim Wareneingang", "Акт расхождений при приёмке", "Goods receipt discrepancy report"),
    "sender" to arrayOf("Absender", "Отправитель", "Sender"),
    "order" to arrayOf("Bestellnummer", "Номер заказа", "Order number"),
    "doc_time" to arrayOf("Datum und Uhrzeit (laut Dokument)", "Дата и время (по документу)", "Date and time (per document)"),
    "received_by" to arrayOf("Angenommen von", "Принял", "Received by"),
    "printed" to arrayOf("Gedruckt", "Распечатано", "Printed"),
    "state_h" to arrayOf("Stand des Wareneingangs", "Состояние приёма", "Receipt status"),
    "th_no" to arrayOf("Nr.", "№", "No."),
    "th_item" to arrayOf("Artikel", "Товар", "Item"),
    "th_plan" to arrayOf("Soll", "План", "Planned"),
    "th_fact" to arrayOf("Ist", "Факт", "Actual"),
    "th_defect" to arrayOf("Defekt", "Брак", "Defective"),
    "th_good" to arrayOf("In Ordnung", "Годный", "Good"),
    "th_diff" to arrayOf("Abweichung", "Расхождение", "Difference"),
    "th_status" to arrayOf("Status", "Статус", "Status"),
    "total" to arrayOf("Summe", "Итого", "Total"),
    "st_short" to arrayOf("Fehlmenge", "Недостача", "Shortage"),
    "st_excess" to arrayOf("Überschuss", "Излишек", "Excess"),
    "st_extra" to arrayOf("Nicht im Plan", "Нет в плане", "Not in plan"),
    "st_ok" to arrayOf("Stimmt", "Совпало", "Match"),
    "h_short" to arrayOf("Fehlmenge (weniger geliefert als geplant)", "Недостача (привезли меньше плана)", "Shortage (less delivered than planned)"),
    "th_missing" to arrayOf("Es fehlen", "Не хватает", "Missing"),
    "h_excess" to arrayOf("Überschuss (mehr geliefert als geplant)", "Излишек (привезли больше плана)", "Excess (more delivered than planned)"),
    "th_surplus" to arrayOf("Zu viel", "Лишних", "Surplus"),
    "h_extra" to arrayOf("Artikel, die nicht im Plan stehen", "Товары, которых нет в плане", "Items that are not in the plan"),
    "th_arrived" to arrayOf("Geliefert", "Привезли", "Delivered"),
    "h_defects" to arrayOf("Beschädigte Ware", "Бракованный товар", "Damaged goods"),
    "th_recv_total" to arrayOf("Insgesamt angenommen", "Принято всего", "Total received"),
    "bw_title" to arrayOf("Annahmeprotokoll B-Ware (beschädigte Ware)", "Акт приёма B-Ware (повреждённый товар)", "B-Ware receipt report (damaged goods)"),
    "truck" to arrayOf("Fahrzeugnummer", "Номер машины", "Truck number"),
    "trailer" to arrayOf("Anhängernummer", "Номер прицепа", "Trailer number"),
    "date_time" to arrayOf("Datum und Uhrzeit", "Дата и время", "Date and time"),
    "goods_h" to arrayOf("Waren", "Товары", "Goods"),
    "th_qty" to arrayOf("Menge", "Кол-во", "Qty"),
    "th_expiry" to arrayOf("Haltbar bis", "Срок годности", "Best before"),
    "th_details" to arrayOf("Details zu den Schäden", "Подробности повреждений", "Damage details"),
    "inv_title" to arrayOf("Inventarliste beschädigter Ware im Lager (B-Ware)", "Опись испорченного товара на складе (B-Ware)", "Inventory of damaged goods in the warehouse (B-Ware)"),
    "compiled_by" to arrayOf("Erstellt von", "Составил", "Prepared by"),
    "th_reasons" to arrayOf("Gründe", "Причины", "Reasons"),
    "th_integrity" to arrayOf("Art der Beschädigung", "Тип целостности", "Type of damage"),
    "sig_received" to arrayOf("Angenommen von (Unterschrift)", "Принял (подпись)", "Received by (signature)"),
    "sig_driver" to arrayOf("Fahrer (Unterschrift)", "Водитель (подпись)", "Driver (signature)"),
    "sig_compiled" to arrayOf("Erstellt von (Unterschrift)", "Составил (подпись)", "Prepared by (signature)"),
    "sig_checked" to arrayOf("Geprüft von (Unterschrift)", "Проверил (подпись)", "Checked by (signature)"),
    "sig_recipient" to arrayOf("Unterschrift des Empfängers:", "Подпись принимающего:", "Recipient's signature:"),
    "bw_t1" to arrayOf("ANNAHMEPROTOKOLL —", "АКТ ПРИЁМА —", "RECEIPT REPORT —"),
    "bw_t2" to arrayOf("B-Ware (beschädigte Ware)", "B-Ware (повреждённый товар)", "B-Ware (damaged goods)"),
    "inv_t1" to arrayOf("INVENTARLISTE —", "ОПИСЬ —", "INVENTORY —"),
    "inv_t2" to arrayOf("B-Ware (beschädigte Ware im Lager)", "B-Ware (испорченный товар на складе)", "B-Ware (damaged goods in the warehouse)"),
    "internal" to arrayOf("INTERN – FÜR BÜRO", "ВНУТРЕННИЙ – ДЛЯ ОФИСА", "INTERN – FOR OFFICE"),
    "footer_note" to arrayOf("Dies ist ein internes Dokument und kein offizieller Beleg.", "Это внутренний документ, а не официальный документ.", "This is an internal document and not an official receipt."),
    "page_word" to arrayOf("Seite", "Страница", "Page"),
    "of_word" to arrayOf("von", "из", "of"),
    "qr_label" to arrayOf("QR Code für System-Erfassung", "QR-код для ввода в систему", "QR code for system entry")
)

/** Переводы вариантов повреждений (в позициях хранится русский текст). */
private val OPT: Map<String, Array<String>> = mapOf(
    "Когда испортился" to arrayOf("Wann beschädigt", "Когда испортился", "When it was damaged"),
    "В каком виде привезли" to arrayOf("Zustand bei Lieferung", "В каком виде привезли", "Condition on arrival"),
    "Содержимое" to arrayOf("Inhalt", "Содержимое", "Contents"),
    "Причина порчи" to arrayOf("Schadensursache", "Причина порчи", "Cause of damage"),
    "Тип целостности" to arrayOf("Art der Beschädigung", "Тип целостности", "Type of damage"),
    "Испортился во время перевозки" to arrayOf("Während des Transports beschädigt", "Испортился во время перевозки", "Damaged during transport"),
    "Был испорчен уже при погрузке (таким и привезли)" to arrayOf("Bereits beim Verladen beschädigt (so geliefert)", "Был испорчен уже при погрузке (таким и привезли)", "Already damaged at loading (delivered as is)"),
    "Причина неизвестна" to arrayOf("Ursache unbekannt", "Причина неизвестна", "Cause unknown"),
    "Паллет упал или груз сместился" to arrayOf("Palette umgefallen oder Ladung verrutscht", "Паллет упал или груз сместился", "Pallet fell or load shifted"),
    "Плёнка на паллете порвана" to arrayOf("Palettenfolie gerissen", "Плёнка на паллете порвана", "Pallet wrap torn"),
    "Коробка помята" to arrayOf("Karton verbeult", "Коробка помята", "Box dented"),
    "Коробка мокрая" to arrayOf("Karton nass", "Коробка мокрая", "Box wet"),
    "Упаковка порвана" to arrayOf("Verpackung gerissen", "Упаковка порвана", "Packaging torn"),
    "Упаковка вскрыта" to arrayOf("Verpackung geöffnet", "Упаковка вскрыта", "Packaging opened"),
    "Крышка или пробка открыта" to arrayOf("Deckel oder Verschluss offen", "Крышка или пробка открыта", "Lid or cap open"),
    "Упаковка грязная" to arrayOf("Verpackung verschmutzt", "Упаковка грязная", "Packaging dirty"),
    "Этикетка повреждена или отсутствует" to arrayOf("Etikett beschädigt oder fehlt", "Этикетка повреждена или отсутствует", "Label damaged or missing"),
    "Товар рассыпался" to arrayOf("Ware verschüttet", "Товар рассыпался", "Goods spilled"),
    "Товар рассыпан" to arrayOf("Ware verschüttet", "Товар рассыпан", "Goods spilled"),
    "Содержимое осталось внутри упаковки" to arrayOf("Inhalt noch in der Verpackung", "Содержимое осталось внутри упаковки", "Contents remained inside the packaging"),
    "Содержимое вытекло или высыпалось наружу" to arrayOf("Inhalt ausgelaufen oder herausgefallen", "Содержимое вытекло или высыпалось наружу", "Contents leaked or spilled out"),
    "Стекло или банка разбита, треснула" to arrayOf("Glas oder Dose zerbrochen, gesprungen", "Стекло или банка разбита, треснула", "Glass or jar broken, cracked"),
    "Товар раздавлен или деформирован" to arrayOf("Ware zerdrückt oder verformt", "Товар раздавлен или деформирован", "Goods crushed or deformed"),
    "Раздавлен или деформирован" to arrayOf("Zerdrückt oder verformt", "Раздавлен или деформирован", "Crushed or deformed"),
    "Плесень или посторонний запах" to arrayOf("Schimmel oder Fremdgeruch", "Плесень или посторонний запах", "Mold or foreign odor"),
    "Срок годности истёк" to arrayOf("Mindesthaltbarkeitsdatum abgelaufen", "Срок годности истёк", "Best-before date expired"),
    "Истёк срок годности" to arrayOf("Haltbarkeit abgelaufen", "Истёк срок годности", "Expired"),
    "Испортился при перевозке" to arrayOf("Beim Transport beschädigt", "Испортился при перевозке", "Damaged in transport"),
    "Испорчен при хранении на складе" to arrayOf("Bei Lagerung im Lager verdorben", "Испорчен при хранении на складе", "Spoiled in warehouse storage"),
    "Упал при разгрузке или работе с паллетом" to arrayOf("Beim Entladen oder Palettieren heruntergefallen", "Упал при разгрузке или работе с паллетом", "Dropped during unloading or pallet handling"),
    "Подмок, залит водой" to arrayOf("Nass geworden, mit Wasser übergossen", "Подмок, залит водой", "Wet, flooded with water"),
    "Заморозка или перегрев" to arrayOf("Frost oder Überhitzung", "Заморозка или перегрев", "Frozen or overheated"),
    "Вредители" to arrayOf("Schädlinge", "Вредители", "Pests"),
    "Заводской брак" to arrayOf("Herstellungsfehler", "Заводской брак", "Factory defect"),
    "Загрязнён" to arrayOf("Verschmutzt", "Загрязнён", "Contaminated")
)

/** Надпись документа на выбранных языках в одну строку: «Deutsch / Русский / English». */
fun tr(key: String, sep: String = " / "): String {
    val a = T[key] ?: return key
    return PrintCfg.pick(a).joinToString(sep)
}

/** Надписи на включённых языках списком (порядок: немецкий, русский, английский). */
fun trList(key: String): List<String> {
    val a = T[key] ?: return listOf(key)
    return PrintCfg.pick(a)
}

/** Надпись с переносом строк между языками (для узких заголовков таблиц). */
fun trb(key: String): String = tr(key, "<br>")

/** Заголовок документа: первый язык крупно, остальные ниже мельче. */
fun trHead(key: String): String {
    val a = T[key] ?: return key
    val l = PrintCfg.pick(a)
    if (l.isEmpty()) return key
    return l[0] + l.drop(1).joinToString("") { "<br><span class='h1s'>$it</span>" }
}

/** Вариант повреждения (русский текст из позиции) на выбранных языках; неизвестный текст остаётся как есть. */
fun trOpt(ru: String): String {
    val a = OPT[ru] ?: return ru
    return PrintCfg.pick(a).joinToString(" / ")
}
