package ru.storage.app

/** Страны, для которых поле номера умеет подставлять формат. */
enum class PlateCountry(val euCode: String, val flag: String, val title: String, val hint: String) {
    DE("D", "🇩🇪", "Германия", "B AB 1234"),
    FR("F", "🇫🇷", "Франция", "AB-123-CD"),
    PL("PL", "🇵🇱", "Польша", "WA 12345"),
    LT("LT", "🇱🇹", "Литва", "ABC 123"),
    NL("NL", "🇳🇱", "Нидерланды", "AB-123-C"),
    CZ("CZ", "🇨🇿", "Чехия", "1AB 2345"),
    AT("A", "🇦🇹", "Австрия", "W 12345 A"),
    IT("I", "🇮🇹", "Италия", "AB 123 CD"),
    ES("E", "🇪🇸", "Испания", "1234 ABC"),
    RO("RO", "🇷🇴", "Румыния", "B 123 ABC"),
    BE("B", "🇧🇪", "Бельгия", "1-ABC-123"),
    OTHER("?", "🌍", "Другая страна", "Номер")
}

/** Коды немецких регионов (Unterscheidungszeichen): 1, 2 или 3 буквы. */
object GermanRegions {
    private const val RAW = """
A AA AB ABG ABI AC AD AE AIB AIC AK AM AN AÖ AP AS AT AU AUR AW AZ AZE
B BA BAD BAR BB BC BD BGL BI BIR BIT BK BL BLK BM BN BNA BO BOR BOT BRA BRB BS BT BTF BÜS BW BYL BZ
C CA CB CE CHA CLP CO COC COE CUX CW
D DA DAH DAN DAU DBR DD DE DEG DEL DGF DH DL DLG DM DN DO DON DU DÜW DW DZ
E EA EB EBE ED EE EF EH EI EIC EL EM EMD EMS EN ER ERB ERH ES ESA ESW EU EW
F FB FD FDS FF FFB FG FL FN FO FR FRG FRI FRW FS FT FTL FÜ FW
G G GA GAP GC GDB GE GER GF GG GI GL GM GN GP GR GRZ GS GT GTH GÜ GZ
H HA HAL HAM HAS HB HBN HBS HC HD HDH HE HEF HEI HEL HER HF HG HGW HH HI HK HL HM HN HO HOL HOM HP HR HRO HS HSK HST HU HV HVL HWI HX HY HZ
IGB IK IL IN IZ
J JL
K KA KB KC KE KEH KF KG KH KI KIB KL KLE KN KO KR KS KT KU KUS KW KY KYF
L LA LAU LB LC LD LDK LDS LER LEV LG LI LIF LIP LL LM LÖ LOS LRO LSA LSN LSZ LU LWL
M MA MB MC MD ME MEI MG MI MIL MK MKK ML MM MN MOL MOS MQ MR MS MSE MSH MST MTK MTL MVL MW MYK MZ MZG
N NB ND NDH NE NEA NES NEW NF NI NK NL NM NMS NOH NOM NR NU NVP NW NWM
OA OAL OB OD OE OF OG OH OHA OHV OHZ OK OL OPR OS OSL OVL OVP
P PA PAF PAN PB PCH PE PF PI PIR PLÖ PM PR PS
QLB
R RA RC RD RDG RE REG RO ROW RS RT RV RW RZ
S SB SBK SC SDL SE SG SH SHA SHG SHK SIG SIM SK SL SLF SLK SLS SM SN SO SOK SON SP SPN SR ST STA STB STD SU SW SZ
TBB TF TG TIR TO TÖL TP TR TS TÜ TUT
UE UH UL UM UN
V VB VEC VER VIE VK VR VS
W WAF WAK WB WE WEN WES WF WHV WI WIL WL WM WN WND WO WOB WR WS WSF WST WT WTM WUG WUN WW WZL
Z ZI ZW
"""
    val codes: Set<String> = RAW.split(Regex("\\s+")).filter { it.isNotEmpty() }.toSet()
    fun isKnown(code: String) = code.uppercase() in codes
}

