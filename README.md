# WD Cloud (Android)

App Android truy cập thẳng ổ WD qua giao thức chia sẻ thư mục Windows (SMB2/3), không cần dịch vụ My Cloud của WD.

- **Cài đặt kết nối**: địa chỉ (`1.2.3.4` hoặc `\\1.2.3.4\TênShare`), tên thư mục chia sẻ, tài khoản, mật khẩu (mã hoá bằng Android Keystore, chỉ lưu trong máy). Có nút *Kiểm tra kết nối*.
- **Duyệt file**: mở, gửi qua Zalo/app khác, lưu về điện thoại, tải lên (chọn nhiều tệp), tạo thư mục, đổi tên, xoá. Nhấn giữ một dòng để hiện các thao tác.
- **Nhận file chia sẻ**: trong Zalo/Thư viện ảnh… bấm *Chia sẻ* → *Lưu vào WD Cloud*. Mặc định tự tải lên sau 3 giây vào thư mục đặt trong Cài đặt (dùng lại thư mục gần nhất); bấm *Đổi thư mục…* để chọn nơi khác. Trùng tên thì tự đặt `ten (1).jpg`. Chia sẻ đoạn chữ/đường link thì lưu thành `GhiChu_….txt`.

## Mã nguồn
- Java thuần + thư viện [smbj](https://github.com/hierynomus/smbj) 0.13.0 (BouncyCastle cho NTLM/mã hoá). Không dùng AndroidX.
- `Smb.java` giữ một kết nối dùng chung, tự nối lại khi rớt hoặc để yên quá 4 phút.
- minSdk 26 (Android 8.0+).

## Build
Push nhánh `wdcloud` (repo OrderBMB) → GitHub Actions build bản **chưa ký** ở Release `wdcloud-apk` (`WDCloud-unsigned.apk`). Ký:

```
python3 tools/sign_apk.py WDCloud-unsigned.apk WDCloud.apk khoa-ky-WDCloud.p12 wdcloud <mật khẩu>
```

Khoá ký **không** để trong repo. Mất khoá thì bản sau phải gỡ app cũ rồi cài lại (mất cài đặt kết nối). Mỗi bản mới tăng `versionCode`/`versionName` trong `app/build.gradle`.

## Lưu ý bảo mật
Ổ WD đang mở cổng 445 ra Internet — nên chuyển sang truy cập qua VPN (Tailscale/WireGuard) khi có thể; app vẫn dùng được bình thường qua VPN (đổi địa chỉ sang IP nội bộ).
