package com.lau.iptv;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 频道目录规则：把乱七八糟的源整理成电视台直播的样子
 * —— 统一台名、判断是央视/卫视/地方台、给出排序权重、过滤非电视频道
 */
public final class Catalog {

    public static final int CAT_CCTV = 0;    // 央视频道
    public static final int CAT_SAT = 1;     // 各省卫视
    public static final int CAT_LOCAL = 2;   // 省市地方台
    public static final int CAT_HMT = 3;     // 港澳台
    public static final int CAT_SPORT = 4;   // 体育
    public static final int CAT_OTHER = 5;   // 电影/剧场/动画/音乐等非电视频道

    /** 卫视常见顺序，按观众习惯排 */
    private static final String[] SAT_ORDER = {
            "湖南卫视", "浙江卫视", "江苏卫视", "东方卫视", "北京卫视", "深圳卫视",
            "安徽卫视", "山东卫视", "广东卫视", "天津卫视", "四川卫视", "湖北卫视",
            "河南卫视", "河北卫视", "福建东南卫视", "东南卫视", "江西卫视", "辽宁卫视",
            "黑龙江卫视", "吉林卫视", "山西卫视", "陕西卫视", "甘肃卫视", "宁夏卫视",
            "青海卫视", "新疆卫视", "西藏卫视", "云南卫视", "贵州卫视", "广西卫视",
            "内蒙古卫视", "海南卫视", "重庆卫视", "厦门卫视", "三沙卫视", "延边卫视",
            "兵团卫视", "康巴卫视", "安多卫视", "海峡卫视", "农林卫视", "山东教育卫视"
    };

    /** 省级地方台排序 */
    private static final String[] PROVINCE_ORDER = {
            "北京", "上海", "天津", "重庆", "广东", "江苏", "浙江", "山东", "河南",
            "四川", "湖北", "湖南", "河北", "福建", "安徽", "陕西", "辽宁", "江西",
            "广西", "山西", "云南", "贵州", "黑龙江", "吉林", "内蒙古", "新疆",
            "甘肃", "海南", "宁夏", "青海", "西藏", "香港", "澳门", "台湾"
    };

    /** 非电视台直播的分组关键词（点播、剧场、付费等） */
    private static final String[] NOT_LIVE = {
            "电影", "剧场", "动画", "动漫", "音乐", "游戏", "付费", "纪录", "测试",
            "公告", "更新时间", "支持作者", "解说", "轮播", "点播", "节目库"
    };

    /** 台名本身就不是频道的（源作者广告位等） */
    private static boolean isJunkName(String name) {
        if (name == null) return false;
        String[] no = {"支持作者", "打赏", "捐赠", "官网", "izbds", "izbds.com", "www.", "更新时间", "公众号", "扫码"};
        for (String k : no) {
            if (name.toLowerCase().contains(k)) return true;
        }
        return false;
    }

    private static final Pattern CCTV = Pattern.compile("CCTV[-_ ]?(\\d{1,2})(\\+)?", Pattern.CASE_INSENSITIVE);
    private static final Pattern PROVINCE = Pattern.compile(
            "(北京|上海|天津|重庆|广东|江苏|浙江|山东|河南|四川|湖北|湖南|河北|福建|安徽|陕西|辽宁|江西|"
                    + "广西|山西|云南|贵州|黑龙江|吉林|内蒙古|新疆|甘肃|海南|宁夏|青海|西藏)");

    private Catalog() {
    }

    /** 判断分组/台名属于哪一类 */
    public static int category(String group, String name) {
        String g = group == null ? "" : group;
        String n = name == null ? "" : name;
        String gl = g.toLowerCase();

        if (isNotLive(g) || isJunkName(n)) return CAT_OTHER;
        if (g.contains("央视") || n.toUpperCase().startsWith("CCTV")
                || n.toUpperCase().startsWith("CGTN")) {
            return CAT_CCTV;
        }
        // 英文源里的体育分组：Sports / Sport
        if (gl.contains("sport")) return CAT_SPORT;
        if (g.contains("卫") || n.contains("卫视") || n.contains("凤凰")) return CAT_SAT;
        if (g.contains("港澳") || g.contains("港·澳") || g.contains("香港")
                || g.contains("澳门") || g.contains("台湾") || g.contains("HK")) {
            return CAT_HMT;
        }
        if (g.contains("体育") || n.contains("体育")) return CAT_SPORT;
        if (isNotSportShow(n)) return CAT_OTHER;
        if (g.contains("频道") || g.contains("地方") || g.contains("省")) return CAT_LOCAL;
        if (PROVINCE.matcher(g + n).find()) return CAT_LOCAL;
        return CAT_OTHER;
    }

