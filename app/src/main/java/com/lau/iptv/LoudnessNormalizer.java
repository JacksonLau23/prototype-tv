package com.lau.iptv;

import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * v1.22 频道音量平衡：在 ExoPlayer 音频链里做实时响度归一化。
 *
 * <p>不同直播源的音频响度基准差异很大（有的 -14 LUFS、有的 -24 LUFS），切台就会忽大忽小。
 * 这里对解码后的 PCM 做近似 K 加权的响度测量，再用「快压慢放」的平滑增益把长期节目响度
 * 拉到统一目标。关键设计：</p>
 * <ul>
 *   <li>只跟踪 2.5 秒量级的<strong>长期</strong>响度，不压平节目内部的动态（对白/掌声/广告）。</li>
 *   <li>静音门限：无声段不更新响度估计，避免把底噪拉爆。</li>
 *   <li>切台<strong>保留当前增益</strong>并进入 1.6 秒快速收敛窗口，避免瞬间突变。</li>
 *   <li>块内增益逐样本线性过渡 + 峰值上限，防止削波与块边界咔哒声。</li>
 *   <li>不支持的编码透明透传，绝不改变音频格式，也不会让播放失败。</li>
 * </ul>
 */
public final class LoudnessNormalizer extends BaseAudioProcessor {

    private static final String TAG = "IPTV";

    /** 目标响度档位（加权 RMS，观感上近似 LUFS） */
    public static final int TARGET_STANDARD = 0;
    public static final int TARGET_SOFT = 1;
    public static final int TARGET_LOUD = 2;
    public static final String[] TARGET_LABELS = {"标准", "柔和", "响亮"};
    private static final float[] TARGET_RMS = {0.12f, 0.09f, 0.16f};
    private static final float[] MAX_GAIN = {3.2f, 2.6f, 4.0f};

    /** 最多压低约 11dB；上限由档位决定，避免把底噪无限放大 */
    private static final float MIN_GAIN = 0.28f;
    /** 低于此响度视为静音/底噪，不参与估计 */
    private static final float SILENCE_RMS = 0.004f;
    private static final float PEAK_CEILING = 0.97f;
    private static final float HARD_LIMIT = 0.99f;
    /** 长期响度积分时间常数（秒） */
    private static final float SLOW_TAU = 2.5f;
    /** 近似 K 加权：一阶高通去次低频 + 高频搁架提升 */
    private static final float SHELF = 0.45f;

    private volatile boolean enabled = true;
    private volatile int targetMode = TARGET_STANDARD;
    private volatile boolean configured = false;

    private int sampleRate;
    private int channels;
    private int encoding;
    private boolean supported;

    private float[] dcX;
    private float[] dcY;
    private float[] lpState;
    private float rHp;
    private float aLp;

    private float gain = 1f;
    private float lastAppliedGain = 1f;
    private float slowLoudness = 0f;
    private boolean primed = false;
    private long fastUntilMs = 0;
    private long lastLogMs = 0;

    // ===================== 对外控制 =====================

