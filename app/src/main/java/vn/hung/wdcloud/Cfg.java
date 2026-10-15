package vn.hung.wdcloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

import vn.hung.wdcloud.core.Drive;
import vn.hung.wdcloud.core.Fs;

/**
 * Cấu hình lưu trong máy: danh sách ổ (mỗi ổ một thư mục chia sẻ + tài khoản), thư mục ghim, tuỳ chọn.
 * Mật khẩu mã hoá bằng khoá trong Android Keystore. Tự chuyển cấu hình 1 ổ của bản 1.0 sang.
 */
final class Cfg {
    static final String DEFAULT_SHARE_DIR = "Tu dien thoai";
    private static final String KEY_ALIAS = "wdcloud_cfg";

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    // ---------- Ổ đĩa ----------

    private static synchronized void migrate(Context c) {
        SharedPreferences p = sp(c);
        if (p.contains("drives")) return;
        JSONArray arr = new JSONArray();
        String share = p.getString("share", "");
        SharedPreferences.Editor ed = p.edit();
        if (!share.isEmpty()) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", "d1");
                o.put("name", share);
                o.put("host", p.getString("host", ""));
                o.put("port", p.getInt("port", 445));
                o.put("share", share);
                o.put("user", p.getString("user", ""));
                o.put("domain", p.getString("domain", ""));
                o.put("pass", p.getString("pass", "")); // giữ nguyên dạng đã mã hoá
                arr.put(o);
                ed.putString("share_drive", "d1");
                ed.putString("share_dir", p.getString("last_share_dir", p.getString("upload_dir", DEFAULT_SHARE_DIR)));
            } catch (Exception ignored) {}
        }
        ed.putString("drives", arr.toString()).apply();
    }

    private static JSONArray rawDrives(Context c) {
        migrate(c);
        try { return new JSONArray(sp(c).getString("drives", "[]")); } catch (Exception e) { return new JSONArray(); }
    }

    private static Drive fromJson(JSONObject o) {
        Drive d = new Drive();
        d.id = o.optString("id");
        d.name = o.optString("name");
        d.host = o.optString("host");
        d.port = o.optInt("port", 445);
        d.share = o.optString("share");
        d.user = o.optString("user");
        d.domain = o.optString("domain");
        d.pass = decrypt(o.optString("pass"));
        return d;
    }

    private static List<Drive> cache; // tránh giải mã mật khẩu lặp lại

    static synchronized List<Drive> drives(Context c) {
        if (cache == null) {
            JSONArray a = rawDrives(c);
            cache = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) cache.add(fromJson(o));
            }
        }
        List<Drive> out = new ArrayList<>();
        for (Drive d : cache) out.add(d.copy());
        return out;
    }

    static Drive drive(Context c, String id) {
        if (id == null) return null;
        for (Drive d : drives(c)) if (d.id.equals(id)) return d;
        return null;
    }

    /** Thêm mới (id rỗng) hoặc cập nhật ổ. Trả về id. */
    static synchronized String saveDrive(Context c, Drive d) {
        JSONArray a = rawDrives(c);
        if (d.id == null || d.id.isEmpty()) d.id = "d" + UUID.randomUUID().toString().substring(0, 8);
        try {
            JSONObject o = new JSONObject();
            o.put("id", d.id);
            o.put("name", d.name);
            o.put("host", d.host);
            o.put("port", d.port);
            o.put("share", d.share);
            o.put("user", d.user);
            o.put("domain", d.domain);
            o.put("pass", encrypt(d.pass));
            boolean found = false;
            for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x != null && d.id.equals(x.optString("id"))) { a.put(i, o); found = true; break; }
            }
            if (!found) a.put(o);
        } catch (Exception ignored) {}
        sp(c).edit().putString("drives", a.toString()).apply();
        cache = null;
        return d.id;
    }

    static synchronized void deleteDrive(Context c, String id) {
        JSONArray a = rawDrives(c), b = new JSONArray();
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x != null && !id.equals(x.optString("id"))) b.put(x);
        }
        SharedPreferences.Editor ed = sp(c).edit().putString("drives", b.toString());
        if (id.equals(sp(c).getString("share_drive", ""))) ed.remove("share_drive").remove("share_dir");
        ed.apply();
        cache = null;
        // bỏ các thư mục ghim của ổ này
        List<String[]> fav = favorites(c), keep = new ArrayList<>();
        for (String[] f : fav) if (!f[0].equals(id)) keep.add(f);
        saveFavorites(c, keep);
    }

    static synchronized void moveDrive(Context c, String id, int delta) {
        JSONArray a = rawDrives(c);
        List<JSONObject> l = new ArrayList<>();
        int idx = -1;
        for (int i = 0; i < a.length(); i++) {
            JSONObject x = a.optJSONObject(i);
            if (x == null) continue;
            if (id.equals(x.optString("id"))) idx = l.size();
            l.add(x);
        }
        int to = idx + delta;
        if (idx < 0 || to < 0 || to >= l.size()) return;
        JSONObject t = l.remove(idx);
        l.add(to, t);
        JSONArray b = new JSONArray();
        for (JSONObject x : l) b.put(x);
        sp(c).edit().putString("drives", b.toString()).apply();
        cache = null;
    }

    /** Nhận "1.2.3.4", "1.2.3.4:445", "\\1.2.3.4\Share\abc", "smb://1.2.3.4/Share" → {host, port, share}. */
    static String[] splitAddress(String s) {
        s = s == null ? "" : s.trim();
        if (s.toLowerCase(Locale.ROOT).startsWith("smb://")) s = s.substring(6);
        s = s.replace('/', '\\');
        while (s.startsWith("\\")) s = s.substring(1);
        String[] parts = s.split("\\\\");
        String host = parts.length > 0 ? parts[0].trim() : "";
        String share = parts.length > 1 ? parts[1].trim() : "";
        String port = "445";
        int colon = host.lastIndexOf(':');
        if (colon > 0 && host.indexOf(':') == colon) {
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        return new String[]{host, port, share};
    }

    // ---------- Nơi lưu file chia sẻ từ app khác ----------

    static String shareDriveId(Context c) {
        migrate(c);
        String id = sp(c).getString("share_drive", "");
        if (drive(c, id) == null) {
            List<Drive> l = drives(c);
            return l.isEmpty() ? "" : l.get(0).id;
        }
        return id;
    }

    static String shareDir(Context c) { return sp(c).getString("share_dir", DEFAULT_SHARE_DIR); }

    static void setShareDest(Context c, String driveId, String dir) {
        sp(c).edit().putString("share_drive", driveId).putString("share_dir", Fs.norm(dir)).apply();
    }

    static boolean autoUpload(Context c) { return sp(c).getBoolean("auto_upload", true); }

    static void setAutoUpload(Context c, boolean b) { sp(c).edit().putBoolean("auto_upload", b).apply(); }

    // ---------- Hiển thị ----------

    static int sortMode(Context c) { return sp(c).getInt("sort_mode", Fs.SORT_NAME); }

    static boolean sortDesc(Context c) { return sp(c).getBoolean("sort_desc", false); }

    static void setSort(Context c, int mode, boolean desc) { sp(c).edit().putInt("sort_mode", mode).putBoolean("sort_desc", desc).apply(); }

    static boolean showHidden(Context c) { return sp(c).getBoolean("show_hidden", false); }

    static void setShowHidden(Context c, boolean b) { sp(c).edit().putBoolean("show_hidden", b).apply(); }

    // ---------- Thư mục ghim: {driveId, path} ----------

    static List<String[]> favorites(Context c) {
        List<String[]> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("favorites", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new String[]{o.getString("d"), o.getString("p")});
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static void saveFavorites(Context c, List<String[]> l) {
        JSONArray a = new JSONArray();
        try {
            for (String[] f : l) a.put(new JSONObject().put("d", f[0]).put("p", f[1]));
        } catch (Exception ignored) {}
        sp(c).edit().putString("favorites", a.toString()).apply();
    }

    static boolean isFav(Context c, String driveId, String path) {
        for (String[] f : favorites(c)) if (f[0].equals(driveId) && f[1].equalsIgnoreCase(path)) return true;
        return false;
    }

    static void setFav(Context c, String driveId, String path, boolean on) {
        List<String[]> l = favorites(c), keep = new ArrayList<>();
        for (String[] f : l) if (!(f[0].equals(driveId) && f[1].equalsIgnoreCase(path))) keep.add(f);
        if (on) keep.add(new String[]{driveId, path});
        saveFavorites(c, keep);
    }

    // ---------- Tệp đang sửa bằng app khác ----------

    static JSONObject pendingEdit(Context c) {
        try {
            String s = sp(c).getString("pending_edit", "");
            return s.isEmpty() ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static void setPendingEdit(Context c, JSONObject o) {
        sp(c).edit().putString("pending_edit", o == null ? "" : o.toString()).apply();
    }

    // ---------- Mã hoá mật khẩu ----------

    private static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }

    private static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) return "";
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[1 + iv.length + ct.length];
            out[0] = (byte) iv.length;
            System.arraycopy(iv, 0, out, 1, iv.length);
            System.arraycopy(ct, 0, out, 1 + iv.length, ct.length);
            return "k:" + Base64.encodeToString(out, Base64.NO_WRAP);
        } catch (Exception e) {
            return "p:" + Base64.encodeToString(plain.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        }
    }

    private static String decrypt(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            if (s.startsWith("p:")) return new String(Base64.decode(s.substring(2), Base64.NO_WRAP), StandardCharsets.UTF_8);
            if (!s.startsWith("k:")) return "";
            byte[] in = Base64.decode(s.substring(2), Base64.NO_WRAP);
            int n = in[0] & 0xff;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, in, 1, n));
            return new String(c.doFinal(in, 1 + n, in.length - 1 - n), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private Cfg() {}
}
