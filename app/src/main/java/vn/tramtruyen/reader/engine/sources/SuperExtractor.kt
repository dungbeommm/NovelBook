package vn.tramtruyen.reader.engine.sources

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * ENGINE trich xuat kieu SuperCrawler cua Aufi, ban Kotlin chay tren Jsoup,
 * dung bo rule trong AufiRules. Nam o duong trich xuat TONG QUAT:
 *   xoa rac khoi DOM -> chuoi selector + leo thang -> (du phong mat do o
 *   GenericExtractor) -> loc dong quang cao -> cat rac dau/duoi bai.
 * Kem theo: nhan dien trang rac (Cloudflare/bot check/tuong dang nhap/web sap),
 * cong kiem dinh do dai, va cascade dieu huong chuong sau/truoc bang tu khoa
 * da ngon ngu + danh sach chuong ngay tren trang.
 */
object SuperExtractor {

    // ---------- 1) Xoa rac khoi DOM bang TRASH_SELECTORS ----------
    fun removeTrash(doc: Document) {
        val sels = AufiRules.TRASH_SELECTORS
        var i = 0
        while (i < sels.size) {
            val chunk = sels.subList(i, minOf(i + 25, sels.size))
            try {
                doc.select(chunk.joinToString(", ")).remove()
            } catch (_: Exception) {
                // 1 selector hong trong cum -> xoa le tung cai, bo qua cai loi.
                for (sel in chunk) {
                    try { doc.select(sel).remove() } catch (_: Exception) {}
                }
            }
            i += chunk.size
        }
    }

    // ---------- 2) Chuoi selector noi dung + leo thang (kieu Aufi) ----------
    /**
     * Duyet CONTENT_SELECTORS theo dung thu tu rule; ung vien dau tien co text
     * > CANDIDATE_MIN_LEN la mac dinh thang. Chi 'leo thang' sang ung vien dai
     * nhat khi no dai gap >= ESCALATE_RATIO lan, hon >= ESCALATE_GAP ky tu va
     * phan dai hon la van xuoi (tranh truong hop khoi dai nhat la menu/binh luan).
     */
    fun selectContentElement(doc: Document): Element? {
        var first: Element? = null
        var firstLen = 0
        var best: Element? = null
        var bestLen = 0
        for (sel in AufiRules.CONTENT_SELECTORS) {
            val els = try { doc.select(sel) } catch (_: Exception) { continue }
            for (el in els) {
                val len = el.text().trim().length
                if (len <= AufiRules.CANDIDATE_MIN_LEN) continue
                if (first == null) { first = el; firstLen = len }
                if (len > bestLen) { best = el; bestLen = len }
            }
        }
        if (first == null) return null
        if (best != null && best !== first &&
            bestLen >= firstLen * AufiRules.ESCALATE_RATIO &&
            bestLen - firstLen >= AufiRules.ESCALATE_GAP &&
            deltaIsProse(best.text().trim(), first.text().trim())
        ) return best
        return first
    }

    private fun deltaIsProse(bestText: String, firstText: String): Boolean {
        val delta = if (bestText.startsWith(firstText)) bestText.substring(firstText.length) else bestText
        if (delta.length < 200) return false
        val punct = delta.count {
            it == '.' || it == '!' || it == '?' || it == '…' || it == '。' || it == '"' || it == '”'
        }
        val letters = delta.count { it.isLetter() || it == ' ' }
        return punct >= 3 && letters.toDouble() / delta.length > 0.55
    }

    // ---------- 3) Lam sach text: TEXT_FILTERS + junk_strip ----------
    private val plainFilters: List<String> =
        AufiRules.TEXT_FILTERS.filter { !it.startsWith("regex:") }
    private val regexFilters: List<Regex> = AufiRules.TEXT_FILTERS
        .filter { it.startsWith("regex:") }
        .mapNotNull { runCatching { Regex(it.removePrefix("regex:")) }.getOrNull() }

    private fun matchesFilter(line: String): Boolean {
        for (r in regexFilters) if (r.containsMatchIn(line)) return true
        // Filter chuoi con chi ap cho dong NGAN (nhan dieu huong/quang cao/
        // watermark). Dong van xuoi dai chua cum tu giong filter thi giu lai,
        // tranh cat nham noi dung truyen.
        if (line.length <= 200) {
            for (p in plainFilters) if (line.contains(p)) return true
        }
        return false
    }

