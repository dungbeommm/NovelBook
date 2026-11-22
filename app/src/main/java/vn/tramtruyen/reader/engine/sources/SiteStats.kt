package vn.tramtruyen.reader.engine.sources

import vn.tramtruyen.reader.engine.Config
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * SO THEO DOI SUC KHOE TUNG NGUON.
 *
 * Danh sach co hon 70 nguon nhung chung KHONG BANG NHAU: co nguon tra ve trong
 * 300ms, co nguon 6 giay, co nguon chet han da nhieu thang. Truoc day lan tim nao
 * cung doi xu voi chung nhu nhau nen luon phai tra gia cho nhung nguon te nhat.
 *
 * Lop nay ghi lai hai thu cho moi ten mien:
 *  1) THOI GIAN PHAN HOI trung binh (trung binh truot, uu tien lan gan day).
 *  2) SO LAN HONG lien tiep -> hong qua nhieu thi CHO NGHI mot lat.
 *
 * Nho do lan tim sau tu biet hoi nguon nhanh truoc va bo qua nguon dang chet.
 * Du lieu ghi xuong [Config.dataDir] nen mo lai app van con.
 */
object SiteStats {

	/** Hong may lan lien tiep thi cho nguon do nghi. */
	private const val MUTE_AFTER_FAILS = 3

	/** Nghi bao lau truoc khi duoc thu lai. */
	private const val MUTE_MS = 30 * 60 * 1000L

	/**
	 * Diem mac dinh cho nguon CHUA CO SO LIEU.
	 *
	 * Dat o giua: nguon la van duoc thu truoc nhung nguon cham da biet, nhung
	 * khong chen len truoc nhung nguon da chung minh la nhanh.
	 */
	private const val UNKNOWN_RANK = 2_500L

	/** Nguon hong bi cong them tung nay ms cho moi phan tram that bai. */
	private const val FAIL_PENALTY_MS = 4_000L

	/** Trong so cua lan do moi nhat trong trung binh truot (0..1). */
	private const val SMOOTHING = 0.3

	/** Khong ghi xuong dia day hon muc nay, tranh cham o giua lan tim. */
	private const val SAVE_GAP_MS = 5_000L

	private data class Stat(
		var avgMs: Long = 0,
		var ok: Int = 0,
		var fail: Int = 0,
		var consecFail: Int = 0,
		var mutedUntil: Long = 0,
	)

	private val lock = Any()
	private val json = Json { ignoreUnknownKeys = true }
	private var stats: MutableMap<String, Stat>? = null
	private var dirty = false
	private var lastSaveAt = 0L

	private fun file(): File? = try {
		File(Config.dataDir, "site_stats.json")
	} catch (_: Exception) {
		// Config chua kip khoi tao -> chay khong co so lieu cung duoc.
		null
	}

	private fun key(domain: String): String = domain.lowercase().removePrefix("www.")

	private fun ensureLoaded(): MutableMap<String, Stat> {
		stats?.let { return it }
		val loaded = mutableMapOf<String, Stat>()
		try {
			val f = file()
			if (f != null && f.exists()) {
				val root = json.parseToJsonElement(f.readText()).jsonObject
				for ((dom, el) in root) {
					val o = el as? JsonObject ?: continue
					loaded[dom] = Stat(
						avgMs = num(o, "avgMs"),
						ok = num(o, "ok").toInt(),
						fail = num(o, "fail").toInt(),
						consecFail = num(o, "consecFail").toInt(),
						mutedUntil = num(o, "mutedUntil"),
					)
				}
			}
		} catch (_: Exception) {
			// File hong thi coi nhu chua co so lieu, khong lam sap lan tim.
		}
		stats = loaded
		return loaded
	}

