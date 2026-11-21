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
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
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

    private static final String PREF = "iptv_pref";
    private static final String KEY_SOURCE = "source_index";
    private static final String KEY_CHIP = "chip_filter_v2";
    /** 用户自定义分类顺序：不含「全部」，以逗号分隔持久化 */
    private static final String KEY_CHIP_ORDER = "chip_order_v1";
    private static final String KEY_ASSET_VER = "asset_ver";
    /** 内置频道表版本号：每次变更内置表后 +1，自动把用户切回内置源 + 全部分类 */
    private static final int ASSET_VER = 22;
    /** v1.21 实时简中字幕：旁路服务地址、开关、范围、时钟偏移 */
    private static final String KEY_SUBTITLE_ON = "subtitle_on";
    private static final String KEY_SUBTITLE_URL = "subtitle_url";
    private static final String KEY_SUBTITLE_OFFSET = "subtitle_offset_ms";
    private static final String KEY_SUBTITLE_FOREIGN_ONLY = "subtitle_foreign_only";
    /** 电视本机 loopback：由服务端自动执行 adb reverse 转发到 Mac，绕开 VPN 与防火墙 */
    private static final String DEFAULT_SUBTITLE_URL = "http://127.0.0.1:8899";
    /** v1.22 频道音量平衡：所有频道统一到同一长期响度 */
    private static final String KEY_VOLUME_NORMALIZE = "volume_normalize";
    private static final String KEY_VOLUME_TARGET = "volume_target";
    private static final String CHIP_ALL = "全部";
    /** 单个频道最多尝试 3 次（第一次 + 2 次重试/换线路），仍失败就自动切下一个频道 */
    private static final int MAX_PLAY_ATTEMPTS = 3;
    /** 连续自动跳台上限：整表都不通时不至于无限循环 */
    private static final int MAX_AUTO_HOPS = 12;
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private PlayerView playerView;
    private ExoPlayer player;
    /** 返回键触发彻底退出后，禁止任何残留异步回调再次拉起播放 */
    private boolean exiting = false;
    private RtspMediaSource.Factory rtspFactory;
    private SpeedMeter speedMeter;
    /** 物理网络句柄：普通频道直连；YouTube 直播需临时恢复默认（走代理）网络 */
    private ConnectivityManager connectivity;
    private Network physicalNetwork;
    private RecyclerView sectionList;
    private View browseView;
    private TextView titleView;
    private TextView subtitleView;
    private TextView clockView;
    private TextView statusView;
    private TextView nowText;
    private View nowDot;
    private View nowPill;
    private android.widget.LinearLayout chipsRow;
    private android.widget.HorizontalScrollView chipsScroll;
    /** 诊断：直接播放的线路（仅调试用，正常启动为 null） */
    private String debugUrl;
    /** 当前实际送入播放器的地址（字幕旁路服务要拉同一路流） */
    private String currentPlayUrl = "";
    /** 音频链上的实时响度归一化（默认开启，解决切台音量忽大忽小） */
    private final LoudnessNormalizer loudness = new LoudnessNormalizer();
    private SubtitleClient subtitleClient;
    private View subtitleBox;
    private TextView subtitleZh;
    private TextView subtitleSrc;
    private TextView subtitleTag;
    /** v1.23 换台信息浮层 */
    private View channelOsd;
    private TextView channelOsdName;
    private TextView channelOsdMeta;

    private BrowseAdapter adapter;
    /** 当前分类过滤（null = 全部） */
    private String chipFilter;
    /** 当前正在播放的频道（可能被过滤掉不在列表里） */
    private Channel playingChannel;

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
    /** 当前频道连续失败次数（每次开始/成功播放后清零） */
    private int failAttempts = 0;
    /** 连续自动跳台次数，播放成功后清零 */
    private int autoHopCount = 0;
    /** 每次播放尝试递增，用于丢弃上一次尝试迟到的解析/错误回调、避免重复计数 */
    private int playGeneration = 0;
    private int failedGeneration = -1;
    private boolean debugPlaying = false;
    /** 调试入口专用：带上 auto_hop=1 时，让调试播放也走自动跳台（实机验证用） */
    private boolean debugAutoHop = false;
    private String hopNotice;
    private boolean loading = false;
    private boolean listShown = true;
    /** 分类排序模式：焦点在 chips 上时 ←→ 直接移动分类，OK / 返回退出 */
    private boolean chipSortMode = false;

    /** 状态胶囊自动淡出 */
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setVisibility(View.GONE);
        }
    };

    /** 换台信息浮层自动淡出（v1.23） */
    private final Runnable hideChannelOsd = new Runnable() {
        @Override
        public void run() {
            if (channelOsd != null) {
                channelOsd.animate().alpha(0f).setDuration(300)
                        .withEndAction(() -> channelOsd.setVisibility(View.GONE)).start();
            }
        }
    };

    /** 实时字幕：按播放器时钟驱动字幕显示（120ms 一次，比每秒的时钟更细） */
    private final Runnable subtitleTicker = new Runnable() {
        @Override
        public void run() {
            if (subtitleClient != null && player != null) {
                try {
                    subtitleClient.tick(player.getCurrentPosition());
                } catch (Exception ignored) { }
            }
            main.postDelayed(this, 120);
        }
    };

    /** 缓冲时刷新速度显示（http/hls 源有实时测速，RTSP 显示省略号） */
    private final Runnable bufferTicker = new Runnable() {
        @Override
        public void run() {
            if (player == null) return;
            if (player.getPlaybackState() == Player.STATE_BUFFERING) {
                // 排序模式下状态胶囊归排序提示专用，不被缓冲网速刷掉
                if (chipSortMode) { main.postDelayed(this, 600); return; }
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
     * API 21 兼容：bindProcessToNetwork 从 API 23 才有，Android 5.x 老电视回退
     * setProcessDefaultNetwork；用 Throwable 兜住 NoSuchMethodError，绝不让网络绑定把应用带崩。
     */
    private boolean bindNetwork(Network network) {
        if (connectivity == null) return false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                return connectivity.bindProcessToNetwork(network);
            }
            ConnectivityManager.setProcessDefaultNetwork(network);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("IPTV", "bindNetwork failed", t);
            return false;
        }
    }

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
            connectivity = cm;
            NetworkRequest req = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                    .build();
            cm.registerNetworkCallback(req, new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    physicalNetwork = network;
                    if (subtitleClient != null) subtitleClient.setNetwork(network);
                    boolean ok = bindNetwork(network);
                    android.util.Log.i("IPTV", "bindNetwork(physical) " + (ok ? "ok " + network : "FAILED(allowBypass denied)"));
                }
                @Override
                public void onLost(Network network) {
                    bindNetwork(null);
                    android.util.Log.i("IPTV", "physical network lost, unbound");
                }
            });
        } catch (Exception e) {
            android.util.Log.w("IPTV", "bypassVpn failed", e);
        }
    }

    /** 按当前线路类型选择网络：普通频道直连运营商，YouTube / Twitch 直播走 VPN/代理。 */
    private void tuneNetworkFor(String url) {
        if (connectivity == null) return;
        if (YouTubeResolver.isYtLive(url) || TwitchResolver.isTwitchLive(url)) {
            bindNetwork(null);
        } else if (physicalNetwork != null) {
            bindNetwork(physicalNetwork);
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
        // 内置频道表升级：重置到内置源 + 全部分类，让新频道直接可见
        if (prefs.getInt(KEY_ASSET_VER, 0) < ASSET_VER) {
            prefs.edit().putInt(KEY_ASSET_VER, ASSET_VER)
                    .putInt(KEY_SOURCE, 0)
                    .putString(KEY_CHIP, "")
                    .apply();
        }
        chipFilter = emptyToNull(prefs.getString(KEY_CHIP, ""));

        titleView = findViewById(R.id.title);
        subtitleView = findViewById(R.id.subtitle);
        clockView = findViewById(R.id.clock);
        statusView = findViewById(R.id.status);
        sectionList = findViewById(R.id.sections);
        browseView = findViewById(R.id.browse);
        playerView = findViewById(R.id.player);
        nowText = findViewById(R.id.now_text);
        nowDot = findViewById(R.id.now_dot);
        nowPill = findViewById(R.id.now_playing);
        chipsRow = findViewById(R.id.chips);
        chipsScroll = findViewById(R.id.chips_scroll);
        subtitleBox = findViewById(R.id.subtitle_box);
        subtitleZh = findViewById(R.id.subtitle_zh);
        subtitleSrc = findViewById(R.id.subtitle_src);
        subtitleTag = findViewById(R.id.subtitle_tag);
        channelOsd = findViewById(R.id.channel_osd);
        channelOsdName = findViewById(R.id.channel_osd_name);
        channelOsdMeta = findViewById(R.id.channel_osd_meta);

        // v1.21 实时简中字幕：旁路服务推 SSE，客户端按播放时钟排队显示
        subtitleClient = new SubtitleClient();
        subtitleClient.setListener(new SubtitleClient.Listener() {
            @Override public void onCue(String src, String zh, String mode) {
                subtitleZh.setText(zh);
                subtitleSrc.setText(src);
                subtitleTag.setText("demo".equals(mode)
                        ? "AI 实时字幕 · 演示模式" : "AI 实时字幕 · 机器翻译");
                subtitleBox.setVisibility(View.VISIBLE);
            }
            @Override public void onClear() {
                subtitleBox.setVisibility(View.GONE);
            }
            @Override public void onState(String text, boolean error) {
                // v1.23：字幕服务不可达时只记日志，不再用 setStatus 抢占右下角状态胶囊
                // （这里原来每 15 秒重连失败就刷一次「字幕服务未连接」，把换台信息全盖掉了）
                if (error) android.util.Log.w("IPTV", "SUBTITLE_STATE " + text);
            }
        });
        // ADB 诊断入口：am start -n com.lau.iptv/.MainActivity --ez subtitle_on true
        if (getIntent().hasExtra("subtitle_on")) {
            prefs.edit().putBoolean(KEY_SUBTITLE_ON,
                    getIntent().getBooleanExtra("subtitle_on", false)).apply();
        }
        if (getIntent().hasExtra("subtitle_url")) {
            prefs.edit().putString(KEY_SUBTITLE_URL,
                    getIntent().getStringExtra("subtitle_url")).apply();
        }
        // ADB 诊断入口：--ez volume_normalize <bool> --ei volume_target <0|1|2>
        if (getIntent().hasExtra("volume_normalize")) {
            prefs.edit().putBoolean(KEY_VOLUME_NORMALIZE,
                    getIntent().getBooleanExtra("volume_normalize", true)).apply();
        }
        if (getIntent().hasExtra("volume_target")) {
            prefs.edit().putInt(KEY_VOLUME_TARGET,
                    getIntent().getIntExtra("volume_target", 0)).apply();
        }
        loudness.setEnabled(prefs.getBoolean(KEY_VOLUME_NORMALIZE, true));
        loudness.setTargetMode(prefs.getInt(KEY_VOLUME_TARGET, 0));
        applySubtitleSettings();
        if (physicalNetwork != null) subtitleClient.setNetwork(physicalNetwork);
        subtitleClient.start();

        speedMeter = new SpeedMeter();
        DataSource.Factory ds = () -> {
            DataSource source = new TrustAllDataSource(UA, 12000, 18000);
            source.addTransferListener(speedMeter);
            return source;
        };
        // v1.22：把响度归一化装进 AudioSink 的 processor 链，对所有源（HLS/RTSP/平台源）生效。
        // 强制 16bit PCM 输出 + 禁用 offload，保证 processor 一定拿到可处理的 PCM。
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                              boolean enableAudioTrackPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[]{loudness})
                        .setAudioOffloadSupportProvider(
                                (format, attrs) -> AudioOffloadSupport.DEFAULT_UNSUPPORTED)
                        .build();
            }
        };
        player = new ExoPlayer.Builder(this)
                .setRenderersFactory(renderers)
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

        // 诊断入口：adb ... --es play_url <URL> 直接播放指定线路，用于在电视上抽帧核对频道身份
        String dbg = getIntent().getStringExtra("play_url");
        debugAutoHop = getIntent().getBooleanExtra("auto_hop", false);
        android.util.Log.i("IPTV", "INTENT play_url=" + dbg + " auto_hop=" + debugAutoHop);
        if (dbg != null && !dbg.isEmpty()) {
            debugUrl = dbg;
            main.postDelayed(this::playDebugUrl, 2500);
        }

        // 点顶栏「正在播放」胶囊 → 回到全屏画面
        nowPill.setOnClickListener(v -> hideList());

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
                    main.postDelayed(bufferingWatchdog, bufferTimeoutMs);
                    main.removeCallbacks(bufferTicker);
                    main.post(bufferTicker);
                } else if (state == Player.STATE_READY) {
                    main.removeCallbacks(bufferingWatchdog);
                    main.removeCallbacks(bufferTicker);
                    main.removeCallbacks(hideStatus);
                    main.postDelayed(hideStatus, 2600);
                    // 出画面了：清掉失败与连续跳台计数，之后偶发断流重新按 3 次算
                    if (!debugPlaying) {
                        failAttempts = 0;
                        autoHopCount = 0;
                    }
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
        main.post(subtitleTicker);

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
        // 浏览时关掉播放器缓冲圈（避免透过面板显示），缓冲状态由右下胶囊提示
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER);
        // v1.23：进入选频道时优先聚焦当前正在播放的频道（找不到再回退第一张卡）
        sectionList.postDelayed(() -> {
            if (playingChannel != null && adapter.focusChannel(playingChannel)) return;
            if (adapter.getItemCount() > 0 && adapter.focusFirst()) return;
            if (chipsRow != null && chipsRow.getChildCount() > 0) {
                chipsRow.getChildAt(0).requestFocus();
            }
        }, 180);
    }

    private void hideList() {
        listShown = false;
        main.removeCallbacks(hideChannelOsd);
        if (channelOsd != null) channelOsd.setVisibility(View.GONE);
        browseView.animate().alpha(0f).setDuration(200)
                .withEndAction(() -> browseView.setVisibility(View.GONE)).start();
        playerView.requestFocus();
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
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
                buildChips();
                showList();
                updateNowPlaying(null);
                // 默认台固定 CCTV-1（在过滤后的列表里找，避免索引错位）；找不到再回退第一个
                int idx = 0;
                for (int i = 0; i < adapter.size(); i++) {
                    Channel c = adapter.channelAt(i);
                    if (c != null && "CCTV1".equals(Catalog.key(c.name))) {
                        idx = i;
                        break;
                    }
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
                buildChips();
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
                updateNowPlaying(c);
                return true;
            }
        }
        return false;
    }

    private String getTitleText() {
        String label = chipFilter == null ? " · 全部" : " · " + chipFilter;
        return adapter.size() + " 个频道" + label;
    }

    /** 当前分类过滤下是否显示该频道 */
    private boolean matchesChip(Channel c) {
        if (chipFilter == null) return true;
        int cat = Catalog.category(c.group, c.name);
        switch (chipFilter) {
            case "央视":
                return cat == Catalog.CAT_CCTV;
            case "卫视":
                return cat == Catalog.CAT_SAT;
            case "地方台":
                return cat == Catalog.CAT_LOCAL;
            case "港澳台":
                return cat == Catalog.CAT_HMT;
            case "体育":
                return cat == Catalog.CAT_SPORT
                        || (cat == Catalog.CAT_CCTV && c.name.replace(" ", "").startsWith("CCTV-5"));
            case "音乐":
                return isMusic(c);
            case "Twitch":
                return "Twitch".equals(c.platform);
            case "YouTube":
                return "YouTube".equals(c.platform);
            default:
                if (Catalog.isOverseasChip(chipFilter)) {
                    // 平台源按平台归类，不混进按国家划分的海外电视频道分类
                    if (c.platform != null && !c.platform.isEmpty()) return false;
                    return chipFilter.equals(Catalog.overseasChip(c.name, c.group, c.region));
                }
                return chipFilter.equals(c.region);
        }
    }

    /** 音乐类频道：音乐 / MTV / 嘻哈 / 说唱 / MV 台（v1.11 扩展） */
    private static boolean isMusic(Channel c) {
        String text = (c.name == null ? "" : c.name) + " " + (c.group == null ? "" : c.group);
        String up = text.toUpperCase();
        String u = up.replace(" ", "");
        if (u.contains("CCTV15") || u.contains("CCTV-15")) return true;
        return text.matches(".*(音乐|音樂|MTV|演唱会|演唱會|点歌|點歌|古典|KTV|金曲|音乐节|嘻哈|说唱|說唱|饶舌|饒舌).*")
                || u.contains("CHANNELV") || u.contains("VH1") || u.contains("MUSIC")
                || u.contains("HIPHOP") || u.contains("HIP-HOP") || u.contains("RAP")
                || u.contains("VEVO") || u.contains("REVOLT") || u.contains("TRACEURBAN")
                || u.contains("R&B") || u.contains("DELUXE")
                // BET 需按单词匹配：否则 "YouTube TalkSPORT" 里的 YOUTUBE+T 会误命中
                || up.matches(".*(?:^|[^A-Z])BET(?:$|[^A-Z]).*");
    }

    /** 海外国别分类 / 平台分类内部的二次分区：新闻 / 体育 / 音乐 / 综合 */
    private static String overseasTag(Channel c) {
        if (isMusic(c)) return "音乐";
        int cat = Catalog.category(c.group, c.name);
        String g = (c.group == null ? "" : c.group).toLowerCase();
        if (cat == Catalog.CAT_SPORT || g.contains("sport")) return "体育";
        String n = (c.name == null ? "" : c.name).toUpperCase();
        if ("海外新闻".equals(c.group) || n.matches(".*(NEWS|CNN|BBC|AL JAZEERA|EURONEWS|"
                + "BLOOMBERG|CNBC|SKY|FRANCE 24|TRT|ARIRANG|NHK WORLD|DW ENGLISH).*")) {
            return "新闻";
        }
        return "综合";
    }

    /** 按当前分类 chips 过滤填充卡片（分组交给 BrowseAdapter） */
    private void render(boolean keepPlayState) {        List<Channel> shown = new ArrayList<>();
        for (Channel c : allChannels) {
            int cat = Catalog.category(c.group, c.name);
            if (cat == Catalog.CAT_OTHER && Catalog.isJunk(c.name)) continue;
            if (!matchesChip(c)) continue;
            shown.add(c);
        }
        // 海外国别分类、平台分类：内部再按「新闻 / 体育 / 音乐 / 综合」二次分区
        boolean overseas = Catalog.isOverseasChip(chipFilter);
        boolean platform = isPlatformChip(chipFilter);
        for (Channel c : allChannels) c.sectionTag = null;
        if (overseas || platform) {
            for (Channel c : shown) c.sectionTag = overseasTag(c);
        }
        // 音乐 / 体育这类内容大分类：不再按国家细分，直接平铺
        boolean flatSections = "体育".equals(chipFilter) || "音乐".equals(chipFilter);
        adapter.submit(shown, flatSections);
        subtitleView.setText(getTitleText());
        if (!keepPlayState) {
            currentIndex = -1;
            sectionList.scrollToPosition(0);
        }
    }

    // ===================== 分类 chips（YouTube TV 风格） =====================

    /** v1.20：国内大分类 + 港澳台 + 内容分类 + 平台分类 + 海外国别分类；「全部」固定首位 */
    private static final String[] BASE_CHIPS = {"全部", "央视", "卫视", "地方台",
            "香港", "台湾", "澳门", "体育", "音乐", "Twitch", "YouTube",
            "美国", "日本", "英国", "德国", "韩国", "法国", "俄罗斯", "国际"};

    /** 平台分类：按 URL scheme 独立成类，内部仍按音乐 / 体育 / 新闻二次分区 */
    private static boolean isPlatformChip(String chip) {
        return "Twitch".equals(chip) || "YouTube".equals(chip);
    }

    /** 依据当前频道表 + 用户自定义顺序重建 chips 行 */
    private void buildChips() {
        if (chipsRow == null) return;
        chipsRow.removeAllViews();
        List<String> labels = orderedChips();

        // 旧版本遗留的 chip（如「港澳台」或已移除的国家分类）→ 回到「全部」
        if (chipFilter != null && !labels.contains(chipFilter)) {
            chipFilter = null;
            prefs.edit().putString(KEY_CHIP, "").apply();
        }

        for (String label : labels) chipsRow.addView(makeChip(label));
        highlightChips();
        android.util.Log.i("IPTV", "CHIP_ORDER " + labels.toString());
    }

    // ===================== 自定义分类顺序（v1.15） =====================

    /** 「全部」永远固定在首位，其余分类按用户保存的顺序排列 */
    private List<String> orderedChips() {
        List<String> out = new ArrayList<>();
        out.add(CHIP_ALL);
        out.addAll(customChipOrder());
        return out;
    }

    /** 读持久化顺序：过滤掉已废弃分类、去重，并补齐后续版本新加的分类 */
    private List<String> customChipOrder() {
        List<String> out = new ArrayList<>();
        String saved = prefs == null ? "" : prefs.getString(KEY_CHIP_ORDER, "");
        if (saved != null && !saved.isEmpty()) {
            for (String s : saved.split(",")) {
                String t = s.trim();
                if (!t.isEmpty() && !CHIP_ALL.equals(t) && isBaseChip(t) && !out.contains(t)) {
                    out.add(t);
                }
            }
        }
        for (int i = 1; i < BASE_CHIPS.length; i++) {
            if (!out.contains(BASE_CHIPS[i])) out.add(BASE_CHIPS[i]);
        }
        return out;
    }

    private static boolean isBaseChip(String s) {
        for (String b : BASE_CHIPS) if (b.equals(s)) return true;
        return false;
    }

    private void saveChipOrder(List<String> order) {
        StringBuilder sb = new StringBuilder();
        for (String s : order) {
            if (sb.length() > 0) sb.append(',');
            sb.append(s);
        }
        prefs.edit().putString(KEY_CHIP_ORDER, sb.toString()).apply();
    }

    private String chipLabelAt(int i) {
        if (chipsRow == null || i < 0 || i >= chipsRow.getChildCount()) return "";
        View v = chipsRow.getChildAt(i);
        return v instanceof TextView ? ((TextView) v).getText().toString() : "";
    }

    /** 菜单进入排序模式：焦点落到当前分类，←→ 即时移动并保存 */
    private void enterChipSortMode() {
        showList();
        chipSortMode = true;
        int focus = 1;
        if (chipFilter != null) {
            for (int i = 1; i < chipsRow.getChildCount(); i++) {
                if (chipFilter.contentEquals(chipLabelAt(i))) { focus = i; break; }
            }
        }
        final int f = focus;
        setSortStatus("排序模式 · ← → 移动分类 · OK / 返回 保存退出");
        // showList() 会在 180ms 后把焦点放到卡片上，这里稍后夺回到 chips
        main.postDelayed(() -> {
            if (!chipSortMode || chipsRow == null) return;
            View v = chipsRow.getChildAt(f);
            if (v != null) v.requestFocus();
        }, 320);
    }

    private void exitChipSortMode() {
        chipSortMode = false;
        setStatus("分类顺序已保存");
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, 2600);
    }

    /** 交换相邻两个分类（下标为 chipsRow 的位置，0 是固定的「全部」） */
    private void moveChip(int from, int to) {
        List<String> order = customChipOrder();
        int a = from - 1, b = to - 1;
        if (a < 0 || b < 0 || a >= order.size() || b >= order.size()) return;
        Collections.swap(order, a, b);
        saveChipOrder(order);
        buildChips();   // 顺序即时生效，重建后焦点要还回被移动的 chip
        final String moved = order.get(b);
        if (chipsRow != null && to < chipsRow.getChildCount()) {
            chipsRow.getChildAt(to).requestFocus();   // 立即夺回，支持连续快速按 ←→
        }
        main.postDelayed(() -> {
            if (!chipSortMode || chipsRow == null) return;
            View v = chipsRow.getChildAt(to);
            if (v != null) v.requestFocus();
        }, 60);
        setSortStatus("「" + moved + "」移到第 " + to + " 位 · 已保存 · OK 退出");
    }

    private TextView makeChip(final String label) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(13);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        t.setGravity(android.view.Gravity.CENTER);
        t.setPadding(dp(18), dp(9), dp(18), dp(9));
        t.setBackgroundResource(R.drawable.chip_bg);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(10));
        t.setLayoutParams(lp);
        t.setOnClickListener(v -> selectChip(label));
        t.setNextFocusDownId(R.id.sections);
        t.setOnFocusChangeListener((v, has) -> {
            t.setTextColor((has || t.isSelected()) ? 0xFF111111 : 0xFFF2F2F7);
            t.animate().scaleX(has ? 1.12f : 1f).scaleY(has ? 1.12f : 1f)
                    .setDuration(130).start();
            t.setTranslationZ(has ? 18f : 0f);
            if (has) chipsScroll.smoothScrollTo(Math.max(0, t.getLeft() - dp(160)), 0);
        });
        return t;
    }

    private void selectChip(String label) {
        chipFilter = CHIP_ALL.equals(label) ? null : label;
        prefs.edit().putString(KEY_CHIP, chipFilter == null ? "" : chipFilter).apply();
        highlightChips();
        String key = playingChannel == null ? null : Catalog.key(playingChannel.name);
        render(true);
        if (!restorePlaying(key)) {
            currentIndex = -1;
            updateNowPlaying(playingChannel);
        }
        sectionList.scrollToPosition(0);
        // 列表重建后焦点会掉，重新落到第一张卡片
        sectionList.postDelayed(() -> {
            if (!adapter.focusFirst() && chipsRow != null && chipsRow.getChildCount() > 0) {
                chipsRow.getChildAt(0).requestFocus();
            }
        }, 200);
    }

    private void highlightChips() {
        if (chipsRow == null) return;
        for (int i = 0; i < chipsRow.getChildCount(); i++) {
            TextView t = (TextView) chipsRow.getChildAt(i);
            boolean sel = chipFilter == null
                    ? CHIP_ALL.contentEquals(t.getText())
                    : chipFilter.contentEquals(t.getText());
            t.setSelected(sel);
            t.setTextColor((sel || t.isFocused()) ? 0xFF111111 : 0xFFF2F2F7);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    // ===================== 播放 =====================

    private void play(int flatIndex) {
        // 手动选台 / 启动：连续自动跳台计数重新开始
        autoHopCount = 0;
        startChannel(flatIndex);
    }

    /** 开始播放指定频道；自动跳台也走这里，但不会重置 autoHopCount */
    private void startChannel(int flatIndex) {
        Channel c = adapter.channelAt(flatIndex);
        if (c == null) return;
        currentIndex = flatIndex;
        lineIndex = 0;
        autoSwitch = 0;
        failAttempts = 0;
        debugPlaying = false;
        playingChannel = c;
        playCurrent();
        if (listShown) hideList();     // 选台后全屏
    }

    /** 海外直播解析结果回调 */
    private interface LiveResult {
        void ok(String hls);
        void fail(String reason);
    }

    /**
     * 解析 YouTube / Twitch 直播：先走默认网络（VPN/代理），失败后自动切回物理网络
     * 直连重试一次。解析成功后进程保持在该网络上，后续播放走同一条链路。
     */
    private void resolveLive(String raw, LiveResult result) {
        final boolean twitch = TwitchResolver.isTwitchLive(raw);
        if (connectivity != null) {
            bindNetwork(null);
        }
        final boolean[] retried = {false};
        LiveResult attempt = new LiveResult() {
            @Override public void ok(String hls) { result.ok(hls); }
            @Override public void fail(String reason) {
                if (!retried[0] && connectivity != null && physicalNetwork != null) {
                    retried[0] = true;
                    android.util.Log.i("IPTV", "海外源解析失败，改走物理网络重试：" + reason);
                    bindNetwork(physicalNetwork);
                    runResolver(raw, twitch, this);
                } else {
                    result.fail(reason);
                }
            }
        };
        runResolver(raw, twitch, attempt);
    }

    private void runResolver(String raw, boolean twitch, final LiveResult r) {
        if (twitch) {
            TwitchResolver.resolve(raw, new TwitchResolver.Callback() {
                @Override public void onResolved(String hls) { r.ok(hls); }
                @Override public void onError(String reason) { r.fail(reason); }
            });
        } else {
            YouTubeResolver.resolve(raw, new YouTubeResolver.Callback() {
                @Override public void onResolved(String hls) { r.ok(hls); }
                @Override public void onError(String reason) { r.fail(reason); }
            });
        }
    }

    /** 诊断用：直接播放指定 URL（配合 adb 抽帧核对频道） */
    private void playDebugUrl() {
        if (debugUrl == null) return;
        final String raw = debugUrl;
        final int gen = ++playGeneration;
        debugPlaying = true;
        android.util.Log.i("IPTV", "DEBUG_PLAY " + raw + " auto_hop=" + debugAutoHop);
        tuneNetworkFor(raw);
        hideList();
        // 调试入口也走完整的运行时解析链路，便于在电视上验证 YouTube / Twitch 源
        if (TwitchResolver.isTwitchLive(raw) || YouTubeResolver.isYtLive(raw)) {
            setStatus(TwitchResolver.isTwitchLive(raw)
                    ? "正在解析 Twitch 直播源…" : "正在解析 YouTube 直播源…");
            resolveLive(raw, new LiveResult() {
                @Override public void ok(String hls) { if (gen == playGeneration) debugPlay(hls); }
                @Override public void fail(String reason) {
                    android.util.Log.w("IPTV", "海外源解析最终失败：" + reason);
                    if (gen != playGeneration) return;
                    if (debugAutoHop) onPlayFailed("海外源解析失败：" + brief(reason));
                    else setStatus("海外源不可用：" + brief(reason));
                }
            });
            return;
        }
        debugPlay(raw);
    }

    private void debugPlay(String url) {
        debugPlaying = true;
        loudness.onChannelChanged();
        bufferTimeoutMs = bufferTimeoutFor(url);
        try {
            main.removeCallbacks(bufferingWatchdog);
            main.removeCallbacks(bufferTicker);
            MediaItem.Builder b = new MediaItem.Builder().setUri(url).setMediaId("debug");
            // 短链（jmp2.uk/stvp-*）会 302 到 pluto.tv 的 .m3u8，但 URI 本身无后缀；
            // 不显式声明 HLS，media3 会当成普通文件去 sniff，触发 TrustAllDataSource 竞态失败
            if (isHlsUrl(url)) b.setMimeType(MimeTypes.APPLICATION_M3U8);
            MediaItem item = b.build();
            if ("rtsp".equals(item.localConfiguration.uri.getScheme())) {
                player.setMediaSource(rtspFactory.createMediaSource(item));
            } else {
                player.setMediaItem(item);
            }
            player.prepare();
            player.play();
            setStatus("调试播放");
        } catch (Exception e) {
            setStatus("调试播放失败：" + brief(e.getMessage()));
        }
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        String u = intent.getStringExtra("play_url");
        if (u != null && !u.isEmpty()) {
            debugUrl = u;
            debugAutoHop = intent.getBooleanExtra("auto_hop", false);
            playDebugUrl();
        }
    }

    private void playCurrent() {
        Channel c = adapter.channelAt(currentIndex);
        if (c == null) return;
        final int gen = ++playGeneration;   // 新一次尝试，旧的异步回调一律作废
        // 调试 auto_hop：重试也继续用指定的坏源，便于实机验证「3 次失败 → 自动跳台」
        if (debugPlaying && debugAutoHop && debugUrl != null) {
            debugPlay(debugUrl);
            return;
        }
        if (c.urls.isEmpty()) {
            onPlayFailed("没有可用地址");
            return;
        }
        if (lineIndex >= c.urls.size()) lineIndex = 0;
        final String raw = c.urls.get(lineIndex);
        tuneNetworkFor(raw);
        // 海外 24/7 直播：清单带时效签名，播放前实时解析（v1.13）
        if (TwitchResolver.isTwitchLive(raw) || YouTubeResolver.isYtLive(raw)) {
            setStatus(TwitchResolver.isTwitchLive(raw)
                    ? "正在解析 Twitch 直播源…" : "正在解析 YouTube 直播源…");
            resolveLive(raw, new LiveResult() {
                @Override public void ok(String hls) {
                    if (gen == playGeneration) startPlayback(c, raw, hls);
                }
                @Override public void fail(String reason) {
                    if (gen == playGeneration) onPlayFailed("海外源解析失败：" + brief(reason));
                }
            });
            return;
        }
        startPlayback(c, raw, raw);
    }

    /**
     * 是否为 HLS 源。media3 默认只靠 URI 后缀推断容器类型，
     * 而 jmp2.uk/stvp-* 这类短链 302 之后才是 .m3u8，必须显式声明。
     * 其余无后缀源（.mpd / mpegts / 跳转页）保持自动判定，避免误判。
     */
    /** 缓冲看门狗时长：海外 HLS 需要先 302 + 签名 + 拉分片，给足 22 秒；国内源保持 14 秒快速跳台 */
    private int bufferTimeoutMs = 14000;

    private static boolean isSlowOverseas(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("jmp2.uk") || u.contains("pluto.tv") || u.contains("cloudfront.net")
                || u.contains("mjh.nz") || u.contains("akamaized.net");
    }

    /** 平台源（YouTube / Twitch）解析后还要多跳一层签名与分片，给足 30 秒 */
    private static boolean isPlatformHls(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        return u.contains("/api/manifest/hls_") || u.contains("usher.ttvnw.net")
                || u.contains("googlevideo.com") || u.contains(".c.youtube.com");
    }

    private static int bufferTimeoutFor(String url) {
        if (isPlatformHls(url)) return 30000;
        return isSlowOverseas(url) ? 22000 : 14000;
    }

    private static boolean isHlsUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase();
        String path = u.split("[?#]")[0];
        if (path.endsWith(".m3u8")) return true;
        if (u.contains("jmp2.uk/") && (u.contains("/stvp-") || u.contains("/plu-"))) return true;
        // Invidious / YouTube 原生直播清单路径没有 .m3u8 后缀，需显式声明 HLS
        if (u.contains("/api/manifest/hls_")) return true;
        return u.contains("pluto.tv") && u.contains("/hls/");
    }

    private void startPlayback(Channel c, String loggedUrl, String playUrl) {
        android.util.Log.i("IPTV", "PLAY[" + c.name + "] line " + (lineIndex + 1) + "/" + c.urls.size() + " " + loggedUrl);
        bufferTimeoutMs = bufferTimeoutFor(playUrl);
        main.removeCallbacks(bufferingWatchdog);
        main.removeCallbacks(bufferTicker);
        try {
            MediaItem.Builder b = new MediaItem.Builder()
                    .setUri(playUrl)
                    .setMediaId(String.valueOf(currentIndex));
            if (isHlsUrl(playUrl)) b.setMimeType(MimeTypes.APPLICATION_M3U8);
            MediaItem item = b.build();
            if ("rtsp".equals(item.localConfiguration.uri.getScheme())) {
                player.setMediaSource(rtspFactory.createMediaSource(item));
            } else {
                player.setMediaItem(item);
            }
            player.prepare();
            player.play();
            playingChannel = c;
            currentPlayUrl = playUrl;
            // 切台：重置响度测量但保留当前增益，1.6 秒内平滑收敛到新频道
            loudness.onChannelChanged();
            adapter.setPlaying(c);
            updateNowPlaying(c);
            // 切台：清空字幕队列并让旁路服务改拉新频道的音频
            if (subtitleClient != null && prefs.getBoolean(KEY_SUBTITLE_ON, false)) {
                subtitleClient.onChannel(c.name, playUrl, subtitleActiveFor(c),
                        player.getCurrentPosition());
            }
            boolean hop = hopNotice != null;
            String meta = (hop ? hopNotice + " · " : "") + c.region + " · " + c.name;
            hopNotice = null;
            if (c.lineCount() > 1) {
                meta += " · 线路 " + (lineIndex + 1) + "/" + c.lineCount();
            }
            setStatus(meta);
            main.removeCallbacks(hideStatus);
            main.postDelayed(hideStatus, hop ? 5200 : 2600);
            // v1.23：换台信息浮层（独立于状态胶囊，不被缓冲/字幕错误刷掉）
            showChannelOsd(c, meta);
        } catch (Exception e) {
            android.util.Log.w("IPTV", "startPlayback failed", e);
            onPlayFailed(brief(e.getMessage()));
        }
    }

    /** 顶栏「正在播放」胶囊随当前频道更新 */
    private void updateNowPlaying(Channel c) {
        if (nowText == null) return;
        if (c == null) {
            nowDot.setVisibility(View.INVISIBLE);
            nowText.setText("准备就绪");
            nowText.setTextColor(0xFFB0B0BA);
            nowPill.setAlpha(0.9f);
        } else {
            nowDot.setVisibility(View.VISIBLE);
            String t = c.name;
            if (c.lineCount() > 1) t += " · " + (lineIndex + 1) + "/" + c.lineCount();
            nowText.setText(t);
            nowText.setTextColor(0xFFFFFFFF);
            nowPill.setAlpha(1f);
        }
    }

    /**
     * 播放失败统一入口：同一次尝试只算一次（避免 onPlayerError 与缓冲看门狗重复计数）。
     * 同一频道累计尝试 3 次（有备线时顺带换线路，没备线就重试当前线路），
     * 仍放不出来就自动切到下一个频道。
     */
    private void onPlayFailed(String reason) {
        if (failedGeneration == playGeneration) return;
        failedGeneration = playGeneration;
        main.removeCallbacks(bufferTicker);
        main.removeCallbacks(bufferingWatchdog);
        android.util.Log.w("IPTV", "PLAY_FAILED gen=" + playGeneration
                + " channel=" + currentIndex + " reason=" + reason);
        // 重试/跳台时直接 setMediaItem 覆盖即可，不调用 stop()，避免它的异步状态回调被算成新一次失败

        // 诊断入口：默认停在失败状态，方便抽帧核对；带 auto_hop=1 时可验证自动跳台
        if (debugPlaying && !debugAutoHop) {
            if (player != null) player.stop();
            setStatus("调试播放失败：" + brief(reason));
            return;
        }
        Channel failed = adapter.channelAt(currentIndex);
        if (failed == null) return;
        failAttempts++;
        if (failAttempts < MAX_PLAY_ATTEMPTS) {
            boolean hasNextLine = lineIndex < failed.urls.size() - 1;
            if (hasNextLine) {
                lineIndex++;
                autoSwitch++;
            }
            setStatus("播放失败 · 第 " + (failAttempts + 1) + "/" + MAX_PLAY_ATTEMPTS + " 次尝试"
                    + (hasNextLine
                    ? " · 换线路 " + (lineIndex + 1) + "/" + failed.lineCount()
                    : " · 重试当前线路"));
            playCurrent();
            return;
        }
        hopToNextChannel(failed);
    }

    /** 3 次都不通：自动跳下一个频道；到列表末尾回到开头，连续跳台有上限防止整表断网时空转 */
    private void hopToNextChannel(Channel failed) {
        int next = adapter.nextChannelIndex(currentIndex, 1);
        if (next < 0 && adapter.size() > 0) next = 0;
        Channel c = adapter.channelAt(next);
        if (c == null || autoHopCount >= MAX_AUTO_HOPS) {
            if (player != null) player.stop();   // 停掉卡住的缓冲圈
            setStatus("连续多个频道放不出来 · 请检查网络 / CH+ 换台 / 菜单换源");
            android.util.Log.w("IPTV", "AUTO_HOP stop, hops=" + autoHopCount);
            return;
        }
        autoHopCount++;
        hopNotice = "「" + failed.name + "」试了 3 次不通 · 自动切台 "
                + autoHopCount + "/" + MAX_AUTO_HOPS;
        android.util.Log.i("IPTV", "AUTO_HOP " + currentIndex + " -> " + next + " " + c.name
                + " (" + autoHopCount + "/" + MAX_AUTO_HOPS + ")");
        startChannel(next);
    }

    // ===================== 菜单 =====================

    private void showMenu() {
        boolean subOn = prefs.getBoolean(KEY_SUBTITLE_ON, false);
        boolean foreignOnly = prefs.getBoolean(KEY_SUBTITLE_FOREIGN_ONLY, false);
        boolean volOn = prefs.getBoolean(KEY_VOLUME_NORMALIZE, true);
        int volTarget = prefs.getInt(KEY_VOLUME_TARGET, 0);
        if (volTarget < 0 || volTarget >= LoudnessNormalizer.TARGET_LABELS.length) volTarget = 0;
        String[] items = {"检查更新…", "切换节目源…", "重新加载当前源", "手动输入 m3u 链接…",
                "自定义分类顺序…", "恢复默认分类顺序",
                "实时简中字幕（" + (subOn ? "已开启 · 点此关闭" : "已关闭 · 点此开启") + "）",
                "字幕范围（" + (foreignOnly ? "仅外语频道" : "全部频道") + "）",
                "字幕服务地址…", "字幕时间偏移…",
                "频道音量平衡（" + (volOn ? "已开启 · 点此关闭" : "已关闭 · 点此开启") + "）",
                "音量目标（" + LoudnessNormalizer.TARGET_LABELS[volTarget] + "）"};
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
                            loadSource(prefs.getInt(KEY_SOURCE, 0), false);
                            break;
                        case 3:
                            showUrlDialog();
                            break;
                        case 4:
                            enterChipSortMode();
                            break;
                        case 5:
                            prefs.edit().remove(KEY_CHIP_ORDER).apply();
                            buildChips();
                            setStatus("分类顺序已恢复默认");
                            main.removeCallbacks(hideStatus);
                            main.postDelayed(hideStatus, 2600);
                            break;
                        case 6:
                            toggleSubtitles();
                            break;
                        case 7:
                            prefs.edit().putBoolean(KEY_SUBTITLE_FOREIGN_ONLY, !foreignOnly).apply();
                            rebindSubtitleChannel();
                            setStatus(!foreignOnly ? "字幕范围：仅外语频道" : "字幕范围：全部频道");
                            main.removeCallbacks(hideStatus);
                            main.postDelayed(hideStatus, 2600);
                            break;
                        case 8:
                            showSubtitleUrlDialog();
                            break;
                        case 9:
                            showSubtitleOffsetDialog();
                            break;
                        case 10:
                            toggleVolumeNormalize();
                            break;
                        case 11:
                            showVolumeTargetDialog();
                            break;
                        default:
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    // ===================== 频道音量平衡（v1.22） =====================

    private void toggleVolumeNormalize() {
        boolean on = prefs.getBoolean(KEY_VOLUME_NORMALIZE, true);
        prefs.edit().putBoolean(KEY_VOLUME_NORMALIZE, !on).apply();
        loudness.setEnabled(!on);
        setStatus(on ? "频道音量平衡已关闭" : "频道音量平衡已开启");
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, 2600);
    }

    private void showVolumeTargetDialog() {
        int cur = prefs.getInt(KEY_VOLUME_TARGET, 0);
        if (cur < 0 || cur >= LoudnessNormalizer.TARGET_LABELS.length) cur = 0;
        String[] labels = {"标准（推荐）", "柔和（整体更轻 · 保留更多动态）", "响亮（整体更响）"};
        new AlertDialog.Builder(this)
                .setTitle("音量平衡目标")
                .setMessage("把所有频道的长期响度统一到同一水平；切台时增益平滑过渡，不会瞬间突变。")
                .setSingleChoiceItems(labels, cur, (d, w) -> {
                    prefs.edit().putInt(KEY_VOLUME_TARGET, w).apply();
                    loudness.setTargetMode(w);
                    d.dismiss();
                    setStatus("音量平衡目标：" + LoudnessNormalizer.TARGET_LABELS[w]);
                    main.removeCallbacks(hideStatus);
                    main.postDelayed(hideStatus, 2600);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ===================== 实时简中字幕（v1.21） =====================

    private void applySubtitleSettings() {
        if (subtitleClient == null) return;
        subtitleClient.configure(
                prefs.getString(KEY_SUBTITLE_URL, DEFAULT_SUBTITLE_URL),
                prefs.getInt(KEY_SUBTITLE_OFFSET, -1500),
                prefs.getBoolean(KEY_SUBTITLE_ON, false));
        if (!prefs.getBoolean(KEY_SUBTITLE_ON, false) && subtitleBox != null) {
            subtitleBox.setVisibility(View.GONE);
        }
    }

    /** 仅外语频道：平台源 + 明确的海外国别/台标；音乐台（BGM 无可译语音）排除 */
    private boolean subtitleActiveFor(Channel c) {
        if (c == null || !prefs.getBoolean(KEY_SUBTITLE_ON, false)) return false;
        if (!prefs.getBoolean(KEY_SUBTITLE_FOREIGN_ONLY, false)) return true;
        return isForeignChannel(c) && !isMusic(c);
    }

    private static boolean isForeignChannel(Channel c) {
        if (c.platform != null && !c.platform.isEmpty()) return true;
        String s = (c.region == null ? "" : c.region) + " "
                + (c.group == null ? "" : c.group) + " " + (c.name == null ? "" : c.name);
        return s.matches(".*(美国|日本|英国|德国|韩国|法国|俄罗斯|国际|海外|US|UK|JP|KR|NHK|BBC|CNN|FOX|ESPN|BET|MTV|Twitch|YouTube).*");
    }

    /** 设置变化后重新把当前频道绑定给字幕服务（含关闭时停掉 ffmpeg） */
    private void rebindSubtitleChannel() {
        if (subtitleClient == null || playingChannel == null) return;
        if (!prefs.getBoolean(KEY_SUBTITLE_ON, false)) {
            subtitleClient.onChannel(playingChannel.name, currentPlayUrl, false,
                    player != null ? player.getCurrentPosition() : 0);
            return;
        }
        subtitleClient.onChannel(playingChannel.name, currentPlayUrl,
                subtitleActiveFor(playingChannel),
                player != null ? player.getCurrentPosition() : 0);
    }

    private void toggleSubtitles() {
        boolean on = prefs.getBoolean(KEY_SUBTITLE_ON, false);
        prefs.edit().putBoolean(KEY_SUBTITLE_ON, !on).apply();
        applySubtitleSettings();
        rebindSubtitleChannel();
        setStatus(on ? "实时简中字幕已关闭" : "实时简中字幕已开启");
        main.removeCallbacks(hideStatus);
        main.postDelayed(hideStatus, 2600);
    }

    private void showSubtitleUrlDialog() {
        EditText et = new EditText(this);
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        et.setText(prefs.getString(KEY_SUBTITLE_URL, DEFAULT_SUBTITLE_URL));
        et.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("字幕服务地址")
                .setMessage("默认 http://127.0.0.1:8899（服务端自动 adb reverse）；服务在 NAS 上时填局域网地址")
                .setView(et)
                .setPositiveButton("保存", (d, w) -> {
                    String v = et.getText().toString().trim();
                    if (!v.startsWith("http://") && !v.startsWith("https://")) {
                        setStatus("地址需以 http:// 开头");
                        main.removeCallbacks(hideStatus);
                        main.postDelayed(hideStatus, 2600);
                        return;
                    }
                    prefs.edit().putString(KEY_SUBTITLE_URL, v).apply();
                    applySubtitleSettings();
                    rebindSubtitleChannel();
                    setStatus("字幕服务地址已保存：" + v);
                    main.removeCallbacks(hideStatus);
                    main.postDelayed(hideStatus, 2600);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void showSubtitleOffsetDialog() {
        final int[] values = {-4000, -3000, -2000, -1500, -1000, -500, 0, 500, 1000, 2000, 3000};
        String[] labels = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            int v = values[i];
            labels[i] = v == 0 ? "0 ms（不偏移）"
                    : (v > 0 ? "+" : "") + v + " ms（字幕" + (v > 0 ? "延后" : "提前") + "）";
        }
        new AlertDialog.Builder(this)
                .setTitle("字幕时间偏移")
                .setMessage("字幕比画面早 → 调负值；字幕比画面晚 → 调正值。直播缓冲各家不同，需要现场微调。")
                .setItems(labels, (d, w) -> {
                    prefs.edit().putInt(KEY_SUBTITLE_OFFSET, values[w]).apply();
                    applySubtitleSettings();
                    setStatus("字幕偏移已设为 " + values[w] + " ms");
                    main.removeCallbacks(hideStatus);
                    main.postDelayed(hideStatus, 2600);
                })
                .setNegativeButton("取消", null)
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
                    buildChips();
                    play(0);
                    setStatus("自定义源 · " + list.size() + " 个频道");
                });
            } catch (Exception e) {
                main.post(() -> setStatus("加载失败：" + brief(e.getMessage())));
            }
        });
    }

    // ===================== 遥控器 =====================

    /**
     * 分类排序模式必须在事件分发最早期拦截：焦点在 chip 上时，DPAD_CENTER 会被
     * chip 的 OnClickListener 先消费掉（触发切分类），走到 Activity.onKeyDown 已经晚了。
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (chipSortMode) {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return true;
            int keyCode = event.getKeyCode();
            View focused = chipsRow == null ? null : chipsRow.findFocus();
            int idx = focused == null ? -1 : chipsRow.indexOfChild(focused);
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (idx < 1) {   // 焦点跑丢或落到固定的「全部」上时，送回第一个可排序分类
                    if (chipsRow != null && chipsRow.getChildCount() > 1) {
                        chipsRow.getChildAt(1).requestFocus();
                    }
                    return true;
                }
                int to = idx + (keyCode == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : 1);
                // 「全部」固定在第 0 位，不参与排序
                if (to >= 1 && chipsRow != null && to < chipsRow.getChildCount()) {
                    moveChip(idx, to);
                }
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER
                    || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER || keyCode == KeyEvent.KEYCODE_BACK
                    || keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
                exitChipSortMode();
                return true;
            }
            return true;   // 排序期间屏蔽换台等其它按键
        }
        // v1.23：全屏时确定键必须在分发层拦截。缓冲状态下焦点可能落在 PlayerView 的
        // 内部控件上，走到 Activity.onKeyDown 之前就被消费，导致「按确定键没反应」。
        if (!chipSortMode && !listShown && event.getAction() == KeyEvent.ACTION_DOWN) {
            int kc = event.getKeyCode();
            // 全屏播放时返回键直接彻底退出（在分发层拦截，避免被 PlayerView 内部控件消费）
            if (kc == KeyEvent.KEYCODE_BACK) {
                exitApp();
                return true;
            }
            if (kc == KeyEvent.KEYCODE_DPAD_CENTER || kc == KeyEvent.KEYCODE_ENTER
                    || kc == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                showList();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_INFO) {
            showList();
            showMenu();
            return true;
        }
        // 遥控器字幕键：一键开关实时简中字幕
        if (keyCode == KeyEvent.KEYCODE_CAPTIONS) {
            toggleSubtitles();
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (listShown) {
                hideList();
                return true;
            }
            exitApp();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    /** v1.23：换台时在画面上显示频道信息，3.6 秒后淡出 */
    private void showChannelOsd(Channel c, String meta) {
        if (channelOsd == null) return;
        main.removeCallbacks(hideChannelOsd);
        channelOsd.animate().cancel();   // 取消上一次未完成的淡出，避免竞态
        channelOsdName.setText(c.name);
        // 去掉 meta 里的频道名部分，只留地区/线路等补充信息
        String extra = "";
        if (c.region != null && !c.region.isEmpty() && !c.name.contains(c.region)) {
            extra = c.region;
        }
        if (c.lineCount() > 1) {
            extra += (extra.isEmpty() ? "" : " · ") + "线路 " + (lineIndex + 1) + "/" + c.lineCount();
        }
        if (extra.isEmpty()) extra = c.group == null ? "正在播放" : c.group;
        channelOsdMeta.setText(extra);
        channelOsd.setAlpha(1f);
        channelOsd.setVisibility(View.VISIBLE);
        main.postDelayed(hideChannelOsd, 3600);
    }

    private void setStatus(String text) {
        if (chipSortMode) return;   // 排序胶囊不被播放状态覆盖
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
    }

    /** 排序模式专用状态提示，绕过 setStatus 的拦截 */
    private void setSortStatus(String text) {
        statusView.setText(text);
        statusView.setVisibility(View.VISIBLE);
        main.removeCallbacks(hideStatus);
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

    /**
     * 全屏播放时按返回键 = 真正退出：停流、释放播放器、结束整个进程。
     * 部分电视在 Activity stop 后仍保留进程，若不主动 kill，ExoPlayer/字幕/异步解析
     * 会继续在后台拉流（切到别的 App 后仍有网络请求与声音）。
     */
    private void exitApp() {
        if (exiting) return;
        exiting = true;
        main.removeCallbacksAndMessages(null);   // 缓冲看门狗、时钟、OSD、更新提示等
        io.shutdownNow();                        // 中断源解析 / 网络请求
        if (subtitleClient != null) subtitleClient.stop();
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.clearMediaItems(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(subtitleTicker);
        main.removeCallbacks(hideChannelOsd);
        if (subtitleClient != null) subtitleClient.stop();
        io.shutdownNow();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}