private val CYR = mapOf(
    'А' to 'A', 'В' to 'B', 'Е' to 'E', 'К' to 'K', 'М' to 'M', 'Н' to 'H',
    'О' to 'O', 'Р' to 'P', 'С' to 'C', 'Т' to 'T', 'У' to 'Y', 'Х' to 'X'
)

/** Верхний регистр + русские буквы, похожие на латинские, превращаем в латинские. */
private fun norm(s: String): String = buildString {
    for (ch in s.uppercase()) append(CYR[ch] ?: ch)
}

private fun lat(c: Char) = c in 'A'..'Z'
private fun latU(c: Char) = c in 'A'..'Z' || c == 'Ä' || c == 'Ö' || c == 'Ü'

/** Немецкий номер: РЕГИОН(1–3) пробел БУКВЫ(1–2) пробел ЦИФРЫ(1–4)[E|H]. Пробелы расставляются сами. */
fun formatGerman(input: String): String {
    var region = ""; var mid = ""; var digits = ""; var suffix = ""
    var phase = 0
    for (c in norm(input)) {
        val sep = c == ' ' || c == '-'
        when (phase) {
            0 -> when {
                latU(c) -> if (region.length < 3) region += c else { phase = 1; if (lat(c)) mid += c }
                sep -> if (region.isNotEmpty()) phase = 1
                c.isDigit() -> if (region.isNotEmpty()) { phase = 2; digits += c }
            }
            1 -> when {
                lat(c) -> if (mid.length < 2) mid += c
                sep -> if (mid.isNotEmpty()) phase = 2
                c.isDigit() -> { phase = 2; digits += c }
            }
            else -> when {
                c.isDigit() -> if (suffix.isEmpty() && digits.length < 4) digits += c
                (c == 'E' || c == 'H') && digits.isNotEmpty() && suffix.isEmpty() -> suffix = c.toString()
            }
        }
    }
    val sb = StringBuilder(region)
    if (phase >= 1) sb.append(' ').append(mid)
    if (phase >= 2) sb.append(' ').append(digits).append(suffix)
    return sb.toString()
}

/** Шаблон: L – буква, D – цифра, всё остальное – разделитель, который ставится автоматически. */
private fun formatPattern(input: String, pattern: String): String {
    val sb = StringBuilder()
    var i = 0
    for (c in norm(input).filter { lat(it) || it.isDigit() }) {
        while (i < pattern.length && pattern[i] != 'L' && pattern[i] != 'D') { sb.append(pattern[i]); i++ }
        if (i >= pattern.length) break
        val need = pattern[i]
        if ((need == 'L' && lat(c)) || (need == 'D' && c.isDigit())) { sb.append(c); i++ }
    }
    return sb.toString()
}

/** Польша: регион 2–3 буквы, пробел, 4–5 букв/цифр. */
private fun formatPolish(input: String): String {
    var region = ""; var rest = ""; var phase = 0
    for (c in norm(input)) {
        val sep = c == ' ' || c == '-'
        if (phase == 0) {
            when {
                lat(c) -> if (region.length < 3) region += c else { phase = 1; rest += c }
                sep -> if (region.isNotEmpty()) phase = 1
                c.isDigit() -> if (region.isNotEmpty()) { phase = 1; rest += c }
            }
        } else if ((lat(c) || c.isDigit()) && rest.length < 5) rest += c
    }
    return if (phase >= 1) "$region $rest" else region
}

private fun formatFree(input: String): String =
    norm(input).filter { lat(it) || it.isDigit() || it == ' ' || it == '-' || latU(it) }
        .replace(Regex(" {2,}"), " ").take(12)

