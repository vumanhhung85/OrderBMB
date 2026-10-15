package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Errors;
import vn.hung.wdcloud.core.Fs;
import vn.hung.wdcloud.core.SmbPool;

/** Xem và sửa tệp văn bản (.txt, .csv, .json…) ngay trên WD. Lưu bằng cách ghi tệp tạm rồi thay thế. */
public class TextEditActivity extends Activity {
    static final String EXTRA_DRIVE = "drive", EXTRA_PATH = "path";

    private Drive drive;
    private String path;
    private EditText edit;
    private TextView info;
    private ProgressBar spin;
    private Charset charset = StandardCharsets.UTF_8;
    private byte[] bom = new byte[0];
    private boolean dirty, loaded, saving, mono;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        drive = Cfg.drive(this, getIntent().getStringExtra(EXTRA_DRIVE));
        path = getIntent().getStringExtra(EXTRA_PATH);
        if (drive == null || path == null) { finish(); return; }
        setTitle(Fs.nameOf(path));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        info = Ui.text(this, drive.fullPath(Fs.parent(path)), 12, Color.WHITE);
        info.setBackgroundColor(Ui.BRAND_DARK);
        int p = Ui.dp(this, 10);
        info.setPadding(p, Ui.dp(this, 4), p, Ui.dp(this, 4));
        info.setSingleLine(true);
        info.setEllipsize(android.text.TextUtils.TruncateAt.START);
        root.addView(info);
        spin = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        spin.setIndeterminate(true);
        root.addView(spin, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 4)));
        edit = new EditText(this);
        edit.setGravity(Gravity.TOP | Gravity.START);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        edit.setHorizontallyScrolling(false);
        edit.setTextSize(15);
        edit.setBackground(null);
        edit.setPadding(p, p, p, p);
        edit.setEnabled(false);
        root.addView(edit, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);
        load();
    }

    private void load() {
        Ui.BG.execute(() -> {
            String text = null, err = null, warn = null;
            try {
                byte[] data = SmbPool.run(drive, s -> Fs.readAll(s, path, Kind.TEXT_MAX));
                int off = 0;
                if (data.length >= 3 && (data[0] & 0xff) == 0xEF && (data[1] & 0xff) == 0xBB && (data[2] & 0xff) == 0xBF) {
                    bom = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}; off = 3; charset = StandardCharsets.UTF_8;
                } else if (data.length >= 2 && (data[0] & 0xff) == 0xFF && (data[1] & 0xff) == 0xFE) {
                    bom = new byte[]{(byte) 0xFF, (byte) 0xFE}; off = 2; charset = StandardCharsets.UTF_16LE;
                } else if (data.length >= 2 && (data[0] & 0xff) == 0xFE && (data[1] & 0xff) == 0xFF) {
                    bom = new byte[]{(byte) 0xFE, (byte) 0xFF}; off = 2; charset = StandardCharsets.UTF_16BE;
                }
                try {
                    text = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(data, off, data.length - off)).toString();
                } catch (CharacterCodingException e) {
                    charset = Charset.isSupported("windows-1258") ? Charset.forName("windows-1258") : StandardCharsets.ISO_8859_1;
                    text = new String(data, off, data.length - off, charset);
                    warn = "Tệp dùng bảng mã cũ (" + charset.name() + "), chữ có dấu có thể hiển thị sai";
                }
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            final String ft = text, fe = err, fw = warn;
            runOnUiThread(() -> {
                spin.setVisibility(View.GONE);
                if (fe != null) {
                    new AlertDialog.Builder(this).setTitle("Không mở được").setMessage(fe)
                            .setPositiveButton("Đóng", (d, w) -> finish()).setCancelable(false).show();
                    return;
                }
                edit.setText(ft);
                edit.setEnabled(true);
                loaded = true;
                if (fw != null) Toast.makeText(this, fw, Toast.LENGTH_LONG).show();
                edit.addTextChangedListener(new TextWatcher() {
                    @Override public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
                    @Override public void onTextChanged(CharSequence s, int a, int b2, int c) {}
                    @Override public void afterTextChanged(Editable s) {
                        if (!dirty) { dirty = true; setTitle("● " + Fs.nameOf(path)); }
                    }
                });
            });
        });
    }

    private void save(final boolean thenFinish) {
        if (!loaded || saving) return;
        saving = true;
        spin.setVisibility(View.VISIBLE);
        final String text = edit.getText().toString();
        Ui.BG.execute(() -> {
            String err = null;
            try {
                ByteBuffer bb = charset.newEncoder().onUnmappableCharacter(CodingErrorAction.REPLACE).encode(CharBuffer.wrap(text));
                byte[] body = new byte[bom.length + bb.remaining()];
                System.arraycopy(bom, 0, body, 0, bom.length);
                bb.get(body, bom.length, bb.remaining());
                SmbPool.runOnce(drive, s -> { Fs.writeReplace(s, path, body); return null; });
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            final String fe = err;
            runOnUiThread(() -> {
                saving = false;
                spin.setVisibility(View.GONE);
                if (fe != null) {
                    Toast.makeText(this, "Chưa lưu được: " + fe, Toast.LENGTH_LONG).show();
                    return;
                }
                dirty = false;
                setTitle(Fs.nameOf(path));
                Toast.makeText(this, "Đã lưu lên WD", Toast.LENGTH_SHORT).show();
                setResult(RESULT_OK);
                if (thenFinish) finish();
            });
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Lưu").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        m.add(0, 2, 0, "Chữ đều (code)").setCheckable(true).setChecked(mono);
        m.add(0, 3, 0, "Chữ to hơn");
        m.add(0, 4, 0, "Chữ nhỏ hơn");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case android.R.id.home: onBackPressed(); return true;
            case 1: save(false); return true;
            case 2:
                mono = !mono;
                item.setChecked(mono);
                edit.setTypeface(mono ? Typeface.MONOSPACE : Typeface.DEFAULT);
                return true;
            case 3: edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, edit.getTextSize() * 1.15f); return true;
            case 4: edit.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, edit.getTextSize() / 1.15f); return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (dirty) {
            new AlertDialog.Builder(this).setTitle("Lưu thay đổi?")
                    .setPositiveButton("Lưu", (d, w) -> save(true))
                    .setNegativeButton("Bỏ thay đổi", (d, w) -> finish())
                    .setNeutralButton("Ở lại", null).show();
            return;
        }
        super.onBackPressed();
    }
}
