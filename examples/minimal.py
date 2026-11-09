"""最小示例：绑定玩家、跟随、说话。

用法：python examples/minimal.py [身体玩家名] [跟随玩家名]
"""
import json
import sys
import urllib.request

BASE = "http://127.0.0.1:3232"


def post(path: str, payload: dict | None = None) -> str:
    data = json.dumps(payload or {}, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(BASE + path, data=data, method="POST")
    req.add_header("Content-Type", "application/json; charset=utf-8")
    with urllib.request.urlopen(req, timeout=8) as r:
        return r.read().decode("utf-8")


def status() -> str:
    with urllib.request.urlopen(BASE + "/status", timeout=8) as r:
        return r.read().decode("utf-8")


body = sys.argv[1] if len(sys.argv) > 1 else "Companion"
owner = sys.argv[2] if len(sys.argv) > 2 else "YourName"

print("status :", status())
print("bind   :", post("/player", {"name": body}))
print("follow :", post("/follow", {"name": owner}))
print("say    :", post("/say", {"text": "我来了，你在哪"}))
print("status :", status())
