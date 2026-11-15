package com.lau.iptv;

import android.net.Network;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 实时简中字幕客户端（v1.21）。
 *
 * 服务端旁路把直播音频识别 + 翻译后，通过 SSE 推来带 ptsMs 的字幕片段。
 * 这里不按「收到就显示」，而是把每条字幕换算成播放时钟上的显示区间
 * （showAt = ptsMs + offsetMs），由 MainActivity 的轮询用
 * player.getCurrentPosition() 驱动显示，从而吸收直播缓冲造成的错位。
 * 切台时清空队列并通知服务端换 session，旧频道迟到的字幕一律丢弃。
 */
public class SubtitleClient {

    public interface Listener {
        void onCue(String src, String zh, String mode);
        void onClear();
        void onState(String text, boolean error);
    }

    private static final class Cue {
        final long showAtMs;
        final long hideAtMs;
        final String src;
        final String zh;
        final String mode;

        Cue(long showAtMs, long hideAtMs, String src, String zh, String mode) {
            this.showAtMs = showAtMs;
            this.hideAtMs = hideAtMs;
            this.src = src;
            this.zh = zh;
            this.mode = mode;
        }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService post = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final List<Cue> pending = new ArrayList<>();
    private Listener listener;
    private String baseUrl = "";
    private long offsetMs = -1500;
    private volatile boolean enabled = false;
    private volatile boolean active = false;
    private volatile Socket sseSocket;
    /** 物理网络句柄：显式绑定字幕连接，避免换台时 bindProcessToNetwork 重置 socket */
    private volatile Network network;
    private volatile int acceptedSession = -1;
    private volatile String currentChannel = "";
    private Thread sseThread;
    private String lastRenderedKey = "";
    private boolean visible = false;
    private long lastShownAt;
    /** 切台时的播放位置与墙钟，用来把服务端 pts 换算成「距切台经过的毫秒」 */
    private long posAtSwitch;
    private long switchAtMs;

    public void setListener(Listener l) {
        this.listener = l;
    }

    /**
     * Sony BRAVIA 实测：HttpURLConnection 走 OkHttp 时长连接被判为
     * "unexpected end of stream"，Network.openConnection 又会 EPERM。
     * 字幕服务是局域网纯 HTTP，改用裸 Socket 直连最稳。
     */
    public void setNetwork(Network n) {
        // no-op：进程已由 bindProcessToNetwork 绑定物理网络
    }

    private String[] hostPort() throws Exception {
        URL u = new URL(baseUrl);
        if (!"http".equals(u.getProtocol())) {
            throw new Exception("字幕服务仅支持局域网 http");
        }
        int port = u.getPort() > 0 ? u.getPort() : 80;
        if (u.getHost().isEmpty()) throw new Exception("字幕服务地址缺少主机");
        return new String[]{u.getHost(), String.valueOf(port)};
    }

    /** 服务地址变化时断开当前 SSE，让接收循环用新地址重连。 */
    public void configure(String base, long offsetMs, boolean enabled) {
        String b = base == null ? "" : base.trim().replaceAll("/+$", "");
        boolean changed = !b.equals(this.baseUrl);
        this.baseUrl = b;
        this.offsetMs = offsetMs;
        this.enabled = enabled;
        if (changed && running.get()) disconnect();
    }

    public void start() {
        if (running.getAndSet(true)) return;
        sseThread = new Thread(this::sseLoop, "subtitle-sse");
        sseThread.setDaemon(true);
        sseThread.start();
    }

    public void stop() {
        running.set(false);
        disconnect();
        post.shutdownNow();
        if (sseThread != null) sseThread.interrupt();
        synchronized (pending) { pending.clear(); }
    }

    private void disconnect() {
        Socket sock = sseSocket;
        sseSocket = null;
        if (sock != null) {
            try { sock.close(); } catch (Exception ignored) { }
        }
    }

    /**
     * 频道切换入口。playUrl 必须是已解析的最终地址（不能是 ytlive:// / twitchlive://）。
     * active=false 时通知服务端停掉 ffmpeg，避免为不显示字幕的频道白拉一路流。
     */
    public void onChannel(String channelName, String playUrl, boolean active, long playerPosMs) {
        this.active = active;
        this.currentChannel = channelName == null ? "" : channelName;
        this.acceptedSession = -1;
        this.switchAtMs = System.currentTimeMillis();
        this.posAtSwitch = (playerPosMs >= 0 && playerPosMs < 86400000L) ? playerPosMs : 0;
        synchronized (pending) { pending.clear(); }
        clearCue();
        postSession(active ? playUrl : "", this.currentChannel, active);
    }

    /** 由 MainActivity 以约 120ms 间隔调用，传入播放器当前位置（毫秒）。 */
    public void tick(long positionMs) {
        if (!enabled || !active) {
            clearCue();
            return;
        }
        // 主时钟用播放位置（贴合画面）；直播返回 TIME_UNSET 或换 MediaItem 时回退墙钟
        long elapsed;
        if (positionMs >= 0 && positionMs < 86400000L) {
            if (positionMs < posAtSwitch) posAtSwitch = positionMs;
            elapsed = positionMs - posAtSwitch;
        } else {
            elapsed = System.currentTimeMillis() - switchAtMs;
        }
        if (elapsed < 0) elapsed = 0;
        Cue show = null;
        synchronized (pending) {
            for (int i = pending.size() - 1; i >= 0; i--) {
                Cue c = pending.get(i);
                if (c.hideAtMs < elapsed - 6000) {
                    pending.subList(0, i + 1).clear();
                    break;
                }
                if (c.showAtMs <= elapsed && elapsed < c.hideAtMs) {
                    show = c;
                    break;
                }
            }
        }
        if (show != null) {
            lastShownAt = System.currentTimeMillis();
            String key = show.showAtMs + "|" + show.zh;
            if (key.equals(lastRenderedKey) && visible) return;
            lastRenderedKey = key;
            visible = true;
            final Cue f = show;
            main.post(() -> {
                if (listener != null) listener.onCue(f.src, f.zh, f.mode);
            });
        } else if (System.currentTimeMillis() - lastShownAt > 3000) {
            // 3 秒没有可显示的字幕（静音 / 服务断流）就收起，不阻塞播放
            clearCue();
        }
    }

