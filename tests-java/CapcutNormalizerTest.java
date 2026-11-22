import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * Bản port 1:1 sang Java của CapcutVietnameseNormalizer (Kotlin) để kiểm thử
 * đúng bằng java.text.Normalizer — engine mà Android sử dụng.
 */
public class CapcutNormalizerTest {

    // ===== BẮT ĐẦU: logic giống hệt file Kotlin mới =====
    private static final Pattern RE_INVISIBLE = Pattern.compile(
            "[\\u00AD\\u061C\\u180E\\u200B-\\u200F\\u202A-\\u202E\\u2060-\\u2064\\u2066-\\u2069\\uFEFF\\uFFF9-\\uFFFB]");
    private static final Pattern RE_UNICODE_SPACES = Pattern.compile(
            "[\\u00A0\\u1680\\u2000-\\u200A\\u202F\\u205F\\u3000]");
    private static final Pattern RE_DUP_MARK = Pattern.compile("(\\p{M})\\1+");
    private static final Pattern RE_ELLIPSIS = Pattern.compile("…+|\\.(?:\\s*\\.){2,}");
    private static final Pattern RE_MANY_NEWLINES = Pattern.compile("[ \\t]*\\n{2,}[ \\t]*");
    private static final Pattern RE_ONE_NEWLINE = Pattern.compile("[ \\t]*\\n[ \\t]*");
    private static final Pattern RE_MANY_SPACES = Pattern.compile("[ \\t]{2,}");
    private static final Pattern RE_SPACE_BEFORE_PUNCT = Pattern.compile("[ \\t]+([,.;:!?])");
    private static final Pattern RE_PUNCT_BEFORE_LETTER = Pattern.compile("([,;:!?])(?=\\p{L})");
    private static final Pattern RE_DUP_PUNCT = Pattern.compile("([,.!?])\\s*\\1+");
    private static final Pattern RE_TRAILING_EMPHATIC = Pattern.compile("[!?]{2,}\\s*$");

    static String prepareForSsml(String input) {
        if (input == null || input.isBlank()) return input;
        // 1. Thống nhất ký tự xuống dòng.
        String text = input.replace("\r\n", "\n").replace('\r', '\n');
        // 2. Mọi khoảng trắng Unicode (NBSP, narrow, ideographic...) về space thường.
        text = RE_UNICODE_SPACES.matcher(text).replaceAll(" ");
        // 3. Xóa ký tự vô hình TRƯỚC NFC: ZWSP/soft hyphen chen giữa chữ và dấu
        //    sẽ chặn NFC ghép dấu (ngư<ZWSP>ời không bao giờ gộp được).
        text = RE_INVISIBLE.matcher(text).replaceAll("");
        // 4. Gộp dấu tổ hợp lặp kép TRƯỚC NFC (OCR hay gặp): nếu để sau NFC,
        //    dấu đầu đã bị hút vào chữ dựng sẵn, dấu thứ hai lọt qua kẽ hở.
        text = RE_DUP_MARK.matcher(text).replaceAll("$1");
        // 5. NFC: gộp ký tự tổ hợp về dựng sẵn + tự sắp xếp lại dấu sai thứ tự.
        text = Normalizer.normalize(text, Normalizer.Form.NFC);
        // 6. Ba chấm (kể cả ". . ." rời) về một dấu chấm câu rõ ràng.
        text = RE_ELLIPSIS.matcher(text).replaceAll(". ");
        // 7. Xuống dòng -> dấu câu: nhiều dòng = chấm, một dòng = phẩy.
        text = RE_MANY_NEWLINES.matcher(text).replaceAll(". ");
        text = RE_ONE_NEWLINE.matcher(text).replaceAll(", ");
        // 8. Dọn khoảng trắng.
        text = RE_MANY_SPACES.matcher(text).replaceAll(" ");
        text = RE_SPACE_BEFORE_PUNCT.matcher(text).replaceAll("$1");
        // 9. Thêm khoảng trắng sau dấu câu trước CHỮ (không đụng số thập phân 3,5).
        text = RE_PUNCT_BEFORE_LETTER.matcher(text).replaceAll("$1 ");
        // 10. Chuỗi cảm thán/nghi vấn lặp cuối câu thành dấu chấm.
        text = RE_TRAILING_EMPHATIC.matcher(text).replaceAll(".");
        text = RE_DUP_PUNCT.matcher(text).replaceAll("$1");
        return text.trim();
    }
    // ===== KẾT THÚC: logic giống hệt file Kotlin mới =====

    static int passed = 0, failed = 0;

    static void eq(String name, String input, String expected) {
        String actual = prepareForSsml(input);
        boolean ok = expected.equals(actual);
        boolean nfc = actual != null && Normalizer.isNormalized(actual, Normalizer.Form.NFC);
        if (ok && nfc) {
            passed++;
            System.out.println("PASS  " + name);
        } else {
            failed++;
            System.out.println("FAIL  " + name);
            System.out.println("   input   : " + visualize(input));
            System.out.println("   expected: " + visualize(expected));
            System.out.println("   actual  : " + visualize(actual) + (nfc ? "" : "  [KHÔNG NFC!]"));
        }
    }

