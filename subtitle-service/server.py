#!/usr/bin/env python3
"""Prototype TV 实时简中字幕旁路服务（v1.21 MVP，仅标准库 + ffmpeg）。

电视只连局域网，由本服务拉同一路直播流、抽音频、识别、翻译，
再通过 SSE 把带时间戳的双语字幕推给电视端 SubtitleClient。

接口：
  GET  /health                 状态自检
  GET  /events                 SSE 长连接，事件 JSON 在 data: 行
  POST /session                {"url": "...", "channel": "...", "enabled": true}
  GET  /                       状态 JSON

启动： python3 subtitle-service/server.py
自检： python3 subtitle-service/selftest.py
"""

import argparse
import array
import json
import math
import os
import queue
import shutil
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from providers import make_provider, provider_info  # noqa: E402

PORT = int(os.getenv("SUBTITLE_PORT", "8899"))
RATE = 16000
BYTES_PER_SEC = RATE * 2
CHUNK_SEC = float(os.getenv("SUBTITLE_CHUNK_SEC", "2.0"))
CHUNK_BYTES = int(BYTES_PER_SEC * CHUNK_SEC)
VAD_RMS = float(os.getenv("SUBTITLE_VAD_RMS", "120"))
CUE_DUR_MS = int(os.getenv("SUBTITLE_CUE_MS", "3200"))
FRAME_BYTES = 640  # 20ms @ 16k mono s16le
# 诊断用：忽略电视传来的 URL，改用本机可读的音频（例如自检 wav），
# 便于在拉不到直播流的环境里验证「服务端 → 电视 → 浮层」链路。
FORCE_URL = os.getenv("SUBTITLE_FORCE_URL", "").strip()


def audio_rms(pcm):
    a = array.array("h")
    a.frombytes(pcm[: len(pcm) - (len(pcm) % 2)])
    samples = a[::8]
    if not samples:
        return 0.0
    return math.sqrt(sum(v * v for v in samples) / len(samples))


