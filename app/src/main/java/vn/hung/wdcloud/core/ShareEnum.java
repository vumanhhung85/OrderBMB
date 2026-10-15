package vn.hung.wdcloud.core;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ImpersonationLevel;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.NamedPipe;
import com.hierynomus.smbj.share.PipeShare;
import com.hierynomus.smbj.share.Share;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;

/**
 * Lấy danh sách thư mục chia sẻ trên máy chủ (giống khi mở \\IP trong Windows Explorer):
 * gọi NetrShareEnum (cấp 1) của dịch vụ srvsvc qua DCE/RPC trên ống \PIPE\srvsvc.
 */
public final class ShareEnum {
    public static final class Item {
        public String name, remark;
        public int type;
    }

    private static final int FRAG = 4280;

    public static List<Item> list(Drive d) throws IOException {
        SMBClient client = new SMBClient(SmbPool.config());
        try {
            Connection conn = client.connect(d.host, d.port);
            try {
                Session ses = conn.authenticate(SmbPool.auth(d));
                try {
                    Share sh = ses.connectShare("IPC$");
                    try {
                        if (!(sh instanceof PipeShare)) throw new IOException("Máy chủ không có IPC$");
                        NamedPipe pipe = ((PipeShare) sh).open("srvsvc", SMB2ImpersonationLevel.Impersonation,
                                EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE), null,
                                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
                        try {
                            byte[] ack = pipe.transact(bind(1));
                            if (ack.length < 3 || ack[2] != 12) throw new IOException("Máy chủ từ chối liệt kê thư mục chia sẻ (bind)");
                            byte[] stub = call(pipe, request(2, 15, enumStub(d.host)));
                            return parse(stub);
                        } finally {
                            pipe.close();
                        }
                    } finally {
                        try { sh.close(); } catch (Exception ignored) {}
                    }
                } finally {
                    try { ses.close(); } catch (Exception ignored) {}
                }
            } finally {
                try { conn.close(); } catch (Exception ignored) {}
            }
        } finally {
            client.close();
        }
    }

    /** Chỉ giữ thư mục chứa file, bỏ thư mục ẩn/hệ thống (tên kết thúc bằng $). */
    public static List<Item> disks(List<Item> all) {
        List<Item> out = new ArrayList<>();
        for (Item i : all) {
            if ((i.type & 0x0FFFFFFF) == 0 && (i.type & 0x80000000) == 0 && !i.name.endsWith("$")) out.add(i);
        }
        return out;
    }

    // ---------- DCE/RPC ----------

