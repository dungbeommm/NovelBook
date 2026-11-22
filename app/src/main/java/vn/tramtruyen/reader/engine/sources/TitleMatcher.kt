package vn.tramtruyen.reader.engine.sources

import java.text.Normalizer
import kotlin.math.max
import kotlin.math.min

/**
 * BO DO DO GIONG NHAU CUA TEN TRUYEN (tieng Viet).
 *
 * Muc tieu: nguoi dung go "đấu phá thương khung", "dau pha thuong khung",
 * "dauphathuongkhung" hay go THIEU/SAI vai chu van phai ra dung bo truyen.
 *
 * Cach lam:
 *  1) Chuan hoa: bo dau, bo dau cau, gom khoang trang, ha chu thuong.
 *  2) Cham diem nhieu tang (lay diem cao nhat):
 *     - Trung khop tuyet doi        -> 1.00
 *     - Ten ket qua bat dau bang tu khoa / chua tron tu khoa -> 0.80..0.95
 *     - Trung khop theo TU (token)  -> ty le tu khoa xuat hien trong ten
 *     - Gan dung theo Levenshtein   -> 1 - khoangcach/dodai
 *     - Viet lien khong dau         -> so khop chuoi lien
 *  3) Uu tien ket qua ngan gon (ten sat tu khoa) khi diem bang nhau.
 */
object TitleMatcher {

    /** Nguong toi thieu de coi la "gan dung", duoi muc nay coi nhu khong lien quan. */
    const val MIN_SCORE = 0.34

    private val NON_ALNUM = Regex("[^a-z0-9\\s]")
    private val SPACES = Regex("\\s+")
    private val COMBINING = Regex("\\p{InCombiningDiacriticalMarks}+")

    /** Bo dau tieng Viet + ha chu thuong + bo dau cau. */
    /**
     * Bo nho dem chuan hoa.
     *
     * Chuan hoa (bo dau, bo ky tu la) la phan ton CPU nhat cua viec cham diem, ma mot
     * lan tim co the cham diem hang nghin ten truyen, moi lan lai chuan hoa lai dung
     * tu khoa do. Dem lai giup xep hang lai (moi khi mot nguon tra ve) gan nhu mien phi.
     */
    private val normCache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > 1024
    }

    fun normalize(raw: String): String {
        if (raw.isBlank()) return ""
        synchronized(normCache) { normCache[raw] }?.let { return it }
        val out = normalizeUncached(raw)
        synchronized(normCache) { normCache[raw] = out }
        return out
    }

    private fun normalizeUncached(raw: String): String {
        var s = raw.trim().lowercase()
        s = s.replace('\u0111', 'd').replace('\u0110'.lowercaseChar(), 'd')
        s = Normalizer.normalize(s, Normalizer.Form.NFD)
        s = COMBINING.replace(s, "")
        s = NON_ALNUM.replace(s, " ")
        return SPACES.replace(s, " ").trim()
    }

    /** Chuoi lien khong dau, khong khoang trang: "dauphathuongkhung". */
    fun compact(raw: String): String = normalize(raw).replace(" ", "")

    fun tokens(raw: String): List<String> =
        normalize(raw).split(" ").filter { it.isNotBlank() }

    /** Diem 0..1 giua tu khoa nguoi dung go va ten truyen tim duoc. */
    fun score(query: String, title: String): Double {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0

        val qc = q.replace(" ", "")
        val tc = t.replace(" ", "")
        if (qc == tc) return 0.99

        var best = 0.0

        // Ten chua tron tu khoa: "dau pha" -> "dau pha thuong khung"
        if (t.startsWith(q)) best = max(best, 0.95 - lengthPenalty(q, t))
        else if (t.contains(q)) best = max(best, 0.88 - lengthPenalty(q, t))
        else if (tc.contains(qc)) best = max(best, 0.84 - lengthPenalty(qc, tc))

        // Nguoc lai: nguoi dung go DAI hon ten that (them ten tac gia, "truyen ...")
        if (q.contains(t)) best = max(best, 0.80)

        // Trung khop theo TU: bao nhieu tu cua tu khoa co mat trong ten.
        val qt = q.split(" ").filter { it.isNotBlank() }
        val tt = t.split(" ").filter { it.isNotBlank() }
        if (qt.isNotEmpty()) {
            var hit = 0.0
            for (w in qt) {
                when {
                    tt.any { it == w } -> hit += 1.0
                    tt.any { it.startsWith(w) || w.startsWith(it) } -> hit += 0.7
                    tt.any { similarity(it, w) >= 0.8 } -> hit += 0.6
                }
            }
            val ratio = hit / qt.size
            // Thuong them khi cac tu nam dung THU TU trong ten.
            val ordered = if (orderedSubsequence(qt, tt)) 0.06 else 0.0
            best = max(best, ratio * 0.9 + ordered)
        }

        // Gan dung tren toan chuoi (chiu duoc go sai chinh ta).
        best = max(best, similarity(q, t) * 0.9)
        best = max(best, similarity(qc, tc) * 0.85)

        return best.coerceIn(0.0, 1.0)
    }

    /** Nhan hien thi cho nguoi dung. */
    fun label(score: Double): String = when {
        score >= 0.995 -> "Trung khop"
        score >= 0.8 -> "Rat gan"
        score >= 0.6 -> "Gan dung"
        else -> "Co the lien quan"
    }

    fun isExact(query: String, title: String): Boolean =
        normalize(query) == normalize(title) || compact(query) == compact(title)

    // Ten cang dai hon tu khoa thi cang bot diem mot chut (uu tien ten sat nghia).
    private fun lengthPenalty(q: String, t: String): Double {
        if (t.length <= q.length) return 0.0
        return min(0.18, (t.length - q.length) / (t.length.toDouble() * 2.2))
    }

    private fun orderedSubsequence(needle: List<String>, hay: List<String>): Boolean {
        var i = 0
        for (w in hay) {
            if (i < needle.size && (w == needle[i] || w.startsWith(needle[i]))) i++
        }
        return i == needle.size
    }

    /** 1 - khoang cach Levenshtein chuan hoa. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val d = levenshtein(a, b)
        val m = max(a.length, b.length)
        return (1.0 - d.toDouble() / m).coerceIn(0.0, 1.0)
    }

    /** Levenshtein 2 hang (tiet kiem bo nho, du nhanh cho ten truyen). */
    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        // Cat bot de khong tinh tren chuoi qua dai (ten truyen thuc te rat ngan).
        val s = if (a.length > 120) a.substring(0, 120) else a
        val t = if (b.length > 120) b.substring(0, 120) else b
        var prev = IntArray(t.length + 1) { it }
        var cur = IntArray(t.length + 1)
        for (i in 1..s.length) {
            cur[0] = i
            val ca = s[i - 1]
            for (j in 1..t.length) {
                val cost = if (ca == t[j - 1]) 0 else 1
                cur[j] = min(min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost)
            }
            val tmp = prev; prev = cur; cur = tmp
        }
        return prev[t.length]
    }
}