class Hub:
    def __init__(self):
        self.lock = threading.RLock()
        self.clients = set()
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="asr")
        self.provider = make_provider()
        self.session = 0
        self.url = ""
        self.channel = ""
        self.started_at = 0.0
        self.proc = None
        self.stop_evt = threading.Event()
        self.cue_count = 0
        self.last_error = ""
        # 新 SSE 客户端连上时补发最近一次 reset，避免切台事件在重连窗口丢失
        self.last_reset = ""

    def subscribe(self):
        q = queue.Queue(maxsize=256)
        with self.lock:
            self.clients.add(q)
            if self.last_reset:
                try:
                    q.put_nowait(self.last_reset)
                except queue.Full:
                    pass
        return q

    def unsubscribe(self, q):
        with self.lock:
            self.clients.discard(q)

    def broadcast(self, obj):
        data = json.dumps(obj, ensure_ascii=False)
        with self.lock:
            if obj.get("type") == "reset":
                self.last_reset = data
            targets = list(self.clients)
        for q in targets:
            try:
                q.put_nowait(data)
            except queue.Full:
                pass

    def set_session(self, url, channel, enabled=True):
        with self.lock:
            self.stop_current_locked()
            self.session += 1
            sid = self.session
            self.url = FORCE_URL or (url or "")
            self.channel = channel or ""
            self.started_at = time.time()
            self.cue_count = 0
            self.last_error = ""
            self.stop_evt = threading.Event()
            stop = self.stop_evt
            if not enabled or not self.url:
                self.broadcast({"type": "reset", "session": sid, "reason": "disabled"})
                return sid
            worker = threading.Thread(
                target=self._run, args=(sid, self.url, self.channel, stop),
                name="ffmpeg-%d" % sid, daemon=True)
        worker.start()
        self.broadcast({"type": "reset", "session": sid, "channel": channel})
        return sid

    def stop_current_locked(self):
        self.stop_evt.set()
        p = self.proc
        if p is not None:
            try:
                p.kill()
            except Exception:
                pass
        self.proc = None

    def _fail(self, sid, message):
        with self.lock:
            if sid != self.session:
                return
            self.last_error = message
        print("[subtitle] " + message, flush=True)
        self.broadcast({"type": "error", "session": sid, "message": message})

    def _run(self, sid, url, channel, stop):
        cmd = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin"]
        if os.path.exists(url):
            cmd += ["-stream_loop", "-1", "-re"]  # 本地文件循环、按实时速率输出
        cmd += ["-rw_timeout", "15000000", "-i", url,
                "-vn", "-ac", "1", "-ar", str(RATE), "-f", "s16le", "pipe:1"]
        try:
            proc = subprocess.Popen(cmd, stdout=subprocess.PIPE,
                                    stderr=subprocess.PIPE, bufsize=0)
        except Exception as exc:
            self._fail(sid, "ffmpeg 启动失败: %s" % exc)
            return
        with self.lock:
            if stop.is_set() or sid != self.session:
                proc.kill()
                return
            self.proc = proc

        buf = bytearray()
        t0 = time.time()
        index = 0
        while not stop.is_set():
            raw = proc.stdout.read(FRAME_BYTES)
            if not raw:
                break
            buf.extend(raw)
            if len(buf) >= CHUNK_BYTES:
                chunk = bytes(buf[:CHUNK_BYTES])
                del buf[:CHUNK_BYTES]
                index += 1
                pts_ms = int((time.time() - t0) * 1000)
                self._handle_chunk(sid, index, pts_ms, chunk, channel, stop)
        try:
            proc.kill()
        except Exception:
            pass
        with self.lock:
            if self.proc is proc:
                self.proc = None

    def _handle_chunk(self, sid, index, pts_ms, pcm, channel, stop):
        rms = audio_rms(pcm)
        if rms < VAD_RMS and not self.provider.always_emit():
            return

        def job():
            try:
                result = self.provider.transcribe(pcm, channel)
            except Exception as exc:
                self._fail(sid, "识别失败: %s" % exc)
                return
            src = (result.get("src") or "").strip()
            zh = (result.get("zh") or "").strip()
            if not src and not zh:
                return
            with self.lock:
                if sid != self.session or stop.is_set():
                    return
                self.cue_count += 1
            self.broadcast({
                "type": "cue", "session": sid, "index": index,
                "ptsMs": pts_ms, "durMs": CUE_DUR_MS,
                "src": src, "zh": zh,
                "mode": result.get("mode", self.provider.mode),
                "channel": channel,
            })

        self.pool.submit(job)

    def state(self):
        with self.lock:
            return {
                "ok": True, "session": self.session, "channel": self.channel,
                "url": self.url[:120], "clients": len(self.clients),
                "cues": self.cue_count, "provider": self.provider.name,
                "mode": self.provider.mode, "lastError": self.last_error,
                "uptimeSec": int(time.time() - self.started_at) if self.started_at else 0,
                "config": provider_info(),
                "forceUrl": bool(FORCE_URL),
            }


