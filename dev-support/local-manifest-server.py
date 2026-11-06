#!/usr/bin/env python3
"""
本地清单测试服务器（Gitee 推送前验证用）— 读取 update/update.json 真实内容

用法：
  python dev-support/local-manifest-server.py [端口]

示例：
  python dev-support/local-manifest-server.py
  python dev-support/local-manifest-server.py 8919

然后在 IDE 运行配置中选 「Run IDE (Local Manifest)」 启动沙盒。
本地清单 → 不影响 Gitee 用户。
"""
import json
import os
import sys
from http.server import HTTPServer, SimpleHTTPRequestHandler

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 8919

# 定位 update/update.json（相对于本脚本的上两级目录）
SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_DIR = os.path.dirname(SCRIPT_DIR)
MANIFEST_FILE = os.path.join(PROJECT_DIR, "update", "update.json")


class Handler(SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/update.json":
            try:
                with open(MANIFEST_FILE, "rb") as f:
                    body = f.read()
                self.send_response(200)
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except FileNotFoundError:
                self.send_response(500)
                self.end_headers()
                msg = f"清单文件不存在: {MANIFEST_FILE}".encode()
                self.wfile.write(msg)
        elif self.path == "/plugin.zip":
            # 测试用假 zip（不会真的下载安装，仅校验触达）
            self.send_response(200)
            self.end_headers()
            # 最小合法 zip（1 字节也行，校验只检查非空且可打开）
            import zipfile, io
            buf = io.BytesIO()
            with zipfile.ZipFile(buf, "w") as zf:
                zf.writestr("lib/plugin.jar", b"test")
            self.wfile.write(buf.getvalue())
        else:
            self.send_response(404)
            self.end_headers()

    def log_message(self, fmt, *args):
        pass  # 静默输出


if __name__ == "__main__":
    if not os.path.isfile(MANIFEST_FILE):
        print(f"[local-错误] 清单文件不存在: {MANIFEST_FILE}")
        print("[local-提示] 请先编辑 update/update.json 填写测试内容")
        sys.exit(1)
    # 读取并显示清单内容摘要（必须显式 UTF-8：Windows 默认 GBK，清单里有中文会 UnicodeDecodeError）
    with open(MANIFEST_FILE, encoding="utf-8") as f:
        manifest = json.load(f)
    print(f"[local-清单] 版本 {manifest.get('version', '?')} → http://localhost:{PORT}/update.json")
    print(f"[local-清单] 来源: {MANIFEST_FILE}")
    print("[local-manifest] Ctrl+C 停掉服务器")
    HTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
