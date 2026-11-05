package ru.storage.app

/**
 * Один и тот же товар под разными названиями (накладная: «AG Kraftpaket 20kg», Альфа-План: «AGROBS Kraftpaket 20 kg Tüte»).
 *
 * Карточки не удаляются и не переписываются: у каждой есть groupId. Одинаковый groupId = один товар,
 * в списках он показывается одной строкой, история приёмов собирается со всех названий.
 *
 * Автоматически объединяются карточки, у которых:
 *  - совпал штрих-код; или
 *  - совпали вес/объём упаковки и вид упаковки, название похоже (с учётом короткого имени производителя: AG ≈ AGROBS),
 *    и производители не противоречат друг другу (разные производители = разные товары: «цинк 5 кг» от двух фирм не склеивается).
 * Объединение можно в любой момент отменить кнопкой «Отделить»: тогда пара запоминается в apart и автоматически больше не склеивается.
 * Результат не зависит от телефона (код группы = наименьший id участников), поэтому телефоны не спорят друг с другом.
 */
object ProductGroups {

    private fun norm(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }

    /** «ag» и «agrobs»: короткое имя — начало длинного (не короче двух букв). */
    private fun brandAlias(x: String, y: String): Boolean {
        if (x == y) return true
        val (s, l) = if (x.length <= y.length) x to y else y to x
        return s.length >= 2 && l.startsWith(s)
    }

    /** Производитель не указан хотя бы у одного — не противоречит; указан у обоих — должен совпасть (или быть коротким именем). */
    private fun makerOk(a: Product, b: Product): Boolean {
        val x = norm(a.manufacturer)
        val y = norm(b.manufacturer)
        if (x.isEmpty() || y.isEmpty()) return true
        return brandAlias(x, y)
    }

    /** 0 — разные товары; иначе уверенность (2.0 — штрих-код, остальное — похожесть названий). */
    private fun pairScore(a: Product, b: Product, sa: ProductMatch.Sig, sb: ProductMatch.Sig): Double {
        if (a.id == b.id) return 0.0
        if (sameBarcode(a.barcode, b.barcode)) return 2.0
        if (!makerOk(a, b)) return 0.0
        if (sa.size == null || sb.size == null) return 0.0 // без веса/объёма по названию не склеиваем: только по штрих-коду
        val direct = ProductMatch.score(sa, sb)
        if (direct > 0.0) return direct
        // короткое имя производителя в начале названия: AG Kraftpaket ≈ AGROBS Kraftpaket
        val ta = sa.tokens
        val tb = sb.tokens
        if (ta.size >= 2 && tb.size >= 2 && ta[0] != tb[0] && brandAlias(ta[0], tb[0])) {
            return ProductMatch.score(
                ProductMatch.Sig(ta.drop(1), sa.size, sa.pack),
                ProductMatch.Sig(tb.drop(1), sb.size, sb.pack)
            )
        }
        return 0.0
    }

    /** Главная карточка группы: Альфа-План (есть артикул), затем со штрих-кодом, затем с самым полным названием. */
    fun primary(members: List<Product>): Product =
        members.maxWithOrNull(
            compareBy<Product>(
                { if (it.article.isNotBlank()) 1 else 0 },
                { if (it.barcode.isNotBlank()) 1 else 0 },
                { it.name.length }
            ).thenByDescending { it.id }
        ) ?: error("пустая группа")

    /** Все названия товара, включая сам товар. */
    fun membersOf(db: ProductDb, p: Product): List<Product> =
        if (p.groupId.isBlank()) listOf(p) else db.byGroup(p.groupId).ifEmpty { listOf(p) }

    // ---------- автоматическое объединение ----------

    @Volatile private var linkedOnce = false

    /** Один раз за запуск приложения: каталог, загруженный раньше, тоже проверяется на одинаковые товары. */
    fun linkOnce(db: ProductDb): Int = if (linkedOnce) 0 else link(db)

    /** Для подсказок поиска: карточки одного товара — одной строкой (главное название, артикул и штрих-код — с любой карточки группы). */
    fun collapse(db: ProductDb, list: List<Product>): List<Product> {
        val seen = HashSet<String>()
        val out = ArrayList<Product>()
        for (p in list) {
            if (!seen.add(p.groupId.ifBlank { p.id })) continue
            if (p.groupId.isBlank()) { out.add(p); continue }
            val members = db.byGroup(p.groupId).ifEmpty { listOf(p) }
            val main = primary(members)
            out.add(
                main.copy(
                    article = members.firstOrNull { it.article.isNotBlank() }?.article.orEmpty(),
                    barcode = members.firstOrNull { it.barcode.isNotBlank() }?.barcode.orEmpty()
                )
            )
        }
        return out
    }

