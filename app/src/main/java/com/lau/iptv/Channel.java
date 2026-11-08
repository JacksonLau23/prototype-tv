package com.lau.iptv;

import java.util.ArrayList;
import java.util.List;

/** 单个直播频道：同一个频道可能来自多个源，保存为多条备用线路 */
public class Channel {
    public final String name;
    public String group;
    public final String logo;
    /** 备用地址列表，按可用性优先级排列，第一个为默认线路 */
    public final List<String> urls;
    /** 排序权重，越小越靠前：央视 → 卫视 → 各省台 → 港澳台 → 体育 → 其他 */
    public final int order;
    /** 所属国家/地区，用于列表分组 */
    public String region = "其他";

    public Channel(String name, String group, String logo, int order) {
        this.name = name;
        this.group = group == null ? "" : group;
        this.logo = logo == null ? "" : logo;
        this.order = order;
        this.urls = new ArrayList<>(4);
    }

    public void addUrl(String url) {
        if (url == null) return;
        String u = url.trim();
        if (u.isEmpty()) return;
        if (!urls.contains(u) && urls.size() < 8) urls.add(u);
    }

    public String url() {
        return urls.isEmpty() ? "" : urls.get(0);
    }

    public int lineCount() {
        return urls.size();
    }
}
