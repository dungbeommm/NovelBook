package vn.tramtruyen.reader.engine

import org.jsoup.Jsoup
import org.jsoup.nodes.Comment
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.util.regex.Pattern

/** Lam sach noi dung chuong — port cua scrapers/cleaner.py (dung Jsoup thay BeautifulSoup). */
object Cleaner {

    private val REMOVE_SELECTORS = listOf(
        "script", "style", "iframe", "noscript",
        ".ads", ".advertisement", ".ad-container", "[class*='quang-cao']",
        ".comment", ".comments", "#comments", ".fb-comments",
        ".navigation", ".chapter-nav", ".nav-chapter", ".btn-nav",
        ".breadcrumb", ".breadcrumbs", "[aria-label=breadcrumb]", "[itemtype$=BreadcrumbList]",
        ".chapter-meta", ".chapter-info-bar", "time[datetime]", "[itemprop=datePublished]", ".social-share", ".share",
        "footer", "header", "nav",
        ".watermark", "[class*='watermark']",
        ".report", ".bookmark", ".rating",
        "[class*='footer']", "[class*='header']", "[class*='menu']",
        "[class*='sidebar']", ".sidebar",
        ".notice", ".alert", ".warning"
    )

    private val SPAM_PATTERNS: List<Pattern> = listOf(
        "truy\u1ec7n \u0111\u01b0\u1ee3c l\u1ea5y t\u1eeb.*?\\.com",
        "ngu\u1ed3n:?\\s*https?://\\S+",
        "\u0111\u1ecdc truy\u1ec7n.*?t\u1ea1i.*?\\.com",
        "ch\u01b0\u01a1ng tr\u01b0\u1edbc|ch\u01b0\u01a1ng sau|ch\u01b0\u01a1ng ti\u1ebfp",
        "\u2190 tr\u01b0\u1edbc|sau \u2192",
        "www\\.\\S+\\.com",
        "^\\s*qu\u1ea3ng c\u00e1o\\s*$"
    ).map { Pattern.compile(it, Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE) }

    private val VIET_LETTER = Regex("(?<=[a-zA-Z\u00c0-\u1ef9\u0110\u0111])\\.(?=[a-zA-Z\u00c0-\u1ef9\u0110\u0111])")

    fun removeHiddenDots(text: String): String = VIET_LETTER.replace(text, "")

    // Cac mau LINK bi chen trong noi dung chuong (se bi xoa khi lam sach).
    private val LINK_PATTERNS: List<Regex> = listOf(
        Regex("""https?://\S+""", RegexOption.IGNORE_CASE),
        Regex("""www\.[^\s]+""", RegexOption.IGNORE_CASE),
        Regex("""\b(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+(?:com|net|org|vn|info|biz|xyz|top|vip|cc|tv|me|io|co|site|online|store|club|pro|link|app|blog|news)\b(?:/[^\s]*)?""", RegexOption.IGNORE_CASE)
    )
    private val MULTISPACE = Regex("""[ \t]{2,}""")

    /** Xoa link/URL bi chen trong text, giu lai phan chu con lai. */
    fun stripLinks(text: String): String {
        var r = text
        for (p in LINK_PATTERNS) r = p.replace(r, " ")
        return r
    }

    private fun normTitle(s: String?): String {
        var r = (s ?: "").lowercase().trim()
        r = Regex("\\s+").replace(r, " ")
        return r.trim(' ', ':', '\u2013', '\u2014', '-', '.')
    }

    private val inlineWhitespace = Regex("[\\t\\r\\n\\u000C ]+")
    private val pageTimePrefix = Regex(
        """^\s*\d{1,2}:\d{2}(?::\d{2})?\s+\d{1,2}/\d{1,2}/\d{4}\s*\|\s*""",
    )
    private val pageTrail = Regex("""^Trang chủ\s+.+\s+Chương\s*\d+.*Dịch\s+AI\s*Convert$""", RegexOption.IGNORE_CASE)

    private val conversionLabel = Regex("""^Dịch\s+AI\s*Convert$""", RegexOption.IGNORE_CASE)

