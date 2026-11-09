package com.lau.iptv;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 台标加载器：内存 LruCache + 磁盘缓存 + 后台线程下载。
 * 零第三方依赖，与播放器同一网络栈（信任所有证书 + 跟随重定向）。
 */
public class LogoLoader {
    private static final LruCache<String, Bitmap> MEM =
            new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap v) {
                    return v.getByteCount();
                }
            };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static File cacheDir;
    private static android.content.Context appCtx;
    /** 负结果缓存（下载失败的台标不反复重试） */
    private static final java.util.Set<String> FAILED =
            java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    public static void init(Context ctx) {
        appCtx = ctx.getApplicationContext();
        cacheDir = new File(ctx.getCacheDir(), "logos");
        if (!cacheDir.exists()) cacheDir.mkdirs();
    }

    /** 与打包 assets 的命名一致：URL 的 MD5 十六进制 */
    private static String key(String url) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("MD5")
                    .digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(url.hashCode());
        }
    }

    /** 绑定台标到 ImageView；ImageView.tag 记录当前 URL，防止回收复用错位 */
    public static void into(ImageView view, String url) {
        if (view == null) return;
        if (url == null || url.isEmpty() || FAILED.contains(url)) {
            view.setImageBitmap(null);
            view.setVisibility(ImageView.GONE);
            return;
        }
        view.setVisibility(ImageView.VISIBLE);
        view.setTag(url);
        Bitmap hit = MEM.get(url);
        if (hit != null) { view.setImageBitmap(hit); return; }
        view.setImageBitmap(null);
        POOL.execute(() -> {
            Bitmap bmp = load(url);
            if (bmp == null) { FAILED.add(url); return; }
            MEM.put(url, bmp);
            MAIN.post(() -> {
                if (url.equals(view.getTag())) view.setImageBitmap(bmp);
            });
        });
    }

    private static Bitmap load(String url) {
        // 0. 内置 assets（打包进 APK 的台标，离线可用，绕开 DNS 污染）
        if (appCtx != null) {
            try (InputStream a = appCtx.getAssets().open("logos/" + key(url) + ".png")) {
                Bitmap b = BitmapFactory.decodeStream(a);
                if (b != null) return b;
            } catch (Exception ignored) { }
        }
        // 1. 磁盘缓存
        File f = new File(cacheDir, key(url));
        if (f.exists() && f.length() > 200) {
            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());
            if (b != null) return b;
        }
        // 2. 下载
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            if (conn instanceof javax.net.ssl.HttpsURLConnection) {
                javax.net.ssl.HttpsURLConnection hc = (javax.net.ssl.HttpsURLConnection) conn;
                javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
                sc.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
                    @Override public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) { }
                    @Override public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) { }
                    @Override public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
                }}, new java.security.SecureRandom());
                hc.setSSLSocketFactory(sc.getSocketFactory());
                hc.setHostnameVerifier((h, s) -> true);
            }
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) return null;
            InputStream in = conn.getInputStream();
            File tmp = new File(f.getAbsolutePath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.close();
            in.close();
            if (tmp.length() < 200) { tmp.delete(); return null; }
            if (!tmp.renameTo(f)) tmp.delete();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            // 台标卡最大 240px 宽即可
            int sample = 1;
            while (o.outWidth / sample > 240) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (Exception e) {
            Log.w("IPTV", "logo failed " + url + " " + e.getMessage());
            return null;
        }
    }
}
