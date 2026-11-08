package com.lau.iptv;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * M3U / M3U8 播放列表解析器。
 * 解析后会把同名频道合并成一个频道（多线路备份），并按电视台习惯排序。
 */
public class M3uParser {

    public static List<Channel> parse(String text) {
        return parse(text, true);
    }

    public static List<Channel> parse(String text, boolean mergeSame) {
        List<Channel> raw = parseRaw(text);
        if (!mergeSame) return raw;

        Map<String, Channel> merged = new LinkedHashMap<>();
        for (Channel c : raw) {
            String key = Catalog.key(c.name);
            if (key.isEmpty()) continue;
            Channel exist = merged.get(key);
            if (exist == null) {
                merged.put(key, c);
            } else {
                for (String u : c.urls) exist.addUrl(u);
                // 分组优先取更"像电视频道"的那个
                if ((exist.group.isEmpty() || exist.group.contains("更新时间"))
                        && !c.group.isEmpty() && !c.group.contains("更新时间")) {
                    exist.group = c.group;
                }
            }
        }

        List<Channel> out = new ArrayList<>(merged.values());
        Collections.sort(out, (a, b) -> {
            int ra = Catalog.regionRank(a.region);
            int rb = Catalog.regionRank(b.region);
            if (ra != rb) return Integer.compare(ra, rb);
            int ca = Catalog.order(a.group, a.name);
            int cb = Catalog.order(b.group, b.name);
            if (ca != cb) return Integer.compare(ca, cb);
            return a.name.compareTo(b.name);
        });
        return out;
    }

    /** 逐条解析，不做合并 */
    public static List<Channel> parseRaw(String text) {
        List<Channel> out = new ArrayList<>();
        if (text == null || text.isEmpty()) return out;

        String pendingName = null;
        String pendingGroup = "";
        String pendingLogo = "";
        String pendingTvgId = "";

        String[] lines = text.split("\r?\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF")) {
                String group = attr(line, "group-title");
                String logo = attr(line, "tvg-logo");
                String tvgName = attr(line, "tvg-name");
                String tvgId = attr(line, "tvg-id");

                int comma = line.lastIndexOf(',');
                String display = comma >= 0 ? line.substring(comma + 1).trim() : "";

                if (display.isEmpty() && tvgName != null) display = tvgName;
                if (display.isEmpty()) display = tvgName;
                if (display == null || display.isEmpty()) display = "未命名频道";

                pendingName = Catalog.display(clean(display));
                pendingGroup = group == null ? "" : group;
                pendingLogo = logo == null ? "" : logo;
                pendingTvgId = tvgId == null ? "" : tvgId;
            } else if (line.startsWith("#EXTGRP:")) {
                pendingGroup = line.substring("#EXTGRP:".length()).trim();
            } else if (line.startsWith("#")) {
                continue;
            } else if (line.startsWith("http://") || line.startsWith("https://")
                    || line.startsWith("rtmp://") || line.startsWith("rtsp://")) {
                if (pendingName == null) pendingName = "频道 " + (out.size() + 1);
                Channel c = new Channel(pendingName, pendingGroup, pendingLogo,
                        Catalog.order(pendingGroup, pendingName));
                c.region = Catalog.regionOf(pendingName, pendingGroup, pendingTvgId);
                c.addUrl(line);
                out.add(c);
                pendingName = null;
                pendingGroup = "";
                pendingLogo = "";
                pendingTvgId = "";
            }
        }
        return out;
    }

    /** 去掉台名里的线路标记，如「移动」「电信」 */
    private static String clean(String s) {
        String r = s.replaceAll("[\\(（\\[【「].*?[\\)）\\]】」]", "").trim();
        r = r.replaceAll("\\s*[-_·|]+\\s*$", "");
        return r.isEmpty() ? s.trim() : r;
    }

    private static String attr(String line, String key) {
        String needle = key + "=\"";
        int i = line.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        int end = line.indexOf('"', start);
        return end > start ? line.substring(start, end) : null;
    }
}