    /** Находит одинаковые товары и записывает им общий groupId. Возвращает, сколько карточек изменилось. */
    fun link(db: ProductDb): Int {
        linkedOnce = true
        val all = db.allProducts()
        if (all.size < 2) return 0
        val byId = all.associateBy { it.id }

        // компоненты (union-find); у каждого корня — список участников
        val parent = HashMap<String, String>()
        val members = HashMap<String, MutableList<String>>()
        for (p in all) { parent[p.id] = p.id; members[p.id] = mutableListOf(p.id) }
        fun find(x: String): String {
            var r = x
            while (parent[r] != r) r = parent[r]!!
            var c = x
            while (parent[c] != r) { val n = parent[c]!!; parent[c] = r; c = n }
            return r
        }
        fun union(a: String, b: String) {
            val ra = find(a)
            val rb = find(b)
            if (ra == rb) return
            parent[rb] = ra
            members[ra]!!.addAll(members.remove(rb)!!)
        }
        fun apartBetween(ra: String, rb: String): Boolean {
            for (x in members[ra]!!) for (y in members[rb]!!) {
                if (y in byId[x]!!.apart || x in byId[y]!!.apart) return true
            }
            return false
        }

        // уже существующие группы — как есть
        val firstOfGroup = HashMap<String, String>()
        for (p in all) if (p.groupId.isNotBlank()) {
            val f = firstOfGroup.getOrPut(p.groupId) { p.id }
            union(f, p.id)
        }

        // кандидаты: по штрих-коду и по одинаковому весу/объёму упаковки
        val sigs = HashMap<String, ProductMatch.Sig>()
        for (p in all) sigs[p.id] = ProductMatch.sigOfProduct(p)
        val buckets = HashMap<String, MutableList<Product>>()
        for (p in all) {
            val core = p.barcode.trim().trimStart('0')
            if (core.isNotEmpty()) buckets.getOrPut("b:$core") { mutableListOf() }.add(p)
            sigs[p.id]!!.size?.let { buckets.getOrPut("s:$it") { mutableListOf() }.add(p) }
        }
        class Pair3(val a: String, val b: String, val score: Double)
        val pairs = ArrayList<Pair3>()
        val seen = HashSet<String>()
        for ((_, list) in buckets) {
            if (list.size < 2 || list.size > 600) continue
            for (i in 0 until list.size) for (j in i + 1 until list.size) {
                val a = list[i]
                val b = list[j]
                if (find(a.id) == find(b.id)) continue
                val key = if (a.id < b.id) a.id + "|" + b.id else b.id + "|" + a.id
                if (!seen.add(key)) continue
                val sc = pairScore(a, b, sigs[a.id]!!, sigs[b.id]!!)
                if (sc > 0.0) pairs.add(Pair3(a.id, b.id, sc))
            }
        }
        pairs.sortByDescending { it.score }
        for (pr in pairs) {
            val ra = find(pr.a)
            val rb = find(pr.b)
            if (ra == rb) continue
            if (apartBetween(ra, rb)) continue // вручную отделённые не склеиваем
            union(ra, rb)
        }

        // записываем изменения
        var changed = 0
        for ((_, ids) in members.toMap()) {
            val prods = ids.mapNotNull { byId[it] }
            val gid = if (prods.size < 2) ""
            else prods.map { it.groupId }.filter { it.isNotBlank() }.minOrNull() ?: prods.minOf { it.id }
            for (p in prods) if (p.groupId != gid) {
                Cloud.saveProduct(db, p.copy(groupId = gid), log = false)
                changed++
            }
        }
        if (changed > 0) Journal.log("Каталог", "Объединены одинаковые товары, изменено карточек: $changed")
        return changed
    }

    // ---------- ручные действия ----------

    /** Объединить выбранные товары (и все их группы) в один: запреты «отделён» между ними снимаются. */
    fun merge(db: ProductDb, ids: List<String>) {
        val start = ids.mapNotNull { db.getProduct(it) }
        if (start.size < 2) return
        val all = LinkedHashMap<String, Product>()
        start.forEach { s ->
            all[s.id] = s
            if (s.groupId.isNotBlank()) db.byGroup(s.groupId).forEach { all[it.id] = it }
        }
        val gid = all.values.map { it.groupId }.filter { it.isNotBlank() }.minOrNull() ?: all.keys.minOrNull()!!
        val idSet = all.keys
        for (p in all.values) {
            val apart = p.apart.filter { it !in idSet }
            if (p.groupId != gid || apart.size != p.apart.size) {
                Cloud.saveProduct(db, p.copy(groupId = gid, apart = apart), log = false)
            }
        }
        Journal.log("Каталог", "Товары объединены вручную: " + all.values.joinToString(" = ") { it.name })
    }

    /** Отделить товар от его группы; с остальными участниками он автоматически больше не объединяется. */
    fun split(db: ProductDb, id: String) {
        val p = db.getProduct(id) ?: return
        if (p.groupId.isBlank()) return
        val others = db.byGroup(p.groupId).filter { it.id != id }
        val otherIds = others.map { it.id }
        Cloud.saveProduct(db, p.copy(groupId = "", apart = (p.apart + otherIds).distinct()), log = false)
        for (o in others) {
            val groupId = if (others.size == 1) "" else o.groupId
            Cloud.saveProduct(db, o.copy(groupId = groupId, apart = (o.apart + id).distinct()), log = false)
        }
        Journal.log("Каталог", "Товар отделён от группы: " + p.name)
    }
}