    static void idempotent(String name, String input) {
        String once = prepareForSsml(input);
        String twice = prepareForSsml(once);
        if (once.equals(twice)) { passed++; System.out.println("PASS  idempotent: " + name); }
        else {
            failed++;
            System.out.println("FAIL  idempotent: " + name);
            System.out.println("   once : " + visualize(once));
            System.out.println("   twice: " + visualize(twice));
        }
    }

    static String visualize(String s) {
        if (s == null) return "null";
        return s.replace("\u00A0", "<NBSP>").replace("\u200B", "<ZWSP>")
                .replace("\u00AD", "<SHY>").replace("\r", "<CR>").replace("\n", "<NL>")
                .replace("\u202F", "<NNBSP>").replace("\u3000", "<IDSP>");
    }

    public static void main(String[] args) {
        // 1. NFD (tổ hợp) -> NFC (dựng sẵn): ca kinh điển "người" bị tách.
        //    NFD đúng của "người" = n g u+horn o+horn+grave i
        eq("NFD nguoi (partial)", "ng\u01B0o\u031B\u0300i", "người");
        eq("NFD nguoi (full decompose)", "ngu\u031Bo\u031B\u0300i", "người");
        eq("NFD mixed", "ng\u01B0\u1EDDi", "người");
        // 2. Dấu gõ sai thứ tự (grave trước horn) -> NFC tự sắp xếp lại
        eq("wrong mark order", "o\u0300\u031B", "ờ");
        eq("right mark order", "o\u031B\u0300", "ờ");
        // 3. Ký tự vô hình chen giữa từ
        eq("ZWSP in word", "ng\u200Bười", "người");
        eq("ZWSP between base and mark", "ngo\u031B\u200B\u0300i", "ngời");
        eq("soft hyphen in word", "ng\u00ADười", "người");
        eq("LRM RLM", "\u200Exin\u200F chào", "xin chào");
        eq("BOM", "\uFEFFChương một", "Chương một");
        // 4. Khoảng trắng Unicode
        eq("NBSP", "xin\u00A0chào", "xin chào");
        eq("narrow NBSP", "xin\u202Fchào", "xin chào");
        eq("ideographic space", "xin\u3000chào", "xin chào");
        // 5. Xuống dòng
        eq("CRLF", "dòng một\r\ndòng hai", "dòng một, dòng hai");
        eq("single NL -> comma", "trời\nđất", "trời, đất");
        eq("multi NL -> period", "đoạn một\n\n\nđoạn hai", "đoạn một. đoạn hai");
        eq("NL with spaces", "  xin  \n  chào  ", "xin, chào");
        // 6. Dấu câu
        eq("space before punct", "trời . đất ,", "trời. đất,");
        eq("missing space after punct", "Trời,đất;con:người!gì?vâng", "Trời, đất; con: người! gì? vâng");
        eq("decimal comma safe", "được 3,5 điểm", "được 3,5 điểm");
        eq("dup punct", "thật!! sao?? ngay..", "thật! sao? ngay.");
        eq("ellipsis char", "khoan… đã", "khoan. đã");
        eq("spaced ellipsis", "khoan . . . đã", "khoan. đã");
        // 7. Dấu tổ hợp lặp (OCR)
        eq("dup combining grave", "a\u0300\u0300", "à");
        // 8. Văn bản sạch đi qua nguyên vẹn
        eq("clean text unchanged",
                "Người lữ hành cô độc bước đi, giữa đêm khuya tĩnh lặng.",
                "Người lữ hành cô độc bước đi, giữa đêm khuya tĩnh lặng.");
        // 9. Tổng hợp bẩn (mô phỏng copy từ web/EPUB)
        String messy = "Ch\u01B0\u031B\u0301ng\u200B 12\u00A0\u00A0Con ng\u01B0\u0300̀i .\r\n\r\nHọ nói:\u201Ckhông\u201D... rồi đi!!";
        // (\u0300̀ có 1 ký tự combining lạc loại trong literal trên — cố tình giữ để test)
        String out = prepareForSsml(messy);
        System.out.println("INFO  messy -> " + visualize(out));
        idempotent("messy paragraph", messy);
        idempotent("NFD + ZWSP", "ng\u01B0\u0300i \u200B đang\n\nđi . . . xa!!");
        idempotent("plain", "Người lữ hành.");

        // 10. Kiểm tra mọi đầu ra của các ca đều là NFC chuẩn (đã gộp trong eq)
        System.out.println("\n===== KẾT QUẢ: " + passed + " pass, " + failed + " fail =====");
        if (failed > 0) System.exit(1);
    }
}
