package vn.hung.wdcloud.core;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msdtyp.FileTime;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileBasicInformation;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.msfscc.fileinformation.FileStandardInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2CreateOptions;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.mssmb2.SMBApiException;
import com.hierynomus.smbj.share.DiskEntry;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.File;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.Collator;
import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Các thao tác tệp trên một thư mục chia sẻ SMB. Đường dẫn dùng "\" và tính từ gốc thư mục chia sẻ ("" = gốc). */
public final class Fs {
    public static final int KEEP_BOTH = 1, OVERWRITE = 2, SKIP = 3;
    public static final int SORT_NAME = 0, SORT_DATE = 1, SORT_SIZE = 2, SORT_TYPE = 3;
    private static final long SERVER_COPY_CHUNK = 16L * 1024 * 1024;
    /** Đếm số lần chép phía máy chủ thành công (để kiểm thử). */
    public static volatile int serverCopies;

    public static final class Entry {
        public String name, path;
        public boolean dir, hidden;
        public long size, mtime;
    }

    // ---------- Đường dẫn ----------

    public static String join(String dir, String name) { return dir == null || dir.isEmpty() ? name : dir + "\\" + name; }

    public static String parent(String p) {
        int i = p.lastIndexOf('\\');
        return i < 0 ? "" : p.substring(0, i);
    }

    public static String nameOf(String p) {
        int i = p.lastIndexOf('\\');
        return i < 0 ? p : p.substring(i + 1);
    }

    public static String norm(String p) {
        if (p == null) return "";
        p = p.trim().replace('/', '\\').replaceAll("\\\\+", "\\\\");
        while (p.startsWith("\\")) p = p.substring(1);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        return p.trim();
    }

    /** Bỏ dấu tiếng Việt + chữ thường, để tìm "bao cao" ra "Báo cáo". */
    public static String fold(String s) {
        String n = Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return n.replace('đ', 'd');
    }

