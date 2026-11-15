package com.lau.iptv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube 24/7 直播源解析（v1.13）。
 *
 * YouTube 直播的 HLS 地址带 expire 签名且绑定请求 IP，几小时后必然失效，
 * 因此不能把地址写死进 APK。内置表里这类频道用 ytlive://&lt;videoId&gt; 标记，
 * 播放前由本类实时向 Invidious 公共实例 / YouTube 网页取回可用的 HLS 清单，
 * 并缓存 15 分钟。所有端点都失败时会回调 onError，由播放器切下一线路。
 */
public final class YouTubeResolver {

    public interface Callback {
        void onResolved(String hlsUrl);
        void onError(String reason);
    }

    private static final String PREFIX = "ytlive://";
    private static final long TTL_MS = 15L * 60L * 1000L;
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final Pattern HLS = Pattern.compile("\"hlsManifestUrl\"\\s*:\\s*\"([^\"]+)\"");

    /** 依序尝试；Invidious 实例失效时自动回退到 YouTube 网页内嵌的 playerResponse。 */
    private static final String[] ENDPOINTS = {
            "https://yt.chocolatemoo53.com/api/v1/videos/",
            "https://inv.nadeko.net/api/v1/videos/",
            "https://invidious.tiekoetter.com/api/v1/videos/",
            "https://www.youtube.com/watch?v="
    };

    private static final class Entry {
        final String url;
        final long at;
        Entry(String u, long t) { url = u; at = t; }
    }

    private YouTubeResolver() { }

    public static boolean isYtLive(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    private static String idOf(String url) {
        return url.substring(PREFIX.length()).trim();
    }

    public static void resolve(String url, Callback cb) {
        final String id = idOf(url);
        Entry hit = CACHE.get(id);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) {
            cb.onResolved(hit.url);
            return;
        }
        EXEC.execute(() -> {
            String found = null;
            String lastErr = "无可用解析端点";
            for (String ep : ENDPOINTS) {
                try {
                    String target = ep.endsWith("?v=") ? ep + id : ep + id;
                    String body = httpGet(target);
                    String hls = ep.contains("/api/v1/videos/") ? fromInvidious(body) : fromWatchPage(body);
                    if (hls != null && hls.startsWith("http")) {
                        found = hls;
                        break;
                    }
                    lastErr = "解析器未返回直播清单";
                } catch (Exception e) {
                    lastErr = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                }
            }
            final String result = found;
            final String err = lastErr;
            MAIN.post(() -> {
                if (result != null) {
                    CACHE.put(id, new Entry(result, System.currentTimeMillis()));
                    cb.onResolved(result);
                } else {
                    cb.onError(err);
                }
            });
        });
    }

    private static String fromInvidious(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        String hls = o.optString("hlsUrl", "");
        if (hls.isEmpty() || "null".equals(hls)) {
            hls = o.optString("dashUrl", "");
        }
        return hls.isEmpty() || "null".equals(hls) ? null : hls;
    }

    private static String fromWatchPage(String body) {
        Matcher m = HLS.matcher(body);
        if (!m.find()) return null;
        return m.group(1).replace("\\u0026", "&").replace("\\/", "/");
    }

    private static String httpGet(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOExceptionLike("HTTP " + code);
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            int total = 0;
            while ((n = in.read(buf)) > 0 && total < 4 * 1024 * 1024) {
                out.write(buf, 0, n);
                total += n;
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    /** 轻量异常包装，避免为状态文本引入额外依赖。 */
    private static final class IOExceptionLike extends Exception {
        IOExceptionLike(String m) { super(m); }
    }
}
