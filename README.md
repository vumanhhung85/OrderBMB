# WD Cloud (Android)

App Android truy cập thẳng ổ WD qua giao thức chia sẻ thư mục Windows (SMB2/3), không cần dịch vụ My Cloud của WD.

- **Nhiều ổ**: mỗi thư mục chia sẻ trên WD (\\IP\TênShare) là một ổ, tài khoản riêng (tạo trong trang quản trị WD). Nút *Lấy danh sách từ WD* đọc các thư mục chia sẻ; khi thêm mới chọn được nhiều để thêm nhiều ổ cùng lúc. Mật khẩu mã hoá bằng Android Keystore. Cấu hình 1 ổ của bản 1.0 tự chuyển sang.
- **Explorer**: thanh đường dẫn bấm được; thanh nút Lên / Tải lên (tệp, cả thư mục, chụp ảnh) / Tạo mới (thư mục, tệp .txt) / Dán / Sắp xếp. Nhấn giữ để chọn nhiều: Chép, Cắt, Xoá, Gửi, Tải về điện thoại, Đổi tên, Sửa, Mở bằng, Sao chép đường dẫn, Ghim, Thuộc tính. Dán sang ổ khác được; trùng tên thì hỏi Giữ cả hai / Ghi đè / Bỏ qua.
- **Chép trong cùng ổ** dùng chép phía máy chủ (FSCTL_SRV_COPYCHUNK) nên không tốn dữ liệu điện thoại; chuyển trong cùng ổ = đổi tên tức thì. Giữ ngày sửa đổi.
- **Tìm**: lọc ngay trong thư mục, hoặc tìm trong mọi thư mục con; không phân biệt dấu ("bao cao" ra "Báo cáo").
- **Sửa**: tệp văn bản sửa ngay trong app (giữ bảng mã/BOM, ghi tệp tạm rồi thay thế); tệp khác mở bằng app sửa, quay lại thì hỏi lưu đè/lưu bản sao.
- **Nhận file chia sẻ**: Zalo/Thư viện ảnh… → *Chia sẻ* → *Lưu vào WD Cloud*. Tự tải lên sau 3 giây vào nơi lưu gần nhất (ổ + thư mục); *Đổi nơi lưu…* để chọn ổ/thư mục khác.

## Mã nguồn
- Java thuần + thư viện [smbj](https://github.com/hierynomus/smbj) 0.13.0 (BouncyCastle cho NTLM/mã hoá). Không dùng AndroidX.
- `core/` thuần Java (không phụ thuộc Android): `SmbPool` (mỗi ổ một phiên, tự nối lại), `Fs` (liệt kê, chép/chuyển/xoá, tìm, ghi an toàn), `ShareEnum` (NetrShareEnum qua \\PIPE\srvsvc).
- Test `app/src/test/.../FsTest.java` chạy với Samba thật do GitHub Actions dựng (2 thư mục chia sẻ). Log + kết quả test ở Release `wdcloud-log`.
- minSdk 26 (Android 8.0+).

## Build
Push nhánh `wdcloud` (repo OrderBMB) → GitHub Actions build bản **chưa ký** ở Release `wdcloud-apk` (`WDCloud-unsigned.apk`). Ký:

```
python3 tools/sign_apk.py WDCloud-unsigned.apk WDCloud.apk khoa-ky-WDCloud.p12 wdcloud <mật khẩu>
```

Khoá ký **không** để trong repo. Mất khoá thì bản sau phải gỡ app cũ rồi cài lại (mất cài đặt kết nối). Mỗi bản mới tăng `versionCode`/`versionName` trong `app/build.gradle`.

## Lưu ý bảo mật
Ổ WD đang mở cổng 445 ra Internet — nên chuyển sang truy cập qua VPN (Tailscale/WireGuard) khi có thể; app vẫn dùng được bình thường qua VPN (đổi địa chỉ sang IP nội bộ).
