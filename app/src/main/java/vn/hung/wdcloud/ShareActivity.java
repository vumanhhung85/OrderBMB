package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * Nhận file chia sẻ từ Zalo / Thư viện ảnh / app khác và tải thẳng lên WD.
 * Mặc định tự tải lên sau 3 giây vào thư mục đã chọn (bấm "Đổi thư mục" để dừng đếm và chọn nơi khác).
 */
public class ShareActivity extends Activity {
    private static final int REQ_PICK = 1, REQ_SETTINGS = 2;
    private static final int IDLE = 0, RUNNING = 1, DONE = 2;

    private List<Uploader.Item> items;
    private String dir;
    private int state = IDLE;
    private volatile boolean cancel;
    private int countdown = -1;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView dest, statusText;
    private ProgressBar bar;
    private Button go, change, close;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        items = Uploader.fromShareIntent(this, getIntent());
        dir = Cfg.lastShareDir(this);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = Ui.dp(this, 20);
        box.setPadding(p, p, p, p);

        if (items.isEmpty()) {
            box.addView(Ui.text(this, "Không nhận được tệp nào để lưu.", 16, Ui.RED));
            Button x = Ui.button(this, "Đóng", false);
            x.setOnClickListener(v -> finish());
            box.addView(x);
            setContentView(box);
            return;
        }

        long total = 0;
        boolean unknown = false;
        for (Uploader.Item it : items) {
            if (it.size < 0) unknown = true; else total += it.size;
        }
        TextView head = Ui.text(this, items.size() + " tệp" + (unknown ? "" : " · " + Ui.size(this, total)), 20, Color.parseColor("#111827"));
        box.addView(head);

        StringBuilder names = new StringBuilder();
        int show = Math.min(items.size(), 6);
        for (int i = 0; i < show; i++) names.append("• ").append(items.get(i).name).append('\n');
        if (items.size() > show) names.append("… và ").append(items.size() - show).append(" tệp khác\n");
        TextView nl = Ui.text(this, names.toString().trim(), 14, Ui.GRAY);
        nl.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        box.addView(nl);

        box.addView(Ui.text(this, "Lưu vào:", 14, Ui.BRAND));
        dest = Ui.text(this, "", 16, Color.parseColor("#111827"));
        dest.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        box.addView(dest);

        change = Ui.button(this, "Đổi thư mục…", false);
        change.setOnClickListener(v -> pickFolder());
        box.addView(change, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(Ui.spacer(this, 16));
        go = Ui.button(this, "Tải lên WD", true);
        go.setOnClickListener(v -> start());
        box.addView(go, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 56)));

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setVisibility(View.GONE);
        box.addView(bar);

        statusText = Ui.text(this, "", 15, Color.DKGRAY);
        statusText.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
        box.addView(statusText);

        close = Ui.button(this, "Huỷ", false);
        close.setOnClickListener(v -> onBackPressed());
        box.addView(close, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        setContentView(sv);

        showDest();
        if (!Cfg.load(this).ready()) {
            statusText.setTextColor(Ui.RED);
            statusText.setText("Chưa cài đặt kết nối tới WD.");
            go.setText("Mở cài đặt");
            go.setOnClickListener(v -> startActivityForResult(new Intent(this, SettingsActivity.class), REQ_SETTINGS));
        } else if (Cfg.autoUpload(this)) {
            countdown = 3;
            tick();
        }
    }

    private void showDest() {
        Cfg.Conf k = Cfg.load(this);
        dest.setText(k.label() + (dir.isEmpty() ? "" : "\\" + dir));
    }

    private void tick() {
        if (countdown < 0 || state != IDLE) return;
        if (countdown == 0) {
            start();
            return;
        }
        go.setText("Tải lên WD (tự chạy sau " + countdown + " giây)");
        countdown--;
        h.postDelayed(this::tick, 1000);
    }

    private void stopCountdown() {
        countdown = -1;
        h.removeCallbacksAndMessages(null);
        if (state == IDLE && go != null) go.setText("Tải lên WD");
    }

    private void pickFolder() {
        if (state == RUNNING) return;
        stopCountdown();
        Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PICK, true).putExtra(MainActivity.EXTRA_START, dir);
        startActivityForResult(i, REQ_PICK);
    }

    private void start() {
        if (state == RUNNING) return;
        if (state == DONE) { finish(); return; }
        stopCountdown();
        state = RUNNING;
        cancel = false;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        go.setEnabled(false);
        go.setText("Đang tải lên…");
        change.setEnabled(false);
        close.setText("Huỷ tải lên");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setText("Đang kết nối tới WD…");
        Cfg.setLastShareDir(this, dir);
        final String target = dir;
        Smb.BG.execute(new Runnable() {
            long last;

            @Override
            public void run() {
                Exception err = null;
                try {
                    Uploader.upload(ShareActivity.this, items, target, (idx, total, name, done, size) -> {
                        long now = System.currentTimeMillis();
                        if (now - last > 150 || (size > 0 && done >= size)) {
                            last = now;
                            final String line = (total > 1 ? "(" + (idx + 1) + "/" + total + ") " : "") + name
                                    + "\n" + Ui.size(ShareActivity.this, done) + (size > 0 ? " / " + Ui.size(ShareActivity.this, size) : "");
                            runOnUiThread(() -> {
                                statusText.setText(line);
                                bar.setIndeterminate(size <= 0);
                                if (size > 0) bar.setProgress((int) (done * 1000 / Math.max(1, size)));
                            });
                        }
                        return !cancel;
                    });
                } catch (Exception e) {
                    err = e;
                }
                final Exception fe = err;
                runOnUiThread(() -> finished(fe));
            }
        });
    }

    private void finished(Exception e) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bar.setVisibility(View.GONE);
        change.setEnabled(true);
        go.setEnabled(true);
        int n = Uploader.savedCount(items);
        if (e == null) {
            state = DONE;
            statusText.setTextColor(Ui.GREEN);
            statusText.setText("✓ Đã lưu " + n + " tệp vào WD");
            go.setText("Xong");
            close.setText("Đóng");
            Toast.makeText(this, "Đã lưu " + n + " tệp vào WD", Toast.LENGTH_SHORT).show();
            h.postDelayed(() -> { if (!isFinishing()) finish(); }, 1500);
        } else {
            state = IDLE;
            statusText.setTextColor(Ui.RED);
            statusText.setText((n > 0 ? "Đã lưu " + n + "/" + items.size() + " tệp.\n" : "") + "✗ " + Smb.explain(e));
            go.setText(cancel ? "Tải lên WD" : "Thử lại");
            close.setText("Đóng");
        }
    }

    @Override
    public void onBackPressed() {
        if (state == RUNNING) {
            new AlertDialog.Builder(this).setTitle("Huỷ tải lên?")
                    .setMessage("Tệp đang tải dở sẽ bị xoá khỏi WD.")
                    .setPositiveButton("Huỷ tải lên", (d, w) -> cancel = true)
                    .setNegativeButton("Tiếp tục", null).show();
            return;
        }
        stopCountdown();
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null) {
            dir = Cfg.normPath(data.getStringExtra(MainActivity.EXTRA_PATH));
            showDest();
        } else if (req == REQ_SETTINGS && Cfg.load(this).ready()) {
            dir = Cfg.lastShareDir(this);
            showDest();
            statusText.setText("");
            go.setText("Tải lên WD");
            go.setOnClickListener(v -> start());
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        cancel = true;
        super.onDestroy();
    }
}
