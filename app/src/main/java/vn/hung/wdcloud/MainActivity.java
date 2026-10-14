package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Duyệt file trên WD: mở, gửi sang app khác, lưu về máy, tải lên, đổi tên, xoá, tạo thư mục.
 * Mở với EXTRA_PICK = true thì thành màn hình chọn thư mục (dùng cho màn hình nhận file chia sẻ).
 */
public class MainActivity extends Activity {
    static final String EXTRA_PICK = "pick";
    static final String EXTRA_START = "start";
    static final String EXTRA_PATH = "path";

    private static final int REQ_UPLOAD = 1, REQ_SAVE = 2, REQ_SETTINGS = 3;

    private boolean pick;
    private String cur = "";
    private String loadedKey;
    private int gen;

    private TextView pathBar, status;
    private ProgressBar spin;
    private final Adapter adapter = new Adapter();
    private Smb.Entry pendingSave;
    private final SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        if (pick) {
            cur = Cfg.normPath(getIntent().getStringExtra(EXTRA_START));
            setTitle("Chọn thư mục lưu");
            if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        pathBar = Ui.text(this, "", 13, Color.WHITE);
        pathBar.setBackgroundColor(Color.parseColor("#0D47A1"));
        int p = Ui.dp(this, 12);
        pathBar.setPadding(p, Ui.dp(this, 6), p, Ui.dp(this, 6));
        pathBar.setSingleLine(true);
        pathBar.setEllipsize(TextUtils.TruncateAt.START);
        root.addView(pathBar);

        spin = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        spin.setIndeterminate(true);
        spin.setVisibility(View.GONE);
        root.addView(spin, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4)));

        status = Ui.text(this, "", 15, Ui.GRAY);
        status.setPadding(p, p, p, p);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        status.setOnClickListener(v -> load(cur));
        root.addView(status);

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> onTap(adapter.get(pos)));
        if (!pick) list.setOnItemLongClickListener((parent, view, pos, id) -> {
            Smb.Entry e = adapter.get(pos);
            if (e != null) actions(e);
            return true;
        });
        root.addView(list, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        if (pick) {
            LinearLayout bar = new LinearLayout(this);
            bar.setPadding(Ui.dp(this, 8), Ui.dp(this, 4), Ui.dp(this, 8), Ui.dp(this, 8));
            Button nf = Ui.button(this, "+ Thư mục mới", false);
            Button ok = Ui.button(this, "Chọn thư mục này", true);
            nf.setOnClickListener(v -> newFolder());
            ok.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_PATH, cur));
                finish();
            });
            bar.addView(nf, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            bar.addView(ok, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            root.addView(bar);
        }
        setContentView(root);
        cleanOldCache();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Cfg.Conf k = Cfg.load(this);
        if (!k.ready()) {
            if (!pick) {
                showStatus("Chưa có thông tin kết nối.\nChạm vào đây hoặc vào ⋮ → Cài đặt để nhập.", Ui.GRAY);
                status.setOnClickListener(v -> openSettings());
                if (loadedKey == null) {
                    loadedKey = "";
                    openSettings();
                }
            }
            adapter.set(new ArrayList<>());
            updatePath();
            return;
        }
        status.setOnClickListener(v -> load(cur));
        if (!k.key().equals(loadedKey)) {
            if (loadedKey != null && !loadedKey.isEmpty()) cur = "";
            loadedKey = k.key();
            load(cur);
        }
    }

    private void openSettings() {
        startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS);
    }

    private void updatePath() {
        Cfg.Conf k = Cfg.load(this);
        pathBar.setText(k.ready() ? k.label() + (cur.isEmpty() ? "" : "\\" + cur) : "WD Cloud");
    }

    private void showStatus(String s, int color) {
        status.setText(s);
        status.setTextColor(color);
        status.setVisibility(View.VISIBLE);
    }

    private void load(final String dir) {
        final int g = ++gen;
        cur = dir;
        updatePath();
        spin.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        Smb.BG.execute(() -> {
            List<Smb.Entry> l = null;
            String err = null;
            try {
                l = Smb.list(this, dir, pick);
            } catch (Exception e) {
                err = Smb.explain(e);
            }
            final List<Smb.Entry> fl = l;
            final String ferr = err;
            runOnUiThread(() -> {
                if (g != gen || isFinishing()) return;
                spin.setVisibility(View.GONE);
                if (ferr != null) {
                    adapter.set(new ArrayList<>());
                    showStatus("✗ " + ferr + "\n\nChạm để thử lại", Ui.RED);
                    return;
                }
                adapter.set(fl);
                if (fl.isEmpty()) showStatus(pick ? "Không có thư mục con" : "Thư mục trống", Ui.GRAY);
            });
        });
    }

    private void onTap(Smb.Entry e) {
        if (e == null) { // dòng "thư mục cha"
            load(Smb.parent(cur));
        } else if (e.dir) {
            load(e.path);
        } else if (!pick) {
            openFile(e, false);
        }
    }

    @Override
    public void onBackPressed() {
        if (!cur.isEmpty()) load(Smb.parent(cur));
        else super.onBackPressed();
    }

    // ---------- Menu ----------

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        if (!pick) {
            m.add(0, 1, 0, "Tải lên").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            m.add(0, 2, 0, "Thư mục mới");
            m.add(0, 3, 0, "Làm mới");
            m.add(0, 4, 0, "Cài đặt");
        } else {
            m.add(0, 3, 0, "Làm mới");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case 1:
                if (!Cfg.load(this).ready()) { openSettings(); return true; }
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_UPLOAD);
                return true;
            case 2:
                newFolder();
                return true;
            case 3:
                Smb.reset();
                load(cur);
                return true;
            case 4:
                openSettings();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---------- Thao tác trên tệp ----------

    private void actions(final Smb.Entry e) {
        final String[] items = e.dir
                ? new String[]{"Mở", "Đổi tên", "Xoá"}
                : new String[]{"Mở", "Gửi qua Zalo/app khác", "Lưu về điện thoại", "Đổi tên", "Xoá"};
        new AlertDialog.Builder(this).setTitle(e.name).setItems(items, (d, w) -> {
            String s = items[w];
            if (s.equals("Mở")) onTap(e);
            else if (s.startsWith("Gửi")) openFile(e, true);
            else if (s.startsWith("Lưu")) saveToDevice(e);
            else if (s.equals("Đổi tên")) renameDialog(e);
            else if (s.equals("Xoá")) deleteDialog(e);
        }).show();
    }

    /** Tải file về bộ nhớ tạm rồi mở (share = false) hoặc gửi sang app khác (share = true). */
    private void openFile(final Smb.Entry e, final boolean share) {
        final Ui.Prog prog = new Ui.Prog(this, share ? "Đang chuẩn bị gửi" : "Đang tải về để mở");
        Smb.BG.execute(() -> {
            File dir = new File(getCacheDir(), "open/" + System.currentTimeMillis());
            File f = new File(dir, Uploader.sanitize(e.name));
            try {
                if (!dir.mkdirs() && !dir.isDirectory()) throw new java.io.IOException("Không tạo được thư mục tạm");
                try (OutputStream out = new FileOutputStream(f)) {
                    Smb.download(this, e.path, out, done -> prog.update(e.name, done, e.size));
                }
                final Uri uri = CacheProvider.uriFor(this, f);
                prog.dismiss();
                runOnUiThread(() -> launch(uri, e.name, share));
            } catch (Exception ex) {
                f.delete();
                prog.dismiss();
                toast(Smb.explain(ex));
            }
        });
    }

    private void launch(Uri uri, String name, boolean share) {
        String mime = Smb.mime(name);
        if (share) {
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType(mime);
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.setClipData(ClipData.newRawUri(name, uri));
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(s, "Gửi " + name + " qua");
            ch.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(this, ShareActivity.class)});
            startActivity(ch);
            return;
        }
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(uri, mime);
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(v);
        } catch (ActivityNotFoundException ex) {
            v.setDataAndType(uri, "*/*");
            try {
                startActivity(Intent.createChooser(v, "Mở " + name + " bằng"));
            } catch (ActivityNotFoundException ex2) {
                toast("Không có app nào mở được loại tệp này");
            }
        }
    }

    private void saveToDevice(Smb.Entry e) {
        pendingSave = e;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(Smb.mime(e.name));
        i.putExtra(Intent.EXTRA_TITLE, e.name);
        startActivityForResult(i, REQ_SAVE);
    }

    private void doSave(final Smb.Entry e, final Uri dest) {
        final Ui.Prog prog = new Ui.Prog(this, "Đang lưu về điện thoại");
        Smb.BG.execute(() -> {
            boolean ok = false;
            try (OutputStream out = getContentResolver().openOutputStream(dest)) {
                if (out == null) throw new java.io.IOException("Không ghi được vào nơi đã chọn");
                Smb.download(this, e.path, out, done -> prog.update(e.name, done, e.size));
                ok = true;
            } catch (Exception ex) {
                toast(Smb.explain(ex));
            }
            if (!ok) {
                try { DocumentsContract.deleteDocument(getContentResolver(), dest); } catch (Exception ignored) {}
            } else {
                toast("Đã lưu " + e.name);
            }
            prog.dismiss();
        });
    }

    private void uploadUris(List<Uri> uris) {
        final List<Uploader.Item> items = new ArrayList<>();
        for (Uri u : uris) items.add(Uploader.fromUri(this, u, null));
        if (items.isEmpty()) return;
        final String dir = cur;
        final Ui.Prog prog = new Ui.Prog(this, "Đang tải lên WD");
        Smb.BG.execute(() -> {
            String msg;
            try {
                Uploader.upload(this, items, dir, (idx, total, name, done, size) ->
                        prog.update((total > 1 ? "(" + (idx + 1) + "/" + total + ") " : "") + name, done, size));
                msg = "Đã tải lên " + items.size() + " tệp";
            } catch (Exception ex) {
                int n = Uploader.savedCount(items);
                msg = (n > 0 ? "Đã lưu " + n + "/" + items.size() + " tệp. " : "") + Smb.explain(ex);
            }
            prog.dismiss();
            toast(msg);
            runOnUiThread(() -> { if (dir.equals(cur)) load(cur); });
        });
    }

    private void newFolder() {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setHint("Tên thư mục");
        new AlertDialog.Builder(this).setTitle("Thư mục mới").setView(pad(in))
                .setPositiveButton("Tạo", (d, w) -> {
                    final String name = Uploader.sanitize(in.getText().toString());
                    final String path = Smb.join(cur, name);
                    Smb.BG.execute(() -> {
                        try {
                            Smb.mkdir(this, path);
                            runOnUiThread(() -> load(pick ? path : cur));
                        } catch (Exception ex) {
                            toast(Smb.explain(ex));
                        }
                    });
                })
                .setNegativeButton("Huỷ", null).show();
    }

    private void renameDialog(final Smb.Entry e) {
        final EditText in = new EditText(this);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setText(e.name);
        int dot = e.dir ? -1 : e.name.lastIndexOf('.');
        in.setSelection(0, dot > 0 ? dot : e.name.length());
        new AlertDialog.Builder(this).setTitle("Đổi tên").setView(pad(in))
                .setPositiveButton("Đổi", (d, w) -> {
                    final String name = Uploader.sanitize(in.getText().toString());
                    if (name.equals(e.name)) return;
                    Smb.BG.execute(() -> {
                        try {
                            Smb.rename(this, e.path, Smb.join(Smb.parent(e.path), name));
                            runOnUiThread(() -> load(cur));
                        } catch (Exception ex) {
                            toast(Smb.explain(ex));
                        }
                    });
                })
                .setNegativeButton("Huỷ", null).show();
    }

    private void deleteDialog(final Smb.Entry e) {
        new AlertDialog.Builder(this).setTitle("Xoá " + (e.dir ? "thư mục" : "tệp") + "?")
                .setMessage(e.name + (e.dir ? "\n\nToàn bộ nội dung bên trong cũng bị xoá, không khôi phục được." : "\n\nKhông khôi phục được."))
                .setPositiveButton("Xoá", (d, w) -> Smb.BG.execute(() -> {
                    try {
                        Smb.delete(this, e);
                        runOnUiThread(() -> load(cur));
                    } catch (Exception ex) {
                        toast(Smb.explain(ex));
                    }
                }))
                .setNegativeButton("Huỷ", null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_UPLOAD && res == RESULT_OK && data != null) {
            List<Uri> uris = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            uploadUris(uris);
        } else if (req == REQ_SAVE && res == RESULT_OK && data != null && data.getData() != null && pendingSave != null) {
            doSave(pendingSave, data.getData());
            pendingSave = null;
        }
    }

    // ---------- Phụ ----------

    private View pad(View v) {
        LinearLayout box = new LinearLayout(this);
        int p = Ui.dp(this, 20);
        box.setPadding(p, Ui.dp(this, 8), p, 0);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void toast(final String s) {
        runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_LONG).show());
    }

    /** Xoá file tạm đã mở từ hơn 1 ngày trước. */
    private void cleanOldCache() {
        Smb.BG.execute(() -> {
            File[] ds = new File(getCacheDir(), "open").listFiles();
            if (ds == null) return;
            long limit = System.currentTimeMillis() - 24 * 3600 * 1000L;
            for (File d : ds) if (d.lastModified() < limit) deleteTree(d);
        });
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    /** Danh sách; khi không ở gốc thì dòng đầu là "thư mục cha" (item null). */
    private final class Adapter extends BaseAdapter {
        private List<Smb.Entry> items = new ArrayList<>();

        void set(List<Smb.Entry> l) {
            items = l;
            notifyDataSetChanged();
        }

        private boolean up() { return !cur.isEmpty(); }

        Smb.Entry get(int pos) { return up() ? (pos == 0 ? null : items.get(pos - 1)) : items.get(pos); }

        @Override public int getCount() { return items.size() + (up() ? 1 : 0); }

        @Override public Object getItem(int pos) { return get(pos); }

        @Override public long getItemId(int pos) { return pos; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Row r;
            if (v == null) {
                r = new Row();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(MainActivity.this, 12);
                row.setPadding(p, Ui.dp(MainActivity.this, 10), p, Ui.dp(MainActivity.this, 10));
                r.icon = Ui.text(MainActivity.this, "", 24, Color.BLACK);
                r.icon.setPadding(0, 0, p, 0);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                r.name = Ui.text(MainActivity.this, "", 16, Color.parseColor("#111827"));
                r.name.setSingleLine(true);
                r.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                r.sub = Ui.text(MainActivity.this, "", 12, Ui.GRAY);
                col.addView(r.name);
                col.addView(r.sub);
                row.addView(r.icon);
                row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(r);
                v = row;
            } else {
                r = (Row) v.getTag();
            }
            Smb.Entry e = get(pos);
            if (e == null) {
                r.icon.setText("⬆️");
                r.name.setText("Lên thư mục trên");
                r.sub.setVisibility(View.GONE);
            } else {
                r.icon.setText(Smb.icon(e));
                r.name.setText(e.name);
                String d = e.mtime > 0 ? df.format(new Date(e.mtime)) : "";
                r.sub.setText(e.dir ? d : d + (d.isEmpty() ? "" : " · ") + Ui.size(MainActivity.this, e.size));
                r.sub.setVisibility(View.VISIBLE);
            }
            return v;
        }
    }

    private static final class Row {
        TextView icon, name, sub;
    }
}