    /** Loc dong quang cao/bay mo khoa + cat rac dau/duoi bai (junk_strip). */
    fun cleanText(text: String): String {
        if (text.isBlank()) return text
        val kept = text.lines().filterNot { matchesFilter(it) }
        return stripEdgeJunk(kept).joinToString("\n").trim()
    }

    private fun isJunkLine(line: String): Boolean {
        val t = line.trim()
        if (t.isEmpty()) return true
        if (matchesFilter(t)) return true
        // Dong rat ngan, khong dau cau, khong phai loi thoai -> kha nang la rac.
        return t.length <= 40 &&
            t.none { it == '.' || it == '!' || it == '?' || it == '…' || it == '"' } &&
            !t.startsWith("-") && !t.startsWith("—") && !t.startsWith("“")
    }

    private fun stripEdgeJunk(lines: List<String>): List<String> =
        cutHead(cutTail(lines))

    private fun cutTail(lines: List<String>): List<String> {
        if (lines.count { it.isNotBlank() } < AufiRules.JUNK_MIN_LINES_TO_ACT) return lines
        val totalChars = lines.sumOf { it.length }
        var i = lines.size - 1
        var run = 0
        var cutChars = 0
        while (i >= 0) {
            val l = lines[i]
            if (l.isBlank()) { i--; continue }
            if (isJunkLine(l)) { run++; cutChars += l.length; i-- } else break
        }
        if (run < AufiRules.JUNK_MIN_RUN_LINES) return lines
        val keptChars = totalChars - cutChars
        if (keptChars < AufiRules.JUNK_MIN_KEEP_CHARS) return lines
        val frac = if (totalChars == 0) 0.0 else cutChars.toDouble() / totalChars
        // Vuot ty le an toan thi chi cat khi duoi la day rac dai thuan tuy.
        val longPureJunkTail = run >= AufiRules.JUNK_MIN_RUN_LINES * 2 && keptChars >= 1000
        if (frac > AufiRules.JUNK_MAX_TAIL_FRACTION && !longPureJunkTail) return lines
        return lines.subList(0, i + 1)
    }