    /** 挂着"体育"分组但其实不是比赛直播的台 */
    private static boolean isNotSportShow(String name) {
        String[] no = {"斗地主", "广场舞", "垂钓", "购物", "棋牌"};
        for (String k : no) {
            if (name.contains(k)) return true;
        }
        return false;
    }

    private static boolean isNotLive(String group) {
        for (String k : NOT_LIVE) {
            if (group.contains(k)) return true;
        }
        return false;
    }

    /** 是否是垃圾占位频道（广告位等） */
    public static boolean isJunk(String name) {
        return isJunkName(name);
    }

    /** 是否属于"电视台直播"（用于默认过滤） */
    public static boolean isTvChannel(int cat) {
        return cat <= CAT_SPORT;
    }

    /** 计算排序权重 */
    public static int order(String group, String name) {
        int cat = category(group, name);
        switch (cat) {
            case CAT_CCTV:
                return 1000 + cctvIndex(name);
            case CAT_SPORT:
                return 2000 + sportIndex(name);
            case CAT_SAT: {
                int i = indexOfAny(name, SAT_ORDER);
                return 3000 + (i >= 0 ? i : 300 + Math.abs(norm(name).hashCode() % 200));
            }
            case CAT_LOCAL: {
                int p = indexOfAny(group + name, PROVINCE_ORDER);
                return 4000 + (p >= 0 ? p : 90) * 10;
            }
            case CAT_HMT:
                return 5000;
            default:
                return 6000;
        }
    }

    /** 体育台里，NBA / NFL / ESPN 这些排最前 */
    private static int sportIndex(String name) {
        String n = name.toUpperCase();
        if (n.contains("NBA")) return 0;
        if (n.contains("NFL")) return 6;
        if (n.contains("ESPN")) return 12;
        if (n.contains("MLB") || n.contains("NHL")) return 18;
        if (n.contains("UFC") || n.contains("WWE")) return 24;
        if (n.contains("SKY SPORT") || n.contains("TNT") || n.contains("DAZN")) return 30;
        if (n.contains("EUROSPORT") || n.contains("FOX SPORT") || n.contains("BEIN")) return 36;
        if (n.contains("F1") || n.contains("MOTOGP") || n.contains("PREMIER")) return 42;
        if (n.contains("CCTV") || n.contains("体育") || n.contains("足球") || n.contains("篮球")) return 50;
        return 80 + Math.abs(norm(name).hashCode() % 40);
    }

    /** 体育组里只留值得看的台：中文体育台 + 知名赛事台，过滤掉国外的杂牌小台 */
    public static boolean isSportWanted(String name) {
        if (name == null) return false;
        if (name.matches(".*[\\u4e00-\\u9fa5].*")) return true;
        String n = name.toUpperCase();
        String[] keys = {"NBA", "NFL", "MLB", "NHL", "ESPN", "DAZN", "TNT", "SKY SPORT",
                "EUROSPORT", "FOX SPORT", "BEIN", "UFC", "WWE", "F1", "MOTOGP",
                "PREMIER", "CBS SPORT", "NBC SPORT", "GOLF", "PGA", "TENNIS",
                "MMA", "FIFA", "FITE", "BELLATOR"};
        for (String k : keys) {
            if (n.contains(k)) return true;
        }
        return false;
    }

    /** CCTV 频道内部顺序：CCTV-1..17 依次，5+ 跟在 5 后，4K/8K 排后，CGTN 最后 */
    private static int cctvIndex(String name) {
        String n = name.toUpperCase();
        if (n.startsWith("CGTN") || n.contains("CGTN")) {
            if (n.contains("纪录") || n.contains("DOC")) return 890;
            return 800 + Math.abs(norm(name).hashCode() % 50);
        }
        Matcher m = CCTV.matcher(name);
        if (m.find()) {
            int num = Integer.parseInt(m.group(1));
            return num * 10 + (m.group(2) != null ? 5 : 0);
        }
        if (n.contains("4K")) return 780;
        if (n.contains("8K")) return 785;
        return 700 + Math.abs(norm(name).hashCode() % 50);
    }

    private static int indexOfAny(String text, String[] table) {
        for (int i = 0; i < table.length; i++) {
            if (text.contains(table[i])) return i;
        }
        return -1;
    }

