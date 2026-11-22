package ru.storage.app

/**
 * Немецкие буквы без неудобной раскладки: цифра 2 перед буквой превращается в нужный символ, пока набираешь название.
 *   2s → ß    2a → ä    2o → ö    2u → ü    (2A → Ä, 2O → Ö, 2U → Ü; 2S → ß)
 * Не трогаем «2» внутри числа (12s, 0,2s) — только отдельную двойку перед буквой.
 */
fun germanKeys(s: String): String {
    if (s.length < 2 || '2' !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '2' && i + 1 < s.length && (i == 0 || !(s[i - 1].isDigit() || s[i - 1] == '.' || s[i - 1] == ','))) {
            val rep = when (s[i + 1]) {
                's', 'S' -> "ß"
                'a' -> "ä"
                'o' -> "ö"
                'u' -> "ü"
                'A' -> "Ä"
                'O' -> "Ö"
                'U' -> "Ü"
                else -> null
            }
            if (rep != null) { sb.append(rep); i += 2; continue }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}

/** Ключ для поиска: Käse = Kase = Kaese, Straße = Strasse (регистр не важен). */
fun foldDe(s: String): String = s.lowercase()
    .replace("ß", "ss").replace("ä", "a").replace("ö", "o").replace("ü", "u")
    .replace("ae", "a").replace("oe", "o").replace("ue", "u")
