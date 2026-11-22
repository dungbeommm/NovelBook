package vn.tramtruyen.reader.engine

import android.content.Context

/**
 * TACH TU / AM TIET TIENG VIET — hien thuc cho co `apply_word_segmentation`.
 *
 * App goc KHONG kem model tach tu rieng: file `omni.ja` la bo tai nguyen GeckoView
 * (chrome.manifest, tu dien ngat am tiet cua Firefox), khong phai model NLP. Vi vay
 * o day dung 2 lop:
 *
 *   1) Tach AM TIET bang QUY TAC cau truc am tiet tieng Viet (khong can model):
 *      am dau + van + am cuoi. Dung de tach chuoi bi DINH LIEN (khong co dau cach)
 *      thuong gap o noi dung dich may tho — vd "toanhoc" -> "toan hoc".
 *   2) (Tuy chon) Gop TU bang tu dien: neu co file assets `vietnormalizer/vi_words.txt`
 *      (moi dong 1 tu ghep) thi dung khop dai nhat de gop cac am tiet lien quan.
 *      Khong bat buoc — mac dinh chi tach am tiet.
 */
object VietWordSegmenter {

    @Volatile private var loaded = false
    // Tu dien tu ghep (tuy chon). Key la cum "am1 am2" viet thuong khong dau phan biet.
    private val phrases = HashSet<String>()
    private var maxPhraseWords = 1

    // Am dau (xep dai truoc de khop tham lam).
    private val ONSETS = listOf(
        "ngh", "ng", "nh", "ch", "gh", "gi", "kh", "ph", "qu", "th", "tr",
        "b", "c", "d", "\u0111", "g", "h", "k", "l", "m", "n", "p", "q", "r", "s", "t", "v", "x"
    )
    // Am cuoi (xep dai truoc).
    private val CODAS = listOf("ngh", "ng", "nh", "ch", "c", "m", "n", "p", "t")

    private const val VOWELS = "a\u00e0\u00e1\u1ea3\u00e3\u1ea1\u0103\u1eb1\u1eaf\u1eb3\u1eb5\u1eb7\u00e2\u1ea7\u1ea5\u1ea9\u1eab\u1ead" +
        "e\u00e8\u00e9\u1ebb\u1ebd\u1eb9\u00ea\u1ec1\u1ebf\u1ec3\u1ec5\u1ec7" +
        "i\u00ec\u00ed\u1ec9\u0129\u1ecb" +
        "o\u00f2\u00f3\u1ecf\u00f5\u1ecd\u00f4\u1ed3\u1ed1\u1ed5\u1ed7\u1ed9\u01a1\u1edd\u1edb\u1edf\u1ee1\u1ee3" +
        "u\u00f9\u00fa\u1ee7\u0169\u1ee5\u01b0\u1eeb\u1ee9\u1eed\u1eef\u1ef1" +
        "y\u1ef3\u00fd\u1ef7\u1ef9\u1ef5"

    // Tra nguyen am bang HashSet -> O(1) thay vi quet tuyen tinh chuoi VOWELS moi ky tu.
    private val VOWEL_SET: HashSet<Char> = VOWELS.toHashSet()
    private fun isVowel(c: Char): Boolean = VOWEL_SET.contains(c.lowercaseChar())

