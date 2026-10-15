package vn.hung.wdcloud.core;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.hierynomus.smbj.share.DiskShare;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Chạy với máy chủ Samba thật (GitHub Actions dựng sẵn 2 thư mục chia sẻ). Không có biến môi trường SMB_HOST thì bỏ qua.
 */
public class FsTest {
    static Drive d1, d2;
    static String root;

    @BeforeClass
    public static void setup() throws Exception {
        String host = System.getenv("SMB_HOST");
        Assume.assumeTrue("Không có máy chủ SMB để test", host != null && !host.isEmpty());
        d1 = drive(host, System.getenv("SMB_SHARE1"));
        d2 = drive(host, System.getenv("SMB_SHARE2"));
        root = "t_" + System.currentTimeMillis();
        SmbPool.run(d1, s -> { s.mkdir(root); return null; });
        SmbPool.run(d2, s -> { s.mkdir(root); return null; });
    }

    static Drive drive(String host, String share) {
        Drive d = new Drive();
        d.host = host;
        d.share = share;
        d.user = System.getenv("SMB_USER");
        d.pass = System.getenv("SMB_PASS");
        return d;
    }

    static void put(DiskShare s, String path, byte[] data) throws Exception {
        Fs.upload(s, path, new ByteArrayInputStream(data), false, new Job());
    }

