#!/usr/bin/env python3
"""端到端自检：不依赖外网与电视，验证 ffmpeg 抽音频 → VAD → provider → SSE 全链路。"""

import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SERVER = os.path.join(HERE, "server.py")


def free_port():
    s = socket.socket()
    s.bind(("127.0.0.1", 0))
    p = s.getsockname()[1]
    s.close()
    return p


def wait_health(base, timeout=15):
    deadline = time.time() + timeout
    while time.time() < deadline:
        try:
            with urllib.request.urlopen(base + "/health", timeout=1) as r:
                if json.loads(r.read().decode("utf-8"))["ok"]:
                    return True
        except Exception:
            time.sleep(0.2)
    return False


def main():
    if shutil.which("ffmpeg") is None:
        print("FAIL 找不到 ffmpeg")
        return 1
    port = free_port()
    base = "http://127.0.0.1:%d" % port
    tmp = tempfile.mkdtemp(prefix="ptv-subtitle-")
    wav = os.path.join(tmp, "test.wav")
    subprocess.run(["ffmpeg", "-y", "-hide_banner", "-loglevel", "error",
                    "-f", "lavfi", "-i", "sine=frequency=440:duration=8",
                    "-ac", "1", "-ar", "16000", wav], check=True)

    env = dict(os.environ, SUBTITLE_PORT=str(port), SUBTITLE_PROVIDER="demo",
               SUBTITLE_CHUNK_SEC="1.0", SUBTITLE_VAD_RMS="1")
    proc = subprocess.Popen([sys.executable, SERVER], env=env,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True)
    code = 1
    try:
        if not wait_health(base):
            print("FAIL 服务未就绪")
            return 1
        health = json.loads(urllib.request.urlopen(base + "/health", timeout=3).read().decode("utf-8"))
        print("OK   /health provider=%s mode=%s" % (health["provider"], health["mode"]))

        payload = json.dumps({"url": wav, "channel": "自检频道", "enabled": True}).encode("utf-8")
        req = urllib.request.Request(base + "/session", data=payload,
                                     headers={"Content-Type": "application/json"},
                                     method="POST")
        session = json.loads(urllib.request.urlopen(req, timeout=5).read().decode("utf-8"))["session"]
        print("OK   /session session=%d" % session)

        cues = []
        resets = 0
        deadline = time.time() + 15
        with urllib.request.urlopen(base + "/events", timeout=15) as resp:
            while time.time() < deadline and len(cues) < 4:
                line = resp.readline().decode("utf-8").strip()
                if not line or line.startswith(":"):
                    continue
                if not line.startswith("data: "):
                    continue
                msg = json.loads(line[6:])
                if msg.get("type") == "reset":
                    resets += 1
                elif msg.get("type") == "cue":
                    cues.append(msg)
        if resets < 1:
            print("FAIL 缺少 reset 事件（切台清空队列信号）")
            return 1
        print("OK   /events reset=%d cue=%d" % (resets, len(cues)))

        assert len(cues) >= 4, "字幕事件不足 4 条"
        assert all(c["session"] == session for c in cues), "session 串台"
        assert all(c["src"] and c["zh"] for c in cues), "缺少原文或译文"
        assert all(c["ptsMs"] >= 0 and c["durMs"] > 0 for c in cues), "时间戳非法"
        assert all(c["mode"] == "demo" for c in cues), "provider 模式异常"
        assert [c["index"] for c in cues] == sorted(c["index"] for c in cues), "切片序号乱序"
        print("OK   cue 字段、session 隔离、时间戳、序号全部通过")
        for c in cues:
            print("     pts=%5dms  %s | %s" % (c["ptsMs"], c["src"], c["zh"]))

        final = json.loads(urllib.request.urlopen(base + "/health", timeout=3).read().decode("utf-8"))
        print("OK   /health cues=%d lastError=%r" % (final["cues"], final["lastError"]))
        code = 0
    except Exception as exc:
        print("FAIL %s: %s" % (type(exc).__name__, exc))
    finally:
        proc.terminate()
        try:
            out = proc.communicate(timeout=3)[0]
        except subprocess.TimeoutExpired:
            proc.kill()
            out = proc.communicate()[0]
        if code != 0 and out:
            print("--- server output ---\n" + out[-2000:])
        shutil.rmtree(tmp, ignore_errors=True)
    print("PASS 服务端旁路链路自检通过" if code == 0 else "FAIL 自检未通过")
    return code


if __name__ == "__main__":
    sys.exit(main())
