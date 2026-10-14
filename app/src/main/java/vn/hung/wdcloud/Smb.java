package vn.hung.wdcloud;

import android.content.Context;
import android.webkit.MimeTypeMap;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Kết nối SMB (giao thức thư mục chia sẻ Windows) tới WD. Giữ một kết nối dùng chung, tự nối lại khi rớt. */
final class Smb {
    static final ExecutorService BG = Executors.newCachedThreadPool();
    private static final long IDLE_RESET_MS = 4 * 60 * 1000L;

    static final class Entry {
        String name, path;
        boolean dir;
        long size, mtime;
    }

    interface Op<T> { T run(DiskShare s) throws Exception; }

    /** Báo tiến độ; trả về false để huỷ. */
    interface Progress { boolean on(long done); }

    static final class Cancelled extends IOException {
        Cancelled() { super("Đã huỷ"); }
    }

    private static final class Opened implements Closeable {
        SMBClient client;
        Connection conn;
        Session session;
        DiskShare share;

        @Override public void close() {
            try { if (share != null) share.close(); } catch (Exception ignored) {}
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
            try { if (client != null) client.close(); } catch (Exception ignored) {}
        }
    }

    private static Opened cur;
    private static String curKey;
    private static long lastUse;
    private static int busy;

    private static SmbConfig config() {
        return SmbConfig.builder()
                .withSecurityProvider(new BCSecurityProvider())
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    private static Opened open(Cfg.Conf k) throws IOException {
        Opened o = new Opened();
        try {
            o.client = new SMBClient(config());
            o.conn = o.client.connect(k.host, k.port);
            AuthenticationContext ac = k.user.isEmpty()
                    ? new AuthenticationContext("Guest", new char[0], k.domain)
                    : new AuthenticationContext(k.user, k.pass.toCharArray(), k.domain);
            o.session = o.conn.authenticate(ac);
            Share sh = o.session.connectShare(k.share);
            if (!(sh instanceof DiskShare)) {
                try { sh.close(); } catch (Exception ignored) {}
                throw new IOException("\"" + k.share + "\" không phải thư mục chia sẻ chứa file");
            }
            o.share = (DiskShare) sh;
            return o;
        } catch (IOException | RuntimeException e) {
            o.close();
            throw e;
        }
    }

    /** Thử kết nối với thông tin chưa lưu; trả về số mục ở thư mục gốc. */
    static int test(Cfg.Conf k) throws IOException {
        try (Opened o = open(k)) {
            int n = 0;
            for (FileIdBothDirectoryInformation i : o.share.list("")) {
                String name = i.getFileName();
                if (!".".equals(name) && !"..".equals(name)) n++;
            }
            return n;
        }
    }

    private static boolean alive(Opened o) {
        try { return o.conn.isConnected() && o.share.isConnected(); } catch (Exception e) { return false; }
    }

    private static synchronized DiskShare share(Context c) throws IOException {
        Cfg.Conf k = Cfg.load(c);
        if (!k.ready()) throw new IOException("Chưa cài đặt kết nối — vào Cài đặt để nhập địa chỉ, tên thư mục chia sẻ, tài khoản");
        long now = System.currentTimeMillis();
        if (cur != null && busy == 0 && (!k.key().equals(curKey) || now - lastUse > IDLE_RESET_MS || !alive(cur))) {
            cur.close();
            cur = null;
        }
        if (cur == null) {
            cur = open(k);
            curKey = k.key();
        }
        lastUse = now;
        return cur.share;
    }

    static synchronized void reset() {
        if (cur != null) cur.close();
        cur = null;
    }

    private static synchronized void dropBroken() {
        if (cur != null && (busy == 0 || !alive(cur))) {
            cur.close();
            cur = null;
        }
    }

    private static <T> T once(Context c, Op<T> op) throws Exception {
        DiskShare s;
        synchronized (Smb.class) {
            s = share(c);
            busy++;
        }
        try {
            return op.run(s);
        } finally {
            synchronized (Smb.class) {
                busy--;
                lastUse = System.currentTimeMillis();
            }
        }
    }

    /** Chạy thao tác; nếu lỗi do kết nối (không phải lỗi máy chủ trả về) thì nối lại và thử thêm 1 lần. */
    static <T> T run(Context c, Op<T> op) throws Exception {
        try {
            return once(c, op);
        } catch (Exception e) {
            if (!isConnErr(e)) throw e;
            dropBroken();
            return once(c, op);
        }
    }

    /** Chạy thao tác không thử lại (dùng cho truyền dữ liệu, vì thử lại có thể ghi trùng). */
    static <T> T runOnce(Context c, Op<T> op) throws Exception {
        try {
            return once(c, op);
        } catch (Exception e) {
            if (isConnErr(e)) dropBroken();
            throw e;
        }
    }

    private static boolean isConnErr(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SMBApiException || t instanceof Cancelled) return false;
        }
        return true;
    }

    // ---------- Thao tác ----------

    static String join(String dir, String name) { return dir.isEmpty() ? name : dir + "\\" + name; }

    static String parent(String p) {
        int i = p.lastIndexOf('\\');
        return i < 0 ? "" : p.substring(0, i);
    }

    static List<Entry> list(Context c, final String dir, final boolean foldersOnly) throws Exception {
        return run(c, s -> {
            List<Entry> out = new ArrayList<>();
            long dirFlag = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue();
            long hidFlag = FileAttributes.FILE_ATTRIBUTE_HIDDEN.getValue();
            for (FileIdBothDirectoryInformation i : s.list(dir)) {
                String name = i.getFileName();
                if (name == null || ".".equals(name) || "..".equals(name)) continue;
                long a = i.getFileAttributes();
                if ((a & hidFlag) != 0 || name.startsWith(".") || name.startsWith("$")) continue;
                Entry e = new Entry();
                e.name = name;
                e.path = join(dir, name);
                e.dir = (a & dirFlag) != 0;
                if (foldersOnly && !e.dir) continue;
                e.size = i.getEndOfFile();
                try { e.mtime = i.getLastWriteTime().toEpochMillis(); } catch (Exception ignored) {}
                out.add(e);
            }
            final Collator col = Collator.getInstance(new Locale("vi", "VN"));
            Collections.sort(out, (x, y) -> {
                if (x.dir != y.dir) return x.dir ? -1 : 1;
                return col.compare(x.name, y.name);
            });
            return out;
        });
    }

    static void mkdirs(DiskShare s, String dir) {
        if (dir.isEmpty()) return;
        String acc = "";
        for (String part : dir.split("\\\\")) {
            if (part.isEmpty()) continue;
            acc = acc.isEmpty() ? part : acc + "\\" + part;
            if (!s.folderExists(acc)) s.mkdir(acc);
        }
    }

    static boolean exists(DiskShare s, String p) {
        try { return s.fileExists(p) || s.folderExists(p); } catch (SMBApiException e) { return false; }
    }

    /** Tên chưa có trong thư mục: "anh.jpg" → "anh (1).jpg" nếu trùng. */
    static String uniquePath(DiskShare s, String dir, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        String p = join(dir, name);
        for (int i = 1; exists(s, p) && i < 10000; i++) p = join(dir, base + " (" + i + ")" + ext);
        return p;
    }

    static void mkdir(Context c, final String path) throws Exception {
        run(c, s -> { s.mkdir(path); return null; });
    }

    static void delete(Context c, final Entry e) throws Exception {
        run(c, s -> {
            if (e.dir) s.rmdir(e.path, true); else s.rm(e.path);
            return null;
        });
    }

    static void rename(Context c, final String from, final String to) throws Exception {
        run(c, s -> {
            DiskEntry d = s.open(from, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
            try { d.rename(to); } finally { d.close(); }
            return null;
        });
    }

    /** Tải 1 file từ WD ra luồng ghi. */
    static void download(Context c, final String path, final OutputStream out, final Progress p) throws Exception {
        run(c, s -> s.fileExists(path)); // kiểm tra (và nối lại nếu cần) trước khi truyền
        runOnce(c, s -> {
            com.hierynomus.smbj.share.File f = s.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
            try (InputStream in = f.getInputStream()) {
                copy(in, out, p);
            } finally {
                f.close();
            }
            return null;
        });
    }

    /** Ghi luồng vào file mới trên WD; lỗi giữa chừng thì xoá file dở. */
    static void uploadStream(DiskShare s, String path, InputStream in, Progress p) throws IOException {
        com.hierynomus.smbj.share.File f = s.openFile(path, EnumSet.of(AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_CREATE, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
        boolean ok = false;
        try {
            try (OutputStream out = f.getOutputStream()) {
                copy(in, out, p);
            }
            ok = true;
        } finally {
            try { f.close(); } catch (Exception ignored) {}
            if (!ok) {
                try { s.rm(path); } catch (Exception ignored) {}
            }
        }
    }

    static long copy(InputStream in, OutputStream out, Progress p) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (p != null && !p.on(done)) throw new Cancelled();
        }
        out.flush();
        return done;
    }

    // ---------- Tiện ích ----------

    static String mime(String name) {
        int d = name.lastIndexOf('.');
        if (d < 0) return "application/octet-stream";
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substring(d + 1).toLowerCase(Locale.ROOT));
        return t == null ? "application/octet-stream" : t;
    }

    static String icon(Entry e) {
        if (e.dir) return "📁";
        String m = mime(e.name);
        String n = e.name.toLowerCase(Locale.ROOT);
        if (m.startsWith("image/")) return "🖼️";
        if (m.startsWith("video/")) return "🎬";
        if (m.startsWith("audio/")) return "🎵";
        if (m.equals("application/pdf")) return "📕";
        if (n.endsWith(".xls") || n.endsWith(".xlsx") || n.endsWith(".csv")) return "📊";
        if (n.endsWith(".doc") || n.endsWith(".docx")) return "📝";
        if (n.endsWith(".zip") || n.endsWith(".rar") || n.endsWith(".7z")) return "📦";
        return "📄";
    }

    /** Diễn giải lỗi bằng tiếng Việt dễ hiểu. */
    static String explain(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof Cancelled) return "Đã huỷ";
            if (t instanceof SMBApiException) {
                String st = String.valueOf(((SMBApiException) t).getStatus());
                switch (st) {
                    case "STATUS_LOGON_FAILURE":
                        return "Sai tên đăng nhập hoặc mật khẩu";
                    case "STATUS_ACCOUNT_DISABLED":
                    case "STATUS_ACCOUNT_RESTRICTION":
                    case "STATUS_PASSWORD_EXPIRED":
                        return "Tài khoản bị khoá/hạn chế hoặc mật khẩu hết hạn";
                    case "STATUS_BAD_NETWORK_NAME":
                        return "Không có thư mục chia sẻ tên này trên WD — kiểm tra lại tên (giống tên thấy trong Windows Explorer)";
                    case "STATUS_ACCESS_DENIED":
                        return "Tài khoản không có quyền làm việc này";
                    case "STATUS_OBJECT_NAME_COLLISION":
                        return "Đã có tệp hoặc thư mục trùng tên";
                    case "STATUS_OBJECT_NAME_NOT_FOUND":
                    case "STATUS_OBJECT_PATH_NOT_FOUND":
                        return "Không tìm thấy tệp/thư mục (có thể đã bị xoá hoặc đổi tên)";
                    case "STATUS_OBJECT_NAME_INVALID":
                        return "Tên có ký tự không hợp lệ";
                    case "STATUS_DIRECTORY_NOT_EMPTY":
                        return "Thư mục không trống";
                    case "STATUS_SHARING_VIOLATION":
                        return "Tệp đang được mở ở máy khác";
                    case "STATUS_DISK_FULL":
                        return "Ổ WD đã đầy";
                    default:
                        return "Máy chủ báo lỗi: " + st;
                }
            }
            if (t instanceof UnknownHostException) return "Không tìm thấy máy chủ — kiểm tra lại địa chỉ";
            if (t instanceof ConnectException || t instanceof NoRouteToHostException)
                return "Không kết nối được tới WD — kiểm tra mạng điện thoại, địa chỉ IP, hoặc nhà mạng chặn cổng 445";
            if (t instanceof SocketTimeoutException)
                return "Hết thời gian chờ — mạng chậm hoặc WD không phản hồi";
        }
        String m = e.getMessage() == null ? "" : e.getMessage();
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("smbv1") || low.contains("smb1") || low.contains("negotiat"))
            return "WD chỉ đang bật SMB1 (cũ). Vào trang quản trị WD, mục Windows Services/SMB, bật SMB2 hoặc SMB3. (" + m + ")";
        return m.isEmpty() ? "Lỗi: " + e.getClass().getSimpleName() : m;
    }

    private Smb() {}
}
