package com.lau.iptv;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
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
    private static final String UA =
            "Mozilla/5.0 (Linux; Android 12; BRAVIA 4K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    private PlayerView playerView;
    private ExoPlayer player;
    private RecyclerView listView;
    private LinearLayout leftPanel;
    private TextView titleView;
    private TextView statusView;
    private ChannelAdapter adapter;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;

    private final List<Channel> allChannels = new ArrayList<>();
    private int currentIndex = -1;   // 列表行位置
    private int lineIndex = 0;
    private int autoSwitch = 0;
    private int mode = MODE_TV;
    private boolean loading = false;
    private boolean listShown = true;

    /** 换台后状态条自动淡出 */
    private final Runnable hideStatus = new Runnable() {
        @Override
        public void run() {
            statusView.setVisibility(View.GONE);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREF, MODE_PRIVATE);
        mode = prefs.getInt(KEY_MODE, MODE_ALL);

        titleView = findViewById(R.id.title);
        statusView = findViewById(R.id.status);
        listView = findViewById(R.id.list);
        playerView = findViewById(R.id.player);
        leftPanel = findViewById(R.id.left_panel);

        DefaultHttpDataSource.Factory ds = new DefaultHttpDataSource.Factory()
                .setUserAgent(UA)
                .setConnectTimeoutMs(12000)
                .setReadTimeoutMs(18000)
                .setAllowCrossProtocolRedirects(true);
        player = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(ds))
                .build();
        playerView.setPlayer(player);

        adapter = new ChannelAdapter((position, channel) -> play(position));
        listView.setLayoutManager(new LinearLayoutManager(this));
        listView.setAdapter(adapter);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                onPlayFailed(brief(error.getMessage()));
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
    }

    // ===================== 列表浮层 =====================

    private void showList() {
        listShown = true;
        leftPanel.setVisibility(View.VISIBLE);
        leftPanel.requestFocus();
        listView.requestFocus();
    }

    private void hideList() {
        listShown = false;
        leftPanel.setVisibility(View.GONE);
        playerView.requestFocus();
    }

    // ===================== 加载 =====================

    private void startUp() {
        String builtin = readAsset("verified.m3u");
        if (builtin != null && builtin.contains("#EXTINF")) {
            allChannels.clear();
            allChannels.addAll(M3uParser.parse(builtin));
            render(false);
            showList();
            play(0);
        } else {
            loadSource(prefs.getInt(KEY_SOURCE, 1), true);
        }
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
                adapter.setPlayingRow(i);
                listView.scrollToPosition(i);
                titleView.setText(getTitleText());
                return true;
            }
        }
        return false;
    }

    private String getTitleText() {
        String label = mode == MODE_SPORT ? " · 体育赛事"
                : (mode == MODE_ALL ? " · 全部" : "");
        return getString(R.string.channel_count, totalOf(adapter)) + label;
    }

    private int totalOf(ChannelAdapter a) {
        int n = 0;
        for (int i = 0; i < a.size(); i++) if (a.channelAt(i) != null) n++;
        return n;
    }

    /** 按当前显示模式填充列表（列表内部按国家/地区分组） */
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
        titleView.setText(getTitleText());
        if (!keepPlayState) {
            currentIndex = -1;
            listView.scrollToPosition(0);
        }
    }

    // ===================== 播放 =====================

    private void play(int row) {
        Channel c = adapter.channelAt(row);
        if (c == null) {
            row = adapter.nextChannelRow(row - 1, 1);
            c = adapter.channelAt(row);
            if (c == null) return;
        }
        currentIndex = row;
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
        try {
            MediaItem item = new MediaItem.Builder()
                    .setUri(c.urls.get(lineIndex))
                    .setMediaId(String.valueOf(currentIndex))
                    .build();
            player.setMediaItem(item);
            player.prepare();
            player.play();
            adapter.setPlayingRow(currentIndex);
            String meta = c.region + " · " + c.name;
            if (c.lineCount() > 1) {
                meta += " · 线路 " + (lineIndex + 1) + "/" + c.lineCount();
            }
            setStatus(meta);
            main.removeCallbacks(hideStatus);
            main.postDelayed(hideStatus, 2600);
            listView.smoothScrollToPosition(currentIndex);
        } catch (Exception e) {
            setStatus("无法播放：" + brief(e.getMessage()));
        }
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
        setStatus("这个台暂时放不出来 · CH+ 换台 / 菜单换源");
    }

    // ===================== 菜单 =====================

    private void showMenu() {
        String modeLabel = mode == MODE_SPORT ? "只看体育（NBA / NFL）"
                : (mode == MODE_ALL ? "全部频道（按国家）" : "电视台直播");
        String[] items = {"切换节目源…", "显示内容：" + modeLabel + " …", "重新加载当前源", "手动输入 m3u 链接…"};
        new AlertDialog.Builder(this)
                .setTitle("菜单")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0:
                            showSourceDialog();
                            break;
                        case 1:
                            showModeDialog();
                            break;
                        case 2:
                            loadSource(prefs.getInt(KEY_SOURCE, 1), false);
                            break;
                        default:
                            showUrlDialog();
                            break;
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
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
            int prev = adapter.nextChannelRow(currentIndex, -1);
            if (prev >= 0) play(prev);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN && !listShown) {
            int next = adapter.nextChannelRow(currentIndex, 1);
            if (next >= 0) play(next);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_UP || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
            int next = adapter.nextChannelRow(currentIndex, 1);
            if (next >= 0) play(next);
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
            int prev = adapter.nextChannelRow(currentIndex, -1);
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
        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && listShown) {
            hideList();
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
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
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
