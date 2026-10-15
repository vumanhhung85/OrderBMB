package vn.hung.wdcloud.core;

import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.Share;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Giữ kết nối SMB cho từng ổ (mỗi ổ một phiên riêng), tự nối lại khi rớt hoặc để yên quá lâu.
 * Thao tác truyền dữ liệu dùng runOnce (không tự thử lại để tránh ghi trùng).
 */
public final class SmbPool {
    public interface Op<T> { T run(DiskShare s) throws Exception; }

    private static final long IDLE_RESET_MS = 4 * 60 * 1000L;

    static final class Opened implements Closeable {
        SMBClient client;
        Connection conn;
        Session session;
        DiskShare share;

        boolean alive() {
            try { return conn.isConnected() && share.isConnected(); } catch (Exception e) { return false; }
        }

        @Override public void close() {
            try { if (share != null) share.close(); } catch (Exception ignored) {}
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (conn != null) conn.close(); } catch (Exception ignored) {}
            try { if (client != null) client.close(); } catch (Exception ignored) {}
        }
    }

    private static final class Slot {
        Opened o;
        int busy;
        long lastUse;
    }

    private static final Map<String, Slot> POOL = new HashMap<>();

    public static SmbConfig config() {
        return SmbConfig.builder()
                .withSecurityProvider(new BCSecurityProvider())
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(45, TimeUnit.SECONDS)
                .build();
    }

    static AuthenticationContext auth(Drive d) {
        return d.user.isEmpty()
                ? new AuthenticationContext("Guest", new char[0], d.domain)
                : new AuthenticationContext(d.user, d.pass.toCharArray(), d.domain);
    }

    static Opened open(Drive d) throws IOException {
        Opened o = new Opened();
        try {
            o.client = new SMBClient(config());
            o.conn = o.client.connect(d.host, d.port);
            o.session = o.conn.authenticate(auth(d));
            Share sh = o.session.connectShare(d.share);
            if (!(sh instanceof DiskShare)) {
                try { sh.close(); } catch (Exception ignored) {}
                throw new Errors.UserError("\"" + d.share + "\" không phải thư mục chia sẻ chứa file");
            }
            o.share = (DiskShare) sh;
            return o;
        } catch (IOException | RuntimeException e) {
            o.close();
            throw e;
        }
    }

    /** Thử kết nối bằng thông tin chưa lưu; trả về số mục ở thư mục gốc. */
    public static int test(Drive d) throws IOException {
        try (Opened o = open(d)) {
            int n = 0;
            for (FileIdBothDirectoryInformation i : o.share.list("")) {
                String name = i.getFileName();
                if (!".".equals(name) && !"..".equals(name)) n++;
            }
            return n;
        }
    }

    private static synchronized DiskShare acquire(Drive d) throws IOException {
        if (!d.ready()) throw new Errors.UserError("Ổ chưa có địa chỉ hoặc tên thư mục chia sẻ");
        long now = System.currentTimeMillis();
        // Dọn các kết nối để yên quá lâu
        for (Iterator<Map.Entry<String, Slot>> it = POOL.entrySet().iterator(); it.hasNext(); ) {
            Slot s = it.next().getValue();
            if (s.busy == 0 && (now - s.lastUse > IDLE_RESET_MS || s.o == null || !s.o.alive())) {
                if (s.o != null) s.o.close();
                it.remove();
            }
        }
        String k = d.key();
        Slot s = POOL.get(k);
        if (s == null) {
            s = new Slot();
            s.o = open(d);
            POOL.put(k, s);
        }
        s.busy++;
        s.lastUse = now;
        return s.o.share;
    }

    private static synchronized void release(Drive d) {
        Slot s = POOL.get(d.key());
        if (s != null) {
            s.busy = Math.max(0, s.busy - 1);
            s.lastUse = System.currentTimeMillis();
        }
    }

    private static synchronized void dropBroken(Drive d) {
        Slot s = POOL.get(d.key());
        if (s != null && (s.busy == 0 || !s.o.alive())) {
            s.o.close();
            POOL.remove(d.key());
        }
    }

    /** Đóng mọi kết nối không dùng (VD sau khi sửa thông tin ổ, hoặc bấm Làm mới). */
    public static synchronized void reset() {
        List<String> drop = new ArrayList<>();
        for (Map.Entry<String, Slot> e : POOL.entrySet()) if (e.getValue().busy == 0) drop.add(e.getKey());
        for (String k : drop) {
            POOL.remove(k).o.close();
        }
    }

    private static <T> T once(Drive d, Op<T> op) throws Exception {
        DiskShare s = acquire(d);
        try {
            return op.run(s);
        } finally {
            release(d);
        }
    }

    /** Chạy thao tác; lỗi đường truyền thì nối lại và thử thêm 1 lần. */
    public static <T> T run(Drive d, Op<T> op) throws Exception {
        try {
            return once(d, op);
        } catch (Exception e) {
            if (!Errors.isConnErr(e)) throw e;
            dropBroken(d);
            return once(d, op);
        }
    }

    /** Chạy thao tác không thử lại (truyền dữ liệu). Kiểm tra kết nối trước bằng run() để nối lại nếu cần. */
    public static <T> T runOnce(Drive d, Op<T> op) throws Exception {
        run(d, s -> s.folderExists(""));
        try {
            return once(d, op);
        } catch (Exception e) {
            if (Errors.isConnErr(e)) dropBroken(d);
            throw e;
        }
    }

    public interface Op2<T> { T run(DiskShare src, DiskShare dst) throws Exception; }

    /** Mở cùng lúc 2 ổ (cùng ổ thì dùng chung một phiên để chép phía máy chủ). */
    public static <T> T runOnce2(final Drive src, final Drive dst, final Op2<T> op) throws Exception {
        if (src.key().equals(dst.key())) return runOnce(src, s -> op.run(s, s));
        return runOnce(src, s1 -> runOnce(dst, s2 -> op.run(s1, s2)));
    }

    private SmbPool() {}
}
