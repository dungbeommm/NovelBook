package vn.tramtruyen.reader.presentation

import android.content.Context
import vn.tramtruyen.reader.model.PronunciationRule
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class SmartFeaturesStore(context: Context) {
    private val prefs = context.getSharedPreferences("smart_features_v1", Context.MODE_PRIVATE)

    fun rules(): List<PronunciationRule> = runCatching {
        val a = JSONArray(prefs.getString("pronunciation", "[]"))
        (0 until a.length()).map { i -> a.getJSONObject(i) }.map {
            PronunciationRule(it.getString("id"), it.getString("from"), it.getString("to"),
                it.optString("storyId").takeIf(String::isNotBlank))
        }
    }.getOrDefault(emptyList())

    fun addRule(from: String, to: String, storyId: String?): List<PronunciationRule> {
        val cleanFrom = from.trim(); val cleanTo = to.trim()
        if (cleanFrom.isBlank() || cleanTo.isBlank()) return rules()
        val next = rules().filterNot { it.from.equals(cleanFrom, true) && it.storyId == storyId } +
            PronunciationRule(UUID.randomUUID().toString(), cleanFrom, cleanTo, storyId)
        saveRules(next); return next
    }
    fun deleteStories(ids: Set<String>): List<PronunciationRule> {
        val keep = rules().filterNot { it.storyId in ids }
        val profiles = runCatching { JSONObject(prefs.getString("profiles", "{}")) }.getOrDefault(JSONObject())
        ids.forEach(profiles::remove)
        val array = JSONArray()
        keep.forEach { rule -> array.put(JSONObject().put("id", rule.id).put("from", rule.from)
            .put("to", rule.to).put("storyId", rule.storyId ?: "")) }
        check(prefs.edit().putString("profiles", profiles.toString())
            .putString("pronunciation", array.toString()).commit()) { "Không lưu được dữ liệu sau khi xoá" }
        return keep
    }
    fun removeRule(id: String): List<PronunciationRule> = rules().filterNot { it.id == id }.also(::saveRules)
    private fun saveRules(items: List<PronunciationRule>) {
        val a=JSONArray(); items.forEach { r -> a.put(JSONObject().put("id",r.id).put("from",r.from).put("to",r.to).put("storyId",r.storyId ?: "")) }
        prefs.edit().putString("pronunciation",a.toString()).apply()
    }
    fun applyPronunciation(text: String, storyId: String?): String {
        var out=text
        rules().filter { it.storyId == null || it.storyId == storyId }
            .sortedByDescending { it.from.length }
            .forEach { out=out.replace(Regex(Regex.escape(it.from),RegexOption.IGNORE_CASE),it.to) }
        return out
    }
    fun saveProfile(storyId:String, voice:String, speed:Float) {
        if(storyId.isBlank()) return
        val o=runCatching{JSONObject(prefs.getString("profiles","{}"))}.getOrDefault(JSONObject())
        o.put(storyId,JSONObject().put("voice",voice).put("speed",speed.toDouble()))
        prefs.edit().putString("profiles",o.toString()).apply()
    }
    fun profile(storyId:String): Pair<String,Float>? = runCatching {
        val x=JSONObject(prefs.getString("profiles","{}")).optJSONObject(storyId) ?: return null
        x.optString("voice") to x.optDouble("speed",1.0).toFloat()
    }.getOrNull()
    fun exportJson(): JSONObject = JSONObject().put("pronunciation",JSONArray(prefs.getString("pronunciation","[]")))
        .put("profiles",JSONObject(prefs.getString("profiles","{}")))
    fun importJson(o:JSONObject) { prefs.edit().putString("pronunciation",o.optJSONArray("pronunciation")?.toString() ?: "[]")
        .putString("profiles",o.optJSONObject("profiles")?.toString() ?: "{}").apply() }
}
