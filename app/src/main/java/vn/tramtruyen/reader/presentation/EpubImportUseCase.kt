package vn.tramtruyen.reader.presentation

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import vn.tramtruyen.reader.model.ChapterUiModel
import vn.tramtruyen.reader.model.EpubImportResult
import vn.tramtruyen.reader.model.Story
import vn.tramtruyen.reader.model.StorySourceType
import vn.tramtruyen.reader.engine.ReadingEngine
import vn.tramtruyen.reader.engine.ebooks.EpubParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File

/** Owns EPUB I/O and mapping so PlayerViewModel only coordinates UI state. */
internal class EpubImportUseCase(private val context: Context) {
    suspend fun import(uri: Uri): EpubImportResult {
        val resolver = context.contentResolver
        var name = "sach.epub"
        var size = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                    if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                }
            }
        require(size <= EpubParser.MAX_BOOK_BYTES) { "EPUB vượt giới hạn 200 MB" }

        val temp = File.createTempFile("epub_import_", ".epub", context.cacheDir)
        try {
            resolver.openInputStream(uri)?.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count <= 0) break
                        total += count
                        require(total <= EpubParser.MAX_BOOK_BYTES) { "EPUB vượt giới hạn 200 MB" }
                        output.write(buffer, 0, count)
                    }
                    size = total
                }
            } ?: error("Không mở được EPUB")

            val parsed = JSONObject(ReadingEngine.epubParseFile(temp, name))
            val id = parsed.optString("epubId").ifBlank { parsed.getString("id") }
            val jsonChapters = parsed.optJSONArray("chapters") ?: org.json.JSONArray()
            val chapters = (0 until jsonChapters.length()).map { index ->
                val chapter = jsonChapters.getJSONObject(index)
                val number = index + 1
                ChapterUiModel(
                    number,
                    chapter.optString("title").ifBlank { "Chương $number" },
                    "epub://$id/$index",
                    "epub:$id:$index",
                )
            }
            val story = Story(
                id = id,
                title = parsed.optString("title", "Sách EPUB"),
                author = parsed.optString("author", "Không rõ"),
                coverUrl = parsed.optString("coverUrl"),
                totalChapters = parsed.optInt("totalChapters", jsonChapters.length()),
                tags = listOf("EPUB"),
                color = parsed.optString("color", "#8b5cf6"),
                currentChapter = 1,
                source = "EPUB ngoại tuyến",
                sourceType = StorySourceType.EPUB,
                localBookId = id,
                localFileName = name,
                fileSizeBytes = parsed.optLong("fileSizeBytes", size),
                chapterUrl = "epub://$id/0",
                nextUrl = if (jsonChapters.length() > 1) "epub://$id/1" else null,
                chapterTitle = parsed.optString("firstTitle"),
            )
            return EpubImportResult(story, chapters)
        } finally {
            temp.delete()
        }
    }

}