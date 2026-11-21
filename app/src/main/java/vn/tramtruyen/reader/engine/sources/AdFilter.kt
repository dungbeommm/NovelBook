package vn.tramtruyen.reader.engine.sources

/**
 * LOC QUANG CAO TRONG KET QUA TIM KIEM.
 *
 * Rat nhieu trang truyen lau chen banner nha cai / co bac / phim nguoi lon vao
 * chinh danh sach ket qua, dung y het cac dong truyen that. Bo loc nay bo chung
 * di TRUOC khi cham diem, dua tren ba dau hieu:
 *
 *  1) TEN co tu khoa quang cao (da bo dau de bat ca "nhà cái" lan "nha cai").
 *  2) DIA CHI co doan dac trung cua link quang cao / chuyen huong.
 *  3) TEN co hinh dang bat thuong: qua dai, day ky tu trang tri, co so dien thoai,
 *     hoac nhet ca duong link vao trong ten.
 *
 * Nguyen tac: chi bo khi CHAC CHAN la rac. Tu khoa nhap nhang ("game", "tien")
 * KHONG duoc dua vao danh sach, vi ten truyen that co the chua chung.
 */
object AdFilter {

	/** Tu khoa xuat hien trong TEN ket qua -> gan nhu chac chan la quang cao. */
	private val BAD_TITLE_WORDS = listOf(
		// co bac / nha cai
		"nha cai", "ca cuoc", "casino", "tai xiu", "no hu", "game bai", "danh bai",
		"soi cau", "lo de", "xo so", "keo nha cai", "ty le keo", "ca do", "bong da so",
		// ten nha cai hay gap
		"jun88", "789bet", "new88", "hi88", "f8bet", "sunwin", "go88", "iwin",
		"bong88", "188bet", "fb88", "vn88", "w88", "bk8", "kubet", "may88",
		"typhu88", "33win", "vz99", "sv388", "debet", "shbet", "qh88",
		// khieu dam
		"phim sex", "sex viet", "phim nguoi lon", "jav hd", "xnxx", "sexy girl",
		// chao moi / tiep thi
		"quang cao", "khuyen mai", "nap tien", "rut tien", "dang ky nhan",
		"tang 100k", "tien thuong", "hoan tra", "link vao", "trang chu chinh thuc",
		"uy tin nhat", "top 10 nha cai", "vay tien", "vay nhanh", "the cao",
		"dich vu seo", "mua ban", "gia re nhat", "lien he ngay",
	)

	/** Doan dac trung trong DIA CHI -> link quang cao, chuyen huong, hoac trang rac. */
	private val BAD_URL_PARTS = listOf(
		"/ads/", "/ad/", "/adv/", "/quang-cao", "/banner", "adserv", "adclick",
		"doubleclick", "googlesyndication", "googleadservices", "popads", "popcash",
		"/redirect", "/go.php", "/out.php", "utm_source=", "affiliate", "?aff=",
		"&aff=", "/promo", "/khuyen-mai", "/nha-cai", "/casino", "/dang-ky",
		"bit.ly/", "shorturl", "/tag/",
	)

	/**
	 * Ky tu trang tri kieu banner: ngoi sao, mui ten, bieu tuong lat vat.
	 * Chi dung dai ky tu co ban de tranh rui ro voi cap surrogate cua emoji;
	 * emoji that su duoc dem rieng ben duoi.
	 */
	private val DECOR_RE = Regex("[\u2190-\u2BFF]")

	/** So dien thoai / hotline nhet trong ten. */
	private val PHONE_RE = Regex("(0|\\+84)\\d{8,10}")

	/** Ten dai qua muc nay thi khong con la ten truyen nua. */
	private const val MAX_TITLE_LEN = 120

	/**
	 * Ket qua nay co phai quang cao khong?
	 *
	 * @param title Ten hien thi cua ket qua.
	 * @param url   Dia chi ket qua tro toi.
	 */
	fun isAd(title: String, url: String): Boolean {
		val t = title.trim()
		if (t.isBlank()) return true
		if (t.length > MAX_TITLE_LEN) return true

		// Ten truyen that khong bao gio chua ca mot duong link.
		val lowerTitle = t.lowercase()
		if (lowerTitle.contains("http://") || lowerTitle.contains("https://") ||
			lowerTitle.contains("www.")
		) return true
		if (PHONE_RE.containsMatchIn(t)) return true
		if (DECOR_RE.findAll(t).count() >= 2) return true
		// Emoji nam ngoai dai co ban -> dem qua so ky tu surrogate.
		if (t.count { Character.isHighSurrogate(it) } >= 2) return true
		if (t.count { it == '!' } >= 2) return true

		// Bo dau roi moi so, de bat ca "nhà cái" lan "nha cai" lan "NHA CAI".
		val norm = TitleMatcher.normalize(t)
		if (BAD_TITLE_WORDS.any { norm.contains(it) }) return true

		val lowerUrl = url.lowercase()
		if (BAD_URL_PARTS.any { lowerUrl.contains(it) }) return true

		return false
	}
}
