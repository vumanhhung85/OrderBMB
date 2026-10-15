package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseBooleanArray;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.SearchView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Errors;
import vn.hung.wdcloud.core.Fs;
import vn.hung.wdcloud.core.Job;
import vn.hung.wdcloud.core.SmbPool;

/**
 * Màn hình chính kiểu Explorer.
 * - drive == null: danh sách ổ + thư mục ghim.
 * - drive != null: nội dung thư mục cur của ổ; nhấn giữ để chọn nhiều (chép, cắt, xoá, gửi, tải về…).
 * Mở với EXTRA_PICK = true thì thành màn hình chọn thư mục lưu (cho màn hình nhận file chia sẻ).
 */
public class MainActivity extends Activity {
    static final String EXTRA_PICK = "pick", EXTRA_DRIVE = "drive", EXTRA_START = "start", EXTRA_PATH = "path";

    private static final int REQ_UPLOAD = 1, REQ_UPLOAD_TREE = 2, REQ_DOWNLOAD_TREE = 3, REQ_CAMERA = 4, REQ_DRIVE = 5, REQ_TEXT = 6;
    private static final int F_VIEW = 0, F_OPENWITH = 1, F_SEND = 2, F_EDIT = 3;
    private static final int M_SEARCH = 1, M_REFRESH = 2, M_ALL = 3, M_PIN = 4, M_PROPS = 5, M_PATH = 6, M_HIDDEN = 7,
            M_HOME = 8, M_SETTINGS = 9, M_ABOUT = 10;
    private static final int A_ALL = 101, A_RENAME = 102, A_OPENWITH = 103, A_EDIT = 104, A_PATH = 105, A_PIN = 106, A_PROPS = 107;

    private boolean pick;
    private Drive drive;                       // null = danh sách ổ
    private String cur = "";
    private List<Fs.Entry> all = new ArrayList<>();
    private List<Fs.Entry> results;            // kết quả "tìm trong thư mục con" (null = không có)
    private String resultQuery = "", filter = "";
    private final List<Object> rows = new ArrayList<>();
    private final Map<String, Drive> driveMap = new HashMap<>();
    private final Map<String, Integer> scroll = new HashMap<>();
    private int gen;
    private String camPath;
    private Drive dlDrive;
    private List<Fs.Entry> dlItems;
    private ActionMode mode;

