package vn.tramtruyen.reader.engine

import java.text.Normalizer as JavaNormalizer

/**
 * Lớp cuối dành riêng cho CapCut TTS.
 *
 * [normalize] chạy đầy đủ chuẩn hóa tiếng Việt. [prepareForSsml] chỉ làm sạch
 * ký tự/dấu câu, dùng trong provider để vẫn tôn trọng tùy chọn tắt chuẩn hóa.
 *
 * Thứ tự các bước trong [prepareForSsml] rất quan trọng và đã được kiểm thử
 * bằng java.text.Normalizer (28 ca, gồm NFD, ký tự vô hình, dấu lặp):
 * ký tự vô hình và dấu tổ hợp lặp phải xử lý TRƯỚC NFC, nếu không NFC sẽ
 * không ghép được dấu (ký tự vô hình chen giữa) hoặc dấu lặp thứ hai lọt qua
 * (dấu đầu đã bị hút vào chữ dựng sẵn).
 */
object CapcutVietnameseNormalizer {

    // Ký tự vô hình hay gặp khi sao chép từ web/EPUB: soft hyphen, Arabic mark,
    // Mongolian separator, ZWSP/ZWNJ/ZWJ, LRM/RLM, bidi control, word joiner,
    // invisible operator, bidi isolate, BOM, interlinear anchor.
    private val reInvisible =
        Regex("[\u00AD\u061C\u180E\u200B-\u200F\u202A-\u202E\u2060-\u2064\u2066-\u2069\uFEFF\uFFF9-\uFFFB]")

    // Mọi biến thể khoảng trắng Unicode về space thường: NBSP, ogham, en/em quad,
    // hair/thin space, narrow NBSP, medium math space, ideographic space.
    private val reUnicodeSpaces =
        Regex("[\u00A0\u1680\u2000-\u200A\u202F\u205F\u3000]")

    // Dấu tổ hợp giống nhau lặp kép (lỗi OCR/copy) về một.
    private val reDupMark = Regex("""(\p{M})\1+""")

    // Ba chấm: ký tự … hoặc ba dấu chấm trở lên (kể cả viết rời ". . .").
    private val reEllipsis = Regex("""…+|\.(?:\s*\.){2,}""")

    private val reManyNewlines = Regex("[ \t]*\n{2,}[ \t]*")
    private val reOneNewline = Regex("[ \t]*\n[ \t]*")
    private val reManySpaces = Regex("[ \t]{2,}")
    private val reSpaceBeforePunct = Regex("[ \t]+([,.;:!?])")

    // Chỉ thêm khoảng trắng sau dấu câu khi theo sau là CHỮ; không đụng tới
    // số thập phân/ phân tách nghìn (3,5 hoặc 1,000) và tên miền (dấu chấm).
    private val rePunctBeforeLetter = Regex("""([,;:!?])(?=\p{L})""")

    private val reDupPunct = Regex("""([,.!?])\s*\1+""")

    // Dấu kết câu (. ! ? ;) bị dính thêm dấu phẩy do bước xuống dòng -> ", "
    // (ví dụ "Câu một.\nCâu hai" thành "Câu một., Câu hai"): bỏ dấu phẩy thừa,
    // giữ đúng dấu kết câu để TTS ngắt nhịp tự nhiên.
    private val reTerminalComma = Regex("""([.!?;])\s*,\s*""")
    private val reTrailingEmphaticPunct = Regex("""[!?]{2,}\s*$""")

    fun normalize(input: String): String = prepareForSsml(VietNormalizer.normalize(input))

    fun prepareForSsml(input: String): String {
        if (input.isBlank()) return input
        // 1. Thống nhất ký tự xuống dòng.
        var text = input
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        // 2. Khoảng trắng Unicode về space thường.
        text = reUnicodeSpaces.replace(text, " ")
        // 3. Xóa ký tự vô hình TRƯỚC NFC: ZWSP/soft hyphen chen giữa chữ và dấu
        //    sẽ chặn NFC ghép dấu (ngư<ZWSP>ời không bao giờ gộp được).
        text = reInvisible.replace(text, "")
        // 4. Gộp dấu tổ hợp lặp TRƯỚC NFC: để sau NFC thì dấu đầu đã bị hút vào
        //    chữ dựng sẵn, dấu thứ hai lọt qua kẽ hở.
        text = reDupMark.replace(text) { it.groupValues[1] }
        // 5. NFC: gộp tổ hợp về dựng sẵn, tự sắp xếp lại dấu gõ sai thứ tự.
        text = JavaNormalizer.normalize(text, JavaNormalizer.Form.NFC)
        // 6. Ba chấm về dấu chấm câu rõ ràng để CapCut ngắt nhịp ổn định.
        text = reEllipsis.replace(text, ". ")
        // 7. Xuống dòng thành dấu câu: nhiều dòng = chấm, một dòng = phẩy.
        text = reManyNewlines.replace(text, ". ")
        text = reOneNewline.replace(text, ", ")
        // 7b. Dòng trước đã kết bằng dấu câu thì dấu phẩy vừa thêm là thừa:
        // "Câu một., Câu hai" -> "Câu một. Câu hai".
        text = reTerminalComma.replace(text) { "${it.groupValues[1]} " }
        // 8. Dọn khoảng trắng thừa và khoảng trắng trước dấu câu.
        text = reManySpaces.replace(text, " ")
        text = reSpaceBeforePunct.replace(text) { it.groupValues[1] }
        // 9. Thêm khoảng trắng sau dấu câu khi đứng trước chữ (an toàn với 3,5).
        text = rePunctBeforeLetter.replace(text) { "${it.groupValues[1]} " }
        // 10. Chuỗi cảm thán/nghi vấn lặp ở cuối câu thành dấu chấm để TTS kết
        //     thúc ổn định; dấu lặp giữa câu vẫn giữ đúng loại dấu.
        text = reTrailingEmphaticPunct.replace(text, ".")
        text = reDupPunct.replace(text) { it.groupValues[1] }
        return text.trim()
    }
}
