package com.lau.iptv;

import android.net.Uri;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * 信任所有证书的 http/https 数据源。
 * IPTV 直播源大量使用自签名证书 + http→https 跨协议重定向，
 * DefaultHttpDataSource 严格校验会直接失败，这里统一放行。
 * 同一实现既给播放器用，也给线路探测（ProbeReceiver）用，
 * 保证探测结果与真实播放的网络行为完全一致。
 */
public class TrustAllDataSource extends BaseDataSource {
    private final String ua;
    private final int connectMs, readMs;
    private HttpURLConnection conn;
    private InputStream in;
    private Uri uri;
    private boolean opened, ended;

    public TrustAllDataSource() { this("Mozilla/5.0", 12000, 18000); }

    public TrustAllDataSource(String ua, int connectMs, int readMs) {
        super(/* isNetwork= */ true);
        this.ua = ua;
        this.connectMs = connectMs;
        this.readMs = readMs;
    }

    private static void trustAll(HttpsURLConnection c) throws Exception {
        TrustManager[] tm = new TrustManager[]{new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] ch, String a) { }
            @Override public void checkServerTrusted(X509Certificate[] ch, String a) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }};
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, tm, new java.security.SecureRandom());
        c.setSSLSocketFactory(sc.getSocketFactory());
        c.setHostnameVerifier((hostname, session) -> true);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        transferInitializing(dataSpec);
        try {
            URL url = new URL(dataSpec.uri.toString());
            int code = 0;
            for (int i = 0; i < 6; i++) {
                conn = (HttpURLConnection) url.openConnection();
                if (conn instanceof HttpsURLConnection) trustAll((HttpsURLConnection) conn);
                conn.setConnectTimeout(connectMs);
                conn.setReadTimeout(readMs);
                conn.setRequestProperty("User-Agent", ua);
                conn.setInstanceFollowRedirects(false);
                if (dataSpec.position > 0) {
                    conn.setRequestProperty("Range", "bytes=" + dataSpec.position + "-");
                }
                code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    conn.disconnect(); conn = null;
                    if (loc == null) break;
                    url = new URL(url, loc);
                    continue;
                }
                break;
            }
            if (code >= 400) {
                throw new IOException("HTTP " + code + " " + url);
            }
            in = conn.getInputStream();
            uri = Uri.parse(url.toString());
            transferStarted(dataSpec);
            opened = true;
            return dataSpec.length != C.LENGTH_UNSET ? dataSpec.length : C.LENGTH_UNSET;
        } catch (Exception e) {
            if (e instanceof IOException) {
                Log.w("IPTV", "open failed: " + dataSpec.uri + " -> " + e.getMessage());
                throw (IOException) e;
            }
            throw new IOException("open failed " + dataSpec.uri + ": " + e.getMessage());
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (ended) return C.RESULT_END_OF_INPUT;
        int n = in.read(buffer, offset, length);
        if (n == -1) {
            ended = true;
            close();
            return C.RESULT_END_OF_INPUT;
        }
        bytesTransferred(n);
        return n;
    }

    @Override
    public Uri getUri() { return uri; }

    @Override
    public Map<String, List<String>> getResponseHeaders() { return Collections.emptyMap(); }

    @Override
    public void close() throws IOException {
        if (!opened) { ended = false; return; }
        opened = false; ended = false;
        try { if (in != null) in.close(); } catch (IOException ignored) { }
        if (conn != null) conn.disconnect();
        in = null; conn = null;
        transferEnded();
    }
}
