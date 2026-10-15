package vn.hung.wdcloud;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.ByteArrayInputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Fs;
import vn.hung.wdcloud.core.Job;
import vn.hung.wdcloud.core.SmbPool;

/** Đọc tệp trong điện thoại (chia sẻ, chọn tệp, chọn thư mục, chụp ảnh) và tải lên WD. */
final class Uploader {

    static final class Item {
        Uri uri;
        byte[] data;      // nội dung chữ (khi chia sẻ đoạn văn bản/đường link)
        String name;
        String relDir = "";  // thư mục con tương đối (khi tải cả thư mục)
        boolean dirOnly;     // chỉ tạo thư mục (thư mục rỗng)
        long size = -1;
        String savedPath;    // đã lưu xong (để "Thử lại" không tải trùng)
    }

    static String stamp() { return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()); }

    /** Lấy danh sách tệp từ Intent chia sẻ (SEND / SEND_MULTIPLE). */
    @SuppressWarnings("deprecation")
    static List<Item> fromShareIntent(Context c, Intent in) {
        List<Uri> uris = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Parcelable> l = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (l != null) for (Parcelable p : l) if (p instanceof Uri) uris.add((Uri) p);
        } else {
            Parcelable p = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (p instanceof Uri) uris.add((Uri) p);
        }
        if (uris.isEmpty() && in.getClipData() != null) {
            ClipData cd = in.getClipData();
            for (int i = 0; i < cd.getItemCount(); i++) {
                Uri u = cd.getItemAt(i).getUri();
                if (u != null) uris.add(u);
            }
        }
        List<Item> out = new ArrayList<>();
        for (Uri u : uris) out.add(fromUri(c, u, in.getType()));
        if (out.isEmpty()) {
            CharSequence text = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (text != null && text.length() > 0) {
                String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
                String body = (subject != null && !subject.isEmpty() ? subject + "\n\n" : "") + text;
                Item it = new Item();
                it.data = body.getBytes(StandardCharsets.UTF_8);
                it.size = it.data.length;
                it.name = "GhiChu_" + stamp() + ".txt";
                out.add(it);
            }
        }
        return out;
    }

    static Item fromFile(java.io.File f, String name) {
        Item it = new Item();
        it.uri = Uri.fromFile(f);
        it.name = name;
        it.size = f.length();
        return it;
    }

    static Item fromUri(Context c, Uri u, String typeHint) {
        Item it = new Item();
        it.uri = u;
        if ("file".equals(u.getScheme()) && u.getPath() != null) {
            java.io.File f = new java.io.File(u.getPath());
            it.name = f.getName();
            it.size = f.length();
        } else {
            try (Cursor cur = c.getContentResolver().query(u,
                    new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
                if (cur != null && cur.moveToFirst()) {
                    int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (i >= 0 && !cur.isNull(i)) it.name = cur.getString(i);
                    int j = cur.getColumnIndex(OpenableColumns.SIZE);
                    if (j >= 0 && !cur.isNull(j)) it.size = cur.getLong(j);
                }
            } catch (Exception ignored) {
            }
        }
        if (it.name == null || it.name.trim().isEmpty()) {
            String seg = u.getLastPathSegment();
            if (seg != null) seg = seg.substring(Math.max(seg.lastIndexOf('/'), seg.lastIndexOf(':')) + 1);
            it.name = (seg == null || seg.trim().isEmpty()) ? "Tep_" + stamp() : seg;
        }
        it.name = clean(it.name);
        if (it.name.lastIndexOf('.') <= 0) {
            String t = null;
            try { t = c.getContentResolver().getType(u); } catch (Exception ignored) {}
            if (t == null) t = typeHint;
            String ext = t == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(t);
            if (ext != null) it.name += "." + ext;
        }
        return it;
    }

    static String clean(String n) {
        String s = Fs.cleanName(n);
        return s.isEmpty() ? "Tep" : s;
    }

    /** Tên thư mục gốc đã chọn bằng "Tải lên cả thư mục". */
    static String treeName(Context c, Uri tree) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, docId);
        try (Cursor cur = c.getContentResolver().query(u, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst() && !cur.isNull(0)) return clean(cur.getString(0));
        } catch (Exception ignored) {}
        return "ThuMuc_" + stamp();
    }

    /** Liệt kê đệ quy mọi tệp trong thư mục điện thoại đã chọn. */
    static List<Item> fromTree(Context c, Uri tree, Job job) throws IOException {
        List<Item> out = new ArrayList<>();
        walk(c, tree, DocumentsContract.getTreeDocumentId(tree), "", out, job);
        return out;
    }

    private static void walk(Context c, Uri tree, String docId, String rel, List<Item> out, Job job) throws IOException {
        job.check();
        job.item = rel.isEmpty() ? "Đang đọc thư mục…" : rel;
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        String[] cols = {DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE};
        List<String[]> dirs = new ArrayList<>();
        int files = 0;
        try (Cursor cur = c.getContentResolver().query(kids, cols, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = clean(cur.getString(1)), mime = cur.getString(2);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    dirs.add(new String[]{id, name});
                } else {
                    Item it = new Item();
                    it.uri = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    it.name = name;
                    it.relDir = rel;
                    it.size = cur.isNull(3) ? -1 : cur.getLong(3);
                    out.add(it);
                    files++;
                }
            }
        }
        if (files == 0 && dirs.isEmpty() && !rel.isEmpty()) {
            Item it = new Item();
            it.dirOnly = true;
            it.relDir = rel;
            it.name = "";
            out.add(it);
        }
        for (String[] d : dirs) walk(c, tree, d[0], Fs.join(rel, d[1]), out, job);
    }

    private static InputStream open(Context c, Item it) throws IOException {
        if (it.data != null) return new ByteArrayInputStream(it.data);
        if ("file".equals(it.uri.getScheme())) return new FileInputStream(it.uri.getPath());
        InputStream in = c.getContentResolver().openInputStream(it.uri);
        if (in == null) throw new IOException("Không đọc được tệp " + it.name);
        return in;
    }

    /** Tải lên các mục chưa lưu vào thư mục dir của ổ (tự tạo thư mục). Trùng tên thì đặt "tên (1)". */
    static void upload(final Context c, final List<Item> items, final Drive drive, final String dir, final Job job) throws Exception {
        SmbPool.run(drive, s -> { Fs.mkdirs(s, dir); return null; });
        SmbPool.runOnce(drive, s -> {
            Set<String> made = new HashSet<>();
            int files = 0;
            for (Item it : items) if (!it.dirOnly) files++;
            job.count = files;
            job.done = savedCount(items);
            for (Item it : items) {
                if (it.savedPath != null) continue;
                job.check();
                String target = Fs.join(dir, it.relDir);
                if (!it.relDir.isEmpty() && made.add(target.toLowerCase(Locale.ROOT))) Fs.mkdirs(s, target);
                if (it.dirOnly) { it.savedPath = target; continue; }
                String path = Fs.uniquePath(s, target, it.name);
                job.start(it.name, it.size);
                try (InputStream in = open(c, it)) {
                    Fs.upload(s, path, in, false, job);
                }
                it.savedPath = path;
                job.finishItem();
            }
            return null;
        });
    }

    static int savedCount(List<Item> items) {
        int n = 0;
        for (Item it : items) if (it.savedPath != null && !it.dirOnly) n++;
        return n;
    }

    private Uploader() {}
}
