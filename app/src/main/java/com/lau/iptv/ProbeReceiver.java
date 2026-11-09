package com.lau.iptv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.util.Log;

import androidx.media3.datasource.DataSpec;

/**
 * 线路探测广播（诊断用）：
 *   adb shell am broadcast -n com.lau.iptv/.ProbeReceiver -a com.lau.iptv.PROBE --es url <URL>
 * 用与播放器完全一致的网络栈（进程网络绑定 + 信任所有证书）实测一条线路，
 * 结果写 logcat（IPTVPROBE tag）：
 *   IPTVPROBE: <url> | OK-HLS 1234ms   （拉到 m3u8 播放列表，可用）
 *   IPTVPROBE: <url> | OK 890ms        （连通但首段非 m3u8）
 *   IPTVPROBE: <url> | FAIL ...        （HTTP 4xx/5xx、超时、连接失败）
 */
public class ProbeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String url = intent.getStringExtra("url");
        if (url == null || url.isEmpty()) return;
        new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            String result;
            try {
                TrustAllDataSource ds = new TrustAllDataSource("Mozilla/5.0", 8000, 8000);
                ds.open(new DataSpec(android.net.Uri.parse(url)));
                byte[] buf = new byte[4096];
                int n = ds.read(buf, 0, buf.length);
                ds.close();
                String head = n > 0 ? new String(buf, 0, Math.min(n, 160),
                        java.nio.charset.StandardCharsets.UTF_8) : "";
                boolean hls = head.contains("#EXTM3U");
                result = (hls ? "OK-HLS" : "OK") + " " + (SystemClock.elapsedRealtime() - t0) + "ms";
            } catch (Exception e) {
                result = "FAIL " + e.getMessage() + " " + (SystemClock.elapsedRealtime() - t0) + "ms";
            }
            Log.i("IPTVPROBE", url + " | " + result);
        }, "probe").start();
    }
}
