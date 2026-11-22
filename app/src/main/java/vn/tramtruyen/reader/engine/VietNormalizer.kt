package vn.tramtruyen.reader.engine

import android.content.Context
import java.text.Normalizer as JavaNormalizer
import java.util.Locale

/**
 * Bộ chuẩn hóa văn bản tiếng Việt trước khi đưa vào TTS.
 *
 * Nguyên tắc chính:
 *  - xử lý các mẫu có ngữ nghĩa rõ (ngày, giờ, tiền, đơn vị...) trước số thường;
 *  - không làm mất số 0 ở số điện thoại/mã định danh;
 *  - đọc đúng số thập phân và khoảng số;
 *  - chỉ phiên âm từ nước ngoài có trong từ điển, không đoán tên riêng;
 *  - đầu ra chỉ còn văn bản dễ đọc và dấu câu an toàn cho các máy TTS.
 */
object VietNormalizer {

    @Volatile private var loaded = false
    private val acronyms = LinkedHashMap<String, String>()
    private val foreignWords = LinkedHashMap<String, String>()
    @Volatile private var acronymPatterns: List<Pair<Regex, String>> = emptyList()

    private val digits = arrayOf(
        "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín"
    )

    // Từ tiếng Việt thường gặp mà dạng viết hoa chỉ gồm chữ A-Z/Đ: không
    // được đánh vần từng chữ như từ viết tắt (CON, ANH, MAI, TIN, ĐI...).
    // Ưu tiên bao phủ từ thường gặp hơn là giữ khả năng đánh vần viết tắt lạ.
    private val initialismProtectedWords = setOf(
        "AI", "AN", "AO", "AM", "AU", "AY", "BA", "BI", "BO", "CA", "CO", "CU",
        "DA", "DE", "DI", "DO", "DU", "EM", "EN", "EP", "GA", "GO", "HA", "HE",
        "HO", "HU", "IA", "IM", "IN", "IT", "KE", "LA", "LE", "LO", "LU", "MA",
        "ME", "MO", "MU", "NA", "NE", "NO", "NU", "OE", "OI", "OM", "ON", "OT",
        "RA", "SA", "SE", "SI", "SO", "SU", "TA", "TE", "TI", "TO", "TU", "UI",
        "UM", "UT", "VA", "VE", "VO", "VU", "XA", "XE", "XU", "ĐA", "ĐI", "ĐO",
        "ĐU", "ANH", "BAN", "BANG", "BAY", "BEN", "BINH", "CAN", "CANH", "CAO",
        "CAP", "CAT", "CAU", "CAY", "CHA", "CHANG", "CHAO", "CHAU", "CHE", "CHI",
        "CHIA", "CHINH", "CHO", "CHU", "CHUA", "CHUNG", "CON", "CONG", "CUNG",
        "CUOC", "CUOI", "CUU", "DAN", "DANG", "DANH", "DAO", "DAU", "DAY", "DEM",
        "DEN", "DEP", "DIA", "DIEM", "DIEN", "DIEU", "DINH", "DOAN", "DOI",
        "DONG", "DUNG", "DUOC", "DUONG", "DUY", "GAN", "GANG", "GANH", "GAO",
        "GIA", "GIAI", "GIAM", "GIAN", "GIANG", "GIAO", "GIAU", "GIEO", "GIU",
        "GIUA", "GOI", "GON", "GUI", "HAI", "HAM", "HAN", "HANG", "HANH", "HAO",
        "HAU", "HAY", "HEN", "HET", "HIEU", "HINH", "HOA", "HOAC", "HOAN",
        "HOANG", "HOAT", "HOI", "HON", "HONG", "HOP", "HUA", "HUE", "HUONG",
        "HUY", "KEO", "KET", "KEU", "KHA", "KHAC", "KHAI", "KHAN", "KHANH",
        "KHAO", "KHAP", "KHI", "KHO", "KHOA", "KHOAN", "KHOANG", "KHOE", "KHOI",
        "KHON", "KHONG", "KHU", "KHUNG", "KHUYA", "KIA", "KIEM", "KIEN", "KIEU",
        "KIM", "KINH", "KY", "LAC", "LAI", "LAM", "LAN", "LANG", "LANH", "LAO",
        "LAP", "LAT", "LAU", "LAY", "LEN", "LEO", "LIEN", "LIEU", "LINH", "LOAI",
        "LOAN", "LOC", "LOI", "LONG", "LUAT", "LUC", "LUI", "LUOC", "LUOI",
        "LUONG", "LUU", "LY", "MAI", "MAN", "MANG", "MANH", "MAO", "MAT", "MAU",
        "MAY", "MEO", "MIEN", "MINH", "MOI", "MONG", "MUA", "MUC", "MUI", "MUOI",
        "MUON", "NAI", "NAM", "NAN", "NANG", "NAO", "NAP", "NAY", "NEM", "NEN",
        "NEP", "NET", "NGAN", "NGANG", "NGANH", "NGHE", "NGHI", "NGHIA", "NGO",
        "NGOAI", "NGOAN", "NGOC", "NGOI", "NGON", "NGU", "NGUOC", "NGUOI",
        "NGUON", "NHA", "NHAC", "NHAI", "NHAN", "NHANG", "NHANH", "NHAT", "NHAU",
        "NHAY", "NHI", "NHIEN", "NHIEU", "NHIN", "NHIP", "NHO", "NHOM", "NHON",
        "NHU", "NHUA", "NHUC", "NHUNG", "NIEN", "NINH", "NOI", "NON", "NONG",
        "NUOC", "NUOI", "NUONG", "OAN", "OC", "ONG", "PHA", "PHAI", "PHAM",
        "PHAN", "PHANG", "PHAO", "PHAP", "PHAT", "PHE", "PHEP", "PHI", "PHIA",
        "PHIEN", "PHIM", "PHO", "PHONG", "PHU", "PHUC", "PHUN", "PHUNG", "PHUOC",
        "PHUONG", "PHUT", "QUA", "QUAI", "QUAN", "QUANG", "QUANH", "QUAT",
        "QUAY", "QUE", "QUEN", "QUET", "QUOC", "QUY", "QUYEN", "QUYET", "RANG",
        "RANH", "RAO", "RAT", "RE", "REN", "REO", "RINH", "RO", "ROI", "RONG",
        "ROT", "RU", "RUA", "RUC", "RUI", "RUN", "RUNG", "RUOC", "RUOI", "RUONG",
        "RUOU", "RUT", "SAN", "SANG", "SANH", "SAO", "SAP", "SAT", "SAU", "SAY",
        "SEN", "SEP", "SET", "SIA", "SOAN", "SOAT", "SOC", "SOI", "SOM", "SON",
        "SONG", "SOT", "SUA", "SUC", "SUI", "SUNG", "SUOI", "SUON", "SUONG",
        "SUOT", "SUP", "SUT", "TAO", "TAC", "TACH", "TAI", "TAM", "TAN", "TANG",
        "TAP", "TAT", "TAU", "TAY", "TEN", "TEO", "TEP", "TET", "THA", "THAC",
        "THAI", "THAM", "THAN", "THANG", "THANH", "THAO", "THAP", "THAT", "THAU",
        "THAY", "THE", "THEM", "THEO", "THI", "THICH", "THIEN", "THIEP", "THIET",
        "THIEU", "THIN", "THINH", "THO", "THOA", "THOAI", "THOANG", "THOAT",
        "THOC", "THOI", "THOM", "THON", "THONG", "THOT", "THU", "THUA", "THUAN",
        "THUAT", "THUC", "THUE", "THUI", "THUNG", "THUOC", "THUONG", "THUY",
        "THUYEN", "TIA", "TICH", "TIEC", "TIEM", "TIEN", "TIENG", "TIEP", "TIET",
        "TIEU", "TIM", "TIN", "TINH", "TIT", "TOA", "TOAN", "TOC", "TOI", "TOM",
        "TON", "TONG", "TOT", "TRA", "TRAC", "TRAI", "TRAM", "TRAN", "TRANG",
        "TRANH", "TRAO", "TRAT", "TRAU", "TRE", "TREN", "TREO", "TRI", "TRICH",
        "TRIEN", "TRIET", "TRIEU", "TRINH", "TRO", "TRON", "TRONG", "TRU", "TRUA",
        "TRUC", "TRUM", "TRUNG", "TRUOC", "TRUONG", "TRUOT", "TRUY", "TRUYEN",
        "TUA", "TUAN", "TUC", "TUE", "TUI", "TUM", "TUNG", "TUOI", "TUONG",
        "TUOT", "TUT", "TUY", "TUYEN", "TUYET", "VAI", "VAN", "VANG", "VAO",
        "VAT", "VAY", "VEN", "VEO", "VET", "VI", "VIA", "VIEC", "VIEM", "VIEN",
        "VIET", "VINH", "VOI", "VON", "VONG", "VOT", "VUA", "VUC", "VUI", "VUN",
        "VUNG", "VUON", "VUONG", "VUOT", "VUT", "XAI", "XAM", "XANH", "XAO",
        "XAU", "XAY", "XEM", "XEN", "XEO", "XEP", "XET", "XI", "XIA", "XICH",
        "XIET", "XIEU", "XIN", "XINH", "XIT", "XO", "XOA", "XOAI", "XOAN", "XOAY",
        "XOC", "XOI", "XOM", "XONG", "XOP", "XOT", "XUA", "XUAN", "XUAT", "XUC",
        "XUI", "XUNG", "XUOI", "XUONG", "XUYEN", "YEN", "YEU", "ĐANG", "ĐAO",
        "ĐAU", "ĐAY", "ĐEM", "ĐEN", "ĐEO", "ĐEP", "ĐIA", "ĐICH", "ĐIEM", "ĐIEN",
        "ĐIEP", "ĐIET", "ĐIEU", "ĐINH", "ĐOAN", "ĐOAT", "ĐOI", "ĐON", "ĐONG",
        "ĐOT", "ĐUA", "ĐUC", "ĐUI", "ĐUN", "ĐUNG", "ĐUOC", "ĐUOI", "ĐUONG", "ĐUT",
    )

