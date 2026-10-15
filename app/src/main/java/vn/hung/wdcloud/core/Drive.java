package vn.hung.wdcloud.core;

/** Một "ổ" = một thư mục chia sẻ trên WD (\\host\share) với tài khoản riêng. */
public final class Drive {
    public String id = "";
    public String name = "", host = "", share = "", user = "", pass = "", domain = "";
    public int port = 445;

    public boolean ready() { return !host.isEmpty() && !share.isEmpty(); }

    /** Khoá phân biệt kết nối (đổi địa chỉ/tài khoản/mật khẩu thì khoá đổi). */
    public String key() {
        return host.toLowerCase() + ":" + port + "|" + share.toLowerCase() + "|" + domain + "|" + user + "|" + pass.hashCode();
    }

    public String label() { return "\\\\" + host + (port == 445 ? "" : ":" + port) + "\\" + share; }

    public String title() { return name == null || name.trim().isEmpty() ? share : name.trim(); }

    /** Đường dẫn kiểu Windows đầy đủ của một mục trong ổ. */
    public String fullPath(String path) { return label() + (path == null || path.isEmpty() ? "" : "\\" + path); }

    public Drive copy() {
        Drive d = new Drive();
        d.id = id; d.name = name; d.host = host; d.share = share; d.user = user; d.pass = pass; d.domain = domain; d.port = port;
        return d;
    }

    public boolean sameServer(Drive o) {
        return o != null && host.equalsIgnoreCase(o.host) && port == o.port;
    }
}
