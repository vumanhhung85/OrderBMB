package vn.hung.wdcloud;

import android.webkit.MimeTypeMap;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import vn.hung.wdcloud.core.Fs;

/** Loại tệp: kiểu MIME, biểu tượng, có sửa được bằng trình sửa văn bản không. */
final class Kind {
    private static final Set<String> TEXT = new HashSet<>(Arrays.asList(
            "txt", "csv", "md", "log", "json", "xml", "ini", "cfg", "conf", "html", "htm", "css", "js",
            "bat", "cmd", "sql", "yml", "yaml", "properties", "tsv", "srt", "reg", "ps1", "sh", "vbs"));
    static final long TEXT_MAX = 1024 * 1024;

    static String mime(String name) {
        String e = Fs.ext(name);
        if (e.isEmpty()) return "application/octet-stream";
        String t = MimeTypeMap.getSingleton().getMimeTypeFromExtension(e);
        if (t == null && TEXT.contains(e)) return "text/plain";
        return t == null ? "application/octet-stream" : t;
    }

    static boolean isText(String name) { return TEXT.contains(Fs.ext(name)); }

    static String icon(Fs.Entry e) {
        if (e.dir) return "📁";
        String m = mime(e.name), x = Fs.ext(e.name);
        if (m.startsWith("image/")) return "🖼️";
        if (m.startsWith("video/")) return "🎬";
        if (m.startsWith("audio/")) return "🎵";
        if (x.equals("pdf")) return "📕";
        if (x.equals("xls") || x.equals("xlsx") || x.equals("csv")) return "📊";
        if (x.equals("doc") || x.equals("docx")) return "📘";
        if (x.equals("ppt") || x.equals("pptx")) return "📙";
        if (x.equals("zip") || x.equals("rar") || x.equals("7z")) return "📦";
        if (x.equals("apk")) return "🤖";
        if (x.equals("exe") || x.equals("msi")) return "⚙️";
        if (isText(e.name)) return "📝";
        return "📄";
    }

    private Kind() {}
}