    static byte[] get(DiskShare s, String path) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        Fs.download(s, path, bo, new Job());
        return bo.toByteArray();
    }

    static Fs.Entry entry(DiskShare s, String path) {
        for (Fs.Entry e : Fs.list(s, Fs.parent(path), true)) if (e.path.equalsIgnoreCase(path)) return e;
        throw new AssertionError("Không thấy " + path);
    }

    static byte[] rnd(int n) {
        byte[] b = new byte[n];
        new Random(n).nextBytes(b);
        return b;
    }

    @Test
    public void testConnectAndShareList() throws Exception {
        assertTrue(SmbPool.test(d1) >= 1);
        List<ShareEnum.Item> all = ShareEnum.list(d1);
        List<String> names = new ArrayList<>();
        for (ShareEnum.Item i : ShareEnum.disks(all)) names.add(i.name.toLowerCase());
        System.out.println("Shares: " + names + " (tất cả " + all.size() + ")");
        assertTrue(names.contains(d1.share.toLowerCase()));
        assertTrue(names.contains(d2.share.toLowerCase()));
        for (String n : names) assertFalse(n.endsWith("$"));
    }

    @Test
    public void testWrongPassword() {
        Drive bad = d1.copy();
        bad.pass = "sai-mat-khau";
        try {
            SmbPool.test(bad);
            fail("Phải báo sai mật khẩu");
        } catch (Exception e) {
            assertEquals("Sai tên đăng nhập hoặc mật khẩu", Errors.explain(e));
        }
        Drive noShare = d1.copy();
        noShare.share = "khong_co_share_nay";
        try {
            SmbPool.test(noShare);
            fail("Phải báo không có share");
        } catch (Exception e) {
            assertTrue(Errors.explain(e).startsWith("Không có thư mục chia sẻ"));
        }
    }

    @Test
    public void testBasicOpsVietnamese() throws Exception {
        final String dir = root + "\\Cơ bản\\Tháng 10";
        SmbPool.runOnce(d1, s -> {
            Fs.mkdirs(s, dir);
            assertTrue(Fs.isDir(s, dir));
            byte[] data = "Xin chào WD — báo cáo".getBytes(StandardCharsets.UTF_8);
            put(s, Fs.join(dir, "Báo cáo.txt"), data);
            assertArrayEquals(data, get(s, Fs.join(dir, "Báo cáo.txt")));
            assertEquals(Fs.join(dir, "Báo cáo (1).txt"), Fs.uniquePath(s, dir, "Báo cáo.txt"));
            // đổi tên
            Fs.rename(s, Fs.join(dir, "Báo cáo.txt"), Fs.join(dir, "Đã đổi.txt"), false);
            assertFalse(Fs.exists(s, Fs.join(dir, "Báo cáo.txt")));
            assertTrue(Fs.exists(s, Fs.join(dir, "Đã đổi.txt")));
            // ghi đè an toàn + đọc lại
            byte[] v2 = "nội dung mới".getBytes(StandardCharsets.UTF_8);
            Fs.writeReplace(s, Fs.join(dir, "Đã đổi.txt"), v2);
            assertArrayEquals(v2, Fs.readAll(s, Fs.join(dir, "Đã đổi.txt"), 1 << 20));
            assertEquals(1, Fs.list(s, dir, false).size()); // không còn tệp tạm
            // tìm không dấu
            List<Fs.Entry> found = Fs.search(s, root, "da doi", false, 100, new Job());
            assertEquals(1, found.size());
            assertEquals("Đã đổi.txt", found.get(0).name);
            assertEquals(1, Fs.search(s, root, "THANG 10", false, 100, new Job()).size());
            // thống kê
            List<Fs.Entry> top = Collections.singletonList(entry(s, root + "\\Cơ bản"));
            long[] st = Fs.stats(s, top, new Job());
            assertEquals(1, st[0]);
            assertEquals(2, st[1]);
            assertEquals(v2.length, st[2]);
            // xoá đệ quy
            Fs.delete(s, top.get(0));
            assertFalse(Fs.exists(s, root + "\\Cơ bản"));
            return null;
        });
    }

    @Test
    public void testCopySameDriveKeepsDateAndBytes() throws Exception {
        final String dir = root + "\\copy1";
        final byte[] big = rnd(5 * 1024 * 1024 + 123);
        SmbPool.runOnce(d1, s -> {
            Fs.mkdirs(s, dir + "\\src\\sub");
            Fs.mkdirs(s, dir + "\\dst");
            put(s, dir + "\\src\\a.bin", big);
            put(s, dir + "\\src\\sub\\b.txt", "b".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(1500);
            List<Fs.Entry> items = new ArrayList<>();
            items.add(entry(s, dir + "\\src"));
            Job job = new Job();
            Fs.paste(s, s, true, items, dir + "\\dst", false, Fs.KEEP_BOTH, job);
            assertArrayEquals(big, get(s, dir + "\\dst\\src\\a.bin"));
            assertEquals("b", new String(get(s, dir + "\\dst\\src\\sub\\b.txt"), StandardCharsets.UTF_8));
            assertEquals(2, job.done);
            System.out.println("Số lần máy chủ tự chép (server-side copy): " + Fs.serverCopies);
            long m1 = entry(s, dir + "\\src\\a.bin").mtime, m2 = entry(s, dir + "\\dst\\src\\a.bin").mtime;
            assertTrue("Giữ ngày sửa đổi: " + m1 + " vs " + m2, Math.abs(m1 - m2) < 2000);
            // dán lần nữa vào cùng chỗ với KEEP_BOTH → "src (1)"
            Fs.paste(s, s, true, items, dir + "\\dst", false, Fs.KEEP_BOTH, new Job());
            assertTrue(Fs.isDir(s, dir + "\\dst\\src (1)"));
            // chép vào chính thư mục đang chứa → bản sao
            List<Fs.Entry> one = Collections.singletonList(entry(s, dir + "\\src\\a.bin"));
            Fs.paste(s, s, true, one, dir + "\\src", false, Fs.KEEP_BOTH, new Job());
            assertTrue(Fs.exists(s, dir + "\\src\\a (1).bin"));
            // chép thư mục vào chính nó → báo lỗi
            try {
                Fs.paste(s, s, true, items, dir + "\\src\\sub", false, Fs.KEEP_BOTH, new Job());
                fail("Phải chặn chép vào chính nó");
            } catch (Errors.UserError e) {
                assertTrue(e.getMessage().contains("vào chính nó"));
            }
            return null;
        });
    }

    @Test
    public void testMoveSameDriveWithConflicts() throws Exception {
        final String dir = root + "\\move1";
        SmbPool.runOnce(d1, s -> {
            Fs.mkdirs(s, dir + "\\A\\X");
            Fs.mkdirs(s, dir + "\\B\\A\\X");
            put(s, dir + "\\A\\X\\f.txt", "moi".getBytes(StandardCharsets.UTF_8));
            put(s, dir + "\\A\\g.txt", "g".getBytes(StandardCharsets.UTF_8));
            put(s, dir + "\\B\\A\\X\\f.txt", "cu".getBytes(StandardCharsets.UTF_8));
            List<Fs.Entry> items = Collections.singletonList(entry(s, dir + "\\A"));
            assertEquals(Collections.singletonList("A"), Fs.conflicts(s, dir + "\\B", items, true));
            // SKIP: gộp thư mục, giữ tệp cũ, tệp không trùng thì chuyển
            Fs.paste(s, s, true, items, dir + "\\B", true, Fs.SKIP, new Job());
            assertEquals("cu", new String(get(s, dir + "\\B\\A\\X\\f.txt"), StandardCharsets.UTF_8));
            assertTrue(Fs.exists(s, dir + "\\B\\A\\g.txt"));
            assertTrue(Fs.exists(s, dir + "\\A\\X\\f.txt")); // tệp bị bỏ qua vẫn ở chỗ cũ
            // OVERWRITE
            items = Collections.singletonList(entry(s, dir + "\\A"));
            Fs.paste(s, s, true, items, dir + "\\B", true, Fs.OVERWRITE, new Job());
            assertEquals("moi", new String(get(s, dir + "\\B\\A\\X\\f.txt"), StandardCharsets.UTF_8));
            assertFalse(Fs.exists(s, dir + "\\A"));
            // chuyển tệp không trùng = đổi tên tức thì
            put(s, dir + "\\h.txt", "h".getBytes(StandardCharsets.UTF_8));
            Fs.paste(s, s, true, Collections.singletonList(entry(s, dir + "\\h.txt")), dir + "\\B", true, Fs.KEEP_BOTH, new Job());
            assertTrue(Fs.exists(s, dir + "\\B\\h.txt"));
            assertFalse(Fs.exists(s, dir + "\\h.txt"));
            return null;
        });
    }

    @Test
    public void testCrossDriveCopyAndMove() throws Exception {
        final String src = root + "\\cross";
        final byte[] data = rnd(700 * 1024);
        SmbPool.runOnce(d1, s -> {
            Fs.mkdirs(s, src + "\\Ảnh");
            put(s, src + "\\Ảnh\\1.jpg", data);
            put(s, src + "\\ghi chú.txt", "x".getBytes(StandardCharsets.UTF_8));
            return null;
        });
        final List<Fs.Entry> items = SmbPool.run(d1, s -> {
            List<Fs.Entry> l = new ArrayList<>();
            l.add(entry(s, src + "\\Ảnh"));
            l.add(entry(s, src + "\\ghi chú.txt"));
            return l;
        });
        // chép sang ổ 2
        SmbPool.runOnce2(d1, d2, (s, d) -> {
            Fs.paste(s, d, false, items, root, false, Fs.KEEP_BOTH, new Job());
            return null;
        });
        SmbPool.runOnce(d2, d -> {
            assertArrayEquals(data, get(d, root + "\\Ảnh\\1.jpg"));
            assertTrue(Fs.exists(d, root + "\\ghi chú.txt"));
            return null;
        });
        // chuyển sang ổ 2 khi đã trùng: KEEP_BOTH → bản (1), gốc bị xoá
        SmbPool.runOnce2(d1, d2, (s, d) -> {
            Fs.paste(s, d, false, items, root, true, Fs.KEEP_BOTH, new Job());
            return null;
        });
        SmbPool.runOnce(d2, d -> {
            assertTrue(Fs.isDir(d, root + "\\Ảnh (1)"));
            assertArrayEquals(data, get(d, root + "\\Ảnh (1)\\1.jpg"));
            assertTrue(Fs.exists(d, root + "\\ghi chú (1).txt"));
            return null;
        });
        SmbPool.run(d1, s -> {
            assertFalse(Fs.exists(s, src + "\\Ảnh"));
            assertFalse(Fs.exists(s, src + "\\ghi chú.txt"));
            return null;
        });
    }

    @Test
    public void testCancelRemovesPartialFile() throws Exception {
        final byte[] data = rnd(3 * 1024 * 1024);
        SmbPool.runOnce(d1, s -> {
            Job job = new Job() {
                @Override protected void changed() { if (bytes > 512 * 1024) cancelled = true; }
            };
            try {
                Fs.upload(s, root + "\\huy.bin", new ByteArrayInputStream(data), false, job);
                fail("Phải huỷ");
            } catch (Job.Cancelled e) {
                assertEquals("Đã huỷ", Errors.explain(e));
            }
            assertFalse(Fs.exists(s, root + "\\huy.bin"));
            return null;
        });
    }

    @Test
    public void testReconnectAfterReset() throws Exception {
        assertNotNull(SmbPool.run(d1, s -> Fs.list(s, "", false)));
        SmbPool.reset();
        assertNotNull(SmbPool.run(d1, s -> Fs.list(s, "", false)));
        assertEquals("abc", Fs.fold("ÀBC"));
        assertEquals("bao cao thang muoi", Fs.fold("Báo Cáo Tháng Mười"));
        assertEquals("duong", Fs.fold("Đường"));
        assertEquals("a_b_c", Fs.cleanName("a/b:c"));
    }
}
