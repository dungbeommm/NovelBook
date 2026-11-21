package vn.tramtruyen.reader.engine

import kotlinx.serialization.json.*
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object Store {
    private val lock=Any(); private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}; private const val SCHEMA_VERSION=2
    private fun path(n:String)=File(Config.dataDir,n)
    private fun load(n:String):MutableList<JsonObject> = SafeJsonFiles.readOrRecover(
        path(n),
        defaultValue = { mutableListOf() },
    ) { raw ->
        val e=json.parseToJsonElement(raw)
        require(e is JsonArray) { "$n không phải JSON array" }
        e.mapNotNull{it as? JsonObject}.toMutableList()
    }
    private fun loadMap(n:String):MutableMap<String,JsonObject> = SafeJsonFiles.readOrRecover(
        path(n),
        defaultValue = { mutableMapOf() },
    ) { raw ->
        val e=json.parseToJsonElement(raw)
        require(e is JsonObject) { "$n không phải JSON object" }
        // Giá trị không phải object thì BỎ QUA, không thay bằng object rỗng:
        // object rỗng giả sẽ bị các lần lưu sau ghi đè như tiến độ hợp lệ.
        e.entries.mapNotNull{(k,v)->(v as? JsonObject)?.let{k to it}}.toMap().toMutableMap()
    }
    private fun save(n:String,d:List<JsonObject>) =
        SafeJsonFiles.writeAtomic(path(n), JsonArray(d).toString())
    private fun saveMap(n:String,d:Map<String,JsonObject>) =
        SafeJsonFiles.writeAtomic(path(n), JsonObject(d).toString())
    private fun str(o:JsonObject,k:String)=(o[k] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
    private fun identities(o:JsonObject):Set<String> = buildSet {
        listOf("id","storyUrl","story_url","chapterUrl","chapter_url","folder","localBookId").map{str(o,it)}.filter{it.isNotBlank()}.forEach{add(it.lowercase().trimEnd('/'))}
        (o["aliases"] as? JsonArray)?.mapNotNull{(it as? JsonPrimitive)?.contentOrNull}?.forEach{if(it.isNotBlank() && !it.startsWith("title:"))add(it.lowercase().trimEnd('/'))}
        vn.tramtruyen.reader.model.StoryIds.titleIdentity(str(o,"title"), str(o,"storyUrl"), str(o,"chapterUrl"), str(o,"source"))?.let { add(it) }
    }
    private fun same(a:JsonObject,b:JsonObject)=identities(a).intersect(identities(b)).isNotEmpty()
    private fun id(o:JsonObject)=listOf("id","localBookId","storyUrl","chapterUrl","folder","title").firstNotNullOfOrNull{str(o,it).takeIf(String::isNotBlank)}?:""
    private fun norm(o:JsonObject):JsonObject{val m=o.toMutableMap();m["id"]=JsonPrimitive(id(o));m["schemaVersion"]=JsonPrimitive(SCHEMA_VERSION);m["aliases"]=JsonArray(identities(o).filterNot{it.startsWith("title:")}.map{JsonPrimitive(it)});Covers.ensureCover(m);return JsonObject(m)}
    fun migrateSchema()=synchronized(lock){val mark=path("store_schema_v$SCHEMA_VERSION");if(mark.exists())return@synchronized;listOf("history.json","library.json","progress.json","chapter_history.json").forEach{n->val f=path(n);if(f.exists())runCatching{f.copyTo(path("$n.v1.bak"),false)}};listOf("history.json","library.json").forEach{n->val out=mutableListOf<JsonObject>();load(n).map(::norm).sortedByDescending{str(it,"updatedAt").toLongOrNull()?:0}.forEach{o->val i=out.indexOfFirst{same(it,o)};if(i<0)out.add(o)else{val old=out[i];out[i]=JsonObject(old.toMutableMap().apply{o.forEach{(k,v)->if(v !is JsonNull&&v.toString()!="\"\"")put(k,v)};old["saved_at"]?.let{put("saved_at",it)}})}};save(n,out)};mark.writeText("$SCHEMA_VERSION")}
    fun getHistory():List<JsonObject> = synchronized(lock){load("history.json")}
    fun upsertHistory(item: JsonObject): List<JsonObject> = synchronized(lock) {
        val n = norm(item)
        val all = load("history.json")
        val aliases = (all.filter { same(it, n) }.flatMap(::identities) + identities(n))
            .filterNot { it.startsWith("title:") }.distinct()
        val latest = JsonObject(n.toMutableMap().apply {
            put("aliases", JsonArray(aliases.map(::JsonPrimitive)))
            put("updated_at", JsonPrimitive(System.currentTimeMillis() / 1000))
            put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
        })
        val next = (listOf(latest) + all.filterNot { same(it, n) }).take(200)
        save("history.json", next)
        next
    }

    fun removeHistory(sid:String):List<JsonObject> = synchronized(lock){val p=buildJsonObject{put("id",sid)};val l=load("history.json").filterNot{same(it,p)};save("history.json",l);l}
    fun clearHistory():List<JsonObject> = synchronized(lock){save("history.json",emptyList());emptyList()}
    fun getLibrary():List<JsonObject> = synchronized(lock){load("library.json")}
    private var generation = 0L
    fun writeGeneration(): Long = synchronized(lock) { generation }
    fun withWriteGeneration(expected: Long, block: () -> Unit) = synchronized(lock) {
        if (generation == expected) block()
    }

    fun upsertLibrary(item: JsonObject): List<JsonObject> = synchronized(lock) {
        val n = norm(item)
        val all = load("library.json")
        val matches = all.filter { same(it, n) }
        val old = matches.firstOrNull()
        val merged = norm(JsonObject(buildMap {
            old?.forEach { put(it.key, it.value) }
            if (old != null && str(n, "chapterUrl").isNotBlank() && str(old, "chapterUrl") != str(n, "chapterUrl")) {
                listOf("nextUrl", "prevUrl", "chapterTitle").forEach { key ->
                    if (key !in n) put(key, JsonPrimitive(""))
                }
                listOf("sentenceIndex", "sentenceCount", "progress", "positionMs").forEach { key ->
                    if (key !in n) put(key, JsonPrimitive(0))
                }
            }
            n.forEach { put(it.key, it.value) } // newest chapter/link wins, even at a lower index
            put("aliases", JsonArray((matches.flatMap { identities(it) } + identities(n))
                .filterNot { it.startsWith("title:") }.distinct().map(::JsonPrimitive)))
            put("saved_at", old?.get("saved_at") ?: JsonPrimitive(System.currentTimeMillis() / 1000))
            put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
        }))
        // Retire old chapter URLs/progress when a same-name story moves to a new link.
        val retired = matches.map(::id).filter { it != id(n) }
            .map { it.lowercase().trimEnd('/') }.toSet()
        if (retired.isNotEmpty()) {
            generation++ // suppress queued writes containing superseded links
            for (file in listOf("progress.json", "chapter_history.json")) {
                val map = loadMap(file)
                // Chuẩn hóa khóa như deleteStory (lowercase, bỏ '/' cuối) để
                // bản ghi khóa bằng dạng identity khác cũng bị dọn.
                map.keys.filter { it.lowercase().trimEnd('/') in retired }.forEach(map::remove)
                saveMap(file, map)
            }
        }
        val next = listOf(merged) + all.filterNot { same(it, n) }
        save("library.json", next)
        next
    }

    /** All copies, including recovery backups, are scrubbed under the same write lock. */
    fun deleteStory(identity: JsonObject): Set<String> = synchronized(lock) {
        generation++ // queued progress/history writes from before deletion cannot resurrect it
        val library = load("library.json")
        val history = load("history.json")
        val targets = mutableListOf(identity)
        // Resolve aliases transitively (old URL/id after a replacement is still this story).
        var changed: Boolean
        do {
            changed = false
            (library + history).forEach { row ->
                if (row !in targets && targets.any { same(it, row) }) {
                    targets.add(row); changed = true
                }
            }
        } while (changed)
        val ids = targets.flatMap { identities(it) + id(it) }.toSet()
        save("library.json", library.filterNot { row -> targets.any { same(row, it) } })
        save("history.json", history.filterNot { row -> targets.any { same(row, it) } })
        for (name in listOf("progress.json", "chapter_history.json")) {
            val map = loadMap(name)
            map.keys.filter { it.lowercase().trimEnd('/') in ids }.forEach(map::remove)
            saveMap(name, map)
        }
        for (name in listOf("library.json", "history.json", "progress.json", "chapter_history.json")) {
            path(name).parentFile?.listFiles()?.filter {
                it.name.startsWith("$name.") &&
                    (it.name.contains(".bak") || it.name.contains(".corrupt-") || it.name.endsWith(".tmp"))
            }?.forEach { check(it.delete()) { "Không thể xoá bản dữ liệu cũ: ${it.name}" } }
        }
        ids
    }

    fun updateLibraryProgress(identity: JsonObject, patch: JsonObject): List<JsonObject> = synchronized(lock) {
        val rows = load("library.json").toMutableList()
        val index = rows.indexOfFirst { same(it, identity) }
        if (index < 0) return@synchronized rows
        val old = rows[index]
        val merged = norm(JsonObject(old.toMutableMap().apply {
            identity.forEach { (key, value) ->
                if (key in setOf("id", "storyUrl", "localBookId")) put(key, value)
            }
            patch.forEach { (key, value) -> put(key, value) }
            put("aliases", JsonArray((identities(old) + identities(identity))
                .filterNot { it.startsWith("title:") }.map(::JsonPrimitive)))
            put("updatedAt", JsonPrimitive(System.currentTimeMillis()))
            put("lastListenedAt", JsonPrimitive(System.currentTimeMillis()))
        }))
        rows.removeAt(index); rows.add(0, merged); save("library.json", rows); rows
    }

    fun updateLibraryProgress(sid:String,p:JsonObject)=updateLibraryProgress(buildJsonObject{put("id",sid)},p)
    fun removeLibrary(sid:String):List<JsonObject> = synchronized(lock){val p=buildJsonObject{put("id",sid)};val l=load("library.json").filterNot{same(it,p)};save("library.json",l);l}
    fun isInLibrary(identity:JsonObject):Boolean=synchronized(lock){load("library.json").any{same(it,identity)}}
    fun isInLibrary(sid:String)=isInLibrary(buildJsonObject{put("id",sid)})
    fun getProgress(sid:String):JsonObject?=synchronized(lock){loadMap("progress.json")[sid]};fun getAllProgress():Map<String,JsonObject> = synchronized(lock){loadMap("progress.json")}
    fun upsertProgress(sid:String,p:JsonObject):JsonObject=synchronized(lock){val m=loadMap("progress.json");val v=JsonObject(p.toMutableMap().apply{put("storyId",JsonPrimitive(sid));put("updatedAt",JsonPrimitive(System.currentTimeMillis()))});m[sid]=v;saveMap("progress.json",m);v}
    fun getChapterHistory(sid:String):List<JsonObject> = synchronized(lock){val a=loadMap("chapter_history.json")[sid]?.get("items")as?JsonArray?:return@synchronized emptyList();a.mapNotNull{it as?JsonObject}}
    fun exportBackup():String = synchronized(lock){buildJsonObject{
        put("format",3);put("createdAt",System.currentTimeMillis())
        put("history",JsonArray(load("history.json")));put("library",JsonArray(load("library.json")))
        put("progress",JsonObject(loadMap("progress.json")));put("chapterHistory",JsonObject(loadMap("chapter_history.json")))
    }.toString()}
    fun importBackup(raw:String)=synchronized(lock){
        val o=json.parseToJsonElement(raw).jsonObject
        // Parse/validate TẤT CẢ các phần TRƯỚC khi lưu bất cứ thứ gì, để bản
        // backup lỗi không áp dụng nửa vời (trước đây history/library đã lưu
        // rồi mới văng lỗi khi progress chứa giá trị không phải object).
        val newHistory=(o["history"] as? JsonArray)?.mapNotNull{it as? JsonObject}?.map(::norm)
        val newLibraryRaw=(o["library"] as? JsonArray)?.mapNotNull{it as? JsonObject}?.map(::norm)
        val newProgress=(o["progress"] as? JsonObject)?.mapValues{it.value.jsonObject}
        val newChapterHistory=(o["chapterHistory"] as? JsonObject)?.mapValues{it.value.jsonObject}
        // Library nhập vào cũng khử trùng theo same() như mọi đường upsert.
        val newLibrary=newLibraryRaw?.let{rows->
            val out=mutableListOf<JsonObject>()
            for(row in rows){val i=out.indexOfFirst{same(it,row)};if(i<0)out.add(row)else out[i]=row}
            out
        }
        newHistory?.let{save("history.json",it)}
        newLibrary?.let{save("library.json",it)}
        newProgress?.let{saveMap("progress.json",it)}
        newChapterHistory?.let{saveMap("chapter_history.json",it)}
    }
    fun upsertChapterHistory(sid: String, chapter: JsonObject): List<JsonObject> = synchronized(lock) {
        val records = loadMap("chapter_history.json")
        val old = (records[sid]?.get("items") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
        val chapterId = str(chapter, "chapterId").ifBlank { str(chapter, "chapterUrl") }
        val next = listOf(JsonObject(chapter.toMutableMap().apply {
            put("lastListenedAt", JsonPrimitive(System.currentTimeMillis()))
        })) + old.filterNot {
            // Chỉ khử trùng theo chapterId: khớp chapterIndex không đủ, vì
            // chương khác nhau có thể dùng chung chỉ số (tách lại EPUB,
            // chỉ số theo trang khởi động lại...).
            str(it, "chapterId").ifBlank { str(it, "chapterUrl") } == chapterId
        }
        val limited = next.take(5000)
        records[sid] = buildJsonObject {
            put("items", JsonArray(limited)); put("updatedAt", System.currentTimeMillis())
        }
        saveMap("chapter_history.json", records); limited
    }

}

/**
 * Atomic JSON persistence with one known-good backup.
 *
 * A malformed primary is never interpreted as an empty library. We first restore
 * the last valid backup; if that also fails, the damaged files are quarantined
 * with a timestamp so a later save cannot silently destroy the user's data.
 */
internal object SafeJsonFiles {
    fun <T> readOrRecover(file: File, defaultValue: () -> T, parse: (String) -> T): T {
        if (!file.exists()) return defaultValue()
        runCatching { parse(file.readText()) }.getOrNull()?.let { return it }

        val backup = File(file.absolutePath + ".bak")
        if (backup.isFile) {
            runCatching { parse(backup.readText()) }.getOrNull()?.let { recovered ->
                quarantine(file)
                writePrimary(file, backup.readText())
                return recovered
            }
        }

        quarantine(file)
        if (backup.exists()) quarantine(backup)
        return defaultValue()
    }

    fun writeAtomic(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.absolutePath + ".tmp")
        val backup = File(file.absolutePath + ".bak")
        temp.writeText(text)

        // Only rotate a parseable primary. readOrRecover() already quarantines a
        // damaged one, so a good backup is never replaced by corrupt bytes.
        if (file.isFile) {
            val backupTemp = File(backup.absolutePath + ".tmp")
            file.copyTo(backupTemp, overwrite = true)
            moveReplacing(backupTemp, backup)
        }
        moveReplacing(temp, file)
    }

    private fun writePrimary(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.absolutePath + ".restore.tmp")
        temp.writeText(text)
        moveReplacing(temp, file)
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.getOrElse {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun quarantine(file: File) {
        if (!file.exists()) return
        val target = File(file.absolutePath + ".corrupt-" + System.currentTimeMillis())
        if (!file.renameTo(target)) {
            file.copyTo(target, overwrite = false)
            file.delete()
        }
    }
}