    private void clearCue() {
        if (!visible) return;
        visible = false;
        lastRenderedKey = "";
        main.post(() -> {
            if (listener != null) listener.onClear();
        });
    }

    private void sseLoop() {
        int backoff = 1000;
        while (running.get()) {
            if (baseUrl.isEmpty()) {
                sleep(1000);
                continue;
            }
            Socket sock = null;
            try {
                String[] hp = hostPort();
                sock = new Socket();
                sock.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 4000);
                sock.setSoTimeout(20000);
                sseSocket = sock;
                String request = "GET /events HTTP/1.0\r\nHost: " + hp[0] + ":" + hp[1]
                        + "\r\nAccept: text/event-stream\r\nConnection: close\r\n\r\n";
                sock.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
                sock.getOutputStream().flush();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                String line;
                boolean headersDone = false;
                while (running.get() && (line = reader.readLine()) != null) {
                    if (!headersDone) {
                        if (line.isEmpty()) {
                            headersDone = true;
                            backoff = 1000;
                            notifyState("字幕服务已连接", false);
                        }
                        continue;
                    }
                    if (line.startsWith("data: ")) handleData(line.substring(6));
                }
                if (!headersDone) throw new java.io.IOException("响应头不完整");
            } catch (Exception e) {
                if (running.get()) {
                    android.util.Log.w("IPTV", "SUBTITLE_SSE_FAIL " + e);
                    notifyState("字幕服务未连接 · " + brief(e.getMessage()), true);
                }
            } finally {
                if (sock != null) {
                    try { sock.close(); } catch (Exception ignored) { }
                }
                sseSocket = null;
            }
            if (!running.get()) break;
            sleep(backoff);
            backoff = Math.min(backoff * 2, 15000);
        }
    }

    private void handleData(String data) {
        try {
            JSONObject o = new JSONObject(data);
            String type = o.optString("type");
            int sid = o.optInt("session", -1);
            if ("reset".equals(type)) {
                acceptedSession = sid;
                synchronized (pending) { pending.clear(); }
                clearCue();
                return;
            }
            if ("error".equals(type)) {
                notifyState(o.optString("message", "字幕服务异常"), true);
                return;
            }
            if (!"cue".equals(type)) return;
            if (!enabled || !active) return;
            if (acceptedSession < 0 || sid < acceptedSession) return;  // 旧频道迟到字幕
            String channel = o.optString("channel", "");
            if (!currentChannel.isEmpty() && !channel.isEmpty()
                    && !currentChannel.equals(channel)) return;
            long pts = o.optLong("ptsMs", 0);
            long dur = o.optLong("durMs", 3200);
            long showAt = Math.max(0, pts + offsetMs);
            Cue cue = new Cue(showAt, showAt + dur,
                    o.optString("src", ""), o.optString("zh", ""),
                    o.optString("mode", ""));
            android.util.Log.d("IPTV", "SUBTITLE_CUE pts=" + pts + " showAt=" + showAt
                    + " zh=" + brief(cue.zh));
            synchronized (pending) {
                pending.add(cue);
                if (pending.size() > 64) pending.remove(0);
            }
        } catch (Exception e) {
            android.util.Log.w("IPTV", "SUBTITLE_BAD_EVENT " + brief(data));
        }
    }

    private void postSession(String url, String channel, boolean enabled) {
        if (baseUrl.isEmpty()) return;
        post.execute(() -> {
            Socket sock = null;
            try {
                JSONObject body = new JSONObject();
                body.put("url", url == null ? "" : url);
                body.put("channel", channel == null ? "" : channel);
                body.put("enabled", enabled);
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                String[] hp = hostPort();
                sock = new Socket();
                sock.connect(new InetSocketAddress(hp[0], Integer.parseInt(hp[1])), 4000);
                sock.setSoTimeout(6000);
                String head = "POST /session HTTP/1.0\r\nHost: " + hp[0] + ":" + hp[1]
                        + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                        + "Content-Length: " + bytes.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream os = sock.getOutputStream();
                os.write(head.getBytes(StandardCharsets.US_ASCII));
                os.write(bytes);
                os.flush();
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
                String status = reader.readLine();
                if (status == null || !status.contains(" 200 ")) {
                    notifyState("字幕服务换台失败 · " + brief(status), true);
                }
            } catch (Exception e) {
                notifyState("字幕服务不可达 · " + brief(e.getMessage()), true);
                android.util.Log.w("IPTV", "SUBTITLE_SESSION_FAIL " + e);
            } finally {
                if (sock != null) {
                    try { sock.close(); } catch (Exception ignored) { }
                }
            }
        });
    }

    private void notifyState(String text, boolean error) {
        main.post(() -> {
            if (listener != null) listener.onState(text, error);
        });
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { }
    }

    private static String brief(String s) {
        if (s == null) return "未知错误";
        return s.length() > 42 ? s.substring(0, 42) + "…" : s;
    }
}