    /**
     * 合并用的规范化台名：CCTV-1 / CCTV1 / CCTV-1综合 都归一为 CCTV1
     * 湖南卫视 / 湖南卫视高清 归一为 湖南卫视
     */
    public static String key(String name) {
        if (name == null) return "";
        String s = name.trim();
        // 去掉括号里的补充说明
        s = s.replaceAll("[\\(（\\[【「].*?[\\)）\\]】」]", "");
        String upper = s.toUpperCase();
        Matcher c = CCTV.matcher(s);
        if (upper.contains("CCTV") && c.find()) {
            return "CCTV" + c.group(1) + (c.group(2) != null ? "PLUS" : "");
        }
        if (upper.contains("CGTN")) {
            String extra = norm(s.replace("CGTN", ""));
            return "CGTN" + extra;
        }
        // 卫视统一成 XX卫视
        int sat = s.indexOf("卫视");
        if (sat > 0) {
            String head = s.substring(0, sat);
            head = head.replaceAll("^(高清|超清|蓝光|HD|SD|CCTV台|电视)", "");
            return head + "卫视";
        }
        String k = norm(s);
        // ESPN 2 Latin America / ESPN 2 Argentina 归并成同一个台（多线路）
        for (String w : COUNTRY_WORDS) {
            k = k.replace(w, "");
        }
        return k;
    }

    /** 英文台名里的国家/地区后缀，用于合并同名频道 */
    private static final String[] COUNTRY_WORDS = {
            "LATINAMERICA", "ARGENTINA", "COLOMBIA", "MEXICO", "BRAZIL", "CHILE",
            "PERU", "GERMANY", "PORTUGAL", "NETHERLANDS", "SPAIN", "ITALY",
            "FRANCE", "TURKEY", "POLAND", "UNITEDSTATES", "USA"
    };

    /** 去掉画质、线路等后缀，只留台名主干 */
    public static String norm(String s) {
        if (s == null) return "";
        String r = s.toUpperCase();
        r = r.replaceAll("HIGH|FHD|UHD|HD|SD|H265|H264|HEVC|1080P|720P|4K|8K", "");
        r = r.replaceAll("高清|超清|标清|蓝光|原画|备用|线路|移动|电信|联通|测试", "");
        r = r.replaceAll("[^\\u4e00-\\u9fa5A-Z0-9+]", "");
        return r;
    }

    /** 展示名整理：把 "CCTV-1" 之类补成中文台名，去掉多余后缀 */
    public static String display(String name) {
        if (name == null || name.trim().isEmpty()) return "未命名频道";
        String s = name.trim().replaceAll("\\s+", " ");
        return s;
    }

    /** 分组列表头，用于列表里显示 */
    public static String groupLabel(String group, String name) {
        int cat = category(group, name);
        if (cat == CAT_CCTV) return "央视";
        if (group == null || group.isEmpty()) return "";
        String g = group.replaceAll("[\\p{So}\\p{Cn}\\uD83C-\\uDBFF\\uDC00-\\uDFFF]", "").trim();
        g = g.replace("央视频道", "央视").replace("卫视频道", "卫视");
        return g;
    }

    // ===================== 国家 / 地区 =====================

    private static final Pattern TVID_CC = Pattern.compile("\\.([a-z]{2})(?:@|$|[-_])");

    private static final java.util.Map<String, String> CC_NAME = new java.util.HashMap<>();

