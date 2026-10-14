package vn.hung.wdcloud;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Parcelable;
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
import java.util.List;
import java.util.Locale;

/** Đọc các tệp được chia sẻ/chọn trong điện thoại và tải lên WD. */
final class Uploader {

    static final class Item {
        Uri uri;
        byte[] data;      // nội dung chữ (khi chia sẻ đoạn văn bản/đường link)
        String name;
        long size = -1;
        String savedPath; // đã lưu xong (để "Thử lại" không tải trùng)
    }

    interface Listener { boolean on(int index, int total, String name, long done, long size); }

    /** Lấy danh sách tệp từ Intent chia sẻ (SEND / SEND_MULTIPLE). */
    @SuppressWarnings("deprecation")
    static List<Item> fromShareIntent(Context c, Intent in) {
        List<Uri> uris = new ArrayList<>();
        String action = in.getAction();
        if (Intent.ACTION_SEND_MULTIPLE.equals(action)) {
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
                it.name = "GhiChu_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
                out.add(it);
            }
        }
        return out;
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
            if (seg != null) {
                int k = Math.max(seg.lastIndexOf('/'), seg.lastIndexOf(':'));
                seg = seg.substring(k + 1);
            }
            it.name = (seg == null || seg.trim().isEmpty())
                    ? "Tep_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date())
                    : seg;
        }
        it.name = sanitize(it.name);
        if (it.name.lastIndexOf('.') <= 0) {
            String t = null;
            try { t = c.getContentResolver().getType(u); } catch (Exception ignored) {}
            if (t == null) t = typeHint;
            String ext = t == null ? null : MimeTypeMap.getSingleton().getExtensionFromMimeType(t);
            if (ext != null) it.name += "." + ext;
        }
        return it;
    }

    /** Bỏ ký tự Windows không cho phép trong tên tệp. */
    static String sanitize(String n) {
        n = n.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        while (n.endsWith(".") || n.endsWith(" ")) n = n.substring(0, n.length() - 1);
        if (n.length() > 180) {
            int dot = n.lastIndexOf('.');
            String ext = dot > 0 && n.length() - dot <= 10 ? n.substring(dot) : "";
            n = n.substring(0, 180 - ext.length()) + ext;
        }
        return n.isEmpty() ? "Tep" : n;
    }

    private static InputStream open(Context c, Item it) throws IOException {
        if (it.data != null) return new ByteArrayInputStream(it.data);
        if ("file".equals(it.uri.getScheme())) return new FileInputStream(it.uri.getPath());
        InputStream in = c.getContentResolver().openInputStream(it.uri);
        if (in == null) throw new IOException("Không đọc được tệp " + it.name);
        return in;
    }

    /** Tải lên các mục chưa lưu vào thư mục dir (tự tạo thư mục nếu chưa có). */
    static void upload(final Context c, final List<Item> items, final String dir, final Listener l) throws Exception {
        Smb.run(c, s -> { Smb.mkdirs(s, dir); return null; }); // kiểm tra kết nối (có nối lại) + tạo thư mục
        Smb.runOnce(c, s -> {
            final int total = items.size();
            for (int i = 0; i < total; i++) {
                final Item it = items.get(i);
                if (it.savedPath != null) continue;
                final int idx = i;
                String path = Smb.uniquePath(s, dir, it.name);
                if (!l.on(idx, total, it.name, 0, it.size)) throw new Smb.Cancelled();
                try (InputStream in = open(c, it)) {
                    Smb.uploadStream(s, path, in, done -> l.on(idx, total, it.name, done, it.size));
                }
                it.savedPath = path;
            }
            return null;
        });
    }

    static int savedCount(List<Item> items) {
        int n = 0;
        for (Item it : items) if (it.savedPath != null) n++;
        return n;
    }

    private Uploader() {}
}
