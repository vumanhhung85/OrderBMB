package vn.hung.wdcloud;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.InputType;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import vn.hung.wdcloud.core.Errors;
import vn.hung.wdcloud.core.Job;

/** Tiện ích giao diện dựng bằng code + chạy việc nền có hộp tiến độ. */
final class Ui {
    static final ExecutorService BG = Executors.newCachedThreadPool();
    static final int BRAND = Color.parseColor("#1565C0");
    static final int BRAND_DARK = Color.parseColor("#0D47A1");
    static final int GRAY = Color.parseColor("#6B7280");
    static final int TEXT = Color.parseColor("#111827");
    static final int RED = Color.parseColor("#C62828");
    static final int GREEN = Color.parseColor("#2E7D32");
    static final int SELECTED = Color.parseColor("#D6E4FF");
    static final int BAR = Color.parseColor("#F3F6FB");

    static int dp(Activity a, float v) { return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f); }

    static String size(Activity a, long b) { return b < 0 ? "?" : Formatter.formatFileSize(a, b); }

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
            b.setBackgroundTintList(ColorStateList.valueOf(BRAND));
        }
        return b;
    }

    static LinearLayout.LayoutParams lp(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    static View spacer(Activity a, int hdp) {
        View v = new View(a);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(a, hdp)));
        return v;
    }

    static void setRipple(Activity a, View v) {
        TypedValue tv = new TypedValue();
        a.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true);
        v.setBackgroundResource(tv.resourceId);
    }

    /** Nền dòng danh sách: sáng xanh khi được chọn. */
    static StateListDrawable rowBg() {
        StateListDrawable d = new StateListDrawable();
        d.addState(new int[]{android.R.attr.state_activated}, new ColorDrawable(SELECTED));
        d.addState(new int[]{android.R.attr.state_pressed}, new ColorDrawable(Color.parseColor("#EEF2F7")));
        d.addState(new int[]{}, new ColorDrawable(Color.TRANSPARENT));
        return d;
    }

    /** Nút trên thanh công cụ: biểu tượng + chữ nhỏ. */
    static LinearLayout tool(Activity a, String icon, String label, View.OnClickListener l) {
        LinearLayout b = new LinearLayout(a);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        int p = dp(a, 6);
        b.setPadding(p / 2, p, p / 2, p);
        TextView i = text(a, icon, 20, TEXT);
        i.setGravity(Gravity.CENTER);
        TextView t = text(a, label, 12, TEXT);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        b.addView(i);
        b.addView(t);
        setRipple(a, b);
        b.setOnClickListener(l);
        b.setLayoutParams(lp(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return b;
    }

    static void enable(View v, boolean on) {
        v.setEnabled(on);
        v.setAlpha(on ? 1f : 0.35f);
    }

    static void toast(final Activity a, final String s) {
        a.runOnUiThread(() -> Toast.makeText(a, s, Toast.LENGTH_LONG).show());
    }

    static View pad(Activity a, View v) {
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(a, 20);
        box.setPadding(p, dp(a, 8), p, 0);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    interface OnText { void ok(String s); }

    /** Hộp nhập một dòng chữ; selectEnd = vị trí bôi đen (VD trước phần đuôi tệp). */
    static void ask(Activity a, String title, String value, String hint, int selectEnd, String okLabel, final OnText cb) {
        final EditText in = new EditText(a);
        in.setSingleLine(true);
        in.setInputType(InputType.TYPE_CLASS_TEXT);
        in.setHint(hint);
        in.setText(value);
        in.setSelection(0, Math.max(0, Math.min(selectEnd < 0 ? value.length() : selectEnd, value.length())));
        AlertDialog d = new AlertDialog.Builder(a).setTitle(title).setView(pad(a, in))
                .setPositiveButton(okLabel, (x, w) -> {
                    String s = in.getText().toString().trim();
                    if (!s.isEmpty()) cb.ok(s);
                })
                .setNegativeButton("Huỷ", null).create();
        d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        d.show();
        in.requestFocus();
    }

    // ---------- Việc nền có hộp tiến độ ----------

    interface Work { void run(Job job) throws Exception; }

    interface Done { void done(String error); }

    /** Hộp tiến độ có nút Huỷ, gắn với một Job. */
    static final class Prog {
        final Job job;
        private final Activity a;
        private final AlertDialog dlg;
        private final TextView msg;
        private final ProgressBar bar;
        private long lastPost;

        Prog(final Activity act, String title) {
            a = act;
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
            job = new Job() {
                @Override protected void changed() { post(false); }
            };
            dlg = new AlertDialog.Builder(a).setTitle(title).setView(box).setCancelable(false)
                    .setNegativeButton("Huỷ", (d, w) -> job.cancelled = true).create();
            dlg.show();
            a.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        private void post(boolean force) {
            long now = System.currentTimeMillis();
            if (!force && now - lastPost < 150 && !(job.size > 0 && job.bytes >= job.size)) return;
            lastPost = now;
            final Job j = job;
            final String head = (j.count > 0 ? "(" + Math.min(j.done + 1, j.count) + "/" + j.count + ") "
                    : (j.done > 0 ? "Xong " + j.done + " · " : "")) + j.item;
            final long b = j.bytes, s = j.size;
            a.runOnUiThread(() -> {
                msg.setText(head + (s > 0 ? "\n" + size(a, b) + " / " + size(a, s) : (b > 0 ? "\n" + size(a, b) : "")));
                bar.setIndeterminate(s <= 0);
                if (s > 0) bar.setProgress((int) (b * 1000 / Math.max(1, s)));
            });
        }

        void dismiss() {
            a.runOnUiThread(() -> {
                a.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                try { dlg.dismiss(); } catch (Exception ignored) {}
            });
        }
    }

    /** Chạy việc nền có hộp tiến độ; xong thì gọi done(lỗi hoặc null) trên luồng giao diện. */
    static void task(final Activity a, String title, final Work w, final Done done) {
        final Prog p = new Prog(a, title);
        BG.execute(() -> {
            String err = null;
            try {
                w.run(p.job);
            } catch (Exception e) {
                err = Errors.explain(e);
            }
            p.dismiss();
            final String fe = err;
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (done != null) done.done(fe);
                else if (fe != null) Toast.makeText(a, fe, Toast.LENGTH_LONG).show();
            });
        });
    }

    private Ui() {}
}
