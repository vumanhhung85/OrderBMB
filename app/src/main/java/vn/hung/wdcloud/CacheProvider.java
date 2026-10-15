package vn.hung.wdcloud;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Cho app khác đọc tệp tạm đã tải từ WD (thư mục cache của app).
 * Chỉ cho GHI vào cache/edit (sửa bằng app khác) và cache/cam (máy ảnh chụp vào).
 */
public class CacheProvider extends ContentProvider {
    static final String AUTH = "vn.hung.wdcloud.files";

    static Uri uriFor(Context c, File f) throws IOException {
        String base = c.getCacheDir().getCanonicalPath();
        String p = f.getCanonicalPath();
        if (!p.startsWith(base + File.separator)) throw new IOException("Tệp ngoài cache");
        Uri.Builder b = new Uri.Builder().scheme("content").authority(AUTH);
        for (String seg : p.substring(base.length() + 1).split(File.separator)) b.appendPath(seg);
        return b.build();
    }

    private File fileOf(Uri uri) throws FileNotFoundException {
        try {
            File base = getContext().getCacheDir().getCanonicalFile();
            String rel = uri.getPath() == null ? "" : uri.getPath();
            File f = new File(base, rel).getCanonicalFile();
            if (!f.getPath().startsWith(base.getPath() + File.separator) || !f.isFile())
                throw new FileNotFoundException(rel);
            return f;
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
    }

    private boolean writable(File f) {
        try {
            String base = getContext().getCacheDir().getCanonicalPath();
            String p = f.getCanonicalPath();
            return p.startsWith(base + File.separator + "edit" + File.separator)
                    || p.startsWith(base + File.separator + "cam" + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    @Override public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = fileOf(uri);
        int m = ParcelFileDescriptor.parseMode(mode == null ? "r" : mode);
        boolean wantsWrite = (m & ParcelFileDescriptor.MODE_WRITE_ONLY) != 0 || (m & ParcelFileDescriptor.MODE_READ_WRITE) == ParcelFileDescriptor.MODE_READ_WRITE;
        if (wantsWrite && !writable(f)) throw new FileNotFoundException("Chỉ đọc");
        return ParcelFileDescriptor.open(f, wantsWrite ? m : ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try { f = fileOf(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String seg = uri.getLastPathSegment();
        return seg == null ? "application/octet-stream" : Kind.mime(seg);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override public int delete(Uri uri, String sel, String[] args) { return 0; }

    @Override public int update(Uri uri, ContentValues values, String sel, String[] args) { return 0; }
}
