#!/usr/bin/env python3
"""可插拔的 ASR / 机器翻译 provider。

v1.21 MVP 默认使用 demo provider：音频抽帧与 VAD 是真实链路，
但字幕文本是固定样例，用来验证「服务端 → 电视 → 时钟对齐 → 浮层」全链路。
接入真实识别只改环境变量，不用动 App。

环境变量：
  SUBTITLE_PROVIDER   demo | whisper | cloud       （默认 demo）
  SUBTITLE_ASR_URL    OpenAI 兼容 /v1/audio/transcriptions 地址（cloud）
  SUBTITLE_ASR_KEY    该端点 Bearer Key（cloud）
  SUBTITLE_ASR_MODEL  模型名，例如 FunAudioLLM/SenseVoiceSmall（cloud）
  SUBTITLE_ASR_LANG   源语言提示，默认 auto
  SUBTITLE_MT_URL     OpenAI 兼容 /v1/chat/completions 地址
  SUBTITLE_MT_KEY     Bearer Key
  SUBTITLE_MT_MODEL   翻译模型名，例如 qwen-mt-turbo / doubao-seed-translation
  SUBTITLE_WHISPER_MODEL  tiny | base | small（whisper，默认 tiny）
"""

import io
import json
import os
import struct
import urllib.request
import uuid

DEMO_LINES = [
    ("Good evening, and welcome to our live coverage.", "晚上好，欢迎收看我们的现场报道。"),
    ("We are following the latest developments from the studio.", "我们正在跟进演播室传来的最新进展。"),
    ("The match is level at one goal apiece with ten minutes left.", "比赛还剩十分钟，双方一比一战平。"),
    ("Our correspondent joins us now with an update from the scene.", "现在连线现场记者，带来最新情况。"),
    ("Stay with us, we will be right back after this short break.", "请稍候，短暂广告之后马上回来。"),
    ("Thanks for watching, and have a wonderful evening.", "感谢收看，祝您晚安。"),
]


class Provider:
    name = "base"
    mode = "none"

    def always_emit(self):
        """静音切片是否也要产出字幕（demo 需要，真实 ASR 不需要）。"""
        return False

    def transcribe(self, pcm, channel):
        return {"src": "", "zh": "", "mode": self.mode}


class DemoProvider(Provider):
    name = "demo"
    mode = "demo"

    def __init__(self):
        self.index = 0

    def always_emit(self):
        return True

    def transcribe(self, pcm, channel):
        src, zh = DEMO_LINES[self.index % len(DEMO_LINES)]
        self.index += 1
        return {"src": src, "zh": zh, "mode": self.mode}


def _wav_bytes(pcm, rate=16000):
    """16k / 16bit / mono PCM -> WAV，供语音识别端点使用。"""
    out = io.BytesIO()
    data_len = len(pcm)
    out.write(b"RIFF")
    out.write(struct.pack("<I", 36 + data_len))
    out.write(b"WAVEfmt ")
    out.write(struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16))
    out.write(b"data")
    out.write(struct.pack("<I", data_len))
    out.write(pcm)
    return out.getvalue()


