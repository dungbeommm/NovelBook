import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;

/** Port 1:1 logic CapcutTtsProtocol (Kotlin) sang Java để kiểm thử thật. */
public class CapcutProtocolTest {

    // ===== BẮT ĐẦU: logic giống hệt Protocol Kotlin mới =====
    enum CapcutTaskState { WAITING, SUCCEEDED, FAILED }
    static final java.util.Set<String> SUCCESS_CODES =
            new java.util.HashSet<>(java.util.Arrays.asList("", "0", "200", "ok", "success", "succeed"));
    static final int DEVICE_ID_LENGTH = 20;

    static CapcutTaskState classifyStatus(String raw) {
        if (raw == null) return CapcutTaskState.WAITING;
        switch (raw.trim().toLowerCase()) {
            case "succeed": case "success": case "succeeded": case "done": case "completed":
                return CapcutTaskState.SUCCEEDED;
            case "fail": case "failed": case "error": case "cancelled": case "canceled":
                return CapcutTaskState.FAILED;
            default:
                return CapcutTaskState.WAITING;
        }
    }

    static boolean isBlocked(String ret, String errmsg) {
        String code = ret == null ? "" : ret.trim();
        return "-6".equals(code)
                || (errmsg != null && errmsg.toLowerCase().contains("shark"));
    }

    static String apiError(String ret, String errmsg) {
        String code = ret == null ? "" : ret.trim();
        if (SUCCESS_CODES.contains(code)) return null;
        String detail = (errmsg == null || errmsg.trim().isEmpty()) ? "không có mô tả" : errmsg.trim();
        if (isBlocked(code, detail)) {
            return "CapCut đã chặn yêu cầu không chính thức (ret=-6: " + detail
                    + "). Hãy chờ rồi thử lại hoặc tạm dùng Edge/Android TTS.";
        }
        return "CapCut API lỗi " + code + ": " + detail;
    }

    static boolean isValidDeviceId(String value) {
        if (value == null || value.length() != DEVICE_ID_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) if (!Character.isDigit(value.charAt(i))) return false;
        return true;
    }

    static String newDeviceId(Random random) {
        StringBuilder sb = new StringBuilder(DEVICE_ID_LENGTH);
        sb.append("76");
        for (int i = 0; i < DEVICE_ID_LENGTH - 2; i++) sb.append(random.nextInt(10));
        return sb.toString();
    }

    static boolean isSuspiciouslyShort(int textLength, long durationMs) {
        if (textLength < 60 || durationMs <= 0L) return false;
        double estimatedMs = textLength * 1000.0 / 14.0;
        long safeFloorMs = (long) Math.max(900.0, estimatedMs * 0.22);
        return durationMs < safeFloorMs;
    }

    static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    static String buildSsml(String text, String voice, String resourceId, String rate) {
        String voiceBlock = "    <voice name=\"" + voice + "\" mock_tone_info=\"\" platform=\"sami\" "
                + "resource_id=\"" + resourceId + "\" emotion=\"\" emotion_scale=\"0\" style=\"\" role=\"\" "
                + "moyin_emotion=\"\" is_clone_tone=\"false\" need_subtitle_timestamp=\"false\">\n"
                + "        <prosody rate=\"" + rate + "\">" + escapeXml(text) + "</prosody>\n"
                + "    </voice>";
        return "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" "
                + "xml:lang=\"en-US\">\n" + voiceBlock + "\n</speak>";
    }

    static String payloadSignInput(String ssmlMd5Hex, String extraInfo, String deviceId, String appId) {
        return "appid:" + appId + "&did:" + deviceId + "&creditDisable:false&ssml:"
                + ssmlMd5Hex + "&extraInfo:" + extraInfo;
    }

    static String signHeaderInput(String url, String appVersion, String deviceTime, String tdid) {
        String path = url.split("\\?", 2)[0];
        String tail = path.length() >= 7 ? path.substring(path.length() - 7) : path;
        return "9e2c|" + tail + "|3|" + appVersion + "|" + deviceTime + "|" + tdid + "|11ac";
    }

    static String traceId(String uuidHex32) {
        String seed = uuidHex32.substring(0, Math.min(32, uuidHex32.length()));
        return "00-" + seed + "-" + seed.substring(0, Math.min(16, seed.length())) + "-01";
    }

