package com.lau.iptv;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Twitch 直播源运行时解析（v1.13）。
 *
 * Twitch 的 HLS 清单由 usher 用带签名的 token 动态生成，token 有时效且清单里
 * 的 CDN 分片地址会轮换，因此不能把地址写死进 APK。内置表里这类频道用
 * twitchlive://&lt;频道登录名&gt; 标记，播放前由本类向 Twitch GraphQL 公开接口
 * 取回 PlaybackAccessToken，拼出 usher 清单并校验频道确实在播，缓存 30 分钟。
 */
public final class TwitchResolver {

    public interface Callback {
        void onResolved(String hlsUrl);
        void onError(String reason);
    }

    private static final String PREFIX = "twitchlive://";
    private static final String CLIENT_ID = "kimne78kx3ncx6brgo4mv6wki5h1ko";
    private static final String GQL = "https://gql.twitch.tv/gql";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36";
    private static final String QUERY =
            "query P($login: String!, $playerType: String!) { "
            + "streamPlaybackAccessToken(channelName: $login, params: {platform: \"web\", "
            + "playerBackend: \"mediaplayer\", playerType: $playerType}) { value signature }}";

    private static final long TTL_MS = 30L * 60L * 1000L;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor();
    private static final ConcurrentHashMap<String, Entry> CACHE = new ConcurrentHashMap<>();

    private static final class Entry {
        final String url;
        final long at;
        Entry(String u, long t) { url = u; at = t; }
    }

    private TwitchResolver() { }

    public static boolean isTwitchLive(String url) {
        return url != null && url.startsWith(PREFIX);
    }

    public static void resolve(String raw, Callback cb) {
        final String login = raw.substring(PREFIX.length()).trim();
        Entry hit = CACHE.get(login);
        if (hit != null && System.currentTimeMillis() - hit.at < TTL_MS) {
            cb.onResolved(hit.url);
            return;
        }
        EXEC.execute(() -> {
            String result = null;
            String err = "Twitch 解析失败";
            try {
                JSONObject vars = new JSONObject()
                        .put("login", login)
                        .put("playerType", "site");
                JSONObject body = new JSONObject()
                        .put("query", QUERY)
                        .put("variables", vars);
                String resp = httpPostJson(body.toString());
                JSONObject root = new JSONObject(resp);
                JSONObject tok = root.getJSONObject("data")
                        .getJSONObject("streamPlaybackAccessToken");
                String value = tok.getString("value");
                String sig = tok.getString("signature");
                String enc = URLEncoder.encode(value, "UTF-8").replace("+", "%20");
                String hls = "https://usher.ttvnw.net/api/channel/hls/" + login + ".m3u8"
                        + "?client_id=" + CLIENT_ID
                        + "&token=" + enc
                        + "&sig=" + sig
                        + "&allow_source=true&allow_audio_only=true";
                String manifest = httpGet(hls);
                if (!manifest.contains("#EXTM3U")) {
                    throw new IOExceptionLike("频道当前未开播");
                }
                result = hls;
            } catch (Exception e) {
                err = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
                android.util.Log.w("IPTV", "TwitchResolver: " + err, e);
            }
            final String r = result;
            final String e = err;
            MAIN.post(() -> {
                if (r != null) {
                    CACHE.put(login, new Entry(r, System.currentTimeMillis()));
                    cb.onResolved(r);
                } else {
                    cb.onError(e);
                }
            });
        });
    }

    private static String httpPostJson(String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(GQL).openConnection();
        try {
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Client-ID", CLIENT_ID);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            OutputStream os = c.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOExceptionLike("GraphQL HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String httpGet(String target) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(target).openConnection();
        try {
            c.setRequestMethod("GET");
            c.setConnectTimeout(8000);
            c.setReadTimeout(10000);
            c.setRequestProperty("Client-ID", CLIENT_ID);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "*/*");
            c.setInstanceFollowRedirects(true);
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new IOExceptionLike("usher HTTP " + code);
            return readAll(c.getInputStream());
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n, total = 0;
        while ((n = in.read(buf)) > 0 && total < 4 * 1024 * 1024) {
            out.write(buf, 0, n);
            total += n;
        }
        in.close();
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }

    private static final class IOExceptionLike extends Exception {
        IOExceptionLike(String m) { super(m); }
    }
}