HUB = Hub()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PTVSubtitle/1.21"

    def log_message(self, fmt, *args):
        if os.getenv("SUBTITLE_VERBOSE"):
            super().log_message(fmt, *args)

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

    def _json(self, obj, code=200):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Content-Length", "0")
        self._cors()
        self.end_headers()

    def do_GET(self):
        path = urlparse(self.path).path
        if path in ("/", "/health"):
            self._json(HUB.state())
        elif path == "/events":
            self._sse()
        else:
            self._json({"ok": False, "error": "not found"}, 404)

    def do_POST(self):
        if urlparse(self.path).path != "/session":
            self._json({"ok": False, "error": "not found"}, 404)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = 0
        raw = self.rfile.read(length) if length else b"{}"
        try:
            payload = json.loads(raw.decode("utf-8"))
        except Exception:
            payload = {}
        sid = HUB.set_session(
            str(payload.get("url", "")),
            str(payload.get("channel", "")),
            bool(payload.get("enabled", True)))
        print("[subtitle] session=%d channel=%s url=%s"
              % (sid, payload.get("channel", ""), str(payload.get("url", ""))[:100]),
              flush=True)
        self._json({"ok": True, "session": sid})

    def _sse(self):
        # 手写 HTTP/1.0 纯文本流：不带 chunked / Content-Length，读到连接关闭为止。
        # 老电视的 OkHttp 在进程反复 bindProcessToNetwork 时会把 chunked 长流判为
        # "unexpected end of stream"；HTTP/1.0 + Connection: close 最兼容。
        q = HUB.subscribe()
        self.log_message('"GET /events HTTP/1.0" 200 -')
        try:
            head = ("HTTP/1.0 200 OK\r\n"
                    "Content-Type: text/event-stream; charset=utf-8\r\n"
                    "Cache-Control: no-cache, no-transform\r\n"
                    "Connection: close\r\n"
                    "Access-Control-Allow-Origin: *\r\n\r\n")
            self.wfile.write(head.encode("ascii"))
            self.wfile.write(b": connected\n\n")
            self.wfile.flush()
            while True:
                try:
                    data = q.get(timeout=10)
                except queue.Empty:
                    self.wfile.write(b": keepalive\n\n")
                    self.wfile.flush()
                    continue
                self.wfile.write(("data: " + data + "\n\n").encode("utf-8"))
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError, OSError):
            pass
        finally:
            self.close_connection = True
            HUB.unsubscribe(q)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True

    def handle_error(self, request, client_address):
        # 代理/连接池的预连接会在发请求前断开，忽略这类探测噪声
        exc = sys.exc_info()[1]
        if isinstance(exc, OSError) and exc.errno in (57, 54, 104, 32):
            return
        import traceback
        traceback.print_exc()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=PORT)
    ap.add_argument("--host", default="0.0.0.0")
    args = ap.parse_args()
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        print("找不到 ffmpeg，请先安装：brew install ffmpeg", file=sys.stderr)
        return 1
    # 电视上 bindProcessToNetwork 常被拒（allowBypass denied），局域网流量会被
    # Clash tun 接管；macOS 防火墙也会挡住非 loopback 访问。用 adb reverse 把电视的
    # 127.0.0.1:PORT 映射到本机，字幕链路就不依赖代理分流与防火墙放行。
    if os.getenv("SUBTITLE_ADB_REVERSE", "1") == "1":
        adb = os.getenv("ANDROID_ADB") or os.path.expanduser("~/android-sdk/platform-tools/adb")
        if not os.path.exists(adb):
            adb = shutil.which("adb") or ""
        if adb:
            try:
                r = subprocess.run([adb, "reverse", "tcp:%d" % args.port, "tcp:%d" % args.port],
                                   capture_output=True, text=True, timeout=6)
                if r.returncode == 0:
                    print("[subtitle] adb reverse 就绪：电视填 http://127.0.0.1:%d" % args.port, flush=True)
                else:
                    print("[subtitle] adb reverse 跳过：%s"
                          % (r.stderr or r.stdout).strip()[:120], flush=True)
            except Exception as exc:
                print("[subtitle] adb reverse 跳过：%s" % exc, flush=True)
        else:
            print("[subtitle] 未找到 adb，跳过 reverse（可在电视菜单里填局域网地址）", flush=True)

    srv = Server((args.host, args.port), Handler)
    print("[subtitle] ffmpeg=%s provider=%s chunk=%.1fs"
          % (ffmpeg, HUB.provider.name, CHUNK_SEC), flush=True)
    print("[subtitle] SSE  http://<本机IP>:%d/events" % args.port, flush=True)
    print("[subtitle] POST http://<本机IP>:%d/session" % args.port, flush=True)
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        HUB.stop_evt.set()
        srv.server_close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
