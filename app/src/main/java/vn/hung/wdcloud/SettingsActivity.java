package vn.hung.wdcloud;

import android.app.Activity;
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

/** Nhập địa chỉ WD, thư mục chia sẻ, tài khoản; thử kết nối trước khi lưu. */
public class SettingsActivity extends Activity {
    private EditText host, share, user, pass, domain, uploadDir;
    private CheckBox showPass, auto;
    private TextView result;
    private Button testBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 16);
        box.setPadding(p, p, p, p * 2);

        Cfg.Conf k = Cfg.load(this);
        String hostVal = k.host + (k.port == 445 ? "" : ":" + k.port);

        host = field(box, "Địa chỉ WD", "VD: 1.2.3.4  hoặc  \\\\1.2.3.4\\Public", hostVal, InputType.TYPE_TEXT_VARIATION_URI);
        share = field(box, "Tên thư mục chia sẻ (share)", "Tên thấy khi mở \\\\IP trên Windows Explorer, VD: Public", k.share, 0);
        user = field(box, "Tên đăng nhập", "Để trống nếu thư mục cho khách (Guest)", k.user, InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        pass = field(box, "Mật khẩu", "", k.pass, InputType.TYPE_TEXT_VARIATION_PASSWORD);
        showPass = new CheckBox(this);
        showPass.setText("Hiện mật khẩu");
        showPass.setOnCheckedChangeListener((v, on) -> {
            pass.setInputType(InputType.TYPE_CLASS_TEXT | (on ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
            pass.setSelection(pass.getText().length());
        });
        box.addView(showPass);
        domain = field(box, "Workgroup/Domain (thường để trống)", "", k.domain, 0);

        box.addView(Ui.spacer(this, 8));
        uploadDir = field(box, "Thư mục lưu file chia sẻ từ Zalo/app khác",
                "Nằm trong thư mục chia sẻ ở trên; chưa có sẽ tự tạo", Cfg.uploadDir(this), 0);
        auto = new CheckBox(this);
        auto.setText("Khi chia sẻ: tự tải lên sau 3 giây (vẫn đổi thư mục được)");
        auto.setChecked(Cfg.autoUpload(this));
        box.addView(auto);

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
                "Mật khẩu được mã hoá và chỉ lưu trong điện thoại này.\n" +
                "Nếu báo lỗi SMB1: vào trang quản trị WD bật SMB2/SMB3.", 13, Ui.GRAY);
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

    /** Đọc ô nhập thành cấu hình; nhận cả kiểu \\IP\Share trong ô địa chỉ. */
    private Cfg.Conf read() {
        Cfg.Conf k = new Cfg.Conf();
        String[] a = Cfg.splitAddress(host.getText().toString());
        k.host = a[0];
        try { k.port = Integer.parseInt(a[1].trim()); } catch (Exception e) { k.port = 445; }
        String sh = share.getText().toString().trim().replace("\\", "").replace("/", "");
        if (sh.isEmpty() && !a[2].isEmpty()) {
            sh = a[2];
            share.setText(sh);
        }
        if (!a[2].isEmpty()) host.setText(a[0] + (k.port == 445 ? "" : ":" + k.port));
        k.share = sh;
        k.user = user.getText().toString().trim();
        k.pass = pass.getText().toString();
        k.domain = domain.getText().toString().trim();
        return k;
    }

    private void test() {
        final Cfg.Conf k = read();
        if (!k.ready()) {
            result.setTextColor(Ui.RED);
            result.setText("Cần nhập địa chỉ và tên thư mục chia sẻ");
            return;
        }
        testBtn.setEnabled(false);
        result.setTextColor(Color.DKGRAY);
        result.setText("Đang kết nối tới " + k.label() + " …");
        Smb.BG.execute(() -> {
            String msg;
            boolean ok;
            try {
                int n = Smb.test(k);
                ok = true;
                msg = "✓ Kết nối được! Thư mục gốc có " + n + " mục. Bấm Lưu để dùng.";
            } catch (Exception e) {
                ok = false;
                msg = "✗ " + Smb.explain(e);
            }
            final boolean fok = ok;
            final String fmsg = msg;
            runOnUiThread(() -> {
                testBtn.setEnabled(true);
                result.setTextColor(fok ? Ui.GREEN : Ui.RED);
                result.setText(fmsg);
            });
        });
    }

    private void save() {
        Cfg.Conf k = read();
        if (!k.ready()) {
            result.setTextColor(Ui.RED);
            result.setText("Cần nhập địa chỉ và tên thư mục chia sẻ");
            return;
        }
        Cfg.save(this, k);
        String oldDir = Cfg.uploadDir(this);
        String newDir = Cfg.normPath(uploadDir.getText().toString());
        Cfg.setUploadDir(this, newDir);
        if (!oldDir.equals(newDir)) Cfg.clearLastShareDir(this);
        Cfg.setAutoUpload(this, auto.isChecked());
        Smb.reset();
        Toast.makeText(this, "Đã lưu", Toast.LENGTH_SHORT).show();
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
