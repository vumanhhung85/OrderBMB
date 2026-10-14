package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/** Tiện ích giao diện dựng bằng code (không cần thư viện ngoài). */
final class Ui {
    static final int BRAND = Color.parseColor("#1565C0");
    static final int GRAY = Color.parseColor("#6B7280");
    static final int RED = Color.parseColor("#C62828");
    static final int GREEN = Color.parseColor("#2E7D32");

    static int dp(Activity a, float v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }

    static String size(Activity a, long b) { return b < 0 ? "?" : Formatter.formatShortFileSize(a, b); }

    static TextView text(Activity a, String s, float sp, int color) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    static Button button(Activity a, String s, boolean primary) {
        Button b = new Button(a);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextSize(16);
        if (primary) {
            b.setTextColor(Color.WHITE);
            b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(BRAND));
        }
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    /** Hộp thoại tiến độ có nút Huỷ. update() an toàn gọi từ luồng nền. */
    static final class Prog {
        private final Activity a;
        private final AlertDialog dlg;
        private final TextView msg;
        private final ProgressBar bar;
        private volatile boolean cancelled;
        private long lastPost;

        Prog(Activity a, String title) {
            this.a = a;
            LinearLayout box = new LinearLayout(a);
            box.setOrientation(LinearLayout.VERTICAL);
            int p = dp(a, 20);
            box.setPadding(p, dp(a, 8), p, 0);
            msg = text(a, "Đang kết nối…", 15, Color.DKGRAY);
            bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setIndeterminate(true);
            box.addView(msg);
            box.addView(bar);
            dlg = new AlertDialog.Builder(a).setTitle(title).setView(box).setCancelable(false)
                    .setNegativeButton("Huỷ", (d, w) -> cancelled = true).create();
            dlg.show();
        }

        boolean update(final String line, final long done, final long total) {
            long now = System.currentTimeMillis();
            if (now - lastPost >= 150 || (total > 0 && done >= total)) {
                lastPost = now;
                a.runOnUiThread(() -> {
                    msg.setText(line + "\n" + size(a, done) + (total > 0 ? " / " + size(a, total) : ""));
                    bar.setIndeterminate(total <= 0);
                    if (total > 0) bar.setProgress((int) (done * 1000 / Math.max(1, total)));
                });
            }
            return !cancelled;
        }

        void dismiss() {
            a.runOnUiThread(() -> {
                try { dlg.dismiss(); } catch (Exception ignored) {}
            });
        }
    }

    static View spacer(Activity a, int hdp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(a, hdp)));
        return v;
    }

    static void center(TextView t) { t.setGravity(Gravity.CENTER); }

    private Ui() {}
}