def _post_json(url, key, payload, timeout=10):
    headers = {"Content-Type": "application/json"}
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(
        url, data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def _post_multipart(url, key, fields, file_field, filename, file_bytes, timeout=15):
    boundary = "----ptv" + uuid.uuid4().hex
    buf = io.BytesIO()
    for k, v in fields.items():
        buf.write(("--" + boundary + "\r\n").encode())
        buf.write(('Content-Disposition: form-data; name="%s"\r\n\r\n' % k).encode())
        buf.write((str(v) + "\r\n").encode())
    buf.write(("--" + boundary + "\r\n").encode())
    buf.write(('Content-Disposition: form-data; name="%s"; filename="%s"\r\n'
               % (file_field, filename)).encode())
    buf.write(b"Content-Type: audio/wav\r\n\r\n")
    buf.write(file_bytes)
    buf.write(("\r\n--" + boundary + "--\r\n").encode())
    headers = {"Content-Type": "multipart/form-data; boundary=" + boundary}
    if key:
        headers["Authorization"] = "Bearer " + key
    req = urllib.request.Request(url, data=buf.getvalue(), headers=headers, method="POST")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        return json.loads(resp.read().decode("utf-8"))


def translate_to_zh(text):
    """调 OpenAI 兼容的翻译端点；未配置时返回 None，由调用方决定回显策略。"""
    url = os.getenv("SUBTITLE_MT_URL", "").strip()
    if not url or not text.strip():
        return None
    payload = {
        "model": os.getenv("SUBTITLE_MT_MODEL", "qwen-mt-turbo"),
        "messages": [
            {"role": "system", "content": "把用户输入的直播字幕翻译成自然、简洁的简体中文，只输出译文，不要解释。"},
            {"role": "user", "content": text},
        ],
        "temperature": 0.2,
    }
    try:
        r = _post_json(url, os.getenv("SUBTITLE_MT_KEY", ""), payload)
        return (r.get("choices", [{}])[0].get("message", {}).get("content") or "").strip() or None
    except Exception as exc:
        return "[翻译失败: %s]" % type(exc).__name__


def _recognize_cloud(pcm):
    url = os.getenv("SUBTITLE_ASR_URL", "").strip()
    if not url:
        return ""
    fields = {
        "model": os.getenv("SUBTITLE_ASR_MODEL", "FunAudioLLM/SenseVoiceSmall"),
        "language": os.getenv("SUBTITLE_ASR_LANG", "auto"),
        "response_format": "json",
    }
    r = _post_multipart(url, os.getenv("SUBTITLE_ASR_KEY", ""), fields,
                        "file", "chunk.wav", _wav_bytes(pcm))
    return (r.get("text") or "").strip()


class CloudProvider(Provider):
    """ASR 走 OpenAI 兼容 /audio/transcriptions，翻译走 /chat/completions。"""

    name = "cloud"
    mode = "cloud"

    def transcribe(self, pcm, channel):
        try:
            src = _recognize_cloud(pcm)
        except Exception as exc:
            return {"src": "", "zh": "[识别失败: %s]" % type(exc).__name__, "mode": self.mode}
        if not src:
            return {"src": "", "zh": "", "mode": self.mode}
        zh = translate_to_zh(src) or ("[未配置翻译] " + src)
        return {"src": src, "zh": zh, "mode": self.mode}


class WhisperProvider(Provider):
    """本机 faster-whisper（CPU int8）+ 可选翻译端点；未安装时给出明确提示。"""

    name = "whisper"
    mode = "whisper-local"

    def __init__(self):
        from faster_whisper import WhisperModel
        size = os.getenv("SUBTITLE_WHISPER_MODEL", "tiny")
        self.model = WhisperModel(size, device="cpu", compute_type="int8")

    def transcribe(self, pcm, channel):
        import numpy as np
        audio = np.frombuffer(pcm, dtype=np.int16).astype(np.float32) / 32768.0
        segments, info = self.model.transcribe(
            audio, beam_size=1, vad_filter=True,
            condition_on_previous_text=False)
        src = "".join(s.text for s in segments).strip()
        if not src:
            return {"src": "", "zh": "", "mode": self.mode}
        zh = translate_to_zh(src) or ("[未配置翻译] " + src)
        return {"src": src, "zh": zh, "mode": self.mode}


def make_provider():
    name = os.getenv("SUBTITLE_PROVIDER", "demo").strip().lower()
    if name == "whisper":
        try:
            return WhisperProvider()
        except Exception as exc:
            print("[subtitle] faster-whisper 不可用 (%s)，回退 demo" % exc, flush=True)
            return DemoProvider()
    if name == "cloud":
        if not os.getenv("SUBTITLE_ASR_URL", "").strip():
            print("[subtitle] 未设置 SUBTITLE_ASR_URL，回退 demo", flush=True)
            return DemoProvider()
        return CloudProvider()
    return DemoProvider()


def provider_info():
    return {"configured": os.getenv("SUBTITLE_PROVIDER", "demo").lower(),
            "asr_url": bool(os.getenv("SUBTITLE_ASR_URL", "").strip()),
            "mt_url": bool(os.getenv("SUBTITLE_MT_URL", "").strip())}