    private LinearLayout crumbs, bottom, clipBar;
    private HorizontalScrollView crumbScroll;
    private TextView status, hint, clipText;
    private ProgressBar spin;
    private ListView list;
    private SearchView searchView;
    private MenuItem searchItem;
    private final Adapter adapter = new Adapter();
    private final SimpleDateFormat df = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US);

    // =====================================================================
    // Khởi tạo
    // =====================================================================

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        pick = getIntent().getBooleanExtra(EXTRA_PICK, false);
        if (pick) {
            setTitle("Chọn nơi lưu");
            if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        }
        buildViews();

        if (b != null) {
            drive = Cfg.drive(this, b.getString("drive"));
            cur = b.getString("cur", "");
            camPath = b.getString("cam");
            if (drive == null) cur = "";
        } else if (pick) {
            drive = Cfg.drive(this, getIntent().getStringExtra(EXTRA_DRIVE));
            cur = drive == null ? "" : Fs.norm(getIntent().getStringExtra(EXTRA_START));
        } else {
            List<Drive> ds = Cfg.drives(this);
            if (ds.size() == 1) drive = ds.get(0);
            if (ds.isEmpty()) startActivityForResult(new Intent(this, DriveEditActivity.class), REQ_DRIVE);
        }
        show();
        cleanCache();
    }

    private void buildViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbScroll.setBackgroundColor(Ui.BRAND_DARK);
        crumbs = new LinearLayout(this);
        crumbs.setGravity(Gravity.CENTER_VERTICAL);
        crumbs.setPadding(Ui.dp(this, 4), 0, Ui.dp(this, 12), 0);
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll);

        spin = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        spin.setIndeterminate(true);
        spin.setVisibility(View.INVISIBLE);
        root.addView(spin, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4)));

        int p = Ui.dp(this, 12);
        hint = Ui.text(this, "", 15, Ui.BRAND);
        hint.setPadding(p, p, p, p);
        hint.setBackgroundColor(Ui.BAR);
        hint.setVisibility(View.GONE);
        hint.setOnClickListener(v -> deepSearch(filter));
        root.addView(hint);

        status = Ui.text(this, "", 15, Ui.GRAY);
        status.setPadding(p, p * 2, p, p);
        status.setGravity(Gravity.CENTER);
        status.setVisibility(View.GONE);
        root.addView(status);

        list = new ListView(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> onTap(pos));
        root.addView(list, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        clipBar = new LinearLayout(this);
        clipBar.setGravity(Gravity.CENTER_VERTICAL);
        clipBar.setBackgroundColor(Color.parseColor("#FFF7E0"));
        clipBar.setPadding(p, Ui.dp(this, 4), Ui.dp(this, 4), Ui.dp(this, 4));
        clipText = Ui.text(this, "", 14, Ui.TEXT);
        clipBar.addView(clipText, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView pasteBtn = Ui.text(this, "Dán vào đây", 15, Ui.BRAND);
        pasteBtn.setTypeface(Typeface.DEFAULT_BOLD);
        pasteBtn.setPadding(p, p, p, p);
        Ui.setRipple(this, pasteBtn);
        pasteBtn.setOnClickListener(v -> paste());
        TextView clearBtn = Ui.text(this, "✕", 18, Ui.GRAY);
        clearBtn.setPadding(p, p, p, p);
        Ui.setRipple(this, clearBtn);
        clearBtn.setOnClickListener(v -> { Clip.clear(); updateBars(); });
        clipBar.addView(pasteBtn);
        clipBar.addView(clearBtn);
        root.addView(clipBar);

        View line = new View(this);
        line.setBackgroundColor(Color.parseColor("#E5E7EB"));
        root.addView(line, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        bottom = new LinearLayout(this);
        bottom.setBackgroundColor(Ui.BAR);
        root.addView(bottom);
        setContentView(root);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("drive", drive == null ? null : drive.id);
        out.putString("cur", cur);
        out.putString("cam", camPath);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (drive != null) {
            Drive fresh = Cfg.drive(this, drive.id);
            if (fresh == null) { drive = null; cur = ""; show(); }
            else if (!fresh.key().equals(drive.key()) || !fresh.title().equals(drive.title())) { drive = fresh; show(); }
        } else {
            buildHome();
        }
        checkPendingEdit();
    }

    @Override
    protected void onPause() {
        super.onPause();
        JSONObject p = Cfg.pendingEdit(this);
        if (p != null && !p.optBoolean("armed")) {
            try { p.put("armed", true); } catch (Exception ignored) {}
            Cfg.setPendingEdit(this, p);
        }
    }

    // =====================================================================
    // Điều hướng
    // =====================================================================

    private String scrollKey() { return (drive == null ? "" : drive.id) + "|" + cur; }

    private void navigate(Drive d, String dir) {
        scroll.put(scrollKey(), list.getFirstVisiblePosition());
        if (mode != null) mode.finish();
        if (searchItem != null && searchItem.isActionViewExpanded()) searchItem.collapseActionView();
        filter = "";
        results = null;
        drive = d;
        cur = d == null ? "" : Fs.norm(dir);
        show();
    }

    /** Dựng lại toàn bộ màn hình theo chế độ hiện tại. */
    private void show() {
        if (mode != null) mode.finish();
        list.clearChoices();
        if (drive == null || pick) {
            list.setChoiceMode(AbsListView.CHOICE_MODE_NONE);
            list.setOnItemLongClickListener(drive == null && !pick ? (AdapterView.OnItemLongClickListener) (pa, v, pos, id) -> {
                homeMenu(pos);
                return true;
            } : null);
        } else {
            list.setOnItemLongClickListener(null);
            list.setChoiceMode(AbsListView.CHOICE_MODE_MULTIPLE_MODAL);
            list.setMultiChoiceModeListener(selection);
        }
        if (!pick) setTitle(drive == null ? "WD Cloud" : drive.title());
        updateBars();
        invalidateOptionsMenu();
        if (drive == null) buildHome();
        else load();
    }

    private void buildHome() {
        if (drive != null) return;
        gen++;
        spin.setVisibility(View.INVISIBLE);
        hint.setVisibility(View.GONE);
        rows.clear();
        List<Drive> ds = Cfg.drives(this);
        driveMap.clear();
        for (Drive d : ds) driveMap.put(d.id, d);
        rows.add("Ổ ĐĨA TRÊN WD (" + ds.size() + ")");
        rows.addAll(ds);
        List<String[]> fav = Cfg.favorites(this);
        if (!fav.isEmpty()) {
            rows.add("THƯ MỤC GHIM");
            rows.addAll(fav);
        }
        adapter.notifyDataSetChanged();
        if (ds.isEmpty()) {
            status.setText("Chưa có ổ nào.\nBấm “＋ Thêm ổ” bên dưới để nhập địa chỉ WD, thư mục chia sẻ và tài khoản.");
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        updateCrumbs();
    }

    private void load() {
        final int g = ++gen;
        final Drive d = drive;
        final String dir = cur;
        final boolean hidden = Cfg.showHidden(this);
        spin.setVisibility(View.VISIBLE);
        status.setVisibility(View.GONE);
        updateCrumbs();
        Ui.BG.execute(() -> {
            List<Fs.Entry> l = null;
            String err = null;
            try {
                l = SmbPool.run(d, s -> Fs.list(s, dir, hidden));
                if (pick) {
                    List<Fs.Entry> only = new ArrayList<>();
                    for (Fs.Entry e : l) if (e.dir) only.add(e);
                    l = only;
                }
                Fs.sort(l, Cfg.sortMode(this), Cfg.sortDesc(this));
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            final List<Fs.Entry> fl = l;
            final String fe = err;
            runOnUiThread(() -> {
                if (g != gen || isFinishing()) return;
                spin.setVisibility(View.INVISIBLE);
                if (fe != null) {
                    all = new ArrayList<>();
                    rows.clear();
                    adapter.notifyDataSetChanged();
                    status.setText("✗ " + fe + "\n\nChạm để thử lại");
                    status.setTextColor(Ui.RED);
                    status.setVisibility(View.VISIBLE);
                    status.setOnClickListener(v -> { SmbPool.reset(); load(); });
                    return;
                }
                all = fl;
                applyFilter();
                Integer pos = scroll.get(scrollKey());
                list.setSelection(pos != null ? pos : 0);
            });
        });
    }

    private void reload() {
        if (drive == null) buildHome();
        else if (results != null) deepSearch(resultQuery);
        else load();
    }

    private void applyFilter() {
        if (drive == null) return;
        if (mode != null) mode.finish();
        rows.clear();
        List<Fs.Entry> src = results != null ? results : all;
        String f = Fs.fold(filter.trim());
        for (Fs.Entry e : src) if (f.isEmpty() || Fs.fold(e.name).contains(f)) rows.add(e);
        adapter.notifyDataSetChanged();
        status.setOnClickListener(null);
        status.setTextColor(Ui.GRAY);
        if (rows.isEmpty()) {
            status.setText(results != null ? "Không tìm thấy “" + resultQuery + "”"
                    : !f.isEmpty() ? "Không có mục nào khớp trong thư mục này" : (pick ? "Không có thư mục con" : "Thư mục trống"));
            status.setVisibility(View.VISIBLE);
        } else {
            status.setVisibility(View.GONE);
        }
        if (!f.isEmpty() && results == null && !pick) {
            hint.setText("🔎 Tìm “" + filter.trim() + "” trong cả các thư mục con");
            hint.setVisibility(View.VISIBLE);
        } else {
            hint.setVisibility(View.GONE);
        }
        updateCrumbs();
    }

    private void updateCrumbs() {
        crumbs.removeAllViews();
        addCrumb(pick ? "🗄 Chọn ổ" : "🗄 Ổ đĩa", drive == null && results == null, v -> navigate(null, ""));
        if (drive != null) {
            final Drive d = drive;
            addSep();
            addCrumb(d.title(), cur.isEmpty() && results == null, v -> navigate(d, ""));
            if (!cur.isEmpty()) {
                String acc = "";
                String[] parts = cur.split("\\\\");
                for (int i = 0; i < parts.length; i++) {
                    acc = Fs.join(acc, parts[i]);
                    final String target = acc;
                    addSep();
                    addCrumb(parts[i], i == parts.length - 1 && results == null, v -> navigate(d, target));
                }
            }
            if (results != null) {
                addSep();
                addCrumb("🔎 “" + resultQuery + "” (" + results.size() + ")", true, null);
            }
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private void addCrumb(String s, boolean last, View.OnClickListener l) {
        TextView t = Ui.text(this, s, 14, last ? Color.WHITE : Color.parseColor("#BBDEFB"));
        if (last) t.setTypeface(Typeface.DEFAULT_BOLD);
        int p = Ui.dp(this, 8);
        t.setPadding(p, Ui.dp(this, 10), p, Ui.dp(this, 10));
        t.setSingleLine(true);
        if (l != null) {
            Ui.setRipple(this, t);
            t.setOnClickListener(l);
        }
        crumbs.addView(t);
    }

    private void addSep() { crumbs.addView(Ui.text(this, "›", 14, Color.parseColor("#90CAF9"))); }

    @Override
    public void onBackPressed() {
        if (results != null) {
            results = null;
            applyFilter();
            return;
        }
        if (searchItem != null && searchItem.isActionViewExpanded()) {
            searchItem.collapseActionView();
            return;
        }
        if (drive != null) {
            navigate(cur.isEmpty() ? null : drive, cur.isEmpty() ? "" : Fs.parent(cur));
            return;
        }
        super.onBackPressed();
    }

    // =====================================================================
    // Thanh nút
    // =====================================================================

    private void updateBars() {
        bottom.removeAllViews();
        if (mode != null) {
            bottom.addView(Ui.tool(this, "📄", "Chép", v -> copyOrCut(false)));
            bottom.addView(Ui.tool(this, "✂️", "Cắt", v -> copyOrCut(true)));
            bottom.addView(Ui.tool(this, "🗑️", "Xoá", v -> confirmDelete(selected())));
            bottom.addView(Ui.tool(this, "📤", "Gửi", v -> sendSelected()));
            bottom.addView(Ui.tool(this, "⬇️", "Tải về", v -> downloadToPhone(selected())));
        } else if (pick) {
            View nf = Ui.tool(this, "📁", "Thư mục mới", v -> newFolder());
            View ok = Ui.tool(this, "✅", "Lưu vào đây", v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_DRIVE, drive.id).putExtra(EXTRA_PATH, cur));
                finish();
            });
            Ui.enable(nf, drive != null);
            Ui.enable(ok, drive != null);
            bottom.addView(nf);
            bottom.addView(ok);
        } else if (drive == null) {
            bottom.addView(Ui.tool(this, "➕", "Thêm ổ", v -> startActivityForResult(new Intent(this, DriveEditActivity.class), REQ_DRIVE)));
            bottom.addView(Ui.tool(this, "⚙️", "Cài đặt", v -> settings()));
        } else {
            bottom.addView(Ui.tool(this, "⬆️", "Lên", v -> onBackPressed()));
            final View up = Ui.tool(this, "⤴️", "Tải lên", null);
            up.setOnClickListener(v -> uploadMenu(up));
            bottom.addView(up);
            final View nw = Ui.tool(this, "➕", "Tạo mới", null);
            nw.setOnClickListener(v -> newMenu(nw));
            bottom.addView(nw);
            View ps = Ui.tool(this, "📋", Clip.has() ? "Dán (" + Clip.items.size() + ")" : "Dán", v -> paste());
            Ui.enable(ps, Clip.has());
            bottom.addView(ps);
            bottom.addView(Ui.tool(this, "⇅", "Sắp xếp", v -> sortDialog()));
        }
        boolean showClip = Clip.has() && drive != null && !pick && mode == null;
        clipBar.setVisibility(showClip ? View.VISIBLE : View.GONE);
        if (showClip) {
            clipText.setText("📋 " + Clip.items.size() + " mục đang chờ " + (Clip.cut ? "chuyển" : "chép")
                    + " (từ " + Clip.drive.title() + ")");
        }
    }

    private void uploadMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "📄  Chọn tệp…");
        pm.getMenu().add(0, 2, 0, "📁  Cả một thư mục…");
        pm.getMenu().add(0, 3, 0, "📷  Chụp ảnh");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_UPLOAD);
            } else if (it.getItemId() == 2) {
                try {
                    startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_UPLOAD_TREE);
                } catch (ActivityNotFoundException e) {
                    toast("Máy không hỗ trợ chọn thư mục");
                }
            } else {
                takePhoto();
            }
            return true;
        });
        pm.show();
    }

    private void newMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, 1, 0, "📁  Thư mục mới");
        pm.getMenu().add(0, 2, 0, "📝  Tệp văn bản mới (.txt)");
        pm.setOnMenuItemClickListener(it -> {
            if (it.getItemId() == 1) newFolder(); else newTextFile();
            return true;
        });
        pm.show();
    }

    // =====================================================================
    // Menu phía trên
    // =====================================================================

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        searchItem = null;
        searchView = null;
        if (drive != null && !pick) {
            searchItem = m.add(0, M_SEARCH, 0, "Tìm");
            searchItem.setIcon(android.R.drawable.ic_menu_search);
            searchItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS | MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW);
            Context ctx = getActionBar() != null ? getActionBar().getThemedContext() : this;
            searchView = new SearchView(ctx);
            searchView.setQueryHint("Tìm trong thư mục này");
            searchItem.setActionView(searchView);
            searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
                @Override public boolean onQueryTextSubmit(String q) {
                    searchView.clearFocus();
                    deepSearch(q);
                    return true;
                }

                @Override public boolean onQueryTextChange(String q) {
                    filter = q;
                    applyFilter();
                    return true;
                }
            });
            searchItem.setOnActionExpandListener(new MenuItem.OnActionExpandListener() {
                @Override public boolean onMenuItemActionExpand(MenuItem item) { return true; }

                @Override public boolean onMenuItemActionCollapse(MenuItem item) {
                    filter = "";
                    applyFilter();
                    return true;
                }
            });
            m.add(0, M_REFRESH, 0, "Làm mới");
            m.add(0, M_ALL, 0, "Chọn tất cả");
            m.add(0, M_PIN, 0, Cfg.isFav(this, drive.id, cur) ? "Bỏ ghim thư mục này" : "Ghim thư mục này");
            m.add(0, M_PROPS, 0, "Thuộc tính thư mục");
            m.add(0, M_PATH, 0, "Sao chép đường dẫn");
            m.add(0, M_HIDDEN, 0, "Hiện tệp ẩn").setCheckable(true).setChecked(Cfg.showHidden(this));
            m.add(0, M_HOME, 0, "Về danh sách ổ");
            m.add(0, M_SETTINGS, 0, "Cài đặt chung");
        } else if (pick) {
            if (drive != null) m.add(0, M_REFRESH, 0, "Làm mới");
        } else {
            m.add(0, M_SETTINGS, 0, "Cài đặt chung");
            m.add(0, M_ABOUT, 0, "Giới thiệu");
        }
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: finish(); return true;
            case M_REFRESH: SmbPool.reset(); reload(); return true;
            case M_ALL:
                for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Fs.Entry) list.setItemChecked(i, true);
                return true;
            case M_PIN:
                boolean on = !Cfg.isFav(this, drive.id, cur);
                Cfg.setFav(this, drive.id, cur, on);
                toast(on ? "Đã ghim — thư mục hiện ở danh sách ổ" : "Đã bỏ ghim");
                invalidateOptionsMenu();
                return true;
            case M_PROPS: props(new ArrayList<>(all), true); return true;
            case M_PATH: copyPath(cur); return true;
            case M_HIDDEN:
                Cfg.setShowHidden(this, !Cfg.showHidden(this));
                invalidateOptionsMenu();
                load();
                return true;
            case M_HOME: navigate(null, ""); return true;
            case M_SETTINGS: settings(); return true;
            case M_ABOUT: about(); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // =====================================================================
    // Chọn nhiều
    // =====================================================================

    private List<Fs.Entry> selected() {
        List<Fs.Entry> out = new ArrayList<>();
        SparseBooleanArray arr = list.getCheckedItemPositions();
        if (arr == null) return out;
        for (int i = 0; i < arr.size(); i++) {
            int pos = arr.keyAt(i);
            if (arr.valueAt(i) && pos < rows.size() && rows.get(pos) instanceof Fs.Entry) out.add((Fs.Entry) rows.get(pos));
        }
        return out;
    }

    private final AbsListView.MultiChoiceModeListener selection = new AbsListView.MultiChoiceModeListener() {
        @Override
        public boolean onCreateActionMode(ActionMode m, Menu menu) {
            mode = m;
            menu.add(0, A_ALL, 0, "Tất cả").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
            menu.add(0, A_RENAME, 0, "Đổi tên");
            menu.add(0, A_EDIT, 0, "Sửa");
            menu.add(0, A_OPENWITH, 0, "Mở bằng app khác");
            menu.add(0, A_PIN, 0, "Ghim thư mục");
            menu.add(0, A_PATH, 0, "Sao chép đường dẫn");
            menu.add(0, A_PROPS, 0, "Thuộc tính");
            updateBars();
            return true;
        }

        @Override
        public boolean onPrepareActionMode(ActionMode m, Menu menu) {
            List<Fs.Entry> sel = selected();
            boolean single = sel.size() == 1;
            boolean file = single && !sel.get(0).dir;
            menu.findItem(A_RENAME).setVisible(single);
            menu.findItem(A_EDIT).setVisible(file);
            menu.findItem(A_OPENWITH).setVisible(file);
            menu.findItem(A_PIN).setVisible(single && sel.get(0).dir);
            menu.findItem(A_PATH).setVisible(single);
            return true;
        }

        @Override
        public void onItemCheckedStateChanged(ActionMode m, int pos, long id, boolean checked) {
            if (pos >= rows.size() || !(rows.get(pos) instanceof Fs.Entry)) {
                if (checked) list.setItemChecked(pos, false);
                return;
            }
            m.setTitle(list.getCheckedItemCount() + " đã chọn");
            m.invalidate();
            adapter.notifyDataSetChanged();
        }

        @Override
        public boolean onActionItemClicked(ActionMode m, MenuItem item) {
            List<Fs.Entry> sel = selected();
            if (sel.isEmpty() && item.getItemId() != A_ALL) return true;
            switch (item.getItemId()) {
                case A_ALL:
                    for (int i = 0; i < rows.size(); i++) if (rows.get(i) instanceof Fs.Entry) list.setItemChecked(i, true);
                    break;
                case A_RENAME: m.finish(); rename(sel.get(0)); break;
                case A_EDIT: m.finish(); edit(sel.get(0)); break;
                case A_OPENWITH: m.finish(); fetch(sel, F_OPENWITH); break;
                case A_PIN:
                    Cfg.setFav(MainActivity.this, drive.id, sel.get(0).path, true);
                    toast("Đã ghim " + sel.get(0).name);
                    m.finish();
                    break;
                case A_PATH: copyPath(sel.get(0).path); m.finish(); break;
                case A_PROPS: props(sel, false); break;
            }
            return true;
        }

        @Override
        public void onDestroyActionMode(ActionMode m) {
            mode = null;
            updateBars();
            adapter.notifyDataSetChanged();
        }
    };

    // =====================================================================
    // Chạm vào dòng
    // =====================================================================

    private void onTap(int pos) {
        if (pos >= rows.size()) return;
        Object o = rows.get(pos);
        if (o instanceof Drive) {
            navigate((Drive) o, "");
        } else if (o instanceof String[]) {
            String[] f = (String[]) o;
            Drive d = Cfg.drive(this, f[0]);
            if (d == null) toast("Ổ của thư mục ghim này đã bị xoá");
            else navigate(d, f[1]);
        } else if (o instanceof Fs.Entry) {
            Fs.Entry e = (Fs.Entry) o;
            if (e.dir) navigate(drive, e.path);
            else if (!pick) openEntry(e);
        }
    }

    private void homeMenu(int pos) {
        Object o = rows.get(pos);
        if (o instanceof Drive) {
            final Drive d = (Drive) o;
            final String[] items = {"Mở", "Sửa thông tin", "Thêm ổ khác cùng WD & tài khoản", "Đưa lên trên", "Đưa xuống dưới", "Xoá khỏi app"};
            new AlertDialog.Builder(this).setTitle(d.title()).setItems(items, (x, w) -> {
                switch (w) {
                    case 0: navigate(d, ""); break;
                    case 1: startActivityForResult(new Intent(this, DriveEditActivity.class).putExtra(DriveEditActivity.EXTRA_ID, d.id), REQ_DRIVE); break;
                    case 2: startActivityForResult(new Intent(this, DriveEditActivity.class).putExtra(DriveEditActivity.EXTRA_TEMPLATE, d.id), REQ_DRIVE); break;
                    case 3: Cfg.moveDrive(this, d.id, -1); buildHome(); break;
                    case 4: Cfg.moveDrive(this, d.id, 1); buildHome(); break;
                    case 5:
                        new AlertDialog.Builder(this).setTitle("Xoá ổ “" + d.title() + "” khỏi app?")
                                .setMessage("Chỉ xoá khỏi điện thoại này. Dữ liệu trên WD không bị ảnh hưởng.")
                                .setPositiveButton("Xoá", (y, z) -> {
                                    Cfg.deleteDrive(this, d.id);
                                    if (Clip.has() && Clip.drive.id.equals(d.id)) Clip.clear();
                                    buildHome();
                                })
                                .setNegativeButton("Huỷ", null).show();
                        break;
                }
            }).show();
        } else if (o instanceof String[]) {
            final String[] f = (String[]) o;
            new AlertDialog.Builder(this).setTitle(favName(f)).setItems(new String[]{"Mở", "Bỏ ghim"}, (x, w) -> {
                if (w == 0) onTap(pos);
                else { Cfg.setFav(this, f[0], f[1], false); buildHome(); }
            }).show();
        }
    }

    private String favName(String[] f) { return f[1].isEmpty() ? "(gốc)" : Fs.nameOf(f[1]); }

    // =====================================================================
    // Mở / gửi / sửa tệp
    // =====================================================================

    private void openEntry(Fs.Entry e) {
        if (Kind.isText(e.name) && e.size <= Kind.TEXT_MAX) openText(e.path);
        else fetch(Collections.singletonList(e), F_VIEW);
    }

    private void openText(String path) {
        startActivityForResult(new Intent(this, TextEditActivity.class)
                .putExtra(TextEditActivity.EXTRA_DRIVE, drive.id).putExtra(TextEditActivity.EXTRA_PATH, path), REQ_TEXT);
    }

    private void edit(Fs.Entry e) {
        if (Kind.isText(e.name) && e.size <= Kind.TEXT_MAX) {
            openText(e.path);
            return;
        }
        new AlertDialog.Builder(this).setTitle("Sửa “" + e.name + "”")
                .setMessage("Tệp sẽ được tải về và mở bằng app sửa (WPS, Office, app ảnh…). Sửa xong, lưu trong app đó rồi quay lại WD Cloud — app sẽ hỏi lưu lên WD.\n\nLưu ý: một số app lưu ra bản riêng, khi đó hãy dùng “Tải lên”.")
                .setPositiveButton("Tiếp tục", (d, w) -> fetch(Collections.singletonList(e), F_EDIT))
                .setNegativeButton("Huỷ", null).show();
    }

    /** Tải các tệp về bộ nhớ tạm rồi mở / gửi / sửa. */
    private void fetch(final List<Fs.Entry> files, final int purpose) {
        final Drive d = drive;
        final File dir = new File(getCacheDir(), (purpose == F_EDIT ? "edit/" : "open/") + System.currentTimeMillis());
        final List<File> out = new ArrayList<>();
        String title = purpose == F_SEND ? "Đang chuẩn bị gửi" : purpose == F_EDIT ? "Đang tải về để sửa" : "Đang tải về để mở";
        Ui.task(this, title, job -> {
            job.count = files.size();
            SmbPool.runOnce(d, s -> {
                for (int i = 0; i < files.size(); i++) {
                    Fs.Entry e = files.get(i);
                    File sub = new File(dir, String.valueOf(i));
                    if (!sub.mkdirs() && !sub.isDirectory()) throw new IOException("Không tạo được thư mục tạm");
                    File f = new File(sub, Uploader.clean(e.name));
                    job.start(e.name, e.size);
                    try (OutputStream o = new FileOutputStream(f)) {
                        Fs.download(s, e.path, o, job);
                    } catch (Exception ex) {
                        f.delete();
                        throw ex;
                    }
                    out.add(f);
                    job.finishItem();
                }
                return null;
            });
        }, err -> {
            if (err != null) { toast(err); return; }
            try {
                afterFetch(d, files, out, purpose);
            } catch (IOException ex) {
                toast(Errors.explain(ex));
            }
        });
    }

    private void afterFetch(Drive d, List<Fs.Entry> files, List<File> local, int purpose) throws IOException {
        if (local.isEmpty()) return;
        if (purpose == F_SEND) {
            ArrayList<Uri> uris = new ArrayList<>();
            String type = null;
            for (int i = 0; i < local.size(); i++) {
                uris.add(CacheProvider.uriFor(this, local.get(i)));
                String m = Kind.mime(files.get(i).name);
                type = type == null ? m : (type.equals(m) ? type : (type.split("/")[0].equals(m.split("/")[0]) ? type.split("/")[0] + "/*" : "*/*"));
            }
            Intent s = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE);
            s.setType(type);
            if (uris.size() == 1) s.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            else s.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            ClipData cd = ClipData.newRawUri("", uris.get(0));
            for (int i = 1; i < uris.size(); i++) cd.addItem(new ClipData.Item(uris.get(i)));
            s.setClipData(cd);
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(s, "Gửi " + (uris.size() == 1 ? files.get(0).name : uris.size() + " tệp") + " qua");
            ch.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, new ComponentName[]{new ComponentName(this, ShareActivity.class)});
            startActivity(ch);
            return;
        }
        File f = local.get(0);
        Fs.Entry e = files.get(0);
        Uri uri = CacheProvider.uriFor(this, f);
        String mime = Kind.mime(e.name);
        if (purpose == F_EDIT) {
            JSONObject p = new JSONObject();
            try {
                p.put("drive", d.id).put("path", e.path).put("local", f.getPath())
                        .put("mtime", f.lastModified()).put("len", f.length()).put("armed", false);
            } catch (Exception ignored) {}
            Cfg.setPendingEdit(this, p);
            Intent i = new Intent(Intent.ACTION_EDIT);
            i.setDataAndType(uri, mime);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            try {
                startActivity(Intent.createChooser(i, "Sửa " + e.name + " bằng"));
            } catch (ActivityNotFoundException ex) {
                Cfg.setPendingEdit(this, null);
                toast("Không có app nào sửa được loại tệp này");
            }
            return;
        }
        Intent v = new Intent(Intent.ACTION_VIEW);
        v.setDataAndType(uri, mime);
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            if (purpose == F_OPENWITH) startActivity(Intent.createChooser(v, "Mở " + e.name + " bằng"));
            else startActivity(v);
        } catch (ActivityNotFoundException ex) {
            v.setDataAndType(uri, "*/*");
            try {
                startActivity(Intent.createChooser(v, "Mở " + e.name + " bằng"));
            } catch (ActivityNotFoundException ex2) {
                toast("Không có app nào mở được loại tệp này");
            }
        }
    }

    /** Quay lại từ app sửa: nếu tệp đã đổi thì hỏi lưu lên WD. */
    private void checkPendingEdit() {
        JSONObject p = Cfg.pendingEdit(this);
        if (p == null || !p.optBoolean("armed")) return;
        final File f = new File(p.optString("local"));
        final Drive d = Cfg.drive(this, p.optString("drive"));
        final String path = p.optString("path");
        boolean changed = f.isFile() && (f.lastModified() != p.optLong("mtime") || f.length() != p.optLong("len"));
        Cfg.setPendingEdit(this, null);
        if (!changed || d == null) return;
        new AlertDialog.Builder(this).setTitle("“" + Fs.nameOf(path) + "” đã được sửa")
                .setMessage("Lưu thay đổi lên WD?\n" + d.fullPath(Fs.parent(path)))
                .setCancelable(false)
                .setPositiveButton("Ghi đè tệp gốc", (x, w) -> Ui.task(this, "Đang lưu lên WD", job -> {
                    job.start(Fs.nameOf(path), f.length());
                    try (InputStream in = new FileInputStream(f)) {
                        final InputStream fin = in;
                        SmbPool.runOnce(d, s -> { Fs.writeReplace(s, path, fin, job); return null; });
                    }
                }, err -> { toast(err == null ? "Đã lưu lên WD" : err); reload(); }))
                .setNeutralButton("Lưu thành bản sao", (x, w) -> Ui.task(this, "Đang lưu lên WD", job -> {
                    job.start(Fs.nameOf(path), f.length());
                    try (InputStream in = new FileInputStream(f)) {
                        final InputStream fin = in;
                        SmbPool.runOnce(d, s -> {
                            String n = Fs.nameOf(path), ext = Fs.ext(n);
                            String base = ext.isEmpty() ? n : n.substring(0, n.length() - ext.length() - 1);
                            String target = Fs.uniquePath(s, Fs.parent(path), base + " (đã sửa)" + (ext.isEmpty() ? "" : "." + ext));
                            Fs.upload(s, target, fin, false, job);
                            return null;
                        });
                    }
                }, err -> { toast(err == null ? "Đã lưu bản sao lên WD" : err); reload(); }))
                .setNegativeButton("Bỏ thay đổi", null).show();
    }

    private void sendSelected() {
        List<Fs.Entry> files = new ArrayList<>();
        for (Fs.Entry e : selected()) if (!e.dir) files.add(e);
        if (files.isEmpty()) { toast("Chỉ gửi được tệp (không gửi được thư mục)"); return; }
        if (mode != null) mode.finish();
        fetch(files, F_SEND);
    }

    // =====================================================================
    // Chép / cắt / dán / xoá / đổi tên
    // =====================================================================

    private void copyOrCut(boolean cut) {
        List<Fs.Entry> sel = selected();
        if (sel.isEmpty()) return;
        Clip.set(drive.copy(), sel, cut);
        if (mode != null) mode.finish();
        toast((cut ? "Đã cắt " : "Đã chép ") + sel.size() + " mục. Mở thư mục đích (có thể ở ổ khác) rồi bấm “Dán”.");
        updateBars();
    }

    private void paste() {
        if (!Clip.has() || drive == null) return;
        final Drive src = Clip.drive, dst = drive;
        final String dir = cur;
        final List<Fs.Entry> items = new ArrayList<>(Clip.items);
        final boolean cut = Clip.cut, same = src.key().equals(dst.key());
        spin.setVisibility(View.VISIBLE);
        Ui.BG.execute(() -> {
            List<String> conf = null;
            String err = null;
            try {
                conf = SmbPool.run(dst, d -> Fs.conflicts(d, dir, items, same));
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            final List<String> fc = conf;
            final String fe = err;
            runOnUiThread(() -> {
                spin.setVisibility(View.INVISIBLE);
                if (fe != null) { toast(fe); return; }
                if (fc.isEmpty()) { doPaste(src, dst, same, items, dir, cut, Fs.KEEP_BOTH); return; }
                String names = TextUtils.join(", ", fc.subList(0, Math.min(5, fc.size()))) + (fc.size() > 5 ? "…" : "");
                new AlertDialog.Builder(this).setTitle(fc.size() + " mục đã có ở đây: " + names)
                        .setItems(new String[]{"Giữ cả hai (thêm số vào tên mới)", "Ghi đè (thay thế bản đang có)", "Bỏ qua các mục trùng"}, (x, w) ->
                                doPaste(src, dst, same, items, dir, cut, w == 0 ? Fs.KEEP_BOTH : w == 1 ? Fs.OVERWRITE : Fs.SKIP))
                        .setNegativeButton("Huỷ", null).show();
            });
        });
    }

    private void doPaste(final Drive src, final Drive dst, final boolean same, final List<Fs.Entry> items,
                         final String dir, final boolean cut, final int policy) {
        String title = cut ? (same ? "Đang chuyển" : "Đang chuyển sang ổ khác") : (same ? "Đang chép" : "Đang chép sang ổ khác");
        Ui.task(this, title, job -> SmbPool.runOnce2(src, dst, (s, d) -> {
            Fs.paste(s, d, same, items, dir, cut, policy, job);
            return null;
        }), err -> {
            if (err == null) {
                if (cut) Clip.clear();
                toast(cut ? "Đã chuyển xong" : "Đã chép xong");
            } else {
                toast(err);
            }
            updateBars();
            reload();
        });
    }

    private void confirmDelete(final List<Fs.Entry> sel) {
        if (sel.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(8, sel.size()); i++) sb.append(sel.get(i).dir ? "📁 " : "• ").append(sel.get(i).name).append('\n');
        if (sel.size() > 8) sb.append("… và ").append(sel.size() - 8).append(" mục khác\n");
        sb.append("\nXoá hẳn trên WD, không khôi phục được từ app này.");
        final Drive d = drive;
        new AlertDialog.Builder(this).setTitle("Xoá " + sel.size() + " mục?").setMessage(sb.toString())
                .setPositiveButton("Xoá", (x, w) -> {
                    if (mode != null) mode.finish();
                    Ui.task(this, "Đang xoá", job -> {
                        job.count = sel.size();
                        SmbPool.runOnce(d, s -> {
                            for (Fs.Entry e : sel) {
                                job.start(e.name, -1);
                                Fs.delete(s, e);
                                job.finishItem();
                            }
                            return null;
                        });
                    }, err -> {
                        if (Clip.has() && Clip.drive.key().equals(d.key())) {
                            for (Fs.Entry e : sel) for (int i = Clip.items.size() - 1; i >= 0; i--)
                                if (Clip.items.get(i).path.equalsIgnoreCase(e.path)) Clip.items.remove(i);
                            if (Clip.items.isEmpty()) Clip.clear();
                        }
                        toast(err == null ? "Đã xoá " + sel.size() + " mục" : err);
                        updateBars();
                        reload();
                    });
                })
                .setNegativeButton("Huỷ", null).show();
    }

    private void rename(final Fs.Entry e) {
        int dot = e.dir ? -1 : e.name.lastIndexOf('.');
        Ui.ask(this, "Đổi tên", e.name, "Tên mới", dot > 0 ? dot : -1, "Đổi", s -> {
            final String n = Fs.cleanName(s);
            if (n.isEmpty() || n.equals(e.name)) return;
            quick(job -> SmbPool.run(drive, sh -> {
                Fs.rename(sh, e.path, Fs.join(Fs.parent(e.path), n), false);
                return null;
            }), "Đã đổi tên");
        });
    }

    private void newFolder() {
        if (drive == null) return;
        Ui.ask(this, "Thư mục mới", "Thư mục mới", "Tên thư mục", -1, "Tạo", s -> {
            final String n = Fs.cleanName(s);
            if (n.isEmpty()) return;
            final String path = Fs.join(cur, n);
            final Drive d = drive;
            spin.setVisibility(View.VISIBLE);
            Ui.BG.execute(() -> {
                String err = null;
                try {
                    SmbPool.run(d, sh -> { sh.mkdir(path); return null; });
                } catch (Exception ex) {
                    err = Errors.explain(ex);
                }
                final String fe = err;
                runOnUiThread(() -> {
                    spin.setVisibility(View.INVISIBLE);
                    if (fe != null) toast(fe);
                    else if (pick) navigate(d, path);
                    else load();
                });
            });
        });
    }

    private void newTextFile() {
        String def = "Ghi chú " + new SimpleDateFormat("dd-MM-yyyy", Locale.US).format(new Date()) + ".txt";
        Ui.ask(this, "Tệp văn bản mới", def, "Tên tệp", def.length() - 4, "Tạo", s -> {
            String n = Fs.cleanName(s);
            if (n.isEmpty()) return;
            if (Fs.ext(n).isEmpty()) n += ".txt";
            final String name = n;
            final Drive d = drive;
            final String dir = cur;
            spin.setVisibility(View.VISIBLE);
            Ui.BG.execute(() -> {
                String err = null, path = null;
                try {
                    path = SmbPool.run(d, sh -> {
                        String p = Fs.uniquePath(sh, dir, name);
                        Fs.upload(sh, p, new java.io.ByteArrayInputStream(new byte[0]), false, null);
                        return p;
                    });
                } catch (Exception ex) {
                    err = Errors.explain(ex);
                }
                final String fe = err, fp = path;
                runOnUiThread(() -> {
                    spin.setVisibility(View.INVISIBLE);
                    if (fe != null) { toast(fe); return; }
                    load();
                    openText(fp);
                });
            });
        });
    }

    /** Thao tác nhanh: chỉ hiện thanh chạy, xong thì tải lại. */
    private void quick(final Ui.Work w, final String ok) {
        spin.setVisibility(View.VISIBLE);
        Ui.BG.execute(() -> {
            String err = null;
            try {
                w.run(new Job());
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            final String fe = err;
            runOnUiThread(() -> {
                spin.setVisibility(View.INVISIBLE);
                toast(fe == null ? ok : fe);
                reload();
            });
        });
    }

    private void copyPath(String path) {
        String full = drive.fullPath(path);
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Đường dẫn", full));
        toast("Đã chép: " + full);
    }

    // =====================================================================
    // Tải lên / tải về điện thoại / chụp ảnh
    // =====================================================================

    private void uploadItems(final List<Uploader.Item> items, final Drive d, final String dir, final Runnable after) {
        if (items.isEmpty()) return;
        Ui.task(this, "Đang tải lên WD", job -> Uploader.upload(this, items, d, dir, job), err -> {
            int n = Uploader.savedCount(items);
            toast(err == null ? "Đã tải lên " + n + " tệp" : (n > 0 ? "Đã lưu " + n + " tệp. " : "") + err);
            reload();
            if (err == null && after != null) after.run();
        });
    }

    private void takePhoto() {
        try {
            File dir = new File(getCacheDir(), "cam");
            if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Không tạo được thư mục tạm");
            File f = new File(dir, "IMG_" + Uploader.stamp() + ".jpg");
            if (!f.createNewFile() && !f.isFile()) throw new IOException("Không tạo được tệp ảnh");
            camPath = f.getPath();
            Uri u = CacheProvider.uriFor(this, f);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, u);
            i.setClipData(ClipData.newRawUri("", u));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivityForResult(i, REQ_CAMERA);
        } catch (ActivityNotFoundException e) {
            toast("Không tìm thấy app máy ảnh");
        } catch (IOException e) {
            toast(Errors.explain(e));
        }
    }

    private void downloadToPhone(List<Fs.Entry> sel) {
        if (sel.isEmpty()) return;
        dlDrive = drive;
        dlItems = sel;
        if (mode != null) mode.finish();
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_DOWNLOAD_TREE);
            toast("Chọn thư mục trên điện thoại để lưu (VD: Download)");
        } catch (ActivityNotFoundException e) {
            toast("Máy không hỗ trợ chọn thư mục");
        }
    }

    private void downloadTo(com.hierynomus.smbj.share.DiskShare s, ContentResolver cr, Fs.Entry e, Uri parentDoc, Job job) throws Exception {
        job.check();
        if (e.dir) {
            Uri d = DocumentsContract.createDocument(cr, parentDoc, DocumentsContract.Document.MIME_TYPE_DIR, e.name);
            if (d == null) throw new IOException("Không tạo được thư mục " + e.name + " trên điện thoại");
            for (Fs.Entry c : Fs.list(s, e.path, false)) downloadTo(s, cr, c, d, job);
            return;
        }
        Uri f = DocumentsContract.createDocument(cr, parentDoc, Kind.mime(e.name), e.name);
        if (f == null) throw new IOException("Không tạo được tệp " + e.name + " trên điện thoại");
        job.start(e.name, e.size);
        try (OutputStream o = cr.openOutputStream(f)) {
            if (o == null) throw new IOException("Không ghi được vào điện thoại");
            Fs.download(s, e.path, o, job);
        } catch (Exception ex) {
            try { DocumentsContract.deleteDocument(cr, f); } catch (Exception ignored) {}
            throw ex;
        }
        job.finishItem();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_DRIVE) {
            if (drive != null) {
                Drive fresh = Cfg.drive(this, drive.id);
                drive = fresh;
                if (fresh == null) cur = "";
                show();
            } else {
                List<Drive> ds = Cfg.drives(this);
                if (ds.size() == 1 && res == RESULT_OK && !pick) navigate(ds.get(0), "");
                else buildHome();
            }
            return;
        }
        if (req == REQ_TEXT) {
            if (res == RESULT_OK) reload();
            return;
        }
        if (res != RESULT_OK) {
            if (req == REQ_CAMERA && camPath != null) new File(camPath).delete();
            return;
        }
        if (req == REQ_UPLOAD && data != null && drive != null) {
            List<Uploader.Item> items = new ArrayList<>();
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++)
                    items.add(Uploader.fromUri(this, data.getClipData().getItemAt(i).getUri(), null));
            } else if (data.getData() != null) {
                items.add(Uploader.fromUri(this, data.getData(), null));
            }
            uploadItems(items, drive, cur, null);
        } else if (req == REQ_UPLOAD_TREE && data != null && data.getData() != null && drive != null) {
            final Uri tree = data.getData();
            final Drive d = drive;
            final String dir = cur;
            final List<Uploader.Item> items = new ArrayList<>();
            Ui.task(this, "Đang tải thư mục lên WD", job -> {
                String rootName = Uploader.treeName(this, tree);
                items.addAll(Uploader.fromTree(this, tree, job));
                String top = SmbPool.run(d, s -> Fs.uniquePath(s, dir, rootName));
                Uploader.upload(this, items, d, top, job);
            }, err -> {
                int n = Uploader.savedCount(items);
                toast(err == null ? "Đã tải lên thư mục (" + n + " tệp)" : (n > 0 ? "Đã lưu " + n + " tệp. " : "") + err);
                reload();
            });
        } else if (req == REQ_DOWNLOAD_TREE && data != null && data.getData() != null) {
            if (dlItems == null || dlDrive == null) { toast("Hãy chọn lại tệp cần tải về"); return; }
            final Uri tree = data.getData();
            final Drive d = dlDrive;
            final List<Fs.Entry> items = dlItems;
            dlItems = null;
            final ContentResolver cr = getContentResolver();
            Ui.task(this, "Đang tải về điện thoại", job -> {
                Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
                SmbPool.runOnce(d, s -> {
                    for (Fs.Entry e : items) downloadTo(s, cr, e, parent, job);
                    return null;
                });
            }, err -> toast(err == null ? "Đã tải về điện thoại" : err));
        } else if (req == REQ_CAMERA && camPath != null && drive != null) {
            final File f = new File(camPath);
            camPath = null;
            if (f.length() == 0) { f.delete(); toast("Chưa có ảnh"); return; }
            uploadItems(Collections.singletonList(Uploader.fromFile(f, f.getName())), drive, cur, () -> {
                f.delete();
                new AlertDialog.Builder(this).setTitle("Đã lưu ảnh lên WD")
                        .setPositiveButton("📷 Chụp tiếp", (x, w) -> takePhoto())
                        .setNegativeButton("Xong", null).show();
            });
        }
    }

    // =====================================================================
    // Tìm kiếm, sắp xếp, thuộc tính, cài đặt
    // =====================================================================

    private void deepSearch(final String q) {
        if (drive == null || q == null || q.trim().isEmpty()) return;
        final Drive d = drive;
        final String dir = cur;
        final boolean hidden = Cfg.showHidden(this);
        final List<List<Fs.Entry>> box = new ArrayList<>();
        Ui.task(this, "Đang tìm “" + q.trim() + "”", job -> box.add(SmbPool.run(d, s -> Fs.search(s, dir, q, hidden, 500, job))), err -> {
            if (err != null && box.isEmpty()) { toast(err); return; }
            if (box.isEmpty()) return;
            if (searchItem != null && searchItem.isActionViewExpanded()) {
                searchItem.collapseActionView();
            }
            filter = "";
            results = box.get(0);
            resultQuery = q.trim();
            Fs.sort(results, Cfg.sortMode(this), Cfg.sortDesc(this));
            applyFilter();
            if (results.size() >= 500) toast("Hiện 500 kết quả đầu tiên");
        });
    }

    private void sortDialog() {
        final String[] labels = {"Tên A → Z", "Tên Z → A", "Mới nhất trước", "Cũ nhất trước", "Lớn nhất trước", "Nhỏ nhất trước", "Theo loại tệp"};
        final int[][] map = {{Fs.SORT_NAME, 0}, {Fs.SORT_NAME, 1}, {Fs.SORT_DATE, 1}, {Fs.SORT_DATE, 0}, {Fs.SORT_SIZE, 1}, {Fs.SORT_SIZE, 0}, {Fs.SORT_TYPE, 0}};
        int curMode = Cfg.sortMode(this), sel = 0;
        boolean desc = Cfg.sortDesc(this);
        for (int i = 0; i < map.length; i++) if (map[i][0] == curMode && (map[i][1] == 1) == desc) sel = i;
        new AlertDialog.Builder(this).setTitle("Sắp xếp").setSingleChoiceItems(labels, sel, (dlg, w) -> {
            Cfg.setSort(this, map[w][0], map[w][1] == 1);
            Fs.sort(all, map[w][0], map[w][1] == 1);
            if (results != null) Fs.sort(results, map[w][0], map[w][1] == 1);
            applyFilter();
            dlg.dismiss();
        }).show();
    }

    private void props(final List<Fs.Entry> sel, boolean currentFolder) {
        final Drive d = drive;
        if (!currentFolder && sel.size() == 1 && !sel.get(0).dir) {
            Fs.Entry e = sel.get(0);
            new AlertDialog.Builder(this).setTitle(e.name).setMessage(
                    "Loại: " + Kind.mime(e.name) +
                    "\nKích thước: " + Ui.size(this, e.size) + " (" + String.format(Locale.US, "%,d", e.size) + " byte)" +
                    "\nSửa đổi: " + (e.mtime > 0 ? df.format(new Date(e.mtime)) : "?") +
                    "\nVị trí: " + d.fullPath(Fs.parent(e.path)))
                    .setPositiveButton("OK", null).show();
            return;
        }
        final String head = currentFolder
                ? "Vị trí: " + d.fullPath(cur) + "\n\n"
                : (sel.size() == 1 ? "Vị trí: " + d.fullPath(sel.get(0).path) + "\nSửa đổi: " + (sel.get(0).mtime > 0 ? df.format(new Date(sel.get(0).mtime)) : "?") + "\n\n"
                : sel.size() + " mục đã chọn\n\n");
        final TextView tv = Ui.text(this, head + "Đang đếm…", 15, Ui.TEXT);
        final Job job = new Job() {
            long last;
            @Override protected void changed() {
                long now = System.currentTimeMillis();
                if (now - last < 300) return;
                last = now;
                final String t = head + "Đang đếm… " + done + " tệp, " + Ui.size(MainActivity.this, bytes);
                runOnUiThread(() -> tv.setText(t));
            }
        };
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(currentFolder ? (cur.isEmpty() ? d.title() : Fs.nameOf(cur)) : (sel.size() == 1 ? sel.get(0).name : "Thuộc tính"))
                .setView(Ui.pad(this, tv)).setPositiveButton("OK", null).create();
        dlg.setOnDismissListener(x -> job.cancelled = true);
        dlg.show();
        Ui.BG.execute(() -> {
            String t;
            try {
                long[] r = SmbPool.run(d, s -> Fs.stats(s, sel, job));
                t = head + String.format(Locale.US, "%,d", r[0]) + " tệp, " + String.format(Locale.US, "%,d", r[1]) + " thư mục"
                        + "\nTổng dung lượng: " + Ui.size(this, r[2]) + " (" + String.format(Locale.US, "%,d", r[2]) + " byte)";
            } catch (Exception e) {
                t = head + Errors.explain(e);
            }
            final String ft = t;
            runOnUiThread(() -> tv.setText(ft));
        });
    }

    private void settings() {
        final boolean[] v = {Cfg.autoUpload(this), Cfg.showHidden(this)};
        new AlertDialog.Builder(this).setTitle("Cài đặt chung")
                .setMultiChoiceItems(new String[]{"Nhận file chia sẻ: tự tải lên sau 3 giây", "Hiện tệp/thư mục ẩn"}, v, (d, w, on) -> v[w] = on)
                .setPositiveButton("Lưu", (d, w) -> {
                    boolean hidChanged = v[1] != Cfg.showHidden(this);
                    Cfg.setAutoUpload(this, v[0]);
                    Cfg.setShowHidden(this, v[1]);
                    if (hidChanged && drive != null) load();
                    invalidateOptionsMenu();
                })
                .setNegativeButton("Huỷ", null).show();
    }

    private void about() {
        String ver = "?";
        try { ver = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) {}
        new AlertDialog.Builder(this).setTitle("WD Cloud " + ver)
                .setMessage("Truy cập ổ WD qua thư mục chia sẻ (SMB2/3).\n\n" +
                        "• Nhấn giữ một tệp/thư mục để chọn nhiều: Chép, Cắt, Xoá, Gửi, Tải về.\n" +
                        "• Chép/Cắt rồi mở thư mục khác (kể cả ổ khác) và bấm Dán.\n" +
                        "• Chép trong cùng ổ do WD tự làm, không tốn dữ liệu điện thoại.\n" +
                        "• Từ Zalo/Thư viện ảnh: Chia sẻ → Lưu vào WD Cloud.")
                .setPositiveButton("OK", null).show();
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    /** Xoá tệp tạm cũ hơn 1 ngày. */
    private void cleanCache() {
        Ui.BG.execute(() -> {
            long limit = System.currentTimeMillis() - 24 * 3600 * 1000L;
            for (String sub : new String[]{"open", "edit", "cam"}) {
                File[] ds = new File(getCacheDir(), sub).listFiles();
                if (ds == null) continue;
                for (File d : ds) if (d.lastModified() < limit) deleteTree(d);
            }
        });
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    // =====================================================================
    // Danh sách
    // =====================================================================

    private static final class Row {
        TextView icon, name, sub;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return rows.size(); }

        @Override public Object getItem(int pos) { return rows.get(pos); }

        @Override public long getItemId(int pos) { return pos; }

        @Override public int getViewTypeCount() { return 2; }

        @Override public int getItemViewType(int pos) { return rows.get(pos) instanceof String ? 0 : 1; }

        @Override public boolean isEnabled(int pos) { return !(rows.get(pos) instanceof String); }

        @Override public boolean areAllItemsEnabled() { return false; }

        @Override
        public View getView(int pos, View v, ViewGroup parent) {
            Object o = rows.get(pos);
            if (o instanceof String) {
                TextView t = v instanceof TextView ? (TextView) v : Ui.text(MainActivity.this, "", 12, Ui.GRAY);
                t.setText((String) o);
                t.setTypeface(Typeface.DEFAULT_BOLD);
                int p = Ui.dp(MainActivity.this, 14);
                t.setPadding(p, p, p, Ui.dp(MainActivity.this, 4));
                return t;
            }
            Row r;
            if (v == null || !(v.getTag() instanceof Row)) {
                r = new Row();
                LinearLayout row = new LinearLayout(MainActivity.this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int p = Ui.dp(MainActivity.this, 12);
                row.setPadding(p, Ui.dp(MainActivity.this, 10), p, Ui.dp(MainActivity.this, 10));
                row.setBackground(Ui.rowBg());
                r.icon = Ui.text(MainActivity.this, "", 24, Color.BLACK);
                r.icon.setPadding(0, 0, p, 0);
                LinearLayout col = new LinearLayout(MainActivity.this);
                col.setOrientation(LinearLayout.VERTICAL);
                r.name = Ui.text(MainActivity.this, "", 16, Ui.TEXT);
                r.name.setSingleLine(true);
                r.name.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                r.sub = Ui.text(MainActivity.this, "", 12, Ui.GRAY);
                r.sub.setSingleLine(true);
                r.sub.setEllipsize(TextUtils.TruncateAt.START);
                col.addView(r.name);
                col.addView(r.sub);
                row.addView(r.icon);
                row.addView(col, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
                row.setTag(r);
                v = row;
            } else {
                r = (Row) v.getTag();
            }
            if (o instanceof Drive) {
                Drive d = (Drive) o;
                r.icon.setText("🗄️");
                r.name.setText(d.title());
                r.sub.setText(d.label() + (d.user.isEmpty() ? " · khách" : " · " + d.user));
            } else if (o instanceof String[]) {
                String[] f = (String[]) o;
                Drive d = driveMap.get(f[0]);
                r.icon.setText("⭐");
                r.name.setText(favName(f));
                r.sub.setText(d == null ? "(ổ đã xoá)" : d.title() + (f[1].isEmpty() ? "" : "\\" + f[1]));
            } else {
                Fs.Entry e = (Fs.Entry) o;
                boolean checked = mode != null && list.isItemChecked(pos);
                r.icon.setText(checked ? "✅" : Kind.icon(e));
                r.name.setText(e.name);
                r.name.setAlpha(e.hidden ? 0.55f : 1f);
                String date = e.mtime > 0 ? df.format(new Date(e.mtime)) : "";
                String info = e.dir ? date : date + (date.isEmpty() ? "" : " · ") + Ui.size(MainActivity.this, e.size);
                if (results != null) {
                    String par = Fs.parent(e.path);
                    info = (par.isEmpty() ? "\\" : par) + " · " + info;
                }
                r.sub.setText(info);
            }
            return v;
        }
    }
}
