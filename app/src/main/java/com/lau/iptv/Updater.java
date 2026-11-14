package com.lau.iptv;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用内在线更新：检查 GitHub 最新 Release 并下载 APK 安装。
 * 电视直连网络访问 GitHub 不稳定，走「直连 + 镜像」双通道。
 */
public final class Updater {

    public static final String REPO = "JacksonLau23/prototype-tv";
    private static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    /** GitHub 加速镜像前缀（直连失败时依次尝试） */
    private static final String[] PROXIES = {"", "https://gh-proxy.com/", "https://ghproxy.net/"};
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface CheckCallback {
        void onResult(Release release, String error);
    }

    public interface DownloadProgress {
        void onProgress(int percent, long doneBytes, long totalBytes);
    }

    /** 解析出的最新版本信息 */
    public static final class Release {
        public String tag;      // 如 "v1.7"
        public String title;    // Release 标题
        public String notes;    // 更新说明
        public String apkUrl;   // .apk 下载地址
        public long apkSize;    // 字节
        public String apkName;

        /** 当前版本是否落后于该 Release */
        public boolean newerThan(String currentVersion) {
            return compareVersions(stripV(tag), stripV(currentVersion)) > 0;
        }

        private static String stripV(String s) {
            if (s == null) return "0";
            s = s.trim();
            if (s.startsWith("v") || s.startsWith("V")) s = s.substring(1);
            return s.isEmpty() ? "0" : s;
        }
    }

    /** 比较形如 "1.7" 与 "1.10" 的版本号：返回 >0 表示 a 更新 */
    public static int compareVersions(String a, String b) {
        String[] pa = a.split("[.\\-]");
        String[] pb = b.split("[.\\-]");
        int n = Math.max(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int x = i < pa.length ? parseIntSafe(pa[i]) : 0;
            int y = i < pb.length ? parseIntSafe(pb[i]) : 0;
            if (x != y) return Integer.compare(x, y);
        }
        return 0;
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s.replaceAll("[^0-9]", ""));
        } catch (Exception e) {
            return 0;
        }
    }

    // ===================== 检查更新 =====================

    /** 线程安全：可从任意线程调用，网络请求在工作线程，结果回主线程 */
    public static void check(String currentVersion, CheckCallback cb) {
        new Thread(() -> doCheck(currentVersion, cb), "update-check").start();
    }

    private static void doCheck(String currentVersion, CheckCallback cb) {
        String body = null;
        Exception lastErr = null;
        for (String prefix : PROXIES) {
            try {
                body = httpGet(prefix + API, 12000, 15000);
                if (body != null && body.contains("tag_name")) break;
                body = null;
            } catch (Exception e) {
                lastErr = e;
            }
        }
        if (body == null) {
            final Exception err = lastErr;
            MAIN.post(() -> cb.onResult(null, err == null ? "无法连接 GitHub" : err.getMessage()));
            return;
        }
        try {
            JSONObject json = new JSONObject(body);
            Release r = new Release();
            r.tag = json.optString("tag_name", "");
            r.title = json.optString("name", r.tag);
            r.notes = json.optString("body", "");
            org.json.JSONArray assets = json.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.getJSONObject(i);
                    String name = a.optString("name", "");
                    if (name.toLowerCase().endsWith(".apk")) {
                        r.apkUrl = a.optString("browser_download_url", "");
                        r.apkSize = a.optLong("size", 0);
                        r.apkName = name;
                        break;
                    }
                }
            }
            if (r.tag.isEmpty()) {
                final String msg = "Release 信息不完整";
                MAIN.post(() -> cb.onResult(null, msg));
                return;
            }
            final Release fr = r;
            final boolean newer = r.newerThan(currentVersion);
            MAIN.post(() -> {
                if (newer && fr.apkUrl != null && !fr.apkUrl.isEmpty()) {
                    cb.onResult(fr, null);
                } else {
                    cb.onResult(null, newer ? "新版本缺少 APK 附件" : "latest");
                }
            });
        } catch (Exception e) {
            final String msg = e.getMessage();
            MAIN.post(() -> cb.onResult(null, msg));
        }
    }

    // ===================== 下载并安装 =====================

    /** 在后台线程调用；成功后自动弹出系统安装器（回主线程） */
    public static void downloadAndInstall(Context ctx, Release r, DownloadProgress p) throws Exception {
        File apk = targetFile(ctx);
        Exception lastErr = null;
        for (String prefix : PROXIES) {
            try {
                download(prefix + r.apkUrl, apk, p);
                install(ctx, apk);
                return;
            } catch (InterruptedException e) {
                throw e;
            } catch (Exception e) {
                lastErr = e;
                Log.w("IPTV", "update download failed via " + prefix + ": " + e);
            }
        }
        throw lastErr == null ? new IllegalStateException("下载失败") : lastErr;
    }

    private static File targetFile(Context ctx) {
        File dir = ctx.getExternalCacheDir();
        if (dir == null || !dir.canWrite()) dir = ctx.getCacheDir();
        File f = new File(dir, "update_" + System.currentTimeMillis() + ".apk");
        // 清理旧安装包
        File[] olds = dir.listFiles((d, n) -> n.startsWith("update_") && n.endsWith(".apk"));
        if (olds != null) for (File o : olds) if (!o.equals(f)) o.delete();
        return f;
    }

    private static void download(String url, File out, DownloadProgress p) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
        long total = conn.getContentLength();
        try (InputStream in = conn.getInputStream();
             FileOutputStream fo = new FileOutputStream(out)) {
            byte[] b = new byte[16384];
            long done = 0;
            int n;
            while ((n = in.read(b)) > 0) {
                fo.write(b, 0, n);
                done += n;
                if (p != null) {
                    int percent = total > 0 ? (int) (done * 100 / total) : -1;
                    p.onProgress(percent, done, total);
                }
            }
        } finally {
            conn.disconnect();
        }
        if (out.length() < 100000) {
            out.delete();
            throw new IllegalStateException("下载内容异常（过小）");
        }
    }

    private static void install(Context ctx, File apk) {
        MAIN.post(() -> {
            try {
                Uri uri = FileProvider.getUriForFile(ctx, "com.lau.iptv.fileprovider", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ctx.startActivity(i);
            } catch (Exception e) {
                Log.w("IPTV", "install intent failed: " + e);
            }
        });
    }

    // ===================== 基础 HTTP =====================

    private static String httpGet(String url, int connectMs, int readMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(connectMs);
        conn.setReadTimeout(readMs);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) throw new IllegalStateException("HTTP " + code);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }

    private Updater() {
    }
}
