package vn.hung.wdcloud;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Thông tin kết nối lưu trong máy. Mật khẩu mã hoá bằng khoá trong Android Keystore. */
final class Cfg {
    static final String DEFAULT_HOST = "";
    static final String DEFAULT_UPLOAD = "Tu dien thoai";
    private static final String KEY_ALIAS = "wdcloud_cfg";

    static final class Conf {
        String host = "", share = "", user = "", pass = "", domain = "";
        int port = 445;

        boolean ready() { return !host.isEmpty() && !share.isEmpty(); }

        String key() { return host + ":" + port + "|" + share + "|" + domain + "|" + user + "|" + pass.hashCode(); }

        String label() { return "\\\\" + host + (port == 445 ? "" : ":" + port) + "\\" + share; }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("cfg", Context.MODE_PRIVATE);
    }

    static Conf load(Context c) {
        SharedPreferences p = sp(c);
        Conf k = new Conf();
        k.host = p.getString("host", DEFAULT_HOST);
        k.port = p.getInt("port", 445);
        k.share = p.getString("share", "");
        k.user = p.getString("user", "");
        k.domain = p.getString("domain", "");
        k.pass = decrypt(p.getString("pass", ""));
        return k;
    }

    static void save(Context c, Conf k) {
        sp(c).edit()
                .putString("host", k.host).putInt("port", k.port).putString("share", k.share)
                .putString("user", k.user).putString("domain", k.domain)
                .putString("pass", encrypt(k.pass))
                .apply();
    }

    static String uploadDir(Context c) { return sp(c).getString("upload_dir", DEFAULT_UPLOAD); }

    static void setUploadDir(Context c, String d) { sp(c).edit().putString("upload_dir", normPath(d)).apply(); }

    /** Thư mục dùng lần gần nhất khi nhận file chia sẻ (mặc định = thư mục lưu trong cài đặt). */
    static String lastShareDir(Context c) { return sp(c).getString("last_share_dir", uploadDir(c)); }

    static void setLastShareDir(Context c, String d) { sp(c).edit().putString("last_share_dir", normPath(d)).apply(); }

    static void clearLastShareDir(Context c) { sp(c).edit().remove("last_share_dir").apply(); }

    static boolean autoUpload(Context c) { return sp(c).getBoolean("auto_upload", true); }

    static void setAutoUpload(Context c, boolean b) { sp(c).edit().putBoolean("auto_upload", b).apply(); }

    /**
     * Nhận địa chỉ dạng "1.2.3.4", "1.2.3.4:445", "\\1.2.3.4\Public\abc" hoặc "smb://1.2.3.4/Public".
     * Trả về {host, port, share} (share rỗng nếu không có).
     */
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
        if (colon > 0 && host.indexOf(':') == colon) { // không đụng IPv6
            port = host.substring(colon + 1);
            host = host.substring(0, colon);
        }
        return new String[]{host, port, share};
    }

    /** Chuẩn hoá đường dẫn trong share: dùng "\", bỏ "\" đầu/cuối. Gốc = "". */
    static String normPath(String p) {
        if (p == null) return "";
        p = p.trim().replace('/', '\\').replaceAll("\\\\+", "\\\\");
        while (p.startsWith("\\")) p = p.substring(1);
        while (p.endsWith("\\")) p = p.substring(0, p.length() - 1);
        return p.trim();
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
            // Máy không có Keystore dùng được: vẫn lưu trong vùng riêng của app (app khác không đọc được)
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
