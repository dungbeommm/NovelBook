package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Ghi de trang thai nguon o runtime (khong can build lai app).
 *
 * - dead: bo khoi moi lan tim (tru khi user chon uu tien domain do)
 * - force: luon cho vao hang doi (bo qua mute ngan han)
 * - prefer: xep cung nhom uu tien user
 *
 * File: [Config.dataDir]/site_overrides.json
 */
object SiteOverrides {

	enum class Kind { DEAD, FORCE, PREFER }

	private val lock = Any()
	private val json = Json { ignoreUnknownKeys = true }
	private var map: MutableMap<String, Kind>? = null

	private fun file(): File? = try {
		File(Config.dataDir, "site_overrides.json")
	} catch (_: Exception) {
		null
	}

	private fun key(domain: String): String =
		domain.lowercase().removePrefix("www.")

	/** Legacy domains removed from the bundled active-source catalog. */
	private val KNOWN_DEAD = listOf(
		"metruyencv.com", "dtruyen.com", "bachngocsach.com.vn", "bachngocsach.com",
		"truyenlaay.com", "nguyenhoangvu.com",
	)

	private fun ensureLoaded(): MutableMap<String, Kind> {
		map?.let { return it }
		val loaded = mutableMapOf<String, Kind>()
		var fromDisk = false
		try {
			val f = file()
			if (f != null && f.exists()) {
				val root = json.parseToJsonElement(f.readText()).jsonObject
				for ((dom, el) in root) {
					val raw = (el as? JsonPrimitive)?.contentOrNull?.lowercase().orEmpty()
					val kind = when (raw) {
						"dead" -> Kind.DEAD
						"force" -> Kind.FORCE
						"prefer" -> Kind.PREFER
						else -> null
					} ?: continue
					loaded[key(dom)] = kind
				}
			}
			fromDisk = loaded.isNotEmpty()
		} catch (_: Exception) {
			// File hong -> coi nhu khong co override.
		}
		if (!fromDisk) {
			for (d in KNOWN_DEAD) loaded[key(d)] = Kind.DEAD
			map = loaded
			saveLocked()
			return loaded
		}
		map = loaded
		return loaded
	}

	fun get(domain: String): Kind? = synchronized(lock) {
		ensureLoaded()[key(domain)]
	}

	private val REQUIRED_SEARCH_SOURCES = setOf(
		"truyennet.org", "truyenmoikk.com"
	)

	fun isDead(domain: String): Boolean =
		key(domain) !in REQUIRED_SEARCH_SOURCES && get(domain) == Kind.DEAD

	fun isForced(domain: String): Boolean = get(domain) == Kind.FORCE

	fun isPreferred(domain: String): Boolean = get(domain) == Kind.PREFER

	fun set(domain: String, kind: Kind?) = synchronized(lock) {
		val m = ensureLoaded()
		val k = key(domain)
		if (kind == null) m.remove(k) else m[k] = kind
		saveLocked()
	}

	fun markDead(domain: String) = set(domain, Kind.DEAD)

	fun clear() = synchronized(lock) {
		map = mutableMapOf()
		saveLocked()
	}

	private fun saveLocked() {
		val m = map ?: return
		val f = file() ?: return
		try {
			val root = buildJsonObject {
				for ((dom, kind) in m) {
					put(dom, when (kind) {
						Kind.DEAD -> "dead"
						Kind.FORCE -> "force"
						Kind.PREFER -> "prefer"
					})
				}
			}
			f.parentFile?.mkdirs()
			val tmp = File(f.absolutePath + ".tmp")
			tmp.writeText(root.toString())
			if (!tmp.renameTo(f)) {
				tmp.copyTo(f, overwrite = true)
				tmp.delete()
			}
		} catch (_: Exception) {
		}
	}
}
