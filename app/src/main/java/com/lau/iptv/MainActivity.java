package com.lau.iptv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.rtsp.RtspMediaSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    /** 节目源：全部来自 GitHub 上的公开 IPTV 项目，以电视台直播为主 */
    private static final class Source {
        final String name;
        final String[] mirrors;
        final String[] extras;

        Source(String name, String[] mirrors, String... extras) {
            this.name = name;
            this.mirrors = mirrors;
            this.extras = extras == null ? new String[0] : extras;
        }
    }

    private static final String GUOVIN = "https://raw.githubusercontent.com/Guovin/iptv-api/gd/output/result.m3u";
    private static final String VBSKY = "https://raw.githubusercontent.com/vbskycn/iptv/master/tv/iptv4.m3u";
    private static final String YANG = "https://raw.githubusercontent.com/YanG-1989/m3u/main/Gather.m3u";
    private static final String IPTVORG = "https://iptv-org.github.io/iptv/countries/cn.m3u";
    private static final String SPORTS = "https://iptv-org.github.io/iptv/categories/sports.m3u";
    private static final String US = "https://iptv-org.github.io/iptv/countries/us.m3u";
    private static final String WWB = "https://raw.githubusercontent.com/wwb521/live/main/tv.m3u";
    private static final String YUE = "https://raw.githubusercontent.com/YueChan/Live/main/IPTV.m3u";

    private static final Source[] SOURCES = {
            new Source("Prototype 精选（全网抓取 1080P+ 已验证 · 联通秒开线）", new String[]{"asset://bjunicom.m3u"}),
            new Source("内置验证清单（已测速，推荐）", new String[]{"asset://verified.m3u"}),
            new Source("央视 + 卫视 + 各省台 + 体育（在线更新）", new String[]{
                    "https://gh-proxy.com/" + GUOVIN,
                    "https://cdn.jsdelivr.net/gh/Guovin/iptv-api@gd/output/result.m3u",
                    GUOVIN},
                    "https://gh-proxy.com/" + US, US,
                    "https://gh-proxy.com/" + SPORTS, SPORTS),
            new Source("央视 + 卫视 + 地方台（备用源）", new String[]{
                    "https://gh-proxy.com/" + VBSKY,
                    "https://cdn.jsdelivr.net/gh/vbskycn/iptv@master/tv/iptv4.m3u",
                    VBSKY},
                    "https://gh-proxy.com/" + SPORTS, SPORTS),
            new Source("wwb521 直播源", new String[]{
                    "https://gh-proxy.com/" + WWB, WWB}),
            new Source("YueChan 直播源", new String[]{
                    "https://gh-proxy.com/" + YUE, YUE}),
            new Source("咪咕全频道（移动宽带更快）", new String[]{
                    "https://gh-proxy.com/" + YANG,
                    "https://cdn.jsdelivr.net/gh/YanG-1989/m3u@main/Gather.m3u",
                    YANG}),
            new Source("iptv-org 中国大陆频道", new String[]{IPTVORG},
                    "https://gh-proxy.com/" + SPORTS, SPORTS),
            new Source("纯体育赛事（NBA / NFL / ESPN / UFC）", new String[]{
                    "https://gh-proxy.com/" + SPORTS, SPORTS}),
    };

    private static final int MODE_TV = 0;
    private static final int MODE_SPORT = 1;
    private static final int MODE_ALL = 2;

    private static final String PREF = "iptv_pref";
    private static final String KEY_SOURCE = "source_index";
    private static final String KEY_MODE = "show_mode_v2";
    private static final String KEY_ASSET_VER = "asset_ver";
    /** 内置频道表版本号：每次扩充内置表后 +1，自动把用户切回内置源 + 全部频道 */
    private static final int ASSET_VER = 2;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private PlayerView playerView;
    private ExoPlayer player;
    private RtspMediaSource.Factory rtspFactory;
    private SpeedMeter speedMeter;
    private RecyclerView sectionList;
    private View browseView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView clockView;
    private TextView statusView;
    private TextView heroName;
    private android.widget.ImageView heroLogo;
    private TextView heroMeta;
    private TextView heroPlay;
    private View heroDot;
    private TextView heroLabel;
    private android.widget.ImageView playingLogo;
    private BrowseAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private long speedTickAt;
    private long speedTickBytes;

    /** 轻量测速：统计 http 传输字节，供缓冲速度显示 */
    private static final class SpeedMeter implements TransferListener {
        volatile long totalBytes;
        volatile long lastDataAt;

        @Override
        public void onTransferInitializing(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onTransferStart(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }

        @Override
        public void onBytesTransferred(DataSource source, DataSpec dataSpec, boolean isNetwork, int bytes) {
            totalBytes += bytes;
            lastDataAt = android.os.SystemClock.elapsedRealtime();
        }

        @Override
        public void onTransferEnd(DataSource source, DataSpec dataSpec, boolean isNetwork) {
        }
    }

    private final List<Channel> allChannels = new ArrayList<>();
    private int currentIndex = -1;   // 扁平频道序号
    private int lineIndex = 0;
    private int autoSwitch = 0;
    private int mode = MODE_TV;
    private boolean loading = false;
    private boolean listShown = true;

    /** 状态胶囊自动淡出 */
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setVisibility(View.GONE);
        }
    };

    /** 缓冲时刷新速度显示（http/hls 源有实时测速，RTSP 显示省略号） */
    private final Runnable bufferTicker = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            if (player.getPlaybackState() == Player.STATE_BUFFERING) {
                long now = android.os.SystemClock.elapsedRealtime();
                long dt = now - speedTickAt;
                long db = speedMeter.totalBytes - speedTickBytes;
                speedTickAt = now;
                speedTickBytes = speedMeter.totalBytes;
                boolean fresh = speedMeter.lastDataAt > 0 && now - speedMeter.lastDataAt < 2500;
                double mbps = dt > 0 ? db * 8.0 / (dt / 1000.0) / 1000000.0 : 0;
                String text = (fresh && mbps >= 0.05)
                        ? String.format(java.util.Locale.CHINA, "缓冲中 · %.1f Mb/s", mbps)
                        : "缓冲中…";
                statusView.setText(text);
                statusView.setVisibility(View.VISIBLE);
                main.postDelayed(this, 600);
            }
        }
    };

    /** 缓冲看门狗：14 秒还没出画面就当失败，自动换线路 */
    private final Runnable bufferingWatchdog = new Runnable() {
        @Override
        public void run() {
            if (player != null && player.getPlaybackState() == Player.STATE_BUFFERING) {
                onPlayFailed("缓冲超时");
            }
        }
    };

    /** 顶栏时钟 */
    private final Runnable tickClock = new Runnable() {
        @Override
        public void run() {
            clockView.setText(DateFormat.getTimeFormat(MainActivity.this)
                    .format(new Date()));
            main.postDelayed(this, 20000);
        }
    };

    /**
     * VPN 共存（Clash / Zion 等 tun 代理）：
     * 把整个进程绑定到物理网络（Wi-Fi/以太网，NET_CAPABILITY_NOT_VPN），
     * 播放流量直连运营商网络，不进 tun0 隧道。
     * 无 VPN 时绑定到当前默认物理网络同样安全。
     */
    private void bypassVpn() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    boolean ok = cm.bindProcessToNetwork(network);
                    android.util.Log.i("IPTV", "bindProcessToNetwork(physical) " + (ok ? "ok " + network : "FAILED(allowBypass denied)"));
                }
                @Override
                public void onLost(Network network) {
                    cm.bindProcessToNetwork(null);
                    android.util.Log.i("IPTV", "physical network lost, unbound");
                }
            });
        } catch (Exception e) {
            android.util.Log.w("IPTV", "bypassVpn failed", e);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        bypassVpn();
        LogoLoader.init(this);

        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        // 内置频道表升级：重置到内置源 + 全部频道，让新频道直接可见
        if (prefs.getInt(KEY_ASSET_VER, 0) < ASSET_VER) {
            prefs.edit().putInt(KEY_ASSET_VER, ASSET_VER)
                    .putInt(KEY_SOURCE, 0)
                    .putInt(KEY_MODE, MODE_ALL)
                    .apply();
        }
        mode = prefs.getInt(KEY_MODE, MODE_ALL);

        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        clockView = findViewById(R.id.clock);
        statusView = findViewById(R.id.status);
        sectionList = findViewById(R.id.sections);
        browseView = findViewById(R.id.browse);
        playerView = findViewById(R.id.player);
        heroName = findViewById(R.id.hero_name);
        heroLogo = findViewById(R.id.hero_logo);
        heroMeta = findViewById(R.id.hero_meta);
        heroPlay = findViewById(R.id.hero_play);
        heroDot = findViewById(R.id.hero_dot);
        heroLabel = findViewById(R.id.hero_label);
        playingLogo = findViewById(R.id.playing_logo);

        speedMeter = new SpeedMeter();
        DataSource.Factory ds = () -> {
            DataSource source = new TrustAllDataSource(UA, 12000, 18000);
            source.addTransferListener(speedMeter);
            return source;
        };
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
                .build();
        playerView.setPlayer(player);

        // RTSP 强制走 TCP 交织传输（UDP 在运营商 IPTV 网段收不到包）
        rtspFactory = new RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .setTimeoutMs(15000)
                .setUserAgent(UA);

        adapter = new BrowseAdapter((flatIndex, channel) -> play(flatIndex));
        sectionList.setLayoutManager(new LinearLayoutManager(this));
        sectionList.setAdapter(adapter);

        heroPlay.setOnClickListener(v -> hideList());

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                android.util.Log.w("IPTV", "onPlayerError: " + error);
                main.removeCallbacks(bufferingWatchdog);
                main.removeCallbacks(bufferTicker);
                onPlayFailed(brief(error.getMessage()));
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                android.util.Log.w("IPTV", "state=" + state);
                if (state == Player.STATE_BUFFERING) {
                    main.removeCallbacks(bufferingWatchdog);
                    main.postDelayed(bufferingWatchdog, 14000);
                    main.removeCallbacks(bufferTicker);
                    main.post(bufferTicker);
                } else if (state == Player.STATE_READY) {
                    main.removeCallbacks(bufferingWatchdog);
                    main.removeCallbacks(bufferTicker);
                    main.removeCallbacks(hideStatus);
                    main.postDelayed(hideStatus, 2600);
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                if (isPlaying) {
                    main.removeCallbacks(hideStatus);
                    main.postDelayed(hideStatus, 2600);
                }
            }
        });

        startUp();

        // 启动时静默检查更新（新版本只在状态胶囊轻提示，不打扰观看）
        checkUpdate(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        main.post(tickClock);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tickClock);
        if (player != null) player.pause();
    }

    // ===================== 浏览浮层 =====================

    private void showList() {
        listShown = true;
        browseView.setAlpha(0f);
        browseView.setVisibility(View.VISIBLE);
        browseView.animate().alpha(1f).setDuration(200).start();
        playingLogo.setVisibility(View.GONE);
        sectionList.requestFocus();
    }

    private void hideList() {
        listShown = false;
        browseView.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> browseView.setVisibility(View.GONE)).start();
        // 退回全屏：恢复左上角播放台标（有台标且源不自带水印时）
        Channel c = adapter.channelAt(currentIndex);
        if (c != null && c.logo != null && !c.logo.isEmpty() && !hasBurnedInLogo(c)) {
            playingLogo.setVisibility(View.VISIBLE);
        }
        playerView.requestFocus();
    }

    // ===================== 加载 =====================

    private void startUp() {
        // 默认直接用北京联通内置表（asset，秒开）；选过其他源则走网络加载
        int saved = prefs.getInt(KEY_SOURCE, 0);
        if (saved == 0) {
            String bj = readAsset("bjunicom.m3u");
            if (bj != null && bj.contains("#EXTINF")) {
                allChannels.clear();
                allChannels.addAll(M3uParser.parse(bj));
                render(false);
                showList();
                updateHero(null);
                // 默认台固定 CCTV-1；找不到再回退到第一个频道
                int idx = 0;
                for (int i = 0; i < allChannels.size(); i++) {
                    if (allChannels.get(i).name.startsWith("CCTV-1")) { idx = i; break; }
                }
                play(idx);
                return;
            }
        }
        loadSource(saved, true);
    }

    private String readAsset(String name) {
        try (InputStream in = getAssets().open(name)) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    private interface Progress {
        void onProgress(String text);
    }

    private String fetchFirstAvailable(String[] mirrors, Progress p) {
        for (int i = 0; i < mirrors.length; i++) {
            final int n = i + 1;
            if (p != null) main.post(() -> p.onProgress("正在连接线路 " + n + "/" + mirrors.length + "…"));
            try {
                String text = mirrors[i].startsWith("asset://")
                        ? readAsset(mirrors[i].substring(8))
                        : httpGet(mirrors[i]);
                if (text != null && text.contains("#EXTINF")) return text;
            } catch (Exception ignored) {
                // 换下一个镜像
            }
        }
        return null;
    }

    private String mergeExtras(String base, String[] extras, Progress p) {
        if (extras == null || extras.length == 0) return base;
        StringBuilder sb = new StringBuilder(base == null ? "" : base);
        for (String u : extras) {
            if (p != null) main.post(() -> p.onProgress("正在加载体育赛事频道…"));
            try {
                String t = httpGet(u);
                if (t != null && t.contains("#EXTINF")) sb.append('\n').append(t);
            } catch (Exception ignored) {
                // 附加源失败不影响主列表
            }
        }
        return sb.toString();
    }

    private void loadSource(int index, boolean autoPlay) {
        if (loading) return;
        if (index < 0 || index >= SOURCES.length) index = 0;
        loading = true;
        final Source src = SOURCES[index];
        setStatus("正在加载：" + src.name);
        titleView.setText("加载中…");
        final int fi = index;

        io.execute(() -> {
            String text = fetchFirstAvailable(src.mirrors, s -> main.post(() -> setStatus(s)));
            if (text == null) {
                loading = false;
                main.post(() -> setStatus("所有镜像都连不上，按菜单键换个源"));
                return;
            }
            text = mergeExtras(text, src.extras, s -> main.post(() -> setStatus(s)));
            writeFile(cacheFile(), text);
            prefs.edit().putInt(KEY_SOURCE, fi).apply();
            List<Channel> list = M3uParser.parse(text);
            loading = false;
            main.post(() -> {
                String playingKey = currentKey();
                allChannels.clear();
                allChannels.addAll(list);
                render(true);
                if (!restorePlaying(playingKey) || autoPlay) play(0);
                else setStatus(src.name + " · 加载完成");
            });
        });
    }

    private String currentKey() {
        Channel c = adapter.channelAt(currentIndex);
        return c == null ? null : Catalog.key(c.name);
    }

    private boolean restorePlaying(String key) {
        if (key == null) return false;
        for (int i = 0; i < adapter.size(); i++) {
            Channel c = adapter.channelAt(i);
            if (c != null && key.equals(Catalog.key(c.name))) {
                currentIndex = i;
                adapter.setPlaying(c);
                updateHero(c);
                return true;
            }
        }
        return false;
    }

    private String getTitleText() {
        String label = mode == MODE_SPORT ? " · 体育赛事"
                : (mode == MODE_ALL ? " · 全部" : "");
        return adapter.size() + " 个频道" + label;
    }

    /** 按当前显示模式填充卡片（分组交给 BrowseAdapter） */
    private void render(boolean keepPlayState) {
        List<Channel> shown = new ArrayList<>();
        for (Channel c : allChannels) {
            int cat = Catalog.category(c.group, c.name);
            boolean cctv5 = cat == Catalog.CAT_CCTV && c.name.replace(" ", "").startsWith("CCTV-5");
            if (cat == Catalog.CAT_OTHER && Catalog.isJunk(c.name)) continue;
            if (mode == MODE_TV) {
                if (!Catalog.isTvChannel(cat)) continue;
                if (cat == Catalog.CAT_SPORT && !Catalog.isSportWanted(c.name)) continue;
            } else if (mode == MODE_SPORT) {
                if (cat != Catalog.CAT_SPORT && !cctv5) continue;
            }
            shown.add(c);
        }
        adapter.submit(shown);
        subtitleView.setText(getTitleText());
        if (!keepPlayState) {
            currentIndex = -1;
            sectionList.scrollToPosition(0);
        }
    }

    // ===================== 播放 =====================

    private void play(int flatIndex) {
        Channel c = adapter.channelAt(flatIndex);
        if (c == null) return;
        currentIndex = flatIndex;
        lineIndex = 0;
        autoSwitch = 0;
        playCurrent();
        hideList();     // 选台后全屏
    }

    private void playCurrent() {
        Channel c = adapter.channelAt(currentIndex);
        if (c == null) return;
        if (c.urls.isEmpty()) {
            setStatus("这个台没有可用地址");
            return;
        }
        if (lineIndex >= c.urls.size()) lineIndex = 0;
        android.util.Log.i("IPTV", "PLAY[" + c.name + "] line " + (lineIndex + 1) + "/" + c.urls.size() + " " + c.urls.get(lineIndex));
        main.removeCallbacks(bufferingWatchdog);
        main.removeCallbacks(bufferTicker);
        try {
            MediaItem item = new MediaItem.Builder()
                    .setUri(c.urls.get(lineIndex))
                    .setMediaId(String.valueOf(currentIndex))
                    .build();
            if ("rtsp".equals(item.localConfiguration.uri.getScheme())) {
                player.setMediaSource(rtspFactory.createMediaSource(item));
            } else {
                player.setMediaItem(item);
            }
            player.prepare();
            player.play();
            adapter.setPlaying(c);
            updateHero(c);
            String meta = c.region + " · " + c.name;
            if (c.lineCount() > 1) {
                meta += " · 线路 " + (lineIndex + 1) + "/" + c.lineCount();
            }
            setStatus(meta);
            main.removeCallbacks(hideStatus);
            main.postDelayed(hideStatus, 2600);
        } catch (Exception e) {
            setStatus("无法播放：" + brief(e.getMessage()));
        }
    }

    /** Hero 横幅内容随当前频道更新 */
    private void updateHero(Channel c) {
        if (c == null) {
            heroLabel.setText("LIVE TV");
            heroDot.setVisibility(View.INVISIBLE);
            heroName.setText("准备就绪");
            heroMeta.setText("选择下方任意频道开始观看");
            heroPlay.setText("开始观看");
            heroLogo.setImageDrawable(null);
            heroLogo.setVisibility(View.GONE);
            return;
        }
        heroLabel.setText("正在播放");
        heroDot.setVisibility(View.VISIBLE);
        heroName.setText(c.name);
        if (c.logo != null && !c.logo.isEmpty()) {
            heroLogo.setVisibility(View.VISIBLE);
            LogoLoader.into(heroLogo, c.logo);
        } else {
            heroLogo.setImageDrawable(null);
            heroLogo.setVisibility(View.GONE);
        }
        String g = Catalog.groupLabel(c.group, c.name);
        String meta = (g.isEmpty() ? c.region : g) + " · 频道 " + (currentIndex + 1)
                + "/" + adapter.size();
        if (c.lineCount() > 1) meta += " · " + c.lineCount() + " 条线路";
        heroMeta.setText(meta);
        heroPlay.setText("继续播放");

        // 全屏播放时左上角常驻台标（源画面自带台标的频道不重复叠加）
        if (c.logo != null && !c.logo.isEmpty() && !hasBurnedInLogo(c)) {
            LogoLoader.into(playingLogo, c.logo);
            playingLogo.setVisibility(listShown ? View.GONE : View.VISIBLE);
        } else {
            playingLogo.setImageDrawable(null);
            playingLogo.setVisibility(View.GONE);
        }
    }

    /** 央视 / CGTN / 各省卫视等国内官方源画面自带台标水印，无需浮层 */
    private static boolean hasBurnedInLogo(Channel c) {
        String n = c.name == null ? "" : c.name.replace(" ", "");
        return n.startsWith("CCTV") || n.startsWith("CGTN")
                || n.contains("卫视") || n.contains("联播") || n.contains("新闻台");
    }

    private void onPlayFailed(String reason) {
        Channel c = adapter.channelAt(currentIndex);
        if (c == null) return;
        int maxAuto = Math.min(4, c.urls.size() - 1);
        if (autoSwitch < maxAuto) {
            autoSwitch++;
            lineIndex++;
            setStatus("第 " + lineIndex + " 条线路不通，自动切到 " + (lineIndex + 1) + "/" + c.lineCount() + "…");
            playCurrent();
            return;
        }
        main.removeCallbacks(bufferTicker);
        main.removeCallbacks(bufferingWatchdog);
        if (player != null) player.stop();   // 停掉卡住的缓冲圈
        setStatus("这个台暂时放不出来 · CH+ 换台 / 菜单换源");
    }

    // ===================== 菜单 =====================

    private void showMenu() {
        String modeLabel = mode == MODE_SPORT ? "只看体育（NBA / NFL）"
                : (mode == MODE_ALL ? "全部频道（按国家）" : "电视台直播");
        String[] items = {"检查更新…", "切换节目源…", "显示内容：" + modeLabel + " …", "重新加载当前源", "手动输入 m3u 链接…"};
        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            checkUpdate(true);
                            break;
                        case 1:
                            showSourceDialog();
                            break;
                        case 2:
                            showModeDialog();
                            break;
                        case 3:
                            loadSource(prefs.getInt(KEY_SOURCE, 0), false);
                            break;
                        default:
                            showUrlDialog();
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ===================== 在线更新 =====================

    /** 手动/静默检查 GitHub 最新 Release；manual=true 时每一步都有状态提示 */
    private void checkUpdate(boolean manual) {
        if (manual) setStatus("正在检查更新…");
        final String current = BuildConfig.VERSION_NAME;
        Updater.check(current, (release, error) -> {
            if ("latest".equals(error)) {
                if (manual) setStatus("已是最新版本 v" + current);
                return;
            }
            if (release == null) {
                if (manual) setStatus("检查更新失败：" + brief(error));
                return;
            }
            // 静默检查：只提示，不打断
            if (!manual) {
                setStatus("发现新版本 " + release.tag + " · 菜单键检查更新");
                main.removeCallbacks(hideStatus);
                main.postDelayed(hideStatus, 8000);
                return;
            }
            showUpdateDialog(release);
        });
    }

    private void showUpdateDialog(Updater.Release r) {
        String size = r.apkSize > 0 ? String.format(java.util.Locale.CHINA,
                "%.1f MB", r.apkSize / 1048576.0) : "未知大小";
        String notes = r.notes == null || r.notes.isEmpty() ? "" : r.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        new AlertDialog.Builder(this)
                .setTitle("发现新版本 " + r.tag + "（" + size + "）")
                .setMessage(notes)
                .setPositiveButton("下载并安装", (d, w) -> downloadUpdate(r))
                .setNegativeButton("以后再说", null)
                .show();
    }

    private void downloadUpdate(Updater.Release r) {
        setStatus("正在下载 " + r.tag + " …");
        io.execute(() -> {
            try {
                Updater.downloadAndInstall(this, r, (percent, done, total) -> {
                    String t = percent >= 0
                            ? String.format(java.util.Locale.CHINA, "正在下载 %d%%（%.1f/%.1f MB）",
                            percent, done / 1048576.0, total / 1048576.0)
                            : String.format(java.util.Locale.CHINA, "正在下载…（%.1f MB）", done / 1048576.0);
                    main.post(() -> setStatus(t));
                });
                main.post(() -> setStatus("下载完成，请在系统弹窗中确认安装"));
            } catch (Exception e) {
                android.util.Log.w("IPTV", "update failed", e);
                main.post(() -> setStatus("下载失败：" + brief(e.getMessage())));
            }
        });
    }

    private void showModeDialog() {
        String[] items = {"全部频道（先按国家 / 地区分组）", "只看体育（NBA / NFL / ESPN）", "只看电视台直播（央视 / 卫视 / 地方台）"};
        new AlertDialog.Builder(this)
                .setTitle("显示内容")
                .setItems(items, (d, which) -> {
                    mode = which;
                    prefs.edit().putInt(KEY_MODE, mode).apply();
                    String key = currentKey();
                    render(true);
                    if (!restorePlaying(key)) {
                        currentIndex = -1;
                        play(0);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSourceDialog() {
        String[] labels = new String[SOURCES.length];
        for (int i = 0; i < SOURCES.length; i++) labels[i] = (i + 1) + ". " + SOURCES[i].name;
        new AlertDialog.Builder(this)
                .setTitle("选择节目源")
                .setItems(labels, (d, which) -> loadSource(which, true))
                .setNegativeButton("取消", null)
                .show();
    }

    private void showUrlDialog() {
        EditText input = new EditText(this);
        input.setHint("https://example.com/live.m3u");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(48, 24, 48, 0);
        box.addView(input, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("输入播放列表地址")
                .setView(box)
                .setPositiveButton("加载", (d, w) -> loadCustom(input.getText().toString().trim()))
                .setNegativeButton("取消", null)
                .show();
    }

    private void loadCustom(String url) {
        if (url.isEmpty()) return;
        setStatus("正在加载自定义源…");
        io.execute(() -> {
            try {
                String text = httpGet(url);
                List<Channel> list = M3uParser.parse(text);
                if (list.isEmpty()) {
                    main.post(() -> setStatus("这个地址没解析到频道"));
                    return;
                }
                writeFile(cacheFile(), text);
                main.post(() -> {
                    allChannels.clear();
                    allChannels.addAll(list);
                    render(true);
                    play(0);
                    setStatus("自定义源 · " + list.size() + " 个频道");
                });
            } catch (Exception e) {
                main.post(() -> setStatus("加载失败：" + brief(e.getMessage())));
            }
        });
    }

    // ===================== 遥控器 =====================

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            showList();
            showMenu();
            return true;
        }
        // 全屏观看时，上下键直接换台（和电视机一样）
        if (keyCode == KeyEvent.KEYCODE_DPAD_UP && !listShown) {
            int prev = adapter.nextChannelIndex(currentIndex, -1);
            if (prev >= 0) play(prev);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !listShown) {
            int next = adapter.nextChannelIndex(currentIndex, 1);
            if (next >= 0) play(next);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            int next = adapter.nextChannelIndex(currentIndex, 1);
            if (next >= 0) play(next);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            int prev = adapter.nextChannelIndex(currentIndex, -1);
            if (prev >= 0) play(prev);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (!listShown) {
                showList();
                return true;
            }
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && !listShown) {
            showList();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_BACK && listShown) {
            hideList();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void setStatus(String text) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
    }

    private File cacheFile() {
        return new File(getCacheDir(), "playlist.m3u");
    }

    private String httpGet(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", UA);
        int code = conn.getResponseCode();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("HTTP " + code);
        }
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

    private void writeFile(File f, String text) {
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
        }
    }

    private static String brief(String s) {
        if (s == null) return "未知错误";
        return s.length() > 36 ? s.substring(0, 36) + "…" : s;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        io.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