    public void setEnabled(boolean on) {
        this.enabled = on;
        Log.i(TAG, "VOLNORM_ENABLED " + on);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setTargetMode(int mode) {
        this.targetMode = (mode >= 0 && mode < TARGET_RMS.length) ? mode : TARGET_STANDARD;
        // 换档后重新收敛，但保留增益避免突变
        this.fastUntilMs = System.currentTimeMillis() + 1200;
        Log.i(TAG, "VOLNORM_TARGET " + TARGET_LABELS[this.targetMode]);
    }

    public int getTargetMode() {
        return targetMode;
    }

    /** 当前增益（dB），用于状态显示 / 诊断 */
    public float getGainDb() {
        return (float) (20 * Math.log10(Math.max(0.01f, gain)));
    }

    /** 切台：重置测量但保留增益，让新频道在 1.6 秒内平滑收敛 */
    public void onChannelChanged() {
        primed = false;
        slowLoudness = 0f;
        fastUntilMs = System.currentTimeMillis() + 1600;
        if (dcX != null) {
            Arrays.fill(dcX, 0f);
            Arrays.fill(dcY, 0f);
            Arrays.fill(lpState, 0f);
        }
    }

    // ===================== AudioProcessor =====================

    @Override
    protected AudioProcessor.AudioFormat onConfigure(AudioProcessor.AudioFormat in)
            throws AudioProcessor.UnhandledAudioFormatException {
        sampleRate = in.sampleRate;
        channels = Math.max(1, Math.min(8, in.channelCount));
        encoding = in.encoding;
        supported = sampleRate > 0
                && (encoding == C.ENCODING_PCM_16BIT
                || encoding == C.ENCODING_PCM_FLOAT
                || encoding == C.ENCODING_PCM_8BIT);
        int fs = Math.max(8000, sampleRate);
        rHp = (float) Math.exp(-2 * Math.PI * 60.0 / fs);
        aLp = 1f - (float) Math.exp(-2 * Math.PI * 2200.0 / fs);
        dcX = new float[channels];
        dcY = new float[channels];
        lpState = new float[channels];
        configured = true;
        Log.i(TAG, "VOLNORM_CONFIGURE rate=" + sampleRate + " ch=" + channels
                + " enc=" + encoding + " supported=" + supported);
        // 返回原格式：不改变采样率/声道/编码，链路上其他处理器不受影响
        return in;
    }

    /** 恒为 active：关闭开关时透明透传，避免运行时切换需要重建音频链 */
    @Override
    public boolean isActive() {
        return configured;
    }

    @Override
    protected void onReset() {
        configured = false;
        gain = 1f;
        lastAppliedGain = 1f;
        slowLoudness = 0f;
        primed = false;
        dcX = dcY = lpState = null;
    }

    @Override
    public void queueInput(ByteBuffer inputBuffer) {
        if (!configured || !inputBuffer.hasRemaining()) {
            replaceOutputBuffer(0).flip();
            return;
        }
        ByteBuffer out = replaceOutputBuffer(inputBuffer.remaining());
        out.order(inputBuffer.order());
        if (!enabled || !supported) {
            out.put(inputBuffer);
            out.flip();
            return;
        }
        switch (encoding) {
            case C.ENCODING_PCM_16BIT:
                processPcm16(inputBuffer, out);
                break;
            case C.ENCODING_PCM_FLOAT:
                processPcmFloat(inputBuffer, out);
                break;
            case C.ENCODING_PCM_8BIT:
                processPcm8(inputBuffer, out);
                break;
            default:
                out.put(inputBuffer);
                break;
        }
        out.flip();
    }

    // ===================== PCM 处理 =====================

    private void processPcm16(ByteBuffer in, ByteBuffer out) {
        int n = in.remaining() / 2;
        float blockGain = measureInternal(in, n, 1);
        float start = lastAppliedGain;
        for (int i = 0; i < n; i++) {
            float g = start + (blockGain - start) * (i + 1) / (float) n;
            float y = (in.getShort() / 32768f) * g;
            out.putShort((short) Math.round(clamp(y, -HARD_LIMIT, HARD_LIMIT) * 32767f));
        }
        lastAppliedGain = blockGain;
    }

    private void processPcmFloat(ByteBuffer in, ByteBuffer out) {
        int n = in.remaining() / 4;
        float blockGain = measureInternal(in, n, 2);
        float start = lastAppliedGain;
        for (int i = 0; i < n; i++) {
            float g = start + (blockGain - start) * (i + 1) / (float) n;
            out.putFloat(clamp(in.getFloat() * g, -HARD_LIMIT, HARD_LIMIT));
        }
        lastAppliedGain = blockGain;
    }

    private void processPcm8(ByteBuffer in, ByteBuffer out) {
        int n = in.remaining();
        float blockGain = measureInternal(in, n, 3);
        float start = lastAppliedGain;
        for (int i = 0; i < n; i++) {
            float g = start + (blockGain - start) * (i + 1) / (float) n;
            float x = ((in.get() & 0xFF) - 128) / 128f;
            float y = clamp(x * g, -HARD_LIMIT, HARD_LIMIT);
            out.put((byte) Math.round(y * 127f + 128f));
        }
        lastAppliedGain = blockGain;
    }

    /**
     * 第一遍：近似 K 加权测响度 + 找峰值，并推进增益状态。
     * 用 duplicate 只读，不消耗输入缓冲区。kind: 1 = 16bit, 2 = float, 3 = 8bit。
     */
    private float measureInternal(ByteBuffer in, int n, int kind) {
        ByteBuffer m = in.duplicate();
        m.order(in.order());
        double sumSq = 0;
        float peak = 0;
        int ch = 0;
        for (int i = 0; i < n; i++) {
            float x;
            if (kind == 1) x = m.getShort() / 32768f;
            else if (kind == 2) x = m.getFloat();
            else x = ((m.get() & 0xFF) - 128) / 128f;
            float w = weight(x, ch);
            ch++;
            if (ch >= channels) ch = 0;
            sumSq += (double) w * w;
            float ax = Math.abs(x);
            if (ax > peak) peak = ax;
        }
        float measured = (float) Math.sqrt(sumSq / Math.max(1, n));
        int frames = Math.max(1, n / channels);
        return computeGain(measured, peak, frames / (float) Math.max(1, sampleRate));
    }

    /** 近似 K 加权：DC blocker 去次低频 + 一阶低通分频做高频搁架提升 */
    private float weight(float x, int ch) {
        float hp = x - dcX[ch] + rHp * dcY[ch];
        dcX[ch] = x;
        dcY[ch] = hp;
        float low = lpState[ch] + aLp * (hp - lpState[ch]);
        lpState[ch] = low;
        return hp + SHELF * (hp - low);
    }

    /** 长期响度 → 目标增益 → 快压慢放平滑 → 峰值保护 */
    private float computeGain(float measured, float peak, float dt) {
        long now = System.currentTimeMillis();
        if (measured >= SILENCE_RMS) {
            if (!primed) {
                slowLoudness = measured;
                primed = true;
            } else {
                float a = 1f - (float) Math.exp(-dt / SLOW_TAU);
                slowLoudness += a * (measured - slowLoudness);
            }
        }
        if (!primed) return lastAppliedGain;

        int mode = targetMode;
        float desired = clamp(TARGET_RMS[mode] / Math.max(slowLoudness, 0.001f),
                MIN_GAIN, MAX_GAIN[mode]);
        float fast = now < fastUntilMs ? 0.45f : 1f;
        float tau = (desired < gain ? 0.12f : 1.5f) * fast;
        float a = 1f - (float) Math.exp(-dt / Math.max(0.01f, tau));
        gain = clamp((float) (gain * Math.exp(Math.log(desired / gain) * a)),
                MIN_GAIN, MAX_GAIN[mode]);

        float blockGain = Math.min(gain, PEAK_CEILING / Math.max(peak, 0.001f));
        if (blockGain < MIN_GAIN) blockGain = MIN_GAIN;

        if (now - lastLogMs > 5000) {
            lastLogMs = now;
            Log.d(TAG, "VOLNORM rms=" + String.format("%.4f", measured)
                    + " slow=" + String.format("%.4f", slowLoudness)
                    + " gain=" + String.format("%.1f", getGainDb()) + "dB"
                    + " peak=" + String.format("%.2f", peak)
                    + " enabled=" + enabled);
        }
        return blockGain;
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
