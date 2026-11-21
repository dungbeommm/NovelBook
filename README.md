# Trạm Truyện

Ứng dụng Android đọc truyện trực tuyến, nhập EPUB và nghe truyện bằng giọng đọc tổng hợp. Giao diện dùng Kotlin và Jetpack Compose Material 3.

## Cấu trúc mã nguồn 1.008

Mã ứng dụng nằm trong `app/src/main/java/vn/tramtruyen/reader`. Các gói kiểm thử tương ứng nằm trong `app/src/test/java`.

| Tên cũ | Tên mới | Chức năng |
| --- | --- | --- |
| `ReaderApplication` | `TramTruyenApplication` | Khởi tạo kho dữ liệu, mạng và dịch vụ đọc |
| `MainActivity` | `ReaderActivity` | Màn hình Android chính |
| `BrowserActivity` | `SourceBrowserActivity` | Duyệt trang nguồn và mở chương |
| `PlaybackService` | `ReadingPlaybackService` | Phát nền và tích hợp điều khiển Android |
| `MediaNotifier`, `PlaybackBridge` | `ReadingMediaNotifier`, `ReadingMediaBridge` | Thông báo phát và chuyển sự kiện điều khiển |
| `ui/ReaderApp.kt` | `presentation/ReaderNavigation.kt` | Điều hướng và hộp thoại Compose |
| `ui/screens`, `ui/components`, `ui/theme`, `ui/anim` | `presentation/screens`, `presentation/components`, `presentation/theme`, `presentation/motion` | Màn hình, thành phần dùng chung, giao diện và chuyển động |
| `data` | `model` | Mô hình truyện, chương, tiến độ và định danh |
| `core/ReaderCore.kt` | `engine/ReadingEngine.kt` | API nội bộ cho trình đọc, thư viện và giọng đọc |
| `core/scrapers` | `engine/sources` | Cấu hình và trích xuất nội dung từ các nguồn web |
| `core/update` | `engine/catalog` | Quản lý và cập nhật danh mục bộ phân tích |
| `core/net` | `engine/network` | Cấu hình mạng và proxy |
| `core/epub` | `engine/ebooks` | Nhập sách, đọc mục lục và văn bản EPUB |
| `core/tts` | `engine/speech` | Các nhà cung cấp giọng đọc, mô hình ngoại tuyến và điều phối TTS |

### Thay đổi trong bản 1.008

- Khi chuyển chương, trình phát xóa văn bản cũ và hiện nhãn chương đang tải. Nếu tải thất bại, trình phát giữ thông báo lỗi và nút phát thử lại chính yêu cầu vừa thất bại.
- EPUB dùng lại mục lục đã nạp khi chuyển chương trong cùng cuốn sách. Trình nhập xử lý cả bộ chọn tệp chỉ cung cấp một phần metadata.
- CapCut dùng URL âm thanh trong phản hồi tạo tác vụ khi đã có kết quả. Nếu tác vụ báo xong trước khi có URL, app hỏi lại trạng thái của cùng tác vụ trong số lần giới hạn.
- ID cài đặt Android là `com.novel.reader.app`. Bản 1.008 có version code 131 và có thể cập nhật trên bản 1.007 khi dùng cùng chữ ký.

## Build và kiểm thử

Cần JDK 25 để chạy Gradle, Android SDK 36, Android SDK 37.2 cho module Gecko và Android Build Tools 36.0.0.

```bash
./gradlew testDebugUnitTest standaloneJavaTests lintDebug assembleDebug
```

APK debug xuất ở `app/build/outputs/apk/debug/`. GitHub Actions chạy các bước trên cho push hoặc pull request vào `main` và `master`. Tag bắt đầu bằng `v` chạy quy trình release có chữ ký, kiểm tra căn chỉnh và đăng APK chính cùng split Gecko lên Releases. Quy trình release lấy `ANDROID_KEYSTORE_BASE64`, `ANDROID_STORE_PASSWORD`, `ANDROID_KEY_ALIAS` và `ANDROID_KEY_PASSWORD` từ repository secrets. Khóa API TTS được nhập trong ứng dụng.

Sáng Tác Việt dùng module Gecko khi module có sẵn. Dùng `./gradlew :app:bundleDebug` để tạo AAB chứa module. Google Play phân phối Gecko theo yêu cầu. Khi cài thử ngoài Play, có thể dùng bundletool tạo APK universal đã gộp Gecko từ AAB.

## Nguồn thành phần

- `engine/sources/AufiRules.kt` ghi rõ bộ quy tắc dự phòng được trích từ Aufi 6.0.1. Ghi chú trong mã giữ tên và phiên bản nguồn.
- [Tài liệu bộ phân tích](app/src/main/assets/parser_v4/README_ENGINE_V4.md) ghi các tham chiếu tới Novel Reader 3.3.22 và vBook extensions.
- `com.k2fsa.sherpa.onnx` là package của cầu nối Kotlin với thư viện native sherpa-onnx. Tên lớp và package này được JNI dùng khi chạy.
- Tên các nhà cung cấp, tên miền nguồn truyện, định danh lưu trữ và các tệp bằng chứng lịch sử giữ ý nghĩa gốc.

### Phát hành Gecko tải thêm

1. Quy trình release tạo `TramTruyenReader-v*.apk` và `TramTruyen-Gecko.apk` từ cùng một Android App Bundle với `-PstandaloneGeckoSplit=true`. Thiết lập này giữ các thư viện native và tài nguyên của Gecko trong một split. Hai APK phải đi cùng nhau vì Android kiểm tra package, mã phiên bản và chữ ký khi cài thêm split.
2. Đặt `TramTruyen-Gecko.apk` tại `apk/TramTruyen-Gecko.apk` trong bucket `uri410/NovelReaderApp` trước khi phân phối APK chính tương ứng. Có thể tải lên bằng giao diện Hugging Face hoặc lệnh sau.

   ```bash
   hf buckets cp ./TramTruyen-Gecko.apk hf://buckets/uri410/NovelReaderApp/apk/TramTruyen-Gecko.apk
   ```

3. Cài APK chính rồi vào **Cài đặt → Trình duyệt & kết nối → Tải module Gecko**. Cấp quyền cài từ nguồn ngoài cho Trạm Truyện nếu Android yêu cầu, sau đó quay lại bấm tải và xác nhận cài đặt. Mở lại app nếu Android khởi động lại tiến trình. Nguồn Sáng Tác Việt sẽ dùng Gecko khi cấu hình nguồn yêu cầu.
4. Để kiểm tra trước khi đưa lên kho, cài APK chính trên thiết bị ARM64 rồi chạy `adb install-multiple -p com.novel.reader.app TramTruyen-Gecko.apk`. Dùng `adb shell pm path com.novel.reader.app` để xác nhận có `split_gecko.apk`, sau đó mở một chương Sáng Tác Việt trong app. Cài qua nút trong app trên bản chỉ có APK chính để kiểm tra đầy đủ luồng tải và cấp quyền.
