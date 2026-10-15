package vn.hung.wdcloud.core;

import com.hierynomus.mssmb2.SMBApiException;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

/** Diễn giải lỗi SMB/mạng thành câu tiếng Việt. */
public final class Errors {

    /** Lỗi do đường truyền (nên nối lại rồi thử), khác với lỗi máy chủ trả lời có chủ đích. */
    public static boolean isConnErr(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SMBApiException || t instanceof Job.Cancelled || t instanceof UserError) return false;
        }
        return true;
    }

    /** Lỗi do thao tác của người dùng (không phải lỗi mạng). */
    public static final class UserError extends java.io.IOException {
        public UserError(String m) { super(m); }
    }

    public static String status(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SMBApiException) return String.valueOf(((SMBApiException) t).getStatus());
        }
        return "";
    }

    public static String explain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Job.Cancelled) return "Đã huỷ";
            if (t instanceof UserError) return t.getMessage();
            if (t instanceof SMBApiException) {
                String st = String.valueOf(((SMBApiException) t).getStatus());
                switch (st) {
                    case "STATUS_LOGON_FAILURE": return "Sai tên đăng nhập hoặc mật khẩu";
                    case "STATUS_ACCOUNT_DISABLED":
                    case "STATUS_ACCOUNT_RESTRICTION":
                    case "STATUS_PASSWORD_EXPIRED": return "Tài khoản bị khoá/hạn chế hoặc mật khẩu hết hạn";
                    case "STATUS_BAD_NETWORK_NAME": return "Không có thư mục chia sẻ tên này trên WD — kiểm tra lại tên";
                    case "STATUS_ACCESS_DENIED": return "Tài khoản không có quyền làm việc này (xem phân quyền trong trang quản trị WD)";
                    case "STATUS_OBJECT_NAME_COLLISION": return "Đã có tệp hoặc thư mục trùng tên";
                    case "STATUS_OBJECT_NAME_NOT_FOUND":
                    case "STATUS_OBJECT_PATH_NOT_FOUND": return "Không tìm thấy tệp/thư mục (có thể đã bị xoá hoặc đổi tên)";
                    case "STATUS_OBJECT_NAME_INVALID": return "Tên có ký tự không hợp lệ";
                    case "STATUS_DIRECTORY_NOT_EMPTY": return "Thư mục không trống";
                    case "STATUS_SHARING_VIOLATION": return "Tệp đang được mở ở máy khác";
                    case "STATUS_DISK_FULL": return "Ổ WD đã đầy";
                    case "STATUS_FILE_IS_A_DIRECTORY": return "Đây là thư mục, không phải tệp";
                    case "STATUS_NOT_A_DIRECTORY": return "Đây là tệp, không phải thư mục";
                    case "STATUS_CANNOT_DELETE": return "Không xoá được (tệp chỉ đọc hoặc đang dùng)";
                    default: return "Máy chủ báo lỗi: " + st;
                }
            }
            if (t instanceof UnknownHostException) return "Không tìm thấy máy chủ — kiểm tra lại địa chỉ";
            if (t instanceof ConnectException || t instanceof NoRouteToHostException)
                return "Không kết nối được tới WD — kiểm tra mạng điện thoại, địa chỉ IP, hoặc nhà mạng chặn cổng 445";
            if (t instanceof SocketTimeoutException) return "Hết thời gian chờ — mạng chậm hoặc WD không phản hồi";
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("smbv1") || low.contains("smb1") || low.contains("negotiat"))
            return "WD chỉ đang bật SMB1 (cũ). Vào trang quản trị WD, mục Windows Services/SMB, bật SMB2 hoặc SMB3. (" + m + ")";
        return m.isEmpty() ? "Lỗi: " + e.getClass().getSimpleName() : m;
    }

    private Errors() {}
}
