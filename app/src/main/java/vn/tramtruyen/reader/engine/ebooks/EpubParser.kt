package vn.tramtruyen.reader.engine.ebooks

import vn.tramtruyen.reader.engine.ApiError
import vn.tramtruyen.reader.engine.Cleaner
import vn.tramtruyen.reader.engine.Config
import kotlinx.serialization.json.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** EPUB index contains paths and titles only. Chapter reads inflate one ZIP entry. */
object EpubParser {
    internal const val MAX_BOOK_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRY_BYTES = 16L * 1024 * 1024
    private const val MAX_ENTRIES = 10_000
    private const val MAX_INDEX_BYTES = 8L * 1024 * 1024
    private val colors = listOf("#8b5cf6", "#14b8a6", "#f43f5e", "#eab308", "#ec4899", "#3b82f6", "#10b981")
    private val bookId = Regex("epub-[a-f0-9]{24}")
    private val chapterHeading = Regex("""^(chương|chuong|chapter|hồi|quyển)\s*\d+""", RegexOption.IGNORE_CASE)
    private val spaces = Regex("[\\p{Zs}\\s]+")
    private val blankLines = Regex("\\n{3,}")
    private val htmlExtensions = setOf("html", "xhtml", "htm")
    private const val IGNORED_TAGS = "script,style,noscript,rt,rp"
    private val externalScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")
    private val paragraphTags = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote")
    private val indices = LinkedHashMap<String, BookIndex>(8, 0.75f, true)

    private data class Chapter(val path: String, val title: String, val heading: String?)
    private data class BookIndex(val title: String, val author: String, val chapters: List<Chapter>)
    private data class ManifestItem(val id: String, val href: String, val type: String, val properties: Set<String>) {
        val isHtml get() = type.contains("html", true) || href.substringBefore('#').substringBefore('?')
            .substringAfterLast('.').lowercase() in htmlExtensions
    }
    private data class Package(
        val directory: String,
        val title: String,
        val author: String,
        val manifest: Map<String, ManifestItem>,
        val linear: List<String>,
        val spine: List<String>,
        val coverId: String,
    )

    private fun epubDir() = File(Config.dataDir, "epubs").apply { mkdirs() }
    private fun coverDir() = File(Config.dataDir, "epub_covers").apply { mkdirs() }
    private fun file(id: String): File {
        if (!bookId.matches(id)) throw ApiError(404, "Không tìm thấy EPUB")
        return File(epubDir(), "$id.epub")
    }
    private fun indexFile(id: String) = File(epubDir(), "$id.index-v1.json")
    private fun cover(id: String) = coverDir().listFiles()?.firstOrNull {
        it.nameWithoutExtension == id
    }?.toURI()?.toString().orEmpty()

    private fun indexInMemory(id: String, index: BookIndex) = synchronized(indices) {
        indices[id] = index
        if (indices.size > 8) indices.remove(indices.keys.first())
    }

    private fun localName(element: Element) = element.tagName().substringAfterLast(':')

    private fun normalizePath(path: String): String? {
        val segments = ArrayList<String>()
        for (segment in path.replace('\\', '/').split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isEmpty()) return null else segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return segments.joinToString("/")
    }

