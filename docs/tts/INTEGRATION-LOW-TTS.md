# Tích hợp giọng low bên cạnh các giọng cũ

## Sửa lại phạm vi

Bản low-only trước đã hiểu sai yêu cầu và tắt giọng cũ. Bản hiện tại khôi phục đầy đủ các provider/danh mục từ ZIP gốc, chỉ bổ sung model low và các sửa lỗi an toàn khi nạp ONNX.

### Provider và cấu hình

- Android TTS, Google Translate TTS, Edge và CapCut được khởi tạo và có trong danh sách chọn giọng như trước.
- Gemini/Zalo được nạp khi có API key trong cấu hình; các thẻ nhập key trong Cài đặt được khôi phục. Chưa kiểm tra dịch vụ mạng hoặc API key cá nhân.
- Giữ nguyên giọng được chọn trong cài đặt, hồ sơ từng truyện và backup (kể cả `android:default`, các ID cloud và ID offline cũ). Chỉ dùng mặc định Google `gtts:vi` cho cấu hình thiếu/sai định dạng, giống mặc định ZIP gốc.
- Không xóa API key, giọng nhập hoặc model đã cài. Nếu phiên bản low-only đã ghi đè cấu hình trên điện thoại thì mã nguồn mới không thể đoán lại lựa chọn trước khi bị ghi đè: chọn lại giọng hoặc khôi phục backup.

### Danh mục offline

- Giữ nguyên 24 model, URL, checksum và ID từ ZIP gốc, bao gồm Mỹ Tâm `mytam2` và `mytam2794` không có bản low tương ứng.
- Thêm 22 cặp ONNX/JSON từ `dungbeommm/NovelReader_voices/voices/low`, commit `6cc9c32ff567f305df0fba4e04a47b37d02c9511`.
- Tổng cộng 46 model trong danh mục tải. Không có nghĩa tải sẵn 46 giọng; giọng offline chỉ xuất hiện trong màn chọn sau khi cài model.
- ID mới dùng tiền tố `low-` và tên có đuôi “· low”, không chiếm ID model cũ.
- Low tải trực tiếp raw.githubusercontent.com (không qua CDN trong voice_info.json). Đã tải kiểm tra đầy đủ 22 ONNX (kích thước, Git blob ID, SHA-256), parse và tính SHA-256 cho JSON. Bộ cài kiểm tra hash trước khi chuẩn hóa metadata.
- URL/checksum 24 model cũ được giữ đúng ZIP gốc; chưa tải/kiểm lại dịch vụ lưu trữ của cả 24 model trong lần khôi phục này.

### Runtime và an toàn

- Giọng offline tiếp tục dùng Sherpa-ONNX/VITS/Piper; giọng online vẫn dùng provider cũ, không bị đổi thành giọng offline.
- Dữ liệu eSpeak dùng `vbook-settings/tts/tts-data.zip`, ghim commit `1d39a0e21927dc83bcae5c4f39995df77d660b7c`, SHA-256 `000f96193a48015b865cfb67616ad256b613a466aead6f2d3f182a14ff4a5015`.
- Streaming rewrite metadata ONNX thay khóa cũ, không nối khóa trùng; giữ graph/tensor và các metadata khác. Marker v4 sửa các bản chuyển cũ khi cần.
- Tổng hợp offline không cắt cụt văn bản ở ký tự 500. Reader vẫn chia đoạn theo chính sách hiện có.
- Xóa model dùng cùng mutex với JNI generate; dừng playback nếu xóa giọng đang chọn. Lỗi local không bị retry ba lần.
- Cache/retry CapCut được khôi phục như cũ. Cloud provider/API key vẫn do cấu hình trước đây kiểm soát.
- Giữ nguyên applicationId, versionCode/versionName, phần vuốt và animation không liên quan.

## Cách dùng

1. Cài APK hoặc build mã nguồn.
2. Google/Edge/CapCut/Android chọn trong danh sách giọng như trước; Gemini/Zalo nhập API key tại Cài đặt → Âm thanh & giọng đọc.
3. Muốn thêm low: cài trình chạy TTS nếu chưa có, chọn Tải từ mạng, tìm giọng có đuôi low và tải (~61 MiB/model, cần thêm dung lượng tạm). Tải xong chọn giọng vừa cài; cũng có nút Dùng cho giọng đã cài.
4. Giọng offline đọc trên thiết bị sau tải. Giọng online vẫn cần mạng, nguồn truyện online vẫn có thể cần mạng.

## Phần VModel chưa hoàn tất

- `vbook-settings/ai-tts/models.json` có các gói VModel. Bản tham chiếu lưu tại `vbook-ai-models-reference.json`, không phải danh mục giọng chạy được.
- Gói Ban Mai trong catalog chứa `banmai.vmodel` + `config.json`, header `VMD1` không phải ONNX ModelProto. Chưa được cung cấp SDK/runtime hoặc đặc tả tương thích để nạp VMD1.
- `ai-tts/data.zip` chứa `en-word_id.bin`, `vi-word_id.bin`, `non-vietnamese-words.csv`, `sea_g2p.bin`, không có `espeak-ng-data`; không dùng thay tài nguyên eSpeak.
- ZIP VModel bị từ chối với thông báo cần runtime riêng, không đổi đuôi thành ONNX và không khẳng định đã hỗ trợ VModel.

Cần mã nguồn/SDK VMD1, API tổng hợp audio, frontend tương ứng và giấy phép phù hợp để hoàn tất phần này.

## Kiểm thử và giới hạn

- Xem `verification/RESTORED-VOICES-REPORT.md`, log build và XML unit test của bản hiện tại.
- Test bảo đảm không đổi ID provider cũ/ID offline cũ, giữ giọng import/low; test metadata ONNX sửa key trùng, giữ payload lớn và giữ file gốc khi lỗi.
- Ban Mai đã được chuyển bằng mã Kotlin thực tế, qua ONNX checker và sinh WAV trên Linux CPU (sherpa-onnx 1.13.8). Không thay thế QA Android JNI hay kiểm tra chất lượng nghe.
- Chưa chạy trực tiếp trên Android; chưa kiểm tra API online hoặc khả năng tải lại của 24 URL model cũ.
- APK debug có thể không cài đè được bản khác chữ ký. Giữ nguyên versionCode/versionName, không phải bản release tăng phiên bản. Sao lưu trước khi thử.