    private fun getTextSeparated(el: Element): String {
        val text = StringBuilder()
        var lineBreak = false
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element && (node.tag().isBlock || node.normalName() == "br")) {
                    lineBreak = true
                }
                if (node is TextNode) {
                    val value = inlineWhitespace.replace(node.wholeText, " ")
                    if (lineBreak) {
                        if (value.isBlank()) return
                        if (text.isNotEmpty()) text.append('\n')
                        lineBreak = false
                    }
                    text.append(value)
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.tag().isBlock) lineBreak = true
            }
        }, el)
        return text.toString()
    }

    private fun removeComments(root: Node) {
        val toRemove = ArrayList<Node>()
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(n: Node, depth: Int) { if (n is Comment) toRemove.add(n) }
            override fun tail(n: Node, depth: Int) {}
        }, root)
        toRemove.forEach { it.remove() }
    }

    /** Xoa tieu de (ten truyen/ten chuong) bi lap o dau noi dung. */
    fun stripLeadingTitles(text: String, dropTitles: List<String>): String {
        if (text.isEmpty()) return text
        val norms = HashSet<String>()
        for (t in dropTitles) {
            val n = normTitle(t)
            if (n.isNotEmpty() && n.length >= 3) norms.add(n)
        }
        val lines = text.split("\n").toMutableList()
        var i = 0
        var removed = 0
        while (i < lines.size && removed < 6) {
            val raw = lines[i].trim()
            if (raw.isEmpty()) { i++; continue }
            val nline = normTitle(raw)
            if (nline in norms) { i++; removed++; continue }
            break
        }
        return lines.subList(i, lines.size).joinToString("\n").trim()
    }

    /**
     * Lam sach HTML chuong -> text thuan.
     *
     * Toan bo qua trinh chay trong Kotlin: Jsoup boc tach DOM, sau do
     * TextPipeline lam sach phan text da trich xuat.
     */
    fun cleanChapterContent(html: String, contentSelector: String? = null): String {
        val doc = Jsoup.parse(html)
        removeComments(doc)
        for (sel in REMOVE_SELECTORS) {
            try { doc.select(sel).remove() } catch (_: Exception) {}
        }
        var root: Element = doc.body()
        if (contentSelector != null) {
            val el = try { doc.selectFirst(contentSelector) } catch (_: Exception) { null }
            if (el != null) root = el
        }
        return cleanChapterText(getTextSeparated(root))
    }

    /** Removes page chrome while retaining punctuation and prose already extracted as text. */
    fun cleanChapterText(text: String): String =
        text.trim().lineSequence().map(String::trim)
            .dropWhile { it.isBlank() || pageTrail.matches(it) || conversionLabel.matches(it) }
            .mapIndexed { index, line -> if (index == 0) pageTimePrefix.replaceFirst(line, "") else line }
            .joinToString("\n").trim()

    /**
     * Lam sach phan THUAN VAN BAN (loc spam, xoa link, xoa dau an, gop dong trong).
     * Phan nay chay thuan Kotlin, khong phu thuoc native library.
     */
    fun cleanExtractedText(text0: String): String {
        val cleaned = ArrayList<String>()
        for (line0 in text0.split("\n")) {
            val line = line0.trim()
            if (line.isEmpty()) { cleaned.add(""); continue }
            val isSpam = SPAM_PATTERNS.any { it.matcher(line).find() }
            if (isSpam) continue
            // Xoa link bi chen trong dong, giu lai phan chu
            val noLink = MULTISPACE.replace(stripLinks(line), " ").trim()
            cleaned.add(noLink)
        }
        var text = cleaned.joinToString("\n")
        text = removeHiddenDots(text)
        text = Regex("\\n{3,}").replace(text, "\n\n")
        return text.trim()
    }

    fun extractChapterTitle(html: String, titleSelector: String? = null): String {
        val doc = Jsoup.parse(html)
        if (titleSelector != null) {
            val el = try { doc.selectFirst(titleSelector) } catch (_: Exception) { null }
            if (el != null) return el.text().trim()
        }
        for (tag in listOf("h1", "h2")) {
            val el = doc.selectFirst(tag)
            if (el != null) return el.text().trim()
        }
        return "Untitled"
    }
}