    private val letterNames = mapOf(
        'A' to "a", 'B' to "bê", 'C' to "xê", 'D' to "dê", 'Đ' to "đê",
        'E' to "e", 'F' to "ép", 'G' to "giê", 'H' to "hát", 'I' to "i",
        'J' to "giây", 'K' to "ca", 'L' to "e lờ", 'M' to "em", 'N' to "en",
        'O' to "ô", 'P' to "pê", 'Q' to "quy", 'R' to "e rờ", 'S' to "ét",
        'T' to "tê", 'U' to "u", 'V' to "vê", 'W' to "vê kép", 'X' to "ích",
        'Y' to "i dài", 'Z' to "dét"
    )

    // Số thô, có thể có dấu phân cách hàng nghìn hoặc phần thập phân.
    private const val RAW_NUMBER =
        "[+-]?(?:\\d{1,3}(?:[. \\u00A0]\\d{3})+(?:[.,]\\d+)?|\\d+(?:[.,]\\d+)*)"
    private const val SIMPLE_NUMBER = "[+-]?\\d+(?:[.,]\\d+)?"

    private val reDateIso = Regex(
        "(?i)(?<!\\d)(?:ngày\\s+)?(\\d{4})\\s*-\\s*(\\d{1,2})\\s*-\\s*(\\d{1,2})(?!\\d)"
    )
    private val reDateFull = Regex(
        "(?i)(?<!\\d)(?:ngày\\s+)?(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})\\s*[/.-]\\s*(\\d{2,4})(?!\\d)"
    )
    private val reDateShort = Regex(
        "(?i)\\bngày\\s+(\\d{1,2})\\s*[/.-]\\s*(\\d{1,2})(?!\\s*[/.-]\\s*\\d)"
    )
    private val reTime = Regex(
        "(?<!\\d)([01]?\\d|2[0-3])\\s*(?::|[hH])\\s*([0-5]\\d)(?:\\s*(?::|[pP])\\s*([0-5]\\d))?(?!\\d)"
    )
    private val reHourOnly = Regex("(?<![\\p{L}\\d])([01]?\\d|2[0-3])\\s*[hH](?![\\p{L}\\d])")
    private val rePhone = Regex("(?<!\\d)(?:\\+84|84|0)(?:[ .-]?\\d){8,10}(?!\\d)")
    private val reScientific = Regex(
        "(?<![\\p{L}\\d])($SIMPLE_NUMBER)[eE]([+-]?\\d+)(?![\\p{L}\\d])"
    )
    private val reFraction = Regex("(?<!\\d)(\\d+)\\s*/\\s*(\\d+)(?!\\d)")
    private val reRange = Regex(
        "(?<![\\p{L}\\d])($SIMPLE_NUMBER)\\s*[-–—]\\s*($SIMPLE_NUMBER)(?![\\p{L}\\d])"
    )
    private val reDottedSequence = Regex("(?<!\\d)(\\d+(?:\\.\\d+){2,})(?!\\d)")
    private val reGeneralDecimal = Regex(
        "(?<![\\p{L}\\d])([+-]?\\d+(?:[.,]\\d+)+)(?![\\p{L}\\d])"
    )
    private val reGeneralInteger = Regex("(?<![\\p{L}\\d])([+-]?\\d+)(?![\\p{L}\\d])")
    private val reForeignToken = Regex("[A-Za-z][A-Za-z'-]*")
    private val reDottedInitialism = Regex("(?<![\\p{L}\\d])(?:[A-ZĐ]\\.){2,}[A-ZĐ]?\\.?")
    private val reDottedAcronym = Regex("(?<![\\p{L}\\d])[A-ZĐ]{1,10}(?:\\.[A-ZĐ]{1,10})+(?![\\p{L}\\d])")
    private val reInitialism = Regex("(?<![\\p{L}\\d])([A-ZĐ]{2,10})(?![\\p{L}\\d])")
    private val reLetterNumber = Regex("(?<![\\p{L}\\d])([A-ZĐ])(\\d+)(?![\\p{L}\\d])")
    private val reNumberLetter = Regex("(?<![\\p{L}\\d])(\\d+)([A-ZĐ])(?![\\p{L}\\d])")
    private val reSingleLetter = Regex("(?<![\\p{L}\\d])([A-ZĐ])(?![\\p{L}\\d])")
    private val reHorizontalSpace = Regex("[ \\t\\u00A0]{2,}")
    private val reManyBlankLines = Regex("\\n{3,}")