fun formatPlate(country: PlateCountry, input: String): String = when (country) {
    PlateCountry.DE -> formatGerman(input)
    PlateCountry.FR -> formatPattern(input, "LL-DDD-LL")
    PlateCountry.LT -> formatPattern(input, "LLL DDD")
    PlateCountry.PL -> formatPolish(input)
    else -> formatFree(input)
}

/** Подбирает страну по уже записанному номеру (чтобы поле не «ломало» старые записи). */
fun detectPlateCountry(value: String): PlateCountry {
    val v = value.trim()
    if (v.isEmpty()) return PlateCountry.DE
    return when {
        Regex("^[A-Z]{2}-\\d{3}-[A-Z]{2}$").matches(v) -> PlateCountry.FR
        Regex("^[A-Z]{3} \\d{3}$").matches(v) -> PlateCountry.LT
        Regex("^[A-ZÄÖÜ]{1,3} [A-Z]{1,2} \\d{1,4}[EH]?$").matches(v) -> PlateCountry.DE
        else -> PlateCountry.OTHER
    }
}

private fun tokensOf(line: String): List<String> {
    val raw = norm(line).replace(Regex("[^A-Z0-9ÄÖÜ]"), " ").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val out = ArrayList<String>()
    for (t in raw) {
        val m = Regex("^([A-ZÄÖÜ]+)(\\d+[EH]?)$").find(t)
        if (m != null) { out += m.groupValues[1]; out += m.groupValues[2] } else out += t
    }
    return out
}

/** Достаёт номер нужной страны из текста, распознанного камерой. null – если не нашли. */
fun extractPlate(country: PlateCountry, text: String): String? {
    val lines = text.lines()
    when (country) {
        PlateCountry.DE -> {
            for (line in lines) {
                val t = tokensOf(line)
                for (i in t.indices.reversed()) {
                    val m = Regex("^(\\d{1,4})([EH]?)$").find(t[i]) ?: continue
                    if (i >= 2 && Regex("^[A-Z]{1,2}$").matches(t[i - 1]) &&
                        Regex("^[A-ZÄÖÜ]{1,3}$").matches(t[i - 2]) && GermanRegions.isKnown(t[i - 2])
                    ) return "${t[i - 2]} ${t[i - 1]} ${m.groupValues[1]}${m.groupValues[2]}"
                    // «MAB 1234» одним словом: пробуем разрезать по известному региону
                    if (i >= 1 && Regex("^[A-Z]{3,5}$").matches(t[i - 1])) {
                        val w = t[i - 1]
                        for (r in minOf(3, w.length - 1) downTo 1) {
                            val reg = w.substring(0, r); val rest = w.substring(r)
                            if (rest.length in 1..2 && GermanRegions.isKnown(reg))
                                return "$reg $rest ${m.groupValues[1]}${m.groupValues[2]}"
                        }
                    }
                }
            }
            return null
        }
        PlateCountry.FR -> for (line in lines) {
            val s = norm(line).filter { lat(it) || it.isDigit() }
            Regex("([A-Z]{2})(\\d{3})([A-Z]{2})").find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
        }
        PlateCountry.LT -> for (line in lines) {
            val s = norm(line).filter { lat(it) || it.isDigit() }
            Regex("([A-Z]{3})(\\d{3})").find(s)?.let { return "${it.groupValues[1]} ${it.groupValues[2]}" }
        }
        PlateCountry.PL -> for (line in lines) {
            val t = tokensOf(line)
            for (i in 0 until t.size - 1) {
                if (Regex("^[A-Z]{2,3}$").matches(t[i]) && Regex("^[A-Z0-9]{4,5}$").matches(t[i + 1]) &&
                    t[i + 1].any { it.isDigit() }
                ) return "${t[i]} ${t[i + 1]}"
            }
        }
        else -> for (line in lines) {
            val t = tokensOf(line).filter { it.length >= 2 }
            val joined = t.joinToString("")
            if (joined.length in 5..10 && joined.any { it.isDigit() } && joined.any { it.isLetter() })
                return t.joinToString(" ")
        }
    }
    return null
}