    private fun decodeHref(path: String): String = try {
        URLDecoder.decode(path.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        path
    }

    private class Archive(private val zip: ZipFile) {
        init {
            if (zip.size() > MAX_ENTRIES) throw ApiError(400, "EPUB có quá nhiều tệp")
        }

        private val files: Map<String, ZipEntry> by lazy {
            zip.entries().asSequence().filterNot { it.isDirectory }
                .mapNotNull { entry -> normalizePath(entry.name)?.let { it to entry } }.toMap()
        }

        fun resolve(directory: String, href: String): ZipEntry? {
            val path = href.substringBefore('#').substringBefore('?')
            if (path.isBlank() || externalScheme.containsMatchIn(path) || path.startsWith("//")) return null
            val decoded = decodeHref(path)
            for (candidate in listOf(decoded, path)) {
                val joined = if (candidate.startsWith('/') || directory.isEmpty()) candidate else "$directory/$candidate"
                files[normalizePath(joined)]?.let { return it }
            }
            return files[normalizePath(decoded)] ?: files[normalizePath(path)]
        }

        fun packageData(): Package {
            val containerPath = files["META-INF/container.xml"]?.let { entry ->
                document(entry, Parser.xmlParser()).allElements
                    .firstOrNull { localName(it) == "rootfile" }
                    ?.attr("full-path")
            }
            val opf = containerPath?.let { resolve("", it) }
                ?: files.values.firstOrNull { it.name.endsWith(".opf", true) }
                ?: throw ApiError(400, "EPUB không hợp lệ (thiếu tệp .opf)")
            val elements = document(opf, Parser.xmlParser()).allElements
            val manifest = linkedMapOf<String, ManifestItem>()
            val linear = ArrayList<String>()
            val spine = ArrayList<String>()
            var title = ""
            var author = ""
            var coverId = ""
            for (element in elements) {
                when (localName(element)) {
                    "title" -> if (title.isBlank()) title = element.text()
                    "creator" -> if (author.isBlank()) author = element.text()
                    "meta" -> if (element.attr("name").equals("cover", true)) coverId = element.attr("content")
                    "item" -> {
                        val id = element.attr("id")
                        val href = element.attr("href")
                        if (id.isNotBlank() && href.isNotBlank()) {
                            manifest[id] = ManifestItem(id, href, element.attr("media-type"), element.attr("properties").split(' ').toSet())
                        }
                    }
                    "itemref" -> {
                        val id = element.attr("idref")
                        if (id.isNotBlank()) {
                            spine += id
                            if (!element.attr("linear").equals("no", true)) linear += id
                        }
                    }
                }
            }
            val directory = opf.name.replace('\\', '/').substringBeforeLast('/', "")
            return Package(directory, title, author.ifBlank { "Không rõ" }, manifest, linear, spine, coverId)
        }

        fun document(entry: ZipEntry, parser: Parser = Parser.htmlParser()) =
            read(entry) { Jsoup.parse(it, null, "", parser) }

        fun chapter(path: String): ZipEntry = zip.getEntry(path)
            ?: throw ApiError(400, "Không tìm thấy tệp nội dung chương trong EPUB")

        fun <T> read(entry: ZipEntry, consume: (InputStream) -> T): T {
            if (entry.size > MAX_ENTRY_BYTES) throw ApiError(400, "Tệp nội dung trong EPUB vượt giới hạn 16 MB")
            return zip.getInputStream(entry).use { raw ->
                consume(object : FilterInputStream(raw) {
                    private var count = 0L
                    private fun add(read: Int): Int {
                        if (read > 0 && run { count += read; count > MAX_ENTRY_BYTES }) {
                            throw ApiError(400, "Tệp nội dung trong EPUB vượt giới hạn 16 MB")
                        }
                        return read
                    }
                    override fun read(): Int = super.read().also { if (it >= 0) add(1) }
                    override fun read(bytes: ByteArray, off: Int, len: Int): Int = add(super.read(bytes, off, len))
                    override fun markSupported(): Boolean = false
                })
            }
        }
    }

    private fun buildIndex(archive: Archive, pkg: Package, filename: String, savedBook: Boolean = false): BookIndex {
        fun chapters(order: Collection<String>): List<Chapter> {
            return order.mapNotNull { id ->
                val item = pkg.manifest[id]?.takeIf { it.isHtml } ?: return@mapNotNull null
                val entry = archive.resolve(pkg.directory, item.href) ?: return@mapNotNull null
                val document = archive.document(entry)
                val body = document.body()
                body.select(IGNORED_TAGS).remove()
                if (!body.hasText() && (!savedBook || document.title().isBlank())) return@mapNotNull null
                val heading = body.selectFirst("h1,h2,h3,h4,h5,h6")?.text()
                    ?.takeIf { it.isNotBlank() && it != pkg.title }
                    ?: body.selectFirst("p")?.text()?.takeIf { it.length <= 160 && chapterHeading.containsMatchIn(it) }
                val title = heading ?: document.title().takeIf { it.isNotBlank() && it != pkg.title }
                    ?: decodeHref(entry.name.substringAfterLast('/').substringBeforeLast('.'))
                Chapter(entry.name, title, heading)
            }
        }
        val primaryOrder = pkg.linear.ifEmpty { pkg.spine }
        val items = chapters(primaryOrder).ifEmpty {
            chapters(pkg.manifest.values.filter { "nav" !in it.properties }.map { it.id })
        }
        if (items.isEmpty()) throw ApiError(400, "EPUB không có chương văn bản để đọc")
        val title = pkg.title.ifBlank {
            filename.replace(Regex("(?i)\\.epub$"), "").replace(Regex("[-_]+"), " ").trim().ifBlank { "Truyện EPUB" }
        }
        return BookIndex(title, pkg.author, items)
    }

    private fun chapterText(archive: Archive, chapter: Chapter): String {
        val document = archive.document(archive.chapter(chapter.path))
        val body = document.body()
        body.select(IGNORED_TAGS).remove()
        val out = StringBuilder()
        var pendingBreak = 0
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                if (node is Element) {
                    if (node.normalName() == "br") pendingBreak = (pendingBreak + 1).coerceAtMost(2)
                    else if (node.tag().isBlock) pendingBreak = maxOf(pendingBreak, 1)
                }
                if (node is TextNode) {
                    val value = spaces.replace(node.wholeText, " ")
                    if (value.isBlank() && pendingBreak > 0) return
                    if (pendingBreak > 0) {
                        while (out.isNotEmpty() && out.last() == ' ') out.setLength(out.length - 1)
                        if (out.isNotEmpty()) repeat(pendingBreak) { out.append('\n') }
                        pendingBreak = 0
                    }
                    out.append(value)
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.tag().isBlock) {
                    pendingBreak = maxOf(pendingBreak, if (node.normalName() in paragraphTags) 2 else 1)
                }
            }
        }, body)
        val full = out.toString().trim().ifBlank { document.title() }
        // Strip only an exact duplicate of a verified heading. A short first line can be story text.
        val text = if (chapter.heading != null && full.substringBefore('\n').trim() == chapter.heading) {
            full.substringAfter('\n', "").trim().ifEmpty { full }
        } else full
        return Cleaner.stripLinks(text).lineSequence().joinToString("\n") { it.trim() }
            .replace(blankLines, "\n\n").trim().ifEmpty { full }
    }

    private fun copyCover(archive: Archive, pkg: Package, id: String) {
        val image = pkg.manifest.values.firstOrNull { "cover-image" in it.properties }
            ?: pkg.manifest[pkg.coverId]
            ?: pkg.manifest.values.firstOrNull {
                it.type.startsWith("image/") && (it.href.contains("cover", true) || it.id.contains("cover", true))
            }
            ?: return
        val entry = archive.resolve(pkg.directory, image.href) ?: return
        val extension = when (image.type.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/svg+xml" -> "svg"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        runCatching {
            val target = File(coverDir(), "$id.$extension")
            writeAtomically(target) { tmp ->
                archive.read(entry) { input -> tmp.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    private fun writeAtomically(target: File, write: (File) -> Unit) {
        val temp = File.createTempFile("epub-", ".tmp", target.parentFile)
        try {
            write(temp)
            if (!temp.renameTo(target)) throw ApiError(500, "Không lưu được tệp EPUB")
        } finally {
            temp.delete()
        }
    }

    private fun saveIndex(id: String, index: BookIndex) {
        runCatching {
            val data = buildJsonObject {
                put("title", index.title)
                put("author", index.author)
                put("chapters", JsonArray(index.chapters.map { chapter ->
                    buildJsonObject {
                        put("path", chapter.path)
                        put("title", chapter.title)
                        chapter.heading?.let { put("heading", it) }
                    }
                }))
            }
            writeAtomically(indexFile(id)) { it.writeText(data.toString()) }
        }
        indexInMemory(id, index)
    }

    private fun readIndex(id: String): BookIndex? {
        synchronized(indices) { indices[id] }?.let { return it }
        val indexPath = indexFile(id)
        if (indexPath.length() !in 1..MAX_INDEX_BYTES) return null
        return runCatching {
            val data = Json.parseToJsonElement(indexPath.readText()).jsonObject
            val items = data.getValue("chapters").jsonArray
            require(items.size in 1..MAX_ENTRIES)
            val index = BookIndex(data.getValue("title").jsonPrimitive.content,
                data.getValue("author").jsonPrimitive.content, items.map {
                    val item = it.jsonObject
                    Chapter(item.getValue("path").jsonPrimitive.content, item.getValue("title").jsonPrimitive.content,
                        item["heading"]?.jsonPrimitive?.contentOrNull)
                })
            indexInMemory(id, index)
            index
        }.getOrNull()
    }

    private fun checkedBook(id: String): File = file(id).also {
        if (!it.isFile) throw ApiError(404, "Không tìm thấy EPUB")
        if (it.length() > MAX_BOOK_BYTES) throw ApiError(400, "EPUB vượt giới hạn 200 MB")
    }

    private fun getIndex(id: String, book: File): BookIndex {
        readIndex(id)?.let { return it }
        return ZipFile(book).use { zip ->
            val archive = Archive(zip)
            buildIndex(archive, archive.packageData(), "Truyện EPUB", savedBook = true).also { saveIndex(id, it) }
        }
    }

    fun parse(raw: ByteArray, filename: String): JsonObject {
        if (raw.size.toLong() !in 1..MAX_BOOK_BYTES) throw ApiError(400, "EPUB rỗng hoặc vượt giới hạn 200 MB")
        val temp = File.createTempFile("epub-import-", ".epub", epubDir())
        try {
            temp.writeBytes(raw)
            return parse(temp, filename)
        } finally {
            temp.delete()
        }
    }

    fun parse(source: File, filename: String, includeFirstContent: Boolean = true): JsonObject {
        val size = source.length()
        if (!source.isFile || size !in 1..MAX_BOOK_BYTES) throw ApiError(400, "EPUB rỗng hoặc vượt giới hạn 200 MB")
        val digest = MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val id = "epub-" + digest.digest().take(12).joinToString("") { "%02x".format(it) }
        val target = file(id)
        val savedIndex = if (target.isFile) runCatching { getIndex(id, target) }.getOrNull() else null
        val (index, firstText) = ZipFile(source).use { zip ->
            val archive = Archive(zip)
            val pkg = archive.packageData()
            val index = savedIndex ?: buildIndex(archive, pkg, filename)
            val firstText = chapterText(archive, index.chapters.first())
            if (firstText.isBlank()) throw ApiError(400, "Chương đầu của EPUB không có văn bản")
            if (savedIndex == null) writeAtomically(target) { source.copyTo(it, overwrite = true) }
            copyCover(archive, pkg, id)
            saveIndex(id, index)
            index to firstText
        }
        return buildJsonObject {
            writeBookInfo(id, index)
            put("epubId", id)
            put("firstTitle", index.chapters.first().title)
            if (includeFirstContent) put("firstContent", firstText)
            put("fileName", filename)
            put("fileSizeBytes", size)
        }
    }

    private fun JsonObjectBuilder.writeBookInfo(id: String, index: BookIndex) {
        put("id", id)
        put("title", index.title)
        put("author", index.author)
        put("coverUrl", cover(id))
        put("color", colors[Math.floorMod(index.title.hashCode(), colors.size)])
        put("totalChapters", index.chapters.size)
        put("chapters", JsonArray(index.chapters.mapIndexed { number, chapter ->
            buildJsonObject {
                put("id", number.toString())
                put("title", chapter.title)
                put("url", "epub://$id/$number")
            }
        }))
    }

    fun info(id: String): JsonObject {
        val index = getIndex(id, checkedBook(id))
        return buildJsonObject { writeBookInfo(id, index) }
    }

    fun delete(id: String): Boolean {
        val book = file(id)
        synchronized(indices) { indices.remove(id) }
        val index = indexFile(id)
        if (index.exists() && !index.delete()) return false
        coverDir().listFiles()?.filter { it.nameWithoutExtension == id }?.forEach {
            if (!it.delete()) return false
        }
        return !book.exists() || book.delete()
    }

    fun chapter(id: String, index: Int): JsonObject {
        val archive = checkedBook(id)
        val book = getIndex(id, archive)
        val chapter = book.chapters.getOrNull(index) ?: throw ApiError(404, "Chương không tồn tại")
        val text = ZipFile(archive).use { chapterText(Archive(it), chapter) }
        if (text.isBlank()) throw ApiError(422, "Chương EPUB không có văn bản")
        return buildJsonObject {
            put("title", chapter.title)
            put("story_title", book.title)
            put("content", text)
            put("next_url", if (index + 1 < book.chapters.size) JsonPrimitive("epub://$id/${index + 1}") else JsonNull)
            put("prev_url", if (index > 0) JsonPrimitive("epub://$id/${index - 1}") else JsonNull)
            put("chapter_url", "epub://$id/$index")
            put("cover_url", cover(id))
            put("chapter_index", index)
            put("total_chapters", book.chapters.size)
        }
    }
}