    private val currencyPrefixNames = linkedMapOf(
        "US\$" to "đô la Mỹ", "\$" to "đô la Mỹ", "USD" to "đô la Mỹ",
        "₫" to "đồng", "VNĐ" to "đồng", "VND" to "đồng",
        "€" to "ơ rô", "EUR" to "ơ rô", "£" to "bảng Anh", "GBP" to "bảng Anh"
    )
    private val currencySuffixNames = linkedMapOf(
        "đồng" to "đồng", "VNĐ" to "đồng", "VND" to "đồng", "₫" to "đồng", "đ" to "đồng",
        "đô la Mỹ" to "đô la Mỹ", "đô la" to "đô la", "USD" to "đô la Mỹ",
        "EUR" to "ơ rô", "€" to "ơ rô", "GBP" to "bảng Anh", "£" to "bảng Anh"
    )
    private val currencyPrefixAlternation = currencyPrefixNames.keys
        .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val currencySuffixAlternation = currencySuffixNames.keys
        .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val reCurrencyPrefix = Regex(
        "(?i)(?<![\\p{L}\\d])($currencyPrefixAlternation)\\s*($RAW_NUMBER)"
    )
    private val reCurrencySuffix = Regex(
        "(?i)(?<![\\p{L}\\d])($RAW_NUMBER)\\s*($currencySuffixAlternation)(?![\\p{L}\\d])"
    )