    private static ByteBuffer le(int n) { return ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN); }

    private static void header(ByteBuffer b, int ptype, int fragLen, int callId) {
        b.put((byte) 5).put((byte) 0).put((byte) ptype).put((byte) 0x03); // v5.0, cờ first+last
        b.put(new byte[]{0x10, 0, 0, 0});                                // little-endian, ASCII, IEEE
        b.putShort((short) fragLen).putShort((short) 0).putInt(callId);
    }

    private static void uuid(ByteBuffer b, String s, int major, int minor) {
        String h = s.replace("-", "");
        b.putInt((int) Long.parseLong(h.substring(0, 8), 16));
        b.putShort((short) Integer.parseInt(h.substring(8, 12), 16));
        b.putShort((short) Integer.parseInt(h.substring(12, 16), 16));
        for (int i = 16; i < 32; i += 2) b.put((byte) Integer.parseInt(h.substring(i, i + 2), 16));
        b.putShort((short) major).putShort((short) minor);
    }

    static byte[] bind(int callId) {
        ByteBuffer b = le(72);
        header(b, 11, 72, callId);
        b.putShort((short) FRAG).putShort((short) FRAG).putInt(0);
        b.put((byte) 1).put((byte) 0).putShort((short) 0);          // 1 ngữ cảnh
        b.putShort((short) 0).put((byte) 1).put((byte) 0);          // context 0, 1 transfer syntax
        uuid(b, "4b324fc8-1670-01d3-1278-5a47bf6ee188", 3, 0);     // srvsvc v3.0
        uuid(b, "8a885d04-1ceb-11c9-9fe8-08002b104860", 2, 0);     // NDR v2
        return b.array();
    }

    static byte[] request(int callId, int opnum, byte[] stub) {
        int len = 24 + stub.length;
        ByteBuffer b = le(len);
        header(b, 0, len, callId);
        b.putInt(stub.length).putShort((short) 0).putShort((short) opnum);
        b.put(stub);
        return b.array();
    }

    private static void ndrString(ByteBuffer b, String s) {
        String z = s + "\0";
        byte[] u = z.getBytes(StandardCharsets.UTF_16LE);
        b.putInt(z.length()).putInt(0).putInt(z.length()).put(u);
        while (b.position() % 4 != 0) b.put((byte) 0);
    }

    static byte[] enumStub(String host) {
        ByteBuffer b = le(512);
        b.putInt(0x00020000);              // ServerName (con trỏ unique)
        ndrString(b, "\\\\" + host);
        b.putInt(1);                       // InfoStruct.Level = 1
        b.putInt(1);                       // union switch = 1
        b.putInt(0x00020004);              // con trỏ SHARE_INFO_1_CONTAINER
        b.putInt(0);                       // EntriesRead
        b.putInt(0);                       // Buffer = NULL
        b.putInt(0xFFFFFFFF);              // PreferedMaximumLength
        b.putInt(0x00020008);              // ResumeHandle (con trỏ)
        b.putInt(0);
        return Arrays.copyOf(b.array(), b.position());
    }

    /** Gửi yêu cầu, gom các mảnh phản hồi, trả về phần dữ liệu (stub). */
    static byte[] call(NamedPipe pipe, byte[] req) throws IOException {
        ByteArrayOutputStream stub = new ByteArrayOutputStream();
        byte[] buf = pipe.transact(req);
        for (int guard = 0; guard < 1000; guard++) {
            if (buf.length < 16) throw new IOException("Phản hồi RPC không hợp lệ");
            ByteBuffer b = ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN);
            int ptype = buf[2] & 0xff;
            int flags = buf[3] & 0xff;
            int fragLen = b.getShort(8) & 0xffff;
            int authLen = b.getShort(10) & 0xffff;
            if (ptype == 3) throw new IOException("Máy chủ báo lỗi RPC 0x" + Integer.toHexString(b.getInt(24)));
            if (ptype != 2) throw new IOException("Phản hồi RPC lạ (loại " + ptype + ")");
            int end = Math.min(fragLen, buf.length) - (authLen > 0 ? authLen + 8 : 0);
            stub.write(buf, 24, Math.max(0, end - 24));
            if ((flags & 0x02) != 0) break;                 // mảnh cuối
            byte[] more = new byte[65536];
            int n = pipe.read(more);
            buf = Arrays.copyOf(more, n);
        }
        return stub.toByteArray();
    }

    private static final class R {
        final ByteBuffer b;
        R(byte[] d) { b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN); }
        int i() { return b.getInt(); }
        void align() { while (b.position() % 4 != 0) b.get(); }
        String s() {
            i();                    // max count
            i();                    // offset
            int actual = i();
            byte[] u = new byte[actual * 2];
            b.get(u);
            align();
            String r = new String(u, StandardCharsets.UTF_16LE);
            int z = r.indexOf('\0');
            return z >= 0 ? r.substring(0, z) : r;
        }
    }

    static List<Item> parse(byte[] stub) throws IOException {
        R r = new R(stub);
        List<Item> out = new ArrayList<>();
        r.i();                                  // Level
        r.i();                                  // switch
        int pContainer = r.i();
        if (pContainer != 0) {
            r.i();                              // EntriesRead
            int pBuf = r.i();
            if (pBuf != 0) {
                int n = r.i();
                int[] pName = new int[n], type = new int[n], pRemark = new int[n];
                for (int k = 0; k < n; k++) { pName[k] = r.i(); type[k] = r.i(); pRemark[k] = r.i(); }
                for (int k = 0; k < n; k++) {
                    Item it = new Item();
                    it.type = type[k];
                    it.name = pName[k] != 0 ? r.s() : "";
                    it.remark = pRemark[k] != 0 ? r.s() : "";
                    out.add(it);
                }
            }
        }
        r.i();                                  // TotalEntries
        if (r.i() != 0) r.i();                  // ResumeHandle
        int werr = r.i();
        if (werr == 5) throw new Errors.UserError("Tài khoản không được phép xem danh sách thư mục chia sẻ — nhập tên thư mục bằng tay");
        if (werr != 0) throw new IOException("Liệt kê thư mục chia sẻ lỗi " + werr);
        return out;
    }

    private ShareEnum() {}
}
