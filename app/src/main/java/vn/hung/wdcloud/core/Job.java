package vn.hung.wdcloud.core;

import java.io.IOException;

/** Trạng thái một thao tác dài (chép, chuyển, xoá, tải…): báo tiến độ và cho phép huỷ. */
public class Job {
    public static final class Cancelled extends IOException {
        public Cancelled() { super("Đã huỷ"); }
    }

    public volatile boolean cancelled;
    public volatile String item = "";
    public volatile int done, count;          // số mục đã xong / tổng (0 = chưa biết)
    public volatile long bytes, size = -1;    // tiến độ của tệp hiện tại

    public void check() throws Cancelled {
        if (cancelled) throw new Cancelled();
    }

    public void start(String name, long size) throws Cancelled {
        check();
        item = name;
        bytes = 0;
        this.size = size;
        changed();
    }

    public void progress(long b) throws Cancelled {
        check();
        bytes = b;
        changed();
    }

    public void finishItem() {
        done++;
        changed();
    }

    /** Ghi đè để cập nhật giao diện (được gọi từ luồng nền, có thể rất dày). */
    protected void changed() {}
}