	private fun num(o: JsonObject, k: String): Long =
		(o[k] as? JsonPrimitive)?.longOrNull
			?: (o[k] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
			?: 0L

	/** Nguon tra ve ket qua: ghi lai thoi gian va xoa chuoi that bai. */
	fun recordSuccess(domain: String, elapsedMs: Long) = synchronized(lock) {
		val m = ensureLoaded()
		val s = m.getOrPut(key(domain)) { Stat() }
		s.avgMs = if (s.ok == 0) elapsedMs
		else (s.avgMs * (1 - SMOOTHING) + elapsedMs * SMOOTHING).toLong()
		s.ok += 1
		s.consecFail = 0
		s.mutedUntil = 0
		dirty = true
	}

	/** Nguon loi hoac tra ve rong: dem that bai, du nguong thi cho nghi. */
	fun recordFailure(domain: String) = synchronized(lock) {
		val m = ensureLoaded()
		val s = m.getOrPut(key(domain)) { Stat() }
		s.fail += 1
		s.consecFail += 1
		if (s.consecFail >= MUTE_AFTER_FAILS) {
			s.mutedUntil = System.currentTimeMillis() + MUTE_MS
		}
		dirty = true
	}

	/** Nguon nay dang bi cho nghi vi hong lien tuc? */
	fun isMuted(domain: String): Boolean = synchronized(lock) {
		val s = ensureLoaded()[key(domain)] ?: return false
		return s.mutedUntil > System.currentTimeMillis()
	}

	/**
	 * Soft-ban dai: fail rate cao + da thu du lan -> coi la nguon kem, bo khoi
	 * hang doi T1/T2 (tru khi user uu tien hoac SiteOverrides.force).
	 */
	fun isChronicallyBad(domain: String, minSamples: Int = 8, failRate: Double = 0.80): Boolean =
		synchronized(lock) {
			val s = ensureLoaded()[key(domain)] ?: return false
			val total = s.ok + s.fail
			if (total < minSamples) return false
			return s.fail.toDouble() / total >= failRate
		}

	/** Nguon con nen duoc hoi khong (mute ngan + soft-ban dai). */
	fun isHealthy(domain: String): Boolean =
		!isMuted(domain) && !isChronicallyBad(domain)

	/**
	 * DIEM XEP HANG - cang nho cang duoc hoi truoc.
	 *
	 * Bang thoi gian phan hoi trung binh cong them tien phat theo ti le hong, nen
	 * mot nguon nhanh nhung chap chon van thua nguon hoi cham ma on dinh.
	 */
	fun rankKey(domain: String): Long = synchronized(lock) {
		val s = ensureLoaded()[key(domain)] ?: return UNKNOWN_RANK
		val total = s.ok + s.fail
		if (total == 0) return UNKNOWN_RANK
		val failRate = s.fail.toDouble() / total
		val base = if (s.ok == 0) UNKNOWN_RANK else s.avgMs
		return base + (failRate * FAIL_PENALTY_MS).toLong()
	}

	/**
	 * Ghi so lieu xuong dia.
	 *
	 * Goi sau moi lan tim. Tu gian cach it nhat [SAVE_GAP_MS] nen goi day cung khong
	 * lam cham gi; dat [force] = true de ghi bang duoc.
	 */
	fun flush(force: Boolean = false) = synchronized(lock) {
		val m = stats ?: return
		if (!dirty) return
		val now = System.currentTimeMillis()
		if (!force && now - lastSaveAt < SAVE_GAP_MS) return
		val f = file() ?: return
		try {
			val root = buildJsonObject {
				for ((dom, s) in m) {
					put(dom, buildJsonObject {
						put("avgMs", s.avgMs)
						put("ok", s.ok)
						put("fail", s.fail)
						put("consecFail", s.consecFail)
						put("mutedUntil", s.mutedUntil)
					})
				}
			}
			f.parentFile?.mkdirs()
			val tmp = File(f.absolutePath + ".tmp")
			tmp.writeText(root.toString())
			if (!tmp.renameTo(f)) {
				tmp.copyTo(f, true)
				tmp.delete()
			}
			lastSaveAt = now
			dirty = false
		} catch (_: Exception) {
			// Khong ghi duoc thi thoi, so lieu trong bo nho van dung duoc.
		}
	}

	/** Xoa sach so lieu (dung khi nguoi dung muon danh gia lai tu dau). */
	fun clear() = synchronized(lock) {
		stats = mutableMapOf()
		dirty = true
		flush(force = true)
	}
}
