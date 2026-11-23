<h1 align="center">NovelBook</h1>

<p align="center">
  <a href="https://github.com/dungbeommm/NovelBook/releases/tag/v0.02-beta"><img src="https://img.shields.io/github/v/release/dungbeommm/NovelBook?include_prereleases&style=for-the-badge&label=Beta&color=7c5cff" alt="Latest beta release"></a>
  <a href="LICENSE"><img src="https://img.shields.io/github/license/dungbeommm/NovelBook?style=for-the-badge&label=License&color=0e75b6" alt="License"></a>
</p>

<p align="center"><strong>Đọc truyện theo cách của bạn.</strong></p>

<p align="center">
Đọc truyện trực tuyến, nhập sách EPUB và nghe truyện bằng giọng đọc tổng hợp, trong một ứng dụng Android dùng Kotlin và Jetpack Compose.
</p>

<p align="center"><a href="https://github.com/dungbeommm/NovelBook/releases/tag/v0.02-beta"><strong>⬇ Tải NovelBook beta mới nhất</strong></a></p>

> [!NOTE]
> NovelBook đang ở giai đoạn beta. Bản hiện tại là **0.10 beta**.

## Tính năng

| | |
|---|---|
| 📚 **Đọc truyện trực tuyến** | Duyệt các nguồn truyện, mở chương và lưu tiến độ đọc. |
| 📖 **Đọc sách EPUB** | Nhập sách EPUB, xem mục lục và tiếp tục đọc chương đang dở. |
| 🔊 **Nghe truyện** | Phát giọng đọc tổng hợp, nghe khi chuyển ứng dụng và điều khiển từ thông báo Android. |
| 🗣️ **Giọng đọc ngoại tuyến** | Tải model giọng VBook Piper, ViFast hoặc VieNeu để nghe trên thiết bị. Cần nhập runtime từ tệp `.apks` của VBook. |
| 🎨 **Giao diện Android hiện đại** | Jetpack Compose Material 3, hỗ trợ giao diện sáng và tối. |
| 🌐 **Nguồn web mở rộng** | Có thể cập nhật bộ phân tích trang và tải module Gecko cho những nguồn cần trình duyệt tương thích. |

## Tải xuống

Tải APK Android tại [GitHub Releases](https://github.com/dungbeommm/NovelBook/releases/tag/v0.02-beta).

Bản beta hiện tại: [NovelBook-v0.02-beta.apk](https://github.com/dungbeommm/NovelBook/releases/download/v0.02-beta/NovelBook-v0.02-beta.apk)

Thiết bị cần Android 8.0 trở lên. APK hiện được build cho ARM64.

## Build

Cần JDK 25, Android SDK 36, Android SDK 37.2 cho module Gecko và Android Build Tools 36.0.0.

```bash
./gradlew testDebugUnitTest standaloneJavaTests lintDebug assembleDebug
```

APK debug xuất tại `app/build/outputs/apk/debug/`. GitHub Actions kiểm tra các thay đổi trên `main` và tạo bản phát hành có ký khi push tag dạng `v*`. Ví dụ phiên bản `0.02 beta` dùng tag `v0.02-beta`.

## Cấu trúc mã nguồn

Mã ứng dụng nằm trong `app/src/main/java/vn/tramtruyen/reader`. Ứng dụng dùng `com.novel.reader.app` làm application ID.

| Gói | Chức năng |
|---|---|
| `presentation` | Màn hình, điều hướng, giao diện và chuyển động Compose |
| `model` | Dữ liệu truyện, chương, tiến độ và định danh |
| `engine/sources` | Cấu hình và trích xuất nội dung từ các nguồn web |
| `engine/ebooks` | Nhập sách và đọc EPUB |
| `engine/speech` | Giọng đọc trực tuyến, model VBook đã tải và phát âm thanh |
| `engine/catalog` | Cài đặt và cập nhật bộ phân tích, module Gecko và extension |

## Module Gecko

Một số nguồn truyện dùng module Gecko để hiển thị trang và xử lý nội dung. Trong ứng dụng, mở **Cài đặt → Trình duyệt & kết nối → Tải module Gecko**. Android có thể yêu cầu cấp quyền cài ứng dụng từ nguồn ngoài.

Bản release tạo APK chính và APK Gecko riêng. Hai tệp cần cùng phiên bản và chữ ký khi cài thủ công. GitHub Actions tải module Gecko lên bucket công khai để ứng dụng có thể tải theo phiên bản.

## Nguồn thành phần

- [Tài liệu bộ phân tích](app/src/main/assets/parser_v4/README_ENGINE_V4.md) ghi thông tin và tham chiếu của bộ phân tích.
- `engine/sources/AufiRules.kt` ghi chú nguồn gốc của bộ quy tắc dự phòng Aufi.
- Danh mục model VBook lấy từ [vbook-settings](https://github.com/Darkrai9x/vbook-settings); runtime engine được người dùng nhập từ tệp `.apks`.

---

<p align="center">Được xây dựng để việc đọc và nghe truyện liền mạch hơn.</p>