    fun ensureLoaded(context: Context?) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                val ctx = context ?: Config.appContext
                ctx?.assets?.open("vietnormalizer/vi_words.txt")?.bufferedReader(Charsets.UTF_8)?.useLines { lines ->
                    for (raw in lines) {
                        val w = raw.trim().lowercase()
                        if (w.isEmpty()) continue
                        val n = w.split(Regex("\\s+")).size
                        if (n >= 2) { phrases.add(w); if (n > maxPhraseWords) maxPhraseWords = n }
                    }
                }
            } catch (_: Exception) {}
            loaded = true
        }
    }

    // Mot cach khop 1 am tiet: vi tri ket thuc + co am dau + co am cuoi.
    private data class SylCand(val end: Int, val hasOnset: Boolean, val hasCoda: Boolean)

    /**
     * Liet ke MOI cach khop 1 am tiet tieng Viet bat dau tu `i`:
     * truong hop khong co am cuoi, va tung am cuoi hop le. Rong neu khong co nguyen am.
     * Nho giu du cac ung vien nay, splitGlued moi co the quay lui/toi uu de tranh
     * "nuot" phu am dau cua am tiet ke tiep (vd "toanhoc" -> "toan hoc", khong phai "toanh oc").
     */
    private fun syllableCandidates(s: String, i: Int): List<SylCand> {
        val n = s.length
        var j = i
        var hasOnset = false
        // Am dau (tuy chon) - chi nhan neu ngay sau la nguyen am.
        for (o in ONSETS) {
            if (j + o.length <= n && s.regionMatches(j, o, 0, o.length, ignoreCase = true)) {
                val after = j + o.length
                if (after < n && isVowel(s[after])) { j = after; hasOnset = true; break }
            }
        }
        // Nguyen am (bat buoc, 1..3 ky tu).
        val vStart = j
        var vCount = 0
        while (j < n && isVowel(s[j]) && vCount < 3) { j++; vCount++ }
        if (j == vStart) return emptyList()
        val res = ArrayList<SylCand>()
        // Truong hop khong co am cuoi.
        res.add(SylCand(j, hasOnset, false))
        // Cac am cuoi hop le (giu lai de quay lui/toi uu chon sau).
        for (c in CODAS) {
            if (j + c.length <= n && s.regionMatches(j, c, 0, c.length, ignoreCase = true)) {
                res.add(SylCand(j + c.length, hasOnset, true))
            }
        }
        return res
    }

    /** Token co phai DUY NHAT 1 am tiet hop le (tu don) hay khong -> neu phai thi khong tach. */
    private fun isSingleSyllable(token: String): Boolean {
        for (cand in syllableCandidates(token, 0)) {
            if (cand.end == token.length) return true
        }
        return false
    }

    /**
     * Tach 1 token DINH LIEN thanh cac am tiet bang QUY HOACH DONG:
     * chon cach phu kin token co tong diem cao nhat (uu tien am tiet dang CVC co ca
     * am dau lan am cuoi), nho vay khong "nuot" am dau cua am tiet sau.
     * Tra null neu token la tu don hoac khong the phu kin bang am tiet hop le.
     */
    private fun splitGlued(token: String): List<String>? {
        // Tu don hop le -> giu nguyen, tranh tach nham (vd "khoang", "nghieng").
        if (isSingleSyllable(token)) return null
        val n = token.length
        val neg = Int.MIN_VALUE / 4
        // best[i] = diem toi uu de phu kin phan tu i den het; nextEnd[i] = diem ket thuc am tiet chon.
        val best = IntArray(n + 1) { neg }
        val nextEnd = IntArray(n + 1) { -1 }
        best[n] = 0
        for (i in n - 1 downTo 0) {
            for (cand in syllableCandidates(token, i)) {
                val sub = best[cand.end]
                if (sub <= neg) continue
                val score = (if (cand.hasOnset) 2 else 0) + (if (cand.hasCoda) 1 else 0) + sub
                if (score > best[i]) { best[i] = score; nextEnd[i] = cand.end }
            }
        }
        if (best[0] <= neg) return null // khong phu kin duoc bang am tiet hop le
        val out = ArrayList<String>()
        var i = 0
        while (i < n) {
            val e = nextEnd[i]
            if (e <= i) return null
            out.add(token.substring(i, e))
            i = e
        }
        return if (out.size >= 2) out else null
    }

    private fun isAllVietLetters(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isLetter() }

    /**
     * Tach tu cho toan bo van ban:
     *   - Token chu DINH LIEN (dai, nhieu am tiet) -> chen dau cach giua cac am tiet.
     *   - Cac token khac giu nguyen.
     */
    fun segment(input: String): String {
        if (input.isBlank()) return input
        ensureLoaded(Config.appContext)
        val sb = StringBuilder()
        val tok = StringBuilder()
        fun flush() {
            if (tok.isEmpty()) return
            val t = tok.toString()
            tok.setLength(0)
            // Chi thu tach neu token du dai va toan chu cai Viet (co the la chuoi dinh lien).
            if (t.length >= 6 && isAllVietLetters(t)) {
                val parts = splitGlued(t)
                if (parts != null) { sb.append(parts.joinToString(" ")); return }
            }
            sb.append(t)
        }
        for (ch in input) {
            if (ch.isLetter()) tok.append(ch) else { flush(); sb.append(ch) }
        }
        flush()
        var result = sb.toString()
        if (phrases.isNotEmpty()) result = joinPhrases(result)
        return result
    }

    /** (Tuy chon) Gop cac am tiet thanh tu ghep bang tu dien vi_words.txt (khop dai nhat). */
    private fun joinPhrases(text: String): String {
        return text.split("\n").joinToString("\n") { line ->
            val words = line.split(" ")
            val out = ArrayList<String>()
            var i = 0
            while (i < words.size) {
                var matched = false
                var take = minOf(maxPhraseWords, words.size - i)
                while (take >= 2) {
                    val cand = words.subList(i, i + take).joinToString(" ").lowercase()
                    if (phrases.contains(cand)) {
                        out.add(words.subList(i, i + take).joinToString("_"))
                        i += take; matched = true; break
                    }
                    take--
                }
                if (!matched) { out.add(words[i]); i++ }
            }
            out.joinToString(" ")
        }
    }
}
