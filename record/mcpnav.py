# -*- coding: utf-8 -*-
"""录制准备用 MCP 客户端 —— 按绝对路径精确打开文件，替代 Ctrl+Shift+N + 剪贴板 + 方向键。

**为什么要有这个**（2026-10-25 实测）：`Ctrl+Shift+N` 那条路在 demo 工程里会撞上
一堆同名文件，本轮实测踩了三次：
  - 搜 `UserMapper.xml` 开成了 `NativeUserMapper.xml`
  - 搜 `UserMapper.java` 开成了 `mpjdemo` 模块的同名类（内容是 `SELECT SLEEP(1.2)`）
  - 搜 `UserRepository.java` 后按两次方向键，落到了 `UserMapper.java`
每次都要靠截图目视发现再重来，单张图平均浪费 2~3 轮。

MCP 的 `open_file_in_editor` 直接吃 `filePath`，一次到位、零歧义。

**前置**（一次性，需用户授权）：
  IDE 设置 → Tools → MCP Server → 勾选 Enable MCP Server（会弹权限确认框，
  授予「读取所有打开项目文件 + 触发 IDE 动作 + 执行终端命令」）→ OK。
  启用后端点固定为 `http://127.0.0.1:64342/sse`（**端口从 64442 变成 64342**）。
  未启用时端点返回 401；启用后自动带 token，**不需要手工取 token**。

**协议**：标准 MCP SSE——先 `GET /sse` 拿 `sessionId` 与 `/message?sessionId=...`，
再向该地址 POST `initialize` → `notifications/initialized` → `tools/call`。

**用法**：
    from mcpnav import open_file
    open_file("mybatis-plus-demo/src/main/resources/mapper/UserMapper.xml")
    # 然后照常 goto_line / Alt+Shift+Q —— MCP 不替代跳行（它没有行号参数）

命令行：
    python mcpnav.py open <项目内相对路径>
    python mcpnav.py paths          # 列出当前打开的文件
"""
import json
import os
import queue
import sys
import threading
import time
import urllib.parse
import urllib.request

BASE = os.environ.get("IDEA_MCP_BASE", "http://127.0.0.1:64342")
PROJECT = os.environ.get(
    "IDEA_MCP_PROJECT",
    "F:/code/Java/Gitee/SongRenShuo/mybatis-plus-sql-visualizer-demo",
)

_session_id = None
_responses = queue.Queue()


def _sse_loop():
    global _session_id
    req = urllib.request.Request(BASE + "/sse", headers={"Accept": "text/event-stream"})
    with urllib.request.urlopen(req, timeout=180) as resp:
        buf = b""
        while True:
            chunk = resp.read(1)
            if not chunk:
                break
            buf += chunk
            while b"\n" in buf:
                line, buf = buf.split(b"\n", 1)
                line = line.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if _session_id is None and "message?sessionId=" in data:
                    _session_id = data.split("sessionId=", 1)[1]
                    continue
                try:
                    msg = json.loads(data)
                except Exception:
                    continue
                if "id" in msg and "method" not in msg:
                    _responses.put(msg)


def _post(payload):
    url = BASE + "/message?sessionId=" + urllib.parse.quote(_session_id)
    body = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}, method="POST"
    )
    urllib.request.urlopen(req, timeout=20).read()


class _Session:
    def __init__(self):
        self._tid = 0

    def start(self):
        threading.Thread(target=_sse_loop, daemon=True).start()
        for _ in range(120):
            if _session_id:
                break
            time.sleep(0.1)
        if not _session_id:
            raise RuntimeError(
                f"MCP SSE 未返回 sessionId —— IDE 的 MCP Server 是否已启用？端点 {BASE}"
            )
        self.rpc("initialize", {
            "protocolVersion": "2024-11-05",
            "capabilities": {},
            "clientInfo": {"name": "gif-record", "version": "1.0"},
        })
        _post({"jsonrpc": "2.0", "method": "notifications/initialized"})
        return self

    def rpc(self, method, params=None):
        self._tid += 1
        mid = self._tid
        _post({"jsonrpc": "2.0", "id": mid, "method": method, "params": params or {}})
        deadline = time.time() + 25
        while time.time() < deadline:
            try:
                msg = _responses.get(timeout=1)
            except queue.Empty:
                continue
            if msg.get("id") == mid:
                return msg
        raise RuntimeError(f"MCP 调用超时: {method}")

    def call(self, tool, args):
        r = self.rpc("tools/call", {"name": tool, "arguments": args})
        res = r.get("result", {})
        text = "\n".join(
            c.get("text", "") for c in res.get("content", []) if isinstance(c, dict)
        )
        if res.get("isError"):
            raise RuntimeError(f"MCP {tool} 失败: {text[:300]}")
        return text


_cache = {}


def _set_base(base):
    """切换 MCP 端点并复位会话状态（_sse_loop/_post 读模块级 BASE）。"""
    global BASE, _session_id
    BASE = base
    _session_id = None
    with _responses.mutex:
        _responses.queue.clear()


def _detect_base():
    """探测沙盒 IDE 的 MCP 端口。

    端口每次沙盒重启都会漂（20261026：日常 IDE 占 64342，沙盒落 64343），且
    **环境变量不穿透 UAC 提权**（Start-Process -Verb RunAs 起的录制脚本拿不到
    IDEA_MCP_BASE）——所以先 TCP 探活 64340~64459，再对活口逐一握手，以
    「get_all_open_file_paths 能认出 demo 工程」为命中判据。env 只作覆盖。
    """
    env = os.environ.get("IDEA_MCP_BASE")
    if env:
        _set_base(env)
        _cache["s"] = _Session().start()
        return _cache["s"]
    import socket
    live = []
    for port in range(64340, 64460):
        sock = socket.socket()
        sock.settimeout(0.08)
        try:
            sock.connect(("127.0.0.1", port))
            live.append(port)
        except OSError:
            pass
        finally:
            sock.close()
    errs = []
    for port in live:
        base = f"http://127.0.0.1:{port}"
        try:
            _set_base(base)
            s = _Session().start()
            s.call("get_all_open_file_paths", {"projectPath": PROJECT})
            print(f"[mcpnav] 沙盒 MCP 端口探测命中: {base}")
            _cache["s"] = s
            return s
        except Exception as e:
            errs.append(f"{port}: {str(e)[:80]}")
    raise RuntimeError("MCP 端口探测失败：64340~64459 无一能认出 demo 工程（沙盒起了吗？）" +
                       "; ".join(errs))


def session():
    if "s" not in _cache:
        _detect_base()
    return _cache["s"]


def open_file(rel_path, project=None, settle=1.2):
    """按项目内相对路径打开文件（自动激活对应页签）。返回工具返回文本。"""
    text = session().call("open_file_in_editor", {
        "filePath": rel_path,
        "projectPath": project or PROJECT,
    })
    time.sleep(settle)
    return text


def open_paths(project=None):
    """列出当前打开的全部文件绝对路径（用于开录前断言没开错）。"""
    return session().call("get_all_open_file_paths", {"projectPath": project or PROJECT})


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    cmd = sys.argv[1]
    if cmd == "open":
        print(open_file(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None))
    elif cmd == "paths":
        print(open_paths())
    else:
        print(__doc__)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
