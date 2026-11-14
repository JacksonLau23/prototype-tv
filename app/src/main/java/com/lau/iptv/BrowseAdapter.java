package com.lau.iptv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 浏览视图：Netflix / Disney+ 式「分类 → 横向卡片行」。
 * 内部维护一个扁平频道序列（换台顺序 = 卡片序号），对外仍按 flat index 访问。
 */
public class BrowseAdapter extends RecyclerView.Adapter<BrowseAdapter.SectionVH> {

    public interface OnPick {
        void onPick(int flatIndex, Channel channel);
    }

    /** 单个分类：标题 + 该类频道（含各自 flat index） */
    private static class Section {
        final String title;
        final List<Channel> channels = new ArrayList<>();
        final List<Integer> flatIndex = new ArrayList<>();

        Section(String title) {
            this.title = title;
        }
    }

    private final List<Section> sections = new ArrayList<>();
    private final List<Channel> flat = new ArrayList<>();
    private final OnPick onPick;
    private Channel playing;
    /** 共享行内卡片池，滚动更顺 */
    private final RecyclerView.RecycledViewPool pool = new RecyclerView.RecycledViewPool();

    public BrowseAdapter(OnPick onPick) {
        this.onPick = onPick;
    }

    // ===================== 数据 =====================

    public void submit(List<Channel> list) {
        flat.clear();
        sections.clear();
        playing = null;
        if (list != null) {
            flat.addAll(list);
        }

        // 先按自然分组标题分桶
        Map<String, Section> bucket = new LinkedHashMap<>();
        for (int i = 0; i < flat.size(); i++) {
            Channel c = flat.get(i);
            String t = sectionTitle(c);
            Section s = bucket.get(t);
            if (s == null) {
                s = new Section(t);
                bucket.put(t, s);
            }
            s.channels.add(c);
            s.flatIndex.add(i);
        }
        // 分桶太碎时（在线聚合源几千频道），退化为按地区分组
        if (bucket.size() > 24) {
            bucket.clear();
            for (int i = 0; i < flat.size(); i++) {
                Channel c = flat.get(i);
                String t = c.region == null || c.region.isEmpty() ? "其他" : c.region;
                Section s = bucket.get(t);
                if (s == null) {
                    s = new Section(t);
                    bucket.put(t, s);
                }
                s.channels.add(c);
                s.flatIndex.add(i);
            }
        }
        sections.addAll(bucket.values());
        notifyDataSetChanged();
    }

    private static String sectionTitle(Channel c) {
        String g = Catalog.groupLabel(c.group, c.name);
        if (g.isEmpty()) {
            g = c.region == null || c.region.isEmpty() ? "其他" : c.region;
        }
        return g;
    }

    // ===================== 扁平访问（换台用） =====================

    public Channel channelAt(int i) {
        if (i < 0 || i >= flat.size()) return null;
        return flat.get(i);
    }

    public int size() {
        return flat.size();
    }

    /** 上/下一个频道（flat 序列） */
    public int nextChannelIndex(int from, int dir) {
        int i = from + dir;
        return (i >= 0 && i < flat.size()) ? i : -1;
    }

    /** 把焦点放到第一张卡片上（打开面板 / 切换分类后） */
    public boolean focusFirst() {
        for (RecyclerView rv : rowRecyclers.values()) {
            if (rv != null && rv.getChildCount() > 0) {
                View v = rv.getChildAt(0);
                if (v != null) return v.requestFocus();
            }
        }
        return false;
    }

    /** 把焦点移到某频道所在卡片（恢复播放位置后） */
    public boolean focusChannel(Channel c) {
        if (c == null) return false;
        for (int s = 0; s < sections.size(); s++) {
            Section sec = sections.get(s);
            int local = sec.channels.indexOf(c);
            if (local < 0) continue;
            RecyclerView rv = rowRecyclers.get(s);
            if (rv == null) continue;
            rv.scrollToPosition(local);
            final int idx = local;
            rv.post(() -> {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(idx);
                if (vh != null) vh.itemView.requestFocus();
            });
            return true;
        }
        return false;
    }

    public void setPlaying(Channel c) {
        Channel old = playing;
        playing = c;
        if (old != null) notifyChannelChanged(old);
        if (c != null) notifyChannelChanged(c);
    }

    private void notifyChannelChanged(Channel c) {
        for (int s = 0; s < sections.size(); s++) {
            Section sec = sections.get(s);
            int local = sec.channels.indexOf(c);
            if (local >= 0) {
                RecyclerView rv = rowRecyclers.get(s);
                if (rv != null) {
                    RecyclerView.Adapter<?> a = rv.getAdapter();
                    if (a != null) a.notifyItemChanged(local);
                }
            }
        }
    }

    /** 行内 RecyclerView 缓存，用于局部刷新在播标记 */
    private final Map<Integer, RecyclerView> rowRecyclers = new LinkedHashMap<>();

    // ===================== 纵向分区 =====================