    private val unitNames = linkedMapOf(
        "km/h" to "ki lô mét trên giờ", "km/giờ" to "ki lô mét trên giờ",
        "m/s" to "mét trên giây", "m/giây" to "mét trên giây",
        "km²" to "ki lô mét vuông", "km2" to "ki lô mét vuông",
        "m²" to "mét vuông", "m2" to "mét vuông",
        "cm²" to "xen ti mét vuông", "cm2" to "xen ti mét vuông",
        "mm²" to "mi li mét vuông", "mm2" to "mi li mét vuông",
        "m³" to "mét khối", "m3" to "mét khối",
        "cm³" to "xen ti mét khối", "cm3" to "xen ti mét khối",
        "kWh" to "ki lô oát giờ", "kHz" to "ki lô héc", "MHz" to "mê ga héc",
        "GHz" to "ghi ga héc", "Hz" to "héc",
        "TB" to "tê ra bai", "GB" to "ghi ga bai", "MB" to "mê ga bai", "KB" to "ki lô bai",
        "kW" to "ki lô oát", "MW" to "mê ga oát", "W" to "oát",
        "mV" to "mi li vôn", "kV" to "ki lô vôn", "V" to "vôn",
        "mA" to "mi li am pe", "A" to "am pe",
        "kg" to "ki lô gam", "mg" to "mi li gam", "g" to "gam",
        "km" to "ki lô mét", "cm" to "xen ti mét", "mm" to "mi li mét", "µm" to "mi crô mét",
        "ml" to "mi li lít", "mL" to "mi li lít", "l" to "lít", "L" to "lít",
        "ha" to "héc ta", "tấn" to "tấn", "t" to "tấn",
        "ms" to "mi li giây", "giây" to "giây", "phút" to "phút", "giờ" to "giờ",
        "h" to "giờ", "s" to "giây"
    )
    private val unitAlternation = unitNames.keys
        .sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }
    private val reMeasureRange = Regex(
        "(?i)(?<![\\p{L}\\d])($SIMPLE_NUMBER)\\s*[-–—]\\s*($SIMPLE_NUMBER)\\s*($unitAlternation)(?![\\p{L}\\d²³])"
    )
    private val reMeasure = Regex(
        "(?i)(?<![\\p{L}\\d])($RAW_NUMBER)\\s*($unitAlternation)(?![\\p{L}\\d²³])"
    )
    private val rePercentRange = Regex(
        "(?<![\\p{L}\\d])($SIMPLE_NUMBER)\\s*[-–—]\\s*($SIMPLE_NUMBER)\\s*%"
    )
    private val rePercent = Regex("(?<![\\p{L}\\d])($RAW_NUMBER)\\s*%")
    private val reTemperature = Regex(
        "(?i)(?<![\\p{L}\\d])($RAW_NUMBER)\\s*°\\s*([CFK])(?![\\p{L}\\d])"
    )
    private val reDegree = Regex("(?<![\\p{L}\\d])($RAW_NUMBER)\\s*°")
    private val reMultiplierRange = Regex(
        "(?i)(?<![\\p{L}\\d])($SIMPLE_NUMBER)\\s*[-–—]\\s*($SIMPLE_NUMBER)\\s*(nghìn|ngàn|triệu|tỷ|k|tr)(?![\\p{L}])"
    )
    private val reMultiplier = Regex(
        "(?i)(?<![\\p{L}\\d])($RAW_NUMBER)\\s*(nghìn|ngàn|triệu|tỷ|k|tr)(?![\\p{L}])"
    )
    // Display resolution such as 4K is a number + spoken letter "ca", not
    // Vietnamese shorthand for four thousand (normally written lowercase k).
    private val reDisplayK = Regex(
        "(?<![\\p{L}\\d])($SIMPLE_NUMBER)\\s*K(?![\\p{L}])"
    )
    private val reRomanContext = Regex(
        "(?i)\\b(chương|chuong|phần|phan|tập|tap|quyển|quyen|hồi|hoi|mục|muc|thế\\s+chiến|the\\s+chien|thế\\s+kỷ|the\\s+ky)\\s+([IVXLCDM]{1,12})\\b"
    )

    init {
        installBuiltIns()
        rebuildAcronymPatterns()
    }

    private fun installBuiltIns() {
        mapOf(
            "UBND" to "ủy ban nhân dân", "HĐND" to "hội đồng nhân dân",
            "TP" to "thành phố", "TPHCM" to "thành phố Hồ Chí Minh", "VN" to "Việt Nam",
            "AI" to "trí tuệ nhân tạo", "CNTT" to "công nghệ thông tin",
            "CEO" to "giám đốc điều hành", "GDP" to "tổng sản phẩm quốc nội",
            "USD" to "đô la Mỹ", "NXB" to "nhà xuất bản", "STT" to "số thứ tự",
            "CMND" to "chứng minh nhân dân", "CCCD" to "căn cước công dân",
            "THPT" to "trung học phổ thông", "THCS" to "trung học cơ sở",
            "TTS" to "tê tê ét", "CPU" to "xê pê u", "GPU" to "giê pê u",
            "RAM" to "ram", "ROM" to "rôm", "PDF" to "pê đê ép", "APK" to "a pê ca",
            "URL" to "u e rờ lờ", "HTTP" to "hát tê tê pê", "HTTPS" to "hát tê tê pê ét",
            "SMS" to "ét em ét", "GPS" to "giê pê ét", "ATM" to "a tê em",
            "TV" to "tê vê", "MC" to "em xê", "DJ" to "đi giây", "OK" to "ô kê"
        ).forEach { (k, v) -> acronyms.putIfAbsent(k, v) }

        mapOf(
            "app" to "áp", "apps" to "áp", "code" to "cốt", "developer" to "đờ ve lốp pơ",
            "email" to "i meo", "facebook" to "phây búc", "google" to "gu gồ",
            "internet" to "in tơ nét", "online" to "on lai", "offline" to "óp lai",
            "server" to "sơ vơ", "web" to "goép", "website" to "goép sai",
            "youtube" to "du túp", "tiktok" to "tích tóc", "capcut" to "cáp cắt",
            "wifi" to "quai phai", "bluetooth" to "blu tút", "android" to "an đờ roi",
            "iphone" to "ai phôn", "windows" to "uyn đâuz", "chat" to "chát",
            "covid" to "cô vít", "container" to "công ten nơ", "singapore" to "xin ga po"
        ).forEach { (k, v) -> foreignWords.putIfAbsent(k, v) }
    }

    /** Nạp/ghi đè từ điển trong assets. Dòng đầu chỉ bị bỏ nếu thật sự là tiêu đề CSV. */
    fun ensureLoaded(context: Context?) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val ctx = context ?: Config.appContext
            if (ctx != null) {
                runCatching {
                    loadCsv(ctx, "vietnormalizer/acronyms.csv") { k, v -> acronyms[k] = v }
                }
                runCatching {
                    loadCsv(ctx, "vietnormalizer/non-vietnamese-words.csv") { k, v ->
                        foreignWords[k.lowercase(Locale.ROOT)] = v
                    }
                }
            }
            rebuildAcronymPatterns()
            loaded = true
        }
    }

    private fun loadCsv(context: Context, path: String, put: (String, String) -> Unit) {
        context.assets.open(path).bufferedReader(Charsets.UTF_8).useLines { lines ->
            var firstContentLine = true
            for (raw in lines) {
                val line = raw.trim('\uFEFF', '\r', '\n', ' ')
                if (line.isBlank() || line.startsWith("#")) continue
                val comma = line.indexOf(',')
                if (comma <= 0) continue
                val key = line.substring(0, comma).trim().trim('"')
                val value = line.substring(comma + 1).trim().trim('"')
                if (firstContentLine) {
                    firstContentLine = false
                    val header = key.lowercase(Locale.ROOT)
                    if (header in setOf(
                            "key", "source", "original", "acronym", "word", "từ", "tu",
                        )
                    ) continue
                }
                if (key.isNotEmpty() && value.isNotEmpty()) put(key, value)
            }
        }
    }

    private fun rebuildAcronymPatterns() {
        acronymPatterns = acronyms.entries
            .sortedByDescending { it.key.length }
            .map { (key, value) ->
                val ignoreCase = key.any { it.isLowerCase() }
                val options = if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet()
                Regex(
                    "(?<![\\p{L}\\d])${Regex.escape(key)}(?![\\p{L}\\d])",
                    options
                ) to value
            }
    }

    // ------------------------------------------------------------------
    // Đọc số
    // ------------------------------------------------------------------

    private fun readThree(value: Int, forceHundreds: Boolean): String {
        val hundreds = value / 100
        val tens = value / 10 % 10
        val ones = value % 10
        val out = ArrayList<String>(5)

        if (hundreds > 0 || forceHundreds) {
            out += digits[hundreds]
            out += "trăm"
        }
        when {
            tens == 0 -> {
                if (ones > 0 && (hundreds > 0 || forceHundreds)) out += "lẻ"
                if (ones > 0) out += digits[ones]
            }
            tens == 1 -> {
                out += "mười"
                when (ones) {
                    0 -> Unit
                    5 -> out += "lăm"
                    else -> out += digits[ones]
                }
            }
            else -> {
                out += digits[tens]
                out += "mươi"
                when (ones) {
                    0 -> Unit
                    1 -> out += "mốt"
                    5 -> out += "lăm"
                    else -> out += digits[ones]
                }
            }
        }
        return out.joinToString(" ")
    }

    private fun scaleName(groupIndex: Int): String {
        if (groupIndex <= 0) return ""
        val small = when (groupIndex % 3) {
            1 -> "nghìn"
            2 -> "triệu"
            else -> ""
        }
        val billionCount = groupIndex / 3
        return buildList {
            if (small.isNotEmpty()) add(small)
            repeat(billionCount) { add("tỷ") }
        }.joinToString(" ")
    }

    /** Đọc số nguyên không dấu, hỗ trợ số rất lớn theo nhóm nghìn/triệu/tỷ. */
    fun readInteger(digitsIn: String): String {
        val source = digitsIn.filter { it.isDigit() }
        if (source.isEmpty()) return digitsIn
        val clean = source.trimStart('0').ifEmpty { "0" }
        if (clean == "0") return digits[0]
        if (clean.length > 66) return readDigitsIndividually(clean)

        val groups = ArrayList<Int>() // nhóm thấp nhất ở vị trí 0
        var end = clean.length
        while (end > 0) {
            val start = (end - 3).coerceAtLeast(0)
            groups += clean.substring(start, end).toInt()
            end = start
        }
        val highest = groups.indexOfLast { it != 0 }
        val out = ArrayList<String>()
        for (index in highest downTo 0) {
            val group = groups[index]
            if (group == 0) continue
            val forceHundreds = index < highest && group < 100
            out += readThree(group, forceHundreds)
            scaleName(index).takeIf { it.isNotEmpty() }?.let(out::add)
        }
        return out.joinToString(" ")
    }

    private fun readDigitsIndividually(value: String): String = value
        .filter { it.isDigit() }
        .map { digits[it - '0'] }
        .joinToString(" ")

    private data class ParsedNumber(
        val negative: Boolean,
        val integerDigits: String,
        val fractionDigits: String? = null
    )

    private fun parseNumber(raw: String): ParsedNumber? {
        var text = raw.trim().replace(" ", "").replace("\u00A0", "")
        if (text.isEmpty()) return null
        val negative = text.startsWith('-')
        if (text.firstOrNull() == '-' || text.firstOrNull() == '+') text = text.drop(1)
        if (text.isEmpty() || text.any { !it.isDigit() && it != '.' && it != ',' }) return null

        val dotCount = text.count { it == '.' }
        val commaCount = text.count { it == ',' }
        var integerPart = text
        var fractionPart: String? = null

        if (dotCount > 0 && commaCount > 0) {
            val lastDot = text.lastIndexOf('.')
            val lastComma = text.lastIndexOf(',')
            val decimalIndex = maxOf(lastDot, lastComma)
            integerPart = text.substring(0, decimalIndex).replace(".", "").replace(",", "")
            fractionPart = text.substring(decimalIndex + 1).replace(".", "").replace(",", "")
        } else if (dotCount > 0) {
            val parts = text.split('.')
            val thousands = parts.size > 2 && parts.drop(1).all { it.length == 3 } ||
                parts.size == 2 && parts[0].length in 1..3 && parts[1].length == 3
            if (thousands) integerPart = parts.joinToString("")
            else {
                integerPart = parts.dropLast(1).joinToString("").ifEmpty { "0" }
                fractionPart = parts.last()
            }
        } else if (commaCount > 0) {
            val parts = text.split(',')
            val thousands = parts.size > 2 && parts.drop(1).all { it.length == 3 }
            if (thousands) integerPart = parts.joinToString("")
            else {
                integerPart = parts.dropLast(1).joinToString("").ifEmpty { "0" }
                fractionPart = parts.last()
            }
        }

        integerPart = integerPart.filter { it.isDigit() }.ifEmpty { "0" }
        fractionPart = fractionPart?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
        return ParsedNumber(negative, integerPart, fractionPart)
    }

    private fun readNumber(raw: String, preserveLeadingZero: Boolean = true): String {
        val parsed = parseNumber(raw) ?: return raw
        val integerSpoken = if (
            preserveLeadingZero && parsed.integerDigits.length > 1 && parsed.integerDigits.startsWith('0')
        ) readDigitsIndividually(parsed.integerDigits) else readInteger(parsed.integerDigits)
        val out = StringBuilder()
        if (parsed.negative) out.append("âm ")
        out.append(integerSpoken)
        parsed.fractionDigits?.let {
            out.append(" phẩy ").append(readDigitsIndividually(it))
        }
        return out.toString()
    }

    private fun readDate(day: Int, month: Int, year: String?): String {
        val out = StringBuilder("ngày ")
            .append(readInteger(day.toString()))
            .append(" tháng ")
            .append(readInteger(month.toString()))
        if (!year.isNullOrBlank()) {
            // Năm 2 chữ số đọc thành năm đầy đủ của thế kỷ 21 (24 -> 2024),
            // khớp cách isValidDate quy đổi năm.
            val fullYear = if (year.length == 2) "20$year" else year
            out.append(" năm ").append(readInteger(fullYear))
        }
        return out.toString()
    }

    private fun isValidDate(day: Int, month: Int, yearText: String?): Boolean {
        if (month !in 1..12 || day < 1) return false
        val year = yearText?.toIntOrNull()?.let { if (yearText.length == 2) 2000 + it else it } ?: 2000
        val leap = year % 400 == 0 || year % 4 == 0 && year % 100 != 0
        val maxDay = when (month) {
            2 -> if (leap) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
        return day <= maxDay
    }

    private fun readTime(hour: Int, minute: Int, second: Int?): String {
        val out = StringBuilder().append(readInteger(hour.toString())).append(" giờ")
        if (minute > 0 || second != null) {
            out.append(' ')
            if (minute in 1..9) out.append("lẻ ")
            out.append(readInteger(minute.toString())).append(" phút")
        }
        if (second != null) {
            out.append(' ')
            if (second in 1..9) out.append("lẻ ")
            out.append(readInteger(second.toString())).append(" giây")
        }
        return out.toString()
    }

    private fun romanToInt(raw: String): Int? {
        val roman = raw.uppercase(Locale.ROOT)
        if (!Regex("M{0,3}(CM|CD|D?C{0,3})(XC|XL|L?X{0,3})(IX|IV|V?I{0,3})").matches(roman) || roman.isEmpty()) {
            return null
        }
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in roman.indices) {
            val current = values[roman[i]] ?: return null
            val next = roman.getOrNull(i + 1)?.let(values::get) ?: 0
            total += if (current < next) -current else current
        }
        return total.takeIf { it > 0 }
    }

    // ------------------------------------------------------------------
    // Pipeline
    // ------------------------------------------------------------------

    fun normalize(input: String): String {
        if (input.isBlank()) return input
        // Từ điển lớn được nạp đúng một lần khi người dùng bắt đầu nghe, thay vì
        // chiếm cold-start dù phiên hiện tại chỉ đọc giao diện/thư viện.
        ensureLoaded(Config.appContext)
        var text = JavaNormalizer.normalize(input, JavaNormalizer.Form.NFC)
            .replace('\u00A0', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        text = removeEmojiAndControls(text)

        // URL thường chỉ là nhiễu đối với truyện; bỏ phần giao thức nhưng giữ tên miền.
        text = Regex("(?i)\\bhttps?://(?:www\\.)?").replace(text, "")
        text = Regex("(?i)\\bwww\\.").replace(text, "")

        // Ngày tháng: phải đứng trước khoảng số và số thường.
        text = reDateIso.replace(text) { m ->
            val year = m.groupValues[1]
            val month = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            val day = m.groupValues[3].toIntOrNull() ?: return@replace m.value
            if (isValidDate(day, month, year)) readDate(day, month, year) else m.value
        }
        text = reDateFull.replace(text) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val month = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            val year = m.groupValues[3]
            if (isValidDate(day, month, year)) readDate(day, month, year) else m.value
        }
        text = reDateShort.replace(text) { m ->
            val day = m.groupValues[1].toIntOrNull() ?: return@replace m.value
            val month = m.groupValues[2].toIntOrNull() ?: return@replace m.value
            if (isValidDate(day, month, null)) readDate(day, month, null) else m.value
        }

        // Giờ.
        text = reTime.replace(text) { m ->
            val hour = m.groupValues[1].toInt()
            val minute = m.groupValues[2].toInt()
            val second = m.groupValues[3].takeIf { it.isNotBlank() }?.toInt()
            readTime(hour, minute, second)
        }
        text = reHourOnly.replace(text) { m -> readInteger(m.groupValues[1]) + " giờ" }

        // Số La Mã trong ngữ cảnh rõ ràng: Chương IV, thế kỷ XXI...
        text = reRomanContext.replace(text) { m ->
            val number = romanToInt(m.groupValues[2]) ?: return@replace m.value
            "${m.groupValues[1]} ${readInteger(number.toString())}"
        }

        // Tiền tệ phải xử lý trước số thường.
        text = reCurrencyPrefix.replace(text) { m ->
            val unit = lookupIgnoreCase(currencyPrefixNames, m.groupValues[1]) ?: return@replace m.value
            "${readNumber(m.groupValues[2])} $unit"
        }
        text = reCurrencySuffix.replace(text) { m ->
            val unit = lookupIgnoreCase(currencySuffixNames, m.groupValues[2]) ?: return@replace m.value
            "${readNumber(m.groupValues[1])} $unit"
        }

        // Số điện thoại: giữ từng số 0, không đọc thành số nguyên.
        text = rePhone.replace(text) { m ->
            val plus = m.value.trimStart().startsWith('+')
            val spoken = readDigitsIndividually(m.value.filter { it.isDigit() })
            if (plus) "cộng $spoken" else spoken
        }

        // Nhiệt độ, phần trăm, đơn vị và hậu tố nghìn/triệu/tỷ.
        text = reTemperature.replace(text) { m ->
            val unit = when (m.groupValues[2].uppercase(Locale.ROOT)) {
                "C" -> "độ xê"
                "F" -> "độ ép"
                else -> "độ ken vin"
            }
            "${readNumber(m.groupValues[1])} $unit"
        }
        text = reDegree.replace(text) { m -> "${readNumber(m.groupValues[1])} độ" }
        text = rePercentRange.replace(text) { m ->
            "${readNumber(m.groupValues[1])} đến ${readNumber(m.groupValues[2])} phần trăm"
        }
        text = rePercent.replace(text) { m -> "${readNumber(m.groupValues[1])} phần trăm" }
        text = reMeasureRange.replace(text) { m ->
            val unit = lookupIgnoreCase(unitNames, m.groupValues[3]) ?: return@replace m.value
            "${readNumber(m.groupValues[1])} đến ${readNumber(m.groupValues[2])} $unit"
        }
        text = reMeasure.replace(text) { m ->
            val unit = lookupIgnoreCase(unitNames, m.groupValues[2]) ?: return@replace m.value
            "${readNumber(m.groupValues[1])} $unit"
        }
        text = reDisplayK.replace(text) { m -> "${readNumber(m.groupValues[1])} ca" }
        text = reMultiplierRange.replace(text) { m ->
            val unit = normalizeMultiplier(m.groupValues[3])
            "${readNumber(m.groupValues[1])} đến ${readNumber(m.groupValues[2])} $unit"
        }
        text = reMultiplier.replace(text) { m ->
            "${readNumber(m.groupValues[1])} ${normalizeMultiplier(m.groupValues[2])}"
        }

        // Ký pháp khoa học, phân số và khoảng số.
        text = reScientific.replace(text) { m ->
            "${readNumber(m.groupValues[1])} nhân mười mũ ${readNumber(m.groupValues[2], false)}"
        }
        text = reFraction.replace(text) { m ->
            val denominator = m.groupValues[2]
            if (denominator.trimStart('0').ifEmpty { "0" } == "0") m.value
            else "${readInteger(m.groupValues[1])} phần ${readInteger(denominator)}"
        }
        text = reRange.replace(text) { m ->
            "${readNumber(m.groupValues[1])} đến ${readNumber(m.groupValues[2])}"
        }

        // IP/phiên bản có nhiều dấu chấm; chuỗi nhóm 3 vẫn là số hàng nghìn.
        text = reDottedSequence.replace(text) { m ->
            val parts = m.value.split('.')
            if (parts.drop(1).all { it.length == 3 }) readInteger(parts.joinToString(""))
            else parts.joinToString(" chấm ") { readInteger(it) }
        }

        // Số thập phân và số nguyên còn lại.
        text = reGeneralDecimal.replace(text) { m -> readNumber(m.groupValues[1]) }
        text = reGeneralInteger.replace(text) { m -> readNumber(m.groupValues[1]) }

        // Viết tắt có dấu chấm (TP.HCM): nếu dạng gộp liền có trong từ điển
        // thì mở rộng ngay tại đây, tránh bị tách thành "TP" + đánh vần "HCM".
        text = reDottedAcronym.replace(text) { m ->
            val joined = m.value.filter { it.isLetter() }
            acronyms.entries.firstOrNull { it.key.equals(joined, ignoreCase = true) }?.value ?: m.value
        }

        // Từ viết tắt có từ điển.
        for ((pattern, replacement) in acronymPatterns) {
            text = pattern.replace(text, replacement)
        }

        // Từ nước ngoài chỉ thay khi có trong từ điển để không phá tên riêng.
        text = reForeignToken.replace(text) { m ->
            foreignWords[m.value.lowercase(Locale.ROOT)] ?: m.value
        }

        // Các mã chữ/số và chữ viết hoa chưa có trong từ điển thì đánh vần.
        text = reLetterNumber.replace(text) { m ->
            "${spellLetters(m.groupValues[1])} ${readInteger(m.groupValues[2])}"
        }
        text = reNumberLetter.replace(text) { m ->
            "${readInteger(m.groupValues[1])} ${spellLetters(m.groupValues[2])}"
        }
        text = reDottedInitialism.replace(text) { m -> spellLetters(m.value.filter { it.isLetter() }) }
        text = reInitialism.replace(text) { m ->
            val token = m.groupValues[1]
            if (token in initialismProtectedWords) token else spellLetters(token)
        }
        text = reSingleLetter.replace(text) { m -> spellLetters(m.groupValues[1]) }

        text = normalizeSymbolsAndPunctuation(text)
        return tidyWhitespace(text)
    }

    private fun normalizeMultiplier(raw: String): String = when (raw.lowercase(Locale.ROOT)) {
        "k", "nghìn", "ngàn" -> "nghìn"
        "tr", "triệu" -> "triệu"
        else -> "tỷ"
    }

    private fun spellLetters(raw: String): String = raw
        .uppercase(Locale.ROOT)
        .mapNotNull { letterNames[it] }
        .joinToString(" ")
        .ifEmpty { raw }

    private fun <T> lookupIgnoreCase(map: Map<String, T>, key: String): T? =
        map.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value

    private fun normalizeSymbolsAndPunctuation(source: String): String {
        var text = source
            .replace("&", " và ")
            .replace("+", " cộng ")
            .replace("=", " bằng ")
            .replace("×", " nhân ")
            .replace("*", " nhân ")
            .replace("@", " a còng ")
            .replace("#", " số ")
            .replace("_", " gạch dưới ")
            .replace("/", " gạch chéo ")
            .replace("\\", " gạch chéo ")
            .replace('“', '"').replace('”', '"').replace('„', '"')
            .replace('‘', '\u0027').replace('’', '\u0027')

        text = Regex("…+|\\.{3,}").replace(text, ". ")
        text = Regex("(?m)^[ \\t]*[-–—]+[ \\t]*").replace(text, "")
        text = Regex("[ \\t]+[-–—]+[ \\t]+").replace(text, ", ")
        text = Regex("[–—]+").replace(text, ", ")
        text = Regex("(?<=\\p{L})-(?=\\p{L})").replace(text, " ")
        text = Regex("[\\[\\]{}<>]").replace(text, " ")
        text = Regex("[\"']").replace(text, "")
        text = Regex("[!?]{2,}").replace(text) { it.value.take(1) }
        text = Regex(",\\s*,+").replace(text, ", ")
        return text
    }

    private fun tidyWhitespace(source: String): String {
        var text = reHorizontalSpace.replace(source, " ")
        text = Regex("[ \\t]+\\n").replace(text, "\n")
        text = Regex("\\n[ \\t]+").replace(text, "\n")
        text = reManyBlankLines.replace(text, "\n\n")
        text = Regex("\\s+([,.;:!?])").replace(text) { it.groupValues[1] }
        text = Regex("([,;:!?])(?=[\\p{L}\\d])").replace(text) { "${it.groupValues[1]} " }
        return text.trim()
    }

    private fun removeEmojiAndControls(source: String): String {
        val out = StringBuilder(source.length)
        var index = 0
        while (index < source.length) {
            val cp = source.codePointAt(index)
            index += Character.charCount(cp)
            val emoji = cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF
            val control = Character.isISOControl(cp) && cp != '\n'.code && cp != '\t'.code
            when {
                emoji -> out.append(' ')
                control -> Unit
                else -> out.appendCodePoint(cp)
            }
        }
        return out.toString()
    }
}
