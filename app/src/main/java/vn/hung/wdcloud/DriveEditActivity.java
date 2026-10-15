package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Errors;
import vn.hung.wdcloud.core.ShareEnum;
import vn.hung.wdcloud.core.SmbPool;

/**
 * Thêm/sửa một ổ (= một thư mục chia sẻ trên WD + tài khoản tạo trong trang quản trị WD).
 * Nút "Lấy danh sách" đọc các thư mục chia sẻ trên WD; khi thêm mới có thể chọn nhiều để thêm nhiều ổ cùng lúc.
 */
public class DriveEditActivity extends Activity {
    static final String EXTRA_ID = "id";            // sửa ổ có sẵn
    static final String EXTRA_TEMPLATE = "template"; // thêm ổ mới, lấy địa chỉ + tài khoản từ ổ này

    private String editId;
    private EditText name, host, share, user, pass, domain;
    private TextView result;
    private Button testBtn, listBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        editId = getIntent().getStringExtra(EXTRA_ID);
        Drive d = editId != null ? Cfg.drive(this, editId) : null;
        if (d == null) {
            editId = null;
            d = new Drive();
            Drive t = Cfg.drive(this, getIntent().getStringExtra(EXTRA_TEMPLATE));
            List<Drive> all = Cfg.drives(this);
            if (t != null) {
                d.host = t.host; d.port = t.port; d.user = t.user; d.pass = t.pass; d.domain = t.domain;
            } else if (!all.isEmpty()) {
                d.host = all.get(0).host; d.port = all.get(0).port; d.domain = all.get(0).domain;
            }
        }
        setTitle(editId == null ? "Thêm ổ" : "Sửa ổ");

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p * 2);

        host = field(box, "Địa chỉ WD", "VD: 1.2.3.4  hoặc  \\\\1.2.3.4\\TenThuMuc", d.host + (d.port == 445 ? "" : ":" + d.port), InputType.TYPE_TEXT_VARIATION_URI);
        user = field(box, "Tên đăng nhập (tạo trong quản trị WD)", "Để trống nếu thư mục cho khách (Public)", d.user, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass = field(box, "Mật khẩu", "", d.pass, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        CheckBox showPass = new CheckBox(this);
        showPass.setText("Hiện mật khẩu");
        showPass.setOnCheckedChangeListener((v, on) -> {
            pass.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(pass.getText().length());
        });
        box.addView(showPass);

        share = field(box, "Thư mục chia sẻ (share) trên WD", "VD: DungChung", d.share, 0);
        listBtn = Ui.button(this, editId == null ? "📋 Lấy danh sách từ WD (chọn được nhiều)" : "📋 Lấy danh sách từ WD", false);
        listBtn.setOnClickListener(v -> listShares());
        box.addView(listBtn, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        name = field(box, "Tên hiển thị trong app", "Để trống = dùng tên thư mục chia sẻ", d.name, InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        domain = field(box, "Workgroup/Domain (thường để trống)", "", d.domain, 0);

        box.addView(Ui.spacer(this, 12));
        LinearLayout row = new LinearLayout(this);
        testBtn = Ui.button(this, "Kiểm tra kết nối", false);
        Button save = Ui.button(this, "Lưu", true);
        row.addView(testBtn, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(save, Ui.lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(row);

        result = Ui.text(this, "", 15, Color.DKGRAY);
        result.setPadding(0, Ui.dp(this, 12), 0, 0);
        box.addView(result);

        TextView note = Ui.text(this,
                "Mỗi thư mục chia sẻ trên WD là một ổ, có thể dùng tài khoản khác nhau.\n" +
                "Mật khẩu được mã hoá và chỉ lưu trong điện thoại này.", 13, Ui.GRAY);
        note.setPadding(0, Ui.dp(this, 16), 0, 0);
        box.addView(note);

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);

        testBtn.setOnClickListener(v -> test());
        save.setOnClickListener(v -> save());
    }

    private EditText field(LinearLayout box, String label, String hint, String value, int variation) {
        TextView l = Ui.text(this, label, 14, Ui.BRAND);
        l.setPadding(0, Ui.dp(this, 10), 0, 0);
        box.addView(l);
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | variation);
        e.setHint(hint);
        e.setTextSize(16);
        e.setText(value);
        box.addView(e);
        return e;
    }

    private Drive read() {
        Drive d = new Drive();
        String[] a = Cfg.splitAddress(host.getText().toString());
        d.host = a[0];
        try { d.port = Integer.parseInt(a[1].trim()); } catch (Exception e) { d.port = 445; }
        String sh = share.getText().toString().trim().replace("\\", "").replace("/", "");
        if (sh.isEmpty() && !a[2].isEmpty()) {
            sh = a[2];
            share.setText(sh);
        }
        if (!a[2].isEmpty()) host.setText(a[0] + (d.port == 445 ? "" : ":" + d.port));
        d.share = sh;
        d.user = user.getText().toString().trim();
        d.pass = pass.getText().toString();
        d.domain = domain.getText().toString().trim();
        d.name = name.getText().toString().trim();
        return d;
    }

    private void show(boolean ok, String s) {
        result.setTextColor(ok ? Ui.GREEN : Ui.RED);
        result.setText(s);
    }

    private void test() {
        final Drive d = read();
        if (!d.ready()) { show(false, "Cần nhập địa chỉ và tên thư mục chia sẻ"); return; }
        testBtn.setEnabled(false);
        result.setTextColor(Color.DKGRAY);
        result.setText("Đang kết nối tới " + d.label() + " …");
        Ui.BG.execute(() -> {
            String msg;
            boolean ok;
            try {
                int n = SmbPool.test(d);
                ok = true;
                msg = "✓ Kết nối được! Thư mục gốc có " + n + " mục. Bấm Lưu để dùng.";
            } catch (Exception e) {
                ok = false;
                msg = "✗ " + Errors.explain(e);
            }
            final boolean fok = ok;
            final String fmsg = msg;
            runOnUiThread(() -> {
                testBtn.setEnabled(true);
                show(fok, fmsg);
            });
        });
    }

    private boolean exists(Drive d) {
        for (Drive x : Cfg.drives(this)) {
            if (editId != null && x.id.equals(editId)) continue;
            if (x.host.equalsIgnoreCase(d.host) && x.share.equalsIgnoreCase(d.share) && x.user.equals(d.user)) return true;
        }
        return false;
    }

    private void listShares() {
        final Drive d = read();
        if (d.host.isEmpty()) { show(false, "Nhập địa chỉ WD trước"); return; }
        listBtn.setEnabled(false);
        result.setTextColor(Color.DKGRAY);
        result.setText("Đang lấy danh sách thư mục chia sẻ…");
        Ui.BG.execute(() -> {
            List<ShareEnum.Item> l = null;
            String err = null;
            try {
                l = ShareEnum.disks(ShareEnum.list(d));
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            final List<ShareEnum.Item> fl = l;
            final String fe = err;
            runOnUiThread(() -> {
                listBtn.setEnabled(true);
                if (fe != null) { show(false, "✗ " + fe); return; }
                if (fl.isEmpty()) { show(false, "WD không trả về thư mục chia sẻ nào cho tài khoản này"); return; }
                result.setText("");
                chooseShares(d, fl);
            });
        });
    }

    private void chooseShares(final Drive base, final List<ShareEnum.Item> l) {
        final String[] labels = new String[l.size()];
        for (int i = 0; i < l.size(); i++) {
            Drive t = base.copy();
            t.share = l.get(i).name;
            String r = l.get(i).remark == null ? "" : l.get(i).remark.trim();
            labels[i] = l.get(i).name + (r.isEmpty() ? "" : "  — " + r) + (exists(t) ? "  (đã có)" : "");
        }
        if (editId != null) {
            new AlertDialog.Builder(this).setTitle("Chọn thư mục chia sẻ")
                    .setItems(labels, (dlg, w) -> share.setText(l.get(w).name)).show();
            return;
        }
        final boolean[] checked = new boolean[l.size()];
        new AlertDialog.Builder(this).setTitle("Chọn thư mục chia sẻ (chọn nhiều = thêm nhiều ổ)")
                .setMultiChoiceItems(labels, checked, (dlg, w, on) -> checked[w] = on)
                .setPositiveButton("Thêm", (dlg, w) -> {
                    List<String> pick = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) pick.add(l.get(i).name);
                    if (pick.size() == 1) {
                        share.setText(pick.get(0));
                    } else if (pick.size() > 1) {
                        addMany(base, pick);
                    }
                })
                .setNegativeButton("Huỷ", null).show();
    }

    /** Thêm nhiều ổ cùng tài khoản; chỉ thêm ổ kết nối được, báo lại ổ lỗi. */
    private void addMany(final Drive base, final List<String> shares) {
        Ui.task(this, "Đang kiểm tra " + shares.size() + " thư mục", job -> {
            final List<String> ok = new ArrayList<>(), bad = new ArrayList<>();
            job.count = shares.size();
            for (String sh : shares) {
                job.start(sh, -1);
                Drive d = base.copy();
                d.id = "";
                d.share = sh;
                d.name = sh;
                if (exists(d)) { bad.add(sh + ": đã có"); job.finishItem(); continue; }
                try {
                    SmbPool.test(d);
                    Cfg.saveDrive(this, d);
                    ok.add(sh);
                } catch (Exception e) {
                    bad.add(sh + ": " + Errors.explain(e));
                }
                job.finishItem();
            }
            runOnUiThread(() -> {
                String msg = "Đã thêm " + ok.size() + " ổ" + (ok.isEmpty() ? "" : ": " + android.text.TextUtils.join(", ", ok));
                if (bad.isEmpty()) {
                    Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
                    setResult(RESULT_OK);
                    finish();
                } else {
                    new AlertDialog.Builder(this).setTitle(msg)
                            .setMessage("Không thêm được:\n" + android.text.TextUtils.join("\n", bad))
                            .setPositiveButton("OK", (x, w) -> { if (!ok.isEmpty()) { setResult(RESULT_OK); finish(); } })
                            .show();
                }
            });
        }, null);
    }

    private void save() {
        Drive d = read();
        if (!d.ready()) { show(false, "Cần nhập địa chỉ và tên thư mục chia sẻ"); return; }
        if (exists(d)) { show(false, "Đã có ổ " + d.label() + " với tài khoản này"); return; }
        if (d.name.isEmpty()) d.name = d.share;
        d.id = editId == null ? "" : editId;
        Cfg.saveDrive(this, d);
        SmbPool.reset();
        Toast.makeText(this, "Đã lưu ổ " + d.title(), Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