    static final java.util.List<String> COMMON_QUERY_KEYS = java.util.Arrays.asList(
            "app_name", "device_type", "os_version", "channel", "version_name", "device_brand",
            "device_id", "iid", "version_code", "device_platform", "aid");

    static String buildCommonQuery(Map<String, String> device, String babiParam, boolean includeRegion) {
        LinkedHashMap<String, String> query = new LinkedHashMap<>();
        for (String key : COMMON_QUERY_KEYS) query.put(key, device.get(key));
        if (includeRegion) query.put("region", device.get("region"));
        if (babiParam != null) query.put("babi_param", babiParam);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(e.getKey()).append('=').append(URLEncoder.encode(e.getValue(), java.nio.charset.StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    static boolean looksLikeSupportedAudio(byte[] bytes) {
        if (bytes == null || bytes.length < 4) return false;
        // MP3: ID3 tag hoặc frame sync 0xFFEx; WAV: RIFF header.
        boolean id3 = bytes[0] == 'I' && bytes[1] == 'D' && bytes[2] == '3';
        boolean frameSync = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xE0) == 0xE0;
        boolean riff = bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F';
        return id3 || frameSync || riff;
    }
    // ===== KẾT THÚC =====

    static int passed = 0, failed = 0;
    static void check(String name, boolean cond) {
        if (cond) { passed++; System.out.println("PASS  " + name); }
        else { failed++; System.out.println("FAIL  " + name); }
    }
    static void eq(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) { passed++; System.out.println("PASS  " + name); }
        else {
            failed++;
            System.out.println("FAIL  " + name + "\n   expected: " + expected + "\n   actual  : " + actual);
        }
    }

    public static void main(String[] a) throws Exception {
        // classifyStatus
        eq("status succeed", CapcutTaskState.SUCCEEDED, classifyStatus("succeed"));
        eq("status SUCCESS caps", CapcutTaskState.SUCCEEDED, classifyStatus(" SUCCESS "));
        eq("status done", CapcutTaskState.SUCCEEDED, classifyStatus("done"));
        eq("status failed", CapcutTaskState.FAILED, classifyStatus("failed"));
        eq("status cancelled", CapcutTaskState.FAILED, classifyStatus("cancelled"));
        eq("status running -> waiting", CapcutTaskState.WAITING, classifyStatus("running"));
        eq("status null -> waiting", CapcutTaskState.WAITING, classifyStatus(null));

        // apiError
        eq("ret 0 ok", null, apiError("0", null));
        eq("ret 200 ok", null, apiError("200", "anything"));
        eq("ret empty ok", null, apiError(null, null));
        check("ret -6 blocked msg", apiError("-6", "deny").contains("chặn yêu cầu không chính thức"));
        check("shark blocked msg", apiError("-1", "shark detect").contains("chặn yêu cầu không chính thức"));
        eq("generic error", "CapCut API lỗi 40005: quota", apiError("40005", "quota"));
        eq("no desc", "CapCut API lỗi 13: không có mô tả", apiError("13", "  "));
        check("isBlocked trim", isBlocked(" -6 ", null));
        check("not blocked", !isBlocked("40005", "quota"));

        // device id
        check("valid 20 digits", isValidDeviceId("76123456789012345678"));
        check("19 digits invalid", !isValidDeviceId("7612345678901234567"));
        check("letters invalid", !isValidDeviceId("7612345678901234567a"));
        check("null invalid", !isValidDeviceId(null));
        String gen = newDeviceId(new Random(42));
        check("generated 20 digits start 76", gen.length() == 20 && gen.startsWith("76") && gen.matches("\\d+"));

        // isSuspiciouslyShort (PlayerViewModel phụ thuộc hành vi này)
        check("short text exempt", !isSuspiciouslyShort(10, 100));
        check("zero duration exempt", !isSuspiciouslyShort(200, 0));
        check("normal audio ok", !isSuspiciouslyShort(200, 4000));
        check("truncated detected", isSuspiciouslyShort(200, 1500));
        check("boundary floor 900", isSuspiciouslyShort(120, 900));

        // escapeXml + buildSsml
        eq("escapeXml", "a &amp; &lt;b&gt; &quot;c&quot; &apos;d&apos;", escapeXml("a & <b> \"c\" 'd'"));
        String ssml = buildSsml("Xin chào & hẹn <gặp>", "BV421_vivn_streaming", "7252594014782755330", "1.0");
        eq("ssml exact",
            "<speak version=\"1.0\" xmlns=\"http://www.w3.org/2001/10/synthesis\" xml:lang=\"en-US\">\n"
            + "    <voice name=\"BV421_vivn_streaming\" mock_tone_info=\"\" platform=\"sami\" "
            + "resource_id=\"7252594014782755330\" emotion=\"\" emotion_scale=\"0\" style=\"\" role=\"\" "
            + "moyin_emotion=\"\" is_clone_tone=\"false\" need_subtitle_timestamp=\"false\">\n"
            + "        <prosody rate=\"1.0\">Xin chào &amp; hẹn &lt;gặp&gt;</prosody>\n"
            + "    </voice>\n</speak>",
            ssml);

        // payloadSignInput
        eq("payload sign input",
            "appid:359289&did:76123456789012345678&creditDisable:false&ssml:900150983cd24fb0d6963f7d28e17f72&extraInfo:{\"benefit_info\":{}}",
            payloadSignInput("900150983cd24fb0d6963f7d28e17f72", "{\"benefit_info\":{}}", "76123456789012345678", "359289"));

        // signHeaderInput: tail 7 ký tự của URL trước '?'
        eq("sign input new",
            "9e2c|ask/new|3|8.7.0|1754500000|76123456789012345678|11ac",
            signHeaderInput("https://editor-api-sg.capcutapi.com/lv/v1/common_task/new?x=1", "8.7.0", "1754500000", "76123456789012345678"));
        eq("sign input query",
            "9e2c|k/query|3|8.7.0|1754500000|76123456789012345678|11ac",
            signHeaderInput("https://editor-api-sg.capcutapi.com/lv/v1/common_task/query", "8.7.0", "1754500000", "76123456789012345678"));

        // traceId
        eq("trace id", "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
            traceId("0123456789abcdef0123456789abcdef"));

        // buildCommonQuery: thứ tự khóa + URL-encode + region/babi
        LinkedHashMap<String, String> device = new LinkedHashMap<>();
        device.put("app_name", "CapCut"); device.put("device_type", "MacBookPro17,4");
        device.put("os_version", "15.7.4"); device.put("channel", "capcutpc_google");
        device.put("version_name", "8.7.0"); device.put("device_brand", "MacBookPro17,4");
        device.put("device_id", "76123456789012345678"); device.put("iid", "76123456789012345678");
        device.put("version_code", "8.7.0"); device.put("device_platform", "mac");
        device.put("aid", "359289"); device.put("region", "VN");
        eq("common query full",
            "app_name=CapCut&device_type=MacBookPro17%2C4&os_version=15.7.4&channel=capcutpc_google"
            + "&version_name=8.7.0&device_brand=MacBookPro17%2C4&device_id=76123456789012345678"
            + "&iid=76123456789012345678&version_code=8.7.0&device_platform=mac&aid=359289&region=VN&babi_param=%7B%7D",
            buildCommonQuery(device, "{}", true));
        eq("common query no region no babi",
            "app_name=CapCut&device_type=MacBookPro17%2C4&os_version=15.7.4&channel=capcutpc_google"
            + "&version_name=8.7.0&device_brand=MacBookPro17%2C4&device_id=76123456789012345678"
            + "&iid=76123456789012345678&version_code=8.7.0&device_platform=mac&aid=359289",
            buildCommonQuery(device, null, false));

        // looksLikeSupportedAudio
        check("mp3 ID3", looksLikeSupportedAudio(new byte[]{'I','D','3',4}));
        check("mp3 frame sync", looksLikeSupportedAudio(new byte[]{(byte)0xFF,(byte)0xFB,0,0}));
        check("wav RIFF", looksLikeSupportedAudio(new byte[]{'R','I','F','F'}));
        check("json not audio", !looksLikeSupportedAudio("{\"a\":1}".getBytes()));
        check("empty not audio", !looksLikeSupportedAudio(new byte[0]));

        System.out.println("\n===== KẾT QUẢ: " + passed + " pass, " + failed + " fail =====");
        if (failed > 0) System.exit(1);
    }
}