    /** Bỏ ký tự Windows không cho phép trong tên. */
    public static String cleanName(String n) {
        n = n == null ? "" : n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        if (n.length() > 180) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 180 - ext.length()) + ext;
        }
        return n;
    }

    // ---------- Liệt kê ----------

    public static List<Entry> list(DiskShare s, String dir, boolean showHidden) {
        List<Entry> out = new ArrayList<>();
        long dirFlag = FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue();
        long hidFlag = FileAttributes.FILE_ATTRIBUTE_HIDDEN.getValue() | FileAttributes.FILE_ATTRIBUTE_SYSTEM.getValue();
        for (FileIdBothDirectoryInformation i : s.list(dir)) {
            String name = i.getFileName();
            if (name == null || ".".equals(name) || "..".equals(name)) continue;
            long a = i.getFileAttributes();
            Entry e = new Entry();
            e.name = name;
            e.path = join(dir, name);
            e.dir = (a & dirFlag) != 0;
            e.hidden = (a & hidFlag) != 0 || name.startsWith(".") || name.startsWith("$") || name.endsWith(".wdtmp");
            if (e.hidden && !showHidden) continue;
            e.size = e.dir ? 0 : i.getEndOfFile();
            try { e.mtime = i.getLastWriteTime().toEpochMillis(); } catch (Exception ignored) {}
            out.add(e);
        }
        return out;
    }

    public static String ext(String name) {
        int d = name.lastIndexOf('.');
        return d > 0 ? name.substring(d + 1).toLowerCase(Locale.ROOT) : "";
    }

    public static void sort(List<Entry> l, final int mode, final boolean desc) {
        final Collator col = Collator.getInstance(new Locale("vi", "VN"));
        col.setStrength(Collator.SECONDARY);
        Collections.sort(l, new Comparator<Entry>() {
            @Override public int compare(Entry x, Entry y) {
                if (x.dir != y.dir) return x.dir ? -1 : 1;
                int r;
                switch (mode) {
                    case SORT_DATE: r = Long.compare(x.mtime, y.mtime); break;
                    case SORT_SIZE: r = Long.compare(x.size, y.size); break;
                    case SORT_TYPE: r = ext(x.name).compareTo(ext(y.name)); break;
                    default: r = 0;
                }
                if (desc) r = -r;
                if (r == 0) r = col.compare(x.name, y.name) * (desc && mode == SORT_NAME ? -1 : 1);
                return r;
            }
        });
    }

    // ---------- Kiểm tra / tạo ----------

    public static boolean exists(DiskShare s, String p) {
        try { return s.fileExists(p) || s.folderExists(p); } catch (SMBApiException e) { return false; }
    }

    public static boolean isDir(DiskShare s, String p) {
        try { return s.folderExists(p); } catch (SMBApiException e) { return false; }
    }

    public static void mkdirs(DiskShare s, String dir) {
        if (dir == null || dir.isEmpty()) return;
        String acc = "";
        for (String part : dir.split("\\\\")) {
            if (part.isEmpty()) continue;
            acc = join(acc, part);
            if (!s.folderExists(acc)) s.mkdir(acc);
        }
    }

    /** Tên chưa có: "anh.jpg" → "anh (1).jpg". */
    public static String uniquePath(DiskShare s, String dir, String name) {
        String base = name, ext = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0) { base = name.substring(0, dot); ext = name.substring(dot); }
        String p = join(dir, name);
        for (int i = 1; exists(s, p) && i < 100000; i++) p = join(dir, base + " (" + i + ")" + ext);
        return p;
    }

    // ---------- Đổi tên / chuyển trong cùng ổ ----------

    public static void rename(DiskShare s, String from, String to, boolean replace) {
        DiskEntry d = s.open(from, EnumSet.of(AccessMask.DELETE, AccessMask.FILE_READ_ATTRIBUTES), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
        try { d.rename(to, replace); } finally { d.close(); }
    }

    /** Chuyển thư mục vào thư mục đích đã tồn tại: gộp nội dung. */
    static void moveMerge(DiskShare s, String from, String to, int policy, Job job) throws IOException {
        for (Entry c : list(s, from, true)) {
            job.check();
            String t = join(to, c.name);
            boolean ex = exists(s, t);
            if (!ex) {
                rename(s, c.path, t, false);
            } else if (c.dir && isDir(s, t)) {
                moveMerge(s, c.path, t, policy, job);
                continue;
            } else if (policy == OVERWRITE && !c.dir && !isDir(s, t)) {
                rename(s, c.path, t, true);
            } else if (policy == KEEP_BOTH) {
                rename(s, c.path, uniquePath(s, to, c.name), false);
            } else {
                continue; // bỏ qua
            }
            job.finishItem();
        }
        try { s.rmdir(from, false); } catch (Exception ignored) {} // còn tệp bị bỏ qua thì giữ lại
    }

    // ---------- Truyền dữ liệu ----------

    public static long copy(InputStream in, OutputStream out, Job job) throws IOException {
        byte[] buf = new byte[256 * 1024];
        long done = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            done += n;
            if (job != null) job.progress(done);
        }
        out.flush();
        return done;
    }

    private static File openWrite(DiskShare s, String path, boolean overwrite) {
        return s.openFile(path, EnumSet.of(AccessMask.GENERIC_READ, AccessMask.GENERIC_WRITE),
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL), SMB2ShareAccess.ALL,
                overwrite ? SMB2CreateDisposition.FILE_OVERWRITE_IF : SMB2CreateDisposition.FILE_CREATE,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    private static File openRead(DiskShare s, String path) {
        return s.openFile(path, EnumSet.of(AccessMask.GENERIC_READ), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OPEN, EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE));
    }

    /** Ghi luồng vào tệp trên WD; lỗi giữa chừng thì xoá tệp dở. */
    public static void upload(DiskShare s, String path, InputStream in, boolean overwrite, Job job) throws IOException {
        File f = openWrite(s, path, overwrite);
        boolean ok = false;
        try {
            try (OutputStream out = f.getOutputStream()) {
                copy(in, out, job);
            }
            ok = true;
        } finally {
            try { f.close(); } catch (Exception ignored) {}
            if (!ok) try { s.rm(path); } catch (Exception ignored) {}
        }
    }

    public static void download(DiskShare s, String path, OutputStream out, Job job) throws IOException {
        File f = openRead(s, path);
        try (InputStream in = f.getInputStream()) {
            copy(in, out, job);
        } finally {
            f.close();
        }
    }

    public static byte[] readAll(DiskShare s, String path, long max) throws IOException {
        File f = openRead(s, path);
        try {
            long size = f.getFileInformation(FileStandardInformation.class).getEndOfFile();
            if (size > max) throw new Errors.UserError("Tệp quá lớn để sửa trong app (" + (size / 1024) + " KB)");
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) Math.max(16, size));
            try (InputStream in = f.getInputStream()) {
                copy(in, bo, null);
            }
            return bo.toByteArray();
        } finally {
            f.close();
        }
    }

    /** Ghi đè an toàn: ghi ra tệp tạm cạnh tệp gốc rồi đổi tên thay thế (mất mạng giữa chừng không hỏng tệp gốc). */
    public static void writeReplace(DiskShare s, String path, InputStream in, Job job) throws IOException {
        String tmp = join(parent(path), "." + nameOf(path) + ".wdtmp");
        upload(s, tmp, in, true, job);
        try {
            rename(s, tmp, path, true);
        } catch (RuntimeException e) {
            try { s.rm(tmp); } catch (Exception ignored) {}
            throw e;
        }
    }

    public static void writeReplace(DiskShare s, String path, byte[] data) throws IOException {
        writeReplace(s, path, new ByteArrayInputStream(data), null);
    }

    /**
     * Chép 1 tệp. Cùng ổ (cùng phiên) thì nhờ máy chủ tự chép (không đi qua điện thoại), không được thì chép qua điện thoại.
     * Giữ nguyên ngày sửa đổi. deleteSource = true để chuyển (chép xong mới xoá gốc).
     */
    public static void copyFile(DiskShare src, String from, DiskShare dst, String to, boolean overwrite,
                                boolean deleteSource, Job job) throws IOException {
        File in = openRead(src, from);
        try {
            long size = in.getFileInformation(FileStandardInformation.class).getEndOfFile();
            FileBasicInformation basic = null;
            try { basic = in.getFileInformation(FileBasicInformation.class); } catch (Exception ignored) {}
            job.start(nameOf(to), size);
            File out = openWrite(dst, to, overwrite);
            boolean ok = false;
            try {
                boolean done = false;
                if (src == dst && size > 0) {
                    try {
                        long off = 0;
                        while (off < size) {
                            job.check();
                            long n = Math.min(SERVER_COPY_CHUNK, size - off);
                            in.remoteCopyTo(off, out, off, n);
                            off += n;
                            job.progress(off);
                        }
                        done = true;
                        serverCopies++;
                    } catch (Job.Cancelled c) {
                        throw c;
                    } catch (Exception e) {
                        done = false; // máy chủ không hỗ trợ: chép qua điện thoại
                    }
                }
                if (!done) {
                    try (InputStream is = in.getInputStream(); OutputStream os = out.getOutputStream()) {
                        copy(is, os, job);
                    }
                }
                if (basic != null) {
                    try {
                        out.setFileInformation(new FileBasicInformation(basic.getCreationTime(), new FileTime(0),
                                basic.getLastWriteTime(), new FileTime(0), 0));
                    } catch (Exception ignored) {}
                }
                ok = true;
            } finally {
                try { out.close(); } catch (Exception ignored) {}
                if (!ok) try { dst.rm(to); } catch (Exception ignored) {}
            }
        } finally {
            in.close();
        }
        if (deleteSource) src.rm(from);
        job.finishItem();
    }

    /** Chép cây thư mục/tệp; tên trùng bên trong xử lý theo policy. */
    static void copyTree(DiskShare src, String from, boolean dir, DiskShare dst, String to, int policy,
                         boolean deleteSource, Job job) throws IOException {
        job.check();
        if (!dir) {
            if (exists(dst, to)) {
                if (policy == SKIP || isDir(dst, to)) return;
                if (policy == KEEP_BOTH) to = uniquePath(dst, parent(to), nameOf(to));
            }
            copyFile(src, from, dst, to, policy == OVERWRITE, deleteSource, job);
            return;
        }
        if (!isDir(dst, to)) {
            if (exists(dst, to)) to = uniquePath(dst, parent(to), nameOf(to)); // trùng tên với một tệp
            dst.mkdir(to);
        }
        for (Entry c : list(src, from, true)) {
            copyTree(src, c.path, c.dir, dst, join(to, c.name), policy, deleteSource, job);
        }
        if (deleteSource) try { src.rmdir(from, false); } catch (Exception ignored) {}
    }

    /** Tên các mục sẽ bị trùng khi dán vào dstDir. */
    public static List<String> conflicts(DiskShare dst, String dstDir, List<Entry> items, boolean sameDrive) {
        List<String> out = new ArrayList<>();
        for (Entry e : items) {
            String t = join(dstDir, e.name);
            if (sameDrive && t.equalsIgnoreCase(e.path)) continue; // dán vào chính chỗ cũ
            if (exists(dst, t)) out.add(e.name);
        }
        return out;
    }

    /**
     * Dán (chép hoặc chuyển) các mục vào thư mục đích.
     * Cùng ổ + chuyển = đổi tên (tức thì). Cùng ổ + chép = máy chủ tự chép. Khác ổ = chép qua điện thoại.
     */
    public static void paste(DiskShare s, DiskShare d, boolean sameDrive, List<Entry> items, String dstDir,
                             boolean cut, int policy, Job job) throws IOException {
        job.count = 0;
        for (Entry e : items) {
            job.check();
            String target = join(dstDir, e.name);
            if (sameDrive && e.dir && (dstDir.equalsIgnoreCase(e.path) || dstDir.toLowerCase(Locale.ROOT).startsWith(e.path.toLowerCase(Locale.ROOT) + "\\")))
                throw new Errors.UserError("Không thể " + (cut ? "chuyển" : "chép") + " thư mục \"" + e.name + "\" vào chính nó");
            boolean samePlace = sameDrive && target.equalsIgnoreCase(e.path);
            if (samePlace) {
                if (cut) continue;                       // chuyển vào chỗ cũ: không làm gì
                target = uniquePath(d, dstDir, e.name);  // chép vào chỗ cũ: tạo bản sao "tên (1)"
            } else if (exists(d, target)) {
                if (policy == KEEP_BOTH) target = uniquePath(d, dstDir, e.name);
                else if (policy == SKIP && !e.dir) continue;
                // OVERWRITE, hoặc thư mục (SKIP): gộp vào thư mục có sẵn
            }
            if (sameDrive && cut) {
                if (!exists(d, target)) {
                    job.start(e.name, -1);
                    rename(s, e.path, target, false);
                    job.finishItem();
                } else if (e.dir && isDir(d, target)) {
                    moveMerge(s, e.path, target, policy, job);
                } else if (policy == OVERWRITE && !e.dir && !isDir(d, target)) {
                    job.start(e.name, -1);
                    rename(s, e.path, target, true);
                    job.finishItem();
                }
            } else {
                copyTree(s, e.path, e.dir, d, target, policy, cut, job);
            }
        }
    }

    public static void delete(DiskShare s, Entry e) {
        if (e.dir) s.rmdir(e.path, true); else s.rm(e.path);
    }

    /** Đếm tệp/thư mục/dung lượng (đệ quy). Kết quả: {số tệp, số thư mục, tổng byte}. */
    public static long[] stats(DiskShare s, List<Entry> items, Job job) throws IOException {
        long[] r = new long[3];
        ArrayDeque<String> q = new ArrayDeque<>();
        for (Entry e : items) {
            if (e.dir) { r[1]++; q.add(e.path); } else { r[0]++; r[2] += e.size; }
        }
        while (!q.isEmpty()) {
            job.check();
            String dir = q.poll();
            for (Entry c : list(s, dir, true)) {
                if (c.dir) { r[1]++; q.add(c.path); } else { r[0]++; r[2] += c.size; }
            }
            job.done = (int) r[0];
            job.bytes = r[2];
            job.item = dir;
            job.changed();
        }
        return r;
    }

    /** Tìm theo tên (không phân biệt dấu/hoa thường) trong dir và mọi thư mục con. */
    public static List<Entry> search(DiskShare s, String dir, String query, boolean showHidden, int limit, Job job) throws IOException {
        String qf = fold(query.trim());
        List<Entry> out = new ArrayList<>();
        ArrayDeque<String> q = new ArrayDeque<>();
        q.add(dir);
        int scanned = 0;
        while (!q.isEmpty() && out.size() < limit) {
            job.check();
            String d = q.poll();
            scanned++;
            job.item = "Đã xem " + scanned + " thư mục, thấy " + out.size() + "\n" + (d.isEmpty() ? "\\" : d);
            job.changed();
            List<Entry> l;
            try { l = list(s, d, showHidden); } catch (SMBApiException ex) { continue; } // thư mục không có quyền: bỏ qua
            for (Entry c : l) {
                if (fold(c.name).contains(qf)) {
                    out.add(c);
                    if (out.size() >= limit) break;
                }
                if (c.dir) q.add(c.path);
            }
        }
        return out;
    }

    private Fs() {}
}
