package vn.hung.wdcloud;

import java.util.ArrayList;
import java.util.List;

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Fs;

/** Bộ nhớ tạm cho Chép/Cắt → Dán (dùng chung cả app, giữ khi chuyển ổ). */
final class Clip {
    static Drive drive;
    static final List<Fs.Entry> items = new ArrayList<>();
    static boolean cut;

    static boolean has() { return drive != null && !items.isEmpty(); }

    static void set(Drive d, List<Fs.Entry> l, boolean isCut) {
        drive = d;
        items.clear();
        items.addAll(l);
        cut = isCut;
    }

    static void clear() {
        drive = null;
        items.clear();
    }

    private Clip() {}
}