    private fun cutHead(lines: List<String>): List<String> {
        if (lines.count { it.isNotBlank() } < AufiRules.JUNK_MIN_LINES_TO_ACT) return lines
        val totalChars = lines.sumOf { it.length }
        var i = 0
        var run = 0
        var cutChars = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.isBlank()) { i++; continue }
            if (isJunkLine(l)) { run++; cutChars += l.length; i++ } else break
        }
        if (run < AufiRules.JUNK_MIN_HEAD_RUN_LINES) return lines
        val keptChars = totalChars - cutChars
        if (keptChars < AufiRules.JUNK_MIN_KEEP_CHARS) return lines
        val frac = if (totalChars == 0) 0.0 else cutChars.toDouble() / totalChars
        val longPureJunkHead = run >= 6 && keptChars >= 1000
        if (frac > AufiRules.JUNK_MAX_HEAD_FRACTION && !longPureJunkHead) return lines
        return lines.subList(i, lines.size)
    }

    // ---------- 4) Nhan dien trang rac tren HTML tho ----------
    private val CF_MARKERS = listOf("_cf_chl_opt", "/cdn-cgi/challenge-platform/")
    private val BOT_CHECK_RE = Regex(
        "security check|verify (that )?you('| a)re (a )?human|are you a robot|captcha|" +
            "checking your browser|just a moment|access denied|unusual traffic|" +
            "too many requests|rate limit",
        RegexOption.IGNORE_CASE,
    )
    private val ACCESS_WALL_RE = Regex(
        "đăng nhập để (đọc|tiếp tục)|mở khoá chương|mở khóa chương|" +
            "login to (read|continue)|sign in to (read|continue)|subscribe to read",
        RegexOption.IGNORE_CASE,
    )
    private val SITE_DOWN_RE = Regex(
        "5\\d\\d: connection timed out|web server is down|error (5\\d\\d)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Tra ve loai trang rac: "bot_check" | "site_login" | "site_error", hoac
     * null neu la trang binh thuong. Nguong do dai theo dung Aufi: marker
     * Cloudflare thi khong gioi han (chi xuat hien o trang thu thach), bot
     * check va tuong dang nhap chi xet khi trang <= 1500 ky tu, loi 5xx khi
     * <= 3000 ky tu — trang chuong that luon dai hon nhieu.
     */
    fun junkKind(html: String): String? {
        for (m in CF_MARKERS) if (html.contains(m)) return "bot_check"
        if (html.length <= 1500 && BOT_CHECK_RE.containsMatchIn(html)) return "bot_check"
        if (html.length <= 1500 && ACCESS_WALL_RE.containsMatchIn(html)) return "site_login"
        if (html.length <= 3000 && SITE_DOWN_RE.containsMatchIn(html)) return "site_error"
        return null
    }

    /** Cong kiem dinh do dai kieu Aufi (tang goi quyet dinh chap nhan ket qua). */
    fun isValidChapter(content: String, matchedSelector: Boolean): Boolean {
        val len = content.trim().length
        return if (matchedSelector) len >= AufiRules.MIN_CHARS_MATCHED
        else len >= AufiRules.MIN_CHARS_UNMATCHED
    }

    // ---------- 5) Dieu huong: cascade tu khoa + danh sach chuong tren trang ----------
    private val TAXONOMY_RE = Regex(
        "/(tag|tags|the-loai|category|categories|genre|genres)/|[?&]tags?=",
        RegexOption.IGNORE_CASE,
    )

    fun findNextByCascade(doc: Document, currentUrl: String): String? =
        findByKeyword(doc, currentUrl, AufiRules.NEXT_KEYWORDS)
            ?: neighborInChapterList(doc, currentUrl, +1)

    fun findPrevByCascade(doc: Document, currentUrl: String): String? =
        findByKeyword(doc, currentUrl, AufiRules.PREV_KEYWORDS)
            ?: neighborInChapterList(doc, currentUrl, -1)

    /**
     * Cham diem moi the <a> theo tu khoa da ngon ngu: khop nguyen van > bat dau
     * bang > chua tu khoa; tu khoa cang dai cang dac hieu. Bo link tro ve chinh
     * trang hien tai va link trang phan loai (tag/the loai...).
     */
    private fun findByKeyword(doc: Document, currentUrl: String, keywords: List<String>): String? {
        var bestUrl: String? = null
        var bestScore = 0
        for (a in doc.select("a[href]")) {
            val href = a.attr("href")
            if (href.isBlank() || href == "#" || href.startsWith("javascript:")) continue
            val abs = a.absUrl("href").ifBlank { href }
            if (abs == currentUrl) continue
            if (TAXONOMY_RE.containsMatchIn(abs)) continue
            val text = a.text().trim().lowercase()
            val title = a.attr("title").trim().lowercase()
            var score = 0
            for (kw in keywords) {
                val k = kw.lowercase()
                if (k.isEmpty()) continue
                score = when {
                    text == k || title == k -> maxOf(score, 100 + k.length)
                    text.startsWith(k) || title.startsWith(k) -> maxOf(score, 60 + k.length)
                    text.contains(k) || title.contains(k) -> maxOf(score, 30 + k.length)
                    else -> score
                }
            }
            if (score > bestScore) { bestScore = score; bestUrl = abs }
        }
        // Nguong toi thieu: tranh bat nham link chua tu khoa qua ngan/mo ho.
        return if (bestScore >= 40) bestUrl else null
    }

    /** Chuong ke ngay trong danh sach chuong nhung san tren trang chuong. */
    private fun neighborInChapterList(doc: Document, currentUrl: String, dir: Int): String? {
        val links = try { GenericExtractor.guessChapterLinks(doc) } catch (_: Exception) { return null }
        if (links.size < 2) return null
        val idx = links.indexOfFirst { it.url.trimEnd('/') == currentUrl.trimEnd('/') }
        if (idx < 0) return null
        val n = idx + dir
        return if (n in links.indices) links[n].url else null
    }
}
