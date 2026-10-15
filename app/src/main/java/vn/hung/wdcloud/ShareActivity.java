package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
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

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Errors;
import vn.hung.wdcloud.core.Job;

/**
 * Nhận file chia sẻ từ Zalo / Thư viện ảnh / app khác và tải thẳng lên WD.
 * Mặc định tự tải lên sau 3 giây vào nơi lưu gần nhất (ổ + thư mục); bấm "Đổi nơi lưu" để chọn ổ/thư mục khác.
 */
public class ShareActivity extends Activity {
    private static final int REQ_PICK = 1, REQ_DRIVE = 2;
    private static final int IDLE = 0, RUNNING = 1, DONE = 2;

    private List<Uploader.Item> items;
    private Drive drive;
    private String dir;
    private int state = IDLE;
    private int countdown = -1;
    private Job job;
    private final Handler h = new Handler(Looper.getMainLooper());

    private TextView dest, statusText;
    private ProgressBar bar;
    private Button go, change, close;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        items = Uploader.fromShareIntent(this, getIntent());
        drive = Cfg.drive(this, Cfg.shareDriveId(this));
        dir = Cfg.shareDir(this);

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
        box.addView(Ui.text(this, items.size() + " tệp" + (unknown ? "" : " · " + Ui.size(this, total)), 20, Ui.TEXT));

        StringBuilder names = new StringBuilder();
        int show = Math.min(items.size(), 6);
        for (int i = 0; i < show; i++) names.append("• ").append(items.get(i).name).append('\n');
        if (items.size() > show) names.append("… và ").append(items.size() - show).append(" tệp khác\n");
        TextView nl = Ui.text(this, names.toString().trim(), 14, Ui.GRAY);
        nl.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 14));
        box.addView(nl);

        box.addView(Ui.text(this, "Lưu vào:", 14, Ui.BRAND));
        dest = Ui.text(this, "", 16, Ui.TEXT);
        dest.setPadding(0, Ui.dp(this, 2), 0, Ui.dp(this, 6));
        box.addView(dest);

        change = Ui.button(this, "Đổi nơi lưu (ổ / thư mục)…", false);
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

        refreshDest();
        if (drive != null && Cfg.autoUpload(this)) {
            countdown = 3;
            tick();
        }
    }

    private void refreshDest() {
        if (drive == null) {
            dest.setText("(chưa có ổ nào)");
            statusText.setTextColor(Ui.RED);
            statusText.setText("Chưa cài đặt ổ WD nào.");
            go.setText("Thêm ổ");
            go.setOnClickListener(v -> startActivityForResult(new Intent(this, DriveEditActivity.class), REQ_DRIVE));
            change.setEnabled(false);
            return;
        }
        change.setEnabled(true);
        dest.setText("🗄 " + drive.title() + (dir.isEmpty() ? "" : " \\ " + dir.replace("\\", " \\ ")) + "\n" + drive.fullPath(dir));
        if (state == IDLE) {
            go.setOnClickListener(v -> start());
            if (countdown < 0) go.setText("Tải lên WD");
        }
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
        if (state == IDLE && go != null && drive != null) go.setText("Tải lên WD");
    }

    private void pickFolder() {
        if (state == RUNNING) return;
        stopCountdown();
        Intent i = new Intent(this, MainActivity.class).putExtra(MainActivity.EXTRA_PICK, true);
        if (drive != null) i.putExtra(MainActivity.EXTRA_DRIVE, drive.id).putExtra(MainActivity.EXTRA_START, dir);
        startActivityForResult(i, REQ_PICK);
    }

    private void start() {
        if (state == RUNNING || drive == null) return;
        if (state == DONE) { finish(); return; }
        stopCountdown();
        state = RUNNING;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        go.setEnabled(false);
        go.setText("Đang tải lên…");
        change.setEnabled(false);
        close.setText("Huỷ tải lên");
        bar.setVisibility(View.VISIBLE);
        bar.setIndeterminate(true);
        statusText.setTextColor(Color.DKGRAY);
        statusText.setText("Đang kết nối tới WD…");
        Cfg.setShareDest(this, drive.id, dir);
        final Drive d = drive;
        final String target = dir;
        job = new Job() {
            long last;

            @Override protected void changed() {
                long now = System.currentTimeMillis();
                if (now - last < 150 && !(size > 0 && bytes >= size)) return;
                last = now;
                final String line = (count > 1 ? "(" + Math.min(done + 1, count) + "/" + count + ") " : "") + item
                        + "\n" + Ui.size(ShareActivity.this, bytes) + (size > 0 ? " / " + Ui.size(ShareActivity.this, size) : "");
                final long b = bytes, s = size;
                runOnUiThread(() -> {
                    statusText.setText(line);
                    bar.setIndeterminate(s <= 0);
                    if (s > 0) bar.setProgress((int) (b * 1000 / Math.max(1, s)));
                });
            }
        };
        final Job j = job;
        Ui.BG.execute(() -> {
            Exception err = null;
            try {
                Uploader.upload(ShareActivity.this, items, d, target, j);
            } catch (Exception e) {
                err = e;
            }
            final Exception fe = err;
            runOnUiThread(() -> finished(fe, j.cancelled));
        });
    }

    private void finished(Exception e, boolean wasCancelled) {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        bar.setVisibility(View.GONE);
        change.setEnabled(true);
        go.setEnabled(true);
        int n = Uploader.savedCount(items);
        if (e == null) {
            state = DONE;
            statusText.setTextColor(Ui.GREEN);
            statusText.setText("✓ Đã lưu " + n + " tệp vào " + drive.title());
            go.setText("Xong");
            close.setText("Đóng");
            Toast.makeText(this, "Đã lưu " + n + " tệp vào WD", Toast.LENGTH_SHORT).show();
            h.postDelayed(() -> { if (!isFinishing()) finish(); }, 1500);
        } else {
            state = IDLE;
            statusText.setTextColor(Ui.RED);
            statusText.setText((n > 0 ? "Đã lưu " + n + "/" + items.size() + " tệp.\n" : "") + "✗ " + Errors.explain(e));
            go.setText(wasCancelled ? "Tải lên WD" : "Thử lại");
            close.setText("Đóng");
        }
    }

    @Override
    public void onBackPressed() {
        if (state == RUNNING) {
            new AlertDialog.Builder(this).setTitle("Huỷ tải lên?")
                    .setMessage("Tệp đang tải dở sẽ bị xoá khỏi WD.")
                    .setPositiveButton("Huỷ tải lên", (d, w) -> { if (job != null) job.cancelled = true; })
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
            Drive d = Cfg.drive(this, data.getStringExtra(MainActivity.EXTRA_DRIVE));
            if (d != null) {
                drive = d;
                dir = vn.hung.wdcloud.core.Fs.norm(data.getStringExtra(MainActivity.EXTRA_PATH));
            }
            refreshDest();
        } else if (req == REQ_DRIVE) {
            drive = Cfg.drive(this, Cfg.shareDriveId(this));
            dir = Cfg.shareDir(this);
            statusText.setText("");
            refreshDest();
        }
    }

    @Override
    protected void onDestroy() {
        h.removeCallbacksAndMessages(null);
        if (job != null) job.cancelled = true;
        super.onDestroy();
    }
}
