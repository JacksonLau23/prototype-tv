package com.lau.iptv;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 频道列表：按国家/地区分组，组内是频道（带线路数） */
public class ChannelAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnPick {
        void onPick(int position, Channel channel);
    }

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_CHANNEL = 1;

    private final List<Object> rows = new ArrayList<>();
    private final OnPick onPick;
    private int playingRow = -1;

    public ChannelAdapter(OnPick onPick) {
        this.onPick = onPick;
    }

    public void submit(List<Channel> list) {
        rows.clear();
        if (list != null && !list.isEmpty()) {
            Map<String, Integer> count = new LinkedHashMap<>();
            for (Channel c : list) {
                count.merge(c.region, 1, Integer::sum);
            }
            String last = null;
            for (Channel c : list) {
                if (!c.region.equals(last)) {
                    rows.add(new Header(c.region, count.get(c.region)));
                    last = c.region;
                }
                rows.add(c);
            }
        }
        notifyDataSetChanged();
    }

    public void setPlayingRow(int row) {
        int old = playingRow;
        playingRow = row;
        if (old >= 0 && old < rows.size()) notifyItemChanged(old);
        if (row >= 0 && row < rows.size()) notifyItemChanged(row);
    }

    public Channel channelAt(int row) {
        if (row < 0 || row >= rows.size()) return null;
        Object o = rows.get(row);
        return o instanceof Channel ? (Channel) o : null;
    }

    public int size() {
        return rows.size();
    }

    /** 当前行之后的下一个频道行（跳过分组标题） */
    public int nextChannelRow(int from, int dir) {
        for (int i = from + dir; i >= 0 && i < rows.size(); i += dir) {
            if (rows.get(i) instanceof Channel) return i;
        }
        return -1;
    }

    /** 找到指定频道所在的 row */
    public int rowOf(Channel c) {
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) == c) return i;
        }
        return -1;
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof Header ? TYPE_HEADER : TYPE_CHANNEL;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HEADER) {
            return new HeaderVH(inf.inflate(R.layout.item_header, parent, false));
        }
        return new VH(inf.inflate(R.layout.item_channel, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object o = rows.get(position);
        if (holder instanceof HeaderVH) {
            Header h = (Header) o;
            HeaderVH vh = (HeaderVH) holder;
            vh.name.setText(h.region);
            vh.count.setText(h.count + " 个频道");
            return;
        }
        Channel c = (Channel) o;
        VH h = (VH) holder;
        h.num.setText(String.valueOf(channelIndex(position)));
        h.name.setText(c.name);
        String g = Catalog.groupLabel(c.group, c.name);
        StringBuilder meta = new StringBuilder(g);
        if (c.lineCount() > 1) {
            if (meta.length() > 0) meta.append(" · ");
            meta.append(c.lineCount()).append(" 线路");
        }
        if (meta.length() == 0) {
            h.group.setVisibility(View.GONE);
        } else {
            h.group.setVisibility(View.VISIBLE);
            h.group.setText(meta.toString());
        }
        h.playing.setVisibility(position == playingRow ? View.VISIBLE : View.INVISIBLE);
        // Apple TV 风格：聚焦时白底黑字 + 卡片轻微放大
        boolean focusedNow = h.itemView.isFocused();
        h.name.setTextColor(focusedNow ? 0xFF000000 : 0xFFFFFFFF);
        h.group.setTextColor(focusedNow ? 0x99000000 : 0xFF8E8E93);
        h.num.setTextColor(focusedNow ? 0x66000000 : 0xFF8E8E93);
        h.itemView.setOnFocusChangeListener((v, has) -> {
            h.name.setTextColor(has ? 0xFF000000 : 0xFFFFFFFF);
            h.group.setTextColor(has ? 0x99000000 : 0xFF8E8E93);
            h.num.setTextColor(has ? 0x66000000 : 0xFF8E8E93);
            v.animate().scaleX(has ? 1.04f : 1f).scaleY(has ? 1.04f : 1f).setDuration(130).start();
        });
        h.itemView.setOnClickListener(v -> {
            int p = holder.getAdapterPosition();
            Channel ch = channelAt(p);
            if (ch != null) onPick.onPick(p, ch);
        });
    }

    private int channelIndex(int row) {
        int n = 0;
        for (int i = 0; i <= row && i < rows.size(); i++) {
            if (rows.get(i) instanceof Channel) n++;
        }
        return n;
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    static class Header {
        final String region;
        final int count;

        Header(String region, int count) {
            this.region = region;
            this.count = count;
        }
    }

    static class HeaderVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView count;

        HeaderVH(View v) {
            super(v);
            name = v.findViewById(R.id.header_name);
            count = v.findViewById(R.id.header_count);
        }
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView num;
        final TextView name;
        final TextView group;
        final TextView playing;

        VH(View v) {
            super(v);
            num = v.findViewById(R.id.num);
            name = v.findViewById(R.id.name);
            group = v.findViewById(R.id.group);
            playing = v.findViewById(R.id.playing);
        }
    }
}