    @NonNull
    @Override
    public SectionVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_section, parent, false);
        return new SectionVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull SectionVH holder, int position) {
        Section s = sections.get(position);
        holder.name.setText(s.title);
        holder.count.setText(s.channels.size() + " 个频道");
        rowRecyclers.put(position, holder.row);

        if (holder.row.getAdapter() == null) {
            holder.row.setLayoutManager(
                    new LinearLayoutManager(holder.row.getContext(),
                            LinearLayoutManager.HORIZONTAL, false));
            holder.row.setRecycledViewPool(pool);
            holder.row.setAdapter(new CardAdapter(s));
        } else {
            ((CardAdapter) holder.row.getAdapter()).update(s);
        }
    }

    @Override
    public void onViewRecycled(@NonNull SectionVH holder) {
        super.onViewRecycled(holder);
        for (Map.Entry<Integer, RecyclerView> e : rowRecyclers.entrySet()) {
            if (e.getValue() == holder.row) {
                rowRecyclers.remove(e.getKey());
                break;
            }
        }
    }

    @Override
    public int getItemCount() {
        return sections.size();
    }

    static class SectionVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;
        final RecyclerView row;

        SectionVH(View v) {
            super(v);
            name = v.findViewById(R.id.section_name);
            count = v.findViewById(R.id.section_count);
            row = v.findViewById(R.id.section_list);
        }
    }

    // ===================== 横向卡片 =====================

    private class CardAdapter extends RecyclerView.Adapter<CardVH> {

        private Section section;

        CardAdapter(Section s) {
            this.section = s;
        }

        void update(Section s) {
            this.section = s;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public CardVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_channel_card, parent, false);
            return new CardVH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull CardVH h, int position) {
            Channel c = section.channels.get(position);
            int flatIdx = section.flatIndex.get(position);

            h.num.setText(String.valueOf(flatIdx + 1));
            h.name.setText(c.name);
            // 台标：有 logo 显示图片；没有则频道名大字兜底
            if (c.logo != null && !c.logo.isEmpty()) {
                h.fallback.setVisibility(View.GONE);
                h.name.setVisibility(View.VISIBLE);
                LogoLoader.into(h.logo, c.logo);
            } else {
                h.logo.setVisibility(View.GONE);
                h.fallback.setText(c.name);
                h.fallback.setVisibility(View.VISIBLE);
                h.name.setVisibility(View.GONE);
            }
            String g = Catalog.groupLabel(c.group, c.name);
            if (c.lineCount() > 1) {
                g = (g.isEmpty() ? "" : g + " · ") + c.lineCount() + " 线路";
            }
            h.group.setText(g);
            h.group.setVisibility(g.isEmpty() ? View.GONE : View.VISIBLE);
            h.live.setVisibility(c == playing ? View.VISIBLE : View.GONE);

            // Apple tvOS / YouTube TV 聚焦：白卡 + 军绿粗描边 + 放大 + 投影；
            // 聚焦态背景在内层卡片上切换（焦点在 itemView 上，选择器不会自动生效）
            h.itemView.setOnFocusChangeListener((v, has) -> {
                v.animate().scaleX(has ? 1.15f : 1f)
                        .scaleY(has ? 1.15f : 1f)
                        .setDuration(150).start();
                h.itemView.setTranslationZ(has ? 24f : 0f);
                v.setAlpha(has ? 1f : 0.92f);
                applyCardColors(h, has);
                if (has) {
                    // 聚焦时让所在行滚到该卡片
                    v.post(() -> {
                        View p = (View) v.getParent();
                        if (p instanceof RecyclerView) {
                            RecyclerView rv = (RecyclerView) p;
                            rv.smoothScrollToPosition(
                                    h.getBindingAdapterPosition());
                        }
                    });
                }
            });
            applyCardColors(h, h.itemView.isFocused());
            h.itemView.setAlpha(h.itemView.isFocused() ? 1f : 0.92f);

            h.itemView.setOnClickListener(v -> {
                int p = h.getBindingAdapterPosition();
                if (p >= 0 && p < section.channels.size()) {
                    onPick.onPick(section.flatIndex.get(p), section.channels.get(p));
                }
            });
        }

        private void applyCardColors(CardVH h, boolean focused) {
            h.body.setBackgroundResource(focused
                    ? R.drawable.card_focus_bg : R.drawable.card_normal_bg);
            h.name.setTextColor(focused ? 0xFF111111 : 0xFFF2F2F7);
            h.group.setTextColor(focused ? 0xFF55555C : 0xFFB0B0BA);
            h.num.setTextColor(focused ? 0xFF55555C : 0xFFB0B0BA);
            h.fallback.setTextColor(focused ? 0xFF111111 : 0xFFF2F2F7);
        }

        @Override
        public int getItemCount() {
            return section.channels.size();
        }
    }

    static class CardVH extends RecyclerView.ViewHolder {
        final View body;
        final TextView num;
        final TextView name;
        final TextView group;
        final TextView fallback;
        final ImageView logo;
        final View live;

        CardVH(View v) {
            super(v);
            body = v.findViewById(R.id.card_body);
            num = v.findViewById(R.id.num);
            name = v.findViewById(R.id.name);
            group = v.findViewById(R.id.group);
            fallback = v.findViewById(R.id.logo_name_fallback);
            logo = v.findViewById(R.id.logo);
            live = v.findViewById(R.id.live_badge);
        }
    }
}