    static {
        String[][] pairs = {
                {"cn", "中国大陆"}, {"hk", "中国港澳台"}, {"mo", "中国港澳台"}, {"tw", "中国港澳台"},
                {"us", "美国"}, {"uk", "英国"}, {"gb", "英国"}, {"jp", "日本"}, {"kr", "韩国"},
                {"de", "德国"}, {"fr", "法国"}, {"it", "意大利"}, {"es", "西班牙"},
                {"nl", "荷兰"}, {"pt", "葡萄牙"}, {"br", "巴西"}, {"ar", "阿根廷"},
                {"mx", "墨西哥"}, {"ca", "加拿大"}, {"au", "澳大利亚"}, {"in", "印度"},
                {"ru", "俄罗斯"}, {"tr", "土耳其"}, {"pl", "波兰"}, {"se", "瑞典"},
                {"no", "挪威"}, {"dk", "丹麦"}, {"fi", "芬兰"}, {"ch", "瑞士"},
                {"at", "奥地利"}, {"be", "比利时"}, {"ie", "爱尔兰"}, {"gr", "希腊"},
                {"il", "以色列"}, {"ae", "阿联酋"}, {"sa", "沙特"}, {"th", "泰国"},
                {"vn", "越南"}, {"my", "马来西亚"}, {"sg", "新加坡"}, {"id", "印尼"},
                {"ph", "菲律宾"}, {"ir", "伊朗"}, {"eg", "埃及"}, {"za", "南非"},
                {"cl", "智利"}, {"co", "哥伦比亚"}, {"pe", "秘鲁"}, {"ve", "委内瑞拉"},
                {"ua", "乌克兰"}, {"ro", "罗马尼亚"}, {"hu", "匈牙利"}, {"cz", "捷克"},
                {"sk", "斯洛伐克"}, {"bg", "保加利亚"}, {"hr", "克罗地亚"},
                {"rs", "塞尔维亚"}, {"si", "斯洛文尼亚"}, {"lt", "立陶宛"},
                {"lv", "拉脱维亚"}, {"ee", "爱沙尼亚"}, {"by", "白俄罗斯"},
                {"kz", "哈萨克斯坦"}, {"ge", "格鲁吉亚"}, {"am", "亚美尼亚"},
                {"az", "阿塞拜疆"}, {"mn", "蒙古"}, {"np", "尼泊尔"}, {"lk", "斯里兰卡"},
                {"mm", "缅甸"}, {"kh", "柬埔寨"}, {"la", "老挝"}, {"pk", "巴基斯坦"},
                {"bd", "孟加拉"}, {"iq", "伊拉克"},
        };
        for (String[] p : pairs) CC_NAME.put(p[0], p[1]);
    }

    /** 地区排序权重：中国大陆 → 港澳台 → 美国 → 英国 → 其他 */
    private static final String[] REGION_ORDER = {
            "中国大陆", "中国港澳台", "美国", "英国", "日本", "韩国", "加拿大", "澳大利亚",
            "德国", "法国", "意大利", "西班牙", "荷兰", "葡萄牙", "俄罗斯", "印度"
    };

    public static int regionRank(String region) {
        if ("中国".equals(region)) return 0;
        for (int i = 0; i < REGION_ORDER.length; i++) {
            if (REGION_ORDER[i].equals(region)) return i;
        }
        return 90;
    }

    /** 判断频道属于哪个国家/地区：优先看 tvg-id 里的国家码，其次看台名 */
    public static String regionOf(String name, String group, String tvgId) {
        if (tvgId != null && !tvgId.isEmpty()) {
            Matcher m = TVID_CC.matcher(tvgId);
            if (m.find()) {
                String cn = CC_NAME.get(m.group(1));
                if (cn != null) return cn;
            }
        }
        String n = name == null ? "" : name;
        String text = n + " " + (group == null ? "" : group);
        if (n.matches(".*[\\u4e00-\\u9fa5].*")) {
            if (text.matches(".*(香港|翡翠|明珠|TVB|澳门|澳视|台湾|台视|中视|华视|民视|东森|三立|纬来|非凡|年代).*")) {
                return "中国港澳台";
            }
            return "中国大陆";
        }
        String[][] kw = {
                {"UNITED STATES|USA|\\bUS\\b", "美国"}, {"UNITED KINGDOM|\\bUK\\b", "英国"},
                {"JAPAN", "日本"}, {"KOREA", "韩国"}, {"GERMANY|DEUTSCH", "德国"},
                {"FRANCE", "法国"}, {"ITALY|ITALIA", "意大利"}, {"SPAIN|ESPAÑA", "西班牙"},
                {"NETHERLANDS", "荷兰"}, {"PORTUGAL", "葡萄牙"}, {"BRAZIL|BRASIL", "巴西"},
                {"ARGENTINA", "阿根廷"}, {"MEXICO", "墨西哥"}, {"CANADA", "加拿大"},
                {"INDIA", "印度"}, {"TURKEY|TURKIYE", "土耳其"}, {"AUSTRALIA", "澳大利亚"},
                {"RUSSIA", "俄罗斯"}, {"POLAND", "波兰"}, {"SWEDEN", "瑞典"},
                {"GREECE", "希腊"}, {"ISRAEL", "以色列"}, {"THAILAND", "泰国"},
                {"VIETNAM", "越南"}, {"MALAYSIA", "马来西亚"}, {"SINGAPORE", "新加坡"},
                {"INDONESIA", "印尼"}, {"PHILIPPINES", "菲律宾"}, {"CHINA", "中国大陆"},
        };
        String up = text.toUpperCase();
        for (String[] k : kw) {
            if (up.matches(".*(" + k[0] + ").*")) return k[1];
        }
        return "其他";
    }
}
