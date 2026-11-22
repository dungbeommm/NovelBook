package vn.tramtruyen.reader.engine.sources

/**
 * BO NHO DEM KET QUA TIM KIEM.
 *
 * Muc tieu: go lai tu khoa da tim -> hien TUC THI (0ms), khong cham mang.
 *
 * Hai muc do dung lai:
 *  1) TRUNG TU KHOA: tra ve nguyen ket qua cu (con han).
 *  2) MO RONG TU KHOA ("tien" -> "tien ngh"): loc ngay tren ket qua cu de co
 *     cai hien LIEN, roi mang ve sau se ghi de. Nho vay go den dau thay den do.
 */
object SearchCache {

    /** Ket qua song trong 10 phut — du de go tim qua lai ma khong lam cu du lieu. */
    private const val TTL_MS = 10 * 60 * 1000L
    private const val MAX_ENTRIES = 40

    private data class Entry(val hits: List<SearchHit>, val at: Long, val complete: Boolean)

    // accessOrder = true -> LinkedHashMap tu sap xep theo lan dung gan nhat (LRU).
    private val store = object : LinkedHashMap<String, Entry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean =
            size > MAX_ENTRIES
    }

    private fun key(query: String): String = TitleMatcher.normalize(query)

    /** Ket qua da luu cho DUNG tu khoa nay (null neu chua co hoac het han). */
    @Synchronized
    fun get(query: String): List<SearchHit>? {
        val k = key(query)
        val e = store[k] ?: return null
        if (System.currentTimeMillis() - e.at > TTL_MS) {
            store.remove(k)
            return null
        }
        return e.hits
    }

    /**
     * DOAN NHANH cho tu khoa dai hon tu khoa da tim truoc do.
     * Vi du da tim "tien", nguoi dung go tiep thanh "tien nghich" -> loc ngay tren
     * ket qua cu de co cai hien trong khi cho mang.
     */
    @Synchronized
    fun guessFromPrefix(query: String): List<SearchHit> {
        val k = key(query)
        if (k.length < 2) return emptyList()
        val now = System.currentTimeMillis()
        var best: List<SearchHit>? = null
        var bestLen = -1
        for ((cachedKey, e) in store) {
            if (now - e.at > TTL_MS) continue
            if (!e.complete) continue
            if (!k.startsWith(cachedKey)) continue
            if (cachedKey.length > bestLen) {
                best = e.hits
                bestLen = cachedKey.length
            }
        }
        val source = best ?: return emptyList()
        return SearchEngine.rank(query, source.map { it.copy(mirrors = emptyList()) })
    }

    @Synchronized
    fun put(query: String, hits: List<SearchHit>, complete: Boolean) {
        // Khong cache ket qua rong: mot loi DNS/403 tam thoi khong duoc bien thanh
        // "khong co truyen" trong 10 phut tiep theo.
        if (hits.isEmpty()) return
        store[key(query)] = Entry(hits, System.currentTimeMillis(), complete)
    }

    @Synchronized
    fun clear() = store.clear()
}
